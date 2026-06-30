package com.commerce.merchant.infrastructure

import com.commerce.merchant.domain.Settlement
import com.commerce.merchant.domain.SettlementStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface SettlementJpaRepository : JpaRepository<Settlement, Long> {
    fun findByMerchantIdAndPeriodStartAndPeriodEnd(
        merchantId: Long,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Settlement?

    /** 해당 가맹점에 주어진 날짜를 포함하는 정산이 주어진 상태(예: CONFIRMED/PAID)로 존재하는지. */
    fun existsByMerchantIdAndStatusInAndPeriodStartLessThanEqualAndPeriodEndGreaterThanEqual(
        merchantId: Long,
        statuses: Collection<SettlementStatus>,
        periodStartUpperBound: LocalDate,
        periodEndLowerBound: LocalDate,
    ): Boolean
}
