"""NAS 로컬에서 승인 manifest만 서명. AWS/DB/API 통신 없음."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path

from contract import canonical, fingerprint, load_public, require, validate
from fixture import make_fixture


def main():
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    p = argparse.ArgumentParser()
    p.add_argument('--manifest', required=True)
    p.add_argument('--private-key', required=True)
    p.add_argument('--output', required=True)
    args = p.parse_args()
    os.umask(0o077)
    private = serialization.load_pem_private_key(Path(args.private_key).read_bytes(), password=None)
    key = load_public()
    require(fingerprint(private.public_key()) == fingerprint(key), 'PRIVATE_KEY_MISMATCH')
    manifest = json.loads(Path(args.manifest).read_text())
    manifest['keyFingerprint'] = fingerprint(key)
    manifest['fixtureSha256'] = hashlib.sha256(make_fixture(manifest)[2]).hexdigest()
    validate(manifest, public_key=key)
    signature = private.sign(canonical(manifest), padding.PSS(mgf=padding.MGF1(hashes.SHA256()),
                             salt_length=padding.PSS.DIGEST_LENGTH), hashes.SHA256())
    with open(args.output, 'x', opener=lambda path, flags: os.open(path, flags, 0o600)) as out:
        json.dump({'manifest': manifest, 'signature': base64.b64encode(signature).decode()}, out, sort_keys=True)
    print('승인 manifest 서명 완료. 비밀값/네트워크 출력 없음.')


if __name__ == '__main__':
    main()
