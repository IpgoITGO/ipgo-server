#!/usr/bin/env bash
set -euo pipefail

check_id="$$"
network_name="ipgo-db-check-${check_id}"
database_container="ipgo-db-check-${check_id}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cleanup() {
  docker rm -fv "${database_container}" >/dev/null 2>&1 || true
  docker network rm "${network_name}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

expect_denied() {
  local role="$1"
  local sql="$2"
  if docker exec "${database_container}" \
      psql -v ON_ERROR_STOP=1 -U "${role}" -d ipgo -c "${sql}" >/dev/null 2>&1; then
    echo "expected PostgreSQL to deny ${role}: ${sql}" >&2
    return 1
  fi
}

docker network create --internal "${network_name}" >/dev/null
docker run -d --name "${database_container}" --network "${network_name}" \
  -e POSTGRES_DB=ipgo \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_HOST_AUTH_METHOD=trust \
  postgres:16-alpine >/dev/null

for attempt in $(seq 1 30); do
  if docker exec "${database_container}" \
      psql -v ON_ERROR_STOP=1 -U postgres -d ipgo -c "SELECT 1" >/dev/null 2>&1; then
    break
  fi
  if [[ "${attempt}" -eq 30 ]]; then
    echo "temporary PostgreSQL did not become ready" >&2
    exit 1
  fi
  sleep 1
done

docker exec -i "${database_container}" psql -v ON_ERROR_STOP=1 -U postgres -d ipgo \
  < "${repository_root}/database/bootstrap/create_roles.sql" >/dev/null

docker run --rm --network "${network_name}" \
  -v "${repository_root}/src/main/resources/db/migration:/flyway/sql:ro" \
  flyway/flyway:11-alpine \
  -url="jdbc:postgresql://${database_container}:5432/ipgo" \
  -user=ipgo_migrator \
  -target=9 \
  migrate

docker exec -i "${database_container}" psql -v ON_ERROR_STOP=1 -U ipgo_migrator -d ipgo >/dev/null <<'SQL'
INSERT INTO users (id, email, password_hash) VALUES
    (1, 'owner@example.test', 'test-only-hash'),
    (2, 'other@example.test', 'test-only-hash');
INSERT INTO warehouses (id, user_id, name) VALUES
    (10, 1, 'owner warehouse'),
    (20, 2, 'other warehouse');
INSERT INTO products (id, user_id, sku_barcode, sku_name) VALUES
    (100, 1, 'SKU-100', 'owner product');
INSERT INTO inventory_units (id, product_id, warehouse_id, source_inventory_key, received_on)
VALUES (1000, 100, 10, 'LOT-100', DATE '2026-10-01');
INSERT INTO upload_files
    (id, warehouse_id, original_name, storage_key, file_hash, revision_no, period_start, period_end, status)
VALUES
    (2000, 10, '2026-10.xlsx', '1/upload', repeat('a', 64), 1,
     DATE '2026-10-01', DATE '2026-10-31', 'DONE');
INSERT INTO planned_receipts
    (product_id, warehouse_id, expected_on, quantity, status, created_at, updated_at)
