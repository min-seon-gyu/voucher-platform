package com.commerce.transaction.domain

enum class TransactionStatus {
    PENDING, COMPLETED, FAILED, CANCEL_REQUESTED, CANCELLED
}

enum class TransactionType {
    PURCHASE, REDEMPTION, REFUND, WITHDRAWAL, EXPIRY, CANCELLATION, SETTLEMENT,
    ORDER_PAYMENT, ORDER_CANCEL,
}
