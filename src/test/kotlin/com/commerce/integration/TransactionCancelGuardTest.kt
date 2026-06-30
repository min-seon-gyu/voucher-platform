package com.commerce.integration

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.merchant.application.SettlementService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.transaction.application.TransactionCancelService
import com.commerce.voucher.application.VoucherRedemptionService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 거래 취소 가드 검증:
 * - 결제(REDEMPTION) 거래만 취소 가능(보상 거래 재취소·이중 취소 차단)
 * - 이미 정산(확정)된 결제는 취소 불가
 */
class TransactionCancelGuardTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var redemptionService: VoucherRedemptionService
    @Autowired lateinit var cancelService: TransactionCancelService
    @Autowired lateinit var settlementService: SettlementService

    private var regionId: Long = 0
    private var memberId: Long = 0
    private var merchantId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion()
        val member = fixtures.createMember()
        val merchant = fixtures.createMerchant(region, fixtures.createMember())
        regionId = region.id
        memberId = member.id
        merchantId = merchant.id
    }

    @Test
    fun `cannot cancel a non-redemption (compensating) transaction`() {
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val r = redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"))
        val compensatingId = cancelService.cancel(r.transactionId)

        // 보상 거래(CANCELLATION)는 결제 거래가 아니므로 취소 불가
        val ex = shouldThrow<BusinessException> { cancelService.cancel(compensatingId) }
        ex.errorCode shouldBe ErrorCode.TRANSACTION_NOT_CANCELLABLE
    }

    @Test
    fun `cannot cancel the same redemption twice`() {
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val r = redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"))
        cancelService.cancel(r.transactionId)

        val ex = shouldThrow<BusinessException> { cancelService.cancel(r.transactionId) }
        ex.errorCode shouldBe ErrorCode.TRANSACTION_NOT_CANCELLABLE
    }

    @Test
    fun `cannot cancel a settled redemption`() {
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val r = redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"))

        val today = LocalDate.now()
        val settlement = settlementService.calculate(
            merchantId, today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth())
        )
        settlementService.confirm(settlement.id) // CONFIRMED — 결제 기간을 포함

        val ex = shouldThrow<BusinessException> { cancelService.cancel(r.transactionId) }
        ex.errorCode shouldBe ErrorCode.TRANSACTION_NOT_CANCELLABLE
    }
}
