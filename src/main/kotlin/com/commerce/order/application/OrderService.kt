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
import com.commerce.order.domain.event.OrderCancelledEvent
import com.commerce.order.domain.event.OrderPlacedEvent
import com.commerce.order.infrastructure.OrderJpaRepository
import com.commerce.order.infrastructure.OrderLineJpaRepository
import com.commerce.point.application.PointEarnService
import com.commerce.product.domain.ProductStatus
import com.commerce.product.infrastructure.ProductJpaRepository
import com.commerce.product.infrastructure.SkuJpaRepository
import com.commerce.promotion.domain.CouponStatus
import com.commerce.promotion.infrastructure.CouponJpaRepository
import com.commerce.promotion.infrastructure.PromotionBudgetManager
import com.commerce.promotion.infrastructure.PromotionJpaRepository
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionType
import org.springframework.context.ApplicationEventPublisher
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
 * 원장:
 *   - 무쿠폰: DEBIT CUSTOMER_CASH(T) / CREDIT SELLER_PAYABLE(T)
 *   - 쿠폰(할인 D): DEBIT CUSTOMER_CASH(T−D) / CREDIT SELLER_PAYABLE(T−D)  +  DEBIT PROMOTION_FUNDING(D) / CREDIT SELLER_PAYABLE(D)
 *     → 판매자는 항상 gross T, 고객 현금은 T−D, 플랫폼 출연 D. 취소는 반대.
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
    private val couponRepository: CouponJpaRepository,
    private val promotionRepository: PromotionJpaRepository,
    private val budgetManager: PromotionBudgetManager,
    private val transactionTemplate: TransactionTemplate,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private data class LineSpec(val skuId: Long, val sellerId: Long, val quantity: Int, val unitPrice: BigDecimal)
    private data class AppliedCoupon(val couponId: Long, val promotionId: Long, val discount: BigDecimal)

    fun placeOrder(memberId: Long, couponId: Long? = null): Order {
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

        // 쿠폰 검증 + 예산 원자 예약(tx 밖). 다운스트림 실패 시 예약 보상 반환.
        val applied = resolveCoupon(memberId, couponId, total)
        val discount = applied?.discount ?: BigDecimal.ZERO
        val paid = total - discount

        try {
            return stockLockManager.withStockLocks(specs.map { it.skuId }) {
                transactionTemplate.execute { _ ->
                    specs.forEach { stockService.deductWithinTx(it.skuId, it.quantity) }

                    val order = orderRepository.save(
                        Order(memberId = memberId, totalAmount = total, discountAmount = discount, paidAmount = paid, couponId = applied?.couponId)
                    )
                    specs.forEach {
                        orderLineRepository.save(
                            OrderLine(order.id, it.skuId, it.sellerId, it.quantity, it.unitPrice, it.unitPrice * it.quantity.toBigDecimal())
                        )
                    }

                    val tx = transactionService.create(type = TransactionType.ORDER_PAYMENT, amount = total, memberId = memberId)
                    // 고객 현금(T−D) → 판매자 미지급금
                    ledgerService.record(AccountCode.CUSTOMER_CASH, AccountCode.SELLER_PAYABLE, paid, tx.id, LedgerEntryType.ORDER_PAYMENT)
                    // 플랫폼 할인 출연(D) → 판매자 미지급금 (판매자는 gross 유지)
                    if (discount > BigDecimal.ZERO) {
                        ledgerService.record(AccountCode.PROMOTION_FUNDING, AccountCode.SELLER_PAYABLE, discount, tx.id, LedgerEntryType.COUPON_SUBSIDY)
                    }
                    tx.complete()
                    order.paymentTransactionId = tx.id

                    // 쿠폰 사용 확정(ISSUED→REDEEMED) — 같은 tx에서 관리 엔티티로 로드
                    applied?.let { couponRepository.findById(it.couponId).get().redeem() }

                    // 포인트는 실결제액(T−D) 기준 적립
                    pointEarnService.earn(memberId, paid, tx.id)
                    cartService.clear(memberId)
                    // 주문 이벤트 발행 → OrderOutboxRecorder(BEFORE_COMMIT)가 같은 tx에서 outbox 캡처(원자적)
                    eventPublisher.publishEvent(OrderPlacedEvent(order.id, memberId, total))
                    order
                }!!
            }
        } catch (e: Exception) {
            // tx/락 실패 → 예약한 예산을 되돌린다(보상).
            applied?.let { budgetManager.release(it.promotionId, it.discount) }
            throw e
        }
    }

    /** 쿠폰 검증(소유·상태·만료·프로모션 활성·최소결제·1인 한도) 후 할인액 산정 + 예산 원자 예약. 쿠폰 없으면 null. */
    private fun resolveCoupon(memberId: Long, couponId: Long?, orderTotal: BigDecimal): AppliedCoupon? {
        if (couponId == null) return null
        val coupon = couponRepository.findById(couponId)
            .orElseThrow { BusinessException(ErrorCode.COUPON_NOT_FOUND) }
        if (coupon.memberId != memberId) throw BusinessException(ErrorCode.ACCESS_DENIED)
        if (coupon.status != CouponStatus.ISSUED) throw BusinessException(ErrorCode.COUPON_ALREADY_USED)
        if (coupon.isExpired()) throw BusinessException(ErrorCode.COUPON_EXPIRED)

        val promotion = promotionRepository.findById(coupon.promotionId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        if (!promotion.isActive()) throw BusinessException(ErrorCode.PROMOTION_NOT_ACTIVE)
        if (orderTotal < promotion.minSpend) throw BusinessException(ErrorCode.MIN_SPEND_NOT_MET)
        val alreadyUsed = couponRepository.countByMemberIdAndPromotionIdAndStatus(memberId, promotion.id, CouponStatus.REDEEMED)
        if (alreadyUsed >= promotion.perMemberLimit) throw BusinessException(ErrorCode.COUPON_USAGE_LIMIT_EXCEEDED)

        // 할인액: 주문총액 초과 불가(클램프). PERCENTAGE는 0원 단위 내림(과할인 방지, 도메인에서 처리).
        val discount = promotion.calculateDiscount(orderTotal).min(orderTotal)
        if (discount <= BigDecimal.ZERO) return null

        if (!budgetManager.reserve(promotion.id, discount, promotion.budgetLimit))
            throw BusinessException(ErrorCode.PROMOTION_BUDGET_EXCEEDED)
        return AppliedCoupon(couponId, promotion.id, discount)
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
                eventPublisher.publishEvent(OrderCancelledEvent(o.id, requesterMemberId, o.totalAmount))
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
