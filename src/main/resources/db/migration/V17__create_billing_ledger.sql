-- 테스트 카드 빌링 원장. 기존 계정/결제 사실의 backfill 및 청구는 하지 않는다.
CREATE TABLE billing_subscriptions (
    user_id BIGINT PRIMARY KEY REFERENCES users(id),
    merchant_id VARCHAR(64) NOT NULL,
    mode VARCHAR(8) NOT NULL CHECK(mode='TEST'),
    status VARCHAR(24) NOT NULL CHECK (status IN ('TRIALING','ACTIVE','CANCELED')),
    plan_id VARCHAR(24) NOT NULL CHECK (plan_id IN ('trial','seller','business')),
    price_version VARCHAR(40) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount >= 0),
    trial_end TIMESTAMP WITH TIME ZONE NOT NULL,
    period_start TIMESTAMP WITH TIME ZONE,
    period_end TIMESTAMP WITH TIME ZONE,
    anchor_at TIMESTAMP WITH TIME ZONE,
    cycle_no INTEGER NOT NULL DEFAULT 0,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    customer_key VARCHAR(50) NOT NULL UNIQUE,
    default_method_id VARCHAR(36),
    next_charge_at TIMESTAMP WITH TIME ZONE,
    CHECK ((period_start IS NULL AND period_end IS NULL) OR period_end > period_start)
);
CREATE INDEX idx_billing_subscriptions_due ON billing_subscriptions(status,next_charge_at);
CREATE TABLE billing_methods (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES billing_subscriptions(user_id),
    encrypted_key TEXT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL UNIQUE,
    masked_number VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('ACTIVE','RETIRED','DELETED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(id,user_id)
);
CREATE TABLE billing_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES billing_subscriptions(user_id),
    purpose VARCHAR(24) NOT NULL CHECK (purpose IN ('START_SUBSCRIPTION','REPLACE_METHOD')),
    plan_id VARCHAR(24),
    amount BIGINT NOT NULL CHECK (amount >= 0),
    price_version VARCHAR(40) NOT NULL,
    consent_version VARCHAR(40) NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('OPEN','ISSUING','COMPLETED','REJECTED','UNKNOWN')),
    issue_key VARCHAR(36) NOT NULL UNIQUE,
    auth_hash VARCHAR(64),
    method_id VARCHAR(36),
    FOREIGN KEY(method_id,user_id) REFERENCES billing_methods(id,user_id),
    UNIQUE(id,user_id)
);
CREATE TABLE billing_invoices (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES billing_subscriptions(user_id),
    session_id VARCHAR(36),
    cycle_no INTEGER NOT NULL,
    plan_id VARCHAR(24) NOT NULL,
    price_version VARCHAR(40) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    order_name VARCHAR(100) NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency = 'KRW'),
    period_start TIMESTAMP WITH TIME ZONE NOT NULL,
    period_end TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('OPEN','PAID','VOID')),
    paid_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(user_id,cycle_no),
    UNIQUE(id,user_id),
    FOREIGN KEY(session_id,user_id) REFERENCES billing_sessions(id,user_id),
    CHECK(period_end > period_start)
);
CREATE TABLE billing_attempts (
    id VARCHAR(36) PRIMARY KEY,
    invoice_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    method_id VARCHAR(36) NOT NULL,
    attempt_no INTEGER NOT NULL,
    order_id VARCHAR(64) NOT NULL UNIQUE,
    idempotency_key VARCHAR(36) NOT NULL UNIQUE,
    body_hash VARCHAR(64) NOT NULL,
    payment_key VARCHAR(200) UNIQUE,
    status VARCHAR(24) NOT NULL CHECK (status IN ('READY','DISPATCHING','SUCCEEDED','DECLINED','UNKNOWN','REVIEW_REQUIRED','VOID')),
    error_code VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    last_checked_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(invoice_id,attempt_no),
    FOREIGN KEY(invoice_id,user_id) REFERENCES billing_invoices(id,user_id),
    FOREIGN KEY(method_id,user_id) REFERENCES billing_methods(id,user_id)
);
CREATE INDEX idx_billing_attempts_work ON billing_attempts(status,created_at);
CREATE TABLE billing_commands (
    user_id BIGINT NOT NULL REFERENCES billing_subscriptions(user_id),
    operation VARCHAR(100) NOT NULL,
    command_key VARCHAR(100) NOT NULL,
    body_hash VARCHAR(64) NOT NULL,
    consent_version VARCHAR(40),
    result_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(user_id,operation,command_key)
);
CREATE TABLE billing_webhook_inbox (
    event_key VARCHAR(64) PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    entity_fingerprint VARCHAR(64) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL
);
-- PostgreSQL 전용 제약/역할은 아래 구간이다. H2 단위 검증에서는 이 구간만 제외한다.
CREATE UNIQUE INDEX uk_billing_live_attempt ON billing_attempts(invoice_id)
    WHERE status IN ('READY','DISPATCHING','UNKNOWN','REVIEW_REQUIRED');
CREATE UNIQUE INDEX uk_billing_paid_attempt ON billing_attempts(invoice_id) WHERE status='SUCCEEDED';
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ipgo_server') THEN
        GRANT SELECT, INSERT, UPDATE ON billing_subscriptions, billing_methods, billing_sessions,
            billing_invoices, billing_attempts, billing_commands, billing_webhook_inbox TO ipgo_server;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ipgo_server') THEN
        REVOKE DELETE ON billing_subscriptions, billing_methods, billing_sessions,
            billing_invoices, billing_attempts, billing_commands, billing_webhook_inbox FROM ipgo_server;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ipgo_ai_reader') THEN
        REVOKE ALL ON billing_subscriptions, billing_methods, billing_sessions,
            billing_invoices, billing_attempts, billing_commands, billing_webhook_inbox FROM ipgo_ai_reader;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ipgo_ai_service_reader') THEN
        REVOKE ALL ON billing_subscriptions, billing_methods, billing_sessions,
            billing_invoices, billing_attempts, billing_commands, billing_webhook_inbox FROM ipgo_ai_service_reader;
    END IF;
END $$;
