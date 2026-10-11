-- V104: the last "test connection" outcome lives on the credential, so the panel shows
-- "conectado em …" without calling the bank on every open. detail is one of our fixed phrases.
-- fingerprint identifies the stored secret blob without revealing it; secrets_set records which
-- secret fields have a value, so the panel can show "configured" without ever reading them back.
ALTER TABLE merchants.provider_credentials
    ADD COLUMN last_test_ok BOOLEAN,
    ADD COLUMN last_test_detail VARCHAR(120),
    ADD COLUMN last_test_at TIMESTAMPTZ,
    ADD COLUMN fingerprint CHAR(64),
    ADD COLUMN secrets_set JSONB NOT NULL DEFAULT '{}'::jsonb;
