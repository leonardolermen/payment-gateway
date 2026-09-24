-- GET /v1/payments?reference=... is what a client runs after a 409 IN_PROGRESS to learn whether
-- the interrupted create left a payment behind; without this it scans every payment of the merchant.
CREATE INDEX idx_payments_merchant_reference ON payments.payments (merchant_id, reference) WHERE reference IS NOT NULL;
