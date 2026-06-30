# 커머스 결제·프로모션 백엔드

대용량 트래픽에서 결제·정산·쿠폰·포인트의 재무 정합성을 보장하는 커머스 백엔드.
복식부기 원장, 보상 트랜잭션, 분산락 기반 동시성 제어로 **재무 무결성**, **감사 추적성**, **동시성 안전**을 보장한다.

## 커머스 핵심 역량 (전체 구현 완료)

| 축 | 설명 | 주요 기술 |
|---|---|---|
| **결제·정산·쿠폰·포인트 신뢰성** | 복식부기 원장 + 보상 트랜잭션으로 결제·쿠폰·포인트 분개의 재무 정합성 보장. 멱등성 exactly-once. | `LedgerService`, `RedemptionOrchestrator`, `PointEarnService`, `@Idempotent` |
| **대용량 동시성** | Redisson 분산락 + DB 비관적 락 + Redis Lua 예산/한도 카운터 + k6 부하테스트로 동시성 안전 증명. | `VoucherLockManager`, `PromotionBudgetManager` |

---

## 설계 원칙

| 원칙 | 커머스에서의 의미 | 구현 방식 | 핵심 코드 |
|------|----------------|----------|----------|
| **재무 무결성** | 결제·정산·쿠폰·포인트 분개의 재무 정합성 | 모든 금전 변동을 복식부기 원장(DEBIT/CREDIT 2행)으로 기록. 정합성 검증 배치로 캐시 잔액 vs 원장 합산 비교 | `LedgerService.record()` |
| **감사 추적성** | 환불·취소 분쟁의 완벽한 감사 추적 | 취소/환불을 DELETE 대신 보상 트랜잭션으로 처리. 원 거래 불변 보존 | `TransactionCancelService.cancel()` |
| **동시성 안전** | 재고/예산/잔액의 동시성 안전 | Redisson 분산락 + DB 비관적 락 + Redis Lua 원자 카운터. 핫키 동시 결제·예산 경합 테스트로 검증 | `VoucherLockManager`, `PromotionBudgetManager` |

---

## 시스템 아키텍처

Aggregate 중심 모듈러 모놀리스. 10개 모듈, 123개 소스 파일, 약 42개 API 엔드포인트. 각 모듈은 `domain / application / infrastructure / interfaces` 4계층(헥사고날)으로 분리된다.

```
com/commerce/
├── common/       ← BaseEntity, ErrorCode, ApiResponse, RequestTraceFilter, AuditLog, Idempotency, SecurityUtils
├── config/       ← Redis, Security, JWT, QueryDSL, Swagger
├── region/       ← 지자체 (Region + RegionPolicy + QueryDSL)
├── member/       ← 회원 (Member + JWT 인증 + Spring Security)
├── merchant/     ← 가맹점 (Merchant + 승인 플로우 + Settlement + 이의제기)
├── voucher/      ← 상품권 (구매, 결제, 환불, 청약철회, 만료 배치)
├── promotion/    ← 쿠폰/프로모션 (Promotion + Coupon + 결합결제 오케스트레이터)
├── point/        ← 포인트 (PointAccount + PointTransaction + 적립/취소 정합성)
├── transaction/  ← 거래 (Transaction + 보상 트랜잭션 + 취소 API)
└── ledger/       ← 원장 (LedgerEntry + 정합성 검증 + 관리자 조회 API)
```

### 핵심 흐름

```
구매 ──→ 결제(쿠폰 결합 가능) ──→ 추가결제 ──→ 잔액환불(60%+) ──→ 정산(역월, gross)
  │         │      │                          │
  │         │      └── 포인트 적립(T−D의 1%, 동기)   └── 원장 기록 (동기, 같은 DB 트랜잭션)
  │         └── 거래 취소 (보상 트랜잭션 + 포인트 역분개)
  │
  └── 청약철회 (7일 이내 전액 환불)
```

### 모듈 간 의존 관계

```
voucher    ──동기──→ ledger      (잔액 변경 시 원장 기록, 같은 DB 트랜잭션)
voucher    ──동기──→ transaction (거래 생성)
promotion  ──동기──→ voucher/ledger/point (결합 결제: 바우처 차감 + 보조분 분개 + 적립)
point      ──동기──→ ledger      (적립/취소 원장 기록, 같은 DB 트랜잭션)
merchant   ──동기──→ transaction (정산 대상 거래 조회)

비핵심 이벤트 ──outbox→Kafka──→ audit  (비동기, at-least-once, DLT)
```

### 감사 이벤트: Transactional Outbox + Kafka (구현됨)

