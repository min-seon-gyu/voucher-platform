# 커머스 백엔드 (마켓플레이스)

대용량 트래픽에서 **주문·결제·재고·정산의 정합성**을 보장하는 마켓플레이스 커머스 백엔드.
복식부기 원장, 보상 트랜잭션, 분산락 기반 동시성 제어로 **재무 무결성**·**재고 정확성**·**동시성 안전**을 보장한다.

> **전환(pivot) 진행 중**: 지역상품권(voucher/region) 도메인에서 **오늘의집형 커머스**(상품·재고·장바구니·주문·판매자 정산)로 재편 중이다.
> 커머스 코어(seller·product·inventory·cart·order)와 재무 근간(ledger·point·promotion·transaction) 재배선은 완료됐고, 레거시(voucher·region·audit)는 제거 예정이다(하단 로드맵).

---

## 핵심 역량

| 축 | 설명 | 핵심 코드 |
|---|---|---|
| **주문·결제·정산 신뢰성** | 복식부기 원장 + 보상 트랜잭션으로 주문 결제/취소·정산 분개의 재무 정합성 보장. 멱등성 exactly-once. | `OrderService`, `LedgerService`, `SettlementService` |
| **재고 정확성 · 대용량 동시성** | SKU 재고를 Redisson 분산락 + DB 비관적 락 이중 방어로 차감 → 초과판매(oversell) 0. k6 부하테스트로 증명. | `StockService`, `StockLockManager` |
| **판매자 정산 자동화** | 다판매자 주문 라인을 판매자별·주기별 합산. Spring Batch 결산 배치로 대량 정산 자동화. | `SettlementService`, `SettlementBatchConfig` |

---

## 도메인 구성

Aggregate 중심 모듈러 모놀리스. 각 모듈은 `domain / application / infrastructure / interfaces` 4계층(헥사고날)으로 분리된다. 패키지 루트 `com.commerce`.

### 🛒 커머스 코어
| 도메인 | 역할 |
|---|---|
| **order** | 다판매자 **주문**(Order/OrderLine). 결제 = SKU 정준락 → 한 트랜잭션에서 재고 차감 + 원장(gross) + 포인트 적립, 취소 = 보상 트랜잭션 |
| **cart** | 회원별 **장바구니**(CartItem, 다판매자 카트) |
| **product** | **상품 카탈로그** — Product + SKU(옵션 JSON·가격), 판매자 소유, DRAFT→ON_SALE |
| **inventory** | SKU별 **재고**(Stock) — 분산락 + `SELECT FOR UPDATE` 이중 방어로 초과판매 차단 |
| **seller** | **판매자(입점 브랜드)** — 등록/승인 생애주기 + **정산**(주문 라인 합산) + **결산 배치**(Spring Batch) |

### 💰 재무·지원
| 도메인 | 역할 |
|---|---|
| **ledger** | **복식부기 원장** — 모든 자금이동을 DEBIT/CREDIT 2행으로 기록 + 정합성 검증 배치 |
| **point** | **포인트** — 결제 시 적립, 취소 시 역분개, 원장 연동 |
| **promotion** | **프로모션/쿠폰** — Redis-Lua 예산 원자제어, 쿠폰 발급 |
| **transaction** | **거래** 기록 + 보상 트랜잭션 |
| **member** | **회원** — JWT 인증, 역할(USER / SELLER / ADMIN) |

### 🔧 공통/인프라
| 모듈 | 역할 |
|---|---|
| **common** | 표준 응답 봉투 · 요청추적(MDC) · 멱등성 · 보안유틸 · 예외 · *(audit — 제거 예정)* |
| **config** | Security / JWT / Redis(Redisson) / QueryDSL / Swagger / Batch |

### 🗑️ 레거시 (제거 예정)
| 도메인 | 비고 |
|---|---|
| **voucher** | 선불 상품권(발행/결제/환불/철회/만료). 커머스 주문으로 대체됨 |
| **region** | 지자체/정책. `seller`가 정산주기를 참조 중 → 제거 시 seller로 흡수 |

---

## 핵심 흐름

```
상품 등록(판매자) ──→ 판매 개시 ──→ 장바구니 담기 ──→ 주문/결제 ──→ (주문 취소) ──→ 판매자 정산
     │                              │           │                 │                    │
     └ Product+SKU+초기재고      다판매자      재고 차감          보상 트랜잭션         OrderLine을 판매자별
       (한 tx 원자 등록)          카트         +원장+포인트        (재고 복원+역분개)     합산 → 결산 배치
```

