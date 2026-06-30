# 모바일 상품권 시스템 구현 계획

> **상태: 전체 구현 완료 + 코드 품질 개선** — 16개 태스크 모두 완료, 28개 커밋, 소스 84개 파일, 테스트 13개 파일

**목표:** 지역사랑상품권의 발행-유통-정산 전 생애주기를 관리하는 백엔드 시스템 구축. 재무 무결성, 감사 추적성, 동시성 안전을 커머스 백엔드 포트폴리오로 증명.

**아키텍처:** Aggregate 중심 모듈러 모놀리스, 6개 도메인 모듈 (region, member, merchant, voucher, transaction, ledger) + 공통 모듈 + config. 하이브리드 복식부기 + 동기 원장 기록. 도메인 이벤트는 감사/알림 부수효과에만 사용. 분산락 사용 서비스는 TransactionTemplate으로 락-커밋 순서 보장. Region 월 한도는 Redis Lua 스크립트로 원자적 검증.

**기술 스택:** Kotlin 1.9.23, Spring Boot 3.2.5, JPA + QueryDSL 5.1.0, MySQL 8.x, Redis (Redisson 3.27.2), JWT (jjwt 0.12.5), Swagger/OpenAPI, JUnit 5 + Kotest 5.8.1 + Testcontainers 1.19.7, Gradle Kotlin DSL, Docker Compose

**스펙 문서:**
- `docs/01-domain-design.md` — 도메인 엔티티, 상태 머신, 불변식
- `docs/02-architecture-decisions.md` — 아키텍처, 동시성, 이벤트 설계
- `docs/03-implementation-roadmap.md` — 태스크 개요 및 의존성 그래프

**기본 경로:**
- 프로젝트 루트: `<project-root>`
- 소스: `src/main/kotlin/com/commerce/`
- 테스트: `src/test/kotlin/com/commerce/`
- 리소스: `src/main/resources/`
- 테스트 리소스: `src/test/resources/`

---

## 태스크 1: 프로젝트 초기 설정 및 인프라 구성

**Files:**
- Create: `build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `docker-compose.yml`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/application-test.yml`
- Create: `src/main/kotlin/com/commerce/VoucherApplication.kt`
- Create: `.gitignore`

- [x] **Step 1: Initialize Gradle project**

Create `settings.gradle.kts`:
```kotlin
rootProject.name = "voucher-system"
```

Create `build.gradle.kts`:
```kotlin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.spring") version "1.9.23"
    kotlin("plugin.jpa") version "1.9.23"
    kotlin("kapt") version "1.9.23"
}

group = "com.commerce"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // QueryDSL
    implementation("com.querydsl:querydsl-jpa:5.1.0:jakarta")
    kapt("com.querydsl:querydsl-apt:5.1.0:jakarta")

    // Redisson
    implementation("org.redisson:redisson-spring-boot-starter:3.27.2")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")

    // MySQL
    runtimeOnly("com.mysql:mysql-connector-j")

    // Micrometer
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.kotest:kotest-runner-junit5:5.8.1")
    testImplementation("io.kotest:kotest-assertions-core:5.8.1")
    testImplementation("io.kotest.extensions:kotest-extensions-spring:1.1.3")
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:mysql:1.19.7")
    testImplementation("org.testcontainers:junit-jupiter:1.19.7")
    testImplementation("io.mockk:mockk:1.13.10")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

- [x] **Step 2: Create Docker Compose**

Create `docker-compose.yml`:
```yaml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    container_name: voucher-mysql
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: voucher
      MYSQL_CHARACTER_SET_SERVER: utf8mb4
      MYSQL_COLLATION_SERVER: utf8mb4_unicode_ci
    command: --default-authentication-plugin=caching_sha2_password
    volumes:
      - mysql-data:/var/lib/mysql

  redis:
    image: redis:7-alpine
    container_name: voucher-redis
    ports:
      - "6379:6379"

volumes:
  mysql-data:
```

- [x] **Step 3: Create application configs**

Create `src/main/resources/application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/voucher?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
        format_sql: true
    open-in-view: false
  data:
    redis:
      host: localhost
      port: 6379

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
```

Create `src/main/resources/application-test.yml`:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
```

- [x] **Step 4: Create main application class**

Create `src/main/kotlin/com/commerce/VoucherApplication.kt`:
```kotlin
package com.commerce

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableScheduling
class VoucherApplication

fun main(args: Array<String>) {
    runApplication<VoucherApplication>(*args)
}
```

- [x] **Step 5: Create .gitignore**

```
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
*.class
*.jar
*.war
.idea/
*.iml
out/
.DS_Store
src/main/generated/
```

- [x] **Step 6: Create Testcontainers base test config**

Create `src/test/kotlin/com/commerce/support/IntegrationTestSupport.kt`:
```kotlin
package com.commerce.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
abstract class IntegrationTestSupport {

    companion object {
        val mysql = MySQLContainer("mysql:8.0").apply {
            withDatabaseName("voucher_test")
            withUsername("test")
            withPassword("test")
            withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
            start()
        }

        val redis = GenericContainer("redis:7-alpine").apply {
            withExposedPorts(6379)
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }
        }
    }
}
```

- [x] **Step 7: Verify project builds**

Run: `cd <project-root> && ./gradlew build`
Expected: BUILD SUCCESSFUL

- [x] **Step 8: Verify Docker Compose starts**

Run: `cd <project-root> && docker compose up -d && docker compose ps`
Expected: mysql and redis containers running

- [x] **Step 9: Commit**

```bash
git init
git add .
git commit -m "feat: initialize project with Spring Boot 3.x, Kotlin, Docker Compose"
```

---

## 태스크 2: 공통 모듈 — BaseEntity, 예외 체계, 감사 로그

**Files:**
- Create: `src/main/kotlin/com/commerce/common/domain/BaseEntity.kt`
- Create: `src/main/kotlin/com/commerce/common/domain/DomainEvent.kt`
- Create: `src/main/kotlin/com/commerce/common/exception/ErrorCode.kt`
- Create: `src/main/kotlin/com/commerce/common/exception/BusinessException.kt`
- Create: `src/main/kotlin/com/commerce/common/exception/GlobalExceptionHandler.kt`
- Create: `src/main/kotlin/com/commerce/common/audit/AuditLog.kt`
- Create: `src/main/kotlin/com/commerce/common/audit/AuditLogRepository.kt`
- Create: `src/main/kotlin/com/commerce/common/audit/AuditEventListener.kt`
- Create: `src/main/kotlin/com/commerce/common/audit/AuditSeverity.kt`
- Test: `src/test/kotlin/com/commerce/common/audit/AuditEventListenerTest.kt`

- [x] **Step 1: Write BaseEntity test**

Create `src/test/kotlin/com/commerce/common/domain/BaseEntityTest.kt`:
```kotlin
package com.commerce.common.domain

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldNotBe

class BaseEntityTest : DescribeSpec({
    describe("BaseEntity") {
        it("should have version field for optimistic locking") {
            val field = BaseEntity::class.java.getDeclaredField("version")
            field shouldNotBe null
        }
    }
})
```

- [x] **Step 2: Run test — expect FAIL**

Run: `./gradlew test --tests "com.commerce.common.domain.BaseEntityTest"`
Expected: FAIL — BaseEntity class not found

- [x] **Step 3: Implement BaseEntity**

Create `src/main/kotlin/com/commerce/common/domain/BaseEntity.kt`:
```kotlin
package com.commerce.common.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@MappedSuperclass
abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
        protected set

    @Version
    val version: Long = 0L

    @PreUpdate
    fun onPreUpdate() {
        updatedAt = LocalDateTime.now()
    }
}
```

- [x] **Step 4: Run test — expect PASS**

Run: `./gradlew test --tests "com.commerce.common.domain.BaseEntityTest"`
Expected: PASS

- [x] **Step 5: Implement DomainEvent base class**

Create `src/main/kotlin/com/commerce/common/domain/DomainEvent.kt`:
```kotlin
package com.commerce.common.domain

import java.time.LocalDateTime
import java.util.UUID

abstract class DomainEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val occurredAt: LocalDateTime = LocalDateTime.now()
) {
    abstract val aggregateType: String
    abstract val aggregateId: Long
    abstract val eventType: String
}
```

- [x] **Step 6: Implement ErrorCode and BusinessException**

Create `src/main/kotlin/com/commerce/common/exception/ErrorCode.kt`:
```kotlin
package com.commerce.common.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String
) {
    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력값입니다"),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "데이터를 찾을 수 없습니다"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "동시 수정이 감지되었습니다"),
    LOCK_ACQUISITION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "처리 중입니다. 잠시 후 다시 시도해주세요"),
    IDEMPOTENCY_DUPLICATE(HttpStatus.OK, "이미 처리된 요청입니다"),

    // Region
    REGION_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "운영 중인 지자체가 아닙니다"),
    REGION_MONTHLY_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "지자체 월 발행한도를 초과했습니다"),

    // Member
    MEMBER_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "활성 상태의 회원이 아닙니다"),
    MEMBER_PURCHASE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "1인 구매한도를 초과했습니다"),

    // Merchant
    MERCHANT_NOT_APPROVED(HttpStatus.BAD_REQUEST, "승인된 가맹점이 아닙니다"),
    INVALID_STATE_TRANSITION(HttpStatus.BAD_REQUEST, "허용되지 않은 상태 전이입니다"),

    // Voucher
    VOUCHER_NOT_USABLE(HttpStatus.BAD_REQUEST, "사용할 수 없는 상품권입니다"),
    VOUCHER_EXPIRED(HttpStatus.BAD_REQUEST, "만료된 상품권입니다"),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "잔액이 부족합니다"),
    REFUND_CONDITION_NOT_MET(HttpStatus.BAD_REQUEST, "환불 조건을 충족하지 않습니다 (60% 이상 사용 필요)"),
    WITHDRAWAL_PERIOD_EXPIRED(HttpStatus.BAD_REQUEST, "청약철회 기간이 만료되었습니다 (구매 후 7일 이내)"),
    WITHDRAWAL_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "사용된 상품권은 청약철회할 수 없습니다"),

    // Transaction
    TRANSACTION_NOT_CANCELLABLE(HttpStatus.BAD_REQUEST, "취소할 수 없는 거래입니다"),

    // Ledger
    LEDGER_IMBALANCE_DETECTED(HttpStatus.INTERNAL_SERVER_ERROR, "원장 정합성 오류가 감지되었습니다"),
    MANUAL_ADJUSTMENT_REQUIRES_ADMIN(HttpStatus.FORBIDDEN, "수동 원장 조정은 관리자 승인이 필요합니다"),
}
```

