package com.commerce.integration

import com.commerce.config.JwtTokenProvider
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal

/**
 * 상품권 자금 이동(redeem/refund/withdraw) 및 조회(list/detail)의 인증·소유권 강제를 검증한다.
 * (이전: 변경 엔드포인트가 무인증 IDOR였고, 조회는 memberId+balance를 공개 노출)
 */
@AutoConfigureMockMvc
class VoucherAuthorizationTest : IntegrationTestSupport() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var voucherRepository: VoucherJpaRepository

    private var voucherId: Long = 0
    private var ownerId: Long = 0
    private var merchantId: Long = 0
    private var otherMemberId: Long = 0
    private var otherVoucherId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion()
        val owner = fixtures.createMember()
        val merchant = fixtures.createMerchant(region, fixtures.createMember())
        val voucher = fixtures.issueVoucher(owner.id, region.id, BigDecimal("50000"))
        val otherMember = fixtures.createMember()
        val otherVoucher = fixtures.issueVoucher(otherMember.id, region.id, BigDecimal("30000"))
        ownerId = owner.id
        merchantId = merchant.id
        voucherId = voucher.id
        otherMemberId = otherMember.id
        otherVoucherId = otherVoucher.id
    }

    private fun redeemBody() = """{"merchantId": $merchantId, "amount": 10000}"""

    // ── 자금 이동: redeem / refund / withdraw ────────────────────────────────

    @Test
    fun `redeem without token returns 401`() {
        mockMvc.post("/api/v1/vouchers/$voucherId/redeem") {
            contentType = MediaType.APPLICATION_JSON
            content = redeemBody()
        }.andExpect { status { isUnauthorized() } }

        voucherRepository.findById(voucherId).get().balance.compareTo(BigDecimal("50000")) shouldBe 0
    }

    @Test
    fun `redeem by non-owner returns 404 and does not debit balance`() {
        // 존재성 오라클 차단: 타인 소유 바우처는 미존재와 동일하게 404로 수렴한다.
        val attackerToken = jwtTokenProvider.generateToken(otherMemberId, "USER")
        mockMvc.post("/api/v1/vouchers/$voucherId/redeem") {
            header("Authorization", "Bearer $attackerToken")
            contentType = MediaType.APPLICATION_JSON
            content = redeemBody()
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("ENTITY_NOT_FOUND") }
        }

        voucherRepository.findById(voucherId).get().balance.compareTo(BigDecimal("50000")) shouldBe 0
    }

    @Test
    fun `redeem by owner succeeds and debits balance`() {
        val ownerToken = jwtTokenProvider.generateToken(ownerId, "USER")
        mockMvc.post("/api/v1/vouchers/$voucherId/redeem") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = redeemBody()
        }.andExpect { status { isOk() } }

        voucherRepository.findById(voucherId).get().balance.compareTo(BigDecimal("40000")) shouldBe 0
    }

    @Test
    fun `refund without token returns 401`() {
        mockMvc.post("/api/v1/vouchers/$voucherId/refund")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `withdraw without token returns 401`() {
        mockMvc.post("/api/v1/vouchers/$voucherId/withdraw")
            .andExpect { status { isUnauthorized() } }
    }

    // ── 조회: list / detail (정보 노출 차단) ─────────────────────────────────

    @Test
    fun `voucher detail by non-owner returns 404 with no existence oracle`() {
        // 미존재 id와 동일한 404 ENTITY_NOT_FOUND를 반환해 voucher id 열거를 차단한다.
        val token = jwtTokenProvider.generateToken(otherMemberId, "USER")
        mockMvc.get("/api/v1/vouchers/$voucherId") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("ENTITY_NOT_FOUND") }
        }
    }

    @Test
    fun `voucher detail by owner returns 200`() {
        val token = jwtTokenProvider.generateToken(ownerId, "USER")
        mockMvc.get("/api/v1/vouchers/$voucherId") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(voucherId) }
        }
    }

    @Test
    fun `voucher list scopes to caller even when filtering another member`() {
        val token = jwtTokenProvider.generateToken(ownerId, "USER")
        // 비-ADMIN이 타인 memberId로 필터해도 자신의 바우처만 반환되어야 한다(정보 노출 차단).
        mockMvc.get("/api/v1/vouchers?memberId=$otherMemberId") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.content.length()") { value(1) }
            jsonPath("$.data.content[0].id") { value(voucherId) }
        }
    }
}
