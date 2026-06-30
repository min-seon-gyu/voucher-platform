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

                    // Check region monthly limit (Redis Lua script — atomic). 성공 시 INCRBY가 적용됨.
                    checkRegionMonthlyLimit(regionId, faceValue, region.policy.monthlyIssuanceLimit)
                    regionLimitReserved = true // 이후 단계가 실패하면 보상 DECRBY 대상

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
                if (regionLimitReserved) compensateRegionMonthlyLimit(regionId, faceValue)
                throw e
            }
        }
    }

    /** 예약 이후 실패 시 지역 월 발행한도 카운터를 원복(DECRBY)한다. */
    private fun compensateRegionMonthlyLimit(regionId: Long, amount: BigDecimal) {
        try {
            val key = "region:monthly:$regionId:${YearMonth.now()}"
            redissonClient.getAtomicLong(key).addAndGet(-amount.longValueExact())
            log.info("Compensated region {} monthly counter by -{} after issuance failure", regionId, amount)
        } catch (e: Exception) {
            // 보상 실패는 매시 재동기화 잡이 상향 보정으로 수렴시키지 못할 수 있으므로 명확히 경고만 남긴다.
            log.error("Failed to compensate region {} monthly counter: {}", regionId, e.message)
        }
    }

    private fun checkRegionMonthlyLimit(regionId: Long, amount: BigDecimal, limit: BigDecimal) {
        val key = "region:monthly:$regionId:${YearMonth.now()}"
        val amountLong = amount.longValueExact()
        val limitLong = limit.longValueExact()

        // Lua 스크립트로 INCRBY + 한도 검증을 원자적으로 수행
        // 한도 초과 시 자동 롤백 후 -1 반환, 성공 시 새 합계 반환
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

        // TTL이 설정되지 않은 경우 월말 + 1일로 설정
        val counter = redissonClient.getAtomicLong(key)
        if (counter.remainTimeToLive() == -1L) {
            val endOfMonth = YearMonth.now().atEndOfMonth().plusDays(1)
            counter.expire(Duration.between(LocalDateTime.now(), endOfMonth.atStartOfDay()))
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
