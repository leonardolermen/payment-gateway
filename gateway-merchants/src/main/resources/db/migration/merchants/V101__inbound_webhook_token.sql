-- Opaque per-merchant token in the path of the bank's inbound webhook URL. Not the merchant id: the
-- URL is registered at the bank and shows up in its logs and portal, and an id that also appears in
-- our API responses would let anyone who sees one guess the other. The token only resolves a merchant
-- once the mTLS handshake has already proven the caller is the bank.
ALTER TABLE merchants ADD COLUMN inbound_webhook_token CHAR(26);

-- Existing rows: not a real ULID (Postgres has no ULID generator) but 26 uppercase hex characters from
-- a random UUID — same width, same alphabet subset, and unique enough for a lookup key; the UNIQUE
-- constraint below turns the astronomically unlikely collision into a failed migration, not a shared URL.
-- New merchants get Ulid.next() from Merchant.create.
UPDATE merchants SET inbound_webhook_token = upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 26))
 WHERE inbound_webhook_token IS NULL;

ALTER TABLE merchants ALTER COLUMN inbound_webhook_token SET NOT NULL;
ALTER TABLE merchants ADD CONSTRAINT uq_merchants_inbound_webhook_token UNIQUE (inbound_webhook_token);
