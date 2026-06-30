package com.commerce.common.audit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AuditEventTypePolicyTest {

    @Test
    fun `settlement confirmed is HIGH not CRITICAL`() {
        // README 감사 등급표와 일치 — 정산 확정은 AFTER_COMMIT(HIGH)로 처리한다.
        AuditEventTypePolicy.resolveSeverity("SETTLEMENT_CONFIRMED") shouldBe AuditSeverity.HIGH
    }

    @Test
    fun `merchant and member lifecycle events are HIGH`() {
        AuditEventTypePolicy.resolveSeverity("MERCHANT_REJECTED") shouldBe AuditSeverity.HIGH
        AuditEventTypePolicy.resolveSeverity("MERCHANT_TERMINATED") shouldBe AuditSeverity.HIGH
        AuditEventTypePolicy.resolveSeverity("MEMBER_SUSPENDED") shouldBe AuditSeverity.HIGH
        AuditEventTypePolicy.resolveSeverity("MEMBER_WITHDRAWN") shouldBe AuditSeverity.HIGH
        AuditEventTypePolicy.resolveSeverity("REGION_POLICY_CHANGED") shouldBe AuditSeverity.HIGH
    }

    @Test
    fun `voucher and cancellation events remain CRITICAL`() {
        AuditEventTypePolicy.resolveSeverity("VOUCHER_ISSUED") shouldBe AuditSeverity.CRITICAL
        AuditEventTypePolicy.resolveSeverity("VOUCHER_REDEEMED") shouldBe AuditSeverity.CRITICAL
        AuditEventTypePolicy.resolveSeverity("TRANSACTION_CANCELLED") shouldBe AuditSeverity.CRITICAL
    }

    @Test
    fun `unknown event type defaults to MEDIUM`() {
        AuditEventTypePolicy.resolveSeverity("SOMETHING_ELSE") shouldBe AuditSeverity.MEDIUM
    }
}
