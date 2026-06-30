package com.commerce.voucher.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.common.idempotency.Idempotent
import com.commerce.common.security.SecurityUtils
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
) {

    @GetMapping
    fun search(
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(required = false) regionId: Long?,
        @RequestParam(required = false) status: VoucherStatus?,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ApiResponse<Page<VoucherResponse>> =
        ApiResponse.ok(
            voucherQueryRepository.findByConditions(memberId, regionId, status, pageable)
                .map { VoucherResponse.from(it) }
        )

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ApiResponse<VoucherResponse> =
        ApiResponse.ok(
            VoucherResponse.from(
                voucherJpaRepository.findById(id)
                    .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
            )
        )

    @PostMapping("/purchase")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    fun purchase(@Valid @RequestBody request: PurchaseVoucherRequest): ApiResponse<VoucherResponse> =
        ApiResponse.ok(VoucherResponse.from(issueService.issue(SecurityUtils.currentMemberId(), request.regionId, request.faceValue)))

    @PostMapping("/{id}/redeem")
    @Idempotent
    fun redeem(@PathVariable id: Long, @Valid @RequestBody request: RedeemRequest): ApiResponse<RedemptionResult> {
        requireOwnership(id) // 결제 서비스는 소유권을 검증하지 않으므로 컨트롤러에서 강제(IDOR 차단)
        return ApiResponse.ok(redemptionOrchestrator.redeem(id, request.merchantId, request.amount, request.couponId))
    }

    @PostMapping("/{id}/refund")
    @Idempotent
    fun refund(@PathVariable id: Long): ApiResponse<VoucherResponse> =
        ApiResponse.ok(VoucherResponse.from(refundService.refund(id, SecurityUtils.currentMemberId())))

    @PostMapping("/{id}/withdraw")
    @Idempotent
    fun withdraw(@PathVariable id: Long): ApiResponse<VoucherResponse> =
        ApiResponse.ok(VoucherResponse.from(withdrawalService.withdraw(id, SecurityUtils.currentMemberId())))

    /** 인증 주체가 해당 바우처 소유자인지 검증한다(본문 식별자 불신, 미인증 401·타인 자원 403). */
    private fun requireOwnership(voucherId: Long) {
        val voucher = voucherJpaRepository.findById(voucherId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }
        if (voucher.memberId != SecurityUtils.currentMemberId())
            throw BusinessException(ErrorCode.ACCESS_DENIED)
    }
}
