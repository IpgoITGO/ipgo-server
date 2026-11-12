-- 기존 수동 선택의 출처는 복원할 수 없다. bool/설정은 그대로 두고 LEGACY로 보존한다.
ALTER TABLE products ADD COLUMN activation_mode VARCHAR(20) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE products ADD COLUMN auto_inactive_reason VARCHAR(30);
ALTER TABLE products ADD CONSTRAINT ck_product_activation_mode
    CHECK (activation_mode IN ('LEGACY', 'AUTO', 'MANUAL_ON', 'MANUAL_OFF'));
ALTER TABLE products ADD CONSTRAINT ck_product_manual_activity
    CHECK ((activation_mode <> 'MANUAL_ON' OR is_active = TRUE)
       AND (activation_mode <> 'MANUAL_OFF' OR is_active = FALSE));
ALTER TABLE products ADD CONSTRAINT ck_product_auto_inactive_reason
    CHECK (auto_inactive_reason IS NULL OR
        (auto_inactive_reason = 'NO_RECENT_RECORDS' AND activation_mode = 'AUTO' AND is_active = FALSE));
