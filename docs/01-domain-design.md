# 1단계 — 도메인 및 비즈니스 규칙

> ⚠️ **커머스 전환(pivot) 진행 중** — 이 문서는 지역상품권(voucher/region) 시절 설계 기준이다.
> 최신 도메인/아키텍처는 `seller · product · inventory · cart · order` 중심으로 재편됐다.
> **현행 도메인 목록·역할·흐름은 [`../README.md`](../README.md)를 우선 참조**하라. voucher/region/audit 상세는 제거 예정이며, 이 문서 상세 재작성은 pivot 완료(Phase 4c) 후 진행한다.

## 설계 결정 요약

| 항목 | 결정 |
|------|------|
| 사용 방식 | 부분 사용 + 잔액 환불 (60% 이상 사용 시) + 청약철회 (7일 이내 미사용) |
| 지역 범위 | 다중 지역 (Region 1급 엔티티) |
| 액터 | 시민(User) + 가맹점(Merchant) + 관리자(Admin) 3자 |
| 재무 모델 | 하이브리드 복식부기 (balance 캐시 + Ledger 원장) |
| 모듈 구조 | Aggregate 중심 모듈러 모놀리스 |

---

## 1. 핵심 도메인 엔티티 및 생애주기 상태

### 1.1 Region (지자체)

```
상태: ACTIVE → SUSPENDED → ACTIVE (복원 가능)
                → DEACTIVATED (종료)

ACTIVE ──────→ SUSPENDED (운영 중지)
  ↑                │
  └────────────────┘ (운영 재개)
SUSPENDED ───→ DEACTIVATED (완전 종료)
```

- 소유 정보: 지자체명, 할인율, 1인 구매한도, 월 발행한도, 잔액환불 기준비율(기본 60%), 사용제한 업종 목록
- 정산 주기: Region 단위로 설정 (SettlementPeriod: DAILY/WEEKLY/MONTHLY, 역월 기준, KST 타임존)
- RegionStatus와 SettlementPeriod는 `RegionStatus.kt` 파일에 함께 정의

### 1.2 Member (시민 회원)

```
상태: PENDING → ACTIVE → SUSPENDED → ACTIVE (복원)
                                   → WITHDRAWN (탈퇴)

PENDING ──→ ACTIVE (본인인증 완료)
ACTIVE ───→ SUSPENDED (관리자 정지)
SUSPENDED ─→ ACTIVE (정지 해제)
SUSPENDED ─→ WITHDRAWN (탈퇴 처리)
ACTIVE ───→ WITHDRAWN (자진 탈퇴)
```

### 1.3 Merchant (가맹점)

```
상태: PENDING_APPROVAL → APPROVED → SUSPENDED → APPROVED (복원)
                                               → TERMINATED (해지)
      PENDING_APPROVAL → REJECTED → PENDING_APPROVAL (재신청)

PENDING_APPROVAL ──→ APPROVED (심사 승인)
PENDING_APPROVAL ──→ REJECTED (심사 거절)
REJECTED ──────────→ PENDING_APPROVAL (보완 후 재신청 — 새 레코드 생성)
APPROVED ──────────→ SUSPENDED (운영 정지)
SUSPENDED ─────────→ APPROVED (정지 해제)
SUSPENDED ─────────→ TERMINATED (해지)
APPROVED ──────────→ TERMINATED (자진 해지)
```

- Region에 귀속. 하나의 가맹점은 하나의 Region에만 소속
- REJECTED 가맹점의 재신청: 기존 REJECTED 레코드는 보존하고, 새로운 Merchant 레코드를 PENDING_APPROVAL로 생성 (감사 추적성 유지)

### 1.4 Voucher (상품권)

