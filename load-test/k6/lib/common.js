import http from 'k6/http';
import { Counter } from 'k6/metrics';

// 부하 대상 베이스 URL (호스트에서 bootRun 중인 앱)
export const BASE = __ENV.BASE_URL || 'http://localhost:8080';

// region_code 가 length=2, unique 제약이므로 충돌 시 재시도용 2글자 생성
const CHARS = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';
function rand2() {
  return CHARS[Math.floor(Math.random() * 36)] + CHARS[Math.floor(Math.random() * 36)];
}

// 요청마다 유니크한 멱등키 (jslib 원격 의존 없이 오프라인 동작)
export function uniqueKey() {
  return `${Date.now()}-${__VU}-${__ITER}-${Math.floor(Math.random() * 1e9)}`;
}

export function postJson(path, body, headers) {
  return http.post(BASE + path, JSON.stringify(body), {
    headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
  });
}

// ───────── 응답 4(+)분류: 에러율 오염 방지 ─────────
// success(2xx) / conflict(409 멱등 진행중) / lock_timeout(503) /
// insufficient(잔액부족·소진) / limit(월한도) / member_limit(1인한도) / server_error(5xx) / other
export function classify(res) {
  if (res.status >= 200 && res.status < 300) return 'success';
  if (res.status === 409) return 'conflict';
  let code = '';
  try { code = res.json('code') || ''; } catch (e) { /* non-json body */ }
  if (code === 'LOCK_ACQUISITION_FAILED') return 'lock_timeout';
  if (code === 'INSUFFICIENT_BALANCE' || code === 'VOUCHER_NOT_USABLE') return 'insufficient';
  if (code === 'REGION_MONTHLY_LIMIT_EXCEEDED') return 'limit';
  if (code === 'MEMBER_PURCHASE_LIMIT_EXCEEDED') return 'member_limit';
  if (res.status >= 500) return 'server_error';
  return 'other';
}

const counters = {
  success: new Counter('result_success'),
  conflict: new Counter('result_conflict'),
  lock_timeout: new Counter('result_lock_timeout'),
  insufficient: new Counter('result_insufficient'),
  limit: new Counter('result_limit'),
  member_limit: new Counter('result_member_limit'),
  server_error: new Counter('result_server_error'),
  other: new Counter('result_other'),
};

// 응답을 분류해 카운터에 기록 (k6 요약에 result_* 로 표시됨)
export function record(res) {
  const c = classify(res);
  (counters[c] || counters.other).add(1);
  return c;
}

// ───────── 시드 헬퍼 (셋업 단계에서 API로 데이터 생성) ─────────
const HUGE = '1000000000000000'; // 1e15 — 한도에 안 걸리도록

export function createRegion(monthlyLimit, purchaseLimit) {
  for (let i = 0; i < 30; i++) {
    const res = postJson('/api/v1/regions', {
      name: 'load-' + rand2(),
      regionCode: rand2(),
      discountRate: '0.10',
      purchaseLimitPerPerson: purchaseLimit || HUGE,
      monthlyIssuanceLimit: monthlyLimit || HUGE,
    });
    if (res.status === 201 || res.status === 200) return res.json('id');
  }
  throw new Error('createRegion 실패: region_code(2글자) 충돌. MySQL을 리셋 후 재시도하세요.');
}

export function registerMember() {
  const email = `load_${Date.now()}_${Math.floor(Math.random() * 1e9)}@test.com`;
  const res = postJson('/api/v1/members/register', { email, name: 'loadtester', password: 'password123' });
  if (res.status !== 201 && res.status !== 200) throw new Error('registerMember 실패: ' + res.status + ' ' + res.body);
  return res.json('id');
}

export function createMerchant(regionId, ownerId) {
  const bizNo = '123-45-' + String(Math.floor(Math.random() * 100000)).padStart(5, '0');
  const res = postJson('/api/v1/merchants', {
    name: 'shop-' + Math.floor(Math.random() * 1e9),
    businessNumber: bizNo,
    category: 'RESTAURANT',
    regionId: regionId,
    ownerId: ownerId,
  });
  if (res.status !== 201 && res.status !== 200) throw new Error('createMerchant 실패: ' + res.status + ' ' + res.body);
  const id = res.json('id');
  const ap = http.post(`${BASE}/api/v1/merchants/${id}/approve`);
  if (ap.status !== 200) throw new Error('merchant approve 실패: ' + ap.status + ' ' + ap.body);
  return id;
}

export function issueVoucher(memberId, regionId, faceValue) {
  const res = postJson('/api/v1/vouchers/purchase', { memberId, regionId, faceValue });
  if (res.status !== 201 && res.status !== 200) throw new Error('issueVoucher 실패: ' + res.status + ' ' + res.body);
  return res.json('id');
}

// 시나리오들이 공유하는 계단식 부하 프로파일 (open model)
// 워밍업 후 RPS 단계적 상승 → knee point 탐색. 필요시 __ENV.PEAK 로 상한 조정.
export function rampingProfile() {
  const peak = parseInt(__ENV.PEAK || '1500');
  const warm = __ENV.WARMUP || '30s';
  const stage = __ENV.STAGE || '90s';   // 단계 길이. 짧은 검증은 -e STAGE=40s
  return {
    executor: 'ramping-arrival-rate',
    startRate: 50,
    timeUnit: '1s',
    preAllocatedVUs: parseInt(__ENV.PRE_VUS || '300'),
    maxVUs: parseInt(__ENV.MAX_VUS || '3000'),
    stages: [
      { target: 50, duration: warm },                          // 워밍업 (집계에서 제외하고 보기)
      { target: Math.round(peak * 0.13), duration: stage },
      { target: Math.round(peak * 0.33), duration: stage },
      { target: Math.round(peak * 0.66), duration: stage },
      { target: peak, duration: stage },
    ],
  };
}
