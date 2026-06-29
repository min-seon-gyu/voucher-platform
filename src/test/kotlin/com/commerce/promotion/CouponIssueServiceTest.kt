package com.commerce.promotion

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.promotion.application.CouponIssueService
import com.commerce.promotion.application.PromotionService
import com.commerce.promotion.domain.CouponStatus
import com.commerce.promotion.domain.DiscountType
import com.commerce.promotion.interfaces.dto.CreatePromotionRequest
import com.commerce.support.IntegrationTestSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random

class CouponIssueServiceTest : IntegrationTestSupport() {

    @Autowired lateinit var promotionService: PromotionService
    @Autowired lateinit var couponIssueService: CouponIssueService

    // Truncate to microseconds to match MySQL DATETIME(6) storage precision
    private fun now() = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)

    /** Returns a positive Long that is unique per test run to avoid cross-test collisions. */
    private fun uniqueMemberId(): Long = Random.nextLong(100_000L, Long.MAX_VALUE)

    private fun createPromotionRequest() = CreatePromotionRequest(
        name = "발급 테스트",
        discountType = DiscountType.FIXED,
        discountValue = BigDecimal("3000"),
        minSpend = BigDecimal.ZERO,
        perMemberLimit = 1,
        budgetLimit = BigDecimal("1000000"),
        startsAt = now().minusDays(1),
        endsAt = now().plusDays(7),
    )

    @Test
    fun `issue creates an ISSUED coupon owned by the member with promotion expiry`() {
        val memberId = uniqueMemberId()
        val promotion = promotionService.create(createPromotionRequest())
        val coupon = couponIssueService.issue(promotion.id, memberId = memberId)

        coupon.status shouldBe CouponStatus.ISSUED
        coupon.memberId shouldBe memberId
        coupon.promotionId shouldBe promotion.id
        coupon.expiresAt shouldBe promotion.endsAt

        val memberCoupons = couponIssueService.findByMember(memberId)
        memberCoupons.map { it.id } shouldContain coupon.id
        memberCoupons.all { it.memberId == memberId } shouldBe true
    }

    @Test
    fun `issue on an inactive promotion is rejected`() {
        val memberId = uniqueMemberId()
        val promotion = promotionService.create(
            createPromotionRequest().copy(
                startsAt = now().minusDays(10),
                endsAt = now().minusDays(1), // 이미 종료
            )
        )
        shouldThrow<BusinessException> { couponIssueService.issue(promotion.id, memberId) }
            .errorCode shouldBe ErrorCode.PROMOTION_NOT_ACTIVE
    }
}
