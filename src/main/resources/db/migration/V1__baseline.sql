
    create table audit_logs (
        actor_id bigint,
        aggregate_id bigint not null,
        created_at DATETIME(6) not null,
        id bigint not null auto_increment,
        actor_type varchar(20),
        aggregate_type varchar(30) not null,
        event_id varchar(36) not null,
        action varchar(50) not null,
        event_type varchar(50) not null,
        idempotency_key varchar(64),
        current_state json,
        metadata json,
        previous_state json,
        severity enum ('CRITICAL','HIGH','MEDIUM') not null,
        primary key (id)
    ) engine=InnoDB;

    create table failed_events (
        resolved bit not null,
        retry_count integer not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        event_id varchar(36) not null,
        event_type varchar(50) not null,
        error_message TEXT not null,
        payload TEXT not null,
        primary key (id)
    ) engine=InnoDB;

    create table idempotency_keys (
        response_status integer,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        idempotency_key varchar(64) not null,
        response_body TEXT,
        status enum ('IN_PROGRESS','COMPLETED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table ledger_entries (
        amount decimal(38,2) not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        transaction_id bigint not null,
        account enum ('MEMBER_CASH','VOUCHER_BALANCE','MERCHANT_RECEIVABLE','REVENUE_DISCOUNT','EXPIRED_VOUCHER','REFUND_PAYABLE','SETTLEMENT_PAYABLE','PROMOTION_FUNDING','POINT_BALANCE','POINT_FUNDING') not null,
        entry_type enum ('PURCHASE','REDEMPTION','REFUND','WITHDRAWAL','EXPIRY','SETTLEMENT','CANCELLATION','MANUAL_ADJUSTMENT','COUPON_SUBSIDY','POINT_EARN') not null,
        side enum ('DEBIT','CREDIT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table members (
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6) not null,
        version bigint not null,
        name varchar(50) not null,
        email varchar(255) not null,
        password varchar(255) not null,
        role enum ('USER','MERCHANT_OWNER','ADMIN') not null,
        status enum ('PENDING','ACTIVE','SUSPENDED','WITHDRAWN') not null,
        primary key (id)
    ) engine=InnoDB;

    create table merchants (
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        owner_id bigint not null,
        region_id bigint not null,
        updated_at datetime(6) not null,
        version bigint not null,
        business_number varchar(20) not null,
        name varchar(100) not null,
        category enum ('RESTAURANT','CAFE','RETAIL','GROCERY','OTHER') not null,
        status enum ('PENDING_APPROVAL','APPROVED','REJECTED','SUSPENDED','TERMINATED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table regions (
        discount_rate decimal(5,2) not null,
        monthly_issuance_limit decimal(38,2) not null,
        purchase_limit_per_person decimal(38,2) not null,
        refund_threshold_ratio decimal(3,2) not null,
        region_code varchar(2) not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6) not null,
        version bigint not null,
        name varchar(50) not null,
        settlement_period enum ('DAILY','WEEKLY','MONTHLY') not null,
        status enum ('ACTIVE','SUSPENDED','DEACTIVATED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table settlements (
        period_end date not null,
        period_start date not null,
        total_amount decimal(38,2) not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        merchant_id bigint not null,
        updated_at datetime(6) not null,
        version bigint not null,
        dispute_reason varchar(255),
        status enum ('PENDING','CONFIRMED','PAID','DISPUTED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table transactions (
        amount decimal(38,2) not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        member_id bigint,
        merchant_id bigint,
        original_transaction_id bigint,
        updated_at datetime(6) not null,
        version bigint not null,
        voucher_id bigint,
        status enum ('PENDING','COMPLETED','FAILED','CANCEL_REQUESTED','CANCELLED') not null,
        type enum ('PURCHASE','REDEMPTION','REFUND','WITHDRAWAL','EXPIRY','CANCELLATION') not null,
        primary key (id)
    ) engine=InnoDB;

    create table vouchers (
        balance decimal(38,2) not null,
        face_value decimal(38,2) not null,
        created_at datetime(6) not null,
        expires_at datetime(6) not null,
        id bigint not null auto_increment,
        member_id bigint not null,
        purchased_at datetime(6) not null,
        region_id bigint not null,
        updated_at datetime(6) not null,
        version bigint not null,
        voucher_code varchar(19) not null,
        status enum ('ACTIVE','PARTIALLY_USED','EXHAUSTED','EXPIRED','REFUND_REQUESTED','REFUNDED','WITHDRAWAL_REQUESTED','WITHDRAWN') not null,
        primary key (id)
    ) engine=InnoDB;

    create index idx_audit_aggregate 
       on audit_logs (aggregate_type, aggregate_id, created_at);

    create index idx_audit_event_type 
       on audit_logs (event_type, created_at);

    create index idx_audit_actor 
       on audit_logs (actor_id, created_at);

    alter table audit_logs 
       add constraint UK_famedw8vl6xpi5l5b5l7320kv unique (event_id);

    alter table idempotency_keys 
       add constraint idx_idem_key unique (idempotency_key);

    create index idx_ledger_tx 
       on ledger_entries (transaction_id);

    create index idx_ledger_account 
       on ledger_entries (account, created_at);

    alter table members 
       add constraint UK_9d30a9u1qpg8eou0otgkwrp5d unique (email);

    alter table regions 
       add constraint UK_r0e525uwp8x05ly7akgf4cvsx unique (region_code);

    alter table settlements 
       add constraint uk_settlement_period unique (merchant_id, period_start, period_end);

    create index idx_tx_voucher 
       on transactions (voucher_id, created_at);

    create index idx_tx_merchant_period 
       on transactions (merchant_id, status, created_at);

    create index idx_voucher_member 
       on vouchers (member_id, status);

    create index idx_voucher_region_status 
       on vouchers (region_id, status, expires_at);

    create index idx_voucher_expiry 
       on vouchers (status, expires_at);

    alter table vouchers 
       add constraint UK_hvqsc8qffpt5okjmyot3a4b77 unique (voucher_code);

    alter table merchants 
       add constraint FK8ym7s9hse97bl4kg1kopq2iqy 
       foreign key (owner_id) 
       references members (id);

    alter table merchants 
       add constraint FK8r8uqolikhj01g0f3s4xqs6sa 
       foreign key (region_id) 
       references regions (id);
