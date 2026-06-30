package com.commerce.merchant.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.member.application.MemberService
import com.commerce.merchant.domain.Merchant
import com.commerce.merchant.domain.MerchantCategory
import com.commerce.merchant.domain.event.MerchantApprovedEvent
import com.commerce.merchant.domain.event.MerchantRejectedEvent
import com.commerce.merchant.domain.event.MerchantTerminatedEvent
import com.commerce.merchant.infrastructure.MerchantJpaRepository
import com.commerce.region.application.RegionService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class RegisterMerchantRequest(
    val name: String,
    val businessNumber: String,
    val category: String,
    val regionId: Long,
    val ownerId: Long,
)

@Service
@Transactional(readOnly = true)
class MerchantService(
    private val merchantRepository: MerchantJpaRepository,
    private val regionService: RegionService,
    private val memberService: MemberService,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun register(request: RegisterMerchantRequest): Merchant {
        val region = regionService.getById(request.regionId)
        val owner = memberService.getById(request.ownerId)
        memberService.promoteToMerchantOwner(owner.id)

        return merchantRepository.save(
            Merchant(
                name = request.name,
                businessNumber = request.businessNumber,
                category = MerchantCategory.valueOf(request.category),
                region = region,
                owner = owner,
            )
        )
    }

    @Transactional
    fun approve(merchantId: Long): Merchant {
        val merchant = getById(merchantId)
        merchant.approve()
        eventPublisher.publishEvent(MerchantApprovedEvent(merchant.id, merchant.region.id))
        return merchant
    }

    @Transactional
    fun reject(merchantId: Long): Merchant {
        val merchant = getById(merchantId)
        merchant.reject()
        eventPublisher.publishEvent(MerchantRejectedEvent(merchant.id, merchant.region.id))
        return merchant
    }

    @Transactional
    fun suspend(merchantId: Long): Merchant {
        val merchant = getById(merchantId)
        merchant.suspend()
        return merchant
    }

    @Transactional
    fun unsuspend(merchantId: Long): Merchant {
        val merchant = getById(merchantId)
        merchant.unsuspend()
        return merchant
    }

    @Transactional
    fun terminate(merchantId: Long): Merchant {
        val merchant = getById(merchantId)
        merchant.terminate()
        eventPublisher.publishEvent(MerchantTerminatedEvent(merchant.id, merchant.region.id))
        return merchant
    }

    fun getById(id: Long): Merchant =
        merchantRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
}
