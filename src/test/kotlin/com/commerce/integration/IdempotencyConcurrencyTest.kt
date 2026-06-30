package com.commerce.integration

import com.commerce.config.JwtTokenProvider
import com.commerce.support.TestFixtures
import com.commerce.transaction.domain.TransactionStatus
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IdempotencyConcurrencyTest {

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
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired lateinit var voucherRepository: VoucherJpaRepository
    @Autowired lateinit var transactionRepository: TransactionJpaRepository

    private var regionId: Long = 0
    private var memberId: Long = 0
    private var merchantId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
        val member = fixtures.createMember()
        val merchantOwner = fixtures.createMember()
        val merchant = fixtures.createMerchant(region, merchantOwner)
        regionId = region.id
        memberId = member.id
        merchantId = merchant.id
    }

    /**
     * 같은 Idempotency-Key로 동시에 여러 번 결제 요청이 들어와도,
     * 멱등성은 "정확히 1회"만 처리되도록 보장해야 한다.
     * (현재 구현은 preHandle 통과 후 처리하므로 동시 요청이 모두 통과 → 이중 차감)
     */
    @Test
    fun `same Idempotency-Key concurrent redeem should execute exactly once`() {
        // given: 50,000원 상품권
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val idempotencyKey = UUID.randomUUID().toString()
        val threadCount = 10

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Idempotency-Key", idempotencyKey)
            set("Authorization", "Bearer ${jwtTokenProvider.generateToken(memberId, "USER")}")
        }
        val request = HttpEntity("""{"merchantId": $merchantId, "amount": 10000}""", headers)
        val url = "/api/v1/vouchers/${voucher.id}/redeem"

        // when: 동일 멱등키로 10건 동시 결제 시도
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        val success2xx = AtomicInteger(0)
        val futures = (1..threadCount).map {
            executor.submit {
                latch.await()
                val response = restTemplate.postForEntity(url, request, String::class.java)
                if (response.statusCode.is2xxSuccessful) success2xx.incrementAndGet()
            }
        }
        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        // then: 멱등키가 같으므로 단 1회(10,000원)만 차감되어 잔액 40,000원이어야 함
        val updated = voucherRepository.findById(voucher.id).get()
        updated.balance.compareTo(BigDecimal("40000")) shouldBe 0

        // 완료 거래 = 발행 1건 + 결제 1건 = 2건 (이중 처리면 결제가 여러 건으로 늘어남)
        val completedTxCount = transactionRepository.countByVoucherIdAndStatus(
            voucher.id, TransactionStatus.COMPLETED
        )
        completedTxCount shouldBe 2
    }
}
