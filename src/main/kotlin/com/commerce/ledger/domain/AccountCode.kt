package com.commerce.ledger.domain

enum class AccountCode(val description: String) {
    MEMBER_CASH("회원 현금"),
    VOUCHER_BALANCE("상품권 잔액"),
    MERCHANT_RECEIVABLE("가맹점 미수금"),
    REVENUE_DISCOUNT("할인 수익"),
    EXPIRED_VOUCHER("만료 상품권"),
    REFUND_PAYABLE("환불 미지급금"),
    SETTLEMENT_PAYABLE("정산 미지급금"),

    // 커머스 확장 (스펙 §3.2): 플랫폼 펀딩·정산 gross 모델 전용 계정
    PROMOTION_FUNDING("프로모션 출연금"), // 대변정상: 플랫폼 쿠폰 보조 누적
    POINT_BALANCE("포인트 잔액"),         // 차변정상: VOUCHER_BALANCE와 동일 취급
    POINT_FUNDING("포인트 출연금"),       // 대변정상: 플랫폼 포인트 적립 출연
}
