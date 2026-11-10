import json
import os
import signal
from pathlib import Path

from contract import GateError
from dispatch import audit_to_file, dispatch
from install_gate import verify_install_job

if __name__ == '__main__':
    import boto3
    from botocore.config import Config
    try:
        signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(GateError('INSTALL_STOPPED')))
        manifest, config = verify_install_job(os.environ)
        sdk = Config(retries={'total_max_attempts': 1}, connect_timeout=10, read_timeout=20)
        result = dispatch(boto3.client('ecs', region_name='ap-northeast-2', config=sdk),
                          boto3.client('logs', region_name='ap-northeast-2', config=sdk),
                          manifest, config, os.environ['D0_SIGNED_MANIFEST'], audit=audit_to_file)
        with open(Path(os.environ['RUNNER_TEMP']) / 'd0-install-result.json', 'w',
                  opener=lambda p, flags: os.open(p, flags, 0o600)) as output:
            json.dump(result, output, sort_keys=True)
        print('D0 설치/비활성화 task 검증 완료. 업무 데이터 생성 없음.')
    except Exception:
        print('D0_INSTALL_RECOVERY_REQUIRED: task 영수증을 확인하고 같은 operation의 서명된 deactivate를 별도 승인하세요. DB 로그인 비활성화는 미확인입니다. 자동 재시도/일상 역할 활성화를 중단하세요.')
        raise SystemExit(1) from None
