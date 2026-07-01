# 2단계 — 아키텍처 및 설계 결정

> ⚠️ **커머스 전환(pivot) 진행 중** — 이 문서는 지역상품권 시절 기준이 상당수 남아 있다.
> 최신 도메인/아키텍처(`seller · product · inventory · cart · order` 중심) 및 주문 결제/취소·정산 재배선은 [`../README.md`](../README.md)를 우선 참조하라. 상세 재작성은 pivot 완료(Phase 4c) 후 진행한다.

> 모듈러 모놀리스 + 헥사고날 레이어링(`domain` / `application` / `infrastructure` / `interfaces`).
> 커머스 플랫폼으로 병합된 현재 코드베이스 기준(상품권 + 프로모션/쿠폰 + 포인트). 패키지 루트는 `com.commerce`.
> 도메인 규칙은 [`01-domain-design.md`](01-domain-design.md), 금융/회계 설계는 [`03-financial-design.md`](03-financial-design.md) 참조.

## 1. 패키지 구조 (10개 모듈)

```
com/commerce/
├── common/                          ← 공통 모듈
│   ├── api/ApiResponse.kt              (success/data/error 표준 응답 + ErrorDetail)
│   ├── domain/                         (BaseEntity: id·createdAt·updatedAt·version, DomainEvent)
│   ├── exception/                      (BusinessException, ErrorCode, GlobalExceptionHandler)
│   ├── security/SecurityUtils.kt       (currentMemberId — 인증 principal에서 신원 도출)
│   ├── idempotency/
│   │   ├── IdempotencyKey.kt            (엔티티: status, responseBody, responseStatus)
│   │   ├── IdempotencyStore.kt          (Redis 캐시 + DB 뮤텍스, reserve/complete/release)
│   │   ├── IdempotencyInterceptor.kt    (HandlerInterceptor + ResponseBodyAdvice)
│   │   └── Idempotent.kt               (커스텀 어노테이션)
│   └── audit/                          (AuditLog, AuditSeverity, AuditEventListener,
│                                        FailedEvent + FailedEventRepository — 재처리 대상)
│
├── config/                          ← 인프라 설정
│   ├── SecurityConfig.kt               (필터 체인: RequestTrace → JWT 순, STATELESS)
│   ├── JwtTokenProvider.kt             (HS256 토큰 발급/검증, prod 프로파일 dev-secret fail-fast)
│   ├── JwtAuthenticationFilter.kt      (Bearer → principal=memberId(Long), 권한 ROLE_<role>)
│   ├── RequestTraceFilter.kt           (X-Request-Id → MDC requestId, HIGHEST_PRECEDENCE)
│   └── RedisConfig.kt / QueryDslConfig.kt / SwaggerConfig.kt
│
├── region/                          ← 지자체 모듈
│   ├── domain/                         (Region Aggregate, RegionPolicy VO: 할인율·한도·정산주기, RegionStatus)
│   ├── application/RegionService.kt
│   ├── infrastructure/                 (RegionJpaRepository, RegionQueryRepository — QueryDSL)
│   └── interfaces/                     (RegionController, dto/*)
│
├── member/                          ← 회원 모듈
│   ├── domain/                         (Member Aggregate, MemberStatus, MemberRole: USER/MERCHANT_OWNER/ADMIN)
│   ├── application/MemberService.kt
│   ├── infrastructure/MemberJpaRepository.kt
│   └── interfaces/                     (MemberController, AuthController — 로그인/토큰, dto/*)
│
├── merchant/                        ← 가맹점 모듈
│   ├── domain/                         (Merchant Aggregate, MerchantStatus, MerchantCategory, Settlement,
│   │                                    event/ MerchantApprovedEvent·SettlementConfirmedEvent)
│   ├── application/                    (MerchantService, SettlementService)
│   ├── infrastructure/                 (MerchantJpaRepository, SettlementJpaRepository)
│   └── interfaces/                     (MerchantController, SettlementController)
│
├── voucher/                         ← 상품권 모듈 (핵심)
│   ├── domain/                         (Voucher Aggregate, VoucherStatus, VoucherCodeGenerator, event/*)
│   ├── application/                    (Issue/Redemption/Refund/Withdrawal Service, ExpiryScheduler,
│   │                                    RegionCounterSyncScheduler — Redis 카운터 매시 동기화)
│   ├── infrastructure/                 (JpaRepository, QueryRepository, VoucherLockManager — Redisson 분산락)
│   └── interfaces/                     (VoucherController, dto/*)
│
├── promotion/                       ← 프로모션/쿠폰 모듈 (신규)
│   ├── domain/                         (Promotion, PromotionStatus, Coupon, CouponStatus,
│   │                                    CouponRedemption, DiscountType: FIXED/PERCENTAGE)
│   ├── application/
│   │   ├── RedemptionOrchestrator.kt    (쿠폰+바우처 결합 결제 오케스트레이션)
│   │   ├── PromotionService / CouponIssueService
│   │   └── PromotionBudgetSyncScheduler (예산 카운터 매시 DB 동기화)
│   ├── infrastructure/
│   │   ├── PromotionBudgetManager.kt    (Redis 원자 예산 카운터, Lua reserve/release)
│   │   └── Promotion/Coupon/CouponRedemption JpaRepository
│   └── interfaces/                     (PromotionController, MemberCouponController)
│
├── point/                           ← 포인트 모듈 (신규)
│   ├── domain/                         (PointAccount, PointTransaction, PointTransactionType: EARN/CANCEL)
│   ├── application/                    (PointEarnService — earn/reverseEarn 동기, PointQueryService)
│   ├── infrastructure/                 (PointAccountJpaRepository: INSERT IGNORE + SELECT FOR UPDATE,
│   │                                    PointTransactionJpaRepository)
│   └── interfaces/                     (PointController, dto/PointResponse)
│
├── transaction/                     ← 거래 모듈
│   ├── domain/                         (Transaction Aggregate, TransactionStatus/Type, event/TransactionCancelledEvent)
│   ├── application/                    (TransactionService, TransactionCancelService — 보상 트랜잭션 기반 취소)
│   ├── infrastructure/TransactionJpaRepository.kt
│   └── interfaces/TransactionController.kt
│
└── ledger/                          ← 원장 모듈
    ├── domain/                         (LedgerEntry 불변, LedgerEntryType/Side, AccountCode)
    ├── application/                    (LedgerService — 동기 호출(이벤트 X), LedgerVerificationService — 정합성 검증 배치)
    ├── infrastructure/LedgerJpaRepository.kt
    └── interfaces/LedgerQueryController.kt  (관리자 조회)
```

