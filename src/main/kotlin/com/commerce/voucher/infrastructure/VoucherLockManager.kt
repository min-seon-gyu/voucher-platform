package com.commerce.voucher.infrastructure

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class VoucherLockManager(
    private val redissonClient: RedissonClient,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> withVoucherLock(voucherId: Long, action: () -> T): T =
        withLock("voucher:$voucherId", action)

    fun <T> withMemberPurchaseLock(memberId: Long, action: () -> T): T =
        withLock("member:purchase:$memberId", action)

    /** 정준 락 순서 coupon → voucher 의 외측 락. 키 접두사 `coupon`은 메트릭 태그로 자동 분리된다. */
    fun <T> withCouponLock(couponId: Long, action: () -> T): T =
        withLock("coupon:$couponId", action)

    /**
     * 동일 회원의 같은 프로모션 상환을 직렬화하는 최외측 락.
     * 쿠폰별 락만으로는 한 회원이 같은 프로모션의 서로 다른 쿠폰을 동시 상환할 때 perMemberLimit이 깨지므로,
     * coupon 락보다 바깥에서 (promotion, member) 단위로 직렬화한다.
     */
    fun <T> withPromotionMemberLock(promotionId: Long, memberId: Long, action: () -> T): T =
        withLock("promotion:$promotionId:member:$memberId", action)

    private fun <T> withLock(key: String, action: () -> T): T {
        val lockType = key.substringBefore(':')

        // Redis 분산락 시도 — 실패 시 DB 비관적 락만으로 fallback
        var redisLock: org.redisson.api.RLock? = null
        try {
            val lock = redissonClient.getLock(key)
            val timer = Timer.start(meterRegistry)
            val acquired = lock.tryLock(5, 10, TimeUnit.SECONDS)
            timer.stop(meterRegistry.timer("lock.acquisition.duration", "key", lockType))

            if (!acquired) {
                meterRegistry.counter("lock.acquisition.timeout", "key", lockType).increment()
                throw BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED)
            }
            redisLock = lock
        } catch (e: BusinessException) {
            throw e // 락 타임아웃은 그대로 전파
        } catch (e: Exception) {
            // Redis 장애 — DB 비관적 락(SELECT FOR UPDATE)만으로 동시성 제어
            log.warn("Redis 분산락 획득 실패, DB 락으로 fallback: key={}, error={}", key, e.message)
            meterRegistry.counter("lock.redis.fallback", "key", lockType).increment()
        }

        try {
            return action()
        } finally {
            try {
                if (redisLock != null && redisLock.isHeldByCurrentThread) redisLock.unlock()
            } catch (e: Exception) {
                log.warn("Redis 분산락 해제 실패: key={}, error={}", key, e.message)
            }
        }
    }
}
