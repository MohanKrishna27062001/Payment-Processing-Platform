-- Core schema for the payment processing platform.
-- Money is stored as integer minor units (e.g. cents) to avoid floating-point errors.

CREATE TABLE merchants (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    email         VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,          -- bcrypt; never the plain password
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE payments (
    id                  UUID         PRIMARY KEY,
    merchant_id         UUID         NOT NULL REFERENCES merchants (id),
    amount_minor        BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency            CHAR(3)      NOT NULL,
    status              VARCHAR(20)  NOT NULL
        CHECK (status IN ('PENDING', 'AUTHORIZED', 'CAPTURED', 'SETTLED', 'FAILED', 'REFUNDED')),
    merchant_reference  VARCHAR(100),
    card_brand          VARCHAR(20),                 -- PCI: only brand + last4, never the PAN or CVV
    card_last4          CHAR(4),
    processor_reference VARCHAR(100),
    failure_reason      VARCHAR(200),
    version             BIGINT       NOT NULL DEFAULT 0,   -- optimistic locking
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Every payment query is scoped by merchant, so merchant_id leads each index.
CREATE INDEX idx_payments_merchant_created   ON payments (merchant_id, created_at DESC);
CREATE INDEX idx_payments_merchant_status    ON payments (merchant_id, status);
CREATE INDEX idx_payments_merchant_reference ON payments (merchant_id, merchant_reference);

-- Append-only audit trail of every state transition.
CREATE TABLE payment_events (
    id             BIGSERIAL    PRIMARY KEY,
    payment_id     UUID         NOT NULL REFERENCES payments (id),
    from_status    VARCHAR(20),                       -- null for the creation event
    to_status      VARCHAR(20)  NOT NULL,
    actor_type     VARCHAR(20)  NOT NULL CHECK (actor_type IN ('MERCHANT', 'SYSTEM')),
    actor_id       VARCHAR(100) NOT NULL,
    reason         VARCHAR(200),
    correlation_id VARCHAR(100),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_events_payment ON payment_events (payment_id, created_at);

-- Stored results of mutating requests so client retries replay instead of re-executing.
CREATE TABLE idempotency_keys (
    id              BIGSERIAL    PRIMARY KEY,
    merchant_id     UUID         NOT NULL REFERENCES merchants (id),
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,            -- SHA-256 of method + path + body
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idempotency_merchant_key UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);

-- Transactional outbox: async jobs written in the same transaction as the state change.
CREATE TABLE outbox_jobs (
    id              BIGSERIAL    PRIMARY KEY,
    job_type        VARCHAR(50)  NOT NULL,
    payment_id      UUID         NOT NULL REFERENCES payments (id),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'DEAD')),
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_at       TIMESTAMPTZ,                      -- lets stuck PROCESSING jobs be recovered
    last_error      TEXT,
    correlation_id  VARCHAR(100),                     -- continues the originating request's log trail
    traceparent     VARCHAR(100),                     -- W3C trace context, links worker spans to the API trace
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Partial index keeps the worker's polling query fast as DONE rows accumulate.
CREATE INDEX idx_outbox_due ON outbox_jobs (next_attempt_at) WHERE status = 'PENDING';
