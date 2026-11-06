from datetime import datetime, timedelta, timezone
import copy
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/deployment/render_dev_task.py'
spec = importlib.util.spec_from_file_location('render_dev_task', SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
SHA = 'a' * 40
ACCOUNT = '123456789012'
IMAGE = f'{ACCOUNT}.dkr.ecr.ap-northeast-2.amazonaws.com/ipgo-dev/server:{SHA}-arm64'


class DevDeploymentTests(unittest.TestCase):
    def test_single_result_is_disabled_when_new_inputs_are_absent(self):
        self.assertFalse(module.settings(self.env)['single'])
        self.task['containerDefinitions'][0]['environment'].append({'name':'IPGO_INSIGHT_SINGLE_RESULT_ENABLED','value':'true'})
        values={e['name']:e['value'] for e in self.rendered()['containerDefinitions'][0]['environment']}
        self.assertEqual(values['IPGO_INSIGHT_SINGLE_RESULT_ENABLED'],'false')

    def test_single_result_can_be_approved_without_enabling_global_generation(self):
        self.env.update(INSIGHT_ENABLED='false',INSIGHT_APPROVED='false',INSIGHT_WORKER_ENABLED='false',
                        SINGLE_RESULT_INSIGHT_ENABLED='true',SINGLE_RESULT_INSIGHT_APPROVED='true')
        values={e['name']:e['value'] for e in self.rendered()['containerDefinitions'][0]['environment']}
        self.assertEqual(values['IPGO_INSIGHT_SINGLE_RESULT_ENABLED'],'true')
        self.assertEqual(values['IPGO_INSIGHT_ENABLED'],'false')
        self.assertEqual(values['IPGO_INSIGHT_WORKER_ENABLED'],'false')

    def test_single_result_rejects_missing_approval_global_worker_or_engine_off(self):
        base=dict(self.env,INSIGHT_ENABLED='false',INSIGHT_WORKER_ENABLED='false',
                  SINGLE_RESULT_INSIGHT_ENABLED='true',SINGLE_RESULT_INSIGHT_APPROVED='true')
        for change in [dict(SINGLE_RESULT_INSIGHT_APPROVED='false'),dict(INSIGHT_ENABLED='true'),
                       dict(INSIGHT_WORKER_ENABLED='true'),dict(ENGINE_ENABLED='false')]:
            with self.subTest(change=change), self.assertRaises(module.InvalidDeployment):
                module.settings(dict(base,**change))

    def test_single_result_controls_are_strict_and_explicit_in_workflow(self):
        for name in ['SINGLE_RESULT_INSIGHT_ENABLED','SINGLE_RESULT_INSIGHT_APPROVED']:
            with self.assertRaises(module.InvalidDeployment): module.settings(dict(self.env,**{name:'TRUE'}))
        workflow=(ROOT/'.github/workflows/deploy-dev.yml').read_text()
        self.assertIn('SINGLE_RESULT_INSIGHT_ENABLED: ${{ inputs.single_result_insight_enabled }}',workflow)
        self.assertIn('SINGLE_RESULT_INSIGHT_APPROVED: ${{ inputs.single_result_insight_approved }}',workflow)
    def test_batch_requires_separate_approval_and_excludes_other_modes(self):
        base=dict(self.env,INSIGHT_ENABLED='false',INSIGHT_WORKER_ENABLED='false',
                  BATCH_INSIGHT_ENABLED='true',BATCH_INSIGHT_APPROVED='true')
        self.assertTrue(module.settings(base)['batch'])
        for change in [dict(BATCH_INSIGHT_APPROVED='false'),dict(INSIGHT_ENABLED='true'),
                       dict(INSIGHT_WORKER_ENABLED='true'),dict(ENGINE_ENABLED='false'),
                       dict(SINGLE_RESULT_INSIGHT_ENABLED='true',SINGLE_RESULT_INSIGHT_APPROVED='true')]:
            with self.subTest(change=change), self.assertRaises(module.InvalidDeployment):
                module.settings(dict(base,**change))

    def test_batch_default_off_overwrites_previous_enabled_value(self):
        self.task['containerDefinitions'][0]['environment'].append({'name':'IPGO_INSIGHT_BATCH_ENABLED','value':'true'})
        values={e['name']:e['value'] for e in self.rendered()['containerDefinitions'][0]['environment']}
        self.assertEqual(values['IPGO_INSIGHT_BATCH_ENABLED'],'false')
        env=dict(self.env,INSIGHT_ENABLED='false',INSIGHT_WORKER_ENABLED='false',
                 BATCH_INSIGHT_ENABLED='true',BATCH_INSIGHT_APPROVED='true')
        values={e['name']:e['value'] for e in module.render(self.task,IMAGE,env)['containerDefinitions'][0]['environment']}
        self.assertEqual(values['IPGO_INSIGHT_BATCH_ENABLED'],'true')
        self.assertEqual(values['IPGO_INSIGHT_SINGLE_RESULT_ENABLED'],'false')
        self.assertEqual(values['IPGO_INSIGHT_ENABLED'],'false')
        self.assertEqual(values['IPGO_INSIGHT_WORKER_ENABLED'],'false')

    def test_batch_controls_strict_and_explicit_in_workflow(self):
        workflow=(ROOT/'.github/workflows/deploy-dev.yml').read_text()
        for name,field in [('BATCH_INSIGHT_ENABLED','batch_insight_enabled'),('BATCH_INSIGHT_APPROVED','batch_insight_approved')]:
            self.assertIn(name+': ${{ inputs.'+field+' }}',workflow)
            with self.assertRaises(module.InvalidDeployment): module.settings(dict(self.env,**{name:'TRUE'}))

    def test_insight_window_is_required_and_rendered(self):
        for value in ('', 'bad', '2000-01-01T00:00:00Z', '2026-11-06T08:30:00'):
            with self.assertRaises(module.InvalidDeployment):
                module.settings(dict(self.env, INSIGHT_EXPIRES_AT=value))
        values={e['name']:e['value'] for e in self.rendered()['containerDefinitions'][0]['environment']}
        self.assertEqual(values['IPGO_INSIGHT_DEMO_EXPIRES_AT'],self.env['INSIGHT_EXPIRES_AT'])
        self.assertEqual(values['IPGO_INSIGHT_DEMO_STARTS_AT'],self.env['INSIGHT_STARTS_AT'])

    def test_exact_source_pin_still_rejects_mismatch(self):
        self.assertEqual(module.settings(dict(self.env, DEPLOYMENT_SOURCE_SHA=SHA))['sha'],SHA)
        with self.assertRaises(module.InvalidDeployment):
            module.settings(dict(self.env, DEPLOYMENT_SOURCE_SHA='b'*40))

    def setUp(self):
        self.env = dict(EXPECTED_SHA=SHA, GITHUB_SHA=SHA, AWS_ACCOUNT_ID=ACCOUNT,
                        COMPUTE_MODE='onebox', MAINTENANCE_APPROVED='true', ENGINE_ENABLED='true',
                        ENGINE_CONNECTION_APPROVED='true', ENGINE_BASE_URL='http://ai.internal:8000',
                        INSIGHT_STARTS_AT=(datetime.now(timezone.utc)-timedelta(minutes=1)).isoformat(), INSIGHT_EXPIRES_AT=(datetime.now(timezone.utc)+timedelta(hours=2)).isoformat(), INSIGHT_ENABLED='true', INSIGHT_APPROVED='true', INSIGHT_WORKER_ENABLED='true', DESIRED_COUNT='1')
        self.task = dict(family='ipgo-dev-onebox-server', taskRoleArn='synthetic-task-role',
                         executionRoleArn='synthetic-execution-role', networkMode='bridge',
                         containerDefinitions=[dict(name='server', image='old-image', memory=448,
                         healthCheck={'command': ['CMD-SHELL', 'health-check']},
                         environment=[{'name':'KEEP_SETTING','value':'preserve-me'},
                                      {'name':'IPGO_INSIGHT_ENABLED','value':'true'}],
                         secrets=[{'name':'SPRING_DATASOURCE_PASSWORD','valueFrom':'synthetic-secret-ref'}]),
                         dict(name='proxy',image='proxy-image',memory=32)])

    def rendered(self):
        return module.render(self.task, IMAGE, self.env)

    def test_approved_activation_preserves_other_settings_and_source(self):
        before=copy.deepcopy(self.task)
        result=self.rendered()
        self.assertEqual(self.task,before)
        original=before['containerDefinitions'][0]
        server=result['containerDefinitions'][0]
        values={e['name']:e['value'] for e in server['environment']}
        self.assertEqual(values['IPGO_INSIGHT_ENABLED'],'true')
        self.assertEqual(values['IPGO_INSIGHT_WORKER_ENABLED'],'true')
        self.assertEqual(values['IPGO_ENGINE_BASE_URL'],'http://ai.internal:8000')
        self.assertEqual(values['KEEP_SETTING'],'preserve-me')
        self.assertEqual(server['image'],IMAGE)
        self.assertEqual(server['secrets'],original['secrets'])
        self.assertEqual(server['memory'],original['memory'])
        self.assertEqual(server['healthCheck'],original['healthCheck'])
        self.assertEqual(result['containerDefinitions'][1],before['containerDefinitions'][1])
        self.assertEqual(result['taskRoleArn'],before['taskRoleArn'])
        self.assertEqual(result['executionRoleArn'],before['executionRoleArn'])
        self.assertEqual(result['networkMode'],before['networkMode'])

    def test_disabled_deployment_overrides_old_active_flag(self):
        self.env.update(INSIGHT_ENABLED='false',INSIGHT_APPROVED='false',INSIGHT_WORKER_ENABLED='false')
        env={e['name']:e['value'] for e in self.rendered()['containerDefinitions'][0]['environment']}
        self.assertEqual(env['IPGO_INSIGHT_ENABLED'],'false')
        self.assertEqual(env['IPGO_INSIGHT_WORKER_ENABLED'],'false')

    def test_staged_activation_can_keep_worker_off(self):
        self.env['INSIGHT_WORKER_ENABLED']='false'
        self.assertFalse(module.settings(self.env)['worker'])

    def test_sha_mismatch_and_missing_pin_are_rejected(self):
        for pin in ['', 'abc', 'b'*40, SHA+'\n']:
            with self.subTest(pin=pin), self.assertRaises(module.InvalidDeployment):
                module.settings(dict(self.env,EXPECTED_SHA=pin))

    def test_maintenance_engine_and_cost_approvals_are_required(self):
        for name in ['MAINTENANCE_APPROVED','ENGINE_CONNECTION_APPROVED','INSIGHT_APPROVED','ENGINE_ENABLED']:
            with self.subTest(name=name), self.assertRaises(module.InvalidDeployment):
                module.settings(dict(self.env,**{name:'false'}))

    def test_worker_cannot_be_enabled_with_insight_disabled(self):
        with self.assertRaises(module.InvalidDeployment):
            module.settings(dict(self.env,INSIGHT_ENABLED='false'))

    def test_missing_url_blocks_engine_before_rendering(self):
        with self.assertRaises(module.InvalidDeployment):
            module.settings(dict(self.env,ENGINE_BASE_URL=''))

    def test_url_rejects_credentials_path_query_fragment_and_multiline(self):
        for url in ['ftp://ai.internal','http://user:password@ai.internal','http://ai.internal/path',
                    'http://ai.internal?x=1','http://ai.internal#fragment','http://ai.internal\nEXTRA=true',
                    'http://127.0.0.1:8000','http://[::1]:8000','http://ai.internal:99999','http://:8000','http://@ai.internal','http://ai.internal?',
                    'http://ai.internal#','http://ai.internal\x00']:
            with self.subTest(url=url), self.assertRaises(module.InvalidDeployment):
                module.settings(dict(self.env,ENGINE_BASE_URL=url))

    def test_invalid_boolean_mode_count_are_rejected(self):
        for change in [dict(INSIGHT_ENABLED='TRUE'),dict(INSIGHT_WORKER_ENABLED=''),dict(DESIRED_COUNT='2'),dict(COMPUTE_MODE='unknown')]:
            with self.subTest(change=change),self.assertRaises(module.InvalidDeployment):
                module.settings(dict(self.env,**change))

    def test_image_cannot_change_sha_account_or_architecture(self):
        for image in [IMAGE.replace(SHA,'b'*40),IMAGE.replace(ACCOUNT,'987654321098'),IMAGE.removesuffix('-arm64'),'repo:latest']:
            with self.subTest(image=image),self.assertRaises(module.InvalidDeployment):
                module.render(self.task,image,self.env)

    def test_wrong_family_or_ambiguous_server_is_rejected(self):
        for mutation in ['family','duplicate','missing']:
            t=copy.deepcopy(self.task)
            if mutation=='family': t['family']='ipgo-production-server'
            elif mutation=='duplicate': t['containerDefinitions'].append(copy.deepcopy(t['containerDefinitions'][0]))
            else: t['containerDefinitions']=[]
            with self.subTest(mutation=mutation),self.assertRaises(module.InvalidDeployment):
                module.render(t,IMAGE,self.env)

    def test_openai_key_is_never_injected_into_server(self):
        for field in ['environment','secrets']:
            t=copy.deepcopy(self.task)
            t['containerDefinitions'][0][field].append({'name':'OPENAI_API_KEY','value':'synthetic','valueFrom':'synthetic-ref'})
            with self.subTest(field=field),self.assertRaises(module.InvalidDeployment):
                module.render(t,IMAGE,self.env)

    def test_duplicate_old_managed_keys_are_replaced_once(self):
        self.task['containerDefinitions'][0]['environment'].append({'name':'IPGO_INSIGHT_ENABLED','value':'false'})
        self.assertEqual(sum(e['name']=='IPGO_INSIGHT_ENABLED' for e in self.rendered()['containerDefinitions'][0]['environment']),1)

    def test_fully_disabled_deployment_preserves_existing_engine_origin(self):
        self.env.update(ENGINE_ENABLED='false',INSIGHT_ENABLED='false',INSIGHT_APPROVED='false',INSIGHT_WORKER_ENABLED='false',ENGINE_BASE_URL='')
        self.task['containerDefinitions'][0]['environment'].append({'name':'IPGO_ENGINE_BASE_URL','value':'http://preserved.internal:8000'})
        env={e['name']:e['value'] for e in self.rendered()['containerDefinitions'][0]['environment']}
        self.assertEqual(env['IPGO_ENGINE_BASE_URL'],'http://preserved.internal:8000')

    def test_cli_output_is_private_and_never_prints_task_or_origin(self):
        with tempfile.TemporaryDirectory() as tmp:
            source=Path(tmp)/'source.json';output=Path(tmp)/'output.json'
            source.write_text(json.dumps(self.task))
            result=subprocess.run([sys.executable,str(SCRIPT),'render','--source',str(source),'--output',str(output),'--image',IMAGE],
                                  env=dict(os.environ,**self.env),capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual(output.stat().st_mode & 0o777,0o600)
            self.assertNotIn('preserve-me',result.stdout+result.stderr)
            self.assertNotIn('ai.internal',result.stdout+result.stderr)
            self.assertNotIn('synthetic-secret-ref',result.stdout+result.stderr)
            self.assertEqual(json.loads(output.read_text()),self.rendered())

    def test_cli_rejection_does_not_write_output_or_leak_supplied_url(self):
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp)/'output.json'
            env=dict(os.environ,**self.env);env['ENGINE_BASE_URL']='http://user:synthetic-password@ai.internal'
            result=subprocess.run([sys.executable,str(SCRIPT),'render','--output',str(output)],env=env,capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0)
            self.assertFalse(output.exists())
            self.assertNotIn('synthetic-password',result.stdout+result.stderr)

    def test_workflow_calls_gate_before_credentials_or_image_upload(self):
        workflow=(ROOT/'.github/workflows/deploy-dev.yml').read_text()
        gate=workflow.index('run: python3 scripts/deployment/render_dev_task.py validate')
        self.assertLess(gate,workflow.index('uses: aws-actions/configure-aws-credentials'))
        self.assertLess(gate,workflow.index('push: true'))
        for name in ['EXPECTED_SHA','INSIGHT_ENABLED','INSIGHT_APPROVED','INSIGHT_WORKER_ENABLED','ENGINE_BASE_URL']:
            self.assertIn(name+': ${{ ',workflow)
        self.assertIn('python3 scripts/deployment/render_dev_task.py render',workflow)
        # Environment-level vars are resolved inside runner steps, after the dev environment is declared.
        self.assertEqual(workflow.count('          ENGINE_BASE_URL: ${{ vars.AWS_AI_ENGINE_BASE_URL }}'),2)
        self.assertIn('task-definition: ${{ steps.render.outputs.task-definition }}',workflow)

    def test_legacy_image_and_family_are_separate(self):
        self.env.update(COMPUTE_MODE='legacy',MAINTENANCE_APPROVED='false')
        self.task['family']='ipgo-dev-server'
        result=module.render(self.task,IMAGE.removesuffix('-arm64'),self.env)
        self.assertEqual(result['containerDefinitions'][0]['image'],IMAGE.removesuffix('-arm64'))


if __name__=='__main__':
    unittest.main()
