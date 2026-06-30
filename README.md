# 커머스 결제·프로모션 백엔드

대용량 트래픽에서 결제·정산·쿠폰·포인트의 재무 정합성을 보장하고, AI로 프로모션 운영을 자동화하는 커머스 백엔드.
복식부기 원장, 보상 트랜잭션, 분산락 기반 동시성 제어로 **재무 무결성**, **감사 추적성**, **동시성 안전**을 보장한다.

## 커머스 3축 핵심 역량 (전체 구현 완료)

| 축 | 설명 | 주요 기술 |
|---|---|---|
| **결제·정산·쿠폰·포인트 신뢰성** | 복식부기 원장 + 보상 트랜잭션으로 결제·쿠폰·포인트 분개의 재무 정합성 보장. 멱등성 exactly-once. | `LedgerService`, `PromotionService`, `@Idempotent` |
| **대용량 동시성** | Redisson 분산락 + DB 비관적 락 + Redis Lua 예산 카운터 + k6 부하테스트로 동시성 안전 증명. | `VoucherLockManager`, `RegionCounterSyncScheduler` |
| **AI 프로모션 자동화** | 자연어 입력 → 프로모션 초안 생성 + 결정적 서버사이드 가드레일 검증. AI는 제안, 서버가 결정. | `PromotionDraftService` (Claude API) |

---

## 설계 원칙

| 원칙 | 커머스에서의 의미 | 구현 방식 | 핵심 코드 |
|------|----------------|----------|----------|
| **재무 무결성** | 결제·정산·쿠폰·포인트 분개의 재무 정합성 | 모든 금전 변동을 복식부기 원장(DEBIT/CREDIT 2행)으로 기록. 정합성 검증 배치로 캐시 잔액 vs 원장 합산 비교 | `LedgerService.record()` |
| **감사 추적성** | 환불·취소 분쟁의 완벽한 감사 추적 | 취소/환불을 DELETE 대신 보상 트랜잭션으로 처리. 원 거래 불변 보존 | `TransactionCancelService.cancel()` |
| **동시성 안전** | 재고/예산/잔액의 동시성 안전 | Redisson 분산락 + DB 비관적 락 이중 방어. 10스레드 동시 결제 테스트로 검증 | `VoucherLockManager` |

---

## 시스템 아키텍처

Aggregate 중심 모듈러 모놀리스. 7개 모듈, 87개 소스 파일, 35개 API 엔드포인트.

```
com/commerce/
├── common/       ← BaseEntity, ErrorCode, AuditLog, Idempotency, FailedEvent
├── region/       ← 지자체 (Region + RegionPolicy + QueryDSL)
├── member/       ← 회원 (Member + JWT 인증 + Spring Security)
├── merchant/     ← 가맹점 (Merchant + 승인 플로우 + Settlement + 이의제기)
├── voucher/      ← 상품권 (구매, 결제, 환불, 청약철회, 만료 배치)
├── transaction/  ← 거래 (Transaction + 보상 트랜잭션 + 취소 API)
├── ledger/       ← 원장 (LedgerEntry + 정합성 검증 + 관리자 조회 API)
└── config/       ← Redis, Security, JWT, QueryDSL, Swagger
```

### 핵심 흐름

```
구매 ──→ 결제(부분사용) ──→ 추가결제 ──→ 잔액환불(60%+) ──→ 정산(역월)
  │         │                              │
  │         └── 거래 취소 (보상 트랜잭션)      └── 원장 기록 (동기, 같은 DB 트랜잭션)
  │
  └── 청약철회 (7일 이내 전액 환불)
```

### 모듈 간 의존 관계

```
voucher ──동기──→ ledger    (잔액 변경 시 원장 기록, 같은 DB 트랜잭션)
voucher ──동기──→ transaction (거래 생성)
merchant ─동기──→ transaction (정산 대상 거래 조회)

나머지 ──이벤트──→ audit     (비동기, Kafka 전환 가능)
```

### Kafka 전환 전략

현재 `ApplicationEventPublisher`로 구현된 이벤트 시스템은 **Kafka로 교체 가능한 구조**로 설계되었다.

