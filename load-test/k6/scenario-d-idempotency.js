// 시나리오 D — 멱등성 동시성 (exactly-once, 정합성 데모) [선택]
// 목적: 키 풀(KEYS개)을 여러 VU가 동시에 폭격 → 키마다 단 1건만 실제 처리되고
//       나머지는 409(처리중) 또는 캐시 재반환(200) 되어야 한다(이중 차감 0).
// 이건 처리량보다 '고부하에서 멱등성이 깨지지 않음'을 보이는 정합성 테스트다.
// 부하 후 SQL 로 불변식을 확인한다:
//   사용한 키 수 == COMPLETED REDEMPTION 거래 수, 잔액 == 초기 - (키수 × amount)
import { createRegion, registerMember, createMerchant, issueVoucher, postJson, record, rampingProfile } from './lib/common.js';

const KEYS = parseInt(__ENV.KEYS || '200');

export const options = {
  scenarios: { idempotency: rampingProfile() },
  thresholds: {
    result_server_error: ['count<1'],
  },
};

export function setup() {
  const regionId = createRegion();
  const memberId = registerMember();
  const merchantId = createMerchant(regionId, registerMember());
  const voucherId = issueVoucher(memberId, regionId, '1000000000000'); // 큰 잔액(키당 1회만 차감되어야)
  const keys = [];
  for (let i = 0; i < KEYS; i++) keys.push(`idem-${Date.now()}-${i}`);
  return { voucherId, merchantId, keys };
}

export default function (data) {
  // 키 풀에서 무작위 선택 → 같은 키에 동시 요청이 몰림
  const key = data.keys[Math.floor(Math.random() * data.keys.length)];
  const res = postJson(
    `/api/v1/vouchers/${data.voucherId}/redeem`,
    { merchantId: data.merchantId, amount: 100 },
    { 'Idempotency-Key': key },
  );
  record(res);
}
