-- A plan is catalogue, not money in flight: no environment, immutable price and interval (spec
-- §4.4). Changing the price is a new plan, so running subscriptions never move by accident.
CREATE TABLE billing.plans (
    id             CHAR(26)     PRIMARY KEY,
    merchant_id    CHAR(26)     NOT NULL,
    name           VARCHAR(80)  NOT NULL,
    amount         BIGINT       NOT NULL,
    currency       CHAR(3)      NOT NULL,
    interval       VARCHAR(5)   NOT NULL,
    interval_count SMALLINT     NOT NULL,
    trial_days     SMALLINT     NOT NULL,
    active         BOOLEAN      NOT NULL,
    version        BIGINT       NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_plans_merchant ON billing.plans (merchant_id, active);
