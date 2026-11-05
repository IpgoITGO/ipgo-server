"""AWS 호출 없이 dev 배포 승인 조건을 검사하고 task definition을 렌더링한다."""
import argparse
import copy
import json
import os
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit


class InvalidDeployment(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidDeployment(message)


def flag(env, name):
    require(env.get(name) in ("true", "false"), f"{name} must be true or false")
    return env[name] == "true"


def settings(env):
    sha = env.get("EXPECTED_SHA", "")
    require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None, "A full approved EXPECTED_SHA is required")
    require(sha == env.get("GITHUB_SHA"), "Workflow SHA differs from the approved SHA")
    mode = env.get("COMPUTE_MODE", "")
    require(mode in ("", "legacy", "onebox"), "Unsupported compute mode")
    maintenance = flag(env, "MAINTENANCE_APPROVED")
    engine = flag(env, "ENGINE_ENABLED")
    connected = flag(env, "ENGINE_CONNECTION_APPROVED")
    insight = flag(env, "INSIGHT_ENABLED")
    approved = flag(env, "INSIGHT_APPROVED")
    worker = flag(env, "INSIGHT_WORKER_ENABLED")
    # Older callers omit the new controls; absence must keep the exception disabled.
    single = flag({"SINGLE_RESULT_INSIGHT_ENABLED": "false", **env}, "SINGLE_RESULT_INSIGHT_ENABLED")
    single_approved = flag({"SINGLE_RESULT_INSIGHT_APPROVED": "false", **env}, "SINGLE_RESULT_INSIGHT_APPROVED")
    require(mode != "onebox" or maintenance, "One-box restart requires maintenance approval")
    require(not engine or connected, "Engine connection approval is required")
    require(not insight or (engine and approved), "Insight activation requires engine and cost approval")
    require(not worker or insight, "Insight worker requires insight activation")
    require(not single or (engine and single_approved and not insight and not worker),
            "Single result 811 requires separate approval and automatic insight/worker disabled")
    require(env.get("DESIRED_COUNT") in ("0", "1"), "Unsupported task count")
    base = env.get("ENGINE_BASE_URL", "")
    require(not engine or bool(base), "AWS_AI_ENGINE_BASE_URL is required when engine is enabled")
    if base:
        require(not any(c.isspace() or ord(c) < 33 or ord(c) == 127 for c in base), "Engine URL must not contain whitespace")
        try:
            uri = urlsplit(base)
            port = uri.port
            require(uri.scheme in ("http", "https") and uri.hostname is not None, "Invalid engine HTTP(S) origin")
            require(uri.username is None and uri.password is None and "?" not in base and "#" not in base
                    and uri.path in ("", "/"), "Engine URL must be an origin without credentials")
            require(port is None or 0 < port <= 65535, "Invalid engine port")
            require(uri.hostname not in ("localhost", "0.0.0.0", "127.0.0.1", "::1"), "Engine origin cannot be loopback")
        except (ValueError, InvalidDeployment):
            raise InvalidDeployment("Invalid engine origin configuration") from None
    return {"sha": sha, "mode": mode, "engine": engine, "insight": insight, "worker": worker, "single": single, "base": base}


def render(task, image, env):
    config = settings(env)
    account = env.get("AWS_ACCOUNT_ID", "")
    require(re.fullmatch(r"[0-9]{12}", account) is not None, "AWS_ACCOUNT_ID is required")
    expected = f"{account}.dkr.ecr.ap-northeast-2.amazonaws.com/ipgo-dev/server:{config['sha']}"
    if config["mode"] == "onebox":
        expected += "-arm64"
    require(image == expected, "Server image must match the approved account and SHA")
    require(isinstance(task, dict), "Invalid task definition")
    family = "ipgo-dev-onebox-server" if config["mode"] == "onebox" else "ipgo-dev-server"
    require(task.get("family") == family, "Task family differs from the approved compute mode")
    result = copy.deepcopy(task)
    servers = [c for c in result.get("containerDefinitions", []) if c.get("name") == "server"]
    require(len(servers) == 1, "Exactly one server container is required")
    server = servers[0]
    previous = server.get("environment", [])
    require(isinstance(previous, list) and all(isinstance(e, dict) and isinstance(e.get("name"), str)
            and isinstance(e.get("value"), str) for e in previous), "Invalid container environment")
    require(not any(e["name"] == "OPENAI_API_KEY" for e in previous)
            and not any(e.get("name") == "OPENAI_API_KEY" for e in server.get("secrets", [])),
            "OpenAI credentials belong only to the AI container")
    managed = {"IPGO_API_DOCS_ENABLED": "true", "IPGO_ENGINE_ENABLED": str(config["engine"]).lower(),
               "IPGO_INSIGHT_ENABLED": str(config["insight"]).lower(),
               "IPGO_INSIGHT_SINGLE_RESULT_ENABLED": str(config["single"]).lower(),
               "IPGO_INSIGHT_WORKER_ENABLED": str(config["worker"]).lower()}
    if config["base"]:
        managed["IPGO_ENGINE_BASE_URL"] = config["base"]
    server["environment"] = [e for e in previous if e["name"] not in managed]
    server["environment"].extend({"name": k, "value": v} for k, v in managed.items())
    server["image"] = image
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("validate", "render"))
    parser.add_argument("--source")
    parser.add_argument("--output")
    parser.add_argument("--image")
    args = parser.parse_args()
    try:
        config = settings(os.environ)
        if args.command == "render":
            require(bool(args.source and args.output and args.image), "Source, output and image are required")
            task = json.loads(Path(args.source).read_text())
            result = render(task, args.image, os.environ)
            # File content can include existing DB secret references; never print the task definition.
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w") as output:
                json.dump(result, output)
        print(json.dumps({"validated": True, "insight_enabled": config["insight"],
                          "single_result_insight_enabled": config["single"],
                          "insight_worker_enabled": config["worker"]}))
    except (InvalidDeployment, OSError, json.JSONDecodeError):
        print("::error::Deployment configuration rejected; verify approved SHA, flags, engine origin and task shape.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
