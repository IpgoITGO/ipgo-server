import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]


class WorkflowTest(unittest.TestCase):
    def test_pr_has_no_aws_credentials_and_manual_job_checks_before_oidc(self):
        text=(ROOT/'.github/workflows/d0-validation.yml').read_text()
        local,run=text.split('  run:',1)
        self.assertNotIn('id-token: write',local)
        self.assertNotIn('configure-aws-credentials',local)
        self.assertIn("github.event_name == 'workflow_dispatch'",run)
        self.assertIn("vars.D0_ENABLED == 'true'",run)
        self.assertIn('environment: d0-validation',run)
        self.assertLess(run.index('environment_gate.py'),run.index('configure-aws-credentials'))
        self.assertLess(run.index('scripts/d0/gate.py'),run.index('configure-aws-credentials'))
        self.assertNotIn('push:',text)
        self.assertNotIn('cancel-in-progress: true',text)