비핵심(HIGH/MEDIUM) 도메인 이벤트는 **Transactional Outbox + Kafka**로 전달된다. 전달 메커니즘은 킬스위치(`audit.kafka.enabled`)로 교체 가능하다 — prod는 Kafka, 로컬/CI는 직접 relay(브로커 불필요).

```
도메인 이벤트
 ├─ CRITICAL(발행/결제/환불/취소) ─ 동기(BEFORE_COMMIT, 비즈니스 tx) → AuditLog   ← 절대 비동기화 안 함
 └─ HIGH/MEDIUM ─ OutboxRecorder(BEFORE_COMMIT) → outbox_events (같은 tx, 원자적 캡처)
                    → relay(폴링) ─┬─ Kafka 발행 → @KafkaListener → AuditLog   (enabled=true, prod)
                                   └─ 직접 적용 → AuditLog                      (enabled=false, 로컬/CI)
```

| 단계 | 구현 |
|------|------|
| 캡처 | `OutboxRecorder` `@TransactionalEventListener(BEFORE_COMMIT)` → `outbox_events`(비즈니스 tx와 원자적) |
| 전달 | `KafkaOutboxRelay`(폴링 → `KafkaTemplate`) 또는 `DirectOutboxRelay`(직접) — `@ConditionalOnProperty` |
| 적용 | `AuditKafkaConsumer`(`@KafkaListener`) — 멱등(`existsByEventId`), 실패 시 백오프 재시도 후 **DLT(`audit-events.DLT`)** |
| 보장 | at-least-once (outbox=Kafka 안착, 컨슈머 실패는 DLT로 복구 — 무성 유실 없음) |

**원장 기록은 Kafka로 전환하지 않는다 — 동기 유지.** 잔액 변경과 원장 기록은 같은 DB 트랜잭션에서 동기 처리해야 정합성이 보장된다. **CRITICAL 감사도 동기(BEFORE_COMMIT)로 유지**해 "감사 없는 금전 변동"을 원천 차단한다. Kafka는 비핵심 부수효과(감사·알림·분석) 전용이다.

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
- **JWT 인증**: 로그인 시 JWT 토큰 발급. principal은 `memberId: Long`로 도출하며, `SecurityUtils.currentMemberId()`로 컨트롤러가 신원을 조회한다(본문 memberId는 신뢰하지 않음)
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

### 5. 상품권 결제 — 부분 사용 + 쿠폰 결합 (Voucher Redemption)

가맹점에서 상품권으로 결제하는 핵심 기능. 동시성 제어의 핵심 지점이며, 쿠폰이 함께 적용되면 결합 결제로 오케스트레이션된다.

- **부분 사용**: 50,000원 상품권으로 30,000원 결제 → 잔액 20,000원 유지. 여러 번 나누어 사용 가능
- **이중 방어 동시성 제어**:
  1. Redisson 분산락(`voucher:{id}`)으로 1차 직렬화
  2. DB `SELECT FOR UPDATE`로 2차 비관적 락 (Redis 장애 시 방어)
- **상태 자동 전이**: `ACTIVE` → `PARTIALLY_USED` (부분 사용), `PARTIALLY_USED` → `EXHAUSTED` (전액 소진)
- **검증 순서**: 분산락 획득 → DB 락 → 사용 가능 상태 확인 → 만료 여부 확인 → 잔액 확인 → 차감
- **원장 기록(일반 결제)**: 동일 DB 트랜잭션 내에서 `LedgerEntry` 2행 동기 생성 (DEBIT: MERCHANT_RECEIVABLE, CREDIT: VOUCHER_BALANCE)
- **쿠폰 결합 결제** (`RedemptionOrchestrator`): 주문총액 `T`, 할인액 `D`일 때
  - 정준 락 순서 `coupon → voucher → DB tx`로 데드락 방지
  - 예산은 DB 트랜잭션 밖에서 원자 예약(`reserve`)하고, 다운스트림 실패 시 보상 `DECRBY`로 누수 차단
  - 동일 `transactionId`를 공유하는 **균형 2-leg 2쌍** 분개:
    - 쌍1 바우처 결제분: DEBIT MERCHANT_RECEIVABLE / CREDIT VOUCHER_BALANCE (`T−D`, REDEMPTION)
    - 쌍2 플랫폼 보조분: DEBIT MERCHANT_RECEIVABLE / CREDIT PROMOTION_FUNDING (`D`, COUPON_SUBSIDY)
  - **가맹점은 gross `T`로 정산**(거래 amount = T). 금액 0인 leg(전액 쿠폰 보전 `T−D=0` 또는 `D=0`)는 생략해도 순잔액·정합성 동일
