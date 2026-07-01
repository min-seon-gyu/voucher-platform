# 03. 재무·회계 설계 — 복식부기 원장

커머스에서 발생하는 모든 자금 이동의 **정합성 단일 레퍼런스**. 복식부기 원장, 계정과목, 연산별 분개, 보상 트랜잭션, 정합성 검증을 다룬다.
모든 분개는 `com.commerce.ledger / .order / .point / .seller` 코드에 대조된다.

---

## 왜 복식부기인가

단순 잔액 필드 차감은 "돈이 어디서 와서 어디로 갔는지" 추적할 수 없다. 결제·정산·포인트가 얽힌 커머스에서, 감사 시 **원장만으로 완전한 자금 추적**이 가능해야 한다.

- 모든 금전 변동을 **DEBIT 1행 + CREDIT 1행 = 2행**의 `LedgerEntry`로 기록한다.
- `LedgerEntry`는 **불변**(`@Immutable`) — 수정/삭제 대신 역분개(보상)로만 되돌린다.
- 캐시 잔액(`PointAccount.balance`)을 두되, 진실의 원천은 항상 원장이다. 검증 배치가 둘을 대조한다.

```kotlin
// LedgerService.record — 항상 2행
fun record(debitAccount, creditAccount, amount, transactionId, entryType): List<LedgerEntry> {
    val debit  = LedgerEntry(account = debitAccount,  side = DEBIT,  amount, transactionId, entryType)
    val credit = LedgerEntry(account = creditAccount, side = CREDIT, amount, transactionId, entryType)
    return ledgerRepository.saveAll(listOf(debit, credit))
}
```

---

## 계정과목 (AccountCode)

| 계정 | 정상 잔액 | 의미 |
|------|:--------:|------|
| `CUSTOMER_CASH` | 차변 | 고객이 지불한 결제 현금 유입 |
| `SELLER_PAYABLE` | 대변 | 플랫폼이 판매자에게 지급할 정산액(gross) |
| `SETTLEMENT_PAYABLE` | 대변 | 정산 확정으로 지급이 확정된 금액 |
| `POINT_BALANCE` | 차변 | 적립 포인트 잔액 |
| `POINT_FUNDING` | 대변 | 플랫폼 포인트 적립 출연 |
| `PROMOTION_FUNDING` | 대변 | 플랫폼 쿠폰/프로모션 출연 |

---

## 연산별 분개

### 1) 주문 결제 (ORDER_PAYMENT)

주문총액 `T`(무쿠폰 기준 결제액과 동일)에 대해:

```
DEBIT  CUSTOMER_CASH   T      고객 현금 유입
CREDIT SELLER_PAYABLE  T      판매자 미지급금(gross) 누적
```

동시에 포인트 적립(실제 결제액의 1%, `P`):

```
DEBIT  POINT_BALANCE   P      (POINT_EARN)
CREDIT POINT_FUNDING   P
```

- 재고 차감·주문/라인 저장·거래 생성·원장·포인트 적립이 **모두 같은 트랜잭션**에서 처리된다(원자성).
- 다판매자 주문의 판매자별 귀속은 `OrderLine.sellerId`로 추적한다(원장은 총액 기준, 판매자별 상세는 라인).

### 2) 주문 취소 (ORDER_CANCEL, 보상 트랜잭션)

원 주문을 수정하지 않고 역분개 + 재고 복원 + 포인트 역적립:

```
DEBIT  SELLER_PAYABLE  T      판매자 미지급금 취소
CREDIT CUSTOMER_CASH   T      고객 현금 환원
```

포인트 역분개(`CANCELLATION`):

```
DEBIT  POINT_FUNDING   P
CREDIT POINT_BALANCE   P      → PointAccount.balance 차감, net 0 복귀
```

- 원 EARN 행은 보존하고 CANCEL 행을 추가한다(감사 추적성).
- 보상 거래는 `originalTransactionId`로 원 결제 거래에 연결된다.

### 3) 정산 확정 (SETTLEMENT)

정산 기간 내 **PAID 주문의 OrderLine 금액을 판매자별 합산**한 `S`에 대해:

```
DEBIT  SELLER_PAYABLE     S   판매자 미지급금 → 지급 확정으로 이전
CREDIT SETTLEMENT_PAYABLE S
```

- 확정 시점에 정산액을 **재계산**한다(스냅샷 신뢰 금지) — calculate 이후 취소된 주문분이 과지급되지 않도록.
- 0원 정산(해당 기간 주문 없음/전부 취소)은 원장 분개를 만들지 않는다.

---

## 정합성 검증 (LedgerVerificationService)

두 가지를 대조한다:

1. **전역 차·대변 균형**: `Σ(전체 DEBIT) == Σ(전체 CREDIT)`. 모든 분개가 2행 균형이므로 항상 성립해야 하며, 깨지면 반쪽 분개가 있다는 신호.
2. **포인트 캐시 vs 원장**: `POINT_BALANCE` 원장 net(차변−대변) == Σ(PointAccount.balance).

- 매일 배치(`@Scheduled`)로 검증하고, 불일치 시 **자동 수정하지 않고** ERROR 로그 + 메트릭으로 사람이 판단하게 한다.
- 관리자 API로 거래별 원장, 계정별 net, 글로벌 차대변, 수동 검증을 조회할 수 있다.

---

## 핵심 원칙

- **원장 기록은 잔액 변경과 같은 트랜잭션에서 동기로.** 이벤트/Kafka로 미루면 커밋 후 장애 시 원장 누락이 생긴다.
- **불변 + 보상.** 되돌릴 때 원 분개를 지우지 않고 역분개를 추가한다 — "왜 금액이 바뀌었는가"를 원장이 증명한다.
- **캐시는 편의, 진실은 원장.** 잔액 캐시는 성능용이며 검증 배치가 원장과의 일치를 지킨다.