### 주문 결제 분개 (무쿠폰)

```
DEBIT  CUSTOMER_CASH   (T)   고객 결제 현금 유입
CREDIT SELLER_PAYABLE  (T)   플랫폼이 판매자에게 지급할 정산액(gross)
+ 포인트 적립: DEBIT POINT_BALANCE / CREDIT POINT_FUNDING
```

주문 취소는 위 분개를 반대로(ORDER_CANCEL) 기록하고 재고를 복원하며 포인트를 역적립한다.
판매자 정산 확정 시: `DEBIT SELLER_PAYABLE / CREDIT SETTLEMENT_PAYABLE`.

---

## 주요 설계 결정

1. **복식부기 원장** — 단순 잔액 차감은 자금 추적이 불가능하다. 모든 금전 변동을 DEBIT+CREDIT 2행으로 기록해 감사 시 원장만으로 완전한 자금 추적이 가능하다. `LedgerVerificationService`가 캐시 잔액과 원장 net을 대조한다.
2. **재고 차감 이중 방어** — 동일 SKU 동시 주문은 초과판매를 유발한다. Redisson 분산락(1차) → DB `SELECT FOR UPDATE`(2차) → **주문과 같은 트랜잭션**에서 차감 → 실패 시 원자 롤백(별도 보상 불필요). 다품목은 skuId 오름차순 정준 락으로 데드락을 방지한다.
3. **보상 트랜잭션** — 주문 취소를 DELETE/수정으로 처리하면 "왜 바뀌었는가"를 증명할 수 없다. 원 주문을 불변 보존하고 역방향 분개 + 재고 복원 + 포인트 역적립으로 처리한다.
4. **gross 판매자 정산** — 판매자는 할인과 무관하게 주문총액(gross)을 받아야 한다. OrderLine에 sellerId를 비정규화해 판매자별 합산을 단순화하고, 쿠폰 출연분은 별도 계정으로 분리 추적한다.
5. **멱등키 이중 저장** — 구매/결제/취소 등 과금 API를 Redis(1차) + DB(2차)로 중복 방지. 중복 시 원 응답을 원 상태코드로 반환.
6. **결산 배치(Spring Batch)** — 기준일 파라미터로 APPROVED 판매자 전체를 청크 처리. 재실행 멱등·0원 스킵·단건 실패 skip·검증 스텝으로 대량 결산을 자동화.

---

## 동시성 제어 전략

| 작업 | 전략 | 방지하는 장애 |
|------|------|-------------|
| 주문 결제(재고 차감) | Redisson 분산락 + DB 비관적 락 + 주문 tx 내 차감 | 초과판매(oversell), 락-커밋 역전 |
| 다품목 주문 | skuId 오름차순 정준 락 | 교차 데드락 |
| 포인트 적립/취소 | `SELECT FOR UPDATE`(회원별) + `INSERT IGNORE` 행 보장 | 동시 적립 경합, 갭 락 데드락 |
| 쿠폰 예산 | Redis-Lua 원자 카운터(INCRBY+검증+DECRBY 롤백) | 예산 초과, 예산 누수 |
| 정산 생성 | DB Unique Constraint(seller_id + period) | 중복 정산 |

---

## API 엔드포인트 (커머스)

모든 응답은 `ApiResponse<T>`(`{ success, data, error }`)로 래핑. (인증) = JWT 필요.

| 모듈 | Method | Endpoint | 설명 |
|------|--------|----------|------|
| **회원** | POST | `/api/v1/members/register` · `/login` | 가입 · 로그인(JWT) |
| **판매자** | POST | `/api/v1/sellers` | 등록(인증, 본인 소유) |
| | POST | `/api/v1/sellers/{id}/approve` `/reject` `/suspend` `/terminate` | 심사/운영(ADMIN) |
| **상품** | POST | `/api/v1/products` · `/{id}/on-sale` | 등록·판매개시(판매자 소유주) |
| | GET | `/api/v1/products` · `/{id}` | 목록·상세(공개, SKU+재고 포함) |
| **재고** | PUT | `/api/v1/admin/inventory/skus/{skuId}` | 입고/정정(ADMIN) |
| **장바구니** | GET·POST·PUT·DELETE | `/api/v1/cart` · `/cart/items/{skuId}` | 조회·담기·수량·삭제(인증, 본인) |
| **주문** | POST | `/api/v1/orders` · `/{id}/cancel` | 체크아웃·취소(인증, 본인) |
| | GET | `/api/v1/orders/{id}` | 조회(인증, 본인/ADMIN) |
| **정산** | POST | `/api/v1/settlements/calculate` `/{id}/confirm` `/pay` `/dispute` | 정산 생성/확정/지급/이의(ADMIN) |
| | POST | `/api/v1/admin/settlements/batch` | 결산 일괄 배치 실행(ADMIN) |
| **원장(admin)** | GET·POST | `/api/v1/admin/ledger/...` | 거래별/계정별/글로벌 조회, 정합성 검증 |
| **프로모션** | POST | `/api/v1/promotions` · `/{id}/coupons` | 생성(ADMIN)·쿠폰 발급(인증) |

