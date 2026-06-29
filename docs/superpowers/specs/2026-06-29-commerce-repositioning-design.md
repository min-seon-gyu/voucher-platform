# 커머스 백엔드 재포지셔닝 — 설계 문서 (v2, critique 반영)

- **작성일**: 2026-06-29
- **대상 포지션**: Backend Engineer, Commerce (커머스 플랫폼)
- **베이스 프로젝트**: 지역사랑상품권 백엔드 (Kotlin / Spring Boot 3.2 / MySQL / Redis)
- **전략**: 재포지셔닝 + 핵심 보강 (풀 리빌드 아님)
- **투입 기간 가정**: 집중 1~2주
- **상태**: v2 — 코드베이스 검증 + 적대적 critique 반영 완료. 구현 계획 작성 직전.

> **v2 변경 요약**: 실제 코드(`LedgerService`, `TransactionCancelService`, `SettlementService`) 검증 결과 v1의 재무 분개에 결함이 있어 전면 수정했다. (1) 쿠폰 분개 차/대변 방향을 기존 redemption에 맞게 정정, (2) 2-leg 헬퍼 제약을 "2-leg 2쌍 구성"으로 우회, (3) 할인 자금 모델(플랫폼 펀딩·정산 gross)과 전용 계정 도입, (4) 포인트를 차변정상 `POINT_BALANCE`로 재설계하고 MUST를 적립+전역정합성으로 축소, (5) 결합결제 취소/환불·예산 보상·락 순서를 명시, (6) JWT를 "필터+신규 엔드포인트 principal"(MUST) / "기존 retrofit"(STRETCH)로 재계층화, (7) Flyway를 조기 베이스라인으로 재배치, (8) AI 운영 가드(비용 상한·타임아웃·서킷브레이커·키 없는 부팅) 추가. 상세는 §14 변경 이력.

---

## 1. 배경 & 목표

### 1.1 한 줄 정의
> **대용량 트래픽에서 결제·정산·쿠폰·포인트의 재무 정합성을 보장하고, AI로 프로모션 운영을 자동화하는 커머스 백엔드.**

### 1.2 출발점 (현재 강점 — 버리지 않는다)
현재 프로젝트는 공공·금융 도메인 포트폴리오로 출발했지만, 엔지니어링 자산 대부분이 **그대로 커머스 핵심**과 직결된다. (아래 자산은 모두 코드에서 file:line으로 검증됨.)

| 자산 | 커머스에서의 의미 |
|---|---|
| 복식부기 원장(`@Immutable`, 2-leg `record`) + 정합성 검증 배치 | 결제·정산·포인트의 **재무 정합성** |
| 보상 트랜잭션(취소를 DELETE 대신 역분개) | 환불·취소 분쟁의 **감사 추적성** |
| Redisson 분산락 + DB 비관적 락 + 낙관적 `@Version` | 재고/예산/잔액의 **동시성 안전** |
| Redis + DB 이중 멱등성(`@Idempotent`) | 결제 API의 **중복 방지(exactly-once)** |
| Redis Lua 원자 카운터(지역 월 발행 한도) + `RegionCounterSyncScheduler` | 프로모션 **예산 상한 제어 + DB 재동기화** |
| k6 부하테스트 + Prometheus/Grafana | **대용량 트래픽** 증명 + 관측성 |

### 1.3 가장 큰 갭 (탐색·검증으로 확인)
1. **도메인이 커머스답지 않다** — 쿠폰·포인트·주문 도메인 부재, "바우처=주문"이라 얇음.
2. **AI가 0이다** — 코드·의존성·런타임에 LLM 흔적 전무. 타깃 조직은 AI Native이며 조직문화 인터뷰에 *AI 활용 역량* 세션 존재 → **가장 치명적 갭**.
3. **신뢰성 결함** — 인증 무력(`SecurityConfig.permitAll()` + body로 `memberId`/`merchantId`, JWT 필터 미연결), 스키마 `ddl-auto: update`.