| 현재 (모놀리스) | Kafka 전환 시 |
|---------------|-------------|
| `ApplicationEventPublisher.publishEvent()` | `KafkaTemplate.send()` |
| `@TransactionalEventListener` | `@KafkaListener` |
| 같은 JVM 내 동기/비동기 전달 | 브로커 기반 비동기 전달 |

**원장 기록은 Kafka로 전환하지 않는다.** 잔액 변경과 원장 기록은 같은 DB 트랜잭션에서 동기 처리해야 정합성(I2, I3)이 보장된다. 이벤트 기반으로 전환하면 커밋 후 리스너 실행 전 장애 시 원장 누락이 발생한다.

**Transactional Outbox 패턴 적용 시:**
1. 이벤트 발행 시점에 `outbox` 테이블에 INSERT (같은 DB 트랜잭션)
2. CDC(Change Data Capture) 또는 Polling으로 outbox → Kafka 발행
3. 도메인 코드 변경 없이 인프라 계층만 교체

---

## 구현 기능 상세

### 1. 지자체 관리 (Region)

지역사랑상품권은 지자체별로 독립 운영된다. 각 지자체는 고유한 정책(할인율, 구매한도, 발행한도, 환불 기준, 정산 주기)을 가진다.

- **지자체 등록**: 지자체명, 지역코드(2자리), 정책 설정
- **정책 수정**: 할인율, 1인 구매한도, 월 발행한도, 환불 기준비율(기본 60%), 정산 주기(일/주/월) 변경
- **운영 상태 관리**: `ACTIVE` → `SUSPENDED` → `DEACTIVATED` 상태 전이
- **정책은 Value Object(`RegionPolicy`)로 분리**: JPA `@Embeddable`로 매핑하여 도메인 의미를 보존

### 2. 회원 관리 (Member)

시민이 상품권을 구매하고 사용하기 위한 회원 체계.

- **회원 가입**: 이메일 중복 검증, BCrypt 비밀번호 암호화
- **JWT 인증**: 로그인 시 JWT 토큰 발급, 토큰에 memberId + role 포함
- **Role 기반 권한**: `USER` / `MERCHANT_OWNER` / `ADMIN` 세 가지 역할
- **상태 관리**: `PENDING` → `ACTIVE` → `SUSPENDED` → `WITHDRAWN` 상태 머신
- **관리자 기능**: 회원 정지(`suspend`), 정지 해제(`unsuspend`), 탈퇴 처리(`withdraw`)

### 3. 가맹점 관리 (Merchant)

지역사랑상품권을 받을 수 있는 가맹점의 등록부터 해지까지의 전체 생애주기.

- **가맹점 등록**: 사업자번호, 업종, 소속 지자체(Region) 지정. 등록 시 `PENDING_APPROVAL` 상태
- **심사 플로우**: 관리자가 `approve`(승인) 또는 `reject`(거절). 거절된 가맹점은 새 레코드로 재신청 가능 (기존 기록 보존)
- **운영 상태 관리**: `PENDING_APPROVAL` → `APPROVED` / `REJECTED`, `APPROVED` → `SUSPENDED` → `TERMINATED`
- **상태 전이 검증**: 도메인 엔티티 내부에서 잘못된 전이 시 예외 발생 (`PENDING`에서 `TERMINATED`로 바로 전이 불가)
- **이벤트 발행**: 승인 시 `MerchantApprovedEvent` → HIGH 등급 감사 로그 자동 기록

### 4. 상품권 발행 (Voucher Issuance)

선불 바우처(상품권)를 구매·주문하는 기능. 구매 한도 초과를 분산 환경에서도 방지한다.

- **상품권 코드 생성**: `SecureRandom` 기반 16자리 + Luhn mod 36 체크 디짓 (예: `SN-A3K9M2X7P1B4Q8R5`)
- **1인 구매한도 검증**: Redisson 분산락(`member:purchase:{memberId}`)으로 동시 구매 직렬화 후 DB 합산 검증
- **지자체 월 발행한도 검증**: Redis Lua 스크립트로 `INCRBY` + 한도 비교 + 초과 시 `DECRBY` 롤백을 **단일 원자적 연산**으로 수행
  - 데드락 방지: 분산락은 Member 1개만 사용, Region 한도는 Lua 스크립트로 별도 처리
  - Redis 재시작 대비: 매시간 배치로 DB 발행액을 Redis 카운터에 동기화