**모듈 간 핵심 의존관계:**
- `voucher` / `promotion` / `point` → `ledger` (동기 호출: 잔액 변경 시 원장 기록)
- `promotion.RedemptionOrchestrator` → `voucher` + `point` + `transaction` + `ledger` (결합 결제 오케스트레이션)
- `transaction.TransactionCancelService` → `voucher` + `promotion` + `point` (취소 시 보상 역분개)
- `merchant` → `transaction` (조회: 정산 대상 거래)
- 나머지 모듈 간 통신: Domain Event (비동기, Kafka-replaceable)

---

## 2. 동시성 제어 전략

| Operation | Strategy | 방지하는 장애 |
|-----------|----------|--------------|
| **상품권 사용(결제)** | Redisson 분산락 (`voucher:{id}`) + DB 비관적 락(`SELECT FOR UPDATE`) + `TransactionTemplate` | 이중 사용, 잔액 초과 차감, 락-커밋 순서 역전 |
| **쿠폰+바우처 결합 결제** | 정준 락 순서 `coupon:{id}` → `voucher:{id}` + Redis 예산 예약 + `TransactionTemplate` | 데드락, 쿠폰 이중 사용, 예산 초과 보조 |
| **상품권 발행** | 분산락 (`member:purchase:{memberId}`) + Redis Lua (`region:monthly:{regionId}:{yyyyMM}`) + `TransactionTemplate` | 1인 한도·지역 한도 초과, 데드락 |
| **포인트 적립** | `INSERT IGNORE`(계좌 선보장) → `SELECT FOR UPDATE` | 갭 락 데드락, 동시 적립 경합 |
| **잔액 환불 / 청약철회** | 분산락 (`voucher:{id}`) + `TransactionTemplate` | 사용 중 환불/철회 처리 |
| **만료 처리(배치)** | DB 비관적 락 (`SELECT FOR UPDATE`) | 만료 중 결제 경합 |
| **가맹점·회원 수정** | JPA Optimistic Lock (`@Version`) | 동시 상태/프로필 변경 |
| **정산 생성** | DB Unique Constraint (`merchant_id + period`) | 중복 정산 |

