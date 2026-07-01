package com.commerce.support

import com.commerce.member.application.MemberService
import com.commerce.member.domain.Member
import com.commerce.member.interfaces.dto.RegisterMemberRequest
import com.commerce.cart.application.CartService
import com.commerce.order.application.OrderService
import com.commerce.order.domain.Order
import com.commerce.product.application.ProductService
import com.commerce.product.application.SkuSpec
import com.commerce.product.domain.ProductCategory
import com.commerce.seller.application.SellerService
import com.commerce.seller.application.RegisterSellerRequest
import com.commerce.seller.domain.Seller
import com.commerce.seller.infrastructure.SellerJpaRepository
import com.commerce.promotion.application.CouponIssueService
import com.commerce.promotion.application.PromotionService
import com.commerce.promotion.domain.Coupon
import com.commerce.promotion.domain.DiscountType
import com.commerce.promotion.domain.Promotion
import com.commerce.promotion.interfaces.dto.CreatePromotionRequest
import com.commerce.region.application.RegionService
import com.commerce.region.domain.Region
import com.commerce.region.interfaces.dto.CreateRegionRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime

@Component
class TestFixtures(
    private val regionService: RegionService,
    private val memberService: MemberService,
    private val sellerService: SellerService,
    private val sellerRepository: SellerJpaRepository,
    private val promotionService: PromotionService,
    private val couponIssueService: CouponIssueService,
    private val productService: ProductService,
    private val cartService: CartService,
    private val orderService: OrderService,
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

    fun createSeller(region: Region, owner: Member): Seller {
        val id = nextId()
        val seller = sellerService.register(
            RegisterSellerRequest(
                name = "테스트가게$id",
                businessNumber = "123-45-${String.format("%05d", id)}",
                category = "RESTAURANT",
                regionId = region.id,
                ownerId = owner.id,
            )
        )
        return sellerService.approve(seller.id)
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

    /** 판매 중인 SKU(가격=price, 재고=stock)를 만들고 SKU id 반환. */
    @Transactional
    fun createOnSaleSku(sellerId: Long, price: BigDecimal, stock: Int = 1000): Long {
        val ownerId = sellerRepository.findById(sellerId).orElseThrow().owner.id
        val product = productService.createProduct(
            requesterMemberId = ownerId,
            sellerId = sellerId,
            name = "상품${nextId()}",
            description = null,
            category = ProductCategory.OTHER,
            skus = listOf(SkuSpec("SKU-${nextId()}", "기본", emptyMap(), price, stock)),
        )
        productService.onSale(ownerId, product.id)
        return productService.getDetail(product.id).skus.first().sku.id
    }

    /** 단일 SKU 주문 1건(결제 완료). placeOrder는 자체 tx/락을 관리하므로 감싸지 않는다. */
    fun placeSingleOrder(buyerId: Long, skuId: Long, quantity: Int = 1): Order {
        cartService.addItem(buyerId, skuId, quantity)
        return orderService.placeOrder(buyerId)
    }

    /** 판매자에게 amount 매출 1건(주문 1건, price=amount×qty1)을 발생시키고 주문을 반환. */
    fun sellerSale(buyerId: Long, sellerId: Long, amount: BigDecimal): Order {
        val skuId = createOnSaleSku(sellerId, amount, stock = 1)
        return placeSingleOrder(buyerId, skuId, 1)
    }
}
