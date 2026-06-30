package com.commerce.common.audit

import org.springframework.data.jpa.repository.JpaRepository

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long> {
    /** 미발행 이벤트를 id 오름차순(발행 순서 보장)으로 일괄 조회. */
    fun findTop200ByPublishedFalseOrderByIdAsc(): List<OutboxEvent>
}
