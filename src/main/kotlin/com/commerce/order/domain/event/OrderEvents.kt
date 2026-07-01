package com.commerce.order.domain.event

import com.commerce.common.domain.DomainEvent
import java.math.BigDecimal

/**
 * 주문 도메인 이벤트. Transactional Outbox → Kafka → 소비자(order_event_log)로 전달된다.
 * (구 감사 파이프라인을 주문 이벤트 전달로 이관 — 이벤트 이력/알림/분석 fan-out의 단일 백본.)
 */
sealed class OrderEvent : DomainEvent() {
    final override val aggregateType = "ORDER"
    abstract val memberId: Long
    abstract val totalAmount: BigDecimal
}

class OrderPlacedEvent(
    override val aggregateId: Long, // orderId
    override val memberId: Long,
    override val totalAmount: BigDecimal,
) : OrderEvent() {
    override val eventType = "ORDER_PLACED"
    override val currentState get() = """{"status":"PAID","totalAmount":$totalAmount,"memberId":$memberId}"""
}

class OrderCancelledEvent(
    override val aggregateId: Long, // orderId
    override val memberId: Long,
    override val totalAmount: BigDecimal,
) : OrderEvent() {
    override val eventType = "ORDER_CANCELLED"
    override val currentState get() = """{"status":"CANCELLED","totalAmount":$totalAmount,"memberId":$memberId}"""
}

/**
 * 부분/전액 환불. [totalAmount]는 이번 환불로 고객에게 환급된 실결제액(net)이다.
 * [fullyRefunded]가 true면 주문 전체가 환불됨(REFUNDED), false면 부분환불(PARTIALLY_REFUNDED).
 */
class OrderRefundedEvent(
    override val aggregateId: Long, // orderId
    override val memberId: Long,
    override val totalAmount: BigDecimal, // 이번 환불 net
    val fullyRefunded: Boolean,
) : OrderEvent() {
    override val eventType = "ORDER_REFUNDED"
    override val currentState
        get() = """{"status":"${if (fullyRefunded) "REFUNDED" else "PARTIALLY_REFUNDED"}","refundedAmount":$totalAmount,"memberId":$memberId}"""
}
