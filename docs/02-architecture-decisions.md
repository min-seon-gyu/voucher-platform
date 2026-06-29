# 2단계 — 아키텍처 및 설계 결정

## 1. 패키지 구조 (모듈러 모놀리스)

```
com/commerce/
├── common/                          ← 공통 모듈
│   ├── domain/
│   │   ├── BaseEntity.kt              (id, createdAt, updatedAt, version)
│   │   └── DomainEvent.kt             (이벤트 베이스 클래스)
│   ├── exception/
│   │   ├── BusinessException.kt
│   │   ├── ErrorCode.kt
│   │   └── GlobalExceptionHandler.kt  (@RestControllerAdvice)
│   ├── idempotency/
│   │   ├── IdempotencyKey.kt           (엔티티)
│   │   ├── IdempotencyRepository.kt
│   │   ├── IdempotencyInterceptor.kt   (AOP)
│   │   └── Idempotent.kt              (커스텀 어노테이션)
│   └── audit/
│       ├── AuditLog.kt                 (엔티티)
│       ├── AuditSeverity.kt            (CRITICAL, HIGH, MEDIUM)
│       ├── AuditLogRepository.kt
│       ├── AuditEventListener.kt
│       ├── FailedEvent.kt              (실패 이벤트 엔티티, 재처리 대상)
│       └── FailedEventRepository.kt
│
├── region/                          ← 지자체 모듈
│   ├── domain/
│   │   ├── Region.kt                   (Aggregate Root)
│   │   ├── RegionPolicy.kt             (Value Object: 할인율, 한도, 정산주기 등)
│   │   └── RegionStatus.kt             (enum)
│   ├── application/
│   │   └── RegionService.kt
│   ├── infrastructure/
│   │   ├── RegionJpaRepository.kt
│   │   └── RegionQueryRepository.kt    (QueryDSL)
│   └── interfaces/
│       ├── RegionController.kt
│       └── dto/
│           ├── RegionRequest.kt
│           └── RegionResponse.kt
│
├── member/                          ← 회원 모듈
│   ├── domain/
│   │   ├── Member.kt                   (Aggregate Root)
│   │   ├── MemberStatus.kt             (PENDING, ACTIVE, SUSPENDED, WITHDRAWN)
│   │   └── MemberRole.kt              (USER, MERCHANT_OWNER, ADMIN)
│   ├── application/
│   │   └── MemberService.kt
│   ├── infrastructure/
│   │   └── MemberJpaRepository.kt
│   └── interfaces/
│       ├── MemberController.kt
│       └── dto/
│           ├── MemberRequest.kt        (RegisterMemberRequest, LoginRequest)
│           └── MemberResponse.kt
│
├── merchant/                        ← 가맹점 모듈
│   ├── domain/
│   │   ├── Merchant.kt                 (Aggregate Root)
│   │   ├── MerchantStatus.kt           (PENDING_APPROVAL, APPROVED, REJECTED, SUSPENDED, TERMINATED)
│   │   ├── MerchantCategory.kt         (업종)
│   │   ├── Settlement.kt               (정산 엔티티)
│   │   └── event/
│   │       ├── MerchantApprovedEvent.kt
│   │       └── SettlementConfirmedEvent.kt
│   ├── application/
│   │   ├── MerchantService.kt
│   │   └── SettlementService.kt
│   ├── infrastructure/
│   │   ├── MerchantJpaRepository.kt
│   │   └── SettlementJpaRepository.kt
│   └── interfaces/
│       ├── MerchantController.kt
│       └── SettlementController.kt
│
├── voucher/                         ← 상품권 모듈 (핵심)
│   ├── domain/
│   │   ├── Voucher.kt                  (Aggregate Root)
│   │   ├── VoucherStatus.kt
│   │   ├── VoucherCodeGenerator.kt     (SecureRandom + Luhn mod 36)
│   │   └── event/
│   │       ├── VoucherIssuedEvent.kt
│   │       ├── VoucherRedeemedEvent.kt
│   │       ├── VoucherRefundedEvent.kt
│   │       ├── VoucherWithdrawnEvent.kt  (청약철회)
│   │       └── VoucherExpiredEvent.kt
│   ├── application/
│   │   ├── VoucherIssueService.kt      (발행)
│   │   ├── VoucherRedemptionService.kt (사용/결제)
│   │   ├── VoucherRefundService.kt     (잔액 환불)
│   │   ├── VoucherWithdrawalService.kt (청약철회)
│   │   ├── VoucherExpiryScheduler.kt   (만료 배치)
│   │   └── RegionCounterSyncScheduler.kt (Redis 카운터 동기화 배치, 매시간)
│   ├── infrastructure/
│   │   ├── VoucherJpaRepository.kt
│   │   ├── VoucherQueryRepository.kt   (QueryDSL)
│   │   └── VoucherLockManager.kt       (Redisson 분산락 관리)
│   └── interfaces/
│       ├── VoucherController.kt
│       └── dto/
│           ├── VoucherRequest.kt
│           └── VoucherResponse.kt
│
├── transaction/                     ← 거래 모듈
│   ├── domain/
│   │   ├── Transaction.kt              (Aggregate Root)
│   │   ├── TransactionStatus.kt        (TransactionStatus + TransactionType 동일 파일)
│   │   └── event/
│   │       └── TransactionCancelledEvent.kt
│   ├── application/
│   │   ├── TransactionService.kt
│   │   └── TransactionCancelService.kt (보상 트랜잭션 기반 취소)
│   ├── infrastructure/
│   │   └── TransactionJpaRepository.kt
│   └── interfaces/
│       └── TransactionController.kt    (DTO 인라인 정의)
│
├── ledger/                          ← 원장 모듈
│   ├── domain/
│   │   ├── LedgerEntry.kt              (Immutable Entity)
│   │   ├── LedgerEntryType.kt          (LedgerEntryType + LedgerEntrySide 동일 파일)
│   │   └── AccountCode.kt             (enum: MEMBER_CASH, VOUCHER_BALANCE, MERCHANT_RECEIVABLE 등)
│   ├── application/
│   │   ├── LedgerService.kt            (원장 기록 — 동기 호출, 이벤트 X)
│   │   └── LedgerVerificationService.kt(정합성 검증 배치)
│   ├── infrastructure/
│   │   └── LedgerJpaRepository.kt
│   └── interfaces/
│       └── LedgerQueryController.kt    (관리자 조회)
│
└── config/                          ← 인프라 설정
    ├── JwtTokenProvider.kt             (JWT 토큰 생성/검증)
    ├── RedisConfig.kt
    ├── SecurityConfig.kt
    ├── QueryDslConfig.kt
    └── SwaggerConfig.kt               (OpenAPI/Swagger 문서화)
```

