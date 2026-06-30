package com.commerce.common.audit

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * Transactional Outbox 레코드. 비즈니스 트랜잭션과 같은 tx에서 기록되어(원자적 캡처),
 * relay가 비동기로 감사 로그를 적용한다. payload(JSON)에 감사 복원에 필요한 전체 상태를 담는다.
 */
@Entity
@Table(
    name = "outbox_events",
    indexes = [Index(name = "idx_outbox_unpublished", columnList = "published, id")],
)
class OutboxEvent(
    @Column(nullable = false, unique = true, length = 36)
    val eventId: String,

    @Column(nullable = false, length = 50)
    val eventType: String,

    @Column(nullable = false, length = 30)
    val aggregateType: String,

    @Column(nullable = false)
    val aggregateId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val severity: AuditSeverity,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json", nullable = false)
    val payload: String,

    @Column(nullable = false)
    var published: Boolean = false,

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(columnDefinition = "DATETIME(6)")
    var publishedAt: LocalDateTime? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    fun markPublished(at: LocalDateTime = LocalDateTime.now()) {
        published = true
        publishedAt = at
    }
}
