package com.commerce.promotion.infrastructure.ai

import com.commerce.promotion.application.LlmDraftCommand
import com.commerce.promotion.domain.PromotionDraft

/**
 * LLM 추상화. 구현체는 자연어를 스키마 강제 구조화 출력으로 변환해 PromotionDraft 를 반환한다.
 * 가드레일 검증은 호출 측(PromotionDraftService)이 별도로 수행한다.
 * LLM 불가/스키마 불일치/거부 시 BusinessException(AI_DRAFT_GENERATION_FAILED) 또는
 * 비활성 시 BusinessException(AI_DRAFT_UNAVAILABLE) 를 던진다(부분/오염 데이터 0).
 */
interface LlmClient {
    fun generateDraft(command: LlmDraftCommand): PromotionDraft
}
