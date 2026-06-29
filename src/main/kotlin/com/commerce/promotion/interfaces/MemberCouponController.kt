package com.commerce.promotion.interfaces

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.common.security.SecurityUtils
import com.commerce.promotion.application.CouponIssueService
import com.commerce.promotion.interfaces.dto.CouponResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/members")
class MemberCouponController(
    private val couponIssueService: CouponIssueService,
) {

    @GetMapping("/{memberId}/coupons")
    fun listCoupons(@PathVariable memberId: Long): List<CouponResponse> {
        if (memberId != SecurityUtils.currentMemberId())
            throw BusinessException(ErrorCode.ACCESS_DENIED)
        return couponIssueService.findByMember(memberId).map { CouponResponse.from(it) }
    }
}
