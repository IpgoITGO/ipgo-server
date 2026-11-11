"""Prepare a private TEST-only JSON payload without printing credential values."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets


def prepare(source, output):
    source = Path(source)
    output = Path(output)
    if source.stat().st_mode & 0o077:
        raise ValueError("INPUT_PERMISSIONS_MUST_BE_PRIVATE")
    data = json.loads(source.read_text())
    client = data.get("test_client_key", "").strip()
    secret = data.get("test_secret_key", "").strip()
    merchant = data.get("merchant_id", "").strip()
    security = data.get("security_key", "").strip()
    if not client.startswith("test_ck_") or not secret.startswith("test_sk_"):
        raise ValueError("TEST_INDIVIDUAL_API_KEYS_REQUIRED")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", merchant) or not security:
        raise ValueError("MERCHANT_AND_SECURITY_KEY_REQUIRED")
    if output.exists():
        if output.stat().st_mode & 0o077:
            raise ValueError("OUTPUT_PERMISSIONS_MUST_BE_PRIVATE")
        previous = json.loads(output.read_text())
        if any(previous.get(k) != v for k, v in (("secret_key", secret), ("merchant_id", merchant), ("security_key", security))):
            raise ValueError("EXISTING_PAYLOAD_DIFFERS_REVIEW_ROTATION")
        return
    encryption = base64.b64encode(secrets.token_bytes(32)).decode()
    lookup = base64.b64encode(secrets.token_bytes(32)).decode()
    payload = {"secret_key": secret, "merchant_id": merchant, "security_key": security,
               "encryption_key": encryption, "lookup_key": lookup}
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        json.dump(payload, stream)
        stream.write("\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        prepare(args.input, args.output)
    except (ValueError, OSError, TypeError, AttributeError):
        raise SystemExit("TEST_BILLING_PAYLOAD_PREPARATION_FAILED") from None
    print("TEST_BILLING_PAYLOAD_READY_VALUES_REDACTED")
