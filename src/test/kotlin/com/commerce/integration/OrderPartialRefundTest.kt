package com.commerce.integration

import com.commerce.cart.application.CartService
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.inventory.application.StockService
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntrySide
import com.commerce.order.application.OrderService
import com.commerce.order.domain.OrderStatus
import com.commerce.order.infrastructure.OrderLineJpaRepository
import com.commerce.point.infrastructure.PointAccountJpaRepository
import com.commerce.promotion.domain.DiscountType
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 부분 환불(라인 단위) + 할인 주문 전체취소의 원장 정합성 검증.
 * 핵심: 할인·포인트를 라인별 최대잔여 배분으로 정확히 나눠 환불하고, 반복/전액 환불 후 계정 net이 0으로 복귀한다.
 */
class OrderPartialRefundTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var cartService: CartService
    @Autowired lateinit var orderLineRepository: OrderLineJpaRepository
    @Autowired lateinit var pointAccountRepository: PointAccountJpaRepository
    @Autowired lateinit var stockService: StockService
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var transactionRepository: TransactionJpaRepository
    @Autowired lateinit var verificationService: LedgerVerificationService

    /** 한 판매자·두 SKU(가격 p1,p2) 카트를 만든 buyer와 seller를 돌려준다. */
    private fun twoLineCart(p1: BigDecimal, p2: BigDecimal): Triple<Long, Long, Pair<Long, Long>> {
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val buyer = fixtures.createMember()
        val sku1 = fixtures.createOnSaleSku(seller.id, p1, 100)
        val sku2 = fixtures.createOnSaleSku(seller.id, p2, 100)
        cartService.addItem(buyer.id, sku1, 1)
        cartService.addItem(buyer.id, sku2, 1)
        return Triple(buyer.id, seller.id, sku1 to sku2)
    }

    /** 결제 tx + 그 보상(ORDER_CANCEL) tx 전체 id. */
    private fun orderTxIds(paymentTxId: Long): List<Long> =
        listOf(paymentTxId) + transactionRepository.findAll().filter { it.originalTransactionId == paymentTxId }.map { it.id }

    private fun compTxIds(paymentTxId: Long): List<Long> =
        transactionRepository.findAll().filter { it.originalTransactionId == paymentTxId }.map { it.id }

    private fun sumSide(txIds: List<Long>, account: AccountCode, side: LedgerEntrySide): BigDecimal =
        txIds.flatMap { ledgerService.getEntriesByTransactionId(it) }
            .filter { it.account == account && it.side == side }
            .fold(BigDecimal.ZERO) { a, e -> a + e.amount }

    /** 계정 net = Σdebit − Σcredit (환불 완료 후 0이면 정확 복귀). */
    private fun accountNet(txIds: List<Long>, account: AccountCode): BigDecimal =
        sumSide(txIds, account, LedgerEntrySide.DEBIT) - sumSide(txIds, account, LedgerEntrySide.CREDIT)

    private fun pointBalance(memberId: Long): BigDecimal =
        pointAccountRepository.findByMemberId(memberId)!!.balance

    @Test
    fun `partial refund splits discount and points per line, then full refund zeroes accounts`() {
        val (buyerId, _, skus) = twoLineCart(BigDecimal("30000"), BigDecimal("20000")) // T = 50,000
        val (sku1, sku2) = skus
        val promo = fixtures.createPromotion(DiscountType.FIXED, BigDecimal("5000")) // D = 5,000, paid = 45,000
        val coupon = fixtures.issueCoupon(promo.id, buyerId)

        val order = orderService.placeOrder(buyerId, coupon.id)
        order.paidAmount.compareTo(BigDecimal("45000")) shouldBe 0
        pointBalance(buyerId).compareTo(BigDecimal("450")) shouldBe 0 // 45,000 * 1%
        val paymentTxId = order.paymentTransactionId!!

        val lines = orderLineRepository.findByOrderId(order.id)
        val line1 = lines.first { it.skuId == sku1 } // 30,000
        val line2 = lines.first { it.skuId == sku2 } // 20,000

        // ── 부분환불: line1(30,000) ── 할인 배분 3,000 → net 27,000, 포인트 270
        orderService.refundLines(buyerId, order.id, listOf(line1.id))

        orderService.getDetail(order.id).order.status shouldBe OrderStatus.PARTIALLY_REFUNDED
        orderLineRepository.findByOrderId(order.id).first { it.id == line1.id }.refunded.shouldBeTrue()
        orderLineRepository.findByOrderId(order.id).first { it.id == line2.id }.refunded.shouldBeFalse()
        stockService.getBySkuId(sku1).quantity shouldBe 100 // 복원
        stockService.getBySkuId(sku2).quantity shouldBe 99  // 유지
        pointBalance(buyerId).compareTo(BigDecimal("180")) shouldBe 0 // 450 − 270

        val comp1 = compTxIds(paymentTxId)
        sumSide(comp1, AccountCode.CUSTOMER_CASH, LedgerEntrySide.CREDIT).compareTo(BigDecimal("27000")) shouldBe 0
        sumSide(comp1, AccountCode.PROMOTION_FUNDING, LedgerEntrySide.CREDIT).compareTo(BigDecimal("3000")) shouldBe 0
        sumSide(comp1, AccountCode.SELLER_PAYABLE, LedgerEntrySide.DEBIT).compareTo(BigDecimal("30000")) shouldBe 0
        verificationService.verify().isBalanced.shouldBeTrue()

        // ── 나머지 환불: line2(20,000) ── net 18,000, 포인트 180 → 전액 환불
        orderService.refundLines(buyerId, order.id, listOf(line2.id))

        orderService.getDetail(order.id).order.status shouldBe OrderStatus.REFUNDED
        stockService.getBySkuId(sku2).quantity shouldBe 100
        pointBalance(buyerId).compareTo(BigDecimal.ZERO) shouldBe 0

        // 전액 환불 후 결제/보상 tx 전체에서 각 계정 net이 0으로 복귀(과·과소 환불 없음)
        val all = orderTxIds(paymentTxId)
        accountNet(all, AccountCode.CUSTOMER_CASH).compareTo(BigDecimal.ZERO) shouldBe 0
        accountNet(all, AccountCode.PROMOTION_FUNDING).compareTo(BigDecimal.ZERO) shouldBe 0
        accountNet(all, AccountCode.SELLER_PAYABLE).compareTo(BigDecimal.ZERO) shouldBe 0
        accountNet(all, AccountCode.POINT_BALANCE).compareTo(BigDecimal.ZERO) shouldBe 0
        verificationService.verify().isBalanced.shouldBeTrue()
    }

    @Test
    fun `uneven discount and point split still zeroes accounts after refunding every line`() {
        // 3라인 동일가(1,000) + 할인 1,000 → 라인당 333.33...(딱 나눠지지 않음). 최대잔여 배분이 합계를 정확히 맞추는지.
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val buyerId = fixtures.createMember().id
        val skus = (1..3).map { fixtures.createOnSaleSku(seller.id, BigDecimal("1000"), 100) }
        skus.forEach { cartService.addItem(buyerId, it, 1) }
        val promo = fixtures.createPromotion(DiscountType.FIXED, BigDecimal("1000")) // T=3,000, paid=2,000
        val coupon = fixtures.issueCoupon(promo.id, buyerId)

        val order = orderService.placeOrder(buyerId, coupon.id)
        val paymentTxId = order.paymentTransactionId!!
        pointBalance(buyerId).compareTo(BigDecimal("20")) shouldBe 0 // 2,000 * 1%

        // 라인을 하나씩 환불 → 마지막에 전액 환불
        orderLineRepository.findByOrderId(order.id).forEach {
            orderService.refundLines(buyerId, order.id, listOf(it.id))
        }

        orderService.getDetail(order.id).order.status shouldBe OrderStatus.REFUNDED
        pointBalance(buyerId).compareTo(BigDecimal.ZERO) shouldBe 0 // 배분 합 = 원 적립 20, 정확 역적립

        // 반올림에도 각 계정 net이 0으로 정확히 복귀(할인 배분 합 = D, 포인트 배분 합 = P)
        val all = orderTxIds(paymentTxId)
        accountNet(all, AccountCode.CUSTOMER_CASH).compareTo(BigDecimal.ZERO) shouldBe 0
        accountNet(all, AccountCode.PROMOTION_FUNDING).compareTo(BigDecimal.ZERO) shouldBe 0
        accountNet(all, AccountCode.SELLER_PAYABLE).compareTo(BigDecimal.ZERO) shouldBe 0
        accountNet(all, AccountCode.POINT_BALANCE).compareTo(BigDecimal.ZERO) shouldBe 0
        verificationService.verify().isBalanced.shouldBeTrue()
    }

    @Test
    fun `refunds a line whose allocated net is zero without amount-must-be-positive crash`() {
        // sku1=1원 + sku2=9999원, FIXED 9999 쿠폰 → line1 배분 후 net=0. line1 단독 환불이 500 없이 처리돼야 한다.
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val buyerId = fixtures.createMember().id
        val sku1 = fixtures.createOnSaleSku(seller.id, BigDecimal("1"), 100)
        val sku2 = fixtures.createOnSaleSku(seller.id, BigDecimal("9999"), 100)
        cartService.addItem(buyerId, sku1, 1)
        cartService.addItem(buyerId, sku2, 1)
        val promo = fixtures.createPromotion(DiscountType.FIXED, BigDecimal("9999")) // T=10000, D=9999, paid=1
        val coupon = fixtures.issueCoupon(promo.id, buyerId)

        val order = orderService.placeOrder(buyerId, coupon.id)
        order.paidAmount.compareTo(BigDecimal("1")) shouldBe 0
        val paymentTxId = order.paymentTransactionId!!
        val line1 = orderLineRepository.findByOrderId(order.id).first { it.skuId == sku1 } // 배분: net 0, discount 1.00

        // net=0 라인 단독 환불 — 예외 없이 성공(현금 leg 생략, 출연 leg만)
        orderService.refundLines(buyerId, order.id, listOf(line1.id))

        orderService.getDetail(order.id).order.status shouldBe OrderStatus.PARTIALLY_REFUNDED
        stockService.getBySkuId(sku1).quantity shouldBe 100 // 복원됨
        val comp1 = compTxIds(paymentTxId)
        sumSide(comp1, AccountCode.CUSTOMER_CASH, LedgerEntrySide.CREDIT).compareTo(BigDecimal.ZERO) shouldBe 0 // 현금 leg 없음
        sumSide(comp1, AccountCode.PROMOTION_FUNDING, LedgerEntrySide.CREDIT).compareTo(BigDecimal("1")) shouldBe 0 // 출연 1.00 환입
        verificationService.verify().isBalanced.shouldBeTrue()

        // 나머지 라인까지 환불 → 전액 환불, 각 계정 net 0 복귀
        val line2 = orderLineRepository.findByOrderId(order.id).first { it.skuId == sku2 }
        orderService.refundLines(buyerId, order.id, listOf(line2.id))
        orderService.getDetail(order.id).order.status shouldBe OrderStatus.REFUNDED
        val all = orderTxIds(paymentTxId)
        accountNet(all, AccountCode.CUSTOMER_CASH).compareTo(BigDecimal.ZERO) shouldBe 0
        accountNet(all, AccountCode.PROMOTION_FUNDING).compareTo(BigDecimal.ZERO) shouldBe 0
        accountNet(all, AccountCode.SELLER_PAYABLE).compareTo(BigDecimal.ZERO) shouldBe 0
        verificationService.verify().isBalanced.shouldBeTrue()
    }

    @Test
    fun `hundred percent discount order places and cancels without amount-must-be-positive crash`() {
        // FIXED 할인 = 주문총액 → paid=0. 현금 leg가 0원이므로 생략되어야 하고 place/cancel 모두 500 없이 처리.
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val buyerId = fixtures.createMember().id
        val skuId = fixtures.createOnSaleSku(seller.id, BigDecimal("10000"), 100)
        cartService.addItem(buyerId, skuId, 1)
        val promo = fixtures.createPromotion(DiscountType.FIXED, BigDecimal("10000")) // D=T=10000, paid=0
        val coupon = fixtures.issueCoupon(promo.id, buyerId)

        val order = orderService.placeOrder(buyerId, coupon.id)
        order.paidAmount.compareTo(BigDecimal.ZERO) shouldBe 0
        order.discountAmount.compareTo(BigDecimal("10000")) shouldBe 0
        val paymentTxId = order.paymentTransactionId!!
        // 현금 leg 없음, 출연 leg만
        sumSide(listOf(paymentTxId), AccountCode.CUSTOMER_CASH, LedgerEntrySide.DEBIT).compareTo(BigDecimal.ZERO) shouldBe 0
        sumSide(listOf(paymentTxId), AccountCode.PROMOTION_FUNDING, LedgerEntrySide.DEBIT).compareTo(BigDecimal("10000")) shouldBe 0
        verificationService.verify().isBalanced.shouldBeTrue()

        orderService.cancelOrder(buyerId, order.id)

        orderService.getDetail(order.id).order.status shouldBe OrderStatus.CANCELLED
        val all = orderTxIds(paymentTxId)
        accountNet(all, AccountCode.PROMOTION_FUNDING).compareTo(BigDecimal.ZERO) shouldBe 0
        accountNet(all, AccountCode.SELLER_PAYABLE).compareTo(BigDecimal.ZERO) shouldBe 0
        accountNet(all, AccountCode.CUSTOMER_CASH).compareTo(BigDecimal.ZERO) shouldBe 0
        verificationService.verify().isBalanced.shouldBeTrue()
    }

    @Test
    fun `full cancel of discounted order refunds paid amount and reverses funding`() {
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(owner)
        val buyerId = fixtures.createMember().id
        val skuId = fixtures.createOnSaleSku(seller.id, BigDecimal("20000"), 100)
        cartService.addItem(buyerId, skuId, 1)
        val promo = fixtures.createPromotion(DiscountType.FIXED, BigDecimal("3000")) // paid 17,000
        val coupon = fixtures.issueCoupon(promo.id, buyerId)

        val order = orderService.placeOrder(buyerId, coupon.id)
        val paymentTxId = order.paymentTransactionId!!
        pointBalance(buyerId).compareTo(BigDecimal("170")) shouldBe 0

        orderService.cancelOrder(buyerId, order.id)

        orderService.getDetail(order.id).order.status shouldBe OrderStatus.CANCELLED
        stockService.getBySkuId(skuId).quantity shouldBe 100
        pointBalance(buyerId).compareTo(BigDecimal.ZERO) shouldBe 0

        // 고객에겐 실결제액 17,000만 환급(gross 20,000이 아님) + 출연 3,000 환입 → 두 계정 모두 net 0
        val all = orderTxIds(paymentTxId)
        accountNet(all, AccountCode.CUSTOMER_CASH).compareTo(BigDecimal.ZERO) shouldBe 0
        accountNet(all, AccountCode.PROMOTION_FUNDING).compareTo(BigDecimal.ZERO) shouldBe 0
        accountNet(all, AccountCode.SELLER_PAYABLE).compareTo(BigDecimal.ZERO) shouldBe 0
        verificationService.verify().isBalanced.shouldBeTrue()
    }

    @Test
    fun `refund rejects already-refunded line, foreign line, empty request and non-owner`() {
        val (buyerId, _, skus) = twoLineCart(BigDecimal("30000"), BigDecimal("20000"))
        val order = orderService.placeOrder(buyerId) // 무쿠폰
        val lines = orderLineRepository.findByOrderId(order.id)
        val line1 = lines.first { it.skuId == skus.first }

        orderService.refundLines(buyerId, order.id, listOf(line1.id))

        shouldThrow<BusinessException> { orderService.refundLines(buyerId, order.id, listOf(line1.id)) }
            .errorCode shouldBe ErrorCode.ORDER_LINE_ALREADY_REFUNDED
        shouldThrow<BusinessException> { orderService.refundLines(buyerId, order.id, listOf(999_999L)) }
            .errorCode shouldBe ErrorCode.INVALID_REFUND_LINES
        shouldThrow<BusinessException> { orderService.refundLines(buyerId, order.id, emptyList()) }
            .errorCode shouldBe ErrorCode.INVALID_REFUND_LINES
        shouldThrow<BusinessException> { orderService.refundLines(fixtures.createMember().id, order.id, listOf(lines[1].id)) }
            .errorCode shouldBe ErrorCode.ACCESS_DENIED
    }

    @Test
    fun `settlement sum excludes refunded lines but keeps remaining lines of partially refunded order`() {
        val (buyerId, sellerId, skus) = twoLineCart(BigDecimal("30000"), BigDecimal("20000"))
        val order = orderService.placeOrder(buyerId) // 무쿠폰, 판매자 매출 50,000
        val start = LocalDateTime.now().minusDays(1)
        val end = LocalDateTime.now().plusDays(1)
        orderLineRepository.sumSellerSalesInPeriod(sellerId, start, end).compareTo(BigDecimal("50000")) shouldBe 0

        val line1 = orderLineRepository.findByOrderId(order.id).first { it.skuId == skus.first } // 30,000
        orderService.refundLines(buyerId, order.id, listOf(line1.id))

        // 부분환불 주문의 잔여 라인(20,000)은 정산에 계속 포함, 환불 라인(30,000)은 제외
        orderLineRepository.sumSellerSalesInPeriod(sellerId, start, end).compareTo(BigDecimal("20000")) shouldBe 0
    }
}
