package com.commerce.ledger.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AccountCodeAndEntryTypeTest {

    @Test
    fun `new account codes exist with Korean descriptions`() {
        AccountCode.PROMOTION_FUNDING.description shouldBe "프로모션 출연금"
        AccountCode.POINT_BALANCE.description shouldBe "포인트 잔액"
        AccountCode.POINT_FUNDING.description shouldBe "포인트 출연금"
    }

    @Test
    fun `new account codes are resolvable by name`() {
        AccountCode.valueOf("PROMOTION_FUNDING") shouldBe AccountCode.PROMOTION_FUNDING
        AccountCode.valueOf("POINT_BALANCE") shouldBe AccountCode.POINT_BALANCE
        AccountCode.valueOf("POINT_FUNDING") shouldBe AccountCode.POINT_FUNDING
    }

    @Test
    fun `new ledger entry types exist`() {
        LedgerEntryType.valueOf("COUPON_SUBSIDY") shouldBe LedgerEntryType.COUPON_SUBSIDY
        LedgerEntryType.valueOf("POINT_EARN") shouldBe LedgerEntryType.POINT_EARN
    }

    @Test
    fun `enum string lengths fit ledger column limits`() {
        // LedgerEntry.account length=30, entryType length=20
        AccountCode.entries.forEach { it.name.length shouldBe it.name.length.coerceAtMost(30) }
        LedgerEntryType.entries.forEach { it.name.length shouldBe it.name.length.coerceAtMost(20) }
    }
}
