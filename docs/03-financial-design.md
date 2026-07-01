# 03. 금융·회계 설계 (Financial & Accounting Design)

> ⚠️ **커머스 전환(pivot) 진행 중** — 주문 결제 분개는 신규 계정 `CUSTOMER_CASH / SELLER_PAYABLE`로,
> 판매자 정산은 OrderLine 합산으로 재배선됐다(README 참조). voucher 기준 분개 서술은 제거 예정이며,
> 이 문서 상세 재작성은 pivot 완료(Phase 4c) 후 진행한다. **현행 자금 흐름은 [`../README.md`](../README.md) 우선.**

> 커머스 결제·프로모션 백엔드의 **자금 정합성 설계 단일 레퍼런스**.
> 복식부기 원장, 계정과목, 연산별 분개, 보상 트랜잭션, 정합성 검증을 다룬다.
> 모든 분개는 `com.commerce.ledger / .promotion / .point / .transaction` 실제 코드에 대조해 검증했다.
>
> 연관 문서: [01-domain-design.md](01-domain-design.md) · [02-architecture-decisions.md](02-architecture-decisions.md)

**핵심 설계 결정 요약**

| 결정 | 근거 |
|---|---|
| 복식부기 원장을 진실 원천으로, 잔액은 캐시 | 증명 가능성·자기 검증·불변 감사선(§1) |
| 분개는 항상 균형 2-leg, 양수 금액 | 차대 균형 자동 보장, 0원 leg 금지(§3) |
| 취소 = 역분개(보상 트랜잭션), 삭제·수정 금지 | 과거 사실 보존, `@Immutable` 원장(§5) |
| 쿠폰 할인은 가맹점 gross T 정산 + 플랫폼 `PROMOTION_FUNDING` 부담 | 가맹점 정산 단순화, 재원 추적 분리(§4.3) |
| 포인트는 `POINT_BALANCE`/`POINT_FUNDING` 대칭 계정으로 원장 편입 | 적립·취소까지 자금 정합성에 포함(§4.4) |
| 정합성 불일치는 자동 보정 금지, 사람에게 보고 | 버그 은폐·손실 확대 방지(§6) |

---

## 1. 왜 복식부기 원장인가

엔티티의 `balance` 필드(상품권 잔액, 포인트 잔액)는 **조회 성능을 위한 캐시**일 뿐이다.
자금의 **진실 원천(source of truth)은 원장(`ledger_entries`)** 이며, 모든 잔액은 원장의 차변·대변 누계로부터 재구성될 수 있어야 한다.

단식부기(잔액만 갱신)는 "왜·언제·어떤 거래로 줄었는가"를 증명하지 못한다. 금융 시스템에서 이는 치명적이다.

단식부기에서 "상품권 잔액이 50,000 → 20,000으로 줄었다"는 사실만 남으면, *언제·왜·누가·어떤 거래로* 30,000이 줄었는지, 조작은 아닌지 답할 수 없다. 복식부기는 이 질문에 항상 답한다.

- **증명 가능성(provability)**: 모든 자금 이동이 `(차변 1행 + 대변 1행)`으로 양쪽에 기록 → "돈의 출발지와 도착지"가 항상 남는다. `transactionId`로 어떤 거래가 일으켰는지까지 역추적된다.
- **자기 검증(self-balancing)**: 전역 Σ차변 = Σ대변 이 깨지면 그 자체가 버그 신호 → `LedgerVerificationService`가 매일 자동 대조(§6). 캐시 잔액은 원장 net과 교차 검증되어, 어느 한쪽이 손상돼도 즉시 드러난다.
- **불변 감사선(immutable audit trail, WORM)**: 원장은 추가 전용(append-only)이며 한 번 쓴 행은 수정·삭제하지 않는다. 취소조차 삭제가 아니라 역분개로 처리(§5)하므로 "1월에 결제, 2월에 취소"라는 시간 순서의 사실이 보존된다. 이는 금융 감사·분쟁 대응의 전제다.

즉, `voucher.balance`가 손상돼도 원장으로 복구·검증할 수 있고, 원장과 캐시의 불일치는 곧바로 사고로 탐지된다.