```
상태: ACTIVE → PARTIALLY_USED → EXHAUSTED
      ACTIVE → EXPIRED
      ACTIVE → WITHDRAWAL_REQUESTED → WITHDRAWN (청약철회, 7일 이내 미사용)
      PARTIALLY_USED → REFUND_REQUESTED → REFUNDED (잔액 환불, 60%+ 사용)
      PARTIALLY_USED → EXHAUSTED
      PARTIALLY_USED → EXPIRED

ACTIVE ──────────→ PARTIALLY_USED (부분 사용)
ACTIVE ──────────→ EXHAUSTED (전액 사용)
ACTIVE ──────────→ EXPIRED (유효기간 만료)
ACTIVE ──────────→ WITHDRAWAL_REQUESTED (청약철회 요청, 구매 후 7일 이내 미사용)
PARTIALLY_USED ──→ PARTIALLY_USED (추가 부분 사용)
PARTIALLY_USED ──→ EXHAUSTED (잔액 전부 사용)
PARTIALLY_USED ──→ EXPIRED (유효기간 만료)
PARTIALLY_USED ──→ REFUND_REQUESTED (잔액 환불 요청, 60%+ 사용)
REFUND_REQUESTED → REFUNDED (환불 완료)
REFUND_REQUESTED → PARTIALLY_USED (환불 거절, 원상복귀)
WITHDRAWAL_REQUESTED → WITHDRAWN (청약철회 완료, 전액 환불)
WITHDRAWAL_REQUESTED → ACTIVE (청약철회 거절, 원상복귀)
```

- 구매 완료 시 ACTIVE 상태로 직접 생성 (결제 확인이 완료된 시점에 Voucher 레코드 생성)
- 핵심 필드: `faceValue`(액면가), `balance`(캐시 잔액), `purchasedAt`, `expiresAt`, Region 귀속
- 청약철회(WITHDRAWAL)와 잔액환불(REFUND)은 별개 프로세스:
  - 청약철회: 전자상거래법에 따른 구매 후 7일 이내 전액 환불 (미사용 상태에서만 가능)
  - 잔액환불: 60% 이상 사용 시 잔액 현금 환불 (지역사랑상품권 고유 정책)

### 1.5 상품권 코드 생성 전략

- 형식: `{지역코드 2자리}-{16자리 영숫자}` (예: `SN-A3K9M2X7P1B4Q8R5`)
- 생성: `SecureRandom` 기반 비예측 난수
- 유일성: DB UNIQUE 제약 + 충돌 시 재생성 (충돌 확률: 36^16 ≈ 무시 가능)
- 검증 자릿수: 마지막 1자리는 Luhn mod 36 체크 디짓 (오프라인 검증용)

### 1.6 LedgerEntry (원장 엔트리)

```
상태: 없음 (불변, 추가 전용)
```

- 한 번 기록되면 수정/삭제 불가 (Immutable)
- 필드: `accountCode`(AccountCode), `side`(LedgerEntrySide: DEBIT/CREDIT), `amount`, `transactionId`, `entryType`(LedgerEntryType), `createdAt`
- entryType: `PURCHASE`, `REDEMPTION`, `REFUND`, `WITHDRAWAL`, `EXPIRY`, `SETTLEMENT`, `CANCELLATION`, `MANUAL_ADJUSTMENT`, `COUPON_SUBSIDY`(쿠폰 보조 분개), `POINT_EARN`(포인트 적립 분개)
- `MANUAL_ADJUSTMENT`: 관리자 승인 필수, 사유 필드(reason) 필수 입력, 생성 시 반드시 CRITICAL 감사 로그 동반

**계정 코드 (AccountCode) 전체 목록** — 복식부기 상세 분개는 `03-financial-design.md` 참조.

| 코드 | 설명 | 정상잔액 |
|------|------|:-------:|
| `MEMBER_CASH` | 회원 현금 (충전/환불 기준점) | 차변 |
| `VOUCHER_BALANCE` | 상품권 잔액 (발행 부채) | 차변 |
| `MERCHANT_RECEIVABLE` | 가맹점 미수금 (결제 후 정산 전) | 차변 |
| `REVENUE_DISCOUNT` | 할인 수익 (지자체 보조 처리) | 대변 |
| `EXPIRED_VOUCHER` | 만료 상품권 귀속 수익 | 대변 |
| `REFUND_PAYABLE` | 환불 미지급금 | 대변 |
| `SETTLEMENT_PAYABLE` | 정산 미지급금 (가맹점 지급 전) | 대변 |
| `PROMOTION_FUNDING` | 프로모션 출연금 (쿠폰 보조 누적) | 대변 |
| `POINT_BALANCE` | 포인트 잔액 (회원 적립 자산) | 차변 |
| `POINT_FUNDING` | 포인트 출연금 (적립 비용 누적) | 대변 |

### 1.7 Transaction (거래)

