#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig=${ZIG:-/tmp/misul-zig-0.16.0/zig}
global_cache=${ZIG_GLOBAL_CACHE_DIR:-/private/tmp/misul-zig-global}
reproducer="$root/../.dttdrv/repro/runtime-01-divergence.sh"
cache_dir=$(mktemp -d "/private/tmp/misul-runtime-composition-cache.XXXXXX")
witness_dir=
witness_server_pid=

cleanup() {
    if [ -n "${witness_server_pid:-}" ]; then kill "$witness_server_pid" 2>/dev/null || true; fi
    if [ -n "${witness_dir:-}" ]; then rm -rf "$witness_dir"; fi
    rm -rf "$cache_dir"
}
trap cleanup EXIT INT TERM

build() {
    optimize=$1
    cd "$root"
    env ZIG_GLOBAL_CACHE_DIR="$global_cache" "$zig" build --cache-dir "$cache_dir" -Doptimize="$optimize"
}

tests() {
    optimize=$1
    test_cwd="$cache_dir/test-cwd-$optimize"
    test_cache="$cache_dir/test-cache-$optimize"
    mkdir -p "$test_cwd" "$test_cache"
    cd "$test_cwd"
    env ZIG_GLOBAL_CACHE_DIR="$global_cache" "$zig" build --build-file "$root/build.zig" --cache-dir "$test_cache" test -Doptimize="$optimize" --summary all
}

run_http_witness() {
    witness_dir=$(mktemp -d "${TMPDIR:-/tmp}/misul-runtime-composition.XXXXXX")

    ROOT="$root" WITNESS_DIR="$witness_dir" python3 - <<'PY'
import json
import os
import pathlib
import select
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

root = pathlib.Path(os.environ["ROOT"])
witness = pathlib.Path(os.environ["WITNESS_DIR"])
profiles = {
    "profiles": [
        {
            "id": "composition-model",
            "name": "Composition model",
            "provider": "openai-compatible",
            "context_window": 8192,
            "output_limit": 91,
            "max_output_tokens": 512,
            "cache_retention": "short",
            "cache_aggressiveness": "balanced",
            "transport": "auto",
        },
        {
            "id": "policy-model",
            "name": "Policy model",
            "provider": "openai-compatible",
            "context_window": 65536,
            "output_limit": 333,
            "max_output_tokens": 4096,
            "reasoning": "high",
            "cache_retention": "long",
            "cache_aggressiveness": "aggressive",
            "transport": "auto",
        },
    ]
}
environment = os.environ.copy()
test_home = witness / "home"
test_home.mkdir()
environment["HOME"] = str(test_home)
environment["MISUL_MODEL_PROFILES_JSON"] = json.dumps(profiles, separators=(",", ":"))
environment["OPENAI_API_KEY"] = "composition-test-key"
release_success = threading.Event()
release_abort = threading.Event()
release_eof = threading.Event()
request_started = [threading.Event() for _ in range(16)]
prompt_started = {name: threading.Event() for name in ("composition-cli", "composition-rpc", "abort-active", "eof-active")}
request_lock = threading.Lock()
request_count = 0


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        global request_count
        with request_lock:
            request_count += 1
            number = request_count
        body = self.rfile.read(int(self.headers["content-length"]))
        witness.joinpath(f"request-{number}.json").write_bytes(body)
        request_started[number].set()
        request = json.loads(body)
        prompt = request["messages"][-1]["content"]
        prompt_started.get(prompt, threading.Event()).set()
        if prompt == "composition-rpc":
            assert release_success.wait(10), "success response was never released"
        elif prompt == "abort-active":
            assert release_abort.wait(10), "abort response was never released"
        elif prompt == "eof-active":
            assert release_eof.wait(10), "EOF response was never released"
        response = json.dumps({
            "choices": [{"message": {"content": "server-derived:" + prompt}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10},
        }).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(response)))
        self.end_headers()
        try:
            self.wfile.write(response)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *_):
        pass


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
server_thread = threading.Thread(target=server.serve_forever, daemon=True)
server_thread.start()
base_url = f"http://127.0.0.1:{server.server_port}/v1"
provider_configs = {
    "providers": [
        {
            "id": "openai-compatible",
            "endpoint": base_url,
            "credential_ref": "composition-test-key",
            "credential_source": "explicit",
            "headers": [{"name": "x-misul-fixture", "value": "runtime-composition"}],
            "dialect": "openai_chat",
            "models": [
                {
                    "id": "composition-model",
                    "context_window": 8192,
                    "output_limit": 512,
                    "short_cache_retention": "prompt_cache_options_ttl_30m",
                },
                {
                    "id": "policy-model",
                    "context_window": 65536,
                    "output_limit": 4096,
                    "reasoning": True,
                    "reasoning_efforts": ["high"],
                    "long_cache_retention": "prompt_cache_retention_24h",
                },
            ],
            "retry": {"max_retries": 0, "max_retry_delay_ms": 1000, "deadline_ms": 10000},
        }
    ]
}
environment["MISUL_PROVIDER_CONFIGS_JSON"] = json.dumps(provider_configs, separators=(",", ":"))