**분산락 fallback:** `VoucherLockManager`는 Redisson `tryLock`(대기 5s / 보유 10s) 실패가 *Redis 장애*면 경고 로그 + `lock.redis.fallback` 카운터를 남기고 **DB 비관적 락만으로 강등** 동작한다(가용성 우선). 락 타임아웃은 `LOCK_ACQUISITION_FAILED`로 전파. 키 접두사(`voucher`/`coupon`/`member`)는 `lock.acquisition.duration` 메트릭 태그로 분리된다.

**(a) 프로모션 예산 — Redis Lua 원자 카운터 (`PromotionBudgetManager`):**

지역 월 발행한도 패턴(아래 (d))을 그대로 미러링한다.

- 키: `promotion:budget:{promotionId}` (소비된 할인액 누적, 원 단위 정수)
- 예약(`reserve`): Lua로 `INCRBY` + 한도 비교 + 초과 시 `DECRBY` 롤백을 **단일 원자 연산**으로 수행. 초과면 `-1` 반환 → `PROMOTION_BUDGET_EXCEEDED`
- 반환(`release`): DB 트랜잭션 실패(롤백) 또는 결제 취소 시 `DECRBY`로 예약 보전(예산 누수 방지). `RedemptionOrchestrator`는 예산 예약을 DB 트랜잭션 **밖**에서 수행하고 `finally`에서 미커밋 시 `release`
- 진실원천은 `CouponRedemption.discountAmount` 합계 → `PromotionBudgetSyncScheduler`가 **매시 정각** DB 합계로 Redis 카운터를 보정(재시작/보상 누락 복구)

```lua
local current = redis.call('INCRBY', KEYS[1], ARGV[1])
if current > tonumber(ARGV[2]) then
    redis.call('DECRBY', KEYS[1], ARGV[1])
    return -1
end
return current
```

**(b) 결합 결제 데드락 방지 — 정준 락 순서:**

쿠폰과 바우처를 동시에 잠그는 모든 경로(`RedemptionOrchestrator`, `TransactionCancelService`)는 항상 `coupon:{id}`(외측) → `voucher:{id}`(내측) → DB 트랜잭션 순서로 잠근다. **동일한 키 정렬 규칙**을 따르므로 순환 대기(데드락)가 발생하지 않는다. 락 내부 진입 후 쿠폰 상태(ISSUED)를 재조회·재확인하여 이중 사용을 차단한다.

```kotlin
lockManager.withCouponLock(couponId) {        // 1st: 쿠폰 락(외측)
    lockManager.withVoucherLock(voucherId) {  // 2nd: 바우처 락(내측)
        transactionTemplate.execute { ... }   // DB 트랜잭션(커밋)
    }
}
```

`RedemptionOrchestrator.redeem` 흐름:
1. 쿠폰 없으면 일반 바우처 결제(`VoucherRedemptionService`)로 위임
2. `coupon → voucher` 락 획득 후 사전 검증(쿠폰 상태/만료, 프로모션 활성, `minSpend`, 1인 한도) — 예산 예약 전 빠른 거부
3. 할인 산정 `D = min(promotion.calculateDiscount(T), T)`, `voucherCharged = T − D`
4. **예산 원자 예약**(DB 트랜잭션 밖). 초과면 즉시 `PROMOTION_BUDGET_EXCEEDED`
5. DB 트랜잭션: 쿠폰 재확인 → `voucher` `SELECT FOR UPDATE` → 잔액 차감 → Transaction 생성 → 원장 2-leg×2쌍 → 쿠폰 `redeem()` + `CouponRedemption` 저장 → 포인트 적립 → CRITICAL 감사 이벤트
6. `finally`: 미커밋이면 `budgetManager.release`로 예산 보상(누수 방지)

**(c) 포인트 신규 계좌 — `INSERT IGNORE` 선행:**

신규 회원의 포인트 적립은 `SELECT FOR UPDATE` 대상 행이 아직 없을 수 있다. 존재하지 않는 행에 `SELECT FOR UPDATE`를 걸면 InnoDB가 **갭 락(gap lock)**을 잡아 동시 INSERT가 교착될 수 있다. 따라서 `ensureExists`(네이티브 `INSERT IGNORE`)로 계좌 행을 먼저 원자적으로 보장한 뒤 `findByMemberIdForUpdate`가 항상 **기존 행**을 잠그게 하여 갭 락 데드락을 회피한다.

