package com.commerce.promotion.infrastructure.ai

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.client.ClientHttpRequestFactories
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration

@Configuration
@EnableConfigurationProperties(AiPromotionProperties::class)
class AiPromotionConfig {

    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun aiClock(): Clock = Clock.systemDefaultZone()

    /** enabled=true 일 때만 실제 Claude 클라이언트를 구성한다(키 필요). */
    @Bean
    @ConditionalOnProperty(prefix = "ai.promotion", name = ["enabled"], havingValue = "true")
    fun claudeLlmClient(
        properties: AiPromotionProperties,
        objectMapper: ObjectMapper,
        meterRegistry: MeterRegistry,
    ): LlmClient {
        val settings = ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs))
            .withReadTimeout(Duration.ofMillis(properties.readTimeoutMs))
        val restClient = RestClient.builder()
            .requestFactory(ClientHttpRequestFactories.get(settings))
            .baseUrl(properties.baseUrl)
            .build()
        return ClaudeLlmClient(
            properties = properties,
            restClient = restClient,
            parser = ClaudeResponseParser(objectMapper),
            objectMapper = objectMapper,
            meterRegistry = meterRegistry,
            circuitBreaker = SimpleCircuitBreaker(
                failureThreshold = properties.circuitFailureThreshold,
                openMillis = properties.circuitOpenMs,
            ),
        )
    }

    /** enabled=false(또는 미설정) 면 킬스위치 구현을 주입 → API 키 없이 부팅(CI). */
    @Bean
    @ConditionalOnProperty(prefix = "ai.promotion", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun disabledLlmClient(): LlmClient = DisabledLlmClient()
}
