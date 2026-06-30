package com.commerce.integration

import com.commerce.common.audit.AuditSeverity
import com.commerce.common.audit.OutboxEventRepository
import com.commerce.member.application.MemberService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.voucher.application.VoucherRedemptionService
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

/**
 * OutboxRecorder가 비핵심(HIGH/MEDIUM) 이벤트를 비즈니스 tx 内 outbox에 캡처하고,
 * CRITICAL 이벤트는 outbox로 보내지 않음(동기 처리)을 검증한다.
 */
class OutboxRecorderTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var redemptionService: VoucherRedemptionService
    @Autowired lateinit var outboxRepository: OutboxEventRepository

    private var regionId: Long = 0
    private var merchantId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion()
        val merchant = fixtures.createMerchant(region, fixtures.createMember())
        regionId = region.id
        merchantId = merchant.id
    }

    @Test
    fun `high event is captured in outbox as unpublished`() {
        val member = fixtures.createMember()

        memberService.suspend(member.id) // MEMBER_SUSPENDED (HIGH)

        val row = outboxRepository.findAll()
            .find { it.aggregateType == "MEMBER" && it.aggregateId == member.id && it.eventType == "MEMBER_SUSPENDED" }
        row.shouldNotBeNull()
        row.severity shouldBe AuditSeverity.HIGH
        row.published shouldBe false
    }

    @Test
    fun `critical events are never written to the outbox`() {
        val member = fixtures.createMember()
        val voucher = fixtures.issueVoucher(member.id, regionId, BigDecimal("50000")) // VOUCHER_ISSUED (CRITICAL)
        redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000")) // VOUCHER_REDEEMED (CRITICAL)

        // OutboxRecorder는 CRITICAL을 건너뛴다(AuditEventListener가 동기 기록).
        outboxRepository.findAll().none { it.severity == AuditSeverity.CRITICAL } shouldBe true
        outboxRepository.findAll().none { it.eventType == "VOUCHER_REDEEMED" } shouldBe true
    }
}