- **포인트 적립**: 결제 트랜잭션 내부에서 실제 결제액(`T−D`)의 1%를 동기 적립(전액 쿠폰 보전 시 zero-guard로 생략)
- **메트릭**: 결제 처리 시간(`voucher.redemption.duration` / `coupon.redemption.duration`), 성공/실패 건수
- **멱등키 적용**: 네트워크 타임아웃으로 인한 중복 결제 방지

### 6. 쿠폰·프로모션 (Promotion & Coupon)

플랫폼이 출연하는 할인 캠페인. 예산을 초과하지 않으면서 동시 발급·사용을 안전하게 처리한다.

- **프로모션 생성**: 할인 방식(정액/정률), 최소 주문액(`minSpend`), 1인 사용 한도(`perMemberLimit`), 예산 한도(`budgetLimit`), 유효기간
- **쿠폰 발급**: `POST /promotions/{id}/coupons` — 회원에게 쿠폰 1매 발급(멱등). 상태 `ISSUED` → `REDEEMED` / `EXPIRED`
- **Redis-Lua 예산 원자 제어** (`PromotionBudgetManager`): `INCRBY` + 한도 검증 + 초과 시 `DECRBY` 롤백을 단일 Lua 스크립트로 원자 수행. 진실의 원천은 `CouponRedemption.discountAmount` 합계이며, 재동기화 잡이 Redis 카운터를 보정
- **결합 결제 분개**: 위 5절 참조 — 가맹점 gross 정산 + 플랫폼 보조분(PROMOTION_FUNDING) 분리 기록
- **사용 검증**: 만료/중복 사용/최소 주문액/1인 한도/소유자 일치를 결제 전·락 내부에서 이중 확인

### 7. 포인트 적립 (Point)

결제 시 적립되는 멤버십 포인트. 결제 트랜잭션과 같은 DB 트랜잭션에서 동기 적립하여 정합성을 보장한다.

- **계정/원장 모델**: `PointAccount`(회원별 잔액 캐시) + `PointTransaction`(EARN/CANCEL 이력, 불변)
- **동기 적립**: 결제 시 실제 결제액의 1%(`point.earn-rate=0.01`, 1원 단위 HALF_UP)를 적립. 동일 redemption `transactionId`를 공유
- **원장 기록**: DEBIT POINT_BALANCE / CREDIT POINT_FUNDING (POINT_EARN)
- **동시성**: `INSERT IGNORE`로 계좌 행 보장 후 `SELECT FOR UPDATE`로 동일 회원 동시 적립 직렬화 (갭 락 데드락 회피)
- **전역 정합성**: `POINT_BALANCE`의 원장 net == Σ(회원 잔액). 정합성 테스트로 검증
- **취소 시 역분개**: 결제 취소되면 원 EARN을 수정하지 않고 CANCEL 보상 트랜잭션으로 역분개(DEBIT POINT_FUNDING / CREDIT POINT_BALANCE) → net 0 복귀

### 8. 잔액 환불 (Balance Refund)

지역사랑상품권 고유 정책: 60% 이상 사용한 상품권의 잔액을 현금으로 환불.

- **환불 조건 검증**: `usageRatio = (faceValue - balance) / faceValue ≥ 0.60` (Region 정책에서 비율 조회)
- **상태 전이**: `PARTIALLY_USED` → `REFUND_REQUESTED` → `REFUNDED`
- **잔액 처리**: 잔액 전액을 환불하고 balance를 0으로 설정
- **원장 기록**: DEBIT: REFUND_PAYABLE, CREDIT: VOUCHER_BALANCE
- **분산락**: 환불 처리 중 결제 요청 경합 방지 (`voucher:{id}` 락)
- **경계값 검증**: 59% → 거절, 60% → 허용, 61% → 허용 (통합 테스트로 검증)

### 9. 청약철회 (Withdrawal)

전자상거래법에 따른 구매 후 7일 이내 전액 환불. 잔액환불과는 별개의 독립 프로세스.

- **철회 조건**: `ACTIVE` 상태(미사용) + 구매 후 7일 이내 (`purchasedAt + 7일 ≥ now`)
- **잔액환불과의 차이**: 청약철회는 미사용 상품권의 전액 환불, 잔액환불은 60%+ 사용 상품권의 잔액 환불
- **상태 전이**: `ACTIVE` → `WITHDRAWAL_REQUESTED` → `WITHDRAWN`
- **원장 기록**: DEBIT: REFUND_PAYABLE, CREDIT: VOUCHER_BALANCE (전액)
- **경계값 검증**: 7일째 → 허용, 8일째 → 거절 (통합 테스트로 검증)

