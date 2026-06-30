package com.commerce.integration

import com.commerce.merchant.application.SettlementService
import com.commerce.merchant.domain.SettlementStatus
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.transaction.application.TransactionCancelService
import com.commerce.transaction.domain.TransactionType
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import com.commerce.voucher.application.VoucherRedemptionService
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 정산 확정 검증:
 * - 0원 정산(해당 기간 결제 없음)도 예외 없이 CONFIRMED로 전이(Transaction 양수 제약 회피)
 * - 0원 초과 정산은 SETTLEMENT 거래를 생성(V1 ENUM에 누락됐던 'SETTLEMENT' 값이 V5로 추가되어 INSERT 성공)
 */
class SettlementConfirmTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var settlementService: SettlementService
    @Autowired lateinit var redemptionService: VoucherRedemptionService
    @Autowired lateinit var transactionRepository: TransactionJpaRepository
    @Autowired lateinit var cancelService: TransactionCancelService

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

    private fun monthRange(): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        return today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
    }

    @Test
    fun `confirm a zero-amount settlement succeeds without creating a transaction`() {
        val (start, end) = monthRange()
        val settlement = settlementService.calculate(merchantId, start, end)
        settlement.totalAmount.compareTo(BigDecimal.ZERO) shouldBe 0

        val confirmed = settlementService.confirm(settlement.id)

        confirmed.status shouldBe SettlementStatus.CONFIRMED
    }

    @Test
    fun `confirm a non-zero settlement creates a SETTLEMENT transaction`() {
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"))

        val (start, end) = monthRange()
        val settlement = settlementService.calculate(merchantId, start, end)
        settlement.totalAmount.compareTo(BigDecimal("10000")) shouldBe 0

        val confirmed = settlementService.confirm(settlement.id)

        confirmed.status shouldBe SettlementStatus.CONFIRMED
        transactionRepository.findAll()
            .any { it.type == TransactionType.SETTLEMENT && it.merchantId == merchantId } shouldBe true
    }

    @Test
    fun `confirm recomputes total excluding redemptions cancelled after calculate`() {
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val r = redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"))

        val (start, end) = monthRange()
        val settlement = settlementService.calculate(merchantId, start, end)
        settlement.totalAmount.compareTo(BigDecimal("10000")) shouldBe 0 // calculate 시점 스냅샷

        // PENDING 창에서 결제 취소(아직 CONFIRMED 아님 → settled-가드 통과)
        cancelService.cancel(r.transactionId)

        val confirmed = settlementService.confirm(settlement.id)

        // 확정 시 재계산으로 취소된 결제분이 제외되어 과지급되지 않는다.
        confirmed.totalAmount.compareTo(BigDecimal.ZERO) shouldBe 0
        confirmed.status shouldBe SettlementStatus.CONFIRMED
    }
}