**(d) 지역 월 발행한도 — Redis Lua:**

키 `region:monthly:{regionId}:{yyyyMM}`(TTL 월말+1일)에 (a)와 동일한 Lua 패턴 적용. Member 락 1개만 사용하므로 데드락이 없고, 월초 실제 발행액으로 카운터를 동기화한다.

**(e) 트랜잭션-락 순서 보장 — `TransactionTemplate`:**

분산락 사용 서비스는 `@Transactional` 대신 `TransactionTemplate`을 써서 **분산락 → 트랜잭션(커밋) → 락 해제** 순서를 보장한다. `@Transactional`이면 락 해제~커밋 사이에 다른 스레드가 커밋 전 데이터를 읽는 문제가 생긴다.

---

## 3. 멱등성 설계

### 멱등키 필요 엔드포인트

| Endpoint | 이유 |
|----------|------|
| `POST /api/v1/vouchers/purchase` | 결제 연동. 재시도 시 중복 구매 방지 |
| `POST /api/v1/vouchers/{id}/redeem` | 결제 처리. 재시도 시 이중 차감 방지 |
| `POST /api/v1/vouchers/{id}/refund` / `…/withdraw` | 환불·청약철회 이중 처리 방지 |
| `POST /api/v1/transactions/{id}/cancel` | 거래 취소 이중 처리 방지 |

`@Idempotent` 메서드는 `Idempotency-Key` 헤더를 요구한다.

### 저장 전략: Redis 캐시 + DB 뮤텍스
- **DB UNIQUE 제약을 분산 뮤텍스로 사용**: 처리 "전에" `IN_PROGRESS` 행을 `reserve(INSERT)`로 선점. 같은 키 동시 요청은 단 하나만 선점에 성공하고 나머지는 `DataIntegrityViolationException`으로 걸러진다(동시 이중 처리 차단).
- **Redis 빠른 경로**: `idempotency:{key}` → 완료 응답(`status|body`) 캐시, TTL 24h. 순차 재시도를 DB 조회 없이 처리.
- **Redis FALLBACK**: `findCachedResponse`가 Redis 장애 시 경고 로그 후 `null` 반환 → DB(`findCompletedInDb`)로 안전하게 강등(가용성 우선). DB가 source of truth이고 Redis 캐시 기록 실패는 로깅만 하고 무시한다.

**선택 이유:** Redis만 쓰면 TTL 만료/장애 시 중복 실행 가능, DB만 쓰면 매 요청 DB 조회로 성능 저하. 이중 저장이 금융 시스템에 적합하다.

### 중복 감지 시 응답
- **완료된 요청 재시도** → 캐시/DB의 **원본 응답(status + body)을 그대로 재반환**. 클라이언트 입장에서 재시도는 "성공 여부를 모르는 상태"이므로 원래 결과를 받아야 정상 흐름을 이어갈 수 있다.
- **처리 중(in-progress) 동시 중복** → **409 Conflict**(`"이미 처리 중인 요청입니다"`). 아직 결과가 없어 재반환할 응답이 없기 때문.
- 처리 실패(예외/4xx·5xx) → `afterCompletion`에서 선점 행을 `release(DELETE)`하여 재시도 허용.

성공 응답 캡처는 `IdempotencyResponseAdvice`(`ResponseBodyAdvice`)가 직렬화 후 `markCompleted` → DB COMPLETED + Redis 캐시.

---

## 4. 도메인 이벤트 설계

| Event | 트리거 시점 | Payload(요약) | Listener 책임 |
|-------|-----------|---------------|---------------|
| `VoucherIssuedEvent` | 발행 완료 | voucherId, memberId, regionId, faceValue | 감사 로그, 알림 |
| `VoucherRedeemedEvent` | 결제(사용) 완료 | voucherId, merchantId, amount, remainingBalance, transactionId | 감사 로그, 정산 대상 추가, 알림 |
| `VoucherRefundedEvent` / `VoucherWithdrawnEvent` | 환불/청약철회 완료 | voucherId, memberId, refundAmount, transactionId | 감사 로그, 알림 |
| `VoucherExpiredEvent` | 만료 처리 | voucherId, remainingBalance | 감사 로그 |
| `TransactionCancelledEvent` | 거래 취소 | transactionId, voucherId, cancelAmount | 감사 로그, 정산 차감 |
| `MerchantApprovedEvent` / `SettlementConfirmedEvent` | 가맹점 승인 / 정산 확정 | merchantId / settlementId, totalAmount, period | 감사 로그, (향후) 지급 연동 |

