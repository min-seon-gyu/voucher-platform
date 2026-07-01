package com.commerce.integration

import com.commerce.cart.application.CartService
import com.commerce.inventory.application.StockService
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.order.application.OrderService
import com.commerce.order.domain.OrderStatus
import com.commerce.product.application.ProductService
import com.commerce.product.application.SkuSpec
import com.commerce.product.domain.ProductCategory
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

/**
 * 다판매자 주문 결제/취소 E2E. 카트 → 주문 결제 시 재고 차감 + 원장(CUSTOMER_CASH/SELLER_PAYABLE) + 포인트,
 * 취소 시 보상(재고 복원 + 원장 역분개 + 포인트 역적립). 라인이 판매자별로 나뉘고 원장은 항상 차·대변 균형.
 */
class OrderFlowTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var productService: ProductService
    @Autowired lateinit var cartService: CartService
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var stockService: StockService
    @Autowired lateinit var verificationService: LedgerVerificationService

    private fun onSaleSku(ownerId: Long, sellerId: Long, name: String, code: String, price: String, stock: Int): Long {
        val product = productService.createProduct(
            ownerId, sellerId, name, null, ProductCategory.FURNITURE,
            listOf(SkuSpec(code, "기본", emptyMap(), BigDecimal(price), stock)),
        )
        productService.onSale(ownerId, product.id)
        return productService.getDetail(product.id).skus.first().sku.id
    }

    private fun ledgerBalanced() =
        verificationService.verify().let { it.globalDebitTotal.compareTo(it.globalCreditTotal) == 0 }

    @Test
    fun `place multi-seller order then cancel restores stock, ledger and points`() {
        val region = fixtures.createRegion()
        val buyer = fixtures.createMember()
        val ownerA = fixtures.createMember()
        val ownerB = fixtures.createMember()
        val sellerA = fixtures.createSeller(region, ownerA)
        val sellerB = fixtures.createSeller(region, ownerB)

        val skuA = onSaleSku(ownerA.id, sellerA.id, "의자", "CHAIR-${ownerA.id}", "50000", 10)
        val skuB = onSaleSku(ownerB.id, sellerB.id, "조명", "LAMP-${ownerB.id}", "30000", 5)

        cartService.addItem(buyer.id, skuA, 2)
        cartService.addItem(buyer.id, skuB, 1)

        // 결제
        val order = orderService.placeOrder(buyer.id)
        order.status shouldBe OrderStatus.PAID
        order.totalAmount.compareTo(BigDecimal("130000")) shouldBe 0 // 50000*2 + 30000
        stockService.getBySkuId(skuA).quantity shouldBe 8
        stockService.getBySkuId(skuB).quantity shouldBe 4

        val detail = orderService.getDetail(order.id)
        detail.lines.size shouldBe 2
        detail.lines.map { it.sellerId } shouldContainExactlyInAnyOrder listOf(sellerA.id, sellerB.id)
        cartService.getItems(buyer.id).isEmpty().shouldBeTrue() // 카트 비움
        ledgerBalanced().shouldBeTrue()

        // 취소(보상)
        orderService.cancelOrder(buyer.id, order.id)
        orderService.getDetail(order.id).order.status shouldBe OrderStatus.CANCELLED
        stockService.getBySkuId(skuA).quantity shouldBe 10 // 복원
        stockService.getBySkuId(skuB).quantity shouldBe 5
        ledgerBalanced().shouldBeTrue()
    }

    @Test
    fun `cannot place order with empty cart`() {
        val buyer = fixtures.createMember()
        val ex = io.kotest.assertions.throwables.shouldThrow<com.commerce.common.exception.BusinessException> {
            orderService.placeOrder(buyer.id)
        }
        ex.errorCode shouldBe com.commerce.common.exception.ErrorCode.CART_EMPTY
    }
}