### 10. 거래 취소 — 보상 트랜잭션 (Compensating Transaction)

기존 거래를 **삭제하거나 수정하지 않고** 역방향 보상 트랜잭션을 생성하여 취소 처리.

- **보상 트랜잭션 생성**: `TransactionType.CANCELLATION` + `originalTransactionId`로 원 거래 연결
- **역방향 원장**: 원 거래의 차변/대변을 반대로 기록 (DEBIT: VOUCHER_BALANCE, CREDIT: MERCHANT_RECEIVABLE)
- **잔액 복원**: `voucher.restoreBalance(amount)` — 취소된 금액만큼 잔액 증가, 상태 자동 복귀
- **포인트 역분개**: 원 결제가 적립한 포인트를 CANCEL 트랜잭션으로 역분개(net 0 복귀)
- **원 거래 상태**: `COMPLETED` → `CANCEL_REQUESTED` → `CANCELLED` (원 거래의 금액/내용은 불변)
- **감사 추적성**: 원 거래 + 보상 거래가 모두 원장에 보존되어, 감사 시 "왜 금액이 변경되었는가"를 증명 가능
- **정산 자동 반영**: 취소된 거래는 `CANCELLED` 상태이므로 정산 시 `COMPLETED` 조건에서 자동 제외

### 11. 만료 처리 배치 (Expiry Scheduler)

유효기간이 지난 상품권을 자동으로 만료 처리하고, 잔액을 만료 계정으로 이동.

- **스케줄링**: `@Scheduled(cron = "0 */5 * * * *")` — 5분마다 실행
- **건별 독립 트랜잭션**: `@Transactional(propagation = REQUIRES_NEW)` — 1건 실패해도 나머지 정상 처리
- **경합 방지**: 만료 대상 ID 목록을 먼저 조회 → 건별로 `SELECT FOR UPDATE` 후 처리
- **이중 체크**: 락 획득 후 `isUsable()` 재확인 (조회 ~ 락 사이에 다른 결제가 처리될 수 있음)
- **원장 기록**: 잔액이 남아있는 경우 DEBIT: EXPIRED_VOUCHER, CREDIT: VOUCHER_BALANCE
- **balance 초기화**: 만료 시 잔액을 0으로 설정

### 12. 가맹점 정산 (Settlement)

가맹점이 받을 정산 금액을 기간별로 계산하고 확정하는 프로세스.

- **정산 금액 계산**: 해당 기간의 `COMPLETED` 상태 결제 합산(거래 amount = gross `T`). 취소된 거래는 `CANCELLED` 상태이므로 자동 제외
- **정산 주기**: Region 단위로 설정 (일/주/월), 역월 기준 (KST)
- **중복 방지**: `(merchantId, periodStart, periodEnd)` Unique Constraint
- **이의 제기 플로우**: `PENDING` → `DISPUTED` (사유 기록) → `CONFIRMED` (이의 해결)
- **정산 확정 이벤트**: `SettlementConfirmedEvent` 발행 → HIGH 감사 로그 자동 기록
- **상태 머신**: `PENDING` → `CONFIRMED` → `PAID`, `PENDING` → `DISPUTED` → `CONFIRMED`

### 13. 복식부기 원장 및 정합성 검증 (Ledger & Verification)

프로젝트의 가장 핵심적인 재무 무결성 보장 체계.

- **2행 모델**: 모든 금전 변동마다 DEBIT 1행 + CREDIT 1행 = 2행의 `LedgerEntry` 생성
- **불변 엔티티**: `@Immutable` 어노테이션으로 Hibernate의 UPDATE/DELETE 원천 차단
- **계정 코드 체계(10종)**: `MEMBER_CASH`, `VOUCHER_BALANCE`, `MERCHANT_RECEIVABLE`, `REVENUE_DISCOUNT`, `EXPIRED_VOUCHER`, `REFUND_PAYABLE`, `SETTLEMENT_PAYABLE`, `PROMOTION_FUNDING`, `POINT_BALANCE`, `POINT_FUNDING`
- **동기 기록 원칙**: 이벤트 리스너가 아닌 서비스 내 직접 호출로 잔액 변경과 같은 DB 트랜잭션에서 처리
- **정합성 검증 배치** (`LedgerVerificationService`):
  - 글로벌 검증: `sum(전체 DEBIT) == sum(전체 CREDIT)`
  - 계정별 검증: 캐시 잔액(Voucher.balance, PointAccount.balance) vs 해당 계정 원장 net 비교
  - 불일치 발견 시: **자동 수정하지 않음** — CRITICAL 감사 로그 + 관리자 알림 (사람이 판단)
