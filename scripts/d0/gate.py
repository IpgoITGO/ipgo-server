"""OIDC 발급 전 입력 검사. 서명된 승인 봉투 외 실행 입력은 받지 않는다."""
import base64
import json
import os
import re

from contract import require, verify_envelope

CONFIG_KEYS = {'enabled', 'runner_role_arn', 'task_definition_arn', 'execution_role_arn',
               'cluster_arn', 'image_uri', 'subnet_ids', 'security_group_ids', 'log_group'}


def verify_job(env):
    require(env.get('GITHUB_REPOSITORY') == 'IpgoITGO/ipgo-server', 'REPOSITORY')
    require(env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and env.get('GITHUB_REF') == 'refs/heads/dev',
            'MANUAL_DEV_ONLY')
    require(env.get('GITHUB_RUN_ATTEMPT') == '1', 'RERUN_REQUIRES_NEW_REVIEW')
    require(re.fullmatch('[a-f0-9]{40}', env.get('EXPECTED_SHA', ''))
            and env['EXPECTED_SHA'] == env.get('GITHUB_SHA'), 'WORKFLOW_SHA')
    mode = env.get('D0_MODE')
    require(mode in ('read-only', 'prepare'), 'RUNTIME_MODE')
    require(env.get('CONFIRMATION') == ('D0_READ_ONLY' if mode == 'read-only' else 'PREPARE_D0'), 'CONFIRMATION')
    require(env.get('D0_ENABLED') == 'true', 'D0_DISABLED')
    encoded = env.get('D0_SIGNED_MANIFEST', '')
    require(0 < len(encoded) <= 6500, 'ENVELOPE_SIZE')
    try:
        manifest = verify_envelope(json.loads(base64.b64decode(encoded, validate=True)))
        cfg = json.loads(env['D0_RUNTIME_JSON'])
    except (ValueError, KeyError):
        raise ValueError('D0_CONFIGURATION_INVALID') from None
    require(manifest['kind'] == mode and set(cfg) == CONFIG_KEYS and cfg['enabled'] is True, 'D0_CONFIGURATION')
    match = re.fullmatch(r'arn:aws:iam::([0-9]{12}):role/ipgo-dev-d0-runner-oidc', cfg['runner_role_arn'])
    require(match, 'OIDC_ROLE')
    account = match.group(1)
    require(account == manifest['accountId'] and manifest['workflowSha'] == env['EXPECTED_SHA']
            and manifest['helperSha'] == env['EXPECTED_SHA']
            and cfg['task_definition_arn'] == manifest['taskDefinitionArn']
            and cfg['image_uri'] == manifest['imageUri'] and cfg['subnet_ids'] == manifest['subnetIds']
            and cfg['security_group_ids'] == manifest['securityGroupIds'], 'APPROVED_TARGET_CHANGED')
    require(cfg['execution_role_arn'] == f'arn:aws:iam::{account}:role/ipgo-dev-d0-execution'
            and cfg['cluster_arn'] == f'arn:aws:ecs:ap-northeast-2:{account}:cluster/ipgo-dev-cluster'
            and cfg['log_group'] == '/ipgo/dev/d0-validation', 'D0_TARGET')
    require(re.fullmatch(f'arn:aws:ecs:ap-northeast-2:{account}:task-definition/ipgo-dev-d0-validation:[1-9][0-9]*',
                         cfg['task_definition_arn']), 'TASK_REVISION')
    require(re.fullmatch(f'{account}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/ipgo-dev/database@sha256:[a-f0-9]{{64}}',
                         cfg['image_uri']), 'IMAGE_DIGEST')
    require(1 <= len(cfg['subnet_ids']) <= 3 and all(re.fullmatch('subnet-[a-f0-9]+', x) for x in cfg['subnet_ids'])
            and len(cfg['security_group_ids']) == 1
            and all(re.fullmatch('sg-[a-f0-9]+', x) for x in cfg['security_group_ids']), 'NETWORK')
    return manifest, cfg


if __name__ == '__main__':
    m, config = verify_job(os.environ)
    from runtime import verify_client
    verify_client()
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write('role_arn=' + config['runner_role_arn'] + '\n')
        output.write('account_id=' + config['runner_role_arn'].split(':')[4] + '\n')
    print('D0 입력/서명/날짜 검사 성공. manifest/인증정보 출력 없음.')
