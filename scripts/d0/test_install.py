import contextlib
import io
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from contract import GateError
from install import install


class InstallTest(unittest.TestCase):
    manifest={'dbHost':'ipgo-dev.abcdefgh.ap-northeast-2.rds.amazonaws.com','dbName':'ipgo',
              'operationId':'dc2d35e0-0b2e-4197-a098-92358d9226be'}
    env={'D0_SECRET_ARN':'arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:/ipgo/dev/d0-bootstrap-Abc123'}

    def database(self,already=False):
        c=MagicMock();c.fetchone.return_value=(True,) if already else None
        conn=MagicMock();conn.cursor.return_value.__enter__.return_value=c
        return SimpleNamespace(connection=conn),c

    @patch.dict('os.environ',env)
    @patch('boto3.client')
    def test_preexisting_live_login_is_not_rotated(self,client):
        db,c=self.database(already=True)
        with self.assertRaisesRegex(GateError,'ALREADY_INSTALLED'):
            install(self.manifest,db)
        client.assert_not_called()
        self.assertEqual(1,len(c.execute.call_args_list))

    @patch.dict('os.environ',env)
    @patch('boto3.client')
    def test_secret_failure_keeps_new_login_inactive_and_does_not_log_secret(self,client):
        client.return_value.put_secret_value.side_effect=RuntimeError('private-failure-value')
        db,c=self.database()
        output=io.StringIO()
        with contextlib.redirect_stdout(output),self.assertRaises(RuntimeError):
            install(self.manifest,db)
        self.assertEqual('',output.getvalue())
        self.assertFalse(any('PASSWORD' in str(call) for call in c.execute.call_args_list))
        self.assertEqual(self.env['D0_SECRET_ARN'],client.return_value.put_secret_value.call_args.kwargs['SecretId'])

    @patch.dict('os.environ',env)
    @patch('boto3.client')
    @patch('psycopg.connect')
    def test_probe_failure_deactivates_only_the_new_login(self,connect,client):
        connect.side_effect=RuntimeError('probe-unavailable')
        db,c=self.database()
        with self.assertRaises(RuntimeError):
            install(self.manifest,db)
        self.assertEqual('ALTER ROLE ipgo_d0_bootstrap NOLOGIN',c.execute.call_args.args[0])
        self.assertEqual(1,client.return_value.put_secret_value.call_count)

    @patch.dict('os.environ',env)
    @patch('boto3.client')
    @patch('psycopg.connect')
    def test_success_keeps_credentials_out_of_result(self,connect,client):
        connect.return_value.__enter__.return_value.cursor.return_value.__enter__.return_value.fetchone.return_value = ({'oldRowsUnchanged': True, 'privilegeBoundaryOk': True},)
        from psycopg.errors import InsufficientPrivilege
        probe_cursor=connect.return_value.__enter__.return_value.cursor.return_value.__enter__.return_value
        probe_cursor.execute.side_effect=lambda query: None if query=='SELECT d0_ops.preflight(NULL)' else (_ for _ in ()).throw(InsufficientPrivilege())
        db,c=self.database()
        result=install(self.manifest,db)
        self.assertEqual({'status':'INSTALLED','operationId':self.manifest['operationId'],'businessWrites':0},result)
        self.assertEqual(1,client.return_value.put_secret_value.call_count)

    @patch.dict('os.environ',env)
    @patch('boto3.client')
    def test_unknown_activation_response_still_deactivates_new_login(self,client):
        db,c=self.database()
        c.execute.side_effect=[None,None,RuntimeError('response-lost'),None]
        with self.assertRaises(RuntimeError):
            install(self.manifest,db)
        self.assertEqual('ALTER ROLE ipgo_d0_bootstrap NOLOGIN',c.execute.call_args.args[0])

    @patch.dict('os.environ',env)
    @patch('boto3.client')
    @patch('psycopg.connect')
    def test_unexpected_direct_db_permission_deactivates_login(self,connect,client):
        connect.return_value.__enter__.return_value.cursor.return_value.__enter__.return_value.fetchone.return_value=({'oldRowsUnchanged':True,'privilegeBoundaryOk':True},)
        db,c=self.database()
        with self.assertRaisesRegex(GateError,'INSTALL_DENY_PROBE_ALLOWED'):
            install(self.manifest,db)
        self.assertEqual('ALTER ROLE ipgo_d0_bootstrap NOLOGIN',c.execute.call_args.args[0])
