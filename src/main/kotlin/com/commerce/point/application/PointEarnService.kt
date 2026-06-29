package com.commerce.point.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
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

        // INSERT IGNORE로 행을 먼저 보장한다: 없으면 생성, 있으면 무시.
        // 이후 SELECT FOR UPDATE는 항상 기존 행을 잠그므로 갭 락(gap lock) 데드락을 회피한다.
        pointAccountRepository.ensureExists(memberId)
        val account = pointAccountRepository.findByMemberIdForUpdate(memberId)!!
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

    /**
     * 적립 취소(보상). redemption 취소 시 호출되며, 원 redemption이 적립한 포인트를 역분개한다.
     * 반드시 **호출자의 활성 취소 트랜잭션 내부에서 동기**로 실행한다(별도 @Transactional 없음 —
     * TransactionCancelService의 TransactionTemplate가 트랜잭션을 제공한다).
     *
     * 보상 트랜잭션 규율(원 EARN 행 불변 보존):
     * - 원 EARN [PointTransaction]은 수정/삭제하지 않는다.
     * - 새 CANCELLATION 원장쌍 + 새 CANCEL [PointTransaction]으로 역분개를 기록한다.
     *
     * 멱등/0원 처리: sourceTransactionId에 해당하는 EARN이 없거나(이미 롤백/미적립) 합계가 0이면 no-op.
     *
     * @param sourceTransactionId 원 redemption 거래 id (EARN 행의 sourceTransactionId)
     * @param compensatingTransactionId 보상(취소) 거래 id (역분개 원장 행이 매달릴 거래)
     */
    fun reverseEarn(memberId: Long, sourceTransactionId: Long, compensatingTransactionId: Long) {
        val earnedAmount = pointTransactionRepository.findBySourceTransactionId(sourceTransactionId)
            .filter { it.type == PointTransactionType.EARN }
            .fold(BigDecimal.ZERO) { acc, tx -> acc + tx.amount }
        if (earnedAmount.signum() <= 0) return

        // 캐시 잔액 차감을 SELECT FOR UPDATE로 직렬화(동시 적립/취소 경합 방지).
        // EARN이 존재하므로 계좌도 반드시 존재한다. 없으면 데이터 불일치 → 명시적 오류.
        val account = pointAccountRepository.findByMemberIdForUpdate(memberId)
            ?: throw BusinessException(ErrorCode.POINT_ACCOUNT_NOT_FOUND)
        account.deduct(earnedAmount)

        // 감사 추적: CANCEL PointTransaction (원 EARN의 sourceTransactionId 공유, 양수 금액).
        pointTransactionRepository.save(
            PointTransaction(
                memberId = memberId,
                type = PointTransactionType.CANCEL,
                amount = earnedAmount,
                balanceAfter = account.balance,
                sourceTransactionId = sourceTransactionId,
            )
        )

        // EARN(DEBIT POINT_BALANCE / CREDIT POINT_FUNDING)의 정확한 역분개:
        // DEBIT POINT_FUNDING / CREDIT POINT_BALANCE → POINT_BALANCE net이 0으로 복귀.
        ledgerService.record(
            debitAccount = AccountCode.POINT_FUNDING,
            creditAccount = AccountCode.POINT_BALANCE,
            amount = earnedAmount,
            transactionId = compensatingTransactionId,
            entryType = LedgerEntryType.CANCELLATION,
        )

        meterRegistry.counter("point.cancel.count").increment()
    }
}
