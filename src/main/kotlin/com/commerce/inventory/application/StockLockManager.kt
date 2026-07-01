package com.commerce.inventory.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * SKU 재고 차감을 직렬화하는 분산락. Redisson 분산락을 1차로 시도하고, Redis 장애 시 DB 비관적 락만으로 fallback한다.
 * (voucher 결제의 검증된 락 패턴을 재고 도메인에 이식.)
 */
@Component
class StockLockManager(
    private val redissonClient: RedissonClient,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> withStockLock(skuId: Long, action: () -> T): T = withLock("stock:$skuId", action)

    /** 여러 SKU 락을 skuId 오름차순으로 중첩 획득(정준 순서 → 교차 데드락 방지). 주문처럼 다품목 차감에 사용. */
    fun <T> withStockLocks(skuIds: Collection<Long>, action: () -> T): T {
        val sorted = skuIds.distinct().sorted()
        fun acquire(i: Int): T = if (i >= sorted.size) action() else withStockLock(sorted[i]) { acquire(i + 1) }
        return acquire(0)
    }

    private fun <T> withLock(key: String, action: () -> T): T {
        val lockType = key.substringBefore(':')
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
            throw e
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