**자금 흐름 한눈에:**

```
[구매]  MEMBER_CASH ──▶ VOUCHER_BALANCE
                              │
            ┌─────────────────┼──────────────────┬───────────┐
            ▼                 ▼                  ▼           ▼
        [결제]            [환불/철회]          [만료]    [쿠폰 결제]
   MERCHANT_RECEIVABLE  REFUND_PAYABLE   EXPIRED_VOUCHER  + PROMOTION_FUNDING(D)
            │  (gross T)       │                          + POINT_BALANCE(적립)
            ▼                  ▼
       [정산 확정]         [외부 이체]
   SETTLEMENT_PAYABLE     회원 계좌 환원
            │
            ▼
      [외부 이체] 가맹점 계좌
```

---

## 2. 계정과목 (Chart of Accounts) — 10개

`AccountCode`(`ledger/domain/AccountCode.kt`)의 전체 계정. "정상잔액"은 해당 계정이 평상시 어느 쪽으로 누적되는가(차변정상 = 자산/비용, 대변정상 = 부채/수익)를 뜻한다.

분류 관점: **차변정상 자산** 4개(`MEMBER_CASH`, `VOUCHER_BALANCE`, `MERCHANT_RECEIVABLE`, `POINT_BALANCE`), **대변정상 재원/부채** 4개(`REFUND_PAYABLE`, `SETTLEMENT_PAYABLE`, `PROMOTION_FUNDING`, `POINT_FUNDING`), **대변정상 수익** 2개(`REVENUE_DISCOUNT`, `EXPIRED_VOUCHER`). 바우처-포인트는 `BALANCE`(자산)·`FUNDING`(재원)이 대칭 쌍을 이룬다.

| 계정 (AccountCode) | 정상잔액 | 분류 | 역할 |
|---|---|---|---|
| `MEMBER_CASH` | 차변 | 자산 | 회원이 상품권 구매에 투입한 현금. 구매 시 대변(유출), 환불 시 외부 정산으로 환원. |
| `VOUCHER_BALANCE` | 차변 | 자산 | 상품권 사용가능 잔액. **순잔액 = `voucher.balance` 캐시와 일치해야 함**(I-2). |
| `MERCHANT_RECEIVABLE` | 차변 | 자산 | 가맹점 미수금. 결제 시 발생(차변), 취소 시 소멸, 정산 확정 시 해소. **gross 기준 누적**. |
| `REVENUE_DISCOUNT` | 대변 | 수익 | 할인 수익 계정(정의만 존재, 현재 분개 미사용 — §2 주석 참고). |
| `EXPIRED_VOUCHER` | 대변 | 수익 | 만료로 소멸한 잔액 인식. 발행 주체의 사장(死藏) 수입. |
| `REFUND_PAYABLE` | 대변 | 부채 | 환불·청약철회 지급 의무. 본 시스템에선 의무 확정 시점에 **차변**으로 인식(아래 주석). |
| `SETTLEMENT_PAYABLE` | 대변 | 부채 | 가맹점 정산 지급 의무. 정산 확정 시 인식(아래 주석). |
| `PROMOTION_FUNDING` | 대변 | 출연/부채 | **쿠폰 할인 재원**. 가맹점은 gross T를 정산받고, 할인분 D를 플랫폼이 이 계정으로 부담. |
| `POINT_BALANCE` | 차변 | 자산 | **포인트 잔액**. `VOUCHER_BALANCE`와 대칭(차변정상). 순잔액 = Σ`PointAccount.balance`(I-4). |
| `POINT_FUNDING` | 대변 | 출연/부채 | **포인트 적립 재원**. 플랫폼이 적립으로 부담하는 출연금. |

> **payable 계정의 부호 관례(코드 기준).** 전통 회계라면 환불·정산 의무가 확정될 때 부채 계정을 *대변*으로 늘린다. 그러나 본 시스템은 실제 계좌이체를 외부 시스템에 위임하므로, `REFUND_PAYABLE`·`SETTLEMENT_PAYABLE`을 **"지급 인식 leg"** 로 보고 의무 확정 시점에 **차변**에 기록한다(§4 REFUND/WITHDRAWAL/SETTLEMENT). 결과적으로 두 계정은 현재 모델에서 *차변(음의 대변) 잔액*을 누적하는 단순화된 형태다. 이는 전역 차대 균형(I-1)에는 영향이 없으며, 외부 이체 완료 시 반대 leg로 해소되는 설계다. 정상잔액 분류(대변)는 회계적 본질을, 실제 분개 부호는 코드 동작을 나타낸다.

