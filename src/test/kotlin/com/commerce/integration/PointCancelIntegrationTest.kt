package com.commerce.integration

import com.commerce.ledger.application.LedgerService
import com.commerce.ledger.application.LedgerVerificationService
import com.commerce.ledger.domain.AccountCode
import com.commerce.ledger.domain.LedgerEntrySide
import com.commerce.ledger.domain.LedgerEntryType
import com.commerce.order.application.OrderService
import com.commerce.point.domain.PointTransactionType
import com.commerce.point.infrastructure.PointAccountJpaRepository
import com.commerce.point.infrastructure.PointTransactionJpaRepository
import com.commerce.support.IntegrationTestSupport
import com.commerce.support.TestFixtures
import com.commerce.transaction.domain.TransactionType
import com.commerce.transaction.infrastructure.TransactionJpaRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.UUID

/**
 * 주문 취소(보상) 트랜잭션이 결제로 적립된 포인트까지 역분개하는지 검증한다.
 * 불변식: 취소 후 isBalanced && pointBalanceMatches, PointAccount.balance는 적립 전(0)으로 복귀,
 *        원 EARN 행 보존 + CANCELLATION 원장쌍 + CANCEL PointTransaction 추가.
 */
class PointCancelIntegrationTest : IntegrationTestSupport() {

    @Autowired lateinit var fixtures: TestFixtures
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var pointAccountRepository: PointAccountJpaRepository
    @Autowired lateinit var pointTransactionRepository: PointTransactionJpaRepository
    @Autowired lateinit var transactionRepository: TransactionJpaRepository
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var verificationService: LedgerVerificationService

    private var memberId: Long = 0
    private var sellerId: Long = 0

    @BeforeEach
    fun setup() {
        val region = fixtures.createRegion(code = UUID.randomUUID().toString().take(2).uppercase())
        val member = fixtures.createMember()
        val seller = fixtures.createSeller(region, fixtures.createMember())
        memberId = member.id
        sellerId = seller.id
    }

    @Test
    fun `order payment earns points then cancel reverses them and restores point balance`() {
        // 적립률 0.01: 30,000 결제 → 300 포인트 적립
        val order = fixtures.sellerSale(memberId, sellerId, BigDecimal("30000"))
        val paymentTxId = order.paymentTransactionId!!

        // 적립 직후: balance 300
        pointAccountRepository.findByMemberId(memberId)!!.balance.compareTo(BigDecimal("300")) shouldBe 0

        // 주문 취소 → 포인트 역분개
        orderService.cancelOrder(memberId, order.id)
        val compensatingTxId = transactionRepository.findAll()
            .first { it.type == TransactionType.ORDER_CANCEL && it.originalTransactionId == paymentTxId }.id

        // 1) PointAccount.balance 적립 전 값(0)으로 복귀
        pointAccountRepository.findByMemberId(memberId)!!.balance.compareTo(BigDecimal.ZERO) shouldBe 0

        // 2) CANCEL PointTransaction 적재(원 EARN 행 보존 + CANCEL 추가)
        val pointTxs = pointTransactionRepository.findBySourceTransactionId(paymentTxId)
        pointTxs.count { it.type == PointTransactionType.EARN } shouldBe 1
        pointTxs.count {
            it.type == PointTransactionType.CANCEL && it.amount.compareTo(BigDecimal("300")) == 0
        } shouldBe 1

        // 3) 역분개 원장쌍: DEBIT POINT_FUNDING 300 / CREDIT POINT_BALANCE 300 (CANCELLATION)
        val cancelEntries = ledgerService.getEntriesByTransactionId(compensatingTxId)
        cancelEntries.count {
            it.account == AccountCode.POINT_FUNDING &&
                it.side == LedgerEntrySide.DEBIT &&
                it.amount.compareTo(BigDecimal("300")) == 0 &&
                it.entryType == LedgerEntryType.CANCELLATION
        } shouldBe 1
        cancelEntries.count {
            it.account == AccountCode.POINT_BALANCE &&
                it.side == LedgerEntrySide.CREDIT &&
                it.amount.compareTo(BigDecimal("300")) == 0 &&
                it.entryType == LedgerEntryType.CANCELLATION
        } shouldBe 1

        // 4) 글로벌 정합성: isBalanced && pointBalanceMatches
        val verify = verificationService.verify()
        verify.isBalanced shouldBe true
        verify.pointBalanceMatches shouldBe true
    }
}
