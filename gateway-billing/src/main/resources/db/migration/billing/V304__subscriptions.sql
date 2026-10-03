-- A subscription is customer + plan + method; each cycle becomes an order with invoice_number
-- (spec §4.5). anchor_day keeps the day the subscription started so a month that is too short
-- (31 → Feb 28) does not shorten every month after it. ENDED is the natural end after
-- cancel_at_period_end; CANCELED is immediate.
CREATE TABLE billing.subscriptions (
    id                   CHAR(26)     PRIMARY KEY,
    merchant_id          CHAR(26)     NOT NULL,
    environment          VARCHAR(10)  NOT NULL,
    customer_id          CHAR(26)     NOT NULL,
    plan_id              CHAR(26)     NOT NULL,
    method               VARCHAR(10)  NOT NULL,
    card_id              CHAR(26),
    status               VARCHAR(10)  NOT NULL,
    anchor_day           SMALLINT     NOT NULL,
    current_period_start DATE,
    current_period_end   DATE,
    next_billing_at      TIMESTAMPTZ,
    last_invoice_number  INTEGER      NOT NULL,
    cancel_at_period_end BOOLEAN      NOT NULL,
    canceled_at          TIMESTAMPTZ,
    ended_at             TIMESTAMPTZ,
    version              BIGINT       NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_subscriptions_customer ON billing.subscriptions (merchant_id, environment, customer_id);
CREATE INDEX idx_subscriptions_due ON billing.subscriptions (next_billing_at)
 WHERE status IN ('ACTIVE', 'PAST_DUE');

-- What dunning did and when (spec §4.6): the merchant reads it on the subscription.
CREATE TABLE billing.dunning_attempts (
    id              CHAR(26)    PRIMARY KEY,
    subscription_id CHAR(26)    NOT NULL,
    order_id        CHAR(26)    NOT NULL,
    attempt         SMALLINT    NOT NULL,
    scheduled_at    TIMESTAMPTZ NOT NULL,
    ran_at          TIMESTAMPTZ,
    outcome         VARCHAR(20),
    payment_id      CHAR(26)
);
CREATE INDEX idx_dunning_subscription ON billing.dunning_attempts (subscription_id, attempt);
CREATE UNIQUE INDEX uq_dunning_pending_order ON billing.dunning_attempts (order_id) WHERE outcome IS NULL;
