package com.commerce.ledger.application

import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntrySide
import com.commerce.ledger.infrastructure.LedgerJpaRepository
import com.commerce.point.infrastructure.PointAccountJpaRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class VerificationResult(
    val isBalanced: Boolean,
    val globalDebitTotal: BigDecimal,
    val globalCreditTotal: BigDecimal,
    val pointBalanceMatches: Boolean,
)

@Service
class LedgerVerificationService(
    private val ledgerRepository: LedgerJpaRepository,
    private val pointAccountRepository: PointAccountJpaRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun scheduledVerification() {
        val result = verify()
        meterRegistry.gauge("ledger.verification.point.matches", if (result.pointBalanceMatches) 1.0 else 0.0)
        if (!result.isBalanced) {
            log.error(
                "LEDGER IMBALANCE DETECTED: pointBalanceMatches={}, global debit={}, credit={}",
                result.pointBalanceMatches, result.globalDebitTotal, result.globalCreditTotal,
            )
        } else {
            log.info("Ledger verification passed. Global balance: {}", result.globalDebitTotal)
        }
    }

    fun verify(): VerificationResult {
        val globalDebit = ledgerRepository.sumBySide(LedgerEntrySide.DEBIT)
        val globalCredit = ledgerRepository.sumBySide(LedgerEntrySide.CREDIT)
        val globalBalanced = globalDebit.compareTo(globalCredit) == 0

        val pointBalanceMatches = checkPointBalance()

        return VerificationResult(
            isBalanced = globalBalanced && pointBalanceMatches,
            globalDebitTotal = globalDebit,
            globalCreditTotal = globalCredit,
            pointBalanceMatches = pointBalanceMatches,
        )
    }

    // POINT_BALANCE 차변정상: 원장 net(차변-대변) == 모든 PointAccount.balance 합.
    private fun checkPointBalance(): Boolean {
        val ledgerPointBalance =
            ledgerRepository.sumByAccountAndSide(AccountCode.POINT_BALANCE, LedgerEntrySide.DEBIT) -
            ledgerRepository.sumByAccountAndSide(AccountCode.POINT_BALANCE, LedgerEntrySide.CREDIT)
        val cachedPointTotal = pointAccountRepository.sumAllBalances()
        return cachedPointTotal.compareTo(ledgerPointBalance) == 0
    }
}
