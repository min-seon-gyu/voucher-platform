package com.commerce.promotion.infrastructure.ai

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.promotion.application.LlmDraftCommand
import com.commerce.promotion.domain.PromotionDraft
import org.springframework.web.client.RestClient

/** Claude Messages API 클라이언트. 본문은 Task 4 에서 구현. */
class ClaudeLlmClient(
    private val properties: AiPromotionProperties,
    private val restClient: RestClient,
    private val parser: ClaudeResponseParser,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val circuitBreaker: SimpleCircuitBreaker,
) : LlmClient {
    override fun generateDraft(command: LlmDraftCommand): PromotionDraft {
        throw BusinessException(ErrorCode.AI_DRAFT_GENERATION_FAILED)
    }
}
