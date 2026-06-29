package com.commerce.point.application

import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.point.domain.PointAccount
import com.commerce.point.domain.PointTransactionType
import com.commerce.point.infrastructure.PointAccountJpaRepository
import com.commerce.point.infrastructure.PointTransactionJpaRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import java.math.BigDecimal

class PointEarnServiceTest : DescribeSpec({

    // 순수 계산 검증이라 리포지토리/원장은 모킹, 적립률은 0.01 고정 주입.
    val service = PointEarnService(
        pointAccountRepository = mockk(relaxed = true),
        pointTransactionRepository = mockk(relaxed = true),
        ledgerService = mockk(relaxed = true),
        meterRegistry = SimpleMeterRegistry(),
        earnRate = BigDecimal("0.01"),
    )

    describe("calculateEarn (1% , 1원 단위 HALF_UP)") {
        it("rounds exact and fractional amounts") {
            service.calculateEarn(BigDecimal("20000")).compareTo(BigDecimal("200")) shouldBe 0
            service.calculateEarn(BigDecimal("15000")).compareTo(BigDecimal("150")) shouldBe 0
            // 12350 * 0.01 = 123.50 -> HALF_UP -> 124
            service.calculateEarn(BigDecimal("12350")).compareTo(BigDecimal("124")) shouldBe 0
            // 12340 * 0.01 = 123.40 -> HALF_UP -> 123
            service.calculateEarn(BigDecimal("12340")).compareTo(BigDecimal("123")) shouldBe 0
        }

        it("returns 0 when the base amount is too small to earn 1 won") {
            // 49 * 0.01 = 0.49 -> 0
            service.calculateEarn(BigDecimal("49")).compareTo(BigDecimal.ZERO) shouldBe 0
        }
    }

    describe("earn() 호출 시퀀스 검증") {
        it("적립이 발생하면 계좌·포인트 트랜잭션·원장을 정확한 인자로 기록하고 카운터를 증가시킨다") {
            val memberId = 1L
            val sourceTransactionId = 100L
            val baseAmount = BigDecimal("20000") // 20000 * 0.01 = 200
            val expectedEarn = BigDecimal("200")

            val mockPointAccountRepo = mockk<PointAccountJpaRepository>()
            val mockPointTransactionRepo = mockk<PointTransactionJpaRepository>()
            val mockLedgerService = mockk<LedgerService>()
            val registry = SimpleMeterRegistry()

            val strictService = PointEarnService(
                pointAccountRepository = mockPointAccountRepo,
                pointTransactionRepository = mockPointTransactionRepo,
                ledgerService = mockLedgerService,
                meterRegistry = registry,
                earnRate = BigDecimal("0.01"),
            )

            // findByMemberIdForUpdate returns a real PointAccount so we can assert balance after
            val account = PointAccount(memberId = memberId)
            every { mockPointAccountRepo.findByMemberIdForUpdate(memberId) } returns account
            every { mockPointTransactionRepo.save(any()) } returns mockk()
            every { mockLedgerService.record(any(), any(), any(), any(), any()) } returns emptyList()

            strictService.earn(memberId, baseAmount, sourceTransactionId)

            // PointAccount balance incremented via JPA dirty-checking (no explicit save)
            account.balance.compareTo(expectedEarn) shouldBe 0

            // LedgerService called exactly once with POINT_BALANCE debit / POINT_FUNDING credit
            verify(exactly = 1) {
                mockLedgerService.record(
                    debitAccount = AccountCode.POINT_BALANCE,
                    creditAccount = AccountCode.POINT_FUNDING,
                    amount = expectedEarn,
                    transactionId = sourceTransactionId,
                    entryType = LedgerEntryType.POINT_EARN,
                )
            }

            // PointTransaction saved with correct EARN type, memberId, amount, sourceTransactionId
            verify {
                mockPointTransactionRepo.save(match {
                    it.memberId == memberId &&
                        it.type == PointTransactionType.EARN &&
                        it.amount.compareTo(expectedEarn) == 0 &&
                        it.sourceTransactionId == sourceTransactionId
                })
            }

            // counter incremented by 1
            registry.counter("point.earn.count").count() shouldBe 1.0
        }

        it("적립액이 0이면 원장 기록(ledgerService.record)을 전혀 호출하지 않는다") {
            val mockLedgerService = mockk<LedgerService>()
            val zeroEarnService = PointEarnService(
                pointAccountRepository = mockk(relaxed = true),
                pointTransactionRepository = mockk(relaxed = true),
                ledgerService = mockLedgerService,
                meterRegistry = SimpleMeterRegistry(),
                earnRate = BigDecimal("0.01"),
            )

            // 49 * 0.01 = 0.49 -> HALF_UP -> 0 → earn() early-returns before any recording
            zeroEarnService.earn(1L, BigDecimal("49"), 1L)

            verify(exactly = 0) { mockLedgerService.record(any(), any(), any(), any(), any()) }
        }
    }
})