Create `src/main/kotlin/com/commerce/common/exception/BusinessException.kt`:
```kotlin
package com.commerce.common.exception

class BusinessException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.message
) : RuntimeException(message)
```

Create `src/main/kotlin/com/commerce/common/exception/GlobalExceptionHandler.kt`:
```kotlin
package com.commerce.common.exception

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    data class ErrorResponse(
        val code: String,
        val message: String
    )

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ErrorResponse> {
        log.warn("Business exception: {} - {}", e.errorCode, e.message)
        return ResponseEntity
            .status(e.errorCode.status)
            .body(ErrorResponse(e.errorCode.name, e.message))
    }
}
```

- [x] **Step 7: Write AuditLog entity test**

Create `src/test/kotlin/com/commerce/common/audit/AuditEventListenerTest.kt`:
```kotlin
package com.commerce.common.audit

import com.commerce.common.domain.DomainEvent
import com.commerce.support.IntegrationTestSupport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.annotation.Transactional

class AuditEventListenerTest : IntegrationTestSupport() {

    @Autowired
    lateinit var eventPublisher: ApplicationEventPublisher

    @Autowired
    lateinit var auditLogRepository: AuditLogRepository

    @Test
    @Transactional
    fun `CRITICAL event should be recorded within same transaction`() {
        // given
        val event = TestCriticalEvent(aggregateId = 1L)

        // when
        eventPublisher.publishEvent(event)

        // then
        val logs = auditLogRepository.findAll()
        logs.size shouldBe 1
        logs[0].severity shouldBe AuditSeverity.CRITICAL
        logs[0].eventType shouldBe "TEST_CRITICAL"
        logs[0].aggregateId shouldBe 1L
    }

    class TestCriticalEvent(
        override val aggregateId: Long
    ) : DomainEvent() {
        override val aggregateType = "TEST"
        override val eventType = "TEST_CRITICAL"
    }
}
```

- [x] **Step 8: Run test — expect FAIL**

Run: `./gradlew test --tests "com.commerce.common.audit.AuditEventListenerTest"`
Expected: FAIL — AuditLog classes not found

- [x] **Step 9: Implement AuditLog entity and listener**

Create `src/main/kotlin/com/commerce/common/audit/AuditSeverity.kt`:
```kotlin
package com.commerce.common.audit

enum class AuditSeverity {
    CRITICAL, HIGH, MEDIUM
}
```

Create `src/main/kotlin/com/commerce/common/audit/AuditLog.kt`:
```kotlin
package com.commerce.common.audit

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Entity
@Table(
    name = "audit_logs",
    indexes = [
        Index(name = "idx_audit_aggregate", columnList = "aggregateType, aggregateId, createdAt"),
        Index(name = "idx_audit_event_type", columnList = "eventType, createdAt"),
        Index(name = "idx_audit_actor", columnList = "actorId, createdAt")
    ]
)
class AuditLog(
    @Column(nullable = false, unique = true, length = 36)
    val eventId: String,

    @Column(nullable = false, length = 50)
    val eventType: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val severity: AuditSeverity,

    @Column(nullable = false, length = 30)
    val aggregateType: String,

    @Column(nullable = false)
    val aggregateId: Long,

    val actorId: Long? = null,

    @Column(length = 20)
    val actorType: String? = null,

    @Column(nullable = false, length = 50)
    val action: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    val previousState: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    val currentState: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    val metadata: String? = null,

    @Column(length = 64)
    val idempotencyKey: String? = null,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L
}
```

Create `src/main/kotlin/com/commerce/common/audit/AuditLogRepository.kt`:
```kotlin
package com.commerce.common.audit

import org.springframework.data.jpa.repository.JpaRepository

interface AuditLogRepository : JpaRepository<AuditLog, Long>
```

Create `src/main/kotlin/com/commerce/common/audit/AuditEventListener.kt`:
```kotlin
package com.commerce.common.audit

import com.commerce.common.domain.DomainEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.event.TransactionPhase

@Component
class AuditEventListener(
    private val auditLogRepository: AuditLogRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * CRITICAL: same transaction (BEFORE_COMMIT).
     * Failure rolls back the entire transaction.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun handleCriticalAudit(event: DomainEvent) {
        val severity = resolveSeverity(event.eventType)
        if (severity != AuditSeverity.CRITICAL) return

        saveAuditLog(event, severity)
    }

    /**
     * HIGH/MEDIUM: after commit (AFTER_COMMIT).
     * Runs in a new transaction (REQUIRES_NEW) since the original tx is already committed.
     * Failure logged and saved to failed_events table for retry.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handleNonCriticalAudit(event: DomainEvent) {
        val severity = resolveSeverity(event.eventType)
        if (severity == AuditSeverity.CRITICAL) return

        try {
            saveAuditLog(event, severity)
        } catch (e: Exception) {
            log.error("Failed to save audit log for event {}: {}", event.eventId, e.message)
            saveFailedEvent(event, e)
        }
    }

    private fun saveFailedEvent(event: DomainEvent, error: Exception) {
        try {
            failedEventRepository.save(FailedEvent(
                eventId = event.eventId,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
                errorMessage = error.message ?: "Unknown error",
            ))
        } catch (e: Exception) {
            log.error("Failed to save failed event record: {}", e.message)
        }
    }

    private fun saveAuditLog(event: DomainEvent, severity: AuditSeverity) {
        val auditLog = AuditLog(
            eventId = event.eventId,
            eventType = event.eventType,
            severity = severity,
            aggregateType = event.aggregateType,
            aggregateId = event.aggregateId,
            action = resolveAction(event.eventType),
            createdAt = event.occurredAt
        )
        auditLogRepository.save(auditLog)
        log.info("Audit log saved: {} [{}] for {}:{}", event.eventType, severity, event.aggregateType, event.aggregateId)
    }

    private fun resolveSeverity(eventType: String): AuditSeverity = when {
        eventType in CRITICAL_EVENTS -> AuditSeverity.CRITICAL
        eventType in HIGH_EVENTS -> AuditSeverity.HIGH
        else -> AuditSeverity.MEDIUM
    }

    private fun resolveAction(eventType: String): String = when {
        eventType.contains("ISSUED") || eventType.contains("APPROVED") -> "CREATE"
        eventType.contains("REDEEMED") || eventType.contains("REFUNDED") || eventType.contains("WITHDRAWN") -> "STATE_CHANGE"
        eventType.contains("CANCELLED") -> "CANCEL"
        eventType.contains("EXPIRED") -> "EXPIRE"
        eventType.contains("CONFIRMED") -> "CONFIRM"
        else -> "UPDATE"
    }

    companion object {
        val CRITICAL_EVENTS = setOf(
            "VOUCHER_ISSUED", "VOUCHER_REDEEMED", "VOUCHER_REFUNDED",
            "VOUCHER_WITHDRAWN", "TRANSACTION_CANCELLED",
            "SETTLEMENT_CONFIRMED", "MANUAL_ADJUSTMENT",
            "TEST_CRITICAL" // for testing
        )
        val HIGH_EVENTS = setOf(
            "MERCHANT_APPROVED", "MERCHANT_REJECTED", "MERCHANT_TERMINATED",
            "MEMBER_SUSPENDED", "MEMBER_WITHDRAWN", "REGION_POLICY_CHANGED"
        )
    }
}
```

- [x] **Step 10: Run test — expect PASS**

Run: `./gradlew test --tests "com.commerce.common.audit.AuditEventListenerTest"`
Expected: PASS

- [x] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: add common module with BaseEntity, exception hierarchy, audit log system"
```

---

## 태스크 3: Region 모듈

**Files:**
- Create: `src/main/kotlin/com/commerce/region/domain/Region.kt`
- Create: `src/main/kotlin/com/commerce/region/domain/RegionPolicy.kt`
- Create: `src/main/kotlin/com/commerce/region/domain/RegionStatus.kt`
- Create: `src/main/kotlin/com/commerce/region/application/RegionService.kt`
- Create: `src/main/kotlin/com/commerce/region/infrastructure/RegionJpaRepository.kt`
- Create: `src/main/kotlin/com/commerce/region/interfaces/RegionController.kt`
- Create: `src/main/kotlin/com/commerce/region/interfaces/dto/RegionRequest.kt`
- Create: `src/main/kotlin/com/commerce/region/interfaces/dto/RegionResponse.kt`
- Test: `src/test/kotlin/com/commerce/region/domain/RegionTest.kt`
- Test: `src/test/kotlin/com/commerce/region/application/RegionServiceTest.kt`

- [x] **Step 1: Write Region domain test**

Create `src/test/kotlin/com/commerce/region/domain/RegionTest.kt`:
```kotlin
package com.commerce.region.domain

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class RegionTest : DescribeSpec({
    describe("Region state transitions") {
        it("ACTIVE -> SUSPENDED is valid") {
            val region = createRegion(RegionStatus.ACTIVE)
            region.suspend()
            region.status shouldBe RegionStatus.SUSPENDED
        }

        it("SUSPENDED -> ACTIVE is valid") {
            val region = createRegion(RegionStatus.SUSPENDED)
            region.activate()
            region.status shouldBe RegionStatus.ACTIVE
        }

        it("SUSPENDED -> DEACTIVATED is valid") {
            val region = createRegion(RegionStatus.SUSPENDED)
            region.deactivate()
            region.status shouldBe RegionStatus.DEACTIVATED
        }

        it("ACTIVE -> DEACTIVATED is invalid") {
            val region = createRegion(RegionStatus.ACTIVE)
            val ex = shouldThrow<BusinessException> { region.deactivate() }
            ex.errorCode shouldBe ErrorCode.INVALID_STATE_TRANSITION
        }
    }
})

