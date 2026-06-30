package com.commerce.voucher.interfaces.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

data class PurchaseVoucherRequest(
    @field:NotNull val regionId: Long,
    @field:NotNull @field:Min(1000) val faceValue: BigDecimal,
)

data class RedeemRequest(
    @field:NotNull val merchantId: Long,
    @field:NotNull @field:Min(1) val amount: BigDecimal, // 주문 총액 T (gross)
    val couponId: Long? = null,
)
