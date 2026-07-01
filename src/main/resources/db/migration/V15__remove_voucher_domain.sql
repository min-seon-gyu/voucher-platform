-- 커머스 pivot Phase 4b(PR2b): voucher 도메인 제거.
-- vouchers(선불상품권), coupon_redemptions(쿠폰-바우처 결합 결제 이력) 테이블 폐기.
-- coupon_redemptions를 먼저(vouchers 참조 가능) 드롭한다.
-- (transactions.voucher_id / ledger_entries.voucher_id 컬럼은 엔티티에 남아 유지 — 후속 PR에서 정리)
drop table if exists coupon_redemptions;
drop table if exists vouchers;
