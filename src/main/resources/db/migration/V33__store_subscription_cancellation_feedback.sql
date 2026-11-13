-- #159 해지 명령에 선택 사유를 보관한다. 기존 명령은 NULL로 남기며 과거 사유를 만들지 않는다.
ALTER TABLE billing_commands ADD COLUMN cancellation_reason_codes VARCHAR(200);
ALTER TABLE billing_commands ADD COLUMN cancellation_note VARCHAR(500);
ALTER TABLE billing_commands ADD CONSTRAINT ck_billing_cancellation_feedback_operation CHECK (
    operation = 'CANCEL' OR (cancellation_reason_codes IS NULL AND cancellation_note IS NULL)
);
