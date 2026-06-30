package com.commerce.voucher.domain

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalDateTime

class VoucherTest : DescribeSpec({

    fun createVoucher(
        status: VoucherStatus = VoucherStatus.ACTIVE,
        balance: BigDecimal = BigDecimal("50000"),
        faceValue: BigDecimal = BigDecimal("50000"),
        purchasedAt: LocalDateTime = LocalDateTime.now(),
        expiresAt: LocalDateTime = LocalDateTime.now().plusMonths(6),
    ) = Voucher(
        voucherCode = "SN-A3K9M2X7P1B4Q8R5",
        faceValue = faceValue,
        balance = balance,
        memberId = 1L,
        regionId = 1L,
        purchasedAt = purchasedAt,
        expiresAt = expiresAt,
        status = status,
    )

    describe("redeem") {
        it("should deduct balance and change to PARTIALLY_USED") {
            val v = createVoucher()
            v.redeem(BigDecimal("30000"))
            v.balance shouldBe BigDecimal("20000")
            v.status shouldBe VoucherStatus.PARTIALLY_USED
        }

        it("should change to EXHAUSTED when balance reaches zero") {
            val v = createVoucher()
            v.redeem(BigDecimal("50000"))
            v.balance shouldBe BigDecimal.ZERO
            v.status shouldBe VoucherStatus.EXHAUSTED
        }

        it("should reject when balance is insufficient") {
            val v = createVoucher(balance = BigDecimal("5000"))
            val ex = shouldThrow<BusinessException> { v.redeem(BigDecimal("10000")) }
            ex.errorCode shouldBe ErrorCode.INSUFFICIENT_BALANCE
        }

        it("should reject when voucher is not usable") {
            val v = createVoucher(status = VoucherStatus.EXHAUSTED)
            val ex = shouldThrow<BusinessException> { v.redeem(BigDecimal("1000")) }
            ex.errorCode shouldBe ErrorCode.VOUCHER_NOT_USABLE
        }
    }

    describe("requestRefund") {
        it("should allow when usage ratio >= 60%") {
            val v = createVoucher(status = VoucherStatus.PARTIALLY_USED, balance = BigDecimal("15000"))
            // usage = (50000-15000)/50000 = 0.70 >= 0.60
            v.requestRefund(BigDecimal("0.60"))
            v.status shouldBe VoucherStatus.REFUND_REQUESTED
        }

        it("should reject when usage ratio < 60%") {
            val v = createVoucher(status = VoucherStatus.PARTIALLY_USED, balance = BigDecimal("30000"))
            // usage = (50000-30000)/50000 = 0.40 < 0.60
            val ex = shouldThrow<BusinessException> { v.requestRefund(BigDecimal("0.60")) }
            ex.errorCode shouldBe ErrorCode.REFUND_CONDITION_NOT_MET
        }

        it("should reject when status is ACTIVE (not partially used)") {
            val v = createVoucher(status = VoucherStatus.ACTIVE)
            val ex = shouldThrow<BusinessException> { v.requestRefund(BigDecimal("0.60")) }
            ex.errorCode shouldBe ErrorCode.REFUND_CONDITION_NOT_MET
        }
    }

    describe("requestWithdrawal") {
        it("should allow within 7 days of purchase") {
            val v = createVoucher(purchasedAt = LocalDateTime.now().minusDays(3))
            v.requestWithdrawal()
            v.status shouldBe VoucherStatus.WITHDRAWAL_REQUESTED
        }

        it("should reject after 7 days") {
            val v = createVoucher(purchasedAt = LocalDateTime.now().minusDays(8))
            val ex = shouldThrow<BusinessException> { v.requestWithdrawal() }
            ex.errorCode shouldBe ErrorCode.WITHDRAWAL_PERIOD_EXPIRED
        }

        it("should reject when not ACTIVE") {
            val v = createVoucher(status = VoucherStatus.PARTIALLY_USED)
            val ex = shouldThrow<BusinessException> { v.requestWithdrawal() }
            ex.errorCode shouldBe ErrorCode.WITHDRAWAL_NOT_ALLOWED
        }
    }

    describe("completeWithdrawal") {
        it("should refund full amount and set WITHDRAWN") {
            val v = createVoucher(status = VoucherStatus.WITHDRAWAL_REQUESTED)
            val refund = v.completeWithdrawal()
            refund shouldBe BigDecimal("50000")
            v.balance shouldBe BigDecimal.ZERO
            v.status shouldBe VoucherStatus.WITHDRAWN
        }
    }

    describe("expire") {
        it("should change status to EXPIRED") {
            val v = createVoucher(status = VoucherStatus.ACTIVE)
            v.expire()
            v.status shouldBe VoucherStatus.EXPIRED
        }

        it("should reject when already exhausted") {
            val v = createVoucher(status = VoucherStatus.EXHAUSTED)
            shouldThrow<BusinessException> { v.expire() }
        }
    }

    describe("restoreBalance") {
        it("should increase balance and update status") {
            val v = createVoucher(status = VoucherStatus.PARTIALLY_USED, balance = BigDecimal("20000"))
            v.restoreBalance(BigDecimal("30000"))
            v.balance shouldBe BigDecimal("50000")
            v.status shouldBe VoucherStatus.ACTIVE
        }

        it("should allow restore from EXHAUSTED (full redemption cancel)") {
            val v = createVoucher(status = VoucherStatus.EXHAUSTED, balance = BigDecimal.ZERO)
            v.restoreBalance(BigDecimal("50000"))
            v.status shouldBe VoucherStatus.ACTIVE
        }

        it("should allow restore from EXPIRED (cancelling a pre-expiry redemption — no cash outflow)") {
            val v = createVoucher(status = VoucherStatus.EXPIRED, balance = BigDecimal.ZERO)
            v.restoreBalance(BigDecimal("30000"))
            v.balance shouldBe BigDecimal("30000")
            v.status shouldBe VoucherStatus.PARTIALLY_USED
        }

        // 현금이 지급(진행)된 상태는 복원 차단 — 무에서 가치 재생성 방지.
        listOf(
            VoucherStatus.REFUNDED,
            VoucherStatus.WITHDRAWN,
            VoucherStatus.REFUND_REQUESTED,
            VoucherStatus.WITHDRAWAL_REQUESTED,
        ).forEach { terminal ->
            it("should reject restore from $terminal (no reviving cash-disbursed vouchers)") {
                val v = createVoucher(status = terminal, balance = BigDecimal.ZERO)
                val ex = shouldThrow<BusinessException> { v.restoreBalance(BigDecimal("10000")) }
                ex.errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
            }
        }
    }

    describe("usageRatio") {
        it("should calculate correctly") {
            val v = createVoucher(balance = BigDecimal("20000"))
            // (50000 - 20000) / 50000 = 0.6000
            v.usageRatio.compareTo(BigDecimal("0.6")) shouldBe 0
        }
    }
})
