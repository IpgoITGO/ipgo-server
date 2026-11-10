"""D0 전용 계약. 운영 자격증명을 읽거나 네트워크를 호출하지 않는다."""
import base64
import hashlib
import json
import re
from datetime import date, datetime, timedelta
from pathlib import Path
from uuid import UUID
from zoneinfo import ZoneInfo

SCOPE = 'ipgo-d0-receipts-v1'
PUBLIC_KEY = Path(__file__).with_name('public-key.pem')
CLIENT_SHA = '6c515b993b52b65b5c59da9dcacd6011288420c9'
ASSET_PATH = '/assets/index-CHslFJ9p.js'
ASSET_SHA = 'c42f08bff10c5329e1a6b06615e9925b5e915f92c5da7794b01a3a4250a0aa79'
SERVER_TD = 32
AI_TD = 19
API_ORIGIN = 'https://ipgo-dev-api.iwhya.kr'
KEYS = {'scope', 'operationId', 'kind', 'd0Date', 'd1Date', 'dbHost', 'dbName',
        'fixtureSha256', 'keyFingerprint', 'clientSha', 'serverTd', 'aiTd',
        'accountId', 'workflowSha', 'helperSha', 'imageUri', 'taskDefinitionArn', 'subnetIds', 'securityGroupIds'}


class GateError(Exception):
    """메시지는 고정된 비밀값 없는 오류 코드만 담는다."""


def require(value, code):
    if not value:
        raise GateError(code)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def today():
    return datetime.now(ZoneInfo('Asia/Seoul')).date()


def fingerprint(public_key):
    from cryptography.hazmat.primitives import serialization
    return hashlib.sha256(public_key.public_bytes(serialization.Encoding.DER,
                                                  serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()


def load_public(path=PUBLIC_KEY):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicKey
    key = serialization.load_pem_public_key(Path(path).read_bytes())
    require(isinstance(key, RSAPublicKey) and key.key_size >= 3072, 'PUBLIC_KEY_INVALID')
    return key


def validate(manifest, *, day=None, public_key=None):
    require(isinstance(manifest, dict) and set(manifest) == KEYS, 'MANIFEST_FIELDS')
    require(manifest['scope'] == SCOPE, 'MANIFEST_SCOPE')
    require(manifest['kind'] in ('install', 'deactivate', 'read-only', 'prepare'), 'MANIFEST_KIND')
    require(isinstance(manifest['accountId'], str) and re.fullmatch('[0-9]{12}', manifest['accountId']), 'ACCOUNT_ID')
    for name in ('workflowSha', 'helperSha'):
        require(isinstance(manifest[name], str) and re.fullmatch('[a-f0-9]{40}', manifest[name]), 'SOURCE_SHA')
    account = manifest['accountId']
    family = 'install' if manifest['kind'] in ('install', 'deactivate') else 'validation'
    require(re.fullmatch(f'arn:aws:ecs:ap-northeast-2:{account}:task-definition/ipgo-dev-d0-{family}:[1-9][0-9]*',
                         manifest['taskDefinitionArn']), 'TASK_REVISION')
    require(re.fullmatch(f'{account}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/ipgo-dev/database@sha256:[a-f0-9]{{64}}',
                         manifest['imageUri']), 'IMAGE_DIGEST')
    require(isinstance(manifest['subnetIds'], list) and 1 <= len(manifest['subnetIds']) <= 3
            and all(isinstance(x, str) and re.fullmatch('subnet-[a-f0-9]+', x) for x in manifest['subnetIds'])
            and isinstance(manifest['securityGroupIds'], list) and len(manifest['securityGroupIds']) == 1
            and all(isinstance(x, str) and re.fullmatch('sg-[a-f0-9]+', x) for x in manifest['securityGroupIds']), 'NETWORK')
    try:
        require(str(UUID(manifest['operationId'])) == manifest['operationId'], 'OPERATION_ID')
        d0, d1 = date.fromisoformat(manifest['d0Date']), date.fromisoformat(manifest['d1Date'])
    except (TypeError, ValueError, AttributeError):
        raise GateError('MANIFEST_DATE_OR_ID') from None
    require(d0 == (day or today()) and d1 == d0 + timedelta(days=1), 'KST_DATE_MISMATCH')
    require(manifest['kind'] == 'deactivate' or (d0.month == d1.month and d0.year == d1.year),
            'MONTH_BOUNDARY_REVIEW_REQUIRED')
    require(isinstance(manifest['dbHost'], str) and re.fullmatch(
        r'[a-z0-9-]+\.[a-z0-9]+\.ap-northeast-2\.rds\.amazonaws\.com', manifest['dbHost']), 'DB_HOST')
    require(isinstance(manifest['dbName'], str) and re.fullmatch(r'[a-z][a-z0-9_]{0,62}', manifest['dbName']), 'DB_NAME')
    for name in ('fixtureSha256', 'keyFingerprint'):
        require(isinstance(manifest[name], str) and re.fullmatch('[a-f0-9]{64}', manifest[name]), 'MANIFEST_HASH')
    require(manifest['clientSha'] == CLIENT_SHA and type(manifest['serverTd']) is int
            and manifest['serverTd'] == SERVER_TD and type(manifest['aiTd']) is int
            and manifest['aiTd'] == AI_TD, 'BASELINE_MISMATCH')
    if public_key is not None:
        require(manifest['keyFingerprint'] == fingerprint(public_key), 'KEY_FINGERPRINT')
    return manifest


def verify_envelope(envelope, *, day=None, public_key=None):
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    require(isinstance(envelope, dict) and set(envelope) == {'manifest', 'signature'}, 'ENVELOPE_FIELDS')
    key = public_key or load_public()
    manifest = validate(envelope['manifest'], day=day, public_key=key)
    try:
        signature = base64.b64decode(envelope['signature'], validate=True)
        key.verify(signature, canonical(manifest), padding.PSS(mgf=padding.MGF1(hashes.SHA256()),
                   salt_length=padding.PSS.DIGEST_LENGTH), hashes.SHA256())
    except (InvalidSignature, ValueError, TypeError):
        raise GateError('MANIFEST_SIGNATURE') from None
    return manifest


def seal_credentials(key, email, password):
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    sealed = key.encrypt(canonical({'email': email, 'password': password}),
                         padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
    return base64.b64encode(sealed).decode()
