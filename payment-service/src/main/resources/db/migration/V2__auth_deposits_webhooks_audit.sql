-- ============================================================
-- SentinelPay :: Payment Service — V2 Migration
-- Adds: auth (password, role), deposit/withdrawal schema,
--       webhook registrations, audit log
-- ============================================================

-- ============================================================
-- Auth: add password_hash and role to users
-- ============================================================
ALTER TABLE users
    ADD COLUMN password_hash VARCHAR(255),
    ADD COLUMN role          VARCHAR(20) NOT NULL DEFAULT 'USER'
        CHECK (role IN ('USER', 'ADMIN'));

-- ============================================================
-- Deposit / Withdrawal: relax NOT NULL on wallet FKs
-- Deposits have no sender_wallet (money comes from outside).
-- Withdrawals have no receiver_wallet (money goes outside).
-- ============================================================
ALTER TABLE transactions
    ALTER COLUMN sender_wallet_id   DROP NOT NULL,
    ALTER COLUMN receiver_wallet_id DROP NOT NULL;

-- Replace the old same-wallet check with a type-aware constraint
ALTER TABLE transactions DROP CONSTRAINT chk_txn_different_wallets;
ALTER TABLE transactions ADD CONSTRAINT chk_txn_wallets CHECK (
    (type IN ('TRANSFER', 'REVERSAL')
        AND sender_wallet_id   IS NOT NULL
        AND receiver_wallet_id IS NOT NULL
        AND sender_wallet_id  <> receiver_wallet_id)
    OR
    (type = 'DEPOSIT'
        AND sender_wallet_id   IS NULL
        AND receiver_wallet_id IS NOT NULL)
    OR
    (type = 'WITHDRAWAL'
        AND sender_wallet_id   IS NOT NULL
        AND receiver_wallet_id IS NULL)
);

-- ============================================================
-- Table: webhook_registrations
-- Clients register URLs to receive real-time event callbacks.
-- ============================================================
CREATE TABLE webhook_registrations (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL,
    url        VARCHAR(500) NOT NULL,
    events     TEXT         NOT NULL,   -- CSV of event types, e.g. 'TRANSACTION_COMPLETED,FRAUD_ALERT'
    secret     VARCHAR(255),            -- HMAC-SHA256 signing secret for payload verification
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_webhook_registrations PRIMARY KEY (id),
    CONSTRAINT fk_webhook_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_webhook_user   ON webhook_registrations (user_id);
CREATE INDEX idx_webhook_active ON webhook_registrations (active) WHERE active = TRUE;

COMMENT ON TABLE  webhook_registrations        IS 'Client-registered webhook endpoints for event callbacks';
COMMENT ON COLUMN webhook_registrations.events IS 'CSV of subscribed event types';
COMMENT ON COLUMN webhook_registrations.secret IS 'Used to sign webhook payloads via X-SentinelPay-Signature header';

-- ============================================================
-- Table: audit_log
-- Immutable append-only record of every state change.
-- ============================================================
CREATE TABLE audit_log (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    entity_type VARCHAR(100) NOT NULL,   -- e.g. 'User', 'Wallet', 'Transaction'
    entity_id   VARCHAR(128) NOT NULL,   -- UUID of the changed entity
    action      VARCHAR(50)  NOT NULL,   -- e.g. 'CREATE', 'STATUS_CHANGE', 'KYC_VERIFY'
    actor_id    UUID,                    -- userId who triggered the change (null = system)
    old_value   TEXT,                    -- JSON snapshot before change
    new_value   TEXT,                    -- JSON snapshot after change
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);

CREATE INDEX idx_audit_entity     ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_actor      ON audit_log (actor_id);
CREATE INDEX idx_audit_created_at ON audit_log (created_at DESC);

COMMENT ON TABLE  audit_log           IS 'Immutable audit trail — append-only, never updated or deleted';
COMMENT ON COLUMN audit_log.actor_id  IS 'NULL means a system-initiated change (scheduler, outbox publisher, etc.)';
COMMENT ON COLUMN audit_log.old_value IS 'JSON snapshot of entity state before the change';
COMMENT ON COLUMN audit_log.new_value IS 'JSON snapshot of entity state after the change';
