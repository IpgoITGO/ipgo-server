"""최초 설치와 서명된 비활성화만. 기존 DB 역할/비밀번호를 회전하지 않는다."""
import json
import os
import secrets
from pathlib import Path

from contract import GateError, require

TABLES = ('users', 'warehouses', 'products', 'upload_files', 'calculation_runs',
          'calculation_results', 'ai_explanations', 'ai_explanation_calls',
          'engine_jobs', 'planned_receipts')


def business_snapshot(conn):
    # 실제 업무행을 출력하지 않고 count/hash만 비교한다. 기존 관리자 membership은
    # 읽기 transaction의 rollback으로 복원한다. 권한 부족이면 DDL 전 중단한다.
    from psycopg import sql
    result = {}
    with conn.transaction(force_rollback=True):
        with conn.cursor() as c:
            c.execute(sql.SQL('GRANT ipgo_migrator TO {} WITH INHERIT TRUE, SET TRUE').format(
                sql.Identifier(conn.info.user)))
            c.execute('SET LOCAL ROLE ipgo_migrator')
            for table in TABLES:
                c.execute(sql.SQL("SELECT count(*),md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY md5(to_jsonb(t)::text)),'')) FROM public.{} t").format(sql.Identifier(table)))
                result[table] = c.fetchone()
    return result


def deactivate(manifest, database):
    conn = database.connection
    # operation marker가 일치하는 신규 역할만 차단. 기존 계정/다른 설치 건은 건드리지 않는다.
    with conn.transaction():
        with conn.cursor() as c:
            c.execute("SELECT pg_advisory_xact_lock(hashtextextended('ipgo:d0:installation',0))")
            c.execute("SELECT shobj_description(oid,'pg_authid') FROM pg_roles WHERE rolname='ipgo_d0_bootstrap'")
            row = c.fetchone()
            require(row and row[0] == 'ipgo:d0:install:' + manifest['operationId'], 'DEACTIVATION_OPERATION_MISMATCH')
            c.execute('ALTER ROLE ipgo_d0_bootstrap NOLOGIN')
    # NOLOGIN만으로 기존 세션은 종료되지 않는다. 확정된 전용 role의 세션만 종료한다.
    # 권한/응답 불명확이면 성공으로 표시하지 않고 수동 복구가 필요한 상태로 남긴다.
    try:
        with conn.cursor() as c:
            c.execute("SELECT pg_terminate_backend(pid,5000) FROM pg_stat_activity WHERE usename='ipgo_d0_bootstrap' AND pid<>pg_backend_pid()", prepare=False)
            require(all(row[0] is True for row in c.fetchall()), 'DEACTIVATION_SESSIONS_UNCONFIRMED')
            c.execute("SELECT NOT rolcanlogin AND NOT EXISTS(SELECT 1 FROM pg_stat_activity WHERE usename='ipgo_d0_bootstrap') FROM pg_roles WHERE rolname='ipgo_d0_bootstrap'")
            require(c.fetchone() == (True,), 'DEACTIVATION_SESSIONS_UNCONFIRMED')
    except Exception:
        raise GateError('INSTALL_DEACTIVATION_UNCONFIRMED') from None
    return {'status': 'DEACTIVATED', 'operationId': manifest['operationId'], 'businessWrites': 0}


def install(manifest, database):
    # SQL commit 후 Secret/LOGIN/probe까지 같은 session lock을 유지한다.
    # 응답 유실된 installer가 복구 NOLOGIN 뒤 다시 활성화하는 경쟁을 방지한다.
    conn = database.connection
    with conn.cursor() as c:
        c.execute("SELECT pg_advisory_lock(hashtextextended('ipgo:d0:installation',0))")
    try:
        return _install(manifest, database)
    finally:
        with conn.cursor() as c:
            c.execute("SELECT pg_advisory_unlock(hashtextextended('ipgo:d0:installation',0))")


def _install(manifest, database):
    from psycopg import sql
    import boto3
    conn = database.connection
    secret_arn = os.environ.get('D0_SECRET_ARN', '')
    require(secret_arn.startswith('arn:aws:secretsmanager:ap-northeast-2:')
            and ':secret:/ipgo/dev/d0-bootstrap-' in secret_arn, 'INSTALL_SECRET_BOUNDARY')
    with conn.cursor() as c:
        c.execute("SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname IN ('ipgo_d0_owner','ipgo_d0_bootstrap')) OR EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='d0_ops')")
        require(c.fetchone() == (False,), 'PREEXISTING_OBJECT_REVIEW_REQUIRED')
    before = business_snapshot(conn)
    with conn.cursor() as c:
        c.execute("SELECT set_config('d0.install_operation_id',%s,false)", (manifest['operationId'],))
        # advisory lock内にも衝突検査がある。部分/過去インストールの再利用はしない。
        c.execute((Path(__file__).resolve().parents[2] / 'database/bootstrap/create_d0_ops.sql').read_text())
    try:
        require(business_snapshot(conn) == before, 'INSTALL_BUSINESS_ROWS_CHANGED')
        password = secrets.token_urlsafe(40)
        boto3.client('secretsmanager', region_name='ap-northeast-2').put_secret_value(
            SecretId=secret_arn, ClientRequestToken=manifest['operationId'],
            SecretString=json.dumps({'username': 'ipgo_d0_bootstrap', 'password': password}))
        with conn.cursor() as c:
            c.execute(sql.SQL('ALTER ROLE ipgo_d0_bootstrap PASSWORD {} LOGIN').format(sql.Literal(password)))
        import psycopg
        from runtime import CA_FILE
        with psycopg.connect(host=manifest['dbHost'], port=5432, dbname=manifest['dbName'],
                            user='ipgo_d0_bootstrap', password=password, sslmode='verify-full',
                            sslrootcert=str(CA_FILE), connect_timeout=10) as probe:
            with probe.cursor() as c:
                c.execute('SELECT d0_ops.preflight(NULL)')
                state = c.fetchone()[0]
                require(state['privilegeBoundaryOk'] is True, 'INSTALL_PROBE')
            for query in (
                'SELECT id FROM public.users LIMIT 0',
                'SELECT password_hash FROM public.users LIMIT 0',
                'UPDATE public.users SET id=id WHERE false',
                'DELETE FROM public.users WHERE false',
                'CREATE TABLE public.d0_deny_probe(id integer)',
                'SET LOCAL ROLE ipgo_server',
            ):
                try:
                    with probe.transaction(force_rollback=True):
                        with probe.cursor() as c:
                            c.execute(query)
                except psycopg.errors.InsufficientPrivilege:
                    continue
                require(False, 'INSTALL_DENY_PROBE_ALLOWED')
        require(business_snapshot(conn) == before, 'INSTALL_BUSINESS_ROWS_CHANGED')
        return {'status': 'INSTALLED', 'operationId': manifest['operationId'], 'businessWrites': 0,
                'existingRowsUnchanged': True}
    except Exception:
        try:
            deactivate(manifest, database)
        except Exception:
            raise GateError('INSTALL_DEACTIVATION_UNCONFIRMED') from None
        raise