fun createRegion(status: RegionStatus = RegionStatus.ACTIVE): Region {
    return Region(
        name = "성남시",
        regionCode = "SN",
        policy = RegionPolicy(
            discountRate = BigDecimal("0.10"),
            purchaseLimitPerPerson = BigDecimal("500000"),
            monthlyIssuanceLimit = BigDecimal("10000000000"),
            refundThresholdRatio = BigDecimal("0.60"),
            settlementPeriod = SettlementPeriod.MONTHLY
        ),
        status = status
    )
}
```

- [x] **Step 2: Run test — expect FAIL**

Run: `./gradlew test --tests "com.commerce.region.domain.RegionTest"`
Expected: FAIL

- [x] **Step 3: Implement Region domain**

Create `src/main/kotlin/com/commerce/region/domain/RegionStatus.kt`:
```kotlin
package com.commerce.region.domain

enum class RegionStatus {
    ACTIVE, SUSPENDED, DEACTIVATED
}

enum class SettlementPeriod {
    DAILY, WEEKLY, MONTHLY
}
```

Create `src/main/kotlin/com/commerce/region/domain/RegionPolicy.kt`:
```kotlin
package com.commerce.region.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import java.math.BigDecimal

@Embeddable
data class RegionPolicy(
    @Column(nullable = false, precision = 5, scale = 2)
    val discountRate: BigDecimal,

    @Column(nullable = false)
    val purchaseLimitPerPerson: BigDecimal,

    @Column(nullable = false)
    val monthlyIssuanceLimit: BigDecimal,

    @Column(nullable = false, precision = 3, scale = 2)
    val refundThresholdRatio: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val settlementPeriod: SettlementPeriod
)
```

Create `src/main/kotlin/com/commerce/region/domain/Region.kt`:
```kotlin
package com.commerce.region.domain

import com.commerce.common.domain.BaseEntity
import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import jakarta.persistence.*

@Entity
@Table(name = "regions")
class Region(
    @Column(nullable = false, length = 50)
    val name: String,

    @Column(nullable = false, unique = true, length = 2)
    val regionCode: String,

    @Embedded
    var policy: RegionPolicy,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    var status: RegionStatus = RegionStatus.ACTIVE
) : BaseEntity() {

    fun suspend() {
        if (status != RegionStatus.ACTIVE)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "ACTIVE 상태에서만 정지할 수 있습니다")
        status = RegionStatus.SUSPENDED
    }

    fun activate() {
        if (status != RegionStatus.SUSPENDED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "SUSPENDED 상태에서만 활성화할 수 있습니다")
        status = RegionStatus.ACTIVE
    }

    fun deactivate() {
        if (status != RegionStatus.SUSPENDED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "SUSPENDED 상태에서만 종료할 수 있습니다")
        status = RegionStatus.DEACTIVATED
    }

    fun updatePolicy(newPolicy: RegionPolicy) {
        if (status != RegionStatus.ACTIVE)
            throw BusinessException(ErrorCode.REGION_NOT_ACTIVE)
        policy = newPolicy
    }
}
```

- [x] **Step 4: Run domain test — expect PASS**

Run: `./gradlew test --tests "com.commerce.region.domain.RegionTest"`
Expected: PASS

- [x] **Step 5: Implement Region service, repository, controller, DTOs**

Create `src/main/kotlin/com/commerce/region/infrastructure/RegionJpaRepository.kt`:
```kotlin
package com.commerce.region.infrastructure

import com.commerce.region.domain.Region
import org.springframework.data.jpa.repository.JpaRepository

interface RegionJpaRepository : JpaRepository<Region, Long> {
    fun findByRegionCode(regionCode: String): Region?
}
```

Create `src/main/kotlin/com/commerce/region/interfaces/dto/RegionRequest.kt`:
```kotlin
package com.commerce.region.interfaces.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

data class CreateRegionRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val regionCode: String,
    @field:Positive val discountRate: BigDecimal,
    @field:Positive val purchaseLimitPerPerson: BigDecimal,
    @field:Positive val monthlyIssuanceLimit: BigDecimal,
    val refundThresholdRatio: BigDecimal = BigDecimal("0.60"),
    val settlementPeriod: String = "MONTHLY"
)
```

Create `src/main/kotlin/com/commerce/region/interfaces/dto/RegionResponse.kt`:
```kotlin
package com.commerce.region.interfaces.dto

import com.commerce.region.domain.Region
import java.math.BigDecimal

data class RegionResponse(
    val id: Long,
    val name: String,
    val regionCode: String,
    val status: String,
    val discountRate: BigDecimal,
    val purchaseLimitPerPerson: BigDecimal,
    val monthlyIssuanceLimit: BigDecimal,
    val refundThresholdRatio: BigDecimal,
    val settlementPeriod: String
) {
    companion object {
        fun from(region: Region) = RegionResponse(
            id = region.id,
            name = region.name,
            regionCode = region.regionCode,
            status = region.status.name,
            discountRate = region.policy.discountRate,
            purchaseLimitPerPerson = region.policy.purchaseLimitPerPerson,
            monthlyIssuanceLimit = region.policy.monthlyIssuanceLimit,
            refundThresholdRatio = region.policy.refundThresholdRatio,
            settlementPeriod = region.policy.settlementPeriod.name
        )
    }
}
```

Create `src/main/kotlin/com/commerce/region/application/RegionService.kt`:
```kotlin
package com.commerce.region.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.region.domain.*
import com.commerce.region.infrastructure.RegionJpaRepository
import com.commerce.region.interfaces.dto.CreateRegionRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class RegionService(
    private val regionRepository: RegionJpaRepository
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
        return regionRepository.save(Region(
            name = request.name,
            regionCode = request.regionCode,
            policy = policy
        ))
    }

    fun getById(id: Long): Region =
        regionRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }

    fun getByCode(code: String): Region =
        regionRepository.findByRegionCode(code)
            ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)

    fun findAll(): List<Region> = regionRepository.findAll()
}
```

Create `src/main/kotlin/com/commerce/region/interfaces/RegionController.kt`:
```kotlin
package com.commerce.region.interfaces

import com.commerce.region.application.RegionService
import com.commerce.region.interfaces.dto.CreateRegionRequest
import com.commerce.region.interfaces.dto.RegionResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/regions")
class RegionController(
    private val regionService: RegionService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateRegionRequest): RegionResponse =
        RegionResponse.from(regionService.create(request))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): RegionResponse =
        RegionResponse.from(regionService.getById(id))

    @GetMapping
    fun findAll(): List<RegionResponse> =
        regionService.findAll().map { RegionResponse.from(it) }
}
```

- [x] **Step 6: Write integration test for RegionService**

Create `src/test/kotlin/com/commerce/region/application/RegionServiceTest.kt`:
```kotlin
package com.commerce.region.application

import com.commerce.region.domain.RegionStatus
import com.commerce.region.interfaces.dto.CreateRegionRequest
import com.commerce.support.IntegrationTestSupport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

class RegionServiceTest : IntegrationTestSupport() {

    @Autowired
    lateinit var regionService: RegionService

    @Test
    fun `should create a region with policy`() {
        val request = CreateRegionRequest(
            name = "성남시",
            regionCode = "SN",
            discountRate = BigDecimal("0.10"),
            purchaseLimitPerPerson = BigDecimal("500000"),
            monthlyIssuanceLimit = BigDecimal("10000000000"),
        )

        val region = regionService.create(request)

        region.id shouldNotBe 0L
        region.name shouldBe "성남시"
        region.status shouldBe RegionStatus.ACTIVE
        region.policy.discountRate shouldBe BigDecimal("0.10")
    }
}
```

- [x] **Step 7: Run all tests — expect PASS**

Run: `./gradlew test --tests "com.commerce.region.*"`
Expected: PASS

- [x] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: add Region module with entity, policy, service, and API"
```

---

## 태스크 4: Member 모듈

**Files:**
- Create: `src/main/kotlin/com/commerce/member/domain/Member.kt`
- Create: `src/main/kotlin/com/commerce/member/domain/MemberStatus.kt`
- Create: `src/main/kotlin/com/commerce/member/domain/MemberRole.kt`
- Create: `src/main/kotlin/com/commerce/member/application/MemberService.kt`
- Create: `src/main/kotlin/com/commerce/member/infrastructure/MemberJpaRepository.kt`
- Create: `src/main/kotlin/com/commerce/member/interfaces/MemberController.kt`
- Create: `src/main/kotlin/com/commerce/member/interfaces/dto/`
- Create: `src/main/kotlin/com/commerce/config/SecurityConfig.kt`
- Test: `src/test/kotlin/com/commerce/member/domain/MemberTest.kt`

Follow the same TDD pattern as Task 3:

- [x] **Step 1: Write Member domain state transition tests**

Test cases: PENDING→ACTIVE, ACTIVE→SUSPENDED, SUSPENDED→ACTIVE, ACTIVE→WITHDRAWN, invalid transitions throw BusinessException.

- [x] **Step 2: Run tests — expect FAIL**

- [x] **Step 3: Implement Member entity with state machine**

```kotlin
@Entity
@Table(name = "members")
class Member(
    @Column(nullable = false, unique = true) val email: String,
    @Column(nullable = false) var name: String,
    @Column(nullable = false) var password: String,
    @Enumerated(EnumType.STRING) var status: MemberStatus = MemberStatus.PENDING,
    @Enumerated(EnumType.STRING) var role: MemberRole = MemberRole.USER,
) : BaseEntity() {
    fun activate() { /* validate PENDING→ACTIVE */ }
    fun suspend() { /* validate ACTIVE→SUSPENDED */ }
    fun unsuspend() { /* validate SUSPENDED→ACTIVE */ }
    fun withdraw() { /* validate ACTIVE|SUSPENDED→WITHDRAWN */ }
}
```

`MemberStatus`: `PENDING, ACTIVE, SUSPENDED, WITHDRAWN`
`MemberRole`: `USER, MERCHANT_OWNER, ADMIN`

- [x] **Step 4: Run domain tests — expect PASS**

- [x] **Step 5: Implement MemberService, repository, controller, DTOs**

Service handles: register, login (JWT), getById, suspend, withdraw.
For JWT: simple `JwtTokenProvider` utility in `config/` that generates/validates tokens. Use `io.jsonwebtoken`.

- [x] **Step 6: Implement SecurityConfig**

```kotlin
@Configuration
@EnableWebSecurity
class SecurityConfig : SecurityFilterChain { ... }
```

For the portfolio, use a simple stateless JWT filter. Permit all for now, secure specific endpoints in later tasks.

- [x] **Step 7: Write integration test for MemberService**

- [x] **Step 8: Run all tests — expect PASS**

