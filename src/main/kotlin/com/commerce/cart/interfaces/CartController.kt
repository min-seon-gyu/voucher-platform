package com.commerce.cart.interfaces

import com.commerce.cart.application.CartService
import com.commerce.cart.application.CartView
import com.commerce.common.api.ApiResponse
import com.commerce.common.security.SecurityUtils
import org.springframework.web.bind.annotation.*

data class AddCartItemRequest(val skuId: Long, val quantity: Int)
data class UpdateCartItemRequest(val quantity: Int)

/** 회원 장바구니(다판매자). 인증 필수 — 항상 JWT 주체의 카트만 조작한다. */
@RestController
@RequestMapping("/api/v1/cart")
class CartController(
    private val cartService: CartService,
) {

    @GetMapping
    fun getCart(): ApiResponse<CartView> =
        ApiResponse.ok(cartService.getCartView(SecurityUtils.currentMemberId()))

    @PostMapping("/items")
    fun addItem(@RequestBody request: AddCartItemRequest): ApiResponse<CartView> {
        cartService.addItem(SecurityUtils.currentMemberId(), request.skuId, request.quantity)
        return ApiResponse.ok(cartService.getCartView(SecurityUtils.currentMemberId()))
    }

    @PutMapping("/items/{skuId}")
    fun updateItem(@PathVariable skuId: Long, @RequestBody request: UpdateCartItemRequest): ApiResponse<CartView> {
        cartService.setQuantity(SecurityUtils.currentMemberId(), skuId, request.quantity)
        return ApiResponse.ok(cartService.getCartView(SecurityUtils.currentMemberId()))
    }

    @DeleteMapping("/items/{skuId}")
    fun removeItem(@PathVariable skuId: Long): ApiResponse<CartView> {
        cartService.removeItem(SecurityUtils.currentMemberId(), skuId)
        return ApiResponse.ok(cartService.getCartView(SecurityUtils.currentMemberId()))
    }

    @DeleteMapping
    fun clear(): ApiResponse<Unit> {
        cartService.clear(SecurityUtils.currentMemberId())
        return ApiResponse.ok(Unit)
    }
}
