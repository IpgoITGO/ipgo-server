"""최초 설치용 protected Infra job. 일상 runtime role과 분리."""
import base64
import json
import os
import re

from contract import require, verify_envelope


def verify_install_job(env):
    require(env.get('GITHUB_REPOSITORY') == 'IpgoITGO/ipgo-infra', 'INSTALL_REPOSITORY')
    require(env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and env.get('GITHUB_REF') == 'refs/heads/dev'
            and env.get('GITHUB_RUN_ATTEMPT') == '1', 'INSTALL_MANUAL_DEV_ONLY')
    require(env.get('D0_INSTALL_ENABLED') == 'true' and env.get('CONFIRMATION') == 'INSTALL_D0', 'INSTALL_DISABLED')
    require(re.fullmatch('[a-f0-9]{40}', env.get('EXPECTED_SHA', ''))
            and env['EXPECTED_SHA'] == env.get('GITHUB_SHA'), 'INSTALL_SOURCE_SHA')
    encoded = env.get('D0_SIGNED_MANIFEST', '')
    require(0 < len(encoded) <= 6500, 'ENVELOPE_SIZE')
    manifest = verify_envelope(json.loads(base64.b64decode(encoded, validate=True)))
    cfg = json.loads(env['D0_INSTALL_JSON'])
    require(manifest['kind'] == 'install' and manifest['workflowSha'] == env['EXPECTED_SHA']
            and manifest['helperSha'] == env.get('HELPER_SHA')
            and manifest['accountId'] == env.get('AWS_ACCOUNT_ID'), 'INSTALL_SIGNATURE_SCOPE')
    required = {'task_definition_arn','secret_arn','task_role_arn','execution_role_arn','log_group',
                'cluster_arn','image_uri','subnet_ids','security_group_ids'}
    require(set(cfg) == required and cfg['task_definition_arn'] == manifest['taskDefinitionArn']
            and cfg['image_uri'] == manifest['imageUri'] and cfg['subnet_ids'] == manifest['subnetIds']
            and cfg['security_group_ids'] == manifest['securityGroupIds'], 'INSTALL_TARGET')
    account = manifest['accountId']
    require(cfg['cluster_arn'] == f'arn:aws:ecs:ap-northeast-2:{account}:cluster/ipgo-dev-cluster'
            and cfg['task_role_arn'] == f'arn:aws:iam::{account}:role/ipgo-dev-d0-install-task'
            and cfg['execution_role_arn'].startswith(f'arn:aws:iam::{account}:role/ipgo-dev-')
            and cfg['secret_arn'].startswith(f'arn:aws:secretsmanager:ap-northeast-2:{account}:secret:/ipgo/dev/d0-bootstrap-')
            and cfg['log_group'].startswith('/ipgo/'), 'INSTALL_ROLE_BOUNDARY')
    return manifest, cfg


if __name__ == '__main__':
    manifest, _ = verify_install_job(os.environ)
    from runtime import verify_client
    verify_client()
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write('account_id=' + manifest['accountId'] + '\n')
    print('설치 서명/대상 검증 완료. 자격증명 출력 없음.')
