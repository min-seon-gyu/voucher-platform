// 시나리오 B — 분산 다수 상품권 결제 (자원 천장: HikariCP 풀 / DB 쓰기 IOPS)
// 목적: 서로 다른 상품권에 분산시켜 락 경합을 최소화하면, 병목이 락이 아니라
//       커넥션 풀 고갈(hikaricp_connections_pending↑) / DB 쓰기 IOPS / CPU 로 이동한다.
//       HikariCP 풀 크기만 단일 변수로 바꿔(10→20→40) before/after 를 비교한다.
import { createRegion, registerMember, createMerchant, issueVoucher, postJson, record, uniqueKey, rampingProfile } from './lib/common.js';

const VOUCHERS = parseInt(__ENV.VOUCHERS || '200');

export const options = {
  scenarios: { distributed: rampingProfile() },
  thresholds: {
    http_req_duration: ['p(99)<2000'],
    result_server_error: ['count<1'],
  },
};

export function setup() {
  const regionId = createRegion();
  const memberId = registerMember();
  const merchantId = createMerchant(regionId, registerMember());
  const ids = [];
  for (let i = 0; i < VOUCHERS; i++) {
    ids.push(issueVoucher(memberId, regionId, '100000000')); // 1e8 each — 소진되지 않게
  }
  return { ids, merchantId };
}

export default function (data) {
  const vid = data.ids[Math.floor(Math.random() * data.ids.length)];
  const res = postJson(
    `/api/v1/vouchers/${vid}/redeem`,
    { merchantId: data.merchantId, amount: 100 },
    { 'Idempotency-Key': uniqueKey() },
  );
  record(res);
}
