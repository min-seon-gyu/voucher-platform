package com.commerce.cart.domain

import com.commerce.common.domain.BaseEntity
import jakarta.persistence.*

/**
 * 장바구니 항목(회원별). 다판매자 카트를 자연히 지원한다 — 각 항목은 SKU 하나를 가리키므로
 * 서로 다른 판매자의 SKU가 한 회원의 카트에 공존할 수 있다. (member_id, sku_id) 유니크.
 */
@Entity
@Table(
    name = "cart_items",
    uniqueConstraints = [UniqueConstraint(name = "uk_cart_member_sku", columnNames = ["memberId", "skuId"])],
)
class CartItem(
    @Column(nullable = false)
    val memberId: Long,

    @Column(nullable = false)
    val skuId: Long,

    @Column(nullable = false)
    var quantity: Int,
) : BaseEntity() {

    fun changeQuantity(newQuantity: Int) {
        require(newQuantity > 0) { "수량은 1 이상이어야 합니다" }
        quantity = newQuantity
    }

    fun addQuantity(delta: Int) {
        require(delta > 0) { "추가 수량은 1 이상이어야 합니다" }
        quantity += delta
    }
}
