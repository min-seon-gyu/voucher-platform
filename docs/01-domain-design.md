# 1단계 — 도메인 및 비즈니스 규칙

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
- entryType: `PURCHASE`, `REDEMPTION`, `REFUND`, `WITHDRAWAL`, `EXPIRY`, `SETTLEMENT`, `CANCELLATION`, `MANUAL_ADJUSTMENT`
- accountCode: `MEMBER_CASH`(회원 현금), `VOUCHER_BALANCE`(상품권 잔액), `MERCHANT_RECEIVABLE`(가맹점 미수금), `REVENUE_DISCOUNT`(할인 수익), `EXPIRED_VOUCHER`(만료 상품권), `REFUND_PAYABLE`(환불 미지급금), `SETTLEMENT_PAYABLE`(정산 미지급금)
- `MANUAL_ADJUSTMENT`: 관리자 승인 필수, 사유 필드(reason) 필수 입력, 생성 시 반드시 CRITICAL 감사 로그 동반

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
- TransactionType: `PURCHASE`, `REDEMPTION`, `REFUND`, `WITHDRAWAL`, `EXPIRY`, `CANCELLATION`
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

---

## 4. 일관성 요구사항

| 영역 | 일관성 수준 | 이유 |
|------|:---------:|------|
| 상품권 잔액 차감 (결제) | **강한 일관성** | 금전 무결성. 초과 차감 시 실손 발생 |
| 원장 기록 | **강한 일관성** | 잔액 변경과 동일 트랜잭션 내 동기 기록 필수 |
| 구매한도 검증 | **강한 일관성** | 한도 초과 시 정책 위반 |
| 월 발행한도 | **강한 일관성** | 지자체 예산 초과 방지 |
| CRITICAL 감사 로그 | **강한 일관성** | 금전 변동 감사 실패 시 트랜잭션 롤백 |
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

**1단계 핵심 결정:** Voucher는 ACTIVE 직접 생성, 청약철회(7일)와 잔액환불(60%)을 별개 프로세스로 분리, 원장 기록은 동일 트랜잭션 내 동기 호출, MANUAL_ADJUSTMENT는 관리자 승인 필수, 데이터 5년 보관. BigDecimal 비교는 `compareTo`로 통일하여 scale 차이에 의한 비교 오류 방지 (예: `0.00 != 0` 문제).
