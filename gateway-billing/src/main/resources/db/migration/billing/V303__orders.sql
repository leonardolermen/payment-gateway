-- An order is a sale: value, who pays, and the attempts that try to settle it (spec §4.2).
-- customer_id XOR payer: a registered customer, or the inline payer of a checkout without one.
-- payer keeps the document sealed (document_ciphertext, like billing.customers): a stored order
-- re-attempting a Bolecode needs the digits, and a hash alone could not give them back. The
-- invoice columns are set only when the order is a subscription cycle.
CREATE TABLE billing.orders (
    id              CHAR(26)     PRIMARY KEY,
    merchant_id     CHAR(26)     NOT NULL,
    environment     VARCHAR(10)  NOT NULL,
    customer_id     CHAR(26),
    payer           JSONB,
    amount          BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,
    reference       VARCHAR(100),
    description     VARCHAR(200),
    status          VARCHAR(10)  NOT NULL,
    paid_payment_id CHAR(26),
    paid_at         TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    subscription_id CHAR(26),
    invoice_number  INTEGER,
    period_start    DATE,
    period_end      DATE,
    version         BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_orders_who_pays CHECK ((customer_id IS NULL) <> (payer IS NULL))
);
CREATE INDEX idx_orders_merchant_reference ON billing.orders (merchant_id, environment, reference);
CREATE INDEX idx_orders_merchant_created ON billing.orders (merchant_id, environment, created_at DESC);
CREATE UNIQUE INDEX uq_orders_invoice ON billing.orders (subscription_id, invoice_number)
 WHERE subscription_id IS NOT NULL;
CREATE INDEX idx_orders_open_expiry ON billing.orders (expires_at) WHERE status = 'OPEN' AND expires_at IS NOT NULL;

-- Idempotency of the internal outbox consumer (spec §8): one row per event the billing module
-- already reacted to, written in the same transaction as the reaction.
CREATE TABLE billing.processed_events (
    event_id     CHAR(26)    PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
