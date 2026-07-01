# 커머스 백엔드 (마켓플레이스)

여러 판매자가 입점하는 마켓플레이스 커머스 백엔드. **커머스 도메인의 설계 실험과 트러블슈팅 과정을 학습**하기 위한 프로젝트로,
대용량 동시성·재무 정합성·이벤트 드리븐·멱등성 같은 백엔드 난제를 실제 코드로 다룬다.

상품·재고·장바구니·주문·결제·정산을 직접 설계하고, 각 지점에서 발생하는 **동시성/정합성 문제를 어떤 전략으로 해결했는지**에 초점을 둔다.

---

## 학습 주제

| 주제 | 이 프로젝트에서 다루는 것 | 핵심 코드 |
|------|------------------------|----------|
| **재무 정합성** | 복식부기 원장으로 모든 자금 이동을 2행 분개. 정합성 검증 배치로 캐시 잔액 vs 원장 대조. | `LedgerService`, `LedgerVerificationService` |
| **재고 정확성 · 동시성** | SKU 재고를 분산락 + DB 비관적 락 이중 방어로 차감 → 초과판매(oversell) 0. 다품목은 정준 락 순서로 데드락 방지. | `StockService`, `StockLockManager` |
| **이벤트 드리븐** | 주문 이벤트를 Transactional Outbox → Kafka → 소비자로 at-least-once 전달(DLT·재조정). | `OrderOutboxRecorder`, `OrderEventKafkaConfig` |
| **멱등성** | 체크아웃 중복 요청을 Redis+DB 이중 저장으로 정확히 1회 처리. | `@Idempotent`, `IdempotencyInterceptor` |
| **보상 트랜잭션** | 주문 취소를 삭제/수정이 아니라 역분개 + 재고 복원 + 포인트 역적립으로 처리. | `OrderService.cancelOrder` |
| **정산 자동화** | 판매자별 주문 라인을 주기별 합산. Spring Batch로 대량 결산 자동화. | `SettlementService`, `SettlementBatchConfig` |

---

## 도메인 구성

Aggregate 중심 모듈러 모놀리스. 각 모듈은 `domain / application / infrastructure / interfaces` 4계층(헥사고날)으로 분리된다. 패키지 루트 `com.commerce`.

### 커머스 코어
| 도메인 | 역할 |
|---|---|
| **seller** | 판매자(입점 브랜드) — 등록/승인 생애주기, 정산, 결산 배치 |
| **product** | 상품 카탈로그 — `Product` + `Sku`(옵션 조합·가격), DRAFT→ON_SALE |
| **inventory** | SKU별 재고(`Stock`) — 분산락 + `SELECT FOR UPDATE`로 초과판매 차단 |
| **cart** | 회원 장바구니(`CartItem`) — 여러 판매자 SKU 혼재 가능 |
| **order** | 주문(`Order`/`OrderLine`) — 다판매자, 결제 시 재고 차감+원장+포인트, 취소 시 보상 트랜잭션 |

### 재무·지원
| 도메인 | 역할 |
|---|---|
| **ledger** | 복식부기 원장(`LedgerEntry`, 불변) + 정합성 검증 |
| **point** | 포인트(`PointAccount`/`PointTransaction`) — 결제 적립/취소 역분개 |
| **promotion** | 프로모션/쿠폰 — 예산 원자 제어(Redis-Lua), 쿠폰 발급 |
| **transaction** | 거래 기록 + 보상 트랜잭션 |
| **member** | 회원 — JWT 인증, 역할(USER / SELLER / ADMIN) |

### 공통/인프라
| 모듈 | 역할 |
|---|---|
| **common** | 표준 응답 봉투 · 요청추적(MDC) · 멱등성 · 보안유틸 · 예외 · 주문 이벤트 파이프라인 |
| **config** | Security / JWT / Redis(Redisson) / QueryDSL / Swagger / Batch |

---

## 핵심 흐름

```
판매자 등록·승인 ──→ 상품 등록(SKU+재고) ──→ 판매 개시 ──→ 장바구니 담기 ──→ 주문/결제 ──→ (취소) ──→ 정산
                        │                                    │            │            │
                   한 tx 원자 등록                        다판매자      재고 차감      OrderLine을 판매자별
                                                          카트         +원장+포인트    합산 → 결산 배치
```

주문 결제 분개(무쿠폰):

