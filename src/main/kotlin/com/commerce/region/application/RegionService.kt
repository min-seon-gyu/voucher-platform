package com.commerce.region.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.region.domain.Region
import com.commerce.region.domain.RegionPolicy
import com.commerce.region.domain.SettlementPeriod
import com.commerce.region.domain.event.RegionPolicyChangedEvent
import com.commerce.region.infrastructure.RegionJpaRepository
import com.commerce.region.interfaces.dto.CreateRegionRequest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class RegionService(
    private val regionRepository: RegionJpaRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun create(request: CreateRegionRequest): Region {
        val policy = RegionPolicy(
            discountRate = request.discountRate,
            purchaseLimitPerPerson = request.purchaseLimitPerPerson,
            monthlyIssuanceLimit = request.monthlyIssuanceLimit,
            refundThresholdRatio = request.refundThresholdRatio,
            settlementPeriod = SettlementPeriod.valueOf(request.settlementPeriod)
        )
        return regionRepository.save(
            Region(
                name = request.name,
                regionCode = request.regionCode,
                policy = policy
            )
        )
    }

    fun getById(id: Long): Region =
        regionRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }

    fun getByCode(code: String): Region =
        regionRepository.findByRegionCode(code)
            ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)

    @Transactional
    fun updatePolicy(id: Long, request: CreateRegionRequest): Region {
        val region = getById(id)
        val previous = policyJson(region.policy)
        val newPolicy = RegionPolicy(
            discountRate = request.discountRate,
            purchaseLimitPerPerson = request.purchaseLimitPerPerson,
            monthlyIssuanceLimit = request.monthlyIssuanceLimit,
            refundThresholdRatio = request.refundThresholdRatio,
            settlementPeriod = SettlementPeriod.valueOf(request.settlementPeriod)
        )
        region.updatePolicy(newPolicy)
        eventPublisher.publishEvent(
            RegionPolicyChangedEvent(
                aggregateId = region.id,
                previousState = previous,
                currentState = policyJson(newPolicy),
            )
        )
        return region
    }

    private fun policyJson(policy: RegionPolicy): String =
        """{"discountRate":"${policy.discountRate}","purchaseLimitPerPerson":"${policy.purchaseLimitPerPerson}",""" +
            """"monthlyIssuanceLimit":"${policy.monthlyIssuanceLimit}","refundThresholdRatio":"${policy.refundThresholdRatio}",""" +
            """"settlementPeriod":"${policy.settlementPeriod}"}"""

    @Transactional
    fun suspend(id: Long): Region {
        val region = getById(id)
        region.suspend()
        return region
    }

    @Transactional
    fun activate(id: Long): Region {
        val region = getById(id)
        region.activate()
        return region
    }

    fun findAll(): List<Region> = regionRepository.findAll()
}
