package com.commerce.common.audit

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * 감사 이벤트 토픽 컨슈머. outbox relay가 발행한 페이로드를 받아 감사 로그를 멱등하게 기록한다.
 * 별도 컨슈머 그룹/서비스로 분리·확장 가능(알림·분석 컨슈머 추가 시 같은 토픽 fan-out).
 */
@Component
@ConditionalOnProperty(prefix = "audit.kafka", name = ["enabled"], havingValue = "true")
class AuditKafkaConsumer(
    private val applier: OutboxAuditApplier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${audit.kafka.topic:audit-events}"],
        groupId = "\${audit.kafka.group:audit-log-writers}",
    )
    fun consume(payloadJson: String) {
        try {
            applier.apply(payloadJson)
        } catch (e: Exception) {
            // 예외 전파 → AuditKafkaConfig의 DefaultErrorHandler가 백오프 재시도 후 audit-events.DLT로 적재(무성 유실 방지).
            // apply는 멱등(existsByEventId)이라 재시도/재처리가 안전하다.
            log.error("Audit consumer failed to apply payload (will retry/DLT): {}", e.message)
            throw e
        }
    }
}
