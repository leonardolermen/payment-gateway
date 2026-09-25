-- Bolecode (spec 2026-09-25 §2): details becomes {"pix": {...}, "boleto": {...}|null}. Existing rows carry the
-- flat pix shape from V200; they are nested so every row reads the same way, and the two expression
-- indexes follow the keys. `details ? 'pix'` is Postgres' key-exists operator (Flyway passes the statement
-- through untouched; there is no JDBC placeholder here).

UPDATE payments.payments SET details = jsonb_build_object('pix', details) WHERE NOT (details ? 'pix');

DROP INDEX payments.uq_payments_provider_txid;
CREATE UNIQUE INDEX uq_payments_provider_txid ON payments.payments (provider, (details->'pix'->>'txid'));

DROP INDEX payments.idx_payments_e2eid;
CREATE INDEX idx_payments_e2eid ON payments.payments ((details->'pix'->>'endToEndId'));

-- The poll and the cancel look a boleto up by its number; the merchant lists by it in support.
CREATE INDEX idx_payments_merchant_nosso_numero ON payments.payments (merchant_id, (details->'boleto'->>'nossoNumero')) WHERE details ? 'boleto';

-- Nosso número: sequential per merchant, 8 digits, allocated with UPDATE ... RETURNING in the CREATED
-- transaction. One row per merchant; 10^8 numbers is more than enough that reuse (45 days after
-- baixa/liquidação, spec §8) is not handled.
CREATE TABLE payments.boleto_numbers (
    merchant_id CHAR(26) PRIMARY KEY,
    next_value  BIGINT   NOT NULL
);
