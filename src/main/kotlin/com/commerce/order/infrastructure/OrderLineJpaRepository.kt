package com.commerce.order.infrastructure

import com.commerce.order.domain.OrderLine
import org.springframework.data.jpa.repository.JpaRepository

interface OrderLineJpaRepository : JpaRepository<OrderLine, Long> {
    fun findByOrderId(orderId: Long): List<OrderLine>
    fun findBySellerId(sellerId: Long): List<OrderLine>
}
