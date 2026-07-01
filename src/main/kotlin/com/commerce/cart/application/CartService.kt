package com.commerce.cart.application

import com.commerce.cart.domain.CartItem
import com.commerce.cart.infrastructure.CartItemJpaRepository
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.inventory.application.StockService
import com.commerce.product.infrastructure.ProductJpaRepository
import com.commerce.product.infrastructure.SkuJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class CartItemView(
    val cartItemId: Long,
    val skuId: Long,
    val productId: Long,
    val productName: String,
    val optionName: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
    val lineAmount: BigDecimal,
    val stockQuantity: Int,
)

data class CartView(val items: List<CartItemView>, val totalAmount: BigDecimal)

@Service
class CartService(
    private val cartItemRepository: CartItemJpaRepository,
    private val skuRepository: SkuJpaRepository,
    private val productRepository: ProductJpaRepository,
    private val stockService: StockService,
) {

    /** 카트에 SKU 추가. 이미 있으면 수량 가산. */
    @Transactional
    fun addItem(memberId: Long, skuId: Long, quantity: Int): CartItem {
        require(quantity > 0) { "수량은 1 이상이어야 합니다" }
        skuRepository.findById(skuId).orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        val existing = cartItemRepository.findByMemberIdAndSkuId(memberId, skuId)
        return if (existing != null) existing.also { it.addQuantity(quantity) }
        else cartItemRepository.save(CartItem(memberId, skuId, quantity))
    }

    /** 카트 항목 수량 변경. */
    @Transactional
    fun setQuantity(memberId: Long, skuId: Long, quantity: Int): CartItem {
        val item = cartItemRepository.findByMemberIdAndSkuId(memberId, skuId)
            ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
        item.changeQuantity(quantity)
        return item
    }

    @Transactional
    fun removeItem(memberId: Long, skuId: Long) {
        cartItemRepository.findByMemberIdAndSkuId(memberId, skuId)?.let { cartItemRepository.delete(it) }
    }

    @Transactional
    fun clear(memberId: Long) = cartItemRepository.deleteByMemberId(memberId)

    @Transactional(readOnly = true)
    fun getItems(memberId: Long): List<CartItem> = cartItemRepository.findByMemberId(memberId)

    /** 카트 상세(상품명·가격·재고 enrich). */
    @Transactional(readOnly = true)
    fun getCartView(memberId: Long): CartView {
        val items = cartItemRepository.findByMemberId(memberId)
        if (items.isEmpty()) return CartView(emptyList(), BigDecimal.ZERO)
        val skus = skuRepository.findAllById(items.map { it.skuId }).associateBy { it.id }
        val stocks = stockService.quantitiesBySkuIds(items.map { it.skuId })
        val productNames = productRepository.findAllById(skus.values.map { it.productId }).associate { it.id to it.name }
        val views = items.mapNotNull { item ->
            val sku = skus[item.skuId] ?: return@mapNotNull null
            CartItemView(
                cartItemId = item.id,
                skuId = sku.id,
                productId = sku.productId,
                productName = productNames[sku.productId] ?: "",
                optionName = sku.optionName,
                unitPrice = sku.price,
                quantity = item.quantity,
                lineAmount = sku.price * item.quantity.toBigDecimal(),
                stockQuantity = stocks[sku.id] ?: 0,
            )
        }
        return CartView(views, views.fold(BigDecimal.ZERO) { acc, v -> acc + v.lineAmount })
    }
}
