-- Creates all SentinelPay databases on the shared PostgreSQL instance.
-- Runs automatically when the postgres container first starts.

CREATE DATABASE sentinelpay_notifications;
CREATE DATABASE sentinelpay_kyc;

-- Grant the shared sentinelpay user access to all databases
GRANT ALL PRIVILEGES ON DATABASE sentinelpay                TO sentinelpay;
GRANT ALL PRIVILEGES ON DATABASE sentinelpay_notifications  TO sentinelpay;
GRANT ALL PRIVILEGES ON DATABASE sentinelpay_kyc            TO sentinelpay;
