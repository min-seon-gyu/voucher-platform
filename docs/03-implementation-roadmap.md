# 3단계 — 구현 로드맵

> **상태: 전체 구현 완료 + 코드 품질 개선** (Task 1~16 모두 완료, 28개 커밋)

## 기술 스택

| 항목 | 기술 | 버전 |
|------|------|------|
| Language | Kotlin | 1.9.23 |
| Framework | Spring Boot | 3.2.5 |
| ORM | Spring Data JPA + QueryDSL | QueryDSL 5.1.0 |
| DB | MySQL | 8.x |
| Cache / Lock | Redis (Redisson) | Redisson 3.27.2 |
| Auth | JWT (jjwt) | 0.12.5 |
| API 문서 | Swagger/OpenAPI | SpringDoc |
| Events | Spring ApplicationEventPublisher (Kafka-replaceable) | - |
| Test | JUnit 5 + Kotest + Testcontainers | Kotest 5.8.1, TC 1.19.7 |
| Build | Gradle (Kotlin DSL) | - |
| Infra | Docker Compose (local) | - |
| Monitoring | Spring Actuator + Micrometer Prometheus | - |

---

## 태스크 의존성 그래프

```
Task 1 (프로젝트 설정)
  └→ Task 2 (공통 모듈)
       ├→ Task 3 (Region) ──────┐
       └→ Task 4 (Member) ──────┤
            └→ Task 5 (Merchant)┤
                                ├→ Task 6 (Ledger) ─→ Task 7 (멱등키)
                                │                        └→ Task 8 (발행)
                                │                             └→ Task 9 (결제) ★★
                                │                                  ├→ Task 10 (환불)
                                │                                  ├→ Task 10a (청약철회)
                                │                                  └→ Task 11 (취소/보상) ★★
                                │                                       └→ Task 12 (만료 배치)
                                │                                            └→ Task 13 (정산)
                                │                                                 └→ Task 14 (정합성 검증) ★★
                                │                                                      └→ Task 15 (통합 테스트)
                                │                                                           └→ Task 16 (문서화)
```

---

## 태스크 목록

### Task 1: 프로젝트 초기 설정 및 인프라 구성 ✅
- **Tags:** [INFRA]
- **Goal:** Spring Boot 3.x + Kotlin 프로젝트 스캐폴딩, Docker Compose(MySQL 8 + Redis), Gradle 의존성 설정
- **Key implementation concern:** QueryDSL kapt 설정과 Testcontainers가 Docker Compose의 MySQL/Redis와 충돌 없이 동작하도록 포트 분리
- **커머스 도메인 역량:** N/A

### Task 2: 공통 모듈 — BaseEntity, 예외 체계, 감사 로그 기반 ★ ✅
- **Tags:** [DOMAIN] [INFRA]
- **Goal:** 전체 모듈이 공유하는 BaseEntity(@Version 포함), 비즈니스 예외 체계(ErrorCode enum), AuditLog 엔티티 및 이벤트 리스너 구현
- **Key implementation concern:** AuditLog의 `BEFORE_COMMIT` / `AFTER_COMMIT` 리스너 분기 로직. CRITICAL 등급 감사 실패 시 트랜잭션 롤백이 정확히 동작하는지 검증
- **커머스 도메인 역량:** 감사 추적 체계를 가장 먼저 구축함으로써 이후 모든 기능이 자동으로 감사 대상이 됨. 공공 시스템의 컴플라이언스 우선 설계

### Task 3: Region 모듈 — 지자체 엔티티 및 정책 관리 ★ ✅
- **Tags:** [DOMAIN] [API]
- **Goal:** Region Aggregate Root, RegionPolicy Value Object(할인율, 한도, 정산주기 포함), CRUD API 구현
- **Key implementation concern:** RegionPolicy(할인율, 구매한도, 월 발행한도, 환불 기준비율, 정산주기)를 Value Object로 모델링하면서 JPA Embeddable로 매핑
- **커머스 도메인 역량:** 지자체별 정책 차이를 데이터로 관리하는 구조 — 실제 지역사랑상품권 플랫폼의 핵심 요구사항

### Task 4: Member 모듈 — 회원 엔티티 및 인증 기반 ✅
- **Tags:** [DOMAIN] [API] [SECURITY]
- **Goal:** Member 엔티티, Role 기반 권한(USER/MERCHANT_OWNER/ADMIN), Spring Security 설정, JWT 인증
- **Key implementation concern:** Role별 API 접근 제어를 간결하게 유지하면서도 가맹점 소유자의 이중 역할(User이면서 Merchant 관리자) 처리
- **커머스 도메인 역량:** N/A