```
상태: PENDING → COMPLETED
      PENDING → FAILED
      COMPLETED → CANCEL_REQUESTED → CANCELLED

PENDING ────────→ COMPLETED (정상 처리)
PENDING ────────→ FAILED (처리 실패)
COMPLETED ──────→ CANCEL_REQUESTED (취소 요청)
CANCEL_REQUESTED → CANCELLED (취소 완료)
```

- 모든 금전 변동의 단위. 상품권 사용, 환불, 정산 등 각각이 하나의 Transaction
- 하나의 Transaction에 2개의 LedgerEntry(차변/대변)가 반드시 쌍으로 생성
- 원장 기록은 이벤트 리스너가 아닌 **서비스 내 동기 호출**로 동일 DB 트랜잭션에서 처리 (I2, I3 보장)
- TransactionType: `PURCHASE`, `REDEMPTION`, `REFUND`, `WITHDRAWAL`, `EXPIRY`, `CANCELLATION`, `SETTLEMENT`
- TransactionStatus와 TransactionType은 `TransactionStatus.kt` 파일에 함께 정의

### 1.8 Settlement (정산)

```
상태: PENDING → CONFIRMED → PAID
      PENDING → DISPUTED

PENDING ───→ CONFIRMED (정산 금액 확정)
CONFIRMED ─→ PAID (실제 지급 완료)
PENDING ───→ DISPUTED (이의 제기)
DISPUTED ──→ CONFIRMED (이의 해결)
```

- 정산 주기: Region 단위로 설정 (RegionPolicy.settlementPeriod)
- 기간 경계: 역월 기준 (예: 3/1 00:00 KST ~ 3/31 23:59:59 KST)
- 타임존: KST (Asia/Seoul) 고정 — 국내 전용 시스템

### 1.9 Promotion / Coupon 도메인

**Promotion (프로모션)**

```
상태: DRAFT → ACTIVE → PAUSED
                     → ENDED
```

- 필드: `name`, `discountType`(FIXED 정액/PERCENTAGE 정률), `discountValue`, `minSpend`(최소 결제금액), `perMemberLimit`(회원당 사용 한도), `budgetLimit`(예산 한도), `startsAt`, `endsAt`, `stackable`(현재 false 고정 — 단일 쿠폰 정책)
- 할인액 산정: FIXED → `discountValue`; PERCENTAGE → `orderTotal × rate / 100` (원단위 내림, 과할인 방지). 과할인 클램프 `D = min(D, T)`는 오케스트레이터(RedemptionOrchestrator)가 적용
- 예산: Redis 원자 예약(reserve) → DB 커밋 실패 시 보상 반환(release)으로 예산 누수 방지

**Coupon (쿠폰)**

```
상태: ISSUED → REDEEMED (결제 완료)
      ISSUED → EXPIRED  (유효기간 만료)
      REDEEMED → CANCELLED (결제 취소로 회수)
```

- 회원(memberId) + 프로모션(promotionId) 조합으로 발급. `expiresAt = promotion.endsAt` (발급 시 복사)
- ISSUED 상태에서만 REDEEMED 전환 가능 — 단일 사용 보장 (I12)
- RESERVED 상태는 STRETCH 비동기 예약 흐름 전용; 현재 구현(MUST)에서는 ISSUED → REDEEMED 직행

**CouponRedemption (조정 레코드)**

- 쿠폰 결제 건마다 생성되는 불변 조정 레코드 (취소 시 `cancelled=true`로 soft-flag)
- 필드: `orderTotal`(T, 주문 총액), `discountAmount`(D), `voucherCharged`(T−D, 실 바우처 차감액), `transactionId`, `cancelled`
- 결합 결제 분개: 바우처분 `DEBIT MERCHANT_RECEIVABLE / CREDIT VOUCHER_BALANCE`(T−D) + 보조분 `DEBIT MERCHANT_RECEIVABLE / CREDIT PROMOTION_FUNDING`(D). 금액이 0인 leg는 생략

### 1.10 Point 도메인

**PointAccount (포인트 계좌)**

- 회원 1인당 1계좌 (`memberId` UNIQUE). `balance`(차변정상, 음수 불가)
- 동시 적립 요청은 `SELECT FOR UPDATE`로 직렬화 (INSERT IGNORE로 갭 락 데드락 회피 후 잠금)

**PointTransaction (포인트 거래)**

