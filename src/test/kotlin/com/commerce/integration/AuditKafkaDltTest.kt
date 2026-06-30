package com.commerce.integration

import com.commerce.support.IntegrationTestSupport
import io.kotest.matchers.shouldBe
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.test.context.TestPropertySource
import java.time.Duration

/**
 * 컨슈머 apply가 실패하면(여기선 파싱 불가 페이로드) 백오프 재시도 후 Dead Letter Topic으로 적재되어
 * **무성 유실되지 않음**을 검증한다(리뷰의 at-least-once 유실 결함 수정 확인).
 */
@EmbeddedKafka(
    partitions = 1,
    topics = ["audit-events", "audit-events.DLT"],
    bootstrapServersProperty = "spring.kafka.bootstrap-servers",
)
@TestPropertySource(properties = ["audit.kafka.enabled=true"])
class AuditKafkaDltTest : IntegrationTestSupport() {

    @Autowired lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired lateinit var embeddedKafka: EmbeddedKafkaBroker

    @Test
    fun `consumer failure routes the message to the dead letter topic instead of dropping it`() {
        // apply가 실패하도록 파싱 불가 페이로드를 토픽에 직접 발행한다(JSON 아님 → readValue throw).
        kafkaTemplate.send("audit-events", "k", "this-is-not-json").get()

        val props = KafkaTestUtils.consumerProps("dlt-verify-group", "true", embeddedKafka)
        val consumer = DefaultKafkaConsumerFactory(props, StringDeserializer(), StringDeserializer()).createConsumer()
        consumer.use {
            it.subscribe(listOf("audit-events.DLT"))
            // 백오프 재시도(3×1s) 소진 후 DLT 발행 → 최대 20초 대기
            val record = KafkaTestUtils.getSingleRecord(it, "audit-events.DLT", Duration.ofSeconds(20))
            record.value() shouldBe "this-is-not-json"
        }
    }
}
