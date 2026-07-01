package com.commerce.integration

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.inventory.application.StockService
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntrySide
import com.commerce.order.infrastructure.OrderJpaRepository
import com.commerce.point.infrastructure.PointAccountJpaRepository
import com.commerce.promotion.domain.CouponStatus
import com.commerce.promotion.domain.DiscountType
import com.commerce.promotion.infrastructure.CouponJpaRepository
import com.commerce.promotion.infrastructure.PromotionBudgetManager
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

/**
 * 주문 결제 쿠폰 할인 적용 검증.
 * 원장: 고객 현금(T−D) + 플랫폼 출연(D) = 판매자 gross(T). 적립은 실결제액(T−D) 기준. 쿠폰 REDEEMED, 예산 원자 예약.
 */
class OrderCouponDiscountTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var orderService: com.commerce.order.application.OrderService
    @Autowired lateinit var cartService: com.commerce.cart.application.CartService
    @Autowired lateinit var orderRepository: OrderJpaRepository
    @Autowired lateinit var couponRepository: CouponJpaRepository
    @Autowired lateinit var pointAccountRepository: PointAccountJpaRepository
    @Autowired lateinit var stockService: StockService
    @Autowired lateinit var budgetManager: PromotionBudgetManager
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var verificationService: LedgerVerificationService

    private fun buyerWithCart(price: BigDecimal): Pair<Long, Long> {
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val buyer = fixtures.createMember()
        val skuId = fixtures.createOnSaleSku(seller.id, price, 100)
        cartService.addItem(buyer.id, skuId, 1)
        return buyer.id to skuId
    }

    private fun net(txId: Long, account: AccountCode, side: LedgerEntrySide): BigDecimal =
        ledgerService.getEntriesByTransactionId(txId)
            .filter { it.account == account && it.side == side }
            .fold(BigDecimal.ZERO) { acc, e -> acc + e.amount }

    @Test
    fun `FIXED coupon applies discount, splits ledger, earns on net, redeems coupon`() {
        val (buyerId, _) = buyerWithCart(BigDecimal("20000")) // T = 20,000
        val promo = fixtures.createPromotion(
            discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
            minSpend = BigDecimal.ZERO, perMemberLimit = 1, budgetLimit = BigDecimal("1000000"),
        )
        val coupon = fixtures.issueCoupon(promo.id, buyerId)

        val order = orderService.placeOrder(buyerId, coupon.id)

        order.totalAmount.compareTo(BigDecimal("20000")) shouldBe 0
        order.discountAmount.compareTo(BigDecimal("3000")) shouldBe 0
        order.paidAmount.compareTo(BigDecimal("17000")) shouldBe 0
        order.couponId shouldBe coupon.id
        couponRepository.findById(coupon.id).get().status shouldBe CouponStatus.REDEEMED

        // 적립은 실결제액 17,000의 1% = 170
        pointAccountRepository.findByMemberId(buyerId)!!.balance.compareTo(BigDecimal("170")) shouldBe 0

        // 원장: 판매자 gross 20,000 / 고객 현금 17,000 / 플랫폼 출연 3,000
        val txId = order.paymentTransactionId!!
        net(txId, AccountCode.SELLER_PAYABLE, LedgerEntrySide.CREDIT).compareTo(BigDecimal("20000")) shouldBe 0
        net(txId, AccountCode.CUSTOMER_CASH, LedgerEntrySide.DEBIT).compareTo(BigDecimal("17000")) shouldBe 0
        net(txId, AccountCode.PROMOTION_FUNDING, LedgerEntrySide.DEBIT).compareTo(BigDecimal("3000")) shouldBe 0

        verificationService.verify().isBalanced.shouldBeTrue()
    }

    @Test
    fun `PERCENTAGE coupon discounts by rate`() {
        val (buyerId, _) = buyerWithCart(BigDecimal("20000"))
        val promo = fixtures.createPromotion(
            discountType = DiscountType.PERCENTAGE, discountValue = BigDecimal("10"), // 10%
            minSpend = BigDecimal.ZERO, perMemberLimit = 1, budgetLimit = BigDecimal("1000000"),
        )
        val coupon = fixtures.issueCoupon(promo.id, buyerId)

        val order = orderService.placeOrder(buyerId, coupon.id)

        order.discountAmount.compareTo(BigDecimal("2000")) shouldBe 0 // 20000 * 10 / 100
        order.paidAmount.compareTo(BigDecimal("18000")) shouldBe 0
        verificationService.verify().isBalanced.shouldBeTrue()
    }

    @Test
    fun `budget exceeded rejects order and restores stock and budget`() {
        val (buyerId, skuId) = buyerWithCart(BigDecimal("20000"))
        val promo = fixtures.createPromotion(
            discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
            minSpend = BigDecimal.ZERO, perMemberLimit = 1, budgetLimit = BigDecimal("2000"), // 예산 < 할인
        )
        val coupon = fixtures.issueCoupon(promo.id, buyerId)

        val ex = io.kotest.assertions.throwables.shouldThrow<BusinessException> {
            orderService.placeOrder(buyerId, coupon.id)
        }
        ex.errorCode shouldBe ErrorCode.PROMOTION_BUDGET_EXCEEDED

        orderRepository.findByMemberId(buyerId).isEmpty().shouldBeTrue() // 주문 미생성
        stockService.getBySkuId(skuId).quantity shouldBe 100             // 재고 그대로
        budgetManager.consumed(promo.id) shouldBe 0L                     // 예산 반환
        couponRepository.findById(coupon.id).get().status shouldBe CouponStatus.ISSUED // 쿠폰 미사용
    }

    @Test
    fun `min spend not met rejects coupon`() {
        val (buyerId, _) = buyerWithCart(BigDecimal("5000")) // T = 5,000
        val promo = fixtures.createPromotion(
            discountType = DiscountType.FIXED, discountValue = BigDecimal("3000"),
            minSpend = BigDecimal("10000"), perMemberLimit = 1, budgetLimit = BigDecimal("1000000"),
        )
        val coupon = fixtures.issueCoupon(promo.id, buyerId)

        val ex = io.kotest.assertions.throwables.shouldThrow<BusinessException> {
            orderService.placeOrder(buyerId, coupon.id)
        }
        ex.errorCode shouldBe ErrorCode.MIN_SPEND_NOT_MET
    }
}
