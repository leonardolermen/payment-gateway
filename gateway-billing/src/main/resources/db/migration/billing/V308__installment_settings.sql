-- How a merchant prices card installments, per environment (spec 2026-10-07 §2): the TEST rate never
-- applies in production. No row is the behavior before this table existed: 12 installments, all of
-- them interest-free. The rate is in basis points per month (2,99% = 299).
CREATE TABLE billing.installment_settings (
    merchant_id         CHAR(26)     NOT NULL,
    environment         VARCHAR(10)  NOT NULL,
    max_installments    SMALLINT     NOT NULL CHECK (max_installments BETWEEN 1 AND 12),
    interest_free_up_to SMALLINT     NOT NULL,
    monthly_rate_bps    INTEGER      NOT NULL CHECK (monthly_rate_bps BETWEEN 0 AND 1000),
    updated_at          TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (merchant_id, environment),
    CHECK (interest_free_up_to BETWEEN 1 AND max_installments)
);
