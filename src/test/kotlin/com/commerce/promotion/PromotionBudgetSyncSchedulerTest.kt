package com.commerce.promotion

import com.commerce.promotion.application.PromotionBudgetSyncScheduler
import com.commerce.promotion.domain.CouponRedemption
import com.commerce.promotion.domain.DiscountType
import com.commerce.promotion.domain.Promotion
import com.commerce.promotion.domain.PromotionStatus
import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
import com.commerce.promotion.infrastructure.PromotionBudgetManager
import com.commerce.promotion.infrastructure.PromotionJpaRepository
import com.commerce.support.IntegrationTestSupport
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.random.Random

class PromotionBudgetSyncSchedulerTest : IntegrationTestSupport() {

    @Autowired lateinit var scheduler: PromotionBudgetSyncScheduler
    @Autowired lateinit var promotionRepository: PromotionJpaRepository
    @Autowired lateinit var couponRedemptionRepository: CouponRedemptionJpaRepository
    @Autowired lateinit var promotionBudgetManager: PromotionBudgetManager

    @Test
    fun `syncPromotionBudgets reconciles drifted Redis counter to DB active sum`() {
        // Arrange: persist a promotion and two active redemptions (total 5000)
        val promotion = promotionRepository.save(
            Promotion(
                name = "동기화 테스트 프로모션 ${Random.nextInt()}",
                discountType = DiscountType.FIXED,
                discountValue = BigDecimal("3000"),
                minSpend = BigDecimal.ZERO,
                perMemberLimit = 5,
                budgetLimit = BigDecimal("1000000"),
                startsAt = LocalDateTime.now().minusDays(1),
                endsAt = LocalDateTime.now().plusDays(1),
                status = PromotionStatus.ACTIVE,
            )
        )
        val promotionId = promotion.id

        couponRedemptionRepository.save(
            CouponRedemption(
                couponId = 1L, promotionId = promotionId, memberId = 1L,
                voucherId = 1L, transactionId = Random.nextLong(1, Long.MAX_VALUE),
                orderTotal = BigDecimal("10000"), discountAmount = BigDecimal("2000"),
                voucherCharged = BigDecimal("8000"),
            )
        )
        couponRedemptionRepository.save(
            CouponRedemption(
                couponId = 2L, promotionId = promotionId, memberId = 2L,
                voucherId = 2L, transactionId = Random.nextLong(1, Long.MAX_VALUE),
                orderTotal = BigDecimal("15000"), discountAmount = BigDecimal("3000"),
                voucherCharged = BigDecimal("12000"),
            )
        )
        // cancelled redemption — must NOT be counted
        val cancelledRedemption = couponRedemptionRepository.save(
            CouponRedemption(
                couponId = 3L, promotionId = promotionId, memberId = 3L,
                voucherId = 3L, transactionId = Random.nextLong(1, Long.MAX_VALUE),
                orderTotal = BigDecimal("20000"), discountAmount = BigDecimal("9999"),
                voucherCharged = BigDecimal("10001"),
            )
        )
        cancelledRedemption.markCancelled()
        couponRedemptionRepository.save(cancelledRedemption)

        // Pre-set Redis to a WRONG value to prove reconciliation corrects drift
        val wrongValue = 99_999L
        promotionBudgetManager.release(promotionId, BigDecimal.ZERO) // ensure key exists
        // Force the counter to the wrong value via reserve/release trick
        val currentConsumed = promotionBudgetManager.consumed(promotionId)
        if (currentConsumed < wrongValue) {
            promotionBudgetManager.reserve(
                promotionId,
                BigDecimal(wrongValue - currentConsumed),
                BigDecimal(Long.MAX_VALUE),
            )
        }
        promotionBudgetManager.consumed(promotionId) // confirm it is wrong

        // Act
        scheduler.syncPromotionBudgets()

        // Assert: Redis should now reflect only the active DB sum = 2000 + 3000 = 5000
        promotionBudgetManager.consumed(promotionId) shouldBe 5000L
    }
}