---

## 3. 분개 규칙 (2-leg posting)

모든 자금 이동은 `LedgerService.record()`(`ledger/application/LedgerService.kt`)를 통해 기록된다.

```kotlin
fun record(debitAccount, creditAccount, amount, transactionId, entryType): List<LedgerEntry>
// 정확히 DEBIT 1행 + CREDIT 1행을 생성해 saveAll → 항상 균형
```

규칙:

1. **항상 1 DEBIT + 1 CREDIT**, 동일 `amount`, 동일 `transactionId` + `entryType`. → leg 단위로 Σ차변 = Σ대변 자동 보장.
2. **원자성**: 호출자의 활성 `@Transactional`(또는 `TransactionTemplate`) 안에서 동기 호출. 잔액 변경과 원장 기록이 한 트랜잭션에서 커밋/롤백된다.
3. **불변(immutable)**: `LedgerEntry`는 `@Immutable`(`@Entity`) — Hibernate가 UPDATE/DELETE를 발행하지 않는다. `init { require(amount > 0) }` 로 0·음수 금액 leg 자체를 금지.
4. **금액은 항상 양수**, 방향은 `side(DEBIT/CREDIT)`로만 표현. 0원 leg는 존재할 수 없으므로 "금액 0인 쌍은 생략"이 안전하다(§4 쿠폰).

**스키마(`ledger_entries`)**: `amount`는 `BigDecimal(precision=15, scale=2)` — 부동소수점 오차 없는 통화 연산. `account`/`side`/`entryType`은 `EnumType.STRING`(스키마 가독성·안정성). 조회 인덱스 `idx_ledger_tx(transactionId)`(거래별 분개 묶음 조회), `idx_ledger_account(account, createdAt)`(계정별 시계열·net 집계). `id`는 `IDENTITY` 자동 증가로 기록 순서를 보존한다.

하나의 비즈니스 연산은 **여러 개의 균형 2-leg 쌍**을 같은 `transactionId`로 묶을 수 있다(쿠폰 결제 = 2쌍, 적립 동반 결제 = 결제쌍 + 적립쌍).

**`LedgerEntryType` 10종 → 분개 매핑:**

| entryType | DEBIT → CREDIT | 발생 연산 |
|---|---|---|
| `PURCHASE` | `VOUCHER_BALANCE` → `MEMBER_CASH` | 상품권 구매(§4.1) |
| `REDEMPTION` | `MERCHANT_RECEIVABLE` → `VOUCHER_BALANCE` | 결제·쿠폰 결제 쌍1(§4.2/4.3) |
| `COUPON_SUBSIDY` | `MERCHANT_RECEIVABLE` → `PROMOTION_FUNDING` | 쿠폰 결제 쌍2(§4.3) |
| `POINT_EARN` | `POINT_BALANCE` → `POINT_FUNDING` | 포인트 적립(§4.4) |
| `REFUND` | `REFUND_PAYABLE` → `VOUCHER_BALANCE` | 잔액환불(§4.5) |
| `WITHDRAWAL` | `REFUND_PAYABLE` → `VOUCHER_BALANCE` | 청약철회(§4.6) |
| `EXPIRY` | `EXPIRED_VOUCHER` → `VOUCHER_BALANCE` | 만료(§4.7) |
| `SETTLEMENT` | `SETTLEMENT_PAYABLE` → `MERCHANT_RECEIVABLE` | 정산 확정(§4.8) |
| `CANCELLATION` | (원 분개의 역방향) | 취소·역분개(§4.9) |
| `MANUAL_ADJUSTMENT` | (운영자 지정) | 예약 — 수동 정정용(현재 자동 분개 없음) |

---

## 4. 연산별 분개표 (postings per operation)

