#!/usr/bin/env sh
set -eu

readonly bootstrap_sql="/opt/ipgo/database/bootstrap/create_roles.sql"
readonly password_sql="/opt/ipgo/database/bootstrap/set_role_passwords.sql"

require_variables() {
  for variable_name in "$@"; do
    eval "variable_value=\${${variable_name}:-}"
    if [ -z "${variable_value}" ]; then
      echo "required environment variable is missing: ${variable_name}" >&2
      exit 2
    fi
  done
}

psql_base() {
  psql_username="$1"
  shift
  psql \
    --no-psqlrc \
    --set=ON_ERROR_STOP=1 \
    --host="${DB_HOST}" \
    --port="${DB_PORT:-5432}" \
    --dbname="${DB_NAME}" \
    --username="${psql_username}" \
    "$@"
}

expect_denied() {
  role_name="$1"
  role_password="$2"
  sql="$3"

  if PGPASSWORD="${role_password}" psql_base "${role_name}" --command="${sql}" >/dev/null 2>&1; then
    echo "expected PostgreSQL to deny ${role_name} operation" >&2
    exit 1
  fi
}

run_bootstrap() {
  require_variables \
    DB_HOST DB_NAME DB_ADMIN_USERNAME DB_ADMIN_PASSWORD \
    IPGO_MIGRATOR_PASSWORD IPGO_SERVER_PASSWORD IPGO_AI_READER_PASSWORD IPGO_AI_SERVICE_READER_PASSWORD

  export PGPASSWORD="${DB_ADMIN_PASSWORD}"
  psql_base "${DB_ADMIN_USERNAME}" --file="${bootstrap_sql}"
  psql_base "${DB_ADMIN_USERNAME}" --file="${password_sql}"
  psql_base "${DB_ADMIN_USERNAME}" --tuples-only --no-align --command="
    SELECT COUNT(*)
    FROM pg_roles
    WHERE rolname IN ('ipgo_migrator', 'ipgo_server', 'ipgo_ai_reader', 'ipgo_ai_service_reader')
      AND rolcanlogin
      AND NOT rolsuper
      AND NOT rolcreatedb
      AND NOT rolcreaterole;
  " | grep -qx '4'
  unset PGPASSWORD
  echo "Database roles bootstrapped."
}

run_migrate() {
  require_variables DB_HOST DB_NAME DB_USERNAME DB_PASSWORD

  export FLYWAY_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT:-5432}/${DB_NAME}"
  export FLYWAY_USER="${DB_USERNAME}"
  export FLYWAY_PASSWORD="${DB_PASSWORD}"
  flyway \
    -connectRetries=12 \
    -target=17 \
    -validateMigrationNaming=true \
    migrate

  migration_state="$(PGPASSWORD="${DB_PASSWORD}" psql_base "${DB_USERNAME}" \
    --tuples-only --no-align \
    --command="
      SELECT CASE
        WHEN COUNT(*) = 17 AND MAX(version::integer) = 17 THEN 'ok'
        ELSE 'invalid'
      END
      FROM flyway_schema_history
      WHERE success;
    ")"
  if [ "${migration_state}" != "ok" ]; then
    echo "unexpected Flyway state after migration" >&2
    exit 1
  fi
  echo "Flyway V1-V17 migration verified."
}

run_bootstrap_original_reader() {
  require_variables DB_HOST DB_NAME DB_ADMIN_USERNAME DB_ADMIN_PASSWORD IPGO_AI_SERVICE_READER_PASSWORD
  export PGPASSWORD="${DB_ADMIN_PASSWORD}"
  psql_base "${DB_ADMIN_USERNAME}" --file=/opt/ipgo/database/bootstrap/create_original_reader.sql
  unset PGPASSWORD
  echo "AI original reader created without changing existing credentials."
}

