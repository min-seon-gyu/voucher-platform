# 커머스 백엔드 재포지셔닝 — 설계 문서

- **작성일**: 2026-06-29
- **대상 포지션**: Backend Engineer, Commerce (커머스 플랫폼)
- **베이스 프로젝트**: 지역사랑상품권 백엔드 (Kotlin / Spring Boot 3.2 / MySQL / Redis)
- **전략**: 재포지셔닝 + 핵심 보강 (풀 리빌드 아님)
- **투입 기간 가정**: 집중 1~2주
- **상태**: 설계 확정, 구현 계획 작성 직전

---

## 1. 배경 & 목표

### 1.1 한 줄 정의
> **대용량 트래픽에서 결제·정산·쿠폰·포인트의 재무 정합성을 보장하고, AI로 프로모션 운영을 자동화하는 커머스 백엔드.**

### 1.2 출발점 (현재 강점 — 버리지 않는다)
현재 프로젝트는 공공·금융 도메인 포트폴리오로 출발했지만, 엔지니어링 자산 대부분이 **그대로 커머스 핵심**과 직결된다.

| 자산 | 커머스에서의 의미 |
|---|---|
| 복식부기 원장 + 정합성 검증 배치 | 결제·정산·포인트의 **재무 정합성** |
| 보상 트랜잭션(취소를 DELETE 대신 역분개) | 환불·취소·정산 분쟁의 **감사 추적성** |
| Redisson 분산락 + DB 비관적 락 + 낙관적 `@Version` | 재고/예산/잔액의 **동시성 안전** |
| Redis + DB 이중 멱등성(`@Idempotent`) | 결제 API의 **중복 방지(exactly-once)** |
| Redis Lua 원자 카운터(지역 월 발행 한도) | 프로모션 **예산 상한 제어** |
| k6 부하테스트 + Prometheus/Grafana | **대용량 트래픽** 증명 + 관측성 |

### 1.3 가장 큰 갭 (탐색으로 확인)
1. **도메인이 커머스답지 않다** — 쿠폰·포인트·주문 도메인이 없고 "바우처=주문"이라 얇다.
2. **AI가 0이다** — 코드·의존성·런타임에 LLM 흔적이 전혀 없다. 타깃 조직은 AI Native 조직이며 조직문화 인터뷰에 *AI 활용 역량* 세션이 있다 → **가장 치명적인 갭**.
3. **신뢰성 결함** — 인증이 사실상 무력(`permitAll` + body로 `memberId`), 스키마가 `ddl-auto: update`.

### 1.4 성공 기준
- 커머스 JD의 **자격요건/우대사항 항목별로 "현재 → 계획 후"가 매핑**되고, 핵심 갭(커머스 도메인·AI·신뢰성)이 메워진다.
- 면접 AI 세션에서 **실제 동작하는 AI 기능 1개 + 설계 의사결정 문서**를 제시할 수 있다.
- 신규 도메인이 기존 재무 무결성 디시플린(원장 분개·멱등성·동시성·테스트)을 **동일하게 따른다** (갖다 붙인 느낌 0).

---

## 2. 전략 결정: 왜 "재포지셔닝 + 핵심 보강"인가

| 대안 | 평가 |
|---|---|
| A. 재포지셔닝 + 핵심 보강 **(채택)** | 기존 강점(원장·동시성·멱등성)을 커머스 신뢰성으로 재활용하면서 쿠폰·포인트·AI를 얹음. 1~2주 현실성 + 차별화 유지. |
| B. 본격 커머스 풀 확장(카탈로그·리뷰·물류·MSA 분리) | 다개월 리빌드. 차별화인 재무 무결성 서사가 희석될 위험. 1~2주에 불가능. |
| C. 별도 커머스 신규 프로젝트 | 검증된 동시성/원장 자산을 처음부터 다시 만들어야 함. 비효율. |