기호: `T` = 주문총액(gross, 가맹점 수취 기준), `D` = 쿠폰 할인액(클램프 후 `min(D,T)`), `T−D` = 바우처 실제 차감액(`voucherCharged`).

### 4.1 구매 (PURCHASE) — `VoucherIssueService`

| side | account | amount |
|---|---|---|
| DEBIT | `VOUCHER_BALANCE` | 액면가 |
| CREDIT | `MEMBER_CASH` | 액면가 |

회원 현금이 상품권 잔액으로 전환. (`entryType = PURCHASE`)

### 4.2 결제 — 쿠폰 없음 (REDEMPTION) — `VoucherRedemptionService`

| side | account | amount |
|---|---|---|
| DEBIT | `MERCHANT_RECEIVABLE` | T |
| CREDIT | `VOUCHER_BALANCE` | T |

상품권 잔액이 가맹점 미수금으로 이동. 결제 직후 **포인트 적립(§4.4)** 이 같은 `txId`로 동반된다.

### 4.3 쿠폰 결제 (combined) — `RedemptionOrchestrator.redeemWithCoupon`

하나의 `transactionId`에 **균형 2-leg 2쌍**을 기록한다. 거래 `amount`는 gross `T`(정산 집계 기준).

**쌍1 — 바우처 결제분 (`REDEMPTION`), 금액 `T−D`:**

| side | account | amount |
|---|---|---|
| DEBIT | `MERCHANT_RECEIVABLE` | T−D |
| CREDIT | `VOUCHER_BALANCE` | T−D |

**쌍2 — 플랫폼 보조분 (`COUPON_SUBSIDY`), 금액 `D`:**

| side | account | amount |
|---|---|---|
| DEBIT | `MERCHANT_RECEIVABLE` | D |
| CREDIT | `PROMOTION_FUNDING` | D |

→ 가맹점 미수금 합계 = `(T−D) + D = T` (**가맹점은 gross T 정산**). 할인 D는 회원이 아니라 플랫폼이 `PROMOTION_FUNDING`으로 부담한다.

**0원 leg 생략**: 전액 쿠폰 보전(`T−D = 0`)이면 쌍1 생략, `D = 0`이면 쌍2 생략. `LedgerEntry`는 양수 금액만 허용하며 0원 leg는 어떤 잔액에도 기여하지 않으므로 순효과·정합성은 동일하다. 바우처 차감(`voucher.redeem`)도 `T−D > 0`일 때만 수행한다.

**동시성·원자성 설계.** 결합 결제는 두 자원(쿠폰·바우처)을 잠그므로 **정준 락 순서 `coupon → voucher`** 로 데드락을 방지한다(취소도 동일 순서). 예산 예약(`PROMOTION_FUNDING` 한도)은 Redis에서 **DB 트랜잭션 밖에서 원자 `reserve`** 한 뒤, 다운스트림 실패로 DB가 롤백되면 `finally`에서 **보상 `release`(DECRBY)** 로 예산 누수를 막는다. 분개·바우처 차감·쿠폰 사용·포인트 적립은 모두 단일 `TransactionTemplate` 안에서 커밋/롤백된다(원자성).

### 4.4 포인트 적립 (POINT_EARN) — `PointEarnService.earn`

결제와 **동기**로, 결제와 **같은 `txId`** 를 공유해 기록.

| side | account | amount |
|---|---|---|
| DEBIT | `POINT_BALANCE` | earn |
| CREDIT | `POINT_FUNDING` | earn |

- **적립 기준액 = `T−D`** (쿠폰 적용 후 실제 결제액 `voucherCharged`). 할인분 D는 적립 대상이 아니다.
- **적립률 = `point.earn-rate` = `0.01` (1%)**, `earn = base × rate`, `setScale(0, HALF_UP)` (1원 단위).
- **zero-guard**: `earn ≤ 0`(소액·전액쿠폰)이면 원장·`PointTransaction` 기록 없이 no-op.
- 동시 적립은 `findByMemberIdForUpdate`(SELECT FOR UPDATE)로 직렬화. `PointAccount.balance` 증가는 JPA dirty-checking으로 영속화되므로 반드시 호출자 트랜잭션 내부에서 실행.
- 적립은 결제와 같은 `txId`를 공유하므로, 취소 시 그 `txId` 기준으로 EARN을 찾아 정확히 역분개(§4.9)할 수 있다 — 적립·취소가 결제 거래에 묶여 추적된다.

