package com.commerce.common.audit

import com.commerce.common.domain.DomainEvent
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDateTime

/**
 * outbox/Kafka로 흐르는 감사 이벤트의 자기완결적 페이로드.
 * 감사 로그 복원에 필요한 모든 상태(previousState/currentState 포함)를 담아,
 * relay/consumer가 원본 이벤트 객체 없이도 AuditLog를 재구성할 수 있게 한다.
 */
data class AuditEventPayload @JsonCreator constructor(
    @JsonProperty("eventId") val eventId: String,
    @JsonProperty("eventType") val eventType: String,
    @JsonProperty("severity") val severity: AuditSeverity,
    @JsonProperty("aggregateType") val aggregateType: String,
    @JsonProperty("aggregateId") val aggregateId: Long,
    @JsonProperty("action") val action: String,
    @JsonProperty("previousState") val previousState: String?,
    @JsonProperty("currentState") val currentState: String?,
    @JsonProperty("occurredAt") val occurredAt: LocalDateTime,
) {
    fun toAuditLog(): AuditLog = AuditLog(
        eventId = eventId,
        eventType = eventType,
        severity = severity,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        action = action,
        previousState = previousState,
        currentState = currentState,
        createdAt = occurredAt,
    )

    companion object {
        fun from(event: DomainEvent, severity: AuditSeverity): AuditEventPayload = AuditEventPayload(
            eventId = event.eventId,
            eventType = event.eventType,
            severity = severity,
            aggregateType = event.aggregateType,
            aggregateId = event.aggregateId,
            action = AuditEventTypePolicy.resolveAction(event.eventType),
            previousState = event.previousState,
            currentState = event.currentState,
            occurredAt = event.occurredAt,
        )
    }
}