def read_line(process, timeout=10):
    ready, _, _ = select.select([process.stdout], [], [], timeout)
    assert ready, "timed out waiting for an RPC line"
    line = process.stdout.readline()
    assert line, f"RPC closed unexpectedly: {process.stderr.read()}"
    return json.loads(line)


cli = subprocess.run(
    [
        str(root / "zig-out/bin/misul"),
        "run",
        "--base-url", base_url,
        "--api-key", "composition-test-key",
        "--model", "composition-model",
        "--max-tokens", "91",
        "--session", str(witness / "session.jsonl"),
        "composition-cli",
    ],
    cwd=root,
    env=environment,
    text=True,
    capture_output=True,
    timeout=20,
)
assert cli.returncode == 0, cli.stderr
assert cli.stdout.strip() == "server-derived:composition-cli", cli.stdout

too_large = subprocess.run(
    [
        str(root / "zig-out/bin/misul"),
        "run",
        "--base-url", base_url,
        "--api-key", "composition-test-key",
        "--model", "composition-model",
        "--max-tokens", "513",
        "must-not-reach-provider",
    ],
    cwd=root,
    env=environment,
    text=True,
    capture_output=True,
    timeout=10,
)
assert too_large.returncode == 2, too_large
assert too_large.stdout == "", too_large.stdout
assert too_large.stderr.strip() == "error: --max-tokens exceeds the selected model profile", too_large.stderr

policy_profile = subprocess.run(
    [
        str(root / "zig-out/bin/misul"),
        "run",
        "--base-url", base_url,
        "--api-key", "composition-test-key",
        "--model", "policy-model",
        "must-fail-before-http",
    ],
    cwd=root,
    env=environment,
    text=True,
    capture_output=True,
    timeout=10,
)
assert policy_profile.returncode == 1, policy_profile
assert policy_profile.stdout == "", policy_profile.stdout
assert policy_profile.stderr.strip() == "error: provider request failed", policy_profile.stderr
assert request_count == 1, "policy profile did not reach the provider option validator before HTTP"

unknown = subprocess.run(
    [str(root / "zig-out/bin/misul"), "rpc", "--model", "adversarial-8k"],
    cwd=root,
    env=environment,
    input='{"jsonrpc":"2.0","id":1,"method":"model/list","params":{}}\n',
    text=True,
    capture_output=True,
    timeout=10,
)
assert unknown.returncode == 2, unknown
assert unknown.stdout == "", unknown.stdout
assert unknown.stderr.strip() == "error: selected model is absent from MISUL_MODEL_PROFILES_JSON", unknown.stderr

rpc_env = environment.copy()
rpc_env["OPENAI_BASE_URL"] = base_url
rpc = subprocess.Popen(
    [str(root / "zig-out/bin/misul"), "rpc", "--model", "composition-model"],
    cwd=root,
    env=rpc_env,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    bufsize=1,
)


def send(value):
    rpc.stdin.write(json.dumps(value, separators=(",", ":")) + "\n")
    rpc.stdin.flush()


