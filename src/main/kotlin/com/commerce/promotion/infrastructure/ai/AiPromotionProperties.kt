package com.commerce.promotion.infrastructure.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * AI 프로모션 어시스턴트 설정. 운영 가드(토큰/비용 상한, 타임아웃, 재시도/백오프, 서킷브레이커)와
 * 결정적 가드레일 한도(허용 지역, 최대 예산)를 한곳에서 관리한다.
 * enabled=false(기본) 면 API 키 없이 부팅(킬스위치).
 */
@ConfigurationProperties(prefix = "ai.promotion")
data class AiPromotionProperties(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val baseUrl: String = "https://api.anthropic.com",
    /** 모델 id 는 빌드 시 claude-api 스킬로 확정. 후보: claude-haiku-4-5(저비용 추출), claude-opus-4-8(복잡 추론). */
    val model: String = "claude-haiku-4-5",
    val anthropicVersion: String = "2023-06-01",
    /** 요청당 최대 토큰(=비용 상한). */
    val maxTokens: Int = 1024,
    val connectTimeoutMs: Long = 2000,
    val readTimeoutMs: Long = 20000,
    /** 전송 자체 재시도 횟수(지수 백오프). */
    val maxRetries: Int = 2,
    val backoffBaseMs: Long = 200,
    /** 서킷브레이커: 연속 실패 임계치와 오픈 유지 시간. */
    val circuitFailureThreshold: Int = 5,
    val circuitOpenMs: Long = 30000,
    /** 가드레일: 허용 지역 코드. "ALL" 타깃은 항상 허용. */
    val allowedRegions: Set<String> = setOf("SN", "SU", "GN"),
    /** 가드레일: 캠페인당 최대 예산 상한. */
    val maxBudget: BigDecimal = BigDecimal("100000000"),
)
