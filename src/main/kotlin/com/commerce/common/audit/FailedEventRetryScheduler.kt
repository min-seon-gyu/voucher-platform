package com.commerce.common.audit

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * AFTER_COMMIT 감사 기록 실패로 `failed_events`에 적재된 이벤트를 주기적으로 재처리한다.
 * 재시도 성공 시 감사 로그를 복원하고 resolved=true로 마킹하며, 실패 시 retryCount를 증가시킨다.
 * retryCount가 [MAX_RETRY] 이상이면 자동 재시도 대상에서 제외된다(운영자 개입 영역).
 */
@Component
class FailedEventRetryScheduler(
    private val failedEventRepository: FailedEventRepository,
    private val retryProcessor: FailedEventRetryProcessor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 5분마다 미해결 실패 이벤트 재처리 */
    @Scheduled(cron = "0 */5 * * * *")
    fun retryFailedEvents() {
        val pending = failedEventRepository.findByResolvedFalseAndRetryCountLessThan(MAX_RETRY)
        if (pending.isEmpty()) return
        log.info("Retrying {} failed audit event(s)", pending.size)
        pending.forEach { fe ->
            // 감사 복원은 건별 독립 트랜잭션(REQUIRES_NEW). 복원 실패 시 별도 트랜잭션에서 retryCount만 증가시켜
            // 한 건의 실패가 다른 건이나 retryCount 증가까지 롤백시키지 않도록 격리한다.
            try {
                retryProcessor.retryOne(fe.id)
            } catch (e: Exception) {
                log.warn("Audit retry failed for failed-event id={} ({}): {}", fe.id, fe.eventType, e.message)
                runCatching { retryProcessor.recordRetryFailure(fe.id) }
            }
        }
    }

    companion object {
        const val MAX_RETRY = 5
    }
}

@Service
class FailedEventRetryProcessor(
    private val failedEventRepository: FailedEventRepository,
    private val auditLogRepository: AuditLogRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 단일 실패 이벤트를 감사 로그로 복원한다. 복원에 실패하면 예외를 전파하여
     * 이 REQUIRES_NEW 트랜잭션 전체를 롤백시킨다(retryCount 증가는 호출자가 별도 처리).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun retryOne(failedEventId: Long) {
        val fe = failedEventRepository.findById(failedEventId).orElse(null) ?: return
        if (fe.resolved) return

        // 멱등: 같은 eventId 감사 로그가 이미 존재하면 중복 저장하지 않고 해결 처리한다.
        if (!auditLogRepository.existsByEventId(fe.eventId)) {
            auditLogRepository.save(reconstruct(fe))
        }
        fe.resolved = true
        failedEventRepository.save(fe)
        log.info("Failed audit event resolved on retry: {} ({})", fe.eventId, fe.eventType)
    }

    /** 복원 실패 시 retryCount만 증가시키는 독립 트랜잭션. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordRetryFailure(failedEventId: Long) {
        val fe = failedEventRepository.findById(failedEventId).orElse(null) ?: return
        fe.retryCount += 1
        failedEventRepository.save(fe)
    }

    /** FailedEvent.payload("aggregateType:aggregateId")로부터 감사 로그를 복원한다. */
    private fun reconstruct(fe: FailedEvent): AuditLog {
        val parts = fe.payload.split(":", limit = 2)
        val aggregateType = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
        val aggregateId = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return AuditLog(
            eventId = fe.eventId,
            eventType = fe.eventType,
            severity = AuditEventTypePolicy.resolveSeverity(fe.eventType),
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            action = AuditEventTypePolicy.resolveAction(fe.eventType),
            createdAt = fe.createdAt,
        )
    }
}
