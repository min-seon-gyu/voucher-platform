# 01. 도메인 설계 — 모델 · 상태머신 · 불변식

마켓플레이스 커머스의 도메인 모델. 각 Aggregate의 엔티티, 상태 전이, 지켜야 할 불변식(invariant)을 정리한다.
패키지 루트 `com.commerce`, 4계층(`domain / application / infrastructure / interfaces`).

---

## 회원 (member)

- **Member**: 이메일(유니크)·BCrypt 비밀번호·역할·상태.
- **역할(MemberRole)**: `USER` / `SELLER` / `ADMIN`. 판매자 등록 시 `USER` → `SELLER`로 승격.
- **상태머신(MemberStatus)**: `PENDING → ACTIVE → SUSPENDED → WITHDRAWN`.
- 인증: 로그인 시 JWT 발급. principal = `memberId`. 컨트롤러는 `SecurityUtils.currentMemberId()`로 신원을 도출하며 **본문 memberId는 신뢰하지 않는다**(타인 자원 접근·권한 상승 차단).

## 판매자 (seller)

- **Seller**: 상호·사업자번호·업종(`SellerCategory`)·**정산 주기(`SettlementPeriod`)**·소유주(Member)·상태.
- **정산 주기(SettlementPeriod)**: `DAILY` / `WEEKLY` / `MONTHLY`. 판매자가 직접 보유하며 정산 기간 산출의 기준이 된다.
- **상태머신(SellerStatus)**:
  ```
  PENDING_APPROVAL ──approve──▶ APPROVED ──suspend──▶ SUSPENDED
        │                          │ ▲                   │
        └──reject──▶ REJECTED      │ └─────unsuspend──────┘
                                   └──terminate──▶ TERMINATED (SUSPENDED에서도 가능)
  ```
- 잘못된 전이는 도메인 엔티티가 예외를 던진다(예: `PENDING_APPROVAL`에서 바로 `TERMINATED` 불가).
- 상품 등록·정산의 소유권은 `seller.owner`(Member)로 검증한다.

## 상품 (product)

- **Product**: 상품명·설명·카테고리(`ProductCategory`)·판매자(sellerId)·상태.
- **상태머신(ProductStatus)**: `DRAFT → ON_SALE → SUSPENDED`(다시 `ON_SALE` 가능). 판매 중(`ON_SALE`)인 상품만 주문 가능.
- **Sku**: 상품의 옵션 조합별 판매/재고 단위. `productId` · `skuCode`(유니크) · `optionName`(표시용) · `options`(JSON, 예 `{"색상":"블랙","사이즈":"L"}`) · `price`.
- 상품 등록은 **Product + SKU들 + 초기 재고**를 한 트랜잭션에서 원자적으로 생성한다. 장바구니·주문·재고 차감의 기본 단위는 **SKU**다.

## 재고 (inventory)

- **Stock**: SKU별 재고(`skuId` 유니크 · `quantity`).
- **불변식**: `quantity >= 0` (초과판매 금지). `deduct(qty)`는 부족하면 `OUT_OF_STOCK` 예외.
- 차감/복원의 동시성 안전은 상위 서비스의 분산락 + 비관적 락으로 보장한다([02](02-architecture-decisions.md)).

## 장바구니 (cart)

- **CartItem**: 회원별 항목(`memberId`, `skuId`, `quantity`). `(member_id, sku_id)` 유니크 → 같은 SKU는 수량 가산.
- 한 회원의 카트에 **여러 판매자의 SKU가 공존**할 수 있다(다판매자 카트).

## 주문 (order)

- **Order**: 회원·상태·`totalAmount`(주문총액)·`discountAmount`·`paidAmount`·`paymentTransactionId`.
- **OrderLine**: SKU 단위 라인(`orderId`, `skuId`, `sellerId`(비정규화), `quantity`, `unitPrice`, `lineAmount`). `sellerId`를 비정규화해 **판매자별 정산 합산**을 단순화한다.
- **상태머신(OrderStatus)**: `PAID`(결제 동기 완료로 생성) → `CANCELLED`. 취소는 `PAID`에서만 가능.
- 한 주문이 **여러 판매자 라인**을 포함할 수 있다(다판매자 주문). 결제/취소의 재무 처리는 [03](03-financial-design.md).

## 포인트 (point)

- **PointAccount**: 회원별 잔액 캐시. **PointTransaction**: `EARN`/`CANCEL` 이력(불변).
- 결제 시 실제 결제액의 1%를 **결제와 같은 트랜잭션**에서 적립(`point.earn-rate=0.01`, 1원 단위 HALF_UP).
- **불변식**: `POINT_BALANCE` 원장 net(차변−대변) == Σ(PointAccount.balance). 취소 시 원 EARN을 보존하고 `CANCEL`로 역분개해 net 0으로 복귀.

## 프로모션/쿠폰 (promotion)

- **Promotion**: 할인 방식(정액/정률)·최소 주문액·1인 한도·예산 한도·유효기간·상태.
- **Coupon**: 회원에게 발급된 쿠폰(`ISSUED → REDEEMED / EXPIRED`).
- 예산은 Redis-Lua 원자 카운터로 초과를 막는다(예산 원자 제어). 쿠폰 **발급**은 멱등(`@Idempotent`)이다.

## 거래 (transaction)

- **Transaction**: 자금 이동 기록(`type`·`amount`·`status`·`sellerId`·`originalTransactionId`).
- **유형(TransactionType)**: `ORDER_PAYMENT`(주문 결제) · `ORDER_CANCEL`(취소 보상) · `SETTLEMENT`(정산 확정).
- 취소는 원 거래를 수정하지 않고 `originalTransactionId`로 연결된 **보상 거래**를 새로 만든다.

## 원장 (ledger)

- **LedgerEntry**: 복식부기 분개 1행(`account`·`side`(DEBIT/CREDIT)·`amount`·`transactionId`·`entryType`). **불변**(`@Immutable`).
- 모든 금전 변동은 DEBIT 1행 + CREDIT 1행 = 2행으로 기록된다. 계정·분개는 [03](03-financial-design.md).

## 정산 (settlement, seller 모듈)

- **Settlement**: 판매자·정산 기간(`periodStart`~`periodEnd`)·정산액·상태·이의사유.
- **상태머신(SettlementStatus)**: `PENDING → CONFIRMED → PAID`, `PENDING → DISPUTED → CONFIRMED`.
- 정산액 = 기간 내 **PAID 주문의 OrderLine 금액을 판매자별 합산**(취소 주문은 `CANCELLED`이라 자동 제외).
- 정산 주기(일/주/월)는 `seller.settlementPeriod`에서 KST 역월 기준으로 산출한다. `(seller, periodStart, periodEnd)` 유니크로 중복 정산 방지.
- 확정(`confirm`) 시점에 정산액을 **재계산**한다(스냅샷 신뢰 금지) — calculate 이후 취소된 주문분을 과지급하지 않기 위함.

---

## 공통 횡단 관심사 (common)

- **표준 응답** `ApiResponse<T>`: `{ success, data, error }`로 일관 래핑.
- **요청 추적** `RequestTraceFilter`: `X-Request-Id`를 MDC `requestId`로 전 로그에 전파.
- **멱등성** `@Idempotent` + `IdempotencyInterceptor`: `Idempotency-Key` 헤더로 중복 요청을 정확히 1회 처리.
- **JWT 인증**: principal = `memberId`, 컨트롤러가 본인 자원 인가를 강제.
- **스키마**: Flyway가 단일 진실의 원천(`ddl-auto: validate`).