### Task 5: Merchant 모듈 — 가맹점 등록 및 상태 관리 ★ ✅
- **Tags:** [DOMAIN] [API]
- **Goal:** Merchant Aggregate Root, 상태 머신(PENDING_APPROVAL→APPROVED/REJECTED→SUSPENDED→TERMINATED), Region 귀속 관계, 관리자 승인 API. REJECTED 가맹점의 재신청 플로우(새 레코드 생성) 포함
- **Key implementation concern:** 상태 전이 검증 로직을 도메인 엔티티 내부에서 강제 (잘못된 전이 시 예외). `MerchantApprovedEvent` 발행 및 감사 로그 연동
- **커머스 도메인 역량:** 가맹점 심사/승인 프로세스는 지역사랑상품권 운영의 핵심 관리 영역

### Task 6: Ledger 모듈 — 복식부기 원장 ★★ ✅
- **Tags:** [DOMAIN]
- **Goal:** LedgerEntry(immutable), AccountCode, LedgerService(차변/대변 쌍 생성 — 동기 호출), 계정 코드 체계(VOUCHER_BALANCE, REVENUE, REFUND, EXPIRED 등), Transaction 엔티티
- **Key implementation concern:** LedgerEntry의 불변성 보장 — JPA에서 UPDATE/DELETE를 원천 차단하는 설계. 차변/대변 합이 항상 0인 불변식을 서비스 계층에서 강제. LedgerService는 이벤트가 아닌 동기 호출로 사용
- **커머스 도메인 역량:** 재무 무결성의 근간. "모든 돈의 흐름을 원장으로 증명할 수 있다"는 것이 이 프로젝트의 가장 강력한 어필 포인트

### Task 7: 멱등키 모듈 ★ ✅
- **Tags:** [INFRA] [SECURITY]
- **Goal:** IdempotencyKey 엔티티, Redis 1차 저장 + DB 2차 저장, AOP 인터셉터로 대상 API 자동 적용
- **Key implementation concern:** 멱등키 인터셉터가 응답 본문까지 캐시하여 중복 요청 시 동일 응답을 반환하는 메커니즘. Redis 장애 시 DB fallback이 투명하게 동작하는지 검증
- **커머스 도메인 역량:** 금융 시스템의 중복 처리 방지 — 네트워크 불안정 시에도 정확히 한 번 처리 보장

### Task 8: Voucher 모듈 — 발행 및 상태 관리 ★ ✅
- **Tags:** [DOMAIN] [API]
- **Goal:** Voucher Aggregate Root(상태 머신), VoucherCodeGenerator(SecureRandom + Luhn mod 36), 발행 서비스(Member 분산락 + Region Redis Lua 스크립트로 한도 검증), VoucherIssuedEvent 발행, 구매 API
- **Key implementation concern:** Member 분산락 1개만 사용하고 Region 한도는 Redis Lua 스크립트로 INCRBY + 한도 비교 + 롤백을 원자적으로 수행하여 데드락 및 경쟁 상태 제거. 상품권 코드 생성 시 SecureRandom + 체크 디짓 적용
- **커머스 도메인 역량:** 상품권 발행은 지자체 예산과 직결. 한도 초과 발행은 예산 사고

### Task 9: Voucher 모듈 — 결제(사용) 처리 ★★ ✅
- **Tags:** [DOMAIN] [API]
- **Goal:** 결제 서비스 — 잔액 차감 + Transaction 생성 + 원장 기록(LedgerService 동기 호출) + 감사 이벤트 발행을 하나의 트랜잭션에서 처리. Redisson 분산락 + DB 비관적 락 이중 방어. TransactionTemplate으로 락-커밋 순서 보장
- **Key implementation concern:** 분산락 → TransactionTemplate.execute { DB 비관적 락 → 잔액/만료 검증 → 차감 → Transaction 생성 → LedgerService.record() 동기 호출 → VoucherRedeemedEvent 발행 → **커밋** } → 분산락 해제. 락 해제 전에 트랜잭션이 커밋되므로 다른 스레드가 커밋 전 데이터를 읽는 문제를 원천 방지. BigDecimal 잔액 비교는 `compareTo`로 통일하여 scale 차이에 의한 비교 오류 방지
- **커머스 도메인 역량:** 상품권 결제는 시스템의 존재 이유. 동시성 제어 + 재무 무결성의 종합 시험

