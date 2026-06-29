package com.commerce.integration

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.promotion.application.RedemptionOrchestrator
import com.commerce.promotion.domain.CouponStatus
import com.commerce.promotion.domain.DiscountType
import com.commerce.promotion.infrastructure.CouponJpaRepository
import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
import com.commerce.promotion.infrastructure.PromotionBudgetManager
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CouponConcurrencyTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var orchestrator: RedemptionOrchestrator
    @Autowired lateinit var voucherRepository: VoucherJpaRepository
    @Autowired lateinit var couponRepository: CouponJpaRepository
    @Autowired lateinit var couponRedemptionRepository: CouponRedemptionJpaRepository
    @Autowired lateinit var budgetManager: PromotionBudgetManager
    @Autowired lateinit var verificationService: LedgerVerificationService

    private var regionId: Long = 0
    private var merchantId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
        val merchant = fixtures.createMerchant(region, fixtures.createMember())
        regionId = region.id
        merchantId = merchant.id
    }

    @Test
    fun `N threads redeeming the same coupon must use it exactly once (no double-use)`() {
        val member = fixtures.createMember()
        val voucher = fixtures.issueVoucher(member.id, regionId, BigDecimal("50000"))
        val promotion = fixtures.createPromotion(
            discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
            budgetLimit = BigDecimal("1000000"),
        )
        val coupon = fixtures.issueCoupon(promotion.id, member.id)

        val threadCount = 10
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        val success = AtomicInteger(0)
        val alreadyUsed = AtomicInteger(0)

        val futures = (1..threadCount).map {
            executor.submit {
                latch.await()
                try {
                    orchestrator.redeem(voucher.id, merchantId, BigDecimal("10000"), coupon.id)
                    success.incrementAndGet()
                } catch (e: BusinessException) {
                    if (e.errorCode == ErrorCode.COUPON_ALREADY_USED)
                        alreadyUsed.incrementAndGet()
                }
            }
        }
        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        // 정확히 1회 사용
        success.get() shouldBe 1
        alreadyUsed.get() shouldBe 9
        couponRepository.findById(coupon.id).get().status shouldBe CouponStatus.REDEEMED
        couponRedemptionRepository.countByMemberIdAndPromotionIdAndCancelledFalse(member.id, promotion.id) shouldBe 1L
        // 바우처는 단 1회(7,000)만 차감
        voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("43000")) shouldBe 0
        verificationService.verify().isBalanced shouldBe true
    }

    @Test
    fun `N threads on a shared budget must never over-spend the promotion budget`() {
        // 예산 9,000 / 할인 3,000 → 정확히 3건만 성공
        val promotion = fixtures.createPromotion(
            discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
            perMemberLimit = 1, budgetLimit = BigDecimal("9000"),
        )
        val threadCount = 10
        // 회원/바우처/쿠폰을 1:1:1로 준비(회원당 한도 1과 무관하게 예산만 핫스팟)
        data class Ctx(val voucherId: Long, val couponId: Long)
        val ctxs = (1..threadCount).map {
            val m = fixtures.createMember()
            val v = fixtures.issueVoucher(m.id, regionId, BigDecimal("50000"))
            val c = fixtures.issueCoupon(promotion.id, m.id)
            Ctx(v.id, c.id)
        }

        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        val success = AtomicInteger(0)
        val budgetExceeded = AtomicInteger(0)

        val futures = ctxs.map { ctx ->
            executor.submit {
                latch.await()
                try {
                    orchestrator.redeem(ctx.voucherId, merchantId, BigDecimal("10000"), ctx.couponId)
                    success.incrementAndGet()
                } catch (e: BusinessException) {
                    if (e.errorCode == ErrorCode.PROMOTION_BUDGET_EXCEEDED)
                        budgetExceeded.incrementAndGet()
                }
            }
        }
        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        // 예산 9,000 / 3,000 = 정확히 3건 성공, 나머지 예산 초과
        success.get() shouldBe 3
        budgetExceeded.get() shouldBe 7
        // 소비 예산이 한도를 넘지 않음(보상으로 누수도 없음)
        budgetManager.consumed(promotion.id) shouldBe 9000L
        couponRedemptionRepository.sumActiveDiscountByPromotion(promotion.id)
            .compareTo(BigDecimal("9000")) shouldBe 0
        verificationService.verify().isBalanced shouldBe true
    }
}