```
DEBIT  CUSTOMER_CASH   (T)   고객 결제 현금 유입
CREDIT SELLER_PAYABLE  (T)   플랫폼이 판매자에게 지급할 정산액(gross)
+ 포인트 적립: DEBIT POINT_BALANCE / CREDIT POINT_FUNDING
```

주문 취소는 위를 반대로(ORDER_CANCEL) 기록하고 재고를 복원하며 포인트를 역적립한다.
정산 확정 시: `DEBIT SELLER_PAYABLE / CREDIT SETTLEMENT_PAYABLE`.

---

## 주요 설계 결정 · 트러블슈팅

1. **복식부기 원장** — 잔액 필드 차감만으로는 "돈이 어디서 어디로" 추적이 불가능하다. 모든 금전 변동을 DEBIT+CREDIT 2행으로 기록해, 원장만으로 완전한 자금 추적이 가능하게 했다. `LedgerVerificationService`가 캐시 잔액(PointAccount)과 원장 net을 대조하고, 전역 차·대변 균형을 검증한다.
2. **재고 차감 이중 방어** — 동일 SKU 동시 주문은 초과판매를 유발한다. Redisson 분산락(1차) → DB `SELECT FOR UPDATE`(2차) → **주문과 같은 트랜잭션**에서 차감해, 실패 시 원자적으로 롤백된다(별도 보상 불필요). 다품목 주문은 `skuId` 오름차순 정준 락으로 교차 데드락을 막는다.
3. **락 → 트랜잭션 → 락 해제 순서** — 락을 트랜잭션 커밋 전에 풀면 다른 스레드가 커밋 전 데이터를 읽는다. 분산락 획득 → `TransactionTemplate` 커밋 → 락 해제 순서를 강제한다.
4. **보상 트랜잭션** — 주문 취소를 DELETE/상태변경으로 처리하면 "왜 바뀌었는가"를 증명할 수 없다. 원 주문을 불변 보존하고 역분개 + 재고 복원 + 포인트 역적립으로 처리한다.
5. **멱등성 이중 저장** — 네트워크 재시도·더블클릭 체크아웃을 Redis(1차 빠른 감지) + DB(2차 장애 대비)로 정확히 1회 처리. 재시도 시 409가 아니라 **원 응답을 원 상태코드(201)로** 반환한다.
6. **이벤트 전달 신뢰성** — 주문 이벤트를 `AFTER_COMMIT`으로 발행하면 커밋 후 장애 시 유실된다. **Transactional Outbox**(BEFORE_COMMIT, 같은 tx에 원자 캡처) → relay가 Kafka로 발행 → 소비자가 멱등 적용. 소비자 실패는 백오프 후 **DLT**로 적재하고, "발행됐지만 미적용" 행은 **재조정 스윕**으로 회복한다.
7. **정산 배치** — 결산 주기(일/주/월)에 맞춰 판매자 전체를 청크 처리. 재실행 멱등·0원 스킵·단건 실패 skip으로 대량 결산을 중단 없이 완주한다.

---

## 동시성 제어 전략

| 작업 | 전략 | 방지하는 장애 |
|------|------|-------------|
| 주문 결제(재고 차감) | Redisson 분산락 + DB 비관적 락 + 주문 tx 내 차감 | 초과판매, 락-커밋 역전 |
| 다품목 주문 | `skuId` 오름차순 정준 락 | 교차 데드락 |
| 포인트 적립/취소 | `SELECT FOR UPDATE`(회원별) + `INSERT IGNORE` 행 보장 | 동시 적립 경합, 갭 락 데드락 |
| 쿠폰 예산 | Redis-Lua 원자 카운터(INCRBY+검증+DECRBY 롤백) | 예산 초과, 예산 누수 |
| 체크아웃 | 멱등키(Redis+DB) | 이중 주문/이중 차감 |
| 정산 생성 | DB Unique Constraint(seller + period) | 중복 정산 |

---

## API 엔드포인트

모든 응답은 `ApiResponse<T>`(`{ success, data, error }`)로 래핑. (인증) = JWT 필요.