예) `T−D = 8,000` → `8,000 × 0.01 = 80.00` → `80`. `T−D = 7,950` → `79.50` → `HALF_UP` → `80`. `T−D = 40`(소액) → `0.40` → `0` → zero-guard로 적립 생략.

### 4.5 잔액환불 (REFUND) — `VoucherRefundService`

| side | account | amount |
|---|---|---|
| DEBIT | `REFUND_PAYABLE` | 잔액 |
| CREDIT | `VOUCHER_BALANCE` | 잔액 |

`PARTIALLY_USED` & 사용률 ≥ `region.policy.refundThresholdRatio` 조건 충족 시 남은 잔액 전액 환불.

### 4.6 청약철회 (WITHDRAWAL) — `VoucherWithdrawalService`

| side | account | amount |
|---|---|---|
| DEBIT | `REFUND_PAYABLE` | 전액(잔액) |
| CREDIT | `VOUCHER_BALANCE` | 전액(잔액) |

`ACTIVE` & 구매 후 7일 이내일 때 **전액** 환불(미사용 상품권이므로 잔액 = 액면가). 원장 구조는 REFUND와 동일하되 `entryType = WITHDRAWAL`.

| | 청약철회(WITHDRAWAL) | 잔액환불(REFUND) |
|---|---|---|
| 대상 상태 | `ACTIVE`(미사용) | `PARTIALLY_USED`(부분사용) |
| 조건 | 구매 후 7일 이내 | 사용률 ≥ `refundThresholdRatio`(지역 정책) |
| 금액 | 전액(= 액면가) | 잔여 잔액 |
| 결과 상태 | `WITHDRAWN` | `REFUNDED` |
| 원장 | DEBIT `REFUND_PAYABLE` / CREDIT `VOUCHER_BALANCE` (공통) | 동일 |

### 4.7 만료 (EXPIRY) — `VoucherExpiryProcessor`

| side | account | amount |
|---|---|---|
| DEBIT | `EXPIRED_VOUCHER` | 잔여잔액 |
| CREDIT | `VOUCHER_BALANCE` | 잔여잔액 |

5분 주기 배치. `REQUIRES_NEW` + 분산락으로 건별 독립 처리(한 건 실패가 다른 건에 영향 없음, 실패 건은 다음 주기 자동 재시도). 잔여잔액을 캡처 → `balance = 0` → **잔여 > 0일 때만** 원장 기록(0원 leg 금지). 배치 지연(최대 5분) 사이의 만료 상품권 결제는 `redeem()` 내부 `isExpired()` 실시간 가드로 차단되므로(이중 방어), 만료 후 사용은 실제로 발생하지 않는다.

### 4.8 정산 (SETTLEMENT) — `SettlementService.confirm`

정산 확정 시 가맹점 미수금을 정산 지급 의무로 전환:

| side | account | amount |
|---|---|---|
| DEBIT | `SETTLEMENT_PAYABLE` | 정산총액 |
| CREDIT | `MERCHANT_RECEIVABLE` | 정산총액 |

정산총액 = 기간 내 `REDEMPTION` & `COMPLETED` 거래 `amount` 합(gross T 기준, 취소된 원거래는 `CANCELLED`라 자동 제외). 미수금이 대변으로 해소되고, `SETTLEMENT_PAYABLE`이 지급 인식 leg로 차변 기록된다(§2 payable 부호 관례 참고). 실제 이체는 외부 시스템에서 처리.

**정산 상태 머신**: `PENDING → CONFIRMED → PAID`, 분쟁 시 `PENDING ⇄ DISPUTED`. 위 분개는 `confirm()`(→`CONFIRMED`) 시점에 발생한다. **중복 정산 방지**: DB UNIQUE `(merchantId, periodStart, periodEnd)`로 같은 가맹점·같은 기간 정산은 1건만 존재(I-8). `calculate()`는 기존 정산 조회 후 존재 시 즉시 거부.

