-- CHAR(n) pads with spaces and maps to a different JDBC type than Java String, which Hibernate's schema
-- validation rejects. VARCHAR(n) keeps the same length limits without the padding semantics.
ALTER TABLE payments
    ALTER COLUMN currency TYPE VARCHAR(3),
    ALTER COLUMN card_last4 TYPE VARCHAR(4);

ALTER TABLE idempotency_keys
    ALTER COLUMN request_hash TYPE VARCHAR(64);