Swagger UI: `http://localhost:8080/swagger-ui.html`

---

## 기술 스택

| 항목 | 기술 |
|------|------|
| Language / Framework | Kotlin 1.9.23 / Java 17 · Spring Boot 3.2.5 |
| ORM | Spring Data JPA + QueryDSL 5.1.0 |
| Batch | Spring Batch 5.1 (결산 일괄 정산 Job) |
| DB | MySQL 8 + Flyway (V1→V11, `ddl-auto: validate`) |
| Cache / Lock | Redis 7 (Redisson 3.27.2) |
| Auth | JWT (jjwt 0.12.5) + Spring Security |
| Test | JUnit 5 + Kotest 5.8.1 + MockK + Testcontainers(MySQL/Redis) + Spring Batch Test |
| Monitoring | Spring Actuator + Micrometer (Prometheus / Grafana) |
| Build / Infra | Gradle 8 (Kotlin DSL) · Docker Compose · Dockerfile · GitHub Actions CI |

> Kafka(spring-kafka)는 현재 audit 전달에 사용 중이며, **주문 이벤트 파이프라인으로 이관 예정**이다(감사 도메인 제거와 함께).

---

## 실행 방법

```bash
# 1. 인프라 (MySQL + Redis + Kafka + Prometheus + Grafana)
docker compose up -d
# 2. 애플리케이션
./gradlew bootRun
# 3. 테스트 (Testcontainers가 MySQL/Redis 자동 구동)
./gradlew test
```

| 서비스 | URL |
|--------|-----|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Grafana | http://localhost:3000 (admin/admin) |
| Prometheus | http://localhost:9090 |

---

## 테스트

Testcontainers(MySQL + Redis) 위에서 `IntegrationTestSupport` / `TestFixtures`로 실제 인프라를 구동해 검증한다.

| 분류 | 대표 검증 |
|------|----------|
| **커머스** | 상품+SKU+재고 원자 등록, 다판매자 주문 결제/취소(원장 균형), 판매자 정산(주문 라인 합산) |
| **동시성** | 재고 5 SKU에 10 동시 주문 → 정확히 5 성공/5 OUT_OF_STOCK, 최종 0(초과판매 없음) |
| **정산** | 주기(일/주/월) 산출, 확정 재계산(취소 제외), 결산 배치(멱등) |
| **재무** | 복식부기 2행·글로벌 균형, 포인트 적립/취소 정합성 |
| **멱등성** | 동시 중복 요청 단일 처리 + 동일 응답 |

---

## 전환(pivot) 로드맵

- ✅ **Phase 1** seller 개명 · **Phase 2** 상품+재고 · **Phase 3** 장바구니+주문+결제/취소 재배선 · **Phase 4a** 정산을 주문 기준으로 재배선
- ⏳ **Phase 4b** `voucher`·`region` 제거 + 얽힌 결합(promotion·transaction·ledger) 해체 + 죽은 계정/이벤트 정리
- ⏳ **감사 제거 + Kafka를 주문 이벤트로 이관** (Outbox+DLT+재조정 파이프라인을 주문 이벤트 전달로 재활용)
- ⏳ **Phase 4c** docs 상세 재작성(현재 `docs/`는 지역상품권 시절 설계 문서 — pivot 완료 후 갱신)

---

## 프로젝트 문서

- [`docs/01-domain-design.md`](docs/01-domain-design.md) — 도메인 & 비즈니스 규칙
- [`docs/02-architecture-decisions.md`](docs/02-architecture-decisions.md) — 아키텍처 설계 결정
- [`docs/03-financial-design.md`](docs/03-financial-design.md) — 재무·회계(복식부기) 설계

> ⚠️ `docs/`는 상당 부분 지역상품권 시절 기준이다. **최신 아키텍처·도메인은 이 README를 우선 참조**하고, 상세 문서는 Phase 4c에서 갱신한다.
