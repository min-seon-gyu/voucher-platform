package com.commerce.merchant.batch

import com.commerce.merchant.application.SettlementService
import com.commerce.merchant.domain.Merchant
import com.commerce.merchant.domain.MerchantStatus
import com.commerce.merchant.domain.Settlement
import com.commerce.merchant.domain.SettlementStatus
import com.commerce.merchant.infrastructure.SettlementJpaRepository
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManagerFactory
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.JpaPagingItemReader
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 결산 주기 일괄 정산 배치.
 *
 * 기준일(referenceDate) 파라미터로 **APPROVED 가맹점 전체**를 청크 처리하며, 각 가맹점의 소속 지자체 정산주기(일/주/월)에 맞춰
 * 해당 구간의 정산(PENDING)을 생성한다. 재실행 안전(멱등: 구간 중복 스킵 + unique 제약), 0원 스킵, 단건 실패 skip(격리)로
 * 대량 결산이 중단 없이 완주하도록 설계했다. 확정·원장분개·지급은 승인이 필요한 별도 액션으로 남긴다(정산 관행).
 *
 * 스키마는 Flyway V8이 소유(spring.batch.jdbc.initialize-schema=never), 起動 자동 실행은 끔(spring.batch.job.enabled=false).
 * JobRepository/JobLauncher는 Spring Boot BatchAutoConfiguration이 제공(@EnableBatchProcessing 미사용).
 */
@Configuration
class SettlementBatchConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val entityManagerFactory: EntityManagerFactory,
    private val settlementService: SettlementService,
    private val settlementRepository: SettlementJpaRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val JOB_NAME = "settlementJob"
        private const val CHUNK_SIZE = 100
        private const val SKIP_LIMIT = 20
    }

    @Bean
    fun settlementJob(calculateSettlementStep: Step, verifySettlementStep: Step): Job =
        JobBuilder(JOB_NAME, jobRepository)
            .incrementer(RunIdIncrementer()) // 같은 referenceDate 재실행 허용(run.id로 인스턴스 구분), 멱등은 processor가 보장
            .start(calculateSettlementStep)
            .next(verifySettlementStep)
            .build()

    /** APPROVED 가맹점을 id 순으로 페이징. 프로세서에선 id만 사용하므로 LAZY 연관 접근 없음. */
    @Bean
    @StepScope
    fun approvedMerchantReader(): JpaPagingItemReader<Merchant> =
        JpaPagingItemReaderBuilder<Merchant>()
            .name("approvedMerchantReader")
            .entityManagerFactory(entityManagerFactory)
            .queryString("SELECT m FROM Merchant m WHERE m.status = :status ORDER BY m.id ASC")
            .parameterValues(mapOf<String, Any>("status" to MerchantStatus.APPROVED))
            .pageSize(CHUNK_SIZE)
            .build()

    /** 가맹점별 정산(미저장)을 만든다. 중복/0원이면 null → Spring Batch가 filter로 집계하고 다음으로 진행. */
    @Bean
    @StepScope
    fun settlementProcessor(
        @Value("#{jobParameters['referenceDate']}") referenceDate: String,
    ): ItemProcessor<Merchant, Settlement> {
        val ref = LocalDate.parse(referenceDate)
        // ItemProcessor.process는 @Nullable — null 반환 시 해당 아이템은 filter된다.
        return ItemProcessor { merchant -> settlementService.buildSettlementForBatch(merchant.id, ref) }
    }

    /** 생성된 정산을 일괄 저장. 청크 트랜잭션 안에서 실행된다. */
    @Bean
    fun settlementWriter(): ItemWriter<Settlement> =
        ItemWriter { chunk -> settlementRepository.saveAll(chunk.items) }

    @Bean
    fun calculateSettlementStep(
        approvedMerchantReader: JpaPagingItemReader<Merchant>,
        settlementProcessor: ItemProcessor<Merchant, Settlement>,
        settlementWriter: ItemWriter<Settlement>,
    ): Step =
        StepBuilder("calculateSettlementStep", jobRepository)
            .chunk<Merchant, Settlement>(CHUNK_SIZE, transactionManager)
            .reader(approvedMerchantReader)
            .processor(settlementProcessor)
            .writer(settlementWriter)
            .faultTolerant()
            .skip(Exception::class.java)   // 단건 가맹점 실패는 격리하고 결산 전체는 완주(운영 모니터링 대상)
            .skipLimit(SKIP_LIMIT)
            .build()

    /**
     * 검증 스텝: 결산 후 정합성 자동 점검. PENDING 정산 중 금액이 0 이하인 이상치가 있으면 ERROR + 메트릭.
     * (배치는 0원을 스킵하므로 정상 시 이상치=0. 자동화된 마감 검증의 최소 형태.)
     */
    @Bean
    fun verifySettlementStep(): Step =
        StepBuilder("verifySettlementStep", jobRepository)
            .tasklet(settlementVerificationTasklet(), transactionManager)
            .build()

    @Bean
    fun settlementVerificationTasklet(): Tasklet = Tasklet { _, _ ->
        val pendingCount = settlementRepository.countByStatus(SettlementStatus.PENDING)
        val anomalies = settlementRepository.countByStatusAndTotalAmountLessThanEqual(
            SettlementStatus.PENDING, BigDecimal.ZERO,
        )
        meterRegistry.gauge("settlement.batch.pending.count", pendingCount.toDouble())
        meterRegistry.gauge("settlement.batch.pending.nonpositive", anomalies.toDouble())
        if (anomalies > 0) {
            log.error("SETTLEMENT VERIFY: {} PENDING settlements with non-positive amount (needs review)", anomalies)
        } else {
            log.info("SETTLEMENT VERIFY passed. PENDING settlements: {}", pendingCount)
        }
        RepeatStatus.FINISHED
    }
}