VALUES (100, 10, DATE '2026-11-05', 5, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO calculation_runs
    (id, warehouse_id, run_type, as_of_date, status, engine_version, selection_snapshot, created_at)
VALUES (3000, 10, 'CALCULATE', DATE '2026-10-31', 'DONE', '0.1.0', '{}'::jsonb, CURRENT_TIMESTAMP);
INSERT INTO calculation_results
    (id, run_id, product_id, origin_date, start_date, multiplier, horizon_days, status, input_snapshot)
VALUES (4000, 3000, 100, DATE '2026-10-31', DATE '2026-11-01', 1.000, 30, 'OK', '{}'::jsonb);
SQL

docker run --rm --network "${network_name}" \
  -v "${repository_root}/src/main/resources/db/migration:/flyway/sql:ro" \
  flyway/flyway:11-alpine \
  -url="jdbc:postgresql://${database_container}:5432/ipgo" \
  -user=ipgo_migrator \
  migrate

migration_count="$(docker exec "${database_container}" \
  psql -U ipgo_migrator -d ipgo -Atc \
  "SELECT COUNT(*) FROM flyway_schema_history WHERE success")"
table_count="$(docker exec "${database_container}" \
  psql -U ipgo_migrator -d ipgo -Atc \
  "SELECT COUNT(*) FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'")"
if [[ "${migration_count}" != "19" || "${table_count}" != "32" ]]; then
  echo "unexpected schema: migrations=${migration_count}, tables=${table_count}" >&2
  exit 1
fi

docker exec -i "${database_container}" psql -v ON_ERROR_STOP=1 -U ipgo_server -d ipgo >/dev/null <<'SQL'
INSERT INTO product_daily_stocks
    (product_id, upload_file_id, stock_date, opening_qty, inbound_qty, outbound_qty,
     adjustment_qty, closing_qty, is_valid_observation)
VALUES (100, 2000, DATE '2026-10-01', 10.000, 2.000, 1.000, 0.000, 11.000, TRUE);
SQL

visible_rows="$(docker exec "${database_container}" \
  psql -U ipgo_ai_reader -d ipgo -Atc "SELECT COUNT(*) FROM ai_product_daily_stocks_v1")"
read_only="$(docker exec "${database_container}" \
  psql -U ipgo_ai_reader -d ipgo -Atc "SHOW default_transaction_read_only")"
if [[ "${visible_rows}" != "1" || "${read_only}" != "on" ]]; then
  echo "AI view or read-only default is not active" >&2
  exit 1
fi

# V15 preserves external metadata and limits raw originals to the explicit reader view.
docker exec -i "${database_container}" psql -v ON_ERROR_STOP=1 -U ipgo_server -d ipgo >/dev/null <<'SQL'
DO $$ BEGIN
    IF (SELECT storage_kind FROM upload_files WHERE id = 2000) <> 'EXTERNAL' THEN
        RAISE EXCEPTION 'existing external original changed';
    END IF;
END $$;
INSERT INTO upload_files(id,warehouse_id,original_name,storage_key,file_hash,revision_no,period_start,period_end,status,storage_kind)
VALUES (2001,10,'2026-11.xlsx','db/1/test',repeat('b',64),1,DATE '2026-11-01',DATE '2026-11-30','PROCESSING','DATABASE');
INSERT INTO upload_originals(upload_file_id,content,content_type,size_bytes)
VALUES (2001,decode('616263','hex'),'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',3);
SQL
docker exec "${database_container}" psql -v ON_ERROR_STOP=1 -U ipgo_ai_service_reader -d ipgo \
  -c "SELECT upload_file_id,user_id,warehouse_id,size_bytes,content FROM ai_upload_originals_v1" >/dev/null
expect_denied ipgo_ai_reader "SELECT * FROM upload_originals"
expect_denied ipgo_ai_reader "SELECT * FROM ai_upload_originals_v1"
expect_denied ipgo_ai_service_reader "SELECT * FROM upload_originals"
expect_denied ipgo_ai_service_reader "UPDATE upload_files SET status='FAILED' WHERE false"
expect_denied postgres "DELETE FROM upload_originals WHERE upload_file_id=2001"
expect_denied postgres "TRUNCATE upload_originals"
expect_denied ipgo_server "UPDATE upload_originals SET size_bytes=3 WHERE upload_file_id=2001"
expect_denied ipgo_server "DELETE FROM upload_originals WHERE upload_file_id=2001"
expect_denied ipgo_server "INSERT INTO upload_originals VALUES (2000,decode('61','hex'),'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',2,CURRENT_TIMESTAMP)"

# Cleaning metadata is available only through the three explicit views; base tables remain denied.
docker exec "${database_container}" psql -v ON_ERROR_STOP=1 -U ipgo_ai_reader -d ipgo \
  -c "SELECT upload_file_id,user_id,warehouse_id FROM ai_cleaning_sources_v1; SELECT user_id,sku_barcode FROM ai_cleaning_products_v1; SELECT upload_file_id,closing_qty FROM ai_cleaning_previous_units_v1" >/dev/null
expect_denied ipgo_ai_reader "SELECT * FROM engine_jobs"
expect_denied ipgo_ai_reader "SELECT * FROM upload_files"
expect_denied ipgo_ai_reader "SELECT * FROM users"
expect_denied ipgo_ai_reader "SELECT * FROM product_daily_stocks"
expect_denied ipgo_ai_reader "INSERT INTO products (user_id, sku_barcode, sku_name) VALUES (1, 'DENIED', 'denied')"
expect_denied ipgo_ai_reader "UPDATE products SET sku_name = 'denied' WHERE id = 100"
expect_denied ipgo_ai_reader "DELETE FROM products WHERE id = 100"
expect_denied ipgo_ai_reader "CREATE TABLE denied_by_ai (id BIGINT)"
expect_denied ipgo_server "CREATE TABLE denied_by_server (id BIGINT)"
expect_denied ipgo_server \
  "INSERT INTO inventory_units (product_id, warehouse_id, source_inventory_key, received_on) VALUES (100, 20, 'CROSS-OWNER', DATE '2026-10-02')"
expect_denied ipgo_server "UPDATE products SET user_id = 2 WHERE id = 100"

docker exec "${database_container}" psql -v ON_ERROR_STOP=1 -U ipgo_server -d ipgo \
  -c "UPDATE products SET sku_name = 'updated by server' WHERE id = 100" >/dev/null

# V16: 실제 서버 역할의 쓰기, 중복 발주 배정 거부, 소유자/창고/상품 범위 FK, AI 접근 차단.
docker exec -i "${database_container}" psql -v ON_ERROR_STOP=1 -U ipgo_server -d ipgo >/dev/null <<'SQL'
INSERT INTO receipt_matching_events(id,user_id,warehouse_id,product_id,stock_date,quantity,valid_observation,source_upload_file_id)
VALUES (9000,1,10,100,DATE '2026-10-01',5,TRUE,2000);
INSERT INTO receipt_matching_allocations(receipt_id,event_id,warehouse_id,product_id,quantity,sku_name)
SELECT MIN(id),9000,10,100,5,'owner product' FROM planned_receipts;
INSERT INTO receipt_matching_reports(upload_file_id,report) VALUES (2000,'{}'::jsonb);
SQL
expect_denied ipgo_server "INSERT INTO receipt_matching_events(user_id,warehouse_id,product_id,stock_date,quantity,valid_observation,source_upload_file_id) VALUES (2,10,100,DATE '2026-10-02',5,TRUE,2000)"
expect_denied ipgo_server "INSERT INTO receipt_matching_events(user_id,warehouse_id,product_id,stock_date,quantity,valid_observation,source_upload_file_id) VALUES (1,20,100,DATE '2026-10-02',5,TRUE,2000)"
expect_denied ipgo_server "INSERT INTO receipt_matching_allocations(receipt_id,event_id,warehouse_id,product_id,quantity,sku_name) SELECT MIN(id),9000,10,100,5,'duplicate' FROM planned_receipts"
expect_denied ipgo_server "UPDATE receipt_matching_allocations SET warehouse_id=20 WHERE event_id=9000"
expect_denied ipgo_ai_reader "SELECT * FROM receipt_matching_events"
expect_denied ipgo_ai_reader "SELECT * FROM receipt_matching_allocations"
expect_denied ipgo_ai_service_reader "SELECT * FROM receipt_matching_reports"

# Server owns call observations; neither AI reader may inspect requests/usage or change history.
docker exec "${database_container}" psql -v ON_ERROR_STOP=1 -U ipgo_server -d ipgo \
  -c "SELECT attempt_id,result_id,input_tokens FROM ai_explanation_calls; UPDATE ai_explanation_calls SET status='DONE' WHERE false" >/dev/null
expect_denied ipgo_ai_reader "SELECT * FROM ai_explanation_calls"
expect_denied ipgo_ai_service_reader "SELECT * FROM ai_explanation_calls"
# V19: 보관 요율·상자 크기는 서버 역할만 쓰고, 0 이하 값은 거부하며 AI 역할은 볼 수 없다(#111).
docker exec -i "${database_container}" psql -v ON_ERROR_STOP=1 -U ipgo_server -d ipgo >/dev/null <<'SQL'
UPDATE warehouses SET storage_rate_per_cbm = 15000.50 WHERE id = 10;
UPDATE warehouses SET storage_rate_per_cbm = NULL WHERE id = 20;
INSERT INTO product_boxes(product_id,width_cm,depth_cm,height_cm) VALUES (100,30.5,20,10);
UPDATE product_boxes SET height_cm = 12.5 WHERE product_id = 100;
SQL
expect_denied ipgo_server "UPDATE warehouses SET storage_rate_per_cbm = 0 WHERE id = 10"
expect_denied ipgo_server "INSERT INTO product_boxes(product_id,width_cm,depth_cm,height_cm) VALUES (100,1,1,1)"
expect_denied ipgo_server "UPDATE product_boxes SET width_cm = 0 WHERE product_id = 100"
expect_denied ipgo_server "INSERT INTO product_boxes(product_id,width_cm,depth_cm,height_cm) VALUES (999999,1,1,1)"
expect_denied ipgo_ai_reader "SELECT * FROM product_boxes"
expect_denied ipgo_ai_service_reader "SELECT * FROM product_boxes"
docker exec "${database_container}" psql -v ON_ERROR_STOP=1 -U ipgo_server -d ipgo \
  -c "DELETE FROM product_boxes WHERE product_id = 100" >/dev/null

echo "PostgreSQL migrations, tenant integrity, and database roles verified."
