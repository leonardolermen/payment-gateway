-- Operations (plan "operations and disputes", spec 2026-10-04 §2). A divergence gains a lifecycle
-- and an origin: SYSTEM rows are what reconciliation, settlement and the card/boleto paths open;
-- MERCHANT rows are disputes a merchant opens on its own payment. Both sit in one queue for the
-- operator. Resolving never moves money: refunds and cancels keep their own routes, so the audit of
-- "decided" and "paid back" are two distinct actions.
ALTER TABLE payments.reconciliation_divergences
    ADD COLUMN origin          VARCHAR(10)  NOT NULL DEFAULT 'SYSTEM',
    ADD COLUMN reason          VARCHAR(20),
    ADD COLUMN merchant_note   VARCHAR(500),
    ADD COLUMN resolution      VARCHAR(16),
    ADD COLUMN resolution_note VARCHAR(500),
    ADD COLUMN resolved_by     VARCHAR(80),
    ADD COLUMN resolved_at     TIMESTAMPTZ,
    ADD COLUMN updated_at      TIMESTAMPTZ;
UPDATE payments.reconciliation_divergences SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE payments.reconciliation_divergences ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE payments.reconciliation_divergences ALTER COLUMN status TYPE VARCHAR(12);

-- One unsettled (OPEN or UNDER_REVIEW) SYSTEM divergence per (payment, kind); one unsettled dispute
-- per payment. A SYSTEM row and a MERCHANT row may coexist: the bank disagreeing and the merchant
-- complaining are two facts. UNDER_REVIEW is in the SYSTEM index too: with OPEN only, an operator
-- reviewing a row freed the slot, and the next reconciliation pass that re-detected the same mismatch
-- inserted a second OPEN row for a case a human already held (queue duplicates, inflated gauge).
-- Safe on existing data: UNDER_REVIEW did not exist before this migration, and the old index already
-- allowed one OPEN row per (payment, provider_status). Keep in step with DivergenceStatus.UNSETTLED_NAMES.
DROP INDEX payments.ux_divergences_open_payment_status;
CREATE UNIQUE INDEX ux_divergences_open_system
    ON payments.reconciliation_divergences (payment_id, provider_status)
 WHERE status IN ('OPEN', 'UNDER_REVIEW') AND origin = 'SYSTEM';
CREATE UNIQUE INDEX ux_divergences_open_dispute
    ON payments.reconciliation_divergences (payment_id)
 WHERE status IN ('OPEN', 'UNDER_REVIEW') AND origin = 'MERCHANT';
CREATE INDEX idx_divergences_listing ON payments.reconciliation_divergences (status, origin, created_at DESC, id DESC);
