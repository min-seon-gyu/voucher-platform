package com.commerce.merchant.interfaces

import com.commerce.common.api.ApiResponse
import com.commerce.merchant.batch.SettlementBatchConfig
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.ZoneId

data class SettlementBatchRunResponse(
    val jobExecutionId: Long?,
    val status: String,
    val referenceDate: LocalDate,
)

/**
 * 결산 일괄 정산 배치의 수동 실행(관리자). 경로가 `/api/v1/admin` 하위이므로 SecurityConfig에서 ADMIN 게이트된다.
 * JobLauncher 기본값은 동기 실행 — 즉시 결과를 반환한다(대량 결산은 스케줄러 경로 권장).
 */
@RestController
@RequestMapping("/api/v1/admin/settlements/batch")
class SettlementBatchController(
    private val jobLauncher: JobLauncher,
    @Qualifier(SettlementBatchConfig.JOB_NAME) private val settlementJob: Job,
) {
    @PostMapping
    fun run(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        referenceDate: LocalDate?,
    ): ApiResponse<SettlementBatchRunResponse> {
        val ref = referenceDate ?: LocalDate.now(KST)
        val params = JobParametersBuilder()
            .addString("referenceDate", ref.toString())
            .addLong("run.id", System.currentTimeMillis()) // 매 실행 유니크 인스턴스 보장(직접 launch 시 incrementer 미적용)
            .toJobParameters()
        val execution = jobLauncher.run(settlementJob, params)
        return ApiResponse.ok(
            SettlementBatchRunResponse(
                jobExecutionId = execution.id,
                status = execution.status.name,
                referenceDate = ref,
            )
        )
    }

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
    }
}
