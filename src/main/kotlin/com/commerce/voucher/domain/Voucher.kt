package com.commerce.voucher.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

@Entity
@Table(
    name = "vouchers",
    indexes = [
        Index(name = "idx_voucher_member", columnList = "memberId, status"),
        Index(name = "idx_voucher_region_status", columnList = "regionId, status, expiresAt"),
        Index(name = "idx_voucher_expiry", columnList = "status, expiresAt"),
    ]
)
class Voucher(
    @Column(nullable = false, unique = true, length = 19)
    val voucherCode: String,

    @Column(nullable = false, precision = 15, scale = 2)
    val faceValue: BigDecimal,

    @Column(nullable = false, precision = 15, scale = 2)
    var balance: BigDecimal,

    @Column(nullable = false)
    val memberId: Long,

    @Column(nullable = false)
    val regionId: Long,

    @Column(nullable = false)
    val purchasedAt: LocalDateTime,

    @Column(nullable = false)
    val expiresAt: LocalDateTime,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    var status: VoucherStatus = VoucherStatus.ACTIVE,
) : BaseEntity() {

    init {
        require(faceValue > BigDecimal.ZERO) { "액면가는 0보다 커야 합니다" }
        require(balance >= BigDecimal.ZERO) { "잔액은 0 이상이어야 합니다" }
    }

    val usageRatio: BigDecimal
        get() = (faceValue - balance).divide(faceValue, 4, RoundingMode.HALF_UP)

    fun redeem(amount: BigDecimal) {
        if (!isUsable()) throw BusinessException(ErrorCode.VOUCHER_NOT_USABLE)
        if (isExpired()) throw BusinessException(ErrorCode.VOUCHER_EXPIRED)
        if (balance < amount) throw BusinessException(ErrorCode.INSUFFICIENT_BALANCE)

        balance -= amount
        status = if (balance.compareTo(BigDecimal.ZERO) == 0) VoucherStatus.EXHAUSTED else VoucherStatus.PARTIALLY_USED
    }

    fun expire() {
        if (!isUsable()) throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "사용 가능 상태에서만 만료 처리할 수 있습니다")
        status = VoucherStatus.EXPIRED
    }

    fun requestRefund(refundThresholdRatio: BigDecimal) {
        if (status != VoucherStatus.PARTIALLY_USED)
            throw BusinessException(ErrorCode.REFUND_CONDITION_NOT_MET, "부분 사용된 상품권만 환불 가능합니다")
        if (usageRatio < refundThresholdRatio)
            throw BusinessException(ErrorCode.REFUND_CONDITION_NOT_MET)
        status = VoucherStatus.REFUND_REQUESTED
    }

    fun completeRefund(): BigDecimal {
        if (status != VoucherStatus.REFUND_REQUESTED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        val refundAmount = balance
        balance = BigDecimal.ZERO
        status = VoucherStatus.REFUNDED
        return refundAmount
    }

    fun requestWithdrawal(now: LocalDateTime = LocalDateTime.now()) {
        if (status != VoucherStatus.ACTIVE)
            throw BusinessException(ErrorCode.WITHDRAWAL_NOT_ALLOWED)
        if (purchasedAt.plusDays(7).isBefore(now))
            throw BusinessException(ErrorCode.WITHDRAWAL_PERIOD_EXPIRED)
        status = VoucherStatus.WITHDRAWAL_REQUESTED
    }

    fun completeWithdrawal(): BigDecimal {
        if (status != VoucherStatus.WITHDRAWAL_REQUESTED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        val refundAmount = balance
        balance = BigDecimal.ZERO
        status = VoucherStatus.WITHDRAWN
        return refundAmount
    }

    fun restoreBalance(amount: BigDecimal) {
        // 현금이 (지급 진행 중이거나) 이미 지급된 상태의 상품권을 결제취소로 부활시켜 가치를 재생성하는 것을 차단한다.
        // EXPIRED는 현금 유출 없이 잔액이 EXPIRED_VOUCHER 계정으로 이동했을 뿐이므로, 만료 전 결제의 취소 복원은 허용한다.
        if (status == VoucherStatus.REFUND_REQUESTED || status == VoucherStatus.REFUNDED ||
            status == VoucherStatus.WITHDRAWAL_REQUESTED || status == VoucherStatus.WITHDRAWN
        ) {
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "환불·청약철회된 상품권은 잔액을 복원할 수 없습니다")
        }
        require(balance + amount <= faceValue) { "복원 후 잔액이 액면가를 초과할 수 없습니다" }
        balance += amount
        status = if (balance.compareTo(faceValue) == 0) VoucherStatus.ACTIVE else VoucherStatus.PARTIALLY_USED
    }

    fun isUsable(): Boolean = status in setOf(VoucherStatus.ACTIVE, VoucherStatus.PARTIALLY_USED)

    fun isExpired(now: LocalDateTime = LocalDateTime.now()): Boolean = expiresAt.isBefore(now)
}
