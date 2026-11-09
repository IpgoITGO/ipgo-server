"""정확 revision task 한 개 실행/관찰. 새 task 자동 재시도 없음."""
import hashlib
import json
import os
import signal
import time
from pathlib import Path

from contract import GateError, require
from gate import verify_job


def baseline(ecs, cfg):
    r = ecs.describe_services(cluster=cfg['cluster_arn'],
        services=['ipgo-dev-onebox-server', 'ipgo-dev-onebox-ai-api'])
    require(not r.get('failures') and len(r['services']) == 2, 'SERVICE_READ')
    require({s['serviceName'] for s in r['services']} == {'ipgo-dev-onebox-server', 'ipgo-dev-onebox-ai-api'}, 'SERVICE_NAMES')
    for service in r['services']:
        td = 31 if service['serviceName'] == 'ipgo-dev-onebox-server' else 17
        account = cfg['cluster_arn'].split(':')[4]
        expected = f"arn:aws:ecs:ap-northeast-2:{account}:task-definition/{service['serviceName']}:{td}"
        require(service['taskDefinition'] == expected and service['desiredCount'] == 1
                and service['runningCount'] == 1 and service['pendingCount'] == 0
                and len(service['deployments']) == 1
                and service['deployments'][0].get('rolloutState') == 'COMPLETED', 'SERVICE_BASELINE_CHANGED')


def dispatch(ecs, logs, manifest, cfg, encoded, *, clock=time.monotonic, wait=time.sleep, audit=lambda *_: None):
    baseline(ecs, cfg)
    overrides = {'cpu': '256', 'memory': '512', 'containerOverrides': [{'name': 'runner', 'command': [manifest['kind']],
                 'environment': [{'name': 'D0_SIGNED_MANIFEST', 'value': encoded}]}]}
    require(len(json.dumps(overrides).encode()) <= 8192, 'OVERRIDES_SIZE')
    token = hashlib.sha256((manifest['operationId'] + ':' + manifest['kind']).encode()).hexdigest()
    audit('REQUESTED', {'operationId': manifest['operationId'], 'clientToken': token,
                        'taskDefinitionArn': cfg['task_definition_arn']})
    response = ecs.run_task(cluster=cfg['cluster_arn'], taskDefinition=cfg['task_definition_arn'],
        launchType='FARGATE', platformVersion='1.4.0', count=1, enableExecuteCommand=False,
        clientToken=token, startedBy='ipgo-d0-' + manifest['operationId'][:8],
        networkConfiguration={'awsvpcConfiguration': {'subnets': cfg['subnet_ids'],
            'securityGroups': cfg['security_group_ids'], 'assignPublicIp': 'ENABLED'}},
        tags=[{'key': 'Purpose', 'value': 'd0-install' if manifest['kind'] == 'install' else 'd0-synthetic'},
              {'key': 'OperationId', 'value': manifest['operationId']}],
        overrides=overrides)
    require(not response.get('failures') and len(response.get('tasks', [])) == 1, 'TASK_START')
    arn = response['tasks'][0]['taskArn']
    require(arn.startswith(cfg['cluster_arn'].replace(':cluster/', ':task/') + '/'), 'TASK_ARN')
    audit('STARTED', {'taskArn': arn})
    stopped = False
    try:
        end = clock() + 660
        while True:
            require(clock() < end, 'TASK_WAIT_TIMEOUT')
            status = ecs.describe_tasks(cluster=cfg['cluster_arn'], tasks=[arn])
            require(not status.get('failures') and len(status['tasks']) == 1, 'TASK_READ')
            task = status['tasks'][0]
            require(task['taskDefinitionArn'] == cfg['task_definition_arn'], 'TASK_REVISION_CHANGED')
            if task['lastStatus'] == 'STOPPED':
                stopped = True
                containers = task.get('containers', [])
                require(len(containers) == 1 and containers[0].get('exitCode') == 0
                        and containers[0].get('imageDigest') == cfg['image_uri'].split('@')[1], 'TASK_FAILED')
                break
            wait(3)
        stream = ('d0-install' if manifest['kind'] == 'install' else 'd0') + '/runner/' + arn.rsplit('/', 1)[1]
        result = None
        for _ in range(10):
            events = logs.get_log_events(logGroupName=cfg['log_group'], logStreamName=stream,
                                        startFromHead=True, limit=1000)
            for event in events.get('events', []):
                try:
                    value = json.loads(event['message'])
                except ValueError:
                    continue
                if isinstance(value, dict) and value.get('status') in ('INSTALLED', 'VERIFIED', 'READ_ONLY_OK', 'ALREADY_SEEDED', 'FAILED'):
                    result = value
            if result is not None:
                break
            wait(2)
        expected_status = {'install': 'INSTALLED', 'prepare': 'VERIFIED', 'read-only': 'READ_ONLY_OK'}[manifest['kind']]
        require(result is not None and result['status'] == expected_status,
                'RESULT_NOT_VERIFIED')
        if manifest['kind'] == 'install':
            require(result.get('businessWrites') == 0 and result.get('operationId') == manifest['operationId'], 'INSTALL_RESULT')
        else:
            require(result.get('llmCalls') == 0, 'UNEXPECTED_D0_LLM')
        if manifest['kind'] == 'prepare':
            require(result.get('operationId') == manifest['operationId'] and result.get('existingRowsUnchanged') is True
                    and result.get('incrementalLlmUsd') == 0
                    and all(type(result.get(k)) is int and result[k] > 0 for k in ('userId', 'productId', 'uploadId'))
                    and isinstance(result.get('receiptIds'), list) and len(result['receiptIds']) == 3
                    and all(type(x) is int and x > 0 for x in result['receiptIds'])
                    and len(set(result['receiptIds'])) == 3, 'D0_RESULT')
            import base64
            sealed = base64.b64decode(result.get('sealedCredentials', ''), validate=True)
            require(len(sealed) >= 384, 'SEALED_RESULT')
        baseline(ecs, cfg)
        allowed = {'status', 'operationId', 'userId', 'productId', 'uploadId', 'receiptIds', 'sealedCredentials',
                   'llmCalls', 'incrementalLlmUsd', 'existingRowsUnchanged', 'baseline', 'businessWrites'}
        require(set(result) <= allowed, 'RESULT_FIELDS')
        return result
    finally:
        if not stopped:
            ecs.stop_task(cluster=cfg['cluster_arn'], task=arn, reason='Approved D0 task failed or timed out')

