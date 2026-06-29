package com.commerce.promotion.application

import com.commerce.promotion.domain.DraftDiscountType
import com.commerce.promotion.domain.PromotionDraft
import com.commerce.promotion.domain.ValidationReport
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

/**
 * 결정적 가드레일: AI 가 제안한 초안을 RegionPolicy + 예산 상한 + 날짜 유효성으로 검증한다.
 * silent-pass 금지 — 위반이 있으면 valid=false 와 모든 사유를 반환한다.
 * 프롬프트 인젝션 방어의 핵심: 이 검증을 통과하지 못한 초안은 절대 확정될 수 없다.
 */
@Component
class PromotionDraftValidator(
    private val regionPolicy: RegionPolicy,
    private val clock: Clock,
) {
    fun validate(draft: PromotionDraft): ValidationReport {
        val reasons = mutableListOf<String>()

        // 1) RegionPolicy
        if (!regionPolicy.isAllowedTarget(draft.target)) {
            reasons += "허용되지 않은 대상 지역: ${draft.target}"
        }

        // 2) 예산 상한
        if (draft.budgetCap <= BigDecimal.ZERO) {
            reasons += "예산은 0보다 커야 합니다: ${draft.budgetCap.toPlainString()}"
        } else if (draft.budgetCap > regionPolicy.maxBudget) {
            reasons += "예산이 상한(${regionPolicy.maxBudget.toPlainString()})을 초과했습니다: ${draft.budgetCap.toPlainString()}"
        }

        // 3) 할인값
        if (draft.discountValue <= BigDecimal.ZERO) {
            reasons += "할인값은 0보다 커야 합니다: ${draft.discountValue.toPlainString()}"
        } else if (draft.discountType == DraftDiscountType.PERCENTAGE && draft.discountValue > BigDecimal("100")) {
            reasons += "정률 할인은 100%를 초과할 수 없습니다: ${draft.discountValue.toPlainString()}"
        }

        // 4) 최소 결제액
        if (draft.minSpend < BigDecimal.ZERO) {
            reasons += "최소 결제액은 음수일 수 없습니다: ${draft.minSpend.toPlainString()}"
        }

        // 5) 날짜 유효성
        val today = LocalDate.now(clock)
        if (draft.validFrom.isAfter(draft.validUntil)) {
            reasons += "유효기간이 올바르지 않습니다: 시작일(${draft.validFrom})이 종료일(${draft.validUntil}) 이후입니다"
        }
        if (draft.validFrom.isBefore(today)) {
            reasons += "시작일(${draft.validFrom})이 오늘(${today}) 이전입니다"
        }

        // 6) 스택 금지
        if (draft.stackable) {
            reasons += "쿠폰 스택은 허용되지 않습니다(stackable 은 false 여야 합니다)"
        }

        return ValidationReport(valid = reasons.isEmpty(), reasons = reasons)
    }
}