### 4.9 취소 (CANCELLATION) — 보상 분개 — `TransactionCancelService`

원거래는 **불변**으로 보존하고, 새 보상 거래(`type = CANCELLATION`, `originalTransactionId = 원거래`)에 **역분개**를 기록한다.

**무쿠폰 결제 취소** — 원 REDEMPTION의 정확한 반대:

| side | account | amount |
|---|---|---|
| DEBIT | `VOUCHER_BALANCE` | T |
| CREDIT | `MERCHANT_RECEIVABLE` | T |

**쿠폰 결제 취소** — 두 쌍 모두 역분개(`entryType = CANCELLATION`):

- 쌍1 역분개 (`T−D > 0`): DEBIT `VOUCHER_BALANCE` / CREDIT `MERCHANT_RECEIVABLE`, 금액 `T−D`.
- 쌍2 역분개 (`D > 0`): DEBIT `PROMOTION_FUNDING` / CREDIT `MERCHANT_RECEIVABLE`, 금액 `D`.
- 바우처는 `restoreBalance(T−D)` 로 복원(gross T 아님 — 과복원 방지), 쿠폰 `CANCELLED`, 예약 예산 `release`(DECRBY).

**포인트 역분개** — 원 결제가 적립했었다면(`reverseEarn`), 보상 거래 `txId`로:

| side | account | amount |
|---|---|---|
| DEBIT | `POINT_FUNDING` | earned |
| CREDIT | `POINT_BALANCE` | earned |

원 EARN `PointTransaction`은 보존하고 새 `CANCEL` 행 + 위 역분개로 `POINT_BALANCE` 순잔액을 0으로 복귀. 적립이 없었으면 no-op(멱등).

### 4.10 통합 원장 트레이스 예시

`T = 10,000`, `D = 2,000`(쿠폰), `earn = (10,000−2,000) × 1% = 80`. 쿠폰 결제 1건이 만드는 실제 `ledger_entries` 행(`txId`는 결제 거래 하나로 공유):

| # | account | side | amount | entryType |
|---|---|---|---|---|
| 1 | `MERCHANT_RECEIVABLE` | DEBIT | 8,000 | REDEMPTION |
| 2 | `VOUCHER_BALANCE` | CREDIT | 8,000 | REDEMPTION |
| 3 | `MERCHANT_RECEIVABLE` | DEBIT | 2,000 | COUPON_SUBSIDY |
| 4 | `PROMOTION_FUNDING` | CREDIT | 2,000 | COUPON_SUBSIDY |
| 5 | `POINT_BALANCE` | DEBIT | 80 | POINT_EARN |
| 6 | `POINT_FUNDING` | CREDIT | 80 | POINT_EARN |

검증: Σ차변 = 8,000+2,000+80 = 10,080 = Σ대변. 가맹점 미수금 = 10,000(gross T). 바우처 잔액 −8,000, 포인트 +80. 이후 취소하면 §5 표대로 6개 계정이 모두 net 0으로 수렴한다.

---

## 5. 보상 트랜잭션 (Compensating Transaction)

완료된 거래를 되돌릴 때 **원장 행을 수정·삭제하지 않고, 반대 방향의 새 거래를 추가**한다.

**왜 DELETE/UPDATE가 아닌가:**

- **감사 증명성**: "1월에 결제됐고 2월에 취소됨"이라는 두 사실이 모두 남아야 한다. 1월 기록을 지우면 과거가 왜곡된다.
- **불변 원장**: `LedgerEntry`는 `@Immutable` — 애초에 변경이 차단된다.
- **참조 무결성**: 원장은 `transactionId`를 참조하므로 원거래를 지우면 고아 레코드가 발생.
- **상태만 전이**: 원거래는 내용 불변, `status`만 `CANCELLED`로 전이. 보상 거래는 `originalTransactionId`로 원거래를 가리킨다.

**상쇄 보장 — 쿠폰 결제 + 적립 후 취소 시나리오** (`T=10,000`, `D=2,000`, `earn=80`):

