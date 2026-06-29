package com.commerce.common.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String
) {
    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력값입니다"),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "데이터를 찾을 수 없습니다"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "동시 수정이 감지되었습니다"),
    LOCK_ACQUISITION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "처리 중입니다. 잠시 후 다시 시도해주세요"),
    IDEMPOTENCY_DUPLICATE(HttpStatus.OK, "이미 처리된 요청입니다"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),

    // Region
    REGION_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "운영 중인 지자체가 아닙니다"),
    REGION_MONTHLY_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "지자체 월 발행한도를 초과했습니다"),

    // Member
    MEMBER_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "활성 상태의 회원이 아닙니다"),
    MEMBER_PURCHASE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "1인 구매한도를 초과했습니다"),

    // Merchant
    MERCHANT_NOT_APPROVED(HttpStatus.BAD_REQUEST, "승인된 가맹점이 아닙니다"),
    INVALID_STATE_TRANSITION(HttpStatus.BAD_REQUEST, "허용되지 않은 상태 전이입니다"),

    // Voucher
    VOUCHER_NOT_USABLE(HttpStatus.BAD_REQUEST, "사용할 수 없는 상품권입니다"),
    VOUCHER_EXPIRED(HttpStatus.BAD_REQUEST, "만료된 상품권입니다"),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "잔액이 부족합니다"),
    REFUND_CONDITION_NOT_MET(HttpStatus.BAD_REQUEST, "환불 조건을 충족하지 않습니다 (60% 이상 사용 필요)"),
    WITHDRAWAL_PERIOD_EXPIRED(HttpStatus.BAD_REQUEST, "청약철회 기간이 만료되었습니다 (구매 후 7일 이내)"),
    WITHDRAWAL_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "사용된 상품권은 청약철회할 수 없습니다"),

    // Transaction
    TRANSACTION_NOT_CANCELLABLE(HttpStatus.BAD_REQUEST, "취소할 수 없는 거래입니다"),

    // Ledger
    LEDGER_IMBALANCE_DETECTED(HttpStatus.INTERNAL_SERVER_ERROR, "원장 정합성 오류가 감지되었습니다"),
    MANUAL_ADJUSTMENT_REQUIRES_ADMIN(HttpStatus.FORBIDDEN, "수동 원장 조정은 관리자 승인이 필요합니다"),

    // Promotion / Coupon (스펙 §4.1, §7)
    PROMOTION_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "진행 중인 프로모션이 아닙니다"),
    PROMOTION_BUDGET_EXCEEDED(HttpStatus.BAD_REQUEST, "프로모션 예산이 소진되었습니다"),
    INVALID_DISCOUNT(HttpStatus.BAD_REQUEST, "유효하지 않은 할인 금액입니다"),
    MIN_SPEND_NOT_MET(HttpStatus.BAD_REQUEST, "최소 결제금액 조건을 충족하지 않습니다"),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다"),
    COUPON_EXPIRED(HttpStatus.BAD_REQUEST, "만료된 쿠폰입니다"),
    COUPON_ALREADY_USED(HttpStatus.BAD_REQUEST, "이미 사용된 쿠폰입니다"),
    COUPON_USAGE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "회원당 쿠폰 사용 한도를 초과했습니다"),

    // Auth — 본인 자원 외 접근(FORBIDDEN, 403) 전용. 미인증(UNAUTHORIZED, 401)은 SecurityUtils.currentMemberId()가 던진다.
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인 자원에만 접근할 수 있습니다"),

    // Point
    POINT_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "포인트 계정을 찾을 수 없습니다"),

    // AI Promotion Assistant
    AI_DRAFT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 프로모션 초안 기능이 비활성화되어 있습니다"),
    AI_DRAFT_GENERATION_FAILED(HttpStatus.BAD_GATEWAY, "AI 초안 생성에 실패했습니다. 잠시 후 다시 시도해주세요"),
}