- **원장 기록**: 구매와 동시에 `LedgerEntry` 2행(DEBIT: VOUCHER_BALANCE, CREDIT: MEMBER_CASH) 동기 생성
- **멱등키 적용**: `Idempotency-Key` 헤더로 중복 구매 방지

### 5. 상품권 결제 — 부분 사용 (Voucher Redemption)

가맹점에서 상품권으로 결제하는 핵심 기능. 동시성 제어의 핵심 지점.

- **부분 사용**: 50,000원 상품권으로 30,000원 결제 → 잔액 20,000원 유지. 여러 번 나누어 사용 가능
- **이중 방어 동시성 제어**:
  1. Redisson 분산락(`voucher:{id}`)으로 1차 직렬화 (5초 대기, 10초 보유)
  2. DB `SELECT FOR UPDATE`로 2차 비관적 락 (Redis 장애 시 방어)
- **상태 자동 전이**: `ACTIVE` → `PARTIALLY_USED` (부분 사용), `PARTIALLY_USED` → `EXHAUSTED` (전액 소진)
- **검증 순서**: 분산락 획득 → DB 락 → 사용 가능 상태 확인 → 만료 여부 확인 → 잔액 확인 → 차감
- **원장 기록**: 동일 DB 트랜잭션 내에서 `LedgerEntry` 2행 동기 생성 (DEBIT: MERCHANT_RECEIVABLE, CREDIT: VOUCHER_BALANCE)
- **메트릭**: 결제 처리 시간(`voucher.redemption.duration`), 성공/실패 건수(`voucher.redemption.count`)
- **멱등키 적용**: 네트워크 타임아웃으로 인한 중복 결제 방지

### 6. 잔액 환불 (Balance Refund)

지역사랑상품권 고유 정책: 60% 이상 사용한 상품권의 잔액을 현금으로 환불.

- **환불 조건 검증**: `usageRatio = (faceValue - balance) / faceValue ≥ 0.60` (Region 정책에서 비율 조회)
- **상태 전이**: `PARTIALLY_USED` → `REFUND_REQUESTED` → `REFUNDED`
- **잔액 처리**: 잔액 전액을 환불하고 balance를 0으로 설정
- **원장 기록**: DEBIT: REFUND_PAYABLE, CREDIT: VOUCHER_BALANCE
- **분산락**: 환불 처리 중 결제 요청 경합 방지 (`voucher:{id}` 락)
- **경계값 검증**: 59% → 거절, 60% → 허용, 61% → 허용 (통합 테스트로 검증)

### 7. 청약철회 (Withdrawal)

전자상거래법에 따른 구매 후 7일 이내 전액 환불. 잔액환불과는 별개의 독립 프로세스.

- **철회 조건**: `ACTIVE` 상태(미사용) + 구매 후 7일 이내 (`purchasedAt + 7일 ≥ now`)
- **잔액환불과의 차이**: 청약철회는 미사용 상품권의 전액 환불, 잔액환불은 60%+ 사용 상품권의 잔액 환불
- **상태 전이**: `ACTIVE` → `WITHDRAWAL_REQUESTED` → `WITHDRAWN`
- **원장 기록**: DEBIT: REFUND_PAYABLE, CREDIT: VOUCHER_BALANCE (전액)
- **경계값 검증**: 7일째 → 허용, 8일째 → 거절 (통합 테스트로 검증)

### 8. 거래 취소 — 보상 트랜잭션 (Compensating Transaction)

기존 거래를 **삭제하거나 수정하지 않고** 역방향 보상 트랜잭션을 생성하여 취소 처리.

- **보상 트랜잭션 생성**: `TransactionType.CANCELLATION` + `originalTransactionId`로 원 거래 연결
- **역방향 원장**: 원 거래의 차변/대변을 반대로 기록 (DEBIT: VOUCHER_BALANCE, CREDIT: MERCHANT_RECEIVABLE)
- **잔액 복원**: `voucher.restoreBalance(amount)` — 취소된 금액만큼 잔액 증가, 상태 자동 복귀
- **원 거래 상태**: `COMPLETED` → `CANCEL_REQUESTED` → `CANCELLED` (원 거래의 금액/내용은 불변)
- **감사 추적성**: 원 거래 + 보상 거래가 모두 원장에 보존되어, 감사 시 "왜 금액이 변경되었는가"를 증명 가능
- **정산 자동 반영**: 취소된 거래는 `CANCELLED` 상태이므로 정산 시 `COMPLETED` 조건에서 자동 제외

