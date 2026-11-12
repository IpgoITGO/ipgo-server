#!/usr/bin/env bash
# 폐기용 PostgreSQL16 + Java21. 외부 통신/운영 자격증명/호스트 포트 없음.
set -euo pipefail
matching_root=$(cd "$(dirname "$0")/../.." && pwd)
matching_prefix="ipgo-receipt-matching-$$"
matching_cache="${IPGO_MATCHING_TEST_GRADLE_HOME:-${HOME}/.gradle}"
matching_network_created=false
matching_database_created=false
cleanup() {
  if "$matching_database_created"; then docker rm -fv "$matching_prefix-db" >/dev/null; fi
  if "$matching_network_created"; then docker network rm "$matching_prefix" >/dev/null; fi
}
trap cleanup EXIT
docker image inspect postgres:16-alpine eclipse-temurin:21-jdk-jammy flyway/flyway:11-alpine >/dev/null
[[ -d "$matching_cache" ]] || { echo 'Prepared Gradle cache required' >&2; exit 1; }
docker network create --internal "$matching_prefix" >/dev/null
matching_network_created=true
docker run -d --name "$matching_prefix-db" --network "$matching_prefix" --network-alias ipgo-engine-e2e-118-test-db \
  -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=engine_test postgres:16-alpine >/dev/null
matching_database_created=true
matching_ready=false
for matching_attempt in {1..30}; do
  if docker exec "$matching_prefix-db" psql -h 127.0.0.1 -U postgres -d engine_test -v ON_ERROR_STOP=1 -Atc 'SELECT 1' >/dev/null 2>&1; then matching_ready=true; break; fi
  sleep 1
done
"$matching_ready" || { echo 'Disposable PostgreSQL not ready' >&2; exit 1; }
docker exec -i "$matching_prefix-db" psql -v ON_ERROR_STOP=1 -U postgres -d engine_test \
  < "$matching_root/database/bootstrap/create_roles.sql" >/dev/null
docker run --rm --network "$matching_prefix" -v "$matching_root/src/main/resources/db/migration:/flyway/sql:ro" \
  flyway/flyway:11-alpine -url=jdbc:postgresql://ipgo-engine-e2e-118-test-db:5432/engine_test -user=ipgo_migrator -target=19 migrate
# 기존 V16 배정이 있는 DB에서 V20 기준 이력 생성과 원본 보존을 확인한다.
docker exec -i "$matching_prefix-db" psql -v ON_ERROR_STOP=1 -U ipgo_migrator -d engine_test <<'SQL'
INSERT INTO users(id,email,password_hash) VALUES (9801,'migration-only@example.test','test-only-hash');
INSERT INTO warehouses(id,user_id,name) VALUES (9801,9801,'migration fixture');
INSERT INTO products(id,user_id,sku_barcode,sku_name) VALUES (9801,9801,'BASE','baseline');
INSERT INTO upload_files(id,warehouse_id,original_name,storage_key,file_hash,revision_no,period_start,period_end,status,job_id)
VALUES (9801,9801,'2026-09.xlsx','fixture','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',1,'2026-09-01','2026-09-30','DONE','migration-fixture');
INSERT INTO planned_receipts(id,warehouse_id,product_id,quantity,expected_on,received_on,status,created_at,updated_at)
VALUES (9801,9801,9801,100,'2026-09-10','2026-09-01','RECEIVED',now(),now());
INSERT INTO receipt_matching_events(id,user_id,warehouse_id,product_id,stock_date,quantity,valid_observation,source_upload_file_id,review_required)
VALUES (9801,9801,9801,9801,'2026-09-01',160,TRUE,9801,TRUE);
INSERT INTO receipt_matching_allocations(receipt_id,event_id,warehouse_id,product_id,quantity,sku_name)
VALUES (9801,9801,9801,9801,100,'baseline');
INSERT INTO receipt_matching_reports(upload_file_id,report) VALUES (9801,'{"marker":"immutable-baseline"}');
SQL
matching_views_before=$(docker exec "$matching_prefix-db" psql -At -U ipgo_migrator -d engine_test -c "SELECT md5(string_agg(viewname||definition,'' ORDER BY viewname)) FROM pg_views WHERE schemaname='public' AND viewname LIKE 'ai_%'")
docker run --rm --network "$matching_prefix" -v "$matching_root/src/main/resources/db/migration:/flyway/sql:ro" \
  flyway/flyway:11-alpine -url=jdbc:postgresql://ipgo-engine-e2e-118-test-db:5432/engine_test -user=ipgo_migrator -target=20 migrate