- [x] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: add Member module with JWT auth and role-based security"
```

---

## 태스크 5: Merchant 모듈

**Files:**
- Create: `src/main/kotlin/com/commerce/merchant/domain/Merchant.kt`
- Create: `src/main/kotlin/com/commerce/merchant/domain/MerchantStatus.kt`
- Create: `src/main/kotlin/com/commerce/merchant/domain/MerchantCategory.kt`
- Create: `src/main/kotlin/com/commerce/merchant/domain/event/MerchantApprovedEvent.kt`
- Create: `src/main/kotlin/com/commerce/merchant/application/MerchantService.kt`
- Create: `src/main/kotlin/com/commerce/merchant/infrastructure/MerchantJpaRepository.kt`
- Create: `src/main/kotlin/com/commerce/merchant/interfaces/MerchantController.kt`
- Test: `src/test/kotlin/com/commerce/merchant/domain/MerchantTest.kt`

- [x] **Step 1: Write Merchant domain tests**

Test all state transitions from spec:
- PENDING_APPROVAL→APPROVED, PENDING_APPROVAL→REJECTED
- APPROVED→SUSPENDED, SUSPENDED→APPROVED, SUSPENDED→TERMINATED, APPROVED→TERMINATED
- Invalid transitions throw BusinessException

- [x] **Step 2: Run tests — expect FAIL**

- [x] **Step 3: Implement Merchant entity**

```kotlin
@Entity
@Table(name = "merchants")
class Merchant(
    @Column(nullable = false) val name: String,
    @Column(nullable = false) val businessNumber: String,
    @Enumerated(EnumType.STRING) val category: MerchantCategory,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "region_id") val region: Region,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "owner_id") val owner: Member,
    @Enumerated(EnumType.STRING) var status: MerchantStatus = MerchantStatus.PENDING_APPROVAL,
) : BaseEntity() {
    fun approve() { /* PENDING_APPROVAL→APPROVED + publish MerchantApprovedEvent */ }
    fun reject() { /* PENDING_APPROVAL→REJECTED */ }
    fun suspend() { /* APPROVED→SUSPENDED */ }
    fun unsuspend() { /* SUSPENDED→APPROVED */ }
    fun terminate() { /* APPROVED|SUSPENDED→TERMINATED */ }
}
```

`MerchantStatus`: `PENDING_APPROVAL, APPROVED, REJECTED, SUSPENDED, TERMINATED`
`MerchantCategory`: `RESTAURANT, CAFE, RETAIL, GROCERY, OTHER`

MerchantApprovedEvent extends DomainEvent:
```kotlin
class MerchantApprovedEvent(
    override val aggregateId: Long,
    val regionId: Long,
) : DomainEvent() {
    override val aggregateType = "MERCHANT"
    override val eventType = "MERCHANT_APPROVED"
}
```

- [x] **Step 4: Run domain tests — expect PASS**

- [x] **Step 5: Implement MerchantService (register, approve, reject, re-apply as new record), repository, controller**

- [x] **Step 6: Write integration test verifying MerchantApprovedEvent triggers audit log**

- [x] **Step 7: Run all tests — expect PASS**

- [x] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: add Merchant module with state machine and approval flow"
```

---

## 태스크 6: Ledger 및 Transaction 모듈 ★★

**Files:**
- Create: `src/main/kotlin/com/commerce/ledger/domain/LedgerEntry.kt`
- Create: `src/main/kotlin/com/commerce/ledger/domain/LedgerEntryType.kt`
- Create: `src/main/kotlin/com/commerce/ledger/domain/AccountCode.kt`
- Create: `src/main/kotlin/com/commerce/ledger/application/LedgerService.kt`
- Create: `src/main/kotlin/com/commerce/ledger/infrastructure/LedgerJpaRepository.kt`
- Create: `src/main/kotlin/com/commerce/transaction/domain/Transaction.kt`
- Create: `src/main/kotlin/com/commerce/transaction/domain/TransactionStatus.kt`
- Create: `src/main/kotlin/com/commerce/transaction/domain/TransactionType.kt`
- Create: `src/main/kotlin/com/commerce/transaction/application/TransactionService.kt`
- Create: `src/main/kotlin/com/commerce/transaction/infrastructure/TransactionJpaRepository.kt`
- Test: `src/test/kotlin/com/commerce/ledger/application/LedgerServiceTest.kt`
- Test: `src/test/kotlin/com/commerce/ledger/domain/LedgerEntryTest.kt`

- [x] **Step 1: Write LedgerEntry immutability test**

```kotlin
class LedgerEntryTest : DescribeSpec({
    describe("LedgerEntry immutability") {
        it("should not have any setter methods except for JPA") {
            val methods = LedgerEntry::class.java.declaredMethods
            val setters = methods.filter { it.name.startsWith("set") }
            setters shouldBe emptyList()
        }
    }
})
```

- [x] **Step 2: Run test — expect FAIL**

- [x] **Step 3: Implement AccountCode, LedgerEntryType, LedgerEntry (2-row model)**

> **Design note:** 스펙에 따라 하나의 Transaction에 2개의 LedgerEntry(차변 1행 + 대변 1행)를 쌍으로 생성합니다. 각 행은 하나의 계정에 대한 단일 방향(DEBIT 또는 CREDIT) 기록입니다.

```kotlin
enum class AccountCode(val description: String) {
    MEMBER_CASH("회원 현금"),
    VOUCHER_BALANCE("상품권 잔액"),
    MERCHANT_RECEIVABLE("가맹점 미수금"),
    REVENUE_DISCOUNT("할인 수익"),
    EXPIRED_VOUCHER("만료 상품권"),
    REFUND_PAYABLE("환불 미지급금"),
    SETTLEMENT_PAYABLE("정산 미지급금"),
}

enum class LedgerEntryType {
    PURCHASE, REDEMPTION, REFUND, WITHDRAWAL, EXPIRY, SETTLEMENT, MANUAL_ADJUSTMENT
}

enum class LedgerEntrySide {
    DEBIT, CREDIT
}

@Entity
@Table(name = "ledger_entries", indexes = [
    Index(name = "idx_ledger_tx", columnList = "transactionId"),
    Index(name = "idx_ledger_account", columnList = "account, createdAt")
])
@Immutable  // Hibernate annotation — prevents UPDATE/DELETE
class LedgerEntry(
    @Enumerated(EnumType.STRING) @Column(nullable = false) val account: AccountCode,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val side: LedgerEntrySide,
    @Column(nullable = false) val amount: BigDecimal,
    @Column(nullable = false) val transactionId: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val entryType: LedgerEntryType,
    @Column(nullable = false) val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    init {
        require(amount > BigDecimal.ZERO) { "Amount must be positive" }
    }
}
```

> **원화(KRW) 참고:** 모든 금액은 정수 원 단위입니다. BigDecimal을 사용하지만 소수점 이하 값은 발생하지 않습니다. Redis 카운터에서 `toLong()` 변환 시 정밀도 손실이 없습니다.

- [x] **Step 4: Run immutability test — expect PASS**

- [x] **Step 5: Implement Transaction entity**

```kotlin
enum class TransactionType {
    PURCHASE, REDEMPTION, REFUND, WITHDRAWAL, EXPIRY, CANCELLATION
}

@Entity
@Table(name = "transactions", indexes = [
    Index(name = "idx_tx_voucher", columnList = "voucherId, createdAt"),
    Index(name = "idx_tx_merchant_period", columnList = "merchantId, status, createdAt")
])
class Transaction(
    @Enumerated(EnumType.STRING) @Column(nullable = false) val type: TransactionType,
    @Column(nullable = false) val amount: BigDecimal,
    val voucherId: Long? = null,
    val merchantId: Long? = null,
    val memberId: Long? = null,
    val originalTransactionId: Long? = null,  // for compensating transactions
    @Enumerated(EnumType.STRING) var status: TransactionStatus = TransactionStatus.PENDING,
) : BaseEntity() {
    fun complete() {
        if (status != TransactionStatus.PENDING) throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = TransactionStatus.COMPLETED
    }
    fun fail() {
        if (status != TransactionStatus.PENDING) throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = TransactionStatus.FAILED
    }
    fun requestCancel() {
        if (status != TransactionStatus.COMPLETED) throw BusinessException(ErrorCode.TRANSACTION_NOT_CANCELLABLE)
        status = TransactionStatus.CANCEL_REQUESTED
    }
    fun cancel() {
        if (status != TransactionStatus.CANCEL_REQUESTED) throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = TransactionStatus.CANCELLED
    }
}
```

- [x] **Step 6: Write LedgerService test — debit/credit pair must balance**

```kotlin
class LedgerServiceTest : IntegrationTestSupport() {
    @Test
    fun `record should create debit and credit entry pair (2 rows)`() {
        val txId = createTransaction()
        ledgerService.record(
            debitAccount = AccountCode.VOUCHER_BALANCE,
            creditAccount = AccountCode.MEMBER_CASH,
            amount = BigDecimal("50000"),
            transactionId = txId,
            entryType = LedgerEntryType.PURCHASE
        )
        val entries = ledgerRepository.findByTransactionId(txId)
        entries.size shouldBe 2  // debit 1행 + credit 1행
        val debit = entries.first { it.side == LedgerEntrySide.DEBIT }
        val credit = entries.first { it.side == LedgerEntrySide.CREDIT }
        debit.account shouldBe AccountCode.VOUCHER_BALANCE
        debit.amount shouldBe BigDecimal("50000")
        credit.account shouldBe AccountCode.MEMBER_CASH
        credit.amount shouldBe BigDecimal("50000")
    }
}
```

- [x] **Step 7: Run test — expect FAIL**

- [x] **Step 8: Implement LedgerService**

