#!/usr/bin/env bash
# Disposable, internal-only FastAPI/FakeClient + PostgreSQL contract test. No provider credentials.
set -euo pipefail
: "${1:?Usage: verify-insight-contract.sh /path/to/ipgo-ai}"
insight_ai_dir=$(realpath "$1")
insight_repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
insight_prefix="ipgo-insight-contract-$$"
insight_network_created=false
insight_db_created=false
insight_api_created=false
cleanup() {
  if "$insight_api_created"; then docker rm -f "$insight_prefix-api" >/dev/null; fi
  if "$insight_db_created"; then docker rm -f "$insight_prefix-db" >/dev/null; fi
  if "$insight_network_created"; then docker network rm "$insight_prefix" >/dev/null; fi
}
trap cleanup EXIT
# These local images and the Gradle cache must be prepared before starting this offline test.
docker image inspect "${IPGO_INSIGHT_TEST_AI_IMAGE:-ipgo-ai:insight-test}" >/dev/null
docker image inspect postgres:16 eclipse-temurin:21-jdk-jammy >/dev/null
docker network create --internal "$insight_prefix" >/dev/null
insight_network_created=true
docker run -d --name "$insight_prefix-db" --network "$insight_prefix" --network-alias ipgo-insight-test-db \
  -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_USER=insight_test -e POSTGRES_DB=insight_test postgres:16 >/dev/null
insight_db_created=true
docker run -d --name "$insight_prefix-api" --user 0:0 --network "$insight_prefix" --network-alias ipgo-insight-fake-api \
  -e ENGINE_MODE=real -e PYTHONDONTWRITEBYTECODE=1 -e PYTHONPATH=/app \
  -v "$insight_ai_dir/app:/app/app:ro" -v "$insight_ai_dir/insight:/app/insight:ro" \
  -v "$insight_ai_dir/schemas:/app/schemas:ro" -v "$insight_ai_dir/engine:/app/engine:ro" \
  -v "$insight_repo_dir/scripts/testing/insight_fake_api.py:/app/insight_fake_api.py:ro" \
  --entrypoint python "${IPGO_INSIGHT_TEST_AI_IMAGE:-ipgo-ai:insight-test}" /app/insight_fake_api.py >/dev/null
insight_api_created=true
insight_ready=false
for insight_attempt in {1..30}; do
  if docker exec "$insight_prefix-db" pg_isready -U insight_test -d insight_test >/dev/null 2>&1 && \
     docker exec "$insight_prefix-api" python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=1)" >/dev/null 2>&1; then
    insight_ready=true
    break
  fi
  sleep 1
done
if ! "$insight_ready"; then echo 'Disposable test services did not become ready' >&2; exit 1; fi
docker run --rm --network "$insight_prefix" \
  -e IPGO_TEST_DB_URL=jdbc:postgresql://ipgo-insight-test-db:5432/insight_test \
  -e IPGO_TEST_DB_USERNAME=insight_test -e IPGO_TEST_DB_PASSWORD= -e IPGO_INSIGHT_LOCAL_FASTAPI=true \
  -v "$insight_repo_dir:/workspace" -v "${HOME}/.gradle:/root/.gradle" -w /workspace \
  eclipse-temurin:21-jdk-jammy ./gradlew --offline --no-daemon --max-workers=2 test \
    --tests '*InsightGenerationIntegrationTests' --rerun-tasks
