package com.commerce.point.application

import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.point.domain.PointAccount
import com.commerce.point.domain.PointTransaction
import com.commerce.point.domain.PointTransactionType
import com.commerce.point.infrastructure.PointAccountJpaRepository
import com.commerce.point.infrastructure.PointTransactionJpaRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class PointEarnService(
    private val pointAccountRepository: PointAccountJpaRepository,
    private val pointTransactionRepository: PointTransactionJpaRepository,
    private val ledgerService: LedgerService,
    private val meterRegistry: MeterRegistry,
    @Value("\${point.earn-rate}") private val earnRate: BigDecimal,
) {

    /** 적립액 = baseAmount * earnRate, 1원 단위 HALF_UP. */
    fun calculateEarn(baseAmount: BigDecimal): BigDecimal =
        baseAmount.multiply(earnRate).setScale(0, RoundingMode.HALF_UP)

    /**
     * 결제 트랜잭션과 **동기**로 적립을 기록한다. 반드시 활성 트랜잭션 내부에서 호출한다.
     * baseAmount = 쿠폰 할인 적용 후 실제 결제액(plain redeem에서는 결제 금액 그대로).
     * 적립액이 0이면 아무 것도 기록하지 않고 0을 반환한다.
     * 동일 회원 동시 적립은 findByMemberIdForUpdate(SELECT FOR UPDATE)로 직렬화한다.
     *
     * **JPA dirty-checking 의존**: [PointAccount.earn]으로 증가한 잔액은 별도의 `save()` 호출 없이
     * JPA 더티 체킹(dirty-checking)으로 영속화된다. 따라서 이 메서드는 **반드시 호출자의 활성 트랜잭션
     * 범위 안에서 실행**되어야 한다(호출자가 `@Transactional`을 보유해야 함).
     * 반면 [PointTransaction] 신규 행과 원장([LedgerService.record]) 행은 명시적으로 저장된다.
     */
    fun earn(memberId: Long, baseAmount: BigDecimal, sourceTransactionId: Long): BigDecimal {
        val earnAmount = calculateEarn(baseAmount)
        if (earnAmount.signum() <= 0) return BigDecimal.ZERO

        val account = pointAccountRepository.findByMemberIdForUpdate(memberId)
            ?: pointAccountRepository.save(PointAccount(memberId = memberId))
        account.earn(earnAmount)

        pointTransactionRepository.save(
            PointTransaction(
                memberId = memberId,
                type = PointTransactionType.EARN,
                amount = earnAmount,
                balanceAfter = account.balance,
                sourceTransactionId = sourceTransactionId,
            )
        )

        // POINT_BALANCE 차변정상: DEBIT POINT_BALANCE / CREDIT POINT_FUNDING (redemption tx와 동일 txId)
        ledgerService.record(
            debitAccount = AccountCode.POINT_BALANCE,
            creditAccount = AccountCode.POINT_FUNDING,
            amount = earnAmount,
            transactionId = sourceTransactionId,
            entryType = LedgerEntryType.POINT_EARN,
        )

        meterRegistry.counter("point.earn.count").increment()
        return earnAmount
    }
}
