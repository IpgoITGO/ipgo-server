#!/usr/bin/env bash
# 폐기용 PostgreSQL + 서버 역할로 빌링 API 검사. 토스 연결/키 입력/실제 청구 없음.
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
check_id="ipgo-billing-check-$$"
cleanup() {
  docker rm -f "${check_id}" >/dev/null 2>&1 || true
  docker network rm "${check_id}" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker network create --internal "${check_id}" >/dev/null
docker run -d --name "${check_id}" --network "${check_id}" \
  -e POSTGRES_DB=ipgo_billing_test -e POSTGRES_USER=postgres \
  -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16-alpine >/dev/null
for attempt in $(seq 1 30); do
  if docker exec "${check_id}" pg_isready -U postgres -d ipgo_billing_test >/dev/null 2>&1; then break; fi
  if [[ "${attempt}" -eq 30 ]]; then echo 'temporary billing database did not become ready' >&2; exit 1; fi
  sleep 1
done
docker exec -i "${check_id}" psql -v ON_ERROR_STOP=1 -U postgres -d ipgo_billing_test \
  < "${repository_root}/database/bootstrap/create_roles.sql" >/dev/null
while IFS= read -r migration; do
  docker exec -i "${check_id}" psql -v ON_ERROR_STOP=1 -U ipgo_migrator -d ipgo_billing_test < "${migration}" >/dev/null
done < <(printf '%s\n' "${repository_root}"/src/main/resources/db/migration/V*.sql | sort -V)
docker exec -i "${check_id}" psql -v ON_ERROR_STOP=1 -U ipgo_migrator -d ipgo_billing_test \
  < "${repository_root}/scripts/testing/billing-ledger-invariants.sql" >/dev/null
for role in ipgo_ai_reader ipgo_ai_service_reader; do
  if docker exec "${check_id}" psql -v ON_ERROR_STOP=1 -U "${role}" -d ipgo_billing_test -c 'SELECT * FROM billing_methods' >/dev/null 2>&1; then
    echo "billing data unexpectedly readable by ${role}" >&2; exit 1
  fi
done
check_host="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "${check_id}")"
cd "${repository_root}"
IPGO_TEST_DB_URL="jdbc:postgresql://${check_host}:5432/ipgo_billing_test" \
IPGO_TEST_DB_USERNAME=ipgo_server IPGO_TEST_DB_PASSWORD='' IPGO_BILLING_TEST_DATABASE=temporary \
SPRING_FLYWAY_ENABLED=false ./gradlew test --rerun-tasks \
  --tests 'com.ipgo.server.billing.*' --tests 'com.ipgo.server.infrastructure.TossBillingGatewayTests' \
  --offline --console=plain
echo 'Billing PostgreSQL V1+ migrations, tenant constraints, roles and fake-provider tests passed'