- **관리자 조회 API**: 거래별 원장 조회, 계정별 잔액 조회, 글로벌 차대변 조회, 수동 정합성 검증 트리거

> 분개 표·계정별 차변정상/대변정상 등 회계 상세는 [`docs/03-financial-design.md`](docs/03-financial-design.md) 참조.

### 14. 멱등키 처리 (Idempotency)

네트워크 불안정으로 인한 중복 요청을 안전하게 처리하는 체계.

- **대상 API**: 구매, 결제, 환불, 청약철회, 거래 취소, 쿠폰 발급 (금전/과금 관련 전체)
- **이중 저장**: Redis(1차, 빠른 감지) + DB(2차, Redis 장애 대비) — Redis-fallback 구조
- **응답 보존**: 원래 응답 본문 + HTTP 상태코드를 함께 캐시하여 중복 요청 시 동일 응답 반환
- **자동 캡처**: `@Idempotent` 어노테이션으로 컨트롤러 코드 수정 없이 적용
- **`Idempotency-Key` 헤더**: 클라이언트가 UUID 기반 멱등키를 요청마다 전달

### 15. 감사 로그 체계 (Audit Log)

모든 비즈니스 이벤트를 등급별로 기록하는 감사 추적 시스템.

- **자동 기록**: 도메인 이벤트 발행 시 `AuditEventListener`가 자동으로 감사 로그 생성
- **등급별 처리**:
  - `CRITICAL` (발행/결제/환불/취소): `BEFORE_COMMIT` — 감사 실패 시 비즈니스 트랜잭션도 롤백
  - `HIGH/MEDIUM` (승인/수정/만료/정산확정): `AFTER_COMMIT` + `REQUIRES_NEW` — 별도 트랜잭션
- **실패 복원**: `AFTER_COMMIT` 리스너 실패 시 `FailedEvent` 테이블에 기록 → 스케줄러가 자동 재처리
- **상태 추적**: `previousState`/`currentState`를 JSON으로 저장하여 변경 전후 상태 비교 가능

### 공통 횡단 관심사 (Cross-cutting)

- **표준 응답 봉투** `ApiResponse<T>`: 모든 응답을 `{ success, data, error }`로 일관 래핑
- **요청 추적** `RequestTraceFilter`: `X-Request-Id` 헤더를 받아 MDC `requestId`로 전 로그에 전파(없으면 생성). 로그 패턴에 `[%X{requestId}]` 포함
- **JWT 인증**: principal = `memberId: Long`, `SecurityUtils`로 컨트롤러가 신원 조회(본인 자원 인가 검사)
- **DB 마이그레이션**: Flyway(`ddl-auto: validate`) — 스키마는 마이그레이션 스크립트가 단일 진실의 원천

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

Voucher/PointAccount에 `balance` 캐시 필드를 두되, 진실의 원천은 항상 원장이다. `LedgerVerificationService`가 캐시 잔액과 원장 net을 비교하여 불일치를 탐지한다.

### 2. 왜 쿠폰 결합 결제를 gross 정산 + 보조분 분리로 분개하는가

가맹점은 할인과 무관하게 **주문 총액(gross `T`)**을 받아야 하고, 할인분(`D`)은 플랫폼이 출연한다. 이를 하나의 거래로 묶으면 누가 얼마를 부담했는지 추적할 수 없다.

```
쌍1 (회원 결제분):  DEBIT MERCHANT_RECEIVABLE / CREDIT VOUCHER_BALANCE   = T − D
쌍2 (플랫폼 보조분): DEBIT MERCHANT_RECEIVABLE / CREDIT PROMOTION_FUNDING = D
─────────────────────────────────────────────────────────────────────────
가맹점 미수금 합계 = T (gross 정산),  플랫폼 출연 = D (PROMOTION_FUNDING 대변 누적)
```

동일 `transactionId`를 공유하므로 거래 단위로 두 부담 주체를 분리 추적할 수 있고, 차대변은 항상 균형을 이룬다.

### 3. 왜 보상 트랜잭션인가

거래 취소를 DELETE나 상태 변경으로 구현하면 "왜 이 금액이 변경되었는가"를 증명할 수 없다. 원 거래를 수정하지 않고 역방향 엔트리(바우처 + 포인트)를 생성하여 원 거래를 불변 보존한다.

### 4. 왜 분산락 + DB 비관적 락 이중 방어인가, 그리고 정준 락 순서

동일 상품권 동시 결제는 잔액 초과 차감이라는 치명적 사고를 유발한다. 분산락(1차) → DB `SELECT FOR UPDATE`(2차) → 커밋 → 락 해제 순서로 다른 스레드가 항상 커밋된 데이터를 읽도록 보장한다.

