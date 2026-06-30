package com.commerce.merchant.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.merchant.domain.Settlement
import com.commerce.merchant.domain.event.SettlementConfirmedEvent
import com.commerce.merchant.infrastructure.MerchantJpaRepository
import com.commerce.merchant.infrastructure.SettlementJpaRepository
import com.commerce.region.domain.SettlementPeriod
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionStatus
import com.commerce.transaction.domain.TransactionType
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Service
@Transactional(readOnly = true)
class SettlementService(
    private val settlementRepository: SettlementJpaRepository,
    private val transactionRepository: TransactionJpaRepository,
    private val transactionService: TransactionService,
    private val ledgerService: LedgerService,
    private val merchantRepository: MerchantJpaRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    /**
     * 가맹점 소속 지자체의 정산 주기(일/주/월)에 맞춰 KST 역월 기준 정산 기간을 산출한 뒤 정산한다.
     * 기준일(referenceDate)이 속한 주기 구간 [start, end]를 닫힌 구간으로 계산한다.
     *   - DAILY  : 기준일 당일
     *   - WEEKLY : 기준일이 속한 ISO 주(월~일)
     *   - MONTHLY: 기준일이 속한 달의 1일~말일
     */
    @Transactional
    fun calculateForPeriod(
        merchantId: Long,
        referenceDate: LocalDate = LocalDate.now(KST),
    ): Settlement {
        val merchant = merchantRepository.findById(merchantId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        val (start, end) = resolvePeriod(merchant.region.policy.settlementPeriod, referenceDate)
        return calculate(merchantId, start, end)
    }

    private fun resolvePeriod(period: SettlementPeriod, ref: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
        SettlementPeriod.DAILY -> ref to ref
        SettlementPeriod.WEEKLY -> ref.with(DayOfWeek.MONDAY) to ref.with(DayOfWeek.SUNDAY)
        SettlementPeriod.MONTHLY -> ref.withDayOfMonth(1) to ref.withDayOfMonth(ref.lengthOfMonth())
    }

    @Transactional
    fun calculate(merchantId: Long, periodStart: LocalDate, periodEnd: LocalDate): Settlement {
        // Check duplicate
        settlementRepository.findByMerchantIdAndPeriodStartAndPeriodEnd(merchantId, periodStart, periodEnd)
            ?.let { throw BusinessException(ErrorCode.INVALID_INPUT, "이미 해당 기간 정산이 존재합니다") }

        val start = periodStart.atStartOfDay()
        val end = periodEnd.atTime(LocalTime.MAX)

        // COMPLETED 상태인 결제만 합산 (취소된 원 거래는 CANCELLED 상태이므로 자동 제외)
        val totalAmount = transactionRepository.sumAmountByMerchantAndTypeAndPeriod(
            merchantId, TransactionType.REDEMPTION, TransactionStatus.COMPLETED, start, end
        )

        return settlementRepository.save(
            Settlement(
                merchantId = merchantId,
                periodStart = periodStart,
                periodEnd = periodEnd,
                totalAmount = totalAmount,
            )
        )
    }

    @Transactional
    fun confirm(settlementId: Long): Settlement {
        val settlement = getById(settlementId)
        settlement.confirm()

        // 정산 확정 시 원장 기록: 가맹점 미수금 → 정산 미지급금
        val tx = transactionService.create(
            type = TransactionType.SETTLEMENT,
            amount = settlement.totalAmount,
            merchantId = settlement.merchantId,
        )
        ledgerService.record(
            debitAccount = AccountCode.SETTLEMENT_PAYABLE,
            creditAccount = AccountCode.MERCHANT_RECEIVABLE,
            amount = settlement.totalAmount,
            transactionId = tx.id,
            entryType = LedgerEntryType.SETTLEMENT,
        )
        tx.complete()

        eventPublisher.publishEvent(
            SettlementConfirmedEvent(
                aggregateId = settlement.id,
                merchantId = settlement.merchantId,
                totalAmount = settlement.totalAmount,
                periodStart = settlement.periodStart,
                periodEnd = settlement.periodEnd,
            )
        )
        return settlement
    }

    @Transactional
    fun dispute(settlementId: Long, reason: String): Settlement {
        val settlement = getById(settlementId)
        settlement.dispute(reason)
        return settlement
    }

    /** 확정된 정산을 지급 완료 처리한다. CONFIRMED 상태에서만 PAID로 전이 가능. */
    @Transactional
    fun markPaid(settlementId: Long): Settlement {
        val settlement = getById(settlementId)
        settlement.pay()
        return settlement
    }

    fun getById(id: Long): Settlement =
        settlementRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
    }
}
