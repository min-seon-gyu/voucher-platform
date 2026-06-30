package com.commerce.integration

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.promotion.application.RedemptionOrchestrator
import com.commerce.promotion.domain.DiscountType
import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 동일 회원이 한 프로모션의 서로 다른 쿠폰 2장을 (다른 바우처로) 동시에 상환해도 perMemberLimit이 지켜져야 한다.
 * 쿠폰별 락만으로는 상호배제가 안 되므로 (promotion, member) 최외측 락으로 직렬화한다.
 * (서로 다른 바우처를 써서 voucher 락에 의한 직렬화 효과를 배제 → promotion·member 락만이 보장한다.)
 */
class CouponPerMemberLimitConcurrencyTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var orchestrator: RedemptionOrchestrator
    @Autowired lateinit var couponRedemptionRepository: CouponRedemptionJpaRepository
    @Autowired lateinit var verificationService: LedgerVerificationService

    private var regionId: Long = 0
    private var merchantId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion()
        val merchant = fixtures.createMerchant(region, fixtures.createMember())
        regionId = region.id
        merchantId = merchant.id
    }

    @Test
    fun `same member two coupons concurrently must respect perMemberLimit of 1`() {
        val member = fixtures.createMember()
        val v1 = fixtures.issueVoucher(member.id, regionId, BigDecimal("50000"))
        val v2 = fixtures.issueVoucher(member.id, regionId, BigDecimal("50000"))
        val promotion = fixtures.createPromotion(
            discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
            perMemberLimit = 1, budgetLimit = BigDecimal("1000000"),
        )
        val c1 = fixtures.issueCoupon(promotion.id, member.id)
        val c2 = fixtures.issueCoupon(promotion.id, member.id)

        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val success = AtomicInteger(0)
        val limitExceeded = AtomicInteger(0)

        val tasks = listOf(v1.id to c1.id, v2.id to c2.id)
        val futures = tasks.map { (voucherId, couponId) ->
            executor.submit {
                latch.await()
                try {
                    orchestrator.redeem(voucherId, merchantId, BigDecimal("10000"), couponId)
                    success.incrementAndGet()
                } catch (e: BusinessException) {
                    if (e.errorCode == ErrorCode.COUPON_USAGE_LIMIT_EXCEEDED) limitExceeded.incrementAndGet()
                }
            }
        }
        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        // perMemberLimit=1 → 정확히 1건만 성공, 1건은 한도 초과로 거절
        success.get() shouldBe 1
        limitExceeded.get() shouldBe 1
        couponRedemptionRepository.countByMemberIdAndPromotionIdAndCancelledFalse(member.id, promotion.id) shouldBe 1L
        verificationService.verify().isBalanced shouldBe true
    }
}
