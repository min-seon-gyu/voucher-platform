package com.commerce.promotion.infrastructure.ai

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.promotion.application.LlmDraftCommand
import com.commerce.promotion.domain.PromotionDraft

/** 킬스위치: ai.promotion.enabled=false (또는 미설정) 일 때 주입. API 키 없이 부팅 가능. */
class DisabledLlmClient : LlmClient {
    override fun generateDraft(command: LlmDraftCommand): PromotionDraft {
        throw BusinessException(ErrorCode.AI_DRAFT_UNAVAILABLE)
    }
}
