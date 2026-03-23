-- ============================================================
-- SentinelPay :: Payment Service — Initial Database Schema
-- Migration: V1__initial_schema.sql
-- ============================================================

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "btree_gin";  -- GIN indexes on jsonb

-- ============================================================
-- Table: users
-- ============================================================
CREATE TABLE users (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    email        VARCHAR(255) NOT NULL,
    full_name    VARCHAR(255) NOT NULL,
    phone_number VARCHAR(20),
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    kyc_verified BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE INDEX idx_users_email     ON users (email);
CREATE INDEX idx_users_phone     ON users (phone_number);
CREATE INDEX idx_users_status    ON users (status);

COMMENT ON TABLE  users             IS 'Registered SentinelPay users';
COMMENT ON COLUMN users.status      IS 'ACTIVE | SUSPENDED | CLOSED';
COMMENT ON COLUMN users.kyc_verified IS 'KYC verification flag — required for large transfers';

-- ============================================================
-- Table: wallets
-- ============================================================
CREATE TABLE wallets (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL,
    currency   CHAR(3)      NOT NULL,                        -- ISO 4217
    balance    NUMERIC(19, 4) NOT NULL DEFAULT 0.0000
                   CHECK (balance >= 0),
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    version    BIGINT       NOT NULL DEFAULT 0,              -- optimistic lock
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_wallets        PRIMARY KEY (id),
    CONSTRAINT fk_wallets_user   FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uq_wallet_user_currency UNIQUE (user_id, currency)
);

CREATE INDEX idx_wallets_user_id        ON wallets (user_id);
CREATE INDEX idx_wallets_user_currency  ON wallets (user_id, currency);
CREATE INDEX idx_wallets_status         ON wallets (status);

COMMENT ON TABLE  wallets          IS 'User wallets — one per currency per user';
COMMENT ON COLUMN wallets.version  IS 'Hibernate @Version field for optimistic locking (double-spend prevention)';
COMMENT ON COLUMN wallets.balance  IS 'Available balance; non-negative enforced by CHECK constraint';
COMMENT ON COLUMN wallets.currency IS 'ISO 4217 currency code (e.g. USD, EUR)';

-- ============================================================
-- Table: transactions
-- ============================================================
CREATE TABLE transactions (
    id                       UUID         NOT NULL DEFAULT gen_random_uuid(),
    idempotency_key          VARCHAR(128) NOT NULL,           -- client-supplied dedup key
    sender_wallet_id         UUID         NOT NULL,
    receiver_wallet_id       UUID         NOT NULL,
    amount                   NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency                 CHAR(3)      NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN (
                                     'PENDING', 'PROCESSING', 'COMPLETED',
                                     'FAILED', 'REVERSED', 'SAGA_COMPENSATING'
                                 )),
    type                     VARCHAR(30)  NOT NULL
                                 CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'REVERSAL')),
    description              VARCHAR(500),
    reference_transaction_id UUID,                            -- populated for reversals
    metadata                 JSONB,                           -- fraud scores, saga state, etc.
    failure_reason           VARCHAR(500),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at             TIMESTAMPTZ,

    CONSTRAINT pk_transactions               PRIMARY KEY (id),
    CONSTRAINT uq_transactions_idempotency   UNIQUE (idempotency_key),
    CONSTRAINT fk_txn_sender_wallet          FOREIGN KEY (sender_wallet_id)
                                                 REFERENCES wallets (id) ON DELETE RESTRICT,
    CONSTRAINT fk_txn_receiver_wallet        FOREIGN KEY (receiver_wallet_id)
                                                 REFERENCES wallets (id) ON DELETE RESTRICT,
    CONSTRAINT fk_txn_reference              FOREIGN KEY (reference_transaction_id)
                                                 REFERENCES transactions (id) ON DELETE RESTRICT,
    CONSTRAINT chk_txn_different_wallets     CHECK (sender_wallet_id <> receiver_wallet_id)
);

CREATE UNIQUE INDEX idx_txn_idempotency_key   ON transactions (idempotency_key);
CREATE INDEX idx_txn_sender_wallet            ON transactions (sender_wallet_id);
CREATE INDEX idx_txn_receiver_wallet          ON transactions (receiver_wallet_id);
CREATE INDEX idx_txn_status                   ON transactions (status);
CREATE INDEX idx_txn_created_at               ON transactions (created_at DESC);
CREATE INDEX idx_txn_metadata_gin             ON transactions USING GIN (metadata);

COMMENT ON TABLE  transactions                    IS 'Immutable payment transaction ledger';
COMMENT ON COLUMN transactions.idempotency_key    IS 'Client-supplied key — guarantees exactly-once processing';
COMMENT ON COLUMN transactions.metadata           IS 'JSONB blob: fraud scores, saga step, extra context';
COMMENT ON COLUMN transactions.reference_transaction_id IS 'For REVERSAL type: points to the original transaction';

-- ============================================================
-- Table: outbox_events
-- Implements the Outbox Pattern for guaranteed Kafka delivery
-- ============================================================
CREATE TABLE outbox_events (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,  -- e.g. "Transaction"
    aggregate_id   VARCHAR(128) NOT NULL,  -- e.g. the transaction UUID
    topic          VARCHAR(255) NOT NULL,  -- target Kafka topic
    event_type     VARCHAR(100) NOT NULL,  -- e.g. "TRANSACTION_COMPLETED"
    payload        TEXT         NOT NULL,  -- JSON-serialized event body
    processed      BOOLEAN      NOT NULL DEFAULT FALSE,
    processed_at   TIMESTAMPTZ,
    retry_count    INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_outbox_events PRIMARY KEY (id)
);

CREATE INDEX idx_outbox_processed_created ON outbox_events (processed, created_at ASC)
    WHERE processed = FALSE;              -- partial index — only unprocessed rows
CREATE INDEX idx_outbox_aggregate_id ON outbox_events (aggregate_id);

COMMENT ON TABLE  outbox_events           IS 'Transactional outbox — guarantees at-least-once Kafka delivery';
COMMENT ON COLUMN outbox_events.processed IS 'Set to TRUE after the event is successfully published to Kafka';
COMMENT ON COLUMN outbox_events.retry_count IS 'Number of failed publish attempts';
