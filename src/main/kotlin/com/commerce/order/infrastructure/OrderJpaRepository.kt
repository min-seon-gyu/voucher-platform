package com.commerce.order.infrastructure

import com.commerce.order.domain.Order
import org.springframework.data.jpa.repository.JpaRepository

interface OrderJpaRepository : JpaRepository<Order, Long> {
    fun findByMemberId(memberId: Long): List<Order>
}
