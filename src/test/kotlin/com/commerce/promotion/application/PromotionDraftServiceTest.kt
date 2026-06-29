package com.commerce.promotion.application

import com.commerce.promotion.domain.DraftDiscountType
import com.commerce.promotion.domain.PromotionDraft
import com.commerce.promotion.domain.ValidationReport
import com.commerce.promotion.infrastructure.ai.AiPromotionProperties
import com.commerce.promotion.infrastructure.ai.LlmClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 라이브 호출 없음: LlmClient 를 MockK 로 모킹하고 서비스가 클라이언트 호출 + 가드레일 검증을 수행하는지 검증. */
class PromotionDraftServiceTest {

    private val llmClient = mockk<LlmClient>()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneId.of("UTC"))
    private val validator = PromotionDraftValidator(
        RegionPolicy(AiPromotionProperties(allowedRegions = setOf("SN"), maxBudget = BigDecimal("100000000"))),
        clock,
    )
    private val meterRegistry = SimpleMeterRegistry()
    private val service = PromotionDraftService(llmClient, validator, meterRegistry)

    private fun draft(target: String = "SN", budget: BigDecimal = BigDecimal("50000000")) = PromotionDraft(
        name = "성남시 7월 할인",
        discountType = DraftDiscountType.PERCENTAGE,
        discountValue = BigDecimal("10"),
        target = target,
        budgetCap = budget,
        minSpend = BigDecimal("10000"),
        validFrom = LocalDate.of(2026, 7, 1),
        validUntil = LocalDate.of(2026, 7, 31),
        stackable = false,
    )

    @Test
    fun `LLM 클라이언트를 호출하고 가드레일 검증 결과를 함께 반환한다`() {
        every { llmClient.generateDraft(any()) } returns draft()

        val result = service.draft(LlmDraftCommand("성남시 10% 할인", null), requesterMemberId = 7L)

        result.draft shouldBe draft()
        result.validation.valid.shouldBeTrue()
        verify(exactly = 1) { llmClient.generateDraft(LlmDraftCommand("성남시 10% 할인", null)) }
    }

    @Test
    fun `가드레일 위반 초안은 valid=false 와 사유를 담아 반환한다(예외 아님)`() {
        every { llmClient.generateDraft(any()) } returns draft(target = "XX")

        val result = service.draft(LlmDraftCommand("미허용 지역", null), requesterMemberId = 7L)

        result.validation.valid.shouldBeFalse()
        (result.validation.reasons.isNotEmpty()).shouldBeTrue()
    }

    @Test
    fun `LLM 클라이언트가 예외를 던지면 예외가 전파되고 메트릭은 기록되지 않는다`() {
        every { llmClient.generateDraft(any()) } throws RuntimeException("timeout")

        shouldThrow<RuntimeException> {
            service.draft(LlmDraftCommand("성남시 10% 할인", null), requesterMemberId = 7L)
        }

        // counter는 increment()가 호출되지 않았으므로 null 이어야 한다
        meterRegistry.find("ai.promotion.draft.count").counter() shouldBe null
    }
}
