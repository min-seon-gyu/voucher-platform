package com.commerce.integration

import com.commerce.config.JwtTokenProvider
import com.commerce.point.domain.PointAccount
import com.commerce.point.domain.PointTransaction
import com.commerce.point.domain.PointTransactionType
import com.commerce.point.infrastructure.PointAccountJpaRepository
import com.commerce.point.infrastructure.PointTransactionJpaRepository
import com.commerce.support.IntegrationTestSupport
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
    @Autowired lateinit var pointAccountRepository: PointAccountJpaRepository
    @Autowired lateinit var pointTransactionRepository: PointTransactionJpaRepository

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
            jsonPath("$.code") { value("ACCESS_DENIED") }
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
        val memberId = 9100L

        // Seed a PointAccount and one EARN transaction directly (no full redeem flow needed).
        pointAccountRepository.save(
            PointAccount(memberId = memberId, balance = BigDecimal("500"))
        )
        pointTransactionRepository.save(
            PointTransaction(
                memberId = memberId,
                type = PointTransactionType.EARN,
                amount = BigDecimal("500"),
                balanceAfter = BigDecimal("500"),
                sourceTransactionId = 999_001L,
            )
        )

        val token = jwtTokenProvider.generateToken(memberId, "USER")

        mockMvc.get("/api/v1/members/$memberId/points") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.memberId") { value(memberId) }
            jsonPath("$.balance") { value(500) }
            jsonPath("$.history.length()") { value(1) }
            jsonPath("$.history[0].type") { value("EARN") }
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
            jsonPath("$.code") { value("POINT_ACCOUNT_NOT_FOUND") }
        }
    }
}
