-- ============================================================
-- SentinelPay :: KYC Service — Initial Schema
-- Migration: V1__initial_schema.sql
-- ============================================================

CREATE TABLE kyc_submissions (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL,
    user_email       VARCHAR(255) NOT NULL,
    full_name        VARCHAR(255) NOT NULL,
    country_code     VARCHAR(2)   NOT NULL,
    document_type    VARCHAR(30)  NOT NULL
                         CHECK (document_type IN (
                             'PASSPORT', 'NATIONAL_ID', 'DRIVING_LICENSE', 'RESIDENCE_PERMIT')),
    document_number  VARCHAR(100) NOT NULL,
    document_url     VARCHAR(512),
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'UNDER_REVIEW', 'APPROVED', 'REJECTED')),
    reviewed_by      UUID,
    rejection_reason VARCHAR(500),
    reviewed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_kyc_submissions PRIMARY KEY (id)
);

CREATE INDEX idx_kyc_user_id    ON kyc_submissions (user_id);
CREATE INDEX idx_kyc_status     ON kyc_submissions (status);
CREATE INDEX idx_kyc_created_at ON kyc_submissions (created_at DESC);

COMMENT ON TABLE  kyc_submissions              IS 'KYC document submissions and their review lifecycle';
COMMENT ON COLUMN kyc_submissions.user_id      IS 'User UUID from payment-service';
COMMENT ON COLUMN kyc_submissions.status       IS 'PENDING → UNDER_REVIEW → APPROVED | REJECTED';
COMMENT ON COLUMN kyc_submissions.reviewed_by  IS 'Admin UUID who performed the review';
