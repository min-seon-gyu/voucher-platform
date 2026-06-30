-- transactions.type ENUM에 'SETTLEMENT' 값을 추가한다.
-- TransactionType 엔티티에는 SETTLEMENT가 존재하지만 V1 baseline의 ENUM 정의에서 누락되어,
-- 정산 확정 시 SETTLEMENT 거래 INSERT가 MySQL strict 모드에서 실패(1265)하던 결함을 해소한다.
ALTER TABLE transactions
    MODIFY COLUMN type ENUM(
        'PURCHASE','REDEMPTION','REFUND','WITHDRAWAL','EXPIRY','SETTLEMENT','CANCELLATION'
    ) NOT NULL;
