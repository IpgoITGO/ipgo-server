import json
import unittest
from unittest.mock import patch
from contract import GateError
from install_gate import verify_install_job


class InstallGateTest(unittest.TestCase):
    def inputs(self,kind):
        account='123456789012';image=account+'.dkr.ecr.ap-northeast-2.amazonaws.com/ipgo-dev/database@sha256:'+'a'*64
        manifest={'kind':kind,'workflowSha':'a'*40,'helperSha':'b'*40,'accountId':account,
                  'taskDefinitionArn':f'arn:aws:ecs:ap-northeast-2:{account}:task-definition/ipgo-dev-d0-install:2',
                  'imageUri':image,'subnetIds':['subnet-abcd'],'securityGroupIds':['sg-abcd']}
        cfg={'task_definition_arn':manifest['taskDefinitionArn'],'secret_arn':f'arn:aws:secretsmanager:ap-northeast-2:{account}:secret:/ipgo/dev/d0-bootstrap-Abc123',
             'task_role_arn':f'arn:aws:iam::{account}:role/ipgo-dev-d0-install-task',
             'execution_role_arn':f'arn:aws:iam::{account}:role/ipgo-dev-database-bootstrap-execution',
             'log_group':'/ipgo/ipgo-dev/database','cluster_arn':f'arn:aws:ecs:ap-northeast-2:{account}:cluster/ipgo-dev-cluster',
             'image_uri':image,'subnet_ids':manifest['subnetIds'],'security_group_ids':manifest['securityGroupIds']}
        env={'GITHUB_REPOSITORY':'IpgoITGO/ipgo-infra','GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_REF':'refs/heads/dev',
             'GITHUB_RUN_ATTEMPT':'1','D0_INSTALL_ENABLED':'true','CONFIRMATION':'DEACTIVATE_D0' if kind=='deactivate' else 'INSTALL_D0',
             'OPERATION':kind,'EXPECTED_SHA':'a'*40,'GITHUB_SHA':'a'*40,'HELPER_SHA':'b'*40,'AWS_ACCOUNT_ID':account,
             'D0_SIGNED_MANIFEST':'e30=','D0_INSTALL_JSON':json.dumps(cfg)}
        return manifest,env

    def test_distinct_signed_kind_and_confirmation_are_required(self):
        for kind in ('install','deactivate'):
            m,e=self.inputs(kind)
            with patch('install_gate.verify_envelope',return_value=m):
                self.assertEqual(m,verify_install_job(e)[0])
                for changes in [{'OPERATION':'prepare'},{'CONFIRMATION':'INSTALL_D0' if kind=='deactivate' else 'DEACTIVATE_D0'},
                                {'D0_INSTALL_ENABLED':'false'},{'HELPER_SHA':'c'*40},{'GITHUB_RUN_ATTEMPT':'2'}]:
                    with self.subTest(kind=kind,changes=changes),self.assertRaises(GateError):verify_install_job(dict(e,**changes))
            with patch('install_gate.verify_envelope',return_value=dict(m,kind='install' if kind=='deactivate' else 'deactivate')):
                with self.assertRaises(GateError):verify_install_job(e)
