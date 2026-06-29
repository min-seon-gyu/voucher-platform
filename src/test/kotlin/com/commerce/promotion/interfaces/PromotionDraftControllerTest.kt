package com.commerce.promotion.interfaces

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.common.idempotency.Idempotent
import com.commerce.promotion.application.LlmDraftCommand
import com.commerce.promotion.application.PromotionDraftResult
import com.commerce.promotion.application.PromotionDraftService
import com.commerce.promotion.domain.DraftDiscountType
import com.commerce.promotion.domain.PromotionDraft
import com.commerce.promotion.domain.ValidationReport
import com.commerce.promotion.interfaces.dto.CreatePromotionDraftRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.math.BigDecimal
import java.time.LocalDate

class PromotionDraftControllerTest {

    private val service = mockk<PromotionDraftService>()
    private val controller = PromotionDraftController(service)

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun authenticateAs(memberId: Long) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(memberId, null, emptyList())
    }

    private val draft = PromotionDraft(
        "성남시 7월 할인", DraftDiscountType.PERCENTAGE, BigDecimal("10"), "SN",
        BigDecimal("50000000"), BigDecimal("10000"),
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), false,
    )

    @Test
    fun `principal 의 memberId 로 서비스에 위임하고 draft+validation 을 반환한다`() {
        authenticateAs(42L)
        every { service.draft(any(), 42L) } returns PromotionDraftResult(draft, ValidationReport(true, emptyList()))

        val response = controller.createDraft(CreatePromotionDraftRequest("성남시 10% 할인", null))

        response.draft.target shouldBe "SN"
        response.validation.valid.shouldBeTrue()
        verify(exactly = 1) { service.draft(LlmDraftCommand("성남시 10% 할인", null), 42L) }
    }

    @Test
    fun `인증 principal 이 없으면 UNAUTHORIZED 를 던진다(본문 memberId 미신뢰)`() {
        val ex = shouldThrow<BusinessException> {
            controller.createDraft(CreatePromotionDraftRequest("성남시 10% 할인", null))
        }
        ex.errorCode shouldBe ErrorCode.UNAUTHORIZED
    }

    @Test
    fun `가드레일 거부 결과는 valid=false 와 사유를 담아 200 으로 반환한다`() {
        authenticateAs(42L)
        val rejectedResult = PromotionDraftResult(
            draft,
            ValidationReport(false, listOf("허용되지 않은 지역입니다")),
        )
        every { service.draft(any(), 42L) } returns rejectedResult

        val response = controller.createDraft(CreatePromotionDraftRequest("미허용 지역 프로모션", null))

        response.validation.valid.shouldBeFalse()
        (response.validation.reasons.isNotEmpty()).shouldBeTrue()
        response.draft shouldBe draft
    }

    @Test
    fun `createDraft 는 @Idempotent 로 표시되어 있다`() {
        val method = PromotionDraftController::class.java
            .getMethod("createDraft", CreatePromotionDraftRequest::class.java)
        method.isAnnotationPresent(Idempotent::class.java).shouldBeTrue()
    }
}