### 9. 만료 처리 배치 (Expiry Scheduler)

유효기간이 지난 상품권을 자동으로 만료 처리하고, 잔액을 만료 계정으로 이동.

- **스케줄링**: `@Scheduled(cron = "0 */5 * * * *")` — 5분마다 실행
- **건별 독립 트랜잭션**: `@Transactional(propagation = REQUIRES_NEW)` — 1건 실패해도 나머지 정상 처리
- **경합 방지**: 만료 대상 ID 목록을 먼저 조회 → 건별로 `SELECT FOR UPDATE` 후 처리
- **이중 체크**: 락 획득 후 `isUsable()` 재확인 (조회 ~ 락 사이에 다른 결제가 처리될 수 있음)
- **원장 기록**: 잔액이 남아있는 경우 DEBIT: EXPIRED_VOUCHER, CREDIT: VOUCHER_BALANCE
- **balance 초기화**: 만료 시 잔액을 0으로 설정

### 10. 가맹점 정산 (Settlement)

가맹점이 받을 정산 금액을 기간별로 계산하고 확정하는 프로세스.

- **정산 금액 계산**: 해당 기간의 `COMPLETED` 상태 결제 합산 (취소된 거래는 `CANCELLED` 상태이므로 자동 제외)
- **정산 주기**: Region 단위로 설정 (일/주/월), 역월 기준 (KST)
- **중복 방지**: `(merchantId, periodStart, periodEnd)` Unique Constraint
- **이의 제기 플로우**: `PENDING` → `DISPUTED` (사유 기록) → `CONFIRMED` (이의 해결)
- **정산 확정 이벤트**: `SettlementConfirmedEvent` 발행 → CRITICAL 감사 로그 자동 기록
- **상태 머신**: `PENDING` → `CONFIRMED` → `PAID`, `PENDING` → `DISPUTED` → `CONFIRMED`

### 11. 복식부기 원장 및 정합성 검증 (Ledger & Verification)

프로젝트의 가장 핵심적인 재무 무결성 보장 체계.

- **2행 모델**: 모든 금전 변동마다 DEBIT 1행 + CREDIT 1행 = 2행의 `LedgerEntry` 생성
- **불변 엔티티**: `@Immutable` 어노테이션으로 Hibernate의 UPDATE/DELETE 원천 차단
- **계정 코드 체계**: `MEMBER_CASH`, `VOUCHER_BALANCE`, `MERCHANT_RECEIVABLE`, `EXPIRED_VOUCHER`, `REFUND_PAYABLE`, `SETTLEMENT_PAYABLE`, `REVENUE_DISCOUNT`
- **동기 기록 원칙**: 이벤트 리스너가 아닌 서비스 내 직접 호출로 잔액 변경과 같은 DB 트랜잭션에서 처리
- **정합성 검증 배치** (`LedgerVerificationService`):
  - 매일 02:00 실행 (`REPEATABLE_READ` 격리 수준)
  - 글로벌 검증: `sum(전체 DEBIT) == sum(전체 CREDIT)`
  - 건별 검증: 각 Voucher의 `balance` 캐시 vs 해당 Voucher 관련 원장 엔트리 합산 비교
  - 불일치 발견 시: **자동 수정하지 않음** — CRITICAL 감사 로그 + 관리자 알림 (사람이 판단)
- **관리자 조회 API**: 거래별 원장 조회, 계정별 잔액 조회, 수동 정합성 검증 트리거

### 12. 멱등키 처리 (Idempotency)

네트워크 불안정으로 인한 중복 요청을 안전하게 처리하는 체계.

