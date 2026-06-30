package com.commerce.member.domain.event

import com.commerce.common.domain.DomainEvent

class MemberWithdrawnEvent(
    override val aggregateId: Long,
) : DomainEvent() {
    override val aggregateType = "MEMBER"
    override val eventType = "MEMBER_WITHDRAWN"
}