### 1.4 성공 기준
- 커머스 JD의 자격요건/우대사항 항목별 "현재 → 계획 후"가 매핑되고 핵심 갭(도메인·AI·신뢰성)이 메워진다.
- 면접 AI 세션에서 **실제 동작 AI 기능 1개 + 설계 의사결정 문서**를 제시.
- 신규 도메인이 기존 재무 무결성 디시플린(원장 분개·멱등성·동시성·테스트)을 **동일하게 따르고**, **신규 분개가 매일 밤 정합성 검증을 통과**한다(=재무 설계가 증명 가능).

---

## 2. 전략 결정: 왜 "재포지셔닝 + 핵심 보강"인가

| 대안 | 평가 |
|---|---|
| A. 재포지셔닝 + 핵심 보강 **(채택)** | 검증된 강점(원장·동시성·멱등성·예산카운터)을 커머스 신뢰성으로 재활용 + 쿠폰·포인트·AI를 얹음. 1~2주 현실성 + 차별화 유지. |
| B. 본격 커머스 풀 확장(카탈로그·리뷰·물류·MSA) | 다개월 리빌드, 차별화 서사 희석. 불가. |
| C. 별도 신규 프로젝트 | 검증된 자산을 처음부터 재구축. 비효율. |

**결정 근거**: 자산의 70%+가 커머스에 재사용 가능하고, 재무 무결성은 결제·정산이 핵심인 커머스에서 오히려 강점이다. **단, 강점이 되려면 신규 분개가 실제로 균형을 이뤄 정합성 검증을 통과해야 한다**(v1의 결함을 v2에서 수정한 이유).

---

## 3. 재무 모델 결정 (Day 1 선결 — 모든 도메인의 전제)

> critique 핵심: 쿠폰/포인트 작업 전에 **분개 방향·자금 모델·계정**을 못 박지 않으면 플래그십 작업과 정합성 테스트를 다시 짜게 된다. 따라서 Day 1에 확정한다.

### 3.1 기존 분개 규약 (코드 검증됨)
- `LedgerService.record(debit, credit, amount, txId, type)` = **차변 1행 + 대변 1행(동액)** 단일 2-leg. (`LedgerService.kt:17-39`)
- 결제(redemption): `DEBIT MERCHANT_RECEIVABLE / CREDIT VOUCHER_BALANCE`. (`VoucherRedemptionService.kt:58-64`)
- 검증: `VOUCHER_BALANCE` **차변정상**(net = 차변−대변)이며 그 net == 캐시 `voucher.balance`. (`LedgerVerificationService.kt:68`) `netBalanceByAccount(account)` 헬퍼 존재(`LedgerService.kt:44-48`).
- 정산: COMPLETED redemption의 `transaction.amount` 합산 = 가맹점 지급액(**gross**, 원장 미연동). (`SettlementService.kt:35-37`)

### 3.2 확정 결정
1. **자금 모델 = 플랫폼 펀딩, 정산 gross.** 쿠폰 할인/포인트 적립 비용은 **플랫폼이 부담**하고, 가맹점은 **주문 총액(gross)** 으로 정산받는다. → `transaction.amount`는 항상 *가맹점 수취 총액*을 의미하므로 기존 정산 집계가 그대로 유효.
2. **2-leg 제약은 "2쌍 구성"으로 우회**(헬퍼 시그니처 불변). 다리(leg)가 3개 필요한 분개는 **transactionId를 공유하는 균형 2-leg 분개 2번 호출**로 표현한다.
3. **전용 계정 추가**(기존 `REVENUE_DISCOUNT` 오버로딩 금지):
   - `PROMOTION_FUNDING` — 플랫폼의 쿠폰 보조 출연(대변정상, 누적 보조액).
   - `POINT_BALANCE` — 회원 포인트 잔액(**차변정상**, `VOUCHER_BALANCE`와 동일 취급 → 검증 공식 재사용).
   - `POINT_FUNDING` — 플랫폼의 포인트 적립 출연(대변정상).
   - *(미사용 `REVENUE_DISCOUNT`/`SETTLEMENT_PAYABLE`은 손대지 않고 둔다.)*
