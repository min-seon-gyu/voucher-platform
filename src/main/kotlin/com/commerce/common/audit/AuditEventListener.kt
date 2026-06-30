package com.commerce.common.audit

import com.commerce.common.domain.DomainEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * CRITICAL 감사는 비즈니스 트랜잭션과 동일 트랜잭션(BEFORE_COMMIT)에서 동기 기록한다 —
 * 감사 실패 시 비즈니스도 함께 롤백되어 "감사 없는 금전변동"을 원천 차단한다.
 *
 * HIGH/MEDIUM(비핵심) 감사는 [OutboxRecorder]가 outbox에 캡처하고 relay(Kafka 또는 직접)가 비동기로 적용한다.
 */
@Component
class AuditEventListener(
    private val auditLogRepository: AuditLogRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun handleCriticalAudit(event: DomainEvent) {
        val severity = AuditEventTypePolicy.resolveSeverity(event.eventType)
        if (severity != AuditSeverity.CRITICAL) return

        auditLogRepository.save(AuditEventPayload.from(event, severity).toAuditLog())
        log.info(
            "Audit log saved (CRITICAL, sync): {} for {}:{}",
            event.eventType, event.aggregateType, event.aggregateId,
        )
    }
}
