-- #208 이용권 주문마다 회원이 동의한 결제 동의 버전과 시각을 남긴다(환불 제한 고지 v2 도입).
-- 기존 주문은 비어 있다(NULL). 되돌릴 때는 새 칸 2개만 지우면 된다.
ALTER TABLE billing_normal_orders ADD COLUMN consent_version VARCHAR(40);
ALTER TABLE billing_normal_orders ADD COLUMN consented_at TIMESTAMP WITH TIME ZONE;
