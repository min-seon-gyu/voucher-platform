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
    private val failedEventRepository: FailedEventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun handleCriticalAudit(event: DomainEvent) {
        val severity = AuditEventTypePolicy.resolveSeverity(event.eventType)
        if (severity != AuditSeverity.CRITICAL) return

        saveAuditLog(event, severity)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handleNonCriticalAudit(event: DomainEvent) {
        val severity = AuditEventTypePolicy.resolveSeverity(event.eventType)
        if (severity == AuditSeverity.CRITICAL) return

        try {
            saveAuditLog(event, severity)
        } catch (e: Exception) {
            log.error("Failed to save audit log for event {}: {}", event.eventId, e.message)
            saveFailedEvent(event, e)
        }
    }

    private fun saveAuditLog(event: DomainEvent, severity: AuditSeverity) {
        val auditLog = AuditLog(
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
        auditLogRepository.save(auditLog)
        log.info("Audit log saved: {} [{}] for {}:{}", event.eventType, severity, event.aggregateType, event.aggregateId)
    }

    private fun saveFailedEvent(event: DomainEvent, error: Exception) {
        try {
            failedEventRepository.save(
                FailedEvent(
                    eventId = event.eventId,
                    eventType = event.eventType,
                    payload = "${event.aggregateType}:${event.aggregateId}",
                    errorMessage = error.message ?: "Unknown error",
                )
            )
        } catch (e: Exception) {
            log.error("Failed to save failed event record: {}", e.message)
        }
    }

}