**모듈 간 핵심 의존관계:**
- `voucher` → `ledger` (동기 호출: 잔액 변경 시 원장 기록)
- `voucher` → `transaction` (동기 호출: 거래 생성)
- `merchant` → `transaction` (조회: 정산 대상 거래 조회)
- 나머지 모듈 간 통신: Domain Event (비동기, Kafka-replaceable)

---

## 2. 동시성 제어 전략

| Operation | Strategy | Reason | 방지하는 장애 시나리오 |
|-----------|----------|--------|----------------------|
| **상품권 사용 (결제)** | Redisson 분산락 (`voucher:{id}`) + DB 비관적 락 + `TransactionTemplate` | 분산락 → 트랜잭션(커밋) → 락 해제 순서 보장. Redis 장애 시 DB 락이 2차 방어 | 이중 사용, 잔액 초과 차감, 락-커밋 순서 역전 |
| **상품권 발행** | Redisson 분산락 (`member:purchase:{memberId}`) + Redis Lua 스크립트 (`region:monthly:{regionId}:{yyyyMM}`) + `TransactionTemplate` | Member 락으로 1인 한도 직렬화. Region 한도는 Lua 스크립트로 INCRBY + 한도 검증을 원자적 수행 (락 불필요) | 한도 초과 발행/구매. 데드락 위험 제거 |
| **잔액 환불** | Redisson 분산락 (`voucher:{id}`) + `TransactionTemplate` | 분산락 → 트랜잭션(커밋) → 락 해제 순서 보장 | 사용 중 환불 처리 |
| **청약철회** | Redisson 분산락 (`voucher:{id}`) + `TransactionTemplate` | 분산락 → 트랜잭션(커밋) → 락 해제 순서 보장 | 사용 중 철회 처리 |
| **만료 처리 (배치)** | DB 비관적 락 (`SELECT FOR UPDATE`) | 배치와 실시간 결제 경합 방지. 건별 처리이므로 분산락 불필요 | 만료 중 결제 경합 |
| **가맹점 등록/수정** | JPA Optimistic Lock (`@Version`) | 충돌 빈도 낮음. 동시 수정 시 재시도로 충분 | 동시 상태 변경 |
| **정산 생성** | DB Unique Constraint (`merchant_id + period`) | 동일 기간 중복 정산 방지 | 중복 정산 |
| **회원 정보 수정** | JPA Optimistic Lock (`@Version`) | 충돌 빈도 낮음 | 동시 프로필 수정 |

