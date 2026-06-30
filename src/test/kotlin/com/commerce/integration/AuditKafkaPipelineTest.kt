package com.commerce.integration

import com.commerce.common.audit.AuditLogRepository
import com.commerce.common.audit.AuditSeverity
import com.commerce.common.audit.KafkaOutboxRelay
import com.commerce.common.audit.OutboxEventRepository
import com.commerce.member.application.MemberService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.TestPropertySource

/**
 * 감사 Outbox → Kafka → Consumer → AuditLog 전체 파이프라인 E2E.
 * @EmbeddedKafka 브로커 + audit.kafka.enabled=true 로 KafkaOutboxRelay/AuditKafkaConsumer를 활성화한다.
 * (기본 통합 테스트는 audit.kafka.enabled=false 로 DirectRelay를 쓰며 브로커가 필요 없다.)
 */
@EmbeddedKafka(partitions = 1, topics = ["audit-events"], bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = ["audit.kafka.enabled=true"])
class AuditKafkaPipelineTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var auditLogRepository: AuditLogRepository
    @Autowired lateinit var outboxRepository: OutboxEventRepository
    @Autowired lateinit var kafkaRelay: KafkaOutboxRelay

    @Test
    fun `non-critical audit flows outbox to kafka to audit log`() {
        val member = fixtures.createMember()

        memberService.suspend(member.id) // MEMBER_SUSPENDED (HIGH) → outbox (비즈니스 tx 内)

        // outbox → Kafka 발행
        kafkaRelay.relayOnce()

        // Consumer(@KafkaListener)가 비동기로 감사 로그를 기록 → 최대 20초 대기
        var audit = auditLogRepository.findByAggregateTypeAndAggregateId("MEMBER", member.id)
            .find { it.eventType == "MEMBER_SUSPENDED" }
        val deadline = System.currentTimeMillis() + 20_000
        while (audit == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            audit = auditLogRepository.findByAggregateTypeAndAggregateId("MEMBER", member.id)
                .find { it.eventType == "MEMBER_SUSPENDED" }
        }

        audit.shouldNotBeNull()
        audit.severity shouldBe AuditSeverity.HIGH

        // outbox 행은 Kafka로 발행 완료 마킹됨
        val row = outboxRepository.findAll().find { it.eventType == "MEMBER_SUSPENDED" && it.aggregateId == member.id }
        row.shouldNotBeNull()
        row.published shouldBe true
    }
}