```kotlin
@Service
class LedgerService(
    private val ledgerRepository: LedgerJpaRepository
) {
    /**
     * 복식부기: debit 1행 + credit 1행 = 2행을 동일 트랜잭션에서 생성.
     * 이 메서드는 반드시 @Transactional 내에서 동기 호출해야 합니다.
     */
    fun record(
        debitAccount: AccountCode,
        creditAccount: AccountCode,
        amount: BigDecimal,
        transactionId: Long,
        entryType: LedgerEntryType
    ): List<LedgerEntry> {
        val debitEntry = LedgerEntry(
            account = debitAccount,
            side = LedgerEntrySide.DEBIT,
            amount = amount,
            transactionId = transactionId,
            entryType = entryType
        )
        val creditEntry = LedgerEntry(
            account = creditAccount,
            side = LedgerEntrySide.CREDIT,
            amount = amount,
            transactionId = transactionId,
            entryType = entryType
        )
        return ledgerRepository.saveAll(listOf(debitEntry, creditEntry))
    }

    fun getEntriesByTransactionId(transactionId: Long): List<LedgerEntry> =
        ledgerRepository.findByTransactionId(transactionId)

    /** 특정 계정의 순잔액 = sum(DEBIT) - sum(CREDIT) */
    fun netBalanceByAccount(account: AccountCode): BigDecimal {
        val debits = ledgerRepository.sumByAccountAndSide(account, LedgerEntrySide.DEBIT) ?: BigDecimal.ZERO
        val credits = ledgerRepository.sumByAccountAndSide(account, LedgerEntrySide.CREDIT) ?: BigDecimal.ZERO
        return debits - credits
    }
}
```

- [x] **Step 9: Run test — expect PASS**

- [x] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: add Ledger and Transaction modules with immutable double-entry bookkeeping"
```

---

## 태스크 7: 멱등키 모듈

**Files:**
- Create: `src/main/kotlin/com/commerce/common/idempotency/IdempotencyKey.kt`
- Create: `src/main/kotlin/com/commerce/common/idempotency/IdempotencyRepository.kt`
- Create: `src/main/kotlin/com/commerce/common/idempotency/IdempotencyInterceptor.kt`
- Create: `src/main/kotlin/com/commerce/common/idempotency/Idempotent.kt` (annotation)
- Create: `src/main/kotlin/com/commerce/common/idempotency/IdempotencyRedisRepository.kt`
- Test: `src/test/kotlin/com/commerce/common/idempotency/IdempotencyInterceptorTest.kt`

- [x] **Step 1: Write idempotency test — duplicate request returns same response**

```kotlin
class IdempotencyInterceptorTest : IntegrationTestSupport() {
    @Test
    fun `duplicate request with same idempotency key should return cached response`() {
        // First request succeeds
        val key = UUID.randomUUID().toString()
        val result1 = mockMvc.perform(post("/api/v1/test/idempotent")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"value": "test"}"""))
            .andExpect(status().isOk)
            .andReturn()

        // Duplicate request returns same response
        val result2 = mockMvc.perform(post("/api/v1/test/idempotent")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"value": "test"}"""))
            .andExpect(status().isOk)
            .andReturn()

        result1.response.contentAsString shouldBe result2.response.contentAsString
    }
}
```

- [x] **Step 2: Run test — expect FAIL**

- [x] **Step 3: Implement `@Idempotent` annotation, IdempotencyKey entity, Redis + DB dual storage, AOP interceptor**

The `@Idempotent` annotation marks controller methods. The AOP interceptor:
1. Extracts `Idempotency-Key` header
2. Checks Redis first, then DB fallback
3. If found: returns cached response body + status
4. If not found: proceeds with the request, stores result in both Redis (TTL 24h) and DB

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Idempotent
```

IdempotencyKey entity stores: key (unique), responseBody (TEXT), responseStatus (INT), createdAt.

- [x] **Step 4: Run test — expect PASS**

- [x] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add idempotency module with Redis+DB dual storage and AOP interceptor"
```

---

## 태스크 8: Voucher 모듈 — 발행 ★

**Files:**
- Create: `src/main/kotlin/com/commerce/voucher/domain/Voucher.kt`
- Create: `src/main/kotlin/com/commerce/voucher/domain/VoucherStatus.kt`
- Create: `src/main/kotlin/com/commerce/voucher/domain/VoucherCodeGenerator.kt`
- Create: `src/main/kotlin/com/commerce/voucher/domain/event/VoucherIssuedEvent.kt`
- Create: `src/main/kotlin/com/commerce/voucher/application/VoucherIssueService.kt`
- Create: `src/main/kotlin/com/commerce/voucher/infrastructure/VoucherJpaRepository.kt`
- Create: `src/main/kotlin/com/commerce/voucher/infrastructure/VoucherLockManager.kt`
- Create: `src/main/kotlin/com/commerce/voucher/interfaces/VoucherController.kt`
- Create: `src/main/kotlin/com/commerce/config/RedisConfig.kt`
- Test: `src/test/kotlin/com/commerce/voucher/domain/VoucherTest.kt`
- Test: `src/test/kotlin/com/commerce/voucher/domain/VoucherCodeGeneratorTest.kt`
- Test: `src/test/kotlin/com/commerce/voucher/application/VoucherIssueServiceTest.kt`

- [x] **Step 1: Write VoucherCodeGenerator test**

```kotlin
class VoucherCodeGeneratorTest : DescribeSpec({
    val generator = VoucherCodeGenerator()

    describe("generate") {
        it("should produce code in format XX-XXXXXXXXXXXXXXXX") {
            val code = generator.generate("SN")
            code.length shouldBe 19  // 2 + 1 + 16
            code.substring(0, 2) shouldBe "SN"
            code[2] shouldBe '-'
        }
        it("should pass Luhn mod 36 check digit validation") {
            val code = generator.generate("SN")
            generator.validate(code) shouldBe true
        }
        it("should fail validation with tampered code") {
            val code = generator.generate("SN")
            val tampered = code.dropLast(1) + if (code.last() == 'A') 'B' else 'A'
            generator.validate(tampered) shouldBe false
        }
    }
})
```

- [x] **Step 2: Run test — expect FAIL**

- [x] **Step 3: Implement VoucherCodeGenerator**

```kotlin
@Component
class VoucherCodeGenerator {
    private val random = SecureRandom()
    private val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun generate(regionCode: String): String {
        val payload = (1..15).map { chars[random.nextInt(chars.length)] }.joinToString("")
        val checkDigit = calculateLuhnMod36CheckDigit(payload)
        return "$regionCode-$payload$checkDigit"
    }

    fun validate(code: String): Boolean {
        val parts = code.split("-")
        if (parts.size != 2 || parts[1].length != 16) return false
        val payload = parts[1].dropLast(1)
        val expected = calculateLuhnMod36CheckDigit(payload)
        return parts[1].last() == expected
    }

