package com.commerce.region.domain.event

import com.commerce.common.domain.DomainEvent

class RegionPolicyChangedEvent(
    override val aggregateId: Long,
    override val previousState: String? = null,
    override val currentState: String? = null,
) : DomainEvent() {
    override val aggregateType = "REGION"
    override val eventType = "REGION_POLICY_CHANGED"
}
