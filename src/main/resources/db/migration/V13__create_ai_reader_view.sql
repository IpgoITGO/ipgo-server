-- 이 migration은 database/bootstrap/create_roles.sql을 먼저 실행한 PostgreSQL에서 적용한다.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipgo_migrator')
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipgo_server')
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipgo_ai_reader') THEN
        RAISE EXCEPTION 'ipgo database roles are missing; run database/bootstrap/create_roles.sql first';
    END IF;
END
$$;

CREATE VIEW ai_product_daily_stocks_v1
WITH (security_barrier = true)
AS
SELECT
    pds.product_id,
    uf.warehouse_id,
    pds.upload_file_id,
    pds.stock_date,
    pds.opening_qty,
    pds.inbound_qty,
    pds.outbound_qty,
    pds.adjustment_qty,
    pds.closing_qty,
    pds.is_valid_observation
FROM product_daily_stocks pds
JOIN upload_files uf ON uf.id = pds.upload_file_id
WHERE uf.status = 'DONE';

COMMENT ON VIEW ai_product_daily_stocks_v1 IS
    'FastAPI calculation input; source files are still selected by warehouse_id and upload_file_id';

-- public schema와 database의 기본 공개 권한을 제거하고 명시한 역할만 연결한다.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON SCHEMA public FROM ipgo_server, ipgo_ai_reader;
GRANT USAGE ON SCHEMA public TO ipgo_server, ipgo_ai_reader;

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM ipgo_server, ipgo_ai_reader;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM ipgo_server, ipgo_ai_reader;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    users,
    email_verifications,
    warehouses,
    products,
    inventory_units,
    upload_files,
    replenishment_settings,
    planned_receipts,
    auth_sessions,
    refresh_tokens,
    calculation_runs,
    calculation_results,
    daily_stock_records,
    product_daily_stocks,
    run_source_files,
    scenario_results,
    backtest_metrics,
    ai_explanations
TO ipgo_server;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ipgo_server;
GRANT SELECT ON ai_product_daily_stocks_v1 TO ipgo_ai_reader;

-- 이후 migration이 만드는 업무 표도 Spring에는 DML을 주되 AI에는 자동 권한을 주지 않는다.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ipgo_server;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ipgo_server;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE ALL ON TABLES FROM ipgo_ai_reader;

REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC, ipgo_server, ipgo_ai_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

DO $$
BEGIN
    EXECUTE format('REVOKE CONNECT, TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM ipgo_server, ipgo_ai_reader', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO ipgo_migrator, ipgo_server, ipgo_ai_reader', current_database());
END
$$;
