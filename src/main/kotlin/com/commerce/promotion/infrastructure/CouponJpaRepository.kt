package com.commerce.promotion.infrastructure

import com.commerce.promotion.domain.Coupon
import com.commerce.promotion.domain.CouponStatus
import org.springframework.data.jpa.repository.JpaRepository

interface CouponJpaRepository : JpaRepository<Coupon, Long> {
    fun findByMemberId(memberId: Long): List<Coupon>

    /** 회원의 특정 프로모션 쿠폰 중 주어진 상태(예: REDEEMED) 개수 — 1인 사용 한도 검증용. */
    fun countByMemberIdAndPromotionIdAndStatus(memberId: Long, promotionId: Long, status: CouponStatus): Long
}
