package com.commerce.integration

import com.commerce.ledger.application.LedgerService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.voucher.application.VoucherIssueService
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doThrow
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.MockBean
import java.math.BigDecimal
import java.time.YearMonth

/**
 * 발행이 INCRBY(지역 월 한도 예약) 이후 단계에서 실패하면 카운터가 보상 DECRBY로 원복되는지 검증한다.
 * 보상이 없으면 롤백된 발행분이 카운터에 영구히 남아 과대집계(거짓 한도초과)를 유발한다.
 *
 * ledgerService.record()를 강제로 throw시켜 INCRBY 이후 DB 트랜잭션을 실패시킨다.
 */
class RegionCounterCompensationTest : IntegrationTestSupport() {

    /** Kotlin + Mockito 5 null-safety 우회 (mockito-kotlin 미사용) — PointEarnRollbackTest와 동일 패턴. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyMatcher(): T = org.mockito.ArgumentMatchers.any() as T

    @MockBean lateinit var ledgerService: LedgerService

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var issueService: VoucherIssueService
    @Autowired lateinit var redissonClient: RedissonClient
    @Autowired lateinit var voucherRepository: VoucherJpaRepository

    @Test
    fun `region monthly counter is compensated when issuance fails after reservation`() {
        val region = fixtures.createRegion()
        val member = fixtures.createMember()
        val key = "region:monthly:${region.id}:${YearMonth.now()}"
        val before = redissonClient.getAtomicLong(key).get()

        // INCRBY 이후 호출되는 ledger 기록을 실패시켜 트랜잭션을 롤백시킨다.
        doThrow(RuntimeException("ledger boom")).`when`(ledgerService)
            .record(anyMatcher(), anyMatcher(), anyMatcher(), anyLong(), anyMatcher())

        assertThrows<Exception> {
            issueService.issue(member.id, region.id, BigDecimal("50000"))
        }

        // INCRBY(+50000)가 보상 DECRBY(-50000)로 원복되어 시도 전 값과 같아야 한다.
        redissonClient.getAtomicLong(key).get() shouldBe before

        // 트랜잭션 롤백으로 바우처도 영속되지 않는다.
        voucherRepository.sumFaceValueByMemberAndRegion(member.id, region.id)
            .compareTo(BigDecimal.ZERO) shouldBe 0
    }
}
