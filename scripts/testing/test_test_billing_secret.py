import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

script = Path(__file__).resolve().parents[1] / 'deployment/prepare_test_billing_secret.py'
spec = importlib.util.spec_from_file_location('prepare_billing_secret', script)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class TestBillingSecretPreparation(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.source = Path(self.tmp.name) / 'input.json'
        self.output = Path(self.tmp.name) / 'output.json'
        self.data = dict(test_client_key='test_ck_fixture', test_secret_key='test_sk_fixture', merchant_id='fixture-mid', security_key='fixture-security')
        self.write()

    def write(self):
        self.source.write_text(json.dumps(self.data))
        self.source.chmod(0o600)

    def test_separate_storage_keys_private_permissions_and_reuse(self):
        module.prepare(self.source, self.output)
        first = self.output.read_bytes()
        data = json.loads(first)
        self.assertNotEqual(data['encryption_key'], data['lookup_key'])
        self.assertEqual(len(base64.b64decode(data['encryption_key'])), 32)
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o600)
        self.assertNotIn('test_client_key', data)
        module.prepare(self.source, self.output)
        self.assertEqual(first, self.output.read_bytes())

    def test_live_and_widget_keys_are_rejected(self):
        for key in ('live_sk_fixture', 'test_gsk_fixture'):
            self.data['test_secret_key'] = key
            self.write()
            with self.assertRaises(ValueError):
                module.prepare(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_group_readable_input_is_rejected(self):
        self.source.chmod(0o640)
        with self.assertRaises(ValueError):
            module.prepare(self.source, self.output)

    def test_existing_material_is_not_silently_rotated(self):
        module.prepare(self.source, self.output)
        first = self.output.read_bytes()
        self.data['merchant_id'] = 'another-mid'
        self.write()
        with self.assertRaises(ValueError):
            module.prepare(self.source, self.output)
        self.assertEqual(first, self.output.read_bytes())