- **대상 API**: 구매, 결제, 환불, 청약철회, 거래 취소 (금전 관련 전체)
- **이중 저장**: Redis TTL 24시간(1차, 빠른 감지) + DB(2차, Redis 장애 대비)
- **응답 보존**: 원래 응답 본문 + HTTP 상태코드를 함께 캐시하여 중복 요청 시 동일 응답 반환
- **자동 캡처**: `@Idempotent` 어노테이션 + `ResponseBodyAdvice`로 컨트롤러 코드 수정 없이 적용
- **`Idempotency-Key` 헤더**: 클라이언트가 UUID 기반 멱등키를 요청마다 전달

### 13. 감사 로그 체계 (Audit Log)

모든 비즈니스 이벤트를 등급별로 기록하는 감사 추적 시스템.

- **자동 기록**: 도메인 이벤트 발행 시 `AuditEventListener`가 자동으로 감사 로그 생성
- **등급별 처리**:
  - `CRITICAL` (발행/결제/환불/취소): `BEFORE_COMMIT` — 감사 실패 시 비즈니스 트랜잭션도 롤백
  - `HIGH/MEDIUM` (승인/수정/만료): `AFTER_COMMIT` + `REQUIRES_NEW` — 별도 트랜잭션
- **실패 복원**: `AFTER_COMMIT` 리스너 실패 시 `FailedEvent` 테이블에 기록 → 스케줄러가 자동 재처리
- **상태 추적**: `previousState`/`currentState`를 JSON으로 저장하여 변경 전후 상태 비교 가능
- **MySQL JSON Column**: `previous_state`, `current_state`, `metadata` 필드에 JSON 타입 활용

---

## 기술적 의사결정

### 1. 왜 복식부기 원장인가

단순 잔액 필드 차감은 "돈이 어디서 와서 어디로 갔는지" 추적이 불가능하다. 결제·정산·쿠폰·포인트가 얽힌 커머스 플랫폼에서, 감사 시 원장만으로 완벽한 자금 추적이 가능해야 한다.

```kotlin
// LedgerService.kt — 복식부기 기록
fun record(debitAccount: AccountCode, creditAccount: AccountCode,
           amount: BigDecimal, transactionId: Long, entryType: LedgerEntryType
): List<LedgerEntry> {
    val debitEntry = LedgerEntry(account = debitAccount, side = LedgerEntrySide.DEBIT, ...)
    val creditEntry = LedgerEntry(account = creditAccount, side = LedgerEntrySide.CREDIT, ...)
    return ledgerRepository.saveAll(listOf(debitEntry, creditEntry))  // 항상 2행
}
```

Voucher에 `balance` 캐시 필드를 두되, 진실의 원천은 항상 원장이다. `LedgerVerificationService`가 매일 캐시 잔액과 원장 합산을 비교하여 불일치를 탐지한다.

### 2. 왜 보상 트랜잭션인가

거래 취소를 DELETE나 상태 변경으로 구현하면 "왜 이 금액이 변경되었는가"를 증명할 수 없다.

```kotlin
// TransactionCancelService.kt — 원 거래를 수정하지 않고 역방향 엔트리 생성
val compensating = transactionService.create(
    type = TransactionType.CANCELLATION,
    amount = original.amount,
    originalTransactionId = original.id,  // 원 거래와 연결
)
ledgerService.record(
    debitAccount = AccountCode.VOUCHER_BALANCE,       // 원래: MERCHANT_RECEIVABLE
    creditAccount = AccountCode.MERCHANT_RECEIVABLE,  // 원래: VOUCHER_BALANCE
    amount = original.amount,                         // 역방향!
    transactionId = compensating.id,
    entryType = LedgerEntryType.CANCELLATION,
)
voucher.restoreBalance(original.amount)  // 잔액 복원
```

### 3. 왜 분산락 + DB 비관적 락 이중 방어인가

동일 상품권 동시 결제는 잔액 초과 차감이라는 치명적 사고를 유발한다.

