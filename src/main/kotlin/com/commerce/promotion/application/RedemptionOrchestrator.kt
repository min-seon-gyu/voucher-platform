package com.commerce.promotion.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.promotion.domain.CouponRedemption
import com.commerce.promotion.domain.CouponStatus
import com.commerce.promotion.infrastructure.CouponJpaRepository
import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
import com.commerce.promotion.infrastructure.PromotionBudgetManager
import com.commerce.promotion.infrastructure.PromotionJpaRepository
import com.commerce.transaction.application.TransactionService
import com.commerce.transaction.domain.TransactionType
import com.commerce.voucher.application.VoucherRedemptionService
import com.commerce.voucher.domain.event.VoucherRedeemedEvent
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import com.commerce.voucher.infrastructure.VoucherLockManager
import com.commerce.point.application.PointEarnService
import com.commerce.voucher.interfaces.dto.RedemptionResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal

/**
 * 결합 결제(바우처 + 쿠폰) 오케스트레이터.
 * 정준 락 순서 coupon → voucher 로 데드락을 방지하고,
 * 예산 예약은 DB tx 밖에서 원자 수행한 뒤 다운스트림 실패 시 보상 DECRBY 한다.
 * 분개는 동일 transactionId를 공유하는 균형 2-leg 2쌍(바우처분 REDEMPTION + 보조분 COUPON_SUBSIDY).
 * 단, leg 금액 0(전액 쿠폰 보전 시 T-D=0, 또는 D=0)인 쌍은 생략한다 — LedgerEntry는 양수 금액만 허용하며
 * 0원 leg는 어떤 계정 잔액에도 기여하지 않으므로 순(net)·정합성은 동일하다.
 */
