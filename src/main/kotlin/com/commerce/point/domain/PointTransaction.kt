package com.commerce.point.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Immutable
@Table(
    name = "point_transactions",
    indexes = [
        Index(name = "idx_point_tx_member", columnList = "member_id,created_at"),
        Index(name = "idx_point_tx_source", columnList = "source_transaction_id"),
    ]
)
class PointTransaction(
    @Column(nullable = false)
    val memberId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: PointTransactionType,

    @Column(nullable = false)
    val amount: BigDecimal,

    @Column(nullable = false)
    val balanceAfter: BigDecimal,

    // 적립을 유발한 원 거래(redemption Transaction)의 id.
    @Column(nullable = false)
    val sourceTransactionId: Long,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    init {
        require(amount > BigDecimal.ZERO) { "포인트 거래 금액은 0보다 커야 합니다" }
    }
}