| account | 결제 | 취소 | net |
|---|---|---|---|
| `VOUCHER_BALANCE` | −8,000 | +8,000 | 0 |
| `MERCHANT_RECEIVABLE` | +10,000 | −10,000 | 0 |
| `PROMOTION_FUNDING` | +2,000 | −2,000 | 0 |
| `POINT_BALANCE` | +80 | −80 | 0 |
| `POINT_FUNDING` | +80 | −80 | 0 |

모든 계정이 0으로 수렴 → 취소 후 시스템은 결제 이전 상태와 자금적으로 동일하다(원장 행은 6쌍 모두 보존된 채). 검증은 `CouponCancelIntegrationTest` / `PointCancelIntegrationTest`가 커버.

**거래 수준 보존**: 원 `Transaction`은 `amount`·`type` 등 내용을 유지한 채 `status`만 `COMPLETED → CANCELLED`로 전이한다. 보상 거래는 `type = CANCELLATION`, `originalTransactionId = 원거래.id`로 **양방향 추적**이 가능하다. 정산 집계는 `REDEMPTION & COMPLETED`만 합산하므로(§4.8), 취소된 원거래는 상태 전이만으로 정산에서 자동 제외된다 — 별도 차감 로직이 필요 없다.

---

## 6. 정합성 검증 (`LedgerVerificationService`)

매일 **02:00**(`@Scheduled(cron = "0 0 2 * * *")`, `readOnly` + `REPEATABLE_READ`) 자동 대조.

`REPEATABLE_READ` 격리를 쓰는 이유: 검증은 원장 합계·바우처 캐시·포인트 캐시를 **여러 번의 쿼리로 교차 대조**하므로, 그 사이 다른 트랜잭션의 커밋이 끼어들면 실제로는 정합한데도 거짓 불일치(phantom)를 보고할 수 있다. 단일 스냅샷에서 읽어 이 위험을 제거한다. 새벽 02:00은 트래픽 최저 시간대로 대조 부하 영향을 최소화한다.

`verify().isBalanced` 는 다음 **세 조건의 AND**:

1. **전역 차대 균형**: `Σ DEBIT == Σ CREDIT` (전 계정 합).
2. **상품권 캐시 정합**: 모든 `Voucher`에 대해 `voucher.balance(캐시) == VOUCHER_BALANCE 원장 net`(= 해당 바우처의 차변−대변). 불일치 바우처는 `ImbalancedVoucher(cached, ledger, difference)`로 수집.
3. **포인트 정합**: `POINT_BALANCE 원장 net(차변−대변) == Σ PointAccount.balance`.

**자동 수정 안 함(no auto-fix).** 불일치 = 데이터 손상 또는 버그 신호이므로, 임의 보정은 사고를 은폐할 위험이 있다. 대신:

- `log.error("LEDGER IMBALANCE DETECTED ...")` — **CRITICAL 감사 로그**로 사람 판단 유도.
- 메트릭 게이지: `ledger.verification.imbalance`(불일치 바우처 수), `ledger.verification.point.matches`(0/1) → 모니터링·알림 연동.

검증 통과 시 `log.info`로 전역 잔액 기록.

**불일치 대응 절차(runbook):** ① CRITICAL 알림 수신 → ② `ImbalancedVoucher(cached, ledger, difference)`로 어느 바우처가 얼마나 어긋났는지 식별 → ③ 해당 바우처의 `transactionId`별 원장 행을 시간순 추적해 누락/중복 leg를 특정 → ④ 원인 거래를 찾아 **보상 분개 또는 `MANUAL_ADJUSTMENT`** 로 정정(원장은 절대 직접 수정하지 않음) → ⑤ 재검증으로 균형 확인. 자동 보정을 배제하는 이유는, 잘못된 자동 정정이 근본 버그를 은폐하고 손실을 키울 수 있기 때문이다.

**멱등성과 exactly-once (I-9).** 결제·취소 등 분개를 발생시키는 쓰기 API는 `X-Idempotency-Key` 기반 멱등 저장소(Redis 캐시 + DB 백업)로 보호된다. 동일 키 재요청 시 비즈니스 로직을 재실행하지 않고 **저장된 이전 응답을 그대로 반환** → 네트워크 타임아웃·재시도가 이중 분개(이중 결제)로 이어지지 않는다. 원장의 균형은 단일 실행을 전제로 하므로, 멱등성은 정합성의 전제 조건이다.

