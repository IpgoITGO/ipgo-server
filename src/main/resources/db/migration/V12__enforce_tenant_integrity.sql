-- PostgreSQL 전용 무결성 검사. 서로 다른 회원의 상품·창고·업로드가
-- 한 행에서 연결되지 않도록 기존 데이터 확인과 쓰기 시점 검사를 함께 둔다.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM inventory_units iu
        JOIN products p ON p.id = iu.product_id
        JOIN warehouses w ON w.id = iu.warehouse_id
        WHERE p.user_id <> w.user_id
    ) THEN
        RAISE EXCEPTION 'inventory_units contains cross-owner rows';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM planned_receipts pr
        JOIN products p ON p.id = pr.product_id
        JOIN warehouses w ON w.id = pr.warehouse_id
        WHERE p.user_id <> w.user_id
    ) THEN
        RAISE EXCEPTION 'planned_receipts contains cross-owner rows';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM daily_stock_records dsr
        JOIN upload_files uf ON uf.id = dsr.upload_file_id
        JOIN inventory_units iu ON iu.id = dsr.inventory_unit_id
        WHERE uf.warehouse_id <> iu.warehouse_id
    ) THEN
        RAISE EXCEPTION 'daily_stock_records contains cross-warehouse rows';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM product_daily_stocks pds
        JOIN products p ON p.id = pds.product_id
        JOIN upload_files uf ON uf.id = pds.upload_file_id
        JOIN warehouses w ON w.id = uf.warehouse_id
        WHERE p.user_id <> w.user_id
    ) THEN
        RAISE EXCEPTION 'product_daily_stocks contains cross-owner rows';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM run_source_files rsf
        JOIN calculation_runs cr ON cr.id = rsf.run_id
        JOIN upload_files uf ON uf.id = rsf.upload_file_id
        WHERE cr.warehouse_id <> uf.warehouse_id
    ) THEN
        RAISE EXCEPTION 'run_source_files contains cross-warehouse rows';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM calculation_results crs
        JOIN products p ON p.id = crs.product_id
        JOIN calculation_runs cr ON cr.id = crs.run_id
        JOIN warehouses w ON w.id = cr.warehouse_id
        WHERE p.user_id <> w.user_id
    ) THEN
        RAISE EXCEPTION 'calculation_results contains cross-owner rows';
    END IF;
END
$$;

CREATE FUNCTION ipgo_reject_owner_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.user_id IS DISTINCT FROM NEW.user_id THEN
        RAISE EXCEPTION 'resource owner cannot be changed'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_warehouses_owner_immutable
BEFORE UPDATE OF user_id ON warehouses
FOR EACH ROW EXECUTE FUNCTION ipgo_reject_owner_change();

CREATE TRIGGER trg_products_owner_immutable
BEFORE UPDATE OF user_id ON products
FOR EACH ROW EXECUTE FUNCTION ipgo_reject_owner_change();

CREATE FUNCTION ipgo_check_product_warehouse_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    product_owner BIGINT;
    warehouse_owner BIGINT;
BEGIN
    SELECT user_id INTO product_owner FROM products WHERE id = NEW.product_id;
    SELECT user_id INTO warehouse_owner FROM warehouses WHERE id = NEW.warehouse_id;
    IF product_owner IS DISTINCT FROM warehouse_owner THEN
        RAISE EXCEPTION 'product and warehouse must have the same owner'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_inventory_units_owner
BEFORE INSERT OR UPDATE OF product_id, warehouse_id ON inventory_units
FOR EACH ROW EXECUTE FUNCTION ipgo_check_product_warehouse_owner();

CREATE TRIGGER trg_planned_receipts_owner
BEFORE INSERT OR UPDATE OF product_id, warehouse_id ON planned_receipts
FOR EACH ROW EXECUTE FUNCTION ipgo_check_product_warehouse_owner();

CREATE FUNCTION ipgo_check_daily_stock_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    upload_warehouse BIGINT;
    unit_warehouse BIGINT;
BEGIN
    SELECT warehouse_id INTO upload_warehouse FROM upload_files WHERE id = NEW.upload_file_id;
    SELECT warehouse_id INTO unit_warehouse FROM inventory_units WHERE id = NEW.inventory_unit_id;
    IF upload_warehouse IS DISTINCT FROM unit_warehouse THEN
        RAISE EXCEPTION 'upload file and inventory unit must use the same warehouse'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_daily_stock_records_scope
BEFORE INSERT OR UPDATE OF upload_file_id, inventory_unit_id ON daily_stock_records
FOR EACH ROW EXECUTE FUNCTION ipgo_check_daily_stock_scope();

CREATE FUNCTION ipgo_check_product_daily_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    product_owner BIGINT;
    upload_owner BIGINT;
BEGIN
    SELECT user_id INTO product_owner FROM products WHERE id = NEW.product_id;
    SELECT w.user_id INTO upload_owner
    FROM upload_files uf
    JOIN warehouses w ON w.id = uf.warehouse_id
    WHERE uf.id = NEW.upload_file_id;
    IF product_owner IS DISTINCT FROM upload_owner THEN
        RAISE EXCEPTION 'product and upload file must have the same owner'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_product_daily_stocks_owner
BEFORE INSERT OR UPDATE OF product_id, upload_file_id ON product_daily_stocks
FOR EACH ROW EXECUTE FUNCTION ipgo_check_product_daily_owner();

CREATE FUNCTION ipgo_check_run_source_warehouse()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    run_warehouse BIGINT;
    upload_warehouse BIGINT;
BEGIN
    SELECT warehouse_id INTO run_warehouse FROM calculation_runs WHERE id = NEW.run_id;
    SELECT warehouse_id INTO upload_warehouse FROM upload_files WHERE id = NEW.upload_file_id;
    IF run_warehouse IS DISTINCT FROM upload_warehouse THEN
        RAISE EXCEPTION 'calculation run and upload file must use the same warehouse'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_run_source_files_warehouse
BEFORE INSERT OR UPDATE OF run_id, upload_file_id ON run_source_files
FOR EACH ROW EXECUTE FUNCTION ipgo_check_run_source_warehouse();

CREATE FUNCTION ipgo_check_calculation_result_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    product_owner BIGINT;
    run_owner BIGINT;
BEGIN
    SELECT user_id INTO product_owner FROM products WHERE id = NEW.product_id;
    SELECT w.user_id INTO run_owner
    FROM calculation_runs cr
    JOIN warehouses w ON w.id = cr.warehouse_id
    WHERE cr.id = NEW.run_id;
    IF product_owner IS DISTINCT FROM run_owner THEN
        RAISE EXCEPTION 'calculation result product and run must have the same owner'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_calculation_results_owner
BEFORE INSERT OR UPDATE OF run_id, product_id ON calculation_results
FOR EACH ROW EXECUTE FUNCTION ipgo_check_calculation_result_owner();

ALTER TABLE planned_receipts
    ADD CONSTRAINT ck_planned_receipts_status_dates CHECK (
        (status = 'NO_DATE' AND expected_on IS NULL AND received_on IS NULL)
        OR (status IN ('PENDING', 'OVERDUE') AND expected_on IS NOT NULL AND received_on IS NULL)
        OR (status = 'RECEIVED' AND received_on IS NOT NULL)
        OR status = 'CANCELLED'
    );
