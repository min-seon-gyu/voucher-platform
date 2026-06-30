package com.commerce.member.domain.event

import com.commerce.common.domain.DomainEvent

class MemberSuspendedEvent(
    override val aggregateId: Long,
) : DomainEvent() {
    override val aggregateType = "MEMBER"
    override val eventType = "MEMBER_SUSPENDED"
}