**핵심 원칙: 원장·포인트 기록은 이벤트가 아닌 서비스 내 동기 호출**
- 잔액 변경 + 원장 기록 + 포인트 적립 + Transaction 생성 = **동일 DB 트랜잭션**(정합성 보장). `RedemptionOrchestrator`는 한 결제 트랜잭션 안에서 바우처 차감·원장 2-leg·쿠폰 회수·포인트 적립을 모두 처리한다.
- 이벤트 리스너 책임: 감사 로그, 알림, 정산 큐 등 비핵심 부수효과.

### 리스너 실패 처리
- CRITICAL 감사: `@TransactionalEventListener(BEFORE_COMMIT)` — 동일 트랜잭션, 실패 시 전체 롤백.
- HIGH/MEDIUM 감사·알림·정산 큐: `AFTER_COMMIT` — 실패 시 `failed_events` 적재 → 스케줄러 재처리.

### Kafka 전환 가능 구조
- 모든 이벤트가 `ApplicationEventPublisher.publishEvent()`로 발행, `domain/event/`의 순수 데이터 클래스.
- 전환 시: `@TransactionalEventListener` → `KafkaTemplate.send()`, 리스너 → `@KafkaListener`. 도메인 코드 변경 없음.
- Transactional Outbox로 가려면 발행 시점에 `outbox` INSERT → CDC/Polling으로 발행.

---

## 5. 감사 로그 설계

| 감사 등급 | 대상 작업 |
|----------|----------|
| **CRITICAL** | 상품권 발행/결제/환불/청약철회, 거래 취소, 쿠폰 결제, 포인트 적립·취소, 정산 확정, 수동 원장 조정 |
| **HIGH** | 가맹점 승인/거절/해지, 회원 정지/탈퇴, 지자체·프로모션 정책 변경 |
| **MEDIUM** | 가맹점 정보 수정, 관리자 로그인, 상품권 만료 처리 |

```sql
CREATE TABLE audit_logs (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id        VARCHAR(36) UNIQUE NOT NULL,  -- UUID, 이벤트 식별자
    event_type      VARCHAR(50) NOT NULL,         -- VOUCHER_ISSUED, COUPON_REDEEMED, POINT_EARNED ...
    severity        VARCHAR(10) NOT NULL,         -- CRITICAL, HIGH, MEDIUM
    aggregate_type  VARCHAR(30) NOT NULL,         -- VOUCHER, COUPON, POINT, MERCHANT ...
    aggregate_id    BIGINT NOT NULL,
    actor_id        BIGINT,                       -- 수행자(시스템 배치는 NULL)
    actor_type      VARCHAR(20),                  -- USER, MERCHANT, ADMIN, SYSTEM
    action          VARCHAR(50) NOT NULL,
    previous_state  JSON, current_state JSON, metadata JSON,
    idempotency_key VARCHAR(64),
    created_at      DATETIME(6) NOT NULL,         -- 마이크로초 정밀도
    INDEX idx_audit_aggregate (aggregate_type, aggregate_id, created_at),
    INDEX idx_audit_event_type (event_type, created_at),
    INDEX idx_audit_actor (actor_id, created_at)
);
```

**구현 방식 — Spring 이벤트 리스너(도메인 이벤트 기반):** AOP("어떤 메서드가 호출되었는가")나 JPA EntityListener(상태 전이의 "누가·왜" 맥락 부족)와 달리, 도메인 이벤트에는 비즈니스 의미가 담겨 감사 맥락이 풍부하다. CRITICAL은 `BEFORE_COMMIT`(실패 시 롤백), HIGH/MEDIUM은 `AFTER_COMMIT`(실패 시 재시도 큐).

---

## 6. 보상 트랜잭션 체계 (시니어 레벨 설계 결정)

