-- The payer pays by a link the merchant forwards. The link carries a random token; the row keeps
-- only its peppered SHA-256, like merchants.api_keys: whoever reads the database cannot pay as the
-- payer. Nullable: orders created before this migration never had a token shown to anyone, and a
-- backfilled one would be a secret nobody holds. UNIQUE so a token resolves to one order and the
-- index serves the public lookup.
ALTER TABLE billing.orders ADD COLUMN checkout_token_hash CHAR(64);
CREATE UNIQUE INDEX ux_orders_checkout_token ON billing.orders (checkout_token_hash);
