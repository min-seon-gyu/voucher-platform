package com.commerce.support

import com.commerce.member.application.MemberService
import com.commerce.member.domain.Member
import com.commerce.member.interfaces.dto.RegisterMemberRequest
import com.commerce.merchant.application.MerchantService
import com.commerce.merchant.application.RegisterMerchantRequest
import com.commerce.merchant.domain.Merchant
import com.commerce.promotion.application.CouponIssueService
import com.commerce.promotion.application.PromotionService
import com.commerce.promotion.domain.Coupon
import com.commerce.promotion.domain.DiscountType
import com.commerce.promotion.domain.Promotion
import com.commerce.promotion.interfaces.dto.CreatePromotionRequest
import com.commerce.region.application.RegionService
import com.commerce.region.domain.Region
import com.commerce.region.interfaces.dto.CreateRegionRequest
import com.commerce.voucher.application.VoucherIssueService
import com.commerce.voucher.domain.Voucher
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime

@Component
class TestFixtures(
    private val regionService: RegionService,
    private val memberService: MemberService,
    private val merchantService: MerchantService,
    private val voucherIssueService: VoucherIssueService,
    private val voucherJpaRepository: VoucherJpaRepository,
    private val promotionService: PromotionService,
    private val couponIssueService: CouponIssueService,
) {
    private val base36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    companion object {
        /** JVM-wide counter — shared across all Spring contexts so separate ApplicationContexts
         *  (e.g. those created by @MockBean) do not regenerate the same region codes. */
        private val globalCounter = java.util.concurrent.atomic.AtomicInteger(0)
    }

    /** 충돌 없는 유니크 2글자 region 코드 생성 (region_code 컬럼이 length=2, unique 제약) */
    private fun nextRegionCode(): String {
        val n = globalCounter.getAndIncrement() % (36 * 36)
        return "${base36[n / 36]}${base36[n % 36]}"
    }

    /** JVM-wide unique int used for emails, business numbers, and other unique fields.
     *  Using the same globalCounter avoids collisions across separate Spring contexts
     *  (e.g. contexts created by @MockBean). */
    private fun nextId(): Int = globalCounter.getAndIncrement()

    fun createRegion(
        name: String = "성남시",
        code: String = "SN", // (미사용) 코드는 nextRegionCode()로 유니크 생성해 충돌 방지
        monthlyLimit: BigDecimal = BigDecimal("10000000000"),
        purchaseLimit: BigDecimal = BigDecimal("5000000"),
        settlementPeriod: String = "MONTHLY",
    ): Region {
        return regionService.create(
            CreateRegionRequest(
                name = name,
                regionCode = nextRegionCode(),
                discountRate = BigDecimal("0.10"),
                purchaseLimitPerPerson = purchaseLimit,
                monthlyIssuanceLimit = monthlyLimit,
                settlementPeriod = settlementPeriod,
            )
        )
    }

    fun createMember(email: String? = null): Member {
        val id = nextId()
        return memberService.register(
            RegisterMemberRequest(
                email = email ?: "user$id@test.com",
                name = "테스트유저$id",
                password = "password123",
            )
        )
    }

    fun createMerchant(region: Region, owner: Member): Merchant {
        val id = nextId()
        val merchant = merchantService.register(
            RegisterMerchantRequest(
                name = "테스트가게$id",
                businessNumber = "123-45-${String.format("%05d", id)}",
                category = "RESTAURANT",
                regionId = region.id,
                ownerId = owner.id,
            )
        )
        return merchantService.approve(merchant.id)
    }

    fun issueVoucher(
        memberId: Long,
        regionId: Long,
        faceValue: BigDecimal = BigDecimal("50000"),
    ): Voucher {
        return voucherIssueService.issue(memberId, regionId, faceValue)
    }

    fun createPromotion(
        discountType: DiscountType = DiscountType.FIXED,
        discountValue: BigDecimal = BigDecimal("3000"),
        minSpend: BigDecimal = BigDecimal.ZERO,
        perMemberLimit: Int = 1,
        budgetLimit: BigDecimal = BigDecimal("1000000"),
    ): Promotion = promotionService.create(
        CreatePromotionRequest(
            name = "프로모션${nextId()}",
            discountType = discountType,
            discountValue = discountValue,
            minSpend = minSpend,
            perMemberLimit = perMemberLimit,
            budgetLimit = budgetLimit,
            startsAt = LocalDateTime.now().minusDays(1),
            endsAt = LocalDateTime.now().plusDays(30),
        )
    )

    fun issueCoupon(promotionId: Long, memberId: Long): Coupon =
        couponIssueService.issue(promotionId, memberId)

    @Transactional
    fun forceExpireVoucher(voucherId: Long) {
        voucherJpaRepository.updateExpiresAt(voucherId, LocalDateTime.now().minusDays(1))
    }

    @Transactional
    fun forcePurchasedAt(voucherId: Long, purchasedAt: LocalDateTime) {
        voucherJpaRepository.updatePurchasedAt(voucherId, purchasedAt)
    }
}
