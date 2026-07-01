package com.commerce.integration

import com.commerce.cart.application.CartService
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.inventory.application.StockService
import com.commerce.order.application.OrderService
import com.commerce.product.application.ProductService
import com.commerce.product.application.SkuSpec
import com.commerce.product.domain.ProductCategory
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 주문 결제 동시성: 재고 5인 SKU를 10명이 각 1개씩 동시 주문 → 정확히 5건 성공/5건 OUT_OF_STOCK, 최종 재고 0.
 * (주문 결제가 재고 차감을 같은 트랜잭션 + 분산락/비관적 락으로 처리해 초과판매를 막는지 검증.)
 */
class OrderConcurrencyTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var productService: ProductService
    @Autowired lateinit var cartService: CartService
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var stockService: StockService

    @Test
    fun `concurrent orders on limited stock should not oversell`() {
        val region = fixtures.createRegion()
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(region, owner)
        val product = productService.createProduct(
            owner.id, seller.id, "한정판 의자", null, ProductCategory.FURNITURE,
            listOf(SkuSpec("LTD-${owner.id}", "기본", emptyMap(), BigDecimal("50000"), 5)),
        )
        productService.onSale(owner.id, product.id)
        val skuId = productService.getDetail(product.id).skus.first().sku.id

        val buyerCount = 10
        val buyers = (1..buyerCount).map { fixtures.createMember() }
        buyers.forEach { cartService.addItem(it.id, skuId, 1) }

        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(buyerCount)
        val success = AtomicInteger(0)
        val outOfStock = AtomicInteger(0)

        val futures = buyers.map { buyer ->
            executor.submit {
                latch.await()
                try {
                    orderService.placeOrder(buyer.id)
                    success.incrementAndGet()
                } catch (e: BusinessException) {
                    if (e.errorCode == ErrorCode.OUT_OF_STOCK) outOfStock.incrementAndGet()
                }
            }
        }
        latch.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        executor.shutdown()

        success.get() shouldBe 5
        outOfStock.get() shouldBe 5
        stockService.getBySkuId(skuId).quantity shouldBe 0
    }
}
