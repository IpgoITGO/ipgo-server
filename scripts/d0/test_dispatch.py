import base64
import copy
import json
import unittest
from unittest.mock import patch

from contract import GateError
from dispatch import dispatch
from gate import verify_job


def configuration():
    account = '123456789012'
    return {'enabled': True, 'runner_role_arn': f'arn:aws:iam::{account}:role/ipgo-dev-d0-runner-oidc',
            'execution_role_arn': f'arn:aws:iam::{account}:role/ipgo-dev-d0-execution',
            'task_definition_arn': f'arn:aws:ecs:ap-northeast-2:{account}:task-definition/ipgo-dev-d0-validation:7',
            'cluster_arn': f'arn:aws:ecs:ap-northeast-2:{account}:cluster/ipgo-dev-cluster',
            'image_uri': f'{account}.dkr.ecr.ap-northeast-2.amazonaws.com/ipgo-dev/database@sha256:' + 'a' * 64,
            'subnet_ids': ['subnet-0abcd'], 'security_group_ids': ['sg-0abcd'], 'log_group': '/ipgo/dev/d0-validation'}

def approval():
    cfg = configuration()
    return {'kind': 'prepare', 'accountId': '123456789012', 'workflowSha': 'a' * 40, 'helperSha': 'a' * 40,
            'taskDefinitionArn': cfg['task_definition_arn'], 'imageUri': cfg['image_uri'],
            'subnetIds': cfg['subnet_ids'], 'securityGroupIds': cfg['security_group_ids']}

class GateTest(unittest.TestCase):
    def environment(self):
        return {'GITHUB_REPOSITORY': 'IpgoITGO/ipgo-server', 'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/dev',
                'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_SHA': 'a' * 40, 'EXPECTED_SHA': 'a' * 40,
                'D0_MODE': 'prepare', 'CONFIRMATION': 'PREPARE_D0', 'D0_ENABLED': 'true',
                'D0_SIGNED_MANIFEST': base64.b64encode(b'{}').decode(),
                'D0_RUNTIME_JSON': json.dumps(configuration())}

    @patch('gate.verify_envelope', return_value=approval())
    def test_job_boundary_is_before_aws_credentials(self, signature):
        env = self.environment()
        self.assertEqual(configuration(), verify_job(env)[1])
        for key, value in [('GITHUB_REPOSITORY', 'other/repo'), ('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_REF', 'refs/heads/main'),
                           ('GITHUB_RUN_ATTEMPT', '2'), ('EXPECTED_SHA', 'b' * 40),
                           ('D0_ENABLED', ''), ('D0_MODE', 'install'), ('CONFIRMATION', 'unreviewed')]:
            with self.subTest(key=key), self.assertRaises(GateError):
                verify_job(dict(env, **{key: value}))

    @patch('gate.verify_envelope', return_value=approval())
    def test_config_cannot_select_broad_roles_mutable_images_or_unscoped_targets(self, signature):
        for key, value in [('runner_role_arn', 'arn:aws:iam::123456789012:role/admin'),
                           ('execution_role_arn', 'arn:aws:iam::123456789012:role/master'),
                           ('task_definition_arn', 'ipgo-dev-d0-validation'),
                           ('image_uri', configuration()['image_uri'].split('@')[0] + ':latest'),
                           ('cluster_arn', 'arn:aws:ecs:ap-northeast-2:123456789012:cluster/prod'),
                           ('security_group_ids', []), ('enabled', False), ('sql', 'select')]:
            cfg = configuration()
            cfg[key] = value
            env = self.environment()
            env['D0_RUNTIME_JSON'] = json.dumps(cfg)
            with self.subTest(key=key), self.assertRaises(GateError):
                verify_job(env)


class FakeEcs:
    def __init__(self, *, digest=None, fail=False):
        self.runs, self.stops = [], []
        self.digest, self.fail = digest or 'sha256:' + 'a' * 64, fail
        self.arn = configuration()['cluster_arn'].replace(':cluster/', ':task/') + '/abcdef'

    def describe_services(self, **args):
        return {'services': [{'serviceName': name, 'taskDefinition': f'arn:aws:ecs:ap-northeast-2:123456789012:task-definition/{name}:{td}', 'desiredCount': 1,
                              'runningCount': 1, 'pendingCount': 0, 'deployments': [{'rolloutState': 'COMPLETED'}]}
                             for name, td in [('ipgo-dev-onebox-server', 32), ('ipgo-dev-onebox-ai-api', 19)]]}

    def run_task(self, **args):
        self.runs.append(args)
        return {'tasks': [{'taskArn': self.arn}], 'failures': []}

    def describe_tasks(self, **args):
        return {'tasks': [{'taskDefinitionArn': configuration()['task_definition_arn'], 'lastStatus': 'STOPPED',
                           'containers': [{'exitCode': 1 if self.fail else 0, 'imageDigest': self.digest}]}]}

    def stop_task(self, **args):
        self.stops.append(args)


