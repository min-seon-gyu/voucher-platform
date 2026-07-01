# 02. 아키텍처 결정 · 트러블슈팅

커머스 백엔드를 만들며 내린 구조적 결정과, 각 지점에서 부딪힌 동시성/정합성 문제를 어떻게 해결했는지 기록한다.

---

## 아키텍처: 모듈러 모놀리스 + 헥사고날

- **모듈러 모놀리스**: 도메인별 모듈(`seller`, `product`, `inventory`, `cart`, `order`, `ledger`, `point`, `promotion`, `transaction`, `member`)로 응집도를 높이되, 배포는 단일 애플리케이션으로 단순하게 유지한다.
- **헥사고날 4계층**: `domain`(엔티티·불변식) / `application`(유스케이스·트랜잭션 경계) / `infrastructure`(JPA·Redis·Kafka) / `interfaces`(REST). 의존은 항상 안쪽을 향한다.
- **모듈 간 의존**은 필요한 최소로 한정한다. 예: `order`가 `product`·`inventory`·`ledger`·`point`를 오케스트레이션하되, 역방향 의존은 없다.

---

## 동시성 — 재고 차감

**문제.** 인기 SKU에 동시 주문이 몰리면 여러 요청이 같은 재고를 읽고 각자 차감해 **초과판매(oversell)** 가 발생한다.

**해결.** 이중 방어 + 트랜잭션 경계 정렬.
1. **Redisson 분산락**(`stock:{skuId}`)으로 1차 직렬화.
2. 락 안에서 **`TransactionTemplate`** 으로 트랜잭션을 열고 **`SELECT ... FOR UPDATE`**(비관적 락)로 재고 행을 잠근 뒤 검증·차감.
3. **차감을 주문과 같은 트랜잭션**에서 수행 → 이후 단계(주문/원장/포인트)가 실패하면 재고 차감도 원자적으로 롤백된다(별도 보상 불필요).
4. 순서는 **락 획득 → 트랜잭션 커밋 → 락 해제**. 락을 커밋 전에 풀면 다른 스레드가 커밋되지 않은 재고를 읽는다.

**데드락.** 다품목 주문은 여러 SKU 락을 잡는다. `skuId` 오름차순 **정준 락 순서**로 획득해 교차 데드락을 막는다.

**Redis 장애 대비.** 분산락 획득이 실패해도 DB 비관적 락만으로 정합성은 유지된다(분산락은 경합 완화용).

> 검증: 재고 5인 SKU에 10건 동시 주문 → 정확히 5건 성공 / 5건 `OUT_OF_STOCK`, 최종 재고 0.

---

## 동시성 — 멱등성

**문제.** 네트워크 재시도나 더블클릭으로 같은 체크아웃 요청이 여러 번 도착하면 이중 주문·이중 차감이 된다.

**해결.** `@Idempotent` + `IdempotencyInterceptor`.
- `Idempotency-Key` 헤더로 요청을 식별. **Redis(1차 빠른 감지) + DB(2차 장애 대비)** 이중 저장으로 "정확히 1회"를 보장.
- 동시 요청은 키 선점(claim)으로 하나만 실행되고 나머지는 캐시된 응답을 받는다.
- 재시도 시 **원 응답 본문 + 원 상태코드(201)** 를 그대로 반환한다(중복을 409로 만들지 않아 클라이언트가 정상 흐름을 잇게 함).

> 검증: 같은 키로 10건 동시 체크아웃 → 주문 정확히 1건 + 재고 1개만 차감.

---

## 이벤트 드리븐 — 주문 이벤트 파이프라인

**문제.** 주문 후속 처리(이벤트 이력·알림·분석 등)를 결제 트랜잭션에 묶으면 결합이 커지고, `AFTER_COMMIT` 발행은 커밋 후 장애 시 **유실**된다.