matching_views_after=$(docker exec "$matching_prefix-db" psql -At -U ipgo_migrator -d engine_test -c "SELECT md5(string_agg(viewname||definition,'' ORDER BY viewname)) FROM pg_views WHERE schemaname='public' AND viewname LIKE 'ai_%'")
[[ "$matching_views_before" == "$matching_views_after" ]] || { echo 'AI view contract changed' >&2; exit 1; }
docker exec -i "$matching_prefix-db" psql -v ON_ERROR_STOP=1 -U ipgo_migrator -d engine_test <<'SQL'
DO $$ BEGIN
  IF (SELECT COUNT(*) FROM receipt_allocation_history WHERE action='BASELINE' AND receipt_id=9801 AND quantity=100 AND decision_id IS NULL)<>1
    OR (SELECT COUNT(*) FROM receipt_matching_allocations WHERE receipt_id=9801 AND quantity=100)<>1
    OR NOT (SELECT review_required FROM receipt_matching_events WHERE id=9801)
    OR (SELECT report->>'marker' FROM receipt_matching_reports WHERE upload_file_id=9801)<>'immutable-baseline'
    THEN RAISE EXCEPTION 'V20 baseline/history changed'; END IF;
  IF has_table_privilege('ipgo_server','receipt_correction_decisions','UPDATE')
    OR has_table_privilege('ipgo_server','receipt_allocation_history','DELETE')
    OR has_table_privilege('ipgo_ai_reader','receipt_correction_decisions','SELECT')
    OR has_table_privilege('ipgo_ai_service_reader','receipt_allocation_history','SELECT')
    THEN RAISE EXCEPTION 'V20 privilege mismatch'; END IF;
END $$;
-- 이 전용 폐기 DB의 migration fixture만 지운다.
TRUNCATE users CASCADE;
SQL
# 폐기 DB에만 설치하는 fixture 정리 경로. 실제 앱에는 원장 UPDATE/DELETE 권한을 주지 않는다.
docker exec -i "$matching_prefix-db" psql -v ON_ERROR_STOP=1 -U ipgo_migrator -d engine_test <<'SQL'
CREATE FUNCTION ipgo_test_clear_receipt_history() RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog,public AS $$ BEGIN
  TRUNCATE public.receipt_allocation_history,public.receipt_correction_decisions;
END $$;
REVOKE ALL ON FUNCTION ipgo_test_clear_receipt_history() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ipgo_test_clear_receipt_history() TO ipgo_server;
CREATE FUNCTION ipgo_test_correction_fault(target text,enabled boolean) RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog,public AS $$ BEGIN
  IF target='audit' THEN
    IF enabled THEN ALTER TABLE public.receipt_allocation_history ADD CONSTRAINT correction_test_fault CHECK(action<>'RELEASE');
    ELSE ALTER TABLE public.receipt_allocation_history DROP CONSTRAINT correction_test_fault; END IF;
  ELSIF target='outbox' THEN
    IF enabled THEN ALTER TABLE public.engine_jobs ADD CONSTRAINT correction_test_fault CHECK(job_type<>'CALCULATE');
    ELSE ALTER TABLE public.engine_jobs DROP CONSTRAINT correction_test_fault; END IF;
  ELSE RAISE EXCEPTION 'unsupported test fault'; END IF;
END $$;
REVOKE ALL ON FUNCTION ipgo_test_correction_fault(text,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ipgo_test_correction_fault(text,boolean) TO ipgo_server;
SQL
# 상품 활성 상태는 V29 필드까지 JPA validate한다. 기존 V16→V20 정정 불변 검사는 위에서 유지한다.
docker run --rm --network "$matching_prefix" -v "$matching_root/src/main/resources/db/migration:/flyway/sql:ro" \
  flyway/flyway:11-alpine -url=jdbc:postgresql://ipgo-engine-e2e-118-test-db:5432/engine_test -user=ipgo_migrator migrate
# 서버 역할의 실제 읽기/쓰기 권한과 전체 V1~29 제약을 사용한다.
docker run --rm --network "$matching_prefix" --user "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/gradle-cache \
  -e IPGO_TEST_DB_URL=jdbc:postgresql://ipgo-engine-e2e-118-test-db:5432/engine_test \
  -e IPGO_TEST_DB_USERNAME=ipgo_server -e IPGO_TEST_DB_PASSWORD= -e IPGO_ENGINE_LOCAL_POSTGRES=true \
  -e SPRING_FLYWAY_ENABLED=false \
  -v "$matching_root:/workspace" -v "$matching_cache:/gradle-cache" -w /workspace \
  eclipse-temurin:21-jdk-jammy ./gradlew --offline --no-daemon --max-workers=2 test \
    --tests '*ProductActivityIntegrationTests' --tests '*ReceiptCorrectionIntegrationTests' --tests '*ReceiptMatchingIntegrationTests' --tests '*UploadCleaningIntegrationTests' \
    --tests '*PlannedReceiptIntegrationTests' --tests '*UploadRecordIntegrationTests' \
    --tests '*EngineRealFlowIntegrationTests' --rerun-tasks
