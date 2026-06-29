package com.commerce.ledger.domain

enum class LedgerEntryType {
    PURCHASE, REDEMPTION, REFUND, WITHDRAWAL, EXPIRY, SETTLEMENT, CANCELLATION, MANUAL_ADJUSTMENT,
    // 커머스 확장 (스펙 §3.2)
    COUPON_SUBSIDY, // 쿠폰 보조 분개 (DEBIT MERCHANT_RECEIVABLE / CREDIT PROMOTION_FUNDING)
    POINT_EARN,     // 포인트 적립 분개 (DEBIT POINT_BALANCE / CREDIT POINT_FUNDING)
}

enum class LedgerEntrySide {
    DEBIT, CREDIT
}