```kotlin
// VoucherRedemptionService.kt — 분산락 → 트랜잭션(커밋) → 분산락 해제 순서 보장
fun redeem(voucherId: Long, merchantId: Long, amount: BigDecimal): RedemptionResult {
    return lockManager.withVoucherLock(voucherId) {       // 1차: Redisson 분산락
        transactionTemplate.execute { _ ->                // 트랜잭션 시작
            val voucher = voucherRepository.findByIdForUpdate(voucherId)  // 2차: DB SELECT FOR UPDATE
                ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND)

            voucher.redeem(amount)                        // 잔액 차감 (compareTo로 0 비교)
            val tx = transactionService.create(...)       // 거래 생성
            ledgerService.record(...)                     // 원장 기록 (동기, 같은 TX)
            tx.complete()
            eventPublisher.publishEvent(VoucherRedeemedEvent(...))  // 감사 로그 (이벤트)
            // ...
        }!!  // 여기서 커밋
    }  // 커밋 후 락 해제 — 다른 스레드가 항상 커밋된 데이터를 읽음
}
```

10스레드 동시 결제 테스트로 검증:
- 50,000원 상품권에 10,000원 × 10건 동시 → 정확히 5건 성공, 5건 `INSUFFICIENT_BALANCE`
- 잔액 0원, 원장 차대변 균형 확인

### 4. 왜 멱등키 이중 저장인가

Redis TTL(24시간)로 빠르게 중복을 감지하고, DB에도 저장하여 Redis 장애/TTL 만료 후에도 중복을 방지한다. 중복 감지 시 409가 아닌 **원래 응답을 원래 상태코드와 함께 반환**하여 클라이언트가 정상 흐름을 이어갈 수 있도록 한다.

### 5. 왜 원장 기록은 이벤트가 아닌 동기 호출인가

`@TransactionalEventListener(AFTER_COMMIT)`으로 원장을 기록하면 커밋 후 리스너 실행 전 장애 시 **원장 누락**이 발생한다. 잔액 변경과 원장 기록은 반드시 같은 DB 트랜잭션에서 동기적으로 처리하여 불변식을 보장한다. 이벤트는 감사 로그, 알림 등 비핵심 부수효과에만 사용한다.

---

## 동시성 제어 전략

| 작업 | 전략 | 방지하는 장애 |
|------|------|-------------|
| 상품권 결제 | Redisson 분산락 + DB 비관적 락 + TransactionTemplate | 이중 사용, 잔액 초과 차감, 락-커밋 역전 |
| 상품권 발행 | Member 분산락 + Region Redis Lua 스크립트 + TransactionTemplate | 한도 초과 발행, 데드락 |
| 잔액 환불 / 청약철회 | Redisson 분산락 + TransactionTemplate | 사용 중 환불 경합 |
| 만료 배치 | DB 비관적 락 (건별 `REQUIRES_NEW` + `SELECT FOR UPDATE`) | 만료 중 결제 경합 |
| 가맹점 수정 | JPA Optimistic Lock (`@Version`) | 동시 상태 변경 |
| 정산 생성 | DB Unique Constraint (`merchant_id + period`) | 중복 정산 |

---

## 감사 로그 체계

모든 금전 변동 이벤트는 도메인 이벤트로 발행되어 자동으로 감사 로그에 기록된다.

| Event | 감사 등급 | 트랜잭션 처리 |
|-------|:--------:|-------------|
| 상품권 발행/결제/환불/철회/취소 | **CRITICAL** | `BEFORE_COMMIT` — 감사 실패 시 전체 롤백 |
| 가맹점 승인/거절/해지, 정산 확정 | **HIGH** | `AFTER_COMMIT` + `REQUIRES_NEW` |
| 가맹점 수정, 만료 처리 | **MEDIUM** | `AFTER_COMMIT` + `REQUIRES_NEW` |

- `AFTER_COMMIT` 리스너 실패 시 `failed_events` 테이블에 기록 → 스케줄러가 자동 재처리
- 감사 로그에 `previousState`/`currentState`를 JSON으로 저장하여 변경 전후 상태 추적

### Kafka 전환 가능 구조

이벤트 클래스는 순수 데이터 클래스로 Spring 의존성이 없다. Kafka 전환 시 `ApplicationEventPublisher` → `KafkaTemplate`, `@TransactionalEventListener` → `@KafkaListener`로 교체하면 도메인 코드 변경 없이 전환 가능.

---

## API 엔드포인트 (35개)