send({"jsonrpc": "2.0", "id": 0, "method": "rpc/handshake", "params": {"protocol_major": 1, "protocol_minor": 0}})
handshake = read_line(rpc)
assert handshake["result"]["protocol"] == {"name": "misul-rpc", "major": 1, "minor": 0}, handshake
assert handshake["result"]["capabilities"]["session_prompt"] is True, handshake
send({"jsonrpc": "2.0", "id": 1, "method": "model/list", "params": {}})
catalog_line = read_line(rpc)
catalog = catalog_line["result"]
assert catalog["source"] == "runtime-profile-registry", catalog
by_id = {item["id"]: item for item in catalog["models"]}
assert by_id["composition-model"]["context_window"] == 8192, by_id
assert by_id["composition-model"].get("configured_reasoning") is None, by_id
assert by_id["composition-model"]["cache_retention"] == "short", by_id
assert by_id["composition-model"]["cache_aggressiveness"] == "balanced", by_id
assert by_id["composition-model"]["transport"] == "auto", by_id
assert by_id["policy-model"]["context_window"] == 65536, by_id
assert by_id["policy-model"]["configured_reasoning"] == "high", by_id
assert by_id["policy-model"]["cache_retention"] == "long", by_id
assert by_id["policy-model"]["cache_aggressiveness"] == "aggressive", by_id
assert by_id["policy-model"]["transport"] == "websocket", by_id

send({"jsonrpc": "2.0", "id": 2, "method": "session/prompt", "params": {"prompt": "composition-rpc"}})
success_lines = [read_line(rpc)]
assert success_lines[0].get("method") == "agent_start", success_lines
assert prompt_started["composition-rpc"].wait(10), "held provider request did not start"
assert not release_success.is_set()
release_success.set()
while not any(line.get("method") == "agent_end" for line in success_lines):
    success_lines.append(read_line(rpc))
success_kinds = [line.get("method", "response") for line in success_lines]
assert success_kinds == ["agent_start", "message_delta", "agent_end"], success_lines
assert success_lines[1]["params"]["text_delta"] == "server-derived:composition-rpc", success_lines
assert success_lines[2]["params"]["stop_reason"] == "complete", success_lines
send({"jsonrpc": "2.0", "id": 20, "method": "session/abort", "params": {}})
while not ({2, 20} <= {line.get("id") for line in success_lines}):
    success_lines.append(read_line(rpc))
prompt_success = next(line for line in success_lines if line.get("id") == 2)
late_abort = next(line for line in success_lines if line.get("id") == 20)
assert prompt_success["result"]["status"] == "completed", success_lines
assert prompt_success["result"]["stop_reason"] == "complete", success_lines
assert prompt_success["result"]["content"] == "server-derived:composition-rpc", success_lines
if "error" in late_abort:
    assert late_abort["error"]["code"] == -32004, success_lines
else:
    assert late_abort["result"]["status"] == "completed", success_lines
    assert late_abort["result"]["stop_reason"] == "complete", success_lines
assert not any(
    line.get("result", {}).get("status") == "canceled"
    and line.get("result", {}).get("stop_reason") == "complete"
    for line in success_lines
), success_lines

send({"jsonrpc": "2.0", "id": 3, "method": "session/prompt", "params": {"prompt": "abort-active"}})
abort_lines = [read_line(rpc)]
assert abort_lines[0].get("method") == "agent_start", abort_lines
assert prompt_started["abort-active"].wait(10), "abort provider request did not start"
send({"jsonrpc": "2.0", "id": 4, "method": "session/abort", "params": {}})
while not ({3, 4} <= {line.get("id") for line in abort_lines}):
    abort_lines.append(read_line(rpc))
release_abort.set()
abort_end = next(index for index, line in enumerate(abort_lines) if line.get("method") == "agent_end")
prompt_final = next(index for index, line in enumerate(abort_lines) if line.get("id") == 3)
abort_final = next(index for index, line in enumerate(abort_lines) if line.get("id") == 4)
assert abort_end < prompt_final and abort_end < abort_final, abort_lines
assert abort_lines[abort_end]["params"]["stop_reason"] == "canceled", abort_lines
assert abort_lines[prompt_final]["result"]["status"] == "canceled", abort_lines
assert abort_lines[abort_final]["result"]["status"] == "canceled", abort_lines
assert all(line.get("result", {}).get("status") != "completed" for line in abort_lines), abort_lines

rpc.stdin.close()
assert rpc.wait(timeout=10) == 0, rpc.stderr.read()
assert rpc.stderr.read() == "", "unexpected RPC stderr"

