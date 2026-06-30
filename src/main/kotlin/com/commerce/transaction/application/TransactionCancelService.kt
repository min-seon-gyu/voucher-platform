package com.commerce.transaction.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.merchant.domain.SettlementStatus
import com.commerce.merchant.infrastructure.SettlementJpaRepository
import com.commerce.point.application.PointEarnService
import com.commerce.promotion.domain.CouponRedemption
import com.commerce.promotion.infrastructure.CouponJpaRepository
import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
import com.commerce.promotion.infrastructure.PromotionBudgetManager
import com.commerce.transaction.domain.TransactionType
import com.commerce.transaction.domain.event.TransactionCancelledEvent
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import com.commerce.voucher.infrastructure.VoucherLockManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal

@Service
class TransactionCancelService(
    private val transactionRepository: TransactionJpaRepository,
    private val transactionService: TransactionService,
    private val voucherRepository: VoucherJpaRepository,
    private val lockManager: VoucherLockManager,
    private val ledgerService: LedgerService,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate,
    private val couponRedemptionRepository: CouponRedemptionJpaRepository,
    private val couponRepository: CouponJpaRepository,
    private val budgetManager: PromotionBudgetManager,
    private val pointEarnService: PointEarnService,
    private val settlementRepository: SettlementJpaRepository,
) {

    /**
     * 거래 취소 (보상 트랜잭션). 분산락 → 트랜잭션(커밋) → 락 해제 순서 보장.
     * requestCancel()도 트랜잭션 + 락 내부에서 실행하여 상태 변경 일관성 확보.
     * 결합결제(쿠폰 적용)면 정준 락 순서 coupon → voucher 로 잠그고 두 leg 쌍을 모두 역분개한다.
     */
    fun cancel(transactionId: Long): Long {
        // 락 키(voucherId)만 얻기 위한 사전 조회 — 상태 변경은 트랜잭션 내부에서 수행
        val original = transactionService.getById(transactionId)

        // 결제(REDEMPTION) 거래만 취소 가능 — 보상(CANCELLATION)·환불·만료·정산 거래의 (재)취소를 차단해
        // 이중 잔액 복원·원장 위조를 막는다.
        if (original.type != TransactionType.REDEMPTION)
            throw BusinessException(ErrorCode.TRANSACTION_NOT_CANCELLABLE, "결제 거래만 취소할 수 있습니다")

        val voucherId = original.voucherId
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "상품권 거래만 취소할 수 있습니다")

        // 이미 정산(확정/지급)된 결제는 취소 불가 — 역정산 경로가 없어 가맹점 과지급·미수금 음수를 유발한다.
        original.merchantId?.let { merchantId ->
            val day = original.createdAt.toLocalDate()
            val settled = settlementRepository
                .existsByMerchantIdAndStatusInAndPeriodStartLessThanEqualAndPeriodEndGreaterThanEqual(
                    merchantId, listOf(SettlementStatus.CONFIRMED, SettlementStatus.PAID), day, day,
                )
            if (settled) throw BusinessException(ErrorCode.TRANSACTION_NOT_CANCELLABLE, "이미 정산된 결제는 취소할 수 없습니다")
        }

        val couponRedemption = couponRedemptionRepository.findByTransactionId(transactionId)

        return if (couponRedemption != null) {
            // 정준 락 순서: coupon(외측) → voucher(내측) → DB tx — RedemptionOrchestrator와 동일
            lockManager.withCouponLock(couponRedemption.couponId) {
                lockManager.withVoucherLock(voucherId) {
                    cancelWithCoupon(transactionId, voucherId, couponRedemption)
                }
            }
        } else {
            lockManager.withVoucherLock(voucherId) {
                cancelVoucherOnly(transactionId, voucherId)
            }
        }
    }

    /** 무쿠폰(기존) 취소: 단일 역분개 + 바우처 잔액 복원. */
    private fun cancelVoucherOnly(transactionId: Long, voucherId: Long): Long =
        transactionTemplate.execute { _ ->
            // 트랜잭션 내부에서 다시 로드해 managed 상태로 만든다.
            // (트랜잭션 밖에서 로드한 detached 엔티티는 open-in-view=false 환경에서
            //  상태 변경(CANCELLED)이 영속화되지 않아 정산 집계에서 취소가 누락됨)
            val original = transactionRepository.findById(transactionId)
                .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
            original.requestCancel()

            // Create compensating transaction
            val compensating = transactionService.create(
                type = TransactionType.CANCELLATION,
                amount = original.amount,
                voucherId = voucherId,
                merchantId = original.merchantId,
                originalTransactionId = original.id,
            )
            // Reverse ledger entries (debit VOUCHER_BALANCE, credit MERCHANT_RECEIVABLE)
            ledgerService.record(
                debitAccount = AccountCode.VOUCHER_BALANCE,
                creditAccount = AccountCode.MERCHANT_RECEIVABLE,
                amount = original.amount,
                transactionId = compensating.id,
                entryType = LedgerEntryType.CANCELLATION,
            )
            compensating.complete()
            original.cancel()

            // Restore voucher balance
            val voucher = voucherRepository.findByIdForUpdate(voucherId)
                ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
            voucher.restoreBalance(original.amount)

            // 포인트 적립 역분개(동기, 같은 취소 트랜잭션 내부). 원 redemption txId 기준으로 EARN을 찾아
            // CANCELLATION 원장쌍 + CANCEL PointTransaction으로 보상한다. 적립이 없었으면 no-op.
            pointEarnService.reverseEarn(voucher.memberId, transactionId, compensating.id)

            eventPublisher.publishEvent(
                TransactionCancelledEvent(original.id, voucherId, original.amount)
            )
            compensating.id
        }!!

    /**
     * 결합결제 취소: 두 leg 쌍을 모두 역분개(보상 트랜잭션, 원 거래 불변 보존).
     * - 쌍1 역분개: DEBIT VOUCHER_BALANCE / CREDIT MERCHANT_RECEIVABLE (T-D). T-D==0이면 생략.
     * - 쌍2 역분개: DEBIT PROMOTION_FUNDING / CREDIT MERCHANT_RECEIVABLE (D). D==0이면 생략.
     * 바우처 잔액 T-D 복원(gross T 아님 — 과복원 방지), 쿠폰 CANCELLED, 예산 반환(DECRBY).
     * 0원 leg 생략은 redeem 분개와 정확히 대칭(LedgerEntry는 양수 금액만 허용).
     */
    private fun cancelWithCoupon(
        transactionId: Long,
        voucherId: Long,
        couponRedemption: CouponRedemption,
    ): Long {
        val compensatingId = transactionTemplate.execute { _ ->
            val original = transactionRepository.findById(transactionId)
                .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
            original.requestCancel()

            val compensating = transactionService.create(
                type = TransactionType.CANCELLATION,
                amount = original.amount, // gross T (정산 집계 기준)
                voucherId = voucherId,
                merchantId = original.merchantId,
                originalTransactionId = original.id,
            )

            // 쌍1 역분개 (바우처 결제분). T-D==0(전액 쿠폰 보전)이면 생략.
            if (couponRedemption.voucherCharged > BigDecimal.ZERO) {
                ledgerService.record(
                    debitAccount = AccountCode.VOUCHER_BALANCE,
                    creditAccount = AccountCode.MERCHANT_RECEIVABLE,
                    amount = couponRedemption.voucherCharged,
                    transactionId = compensating.id,
                    entryType = LedgerEntryType.CANCELLATION,
                )
            }
            // 쌍2 역분개 (플랫폼 보조분). D==0이면 생략.
            if (couponRedemption.discountAmount > BigDecimal.ZERO) {
                ledgerService.record(
                    debitAccount = AccountCode.PROMOTION_FUNDING,
                    creditAccount = AccountCode.MERCHANT_RECEIVABLE,
                    amount = couponRedemption.discountAmount,
                    transactionId = compensating.id,
                    entryType = LedgerEntryType.CANCELLATION,
                )
            }
            compensating.complete()
            original.cancel()

            // 바우처 잔액 T-D 복원 (gross T 아님 — 과복원 방지)
            val voucher = voucherRepository.findByIdForUpdate(voucherId)
                ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
            voucher.restoreBalance(couponRedemption.voucherCharged)

            // 쿠폰 회수(REDEEMED→CANCELLED) + 사용 기록 취소 표시
            val coupon = couponRepository.findById(couponRedemption.couponId)
                .orElseThrow { BusinessException(ErrorCode.COUPON_NOT_FOUND) }
            coupon.cancel()
            couponRedemption.markCancelled()
            couponRedemptionRepository.save(couponRedemption)

            // 포인트 적립 역분개(동기, 같은 취소 트랜잭션 내부). 결합결제 적립 기준은 T−D(voucherCharged)이며
            // 전액 쿠폰(voucherCharged==0)이면 EARN이 없어 reverseEarn은 no-op.
            pointEarnService.reverseEarn(voucher.memberId, transactionId, compensating.id)

            eventPublisher.publishEvent(
                TransactionCancelledEvent(original.id, voucherId, original.amount)
            )
            compensating.id
        }!!

        // 커밋 성공 후 예산 반환(누락돼도 재동기화 잡이 DB 기준으로 보정)
        budgetManager.release(couponRedemption.promotionId, couponRedemption.discountAmount)
        return compensatingId
    }
}
