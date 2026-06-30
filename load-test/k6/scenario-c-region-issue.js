// 시나리오 C — 동일 지역 동시 발행 (Redis Lua 월한도 카운터 핫스팟 + 한도 정확성)
// 목적: 같은 region 의 모든 발행이 단일 Redis 카운터(region:monthly:{id}:{month})에 INCRBY 로 몰린다.
//       DB 핫로우 대신 Redis 원자 연산으로 분산한 설계의 처리량과, 고부하에서 월한도가
//       정확히 지켜지는지(과다발행 0)를 측정한다.
// 주의: 멤버 락(withMemberPurchaseLock) 경합을 피하려고 다수 멤버를 사용해
//       '지역 카운터' 자체를 핫스팟으로 격리한다.
import { createRegion, registerMember, postJson, record, uniqueKey, rampingProfile } from './lib/common.js';

const MEMBERS = parseInt(__ENV.MEMBERS || '50');
const MONTHLY_LIMIT = __ENV.MONTHLY_LIMIT || '100000000'; // 1e8 — 테스트 중 도달하도록 제한
const FACE = __ENV.FACE || '10000';

export const options = {
  scenarios: { regionIssue: rampingProfile() },
  thresholds: {
    http_req_duration: ['p(99)<2000'],
    result_server_error: ['count<1'], // limit/member_limit 은 정상, 5xx 만 실패로 본다
  },
};

export function setup() {
  // 1인 한도는 매우 크게(멤버 락 병목 회피), 월 발행한도만 제한
  const regionId = createRegion(MONTHLY_LIMIT, '1000000000000000');
  const members = [];
  for (let i = 0; i < MEMBERS; i++) members.push(registerMember());
  return { regionId, members };
}

export default function (data) {
  const memberId = data.members[Math.floor(Math.random() * data.members.length)];
  const res = postJson(
    '/api/v1/vouchers/purchase',
    { memberId: memberId, regionId: data.regionId, faceValue: FACE },
    { 'Idempotency-Key': uniqueKey() },
  );
  record(res);
}

// 검증(부하 후 SQL): 발행 성공 건수 × FACE ≤ MONTHLY_LIMIT 이어야 한다(과다발행 0).
//   SELECT SUM(face_value) FROM vouchers WHERE region_id = ?;  ≤ MONTHLY_LIMIT
// Redis 증가 후 DB 롤백 시 over-count 가 발생하는지(soft-limit 누수)를 함께 확인.
