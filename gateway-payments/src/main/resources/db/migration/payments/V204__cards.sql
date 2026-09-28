-- Cards a merchant saved (spec 2026-09-28 §4). The acquirer's token is sealed with the merchants'
-- envelope (Sealer port, AAD = merchant|provider|environment|card), so a row copied to another
-- merchant does not open. Never a number, a CVV or a full expiry date string: brand, last four and
-- month/year are what the merchant's checkout shows.
CREATE TABLE payments.cards (
    id                     CHAR(26)     PRIMARY KEY,
    merchant_id            CHAR(26)     NOT NULL,
    provider               VARCHAR(20)  NOT NULL,
    environment            VARCHAR(10)  NOT NULL,
    token_ciphertext       BYTEA        NOT NULL,
    brand                  VARCHAR(10)  NOT NULL,
    last4                  CHAR(4)      NOT NULL,
    expiry_month           SMALLINT     NOT NULL,
    expiry_year            SMALLINT     NOT NULL,
    holder                 VARCHAR(25)  NOT NULL,
    customer_document_hash CHAR(64),
    created_at             TIMESTAMPTZ  NOT NULL,
    deleted_at             TIMESTAMPTZ
);
CREATE INDEX idx_cards_merchant ON payments.cards (merchant_id) WHERE deleted_at IS NULL;

-- The Cielo's notification names only its PaymentId; the inbox and the reconciliation find the
-- payment by it, scoped to the merchant (PaymentRepository.findByMerchantAndCardPaymentId).
CREATE INDEX idx_payments_card_payment_id
    ON payments.payments (merchant_id, provider, (details->'card'->>'paymentId'))
 WHERE details ? 'card';
