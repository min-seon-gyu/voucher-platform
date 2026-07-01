package com.commerce.order.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*
import java.math.BigDecimal

enum class OrderStatus { PAID, CANCELLED }

/**
 * 주문(결제 완료 단위). 다판매자 주문을 지원한다 — 하위 [OrderLine]이 판매자별로 나뉘며,
 * 판매자 정산은 라인을 판매자별로 합산한다(Phase 4). 결제는 동기 처리되어 생성 시 PAID.
 * total = 주문총액, discount = 할인액(쿠폰, 현재 0), paid = 실결제액(total - discount).
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
) : BaseEntity() {

    fun cancel() {
        if (status != OrderStatus.PAID) throw BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE)
        status = OrderStatus.CANCELLED
    }
}
