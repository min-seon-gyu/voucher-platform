-- Transactional Outbox: 비핵심(HIGH/MEDIUM) 도메인 이벤트를 비즈니스 트랜잭션과 같은 tx에서 기록한다.
-- relay(Kafka 또는 직접)가 미발행 행을 읽어 감사 로그로 적용하고 published로 마킹한다(at-least-once).
create table outbox_events (
    id             bigint auto_increment primary key,
    event_id       varchar(36)  not null unique,
    event_type     varchar(50)  not null,
    aggregate_type varchar(30)  not null,
    aggregate_id   bigint       not null,
    severity       enum ('CRITICAL','HIGH','MEDIUM') not null,
    payload        json         not null,
    published      bit          not null,
    created_at     datetime(6)  not null,
    published_at   datetime(6)  null
);

create index idx_outbox_unpublished on outbox_events (published, id);
