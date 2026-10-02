-- An order groups attempts (plan E, spec §4.3). The partial unique index is the rule "one active
-- attempt per order": the database refuses the second, and OrderPayments turns the violation into
-- 409 ORDER_HAS_ACTIVE_PAYMENT. Opaque to payments: no foreign key, billing owns the table.
ALTER TABLE payments.payments ADD COLUMN order_id CHAR(26);
CREATE INDEX idx_payments_order ON payments.payments (order_id) WHERE order_id IS NOT NULL;
CREATE UNIQUE INDEX uq_payments_order_active ON payments.payments (order_id)
 WHERE order_id IS NOT NULL AND status IN ('CREATED', 'PENDING', 'AUTHORIZED');