4. **신규 도메인 분개는 반드시 결제 트랜잭션과 동기**로 기록(after-commit fire-and-forget 금지 → leg 유실 방지).
5. **정합성 검증 확장**: 전역 `차변합==대변합`은 계정 무관이라 자동 커버. 신규 계정은 **전역 불변식 + `POINT_BALANCE` 전역 합 == Σ`PointAccount.balance`** 로 검증(MUST). 회원별 지역화는 STRETCH(§4.2).

---

## 4. 도메인 설계

> 신규 도메인은 기존 패키지 컨벤션(루트 패키지 하위 `<feature>` → `interfaces`/`application`/`domain`/`infrastructure`)을 따른다(검증됨).

### 4.1 쿠폰/프로모션 엔진 (`promotion` 패키지) — 플래그십

**핵심 엔티티**
- `Promotion`(캠페인): 할인 타입(정액/정률), 할인값, 대상 조건, **예산 상한**, 유효기간, 상태(`DRAFT→ACTIVE→PAUSED→ENDED`), `stackable`(MUST=false 고정, 스택은 STRETCH).
- `Coupon`: 발급 단위. 상태(`ISSUED→RESERVED→REDEEMED→EXPIRED→CANCELLED`), `memberId`, 멱등 발급 키, 만료 시각.
- `CouponRedemption`: 쿠폰 적용 결제 1건 기록(추적성).

**쿠폰 비즈니스 규칙 (MUST)**: 단일 쿠폰만(스택 금지), `discount = min(discount, orderTotal)`(과할인 클램프), `min-spend` 미달 시 거부, **회원당 사용 한도**, 0/음수 할인 거부.

**할인 결제 분개 (수정됨 — 2-leg 2쌍, txId 공유)**
주문총액 `T`, 쿠폰 할인 `D`, 바우처 차감 `T−D`:
```
쌍1 (바우처 결제분): DEBIT MERCHANT_RECEIVABLE (T−D) / CREDIT VOUCHER_BALANCE   (T−D)
쌍2 (플랫폼 보조분): DEBIT MERCHANT_RECEIVABLE (D)   / CREDIT PROMOTION_FUNDING (D)
─────────────────────────────────────────────────────────────────────────────
합계: MERCHANT_RECEIVABLE +T(차변) / VOUCHER_BALANCE −(T−D) / PROMOTION_FUNDING +D(대변)
불변식: 차변합(T) == 대변합(T) ✔, VOUCHER_BALANCE net 감소액 == 캐시 잔액 감소액(T−D) ✔
```
→ 가맹점은 gross `T` 정산, 고객 바우처는 `T−D`만 차감, 차액 `D`는 플랫폼 펀딩. **기존 정합성 검증을 그대로 통과.**

**예산 라이프사이클 (수정됨)**
- **redeem 시점 예약**(발급 아님): Redis Lua 원자 `INCRBY+한도체크` 로 예산 차감. (지역 카운터 패턴 재사용)
- **다운스트림 DB 실패 시 보상 `DECRBY`**(try/finally) — Lua 내부 한도초과 롤백만으로는 부족(커밋된 INCRBY + 롤백된 결제 = 예산 누수).
- **예산 재동기화 잡**: `RegionCounterSyncScheduler` 미러링하여 Redis↔DB 예산 정합 복구.
- **취소/환불 시 예산 반환**(§4.3).

**동시성·멱등성**: 쿠폰 발급/사용 POST에 `@Idempotent`. 사용은 분산락 하에서 처리(락 순서 §4.3).

**API(예시)**: `POST /api/promotions`(관리자), `POST /api/promotions/{id}/coupons`(멱등 발급), `POST /api/payments/redeem` 확장(`couponId`), `GET /api/members/{id}/coupons`.

### 4.2 포인트/적립 도메인 (`point` 패키지) — MUST는 적립 전용

**모델 (수정됨)**: 포인트 잔액을 **`POINT_BALANCE` 차변정상**으로 모델링 → 바우처와 동일 취급, 검증 공식(`net=차변−대변`) 재사용, sign 꼬임 제거.
- `PointAccount`(회원당): 잔액 캐시 + `@Version`.
- `PointTransaction`: append-only(`EARN`/`SPEND`/`EXPIRE`/`CANCEL`), 원 거래 링크, `memberId` 보존.