**Region 월 발행한도 검증 — Redis Lua 스크립트 패턴:**

- 키: `region:monthly:{regionId}:{yyyyMM}` (TTL: 해당 월 말일 + 1일)
- 발행 시: Lua 스크립트로 `INCRBY` + 한도 비교 + 초과 시 `DECRBY` 롤백을 **단일 원자적 연산**으로 수행
  ```lua
  local current = redis.call('INCRBY', KEYS[1], ARGV[1])
  if current > tonumber(ARGV[2]) then
      redis.call('DECRBY', KEYS[1], ARGV[1])
      return -1
  end
  return current
  ```
- 장점: Lua 스크립트는 Redis에서 원자적으로 실행되므로 INCRBY~DECRBY 사이에 다른 요청이 끼어들 수 없음. 분산락 1개(Member)만 사용하므로 데드락 불가
- 초기화: 월초에 해당 Region의 실제 발행액으로 Redis 카운터 동기화 (배치)

**트랜잭션-락 순서 보장 — TransactionTemplate 패턴:**

모든 분산락 사용 서비스에서 `@Transactional` 대신 `TransactionTemplate`을 사용하여 분산락 → 트랜잭션(커밋) → 분산락 해제 순서를 보장한다. `@Transactional`을 사용하면 트랜잭션 시작 → 분산락 획득 → 비즈니스 로직 → 분산락 해제 → 트랜잭션 커밋 순서가 되어, 락 해제~커밋 사이에 다른 스레드가 커밋 전 데이터를 읽는 문제가 발생할 수 있다.

```kotlin
// 올바른 패턴: 락이 트랜잭션을 감싸므로 커밋 후 락 해제
fun redeem(...) = lockManager.withVoucherLock(voucherId) {
    transactionTemplate.execute { _ ->
        // 비즈니스 로직 (여기서 커밋)
    }!!
}  // 여기서 락 해제
```

---

## 3. 멱등성 설계

### 멱등키가 필요한 엔드포인트

| Endpoint | 이유 |
|----------|------|
| `POST /api/v1/vouchers/purchase` | 결제 연동. 재시도 시 중복 구매 방지 |
| `POST /api/v1/vouchers/{id}/redeem` | 결제 처리. 재시도 시 이중 차감 방지 |
| `POST /api/v1/vouchers/{id}/refund` | 환불 처리. 재시도 시 이중 환불 방지 |
| `POST /api/v1/vouchers/{id}/withdraw` | 청약철회. 재시도 시 이중 처리 방지 |
| `POST /api/v1/transactions/{id}/cancel` | 거래 취소. 재시도 시 이중 취소 방지 |

### 저장 전략: Redis TTL + DB 이중 저장

- **1차 (Redis):** `idempotency:{key}` → TTL 24시간. 빠른 중복 감지
- **2차 (DB):** `idempotency_keys` 테이블. Redis 장애 시 fallback + 감사 추적

