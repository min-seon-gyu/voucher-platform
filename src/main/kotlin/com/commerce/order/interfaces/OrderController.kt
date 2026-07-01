package com.commerce.order.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.common.idempotency.Idempotent
import com.commerce.common.security.SecurityUtils
import com.commerce.order.application.OrderService
import com.commerce.order.domain.Order
import com.commerce.order.domain.OrderLine
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

data class PlaceOrderRequest(val couponId: Long? = null)

data class OrderLineResponse(
    val skuId: Long,
    val sellerId: Long,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val lineAmount: BigDecimal,
) {
    companion object {
        fun from(l: OrderLine) = OrderLineResponse(l.skuId, l.sellerId, l.quantity, l.unitPrice, l.lineAmount)
    }
}

data class OrderResponse(
    val id: Long,
    val memberId: Long,
    val status: String,
    val totalAmount: BigDecimal,
    val discountAmount: BigDecimal,
    val paidAmount: BigDecimal,
    val paymentTransactionId: Long?,
    val lines: List<OrderLineResponse>,
) {
    companion object {
        fun of(order: Order, lines: List<OrderLine>) = OrderResponse(
            id = order.id,
            memberId = order.memberId,
            status = order.status.name,
            totalAmount = order.totalAmount,
            discountAmount = order.discountAmount,
            paidAmount = order.paidAmount,
            paymentTransactionId = order.paymentTransactionId,
            lines = lines.map { OrderLineResponse.from(it) },
        )
    }
}

/** 주문(체크아웃/취소/조회). 인증 필수 — 본인 주문만. */
@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val orderService: OrderService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent // 체크아웃 중복 방지 — 같은 Idempotency-Key 재시도 시 원 응답(201) 반환
    fun place(@RequestBody(required = false) request: PlaceOrderRequest?): ApiResponse<OrderResponse> {
        val order = orderService.placeOrder(SecurityUtils.currentMemberId(), request?.couponId)
        val detail = orderService.getDetail(order.id)
        return ApiResponse.ok(OrderResponse.of(detail.order, detail.lines))
    }

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: Long): ApiResponse<OrderResponse> {
        orderService.cancelOrder(SecurityUtils.currentMemberId(), id)
        val detail = orderService.getDetail(id)
        return ApiResponse.ok(OrderResponse.of(detail.order, detail.lines))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): ApiResponse<OrderResponse> {
        val detail = orderService.getDetail(id)
        // 본인 주문만(ADMIN은 전체 조회).
        if (!SecurityUtils.isAdmin() && detail.order.memberId != SecurityUtils.currentMemberId())
            throw BusinessException(ErrorCode.ACCESS_DENIED)
        return ApiResponse.ok(OrderResponse.of(detail.order, detail.lines))
    }
}
