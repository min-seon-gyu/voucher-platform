-- 주문에 적용된 쿠폰 참조(할인 적용). 취소/환불 시 쿠폰·예산 반환 판단에 사용.
alter table orders add column coupon_id bigint null;
