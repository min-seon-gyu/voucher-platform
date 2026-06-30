package com.commerce.integration

import com.commerce.common.audit.AuditLogRepository
import com.commerce.common.audit.AuditSeverity
import com.commerce.common.audit.FailedEvent
import com.commerce.common.audit.FailedEventRepository
import com.commerce.common.audit.FailedEventRetryScheduler
import com.commerce.support.IntegrationTestSupport
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * AFTER_COMMIT 감사 기록 실패로 failed_events에 적재된 이벤트가 스케줄러에 의해
 * 실제로 재처리(감사 로그 복원 + resolved 마킹)되는지 검증한다.
 * (기존: 재처리 스케줄러가 배선되지 않아 실패 이벤트가 영구 누적)
 */
class FailedEventRetrySchedulerTest : IntegrationTestSupport() {

    @Autowired lateinit var failedEventRepository: FailedEventRepository
    @Autowired lateinit var auditLogRepository: AuditLogRepository
    @Autowired lateinit var scheduler: FailedEventRetryScheduler

    @Test
    fun `retry restores audit log with correct severity and marks failed event resolved`() {
        val eventId = UUID.randomUUID().toString()
        val saved = failedEventRepository.save(
            FailedEvent(
                eventId = eventId,
                eventType = "MEMBER_SUSPENDED",
                payload = "MEMBER:777",
                errorMessage = "db down during audit",
            )
        )

        scheduler.retryFailedEvents()

        val audit = auditLogRepository.findByEventType("MEMBER_SUSPENDED").find { it.eventId == eventId }
        audit.shouldNotBeNull()
        audit.severity shouldBe AuditSeverity.HIGH
        audit.aggregateType shouldBe "MEMBER"
        audit.aggregateId shouldBe 777L

        failedEventRepository.findById(saved.id).get().resolved shouldBe true
    }

    @Test
    fun `retry is idempotent and does not duplicate audit logs`() {
        val eventId = UUID.randomUUID().toString()
        failedEventRepository.save(
            FailedEvent(
                eventId = eventId,
                eventType = "REGION_POLICY_CHANGED",
                payload = "REGION:5",
                errorMessage = "x",
            )
        )

        scheduler.retryFailedEvents()
        scheduler.retryFailedEvents()

        auditLogRepository.findByEventType("REGION_POLICY_CHANGED").count { it.eventId == eventId } shouldBe 1
    }
}
