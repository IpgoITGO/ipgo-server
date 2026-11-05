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
  if docker exec "$matching_prefix-db" pg_isready -U postgres -d engine_test >/dev/null 2>&1; then matching_ready=true; break; fi
  sleep 1
done
"$matching_ready" || { echo 'Disposable PostgreSQL not ready' >&2; exit 1; }
docker exec -i "$matching_prefix-db" psql -v ON_ERROR_STOP=1 -U postgres -d engine_test \
  < "$matching_root/database/bootstrap/create_roles.sql" >/dev/null
docker run --rm --network "$matching_prefix" -v "$matching_root/src/main/resources/db/migration:/flyway/sql:ro" \
  flyway/flyway:11-alpine -url=jdbc:postgresql://ipgo-engine-e2e-118-test-db:5432/engine_test -user=ipgo_migrator -target=16 migrate
# 서버 역할의 실제 읽기/쓰기 권한과 전체 V1~16 제약을 사용한다.
docker run --rm --network "$matching_prefix" --user "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/gradle-cache \
  -e IPGO_TEST_DB_URL=jdbc:postgresql://ipgo-engine-e2e-118-test-db:5432/engine_test \
  -e IPGO_TEST_DB_USERNAME=ipgo_server -e IPGO_TEST_DB_PASSWORD= -e IPGO_ENGINE_LOCAL_POSTGRES=true \
  -e SPRING_FLYWAY_ENABLED=false \
  -v "$matching_root:/workspace" -v "$matching_cache:/gradle-cache" -w /workspace \
  eclipse-temurin:21-jdk-jammy ./gradlew --offline --no-daemon --max-workers=2 test \
    --tests '*ReceiptMatchingIntegrationTests' --tests '*UploadCleaningIntegrationTests' \
    --tests '*PlannedReceiptIntegrationTests' --tests '*UploadRecordIntegrationTests' \
    --tests '*EngineRealFlowIntegrationTests' --rerun-tasks
