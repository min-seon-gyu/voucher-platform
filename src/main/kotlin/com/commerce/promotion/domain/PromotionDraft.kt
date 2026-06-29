package com.commerce.promotion.domain

import java.math.BigDecimal
import java.time.LocalDate

/** AI 가 추출한 정률/정액 구분. Plan 2 의 Promotion 엔티티와 의도적으로 분리(초안=제안). */
enum class DraftDiscountType { FIXED, PERCENTAGE }

/**
 * AI 가 자연어에서 추출한 구조화 프로모션 초안(영속화 전 제안).
 * stackable 은 항상 false 여야 한다(MUST=단일 쿠폰, 스택 금지). 검증은 가드레일이 수행.
 */
data class PromotionDraft(
    val name: String,
    val discountType: DraftDiscountType,
    val discountValue: BigDecimal,
    val target: String,
    val budgetCap: BigDecimal,
    val minSpend: BigDecimal,
    val validFrom: LocalDate,
    val validUntil: LocalDate,
    val stackable: Boolean,
)

/** 결정적 가드레일 검증 결과. valid=false 면 reasons 는 항상 1개 이상(silent-pass 금지). */
data class ValidationReport(
    val valid: Boolean,
    val reasons: List<String>,
)
