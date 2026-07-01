package com.commerce.merchant.infrastructure

import com.commerce.merchant.domain.Settlement
import com.commerce.merchant.domain.SettlementStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.LocalDate

interface SettlementJpaRepository : JpaRepository<Settlement, Long> {
    fun findByMerchantIdAndPeriodStartAndPeriodEnd(
        merchantId: Long,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Settlement?

    fun countByStatus(status: SettlementStatus): Long

    /** 배치 검증용: 특정 상태이면서 금액이 임계 이하(예: 0원)인 정산 수 — 있으면 이상치. */
    fun countByStatusAndTotalAmountLessThanEqual(status: SettlementStatus, amount: BigDecimal): Long

    /** 해당 가맹점에 주어진 날짜를 포함하는 정산이 주어진 상태(예: CONFIRMED/PAID)로 존재하는지. */
    fun existsByMerchantIdAndStatusInAndPeriodStartLessThanEqualAndPeriodEndGreaterThanEqual(
        merchantId: Long,
        statuses: Collection<SettlementStatus>,
        periodStartUpperBound: LocalDate,
        periodEndLowerBound: LocalDate,
    ): Boolean
}
