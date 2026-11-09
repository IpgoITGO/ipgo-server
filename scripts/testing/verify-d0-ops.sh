#!/usr/bin/env bash
# 로컬 폐기 PostgreSQL만. 호스트 포트·운영 DSN·AWS 자격증명을 사용하지 않는다.
set -euo pipefail
d0_root=$(cd "$(dirname "$0")/../.." && pwd)
d0_name="ipgo-d0-test-$$"
d0_network="$d0_name-network"
cleanup() {
  docker rm -fv "$d0_name" >/dev/null 2>&1 || true
  docker network rm "$d0_network" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker image inspect postgres:16-alpine >/dev/null
docker network create --internal "$d0_network" >/dev/null
docker run -d --name "$d0_name" --label com.ipgo.disposable-test=d0 --network "$d0_network" \
  -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=ipgo postgres:16-alpine >/dev/null
for d0_attempt in {1..30}; do
  if docker exec "$d0_name" pg_isready -h 127.0.0.1 -U postgres -d ipgo >/dev/null; then break; fi
  sleep 1
done
docker exec -i "$d0_name" psql -X -v ON_ERROR_STOP=1 -U postgres -d ipgo \
  < "$d0_root/database/bootstrap/create_roles.sql" >/dev/null
while IFS= read -r d0_migration; do
  docker exec -i "$d0_name" psql -X -v ON_ERROR_STOP=1 -U ipgo_migrator -d ipgo \
    < "$d0_migration" >/dev/null
done < <(printf '%s\n' "$d0_root"/src/main/resources/db/migration/V*.sql | sort -V)
# RDS master와 같은 비-superuser CREATEROLE/기존 migrator 관리 권한으로 설치한다.
docker exec "$d0_name" psql -X -v ON_ERROR_STOP=1 -U postgres -d ipgo -c \
  "CREATE ROLE d0_local_admin LOGIN CREATEROLE CREATEDB; GRANT ipgo_migrator TO d0_local_admin WITH ADMIN TRUE, INHERIT FALSE, SET FALSE; GRANT CONNECT ON DATABASE ipgo TO d0_local_admin;" >/dev/null
docker exec -i "$d0_name" psql -X -v ON_ERROR_STOP=1 -U d0_local_admin -d ipgo \
  < "$d0_root/database/bootstrap/create_d0_ops.sql" >/dev/null
docker exec "$d0_name" psql -X -v ON_ERROR_STOP=1 -U postgres -d ipgo -c \
  "DO \$\$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.roleid JOIN pg_roles u ON u.oid=m.member WHERE r.rolname='ipgo_d0_owner' AND u.rolname='d0_local_admin' AND m.admin_option AND NOT m.inherit_option AND NOT m.set_option) OR NOT EXISTS(SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.roleid JOIN pg_roles u ON u.oid=m.member WHERE r.rolname='ipgo_migrator' AND u.rolname='d0_local_admin' AND m.admin_option AND NOT m.inherit_option AND NOT m.set_option) THEN RAISE EXCEPTION 'D0_ADMIN_MEMBERSHIP_NOT_RESTORED'; END IF; END \$\$;" >/dev/null
docker exec "$d0_name" psql -X -v ON_ERROR_STOP=1 -U postgres -d ipgo \
  -c 'ALTER ROLE ipgo_d0_bootstrap LOGIN' >/dev/null
python3 "$d0_root/scripts/d0/verify_database.py" "$d0_name"
