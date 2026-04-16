-- ============================================================
-- SentinelPay :: Payment Service — V3 Migration
-- Adds: refresh_tokens, password_reset_tokens
-- ============================================================

-- ============================================================
-- Table: refresh_tokens
-- Stores SHA-256 hashes of opaque refresh tokens.
-- Raw tokens are never persisted — only their hash.
-- ============================================================
CREATE TABLE refresh_tokens (
    id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    token_hash VARCHAR(64) NOT NULL,  -- hex-encoded SHA-256 of the raw token
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);

COMMENT ON TABLE  refresh_tokens            IS 'Opaque refresh token hashes for access token rotation';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'Hex-encoded SHA-256 of the raw token sent to the client';

-- ============================================================
-- Table: password_reset_tokens
-- Single-use, time-limited tokens for password reset flow.
-- ============================================================
CREATE TABLE password_reset_tokens (
    id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    token_hash VARCHAR(64) NOT NULL,  -- hex-encoded SHA-256 of the raw token
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_password_reset_tokens PRIMARY KEY (id),
    CONSTRAINT uq_password_reset_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_password_reset_user    ON password_reset_tokens (user_id);
CREATE INDEX idx_password_reset_expires ON password_reset_tokens (expires_at);

COMMENT ON TABLE  password_reset_tokens            IS 'Single-use password reset tokens (1 hour TTL)';
COMMENT ON COLUMN password_reset_tokens.token_hash IS 'Hex-encoded SHA-256 of the raw token sent to the user';
COMMENT ON COLUMN password_reset_tokens.used       IS 'True once the token has been consumed — cannot be reused';
