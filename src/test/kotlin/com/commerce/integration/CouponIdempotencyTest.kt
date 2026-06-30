package com.commerce.integration

import com.commerce.config.JwtTokenProvider
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.promotion.domain.CouponStatus
import com.commerce.promotion.infrastructure.CouponJpaRepository
import com.commerce.promotion.infrastructure.CouponRedemptionJpaRepository
import com.commerce.promotion.infrastructure.PromotionBudgetManager
import com.commerce.support.TestFixtures
import com.commerce.transaction.domain.TransactionStatus
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import com.fasterxml.jackson.databind.ObjectMapper
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
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class CouponIdempotencyTest {

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
    @Autowired lateinit var couponRepository: CouponJpaRepository
    @Autowired lateinit var couponRedemptionRepository: CouponRedemptionJpaRepository
    @Autowired lateinit var transactionRepository: TransactionJpaRepository
    @Autowired lateinit var verificationService: LedgerVerificationService
    @Autowired lateinit var promotionBudgetManager: PromotionBudgetManager
    @Autowired lateinit var objectMapper: ObjectMapper

    private var regionId: Long = 0
    private var memberId: Long = 0
    private var merchantId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
        val member = fixtures.createMember()
        val merchant = fixtures.createMerchant(region, fixtures.createMember())
        regionId = region.id
        memberId = member.id
        merchantId = merchant.id
    }

    @Test
    fun `same Idempotency-Key concurrent coupon-redeem executes exactly once`() {
        // given: 50,000원 상품권 + 3,000원 정액 할인 쿠폰
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        val promotion = fixtures.createPromotion(
            discountType = com.commerce.promotion.domain.DiscountType.FIXED,
            discountValue = BigDecimal("3000"), budgetLimit = BigDecimal("1000000"),
        )
        val coupon = fixtures.issueCoupon(promotion.id, memberId)

        // when: 동일 Idempotency-Key로 10건 동시 결제 시도 (T=10,000, D=3,000, 차감=7,000)
        val idempotencyKey = UUID.randomUUID().toString()
        val threadCount = 10
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Idempotency-Key", idempotencyKey)
            set("Authorization", "Bearer ${jwtTokenProvider.generateToken(memberId, "USER")}")
        }
        val body = """{"merchantId": $merchantId, "amount": 10000, "couponId": ${coupon.id}}"""
        val request = HttpEntity(body, headers)
        val url = "/api/v1/vouchers/${voucher.id}/redeem"

        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        val success2xx = AtomicInteger(0)
        val conflict409 = AtomicInteger(0)
        val successTransactionId = AtomicLong(-1L)
        val futures = (1..threadCount).map {
            executor.submit {
                latch.await()
                val response = restTemplate.postForEntity(url, request, String::class.java)
                when {
                    response.statusCode.is2xxSuccessful -> {
                        success2xx.incrementAndGet()
                        response.body?.let { rb ->
                            val txId = objectMapper.readTree(rb).get("data").get("transactionId").asLong()
                            successTransactionId.set(txId)
                        }
                    }
                    response.statusCode.value() == 409 -> conflict409.incrementAndGet()
                }
            }
        }
        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        // then: 단 1회(T-D=7,000)만 차감 → 잔액 43,000
        voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("43000")) shouldBe 0

        // 쿠폰 1회 사용 + CouponRedemption 비취소 1건
        couponRepository.findById(coupon.id).get().status shouldBe CouponStatus.REDEEMED
        couponRedemptionRepository.countByMemberIdAndPromotionIdAndCancelledFalse(memberId, promotion.id) shouldBe 1L

        // 완료 거래 = 발행 1 + 결제 1 = 2 (이중 처리면 결제 건이 늘어남)
        transactionRepository.countByVoucherIdAndStatus(voucher.id, TransactionStatus.COMPLETED) shouldBe 2

        // 원장 정합성: 차변 = 대변
        verificationService.verify().isBalanced shouldBe true

        // HTTP 레벨 멱등성 — 인터셉터 설계: 처리 중 동시 중복 요청은 409 CONFLICT,
        // 완료 후 순차 재시도에는 캐시된 2xx를 반환한다.
        // 정확히 1건이 비즈니스 로직 실행, 9건은 처리 중 중복으로 거절됨
        success2xx.get() shouldBe 1
        conflict409.get() shouldBe 9
        (success2xx.get() + conflict409.get()) shouldBe threadCount

        // 프로모션 예산이 정확히 1회(3,000원)만 소비됨
        promotionBudgetManager.consumed(promotion.id) shouldBe 3000L
        couponRedemptionRepository.sumActiveDiscountByPromotion(promotion.id)
            .compareTo(BigDecimal("3000")) shouldBe 0

        // 캐시 경로 검증: 동시 블래스트 완료 후 동일 키로 순차 재시도 → 캐시된 2xx 반환
        val retryResponse = restTemplate.postForEntity(url, request, String::class.java)
        retryResponse.statusCode.is2xxSuccessful shouldBe true
        val retryTransactionId = objectMapper.readTree(retryResponse.body).get("data").get("transactionId").asLong()
        retryTransactionId shouldBe successTransactionId.get()

        // 재시도 후에도 DB 상태 불변 — 추가 처리 없음
        voucherRepository.findById(voucher.id).get().balance.compareTo(BigDecimal("43000")) shouldBe 0
        couponRedemptionRepository.countByMemberIdAndPromotionIdAndCancelledFalse(memberId, promotion.id) shouldBe 1L
    }
}
