package com.commerce.integration

import com.commerce.merchant.domain.SettlementStatus
import com.commerce.merchant.infrastructure.SettlementJpaRepository
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.voucher.application.VoucherRedemptionService
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.test.JobLauncherTestUtils
import org.springframework.batch.test.context.SpringBatchTest
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * 결산 주기 일괄 정산 배치 E2E. APPROVED 가맹점 전체를 청크 처리해 지역 정산주기(기본 MONTHLY) 구간의
 * 정산을 생성하고, 검증 스텝까지 COMPLETED 되는지 확인한다. 0원 스킵과 재실행 멱등도 함께 검증한다.
 *
 * DB가 테스트 간 공유되므로(정리 없음) 배치는 타 테스트의 가맹점까지 처리한다 — 단언은 이 테스트가 만든
 * 가맹점(merchantId)으로만 스코프한다.
 */
@SpringBatchTest
class SettlementBatchJobTest : IntegrationTestSupport() {

    @Autowired lateinit var jobLauncherTestUtils: JobLauncherTestUtils
    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var redemptionService: VoucherRedemptionService
    @Autowired lateinit var settlementRepository: SettlementJpaRepository

    private val kst = ZoneId.of("Asia/Seoul")

    @Test
    fun `batch creates settlements for approved merchants with sales and skips zero, idempotent on rerun`() {
        val today = LocalDate.now(kst)
        val region = fixtures.createRegion() // MONTHLY → 이번 달 구간, 오늘 결제는 구간 내
        val member = fixtures.createMember()

        // 매출 있는 가맹점: 10,000 + 5,000 결제(COMPLETED REDEMPTION) → 정산 15,000
        val merchantWithSales = fixtures.createMerchant(region, member)
        val voucher = fixtures.issueVoucher(member.id, region.id, BigDecimal("50000"))
        redemptionService.redeem(voucher.id, merchantWithSales.id, BigDecimal("10000"))
        redemptionService.redeem(voucher.id, merchantWithSales.id, BigDecimal("5000"))

        // 매출 없는 가맹점: 정산이 만들어지면 안 됨(0원 스킵)
        val merchantNoSales = fixtures.createMerchant(region, fixtures.createMember())

        // 1차 실행
        val firstRun = jobLauncherTestUtils.launchJob(
            JobParametersBuilder()
                .addString("referenceDate", today.toString())
                .addLong("run.id", 1L)
                .toJobParameters()
        )
        firstRun.status shouldBe BatchStatus.COMPLETED

        val salesSettlements = settlementRepository.findAll().filter { it.merchantId == merchantWithSales.id }
        salesSettlements.size shouldBe 1
        salesSettlements.first().totalAmount.compareTo(BigDecimal("15000")) shouldBe 0
        salesSettlements.first().status shouldBe SettlementStatus.PENDING

        // 0원 가맹점은 정산 없음
        settlementRepository.findAll().none { it.merchantId == merchantNoSales.id }.shouldBeTrue()

        // 2차 실행(같은 referenceDate, 새 인스턴스): 멱등 — 중복 생성 없이 COMPLETED
        val secondRun = jobLauncherTestUtils.launchJob(
            JobParametersBuilder()
                .addString("referenceDate", today.toString())
                .addLong("run.id", 2L)
                .toJobParameters()
        )
        secondRun.status shouldBe BatchStatus.COMPLETED

        settlementRepository.findAll().count { it.merchantId == merchantWithSales.id } shouldBe 1
    }
}
