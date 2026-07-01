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

- **Order**: 회원·상태·`totalAmount`(주문총액)·`discountAmount`(쿠폰 할인)·`paidAmount`(실결제액 = 총액 − 할인)·`refundedAmount`(누적 환불 gross)·`couponId`(적용 쿠폰, optional)·`paymentTransactionId`.
- **OrderLine**: SKU 단위 라인(`orderId`, `skuId`, `sellerId`(비정규화), `quantity`, `unitPrice`, `lineAmount`, `refunded`). `sellerId`를 비정규화해 **판매자별 정산 합산**을 단순화한다. `refunded`(라인 환불 플래그)인 라인은 정산 합산에서 제외되고 재환불이 거부된다.
- **상태머신(OrderStatus)**: 결제 동기 완료로 `PAID` 생성. 이후 라인 환불로 `PARTIALLY_REFUNDED`·`REFUNDED`로, 발송 전 전체 취소로 `CANCELLED`로 전이한다.
  ```
  PAID ──부분환불──▶ PARTIALLY_REFUNDED ──잔여 라인 환불──▶ REFUNDED
   │                     └──(추가 부분환불)──┘
   └──취소(발송 전)──▶ CANCELLED
  ```
  - **취소**는 `PAID` + **발송 전(`Shipment` = `PREPARING`)** 에만 허용된다(배송 게이트). 발송/배송 이후의 환불은 셀프 취소가 아니라 **반품 클레임**(운영자 승인) 경로로만 이뤄진다 — 상품 보유 + 전액 환불로 워크플로우를 우회하지 못하게 막는다.
  - **부분/전액 환불**은 라인 단위다. `refundedAmount` 누산기가 `totalAmount`와 같아지면 `REFUNDED`, 아니면 `PARTIALLY_REFUNDED`로 전이한다. 매 환불이 `orders` 행에 versioned UPDATE를 유발하므로, 같은 주문의 동시 환불은 **`@Version` 낙관적 락**으로 직렬화되어 상태 고착을 막는다.
- 한 주문이 **여러 판매자 라인**을 포함할 수 있다(다판매자 주문). 결제/취소/환불의 재무 처리는 [03](03-financial-design.md).

## 배송 (shipping)

- **Shipment**: 주문당 1건(`orderId` 유니크)·상태·택배사(`courier`)·송장번호(`trackingNumber`)·발송/배송완료 시각.
- **상태머신(ShipmentStatus)**: `PREPARING → SHIPPED → DELIVERED`.
  - 결제 완료 시 **주문 트랜잭션과 원자적으로** `PREPARING` 배송이 생성된다(`OrderService`가 `ShippingService.createForOrder` 호출).
  - 운영자가 `ship(courier, trackingNumber)`로 `SHIPPED`, `deliver()`로 `DELIVERED`로 전이한다. 잘못된 전이는 도메인이 예외를 던진다(`INVALID_STATE_TRANSITION`).
- **게이트 역할**: 발송 여부(`PREPARING` 이탈)가 **주문 취소**의 차단선(발송 전에만 취소 가능)이고, `DELIVERED`가 **반품 클레임 요청**의 전제(배송완료 이후에만 반품 가능)가 된다.

## 반품 클레임 (claim)

- **ReturnClaim**: 주문·회원·사유(`ReturnReason`)·상세(`detail`, optional)·상태. 배송완료 후 구매자가 요청하고 운영자가 승인/거절한다.
- **ReturnClaimLine**: 클레임 대상 주문 라인(`claimId`, `orderLineId`). 반품은 **라인 단위**다.
- **사유(ReturnReason)**: `CHANGED_MIND` / `DEFECTIVE` / `WRONG_DELIVERY` / `OTHER`. 현재 슬라이스는 사유 기록까지만 다룬다(귀책 판단·반품배송비 정책은 이후).
- **상태머신(ReturnClaimStatus)**: `REQUESTED → COMPLETED`(승인) / `REQUESTED → REJECTED`(거절).
- **요청 불변식(구매자)**: 배송완료(`DELIVERED`) + 본인 주문 + 대상 라인이 미환불 + 진행 중(`REQUESTED`) 클레임이 선점하지 않은 라인. 위반 시 각각 예외(`RETURN_NOT_ALLOWED` · `ACCESS_DENIED` · `INVALID_CLAIM_LINES` · `LINE_ALREADY_IN_CLAIM`).
- **승인 불변식(운영자)**: 대상 라인의 주문 환불(`OrderService.refundLines`)과 클레임 `COMPLETED` 전이를 **같은 트랜잭션**에서 실행해 원자적으로 커밋한다(부분 성공으로 클레임이 `REQUESTED`에 남지 않도록).
- **이중 환불 차단**: 같은 라인에 대한 동시 요청/동시 승인은 재고락 + in-tx `refunded` 재확인 + `@Version`으로 이중 환불이 막힌다(재무 손실 없음). 경합에서 밀린 쪽은 `REQUESTED`로 남아 운영자가 재시도/거절로 정리한다.

