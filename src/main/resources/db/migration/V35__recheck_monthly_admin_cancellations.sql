-- #171 관리자 정기결제 취소 조회 시각. 기존 승인·기간·환급 원장은 바꾸지 않는다.
ALTER TABLE billing_invoices ADD COLUMN provider_checked_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE billing_invoices ADD COLUMN provider_signal_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_billing_invoice_provider_recheck ON billing_invoices(status,provider_checked_at,paid_at,id);
