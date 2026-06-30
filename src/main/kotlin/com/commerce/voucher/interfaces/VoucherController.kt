package com.commerce.voucher.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.common.idempotency.Idempotent
import com.commerce.common.security.SecurityUtils
import com.commerce.member.infrastructure.MemberJpaRepository
import com.commerce.voucher.application.VoucherIssueService
import com.commerce.voucher.application.VoucherRefundService
import com.commerce.voucher.application.VoucherWithdrawalService
import com.commerce.voucher.domain.VoucherStatus
import com.commerce.voucher.infrastructure.VoucherJpaRepository
import com.commerce.voucher.infrastructure.VoucherQueryRepository
import com.commerce.voucher.interfaces.dto.*
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/vouchers")
class VoucherController(
    private val issueService: VoucherIssueService,
    private val redemptionOrchestrator: com.commerce.promotion.application.RedemptionOrchestrator,
    private val refundService: VoucherRefundService,
    private val withdrawalService: VoucherWithdrawalService,
    private val voucherQueryRepository: VoucherQueryRepository,
    private val voucherJpaRepository: VoucherJpaRepository,
    private val memberJpaRepository: MemberJpaRepository,
) {

    @GetMapping
    fun search(
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(required = false) regionId: Long?,
        @RequestParam(required = false) status: VoucherStatus?,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ApiResponse<Page<VoucherResponse>> {
        // 본인 자원 스코프: 일반 회원은 자신의 바우처만 조회한다. ADMIN만 임의 memberId 필터를 허용한다.
        val effectiveMemberId = if (SecurityUtils.isAdmin()) memberId else SecurityUtils.currentMemberId()
        return ApiResponse.ok(
            voucherQueryRepository.findByConditions(effectiveMemberId, regionId, status, pageable)
                .map { VoucherResponse.from(it) }
        )
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ApiResponse<VoucherResponse> {
        val voucher = voucherJpaRepository.findById(id).orElse(null)
        // 존재성 오라클 차단: 없는 id와 타인 소유 id를 모두 404로 수렴한다(ADMIN은 전체 조회 가능).
        if (voucher == null || (!SecurityUtils.isAdmin() && voucher.memberId != SecurityUtils.currentMemberId()))
            throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
        return ApiResponse.ok(VoucherResponse.from(voucher))
    }

    @PostMapping("/purchase")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    fun purchase(@Valid @RequestBody request: PurchaseVoucherRequest): ApiResponse<VoucherResponse> {
        val memberId = SecurityUtils.currentMemberId()
        requireActiveMember(memberId)
        return ApiResponse.ok(VoucherResponse.from(issueService.issue(memberId, request.regionId, request.faceValue)))
    }

    @PostMapping("/{id}/redeem")
    @Idempotent
    fun redeem(@PathVariable id: Long, @Valid @RequestBody request: RedeemRequest): ApiResponse<RedemptionResult> {
        requireActiveMember(SecurityUtils.currentMemberId()) // 정지된 회원은 토큰이 유효해도 자금 이동 불가
        requireOwnership(id) // 결제 서비스는 소유권을 검증하지 않으므로 컨트롤러에서 강제(IDOR 차단)
        return ApiResponse.ok(redemptionOrchestrator.redeem(id, request.merchantId, request.amount, request.couponId))
    }

    @PostMapping("/{id}/refund")
    @Idempotent
    fun refund(@PathVariable id: Long): ApiResponse<VoucherResponse> {
        val memberId = SecurityUtils.currentMemberId()
        requireActiveMember(memberId)
        return ApiResponse.ok(VoucherResponse.from(refundService.refund(id, memberId)))
    }

    @PostMapping("/{id}/withdraw")
    @Idempotent
    fun withdraw(@PathVariable id: Long): ApiResponse<VoucherResponse> {
        val memberId = SecurityUtils.currentMemberId()
        requireActiveMember(memberId)
        return ApiResponse.ok(VoucherResponse.from(withdrawalService.withdraw(id, memberId)))
    }

    /** 인증 주체가 ACTIVE 회원인지 검증한다 — 로그인 후 정지/탈퇴된 회원의 자금 이동을 차단(토큰 TTL 무관). */
    private fun requireActiveMember(memberId: Long) {
        val member = memberJpaRepository.findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        if (!member.isActive()) throw BusinessException(ErrorCode.MEMBER_NOT_ACTIVE)
    }

    /**
     * 인증 주체가 해당 바우처 소유자인지 검증한다(본문 식별자 불신).
     * 존재성 오라클 차단을 위해 미존재·타인 소유를 모두 404 ENTITY_NOT_FOUND로 수렴한다.
     */
    private fun requireOwnership(voucherId: Long) {
        val voucher = voucherJpaRepository.findById(voucherId).orElse(null)
        if (voucher == null || voucher.memberId != SecurityUtils.currentMemberId())
            throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)
    }
}
