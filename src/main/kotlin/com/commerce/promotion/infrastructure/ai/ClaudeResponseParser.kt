package com.commerce.promotion.infrastructure.ai

import com.fasterxml.jackson.databind.ObjectMapper

/** Claude 응답 본문(raw JSON)을 PromotionDraft 로 파싱. 본문은 Task 3 에서 구현. */
class ClaudeResponseParser(
    private val objectMapper: ObjectMapper,
)
