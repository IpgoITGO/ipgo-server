-- #119 내 입고량. 셀러가 상품 상세에서 정한 입고 수량과 그 기준이 된 계산을 상품당 1개 남긴다.
-- 새 계산이 끝나도 지우지 않는다(11/5 결정). 화면이 calculation_run_id로 "지난 계산 기준"을 표시한다.
CREATE TABLE product_planned_quantities (
    product_id BIGINT PRIMARY KEY REFERENCES products (id) ON DELETE CASCADE,
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    source VARCHAR(20) NOT NULL
        CHECK (source IN ('SCENARIO_80', 'SCENARIO_100', 'SCENARIO_120', 'MANUAL')),
    calculation_run_id BIGINT NOT NULL REFERENCES calculation_runs (id) ON DELETE CASCADE,
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_product_planned_quantities_run ON product_planned_quantities (calculation_run_id);