### Task 10: Voucher 모듈 — 잔액 환불 ★ ✅
- **Tags:** [DOMAIN] [API]
- **Goal:** 잔액 환불 서비스(60% 사용 조건 검증, Generated Column 활용), 보상 트랜잭션 생성, VoucherRefundedEvent 발행
- **Key implementation concern:** 환불 조건(usage_ratio ≥ 0.6) 검증과 환불 처리 사이에 다른 결제가 끼어들지 않도록 분산락으로 직렬화. 보상 트랜잭션이 원 거래와 정확히 연결되는 구조
- **커머스 도메인 역량:** 잔액 환불 정책은 지역사랑상품권 고유 규칙. 정책 이해도를 직접 보여줌

### Task 10a: Voucher 모듈 — 청약철회 ★ ✅
- **Tags:** [DOMAIN] [API]
- **Goal:** 청약철회 서비스(구매 후 7일 이내 + ACTIVE 상태 검증), 전액 환불 처리, VoucherWithdrawnEvent 발행
- **Key implementation concern:** 청약철회와 결제 요청의 동시 경합을 분산락으로 직렬화. 잔액환불(60% 규칙)과 별개의 프로세스로 명확히 분리
- **커머스 도메인 역량:** 전자상거래법 준수. 법적 요구사항 이해도를 보여줌

### Task 11: 거래 취소 및 보상 트랜잭션 ★★ ✅
- **Tags:** [DOMAIN] [API]
- **Goal:** 거래 취소 서비스 — 원 거래를 수정하지 않고 역방향 보상 트랜잭션 + 원장 엔트리 생성, 잔액 복원, 정산 차감 처리
- **Key implementation concern:** 이미 정산된 거래의 취소 시 "다음 정산 주기에서 차감" 로직. `original_transaction_id` 연결 무결성
- **커머스 도메인 역량:** 시니어 레벨 설계 포인트. DELETE 대신 보상 트랜잭션으로 감사 추적성 완벽 보장

### Task 12: 만료 처리 스케줄러 ★ ✅
- **Tags:** [DOMAIN] [INFRA]
- **Goal:** `@Scheduled` 배치로 만료 대상 상품권 스캔 → 상태 변경 → 잔액→만료 계정 원장 기록 → VoucherExpiredEvent 발행
- **Key implementation concern:** 대량 만료 처리 시 chunk 단위 커밋으로 메모리/락 점유 시간 관리. 실시간 결제와의 경합 처리 (건별 `SELECT FOR UPDATE`)
- **커머스 도메인 역량:** 상품권 만료 시 잔액의 회계 처리 — 공공 자금 정산의 필수 요소

### Task 13: Settlement 모듈 — 가맹점 정산 ★ ✅
- **Tags:** [DOMAIN] [API]
- **Goal:** 정산 배치(Region별 정산주기에 따라 역월 기준 사용 내역 합산), 정산 확정/이의제기 플로우, SettlementConfirmedEvent 발행, Unique Constraint로 중복 정산 방지
- **Key implementation concern:** 정산 기간 중 취소된 거래의 차감 처리. 보상 트랜잭션과 정산의 연계
- **커머스 도메인 역량:** 가맹점 정산은 지역사랑상품권 운영의 핵심 업무

### Task 14: 원장 정합성 검증 배치 ★★ ✅
- **Tags:** [DOMAIN] [TEST]
- **Goal:** LedgerVerificationService — 전체 차변/대변 균형 검증, Voucher.balance vs 원장 합산 불일치 탐지, 불일치 시 CRITICAL 감사 로그 + 알림(로그 기반), 헬스체크 메트릭 연동
- **Key implementation concern:** 대량 데이터 검증 시 성능. 불일치 발견 시 자동 수정하지 않고 보고만 하는 것이 원칙 (사람이 판단)
- **커머스 도메인 역량:** "시스템이 스스로 재무 정합성을 검증한다"는 것은 공공 금융 시스템의 필수 요건. 면접에서 가장 인상적인 포인트

### Task 15: 통합 테스트 — 동시성 및 E2E 시나리오 ★ ✅
- **Tags:** [TEST]
- **Goal:** Testcontainers(MySQL + Redis)로 실제 동시성 시나리오 테스트 — 10개 스레드 동시 결제, 이중 사용 방지, 멱등키 동작, 만료 중 결제 경합
- **Key implementation concern:** 동시성 테스트의 비결정성 최소화. CountDownLatch로 동시 시작점 제어, 결과의 불변식 검증(잔액 ≥ 0, 원장 균형)
- **커머스 도메인 역량:** "이 시스템은 동시성 문제를 실제로 테스트한다"는 것 자체가 운영 안정성 증명

