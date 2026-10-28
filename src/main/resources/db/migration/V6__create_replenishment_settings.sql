-- 상품별 입고 조건(BE-06). 상품마다 1행이고, 비어 있는 칸은 창고 기본값을 쓴다. 이름과 칸은 최종 ERD를 따른다.
CREATE TABLE replenishment_settings (
    product_id BIGINT PRIMARY KEY REFERENCES products (id),
    lead_time_days INTEGER CHECK (lead_time_days > 0),
    review_cycle_days INTEGER CHECK (review_cycle_days > 0),
    order_unit_qty BIGINT CHECK (order_unit_qty > 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
