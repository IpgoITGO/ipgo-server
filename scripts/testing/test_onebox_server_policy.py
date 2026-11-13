"""Offline regressions for the Infra #63 / Server #209 deployment boundary."""

import copy
import importlib.util
import unittest
from pathlib import Path
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("server_policy", ROOT / "scripts/deployment/verify_deployment.py")
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)
ACCOUNT = "123456789012"
NAME = "ipgo-dev-onebox-server"
ARN = f"arn:aws:ecs:ap-northeast-2:{ACCOUNT}:task-definition/{NAME}:46"


class ServerPolicyTests(unittest.TestCase):
    def setUp(self):
        self.env = {"AWS_ACCOUNT_ID": ACCOUNT, "COMPUTE_MODE": "onebox", "ECS_SERVICE_NAME": NAME}
        self.current = {
            "serviceName": NAME, "status": "ACTIVE", "taskDefinition": ARN,
            "launchType": "EC2", "schedulingStrategy": "REPLICA",
            "deploymentController": {"type": "ECS"},
            "deploymentConfiguration": {
                "strategy": "ROLLING", "minimumHealthyPercent": 0, "maximumPercent": 100,
                "deploymentCircuitBreaker": {"enable": True, "rollback": True},
            },
            "availabilityZoneRebalancing": "DISABLED",
            "deployments": [{"status": "PRIMARY", "taskDefinition": ARN, "rolloutState": "COMPLETED"}],
        }
        self.read = Mock(side_effect=self.response)

    def response(self, service, operation, **params):
        if (service, operation) == ("sts", "get-caller-identity"):
            return {"Account": ACCOUNT}
        if (service, operation) == ("ecs", "describe-services"):
            return {"services": [copy.deepcopy(self.current)], "failures": []}
        if (service, operation) == ("ecs", "list-tasks"):
            return {"taskArns": []}
        self.fail(f"Unexpected AWS call: {service} {operation}")

    def test_valid_policy_passes_without_writes_or_changes(self):
        original = copy.deepcopy(self.current)
        self.assertEqual(M.preflight(self.env, "server", self.read), {"read_preflight": "passed"})
        self.assertEqual(M.check_templates(self.env, "server", self.read, {NAME: ARN}),
                         {"active_templates_unchanged": True})
        self.assertEqual(self.current, original)
        self.assertTrue(all((c.args[0], c.args[1]) in M.READ_OPERATIONS for c in self.read.call_args_list))

    def test_missing_malformed_or_changed_policy_blocks_both_write_gates(self):
        original = copy.deepcopy(self.current)
        cases = [
            (("launchType",), [None, "FARGATE"]),
            (("schedulingStrategy",), [None, "DAEMON"]),
            (("deploymentController",), [None, [], "ECS"]),
            (("deploymentController", "type"), [None, "CODE_DEPLOY"]),
            (("deploymentConfiguration",), [None, [], "ROLLING"]),
            (("deploymentConfiguration", "strategy"), [None, "BLUE_GREEN"]),
            (("deploymentConfiguration", "minimumHealthyPercent"), [None, False, 0.0, "0", 100]),
            (("deploymentConfiguration", "maximumPercent"), [None, True, 100.0, "100", 200]),
            (("deploymentConfiguration", "deploymentCircuitBreaker"), [None, [], True]),
            (("deploymentConfiguration", "deploymentCircuitBreaker", "enable"), [None, False, 1]),
            (("deploymentConfiguration", "deploymentCircuitBreaker", "rollback"), [None, False, 1]),
            (("availabilityZoneRebalancing",), [None, "ENABLED"]),
        ]
        for path, values in cases:
            for value in values:
                self.current = copy.deepcopy(original)
                target = self.current
                for key in path[:-1]:
                    target = target[key]
                if value is None:
                    target.pop(path[-1])
                else:
                    target[path[-1]] = value
                with self.subTest(path=path, value=value):
                    with self.assertRaisesRegex(M.GateError, "Infra #63"):
                        M.preflight(self.env, "server", self.read)
                    with self.assertRaisesRegex(M.GateError, "Infra #63"):
                        M.check_templates(self.env, "server", self.read, {NAME: ARN})

    def test_policy_drift_after_preflight_blocks_even_with_same_template(self):
        M.preflight(self.env, "server", self.read)
        self.current["deploymentConfiguration"]["maximumPercent"] = 200
        with self.assertRaisesRegex(M.GateError, "Infra #63"):
            M.check_templates(self.env, "server", self.read, {NAME: ARN})

    def test_policy_read_failure_propagates_before_writes(self):
        for gate in [lambda: M.preflight(self.env, "server", self.read),
                     lambda: M.check_templates(self.env, "server", self.read, {NAME: ARN})]:
            self.read.side_effect = PermissionError("offline fixture")
            with self.assertRaises(PermissionError):
                gate()

    def test_extra_aws_defaults_do_not_require_policy_changes(self):
        self.current["deploymentConfiguration"]["deploymentCircuitBreaker"].update(
            resetOnHealthyTask=True, thresholdConfiguration={"type": "BOUNDED_PERCENT", "value": 50})
        self.assertEqual(M.preflight(self.env, "server", self.read), {"read_preflight": "passed"})

    def test_legacy_preflight_accepts_its_existing_policy(self):
        self.env.update(COMPUTE_MODE="legacy", ECS_SERVICE_NAME="ipgo-dev-server")
        self.current = {"serviceName": "ipgo-dev-server", "status": "ACTIVE"}
        self.assertEqual(M.preflight(self.env, "server", self.read), {"read_preflight": "passed"})

    def test_workflow_has_no_policy_write_and_checks_before_ecs_deployment(self):
        workflow = (ROOT / ".github/workflows/deploy-dev.yml").read_text()
        self.assertNotIn("--deployment-configuration", workflow)
        self.assertNotIn("Configure approved one-box restart", workflow)
        self.assertLess(workflow.index(" preflight --service server"), workflow.index("Build and push immutable image"))
        self.assertLess(workflow.index(" check-template --service server"),
                        workflow.index("uses: aws-actions/amazon-ecs-deploy-task-definition@"))
        self.assertIn("--desired-count", workflow)


if __name__ == "__main__":
    unittest.main()
