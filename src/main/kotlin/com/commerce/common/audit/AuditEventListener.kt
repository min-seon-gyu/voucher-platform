package com.commerce.common.audit

import com.commerce.common.domain.DomainEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.event.TransactionPhase

@Component
class AuditEventListener(
    private val auditLogRepository: AuditLogRepository,
    private val nonCriticalWriter: NonCriticalAuditWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** CRITICAL: 비즈니스 트랜잭션과 동일 트랜잭션(BEFORE_COMMIT)에서 기록 — 감사 실패 시 함께 롤백. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun handleCriticalAudit(event: DomainEvent) {
        val severity = AuditEventTypePolicy.resolveSeverity(event.eventType)
        if (severity != AuditSeverity.CRITICAL) return

        auditLogRepository.save(buildAuditLog(event, severity))
        log.info("Audit log saved: {} [{}] for {}:{}", event.eventType, severity, event.aggregateType, event.aggregateId)
    }

    /**
     * HIGH/MEDIUM: 커밋 이후 독립 트랜잭션에서 기록. 감사 기록과 실패 적재를 **서로 다른** REQUIRES_NEW
     * 트랜잭션으로 분리해, 감사 INSERT 실패가 같은 트랜잭션을 rollback-only로 만들어 FailedEvent까지
     * 유실시키는 문제를 방지한다. 리스너 자체에는 트랜잭션을 두지 않는다(AFTER_COMMIT 컨텍스트).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handleNonCriticalAudit(event: DomainEvent) {
        val severity = AuditEventTypePolicy.resolveSeverity(event.eventType)
        if (severity == AuditSeverity.CRITICAL) return

        try {
            nonCriticalWriter.write(event, severity)
        } catch (e: Exception) {
            log.error("Failed to save audit log for event {}: {}", event.eventId, e.message)
            nonCriticalWriter.recordFailure(event, e)
        }
    }
}

/**
 * 비-CRITICAL 감사 기록/실패 적재를 각각 독립 REQUIRES_NEW 트랜잭션으로 수행하는 헬퍼.
 * 별도 빈으로 분리해야 Spring 프록시를 거쳐 트랜잭션 경계가 실제로 새로 열린다.
 */
@Component
class NonCriticalAuditWriter(
    private val auditLogRepository: AuditLogRepository,
    private val failedEventRepository: FailedEventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun write(event: DomainEvent, severity: AuditSeverity) {
        auditLogRepository.save(buildAuditLog(event, severity))
        log.info("Audit log saved: {} [{}] for {}:{}", event.eventType, severity, event.aggregateType, event.aggregateId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(event: DomainEvent, error: Exception) {
        failedEventRepository.save(
            FailedEvent(
                eventId = event.eventId,
                eventType = event.eventType,
                payload = "${event.aggregateType}:${event.aggregateId}",
                errorMessage = error.message ?: "Unknown error",
            )
        )
    }
}

private fun buildAuditLog(event: DomainEvent, severity: AuditSeverity): AuditLog =
    AuditLog(
        eventId = event.eventId,
        eventType = event.eventType,
        severity = severity,
        aggregateType = event.aggregateType,
        aggregateId = event.aggregateId,
        action = AuditEventTypePolicy.resolveAction(event.eventType),
        previousState = event.previousState,
        currentState = event.currentState,
        createdAt = event.occurredAt,
    )
