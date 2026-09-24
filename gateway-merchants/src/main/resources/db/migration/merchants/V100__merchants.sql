-- merchants module: who the customer is and with which credentials it talks to the banks (spec model A).
-- Own schema; no other module reads these tables — modules talk through Java interfaces.

CREATE TABLE merchants (
    id         CHAR(26)     PRIMARY KEY,           -- ULID
    name       VARCHAR(200) NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

-- Hash only: whoever reads the database cannot call the API. prefix = first 12 chars, to find
-- the row before comparing the hash and so the merchant recognises the key in a dashboard.
CREATE TABLE api_keys (
    id          CHAR(26)    PRIMARY KEY,
    merchant_id CHAR(26)    NOT NULL REFERENCES merchants (id),
    environment VARCHAR(10) NOT NULL,                -- LIVE | TEST
    prefix      VARCHAR(12) NOT NULL,
    hash        CHAR(64)    NOT NULL UNIQUE,         -- hex SHA-256(pepper || key)
    active      BOOLEAN     NOT NULL DEFAULT true,
    expires_at  TIMESTAMPTZ,                          -- rotation: the old key is valid until here
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_api_keys_prefix ON api_keys (prefix);
CREATE INDEX idx_api_keys_merchant ON api_keys (merchant_id, environment, active);

-- AES-256-GCM envelope: payload encrypted with a per-row DEK, DEK encrypted with the master key.
-- AAD = merchant_id, so copying a row to another merchant does not decrypt.
CREATE TABLE provider_credentials (
    id            CHAR(26)    PRIMARY KEY,
    merchant_id   CHAR(26)    NOT NULL REFERENCES merchants (id),
    provider      VARCHAR(20) NOT NULL,              -- ITAU | FAKE
    environment   VARCHAR(10) NOT NULL,
    nonce         BYTEA       NOT NULL,
    ciphertext    BYTEA       NOT NULL,
    encrypted_dek BYTEA       NOT NULL,
    dek_nonce     BYTEA       NOT NULL,
    active        BOOLEAN     NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_provider_credentials UNIQUE (merchant_id, provider, environment)
);
