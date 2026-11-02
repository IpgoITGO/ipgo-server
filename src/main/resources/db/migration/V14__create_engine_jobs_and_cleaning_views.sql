-- Additive only: existing uploads/results are not requeued or rewritten.
CREATE TABLE engine_jobs (
    job_id VARCHAR(100) PRIMARY KEY,
    job_type VARCHAR(20) NOT NULL CHECK (job_type IN ('CLEAN', 'CALCULATE')),
    target_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'DONE', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_engine_jobs_due ON engine_jobs (status, next_attempt_at);
ALTER TABLE calculation_results ALTER COLUMN method_reason TYPE VARCHAR(255);
ALTER TABLE calculation_runs ADD COLUMN input_hash VARCHAR(64);
CREATE INDEX idx_calculation_runs_input ON calculation_runs (warehouse_id, input_hash, id);

CREATE VIEW ai_cleaning_sources_v1 WITH (security_barrier = true) AS
SELECT uf.id AS upload_file_id, w.user_id, uf.warehouse_id, uf.storage_key, uf.original_name,
       uf.period_start, uf.period_end, uf.revision_no, uf.status
FROM upload_files uf JOIN warehouses w ON w.id = uf.warehouse_id;
CREATE VIEW ai_cleaning_products_v1 WITH (security_barrier = true) AS
SELECT user_id, sku_barcode FROM products;
CREATE VIEW ai_cleaning_previous_units_v1 WITH (security_barrier = true) AS
SELECT ds.upload_file_id, w.user_id, uf.warehouse_id, iu.source_inventory_key, iu.received_on,
       ds.stock_date, ds.closing_qty
FROM daily_stock_records ds
JOIN inventory_units iu ON iu.id = ds.inventory_unit_id
JOIN upload_files uf ON uf.id = ds.upload_file_id
JOIN warehouses w ON w.id = uf.warehouse_id
WHERE uf.status = 'DONE';
GRANT SELECT ON ai_cleaning_sources_v1, ai_cleaning_products_v1, ai_cleaning_previous_units_v1 TO ipgo_ai_reader;
GRANT SELECT, INSERT, UPDATE, DELETE ON engine_jobs TO ipgo_server;
