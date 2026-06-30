package com.commerce.integration

import com.commerce.support.TestFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.UUID

/**
 * 멱등 재시도 시 원래 응답의 상태코드가 보존되는지 검증한다.
 * purchase는 @ResponseStatus(201 CREATED) — 캐시 status를 200으로 하드코딩하던 버그로
 * 재시도 시 200으로 회귀했었다. 이제 재시도도 201을 반환해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IdempotencyStatusCodeTest {

    companion object {
        val mysql = MySQLContainer("mysql:8.0").apply {
            withDatabaseName("voucher_test")
            withUsername("test")
            withPassword("test")
            withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
            start()
        }
        val redis = GenericContainer<Nothing>("redis:7-alpine").apply {
            withExposedPorts(6379)
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }
        }
    }

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var fixtures: TestFixtures

    @Test
    fun `idempotent replay preserves original 201 CREATED status`() {
        val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
        val member = fixtures.createMember()

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Idempotency-Key", UUID.randomUUID().toString())
        }
        val body = """{"memberId": ${member.id}, "regionId": ${region.id}, "faceValue": 50000}"""
        val request = HttpEntity(body, headers)
        val url = "/api/v1/vouchers/purchase"

        val first = restTemplate.postForEntity(url, request, String::class.java)
        val replay = restTemplate.postForEntity(url, request, String::class.java)

        first.statusCode.value() shouldBe 201
        // 재시도는 캐시된 응답을 반환하되 원래 상태코드(201)를 보존해야 한다.
        replay.statusCode.value() shouldBe 201
    }
}
