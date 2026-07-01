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

주문총액 `T`, 쿠폰 할인 `D`(무쿠폰이면 0), 실결제액 `paid = T − D`.

**무쿠폰(D=0)** — 실결제액과 gross가 같다:

```
DEBIT  CUSTOMER_CASH   T      고객 현금 유입
CREDIT SELLER_PAYABLE  T      판매자 미지급금(gross) 누적
```

**쿠폰 할인(D>0)** — 고객 현금은 `paid`만 유입되고, 할인분 `D`는 플랫폼이 출연한다. 판매자는 항상 gross `T`를 받는다:

```
DEBIT  CUSTOMER_CASH      T−D    고객 현금 유입(실결제액)              [ORDER_PAYMENT]
CREDIT SELLER_PAYABLE     T−D

DEBIT  PROMOTION_FUNDING  D      플랫폼 쿠폰 출연                      [COUPON_SUBSIDY]
CREDIT SELLER_PAYABLE     D
```

→ `SELLER_PAYABLE` 대변 합 = `T`(gross). 고객 현금은 `T−D`, 플랫폼 출연은 `D`.

동시에 포인트 적립(**실결제액** `paid`의 1%, `P`):

```
DEBIT  POINT_BALANCE   P      (POINT_EARN)
CREDIT POINT_FUNDING   P
```

- 재고 차감·주문/라인 저장·거래/원장·포인트 적립·배송(PREPARING) 생성·쿠폰 사용확정(ISSUED→REDEEMED)이 **모두 같은 트랜잭션**에서 처리된다(원자성).
- 쿠폰 예산은 트랜잭션 **밖**에서 원자적으로 예약(reserve)하고, 다운스트림(락/tx) 실패 시 release로 보상한다.
- **경계(100% 할인, paid=0)**: 현금 leg는 amount가 0이 되므로 생략한다(`원장 amount>0` 불변식). 이때 `SELLER_PAYABLE`은 `COUPON_SUBSIDY` 한 쌍만으로 gross를 채운다.
- 다판매자 주문의 판매자별 귀속은 `OrderLine.sellerId`로 추적한다(원장은 총액 기준, 판매자별 상세는 라인).

### 2) 주문 취소 (ORDER_CANCEL, 보상 트랜잭션)

발송 전(`PREPARING`) 주문의 **전체 취소**. 원 주문을 수정하지 않고 역분개 + 재고 복원 + 포인트 역적립으로 되돌린다.
결제 분개를 정확히 역으로 — 고객에겐 **실결제액 `paid`**만 환급하고, 플랫폼 출연 `D`는 **환입**한다:

```
DEBIT  SELLER_PAYABLE     paid   판매자 미지급금 취소(현금 환급분)
CREDIT CUSTOMER_CASH      paid   고객 현금 환원

DEBIT  SELLER_PAYABLE     D      판매자 미지급금 취소(출연 환입분)
CREDIT PROMOTION_FUNDING  D      플랫폼 출연 회수
```

포인트 역분개(`CANCELLATION`):

```
DEBIT  POINT_FUNDING   P
CREDIT POINT_BALANCE   P      → PointAccount.balance 차감, net 0 복귀
```

- 두 쌍의 `SELLER_PAYABLE` 차변 합 = `paid + D = T`(gross) → 결제 시 쌓인 `SELLER_PAYABLE`이 정확히 해소된다. 취소 후 **모든 계정 net 0** 복귀.
- 원 결제·EARN 행은 보존하고 취소 행을 추가한다(감사 추적성). 보상 거래는 `originalTransactionId`로 원 결제 거래에 연결된다.
- **경계**: `paid=0`(100% 할인)이면 현금 환급 leg를, `D=0`(무쿠폰)이면 출연 환입 leg를 생략한다(0원 leg 미기록).
- 발송/배송 후에는 셀프 취소가 막히며(배송 게이트), 환불은 반품 클레임 승인(3절)으로만 이뤄진다.

### 3) 부분 환불 (라인 단위, ORDER_CANCEL)

배송완료 후 반품 클레임이 승인되면 대상 **라인만** 환불한다(`OrderService.refundLines`). 재고 복원 + 원장 역분개 + 포인트 부분 역적립을 한 트랜잭션에서 처리하고, 클레임 완료 전이를 같은 tx 콜백으로 실행해 원자화한다.

- **라인별 배분**: 할인 `D`·적립 `P`를 라인 금액 비율로 **최대잔여(largest-remainder)** 배분해 환불분을 산정한다. 배분은 전체 라인에 대해 결정적이므로, 여러 번의 부분환불이 누적돼도 합계가 원 `D`·원 `P`와 **정확히** 일치한다(페니 드리프트 없음).
- 환불 대상 합산: `refundGross`(라인 금액 합), `refundDiscount`(배분된 할인 합), `refundNet = refundGross − refundDiscount`.

결제 분개의 역분개(환불 대상분만):

```
DEBIT  SELLER_PAYABLE     refundNet       고객 현금 환급(net)
CREDIT CUSTOMER_CASH      refundNet

DEBIT  SELLER_PAYABLE     refundDiscount  플랫폼 출연 환입
CREDIT PROMOTION_FUNDING  refundDiscount
```

포인트는 환불 net에 비례해 **부분 역적립**(`reverseEarnAmount`) — 잔여 순적립(EARN 합 − CANCEL 합)을 한도로 캡해 초과 역분개를 막는다.

- **상태 전이**: `Order.refundedAmount` 누산기가 결정한다. 누적 환불 gross가 `total`과 같아지면 `REFUNDED`, 아니면 `PARTIALLY_REFUNDED`. 환불 라인은 `OrderLine.refunded` 플래그로 표시한다. 누산값 변경이 `orders` 행에 versioned UPDATE를 유발해, 같은 주문의 동시 환불을 `@Version` 낙관락으로 직렬화한다(상태 고착 방지).
- **전액 환불 시**: 모든 라인이 환불되면 배분 합이 원값과 일치하므로 계정별 net이 **0으로 복귀** — 전체 취소(2절)와 동일한 재무 결과.
- **정산 반영**: `sumSellerSalesInPeriod`은 **환불 라인(`ol.refunded`)을 제외**하고, 부분환불 주문(`PARTIALLY_REFUNDED`)의 **잔여 라인은 포함**한다(주문 상태 `PAID`·`PARTIALLY_REFUNDED`만 합산). 따라서 환불된 매출은 판매자 정산에서 자동으로 빠진다.
- **경계**: 배분 결과 `net=0`인 라인, `paid=0`(100% 할인) 등에서 0원 leg는 기록하지 않는다(`원장 amount>0` 불변식). 거래 금액은 항상 `refundGross`(>0)를 쓴다.

### 4) 정산 확정 (SETTLEMENT)

정산 기간 내 **미환불 라인을 판매자별 합산**한 `S`에 대해(주문 상태 `PAID`·`PARTIALLY_REFUNDED`만 포함, `ol.refunded=false`):

```
DEBIT  SELLER_PAYABLE     S   판매자 미지급금 → 지급 확정으로 이전
CREDIT SETTLEMENT_PAYABLE S
```

- 확정 시점에 정산액을 **재계산**한다(스냅샷 신뢰 금지) — calculate 이후 취소·환불된 주문분이 과지급되지 않도록.
- 0원 정산(해당 기간 주문 없음/전부 취소·환불)은 원장 분개를 만들지 않는다.

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
