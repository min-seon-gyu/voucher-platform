package com.commerce.merchant.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.merchant.application.SettlementService
import com.commerce.merchant.domain.Settlement
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

data class SettlementResponse(
    val id: Long,
    val merchantId: Long,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val totalAmount: BigDecimal,
    val status: String,
    val disputeReason: String?,
) {
    companion object {
        fun from(s: Settlement) = SettlementResponse(
            id = s.id,
            merchantId = s.merchantId,
            periodStart = s.periodStart,
            periodEnd = s.periodEnd,
            totalAmount = s.totalAmount,
            status = s.status.name,
            disputeReason = s.disputeReason,
        )
    }
}

/**
 * 정산 생성 요청.
 * - periodStart/periodEnd를 모두 주면 해당 명시 구간으로 계산한다.
 * - 비우면 가맹점 소속 지자체의 정산 주기(일/주/월)에 맞춰 referenceDate(미지정 시 KST 오늘) 기준 구간을 산출한다.
 */
data class CalculateSettlementRequest(
    val merchantId: Long,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val periodStart: LocalDate? = null,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val periodEnd: LocalDate? = null,
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val referenceDate: LocalDate? = null,
)

data class DisputeRequest(
    val reason: String,
)

@RestController
@RequestMapping("/api/v1/settlements")
class SettlementController(
    private val settlementService: SettlementService,
) {

    @PostMapping("/calculate")
    @ResponseStatus(HttpStatus.CREATED)
    fun calculate(@RequestBody request: CalculateSettlementRequest): ApiResponse<SettlementResponse> {
        val settlement = if (request.periodStart != null && request.periodEnd != null) {
            settlementService.calculate(request.merchantId, request.periodStart, request.periodEnd)
        } else if (request.referenceDate != null) {
            settlementService.calculateForPeriod(request.merchantId, request.referenceDate)
        } else {
            settlementService.calculateForPeriod(request.merchantId)
        }
        return ApiResponse.ok(SettlementResponse.from(settlement))
    }

    @PostMapping("/{id}/confirm")
    fun confirm(@PathVariable id: Long): ApiResponse<SettlementResponse> =
        ApiResponse.ok(SettlementResponse.from(settlementService.confirm(id)))

    @PostMapping("/{id}/pay")
    fun pay(@PathVariable id: Long): ApiResponse<SettlementResponse> =
        ApiResponse.ok(SettlementResponse.from(settlementService.markPaid(id)))

    @PostMapping("/{id}/dispute")
    fun dispute(@PathVariable id: Long, @RequestBody request: DisputeRequest): ApiResponse<SettlementResponse> =
        ApiResponse.ok(SettlementResponse.from(settlementService.dispute(id, request.reason)))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ApiResponse<SettlementResponse> =
        ApiResponse.ok(SettlementResponse.from(settlementService.getById(id)))
}
