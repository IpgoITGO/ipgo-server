\set ON_ERROR_STOP on

-- 초기 database에 한 번만 administrator로 실행한다.
-- 비밀번호는 이 파일에서 설정하지 않는다. 역할 생성 뒤 승인된 secret 주입 절차로 설정한다.
SELECT 'CREATE ROLE ipgo_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipgo_migrator')
\gexec

SELECT 'CREATE ROLE ipgo_server LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipgo_server')
\gexec

SELECT 'CREATE ROLE ipgo_ai_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipgo_ai_reader')
\gexec

-- RDS master는 PostgreSQL superuser가 아니므로 SUPERUSER/REPLICATION 속성을
-- false로 재지정하는 ALTER도 실행할 수 없다. CREATE ROLE의 안전한 기본값을 유지하고,
-- CREATEROLE 관리자가 재확인할 수 있는 최소 속성만 고정한다.
ALTER ROLE ipgo_migrator LOGIN NOCREATEDB NOCREATEROLE;
ALTER ROLE ipgo_server LOGIN NOCREATEDB NOCREATEROLE;
ALTER ROLE ipgo_ai_reader LOGIN NOCREATEDB NOCREATEROLE;
ALTER ROLE ipgo_ai_reader SET default_transaction_read_only = on;

-- PostgreSQL 16의 CREATEROLE 관리자는 새 소유자 역할을 SET ROLE 할 수 있어야
-- 객체 소유권을 넘길 수 있다. 이전 직전에 SET 권한을 켜고 직후 다시 끈다.
SELECT format('GRANT ipgo_migrator TO %I WITH SET TRUE', current_user)
\gexec
ALTER SCHEMA public OWNER TO ipgo_migrator;
SELECT format('ALTER DATABASE %I OWNER TO ipgo_migrator', current_database())
\gexec
SELECT format('GRANT ipgo_migrator TO %I WITH SET FALSE', current_user)
\gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