**선택 이유:** Redis만 사용하면 TTL 만료 후 또는 Redis 장애 시 중복 실행 가능. DB만 사용하면 매 요청마다 DB 조회로 성능 저하. 이중 저장이 금융 시스템에 적합.

### 중복 감지 시 응답

- 동일한 200 응답 본문 반환 (원래 처리 결과를 캐시에 저장해두고 반환)
- 409가 아닌 이유: 클라이언트 입장에서 재시도는 "이전 요청이 성공했는지 모르는 상태"이므로, 성공 응답을 받아야 정상 흐름을 이어갈 수 있음

---

## 4. 도메인 이벤트 설계

### 이벤트 목록

| Event | 트리거 시점 | Payload | Listener 책임 |
|-------|-----------|---------|---------------|
| `VoucherIssuedEvent` | 상품권 발행 완료 | voucherId, memberId, regionId, faceValue | 감사 로그 기록, 알림 발송 |
| `VoucherRedeemedEvent` | 결제(사용) 완료 | voucherId, merchantId, amount, remainingBalance, transactionId | 감사 로그, 가맹점 정산 대상 추가, 알림 |
| `VoucherRefundedEvent` | 잔액 환불 완료 | voucherId, memberId, refundAmount, transactionId | 감사 로그, 알림 |
| `VoucherWithdrawnEvent` | 청약철회 완료 | voucherId, memberId, refundAmount, transactionId | 감사 로그, 알림 |
| `VoucherExpiredEvent` | 만료 처리 완료 | voucherId, remainingBalance | 감사 로그 |
| `TransactionCancelledEvent` | 거래 취소 완료 | transactionId, voucherId, cancelAmount | 감사 로그, 정산 차감 |
| `MerchantApprovedEvent` | 가맹점 승인 완료 | merchantId, regionId | 감사 로그 |
| `SettlementConfirmedEvent` | 정산 확정 | settlementId, merchantId, totalAmount, period | 감사 로그, (향후) 지급 연동 |

**핵심 원칙: 원장 기록은 이벤트 리스너가 아닌 서비스 내 동기 호출**
- 잔액 변경 + 원장 기록 + Transaction 생성 = 동일 DB 트랜잭션 (I2, I3 보장)
- 이벤트 리스너 책임: 감사 로그, 알림, 정산 큐 추가 등 비핵심 부수효과

### 리스너 실패 처리 전략

- CRITICAL 감사 로그: `@TransactionalEventListener(phase = BEFORE_COMMIT)` — 동일 트랜잭션. 실패 시 전체 롤백
- HIGH/MEDIUM 감사 로그, 알림, 정산 큐: `@TransactionalEventListener(phase = AFTER_COMMIT)` — 실패 시 `failed_events` 테이블에 기록 → 스케줄러가 재처리

### Kafka 전환 가능 포인트

- 모든 이벤트가 `ApplicationEventPublisher.publishEvent()`를 통해 발행
- 이벤트 클래스는 `domain/event/` 패키지에 순수 데이터 클래스로 정의
- Kafka 전환 시: `@TransactionalEventListener` → `KafkaTemplate.send()`, 리스너 → `@KafkaListener`로 교체. 도메인 코드 변경 없음
- Transactional Outbox 패턴으로 전환하려면 이벤트 발행 시점에 `outbox` 테이블에 INSERT → CDC/Polling으로 Kafka에 발행

---

## 5. 감사 로그 설계

### 감사 대상 작업 (커머스/금융 컴플라이언스 관점)

| 감사 등급 | 대상 작업 |
|----------|----------|
| **CRITICAL** | 상품권 발행, 결제(사용), 환불, 청약철회, 거래 취소, 정산 확정/지급, 수동 원장 조정 |
| **HIGH** | 가맹점 승인/거절/해지, 회원 정지/탈퇴, 지자체 정책 변경 |
| **MEDIUM** | 가맹점 정보 수정, 관리자 로그인, 상품권 만료 처리 |

### 감사 로그 스키마

