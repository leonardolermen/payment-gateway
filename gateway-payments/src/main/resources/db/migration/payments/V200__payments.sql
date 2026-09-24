-- payments module (spec §9). Money is BIGINT cents; the state machine lives in code, the columns only store it.
-- txid is the payment id (ULID): it is what lets a retry after a timeout ask the bank "does this charge exist?".

CREATE TABLE payments (
    id                     CHAR(26)     PRIMARY KEY,
    merchant_id            CHAR(26)     NOT NULL,
    environment            VARCHAR(10)  NOT NULL,           -- LIVE | TEST, always from the API key
    provider               VARCHAR(20)  NOT NULL,
    method                 VARCHAR(10)  NOT NULL,           -- PIX (BOLETO/CARD come with later plans)
    status                 VARCHAR(20)  NOT NULL,
    amount                 BIGINT       NOT NULL,
    currency               CHAR(3)      NOT NULL,
    reference              VARCHAR(140),
    description            VARCHAR(140),
    customer_document_hash CHAR(64),                        -- SHA-256 of the CPF/CNPJ, for search; the value itself is never stored in plan B
    details                JSONB        NOT NULL DEFAULT '{}'::jsonb,   -- method-specific: pix{txid,pixCopiaECola,location,endToEndId}
    expires_at             TIMESTAMPTZ,
    paid_at                TIMESTAMPTZ,
    paid_amount            BIGINT,
    refunded_amount        BIGINT       NOT NULL DEFAULT 0,
    version                BIGINT       NOT NULL DEFAULT 0, -- sequence of the last event; optimistic lock
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_payments_merchant_created ON payments (merchant_id, created_at DESC);
CREATE INDEX idx_payments_status_expires ON payments (status, expires_at);
CREATE UNIQUE INDEX uq_payments_provider_txid ON payments (provider, (details->>'txid'));
CREATE INDEX idx_payments_e2eid ON payments ((details->>'endToEndId'));

-- The log that rebuilds a payment. sequence is per payment and is the version.
CREATE TABLE payment_events (
    id         CHAR(26)    PRIMARY KEY,
    payment_id CHAR(26)    NOT NULL REFERENCES payments (id),
    sequence   BIGINT      NOT NULL,
    type       VARCHAR(30) NOT NULL,
    source     VARCHAR(20) NOT NULL,
    payload    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_payment_events_sequence UNIQUE (payment_id, sequence)
);

CREATE TABLE refunds (
    id                 CHAR(26)     PRIMARY KEY,                 -- also the {id} at the bank
    payment_id         CHAR(26)     NOT NULL REFERENCES payments (id),
    merchant_id        CHAR(26)     NOT NULL,
    amount             BIGINT       NOT NULL,
    state              VARCHAR(20)  NOT NULL,
    provider_refund_id VARCHAR(64),
    reason             VARCHAR(140),
    requested_at       TIMESTAMPTZ  NOT NULL,
    settled_at         TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_refunds_payment ON refunds (payment_id);
CREATE INDEX idx_refunds_state ON refunds (state);

-- Written BEFORE any external call (spec §3.2): the row is the lock.
CREATE TABLE idempotency_keys (
    merchant_id   CHAR(26)     NOT NULL,
    key           VARCHAR(128) NOT NULL,
    request_hash  CHAR(64)     NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    response_code INTEGER,
    response_body TEXT,
    resource_id   CHAR(26),
    created_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (merchant_id, key)
);
CREATE INDEX idx_idempotency_created ON idempotency_keys (created_at);

-- Transactional outbox: written in the same transaction as the payment change; the relay in app delivers.
CREATE TABLE outbox (
    id            CHAR(26)     PRIMARY KEY,
    merchant_id   CHAR(26)     NOT NULL,
    aggregate_id  CHAR(26)     NOT NULL,
    partition_key VARCHAR(64),
    event_type    VARCHAR(60)  NOT NULL,
    payload       TEXT         NOT NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    claimed_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_outbox_pending ON outbox (status, created_at) WHERE status = 'PENDING';

-- Postgres as the job queue (spec §2): lease + SKIP LOCKED, the same shape as webhook-delivery's claim.
CREATE TABLE jobs (
    id          CHAR(26)    PRIMARY KEY,
    type        VARCHAR(30) NOT NULL,
    ref_id      VARCHAR(64) NOT NULL,
    next_run_at TIMESTAMPTZ NOT NULL,
    attempts    INTEGER     NOT NULL DEFAULT 0,
    status      VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    claimed_at  TIMESTAMPTZ,
    last_error  VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_jobs_type_ref UNIQUE (type, ref_id)
);
CREATE INDEX idx_jobs_due ON jobs (status, next_run_at, claimed_at) WHERE status = 'PENDING';

-- Raw provider webhooks, stored before anything else (the bank gives us 5 s).
CREATE TABLE webhook_inbox (
    id          CHAR(26)    PRIMARY KEY,
    provider    VARCHAR(20) NOT NULL,
    merchant_id CHAR(26)    NOT NULL,
    raw_headers TEXT        NOT NULL,
    raw_body    BYTEA       NOT NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'RECEIVED',
    error       VARCHAR(500),
    received_at TIMESTAMPTZ NOT NULL
);

-- Every call to a bank, for support and for the metrics per provider.
CREATE TABLE provider_requests (
    id         CHAR(26)    PRIMARY KEY,
    payment_id CHAR(26),
    provider   VARCHAR(20) NOT NULL,
    operation  VARCHAR(40) NOT NULL,
    request    TEXT,
    response   TEXT,
    status     INTEGER     NOT NULL,
    latency_ms BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_provider_requests_payment ON provider_requests (payment_id);

CREATE TABLE reconciliation_divergences (
    id              CHAR(26)    PRIMARY KEY,
    payment_id      CHAR(26)    NOT NULL,
    gateway_status  VARCHAR(20) NOT NULL,
    provider_status VARCHAR(30) NOT NULL,
    detail          VARCHAR(500),
    status          VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ NOT NULL
);