**적립 분개 (MUST, 결제 트랜잭션과 동기)**: 적립액 `E`
```
DEBIT POINT_BALANCE (E) / CREDIT POINT_FUNDING (E)   # 회원 포인트 +E, 플랫폼 출연 +E
```
- **적립 기준액 명시(MUST)**: 적립은 **쿠폰 할인 적용 후 실제 결제액 기준**, 1원 단위 반올림(rounding mode 명시), **포인트로 결제한 금액엔 미적립**(point-on-point 제외).

**정합성 검증 (MUST=전역)**: `netBalanceByAccount(POINT_BALANCE) == Σ PointAccount.balance` 를 일일 검증에 추가(전역 `차변합==대변합`은 자동). **회원별 지역화는 STRETCH**(redemption tx의 `memberId`가 현재 null이고 per-entity 검증이 voucherId 조인이라, member 스코프 쿼리 + tx에 memberId 채우기가 필요).

**STRETCH**: **포인트 결제수단 사용**(tender). 미러링: `DEBIT MERCHANT_RECEIVABLE (P) / CREDIT POINT_BALANCE (P)` — 바우처 redemption과 동일 형태라 자연스럽지만, **결합결제 오케스트레이터의 가장 어려운 부분**이므로 MUST에서 분리. 포인트 만료 잡(`breakage`)도 STRETCH.

**API**: `GET /api/members/{id}/points`(잔액/내역). 적립은 **결제 완료 트랜잭션 내부 동기 호출**(after-commit 리스너 금지).

### 4.3 결합결제 오케스트레이터 (독립 컴포넌트 — 전용 태스크)

> critique: 멀티-leg 원장 + 다중 서브시스템 + 단일 DB tx + 멱등성 + 예산 + 락 순서가 얽힌 **가장 어려운 단일 컴포넌트**. 쿠폰/포인트 추정에 묻지 말고 별도 태스크 + 동시성 테스트.

- **MUST 범위**: 바우처 + 쿠폰(2 서브시스템). (포인트 tender는 STRETCH로 빠져 MUST 오케스트레이터가 가벼워짐.)
- **정준 락 순서(데드락 방지)**: 항상 `coupon:{id} → voucher:{id}`(키 정렬 규칙) 후 tx 시작. (포인트 tender 추가 시 `→ point:{memberId}` 말미.)
- **흐름**: 락 획득 → tx 시작 → 쿠폰 검증/예약 → 예산 Lua 예약 → 바우처 차감 → 원장 2쌍 분개(동기) → tx 커밋 → 락 해제. 실패 시 보상 `DECRBY` + tx 롤백.
- **테스트**: 동일 쿠폰/예산 N스레드 → 예산 초과·중복 사용 0, 원장 `isBalanced`.

### 4.4 취소/환불 (결합결제 대응 — MUST)

> 현재 `TransactionCancelService`는 단일 `DEBIT VOUCHER_BALANCE/CREDIT MERCHANT_RECEIVABLE` 역분개 + 바우처 잔액만 복원(`:57-71`). 결합결제를 모른다.

- **모든 leg 역분개**: redeem에서 기록한 쌍(바우처분 + 보조분)을 각각 역분개(보상 트랜잭션, 원 거래 불변 보존).
- **상태/자원 복원**: 바우처 잔액 복원, **쿠폰 상태 `REDEEMED→CANCELLED`**, **예산 반환(`DECRBY`)**, (STRETCH: 적립 포인트 회수 `CANCEL` + `POINT_BALANCE` 역분개).
- **테스트**: 결합결제 취소 후 `isBalanced` + 예산/쿠폰/잔액 원복 검증.

---

## 5. AI 프로모션 어시스턴트 (핵심 차별화)

JD의 *"자동화된 프로모션 운영"* 직격. **AI는 제안만, 서버가 결정적 가드레일로 검증·확정**(AI가 DB에 직접 쓰지 않음).

### 5.1 기능
- 입력: 자연어 + 컨텍스트. 출력: 구조화된 `Promotion` 초안 + 검증 리포트.
- `POST /api/promotions/draft` → 초안 → 사람이 검토 후 `POST /api/promotions` 확정.

