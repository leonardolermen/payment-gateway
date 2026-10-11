-- The panel's own login (spec 2026-10-08-usuarios-e-sessao). One e-mail is one user in one store:
-- email_normalized is unique across the system, not per merchant. Every secret here is a hash:
-- Argon2id for the password, peppered SHA-256 for session and one-time tokens.
CREATE TABLE merchants.users (
    id                CHAR(26)     PRIMARY KEY,
    merchant_id       CHAR(26)     NOT NULL REFERENCES merchants.merchants (id),
    name              VARCHAR(120) NOT NULL,
    email             VARCHAR(254) NOT NULL,
    email_normalized  VARCHAR(254) NOT NULL,
    password_hash     VARCHAR(200) NOT NULL,
    role              VARCHAR(10)  NOT NULL CHECK (role IN ('OWNER', 'FINANCE', 'READONLY')),
    email_verified_at TIMESTAMPTZ,
    last_login_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    deleted_at        TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_users_email ON merchants.users (email_normalized) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_merchant ON merchants.users (merchant_id) WHERE deleted_at IS NULL;

-- previous_refresh_hash: a refresh presented after it was rotated is the sign of a stolen cookie;
-- keeping the one just replaced is what lets the service recognise it and revoke the session.
CREATE TABLE merchants.sessions (
    id                    CHAR(26)     PRIMARY KEY,
    user_id               CHAR(26)     NOT NULL REFERENCES merchants.users (id),
    access_hash           CHAR(64)     NOT NULL,
    refresh_hash          CHAR(64)     NOT NULL,
    previous_refresh_hash CHAR(64),
    access_expires_at     TIMESTAMPTZ  NOT NULL,
    refresh_expires_at    TIMESTAMPTZ  NOT NULL,
    ip                    VARCHAR(45),
    user_agent            VARCHAR(200),
    created_at            TIMESTAMPTZ  NOT NULL,
    last_used_at          TIMESTAMPTZ  NOT NULL,
    revoked_at            TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_sessions_access ON merchants.sessions (access_hash);
CREATE UNIQUE INDEX uq_sessions_refresh ON merchants.sessions (refresh_hash);
CREATE INDEX idx_sessions_previous_refresh ON merchants.sessions (previous_refresh_hash)
    WHERE previous_refresh_hash IS NOT NULL;
CREATE INDEX idx_sessions_user_live ON merchants.sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE merchants.user_tokens (
    id          CHAR(26)    PRIMARY KEY,
    user_id     CHAR(26)    REFERENCES merchants.users (id),
    merchant_id CHAR(26)    NOT NULL REFERENCES merchants.merchants (id),
    kind        VARCHAR(16) NOT NULL CHECK (kind IN ('VERIFY_EMAIL', 'RESET_PASSWORD', 'INVITE')),
    token_hash  CHAR(64)    NOT NULL,
    payload     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_user_tokens_hash ON merchants.user_tokens (token_hash);

-- The rendered message waits here for SEND_EMAIL and is deleted once sent: the link inside carries
-- a one-time token, so the row must not outlive the send.
CREATE TABLE merchants.outbound_emails (
    id         CHAR(26)     PRIMARY KEY,
    recipient  VARCHAR(254) NOT NULL,
    subject    VARCHAR(200) NOT NULL,
    text_body  TEXT         NOT NULL,
    html_body  TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);
