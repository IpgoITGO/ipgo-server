"""Package a tested JAR or verify one exact successful dev CI artifact; no AWS writes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile

from verify_deployment import GateError, http_json, pages, require

REPOSITORY = "IpgoITGO/ipgo-server"
MAX_JAR = 100 * 1024 * 1024
MAX_ARCHIVE = MAX_JAR + 1024 * 1024
DOWNLOAD_TIMEOUT_SECONDS = 120


def source_sha(env):
    sha = env.get("DEPLOYMENT_SOURCE_SHA") or env.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[a-f0-9]{40}", sha), "Full source SHA required")
    require(env.get("GITHUB_REPOSITORY") == REPOSITORY, "Artifact repository mismatch")
    require(not env.get("EXPECTED_SHA") or env["EXPECTED_SHA"] == sha, "Approved SHA mismatch")
    return sha


def artifact_name(sha, attempt):
    return f"server-runtime-linux-arm64-{sha}-{attempt}"


def package(jar, output, env):
    sha = source_sha(env)
    run_id, attempt = int(env["GITHUB_RUN_ID"]), int(env["GITHUB_RUN_ATTEMPT"])
    require(run_id > 0 and attempt > 0, "Invalid producer run")
    require(jar.is_file() and not jar.is_symlink() and 0 < jar.stat().st_size <= MAX_JAR,
            "Invalid JAR size")
    require(not output.exists(), "Artifact directory must be fresh")
    output.mkdir(mode=0o700)
    shutil.copyfile(jar, output / "app.jar")
    with (output / "app.jar").open("rb") as stream:
        jar_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    manifest = {"protocol": 1, "repository": REPOSITORY, "source_sha": sha,
                "platform": "linux/arm64", "run_id": run_id, "run_attempt": attempt,
                "jar_sha256": jar_hash,
                "jar_size": jar.stat().st_size}
    (output / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return manifest


def select_artifact(env, evidence, get):
    sha = source_sha(env)
    require(evidence.get("source_sha") == sha and evidence.get("service") == "server",
            "Approved CI evidence mismatch")
    run_id = evidence.get("test_run")
    require(type(run_id) is int and run_id > 0, "Verified CI run required")
    run = get(f"/repos/{REPOSITORY}/actions/runs/{run_id}")
    require(run["id"] == run_id and run["head_sha"] == sha and run["head_branch"] == "dev"
            and run["event"] == "push" and run["path"] == ".github/workflows/test.yml"
            and run["head_repository"]["full_name"] == REPOSITORY
            and run["status"] == "completed" and run["conclusion"] == "success", "Invalid producer CI")
    attempt = run["run_attempt"]
    require(type(attempt) is int and attempt > 0, "Invalid producer attempt")
    artifacts = pages(get, f"/repos/{REPOSITORY}/actions/runs/{run_id}/artifacts", "artifacts")
    matches = [a for a in artifacts if a["name"] == artifact_name(sha, attempt)]
    require(len(matches) == 1, "Runtime artifact missing or ambiguous; no fallback rebuild")
    artifact = matches[0]
    require(artifact["expired"] is False and type(artifact["id"]) is int and artifact["id"] > 0
            and 0 < artifact["size_in_bytes"] <= MAX_ARCHIVE, "Invalid runtime artifact")
    producer = artifact["workflow_run"]
    require(producer["id"] == run_id and producer["head_sha"] == sha
            and producer["head_branch"] == "dev", "Artifact producer mismatch")
    require(re.fullmatch(r"sha256:[a-f0-9]{64}", artifact.get("digest", "")),
            "GitHub artifact digest required")
    return artifact, run


def verify_archive(archive, artifact, run, output):
    require(0 < archive.stat().st_size <= MAX_ARCHIVE, "Archive size rejected")
    with archive.open("rb") as stream:
        digest = "sha256:" + hashlib.file_digest(stream, "sha256").hexdigest()
    require(digest == artifact["digest"], "GitHub artifact digest mismatch")
    with zipfile.ZipFile(archive) as bundle:
        entries = bundle.infolist()
        require(len(entries) == 2 and {e.filename for e in entries} == {"app.jar", "manifest.json"},
                "Unexpected archive members")
        for entry in entries:
            mode = entry.external_attr >> 16
            require(not entry.is_dir() and not stat.S_ISLNK(mode)
                    and stat.S_IFMT(mode) in (0, stat.S_IFREG), "Unsafe artifact member")
            require(0 < entry.file_size <= (4096 if entry.filename == "manifest.json" else MAX_JAR),
                    "Artifact member size rejected")
        manifest = json.loads(bundle.read("manifest.json"))
        require(manifest["protocol"] == 1 and manifest["repository"] == REPOSITORY
                and manifest["source_sha"] == run["head_sha"] and manifest["platform"] == "linux/arm64"
                and manifest["run_id"] == run["id"] and manifest["run_attempt"] == run["run_attempt"],
                "Runtime manifest identity mismatch")
        jar = bundle.read("app.jar")
        require(manifest["jar_size"] == len(jar)
                and manifest["jar_sha256"] == hashlib.sha256(jar).hexdigest(), "JAR digest mismatch")
    require(not output.exists(), "Runtime directory must be fresh")
    output.mkdir(mode=0o700)
    (output / "app.jar").write_bytes(jar)
    (output / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return {"runtime_artifact_reused": True, "source_sha": run["head_sha"],
            "ci_run": run["id"], "ci_attempt": run["run_attempt"],
            "jar_sha256": manifest["jar_sha256"]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("package", "fetch"))
    parser.add_argument("--output", type=Path, default=Path(".prepared-runtime"))
    args = parser.parse_args()
    stage = "arguments"
    try:
        os.umask(0o077)
        if args.command == "package":
            stage = "package"
            require(os.environ.get("RUNNER_ARCH") == "ARM64", "Native ARM producer required")
            jars = [p for p in Path("build/libs").glob("*.jar") if not p.name.endswith("-plain.jar")]
            require(len(jars) == 1, "One tested bootJar required")
            result = package(jars[0], args.output, os.environ)
        else:
            stage = "ci_evidence"
            require(os.environ.get("COMPUTE_MODE") == "onebox", "Native ARM artifact required")
            evidence = json.loads((Path(os.environ["RUNNER_TEMP"]) / "approved-ci.json").read_text())
            get = lambda path: http_json("https://api.github.com" + path, os.environ["GH_TOKEN"])
            stage = "artifact_metadata"
            artifact, run = select_artifact(os.environ, evidence, get)
            with tempfile.TemporaryDirectory(prefix="ipgo-verified-runtime-") as directory:
                archive = Path(directory) / "runtime.zip"
                # Use gh's authenticated artifact endpoint; do not construct or log signed download URLs.
                with archive.open("wb") as stream:
                    stage = "archive_download"
                    subprocess.run(["gh", "api", f"repos/{REPOSITORY}/actions/artifacts/{artifact['id']}/zip"],
                                   stdout=stream, stderr=subprocess.PIPE,
                                   timeout=DOWNLOAD_TIMEOUT_SECONDS, check=True)
                stage = "archive_verification"
                result = verify_archive(archive, artifact, run, args.output)
        print(json.dumps(result))
        return 0
    except (GateError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError):
        print(f"::error::Approved runtime artifact rejected at {stage}; no fallback rebuild or deploy.",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
