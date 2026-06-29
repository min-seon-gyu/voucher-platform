package com.commerce.promotion.application

import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
import com.commerce.promotion.infrastructure.PromotionJpaRepository
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Redis 프로모션 예산 카운터를 DB(CouponRedemption 합계) 기준으로 재동기화.
 * Redis 재시작/보상 누락으로 카운터가 어긋나도 매시 정각에 DB 진실원천으로 복구한다.
 * (RegionCounterSyncScheduler와 동형: voucher 합계 → region 카운터 보정 패턴 미러)
 */
@Component
class PromotionBudgetSyncScheduler(
    private val promotionRepository: PromotionJpaRepository,
    private val couponRedemptionRepository: CouponRedemptionJpaRepository,
    private val redissonClient: RedissonClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 * * * *")
    fun syncPromotionBudgets() {
        promotionRepository.findAll().forEach { promotion ->
            try {
                val key = "promotion:budget:${promotion.id}"
                val dbConsumed = couponRedemptionRepository.sumActiveDiscountByPromotion(promotion.id)
                redissonClient.getAtomicLong(key).set(dbConsumed.longValueExact())
                log.debug("Promotion {} budget synced: {}", promotion.id, dbConsumed)
            } catch (e: Exception) {
                log.error("Failed to sync promotion {} budget: {}", promotion.id, e.message)
            }
        }
    }
}
