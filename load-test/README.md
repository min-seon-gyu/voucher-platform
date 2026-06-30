# voucher-platform 스케일 스터디

**목표**: 실 사용자/실트래픽이 없는 상태에서, **합성 부하로 시스템의 처리량 천장과 병목을 진단·해소하는 성능 엔지니어링 능력**과, **고부하에서도 거래 정합성(복식부기 불변식)이 깨지지 않음**을 증명한다. 동시에 채용공고의 *대용량 트래픽*(필수)과 *Grafana/Prometheus*(우대) 갭을 메운다.

> ⚠️ **정직성 원칙**: 이건 **단일 인스턴스 합성 부하테스트**다. 모든 수치는 이 하드웨어/설정 한정이며 "프로덕션 트래픽을 받았다"·"X DAU 처리"로 일반화하지 않는다. 신뢰 가능한 주장은 둘뿐: ① 동일 하드웨어에서 병목 제거로 **지속 TPS A→B 상대 개선**, ② **포화에서도 정합성 유지**.

---

## 0. 사전 준비
- Docker Desktop 실행 중
- JDK 17 (`JAVA_HOME`)
- k6 — 로컬 설치(`choco install k6` / `brew install k6`) **또는** Docker 이미지(`grafana/k6`) 사용

## 1. DB/Redis 기동 (프로젝트 루트)
```bash
docker compose up -d        # voucher-mysql(3306), voucher-redis(6379)
```

## 2. 앱 실행 (호스트, 8080)
```bash
# 기본(HikariCP 풀 10)
./gradlew bootRun
# 튜닝 변수: 풀 크기를 바꿔가며 재측정 (시나리오 B)
./gradlew bootRun --args='--spring.datasource.hikari.maximum-pool-size=40'
```
확인: `curl localhost:8080/actuator/prometheus | grep http_server_requests_seconds_bucket` 가 출력되면 계측 OK.

## 3. 관측 스택 기동
```bash
cd load-test/monitoring
docker compose up -d
```
- Prometheus: http://localhost:9090 (Status→Targets 가 전부 UP인지 확인)
- Grafana: http://localhost:3000 (익명 Admin) → **Voucher Scale Study** 대시보드 자동 로드

## 4. 부하 실행 (k6)
로컬 k6:
```bash
cd load-test/k6
k6 run scenario-a-hotkey.js
k6 run -e PEAK=2000 scenario-b-distributed.js          # 피크 RPS 조정
k6 run -e VOUCHERS=500 scenario-b-distributed.js        # 분산 키 수 조정
k6 run -e MONTHLY_LIMIT=100000000 scenario-c-region-issue.js
```
Docker k6 (로컬 설치 없이):
```bash
docker run --rm -i --add-host host.docker.internal:host-gateway \
  -e BASE_URL=http://host.docker.internal:8080 \
  -v "${PWD}:/scripts" grafana/k6 run /scripts/scenario-a-hotkey.js
```

### 시나리오
| 파일 | 측정 대상 | 라벨 |
|---|---|---|
| `scenario-a-hotkey` | 단일 상품권 **Redisson+SELECT FOR UPDATE 락 직렬화 상한** | 인위적 최악 |
| `scenario-b-distributed` | 분산 결제 — **HikariCP 풀 / DB IOPS 자원 천장** | 현실 워크로드 |
| `scenario-c-region-issue` | 동일 지역 발행 — **Redis Lua 카운터 핫스팟 + 월한도 정확성** | 현실 핫키 |
| `scenario-d-idempotency` | 멱등 동시성 — **exactly-once 정합성** | 정합성 데모(선택) |

## 5. 읽는 법 (knee point)
- **k6 콘솔**: 클라이언트측 — `http_reqs`(달성 RPS), `http_req_duration` p95/99, `result_*`(분류 카운트). `dropped_iterations`>0 이면 **생성기가 도착률을 못 채운 것**(SUT 포화 아님) → 그게 곧 이 리그의 천장.
- **Grafana**: 서버측 — RPS↑ → p99↑ → `hikaricp pending`↑ → `lock wait p99`↑ 가 수직으로 정렬되면 그 사슬이 병목. knee = 달성 RPS 평탄화 ∩ p99 꺾임.
- **에러 분류**: 늘어나는 게 `result_lock_timeout`(503, 정상 백프레셔)·`result_limit`(월한도)면 정상. `result_server_error`(5xx)가 늘면 진짜 문제.