    private fun calculateLuhnMod36CheckDigit(input: String): Char {
        var factor = 2
        var sum = 0
        for (i in input.indices.reversed()) {
            var addend = chars.indexOf(input[i]) * factor
            addend = (addend / 36) + (addend % 36)
            sum += addend
            factor = if (factor == 2) 1 else 2
        }
        val remainder = sum % 36
        return chars[(36 - remainder) % 36]
    }
}
```

- [x] **Step 4: Run code generator test — expect PASS**

- [x] **Step 5: Write Voucher domain tests (state transitions)**

Test: ACTIVE→PARTIALLY_USED, ACTIVE→EXHAUSTED, ACTIVE→EXPIRED, invalid transitions.

- [x] **Step 6: Implement Voucher entity**

```kotlin
@Entity
@Table(name = "vouchers", indexes = [
    Index(name = "idx_voucher_member", columnList = "memberId, status"),
    Index(name = "idx_voucher_region_status", columnList = "regionId, status, expiresAt"),
    Index(name = "idx_voucher_expiry", columnList = "status, expiresAt"),
])
class Voucher(
    @Column(nullable = false, unique = true) val voucherCode: String,
    @Column(nullable = false) val faceValue: BigDecimal,
    @Column(nullable = false) var balance: BigDecimal,
    @Column(nullable = false) val memberId: Long,
    @Column(nullable = false) val regionId: Long,
    @Column(nullable = false) val purchasedAt: LocalDateTime,
    @Column(nullable = false) val expiresAt: LocalDateTime,
    @Enumerated(EnumType.STRING) var status: VoucherStatus = VoucherStatus.ACTIVE,
) : BaseEntity() {
    val usageRatio: BigDecimal
        get() = if (faceValue > BigDecimal.ZERO)
            (faceValue - balance).divide(faceValue, 4, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

    fun redeem(amount: BigDecimal) { /* validate usable + sufficient balance, deduct */ }
    fun expire() { /* validate ACTIVE|PARTIALLY_USED → EXPIRED */ }
    fun requestRefund() { /* validate PARTIALLY_USED + usageRatio >= 0.6 */ }
    fun completeRefund() { /* REFUND_REQUESTED → REFUNDED */ }
    fun requestWithdrawal() { /* validate ACTIVE + within 7 days */ }
    fun completeWithdrawal() { /* WITHDRAWAL_REQUESTED → WITHDRAWN */ }
    fun isUsable(): Boolean = status in setOf(VoucherStatus.ACTIVE, VoucherStatus.PARTIALLY_USED)
    fun isExpired(): Boolean = expiresAt.isBefore(LocalDateTime.now())
}
```

- [x] **Step 7: Run domain tests — expect PASS**

- [x] **Step 8: Implement VoucherLockManager (Redisson wrapper)**

```kotlin
@Component
class VoucherLockManager(private val redissonClient: RedissonClient) {
    fun <T> withVoucherLock(voucherId: Long, action: () -> T): T =
        withLock("voucher:$voucherId", action)

    fun <T> withMemberPurchaseLock(memberId: Long, action: () -> T): T =
        withLock("member:purchase:$memberId", action)

    private fun <T> withLock(key: String, action: () -> T): T {
        val lock = redissonClient.getLock(key)
        val acquired = lock.tryLock(5, 10, TimeUnit.SECONDS)
        if (!acquired) throw BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED)
        try { return action() } finally { if (lock.isHeldByCurrentThread) lock.unlock() }
    }
}
```

- [x] **Step 9: Implement RedisConfig for Redisson + Region monthly counter**

```kotlin
@Configuration
class RedisConfig {
    @Bean
    fun redissonClient(redisProperties: RedisProperties): RedissonClient {
        val config = Config()
        config.useSingleServer().address = "redis://${redisProperties.host}:${redisProperties.port}"
        return Redisson.create(config)
    }
}
```

- [x] **Step 10: Implement VoucherIssueService**

```kotlin
@Service
class VoucherIssueService(
    private val voucherRepository: VoucherJpaRepository,
    private val lockManager: VoucherLockManager,
    private val regionService: RegionService,
    private val codeGenerator: VoucherCodeGenerator,
    private val ledgerService: LedgerService,
    private val transactionService: TransactionService,
    private val redissonClient: RedissonClient,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Transactional
    fun issue(memberId: Long, regionId: Long, faceValue: BigDecimal): Voucher {
        return lockManager.withMemberPurchaseLock(memberId) {
            val region = regionService.getById(regionId)
            // 1. Check region active
            // 2. Check member purchase limit (query DB)
            // 3. Check region monthly limit (Redis INCRBY atomic counter)
            checkRegionMonthlyLimit(regionId, faceValue, region.policy.monthlyIssuanceLimit)
            // 4. Generate voucher code
            val code = codeGenerator.generate(region.regionCode)
            // 5. Create voucher (ACTIVE)
            val voucher = voucherRepository.save(Voucher(...))
            // 6. Create transaction + ledger entry (synchronous)
            val tx = transactionService.create(TransactionType.PURCHASE, faceValue, voucherId = voucher.id, memberId = memberId)
            ledgerService.record(AccountCode.MEMBER_CASH, AccountCode.VOUCHER_BALANCE, faceValue, tx.id, LedgerEntryType.PURCHASE)
            tx.complete()
            // 7. Publish event (for audit log)
            eventPublisher.publishEvent(VoucherIssuedEvent(voucher.id, memberId, regionId, faceValue))
            voucher
        }
    }

    private fun checkRegionMonthlyLimit(regionId: Long, amount: BigDecimal, limit: BigDecimal) {
        val key = "region:monthly:$regionId:${YearMonth.now()}"
        val counter = redissonClient.getAtomicLong(key)
        val newTotal = counter.addAndGet(amount.toLong())
        if (newTotal > limit.toLong()) {
            counter.addAndGet(-amount.toLong())  // rollback
            throw BusinessException(ErrorCode.REGION_MONTHLY_LIMIT_EXCEEDED)
        }
        // Set TTL if not set (end of month + 1 day)
        if (counter.remainTimeToLive() == -1L) {
            val endOfMonth = YearMonth.now().atEndOfMonth().plusDays(1)
            counter.expire(Duration.between(LocalDateTime.now(), endOfMonth.atStartOfDay()))
        }
    }
}
```

- [x] **Step 11: Write VoucherIssueService integration test**

Test: successful issuance, member limit exceeded, region monthly limit exceeded.

- [x] **Step 12: Run all tests — expect PASS**

- [x] **Step 13: Commit**

```bash
git add -A
git commit -m "feat: add Voucher issuance with distributed lock, atomic region counter, and code generator"
```

---

## 태스크 9: Voucher 모듈 — 결제(사용) ★★

**Files:**
- Create: `src/main/kotlin/com/commerce/voucher/application/VoucherRedemptionService.kt`
- Create: `src/main/kotlin/com/commerce/voucher/domain/event/VoucherRedeemedEvent.kt`
- Modify: `src/main/kotlin/com/commerce/voucher/interfaces/VoucherController.kt` (add redeem endpoint)
- Test: `src/test/kotlin/com/commerce/voucher/application/VoucherRedemptionServiceTest.kt`

- [x] **Step 1: Write redemption test — happy path**

```kotlin
@Test
fun `should redeem voucher and record ledger entry`() {
    val voucher = issueVoucher(faceValue = 50000)
    val result = redemptionService.redeem(voucher.id, merchantId, BigDecimal("30000"), idempotencyKey)

    result.remainingBalance shouldBe BigDecimal("20000")
    // Verify ledger entry exists
    val entries = ledgerRepository.findByTransactionId(result.transactionId)
    entries.size shouldBe 1
    entries[0].debitAccount shouldBe AccountCode.MERCHANT_RECEIVABLE
    entries[0].creditAccount shouldBe AccountCode.VOUCHER_BALANCE
}
```

- [x] **Step 2: Write redemption test — insufficient balance**

```kotlin
@Test
fun `should reject redemption when balance is insufficient`() {
    val voucher = issueVoucher(faceValue = 10000)
    shouldThrow<BusinessException> {
        redemptionService.redeem(voucher.id, merchantId, BigDecimal("20000"), idempotencyKey)
    }.errorCode shouldBe ErrorCode.INSUFFICIENT_BALANCE
}
```

- [x] **Step 3: Write redemption test — expired voucher**

```kotlin
@Test
fun `should reject redemption for expired voucher`() {
    val voucher = issueExpiredVoucher()
    shouldThrow<BusinessException> {
        redemptionService.redeem(voucher.id, merchantId, BigDecimal("5000"), idempotencyKey)
    }.errorCode shouldBe ErrorCode.VOUCHER_EXPIRED
}
```

- [x] **Step 4: Run tests — expect FAIL**

- [x] **Step 5: Implement VoucherRedemptionService**

This is the most critical service. The flow:
1. Acquire Redisson distributed lock on `voucher:{id}`
2. Inside lock: `SELECT FOR UPDATE` to get Voucher row
3. Validate: usable status, not expired, sufficient balance
4. Deduct balance, update status (ACTIVE→PARTIALLY_USED or EXHAUSTED)
5. Create Transaction (REDEMPTION, COMPLETED)
6. Record LedgerEntry: debit MERCHANT_RECEIVABLE, credit VOUCHER_BALANCE
7. Publish VoucherRedeemedEvent
8. All within single @Transactional

```kotlin
@Service
class VoucherRedemptionService(
    private val voucherRepository: VoucherJpaRepository,
    private val lockManager: VoucherLockManager,
    private val ledgerService: LedgerService,
    private val transactionService: TransactionService,
    private val eventPublisher: ApplicationEventPublisher,
    private val meterRegistry: MeterRegistry,
) {
    @Transactional
    fun redeem(voucherId: Long, merchantId: Long, amount: BigDecimal, idempotencyKey: String): RedemptionResult {
        return lockManager.withVoucherLock(voucherId) {
            val timer = Timer.start(meterRegistry)
            try {
                // Pessimistic lock
                val voucher = voucherRepository.findByIdForUpdate(voucherId)
                    ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)

                // Validate
                if (!voucher.isUsable()) throw BusinessException(ErrorCode.VOUCHER_NOT_USABLE)
                if (voucher.isExpired()) throw BusinessException(ErrorCode.VOUCHER_EXPIRED)
                if (voucher.balance < amount) throw BusinessException(ErrorCode.INSUFFICIENT_BALANCE)

                // Deduct balance
                voucher.redeem(amount)

                // Create transaction + ledger (synchronous, same DB tx)
                val tx = transactionService.create(
                    type = TransactionType.REDEMPTION,
                    amount = amount,
                    voucherId = voucherId,
                    merchantId = merchantId
                )
                ledgerService.record(
                    debitAccount = AccountCode.MERCHANT_RECEIVABLE,
                    creditAccount = AccountCode.VOUCHER_BALANCE,
                    amount = amount,
                    transactionId = tx.id,
                    entryType = LedgerEntryType.REDEMPTION
                )
                tx.complete()

                // Publish event (audit log via BEFORE_COMMIT listener)
                eventPublisher.publishEvent(VoucherRedeemedEvent(
                    aggregateId = voucherId,
                    merchantId = merchantId,
                    amount = amount,
                    remainingBalance = voucher.balance,
                    transactionId = tx.id
                ))

                meterRegistry.counter("voucher.redemption.count", "result", "success").increment()
                RedemptionResult(transactionId = tx.id, remainingBalance = voucher.balance)
            } catch (e: Exception) {
                meterRegistry.counter("voucher.redemption.count", "result", "failure").increment()
                throw e
            } finally {
                timer.stop(meterRegistry.timer("voucher.redemption.duration"))
            }
        }
    }
}

data class RedemptionResult(val transactionId: Long, val remainingBalance: BigDecimal)
```

VoucherJpaRepository needs:
```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT v FROM Voucher v WHERE v.id = :id")
fun findByIdForUpdate(@Param("id") id: Long): Voucher?
```

- [x] **Step 6: Run tests — expect PASS**

- [x] **Step 7: Add redeem endpoint to VoucherController**

```kotlin
@PostMapping("/{id}/redeem")
@Idempotent
fun redeem(
    @PathVariable id: Long,
    @RequestBody request: RedeemRequest,
    @RequestHeader("Idempotency-Key") idempotencyKey: String
): RedemptionResult = redemptionService.redeem(id, request.merchantId, request.amount, idempotencyKey)
```

- [x] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: add voucher redemption with distributed lock + pessimistic lock dual defense"
```

---

## 태스크 10: Voucher 모듈 — 잔액 환불 ★

**Files:**
- Create: `src/main/kotlin/com/commerce/voucher/application/VoucherRefundService.kt`
- Create: `src/main/kotlin/com/commerce/voucher/domain/event/VoucherRefundedEvent.kt`
- Test: `src/test/kotlin/com/commerce/voucher/application/VoucherRefundServiceTest.kt`

- [x] **Step 1: Write refund tests**

Test: successful refund (60%+ used), rejection (less than 60% used), refund restores balance to 0.

- [x] **Step 2: Run tests — expect FAIL**

- [x] **Step 3: Implement VoucherRefundService**

Flow: distributed lock → validate PARTIALLY_USED + usageRatio ≥ 0.6 → create compensating transaction → ledger entry (debit REFUND_PAYABLE, credit VOUCHER_BALANCE) → set balance to 0 → REFUNDED status.

- [x] **Step 4: Run tests — expect PASS**

- [x] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add balance refund with 60% usage threshold and compensating transaction"
```

---

## 태스크 10a: Voucher 모듈 — 청약철회 ★

**Files:**
- Create: `src/main/kotlin/com/commerce/voucher/application/VoucherWithdrawalService.kt`
- Create: `src/main/kotlin/com/commerce/voucher/domain/event/VoucherWithdrawnEvent.kt`
- Test: `src/test/kotlin/com/commerce/voucher/application/VoucherWithdrawalServiceTest.kt`

- [x] **Step 1: Write withdrawal tests**

Test: successful withdrawal (ACTIVE + within 7 days), rejection (after 7 days), rejection (PARTIALLY_USED).

- [x] **Step 2: Run tests — expect FAIL**

- [x] **Step 3: Implement VoucherWithdrawalService**

Flow: distributed lock → validate ACTIVE + purchasedAt + 7 days >= now → WITHDRAWAL_REQUESTED → full refund amount = faceValue → compensating transaction → ledger entry (debit REFUND_PAYABLE, credit VOUCHER_BALANCE) → WITHDRAWN.

- [x] **Step 4: Run tests — expect PASS**

- [x] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add 7-day withdrawal (청약철회) with full refund"
```

