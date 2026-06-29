package com.commerce.integration

import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.point.infrastructure.PointAccountJpaRepository
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.voucher.application.VoucherRedemptionService
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID

class PointReconciliationTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var redemptionService: VoucherRedemptionService
    @Autowired lateinit var pointAccountRepository: PointAccountJpaRepository
    @Autowired lateinit var verificationService: LedgerVerificationService
    @Autowired lateinit var transactionTemplate: TransactionTemplate

    private var regionId: Long = 0
    private var memberId: Long = 0
    private var merchantId: Long = 0

    /** Captured actual balance immediately before intentional corruption; null if no corruption occurred. */
    private var preCorruptBalance: BigDecimal? = null

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
        val member = fixtures.createMember()
        val owner = fixtures.createMember()
        val merchant = fixtures.createMerchant(region, owner)
        regionId = region.id
        memberId = member.id
        merchantId = merchant.id
    }

    @AfterEach
    fun cleanup() {
        // Restore the point account only if corruption actually happened in the test body.
        // If the test threw before reaching the capture step, preCorruptBalance stays null
        // and no restore is needed (the account is still in its correct earned state).
        preCorruptBalance?.let { restore ->
            transactionTemplate.executeWithoutResult {
                pointAccountRepository.overwriteBalance(memberId, restore)
            }
        }
        preCorruptBalance = null
    }

    @Test
    fun `point invariant holds after earn and breaks when the cache is corrupted`() {
        val voucher = fixtures.issueVoucher(memberId, regionId, BigDecimal("50000"))
        redemptionService.redeem(voucher.id, merchantId, BigDecimal("20000")) // +200 points

        val ok = verificationService.verify()
        ok.pointBalanceMatches shouldBe true
        ok.isBalanced shouldBe true

        // Capture the real balance before corruption so @AfterEach can restore it exactly.
        preCorruptBalance = transactionTemplate.execute {
            pointAccountRepository.findByMemberId(memberId)?.balance
        }

        // 캐시 잔액을 의도적으로 오염시켜 원장 net(200)과 불일치(201)로 만든다.
        transactionTemplate.executeWithoutResult {
            pointAccountRepository.overwriteBalance(memberId, BigDecimal("201"))
        }

        val broken = verificationService.verify()
        broken.pointBalanceMatches shouldBe false
        broken.isBalanced shouldBe false
    }
}