쿠폰 결합 결제는 자원이 둘(coupon, voucher)이므로 **정준 락 순서 `coupon → voucher`**를 강제하여 교차 데드락을 방지한다. 핫키 동시 결제·쿠폰 예산 경합 테스트로 검증한다.

### 5. 왜 프로모션 예산은 Redis-Lua 원자 카운터인가

다수 회원이 동시에 한 프로모션 쿠폰을 사용하면 예산 초과(over-spend)가 발생할 수 있다. `INCRBY` + 한도 검증 + 초과 시 `DECRBY` 롤백을 **단일 Lua 스크립트**로 원자 수행하여 경합 없이 예산 상한을 보장한다. 예산 예약은 DB 트랜잭션 밖에서 수행하고, 다운스트림 DB 실패 시 보상 `DECRBY`로 누수를 막는다. 진실의 원천은 `CouponRedemption.discountAmount` 합계이며 재동기화 잡이 Redis를 보정한다.

### 6. 왜 포인트 적립은 결제와 동기인가

적립을 비동기로 처리하면 결제 성공 후 적립 누락/중복이 발생할 수 있다. 적립은 결제와 **같은 DB 트랜잭션, 같은 `transactionId`**로 처리하여 `POINT_BALANCE` net == Σ잔액 불변식을 보장한다. 결제가 롤백되면 적립도 함께 롤백된다.

### 7. 왜 멱등키 이중 저장인가

Redis로 빠르게 중복을 감지하고, DB에도 저장하여 Redis 장애 후에도 중복을 방지한다. 중복 감지 시 409가 아닌 **원래 응답을 원래 상태코드와 함께 반환**하여 클라이언트가 정상 흐름을 이어갈 수 있도록 한다.

### 8. 왜 원장 기록은 이벤트가 아닌 동기 호출인가

`@TransactionalEventListener(AFTER_COMMIT)`으로 원장을 기록하면 커밋 후 리스너 실행 전 장애 시 **원장 누락**이 발생한다. 잔액 변경과 원장 기록은 반드시 같은 DB 트랜잭션에서 동기적으로 처리한다. 이벤트는 감사 로그, 알림 등 비핵심 부수효과에만 사용한다.

---

## 동시성 제어 전략

| 작업 | 전략 | 방지하는 장애 |
|------|------|-------------|
| 상품권 결제 | Redisson 분산락 + DB 비관적 락 + TransactionTemplate | 이중 사용, 잔액 초과 차감, 락-커밋 역전 |
| 쿠폰 결합 결제 | 정준 락 순서(coupon→voucher) + Redis-Lua 예산 예약 + 보상 DECRBY | 데드락, 예산 초과, 예산 누수 |
| 상품권 발행 | Member 분산락 + Region Redis Lua 스크립트 + TransactionTemplate | 한도 초과 발행, 데드락 |
| 포인트 적립/취소 | `SELECT FOR UPDATE`(회원별) + `INSERT IGNORE` 행 보장 | 동시 적립 경합, 갭 락 데드락 |
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

### 전달: Outbox + Kafka (CRITICAL은 동기)

