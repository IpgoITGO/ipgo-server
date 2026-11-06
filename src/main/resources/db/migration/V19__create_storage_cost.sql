-- #111 보관 비용 설정. 계산 입력이 아니므로 저장해도 재계산하지 않는다.
-- 창고 보관 요율: 원 / CBM · 월. 비어 있으면 아직 입력하지 않은 것이다.
ALTER TABLE warehouses ADD COLUMN storage_rate_per_cbm NUMERIC(12, 2);
ALTER TABLE warehouses ADD CONSTRAINT ck_warehouses_storage_rate_positive
    CHECK (storage_rate_per_cbm IS NULL OR storage_rate_per_cbm > 0);

-- 상품 상자 1개 크기(cm). 행이 없으면 상자 크기를 입력하지 않은 것이다.
CREATE TABLE product_boxes (
    product_id BIGINT PRIMARY KEY REFERENCES products (id) ON DELETE CASCADE,
    width_cm NUMERIC(7, 1) NOT NULL CHECK (width_cm > 0),
    depth_cm NUMERIC(7, 1) NOT NULL CHECK (depth_cm > 0),
    height_cm NUMERIC(7, 1) NOT NULL CHECK (height_cm > 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
