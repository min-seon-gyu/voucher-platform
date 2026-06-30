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
import org.springframework.test.web.servlet.post
import java.math.BigDecimal

/**
 * 상품권 자금 이동 엔드포인트의 인증/소유권 강제를 검증한다.
 * (이전: redeem/refund/withdraw가 permitAll이고 본문 memberId만 비교 → 무토큰 IDOR로 타인 잔액 탈취 가능)
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

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion()
        val owner = fixtures.createMember()
        val merchant = fixtures.createMerchant(region, fixtures.createMember())
        val voucher = fixtures.issueVoucher(owner.id, region.id, BigDecimal("50000"))
        ownerId = owner.id
        merchantId = merchant.id
        voucherId = voucher.id
    }

    private fun redeemBody() = """{"merchantId": $merchantId, "amount": 10000}"""

    @Test
    fun `redeem without token returns 401`() {
        mockMvc.post("/api/v1/vouchers/$voucherId/redeem") {
            contentType = MediaType.APPLICATION_JSON
            content = redeemBody()
        }.andExpect { status { isUnauthorized() } }

        voucherRepository.findById(voucherId).get().balance.compareTo(BigDecimal("50000")) shouldBe 0
    }

    @Test
    fun `redeem by non-owner returns 403 and does not debit balance`() {
        val attackerToken = jwtTokenProvider.generateToken(ownerId + 99_999, "USER")
        mockMvc.post("/api/v1/vouchers/$voucherId/redeem") {
            header("Authorization", "Bearer $attackerToken")
            contentType = MediaType.APPLICATION_JSON
            content = redeemBody()
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("ACCESS_DENIED") }
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
}
