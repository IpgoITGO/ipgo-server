#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
probe_tmp=$(mktemp -d)
probe_name="ipgo-month-budget-$$"
cleanup() {
  docker rm -f "${probe_name}-java" "${probe_name}-db" >/dev/null 2>&1 || true
  docker network rm "$probe_name" >/dev/null 2>&1 || true
  rm -rf "$probe_tmp"
}
trap cleanup EXIT
cat > "$probe_tmp/probe.gradle" <<'GRADLE'
allprojects {
  afterEvaluate {
    tasks.register('copyMonthlyProbeRuntime', Copy) {
      from configurations.testRuntimeClasspath
      into System.getenv('IPGO_MONTHLY_PROBE_LIB')
    }
  }
}
GRADLE
mkdir -p "$probe_tmp/runtime/lib" "$probe_tmp/runtime/classes"
IPGO_MONTHLY_PROBE_LIB="$probe_tmp/runtime/lib" "$root/gradlew" -p "$root" --no-daemon -I "$probe_tmp/probe.gradle" compileTestJava copyMonthlyProbeRuntime
cp -R "$root/build/classes/java/main/com" "$probe_tmp/runtime/classes/"
cp -R "$root/build/classes/java/test/com" "$probe_tmp/runtime/classes/"
docker network create --internal "$probe_name" >/dev/null
docker run -d --name "${probe_name}-db" --network "$probe_name" \
  -e POSTGRES_USER=ipgo_migrator -e POSTGRES_PASSWORD=fixture-only -e POSTGRES_DB=ipgo_budget_test \
  postgres:16-alpine >/dev/null
for n in $(seq 1 60); do
  if docker exec "${probe_name}-db" pg_isready -U ipgo_migrator -d ipgo_budget_test >/dev/null 2>&1;then break;fi
  sleep 0.5
done
docker exec -i "${probe_name}-db" psql -U ipgo_migrator -d ipgo_budget_test -v ON_ERROR_STOP=1 <<'SQL'
CREATE ROLE ipgo_server LOGIN PASSWORD 'fixture-only';
CREATE ROLE ipgo_ai_reader;
CREATE TABLE ai_explanation_calls(attempt_id VARCHAR(50) PRIMARY KEY,status VARCHAR(20),dispatched_at TIMESTAMP WITH TIME ZONE);
GRANT SELECT,INSERT,UPDATE,DELETE ON ai_explanation_calls TO ipgo_server;
INSERT INTO ai_explanation_calls(attempt_id,status) SELECT 'attempt-'||i,'PROCESSING' FROM generate_series(0,9)i;
SQL
docker cp "$root/src/main/resources/db/migration/V22__insight_monthly_usage.sql" "${probe_name}-db:/tmp/budget.sql"
docker exec "${probe_name}-db" psql -U ipgo_migrator -d ipgo_budget_test -v ON_ERROR_STOP=1 -f /tmp/budget.sql
docker exec -i "${probe_name}-db" psql -U ipgo_migrator -d ipgo_budget_test -v ON_ERROR_STOP=1 <<'SQL'
-- Local-only threshold fixture. No provider calls or existing production data.
DO $$ BEGIN FOR i IN 1..998 LOOP UPDATE insight_monthly_usage SET reserved_calls=reserved_calls+1; END LOOP; END $$;
DO $$ BEGIN IF has_table_privilege('ipgo_ai_reader','insight_monthly_usage','SELECT') THEN RAISE EXCEPTION 'AI budget access'; END IF; END $$;
SQL
docker create --name "${probe_name}-java" --network "$probe_name" \
  -e "IPGO_BUDGET_PROBE_URL=jdbc:postgresql://${probe_name}-db:5432/ipgo_budget_test" \
  eclipse-temurin:21-jdk-jammy java -cp '/probe/classes:/probe/lib/*' \
  com.ipgo.server.service.MonthlyInsightBudgetPostgresProbe >/dev/null
# Copy explicitly so a remote Docker daemon never assumes the host checkout is mounted there.
docker cp "$probe_tmp/runtime/." "${probe_name}-java:/probe"
docker start -a "${probe_name}-java"
test "$(docker inspect -f '{{.State.ExitCode}}' "${probe_name}-java")" = 0
