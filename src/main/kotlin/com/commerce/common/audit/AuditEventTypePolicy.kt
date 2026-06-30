package com.commerce.common.audit

/**
 * 이벤트 타입 → 감사 등급/액션 매핑의 단일 출처.
 * AuditEventListener(최초 기록)와 FailedEventRetryScheduler(실패 재처리)가 공유하여
 * 등급 분류가 두 경로에서 어긋나지 않도록 한다.
 */
object AuditEventTypePolicy {

    /** BEFORE_COMMIT 동기 기록 — 감사 실패 시 비즈니스 트랜잭션도 롤백 */
    val CRITICAL_EVENTS = setOf(
        "VOUCHER_ISSUED", "VOUCHER_REDEEMED", "VOUCHER_REFUNDED",
        "VOUCHER_WITHDRAWN", "TRANSACTION_CANCELLED", "MANUAL_ADJUSTMENT",
    )

    /** AFTER_COMMIT + REQUIRES_NEW — 실패 시 FailedEvent로 적재 후 재처리 */
    val HIGH_EVENTS = setOf(
        "MERCHANT_APPROVED", "MERCHANT_REJECTED", "MERCHANT_TERMINATED",
        "MEMBER_SUSPENDED", "MEMBER_WITHDRAWN", "REGION_POLICY_CHANGED",
        "SETTLEMENT_CONFIRMED",
    )

    fun resolveSeverity(eventType: String): AuditSeverity = when (eventType) {
        in CRITICAL_EVENTS -> AuditSeverity.CRITICAL
        in HIGH_EVENTS -> AuditSeverity.HIGH
        else -> AuditSeverity.MEDIUM
    }

    fun resolveAction(eventType: String): String = when {
        eventType.contains("ISSUED") || eventType.contains("APPROVED") -> "CREATE"
        eventType.contains("REDEEMED") || eventType.contains("REFUNDED") || eventType.contains("WITHDRAWN") -> "STATE_CHANGE"
        eventType.contains("CANCELLED") -> "CANCEL"
        eventType.contains("EXPIRED") -> "EXPIRE"
        eventType.contains("CONFIRMED") -> "CONFIRM"
        else -> "UPDATE"
    }
}
