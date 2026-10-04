-- Two cycle or dunning runs past the job lease could both read "no attempt" on the same order and
-- charge a card twice: uq_payments_order_active covers only CREATED/PENDING/AUTHORIZED, so once the
-- first card payment is COMPLETED (and the relay has not yet marked the order PAID) the index no
-- longer refuses the second. This marker serializes the check and the charge per order; it expires
-- by itself (gateway.billing.attempt-lock) so a process that dies mid-call never locks the order.
ALTER TABLE billing.orders ADD COLUMN attempt_in_progress_at TIMESTAMPTZ;
