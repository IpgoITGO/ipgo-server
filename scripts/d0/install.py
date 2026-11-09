"""설치 전용. 새 D0 자격증명만 생성하며 기존 role 비밀번호는 바꾸지 않는다."""
import json
import os
import secrets
from pathlib import Path

from contract import require


def install(manifest, database):
    from psycopg import sql
    import boto3
    conn = database.connection
    secret_arn = os.environ.get('D0_SECRET_ARN', '')
    require(secret_arn.startswith('arn:aws:secretsmanager:ap-northeast-2:')
            and ':secret:/ipgo/dev/d0-bootstrap-' in secret_arn, 'INSTALL_SECRET_BOUNDARY')
    with conn.cursor() as c:
        c.execute("SELECT rolcanlogin FROM pg_roles WHERE rolname='ipgo_d0_bootstrap'")
        row = c.fetchone()
        require(not row or row[0] is False, 'ALREADY_INSTALLED_REVIEW_REQUIRED')
        c.execute((Path(__file__).resolve().parents[2] / 'database/bootstrap/create_d0_ops.sql').read_text())
    password = secrets.token_urlsafe(40)
    activated = False
    try:
        boto3.client('secretsmanager', region_name='ap-northeast-2').put_secret_value(
            SecretId=secret_arn, ClientRequestToken=manifest['operationId'],
            SecretString=json.dumps({'username': 'ipgo_d0_bootstrap', 'password': password}))
        # ALTER 응답 유실도 활성화 가능성으로 취급한다.
        activated = True
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
                require(state['oldRowsUnchanged'] is True and state['privilegeBoundaryOk'] is True, 'INSTALL_PROBE')
            for query in (
                'SELECT id FROM public.users LIMIT 0',
                'SELECT password_hash FROM public.users LIMIT 0',
                'UPDATE public.users SET id=id WHERE false',
                'DELETE FROM public.users WHERE false',
                'CREATE TABLE public.d0_deny_probe(id integer)',
                'SET LOCAL ROLE ipgo_server',
            ):
                try:
                    # 예상 밖 허용도 영구 변경하지 않도록 항상 rollback한다.
                    with probe.transaction(force_rollback=True):
                        with probe.cursor() as c:
                            c.execute(query)
                except psycopg.errors.InsufficientPrivilege:
                    continue
                require(False, 'INSTALL_DENY_PROBE_ALLOWED')
        return {'status': 'INSTALLED', 'operationId': manifest['operationId'], 'businessWrites': 0}
    except Exception:
        if activated:
            try:
                with conn.cursor() as c:
                    c.execute('ALTER ROLE ipgo_d0_bootstrap NOLOGIN')
            except Exception:
                from contract import GateError
                raise GateError('INSTALL_DEACTIVATION_UNCONFIRMED') from None
        raise