**결정 근거**: 기존 자산의 70% 이상이 커머스에 재사용 가능하고, 재무 무결성은 *결제·정산이 핵심인 커머스에서 오히려 강점*이다. 신규 도메인을 기존 원장에 분개시키면 "공공 포트폴리오를 억지로 커머스로 바꿨다"가 아니라 "결제 신뢰성을 깊게 파는 커머스 엔지니어"로 읽힌다.

---

## 3. 최종 스코프

### 3.1 MUST (1~2주 핵심)
1. **재포지셔닝** — 문서/README/도메인 용어를 커머스로 전환
2. **쿠폰/프로모션 엔진** (플래그십 신규 도메인)
3. **포인트/적립 도메인** (경량 신규 도메인)
4. **AI 프로모션 어시스턴트** (동작 + 설계 문서)
5. **JWT 인증 하드닝** (신뢰성 결함 수정 — STRETCH에서 승격)
6. **프로덕션 성숙도 번들** — Flyway 베이스라인 + App Dockerfile + 풀스택 docker-compose + GitHub Actions CI

### 3.2 STRETCH (우선순위순, 시간 남으면)
1. **Kafka (Transactional Outbox)** — 이미 문서에 설계됨, 순수 이벤트 클래스 재사용
2. **Elasticsearch 검색** — 쿠폰/머천트 검색
3. **k8s / Terraform 스켈레톤** — 문서 수준 어필
4. **2번째 AI 기능** — 수요/소진 예측 요약

### 3.3 안 함 (YAGNI / "다음 단계"로만 명시)
카탈로그·리뷰·물류 풀도메인, 실제 PSP(카드/계좌) 결제 연동, 멀티 인스턴스 장애복구 실증, 풀 k8s 운영, 멀티커런시/세금(VAT), Spark.

---

## 4. 도메인 설계

> 신규 도메인은 기존 패키지 컨벤션(`interfaces` / `application` / `domain` / `infrastructure`)과 원장 분개·멱등성·동시성·테스트 디시플린을 **그대로** 따른다.

### 4.1 쿠폰/프로모션 엔진 (`promotion` 패키지)

**책임**: 프로모션(캠페인) 정의 → 쿠폰 발급 → 결제 시 할인 적용. 예산 상한·중복 사용 방지·할인 경제의 원장 분개까지 책임진다.

**핵심 엔티티**
- `Promotion` (캠페인): 할인 타입(정액/정률), 할인값, 대상 조건(지역/머천트/신규고객 등), **예산 상한**, 유효기간, 스택 가능 여부, 상태(`DRAFT → ACTIVE → PAUSED → ENDED`).
- `Coupon`: 특정 `Promotion`에서 발급된 개별 쿠폰. 상태(`ISSUED → RESERVED → REDEEMED → EXPIRED → CANCELLED`), 소유 `memberId`, 멱등 발급 키.
- `CouponRedemption`: 쿠폰이 적용된 결제 1건 기록(추적성).

**재사용 포인트 (차별화의 핵심)**
- **예산 상한**: 기존 지역 월 발행 한도의 **Redis Lua 원자 INCRBY + 한도 체크 + 롤백** 패턴을 캠페인 예산에 그대로 적용 → 동시 다발 쿠폰 사용에서도 예산 초과 0.
- **할인 경제의 원장 분개**: 현재 **미사용 상태인 `REVENUE_DISCOUNT` 계정**을 사용한다. 쿠폰 적용 결제 시:
  - `DEBIT VOUCHER_BALANCE` = 고객 잔액 차감액
  - `DEBIT REVENUE_DISCOUNT` = 쿠폰 할인(발행자/플랫폼 보조)액
  - `CREDIT MERCHANT_RECEIVABLE` = 머천트 수취 총액
  - 불변식: **머천트 수취액 = 고객 차감액 + 프로모션 보조액**, 그리고 전역 `DEBIT == CREDIT` 유지.
  - → 탐색에서 발견된 "할인 경제가 원장에 안 잡힘" 갭을 정확히 메운다.
- **멱등성**: 쿠폰 발급/사용 POST에 기존 `@Idempotent`(Idempotency-Key) 적용.
- **동시성**: 쿠폰 사용은 `coupon:{id}` + 기존 `voucher:{id}` 분산락 하에서 처리, 중복 사용 차단.

