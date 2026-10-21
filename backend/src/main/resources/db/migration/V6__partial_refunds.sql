-- Partial refunds.
--
-- A refund used to be all-or-nothing, so the status alone could carry the fact: REFUNDED meant "the whole
-- amount went back". Once a payment can be refunded in instalments the status cannot hold the answer any
-- more -- "partly refunded" is a number, not a state -- so the running total becomes the source of truth and
-- the status just says which side of "fully refunded" we are on.

ALTER TABLE payments
    ADD COLUMN refunded_amount_minor BIGINT NOT NULL DEFAULT 0;

-- The invariant that matters: a merchant can never give back more than was taken. Enforced in the domain for
-- a clear error message, and here as well, because a constraint in the database is the one that cannot be
-- bypassed by a bug, a future code path, or someone at a psql prompt.
ALTER TABLE payments
    ADD CONSTRAINT payments_refunded_within_amount
        CHECK (refunded_amount_minor >= 0 AND refunded_amount_minor <= amount_minor);

-- Payments already fully refunded under the old model.
UPDATE payments SET refunded_amount_minor = amount_minor WHERE status = 'REFUNDED';

-- PARTIALLY_REFUNDED: some money returned, some still held.
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments
    ADD CONSTRAINT payments_status_check
        CHECK (status IN ('PENDING', 'AUTHORIZED', 'CAPTURED', 'SETTLED', 'FAILED',
                          'PARTIALLY_REFUNDED', 'REFUNDED'));

-- Keeps "which payments still have money that could be returned" cheap, and is the index the finance-facing
-- queries want: partially refunded payments are the ones somebody has to reconcile.
CREATE INDEX idx_payments_merchant_refunded ON payments (merchant_id, refunded_amount_minor)
    WHERE refunded_amount_minor > 0;