모든 취소/환불은 단순 DELETE·상태 변경이 아니라 **보상 트랜잭션(Compensating Transaction)**으로 처리한다.

- 원 거래를 삭제·수정하지 않고, **역방향 Transaction + LedgerEntry 쌍**을 새로 생성. `original_transaction_id`로 연결.
- **포인트 역분개(`reverseEarn`)**: 거래 취소 시 원 redemption이 적립한 `POINT_EARN`을 찾아 `CANCELLATION` 원장쌍(`POINT_FUNDING`→`POINT_BALANCE`) + `CANCEL` PointTransaction으로 역분개하고 캐시 잔액을 차감한다. 원 `EARN` 행은 불변 보존, 적립이 없었으면 no-op(멱등).

**왜 중요한가:** 감사 추적성(삭제 없이 모든 금전 흐름 보존), 법적 증거력(원 거래 immutable), 정산 정합성("총 사용액 − 총 취소액"), 이상 거래 탐지(취소 비율). 소프트 삭제는 "무효 표시"일 뿐 "돈이 어디로 갔는지"를 원장 수준에서 증명하지 못한다.

### 결합 결제 gross 회계 모델 (2-leg × 2쌍)

가맹점은 항상 **gross 주문총액 `T`**를 수취해야 한다(쿠폰 할인 `D`만큼 덜 받으면 안 됨). 따라서 결제는 동일 `transactionId`를 공유하는 균형 2-leg 두 쌍으로 기록한다. 바우처 실차감은 `T−D`(`voucherCharged`)다.

| 쌍 | 차변(Debit) | 대변(Credit) | 금액 | 의미 |
|----|-------------|--------------|------|------|
| 1. 바우처 결제분 | `MERCHANT_RECEIVABLE` | `VOUCHER_BALANCE` | `T−D` | 회원이 바우처로 지불 |
| 2. 플랫폼 보조분 | `MERCHANT_RECEIVABLE` | `PROMOTION_FUNDING` | `D` | 플랫폼이 쿠폰 할인 출연 |

- 과할인은 `D = min(D, T)`로 클램프. 전액 쿠폰 보전(`T−D=0`)이나 `D=0`이면 해당 쌍을 생략한다(LedgerEntry는 양수 금액만 허용, 순효과·정합성 동일).
- 포인트 적립 기준액은 실제 결제액 `T−D`이며 보조분 `D`는 적립 대상이 아니다.
- **취소(`cancelWithCoupon`)**: 두 쌍을 각각 역분개(`VOUCHER_BALANCE`→`MERCHANT_RECEIVABLE` `T−D`, `PROMOTION_FUNDING`→`MERCHANT_RECEIVABLE` `D`), 바우처 잔액은 `T−D`만 복원(과복원 방지), 쿠폰 `CANCELLED`, 예산 `release(DECRBY)`, 포인트 역분개까지 동일 취소 트랜잭션 내에서 처리.

---

## 7. 횡단 관심사 (Cross-cutting)

### API 응답 표준화 (`ApiResponse<T>`)
모든 응답은 `{ success, data, error }` 형태(`@JsonInclude(NON_NULL)`).
- 성공: 컨트롤러가 `ApiResponse.ok(data)` 반환 → `{ "success": true, "data": {...} }`
- 실패: `GlobalExceptionHandler`(`@RestControllerAdvice`)가 모든 예외를 `ApiResponse.error(code, message)`로 표준화 → `{ "success": false, "error": { "code", "message" } }`. `BusinessException`은 `ErrorCode.status`, 검증 실패(`MethodArgumentNotValid`)는 400, 미인증은 401, 미처리 예외는 500으로 매핑.

```json
// 성공                              // 실패
{ "success": true,                  { "success": false,
  "data": { "balance": 9000 } }       "error": { "code": "INSUFFICIENT_BALANCE",
                                                  "message": "잔액이 부족합니다" } }
```

클라이언트는 `success` 한 필드만으로 분기하고, 실패 시 `error.code`로 도메인 오류를 식별한다(HTTP 상태코드와 이중 신호).