## 6. 튜닝 루프 (변수 하나씩)
1. baseline 측정(풀 10, 기본). knee 위치·지속 TPS·p99 기록.
2. **시나리오 B**: 풀 10→20→40 으로 `bootRun` 재기동 후 동일 부하 재실행 → before/after. 풀은 처리량 노브가 아니라 DB 동시성 게이트 — 어느 지점부터 DB CPU/락으로 역U자 악화하는지 관찰.
3. **시나리오 A**: 임계구역 최소화(락 안에는 잔액검증+원장2INSERT+잔액UPDATE만)·`waitTime` fail-fast 효과를 코드 변경 후 재측정.
4. 각 튜닝 후 **7번 정합성 게이트** 통과 확인.

## 7. 정합성 검증 (부하 후 SQL — 스터디의 결정타)
```sql
-- ① 복식부기 전역 균형: DEBIT 합 == CREDIT 합
SELECT
  (SELECT COALESCE(SUM(amount),0) FROM ledger_entries WHERE side='DEBIT')  AS debit,
  (SELECT COALESCE(SUM(amount),0) FROM ledger_entries WHERE side='CREDIT') AS credit;

-- ② 음수 잔액 0건
SELECT COUNT(*) FROM vouchers WHERE balance < 0;

-- ③ 원장 불변성: 원장 행은 INSERT-only (UPDATE/DELETE 0). @Immutable 로 보장

-- ④ 시나리오 C 과다발행: 지역별 발행 합 ≤ MONTHLY_LIMIT 이어야 (Redis 카운터 정확성)
SELECT region_id, SUM(face_value) AS issued FROM vouchers GROUP BY region_id;

-- ⑤ 시나리오 D exactly-once: 사용한 키 수 == COMPLETED 결제 수, 잔액 == 초기-(키수×100)
SELECT COUNT(*) FROM transactions WHERE type='REDEMPTION' AND status='COMPLETED' AND voucher_id = <voucherId>;
```
> **반증가능성 시연(신뢰의 핵심)**: 위 어서션이 "통과"만 하면 공허하다. 일부러 락을 우회한 변형으로 ②가 한 번 **빨간불**이 켜지는 걸 보여, 검증이 실제로 위반을 잡아냄을 입증할 것.

## 8. 정리
```bash
cd load-test/monitoring && docker compose down
# 누적된 region/회원 데이터로 region_code(2글자) 가 고갈되면, 프로젝트 루트에서:
docker compose down -v && docker compose up -d   # MySQL 볼륨 초기화
```

---

## 9. 포트폴리오 1페이지 서사 (수치는 [실제 측정값]으로 채움)

> **고부하 합성 부하테스트로 상품권 금융 백엔드의 병목을 진단·해소하고, 포화 구간에서도 복식부기 거래 정합성이 유지됨을 증명** *(단일 인스턴스 synthetic load study)*
>
> - **문제**: 정합성 장치(복식부기·분산락·멱등성)는 있으나 실트래픽이 없어 고부하 동작이 미검증. 공고의 대용량·관측성 갭도 동시 존재.
> - **방법**: Micrometer→Prometheus→Grafana 관측 스택 구축, k6 오픈모델로 단계적 부하. 응답을 success/lock_timeout/insufficient/limit/5xx 로 분류해 에러율 오염 제거.
> - **진단**: 대시보드에서 RPS→p99→HikariCP pending→Redisson 락 대기가 수직 정렬돼 병목 사슬을 시각 확인. knee 원인이 시나리오별로 다름 — 핫키=락 직렬화(한 키 TPS [실제값]에서 평탄화), 분산=커넥션풀/DB IOPS, 발행=Redis 카운터.
> - **해소**: HikariCP 풀 튜닝(10→[실제값])으로 분산 워크로드 지속 TPS [A]→[B], p99 [-X%]. 천장이 락→풀→DB 로 이동하는 경로를 측정→진단→처방→재측정 루프로 기록.
> - **정합성**: 포화 피크에서 DEBIT==CREDIT, 잔액 마이너스 [0]건, 지역 과다발행 [0]건을 SQL 어서션으로 확인. 결함 주입 시 어서션이 실제로 빨간불을 켜는 것까지 시연.
> - **한계**: 실 사용자 0의 단일 인스턴스 합성 부하. 신뢰 주장은 ①상대 개선 ②정합성 유지 둘로 한정.