**API (예시)**
- `POST /api/promotions` (관리자) — 프로모션 생성
- `POST /api/promotions/{id}/coupons` — 쿠폰 발급(멱등)
- `POST /api/payments/redeem` 확장 — 결제 시 `couponId` 적용
- `GET /api/members/{id}/coupons` — 보유 쿠폰 조회

### 4.2 포인트/적립 도메인 (`point` 패키지)

**책임**: 결제 시 포인트 적립, 결제 수단으로 포인트 사용. **포인트도 1원 단위로 복식부기 정합성 보장**.

**핵심 엔티티**
- `PointAccount` (회원당): 잔액(캐시) + `@Version`.
- `PointTransaction`: append-only(`EARN`/`SPEND`/`EXPIRE`/`CANCEL`), 원 거래 링크.

**원장 분개 (신규 계정 `POINT_LIABILITY` 추가)**
- 적립: `DEBIT REVENUE_DISCOUNT(또는 마케팅비용) / CREDIT POINT_LIABILITY`
- 사용: `DEBIT POINT_LIABILITY / CREDIT MERCHANT_RECEIVABLE` (포인트를 결제 수단으로)
- **정합성 검증 배치 확장**: 기존 일일 검증(`LedgerVerificationService`)에 **"미사용 포인트 부채 합 == 전 회원 포인트 잔액 합"** 불변식을 추가 → 기존 검증 패턴의 자연스러운 확장. (강력한 면접 포인트)

**API (예시)**
- `GET /api/members/{id}/points` — 잔액/내역
- `POST /api/payments/redeem` 확장 — `usePointAmount` 적용
- 적립은 결제 완료 이벤트에 리스너로 연결(적립 정책 분리)

### 4.3 통합 결제 흐름 (쿠폰 + 포인트 + 바우처)

결제(`redeem`) 한 건에서 할인/결제수단 적용 **순서를 명시**해 정합성을 보장한다.

```
주문금액
  → (1) 쿠폰 할인 적용         → REVENUE_DISCOUNT 분개
  → (2) 포인트 사용(부분 결제) → POINT_LIABILITY 차감 분개
  → (3) 잔여액 바우처 잔액 차감 → VOUCHER_BALANCE 분개
  → 머천트 수취액(CREDIT MERCHANT_RECEIVABLE) = (1)+(2)+(3) 합
모든 분개의 DEBIT 합 == CREDIT 합 (불변식)
```

---

## 5. AI 프로모션 어시스턴트 (핵심 차별화)

JD의 *"자동화된 프로모션 운영"* 을 직접 겨냥. **AI는 제안만 하고, 서버가 가드레일로 검증·확정**한다 (AI가 직접 DB에 쓰지 않음).

### 5.1 기능
- 입력: 자연어 + 컨텍스트(머천트/지역/최근 매출). 예: *"주말에 신규 고객 대상 1만원 할인, 예산 500만원, 중복 사용 불가."*
- 출력: **구조화된 `Promotion` 초안**(할인 타입/값, 대상 조건, 예산, 유효기간, 스택 규칙) + **검증 리포트**(예산 한도·지역 정책 위반 체크).
- 엔드포인트: `POST /api/promotions/draft` → 초안 반환 → 사람이 검토 후 `POST /api/promotions`로 확정.

### 5.2 기술 설계
- **Claude API + tool use / structured output**(JSON 스키마 강제)로 NL → `Promotion` 구조 변환.
- **모델 선택**: 기본 추출/분류는 저비용 **Haiku 4.5(`claude-haiku-4-5`)**, 다중 제약 충돌 해석 등 복잡 추론은 **Opus 4.8(`claude-opus-4-8`)**. *(모델 ID·가격·파라미터는 구현 시 `claude-api` 스킬로 최종 확정)*
- **가드레일(서버측, 결정적)**: AI 출력은 반드시 `RegionPolicy`·예산 한도·날짜 유효성 검증을 통과해야 영속화 가능. 위반 시 영속화 거부 + 사유 반환(절대 silent 통과 없음).
- **멱등성**: draft 엔드포인트에 Idempotency-Key 적용(동일 요청 재호출 시 LLM 재호출/중복 과금 방지).
- **폴백**: LLM 불가/스키마 불일치 시 결정적 에러 반환(부분/오염 데이터 생성 금지).
- **관측성·비용**: LLM 호출 지연·토큰·실패율 메트릭 기록.

