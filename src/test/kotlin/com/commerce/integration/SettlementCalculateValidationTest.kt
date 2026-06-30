package com.commerce.integration

import com.commerce.config.JwtTokenProvider
import com.commerce.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * 정산 생성에서 periodStart/periodEnd를 부분 지정하면(둘 중 하나만) 조용히 region 파생으로 폴백하지 않고
 * 400으로 거부하는지 검증한다.
 */
@AutoConfigureMockMvc
class SettlementCalculateValidationTest : IntegrationTestSupport() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    fun `calculate with only periodStart is rejected with 400 INVALID_INPUT`() {
        val adminToken = jwtTokenProvider.generateToken(1L, "ADMIN")
        mockMvc.post("/api/v1/settlements/calculate") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"merchantId": 1, "periodStart": "2026-06-01"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_INPUT") }
        }
    }
}
