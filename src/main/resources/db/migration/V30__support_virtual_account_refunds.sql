-- Refund account data is encrypted with the existing billing AES-GCM key.
-- Only the current in-flight request retains it. Final/review states erase it.
ALTER TABLE billing_normal_orders ADD COLUMN payment_method VARCHAR(30);
ALTER TABLE billing_normal_orders ADD COLUMN refund_account_ciphertext TEXT;
ALTER TABLE billing_normal_orders ADD COLUMN refund_account_fingerprint VARCHAR(64);
ALTER TABLE billing_normal_orders DROP CONSTRAINT IF EXISTS billing_normal_orders_refund_status_check;
ALTER TABLE billing_normal_orders ADD CONSTRAINT billing_normal_orders_refund_status_check
    CHECK (refund_status IN ('PROCESSING','UNKNOWN','ACCOUNT_REQUIRED','CANCELED','REVIEW_REQUIRED'));
-- PostgreSQL 전용
DROP INDEX IF EXISTS uk_normal_payment_pending_user;
CREATE UNIQUE INDEX uk_normal_payment_pending_user ON billing_normal_orders(user_id)
    WHERE status IN ('OPEN','CONFIRMING','PENDING','UNKNOWN','REVIEW_REQUIRED')
       OR refund_status IN ('PROCESSING','UNKNOWN','ACCOUNT_REQUIRED','REVIEW_REQUIRED');
