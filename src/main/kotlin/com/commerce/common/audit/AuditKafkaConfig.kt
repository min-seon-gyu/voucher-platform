package com.commerce.common.audit

import org.apache.kafka.common.TopicPartition
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * 감사 컨슈머의 에러 처리. apply 실패 시 백오프 재시도로 일시적 오류(DB 순단 등)를 흡수하고,
 * 소진되면 Dead Letter Topic(`audit-events.DLT`)으로 적재해 **무성 유실을 차단**한다.
 *
 * relay는 Kafka send ACK 시점에 outbox를 published로 마킹(=브로커에 안착)하므로, 컨슈머 측 전달 보장은
 * 이 DLT가 안전망이 된다(기존 DefaultErrorHandler의 0ms·10회 후 폐기로 인한 유실 제거).
 * Spring Boot가 단일 CommonErrorHandler 빈을 리스너 컨테이너 팩토리에 자동 연결한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "audit.kafka", name = ["enabled"], havingValue = "true")
class AuditKafkaConfig {

    @Bean
    fun auditKafkaErrorHandler(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler {
        // 실패 레코드를 <원본토픽>.DLT 의 0번 파티션으로 발행(DLT 파티션 수 불일치 회피).
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
            TopicPartition("${record.topic()}.DLT", 0)
        }
        // 1초 간격 3회 재시도 후 DLT — 일시적 오류는 흡수, 영구 오류는 복구 가능하게 보존.
        return DefaultErrorHandler(recoverer, FixedBackOff(1000L, 3L))
    }
}