**해결.** Transactional Outbox + Kafka + DLT + 재조정.
```
OrderPlaced/Cancelled ─(BEFORE_COMMIT)→ order_outbox_events(주문 tx와 원자 캡처)
      → relay 폴링 ─┬─ Kafka `order-events` 발행 → @KafkaListener 소비자 → order_event_log (order.kafka.enabled=true)
                    └─ 직접 적용 → order_event_log                             (false, 로컬/CI, 브로커 불필요)
```
- **원자 캡처**: 이벤트를 주문과 같은 트랜잭션(BEFORE_COMMIT)에 outbox로 기록 → "주문 커밋 ⇔ 이벤트 캡처"가 원자적.
- **at-least-once + 멱등**: 소비자는 `existsByEventId`로 중복 적용을 무시.
- **DLT**: 소비자 apply가 실패하면 지수 백오프 재시도 후 `order-events.DLT`로 적재(무성 유실 차단). DLT 전송 결과까지 검증한다.
- **재조정(reconciliation)**: 발행됐으나 미적용된 "published-but-not-applied" 행을 anti-join으로 찾아 멱등 재적용.
- **poison 가드**: 영구 실패 행은 attempts 누적 후 격리(quarantine)해 head-of-line 블로킹을 끊는다.
- **킬스위치**(`order.kafka.enabled`)로 브로커 없이도(직접 relay) 동작 → 로컬/CI 부담을 줄인다.
- 같은 토픽에 알림·분석 소비자를 추가해 **fan-out**으로 확장할 수 있다.

---

## 트랜잭션 경계

- **원장 기록은 이벤트가 아닌 동기 호출.** 잔액 변경과 원장 분개는 반드시 같은 DB 트랜잭션에서 처리해야 정합성이 보장된다. 이벤트/Kafka는 비핵심 부수효과(이력·알림·분석) 전용.
- **읽기 전용 트랜잭션**(`@Transactional(readOnly=true)`)을 조회 경로에 적용.
- 오케스트레이션(주문 결제/취소)은 서비스 계층에서 명시적 트랜잭션 경계(`TransactionTemplate` 또는 `@Transactional`)를 관리한다.

---

## 정산 배치 (Spring Batch)

- **문제**: 결산 주기마다 판매자 전체의 정산을 안정적으로 생성해야 한다.
- **해결**: `settlementJob = calculateSettlementStep → verifySettlementStep`.
  - Reader `JpaPagingItemReader`(APPROVED 판매자 페이징) → Processor(구간 정산 생성, 중복/0원 시 filter) → Writer(일괄 저장).
  - `faultTolerant().skip(...)` — 단건 실패는 격리하고 배치는 완주.
  - `RunIdIncrementer` + 프로세서 사전 중복 체크 → **재실행 멱등**.
  - 검증 스텝에서 이상치(0 이하 정산)를 점검 + 메트릭.
- 起動 자동 실행은 끄고(`spring.batch.job.enabled=false`), 스케줄러(매일 03:00 KST) + 관리자 엔드포인트로 트리거. 메타 스키마는 Flyway가 소유.

---

## 관측성

- **Actuator + Micrometer**로 Prometheus 노출, Grafana 대시보드로 시각화.
- 커스텀 메트릭: 락 획득 시간, 재고 차감 성공/실패, 정산 배치 이상치, 원장 검증 결과 등.
- `k6` 부하 시나리오로 동시성 거동을 대시보드에서 관찰.
- 요청 추적: `X-Request-Id` → MDC → 전 로그 상관관계.

---

## 테스트 전략

- **Testcontainers**(MySQL/Redis)로 실제 인프라 위에서 통합/동시성 테스트를 돌린다(모킹 최소화).
- **`@EmbeddedKafka`** 로 주문 이벤트 파이프라인 E2E(Outbox→Kafka→소비자→로그)와 DLT 라우팅을 검증.
- 동시성 테스트는 `ExecutorService` + `CountDownLatch`로 실제 경합을 재현한다.
- 기본 프로파일은 `order.kafka.enabled=false`(직접 relay)로 브로커 없이 통합 테스트가 돌아가고, Kafka 파이프라인 테스트만 `@EmbeddedKafka`로 오버라이드한다.