### 5.3 면접용 아티팩트 (설계 문서로 동봉)
모델 선택 근거, 프롬프트·출력 스키마, 가드레일 설계, 토큰/비용 추정, 지연, **평가셋**(NL→규칙 골든 케이스 회귀 테스트) 정리. → AI 세션에서 "AI를 협업자로, 가드레일과 함께 안전하게 운영" 스토리.

### 5.4 (STRETCH) 2번째 AI — 수요/소진 예측 요약
정합성 검증 배치/거래 데이터로 캠페인 예산 소진 추세를 LLM이 요약·예측 → 프로모션 운영 의사결정 보조.

---

## 6. 기술 보강

### 6.1 JWT 인증 하드닝 (MUST)
- **문제**: `SecurityConfig`가 `anyRequest().permitAll()`, 로그인 시 토큰을 발급하지만 검증 필터 부재, `memberId`/`merchantId`를 요청 본문으로 받아 위변조 가능.
- **수정**: `OncePerRequestFilter` 기반 JWT 인증 필터 추가 → `SecurityContext`에 principal 주입 → 컨트롤러/서비스가 **인증된 principal에서 소유권 도출**(본문 ID 신뢰 제거). 보호 엔드포인트에 인가 규칙 적용. 기존 멱등성·동시성 흐름과 충돌 없도록 필터 순서 검증.

### 6.2 Flyway 베이스라인 (MUST)
- `ddl-auto: update` → `validate`로 전환, `spring.flyway.enabled: true`(의존성 이미 존재).
- 현재 스키마를 `V1__baseline.sql`로 베이스라인 + 신규 도메인(promotion/coupon/point/account-code) 마이그레이션 분리.

### 6.3 컨테이너 + CI (MUST)
- **App Dockerfile**(멀티스테이지, JRE 슬림) — 현재 앱은 컨테이너화돼 있지 않음(`bootRun`만).
- **풀스택 docker-compose**: app + mysql + redis (+ STRETCH 시 kafka/es/prometheus/grafana).
- **GitHub Actions CI**: build + test(Testcontainers) + (선택) 이미지 빌드. → JD의 *E2E·운영* 신호.

### 6.4 (STRETCH) Kafka Transactional Outbox
- 이벤트 발행 시 `outbox` 테이블 INSERT(비즈 트랜잭션과 원자적) → 폴링/CDC로 Kafka 발행 → `@KafkaListener` 소비.
- 순수 데이터 이벤트 클래스 재사용(도메인 코드 변경 0). 단일 브로커 docker-compose. → 실시간·Kafka·MSA 토대.

### 6.5 (STRETCH) Elasticsearch 검색 / k8s·Terraform 스켈레톤
- 쿠폰/머천트 검색을 ES로. k8s Deployment/Service + Terraform skeleton은 문서 수준 어필.

---

## 7. 에러 처리 · 동시성 · 멱등성

신규 도메인 전반에 **기존 전략을 재사용**한다: `BusinessException`/`ErrorCode`/`GlobalExceptionHandler`, 분산락→tx begin→commit→unlock 순서, 비관적 락 + `@Version`, `@Idempotent`. 신규 에러코드(예: `COUPON_EXPIRED`, `PROMOTION_BUDGET_EXCEEDED`, `INSUFFICIENT_POINT`)만 추가.

---

## 8. 테스트 전략