---

## 태스크 11: 거래 취소 및 보상 트랜잭션 ★★

**Files:**
- Create: `src/main/kotlin/com/commerce/transaction/application/TransactionCancelService.kt`
- Create: `src/main/kotlin/com/commerce/transaction/domain/event/TransactionCancelledEvent.kt`
- Test: `src/test/kotlin/com/commerce/transaction/application/TransactionCancelServiceTest.kt`

- [x] **Step 1: Write cancellation tests**

Test: cancel a COMPLETED redemption → balance restored, compensating transaction created with `original_transaction_id`, original transaction status becomes CANCELLED, new reverse ledger entries exist.

- [x] **Step 2: Run tests — expect FAIL**

- [x] **Step 3: Implement TransactionCancelService**

Flow:
1. Get original transaction, validate COMPLETED
2. Lock the voucher
3. Create compensating transaction (type=CANCELLATION, originalTransactionId=original.id)
4. Create reverse ledger entry (debit VOUCHER_BALANCE, credit MERCHANT_RECEIVABLE)
5. Restore voucher balance
6. Mark original transaction as CANCELLED
7. Publish TransactionCancelledEvent

- [x] **Step 4: Run tests — expect PASS**

- [x] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add transaction cancellation with compensating transactions (no delete)"
```

---

## 태스크 12: 만료 처리 스케줄러 ★

**Files:**
- Create: `src/main/kotlin/com/commerce/voucher/application/VoucherExpiryScheduler.kt`
- Create: `src/main/kotlin/com/commerce/voucher/domain/event/VoucherExpiredEvent.kt`
- Test: `src/test/kotlin/com/commerce/voucher/application/VoucherExpirySchedulerTest.kt`

- [x] **Step 1: Write expiry test**

Test: expired voucher gets status changed and remaining balance moved to EXPIRED account in ledger.

- [x] **Step 2: Run test — expect FAIL**

- [x] **Step 3: Implement VoucherExpiryScheduler**

```kotlin
@Component
class VoucherExpiryScheduler(
    private val voucherRepository: VoucherJpaRepository,
    private val expiryProcessor: VoucherExpiryProcessor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 */5 * * * *")  // every 5 minutes
    fun processExpiredVouchers() {
        // 만료 대상 조회 (트랜잭션 밖에서)
        val expiredIds = voucherRepository.findExpiredVoucherIds(
            statuses = listOf(VoucherStatus.ACTIVE, VoucherStatus.PARTIALLY_USED),
            now = LocalDateTime.now(),
            limit = PageRequest.of(0, 100)
        )
        // 건별 독립 트랜잭션으로 처리 (C4: chunk 단위 커밋)
        expiredIds.forEach { id ->
            try {
                expiryProcessor.processExpiry(id)
            } catch (e: Exception) {
                log.error("Failed to expire voucher {}: {}", id, e.message)
                // 개별 실패는 다른 건 처리에 영향 없음
            }
        }
    }
}

@Service
class VoucherExpiryProcessor(
    private val voucherRepository: VoucherJpaRepository,
    private val ledgerService: LedgerService,
    private val transactionService: TransactionService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    /** 건별 독립 트랜잭션: 실패 시 이 건만 롤백 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processExpiry(voucherId: Long) {
        val locked = voucherRepository.findByIdForUpdate(voucherId) ?: return
        if (!locked.isUsable() || !locked.isExpired()) return

        val remainingBalance = locked.balance
        locked.expire()

        if (remainingBalance > BigDecimal.ZERO) {
            val tx = transactionService.create(TransactionType.EXPIRY, remainingBalance, voucherId = locked.id)
            ledgerService.record(AccountCode.EXPIRED_VOUCHER, AccountCode.VOUCHER_BALANCE, remainingBalance, tx.id, LedgerEntryType.EXPIRY)
            tx.complete()
        }
        eventPublisher.publishEvent(VoucherExpiredEvent(locked.id, remainingBalance))
    }
}
```

VoucherJpaRepository needs:
```kotlin
@Query("SELECT v FROM Voucher v WHERE v.status IN :statuses AND v.expiresAt < :now ORDER BY v.expiresAt ASC")
fun findExpiredVouchers(statuses: List<VoucherStatus>, now: LocalDateTime, limit: Pageable): List<Voucher>
```

- [x] **Step 4: Run test — expect PASS**

- [x] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add voucher expiry scheduler with chunk processing and ledger recording"
```

---

## 태스크 13: 정산 모듈 ★

**Files:**
- Create: `src/main/kotlin/com/commerce/merchant/domain/Settlement.kt`
- Create: `src/main/kotlin/com/commerce/merchant/domain/SettlementStatus.kt`
- Create: `src/main/kotlin/com/commerce/merchant/domain/event/SettlementConfirmedEvent.kt`
- Create: `src/main/kotlin/com/commerce/merchant/application/SettlementService.kt`
- Create: `src/main/kotlin/com/commerce/merchant/infrastructure/SettlementJpaRepository.kt`
- Test: `src/test/kotlin/com/commerce/merchant/application/SettlementServiceTest.kt`

- [x] **Step 1: Write settlement calculation test**

Test: sum redemptions minus cancellations for a merchant in a period.

- [x] **Step 2: Run test — expect FAIL**

- [x] **Step 3: Implement Settlement entity and service**

Settlement entity: merchantId, periodStart, periodEnd, totalAmount, status (PENDING→CONFIRMED→PAID→DISPUTED), unique constraint on (merchantId, periodStart, periodEnd).

```kotlin
enum class SettlementStatus {
    PENDING, CONFIRMED, PAID, DISPUTED
}

@Entity
@Table(name = "settlements", uniqueConstraints = [
    UniqueConstraint(name = "uk_settlement_period", columnNames = ["merchantId", "periodStart", "periodEnd"])
])
class Settlement(
    val merchantId: Long,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    var totalAmount: BigDecimal,
    @Enumerated(EnumType.STRING) var status: SettlementStatus = SettlementStatus.PENDING,
    var disputeReason: String? = null,
) : BaseEntity() {
    fun confirm() {
        if (status != SettlementStatus.PENDING && status != SettlementStatus.DISPUTED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = SettlementStatus.CONFIRMED
        disputeReason = null
    }
    fun dispute(reason: String) {
        if (status != SettlementStatus.PENDING)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = SettlementStatus.DISPUTED
        disputeReason = reason
    }
    fun pay() {
        if (status != SettlementStatus.CONFIRMED)
            throw BusinessException(ErrorCode.INVALID_STATE_TRANSITION)
        status = SettlementStatus.PAID
    }
}
```

SettlementService: calculate settlement based on completed transactions minus cancelled transactions in the period. Include `dispute()` and `resolveDispute()` methods.

SettlementController (`src/main/kotlin/com/commerce/merchant/interfaces/SettlementController.kt`):
- `POST /api/v1/settlements/calculate` — 정산 배치 실행
- `POST /api/v1/settlements/{id}/confirm` — 정산 확정
- `POST /api/v1/settlements/{id}/dispute` — 이의 제기 (reason 필수)
- `GET /api/v1/settlements?merchantId=&period=` — 정산 내역 조회

- [x] **Step 4: Run test — expect PASS**

- [x] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add Settlement module with period-based calculation and dedup constraint"
```

---

## 태스크 14: 원장 정합성 검증 배치 ★★

**Files:**
- Create: `src/main/kotlin/com/commerce/ledger/application/LedgerVerificationService.kt`
- Test: `src/test/kotlin/com/commerce/ledger/application/LedgerVerificationServiceTest.kt`

- [x] **Step 1: Write verification test — balanced ledger passes**

```kotlin
@Test
fun `should pass verification when ledger is balanced`() {
    // Issue and redeem some vouchers
    val result = verificationService.verify()
    result.isBalanced shouldBe true
    result.imbalancedVouchers shouldBe emptyList()
}
```

- [x] **Step 2: Write verification test — detect imbalance**

```kotlin
@Test
fun `should detect imbalance between voucher balance and ledger sum`() {
    // Manually corrupt a voucher's balance field
    val result = verificationService.verify()
    result.isBalanced shouldBe false
    result.imbalancedVouchers.size shouldBe 1
}
```

- [x] **Step 3: Run tests — expect FAIL**

- [x] **Step 4: Implement LedgerVerificationService**

```kotlin
@Service
class LedgerVerificationService(
    private val ledgerRepository: LedgerJpaRepository,
    private val voucherRepository: VoucherJpaRepository,
    private val auditLogRepository: AuditLogRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 2 * * *")  // daily at 2 AM
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun scheduledVerification() {
        val result = verify()
        meterRegistry.gauge("ledger.verification.imbalance", result.imbalancedVouchers.size)
        if (!result.isBalanced) {
            log.error("LEDGER IMBALANCE DETECTED: {} vouchers", result.imbalancedVouchers.size)
            // Record CRITICAL audit log for each imbalance
        }
    }

    fun verify(): VerificationResult {
        // 1. Global balance check: sum all debits == sum all credits
        val globalBalanced = checkGlobalBalance()
        // 2. Per-voucher check: voucher.balance == net ledger entries for that voucher
        val imbalanced = checkVoucherBalances()
        return VerificationResult(
            isBalanced = globalBalanced && imbalanced.isEmpty(),
            globalDebitTotal = ...,
            globalCreditTotal = ...,
            imbalancedVouchers = imbalanced
        )
    }

    private fun checkGlobalBalance(): Boolean { ... }
    private fun checkVoucherBalances(): List<ImbalancedVoucher> { ... }
}

data class VerificationResult(
    val isBalanced: Boolean,
    val globalDebitTotal: BigDecimal,
    val globalCreditTotal: BigDecimal,
    val imbalancedVouchers: List<ImbalancedVoucher>
)

data class ImbalancedVoucher(
    val voucherId: Long,
    val cachedBalance: BigDecimal,
    val ledgerBalance: BigDecimal,
    val difference: BigDecimal
)
```

- [x] **Step 5: Run tests — expect PASS**

- [x] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add ledger verification batch with global + per-voucher balance check"
```

---

## 태스크 15: 통합 테스트 — 동시성 및 E2E ★

**Files:**
- Create: `src/test/kotlin/com/commerce/integration/ConcurrencyTest.kt`
- Create: `src/test/kotlin/com/commerce/integration/E2EFlowTest.kt`

- [x] **Step 1: Write concurrent redemption test**

```kotlin
@Test
fun `10 concurrent redemptions on same voucher should not over-deduct`() {
    val voucher = issueVoucher(faceValue = 50000)
    val latch = CountDownLatch(1)
    val threads = (1..10).map {
        thread {
            latch.await()
            try {
                redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"), UUID.randomUUID().toString())
            } catch (_: BusinessException) {}
        }
    }
    latch.countDown()
    threads.forEach { it.join() }

    val updated = voucherRepository.findById(voucher.id).get()
    updated.balance shouldBeGreaterThanOrEqualTo BigDecimal.ZERO  // I1: balance >= 0
    // Exactly 5 should succeed (50000 / 10000 = 5)
    val txCount = transactionRepository.countByVoucherIdAndStatus(voucher.id, TransactionStatus.COMPLETED)
    txCount shouldBe 5
    updated.balance shouldBe BigDecimal.ZERO

    // Verify ledger balance
    val verificationResult = ledgerVerificationService.verify()
    verificationResult.isBalanced shouldBe true
}
```

- [x] **Step 2: Write idempotency test**

```kotlin
@Test
fun `duplicate redemption with same idempotency key should not double-deduct`() {
    val voucher = issueVoucher(faceValue = 50000)
    val key = UUID.randomUUID().toString()
    val result1 = redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"), key)
    val result2 = redemptionService.redeem(voucher.id, merchantId, BigDecimal("10000"), key)
    result1.transactionId shouldBe result2.transactionId
    voucherRepository.findById(voucher.id).get().balance shouldBe BigDecimal("40000")
}
```

- [x] **Step 3: Write E2E flow test**

Test the full lifecycle: issue → partial redeem → partial redeem → refund request → refund completed.
Verify ledger entries trace the full lifecycle. Verify audit logs for each step.

- [x] **Step 4: Write expiry-during-redemption race test**

```kotlin
@Test
fun `expiry scheduler and redemption should not conflict`() {
    // Create voucher that expires in 1 second
    // Start expiry scheduler and redemption simultaneously
    // Only one should succeed, and invariants should hold
}
```

- [x] **Step 5: Run all integration tests**

Run: `./gradlew test --tests "com.commerce.integration.*"`
Expected: ALL PASS

- [x] **Step 6: Commit**

```bash
git add -A
git commit -m "test: add concurrency, idempotency, and E2E integration tests"
```

---

## 태스크 16: API 문서화 및 README 작성

**Files:**
- Modify: `build.gradle.kts` (add springdoc-openapi dependency)
- Create: `src/main/kotlin/com/commerce/config/SwaggerConfig.kt`
- Create: `README.md`

- [x] **Step 1: Add Swagger/OpenAPI dependency**

Add to `build.gradle.kts`:
```kotlin
implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.4.0")
```

- [x] **Step 2: Create SwaggerConfig**

```kotlin
@Configuration
class SwaggerConfig {
    @Bean
    fun openAPI(): OpenAPI = OpenAPI()
        .info(Info()
            .title("모바일 상품권 관리 시스템 API")
            .version("1.0.0")
            .description("지역사랑상품권의 발행-유통-정산 전 생애주기를 관리하는 백엔드 API"))
}
```

- [x] **Step 3: Write README.md**

Follow the structure from `docs/03-implementation-roadmap.md` README section.
Include: architecture diagram (text), technical decisions with justifications, concurrency control table, event design, execution instructions, test results.

- [x] **Step 4: Verify Swagger UI loads**

Run: `./gradlew bootRun`
Expected: Swagger UI available at `http://localhost:8080/swagger-ui.html`

- [x] **Step 5: Commit**

```bash
git add -A
git commit -m "docs: add Swagger API documentation and portfolio README"
```

---

## 부록 A: 추가 구현 사항

### A1. FailedEvent 엔티티 (태스크 2에서 함께 생성)

```kotlin
@Entity
@Table(name = "failed_events")
class FailedEvent(
    @Column(nullable = false, length = 36) val eventId: String,
    @Column(nullable = false, length = 50) val eventType: String,
    @Column(nullable = false, columnDefinition = "TEXT") val payload: String,
    @Column(nullable = false) val errorMessage: String,
    var retryCount: Int = 0,
    var resolved: Boolean = false,
    @Column(nullable = false) val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0L
}
```

FailedEvent 재처리 스케줄러를 Task 2에서 함께 구현:
```kotlin
@Component
class FailedEventRetryScheduler(
    private val failedEventRepository: FailedEventRepository,
    private val auditLogRepository: AuditLogRepository,
) {
    @Scheduled(fixedDelay = 60000)  // 1분마다
    @Transactional
    fun retryFailedEvents() {
        val events = failedEventRepository.findByResolvedFalseAndRetryCountLessThan(3)
        events.forEach { /* deserialize payload, re-attempt audit log save, increment retryCount */ }
    }
}
```

### A2. 수동 원장 조정 엔드포인트 (태스크 14 이후 추가)

**Task 14a: Manual Adjustment API**

**Files:**
- Create: `src/main/kotlin/com/commerce/ledger/interfaces/LedgerAdjustmentController.kt`
- Create: `src/main/kotlin/com/commerce/ledger/application/LedgerAdjustmentService.kt`
- Test: `src/test/kotlin/com/commerce/ledger/application/LedgerAdjustmentServiceTest.kt`

- [x] **Step 1: Write test — admin can create manual adjustment with reason**

```kotlin
@Test
fun `admin should be able to create manual adjustment with reason`() {
    val result = adjustmentService.adjust(
        voucherId = voucher.id,
        amount = BigDecimal("1000"),
        reason = "고객 민원에 따른 잔액 보정",
        adminId = adminMember.id
    )
    result.entries.size shouldBe 2  // debit + credit
    // Verify CRITICAL audit log was created
    val auditLogs = auditLogRepository.findByEventType("MANUAL_ADJUSTMENT")
    auditLogs.size shouldBe 1
}
```

- [x] **Step 2: Write test — non-admin is rejected**

```kotlin
@Test
fun `non-admin should be rejected for manual adjustment`() {
    shouldThrow<BusinessException> {
        adjustmentService.adjust(voucher.id, BigDecimal("1000"), "reason", normalUser.id)
    }.errorCode shouldBe ErrorCode.MANUAL_ADJUSTMENT_REQUIRES_ADMIN
}
```

- [x] **Step 3: Write test — reason is required**

- [x] **Step 4: Implement LedgerAdjustmentService**

Validates admin role, requires non-empty reason, creates Transaction + LedgerEntry pair (MANUAL_ADJUSTMENT), publishes MANUAL_ADJUSTMENT event for CRITICAL audit.

- [x] **Step 5: Run tests — expect PASS**

- [x] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add manual ledger adjustment with admin authorization and audit trail"
```