eof_rpc = subprocess.Popen(
    [str(root / "zig-out/bin/misul"), "rpc", "--model", "composition-model"],
    cwd=root,
    env=rpc_env,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    bufsize=1,
)
eof_rpc.stdin.write('{"jsonrpc":"2.0","id":9,"method":"rpc/handshake","params":{"protocol_major":1,"protocol_minor":0}}\n')
eof_rpc.stdin.write('{"jsonrpc":"2.0","id":10,"method":"session/prompt","params":{"prompt":"eof-active"}}\n')
eof_rpc.stdin.flush()
eof_handshake = read_line(eof_rpc)
assert eof_handshake["id"] == 9 and eof_handshake["result"]["protocol"]["major"] == 1, eof_handshake
eof_lines = [read_line(eof_rpc)]
assert eof_lines[0].get("method") == "agent_start", eof_lines
assert prompt_started["eof-active"].wait(10), "EOF provider request did not start"
eof_rpc.stdin.close()
while not any(line.get("id") == 10 for line in eof_lines):
    eof_lines.append(read_line(eof_rpc))
release_eof.set()
assert eof_rpc.wait(timeout=10) == 0, eof_rpc.stderr.read()
assert eof_rpc.stderr.read() == "", "unexpected EOF RPC stderr"
eof_end = next(index for index, line in enumerate(eof_lines) if line.get("method") == "agent_end")
eof_final = next(index for index, line in enumerate(eof_lines) if line.get("id") == 10)
assert eof_end < eof_final, eof_lines
assert eof_lines[eof_end]["params"]["stop_reason"] == "canceled", eof_lines
assert eof_lines[eof_final]["result"]["status"] == "canceled", eof_lines
assert all(line.get("result", {}).get("status") != "completed" for line in eof_lines), eof_lines

server.shutdown()
server.server_close()
server_thread.join(timeout=5)

requests = [json.loads(path.read_text()) for path in witness.glob("request-*.json")]
by_prompt = {request["messages"][-1]["content"]: request for request in requests}
first = by_prompt["composition-cli"]
second = by_prompt["composition-rpc"]
third = by_prompt["abort-active"]
assert first["model"] == "composition-model" and first["max_tokens"] == 91, first
assert second["model"] == "composition-model" and second["max_tokens"] == 91, second
assert third["model"] == "composition-model" and third["max_tokens"] == 91, third
assert first["messages"][-1]["content"] == "composition-cli", first
assert first["prompt_cache_options"] == {"mode": "implicit", "ttl": "30m"}, first
assert second["prompt_cache_options"] == {"mode": "implicit", "ttl": "30m"}, second
assert second["messages"][-1]["content"] == "composition-rpc", second
assert third["messages"][-1]["content"] == "abort-active", third
assert by_prompt["eof-active"]["model"] == "composition-model", by_prompt["eof-active"]
PY

    rm -rf "$witness_dir"
    witness_dir=
}

run_reproducer() {
    repro_cache="$cache_dir/reproducer"
    mkdir -p "$repro_cache"
    repro_zig="$cache_dir/repro-zig"
    cat >"$repro_zig" <<EOF
#!/bin/sh
command="\$1"
shift
exec "$zig" "\$command" --cache-dir "$repro_cache" "\$@"
EOF
    chmod +x "$repro_zig"
    repro_profiles='{"profiles":[{"id":"gpt-4.1-mini","name":"Reproducer default","provider":"openai-compatible","context_window":8192,"output_limit":128,"max_output_tokens":256,"cache_retention":"none","cache_aggressiveness":"balanced","transport":"auto"},{"id":"o3-mini","name":"Reproducer alternate","provider":"openai-compatible","context_window":16384,"output_limit":128,"max_output_tokens":256,"cache_retention":"none","cache_aggressiveness":"balanced","transport":"auto"}]}'
    if output=$(cd "$root" && env MISUL_MODEL_PROFILES_JSON="$repro_profiles" ZIG="$repro_zig" ZIG_GLOBAL_CACHE_DIR="$global_cache" sh -x "$reproducer" 2>&1); then
        printf '%s\n' "$output" >&2
        echo "runtime divergence reproducer unexpectedly passed" >&2
        exit 1
    fi
    printf '%s\n' "$output" | rg -q '\+ fake_(reasoning|completion)=[0-9]'
    printf '%s\n' "$output" | rg -q '\+ fake_reasoning=0|\+ fake_completion=0'
    build Debug >/dev/null 2>&1
    run_http_witness
    echo "runtime divergence reproducer: repaired"
}

