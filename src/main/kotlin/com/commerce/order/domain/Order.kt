package com.commerce.order.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*
import java.math.BigDecimal

/**
 * PAID → (부분환불) PARTIALLY_REFUNDED → (전액 환불) REFUNDED. CANCELLED는 전체 취소 경로.
 * 부분환불은 라인 단위이며 상태는 남은 라인 유무로 결정된다.
 */
enum class OrderStatus { PAID, PARTIALLY_REFUNDED, REFUNDED, CANCELLED }

/**
 * 주문(결제 완료 단위). 다판매자 주문을 지원한다 — 하위 [OrderLine]이 판매자별로 나뉘며,
 * 판매자 정산은 라인을 판매자별로 합산한다(Phase 4). 결제는 동기 처리되어 생성 시 PAID.
 * total = 주문총액, discount = 할인액(쿠폰), paid = 실결제액(total - discount).
 */
@Entity
@Table(name = "orders", indexes = [Index(name = "idx_order_member", columnList = "member_id, status")])
class Order(
    @Column(nullable = false)
    val memberId: Long,

    @Column(nullable = false, precision = 38, scale = 2)
    val totalAmount: BigDecimal,

    @Column(nullable = false, precision = 38, scale = 2)
    val discountAmount: BigDecimal,

    @Column(nullable = false, precision = 38, scale = 2)
    val paidAmount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OrderStatus = OrderStatus.PAID,

    @Column
    var paymentTransactionId: Long? = null,

    @Column(name = "coupon_id")
    val couponId: Long? = null,

    /** 누적 환불 gross(라인 금액 합). 매 환불마다 증가하며, total과 같아지면 전액 환불. */
    @Column(nullable = false, precision = 38, scale = 2)
    var refundedAmount: BigDecimal = BigDecimal.ZERO,
) : BaseEntity() {

    fun cancel() {
        if (status != OrderStatus.PAID) throw BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE)
        status = OrderStatus.CANCELLED
    }

    /**
     * 부분/전액 환불 반영. [refundGross]만큼 누적 환불액을 늘리고, 누적이 total과 같아지면 REFUNDED, 아니면 PARTIALLY_REFUNDED.
     * PAID/PARTIALLY_REFUNDED에서만 허용. 누적값 변경으로 orders 행에 항상 versioned UPDATE가 발생하므로,
     * 같은 주문에 대한 동시 환불은 낙관적 락(@Version)으로 직렬화되어 상태 고착을 방지한다.
     */
    fun applyRefund(refundGross: BigDecimal) {
        if (status != OrderStatus.PAID && status != OrderStatus.PARTIALLY_REFUNDED)
            throw BusinessException(ErrorCode.ORDER_NOT_REFUNDABLE)
        refundedAmount += refundGross
        status = if (refundedAmount.compareTo(totalAmount) == 0) OrderStatus.REFUNDED else OrderStatus.PARTIALLY_REFUNDED
    }
}
