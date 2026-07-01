-- 부분환불: 라인 단위 환불 플래그 + 주문 누적 환불액 + 상태 enum 확장(PARTIALLY_REFUNDED, REFUNDED).
-- 환불된 라인은 정산 합산에서 제외되고 재환불이 거부된다. refunded_amount는 매 환불마다 증가해
-- orders 행에 versioned UPDATE를 유발하므로, 동시 환불이 낙관적 락으로 직렬화되어 상태 고착을 막는다.
alter table order_lines add column refunded boolean not null default false;
alter table orders add column refunded_amount decimal(38, 2) not null default 0;
alter table orders modify column status enum ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED', 'CANCELLED') not null;
