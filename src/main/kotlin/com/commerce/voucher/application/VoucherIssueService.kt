package com.commerce.voucher.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.member.infrastructure.MemberJpaRepository
import com.commerce.region.application.RegionService
import com.commerce.region.domain.RegionStatus
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionType
import com.commerce.voucher.domain.Voucher
import com.commerce.voucher.domain.VoucherCodeGenerator
import com.commerce.voucher.domain.event.VoucherIssuedEvent
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import com.commerce.voucher.infrastructure.VoucherLockManager
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.YearMonth

@Service
class VoucherIssueService(
    private val voucherRepository: VoucherJpaRepository,
    private val memberRepository: MemberJpaRepository,
    private val lockManager: VoucherLockManager,
    private val regionService: RegionService,
    private val codeGenerator: VoucherCodeGenerator,
    private val ledgerService: LedgerService,
    private val transactionService: TransactionService,
    private val redissonClient: RedissonClient,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 상품권 발행.
     * 분산락을 트랜잭션 밖에서 잡아 락 해제 전 커밋을 보장한다.
     * (락 해제 ~ 커밋 사이 다른 스레드가 커밋 전 데이터를 읽는 문제 방지)
     *
     * 지역 월 발행한도 카운터는 DB 트랜잭션보다 먼저 Redis에서 원자 예약(INCRBY)되므로,
     * 이후 트랜잭션이 롤백되면 보상되지 않은 채 카운터가 남아 과대집계(거짓 한도초과)를 유발한다.
     * 따라서 예약 이후 실패하면 명시적으로 보상 DECRBY 하여 누수를 차단한다.
     */
    fun issue(memberId: Long, regionId: Long, faceValue: BigDecimal): Voucher {
        return lockManager.withMemberPurchaseLock(memberId) {
            // 예약/보상 키는 같은 달로 고정한다(월 경계 트랜잭션이 다른 달 카운터를 건드리지 않도록).
            val reservedMonth = YearMonth.now()
            val counterKey = regionMonthlyKey(regionId, reservedMonth)
            var regionLimitReserved = false
            try {
                transactionTemplate.execute { _ ->
                    // DB 비관적 락(이중 방어): Redis 분산락 장애 시에도 동일 회원 구매를 직렬화한다.
                    memberRepository.findByIdForUpdate(memberId)
                        ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)

                    val region = regionService.getById(regionId)

                    if (region.status != RegionStatus.ACTIVE)
                        throw BusinessException(ErrorCode.REGION_NOT_ACTIVE)

                    // Check member purchase limit (DB 락 보유 상태에서 조회 → race condition 방지)
                    val totalPurchased = voucherRepository.sumFaceValueByMemberAndRegion(memberId, regionId)
                    if (totalPurchased + faceValue > region.policy.purchaseLimitPerPerson)
                        throw BusinessException(ErrorCode.MEMBER_PURCHASE_LIMIT_EXCEEDED)

                    // 지역 월 한도 원자 예약(INCRBY + 한도 검증). 정상 반환 = 예약 성공 → 즉시 보상 대상으로 표시.
                    reserveRegionMonthlyLimit(counterKey, faceValue, region.policy.monthlyIssuanceLimit)
                    regionLimitReserved = true
                    // TTL은 best-effort: 실패해도 예약 추적엔 영향 없음(이미 reserved 표시됨).
                    ensureRegionCounterTtl(counterKey, reservedMonth)

                    // Generate voucher code
                    val code = codeGenerator.generate(region.regionCode)

                    // Create voucher (ACTIVE)
                    val voucher = voucherRepository.save(
                        Voucher(
                            voucherCode = code,
                            faceValue = faceValue,
                            balance = faceValue,
                            memberId = memberId,
                            regionId = regionId,
                            purchasedAt = LocalDateTime.now(),
                            expiresAt = LocalDateTime.now().plusMonths(6),
                        )
                    )

                    // Create transaction + ledger (synchronous, same DB tx)
                    val tx = transactionService.create(
                        type = TransactionType.PURCHASE,
                        amount = faceValue,
                        voucherId = voucher.id,
                        memberId = memberId,
                    )
                    ledgerService.record(
                        debitAccount = AccountCode.VOUCHER_BALANCE,
                        creditAccount = AccountCode.MEMBER_CASH,
                        amount = faceValue,
                        transactionId = tx.id,
                        entryType = LedgerEntryType.PURCHASE,
                    )
                    tx.complete()

                    // Publish event (audit log)
                    eventPublisher.publishEvent(
                        VoucherIssuedEvent(voucher.id, memberId, regionId, faceValue)
                    )

                    voucher
                }!!
            } catch (e: Throwable) {
                // 트랜잭션 롤백 시 INCRBY는 자동 보상되지 않으므로 명시적으로 DECRBY 하여 카운터 누수 차단.
                // 예약 시점에 고정한 counterKey를 사용해 월 경계에서도 같은 달 카운터를 보상한다.
                if (regionLimitReserved) compensateRegionMonthlyLimit(counterKey, faceValue)
                throw e
            }
        }
    }

    private fun regionMonthlyKey(regionId: Long, month: YearMonth): String = "region:monthly:$regionId:$month"

    /**
     * 지역 월 발행한도를 Redis Lua로 원자 예약한다(INCRBY + 한도 검증). 한도 초과 시 -1 반환 → 예외.
     * 정상 반환되면 INCRBY가 확정 적용된 상태이며, 이 메서드는 그 외 부수효과(TTL 등)를 하지 않는다.
     * (TTL을 분리해, 예약 성공과 보상 플래그 설정 사이에 예외 가능 지점이 없도록 한다.)
     */
    private fun reserveRegionMonthlyLimit(key: String, amount: BigDecimal, limit: BigDecimal) {
        val amountLong = amount.longValueExact()
        val limitLong = limit.longValueExact()

        // StringCodec: ARGV를 평문 문자열로 인코딩해야 Redis INCRBY가 정수로 해석 가능
        // (기본 바이너리 코덱은 Long을 바이너리로 직렬화해 "value is not an integer" 유발)
        val result = redissonClient.getScript(StringCodec.INSTANCE).eval<Long>(
            RScript.Mode.READ_WRITE,
            MONTHLY_LIMIT_CHECK_SCRIPT,
            RScript.ReturnType.INTEGER,
            listOf(key),
            amountLong.toString(), limitLong.toString(),
        )

        if (result == -1L) {
            throw BusinessException(ErrorCode.REGION_MONTHLY_LIMIT_EXCEEDED)
        }
    }

    /** 카운터에 TTL이 없으면 해당 월말 + 1일로 설정한다(best-effort — 실패해도 예약/보상에 영향 없음). */
    private fun ensureRegionCounterTtl(key: String, month: YearMonth) {
        try {
            val counter = redissonClient.getAtomicLong(key)
            if (counter.remainTimeToLive() == -1L) {
                val expireAt = month.atEndOfMonth().plusDays(1).atStartOfDay()
                val ttl = Duration.between(LocalDateTime.now(), expireAt)
                if (!ttl.isNegative && !ttl.isZero) counter.expire(ttl)
            }
        } catch (e: Exception) {
            log.warn("Failed to set TTL on region counter {}: {}", key, e.message)
        }
    }

    /** 예약 이후 실패 시 지역 월 발행한도 카운터를 원복(DECRBY)한다(예약 시점 키 재사용). */
    private fun compensateRegionMonthlyLimit(key: String, amount: BigDecimal) {
        try {
            redissonClient.getAtomicLong(key).addAndGet(-amount.longValueExact())
            log.info("Compensated region counter {} by -{} after issuance failure", key, amount)
        } catch (e: Exception) {
            // 보상 실패는 매시 재동기화 잡이 상향 보정으로 수렴시키지 못할 수 있으므로 명확히 경고만 남긴다.
            log.error("Failed to compensate region counter {}: {}", key, e.message)
        }
    }

    companion object {
        /**
         * Redis Lua 스크립트: 원자적 한도 검증
         * KEYS[1] = 카운터 키, ARGV[1] = 증가량, ARGV[2] = 한도
         * 반환: 성공 시 새 합계, 한도 초과 시 -1
         */
        private const val MONTHLY_LIMIT_CHECK_SCRIPT = """
            local current = redis.call('INCRBY', KEYS[1], ARGV[1])
            if current > tonumber(ARGV[2]) then
                redis.call('DECRBY', KEYS[1], ARGV[1])
                return -1
            end
            return current
        """
    }
}
