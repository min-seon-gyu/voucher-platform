package com.commerce.integration

import com.commerce.config.JwtTokenProvider
import com.commerce.member.application.MemberService
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.math.BigDecimal

/**
 * 로그인 후 정지된 회원은 토큰이 유효해도 자금 이동(결제)을 할 수 없어야 한다.
 */
@AutoConfigureMockMvc
class MemberSuspendedMoneyOpTest : IntegrationTestSupport() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var voucherRepository: VoucherJpaRepository

    @Test
    fun `suspended member cannot redeem even with a valid token`() {
        val region = fixtures.createRegion()
        val member = fixtures.createMember()
        val merchant = fixtures.createMerchant(region, fixtures.createMember())
        val voucher = fixtures.issueVoucher(member.id, region.id, BigDecimal("50000"))

        memberService.suspend(member.id) // ACTIVE → SUSPENDED
        val token = jwtTokenProvider.generateToken(member.id, "USER")

        mockMvc.post("/api/v1/vouchers/${voucher.id}/redeem") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"merchantId": ${merchant.id}, "amount": 10000}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("MEMBER_NOT_ACTIVE") }
        }

        // 결제가 일어나지 않아 잔액 불변
        voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("50000")) shouldBe 0
    }
}