### 5.2 기술 설계
- **Claude API + tool use / structured output**(JSON 스키마 강제). `starter-web`의 `RestClient`로 호출 가능(신규 HTTP 의존성 불필요), 또는 공식 SDK. *(모델 ID·가격·파라미터는 구현 시 `claude-api` 스킬로 확정. 후보: 저비용 추출=Haiku 4.5, 복잡 추론=Opus 4.8.)*
- **결정적 가드레일(서버측)**: AI 출력은 `RegionPolicy`·예산 한도·날짜 유효성 검증 통과해야만 영속화. 위반 시 거부 + 사유(silent 통과 0). **프롬프트 인젝션은 이 결정적 검증으로 차단**.
- **운영 가드(추가)**: (1) 요청당 **최대 토큰/비용 상한**, (2) **connect/read 타임아웃 + 재시도/백오프 + 서킷브레이커**, (3) **API 키 없이도 앱·CI 부팅되는 kill-switch 플래그**(CI는 실 API 미호출), (4) draft 엔드포인트 멱등성(중복 과금 방지).
- **폴백**: LLM 불가/스키마 불일치 시 결정적 에러(부분/오염 데이터 0).
- **관측성**: `ai.promotion.draft.latency/tokens/failure`.

### 5.3 면접 아티팩트
모델 선택 근거, 프롬프트·출력 스키마, 가드레일, 토큰/비용·지연, **골든셋 회귀 + 모킹 계약 테스트**. → "AI를 협업자로, 가드레일과 함께 안전 운영" 스토리.

### 5.4 (STRETCH) 2번째 AI — 수요/소진 예측 요약.

---

## 6. 기술 보강

### 6.1 JWT 인증 (재계층화)
- **MUST**: `OncePerRequestFilter` JWT 검증 필터를 보안 체인에 연결 + **신규 쿠폰/포인트/프로모션 엔드포인트는 처음부터 인증 principal에서 신원 도출**(body ID 미신뢰). **identity 계약을 Day 1에 정의**(회원 vs 가맹점 주체 모델 — 가맹점은 `Member`/`MemberRole`과 별도 엔티티이므로 redeem/정산의 인증 주체를 문서화).
- **STRETCH**: 기존 voucher/settlement 엔드포인트 retrofit(전 컨트롤러 시그니처·DTO·테스트 인증 변경 → cross-cutting, 비용 큼) + 가맹점 인증 풀 구현.
- 근거: 신규 표면을 처음부터 안전하게 만드는 것이 신뢰성 이득의 대부분을 저비용에 확보. 비싼 부분(기존 전수 retrofit)은 분리.

### 6.2 Flyway (조기 베이스라인 — 재배치)
- **Day 1~2에 현재(안정) 스키마를 `V1__baseline.sql`로 베이스라인**(Hibernate DDL export로 생성해 정확히 매칭) → `ddl-auto: validate` + `flyway.enabled: true`로 **조기 전환**(테스트 깨짐을 즉시 노출). 이후 신규 도메인 테이블은 **만들면서 per-domain 마이그레이션** 추가. *(주의: V1이 생성 DDL과 불일치하면 부팅·전체 Testcontainers 테스트가 동시에 깨짐.)*

### 6.3 컨테이너 + CI (MUST)
- App **Dockerfile**(멀티스테이지, JRE 슬림), **풀스택 docker-compose**(app+mysql+redis), **GitHub Actions CI**(build + Testcontainers 테스트). → E2E·운영 신호.

### 6.4 (STRETCH) Kafka Transactional Outbox / Elasticsearch / k8s·Terraform
- Outbox: 이벤트 → `outbox` INSERT(tx 원자) → 폴링/CDC → Kafka, `@KafkaListener`(순수 이벤트 클래스 재사용). ES: 쿠폰/머천트 검색. k8s/TF: 문서 수준.

---

## 7. 에러 처리 · 동시성 · 멱등성
기존 전략 재사용: `BusinessException`/`ErrorCode`/`GlobalExceptionHandler`, 분산락→tx→커밋→해제, 비관적 락 + `@Version`, `@Idempotent`. 신규 에러코드: `COUPON_EXPIRED`, `COUPON_ALREADY_USED`, `PROMOTION_BUDGET_EXCEEDED`, `MIN_SPEND_NOT_MET`, `COUPON_USAGE_LIMIT_EXCEEDED`.

