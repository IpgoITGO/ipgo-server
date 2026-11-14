"""전용 캐시의 격리·재사용·내부 권한 실패를 검사한다. Docker/외부 통신 없음."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("local-gradle.sh")


class LocalGradleCacheTests(unittest.TestCase):
    def setUp(self):
        if os.geteuid() == 0:
            self.skipTest("일반 사용자 전용 로컬 캐시 검사")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.seed = self.root / "shared"
        self.cache = self.root / "owned"
        # Read the repository's actual wrapper distribution, rather than pinning a past version.
        import re
        properties = SCRIPT.parents[2] / "gradle/wrapper/gradle-wrapper.properties"
        self.dist = re.search(r"(gradle-[0-9][^/ ]*-(?:bin|all))\.zip", properties.read_text()).group(1)
        for name in (f"wrapper/dists/{self.dist}/cached.bin", "caches/modules-2/dependency.jar"):
            p = self.seed / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("prepared")
        self.env = {**os.environ, "IPGO_SERVER_GRADLE_HOME": str(self.cache),
                    "IPGO_SERVER_GRADLE_SEED": str(self.seed)}

    def prepare(self, cwd=None):
        return subprocess.run(["bash", str(SCRIPT), "--prepare-only"], env=self.env,
                              cwd=cwd or SCRIPT.parents[2], text=True, capture_output=True, timeout=15)

    def test_uses_calling_worktree_wrapper_instead_of_script_checkout(self):
        other = self.root / "other-worktree"
        properties = other / "gradle/wrapper/gradle-wrapper.properties"
        properties.parent.mkdir(parents=True)
        other_dist = (self.dist.replace("-bin", "-all") if self.dist.endswith("-bin")
                      else self.dist.replace("-all", "-bin"))
        properties.write_text(f"distributionUrl=https\\://services.gradle.org/distributions/{other_dist}.zip\n")
        (other / "gradlew").write_text("placeholder")
        dist = self.seed / "wrapper/dists" / other_dist / "cached.bin"
        dist.parent.mkdir(parents=True)
        dist.write_text("other wrapper")
        self.assertEqual(self.prepare(other).returncode, 0)
        self.assertTrue((self.cache / "wrapper/dists" / other_dist / "cached.bin").exists())
        self.assertFalse((self.cache / "wrapper/dists" / self.dist).exists())

    def test_copies_read_only_seed_once_without_changing_it(self):
        seed_file = self.seed / "caches/modules-2/dependency.jar"
        seed_file.chmod(0o400)
        self.assertEqual(self.prepare().returncode, 0)
        copy = self.cache / "caches/modules-2/dependency.jar"
        before = copy.stat()
        self.assertEqual(seed_file.stat().st_mode & 0o777, 0o400)
        seed_file.chmod(0o600)
        seed_file.write_text("new seed")  # existing cache must not be recopied on reuse
        seed_file.chmod(0o400)
        self.assertEqual(self.prepare().returncode, 0)
        self.assertEqual(copy.read_text(), "prepared")
        self.assertEqual(copy.stat().st_ino, before.st_ino)
        self.assertEqual(seed_file.stat().st_mode & 0o777, 0o400)
        self.assertTrue(copy.stat().st_mode & 0o200)

    def test_rejects_unwritable_nested_daemon_before_running_gradle(self):
        self.assertEqual(self.prepare().returncode, 0)
        nested = self.cache / "daemon/current"
        nested.mkdir(parents=True)
        nested.chmod(0o500)
        self.addCleanup(nested.chmod, 0o700)
        result = self.prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("내부의 소유권", result.stderr)

    def test_never_uses_shared_seed_as_writable_home(self):
        self.env["IPGO_SERVER_GRADLE_HOME"] = str(self.seed)
        self.assertNotEqual(self.prepare().returncode, 0)

    def test_rejects_symlink_in_seed_without_publishing_partial_cache(self):
        (self.seed / "caches/modules-2/link").symlink_to(self.seed / "caches/modules-2/dependency.jar")
        self.assertNotEqual(self.prepare().returncode, 0)
        self.assertFalse(self.cache.exists())

    def test_missing_seed_does_not_publish_partial_cache(self):
        (self.seed / "caches/modules-2/dependency.jar").unlink()
        (self.seed / "caches/modules-2").rmdir()
        self.assertNotEqual(self.prepare().returncode, 0)
        self.assertFalse(self.cache.exists())


if __name__ == "__main__":
    unittest.main()
