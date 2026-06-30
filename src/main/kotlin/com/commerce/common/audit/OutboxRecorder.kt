package com.commerce.common.audit

import com.commerce.common.domain.DomainEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 비핵심(HIGH/MEDIUM) 도메인 이벤트를 비즈니스 트랜잭션과 같은 tx(BEFORE_COMMIT)에서 outbox에 기록한다.
 * 같은 tx 내 INSERT이므로 "비즈니스 커밋 ⇔ 이벤트 캡처"가 원자적이다(AFTER_COMMIT 발행 유실 문제 제거).
 * CRITICAL은 AuditEventListener가 동기로 직접 감사 기록하므로 outbox로 보내지 않는다.
 */
@Component
class OutboxRecorder(
    private val outboxRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun record(event: DomainEvent) {
        val severity = AuditEventTypePolicy.resolveSeverity(event.eventType)
        if (severity == AuditSeverity.CRITICAL) return

        val payload = AuditEventPayload.from(event, severity)
        outboxRepository.save(
            OutboxEvent(
                eventId = event.eventId,
                eventType = event.eventType,
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                severity = severity,
                payload = objectMapper.writeValueAsString(payload),
            )
        )
    }
}
