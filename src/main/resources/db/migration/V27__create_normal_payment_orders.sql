-- One-off TEST payments. Existing subscription/payment history is preserved.
CREATE TABLE billing_normal_orders (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    invoice_id VARCHAR(36) NOT NULL UNIQUE,
    command_key VARCHAR(100) NOT NULL,
    body_hash VARCHAR(64) NOT NULL,
    customer_key VARCHAR(50) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK(status IN ('OPEN','CONFIRMING','PENDING','PAID','FAILED','UNKNOWN','REVIEW_REQUIRED','CANCELED')),
    payment_key VARCHAR(200) UNIQUE,
    provider_key VARCHAR(100) NOT NULL UNIQUE,
    merchant_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    refund_status VARCHAR(24) CHECK(refund_status IN ('PROCESSING','UNKNOWN','CANCELED','REVIEW_REQUIRED')),
    refund_reason VARCHAR(200),
    refund_key VARCHAR(100) UNIQUE,
    refund_started_at TIMESTAMP WITH TIME ZONE,
    canceled_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(user_id,command_key),
    FOREIGN KEY(invoice_id,user_id) REFERENCES billing_invoices(id,user_id)
);
CREATE INDEX idx_normal_payment_work ON billing_normal_orders(status,lease_until);
-- PostgreSQL 전용
CREATE UNIQUE INDEX uk_normal_payment_pending_user ON billing_normal_orders(user_id)
    WHERE status IN ('OPEN','CONFIRMING','PENDING','UNKNOWN','REVIEW_REQUIRED') OR refund_status IN ('PROCESSING','UNKNOWN','REVIEW_REQUIRED');
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ipgo_server') THEN
        GRANT SELECT,INSERT,UPDATE ON billing_normal_orders TO ipgo_server;
        REVOKE DELETE ON billing_normal_orders FROM ipgo_server;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ipgo_ai_reader') THEN
        REVOKE ALL ON billing_normal_orders FROM ipgo_ai_reader;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ipgo_ai_service_reader') THEN
        REVOKE ALL ON billing_normal_orders FROM ipgo_ai_service_reader;
    END IF;
END $$;
