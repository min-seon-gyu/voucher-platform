package com.commerce.promotion.application

import com.commerce.promotion.domain.DraftDiscountType
import com.commerce.promotion.domain.PromotionDraft
import com.commerce.promotion.infrastructure.ai.AiPromotionProperties
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PromotionDraftValidatorTest {

    // 고정 시계: 오늘 = 2026-06-29
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneId.of("UTC"))
    private val props = AiPromotionProperties(
        allowedRegions = setOf("SN", "SU"),
        maxBudget = BigDecimal("100000000"),
    )
    private val validator = PromotionDraftValidator(RegionPolicy(props), clock)

    private fun validDraft() = PromotionDraft(
        name = "성남시 6월 할인",
        discountType = DraftDiscountType.PERCENTAGE,
        discountValue = BigDecimal("10"),
        target = "SN",
        budgetCap = BigDecimal("50000000"),
        minSpend = BigDecimal("10000"),
        validFrom = LocalDate.of(2026, 7, 1),
        validUntil = LocalDate.of(2026, 7, 31),
        stackable = false,
    )

    @Test
    fun `정상 초안은 통과한다`() {
        val report = validator.validate(validDraft())
        report.valid.shouldBeTrue()
        report.reasons.shouldBeEmpty()
    }

    @Test
    fun `허용되지 않은 지역은 거부된다`() {
        val report = validator.validate(validDraft().copy(target = "XX"))
        report.valid.shouldBeFalse()
        report.reasons shouldContain "허용되지 않은 대상 지역: XX"
    }

    @Test
    fun `ALL 타깃은 항상 허용된다`() {
        val report = validator.validate(validDraft().copy(target = "ALL"))
        report.valid.shouldBeTrue()
        report.reasons.shouldBeEmpty()
    }

    @Test
    fun `예산 상한 초과는 거부된다`() {
        val report = validator.validate(validDraft().copy(budgetCap = BigDecimal("200000000")))
        report.valid.shouldBeFalse()
        report.reasons shouldContain "예산이 상한(100000000)을 초과했습니다: 200000000"
    }

    @Test
    fun `시작일이 종료일보다 늦으면 거부된다`() {
        val report = validator.validate(
            validDraft().copy(validFrom = LocalDate.of(2026, 8, 1), validUntil = LocalDate.of(2026, 7, 1)),
        )
        report.valid.shouldBeFalse()
        report.reasons shouldContain "유효기간이 올바르지 않습니다: 시작일(2026-08-01)이 종료일(2026-07-01) 이후입니다"
    }

    @Test
    fun `시작일이 과거이면 거부된다`() {
        val report = validator.validate(validDraft().copy(validFrom = LocalDate.of(2026, 6, 1)))
        report.valid.shouldBeFalse()
        report.reasons shouldContain "시작일(2026-06-01)이 오늘(2026-06-29) 이전입니다"
    }

    @Test
    fun `0 할인은 거부된다`() {
        val report = validator.validate(validDraft().copy(discountValue = BigDecimal.ZERO))
        report.valid.shouldBeFalse()
        report.reasons shouldContain "할인값은 0보다 커야 합니다: 0"
    }

    @Test
    fun `음수 할인은 거부된다`() {
        val report = validator.validate(validDraft().copy(discountValue = BigDecimal("-5")))
        report.valid.shouldBeFalse()
        report.reasons shouldContain "할인값은 0보다 커야 합니다: -5"
    }

    @Test
    fun `예산 상한이 0이면 거부된다`() {
        val report = validator.validate(validDraft().copy(budgetCap = BigDecimal.ZERO))
        report.valid.shouldBeFalse()
        report.reasons shouldContain "예산은 0보다 커야 합니다: 0"
    }

    @Test
    fun `예산 상한이 음수이면 거부된다`() {
        val report = validator.validate(validDraft().copy(budgetCap = BigDecimal("-1000")))
        report.valid.shouldBeFalse()
        report.reasons shouldContain "예산은 0보다 커야 합니다: -1000"
    }

    @Test
    fun `최소 결제액이 음수이면 거부된다`() {
        val report = validator.validate(validDraft().copy(minSpend = BigDecimal("-1")))
        report.valid.shouldBeFalse()
        report.reasons shouldContain "최소 결제액은 음수일 수 없습니다: -1"
    }

    @Test
    fun `정률 할인 100 초과는 거부된다`() {
        val report = validator.validate(validDraft().copy(discountValue = BigDecimal("150")))
        report.valid.shouldBeFalse()
        report.reasons shouldContain "정률 할인은 100%를 초과할 수 없습니다: 150"
    }

    @Test
    fun `stackable true 는 거부된다(스택 금지)`() {
        val report = validator.validate(validDraft().copy(stackable = true))
        report.valid.shouldBeFalse()
        report.reasons shouldContain "쿠폰 스택은 허용되지 않습니다(stackable 은 false 여야 합니다)"
    }

    @Test
    fun `여러 위반은 모두 사유로 누적된다`() {
        val report = validator.validate(
            validDraft().copy(target = "XX", budgetCap = BigDecimal("999999999"), stackable = true),
        )
        report.valid.shouldBeFalse()
        report.reasons shouldHaveAtLeastSize 3
    }
}