run_verify() {
  require_variables \
    DB_HOST DB_NAME \
    IPGO_SERVER_USERNAME IPGO_SERVER_PASSWORD \
    IPGO_AI_READER_USERNAME IPGO_AI_READER_PASSWORD \
    IPGO_AI_SERVICE_READER_PASSWORD

  PGPASSWORD="${IPGO_SERVER_PASSWORD}" psql_base "${IPGO_SERVER_USERNAME}" >/dev/null <<'SQL'
BEGIN;
INSERT INTO users (id, email, password_hash)
VALUES (-1, 'database-task-check@example.test', 'check-only-hash');
UPDATE users SET password_hash = 'updated-check-only-hash' WHERE id = -1;
DELETE FROM users WHERE id = -1;
ROLLBACK;
SQL

  ai_view_count="$(PGPASSWORD="${IPGO_AI_READER_PASSWORD}" \
    psql_base "${IPGO_AI_READER_USERNAME}" --tuples-only --no-align \
    --command="SELECT COUNT(*) FROM ai_product_daily_stocks_v1;")"
  case "${ai_view_count}" in
    ''|*[!0-9]*)
      echo "AI view verification returned an invalid count" >&2
      exit 1
      ;;
  esac

  PGPASSWORD="${IPGO_AI_READER_PASSWORD}" psql_base "${IPGO_AI_READER_USERNAME}" \
    --command="SELECT upload_file_id,user_id,warehouse_id FROM ai_cleaning_sources_v1 LIMIT 0; SELECT user_id,sku_barcode FROM ai_cleaning_products_v1 LIMIT 0; SELECT upload_file_id,closing_qty FROM ai_cleaning_previous_units_v1 LIMIT 0;" >/dev/null
  expect_denied "${IPGO_AI_READER_USERNAME}" "${IPGO_AI_READER_PASSWORD}" "SELECT * FROM engine_jobs"
  expect_denied "${IPGO_AI_READER_USERNAME}" "${IPGO_AI_READER_PASSWORD}" "SELECT * FROM upload_originals"
  expect_denied "${IPGO_SERVER_USERNAME}" "${IPGO_SERVER_PASSWORD}" "DELETE FROM upload_originals WHERE false"

  read_only="$(PGPASSWORD="${IPGO_AI_READER_PASSWORD}" \
    psql_base "${IPGO_AI_READER_USERNAME}" --tuples-only --no-align \
    --command="SHOW default_transaction_read_only;")"
  if [ "${read_only}" != "on" ]; then
    echo "AI reader is not transaction read-only" >&2
    exit 1
  fi

  expect_denied "${IPGO_SERVER_USERNAME}" "${IPGO_SERVER_PASSWORD}" \
    "CREATE TABLE denied_by_server_task (id BIGINT)"
  expect_denied "${IPGO_AI_READER_USERNAME}" "${IPGO_AI_READER_PASSWORD}" \
    "SELECT * FROM users"
  expect_denied "${IPGO_AI_READER_USERNAME}" "${IPGO_AI_READER_PASSWORD}" \
    "INSERT INTO users (email, password_hash) VALUES ('denied@example.test', 'denied')"
  expect_denied "${IPGO_AI_READER_USERNAME}" "${IPGO_AI_READER_PASSWORD}" \
    "CREATE TABLE denied_by_ai_task (id BIGINT)"

  PGPASSWORD="${IPGO_AI_SERVICE_READER_PASSWORD}" psql_base ipgo_ai_service_reader \
    --command="SELECT upload_file_id FROM ai_upload_originals_v1 LIMIT 0;" >/dev/null
  expect_denied "${IPGO_AI_READER_USERNAME}" "${IPGO_AI_READER_PASSWORD}" "SELECT * FROM ai_upload_originals_v1"
  expect_denied ipgo_ai_service_reader "${IPGO_AI_SERVICE_READER_PASSWORD}" "SELECT * FROM upload_originals"
  expect_denied ipgo_ai_service_reader "${IPGO_AI_SERVICE_READER_PASSWORD}" "UPDATE upload_files SET status='FAILED' WHERE false"

  echo "Runtime database permissions verified."
}

case "${1:-}" in
  bootstrap-original-reader)
    run_bootstrap_original_reader
    ;;
  bootstrap)
    run_bootstrap
    ;;
  migrate)
    run_migrate
    ;;
  verify)
    run_verify
    ;;
  *)
    echo "usage: database-task {bootstrap|bootstrap-original-reader|migrate|verify}" >&2
    exit 2
    ;;
esac