### A3. Clock 주입 패턴 (테스트 가능성)

모든 시간 의존 로직에서 `LocalDateTime.now()` 대신 `Clock`을 주입합니다:

```kotlin
// config/ClockConfig.kt
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}

// Voucher entity에서:
fun isExpired(clock: Clock = Clock.system(ZoneId.of("Asia/Seoul"))): Boolean =
    expiresAt.isBefore(LocalDateTime.now(clock))

// 테스트에서:
val fixedClock = Clock.fixed(Instant.parse("2026-03-30T00:00:00Z"), ZoneId.of("Asia/Seoul"))
voucher.isExpired(fixedClock) shouldBe true
```

모든 서비스에서 `Clock`을 생성자 주입받아 사용합니다. 이렇게 하면 청약철회 7일 기한, 만료 처리 등의 시간 의존 로직을 결정적으로 테스트할 수 있습니다.

### A4. Flyway 마이그레이션 (태스크 1에서 설정)

`build.gradle.kts`에 추가:
```kotlin
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-mysql")
```

`application.yml`에 추가:
```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

초기 마이그레이션 파일: `src/main/resources/db/migration/V1__init_schema.sql`
- 모든 테이블 CREATE 문을 포함 (regions, members, merchants, vouchers, transactions, ledger_entries, audit_logs, idempotency_keys, settlements, failed_events)
- 프로덕션에서는 `hibernate.ddl-auto: validate`와 함께 사용
- 테스트에서는 `spring.flyway.enabled: false` + `hibernate.ddl-auto: create-drop`

---

## 핵심 구현 포인트 요약

| 관심사 | 검증 위치 |
|--------|----------|
| 원장 기록은 동기 호출 (이벤트 X) | 태스크 9 — VoucherRedemptionService에서 LedgerService.record() 직접 호출 |
| 복식부기 항상 균형 (2행 모델) | 태스크 6 — LedgerService.record()가 차변 + 대변 행 생성 |
| 삭제 없음, 보상 트랜잭션만 사용 | 태스크 11 — TransactionCancelService가 역방향 엔트리 생성 |
| 분산락 + 비관적 락 이중 방어 | 태스크 9 — VoucherLockManager (Redisson) + @Lock (JPA) |
| 멱등키 이중 저장 | 태스크 7 — Redis TTL + DB 폴백 |
| CRITICAL 감사 로그는 BEFORE_COMMIT | 태스크 2 — AuditEventListener |
| 비핵심 감사 로그는 AFTER_COMMIT + REQUIRES_NEW | 태스크 2 — AuditEventListener + FailedEvent 폴백 |
| 원장 정합성 검증으로 불일치 탐지 | 태스크 14 — LedgerVerificationService |
| 수동 원장 조정은 관리자 승인 + 사유 필수 | 태스크 14a — LedgerAdjustmentService |
| 동시성 안전은 테스트로 증명 | 태스크 15 — ConcurrencyTest (CountDownLatch) |
| 만료 배치: 건별 독립 트랜잭션 | 태스크 12 — VoucherExpiryProcessor (REQUIRES_NEW) |
| 정산 이의제기 플로우 | 태스크 13 — Settlement.dispute() / confirm() |
| Clock 주입으로 테스트 가능성 확보 | 부록 A3 — 모든 시간 의존 로직에 Clock 주입 |
| 스키마 마이그레이션 | 부록 A4 — Flyway V1__init_schema.sql |
