-- 커머스 pivot Phase 3: 장바구니 + 주문(다판매자) + 원장/거래 enum 확장(주문 결제/취소).

-- 거래 유형에 주문 결제/취소 추가.
alter table transactions
    modify column type enum (
        'PURCHASE','REDEMPTION','REFUND','WITHDRAWAL','EXPIRY','SETTLEMENT','CANCELLATION',
        'ORDER_PAYMENT','ORDER_CANCEL'
    ) not null;

-- 원장 분개유형에 주문 결제/취소 추가.
alter table ledger_entries
    modify column entry_type enum (
        'PURCHASE','REDEMPTION','REFUND','WITHDRAWAL','EXPIRY','SETTLEMENT','CANCELLATION',
        'MANUAL_ADJUSTMENT','COUPON_SUBSIDY','POINT_EARN','ORDER_PAYMENT','ORDER_CANCEL'
    ) not null;

-- 원장 계정에 커머스 주문 계정 추가(CUSTOMER_CASH/SELLER_PAYABLE).
alter table ledger_entries
    modify column account enum (
        'MEMBER_CASH','VOUCHER_BALANCE','MERCHANT_RECEIVABLE','REVENUE_DISCOUNT','EXPIRED_VOUCHER',
        'REFUND_PAYABLE','SETTLEMENT_PAYABLE','PROMOTION_FUNDING','POINT_BALANCE','POINT_FUNDING',
        'CUSTOMER_CASH','SELLER_PAYABLE'
    ) not null;

create table cart_items (
    id         bigint      not null auto_increment,
    member_id  bigint      not null,
    sku_id     bigint      not null,
    quantity   int         not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    version    bigint      not null,
    primary key (id),
    constraint uk_cart_member_sku unique (member_id, sku_id)
) engine=InnoDB;

create table orders (
    id                     bigint       not null auto_increment,
    member_id              bigint       not null,
    status                 enum ('PAID','CANCELLED') not null,
    total_amount           decimal(38,2) not null,
    discount_amount        decimal(38,2) not null,
    paid_amount            decimal(38,2) not null,
    payment_transaction_id bigint,
    created_at             datetime(6)  not null,
    updated_at             datetime(6)  not null,
    version                bigint       not null,
    primary key (id)
) engine=InnoDB;

create table order_lines (
    id         bigint       not null auto_increment,
    order_id   bigint       not null,
    sku_id     bigint       not null,
    seller_id  bigint       not null,
    quantity   int          not null,
    unit_price decimal(38,2) not null,
    line_amount decimal(38,2) not null,
    created_at datetime(6)  not null,
    updated_at datetime(6)  not null,
    version    bigint       not null,
    primary key (id)
) engine=InnoDB;

create index idx_order_member on orders (member_id, status);
create index idx_orderline_order on order_lines (order_id);
create index idx_orderline_seller on order_lines (seller_id);

alter table order_lines
    add constraint fk_orderline_order foreign key (order_id) references orders (id);