```sql
CREATE TABLE audit_logs (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id            VARCHAR(36) UNIQUE NOT NULL,   -- UUID, 이벤트 식별자
    event_type          VARCHAR(50) NOT NULL,          -- VOUCHER_ISSUED, VOUCHER_REDEEMED, ...
    severity            VARCHAR(10) NOT NULL,          -- CRITICAL, HIGH, MEDIUM
    aggregate_type      VARCHAR(30) NOT NULL,          -- VOUCHER, MERCHANT, MEMBER, ...
    aggregate_id        BIGINT NOT NULL,               -- 대상 엔티티 ID
    actor_id            BIGINT,                        -- 수행자 ID (시스템 배치는 NULL)
    actor_type          VARCHAR(20),                   -- USER, MERCHANT, ADMIN, SYSTEM
    action              VARCHAR(50) NOT NULL,          -- CREATE, UPDATE, STATE_CHANGE, ...
    previous_state      JSON,                          -- 변경 전 상태
    current_state       JSON,                          -- 변경 후 상태
    metadata            JSON,                          -- 추가 컨텍스트 (IP, User-Agent 등)
    idempotency_key     VARCHAR(64),                   -- 관련 멱등키
    created_at          DATETIME(6) NOT NULL,          -- 마이크로초 정밀도

    INDEX idx_audit_aggregate (aggregate_type, aggregate_id, created_at),
    INDEX idx_audit_event_type (event_type, created_at),
    INDEX idx_audit_actor (actor_id, created_at)
);
```

### 구현 방식: Spring 이벤트 리스너 (도메인 이벤트 기반)

**선택 이유:**
- Spring AOP: "어떤 메서드가 호출되었는가"에 의존 → 비즈니스 의미가 약함
- JPA EntityListener: 상태 전이의 비즈니스 컨텍스트(누가, 왜)를 캡처하기 어려움
- **Event Listener: 도메인 이벤트에 비즈니스 의미가 담겨 있으므로 감사 로그의 맥락이 풍부함**

**등급별 처리:**
- CRITICAL 등급: `BEFORE_COMMIT`에서 기록 (감사 실패 시 트랜잭션 롤백)
- HIGH/MEDIUM 등급: `AFTER_COMMIT`에서 비동기 기록 (실패 시 재시도 큐)

---

## 6. MySQL 관련 고려사항

### 인덱스 전략

| 테이블 | 인덱스 | 용도 |
|--------|--------|------|
| `vouchers` | `idx_voucher_member (member_id, status)` | 내 상품권 목록 조회 |
| `vouchers` | `idx_voucher_region_status (region_id, status, expires_at)` | 지자체별 상품권 현황, 만료 대상 조회 |
| `vouchers` | `idx_voucher_expiry (status, expires_at)` | 만료 배치 대상 스캔 |
| `vouchers` | `UNIQUE idx_voucher_code (voucher_code)` | 상품권 코드 유일성 |
| `transactions` | `idx_tx_voucher (voucher_id, created_at)` | 상품권별 거래 이력 |
| `transactions` | `idx_tx_merchant_period (merchant_id, status, created_at)` | 가맹점 정산 대상 조회 |
| `ledger_entries` | `idx_ledger_tx (transaction_id)` | 거래별 원장 조회 |
| `ledger_entries` | `idx_ledger_account (debit_account, created_at)` | 계정별 원장 조회 |
| `settlements` | `UNIQUE idx_settlement_period (merchant_id, period_start, period_end)` | 중복 정산 방지 |
| `idempotency_keys` | `UNIQUE idx_idem_key (idempotency_key)` | 멱등키 중복 검사 |

### 트랜잭션 격리 수준

| Operation | Isolation Level | 이유 |
|-----------|----------------|------|
| 상품권 결제 (잔액 차감) | `READ_COMMITTED` + 비관적 락 | `SELECT FOR UPDATE`로 row-level 락 획득. REPEATABLE_READ는 gap lock으로 불필요한 경합 유발 |
| 원장 기록 | `READ_COMMITTED` | INSERT only, 충돌 없음 |
| 정합성 검증 배치 | `REPEATABLE_READ` (MySQL 기본) | 검증 중 일관된 스냅샷 필요 |
| 정산 계산 | `REPEATABLE_READ` | 합산 중 데이터 변경 방지 |
| 일반 조회 | `READ_COMMITTED` | 최신 커밋 데이터 반영 |

