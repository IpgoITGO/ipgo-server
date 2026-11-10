import contextlib
import io
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from contract import GateError
from install import install, deactivate


@patch('install.business_snapshot', return_value={'users': (1,'fixed')})
class InstallTest(unittest.TestCase):
    manifest={'dbHost':'ipgo-dev.abcdefgh.ap-northeast-2.rds.amazonaws.com','dbName':'ipgo',
              'operationId':'dc2d35e0-0b2e-4197-a098-92358d9226be'}
    env={'D0_SECRET_ARN':'arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:/ipgo/dev/d0-bootstrap-Abc123'}

    def database(self,already=False):
        c=MagicMock();c.fetchone.side_effect=[(already,),('ipgo:d0:install:'+self.manifest['operationId'],),(True,)]
        c.fetchall.return_value=[]
        conn=MagicMock();conn.cursor.return_value.__enter__.return_value=c
        return SimpleNamespace(connection=conn),c

    @patch.dict('os.environ',env)
    @patch('boto3.client')
    def test_any_existing_role_or_schema_stops_before_secret_and_snapshot(self,client,snapshot):
        db,c=self.database(already=True)
        with self.assertRaisesRegex(GateError,'PREEXISTING_OBJECT'):
            install(self.manifest,db)
        client.assert_not_called();snapshot.assert_not_called()
        self.assertEqual(3,len(c.execute.call_args_list))

    @patch.dict('os.environ',env)
    @patch('boto3.client')
    def test_secret_failure_deactivates_new_login_without_logging_value(self,client,snapshot):
        client.return_value.put_secret_value.side_effect=RuntimeError('private-failure-value')
        db,c=self.database();output=io.StringIO()
        with contextlib.redirect_stdout(output),self.assertRaises(RuntimeError):install(self.manifest,db)
        self.assertEqual('',output.getvalue())
        self.assertFalse(any('PASSWORD' in str(call) for call in c.execute.call_args_list))
        self.assertIn('ALTER ROLE ipgo_d0_bootstrap NOLOGIN',[call.args[0] for call in c.execute.call_args_list])

    @patch.dict('os.environ',env)
    @patch('boto3.client')
    @patch('psycopg.connect')
    def test_probe_failure_deactivates_only_new_login(self,connect,client,snapshot):
        connect.side_effect=RuntimeError('probe-unavailable');db,c=self.database()
        with self.assertRaises(RuntimeError):install(self.manifest,db)
        self.assertIn('ALTER ROLE ipgo_d0_bootstrap NOLOGIN',[call.args[0] for call in c.execute.call_args_list])
        self.assertEqual(1,client.return_value.put_secret_value.call_count)

    @patch.dict('os.environ',env)
    @patch('boto3.client')
    @patch('psycopg.connect')
    def test_success_uses_real_before_after_comparison_not_preflight_true(self,connect,client,snapshot):
        probe=connect.return_value.__enter__.return_value.cursor.return_value.__enter__.return_value
        probe.fetchone.return_value=({'oldRowsUnchanged':False,'privilegeBoundaryOk':True},)
        from psycopg.errors import InsufficientPrivilege
        probe.execute.side_effect=lambda q: None if q=='SELECT d0_ops.preflight(NULL)' else (_ for _ in ()).throw(InsufficientPrivilege())
        db,c=self.database();result=install(self.manifest,db)
        self.assertTrue(result['existingRowsUnchanged']);self.assertEqual(3,snapshot.call_count)
        self.assertNotIn('password',str(result));self.assertEqual('INSTALLED',result['status'])

    @patch.dict('os.environ',env)
    @patch('boto3.client')
    def test_actual_business_hash_drift_stops_before_secret_activation(self,client,snapshot):
        snapshot.side_effect=[{'users':(1,'old')},{'users':(1,'new')}];db,c=self.database()
        with self.assertRaisesRegex(GateError,'BUSINESS_ROWS_CHANGED'):install(self.manifest,db)
        client.assert_not_called()

    @patch.dict('os.environ',env)
    @patch('boto3.client')
    def test_unknown_activation_response_still_deactivates(self,client,snapshot):
        db,c=self.database()
        def execute(q,*args,**kwargs):
            if 'PASSWORD' in str(q):raise RuntimeError('response-lost')
        c.execute.side_effect=execute
        with self.assertRaises(RuntimeError):install(self.manifest,db)
        self.assertIn('ALTER ROLE ipgo_d0_bootstrap NOLOGIN',[call.args[0] for call in c.execute.call_args_list])

    def test_recovery_refuses_other_operation_before_nologin(self,snapshot):
        db,c=self.database();c.fetchone.side_effect=[('other-operation',)]
        with self.assertRaisesRegex(GateError,'OPERATION_MISMATCH'):deactivate(self.manifest,db)
        self.assertFalse(any('ALTER ROLE' in str(call) for call in c.execute.call_args_list))

    def test_recovery_unconfirmed_sessions_are_not_success(self,snapshot):
        db,c=self.database();c.fetchone.side_effect=[('ipgo:d0:install:'+self.manifest['operationId'],)]
        c.fetchall.return_value=[(False,)]
        with self.assertRaisesRegex(GateError,'DEACTIVATION_UNCONFIRMED'):deactivate(self.manifest,db)
