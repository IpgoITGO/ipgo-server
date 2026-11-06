#!/usr/bin/env bash
# 개발 환경의 V15/V17 보류를 유지한 V1~14,16 -> V18만 검증한다.
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
check_id="$$"
network="ipgo-history-check-${check_id}"
container="ipgo-history-check-${check_id}"
bundle="$(mktemp -d)"
cleanup() {
  docker rm -fv "$container" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf "$bundle"
}
trap cleanup EXIT
for version in $(seq 1 14) 16 18; do
  cp "$repository_root"/src/main/resources/db/migration/V"${version}"__*.sql "$bundle/"
done
docker network create --internal "$network" >/dev/null
docker run -d --name "$container" --network "$network" \
  -e POSTGRES_DB=ipgo -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16-alpine >/dev/null
for attempt in $(seq 1 30); do
  if docker exec "$container" pg_isready -U postgres -d ipgo >/dev/null 2>&1; then break; fi
  if [[ "$attempt" = 30 ]]; then echo '임시 PostgreSQL 시작 실패' >&2; exit 1; fi
  sleep 1
done
docker exec -i "$container" psql -U postgres -d ipgo -v ON_ERROR_STOP=1 \
  < "$repository_root/database/bootstrap/create_roles.sql" >/dev/null
migrate() {
  docker run --rm --network "$network" -v "$bundle:/flyway/sql:ro" flyway/flyway:11-alpine \
    -url="jdbc:postgresql://$container:5432/ipgo" -user=ipgo_migrator -target="$1" migrate
}
migrate 16
docker exec -i "$container" psql -U ipgo_server -d ipgo -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
INSERT INTO users(id,email,password_hash) VALUES (1,'history-fixture@example.test','disabled-fixture');
INSERT INTO warehouses(id,user_id,name) VALUES (10,1,'fixture');
INSERT INTO products(id,user_id,sku_barcode,sku_name) VALUES (100,1,'HISTORY','fixture');
INSERT INTO calculation_runs(id,warehouse_id,run_type,as_of_date,status,engine_version,selection_snapshot,created_at)
VALUES (1,10,'USER',DATE '2026-11-01','DONE','retained-engine','{"original":"retained"}',CURRENT_TIMESTAMP);
INSERT INTO calculation_results(id,run_id,product_id,origin_date,start_date,multiplier,horizon_days,status,input_snapshot)
VALUES (1,1,100,DATE '2026-11-01',DATE '2026-11-01',1,7,'OK','{"input":"retained"}');
INSERT INTO ai_explanations(result_id,mode,status,request_payload,validation_passed,versions,model_name,input_tokens,output_tokens,cost_usd,rendered_text)
VALUES (1,'LLM','DONE','{}',TRUE,'{"prompt":"retained"}','retained-model',12,3,0.001234,'retained text');
SQL
migrate 18
docker exec -i "$container" psql -U ipgo_migrator -d ipgo -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
DO $$ BEGIN
 IF (SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success)
    <> '1,2,3,4,5,6,7,8,9,10,11,12,13,14,16,18' THEN RAISE EXCEPTION 'unexpected history'; END IF;
 IF to_regclass('public.upload_originals') IS NOT NULL OR to_regclass('public.billing_subscriptions') IS NOT NULL
    OR EXISTS(SELECT 1 FROM information_schema.columns WHERE table_name='upload_files' AND column_name='storage_kind')
    THEN RAISE EXCEPTION 'deferred schema changed'; END IF;
 IF (SELECT COUNT(*) FROM ai_explanation_calls)<>0 THEN RAISE EXCEPTION 'invented historical calls'; END IF;
 IF NOT EXISTS(SELECT 1 FROM ai_explanations WHERE result_id=1 AND model_name='retained-model' AND input_tokens=12
    AND output_tokens=3 AND cost_usd=0.001234 AND rendered_text='retained text' AND active_attempt_id IS NULL)
    THEN RAISE EXCEPTION 'historical explanation changed'; END IF;
 IF NOT EXISTS(SELECT 1 FROM calculation_runs WHERE id=1 AND engine_version='retained-engine'
    AND selection_snapshot='{"original":"retained"}'::jsonb) THEN RAISE EXCEPTION 'run snapshot changed'; END IF;
END $$;
SQL
migrate 18
printf '%s\n' 'V1~14,16 -> V18 검증 통과: V15/V17 보류·기존 설명 보존·가짜 호출 이력 없음'