| 모듈 | Method | Endpoint | 설명 |
|------|--------|----------|------|
| **회원** | POST | `/api/v1/members/register` | 회원 가입 |
| | POST | `/api/v1/members/login` | 로그인 (JWT) |
| | POST | `/api/v1/members/{id}/suspend` | 회원 정지 |
| | POST | `/api/v1/members/{id}/withdraw` | 회원 탈퇴 |
| **지자체** | POST | `/api/v1/regions` | 지자체 생성 |
| | PUT | `/api/v1/regions/{id}/policy` | 정책 수정 |
| | POST | `/api/v1/regions/{id}/suspend` | 운영 중지 |
| **가맹점** | POST | `/api/v1/merchants` | 가맹점 등록 |
| | POST | `/api/v1/merchants/{id}/approve` | 심사 승인 |
| | POST | `/api/v1/merchants/{id}/reject` | 심사 거절 |
| | POST | `/api/v1/merchants/{id}/suspend` | 운영 정지 |
| | POST | `/api/v1/merchants/{id}/terminate` | 해지 |
| **상품권** | POST | `/api/v1/vouchers/purchase` | 구매 (멱등) |
| | POST | `/api/v1/vouchers/{id}/redeem` | 결제 (멱등) |
| | POST | `/api/v1/vouchers/{id}/refund` | 잔액 환불 (멱등) |
| | POST | `/api/v1/vouchers/{id}/withdraw` | 청약철회 (멱등) |
| | GET | `/api/v1/vouchers` | 목록 조회 (QueryDSL + 페이지네이션) |
| **거래** | POST | `/api/v1/transactions/{id}/cancel` | 거래 취소 (멱등) |
| **정산** | POST | `/api/v1/settlements/calculate` | 정산 생성 |
| | POST | `/api/v1/settlements/{id}/confirm` | 정산 확정 |
| | POST | `/api/v1/settlements/{id}/dispute` | 이의 제기 |
| **원장** | GET | `/api/v1/admin/ledger/entries/transaction/{id}` | 거래별 원장 조회 |
| | GET | `/api/v1/admin/ledger/balance/{account}` | 계정별 잔액 조회 |
| | POST | `/api/v1/admin/ledger/verify` | 정합성 검증 실행 |

Swagger UI: `http://localhost:8080/swagger-ui.html`

---

## 기술 스택

| 항목 | 기술 |
|------|------|
| Language | Kotlin 1.9 |
| Framework | Spring Boot 3.2 |
| ORM | Spring Data JPA + QueryDSL 5.1 |
| DB | MySQL 8.x (JSON Column, Generated Column) |
| Cache / Lock | Redis 7 (Redisson 3.27) |
| Events | Spring ApplicationEventPublisher (Kafka-replaceable) |
| Auth | JWT (jjwt 0.12) + Spring Security |
| Test | JUnit 5 + Kotest 5.8 + Testcontainers 1.19 |
| Monitoring | Spring Actuator + Micrometer (Prometheus) |
| API Docs | springdoc-openapi (Swagger UI) |
| Build | Gradle Kotlin DSL |
| Infra | Docker Compose (MySQL + Redis) |

---

## 실행 방법

```bash
# 1. 전체 인프라 실행 (MySQL + Redis + Prometheus + Grafana)
docker compose up -d

# 2. 애플리케이션 실행
./gradlew bootRun

# 3. 테스트 실행 (Testcontainers가 MySQL/Redis를 자동 구동)
./gradlew test
```

| 서비스 | URL | 설명 |
|--------|-----|------|
| Swagger UI | http://localhost:8080/swagger-ui.html | API 문서 |
| Grafana | http://localhost:3000 (admin/admin) | 모니터링 대시보드 |
| Prometheus | http://localhost:9090 | 메트릭 직접 쿼리 |

### 모니터링 대시보드

`docker compose up -d` 시 Prometheus + Grafana가 자동 구성된다. Grafana에 접속하면 **Voucher System Dashboard**가 자동 로드되며, 7개 패널로 시스템 상태를 실시간 모니터링할 수 있다.

