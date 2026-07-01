package com.commerce.cart.infrastructure

import com.commerce.cart.domain.CartItem
import org.springframework.data.jpa.repository.JpaRepository

interface CartItemJpaRepository : JpaRepository<CartItem, Long> {
    fun findByMemberId(memberId: Long): List<CartItem>
    fun findByMemberIdAndSkuId(memberId: Long, skuId: Long): CartItem?
    fun deleteByMemberId(memberId: Long)
}
