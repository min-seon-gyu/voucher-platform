package com.commerce.promotion.application

/** LLM 초안 생성 요청. prompt=운영자 자연어, context=선택적 추가 컨텍스트(지역/기간 힌트 등). */
data class LlmDraftCommand(
    val prompt: String,
    val context: String?,
)