### 요청 추적 (`RequestTraceFilter`)
- 인바운드 `X-Request-Id`가 있으면 사용, 없으면 16자 ID를 생성 → MDC `requestId` 주입 → 응답 헤더로 echo.
- 로그 패턴 `[%X{requestId:-}]`(`application.yml`)로 한 요청의 모든 로그가 동일 trace ID로 묶인다(분산 추적·디버깅 기반).
- `@Order(HIGHEST_PRECEDENCE)` — JWT 필터보다 **앞**에 배치(`SecurityConfig`)하여 인증 실패 로그까지 추적된다.

### DB 마이그레이션 (Flyway)
- `ddl-auto: validate` + Flyway가 스키마 **단일 진실원천**(`baseline-on-migrate: true`).
- 마이그레이션: `V1__baseline`(코어 도메인·감사·멱등) → `V2__promotion_coupon` → `V3__point` → `V4__point_cancel`(`PointTransaction.type`에 `CANCEL` 추가).
- **왜 ddl-auto가 아닌 Flyway:** `ddl-auto: update`는 운영에서 비결정적(컬럼 삭제·인덱스 변경 미반영, 적용 순서 불명)이라 금융 데이터에 위험. `validate`는 엔티티-스키마 불일치를 부팅 시 즉시 검출하고, 버전화된 SQL은 리뷰·롤백·재현이 가능하다.

### 인증 (JWT)
- `JwtAuthenticationFilter`가 `Authorization: Bearer <jwt>` 검증 → principal = **memberId(Long)**, 권한 `ROLE_<role>`을 SecurityContext에 주입. 토큰 없음/무효면 익명 → 보호 라우트에서 401(`HttpStatusEntryPoint`).
- `SecurityUtils.currentMemberId()`로 신규 엔드포인트는 **body의 memberId를 신뢰하지 않고** principal에서 신원을 도출(권한 상승 차단).
- 라우팅: `members/register·login`, `actuator/**`, `swagger-ui/**`, `v3/api-docs/**` → `permitAll`; `/api/v1/me` → `authenticated`. 기존 voucher/settlement retrofit은 STRETCH로 현재 전체 허용.
- 세션 STATELESS, CSRF off. `JwtTokenProvider`는 **prod 프로파일에서 dev 기본 시크릿 사용 시 부팅 실패**(fail-fast), 비프로파일은 경고 로그.

---

## 8. MySQL 관련 고려사항

### 인덱스 전략 (발췌)

| 테이블 | 인덱스 | 용도 |
|--------|--------|------|
| `vouchers` | `(member_id, status)` / `(region_id, status, expires_at)` / `(status, expires_at)` / `UNIQUE (voucher_code)` | 목록·현황·만료 스캔·코드 유일성 |
| `transactions` | `(voucher_id, created_at)` / `(merchant_id, status, created_at)` | 거래 이력·정산 대상 |
| `ledger_entries` | `(transaction_id)` / `(debit_account, created_at)` | 거래별·계정별 원장 |
| `promotions` | `idx_promotion_status (status, starts_at, ends_at)` | 활성 프로모션 조회 |
| `coupons` | `idx_coupon_member (member_id, status)` / `idx_coupon_promotion (promotion_id)` | 내 쿠폰·프로모션별 발급 |
| `coupon_redemptions` | `idx_couponredemption_tx (transaction_id)` / `idx_couponredemption_member_promo (member_id, promotion_id, cancelled)` | 취소 역추적·1인 한도 카운트·예산 합계 |
| `point_accounts` | `UNIQUE (member_id)` | 1회원 1계좌(`INSERT IGNORE` 보장) |
| `point_transactions` | `idx_point_tx_member (member_id, created_at)` / `idx_point_tx_source (source_transaction_id)` | 적립 이력·역분개 조회 |
| `settlements` | `UNIQUE (merchant_id, period)` | 중복 정산 방지 |
| `idempotency_keys` | `UNIQUE (idempotency_key)` | 멱등 뮤텍스 |

### 계정과목(Chart of Accounts) 확장
gross 정산·플랫폼 펀딩 모델을 위해 계정을 추가했다: `PROMOTION_FUNDING`(대변정상, 쿠폰 보조 누적), `POINT_BALANCE`(차변정상, 바우처 잔액과 동일 취급), `POINT_FUNDING`(대변정상, 포인트 적립 출연).

### 트랜잭션 격리 수준