---

## 7. 재무 불변식 요약 (Invariants)

| # | 불변식 | 보장 메커니즘 |
|---|---|---|
| I-1 | 전역 Σ차변 = Σ대변 (항상) | `record()`가 항상 균형 2-leg 생성 + 동일 트랜잭션 원자 커밋 |
| I-2 | `VOUCHER_BALANCE` 원장 net = `voucher.balance` 캐시 | 잔액 변경·원장 기록을 한 트랜잭션에서 동기 처리, 매일 02:00 대조 |
| I-3 | `POINT_BALANCE` 원장 net = Σ`PointAccount.balance` | `PointEarnService.earn/reverseEarn`이 캐시·원장을 동기 기록, 매일 대조 |
| I-4 | 잔액 음수 불가 (`voucher.balance ≥ 0`, `point ≥ 0`) | 분산락 + SELECT FOR UPDATE + 도메인 `require`/잔액 검증 |
| I-5 | 원장 엔트리 불변 (수정/삭제 불가) | `@Immutable` + 취소는 역분개(§5) |
| I-6 | 가맹점 정산 = gross T | 쿠폰 결제 시 미수금 `(T−D)+D = T`, 할인은 `PROMOTION_FUNDING` 부담 |
| I-7 | 취소는 완전 상쇄 (모든 관련 계정 net 0) | 원 분개의 정확한 역분개 + 포인트 역분개(§5) |
| I-8 | 동일 기간 중복 정산 불가 | DB UNIQUE `(merchantId, periodStart, periodEnd)` |
| I-9 | 멱등 키 exactly-once | Redis/DB 멱등 저장소 — 동일 키 재요청 시 저장된 결과 반환(이중 분개 방지) |
| I-10 | 0원 leg 부재 | `LedgerEntry.init { require(amount > 0) }`, 0원 쌍은 생략 |

> 핵심: **잔액은 캐시, 원장은 진실.** 둘의 불일치는 자동 보정하지 않고 사람에게 보고한다 — 이것이 금융 정합성의 마지막 방어선이다.

---

## 8. 설계 경계 (Scope Boundary)

이 원장 모델이 **책임지는 것**과 **외부에 위임/보류하는 것**을 명시해, 정합성의 경계를 분명히 한다.

- **실제 현금 이체는 범위 밖**: `REFUND_PAYABLE`·`SETTLEMENT_PAYABLE`은 *지급 의무의 인식*까지만 기록한다. 회원 환불 입금과 가맹점 정산 이체(PG/뱅킹 API)는 외부 시스템 책임이며, 완료 시 반대 leg(`... → MEMBER_CASH` / 정산 계좌)로 해소하는 것이 다음 단계 설계다. 그래서 두 payable 계정이 현재 차변(음의 대변) 잔액을 누적한다(§2 부호 관례).
- **예약 계정**: `REVENUE_DISCOUNT`(할인 수익)과 `MANUAL_ADJUSTMENT`(수동 정정)은 enum에 선언돼 있으나 자동 분개 경로가 아직 없다. 손익 분리 보고나 운영자 정정이 필요해질 때 활성화할 확장 지점이다.
- **포인트 수명주기**: 현재 `EARN` / `CANCEL`(적립·적립취소)만 구현한다. 포인트 **사용(SPEND)·만료(EXPIRE)** 는 STRETCH이며, 도입 시 `POINT_BALANCE`를 대변으로 줄이는 분개와 `PointTransactionType` 확장이 필요하다.
- **gross 정산 모델 고정**: 가맹점은 항상 gross `T`를 정산받고 할인 `D`는 플랫폼(`PROMOTION_FUNDING`)이 부담한다(I-6). 쿠폰 스택(중첩 적용)은 `Promotion.stackable = false`로 고정 — 다중 재원 안분은 별도 설계 과제다.

이 경계 덕분에, 본 시스템 내부의 모든 자금 이동은 단일 원장에서 **닫힌 형태로 균형**을 이루고(I-1), 외부 이체는 멱등 인터페이스를 통해 안전하게 접합된다.
