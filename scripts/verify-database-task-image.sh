#!/usr/bin/env bash
set -euo pipefail

check_id="$$"
image_name="ipgo-database-task:${check_id}"
network_name="ipgo-database-task-${check_id}"
database_container="ipgo-database-task-pg-${check_id}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cleanup() {
  docker rm -f "${database_container}" >/dev/null 2>&1 || true
  docker network rm "${network_name}" >/dev/null 2>&1 || true
  docker image rm -f "${image_name}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

readonly admin_password="local-admin-${check_id}"
readonly migrator_password="local-migrator-${check_id}"
readonly server_password="local-server-${check_id}"
readonly ai_password="local-ai-${check_id}"

docker build --file "${repository_root}/Dockerfile.database" \
  --tag "${image_name}" "${repository_root}"
docker network create "${network_name}" >/dev/null
docker run -d --name "${database_container}" --network "${network_name}" \
  -e POSTGRES_DB=ipgo \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD="local-postgres-${check_id}" \
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

docker exec -i "${database_container}" psql -v ON_ERROR_STOP=1 -U postgres -d ipgo >/dev/null <<SQL
CREATE ROLE bootstrap_admin LOGIN NOSUPERUSER CREATEDB CREATEROLE
    PASSWORD '${admin_password}';
ALTER DATABASE ipgo OWNER TO bootstrap_admin;
SQL

common_environment=(
  --env DB_HOST="${database_container}"
  --env DB_PORT=5432
  --env DB_NAME=ipgo
)

docker run --rm --network "${network_name}" \
  "${common_environment[@]}" \
  --env DB_ADMIN_USERNAME=bootstrap_admin \
  --env DB_ADMIN_PASSWORD="${admin_password}" \
  --env IPGO_MIGRATOR_PASSWORD="${migrator_password}" \
  --env IPGO_SERVER_PASSWORD="${server_password}" \
  --env IPGO_AI_READER_PASSWORD="${ai_password}" \
  "${image_name}" bootstrap

# Bootstrap is intentionally idempotent so an interrupted first run can be retried safely.
docker run --rm --network "${network_name}" \
  "${common_environment[@]}" \
  --env DB_ADMIN_USERNAME=bootstrap_admin \
  --env DB_ADMIN_PASSWORD="${admin_password}" \
  --env IPGO_MIGRATOR_PASSWORD="${migrator_password}" \
  --env IPGO_SERVER_PASSWORD="${server_password}" \
  --env IPGO_AI_READER_PASSWORD="${ai_password}" \
  "${image_name}" bootstrap

if docker exec -e PGPASSWORD="${admin_password}" "${database_container}" \
    psql -h 127.0.0.1 -v ON_ERROR_STOP=1 -U bootstrap_admin -d ipgo \
    -c "SET ROLE ipgo_migrator" >/dev/null 2>&1; then
  echo "bootstrap administrator retained SET privilege on ipgo_migrator" >&2
  exit 1
fi

docker run --rm --network "${network_name}" \
  "${common_environment[@]}" \
  --env DB_USERNAME=ipgo_migrator \
  --env DB_PASSWORD="${migrator_password}" \
  "${image_name}" migrate

docker run --rm --network "${network_name}" \
  "${common_environment[@]}" \
  --env IPGO_SERVER_USERNAME=ipgo_server \
  --env IPGO_SERVER_PASSWORD="${server_password}" \
  --env IPGO_AI_READER_USERNAME=ipgo_ai_reader \
  --env IPGO_AI_READER_PASSWORD="${ai_password}" \
  "${image_name}" verify

echo "Database task image bootstrap, migration, and permissions verified."