@Service
class RedemptionOrchestrator(
    private val voucherRepository: VoucherJpaRepository,
    private val couponRepository: CouponJpaRepository,
    private val promotionRepository: PromotionJpaRepository,
    private val couponRedemptionRepository: CouponRedemptionJpaRepository,
    private val lockManager: VoucherLockManager,
    private val ledgerService: LedgerService,
    private val transactionService: TransactionService,
    private val budgetManager: PromotionBudgetManager,
    private val redemptionService: VoucherRedemptionService,
    private val eventPublisher: ApplicationEventPublisher,
    private val meterRegistry: MeterRegistry,
    private val pointEarnService: PointEarnService,
    private val transactionTemplate: TransactionTemplate,
) {

    /**
     * @param orderTotal 주문 총액 T (= 가맹점 수취 gross). 쿠폰 없으면 바우처 차감액과 동일.
     * @param couponId 적용할 단일 쿠폰(없으면 일반 바우처 결제로 위임).
     */
    fun redeem(voucherId: Long, merchantId: Long, orderTotal: BigDecimal, couponId: Long?): RedemptionResult {
        if (couponId == null) {
            return redemptionService.redeem(voucherId, merchantId, orderTotal)
        }
        // 락 키 도출용 사전 조회(쿠폰의 promotionId·memberId). 본 검증은 락 내부에서 재수행한다.
        val couponMeta = couponRepository.findById(couponId)
            .orElseThrow { BusinessException(ErrorCode.COUPON_NOT_FOUND) }
        // 정준 락 순서: promotion·member(최외측, 동일회원 다른쿠폰 동시상환 직렬화) → coupon → voucher → DB tx
        return lockManager.withPromotionMemberLock(couponMeta.promotionId, couponMeta.memberId) {
            lockManager.withCouponLock(couponId) {
                lockManager.withVoucherLock(voucherId) {
                    redeemWithCoupon(voucherId, merchantId, orderTotal, couponId)
                }
            }
        }
    }

    private fun redeemWithCoupon(
        voucherId: Long,
        merchantId: Long,
        orderTotal: BigDecimal,
        couponId: Long,
    ): RedemptionResult {
        // 1) 사전 검증(읽기) + 할인액 산정 — 예산 예약 전에 빠르게 거부
        val coupon = couponRepository.findById(couponId)
            .orElseThrow { BusinessException(ErrorCode.COUPON_NOT_FOUND) }
        if (coupon.status != CouponStatus.ISSUED) throw BusinessException(ErrorCode.COUPON_ALREADY_USED)
        if (coupon.isExpired()) throw BusinessException(ErrorCode.COUPON_EXPIRED)

        val promotion = promotionRepository.findById(coupon.promotionId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        if (!promotion.isActive()) throw BusinessException(ErrorCode.PROMOTION_NOT_ACTIVE)
        if (orderTotal < promotion.minSpend) throw BusinessException(ErrorCode.MIN_SPEND_NOT_MET)

        val usedCount = couponRedemptionRepository
            .countByMemberIdAndPromotionIdAndCancelledFalse(coupon.memberId, promotion.id)
        if (usedCount >= promotion.perMemberLimit) throw BusinessException(ErrorCode.COUPON_USAGE_LIMIT_EXCEEDED)

        val rawDiscount = promotion.calculateDiscount(orderTotal)
        val discount = rawDiscount.min(orderTotal) // 과할인 클램프 D = min(D, T)
        if (discount <= BigDecimal.ZERO) throw BusinessException(ErrorCode.INVALID_DISCOUNT)
        val voucherCharged = orderTotal - discount // T - D (>= 0, 전액 쿠폰 보전 시 0)

        // 2) 예산 원자 예약 — DB tx 밖. 초과 시 즉시 거부, 실패 시 finally에서 보상 DECRBY
        if (!budgetManager.reserve(promotion.id, discount, promotion.budgetLimit)) {
            throw BusinessException(ErrorCode.PROMOTION_BUDGET_EXCEEDED)
        }

        val timer = Timer.start(meterRegistry)
        var committed = false
        return try {
            val result = transactionTemplate.execute { _ ->
                // 쿠폰 락이 직렬화하므로 재조회로 이중 사용 방지(상태 ISSUED 재확인)
                val lockedCoupon = couponRepository.findById(couponId)
                    .orElseThrow { BusinessException(ErrorCode.COUPON_NOT_FOUND) }
                if (lockedCoupon.status != CouponStatus.ISSUED)
                    throw BusinessException(ErrorCode.COUPON_ALREADY_USED)

                val voucher = voucherRepository.findByIdForUpdate(voucherId)
                    ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
                if (voucher.memberId != lockedCoupon.memberId)
                    throw BusinessException(ErrorCode.INVALID_INPUT, "쿠폰 소유자의 상품권이 아닙니다")
                if (!voucher.isUsable()) throw BusinessException(ErrorCode.VOUCHER_NOT_USABLE)
                if (voucher.isExpired()) throw BusinessException(ErrorCode.VOUCHER_EXPIRED)
                if (voucher.balance < voucherCharged) throw BusinessException(ErrorCode.INSUFFICIENT_BALANCE)

                val previousBalance = voucher.balance // capture before deduction for audit event

                // 바우처 차감은 실제 차감액이 있을 때만 (T==D 전액 쿠폰 보전이면 0원 차감/분개 금지)
                if (voucherCharged > BigDecimal.ZERO) voucher.redeem(voucherCharged)

                val tx = transactionService.create(
                    type = TransactionType.REDEMPTION,
                    amount = orderTotal,                 // gross T (정산 집계 기준)
                    voucherId = voucherId,
                    merchantId = merchantId,
                    memberId = lockedCoupon.memberId,
                )

                // 쌍1: 바우처 결제분 (DEBIT MERCHANT_RECEIVABLE / CREDIT VOUCHER_BALANCE, T-D). 0이면 생략.
                if (voucherCharged > BigDecimal.ZERO) {
                    ledgerService.record(
                        debitAccount = AccountCode.MERCHANT_RECEIVABLE,
                        creditAccount = AccountCode.VOUCHER_BALANCE,
                        amount = voucherCharged,
                        transactionId = tx.id,
                        entryType = LedgerEntryType.REDEMPTION,
                    )
                }
                // 쌍2: 플랫폼 보조분 (DEBIT MERCHANT_RECEIVABLE / CREDIT PROMOTION_FUNDING, D). 0이면 생략.
                if (discount > BigDecimal.ZERO) {
                    ledgerService.record(
                        debitAccount = AccountCode.MERCHANT_RECEIVABLE,
                        creditAccount = AccountCode.PROMOTION_FUNDING,
                        amount = discount,
                        transactionId = tx.id,
                        entryType = LedgerEntryType.COUPON_SUBSIDY,
                    )
                }

                lockedCoupon.redeem()
                couponRedemptionRepository.save(
                    CouponRedemption(
                        couponId = couponId,
                        promotionId = promotion.id,
                        memberId = lockedCoupon.memberId,
                        voucherId = voucherId,
                        transactionId = tx.id,
                        orderTotal = orderTotal,
                        discountAmount = discount,
                        voucherCharged = voucherCharged,
                    )
                )
                tx.complete()

                // 포인트 적립(동기, 같은 redemption txId 공유). 적립 기준액 = 실제 결제액 T−D(voucherCharged).
                // 할인분 D는 PROMOTION_FUNDING 보조분이므로 적립 대상이 아니다.
                // 전액 쿠폰(voucherCharged==0)이면 earn() 내부의 zero-guard가 적립을 건너뜀.
                pointEarnService.earn(
                    memberId = voucher.memberId,
                    baseAmount = voucherCharged,
                    sourceTransactionId = tx.id,
                )

                // CRITICAL 감사 추적 — VoucherRedemptionService와 동일한 BEFORE_COMMIT 방식.
                // 전액 쿠폰 보전(voucherCharged == 0)일 때는 gross orderTotal을 amount로 사용해 감사 레코드를 보장.
                val auditAmount = if (voucherCharged > BigDecimal.ZERO) voucherCharged else orderTotal
                eventPublisher.publishEvent(
                    VoucherRedeemedEvent(
                        aggregateId = voucherId,
                        merchantId = merchantId,
                        amount = auditAmount,
                        remainingBalance = voucher.balance,
                        transactionId = tx.id,
                        previousBalance = previousBalance,
                    )
                )

                RedemptionResult(transactionId = tx.id, remainingBalance = voucher.balance)
            }!!
            committed = true
            meterRegistry.counter("coupon.redemption.count", "result", "success").increment()
            result
        } catch (e: Exception) {
            meterRegistry.counter("coupon.redemption.count", "result", "failure").increment()
            throw e
        } finally {
            // DB tx 실패(롤백) 시 예약된 예산을 보상 반환(예산 누수 방지)
            if (!committed) budgetManager.release(promotion.id, discount)
            timer.stop(meterRegistry.timer("coupon.redemption.duration"))
        }
    }
}
