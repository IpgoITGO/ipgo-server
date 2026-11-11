import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/deployment'))
SPEC = importlib.util.spec_from_file_location('runtime_artifact', ROOT / 'scripts/deployment/runtime_artifact.py')
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)
sys.path.pop(0)


class RuntimeArtifactTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.env = {'GITHUB_REPOSITORY': M.REPOSITORY, 'GITHUB_SHA': 'a' * 40,
                    'EXPECTED_SHA': 'a' * 40, 'GITHUB_RUN_ID': '17', 'GITHUB_RUN_ATTEMPT': '2'}
        self.run = {'id': 17, 'head_sha': 'a' * 40, 'head_branch': 'dev', 'event': 'push',
                    'path': '.github/workflows/test.yml', 'head_repository': {'full_name': M.REPOSITORY},
                    'status': 'completed', 'conclusion': 'success', 'run_attempt': 2}
        jar = self.root / 'boot.jar'
        jar.write_bytes(b'compiled-jvm-bytecode-fixture')
        self.package = self.root / 'package'
        self.manifest = M.package(jar, self.package, self.env)
        self.archive = self.root / 'artifact.zip'
        self.artifact = {'id': 23, 'name': M.artifact_name('a' * 40, 2), 'expired': False,
                         'workflow_run': {'id': 17, 'head_sha': 'a' * 40, 'head_branch': 'dev'}}
        self.repack()

    def repack(self, entries=None):
        with zipfile.ZipFile(self.archive, 'w') as bundle:
            if entries is None:
                for path in self.package.iterdir():
                    bundle.write(path, path.name)
            else:
                for name, value in entries:
                    bundle.writestr(name, value)
        self.artifact.update({'size_in_bytes': self.archive.stat().st_size,
                              'digest': 'sha256:' + hashlib.sha256(self.archive.read_bytes()).hexdigest()})

    def get(self, path):
        if path.endswith('/runs/17'):
            return self.run
        if '/runs/17/artifacts?' in path:
            return {'artifacts': [self.artifact]}
        self.fail('Unexpected API endpoint: ' + path)

    def select(self):
        return M.select_artifact(self.env, {'source_sha': 'a' * 40, 'service': 'server', 'test_run': 17}, self.get)

    def verify(self, artifact=None):
        return M.verify_archive(self.archive, artifact or self.artifact, self.run, self.root / 'accepted')

    def test_exact_successful_producer_and_jar_are_reused(self):
        artifact, run = self.select()
        result = self.verify(artifact)
        self.assertEqual(result['ci_attempt'], 2)
        self.assertEqual(result['source_sha'], run['head_sha'])
        self.assertEqual((self.root / 'accepted/app.jar').read_bytes(), b'compiled-jvm-bytecode-fixture')

    def test_wrong_sha_repo_branch_event_workflow_and_current_attempt_are_rejected(self):
        original = copy.deepcopy(self.run)
        for changes in ({'head_sha': 'b' * 40}, {'head_branch': 'main'}, {'event': 'pull_request'},
                        {'path': '.github/workflows/other.yml'}, {'conclusion': 'failure'},
                        {'status': 'in_progress'}, {'head_repository': {'full_name': 'fork/repo'}},
                        {'run_attempt': 3}):
            with self.subTest(changes=changes):
                self.run = {**original, **changes}
                with self.assertRaises(M.GateError):
                    self.select()

    def test_missing_expired_ambiguous_or_unsigned_artifact_never_falls_back(self):
        original = copy.deepcopy(self.artifact)
        for changes in ({'expired': True}, {'digest': ''}, {'name': 'untrusted'},
                        {'workflow_run': {'id': 18, 'head_sha': 'a' * 40, 'head_branch': 'dev'}}):
            self.artifact = {**original, **changes}
            with self.assertRaises(M.GateError):
                self.select()
        self.artifact = original
        with self.assertRaises(M.GateError):
            M.select_artifact(self.env, {'source_sha': 'a' * 40, 'service': 'server', 'test_run': 17},
                              lambda path: self.run if path.endswith('/runs/17') else {'artifacts': []})
        with self.assertRaises(M.GateError):
            M.select_artifact(self.env, {'source_sha': 'a' * 40, 'service': 'server', 'test_run': 17},
                              lambda path: self.run if path.endswith('/runs/17') else {'artifacts': [original, original]})

    def test_archive_or_jar_tampering_and_manifest_identity_are_rejected(self):
        with self.assertRaises(M.GateError):
            self.verify({**self.artifact, 'digest': 'sha256:' + '0' * 64})
        (self.package / 'app.jar').write_bytes(b'tampered')
        self.repack()
        with self.assertRaises(M.GateError):
            self.verify()
        for key, value in [('source_sha', 'b' * 40), ('run_attempt', 1), ('platform', 'linux/amd64')]:
            (self.package / 'manifest.json').write_text(json.dumps({**self.manifest, key: value}))
            self.repack()
            with self.assertRaises(M.GateError):
                self.verify()

    def test_archive_path_traversal_and_symlinks_are_rejected(self):
        self.repack([('../app.jar', b'bad'), ('manifest.json', b'{}')])
        with self.assertRaises(M.GateError):
            self.verify()
        link = zipfile.ZipInfo('app.jar')
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.repack([(link, b'/outside'), ('manifest.json', b'{}')])
        with self.assertRaises(M.GateError):
            self.verify()

    def test_retained_ecr_image_skips_artifact_and_failed_fetch_prevents_build(self):
        workflow = (ROOT / '.github/workflows/deploy-dev.yml').read_text()
        step = workflow.split('name: Reuse tested runtime without a second compilation')[1].split('      - uses:')[0]
        self.assertIn("if: steps.image_lookup.outputs.exists == 'false'", step)
        self.assertIn('set -euo pipefail', step)
        self.assertIn('runtime_artifact.py" fetch', step)
        self.assertNotIn('||', step)
        self.assertIn('file: ${{ steps.runtime.outputs.dockerfile }}', workflow)
        self.assertIn('provenance: true', workflow)
        self.assertIn('sbom: true', workflow)

    def test_ci_tests_package_once_build_same_jar_and_only_upload_from_dev_push(self):
        workflow = (ROOT / '.github/workflows/test.yml').read_text()
        self.assertIn('test bootJar', workflow)
        self.assertIn('file: Dockerfile.runtime', workflow)
        self.assertIn("if: github.event_name == 'push' && github.ref == 'refs/heads/dev'", workflow)
        self.assertNotIn('needs: test', workflow)
        self.assertIn('retention-days: 7', workflow)
        dockerfile = (ROOT / 'Dockerfile.runtime').read_text()
        bases = [line.split()[1] for line in (ROOT / 'Dockerfile').read_text().splitlines()
                 if line.startswith('FROM ')]
        self.assertIn('FROM ' + bases[-1], dockerfile)
        original = (ROOT / 'Dockerfile').read_text().split(' AS build', 1)[1]
        self.assertIn('FROM eclipse-temurin:21-jre-jammy', dockerfile)
        self.assertIn('COPY --chown=ipgo:ipgo .prepared-runtime/app.jar /app/app.jar', dockerfile)
        self.assertIn('USER 10001:10001', dockerfile)
        self.assertIn(dockerfile.split('ENTRYPOINT ')[1], original)


if __name__ == '__main__':
    unittest.main()
