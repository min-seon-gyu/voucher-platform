package com.commerce.promotion.application

import com.commerce.promotion.infrastructure.ai.AiPromotionProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 결정적 지역/예산 정책. AI 출력이 영속화되기 전 반드시 통과해야 하는 서버측 가드레일의 일부.
 * 허용 지역과 예산 상한은 설정(AiPromotionProperties)에서 가져온다.
 */
@Component
class RegionPolicy(
    private val properties: AiPromotionProperties,
) {
    val maxBudget: BigDecimal get() = properties.maxBudget

    /** "ALL" 은 전역 캠페인으로 항상 허용. 그 외에는 허용 지역 목록에 있어야 한다. */
    fun isAllowedTarget(target: String): Boolean =
        target == "ALL" || target in properties.allowedRegions
}
