package com.commerce.integration

import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.promotion.application.RedemptionOrchestrator
import com.commerce.promotion.domain.CouponStatus
import com.commerce.promotion.domain.DiscountType
import com.commerce.promotion.infrastructure.CouponJpaRepository
import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
import com.commerce.promotion.infrastructure.PromotionBudgetManager
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.transaction.application.TransactionCancelService
import com.commerce.transaction.domain.TransactionStatus
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.UUID

class CouponCancelIntegrationTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var orchestrator: RedemptionOrchestrator
    @Autowired lateinit var cancelService: TransactionCancelService
    @Autowired lateinit var voucherRepository: VoucherJpaRepository
    @Autowired lateinit var couponRepository: CouponJpaRepository
    @Autowired lateinit var couponRedemptionRepository: CouponRedemptionJpaRepository
    @Autowired lateinit var transactionRepository: TransactionJpaRepository
    @Autowired lateinit var budgetManager: PromotionBudgetManager
    @Autowired lateinit var verificationService: LedgerVerificationService

    private var regionId: Long = 0
    private var memberId: Long = 0
    private var merchantId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
        val member = fixtures.createMember()
        val merchant = fixtures.createMerchant(region, fixtures.createMember())
        regionId = region.id
        memberId = member.id
        merchantId = merchant.id
    }

    @Test
    fun `cancelling a coupon-applied redeem reverses both ledger pairs and restores everything`() {
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val promotion = fixtures.createPromotion(
            discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
            budgetLimit = BigDecimal("1000000"),
        )
        val coupon = fixtures.issueCoupon(promotion.id, memberId)

        val result = orchestrator.redeem(voucher.id, merchantId, BigDecimal("10000"), coupon.id)
        budgetManager.consumed(promotion.id) shouldBe 3000L
        voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("43000")) shouldBe 0

        val compensatingTxId = cancelService.cancel(result.transactionId)

        // 바우처 잔액 T-D(7,000) 복원 → 50,000
        voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("50000")) shouldBe 0
        // 쿠폰 CANCELLED + CouponRedemption.cancelled
        couponRepository.findById(coupon.id).get().status shouldBe CouponStatus.CANCELLED
        couponRedemptionRepository.findByTransactionId(result.transactionId)!!.cancelled shouldBe true
        // 예산 반환 → 0
        budgetManager.consumed(promotion.id) shouldBe 0L
        couponRedemptionRepository.sumActiveDiscountByPromotion(promotion.id)
            .compareTo(BigDecimal.ZERO) shouldBe 0
        // 원 거래 CANCELLED, 보상 거래 COMPLETED
        transactionRepository.findById(result.transactionId).get().status shouldBe TransactionStatus.CANCELLED
        transactionRepository.findById(compensatingTxId).get().status shouldBe TransactionStatus.COMPLETED
        // 글로벌 정합성 유지
        verificationService.verify().isBalanced shouldBe true
    }
}