---

## 8. 테스트 전략
- **단위**: `Promotion`/`Coupon`/`PointAccount` 규칙(클램프·min-spend·한도).
- **통합**: 쿠폰 적용 결제 E2E, 포인트 적립 E2E.
- **동시성**: 동일 쿠폰/예산 N스레드 → 예산초과·중복 0, 원장 `isBalanced`; **결합결제 오케스트레이터 전용 동시성 테스트**.
- **멱등성**: 동일 Idempotency-Key 동시 호출 → exactly-once.
- **정합성 회귀**: 쿠폰·포인트·취소 분개가 `LedgerVerificationService.verify().isBalanced` 통과(분개 방향 T-account를 테스트로 못 박음).
- **부하**: k6에 쿠폰 예산 핫스팟 시나리오 추가.
- **AI**: 모킹 계약 테스트 + 가드레일 거부 케이스 + 골든셋 + **키 없이 부팅** 확인.

---

## 9. 관측성
신규 메트릭: `coupon.redemption.count`(result), `promotion.budget.consumed`(gauge), `point.earn.count`, `ai.promotion.draft.{latency,tokens,failure}`. 정합성 imbalance 게이지에 `POINT_BALANCE` 불변식 포함.

---

## 10. JD 커버리지 매핑

| 커머스 JD 요구 | 현재 | 계획 후 |
|---|---|---|
| 커머스 도메인(결제·정산·쿠폰·포인트) | 결제·정산·환불 ✅ / 쿠폰·포인트 ❌ | **쿠폰(풀) + 포인트(적립) 추가** |
| 대용량·실시간 | 분산락·멱등·k6 ✅ / 실시간 약함 | (STRETCH) Kafka outbox |
| RDBMS/NoSQL 설계·운영 | MySQL·Redis ✅ | Flyway 정식화 + (STRETCH) ES |
| MSA·클라우드 | 모듈러 모놀리스 ✅ | (STRETCH) Kafka·ES 분리 토대, 컨테이너 |
| Kafka·Spark·ES | ❌ | (STRETCH) Kafka outbox · ES |
| Docker·K8s·IaC·Grafana | Grafana/Prom ✅ / 컨테이너·IaC ❌ | **App Dockerfile·CI** + (STRETCH) k8s/TF |
| **AI Native(프로모션 자동화·추천·수요예측)** | ❌ **(0)** | **AI 프로모션 어시스턴트 → 최대 갭 해소** |
| E2E·운영 | 부하·관측성 ✅ | 컨테이너·CI로 배포 완성 |

---

## 11. 스코프 계층 & 실행 순서

### 11.1 계층
**MUST**
0. (Day 1) **재무 모델 + identity 계약 확정 + Flyway 현재 스키마 베이스라인/validate 전환**
1. 재포지셔닝(문서/용어)
2. 쿠폰/프로모션 엔진(수정된 분개·예산 라이프사이클·규칙·결합 오케스트레이터·취소/환불·전수 테스트) — 플래그십
3. 포인트 **적립 전용** + 전역 정합성
4. AI 프로모션 어시스턴트(동작 + 운영 가드 + 설계 문서)
5. JWT **필터 + 신규 엔드포인트 principal**
6. Dockerfile + 풀스택 compose + CI

**STRETCH**: 포인트 결제수단(tender) + 회원별 정합성 / 쿠폰 스택 / 쿠폰·포인트 만료 잡 / JWT 기존 retrofit + 가맹점 인증 / Kafka outbox / ES / k8s·TF / 2번째 AI.

**안 함(YAGNI)**: 카탈로그·리뷰·물류 풀도메인, 실제 PSP, 멀티 인스턴스 장애복구, 풀 k8s 운영, 멀티커런시/VAT, Spark.

