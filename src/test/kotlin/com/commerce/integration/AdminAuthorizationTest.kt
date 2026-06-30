package com.commerce.integration

import com.commerce.config.JwtTokenProvider
import com.commerce.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 특권(ADMIN) 엔드포인트가 시큐리티 필터 체인에서 실제로 보호되는지 검증한다.
 * - 토큰 없음 → 401
 * - USER 역할 → 403
 * - ADMIN 역할 → 통과(200)
 * 그리고 공개 엔드포인트는 여전히 토큰 없이 접근 가능해야 한다(과잠금 회귀 방지).
 */
@AutoConfigureMockMvc
class AdminAuthorizationTest : IntegrationTestSupport() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    fun `admin ledger endpoint without token returns 401`() {
        mockMvc.get("/api/v1/admin/ledger/balance/global")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `admin ledger endpoint with USER role returns 403`() {
        val token = jwtTokenProvider.generateToken(1L, "USER")
        mockMvc.get("/api/v1/admin/ledger/balance/global") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin ledger endpoint with ADMIN role returns 200`() {
        val token = jwtTokenProvider.generateToken(1L, "ADMIN")
        mockMvc.get("/api/v1/admin/ledger/balance/global") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `member suspend without token returns 401`() {
        mockMvc.post("/api/v1/members/1/suspend")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `member suspend with USER role returns 403`() {
        val token = jwtTokenProvider.generateToken(1L, "USER")
        mockMvc.post("/api/v1/members/1/suspend") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `settlement calculate without token returns 401`() {
        mockMvc.post("/api/v1/settlements/calculate") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"merchantId": 1}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `public voucher list stays accessible without token`() {
        mockMvc.get("/api/v1/vouchers")
            .andExpect { status { isOk() } }
    }
}
