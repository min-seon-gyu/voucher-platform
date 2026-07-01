package com.commerce.order.application

import com.commerce.cart.application.CartService
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.inventory.application.StockLockManager
import com.commerce.inventory.application.StockService
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.order.domain.Order
import com.commerce.order.domain.OrderLine
import com.commerce.order.domain.OrderStatus
import com.commerce.order.infrastructure.OrderJpaRepository
import com.commerce.order.infrastructure.OrderLineJpaRepository
import com.commerce.point.application.PointEarnService
import com.commerce.product.domain.ProductStatus
import com.commerce.product.infrastructure.ProductJpaRepository
import com.commerce.product.infrastructure.SkuJpaRepository
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal

data class OrderDetail(val order: Order, val lines: List<OrderLine>)

/**
 * 주문 오케스트레이터. 카트를 주문으로 확정하고 결제를 동기 처리한다.
 *
 * 결제(placeOrder): SKU 락(정준 순서) → **한 트랜잭션 안에서** 재고 차감 + 주문/라인 저장 + 거래/원장/포인트 →
 * 카트 비움. 재고 차감이 같은 tx이므로 실패 시 원자적으로 롤백된다(별도 보상 불필요).
 * 취소(cancelOrder): 보상 트랜잭션으로 재고 복원 + 원장 역분개 + 포인트 역적립 + 상태 전이.
 *
 * 원장(무쿠폰): 결제 DEBIT CUSTOMER_CASH / CREDIT SELLER_PAYABLE (gross), 취소는 반대.
 * (판매자별 정산은 OrderLine을 sellerId로 합산 — Phase 4)
 */
@Service
class OrderService(
    private val cartService: CartService,
    private val skuRepository: SkuJpaRepository,
    private val productRepository: ProductJpaRepository,
    private val stockService: StockService,
    private val stockLockManager: StockLockManager,
    private val orderRepository: OrderJpaRepository,
    private val orderLineRepository: OrderLineJpaRepository,
    private val transactionService: TransactionService,
    private val ledgerService: LedgerService,
    private val pointEarnService: PointEarnService,
    private val transactionTemplate: TransactionTemplate,
) {

    private data class LineSpec(val skuId: Long, val sellerId: Long, val quantity: Int, val unitPrice: BigDecimal)

    fun placeOrder(memberId: Long): Order {
        val items = cartService.getItems(memberId)
        if (items.isEmpty()) throw BusinessException(ErrorCode.CART_EMPTY)

        val specs = items.map { item ->
            val sku = skuRepository.findById(item.skuId)
                .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
            val product = productRepository.findById(sku.productId)
                .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
            if (product.status != ProductStatus.ON_SALE) throw BusinessException(ErrorCode.PRODUCT_NOT_ON_SALE)
            LineSpec(sku.id, product.sellerId, item.quantity, sku.price)
        }
        val total = specs.fold(BigDecimal.ZERO) { acc, s -> acc + s.unitPrice * s.quantity.toBigDecimal() }

        return stockLockManager.withStockLocks(specs.map { it.skuId }) {
            transactionTemplate.execute { _ ->
                specs.forEach { stockService.deductWithinTx(it.skuId, it.quantity) }

                val order = orderRepository.save(
                    Order(memberId = memberId, totalAmount = total, discountAmount = BigDecimal.ZERO, paidAmount = total)
                )
                specs.forEach {
                    orderLineRepository.save(
                        OrderLine(order.id, it.skuId, it.sellerId, it.quantity, it.unitPrice, it.unitPrice * it.quantity.toBigDecimal())
                    )
                }

                val tx = transactionService.create(type = TransactionType.ORDER_PAYMENT, amount = total, memberId = memberId)
                ledgerService.record(
                    debitAccount = AccountCode.CUSTOMER_CASH,
                    creditAccount = AccountCode.SELLER_PAYABLE,
                    amount = total,
                    transactionId = tx.id,
                    entryType = LedgerEntryType.ORDER_PAYMENT,
                )
                tx.complete()
                order.paymentTransactionId = tx.id

                pointEarnService.earn(memberId, total, tx.id)
                cartService.clear(memberId)
                order
            }!!
        }
    }

    fun cancelOrder(requesterMemberId: Long, orderId: Long): Order {
        val order = orderRepository.findById(orderId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        if (order.memberId != requesterMemberId) throw BusinessException(ErrorCode.ACCESS_DENIED)
        if (order.status != OrderStatus.PAID) throw BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE)
        val lines = orderLineRepository.findByOrderId(orderId)

        return stockLockManager.withStockLocks(lines.map { it.skuId }) {
            transactionTemplate.execute { _ ->
                val o = orderRepository.findById(orderId).orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
                if (o.status != OrderStatus.PAID) throw BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE) // 재확인(동시 취소 방지)

                lines.forEach { stockService.restoreWithinTx(it.skuId, it.quantity) }

                val comp = transactionService.create(
                    type = TransactionType.ORDER_CANCEL,
                    amount = o.paidAmount,
                    memberId = requesterMemberId,
                    originalTransactionId = o.paymentTransactionId,
                )
                ledgerService.record(
                    debitAccount = AccountCode.SELLER_PAYABLE,
                    creditAccount = AccountCode.CUSTOMER_CASH,
                    amount = o.totalAmount,
                    transactionId = comp.id,
                    entryType = LedgerEntryType.ORDER_CANCEL,
                )
                comp.complete()
                o.paymentTransactionId?.let { pointEarnService.reverseEarn(requesterMemberId, it, comp.id) }
                o.cancel()
                o
            }!!
        }
    }

    @Transactional(readOnly = true)
    fun getDetail(orderId: Long): OrderDetail {
        val order = orderRepository.findById(orderId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        return OrderDetail(order, orderLineRepository.findByOrderId(orderId))
    }
}