def audit_to_file(stage, data):
    path = Path(os.environ['RUNNER_TEMP']) / 'd0-task-receipt.jsonl'
    with open(path, 'a', opener=lambda p, flags: os.open(p, flags, 0o600)) as output:
        output.write(json.dumps({'stage': stage, **data}, sort_keys=True) + '\n')


if __name__ == '__main__':
    import boto3
    from botocore.config import Config
    try:
        signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(GateError('WORKFLOW_STOPPED')))
        m, c = verify_job(os.environ)
        sdk = Config(retries={'total_max_attempts': 1}, connect_timeout=10, read_timeout=20)
        result = dispatch(boto3.client('ecs', region_name='ap-northeast-2', config=sdk),
                          boto3.client('logs', region_name='ap-northeast-2', config=sdk),
                          m, c, os.environ['D0_SIGNED_MANIFEST'], audit=audit_to_file)
        output = Path(os.environ['RUNNER_TEMP']) / 'd0-result.json'
        with open(output, 'w', opener=lambda p, flags: os.open(p, flags, 0o600)) as file:
            json.dump(result, file, sort_keys=True)
        print('D0 task/결과/배포 기준 검증 성공. 비밀값 출력 없음.')
    except Exception:
        print('D0 실행 실패. 새 task/업무 요청을 재시도하지 마세요.')
        raise SystemExit(1) from None