| 패널 | 의미 |
|------|------|
| 결제 처리량 (성공/실패) | 장애 발생 시 실패율 급증 감지 |
| 결제 지연시간 (p50/p95/p99) | 성능 저하 조기 감지 |
| 분산락 획득 시간 | Redis 부하/경합 상태 파악 |
| 락 타임아웃 / Redis Fallback | Redis 장애 발생 여부 확인 |
| 원장 정합성 | **0이 아니면 즉시 조사 필요** |
| JVM 메모리 | 메모리 누수 감지 |
| HTTP 요청량 | 트래픽 패턴 파악 |

---

## 테스트 (62건, 0 실패)

### 단위 테스트 (41건)

| 테스트 | 건수 | 검증 내용 |
|--------|:----:|----------|
| VoucherTest | 15 | 8개 상태 × 전이 조합, 잘못된 전이 시 예외, usageRatio 계산 |
| MerchantTest | 8 | PENDING→APPROVED/REJECTED→SUSPENDED→TERMINATED 전이 |
| MemberTest | 7 | PENDING→ACTIVE→SUSPENDED→WITHDRAWN 전이 |
| RegionTest | 7 | ACTIVE→SUSPENDED→DEACTIVATED 전이, 정책 수정 |
| VoucherCodeGeneratorTest | 4 | Luhn mod 36 체크 디짓 생성/검증, 유일성(100건) |

### 통합 테스트 (21건, Testcontainers)

| 테스트 | 건수 | 검증 내용 |
|--------|:----:|----------|
| **E2EFlowTest** | 6 | 전체 lifecycle, 보상 트랜잭션, 청약철회, 정산, 감사 로그 |
| **BoundaryTest** | 6 | 환불 60% 경계(59/60/61%), 청약철회 7일 경계, 정산 중복 방지 |
| **VoucherExpiryTest** | 3 | 만료 배치 처리, 부분사용 만료, 원장 균형 검증 |
| **ConcurrencyTest** | 2 | 10스레드 동시 결제(잔액>=0, 정확히 5건 성공), 실패 원인 검증 |
| LedgerServiceTest | 2 | 복식부기 2행 생성, 글로벌 차대변 균형 |
| RegionServiceTest | 2 | Region CRUD |

### 핵심 테스트 시나리오

**동시성 안전 검증:**
```
50,000원 상품권 × 10,000원 결제 × 10스레드 동시 →
  성공 5건 + 실패 5건 (모두 INSUFFICIENT_BALANCE)
  잔액 = 0원 (음수 불가 불변식 I1 보장)
  원장 차대변 균형 (불변식 I2 보장)
```

**보상 트랜잭션 검증:**
```
결제(30,000원) → 취소 →
  원 거래: CANCELLED (수정하지 않음)
  보상 거래: CANCELLATION + originalTransactionId 연결
  역방향 원장: debit VOUCHER_BALANCE, credit MERCHANT_RECEIVABLE
  잔액 복원: 50,000원
```

---

## 프로젝트 구조

```
commerce-platform/
├── docs/
│   ├── 01-domain-design.md           # 도메인 & 비즈니스 규칙 (엔티티, 상태머신, 불변식)
│   ├── 02-architecture-decisions.md   # 아키텍처 설계 결정 (동시성, 이벤트, 감사, 모니터링)
│   ├── 03-implementation-roadmap.md   # 구현 로드맵 (16개 태스크, 의존성 그래프)
│   └── 04-implementation-plan.md      # 상세 구현 계획 (TDD 기반 단계별 코드)
├── src/main/kotlin/                   # 84 소스 파일
│   └── com/commerce/
│       ├── common/   (15)  ← BaseEntity, ErrorCode, Audit, Idempotency
│       ├── region/    (9)  ← Region + RegionPolicy + QueryDSL
│       ├── member/    (8)  ← Member + JWT + Security
│       ├── merchant/ (12)  ← Merchant + Settlement + Events
│       ├── voucher/  (19)  ← Voucher + 구매/결제/환불/철회/만료
│       ├── transaction/ (7) ← Transaction + 보상 트랜잭션
│       ├── ledger/    (7)  ← LedgerEntry + 정합성 검증
│       └── config/    (5)  ← Redis, Security, JWT, QueryDSL, Swagger
├── src/test/kotlin/                   # 13 테스트 파일, 62 테스트 케이스
├── docker-compose.yml
└── build.gradle.kts
```