## 포인트 (point)

- **PointAccount**: 회원별 잔액 캐시. **PointTransaction**: `EARN`/`CANCEL` 이력(불변).
- 결제 시 실결제액(`paidAmount` = 총액 − 할인)의 1%를 **결제와 같은 트랜잭션**에서 적립(`point.earn-rate=0.01`, 1원 단위 HALF_UP).
- **불변식**: `POINT_BALANCE` 원장 net(차변−대변) == Σ(PointAccount.balance). 취소 시 원 EARN을 보존하고 `CANCEL`로 역분개해 net 0으로 복귀. 부분환불 시엔 환불 net에 비례해 **잔여 순적립 한도까지 부분 역적립**한다.

## 프로모션/쿠폰 (promotion)

- **Promotion**: 할인 방식(정액/정률)·최소 주문액(`minSpend`)·1인 한도(`perMemberLimit`)·예산 한도(`budgetLimit`)·유효기간·상태.
- **Coupon**: 회원에게 발급된 쿠폰(`ISSUED → REDEEMED / EXPIRED`).
- **체크아웃 할인**: 주문 시 `couponId`(optional)로 쿠폰을 적용한다(무쿠폰 주문은 하위호환). 검증 순서는 소유(본인, 위반 시 `ACCESS_DENIED`)·상태 `ISSUED`·미만료·프로모션 활성·최소결제 충족(`MIN_SPEND_NOT_MET`)·1인 한도(`REDEEMED` 카운트, `COUPON_USAGE_LIMIT_EXCEEDED`)다. 할인액 `D = promotion.calculateDiscount(total).min(total)`(주문총액 초과 불가, 정률은 0원 단위 내림). 결제 확정 시 쿠폰은 같은 트랜잭션에서 `ISSUED → REDEEMED`로 전이한다.
- 예산은 Redis-Lua 원자 카운터로 초과를 막는다(예산 원자 제어). 체크아웃 시 트랜잭션 **밖에서** 예산을 원자 예약(reserve)하고, 다운스트림 실패 시 반환(release)해 보상한다. 쿠폰 **발급**은 멱등(`@Idempotent`)이다.
- 취소·환불은 쿠폰을 되살리지 않는다(단일 사용 쿠폰이 부활하지 않도록 하는 의도적 단순화).

## 거래 (transaction)

- **Transaction**: 자금 이동 기록(`type`·`amount`·`status`·`sellerId`·`originalTransactionId`).
- **유형(TransactionType)**: `ORDER_PAYMENT`(주문 결제) · `ORDER_CANCEL`(취소·환불 보상) · `SETTLEMENT`(정산 확정).
- 취소·환불은 원 거래를 수정하지 않고 `originalTransactionId`로 연결된 **보상 거래**를 새로 만든다.

## 원장 (ledger)

- **LedgerEntry**: 복식부기 분개 1행(`account`·`side`(DEBIT/CREDIT)·`amount`·`transactionId`·`entryType`). **불변**(`@Immutable`).
- 모든 금전 변동은 DEBIT 1행 + CREDIT 1행 = 2행으로 기록된다. 계정·분개는 [03](03-financial-design.md).

## 정산 (settlement, seller 모듈)

- **Settlement**: 판매자·정산 기간(`periodStart`~`periodEnd`)·정산액·상태·이의사유.
- **상태머신(SettlementStatus)**: `PENDING → CONFIRMED → PAID`, `PENDING → DISPUTED → CONFIRMED`.
- 정산액 = 기간 내 **유효 주문(`PAID`·`PARTIALLY_REFUNDED`)의 OrderLine 금액을 판매자별 합산**한다. 환불된 라인(`OrderLine.refunded`)과 전액취소(`CANCELLED`)·전액환불(`REFUNDED`) 주문은 제외되고, 부분환불 주문의 잔여(미환불) 라인은 포함된다.
- 정산 주기(일/주/월)는 `seller.settlementPeriod`에서 KST 역월 기준으로 산출한다. `(seller, periodStart, periodEnd)` 유니크로 중복 정산 방지.
- 확정(`confirm`) 시점에 정산액을 **재계산**한다(스냅샷 신뢰 금지) — calculate 이후 취소·환불된 주문분을 과지급하지 않기 위함.

---

## 공통 횡단 관심사 (common)

- **표준 응답** `ApiResponse<T>`: `{ success, data, error }`로 일관 래핑.
- **요청 추적** `RequestTraceFilter`: `X-Request-Id`를 MDC `requestId`로 전 로그에 전파.
- **멱등성** `@Idempotent` + `IdempotencyInterceptor`: `Idempotency-Key` 헤더로 중복 요청을 정확히 1회 처리.
- **JWT 인증**: principal = `memberId`, 컨트롤러가 본인 자원 인가를 강제.
- **스키마**: Flyway가 단일 진실의 원천(`ddl-auto: validate`).