- `@Immutable` — 생성 후 수정/삭제 불가 (원 EARN 행 보존 원칙)
- 타입: `EARN`(적립), `CANCEL`(보상 취소 — 원 EARN 행은 그대로 두고 별도 CANCEL 행 추가)
- 필드: `memberId`, `type`, `amount`, `balanceAfter`, `sourceTransactionId`(원 redemption txId), `createdAt`

**적립 규칙**

- 적립 시점: 결제(REDEMPTION) 완료와 **동일 DB 트랜잭션** 내 동기 기록
- 적립 기준액: 실제 바우처 차감액 `voucherCharged`(T−D) — 쿠폰 보조분 D는 플랫폼 출연이므로 제외 (I17)
- 적립률: `point.earn-rate`(설정값, 기본 1%). 적립액 = `voucherCharged × rate` (HALF_UP, 1원 단위)
- 취소 시 역적립: 취소 트랜잭션 내 동기 역분개 (`DEBIT POINT_FUNDING / CREDIT POINT_BALANCE`)

---

## 2. 불변식 (절대 위반 불가)

| # | 불변식 | 분산 집행 필요 | 이유 |
|---|--------|:-:|------|
| I1 | `voucher.balance ≥ 0` — 잔액은 음수 불가 | Yes | 동시 사용 요청 시 경쟁 상태 |
| I2 | `sum(원장 차변) == sum(원장 대변)` — 원장 차대변 항상 균형 | No | 단일 트랜잭션 내 쌍 생성으로 보장 |
| I3 | `voucher.balance == sum(관련 원장 엔트리)` — 캐시 잔액과 원장 합산 일치 | No | 배치 검증으로 감지, 트랜잭션 내 동시 갱신으로 예방 |
| I4 | 만료된 상품권으로 결제 불가 | Yes | 만료 시점과 결제 시점의 경합 |
| I5 | 하나의 상품권에 동시에 두 건의 결제 처리 불가 | Yes | 동시 요청 시 잔액 초과 차감 가능 |
| I6 | 잔액 환불은 60%+ 사용 시에만 가능 | No | 단일 요청 내 검증 |
| I7 | 청약철회는 구매 후 7일 이내 + 미사용(ACTIVE) 상태에서만 가능 | No | 단일 요청 내 검증 |
| I8 | 지자체 월 발행한도 초과 발행 불가 | Yes | 동시 발행 요청 시 한도 초과 가능 |
| I9 | 1인 구매한도 초과 구매 불가 | Yes | 동시 구매 요청 시 한도 초과 가능 |
| I10 | 정산 금액 = 해당 기간 가맹점 사용 내역 합산 | No | 배치에서 계산, 원장 기반 검증 |
| I11 | `MANUAL_ADJUSTMENT` 원장 엔트리는 관리자 승인 + 사유 필수 | No | 서비스 계층 검증 |
| I12 | 쿠폰 단일 사용 — ISSUED 상태에서만 REDEEMED 전환 가능, 재사용 불가 | Yes | 동시 결제 요청 시 쿠폰 락으로 직렬화 |
| I13 | 할인액 `D ≤ T` — 주문총액 초과 할인 불가 (클램프 `min(D, T)`) | No | 오케스트레이터 적용 |
| I14 | 프로모션 예산 초과 지급 불가 (`budgetLimit` 원자 예약) | Yes | 동시 결제 시 Redis 원자 연산으로 직렬화 |
| I15 | `PointAccount.balance ≥ 0` — 포인트 잔액 음수 불가 | Yes | 동시 취소 요청 시 경쟁 상태 |
| I16 | `Σ PointAccount.balance == 원장 POINT_BALANCE 순액` — I3의 포인트 버전 | No | 배치 검증, 동일 트랜잭션 내 동기 갱신으로 예방 |
| I17 | 포인트 적립 기준액 = `voucherCharged`(T−D) — 쿠폰 보조분 D는 적립 제외 | No | 서비스 계층 고정 |

---

## 3. 장애 시나리오 및 엣지 케이스