기존 디시플린(Testcontainers 실 인프라, Kotest/MockK, 동시성·멱등성 테스트)을 신규 도메인에 동일 적용.
- **단위**: `Promotion`/`Coupon`/`PointAccount` 도메인 규칙.
- **통합**: 쿠폰 적용 결제 E2E, 포인트 적립/사용 E2E.
- **동시성**: 동일 쿠폰/예산에 N스레드 동시 사용 → 예산 초과·중복 사용 0, 원장 `isBalanced`.
- **멱등성**: 동일 Idempotency-Key로 쿠폰 발급/결제 동시 호출 → exactly-once.
- **부하**: k6 시나리오에 쿠폰 예산 핫스팟(시나리오 E) 추가.
- **AI**: LLM 모킹 계약 테스트 + 가드레일 거부 케이스 + NL→규칙 골든셋 회귀.

---

## 9. 관측성

신규 메트릭: `coupon.redemption.count`(result), `promotion.budget.exhaustion`(gauge), `point.earn/spend`(counter), `ai.promotion.draft.latency/tokens/failure`. 정합성 imbalance 게이지에 포인트 부채 검증 포함.

---

## 10. JD 커버리지 매핑

| 커머스 JD 요구 | 현재 | 계획 후 |
|---|---|---|
| 커머스 도메인(결제·정산·쿠폰·포인트) | 결제·정산·환불 ✅ / 쿠폰·포인트 ❌ | **쿠폰·포인트 추가 → 풀커버** |
| 대용량 트래픽·실시간 | 분산락·멱등·k6 ✅ / 실시간 약함 | (STRETCH) Kafka outbox |
| RDBMS/NoSQL 설계·운영 | MySQL·Redis ✅ | Flyway 정식화 + (STRETCH) ES |
| MSA·클라우드 | 모듈러 모놀리스 ✅ / MSA·클라우드 ❌ | (STRETCH) Kafka·ES로 분리 토대, 컨테이너 |
| Kafka·Spark·ES | ❌ | (STRETCH) Kafka outbox · ES |
| Docker·K8s·IaC·Grafana·Prometheus | Grafana/Prom(부하테스트) ✅ / 컨테이너·IaC ❌ | **App Dockerfile·CI** + (STRETCH) k8s/TF |
| **AI Native(프로모션 자동화·추천·수요예측, LLM 실무)** | ❌ **(0)** | **AI 프로모션 어시스턴트 → 최대 갭 해소** |
| E2E·운영 책임 | 부하테스트·관측성 ✅ | 컨테이너·CI로 배포 스토리 완성 |
| 명확한 커뮤니케이션 | 설계 문서 다수 ✅ | 커머스·AI 설계 문서 추가 |

---

## 11. 실행 순서 (타임라인)

**Week 1 (MUST 핵심)**
1. 재포지셔닝(문서/용어) — 0.5~1일
2. 쿠폰/프로모션 엔진(원장 분개 포함) — 3~4일
3. 포인트 도메인(원장·검증배치 확장) — 1~2일

**Week 2 (MUST 마무리 → STRETCH)**
4. AI 프로모션 어시스턴트(동작 + 설계문서) — 2~3일
5. JWT 인증 하드닝 — 1일
6. Flyway + Dockerfile + docker-compose + CI — 1.5일
7. (남으면) Kafka outbox → ES → k8s/TF

---

## 12. 리스크 & 완화

| 리스크 | 완화 |
|---|---|
| 1~2주에 MUST 과적재 | 쿠폰을 플래그십으로 깊게, 포인트는 원장 재사용으로 경량화. STRETCH는 명확히 분리. |
| AI 기능이 "데모용 장난감"으로 보임 | 서버측 결정적 가드레일 + 평가셋 + 비용/지연 문서 → 운영 가능한 설계로 제시. |
| 신규 도메인이 원장과 따로 놂 | 쿠폰=`REVENUE_DISCOUNT`, 포인트=`POINT_LIABILITY` 분개로 처음부터 원장 통합. |
| 기존 테스트 회귀 | 통합/동시성/멱등성 테스트를 신규 흐름에 동일 적용, CI로 게이트. |

---

## 13. 다음 단계
이 설계를 승인하면 `writing-plans`로 태스크 단위 상세 구현 계획을 작성한다.
