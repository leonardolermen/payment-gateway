-- The fixed header a merchant configures at the acquirer for its notifications (the Cielo's "Post de
-- Notificação" has no signature; docs/webhook: up to three static headers). Only the SHA-256 of the
-- value is kept: the check compares hashes in constant time, and a leaked row does not hand out the
-- header. One per merchant and provider.
CREATE TABLE merchants.inbound_notification_keys (
    id          CHAR(26)    PRIMARY KEY,
    merchant_id CHAR(26)    NOT NULL,
    provider    VARCHAR(20) NOT NULL,
    key_hash    CHAR(64)    NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inbound_notification_keys UNIQUE (merchant_id, provider)
);