### MySQL 8.x 활용 기능


- **JSON Column**: `audit_logs.previous_state`, `current_state`, `metadata` — 스키마 유연성. `JSON_EXTRACT`로 조건부 조회 가능
- **Generated Column**: `voucher_usage_ratio AS ((face_value - balance) / face_value) STORED` — 환불 조건(60%) 검증에 활용. 인덱스 생성 가능
- **Window Function**: 정산 계산 시 `SUM() OVER (PARTITION BY merchant_id)` 활용

---

## 7. 시니어 레벨 설계 결정: 보상 트랜잭션 체계

**대부분의 주니어 개발자가 놓치는 것:**
"취소"를 단순 DELETE나 상태 변경으로 구현하는 것.

**이 프로젝트의 접근:**
모든 취소/환불은 **보상 트랜잭션(Compensating Transaction)**으로 처리한다.

- 원 거래를 삭제하거나 수정하지 않음
- 새로운 역방향 Transaction + LedgerEntry 쌍을 생성
- 원 거래와 보상 거래가 `original_transaction_id`로 연결됨

**왜 이것이 커머스 시스템에 중요한가:**

1. **감사 추적성**: 모든 금전 흐름이 삭제 없이 보존. "왜 이 금액이 변경되었는가"를 원장만으로 완벽 추적
2. **법적 증거력**: 원 거래 기록이 변경 불가(immutable)이므로 분쟁 시 증거로 사용
3. **정산 정합성**: "이 기간 총 사용액 - 이 기간 총 취소액"으로 정확한 정산
4. **이상 거래 탐지**: 원 거래 대비 취소 비율로 가맹점 환불 사기 탐지

**소프트 삭제와의 차이:**
소프트 삭제는 "이 레코드는 무효"라고 표시할 뿐, "돈이 어디로 갔는지"를 원장 수준에서 증명하지 못한다.

---

## 8. 운영 모니터링 (Observability)

포트폴리오 버전에서는 기반만 구축하되, 프로덕션 확장 가능한 구조를 보여준다.

### 메트릭 (Spring Actuator + Micrometer)

| 메트릭 | 설명 |
|--------|------|
| `voucher.redemption.duration` | 결제 처리 지연시간 (Timer) |
| `voucher.redemption.count` | 결제 처리 건수 (Counter, 성공/실패 태그) |
| `lock.acquisition.duration` | 분산락 획득 대기시간 (Timer) |
| `lock.acquisition.timeout` | 분산락 타임아웃 건수 (Counter) |
| `ledger.verification.imbalance` | 정합성 검증 불일치 건수 (Gauge) |
| `idempotency.duplicate.count` | 멱등키 중복 감지 건수 (Counter) |

### 헬스체크

- Spring Actuator `/health` 엔드포인트
- MySQL connectivity, Redis connectivity 자동 포함
- 커스텀: LedgerVerification 마지막 실행 시간 + 결과

### 알림 채널

- 포트폴리오: 로그 출력 (SLF4J WARN/ERROR 레벨)
- 프로덕션 확장 시: Slack/PagerDuty 연동 (ApplicationEventPublisher → 알림 리스너)

---

**2단계 핵심 결정:** 6개 도메인 모듈(region, member, merchant, voucher, transaction, ledger) + 공통 모듈 + config. 원장은 동기 호출(이벤트 X), 발행 시 Member 락 + Region Redis Lua 스크립트(원자적 한도 검증, 데드락 제거), 분산락 사용 서비스는 TransactionTemplate으로 락-커밋 순서 보장, 이벤트는 감사/알림/정산 큐에만 사용, 운영 메트릭 기반 구축. RegionCounterSyncScheduler로 매시간 Redis 카운터 동기화. BigDecimal 비교는 `compareTo`로 통일 (scale 차이에 의한 비교 오류 방지).
