-- #208 이용권 주문마다 회원이 동의한 결제 동의 버전과 시각을 남긴다(환불 제한 고지 v2 도입).
-- 기존 주문은 비어 있거나(NULL) FALSE다. 되돌릴 때는 새 칸 4개만 지우면 된다.
ALTER TABLE billing_normal_orders ADD COLUMN consent_version VARCHAR(40);
ALTER TABLE billing_normal_orders ADD COLUMN consented_at TIMESTAMP WITH TIME ZONE;

-- 환불 제한이 켜진 뒤 받은 환불 요청인지. TRUE면 입금 전 가상계좌 발급 취소만 할 수 있고,
-- 토스 조회에서 이미 입금된 것으로 나오면 환불하지 않고 요청을 되돌린다.
ALTER TABLE billing_normal_orders ADD COLUMN refund_unpaid_only BOOLEAN NOT NULL DEFAULT FALSE;

-- 결제 완료 주문을 토스에 마지막으로 다시 조회한 시각. 토스 관리자 화면에서 한 환불을 반영하는
-- 하루 한 번 확인과 결제 상태 웹훅 신호에 쓴다. 비어 있으면 아직 확인하지 않은 주문이다.
ALTER TABLE billing_normal_orders ADD COLUMN provider_checked_at TIMESTAMP WITH TIME ZONE;
