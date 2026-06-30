package com.commerce.common.audit

import org.springframework.data.jpa.repository.JpaRepository

interface AuditLogRepository : JpaRepository<AuditLog, Long> {
    fun findByEventType(eventType: String): List<AuditLog>
    fun findByAggregateTypeAndAggregateId(aggregateType: String, aggregateId: Long): List<AuditLog>
    fun existsByEventId(eventId: String): Boolean
}