### 11.2 타임라인 & "밀리면 이 순서로 출시"
- **Week 1**: (0) 재무·identity·Flyway 베이스라인 → (1) 재포지셔닝 → (2) 쿠폰 엔진(분개·예산·규칙·오케스트레이터·취소).
- **Week 2**: (4) AI 어시스턴트 → (3) 포인트 적립 → (5) JWT 필터+신규 principal → (6) Docker/CI → 남으면 STRETCH.
- **밀리면 출시 우선순위**(critique 권고): **쿠폰(정합성 통과) + AI + 재포지셔닝 + Flyway/Docker/CI**가 신뢰성 높은 최소 출시 세트. 그다음 포인트 적립, 그다음 JWT. **폭보다 원장 정합성 품질 우선.**

---

## 12. 리스크 & 완화 (critique 반영)

| 리스크 | 완화 |
|---|---|
| 신규 분개가 정합성 검증 실패(v1 결함) | 분개 방향을 기존 redemption에 맞춤 + T-account 테스트로 못 박음(§3,§8). |
| 2-leg 헬퍼로 3-leg 표현 불가 | txId 공유 2-leg 2쌍 구성(헬퍼 불변). |
| 할인 자금/정산 gross-net 미정의 | 플랫폼 펀딩·정산 gross 확정 + 전용 계정(§3.2). |
| 결합결제 취소/환불 누락 | 전 leg 역분개 + 예산/쿠폰/잔액 원복을 MUST 태스크화(§4.4). |
| 예산 Redis-DB 비원자성 누수 | 보상 DECRBY + 재동기화 잡 + 취소 시 반환(§4.1). |
| 데드락(다자원 락) | 정준 락 순서(§4.3). |
| 포인트 sign 꼬임/검증 부적합 | `POINT_BALANCE` 차변정상 + 전역 정합성(회원별은 STRETCH)(§4.2). |
| JWT 과소추정·전 테스트 파손 | 필터+신규 principal만 MUST, 전수 retrofit STRETCH(§6.1). |
| Flyway 부팅·테스트 동시 파손 | 안정 스키마 조기 베이스라인 + validate 조기 전환(§6.2). |
| AI 비용/장애/CI 키 | 비용 상한·타임아웃·서킷브레이커·키 없는 부팅(§5.2). |
| MUST 과적재 | 포인트 적립전용·JWT 분리로 축소 + "밀리면 출시 순서"(§11.2). |

---

## 13. 다음 단계
이 설계를 기반으로 `writing-plans`로 태스크 단위 상세 구현 계획을 작성한다(Day 1 재무·identity·Flyway 선결 → 쿠폰 → AI → 포인트 → JWT → Docker/CI).

---

## 14. 변경 이력 (v1 → v2, critique 반영)
1. **쿠폰 분개 정정**: `DEBIT VOUCHER_BALANCE/CREDIT MERCHANT_RECEIVABLE`(v1, 역방향) → `DEBIT MERCHANT_RECEIVABLE/CREDIT VOUCHER_BALANCE`(+ 보조 leg). 기존 규약과 정합성 검증에 맞춤.
2. **3-leg → 2-leg 2쌍**: `LedgerService.record`가 2-leg 전용임을 확인, txId 공유 2쌍으로 구성.
3. **자금 모델·계정**: 플랫폼 펀딩·정산 gross 명시, `REVENUE_DISCOUNT` 오버로딩 폐기 → `PROMOTION_FUNDING`/`POINT_BALANCE`/`POINT_FUNDING` 도입.
4. **포인트 재설계**: `POINT_LIABILITY`(대변정상, sign 꼬임) → `POINT_BALANCE`(차변정상). MUST=적립+전역정합성, 결제수단·회원별정합성=STRETCH.
5. **결합결제·취소/환불·예산 보상·락 순서**를 명시적 컴포넌트/태스크로 추가(§4.3,§4.4,§4.1).
6. **JWT 재계층화**: 필터+신규 principal(MUST) / 기존 retrofit·가맹점 인증(STRETCH), identity 계약 Day 1.
7. **Flyway 조기 베이스라인**으로 재배치(마지막 → Day 1~2).
8. **AI 운영 가드** 추가(비용 상한·타임아웃·서킷브레이커·키 없는 부팅·계약 테스트).
9. **포인트 적립 기준액·반올림·point-on-point 제외** 명시.
