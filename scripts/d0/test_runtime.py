import base64
import copy
import hashlib
import io
import json
import sys
import unittest
from datetime import date
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

sys.path.insert(0, str(Path(__file__).parent))
from contract import ASSET_SHA, CLIENT_SHA, GateError, canonical, fingerprint, seal_credentials, verify_envelope
from fixture import make_fixture
from runtime import prepare


class ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.private = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        cls.public = cls.private.public_key()

    def manifest(self):
        m = {'scope': 'ipgo-d0-receipts-v1', 'operationId': 'dc2d35e0-0b2e-4197-a098-92358d9226be',
             'kind': 'prepare', 'd0Date': '2026-11-09', 'd1Date': '2026-11-10',
             'dbHost': 'ipgo-dev.abcdefgh.ap-northeast-2.rds.amazonaws.com', 'dbName': 'ipgo',
             'fixtureSha256': '0' * 64, 'keyFingerprint': fingerprint(self.public),
             'clientSha': CLIENT_SHA, 'serverTd': 31, 'aiTd': 17, 'accountId': '123456789012',
             'workflowSha': 'a' * 40, 'helperSha': 'a' * 40,
             'imageUri': '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/ipgo-dev/database@sha256:' + 'a' * 64,
             'taskDefinitionArn': 'arn:aws:ecs:ap-northeast-2:123456789012:task-definition/ipgo-dev-d0-validation:7',
             'subnetIds': ['subnet-0abcd'], 'securityGroupIds': ['sg-0abcd']}
        m['fixtureSha256'] = hashlib.sha256(make_fixture(m)[2]).hexdigest()
        return m

    def envelope(self, manifest):
        signature = self.private.sign(canonical(manifest), padding.PSS(mgf=padding.MGF1(hashes.SHA256()),
                                      salt_length=padding.PSS.DIGEST_LENGTH), hashes.SHA256())
        return {'manifest': manifest, 'signature': base64.b64encode(signature).decode()}

    def test_signed_scope_and_real_date_guard(self):
        m = self.manifest()
        self.assertEqual(m, verify_envelope(self.envelope(m), day=date(2026, 11, 9), public_key=self.public))
        with self.assertRaisesRegex(GateError, 'KST_DATE'):
            verify_envelope(self.envelope(m), day=date(2026, 11, 10), public_key=self.public)

    def test_tampering_wrong_key_unknown_fields_and_baselines(self):
        for key, value in [('dbHost', 'other.abcdefgh.ap-northeast-2.rds.amazonaws.com'),
                           ('fixtureSha256', 'a' * 64), ('workflowSha', 'b' * 40)]:
            e = self.envelope(self.manifest())
            e['manifest'][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(GateError, 'SIGNATURE'):
                verify_envelope(e, day=date(2026, 11, 9), public_key=self.public)
        for key, value in [('sql', 'delete'), ('serverTd', 32), ('aiTd', True), ('dbHost', 'attacker.invalid'),
                           ('d1Date', '2026-11-11')]:
            m = self.manifest()
            m[key] = value
            with self.subTest(key=key), self.assertRaises(GateError):
                verify_envelope(self.envelope(m), day=date(2026, 11, 9), public_key=self.public)
        other = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        with self.assertRaisesRegex(GateError, 'FINGERPRINT'):
            verify_envelope(self.envelope(self.manifest()), day=date(2026, 11, 9), public_key=other.public_key())

    def test_fixture_is_deterministic_single_sku_and_conserved(self):
        import openpyxl
        m = self.manifest()
        name, barcode, data = make_fixture(m)
        self.assertEqual(data, make_fixture(m)[2])
        self.assertEqual('2026-09.xlsx', name)
        s = openpyxl.load_workbook(io.BytesIO(data), data_only=True).active
        self.assertEqual(barcode, s.cell(5, 2).value)
        self.assertEqual(5, s.max_row)
        closes = [s.cell(5, 21 + 4 * day).value for day in range(30)]
        self.assertEqual(42, closes[-1])
        self.assertTrue(all(a - 1 == b for a, b in zip(closes, closes[1:])))

    def test_credentials_are_sealed_not_plain(self):
        sealed = seal_credentials(self.public, 'synthetic@example.invalid', 'test-only-password')
        self.assertNotIn('password', sealed)
        plain = self.private.decrypt(base64.b64decode(sealed),
            padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
        self.assertEqual('test-only-password', json.loads(plain)['password'])

    def test_prepare_only_d0_requests_and_no_paid_trigger(self):
        m = self.manifest()
        db, api = FakeDatabase(), FakeApi(m)
        result = prepare(m, db, api, public_key=self.public)
        self.assertEqual('VERIFIED', result['status'])
        self.assertEqual([80, 40, 100], [x['quantity'] for x in api.receipts])
        self.assertEqual(1, api.uploads)
        self.assertEqual(1, api.logouts)
        self.assertEqual(['UPLOADED', 'RECEIPT_80', 'RECEIPT_40', 'RECEIPT_100', 'VERIFIED'], db.steps)
        self.assertEqual(0, result['llmCalls'])
        self.assertEqual(0, result['incrementalLlmUsd'])

    def test_logout_failure_stops_before_verified_and_preserves_first_business_error(self):
        from unittest.mock import patch
        m = self.manifest()
        db, api = FakeDatabase(), FakeApi(m)
        with patch.object(api, 'logout', side_effect=GateError('LOGOUT_UNAVAILABLE')):
            with self.assertRaisesRegex(GateError, 'LOGOUT_UNAVAILABLE'):
                prepare(m, db, api, public_key=self.public)
        self.assertNotIn('VERIFIED', db.steps)
        self.assertEqual('FAILED', db.steps[-1])
        db, api = FakeDatabase(), FakeApi(m, fail_at=1)
        with patch.object(api, 'logout', side_effect=GateError('LOGOUT_UNAVAILABLE')):
            with self.assertRaisesRegex(GateError, 'SIMULATED_RESPONSE_LOSS'):
                prepare(m, db, api, public_key=self.public)
        self.assertEqual(1, len(api.receipts))

    def test_first_failed_or_unknown_receipt_stops_and_no_replay_writes(self):
        m = self.manifest()
        db, api = FakeDatabase(), FakeApi(m, fail_at=2)
        with self.assertRaisesRegex(GateError, 'SIMULATED_RESPONSE_LOSS'):
            prepare(m, db, api, public_key=self.public)
        self.assertEqual(2, len(api.receipts))
        self.assertEqual('FAILED', db.steps[-1])
        db.replay = True
        fresh = FakeApi(m)
        result = prepare(m, db, fresh, public_key=self.public)
        self.assertEqual('ALREADY_SEEDED', result['status'])
        self.assertEqual([], fresh.receipts)
        self.assertEqual(0, fresh.uploads)

    def test_state_change_or_hash_change_stops_before_writes(self):
        m = self.manifest()
        for patch in [{'privilegeBoundaryOk': False}, {'pendingInsights': 1}, {'activeJobs': 1}, {'oldRowsUnchanged': False},
                      {'defaultsConfirmed': True}, {'runCount': 1}, {'llmCalls': 1}]:
            db, api = FakeDatabase(patch), FakeApi(m)
            with self.subTest(patch=patch), self.assertRaises(GateError):
                prepare(m, db, api, public_key=self.public)
            self.assertEqual(0, api.uploads)
        m['fixtureSha256'] = 'a' * 64
        with self.assertRaisesRegex(GateError, 'FIXTURE_CHANGED'):
            prepare(m, FakeDatabase(), FakeApi(m), public_key=self.public)


class FakeDatabase:
    def __init__(self, patch=None):
        self.steps = []
        self.replay = False
        self.patch = patch or {}

    def call(self, name, *args):
        if name == 'seed_user':
            return {'userId': 999, 'state': 'SEEDED', 'replayed': self.replay, 'sealedCredentials': args[-1]}
        return {'privilegeBoundaryOk': True, 'activeJobs': 0, 'pendingInsights': 0, 'oldRowsUnchanged': True,
                'defaultsConfirmed': False, 'runCount': 0, 'llmCalls': 0, **self.patch}

    def step(self, operation, name, evidence):
        self.steps.append(name)
        return self.call('preflight')


class FakeApi:
    def __init__(self, manifest, fail_at=0):
        self.manifest, self.fail_at = manifest, fail_at
        self.receipts = []
        self.uploads = self.logouts = 0

    def login(self, email, password):
        pass

    def get(self, path):
        if path == '/api/auth/me':
            return {'userId': 999}
        if path == '/api/replenishment-settings':
            return {'confirmed': False}
        if path == '/api/products?page=0&size=20':
            return {'items': [{'productId': 1, 'skuBarcode': make_fixture(self.manifest)[1], 'unitCost': 10}] if self.uploads else []}
        if path == '/api/planned-receipts':
            return {'items': copy.deepcopy(self.receipts)}
        if path == '/api/uploads/10':
            return {'status': 'DONE', 'runId': None, 'validation': {'targetSkuCount': 1, 'excludedSkus': []}}
        raise AssertionError('Unexpected API path')

    def upload(self, name, data):
        self.uploads += 1
        return {'uploadId': 10}

    def post(self, path, payload):
        if path != '/api/planned-receipts':
            raise AssertionError('Unexpected business mutation')
        item = dict(payload, receiptId=len(self.receipts) + 1, status='PENDING', receivedOn=None)
        self.receipts.append(item)
        if len(self.receipts) == self.fail_at:
            raise GateError('SIMULATED_RESPONSE_LOSS')
        return {'receiptId': item['receiptId'], 'runId': None}

    def logout(self):
        self.logouts += 1


if __name__ == '__main__':
    unittest.main()
