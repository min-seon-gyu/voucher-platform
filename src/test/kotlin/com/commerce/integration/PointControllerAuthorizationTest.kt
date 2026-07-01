package com.commerce.integration

import com.commerce.config.JwtTokenProvider
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.math.BigDecimal

@AutoConfigureMockMvc
class PointControllerAuthorizationTest : IntegrationTestSupport() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired lateinit var fixtures: TestFixtures

    // ── Fix 1: cross-member guard ────────────────────────────────────────────

    @Test
    fun `cross-member access returns 403 ACCESS_DENIED without any DB read`() {
        val memberA = 9001L
        val memberB = 9002L
        val token = jwtTokenProvider.generateToken(memberA, "USER")

        mockMvc.get("/api/v1/members/$memberB/points") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("ACCESS_DENIED") }
        }
    }

    @Test
    fun `no token returns 401 UNAUTHORIZED`() {
        mockMvc.get("/api/v1/members/9001/points")
            .andExpect { status { isUnauthorized() } }
    }

    // ── Fix 1: own-member happy path ─────────────────────────────────────────

    @Test
    fun `own points returns 200 with balance and history`() {
        // Use the real earn path so the POINT_BALANCE/POINT_FUNDING ledger legs are posted
        // atomically with the PointAccount update — keeping the global invariant intact.
        val region = fixtures.createRegion()
        val owner = fixtures.createMember()
        val seller = fixtures.createSeller(region, owner)
        val member = fixtures.createMember()
        // 주문 결제 50,000 × 1% 적립 = 500 포인트 (원장 POINT 2-leg 원자 기록)
        fixtures.sellerSale(member.id, seller.id, BigDecimal("50000"))

        val token = jwtTokenProvider.generateToken(member.id, "USER")

        mockMvc.get("/api/v1/members/${member.id}/points") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.memberId") { value(member.id) }
            jsonPath("$.data.balance") { value(500) }
            jsonPath("$.data.history.length()") { value(1) }
            jsonPath("$.data.history[0].type") { value("EARN") }
        }
    }

    @Test
    fun `own points returns 404 POINT_ACCOUNT_NOT_FOUND when account does not exist`() {
        // Use a memberId that has never had an account seeded.
        val memberId = 9999L
        val token = jwtTokenProvider.generateToken(memberId, "USER")

        mockMvc.get("/api/v1/members/$memberId/points") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("POINT_ACCOUNT_NOT_FOUND") }
        }
    }
}
