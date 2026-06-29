package com.commerce.promotion.infrastructure.ai

/** 간이 서킷브레이커. 본문은 Task 4 에서 구현. */
class SimpleCircuitBreaker(
    private val failureThreshold: Int,
    private val openMillis: Long,
)
