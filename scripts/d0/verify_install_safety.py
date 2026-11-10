"""폐기 PG16 Unix socket에서만. AWS 호출/운영 DB 연결 없음."""
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import psycopg
from contract import GateError
from install import install, deactivate, business_snapshot

SOCKET=Path(sys.argv.pop(1)).resolve()
if not SOCKET.name.startswith('ipgo-d0-socket-') or not SOCKET.is_dir():
    raise SystemExit('폐기 socket만 허용')
CONNECT=psycopg.connect
M={'dbHost':'ipgo-dev.abcdefgh.ap-northeast-2.rds.amazonaws.com','dbName':'ipgo',
   'operationId':'dc2d35e0-0b2e-4197-a098-92358d9226be'}
SQL=(Path(__file__).resolve().parents[2]/'database/bootstrap/create_d0_ops.sql').read_text()


def connect(user):return CONNECT(host=str(SOCKET),dbname='ipgo',user=user,autocommit=True)


class InstallationSafetyTest(unittest.TestCase):
    @patch.dict('os.environ',{'D0_SECRET_ARN':'arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:/ipgo/dev/d0-bootstrap-Abc123'})
    def test_complete_local_install_failure_boundaries_and_recovery(self):
        with connect('postgres') as root, connect('d0_local_admin') as admin:
            db=SimpleNamespace(connection=admin)
            # 역할 및 스키마 충돌은 advisory lock 안에서도 신규 DDL 전에 거부한다.
            for create,drop in [('CREATE ROLE ipgo_d0_owner','DROP ROLE ipgo_d0_owner'),
                                ('CREATE SCHEMA d0_ops','DROP SCHEMA d0_ops')]:
                root.execute(create)
                admin.execute("SELECT set_config('d0.install_operation_id',%s,false)",(M['operationId'],))
                with self.assertRaisesRegex(psycopg.errors.RaiseException,'PREEXISTING'):
                    admin.execute(SQL)
                admin.execute('ROLLBACK')
                self.assertIsNone(root.execute("SELECT oid FROM pg_roles WHERE rolname='ipgo_d0_bootstrap'").fetchone())
                root.execute(drop)
            # migrator 관리 권한 부족 시 전체 transaction rollback, 기존 membership 보존.
            root.execute('REVOKE ipgo_migrator FROM d0_local_admin')
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):admin.execute(SQL)
            admin.execute('ROLLBACK')
            self.assertIsNone(root.execute("SELECT oid FROM pg_roles WHERE rolname='ipgo_d0_owner'").fetchone())
            root.execute('GRANT ipgo_migrator TO d0_local_admin WITH ADMIN TRUE, INHERIT FALSE, SET FALSE')
            original=admin.execute("SELECT admin_option,inherit_option,set_option FROM pg_auth_members WHERE roleid='ipgo_migrator'::regrole AND member=current_user::regrole").fetchall()
            before=business_snapshot(admin)
            # Secret API는 mock. TLS 인수를 검사한 probe만 폐기 Unix socket으로 대체한다.
            def local_probe(**kw):
                self.assertEqual('verify-full',kw['sslmode']);self.assertEqual('ipgo_d0_bootstrap',kw['user'])
                return connect('ipgo_d0_bootstrap')
            with patch.dict('os.environ',{'D0_SECRET_ARN':'arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:/ipgo/dev/d0-bootstrap-Abc123'}),patch('boto3.client') as aws,patch('psycopg.connect',side_effect=local_probe):
                def secret_write(**kwargs):
                    self.assertFalse(root.execute("SELECT pg_try_advisory_lock(hashtextextended('ipgo:d0:installation',0))").fetchone()[0])
                aws.return_value.put_secret_value.side_effect=secret_write
                result=install(M,db)
                self.assertTrue(result['existingRowsUnchanged']);self.assertEqual(1,aws.return_value.put_secret_value.call_count)
            self.assertTrue(root.execute("SELECT pg_try_advisory_lock(hashtextextended('ipgo:d0:installation',0))").fetchone()[0])
            root.execute("SELECT pg_advisory_unlock(hashtextextended('ipgo:d0:installation',0))")
            self.assertEqual(before,business_snapshot(admin))
            self.assertEqual(original,admin.execute("SELECT admin_option,inherit_option,set_option FROM pg_auth_members WHERE roleid='ipgo_migrator'::regrole AND member=current_user::regrole").fetchall())
            with self.assertRaisesRegex(GateError,'PREEXISTING'):install(M,db)
            with self.assertRaisesRegex(GateError,'OPERATION_MISMATCH'):
                deactivate(dict(M,operationId='00000000-0000-4000-8000-000000000000'),db)
            self.assertTrue(root.execute("SELECT rolcanlogin FROM pg_roles WHERE rolname='ipgo_d0_bootstrap'").fetchone()[0])
            root.execute('GRANT pg_signal_backend TO d0_local_admin')  # 폐기 fixture에만 부여. 실환경은 사전 확인한다.
            with connect('ipgo_d0_bootstrap') as own,connect('ipgo_server') as server:
                own_pid=own.info.backend_pid
                self.assertEqual('DEACTIVATED',deactivate(M,db)['status'])
                self.assertFalse(root.execute("SELECT rolcanlogin FROM pg_roles WHERE rolname='ipgo_d0_bootstrap'").fetchone()[0])
                self.assertIsNone(root.execute('SELECT pid FROM pg_stat_activity WHERE pid=%s',(own_pid,)).fetchone())
                self.assertEqual((1,),server.execute('SELECT 1').fetchone())
            self.assertEqual('DEACTIVATED',deactivate(M,db)['status'])  # 같은 operation의 복구는 재확인 가능
            self.assertEqual(before,business_snapshot(admin))


if __name__=='__main__':unittest.main(verbosity=2)