| 모듈 | Method | Endpoint | 설명 |
|------|--------|----------|------|
| **인증/회원** | POST | `/api/v1/members/register` · `/login` | 가입 · 로그인(JWT) |
| | GET | `/api/v1/me` | 현재 인증 회원 (인증) |
| | POST | `/api/v1/members/{id}/suspend` `/unsuspend` `/withdraw` | 정지/해제/탈퇴 (ADMIN) |
| **판매자** | POST | `/api/v1/sellers` | 등록 (인증, 본인 소유) |
| | POST | `/api/v1/sellers/{id}/approve` `/reject` `/suspend` `/unsuspend` `/terminate` | 심사/운영 (ADMIN) |
| **상품** | POST | `/api/v1/products` · `/{id}/on-sale` | 등록·판매개시 (판매자 소유주) |
| | GET | `/api/v1/products` · `/{id}` | 목록·상세 (공개, SKU+재고 포함) |
| **재고** | PUT·GET | `/api/v1/admin/inventory/skus/{skuId}` | 입고/정정·조회 (ADMIN) |
| **장바구니** | GET·POST·PUT·DELETE | `/api/v1/cart` · `/cart/items/{skuId}` | 조회·담기·수량·삭제 (인증, 본인) |
| **주문** | POST | `/api/v1/orders` | 체크아웃 (인증, 멱등) |
| | POST·GET | `/api/v1/orders/{id}/cancel` · `/{id}` | 취소·조회 (인증, 본인) |
| **포인트** | GET | `/api/v1/members/{memberId}/points` | 잔액·이력 (인증, 본인) |
| **쿠폰** | GET | `/api/v1/members/{memberId}/coupons` | 보유 쿠폰 (인증, 본인) |
| **프로모션** | POST·GET | `/api/v1/promotions` · `/{id}` · `/{id}/coupons` | 생성(ADMIN)·조회·쿠폰 발급(인증, 멱등) |
| **정산** | POST·GET | `/api/v1/settlements/calculate` `/{id}/confirm` `/pay` `/dispute` `/{id}` | 생성/확정/지급/이의/조회 (ADMIN) |
| | POST | `/api/v1/admin/settlements/batch` | 결산 일괄 배치 실행 (ADMIN) |
| **원장(admin)** | GET·POST | `/api/v1/admin/ledger/...` | 거래별/계정별/글로벌 조회, 정합성 검증 |

Swagger UI: `http://localhost:8080/swagger-ui.html`

---

## 기술 스택

| 항목 | 기술 |
|------|------|
| Language / Framework | Kotlin 1.9.23 / Java 17 · Spring Boot 3.2.5 |
| ORM | Spring Data JPA + QueryDSL 5.1.0 |
| Batch | Spring Batch 5.1 (결산 일괄 정산 Job) |
| DB | MySQL 8 + Flyway (`ddl-auto: validate`) |
| Cache / Lock | Redis 7 (Redisson 3.27.2) |
| Events | Kafka(spring-kafka) — 주문 이벤트 Transactional Outbox + DLT. 킬스위치로 브로커 없이도 동작 |
| Auth | JWT (jjwt 0.12.5) + Spring Security |
| Test | JUnit 5 + Kotest + MockK + Testcontainers(MySQL/Redis) + `@EmbeddedKafka` + Spring Batch Test |
| Monitoring | Spring Actuator + Micrometer (Prometheus / Grafana) · k6 부하 시나리오 |
| Build / Infra | Gradle 8 (Kotlin DSL) · Docker Compose · Dockerfile · GitHub Actions CI |

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

- 환경변수: `DB_HOST/PORT/NAME/USERNAME/PASSWORD`, `REDIS_HOST/PORT`, `JWT_SECRET`, `KAFKA_BOOTSTRAP_SERVERS`, `ORDER_KAFKA_ENABLED`(기본 `true`; 브로커 없이 실행하려면 `false` → 직접 relay).

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
| **동시성** | 재고 5 SKU에 10 동시 주문 → 정확히 5 성공/5 OUT_OF_STOCK, 최종 0 |
| **멱등성** | 같은 키로 10 동시 체크아웃 → 주문 정확히 1건 + 재고 1개만 차감, 재시도 시 201 보존 |
| **이벤트** | 주문 결제 → Outbox → Kafka → 소비자 → `order_event_log`, 파싱 실패 → DLT 라우팅, 재조정 재적용 |
| **재무** | 복식부기 2행·글로벌 균형, 포인트 적립/취소 정합성 |

---

## 문서

- [`docs/01-domain-design.md`](docs/01-domain-design.md) — 도메인 모델 · 상태머신 · 불변식
- [`docs/02-architecture-decisions.md`](docs/02-architecture-decisions.md) — 아키텍처 결정 · 트러블슈팅
- [`docs/03-financial-design.md`](docs/03-financial-design.md) — 복식부기 원장 · 계정 · 분개