tui_submit_test() {
    tui_cwd="$cache_dir/tui-submit-cwd"
    tui_cache="$cache_dir/tui-submit-cache"
    mkdir -p "$tui_cwd" "$tui_cache"
    if tui_output=$(cd "$tui_cwd" && env ZIG_GLOBAL_CACHE_DIR="$global_cache" "$zig" test -ODebug --cache-dir "$tui_cache" --test-filter 'TUI submit reaches the shared runtime and displays provider content' "$root/src/tui.zig" 2>&1); then
        printf '%s\n' "$tui_output"
    else
        printf '%s\n' "$tui_output" >&2
        return 1
    fi
    if ! printf '%s\n' "$tui_output" | rg -Fq '1/1 tui.test.TUI submit reaches the shared runtime and displays provider content...OK'; then
        echo "focused TUI runtime witness did not execute exactly once" >&2
        return 1
    fi
}

run_existing_production_scripts() {
    cd "$root"
    ./scripts/test-cli-errors.sh
    test_profiles='{"profiles":[{"id":"test-model","name":"Production script model","provider":"openai-compatible","context_window":32768,"output_limit":4096,"max_output_tokens":8192,"cache_retention":"none","cache_aggressiveness":"balanced","transport":"auto"}]}'
    env MISUL_MODEL_PROFILES_JSON="$test_profiles" ./scripts/test-openai-cli.sh
    env MISUL_MODEL_PROFILES_JSON="$test_profiles" ./scripts/test-native-tools.sh
}

scan_production_placeholders() {
    pattern='Analyzing request|fake progress|simulated success|placeholder response|hardcoded completion'
    positive="$cache_dir/placeholder-positive.txt"
    surface="$cache_dir/production-surface.txt"
    printf '%s\n' 'simulated success' >"$positive"
    if ! rg -q -i "$pattern" "$positive"; then
        echo "placeholder scan positive control failed" >&2
        exit 1
    fi
    {
        sed '/^const TestStreamState = struct/,$d' "$root/src/runtime.zig"
        sed '/^const test_model = /,$d' "$root/src/agent_loop.zig"
        sed '/^test "/,$d' "$root/src/tui.zig"
        sed '/^const RpcTestState = struct/,$d' "$root/src/rpc_server.zig"
        sed '/^test "/,$d' "$root/src/main.zig"
    } >"$surface"
    if rg -n -i "$pattern" "$surface"; then
        echo "production composition contains placeholder or fake-success text" >&2
        exit 1
    fi
    if rg -n 'PtyInstance|ApprovalRequest|initDetached|default_models|emit(Approval|Diff)|handle(Pty|Approval|Composer|ContextQuery|RulesLoad|ShadowApply)' "$root/src/rpc_server.zig"; then
        echo "RPC server still contains unreachable simulated capability residue" >&2
        exit 1
    fi
    if rg -n 'context_window = 128_000|context_window = 128000' "$root/src/main.zig" "$root/src/rpc_server.zig"; then
        echo "production selection still contains the reviewed universal context window" >&2
        exit 1
    fi
    echo "production placeholder scan: pass"
}

mode=${1:-all}
case "$mode" in
    --reproducer)
        tui_submit_test
        run_reproducer
        ;;
    --ownership)
        tui_submit_test
        build Debug
        run_http_witness
        scan_production_placeholders
        if rg -n 'std\.process\.run' "$root/src/tui.zig"; then
            echo "TUI still has a per-turn child execution path" >&2
            exit 1
        fi
        echo "production composition ownership: pass"
        ;;
    --events)
        tests Debug
        build Debug
        run_http_witness
        echo "runtime event contract: pass"
        ;;
    --configuration)
        tests Debug
        build Debug
        run_http_witness
        echo "runtime configuration routing: pass"
        ;;
    --builds)
        build Debug
        tests Debug
        build ReleaseSafe
        tests ReleaseSafe
        run_existing_production_scripts
        scan_production_placeholders
        echo "runtime composition builds: pass"
        ;;
    all)
        run_reproducer
        build Debug
        run_http_witness
        tests Debug
        build ReleaseSafe
        tests ReleaseSafe
        run_existing_production_scripts
        scan_production_placeholders
        if rg -n 'std\.process\.run' "$root/src/tui.zig"; then
            echo "TUI still has a per-turn child execution path" >&2
            exit 1
        fi
        echo "production composition ownership: pass"
        echo "runtime event contract: pass"
        echo "runtime configuration routing: pass"
        echo "runtime composition builds: pass"
        ;;
    *)
        echo "usage: $0 [--reproducer|--ownership|--events|--configuration|--builds]" >&2
        exit 2
        ;;
esac
