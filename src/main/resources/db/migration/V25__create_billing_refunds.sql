-- Full refunds only. Keep approval and cancellation outcomes in separate ledgers.
CREATE TABLE billing_refunds (
    id VARCHAR(36) PRIMARY KEY,
    invoice_id VARCHAR(36) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    reason VARCHAR(200) NOT NULL,
    provider_key VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PROCESSING','UNKNOWN','CANCELED','REVIEW_REQUIRED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    canceled_at TIMESTAMP WITH TIME ZONE,
    FOREIGN KEY(invoice_id,user_id) REFERENCES billing_invoices(id,user_id),
    CHECK ((status='CANCELED') = (canceled_at IS NOT NULL))
);
CREATE INDEX idx_billing_refunds_work ON billing_refunds(status,lease_until);
-- PostgreSQL 전용
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ipgo_server') THEN
        GRANT SELECT, INSERT, UPDATE ON billing_refunds TO ipgo_server;
        REVOKE DELETE ON billing_refunds FROM ipgo_server;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ipgo_ai_reader') THEN
        REVOKE ALL ON billing_refunds FROM ipgo_ai_reader;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ipgo_ai_service_reader') THEN
        REVOKE ALL ON billing_refunds FROM ipgo_ai_service_reader;
    END IF;
END $$;
