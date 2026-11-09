import copy
import unittest

from contract import GateError
from environment_gate import validate_environment


class EnvironmentTest(unittest.TestCase):
    def test_requires_review_and_exact_dev_branch_not_merely_a_named_environment(self):
        env = {'protection_rules': [{'type': 'required_reviewers', 'reviewers': [{'id': 1}], 'prevent_self_review': True}],
               'deployment_branch_policy': {'custom_branch_policies': True, 'protected_branches': False}}
        branches = {'branch_policies': [{'name': 'dev', 'type': 'branch'}]}
        validate_environment(env, branches)
        for item in [{}, dict(env, protection_rules=[]), dict(env, deployment_branch_policy=None),
                     dict(env, deployment_branch_policy={'protected_branches': True})]:
            with self.subTest(item=item), self.assertRaises(GateError):
                validate_environment(item, branches)
        for item in [{'branch_policies': []}, {'branch_policies': [{'name': '*'}]},
                     {'branch_policies': [{'name': 'main', 'type': 'branch'}]},
                     {'branch_policies': [{'name': 'dev', 'type': 'tag'}]},
                     {'branch_policies': [{'name': 'dev'}, {'name': 'main'}]}]:
            with self.subTest(item=item), self.assertRaises(GateError):
                validate_environment(env, item)
        other = copy.deepcopy(env)
        other['protection_rules'][0]['prevent_self_review'] = False
        with self.assertRaises(GateError):
            validate_environment(other, branches)
