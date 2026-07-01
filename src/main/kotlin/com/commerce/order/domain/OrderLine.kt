package com.commerce.order.domain

import com.commerce.common.domain.BaseEntity
import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 주문 라인(SKU 단위). sellerId를 비정규화로 보관해 판매자별 정산 합산(Phase 4)을 단순화한다.
 * lineAmount = unitPrice × quantity (주문 시점 가격 스냅샷).
 */
@Entity
@Table(
    name = "order_lines",
    indexes = [
        Index(name = "idx_orderline_order", columnList = "order_id"),
        Index(name = "idx_orderline_seller", columnList = "seller_id"),
    ],
)
class OrderLine(
    @Column(nullable = false)
    val orderId: Long,

    @Column(nullable = false)
    val skuId: Long,

    @Column(nullable = false)
    val sellerId: Long,

    @Column(nullable = false)
    val quantity: Int,

    @Column(nullable = false, precision = 38, scale = 2)
    val unitPrice: BigDecimal,

    @Column(nullable = false, precision = 38, scale = 2)
    val lineAmount: BigDecimal,
) : BaseEntity()
