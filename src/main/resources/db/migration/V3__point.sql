create table point_accounts (
    id bigint not null auto_increment,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    version bigint not null,
    member_id bigint not null,
    balance decimal(38,2) not null,
    primary key (id)
) engine=InnoDB;

create table point_transactions (
    id bigint not null auto_increment,
    member_id bigint not null,
    type enum ('EARN') not null,
    amount decimal(38,2) not null,
    balance_after decimal(38,2) not null,
    source_transaction_id bigint not null,
    created_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

alter table point_accounts
    add constraint UK_point_accounts_member_id unique (member_id);

create index idx_point_tx_member on point_transactions (member_id, created_at);
create index idx_point_tx_source on point_transactions (source_transaction_id);
