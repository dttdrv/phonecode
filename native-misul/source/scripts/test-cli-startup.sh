#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
bin=${MISUL_BIN:-"$root/zig-out/bin/misul"}
tmp=$(mktemp -d "${TMPDIR:-/tmp}/misul-cli-startup.XXXXXX")
trap 'rm -rf "$tmp"' EXIT INT TERM

"$bin" --help >"$tmp/help.stdout" 2>"$tmp/help.stderr"
grep -q '^Usage:' "$tmp/help.stdout"
grep -q '^Default model: gpt-4.1-mini$' "$tmp/help.stdout"
test ! -s "$tmp/help.stderr"

python3 - "$bin" "$tmp" <<'PY'
import json
import os
import pathlib
import subprocess
import sys

binary = pathlib.Path(sys.argv[1])
temporary = pathlib.Path(sys.argv[2])
requests = "\n".join((
    json.dumps({"jsonrpc": "2.0", "id": 0, "method": "rpc/handshake", "params": {"protocol_major": 1, "protocol_minor": 0}}),
    json.dumps({"jsonrpc": "2.0", "id": 1, "method": "model/list", "params": {}}),
)) + "\n"

def catalog(environment):
    result = subprocess.run(
        [str(binary), "rpc"],
        cwd="/private/tmp",
        env=environment,
        input=requests,
        text=True,
        capture_output=True,
        timeout=10,
    )
    assert result.returncode == 0, result
    assert result.stderr == "", result.stderr
    lines = [json.loads(line) for line in result.stdout.splitlines()]
    assert len(lines) == 2, lines
    assert lines[0]["result"]["protocol"] == {"name": "misul-rpc", "major": 1, "minor": 0}, lines[0]
    return lines[1]["result"]

environment = os.environ.copy()
environment["HOME"] = str(temporary / "bundled-home")
for name in ("MISUL_MODEL_PROFILES_JSON", "MISUL_PROVIDER_CONFIGS_JSON", "MISUL_MODEL", "OPENAI_MODEL"):
    environment.pop(name, None)
bundled = catalog(environment)
assert bundled["source"] == "runtime-profile-registry", bundled
assert bundled["current"] == "gpt-4.1-mini", bundled
assert bundled["models"] == [{
    "id": "gpt-4.1-mini",
    "name": "GPT-4.1 mini",
    "provider": "openai-compatible",
    "context_window": 1047576,
    "max_tokens": 32768,
    "reasoning": False,
    "cache_retention": "none",
    "cache_aggressiveness": "balanced",
    "transport": "auto",
}], bundled

environment["HOME"] = str(temporary / "custom-home")
environment["MISUL_MODEL_PROFILES_JSON"] = json.dumps({"profiles": [{
    "id": "custom",
    "name": "Custom",
    "provider": "custom-provider",
    "context_window": 8192,
    "output_limit": 512,
    "max_output_tokens": 1024,
}]}, separators=(",", ":"))
result = subprocess.run(
    [str(binary), "rpc", "--model", "custom"],
    cwd="/private/tmp",
    env=environment,
    input=requests,
    text=True,
    capture_output=True,
    timeout=10,
)
assert result.returncode == 0, result
assert result.stderr == "", result.stderr
custom = json.loads(result.stdout.splitlines()[1])["result"]
assert custom["current"] == "custom", custom
assert [item["id"] for item in custom["models"]] == ["custom"], custom
assert custom["models"][0]["context_window"] == 8192, custom

workspace = temporary / "persisted-workspace"
workspace.mkdir()
workspace = workspace.resolve()
environment["HOME"] = str(temporary / "persisted-home")
environment["MISUL_MODEL_PROFILES_JSON"] = json.dumps({"profiles": [{
    "id": "legacy-local",
    "name": "Legacy local",
    "provider": "legacy-provider",
    "context_window": 8192,
    "output_limit": 512,
    "max_output_tokens": 512,
}]}, separators=(",", ":"))
seed_requests = "\n".join((
    json.dumps({"jsonrpc": "2.0", "id": 0, "method": "rpc/handshake", "params": {"protocol_major": 1, "protocol_minor": 0}}),
    json.dumps({"jsonrpc": "2.0", "id": 1, "method": "session/start", "params": {"cwd": str(workspace), "model": "legacy-local"}}),
)) + "\n"
seed = subprocess.run(
    [str(binary), "rpc", "--model", "legacy-local"],
    cwd=workspace,
    env=environment,
    input=seed_requests,
    text=True,
    capture_output=True,
    timeout=10,
)
assert seed.returncode == 0, seed
seed_lines = [json.loads(line) for line in seed.stdout.splitlines()]
assert seed_lines[1].get("result", {}).get("model") == "legacy-local", seed_lines

for name in ("MISUL_MODEL_PROFILES_JSON", "MISUL_PROVIDER_CONFIGS_JSON", "MISUL_MODEL", "OPENAI_MODEL"):
    environment.pop(name, None)
recovered = subprocess.run(
    [str(binary), "rpc", "--model", "gpt-4.1-mini"],
    cwd=workspace,
    env=environment,
    input=requests,
    text=True,
    capture_output=True,
    timeout=10,
)
assert recovered.returncode == 0, recovered
assert recovered.stderr == "", recovered.stderr
recovered_lines = [json.loads(line) for line in recovered.stdout.splitlines()]
assert recovered_lines[1].get("result", {}).get("current") == "gpt-4.1-mini", recovered_lines
PY

if [ -n "${MISUL_EXPECTED_SHA256:-}" ]; then
    test "$(shasum -a 256 "$bin" | awk '{print $1}')" = "$MISUL_EXPECTED_SHA256"
fi

echo cli-startup-ok
