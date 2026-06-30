// 시나리오 A — 단일 상품권 핫키 (Redisson + SELECT FOR UPDATE 락 직렬화 상한)
// 목적: "인위적 최악 케이스"로 락 직렬화 천장을 측정. 한 상품권에 모든 요청이 몰리면
//       동시성과 무관하게 처리량이 1/(임계구역+락RTT+커밋) 에서 평탄화되고 p99가 선형 상승한다.
// 라벨: 이것은 의도적 핫키 스트레스 테스트다(현실 트래픽 아님).
import { createRegion, registerMember, createMerchant, issueVoucher, postJson, record, uniqueKey, rampingProfile } from './lib/common.js';

export const options = {
  scenarios: { hotkey: rampingProfile() },
  thresholds: {
    http_req_duration: ['p(99)<3000'],
    result_server_error: ['count<1'], // 진짜 5xx 는 0이어야. 늘어나는 건 lock_timeout(정상 백프레셔)뿐
  },
};

export function setup() {
  const regionId = createRegion();
  const memberId = registerMember();
  const merchantId = createMerchant(regionId, registerMember());
  // 잔액이 소진되지 않도록 매우 큰 액면가(1e12). 실패는 오직 lock_timeout 이어야 한다.
  const voucherId = issueVoucher(memberId, regionId, '1000000000000');
  return { voucherId, merchantId };
}

export default function (data) {
  const res = postJson(
    `/api/v1/vouchers/${data.voucherId}/redeem`,
    { merchantId: data.merchantId, amount: 100 },
    { 'Idempotency-Key': uniqueKey() }, // 유니크 키 → 멱등 단축경로를 타지 않게
  );
  record(res);
}