비핵심(HIGH/MEDIUM) 감사는 **Transactional Outbox → Kafka(`@KafkaListener`)**로 전달되고(킬스위치로 직접 relay 대체 가능), 실패 시 DLT로 보존된다. CRITICAL 감사는 비즈니스 트랜잭션과 같은 tx(`BEFORE_COMMIT`)에서 동기 기록한다. 상세는 위 ["감사 이벤트: Transactional Outbox + Kafka"](#감사-이벤트-transactional-outbox--kafka-구현됨) 참조.

---

## API 엔드포인트 (약 42개, 11개 컨트롤러)

모든 응답은 `ApiResponse<T>`(`{ success, data, error }`)로 래핑된다. (인증) = JWT 필요, (멱등) = `Idempotency-Key` 지원.

| 모듈 | Method | Endpoint | 설명 |
|------|--------|----------|------|
| **인증** | GET | `/api/v1/me` | 현재 인증 회원 확인 (인증) |
| **회원** | POST | `/api/v1/members/register` | 회원 가입 |
| | POST | `/api/v1/members/login` | 로그인 (JWT) |
| | GET | `/api/v1/members/{id}` | 회원 조회 |
| | POST | `/api/v1/members/{id}/suspend` `/unsuspend` `/withdraw` | 정지/해제/탈퇴 |
| **포인트** | GET | `/api/v1/members/{memberId}/points` | 포인트 잔액 (인증, 본인만) |
| **회원쿠폰** | GET | `/api/v1/members/{memberId}/coupons` | 보유 쿠폰 목록 (인증, 본인만) |
| **상품권** | GET | `/api/v1/vouchers` | 목록 조회 — 인증·본인 스코프(ADMIN 전체), QueryDSL + 페이지네이션 |
| | GET | `/api/v1/vouchers/{id}` | 단건 조회 (인증·소유자, ADMIN 전체) |
| | POST | `/api/v1/vouchers/purchase` | 구매 (인증, 멱등) |
| | POST | `/api/v1/vouchers/{id}/redeem` | 결제 — 쿠폰 결합 가능 (인증·소유자, 멱등) |
| | POST | `/api/v1/vouchers/{id}/refund` | 잔액 환불 (인증·소유자, 멱등) |
| | POST | `/api/v1/vouchers/{id}/withdraw` | 청약철회 (인증·소유자, 멱등) |
| **프로모션** | POST | `/api/v1/promotions` | 프로모션 생성 (인증) |
| | GET | `/api/v1/promotions/{id}` | 프로모션 조회 |
| | POST | `/api/v1/promotions/{id}/coupons` | 쿠폰 발급 (인증, 멱등) |
| **지자체** | POST | `/api/v1/regions` | 생성 |
| | GET | `/api/v1/regions` · `/{id}` | 목록 · 단건 조회 |
| | PUT | `/api/v1/regions/{id}/policy` | 정책 수정 |
| | POST | `/api/v1/regions/{id}/suspend` `/activate` | 중지/재개 |
| **거래** | GET | `/api/v1/transactions/{id}` | 거래 조회 |
| | POST | `/api/v1/transactions/{id}/cancel` | 거래 취소 (멱등) |
| **가맹점** | POST | `/api/v1/merchants` | 등록 |
| | GET | `/api/v1/merchants/{id}` | 조회 |
| | POST | `/api/v1/merchants/{id}/approve` `/reject` `/suspend` `/unsuspend` `/terminate` | 심사/운영 상태 전이 |
| **정산** | POST | `/api/v1/settlements/calculate` | 정산 생성 |
| | POST | `/api/v1/settlements/{id}/confirm` `/dispute` | 확정 / 이의 제기 |
| | GET | `/api/v1/settlements/{id}` | 조회 |
| **원장(admin)** | GET | `/api/v1/admin/ledger/entries/transaction/{id}` | 거래별 원장 조회 |
| | GET | `/api/v1/admin/ledger/balance/{account}` · `/balance/global` | 계정별 · 글로벌 잔액 |
| | POST | `/api/v1/admin/ledger/verify` | 정합성 검증 실행 |

Swagger UI: `http://localhost:8080/swagger-ui.html`

---

## 기술 스택

| 항목 | 기술 |
|------|------|
| Language | Kotlin 1.9.23 / Java 17 |
| Framework | Spring Boot 3.2.5 |
| ORM | Spring Data JPA + QueryDSL 5.1.0 |
| DB | MySQL 8 + Flyway (V1→V4, `ddl-auto: validate`) |
| Cache / Lock | Redis 7 (Redisson 3.27.2) |
| Events | Spring `ApplicationEventPublisher` + **Kafka** (감사 Transactional Outbox 전달, DLT). 킬스위치로 브로커 없이도 동작 |
| Auth | JWT (jjwt 0.12.5) + Spring Security |
| Test | JUnit 5 + Kotest 5.8.1 + MockK 1.13.10 + Testcontainers 1.19.7 |
| Monitoring | Spring Actuator + Micrometer (Prometheus / Grafana) |
| API Docs | springdoc-openapi 2.4.0 (Swagger UI) |
| Build | Gradle 8 (Kotlin DSL) |
| Infra | Docker Compose (MySQL + Redis + Kafka + Prometheus + Grafana), Dockerfile, GitHub Actions CI |

---

## 실행 방법

```bash
# 1. 전체 인프라 실행 (MySQL + Redis + Kafka + Prometheus + Grafana)
docker compose up -d

# 2. 애플리케이션 실행
./gradlew bootRun

# 3. 테스트 실행 (Testcontainers가 MySQL/Redis를 자동 구동)
./gradlew test
```

- **외부화된 환경변수**: `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD`, `REDIS_HOST/REDIS_PORT`, `JWT_SECRET`, `KAFKA_BOOTSTRAP_SERVERS`, `AUDIT_KAFKA_ENABLED`(기본 `true`; 브로커 없이 실행하려면 `false` → 직접 relay).

| 서비스 | URL | 설명 |
|--------|-----|------|
| Swagger UI | http://localhost:8080/swagger-ui.html | API 문서 |
| Grafana | http://localhost:3000 (admin/admin) | 모니터링 대시보드 |
| Prometheus | http://localhost:9090 | 메트릭 직접 쿼리 |

### 모니터링 대시보드

`docker compose up -d` 시 Prometheus + Grafana가 자동 구성된다. Grafana 대시보드로 결제 처리량/지연(p50·p95·p99), 분산락 획득 시간, Redis Fallback, **원장 정합성(0이 아니면 즉시 조사)**, JVM/HTTP 지표를 실시간 모니터링한다. `load-test/`의 k6 시나리오로 부하를 주입하면 동일 대시보드에서 스케일 거동을 관찰할 수 있다.

---

## 테스트 (146건, 0 실패)

모든 통합/동시성 테스트는 Testcontainers(MySQL + Redis) 위에서 `IntegrationTestSupport` / `TestFixtures`로 실제 인프라를 구동하여 검증한다.

| 분류 | 검증 내용 |
|------|----------|
| **단위 (도메인/원장)** | Voucher/Merchant/Member/Region/Coupon/Promotion/PointAccount 상태머신·전이, Luhn 코드 생성, 계정코드·엔트리타입, 복식부기 2행·글로벌 균형 |
| **통합 (E2E/경계값/만료)** | 전체 lifecycle(구매→결제→환불→정산→감사), 환불 60% 경계(59/60/61%)·청약철회 7일 경계, 만료 배치 + 원장 균형 |
| **동시성** | 핫키 동시 결제(잔액≥0, 정확히 N건 성공), 쿠폰 예산 동시 경합(over-spend 0) |
| **멱등성** | 동시 중복 요청 시 단일 처리 + 동일 응답(결제·쿠폰) |
| **포인트** | 적립/취소 정합성, 롤백, 전역 정합성(POINT_BALANCE net == Σ잔액) |
| **쿠폰** | 결합 결제(gross 정산 + 보조분 분개), 취소 역분개, 예산 제어 |

### 핵심 테스트 시나리오

**동시성 안전 (핫키 결제):**
```
50,000원 상품권 × 10,000원 결제 × 동시 요청 →
  성공 5건 + 실패(INSUFFICIENT_BALANCE) → 잔액 = 0원 (음수 불가 불변식)
  원장 차대변 균형
```

**쿠폰 결합 결제 분개:**
```
주문 T = 30,000, 할인 D = 5,000 →
  쌍1: MERCHANT_RECEIVABLE / VOUCHER_BALANCE   = 25,000 (T−D)
  쌍2: MERCHANT_RECEIVABLE / PROMOTION_FUNDING =  5,000 (D)
  가맹점 gross 정산 = 30,000, 포인트 적립 = 250 (T−D의 1%)
```

**보상 트랜잭션:**
```
결제(30,000원) → 취소 →
  원 거래: CANCELLED (수정하지 않음) / 보상 거래: CANCELLATION + originalTransactionId
  역방향 원장(바우처) + 포인트 역분개 → 잔액·포인트 net 복원
```

---

## 프로젝트 구조

```
commerce-platform/
├── docs/
│   ├── 01-domain-design.md            # 도메인 & 비즈니스 규칙 (엔티티, 상태머신, 불변식)
│   ├── 02-architecture-decisions.md   # 아키텍처 설계 결정 (동시성, 이벤트, 감사, 모니터링)
│   └── 03-financial-design.md         # 재무 설계 (복식부기 계정·분개·정합성)
├── src/main/kotlin/com/commerce/      # 123 소스 파일
│   ├── common/      ← BaseEntity, ApiResponse, RequestTraceFilter, Audit, Idempotency, Security
│   ├── config/      ← Redis, Security, JWT, QueryDSL, Swagger
│   ├── region/      ← Region + RegionPolicy + QueryDSL
│   ├── member/      ← Member + JWT + Security
│   ├── merchant/    ← Merchant + Settlement + Events
│   ├── voucher/     ← Voucher + 구매/결제/환불/철회/만료
│   ├── promotion/   ← Promotion + Coupon + RedemptionOrchestrator
│   ├── point/       ← PointAccount + PointTransaction + 적립/취소
│   ├── transaction/ ← Transaction + 보상 트랜잭션
│   └── ledger/      ← LedgerEntry + 정합성 검증
├── src/main/resources/db/migration/   # Flyway V1__baseline → V4__point_cancel
├── src/test/kotlin/                   # 42 테스트 파일, 146 테스트 케이스
├── load-test/                         # k6 4 시나리오 + Prometheus/Grafana 모니터링 스택
├── .github/workflows/ci.yml           # GitHub Actions CI
├── Dockerfile
├── docker-compose.yml
└── build.gradle.kts
```
