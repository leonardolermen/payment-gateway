-- A stored card belongs to a customer once the merchant registers one (plan E, spec §4.1). Cards
-- saved before customers existed carry only the document hash; CustomerService adopts them by it.
ALTER TABLE payments.cards ADD COLUMN customer_id CHAR(26);
CREATE INDEX idx_cards_customer ON payments.cards (customer_id) WHERE deleted_at IS NULL;
