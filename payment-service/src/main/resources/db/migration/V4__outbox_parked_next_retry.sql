-- Phase 2: Outbox hardening
-- Add parked flag for poison-event parking and next_retry_at for exponential backoff scheduling.
-- Parked events are skipped by the outbox publisher until manually reviewed/resolved.

ALTER TABLE outbox_events
    ADD COLUMN parked        BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN next_retry_at TIMESTAMPTZ          DEFAULT NULL;

-- Index to efficiently find publishable events (processed=false, parked=false, due now)
CREATE INDEX idx_outbox_publishable
    ON outbox_events (processed, parked, next_retry_at)
    WHERE processed = false AND parked = false;

COMMENT ON COLUMN outbox_events.parked IS
    'True when the event has exceeded MAX_RETRIES and is parked for manual inspection.';
COMMENT ON COLUMN outbox_events.next_retry_at IS
    'Earliest time this event should be retried. NULL means immediately eligible.';
