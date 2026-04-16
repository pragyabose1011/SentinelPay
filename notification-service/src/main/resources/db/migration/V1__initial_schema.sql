-- ============================================================
-- SentinelPay :: Notification Service — Initial Schema
-- Migration: V1__initial_schema.sql
-- ============================================================

CREATE TABLE notifications (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id        UUID,
    recipient      VARCHAR(255) NOT NULL,
    channel        VARCHAR(20)  NOT NULL
                       CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    subject        VARCHAR(255),
    body           TEXT         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    reference_id   VARCHAR(128),
    failure_reason VARCHAR(500),
    sent_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notifications PRIMARY KEY (id)
);

CREATE INDEX idx_notif_user_id    ON notifications (user_id);
CREATE INDEX idx_notif_channel    ON notifications (channel);
CREATE INDEX idx_notif_status     ON notifications (status);
CREATE INDEX idx_notif_created_at ON notifications (created_at DESC);

COMMENT ON TABLE  notifications           IS 'Audit trail of all dispatched notifications';
COMMENT ON COLUMN notifications.user_id   IS 'Payment-service user UUID (nullable for system events)';
COMMENT ON COLUMN notifications.channel   IS 'EMAIL | SMS | PUSH';
COMMENT ON COLUMN notifications.event_type IS 'Source event (e.g. TRANSACTION_COMPLETED, KYC_APPROVED)';