### Task 16: API 문서화 및 README 작성 ✅
- **Tags:** [API]
- **Goal:** Swagger/OpenAPI로 API 문서화, 포트폴리오 README 완성
- **Key implementation concern:** README가 기술 블로그가 아니라 "이 시스템이 왜 이렇게 설계되었는가"를 설명하도록 구성
- **커머스 도메인 역량:** N/A (하지만 README 구조가 면접관의 첫인상을 결정)

---

## ★ 표기 기준

| 표기 | 의미 |
|------|------|
| ★ | 핵심 도메인 역량을 직접 보여주는 태스크 |
| ★★ | 프로젝트의 기술적 핵심. 면접에서 집중 설명할 포인트 |

---

## 구현 완료 현황

전체 16개 태스크 모두 구현 완료 + 코드 품질 개선(BigDecimal compareTo, Redis Lua 스크립트, TransactionTemplate 락-커밋 순서 보장). 총 28개 커밋, 소스 파일 84개(메인), 테스트 파일 13개.

### 후속 과제

- 패키지를 `com.commerce`로 리네임 완료(커머스 재포지셔닝).

---

## README 구조

```markdown
# 모바일 상품권 관리 시스템 (Mobile Voucher Management System)

## 프로젝트 개요
- 한 줄 설명: 지역사랑상품권의 발행-유통-정산 전 생애주기를 관리하는 백엔드 시스템
- 주요 설계 원칙 3가지 (재무 무결성, 감사 추적성, 동시성 안전)

## 시스템 아키텍처
- 모듈 구조도 (6개 도메인 모듈 + 공통 모듈)
- 핵심 흐름도: 구매 → 결제 → 환불 → 정산

## 기술적 의사결정 (★ 이 섹션이 가장 중요)
- 왜 복식부기 원장인가
- 왜 보상 트랜잭션인가
- 왜 분산락 + DB 비관적 락 이중 방어인가
- 왜 멱등키 이중 저장인가
- 왜 원장 기록은 이벤트가 아닌 동기 호출인가

## 동시성 제어 전략
- 테이블: 작업별 동시성 전략 매핑

## 도메인 이벤트 설계
- 이벤트 목록 및 Kafka-replaceable 구조 설명

## 기술 스택

## 실행 방법
- docker compose up 한 줄로 실행

## 테스트
- 동시성 테스트 시나리오 설명 및 실행 결과
```

**포함하지 말 것:**
- 개인 블로그 링크, 학습 후기
- "~를 공부하면서 만들었습니다" 같은 학습 프로젝트 뉘앙스
- 스크린샷 (백엔드 프로젝트이므로)
- 지나치게 긴 설치 가이드

---

## 면접 2분 답변: 가장 인상적인 기술적 결정

> **"이 시스템에서 가장 중요하게 설계한 부분은 원장 정합성 검증 체계입니다."**
>
> "상품권 시스템에서 가장 치명적인 사고는 '돈이 맞지 않는 것'입니다. 그래서 두 가지 계층으로 설계했습니다.
>
> 첫째, 모든 금전 변동을 복식부기 원장으로 기록합니다. 상품권 잔액은 성능을 위해 캐시 필드로 유지하지만, 진실의 원천(source of truth)은 항상 원장입니다. 취소나 환불도 원 거래를 수정하지 않고 역방향 보상 트랜잭션을 생성합니다. 이렇게 하면 어떤 시점에든 '이 돈이 어디서 와서 어디로 갔는지'를 원장만으로 추적할 수 있습니다. 특히 원장 기록은 이벤트 리스너가 아닌 동기 호출로 잔액 변경과 같은 DB 트랜잭션에서 처리합니다. 이벤트 기반으로 하면 커밋 후 리스너 실행 전 장애 시 원장 누락이 발생할 수 있기 때문입니다.
>
> 둘째, 정합성 검증 배치가 주기적으로 캐시 잔액과 원장 합산을 비교합니다. 불일치가 발견되면 자동 수정하지 않고 CRITICAL 등급 감사 로그를 남기고 관리자에게 알립니다. 자동 수정은 또 다른 오류를 만들 수 있기 때문입니다.
>
> 이 구조가 중요한 이유는, 지역사랑상품권은 지자체 예산으로 운영되는 공공 자금이기 때문입니다. 기업 포인트와 달리, 1원이라도 안 맞으면 감사 대상이 됩니다. 그래서 '정합성을 보장하는 것'뿐 아니라 '정합성을 증명할 수 있는 것'이 핵심이었습니다."
