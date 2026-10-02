-- Customers are the merchant's (spec §4.1). The document is sealed with the merchants' envelope
-- (AAD merchant|customer) and searched by its SHA-256, the same hash payments keeps on cards, so a
-- card saved before the customer existed can be adopted. Address is optional: only a Bolecode
-- subscription needs it, and the service refuses that subscription instead of the customer.
CREATE TABLE billing.customers (
    id                  CHAR(26)     PRIMARY KEY,
    merchant_id         CHAR(26)     NOT NULL,
    environment         VARCHAR(10)  NOT NULL,
    name                VARCHAR(120) NOT NULL,
    document_ciphertext BYTEA        NOT NULL,
    document_hash       CHAR(64)     NOT NULL,
    document_kind       VARCHAR(4)   NOT NULL,
    email               VARCHAR(254),
    address             JSONB,
    version             BIGINT       NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    deleted_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_customers_document
    ON billing.customers (merchant_id, environment, document_hash) WHERE deleted_at IS NULL;
