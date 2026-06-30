package com.commerce.merchant.domain

import com.commerce.common.exception.BusinessException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class SettlementTest {

    private fun newSettlement() = Settlement(
        merchantId = 1L,
        periodStart = LocalDate.of(2026, 1, 1),
        periodEnd = LocalDate.of(2026, 1, 31),
        totalAmount = BigDecimal("10000"),
    )

    @Test
    fun `confirmed settlement can be marked paid`() {
        val settlement = newSettlement()
        settlement.confirm()

        settlement.pay()

        settlement.status shouldBe SettlementStatus.PAID
    }

    @Test
    fun `pending settlement cannot be paid without confirmation`() {
        val settlement = newSettlement()

        shouldThrow<BusinessException> { settlement.pay() }
    }

    @Test
    fun `paid settlement cannot be paid again`() {
        val settlement = newSettlement()
        settlement.confirm()
        settlement.pay()

        shouldThrow<BusinessException> { settlement.pay() }
    }

    @Test
    fun `disputed then confirmed settlement can be paid`() {
        val settlement = newSettlement()
        settlement.dispute("금액 불일치")
        settlement.confirm()

        settlement.pay()

        settlement.status shouldBe SettlementStatus.PAID
    }
}
