package com.commerce.transaction.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.Transaction
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

data class TransactionResponse(
    val id: Long,
    val type: String,
    val amount: BigDecimal,
    val status: String,
    val sellerId: Long?,
    val originalTransactionId: Long?,
) {
    companion object {
        fun from(t: Transaction) = TransactionResponse(
            id = t.id,
            type = t.type.name,
            amount = t.amount,
            status = t.status.name,
            sellerId = t.sellerId,
            originalTransactionId = t.originalTransactionId,
        )
    }
}

@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController(
    private val transactionService: TransactionService,
) {

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ApiResponse<TransactionResponse> =
        ApiResponse.ok(TransactionResponse.from(transactionService.getById(id)))
}
