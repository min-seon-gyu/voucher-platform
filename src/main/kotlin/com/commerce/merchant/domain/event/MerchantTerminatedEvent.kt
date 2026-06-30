package com.commerce.merchant.domain.event

import com.commerce.common.domain.DomainEvent

class MerchantTerminatedEvent(
    override val aggregateId: Long,
    val regionId: Long,
) : DomainEvent() {
    override val aggregateType = "MERCHANT"
    override val eventType = "MERCHANT_TERMINATED"
}
