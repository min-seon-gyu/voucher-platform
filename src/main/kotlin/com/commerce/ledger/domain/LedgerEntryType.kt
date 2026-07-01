package com.commerce.ledger.domain

enum class LedgerEntryType {
    ORDER_PAYMENT,     // 주문 결제 분개 (DEBIT CUSTOMER_CASH / CREDIT SELLER_PAYABLE)
    ORDER_CANCEL,      // 주문 취소 역분개 (DEBIT SELLER_PAYABLE / CREDIT CUSTOMER_CASH)
    POINT_EARN,        // 포인트 적립 분개 (DEBIT POINT_BALANCE / CREDIT POINT_FUNDING)
    COUPON_SUBSIDY,    // 쿠폰 할인 출연 분개 (DEBIT PROMOTION_FUNDING / CREDIT SELLER_PAYABLE)
    SETTLEMENT,        // 정산 확정 분개 (DEBIT SELLER_PAYABLE / CREDIT SETTLEMENT_PAYABLE)
    CANCELLATION,      // 취소 역분개 (포인트 역적립 등)
    MANUAL_ADJUSTMENT, // 수동 정정
}

enum class LedgerEntrySide {
    DEBIT, CREDIT
}
