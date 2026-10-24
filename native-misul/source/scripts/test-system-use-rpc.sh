#!/bin/sh
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig_bin=${ZIG:-/tmp/misul-zig-0.16.0/zig}
scratch=$(mktemp -d "${TMPDIR:-/tmp}/misul-system-use.XXXXXX")
runtime_pid=
cleanup() {
  if [ -n "$runtime_pid" ]; then
    kill "$runtime_pid" 2>/dev/null || true
    wait "$runtime_pid" 2>/dev/null || true
  fi
  rm -rf "$scratch"
}
trap cleanup EXIT INT TERM

cat >"$scratch/sidecar.mjs" <<'JS'
#!/usr/bin/env node
import { appendFileSync } from "node:fs";
import { existsSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";

const record = process.env.MISUL_SYSTEM_USE_RECORD;
appendFileSync(`${record}.pids`, `${process.pid}\n`);
let observations = 0;
function write(id, result) {
  process.stdout.write(`${JSON.stringify({ id, ok: true, result, duration_us: 10 })}\n`);
}
createInterface({ input: process.stdin }).on("line", (line) => {
  appendFileSync(record, `${line}\n`);
  const request = JSON.parse(line);
  if (request.method === "ping") return write(request.id, { pid: process.pid, version: 1 });
  if (request.method === "permissions") {
    const denied = process.env.MISUL_SYSTEM_USE_DENY === "1" || existsSync(`${record}.deny-next`);
    return write(request.id, { accessibility: !denied, screenRecording: !denied });
  }
  if (request.method === "targets") return write(request.id, [{ adapter: "macos", id: "pid:7:window:9", kind: "window", label: "Fixture Window" }]);
  if (request.method === "observe") {
    if (request.target?.id === "pid:crash:window:1" && !existsSync(`${record}.crashed`)) {
      writeFileSync(`${record}.crashed`, "1");
      writeFileSync(`${record}.deny-next`, "1");
      process.exit(9);
    }
    if (request.target?.id === "pid:hung:window:1") return;
    observations += 1;
    const never = request.target?.id === "pid:never:window:1";
    const misleading = request.target?.id === "pid:misleading:window:1";
    const result = {
      semantic: { target: request.target, elements: [{ id: misleading ? "fixture-boot:e2" : "fixture-boot:e1", depth: 0, role: "AXWindow", title: misleading ? "wanted-id" : (!never && observations > 1 ? "Ready" : "Loading") }], truncated: false },
      pointer: { x: 4, y: 5, visible: true },
    };
    if (request.target?.id === "pid:cross:window:1") result.semantic.target = { adapter: "macos", id: "pid:other:window:1", kind: "window", label: "Other" };
    if (request.screenshot) result.screenshot = { mimeType: "image/png", data: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", width: 1, height: 1, pointer: result.pointer };
    if (request.target?.id === "pid:badpng:window:1") result.screenshot.data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC";
    return write(request.id, result);
  }
  if (request.method === "click") {
    if (request.elementId === "slow") return setTimeout(() => write(request.id, { method: "accessibility", elementId: request.elementId, point: null, focusMode: request.focusMode, activated: false }), 1000);
    if (request.elementId === "malformed") return write(request.id, null);
    return write(request.id, { method: request.focusMode === "activate" ? "event" : "accessibility", elementId: request.elementId, point: request.x == null ? null : { x: request.x === 40 ? 999 : request.x, y: request.y }, focusMode: request.focusMode, activated: request.focusMode === "activate" });
  }
  if (request.method === "setValue") return write(request.id, { elementId: request.elementId, actualValue: request.elementId === "echo-only" ? "old" : request.value, focusMode: "background", activated: false });
  if (request.method === "type") {
    if (request.focusMode !== "activate") return write(request.id, null);
    return write(request.id, { method: "event", focusMode: "activate", activated: true, restored: true });
  }
  if (request.method === "scroll") {
    if (request.focusMode !== "activate" || (request.deltaX === 0 && request.deltaY === 0)) return write(request.id, null);
    return write(request.id, { method: "event", focusMode: "activate", activated: true, restored: true });
  }
  if (request.method === "shutdown") {
    write(request.id, { stopping: true });
    process.exit(0);
  }
  process.stdout.write(`${JSON.stringify({ id: request.id, ok: false, error: `Unknown method: ${request.method}`, duration_us: 10 })}\n`);
}).on("close", () => process.exit(0));
JS
chmod +x "$scratch/sidecar.mjs"

cd "$root_dir"
ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$scratch/zig-cache}" "$zig_bin" build --prefix "$scratch/prefix"

MISUL_SYSTEM_USE_BINARY="$scratch/sidecar.mjs" \
MISUL_SYSTEM_USE_RECORD="$scratch/sidecar.requests" \
OPENAI_API_KEY=fixture \
HOME="$scratch/home" \
python3 - "$scratch/prefix/bin/misul" <<'PY'
import json
import os
import subprocess
import sys
import time

process = subprocess.Popen(
    [sys.argv[1], "rpc"],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    env=os.environ,
)
assert process.stdin and process.stdout

def request(request_id, method, params):
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    while True:
        line = process.stdout.readline()
        assert line, process.stderr.read()
        value = json.loads(line)
        if value.get("id") == request_id:
            assert "error" not in value, value
            return value["result"]

try:
    handshake = request(1, "rpc/handshake", {"protocol_major": 1, "protocol_minor": 0})
    assert handshake["capabilities"]["system_use"] is True
    permissions = request(20, "system_use/permissions", {})
    assert permissions == {"accessibility": True, "screenRecording": True}
    target = request(2, "system_use/list", {})["targets"][0]
    observed = request(3, "system_use/observe", {"target": target, "screenshot": True, "max_depth": 2, "max_elements": 8})
    assert observed["semantic"]["elements"][0]["title"] == "Loading"
    assert observed["screenshot"]["mimeType"] == "image/png"
    stable_element_id = observed["semantic"]["elements"][0]["id"]
    acted = request(4, "system_use/action", {"target": target, "name": "click", "element_id": stable_element_id, "focus_mode": "background"})
    assert acted["status"] == "succeeded"
    focused = request(21, "system_use/action", {"target": target, "name": "click", "x": 4, "y": 5, "focus_mode": "activate"})
    assert focused["status"] == "succeeded" and focused["result"]["focusMode"] == "activate"
    clicks_before_float = sum(1 for line in open(os.environ["MISUL_SYSTEM_USE_RECORD"], encoding="utf-8") if json.loads(line)["method"] == "click")
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 28, "method": "system_use/action", "params": {"target": target, "name": "click", "x": 4.5, "y": 5, "focus_mode": "activate"}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    rejected_float = json.loads(process.stdout.readline())
    assert rejected_float["id"] == 28 and rejected_float["error"]["code"] == -32602
    clicks_after_float = sum(1 for line in open(os.environ["MISUL_SYSTEM_USE_RECORD"], encoding="utf-8") if json.loads(line)["method"] == "click")
    assert clicks_after_float == clicks_before_float
    assert request(29, "system_use/list", {})["targets"][0]["id"] == target["id"]
    changed = request(22, "system_use/action", {"target": target, "name": "set_value", "element_id": stable_element_id, "value": "native", "focus_mode": "background"})
    assert changed["status"] == "succeeded" and changed["result"]["elementId"] == stable_element_id and changed["result"]["actualValue"] == "native"
    typed = request(49, "system_use/action", {"target": target, "name": "type", "value": "hello", "focus_mode": "activate"})
    assert typed["status"] == "succeeded" and typed["result"]["activated"] is True
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 50, "method": "system_use/action", "params": {"target": target, "name": "type", "value": "hello", "focus_mode": "background"}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    rejected_type_focus = json.loads(process.stdout.readline())
    assert rejected_type_focus["id"] == 50 and rejected_type_focus["error"]["code"] == -32602
    scrolled = request(51, "system_use/action", {"target": target, "name": "scroll", "element_id": stable_element_id, "delta_y": -300, "focus_mode": "activate"})
    assert scrolled["status"] == "succeeded" and scrolled["result"]["activated"] is True
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 52, "method": "system_use/action", "params": {"target": target, "name": "scroll", "element_id": stable_element_id, "delta_y": 0, "focus_mode": "activate"}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    rejected_scroll_delta = json.loads(process.stdout.readline())
    assert rejected_scroll_delta["id"] == 52 and rejected_scroll_delta["error"]["code"] == -32602
    set_values_before_activate = sum(1 for line in open(os.environ["MISUL_SYSTEM_USE_RECORD"], encoding="utf-8") if json.loads(line)["method"] == "setValue")
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 30, "method": "system_use/action", "params": {"target": target, "name": "set_value", "element_id": stable_element_id, "value": "native", "focus_mode": "activate"}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    rejected_set_focus = json.loads(process.stdout.readline())
    assert rejected_set_focus["id"] == 30 and rejected_set_focus["error"]["code"] == -32602
    set_values_after_activate = sum(1 for line in open(os.environ["MISUL_SYSTEM_USE_RECORD"], encoding="utf-8") if json.loads(line)["method"] == "setValue")
    assert set_values_after_activate == set_values_before_activate
    clicks_before_element_activate = sum(1 for line in open(os.environ["MISUL_SYSTEM_USE_RECORD"], encoding="utf-8") if json.loads(line)["method"] == "click")
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 37, "method": "system_use/action", "params": {"target": target, "name": "click", "element_id": stable_element_id, "focus_mode": "activate"}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    rejected_element_activate = json.loads(process.stdout.readline())
    assert rejected_element_activate["id"] == 37 and rejected_element_activate["error"]["code"] == -32602
    clicks_after_element_activate = sum(1 for line in open(os.environ["MISUL_SYSTEM_USE_RECORD"], encoding="utf-8") if json.loads(line)["method"] == "click")
    assert clicks_after_element_activate == clicks_before_element_activate
    clicks_before_dual = sum(1 for line in open(os.environ["MISUL_SYSTEM_USE_RECORD"], encoding="utf-8") if json.loads(line)["method"] == "click")
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 40, "method": "system_use/action", "params": {"target": target, "name": "click", "element_id": stable_element_id, "x": 4, "y": 5, "focus_mode": "background"}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    dual = json.loads(process.stdout.readline())
    assert dual["id"] == 40 and dual["error"]["code"] == -32602, dual
    assert sum(1 for line in open(os.environ["MISUL_SYSTEM_USE_RECORD"], encoding="utf-8") if json.loads(line)["method"] == "click") == clicks_before_dual
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 41, "method": "system_use/action", "params": {"target": target, "name": "click", "x": 40, "y": 5, "focus_mode": "activate"}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    wrong_point = json.loads(process.stdout.readline())
    assert wrong_point["id"] == 41 and wrong_point["error"]["message"] == "SystemUse service is unavailable", wrong_point
    assert request(42, "system_use/list", {})["targets"][0]["id"] == target["id"]
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 43, "method": "system_use/action", "params": {"target": target, "name": "set_value", "element_id": "echo-only", "value": "new", "focus_mode": "background"}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    echo_only = json.loads(process.stdout.readline())
    assert echo_only["id"] == 43 and echo_only["error"]["message"] == "SystemUse service is unavailable", echo_only
    assert request(44, "system_use/list", {})["targets"][0]["id"] == target["id"]
    cross = {"adapter": "macos", "id": "pid:cross:window:1", "kind": "window", "label": "Cross"}
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 45, "method": "system_use/observe", "params": {"target": cross}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    cross_result = json.loads(process.stdout.readline())
    assert cross_result["id"] == 45 and cross_result["error"]["message"] == "SystemUse service is unavailable", cross_result
    assert request(46, "system_use/list", {})["targets"][0]["id"] == target["id"]
    badpng = {"adapter": "macos", "id": "pid:badpng:window:1", "kind": "window", "label": "Bad PNG"}
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 47, "method": "system_use/observe", "params": {"target": badpng, "screenshot": True}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    badpng_result = json.loads(process.stdout.readline())
    assert badpng_result["id"] == 47 and badpng_result["error"]["message"] == "SystemUse service is unavailable", badpng_result
    assert request(48, "system_use/list", {})["targets"][0]["id"] == target["id"]
    waited = request(5, "system_use/wait", {"wait_id": "wait-5", "target": target, "text": "Ready", "state": "present", "timeout_ms": 1000, "poll_interval_ms": 1})
    assert waited["matched"] is True and waited["samples"] >= 1
    never = {"adapter": "macos", "id": "pid:never:window:1", "kind": "window", "label": "Never Ready"}
    timed_out = request(23, "system_use/wait", {"wait_id": "wait-23", "target": never, "text": "Ready", "state": "present", "timeout_ms": 5, "poll_interval_ms": 1})
    assert timed_out["matched"] is False and timed_out["samples"] >= 1
    bounded_poll_started = time.monotonic()
    bounded_poll = request(38, "system_use/wait", {"wait_id": "wait-38", "target": never, "text": "Ready", "state": "present", "timeout_ms": 1, "poll_interval_ms": 1000})
    assert bounded_poll["matched"] is False and time.monotonic() - bounded_poll_started < 0.2, bounded_poll
    element_wait = request(31, "system_use/wait", {"wait_id": "wait-31", "target": target, "element_id": stable_element_id, "state": "present", "timeout_ms": 50, "poll_interval_ms": 1})
    assert element_wait["matched"] is True
    misleading = {"adapter": "macos", "id": "pid:misleading:window:1", "kind": "window", "label": "Misleading"}
    recycled_wait = request(39, "system_use/wait", {"wait_id": "wait-39", "target": misleading, "element_id": stable_element_id, "state": "present", "timeout_ms": 5, "poll_interval_ms": 1})
    assert recycled_wait["matched"] is False
    hung = {"adapter": "macos", "id": "pid:hung:window:1", "kind": "window", "label": "Hung"}
    hung_started = time.monotonic()
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 32, "method": "system_use/wait", "params": {"wait_id": "wait-32", "target": hung, "text": "Ready", "timeout_ms": 50, "poll_interval_ms": 1}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    hung_result = json.loads(process.stdout.readline())
    assert hung_result["id"] == 32 and hung_result["error"]["message"] == "SystemUse platform operation timed out", hung_result
    assert time.monotonic() - hung_started < 2
    assert request(33, "system_use/list", {})["targets"][0]["id"] == target["id"]
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 6, "method": "system_use/wait", "params": {"wait_id": "wait-42", "target": hung, "text": "Ready", "timeout_ms": 5000, "poll_interval_ms": 10}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 7, "method": "system_use/cancel", "params": {"wait_id": "wait-42"}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    cancellation = {}
    while len(cancellation) < 2:
        value = json.loads(process.stdout.readline())
        if value.get("id") in (6, 7):
            cancellation[value["id"]] = value
    assert cancellation[7].get("result", {}).get("status") == "requested", cancellation
    assert cancellation[6]["error"]["message"] == "SystemUse wait was canceled"

    crashing = {"adapter": "macos", "id": "pid:crash:window:1", "kind": "window", "label": "Crash Once"}
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 8, "method": "system_use/observe", "params": {"target": crashing}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    failed = json.loads(process.stdout.readline())
    assert failed["id"] == 8 and failed["error"]["message"] == "SystemUse service is unavailable"
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 9, "method": "system_use/list", "params": {}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    denied_generation = json.loads(process.stdout.readline())
    assert denied_generation["id"] == 9 and denied_generation["error"]["code"] == -32004, denied_generation
    os.unlink(os.environ["MISUL_SYSTEM_USE_RECORD"] + ".deny-next")
    assert request(34, "system_use/list", {})["targets"][0]["id"] == target["id"]

    generations_before_malformed = len(open(os.environ["MISUL_SYSTEM_USE_RECORD"] + ".pids", encoding="utf-8").read().splitlines())
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 35, "method": "system_use/action", "params": {"target": target, "name": "click", "element_id": "malformed", "focus_mode": "background"}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    malformed = json.loads(process.stdout.readline())
    assert malformed["id"] == 35 and malformed["error"]["message"] == "SystemUse service is unavailable", malformed
    assert request(36, "system_use/list", {})["targets"][0]["id"] == target["id"]
    generations_after_malformed = len(open(os.environ["MISUL_SYSTEM_USE_RECORD"] + ".pids", encoding="utf-8").read().splitlines())
    assert generations_after_malformed == generations_before_malformed + 1

    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 24, "method": "system_use/action", "params": {"target": target, "name": "click", "element_id": "slow", "focus_mode": "background"}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    time.sleep(0.05)
    process.kill()
    process.wait(timeout=2)
    process = subprocess.Popen(
        [sys.argv[1], "rpc"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, env=os.environ,
    )
    assert process.stdin and process.stdout
    restarted = request(25, "rpc/handshake", {"protocol_major": 1, "protocol_minor": 0})
    assert restarted["capabilities"]["system_use"] is True

finally:
    if process.poll() is None:
        process.terminate()
    process.wait(timeout=2)

targets_before_denied_preflight = sum(1 for line in open(os.environ["MISUL_SYSTEM_USE_RECORD"], encoding="utf-8") if json.loads(line)["method"] == "targets")
denied_environment = os.environ.copy()
denied_environment["MISUL_SYSTEM_USE_DENY"] = "1"
denied_environment["HOME"] = os.environ["HOME"] + "-denied"
process = subprocess.Popen(
    [sys.argv[1], "rpc"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    text=True, env=denied_environment,
)
assert process.stdin and process.stdout
try:
    denied_handshake = request(26, "rpc/handshake", {"protocol_major": 1, "protocol_minor": 0})
    assert "system_use" not in denied_handshake["capabilities"]
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 27, "method": "system_use/list", "params": {}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    denied_list = json.loads(process.stdout.readline())
    assert denied_list["id"] == 27 and denied_list["error"]["message"] == "SystemUse is not configured by the shared runtime"
finally:
    process.terminate()
    process.wait(timeout=2)
targets_after_denied_preflight = sum(1 for line in open(os.environ["MISUL_SYSTEM_USE_RECORD"], encoding="utf-8") if json.loads(line)["method"] == "targets")
assert targets_after_denied_preflight == targets_before_denied_preflight

event_logs = list((__import__("pathlib").Path(os.environ["HOME"]) / ".local" / "state" / "misul" / "workspaces").glob("*/misul.events"))
assert len(event_logs) == 1, event_logs
durable = event_logs[0].read_bytes()
for witness in (b'"capability":"system_use_interact"', b'"decision":"allow"', b'"lease_id":"lease-', b'"status":"success"', b'"status":"timeout"', b'"status":"cancelled"', b'"status":"outcome_unknown"'):
    assert witness in durable, witness
for witness in (b'"source_request_id":"rpc:4"', b'"causal_request_id":"rpc:4"', b'"run_id":"rpc:4"', b'"provenance":"rpc:local-user"', b'v1;method=click;result_sha256=', b'v1;method=setValue;result_sha256=', b'v1;method=type;result_sha256=', b'v1;method=scroll;result_sha256=', b'v1;status=failure;method=click;error=InvalidSystemUseResponse'):
    assert witness in durable, witness
import re
immediate = re.search(rb'"action_id":"([^"]+)".{0,4096}?wait_id\\\":42', durable)
assert immediate, "missing durable requested descriptor for immediate wait"
action_id = immediate.group(1)
assert re.search(rb'"action_id":"' + re.escape(action_id) + rb'"[\s\S]*?"status":"cancelled"', durable), action_id
assert b'v1;status=outcome_unknown;stage=restart-orphan;replayed=false' in durable
time.sleep(0.1)
for raw_pid in open(os.environ["MISUL_SYSTEM_USE_RECORD"] + ".pids", encoding="utf-8"):
    pid = int(raw_pid)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        continue
    raise AssertionError(f"stale SystemUse sidecar pid {pid}")
PY

printf '%s\n' "system use rpc integration: pass"