| 시나리오 | 문제 | 대응 전략 |
|---------|------|----------|
| **이중 사용** | 동일 상품권에 두 가맹점이 동시 결제 요청 | Redisson 분산락 (voucher ID 기준) + DB 비관적 락 이중 방어 |
| **부분 사용 중 잔액 초과** | 잔액 5,000원인데 10,000원 결제 요청 | 락 획득 후 잔액 검증 → 부족 시 FAILED 처리 |
| **만료 중 결제 경합** | 만료 배치 실행 중 결제 요청 도달 | 분산락으로 직렬화. 락 획득 후 만료 여부 재확인 |
| **네트워크 장애** | 결제 처리 중 Redis/DB 연결 끊김 | DB 트랜잭션 롤백. 멱등키로 재시도 안전 보장 |
| **중복 API 호출 (재시도 폭주)** | 클라이언트가 타임아웃 후 동일 요청 반복 | 멱등키 + Redis TTL로 중복 감지 → 원래 응답 반환 |
| **가맹점 환불 사기** | 가맹점이 허위 취소 후 재사용 유도 | 취소는 원 거래 기준 검증. 취소 건수/금액 이상 탐지 감사 로그. 관리자 승인 필요 |
| **잔액 환불 조건 미충족** | 60% 미만 사용 상태에서 환불 시도 | `(faceValue - balance) / faceValue < 0.6` 이면 거절 |
| **청약철회 기간 초과** | 구매 후 8일째 청약철회 시도 | `purchasedAt + 7일 < now` 이면 거절 |
| **정산 중 취소** | 정산 확정 후 해당 기간 거래 취소 요청 | 이미 정산된 거래는 다음 정산 주기에서 차감 처리 (보상 트랜잭션) |
| **예산 소진 후 쿠폰 결제** | Redis 예약 성공 후 DB 커밋 실패 | `finally` 블록에서 보상 DECRBY로 예산 반환 (누수 방지) |
| **쿠폰 취소 후 포인트 역적립** | 결제 취소 시 포인트 이미 적립된 상태 | 원 EARN 행 불변 보존 + 새 CANCEL 행 + 역분개로 보상 |

---

## 4. 일관성 요구사항

| 영역 | 일관성 수준 | 이유 |
|------|:---------:|------|
| 상품권 잔액 차감 (결제) | **강한 일관성** | 금전 무결성. 초과 차감 시 실손 발생 |
| 원장 기록 | **강한 일관성** | 잔액 변경과 동일 트랜잭션 내 동기 기록 필수 |
| 구매한도 검증 | **강한 일관성** | 한도 초과 시 정책 위반 |
| 월 발행한도 | **강한 일관성** | 지자체 예산 초과 방지 |
| CRITICAL 감사 로그 | **강한 일관성** | 금전 변동 감사 실패 시 트랜잭션 롤백 |
| 포인트 적립/역적립 | **강한 일관성** | 결제 트랜잭션과 동일 DB 트랜잭션 내 동기 기록 |
| 프로모션 예산 예약 | **강한 일관성** | Redis 원자 연산 (DB 트랜잭션 밖, 보상 패턴 병행) |
| HIGH/MEDIUM 감사 로그 | **최종 일관성** | 비동기 이벤트 리스너로 기록. 실패 시 재시도 |
| 정산 금액 계산 | **최종 일관성** | 배치로 계산. 즉시성 불요 |
| 알림 발송 | **최종 일관성** | 이벤트 기반 비동기 처리 |
| 만료 처리 | **최종 일관성** | 스케줄러 배치. 분 단위 지연 허용 |

---

## 5. 데이터 보관 정책

- 거래 데이터(Transaction, LedgerEntry): **5년 보관** (공공기록물 관리에 관한 법률)
- 감사 로그(AuditLog): **5년 보관**
- 회원 개인정보: **탈퇴 후 1년 보관 후 파기** (개인정보보호법)
- 상품권 데이터: **만료/환불 후 5년 보관**

---

**핵심 결정 요약:** Voucher는 ACTIVE 직접 생성, 청약철회(7일)와 잔액환불(60%)은 별개 프로세스로 분리, 원장 기록은 동일 트랜잭션 내 동기 호출, MANUAL_ADJUSTMENT는 관리자 승인 필수, 데이터 5년 보관. 쿠폰 결합 결제는 정준 락 순서(coupon → voucher)로 데드락 방지, 예산은 Redis 원자 예약 + 보상 패턴으로 누수 방지. 포인트 적립은 바우처 실차감액(T−D) 기준 1%, 취소 시 원 EARN 행 보존 + 역분개 보상. BigDecimal 비교는 `compareTo`로 통일하여 scale 차이에 의한 비교 오류 방지.
