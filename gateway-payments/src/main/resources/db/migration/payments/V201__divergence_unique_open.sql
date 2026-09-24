-- At most one OPEN divergence per (payment, provider status). Reconciliation runs every 15 minutes
-- over a 48 h window; the check-then-insert this replaces loaded every OPEN row per mismatch and
-- still let two concurrent writers both insert. Existing duplicates (same meaning, later copies)
-- are removed first so the index can be built; the oldest row, the one a human may already be
-- reading, is kept.
DELETE FROM payments.reconciliation_divergences d
 USING payments.reconciliation_divergences keep
 WHERE d.status = 'OPEN' AND keep.status = 'OPEN'
   AND d.payment_id = keep.payment_id AND d.provider_status = keep.provider_status
   AND (d.created_at, d.id) > (keep.created_at, keep.id);

CREATE UNIQUE INDEX ux_divergences_open_payment_status
    ON payments.reconciliation_divergences (payment_id, provider_status)
 WHERE status = 'OPEN';
