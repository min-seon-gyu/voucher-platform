package com.commerce.common.audit

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 직접 전달 relay (킬스위치 OFF/기본 또는 테스트). Kafka 없이 outbox → 감사 로그를 직접 적용한다.
 * `audit.kafka.enabled=false`(미설정 포함)일 때 활성화된다.
 */
@Component
@ConditionalOnProperty(prefix = "audit.kafka", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class DirectOutboxRelay(
    private val outboxRepository: OutboxEventRepository,
    private val applier: OutboxAuditApplier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${audit.outbox.poll-ms:1000}")
    fun poll() {
        relayOnce()
    }

    /** 미발행 outbox 이벤트를 감사 로그로 적용하고 발행 마킹. 적용 성공 건수 반환(테스트에서 수동 호출). */
    fun relayOnce(): Int {
        var applied = 0
        outboxRepository.findTop200ByPublishedFalseOrderByIdAsc().forEach { event ->
            try {
                applier.apply(event.payload)
                applier.markPublished(event.id)
                applied++
            } catch (e: Exception) {
                // 미마킹으로 남겨 다음 폴링에서 재시도(apply는 멱등). 영구 실패는 운영 모니터링 대상.
                log.warn("Direct outbox relay failed for event {} ({}): {}", event.id, event.eventType, e.message)
            }
        }
        return applied
    }
}
