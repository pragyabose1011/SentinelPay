-- Phase 4: Razorpay gateway reference on transactions
-- Stores the external gateway order ID (e.g. Razorpay order_xxx) so that
-- incoming payment webhooks can look up the pending transaction.

ALTER TABLE transactions
    ADD COLUMN gateway_reference VARCHAR(128);

-- Partial index — only rows that actually have a gateway reference need fast lookup
CREATE INDEX idx_txn_gateway_reference ON transactions (gateway_reference)
    WHERE gateway_reference IS NOT NULL;
