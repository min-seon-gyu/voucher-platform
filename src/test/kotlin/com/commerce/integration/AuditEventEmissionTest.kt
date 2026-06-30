package com.commerce.integration

import com.commerce.common.audit.AuditLog
import com.commerce.common.audit.AuditLogRepository
import com.commerce.common.audit.AuditSeverity
import com.commerce.common.audit.DirectOutboxRelay
import com.commerce.member.application.MemberService
import com.commerce.merchant.application.MerchantService
import com.commerce.merchant.application.RegisterMerchantRequest
import com.commerce.region.application.RegionService
import com.commerce.region.interfaces.dto.CreateRegionRequest
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

/**
 * HIGH_EVENTS로 선언만 되어 있고 실제로는 발행되지 않던 감사 이벤트들이
 * 이제 서비스에서 발행되어 감사 로그로 기록되는지 검증한다.
 * (기존: reject/terminate/suspend/withdraw/policy-change가 publishEvent를 호출하지 않음)
 */
class AuditEventEmissionTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var merchantService: MerchantService
    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var regionService: RegionService
    @Autowired lateinit var auditLogRepository: AuditLogRepository
    @Autowired lateinit var directRelay: DirectOutboxRelay

    // 비핵심(HIGH) 감사는 outbox → relay로 비동기 적용된다. 테스트(Kafka 비활성)에선 직접 relay를 수동 flush 후 조회.
    private fun auditOf(aggregateType: String, aggregateId: Long, eventType: String): AuditLog? {
        directRelay.relayOnce()
        return auditLogRepository.findByAggregateTypeAndAggregateId(aggregateType, aggregateId)
            .find { it.eventType == eventType }
    }

    @Test
    fun `merchant rejection emits HIGH audit log`() {
        val region = fixtures.createRegion()
        val owner = fixtures.createMember()
        val merchant = merchantService.register(
            RegisterMerchantRequest(
                name = "거절가게",
                businessNumber = "111-11-11111",
                category = "RESTAURANT",
                regionId = region.id,
                ownerId = owner.id,
            )
        )

        merchantService.reject(merchant.id)

        val audit = auditOf("MERCHANT", merchant.id, "MERCHANT_REJECTED")
        audit.shouldNotBeNull()
        audit.severity shouldBe AuditSeverity.HIGH
    }

    @Test
    fun `merchant termination emits HIGH audit log`() {
        val region = fixtures.createRegion()
        val merchant = fixtures.createMerchant(region, fixtures.createMember()) // APPROVED

        merchantService.terminate(merchant.id)

        val audit = auditOf("MERCHANT", merchant.id, "MERCHANT_TERMINATED")
        audit.shouldNotBeNull()
        audit.severity shouldBe AuditSeverity.HIGH
    }

    @Test
    fun `member suspension emits HIGH audit log`() {
        val member = fixtures.createMember() // ACTIVE

        memberService.suspend(member.id)

        val audit = auditOf("MEMBER", member.id, "MEMBER_SUSPENDED")
        audit.shouldNotBeNull()
        audit.severity shouldBe AuditSeverity.HIGH
    }

    @Test
    fun `member withdrawal emits HIGH audit log`() {
        val member = fixtures.createMember() // ACTIVE

        memberService.withdraw(member.id)

        val audit = auditOf("MEMBER", member.id, "MEMBER_WITHDRAWN")
        audit.shouldNotBeNull()
        audit.severity shouldBe AuditSeverity.HIGH
    }

    @Test
    fun `region policy change emits HIGH audit log with before and after state`() {
        val region = fixtures.createRegion()

        regionService.updatePolicy(
            region.id,
            CreateRegionRequest(
                name = region.name,
                regionCode = region.regionCode,
                discountRate = BigDecimal("0.15"),
                purchaseLimitPerPerson = BigDecimal("700000"),
                monthlyIssuanceLimit = BigDecimal("20000000000"),
                refundThresholdRatio = BigDecimal("0.60"),
                settlementPeriod = "WEEKLY",
            ),
        )

        val audit = auditOf("REGION", region.id, "REGION_POLICY_CHANGED")
        audit.shouldNotBeNull()
        audit.severity shouldBe AuditSeverity.HIGH
        audit.currentState.shouldNotBeNull()
    }
}