class FakeLogs:
    def __init__(self, status='VERIFIED', extra=None):
        self.status, self.extra = status, extra or {}

    def get_log_events(self, **args):
        value = {'status': self.status, 'operationId': DispatchTest.manifest['operationId'],
                 'userId': 1, 'productId': 2, 'uploadId': 3, 'receiptIds': [4,5,6],
                 'sealedCredentials': base64.b64encode(b'x' * 384).decode(),
                 'incrementalLlmUsd': 0, 'existingRowsUnchanged': True, 'llmCalls': 0, **self.extra}
        return {'events': [{'message': json.dumps(value)}]}


class DispatchTest(unittest.TestCase):
    manifest = {'kind': 'prepare', 'operationId': 'dc2d35e0-0b2e-4197-a098-92358d9226be'}

    def test_one_task_exact_definition_no_shell_no_exec_and_idempotency_token(self):
        ecs = FakeEcs()
        result = dispatch(ecs, FakeLogs(), self.manifest, configuration(), 'signed-envelope')
        self.assertEqual('VERIFIED', result['status'])
        self.assertEqual(1, len(ecs.runs))
        call = ecs.runs[0]
        self.assertEqual(1, call['count'])
        self.assertFalse(call['enableExecuteCommand'])
        self.assertEqual(['prepare'], call['overrides']['containerOverrides'][0]['command'])
        self.assertEqual(configuration()['task_definition_arn'], call['taskDefinition'])
        self.assertEqual(64, len(call['clientToken']))
        self.assertEqual([], ecs.stops)

    def test_old_or_drifted_services_stop_before_run_task(self):
        from unittest.mock import patch
        for td in (31,33):
            ecs=FakeEcs();reply=ecs.describe_services()
            reply['services'][0]['taskDefinition']=reply['services'][0]['taskDefinition'].rsplit(':',1)[0]+':'+str(td)
            with patch.object(ecs,'describe_services',return_value=reply),self.assertRaisesRegex(GateError,'BASELINE'):
                dispatch(ecs,FakeLogs(),self.manifest,configuration(),'signed-envelope')
            self.assertEqual([],ecs.runs)

    def test_recovery_uses_install_task_and_does_not_require_healthy_app(self):
        from unittest.mock import patch
        ecs=FakeEcs();m=dict(self.manifest,kind='deactivate')
        class RecoveryLogs:
            def get_log_events(self,**kw):
                self.args=kw
                return {'events':[{'message':json.dumps({'status':'DEACTIVATED','operationId':m['operationId'],'businessWrites':0})}]}
        logs=RecoveryLogs()
        with patch.object(ecs,'describe_services',side_effect=AssertionError('recovery must remain available')):
            result=dispatch(ecs,logs,m,configuration(),'signed-envelope')
        self.assertEqual('DEACTIVATED',result['status'])
        self.assertEqual(['deactivate'],ecs.runs[0]['overrides']['containerOverrides'][0]['command'])
        self.assertEqual('d0-install',ecs.runs[0]['tags'][0]['value'])
        self.assertTrue(logs.args['logStreamName'].startswith('d0-install/'))

    def test_failed_exit_digest_or_log_payload_never_starts_another_task(self):
        for ecs, logs in [(FakeEcs(fail=True), FakeLogs()), (FakeEcs(digest='sha256:' + 'b' * 64), FakeLogs()),
                          (FakeEcs(), FakeLogs(status='ALREADY_SEEDED')),
                          (FakeEcs(), FakeLogs(extra={'password': 'must-not-be-artifact'}))]:
            with self.subTest(case=ecs), self.assertRaises(GateError):
                dispatch(ecs, logs, self.manifest, configuration(), 'signed-envelope')
            self.assertEqual(1, len(ecs.runs))

    def test_timeout_stops_only_the_task_created_by_this_invocation(self):
        ecs = FakeEcs()
        times = iter([0, 700])
        with self.assertRaisesRegex(GateError, 'TASK_WAIT_TIMEOUT'):
            dispatch(ecs, FakeLogs(), self.manifest, configuration(), 'signed-envelope', clock=lambda: next(times))
        self.assertEqual([ecs.arn], [x['task'] for x in ecs.stops])
        self.assertEqual(1, len(ecs.runs))


if __name__ == '__main__':
    unittest.main()
