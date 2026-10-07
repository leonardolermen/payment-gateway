-- A card subscription may start without a card (spec 2026-10-07 §2): it waits INCOMPLETE for its
-- first invoice to be paid by link, and ends INCOMPLETE_EXPIRED when that invoice is not. V304 had
-- no CHECK on status and a VARCHAR(10) too short for INCOMPLETE_EXPIRED, so the column widens and
-- both rules are written here for the first time.
ALTER TABLE billing.subscriptions ALTER COLUMN status TYPE VARCHAR(20);

ALTER TABLE billing.subscriptions ADD CONSTRAINT ck_subscriptions_status
    CHECK (status IN ('INCOMPLETE', 'INCOMPLETE_EXPIRED', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'ENDED'));

-- A CARD subscription that bills has a card; only one that never got one (waiting, expired, or
-- canceled while waiting) may lack it.
ALTER TABLE billing.subscriptions ADD CONSTRAINT ck_subscriptions_card_id
    CHECK (method <> 'CARD' OR card_id IS NOT NULL
           OR status IN ('INCOMPLETE', 'INCOMPLETE_EXPIRED', 'CANCELED'));

-- The panel's list (spec §5): newest first by id within a merchant and environment, like orders.
CREATE INDEX idx_subscriptions_merchant_env_id
    ON billing.subscriptions (merchant_id, environment, id DESC);
