package com.commerce.promotion.infrastructure.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.promotion.domain.DraftDiscountType
import com.commerce.promotion.domain.PromotionDraft
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate

/** 파싱 결과: 검증 전 구조화 초안 + 토큰 사용량(관측성용). */
data class ParsedDraft(
    val draft: PromotionDraft,
    val totalTokens: Long,
)

/**
 * Claude Messages API 응답 본문(raw JSON)을 PromotionDraft 로 결정적 파싱한다.
 * structured-output(output_config.format) 사용 시 content[0].text 가 스키마 준수 JSON 문자열이다.
 * 거부(stop_reason=refusal)/본문 부재/스키마 불일치/enum 오류는 모두
 * BusinessException(AI_DRAFT_GENERATION_FAILED) 로 변환한다(부분/오염 데이터 0).
 */
class ClaudeResponseParser(
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun parse(rawBody: String): ParsedDraft {
        val root = runCatching { objectMapper.readTree(rawBody) }
            .getOrElse { fail("응답 본문 JSON 파싱 실패", it) }

        val stopReason = root.path("stop_reason").asText("")
        if (stopReason == "refusal") {
            fail("모델이 요청을 거부했습니다(stop_reason=refusal)", null)
        }

        val text = root.path("content")
            .firstOrNull { it.path("type").asText() == "text" }
            ?.path("text")?.asText()
            ?: fail("응답에 text 콘텐츠 블록이 없습니다", null)

        val draftNode = runCatching { objectMapper.readTree(text) }
            .getOrElse { fail("구조화 출력 JSON 파싱 실패", it) }

        val draft = toDraft(draftNode)

        val usage = root.path("usage")
        val totalTokens = usage.path("input_tokens").asLong(0) + usage.path("output_tokens").asLong(0)

        return ParsedDraft(draft = draft, totalTokens = totalTokens)
    }

    private fun toDraft(n: JsonNode): PromotionDraft {
        try {
            return PromotionDraft(
                name = requireText(n, "name"),
                discountType = DraftDiscountType.valueOf(requireText(n, "discountType")),
                discountValue = requireDecimal(n, "discountValue"),
                target = requireText(n, "target"),
                budgetCap = requireDecimal(n, "budgetCap"),
                minSpend = requireDecimal(n, "minSpend"),
                validFrom = LocalDate.parse(requireText(n, "validFrom")),
                validUntil = LocalDate.parse(requireText(n, "validUntil")),
                stackable = requireField(n, "stackable").asBoolean(),
            )
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            fail("구조화 출력이 PromotionDraft 스키마와 일치하지 않습니다", e)
        }
    }

    private fun requireField(n: JsonNode, field: String): JsonNode {
        val v = n.get(field)
        if (v == null || v.isNull) fail("필수 필드 누락: $field", null)
        return v
    }

    private fun requireText(n: JsonNode, field: String): String {
        val v = requireField(n, field)
        val s = v.asText()
        if (s.isBlank()) fail("필수 문자열 필드가 비어 있습니다: $field", null)
        return s
    }

    private fun requireDecimal(n: JsonNode, field: String): BigDecimal {
        val v = requireField(n, field)
        if (!v.isNumber && !(v.isTextual && v.asText().toBigDecimalOrNull() != null)) {
            fail("숫자 필드가 아닙니다: $field", null)
        }
        return v.decimalValue() ?: BigDecimal(v.asText())
    }

    private fun fail(reason: String, cause: Throwable?): Nothing {
        log.warn("Claude 응답 파싱 실패: {}", reason, cause)
        throw BusinessException(ErrorCode.AI_DRAFT_GENERATION_FAILED)
    }
}
