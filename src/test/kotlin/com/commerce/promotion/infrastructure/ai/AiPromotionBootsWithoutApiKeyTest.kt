package com.commerce.promotion.infrastructure.ai

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.promotion.application.LlmDraftCommand
import com.commerce.support.IntegrationTestSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/**
 * CI/킬스위치 시나리오: ANTHROPIC_API_KEY 없이, ai.promotion.enabled=false 로 부팅된다.
 * 컨텍스트가 정상 로드되고, LlmClient 빈은 DisabledLlmClient 로 대체되며,
 * 호출 시 결정적 에러(AI_DRAFT_UNAVAILABLE)를 던진다(부분/오염 데이터 0).
 * Docker 데몬 필요.
 */
@TestPropertySource(properties = ["ai.promotion.enabled=false", "ai.promotion.api-key="])
class AiPromotionBootsWithoutApiKeyTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var llmClient: LlmClient

    @Test
    fun `킬스위치가 꺼져 있으면 DisabledLlmClient 가 주입된다`() {
        llmClient.shouldBeInstanceOf<DisabledLlmClient>()
    }

    @Test
    fun `비활성 상태에서 호출하면 AI_DRAFT_UNAVAILABLE 를 던진다`() {
        val ex = shouldThrow<BusinessException> {
            llmClient.generateDraft(LlmDraftCommand(prompt = "성남시 10% 할인", context = null))
        }
        ex.errorCode shouldBe ErrorCode.AI_DRAFT_UNAVAILABLE
    }
}