| Operation | Isolation Level | 이유 |
|-----------|----------------|------|
| 상품권 결제 (잔액 차감) | `READ_COMMITTED` + 비관적 락 | `SELECT FOR UPDATE`로 row-level 락. REPEATABLE_READ의 gap lock 경합 회피 |
| 원장 기록 | `READ_COMMITTED` | INSERT only, 충돌 없음 |
| 정합성 검증·정산 배치 | `REPEATABLE_READ` | 검증/합산 중 일관 스냅샷 필요 |
| 일반 조회 | `READ_COMMITTED` | 최신 커밋 데이터 반영 |

### MySQL 8.x 활용
JSON 컬럼(`audit_logs.*_state`/`metadata` — `JSON_EXTRACT` 조건부 조회), Generated Column(`voucher_usage_ratio` — 60% 환불 조건 검증, 인덱싱 가능), Window Function(정산 `SUM() OVER (PARTITION BY merchant_id)`).

### `open-in-view: false` + 트랜잭션 내 재로딩
OSIV를 끄면 영속성 컨텍스트가 트랜잭션 경계에서 닫힌다. 취소 같은 분산락 경로는 락 키(`voucherId`)만 얻기 위해 트랜잭션 밖에서 먼저 조회하지만, **상태 변경 대상 엔티티는 반드시 트랜잭션 안에서 다시 로드(managed)** 해야 한다 — 트랜잭션 밖에서 로드한 detached 엔티티의 `CANCELLED` 전이는 영속화되지 않아 정산 집계에서 취소가 누락된다(`TransactionCancelService`). 이는 OSIV가 가리던 LazyInitialization 함정을 명시적 재로딩으로 제거한 선택이다.

---

## 9. 운영 모니터링 (Observability)

포트폴리오 버전이지만 프로덕션 확장 가능한 스택을 함께 구성한다.

### 메트릭 (Spring Actuator + Micrometer + Prometheus)
`/actuator/prometheus` 노출, 히스토그램·SLO 버킷 설정(`application.yml`).

| 메트릭 | 설명 |
|--------|------|
| `voucher.redemption.duration` / `coupon.redemption.duration` | 결제·결합결제 지연(Timer) |
| `voucher.redemption.count` / `coupon.redemption.count` | 결제·결합결제 건수(성공/실패 태그) |
| `point.earn.count` / `point.cancel.count` | 포인트 적립·취소 건수 |
| `lock.acquisition.duration`(key 태그) / `lock.acquisition.timeout` / `lock.redis.fallback` | 분산락 대기·타임아웃·DB 강등 |
| `ledger.verification.imbalance` | 정합성 검증 불일치(Gauge) |

### 부하 테스트 + 대시보드 (`load-test/`)
- **k6 4종 시나리오**: A 핫키(단일 바우처 경합), B 분산(다수 바우처), C 지역 발행한도, D 멱등성(중복 키 폭주).
- **Prometheus + Grafana** 스택(`load-test/monitoring/docker-compose.yml`, `voucher-scale.json` 대시보드)으로 락 대기·결제 지연·원장 불균형을 실시간 관측.

### 헬스체크 / 알림
- Actuator `/health`(MySQL·Redis connectivity 자동 포함) + LedgerVerification 마지막 실행 결과.
- 포트폴리오는 로그(SLF4J WARN/ERROR), 프로덕션은 Slack/PagerDuty 연동(`ApplicationEventPublisher` → 알림 리스너)으로 확장.

---

**2단계 핵심 결정:** 10개 모듈(voucher·promotion·point·transaction·ledger·merchant·region·member + common·config) 헥사고날 레이어링. 원장·포인트는 동기 호출로 결제 트랜잭션과 원자 처리, 프로모션 예산·지역 한도는 Redis Lua 원자 카운터(+매시 DB 재동기화), 결합 결제는 `coupon→voucher` 정준 락 순서로 데드락 제거, 포인트 신규 계좌는 `INSERT IGNORE`로 갭 락 회피. 멱등성은 DB UNIQUE 뮤텍스 + Redis 캐시(장애 시 DB 폴백, 완료=원본 재반환·진행중=409). 횡단으로 `ApiResponse` 표준 응답·`X-Request-Id` 추적·Flyway 마이그레이션·JWT 인증을 갖추고, gross 정산 회계(2-leg×2쌍)와 모든 취소는 보상 트랜잭션(쿠폰 2-leg·포인트 역분개 포함)으로 처리한다. BigDecimal 비교는 `compareTo`로 통일.
