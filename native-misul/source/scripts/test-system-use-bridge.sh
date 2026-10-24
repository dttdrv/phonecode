#!/bin/sh
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
workspace_dir=$(CDPATH= cd -- "$root_dir/.." && pwd)
zig_bin=${ZIG:-/tmp/misul-zig-0.16.0/zig}
scratch=$(mktemp -d "${TMPDIR:-/tmp}/misul-system-use-bridge.XXXXXX")
cleanup() {
  rm -rf "$scratch"
}
trap cleanup EXIT INT TERM
mkdir -p "$scratch/workspace" "$scratch/home"
workspace_real=$(CDPATH= cd -- "$scratch/workspace" && pwd -P)

cat >"$scratch/sidecar.mjs" <<'JS'
#!/usr/bin/env node
import { createInterface } from "node:readline";
function write(id, result) {
  process.stdout.write(`${JSON.stringify({ id, ok: true, result, duration_us: 1 })}\n`);
}
createInterface({ input: process.stdin }).on("line", (line) => {
  const request = JSON.parse(line);
  if (request.method === "ping") return write(request.id, { pid: process.pid, version: 1 });
  if (request.method === "permissions") return write(request.id, { accessibility: true, screenRecording: true });
  if (request.method === "targets") return write(request.id, [{ adapter: "macos", id: "pid:bridge:window:1", kind: "window", label: "Bridge Fixture" }]);
  if (request.method === "observe") {
    if (request.target?.id === "pid:hung:window:1") return;
    return write(request.id, { semantic: { target: request.target, elements: [{ id: "bridge-boot:e1", depth: 0, role: "AXWindow", title: "Bridge Ready" }], truncated: false }, pointer: { x: 0, y: 0, visible: false } });
  }
  if (request.method === "click") return write(request.id, { method: "accessibility", elementId: request.elementId, point: null, focusMode: request.focusMode, activated: false });
  if (request.method === "setValue") return write(request.id, { elementId: request.elementId, actualValue: request.value, focusMode: "background", activated: false });
  process.stdout.write(`${JSON.stringify({ id: request.id, ok: false, error: "unsupported", duration_us: 1 })}\n`);
});
JS
chmod +x "$scratch/sidecar.mjs"

cat >"$scratch/bridge.mjs" <<'JS'
import { pathToFileURL } from "node:url";
const { startMisulBridge } = await import(pathToFileURL(process.env.BRIDGE_MODULE).href);
const bridge = await startMisulBridge({ binary: process.env.MISUL_BINARY, workspace: process.env.MISUL_WORKSPACE, model: "gpt-4.1-mini" });
const authorization = `Basic ${Buffer.from(`${bridge.username}:${bridge.password}`).toString("base64")}`;
async function call(path, init = {}) {
  const response = await fetch(`${bridge.url}${path}`, { ...init, headers: { origin: "oc://renderer", authorization, "content-type": "application/json", ...init.headers } });
  const body = await response.json();
  if (response.status !== 200) throw new Error(`${response.status}: ${JSON.stringify(body)}`);
  return body.data;
}
try {
  const targets = await call("/api/system-use/targets");
  if (targets.length !== 1 || targets[0].id !== "pid:bridge:window:1") throw new Error("native targets did not traverse bridge");
  const target = targets[0];
  const observed = await call("/api/system-use/observe", { method: "POST", body: JSON.stringify({ target }) });
  if (observed.semantic.elements[0].id !== "bridge-boot:e1") throw new Error("native observation did not traverse bridge");
  const acted = await call("/api/system-use/action", { method: "POST", body: JSON.stringify({ target, name: "click", element_id: "bridge-boot:e1", focus_mode: "background" }) });
  if (acted.status !== "succeeded" || acted.result.focusMode !== "background") throw new Error("native action did not traverse bridge");
  const waited = await call("/api/system-use/wait", { method: "POST", body: JSON.stringify({ wait_id: "wait-76", target, text: "Bridge Ready", timeout_ms: 500, poll_interval_ms: 5 }) });
  if (!waited.matched) throw new Error("native wait did not traverse bridge");
  const hung = { adapter: "macos", id: "pid:hung:window:1", kind: "window", label: "Hung" };
  const wait = call("/api/system-use/wait", { method: "POST", body: JSON.stringify({ wait_id: "wait-77", target: hung, text: "Never", timeout_ms: 5000, poll_interval_ms: 5 }) }).then(() => { throw new Error("hung wait unexpectedly succeeded"); }, (error) => error);
  await new Promise((resolve) => setTimeout(resolve, 50));
  const cancellation = await call("/api/system-use/cancel", { method: "POST", body: JSON.stringify({ wait_id: "wait-77" }) });
  if (cancellation.status !== "requested") throw new Error("native cancellation acknowledgement did not traverse bridge");
  const waitError = await wait;
  if (!String(waitError).includes("canceled")) throw waitError;
} finally {
  await bridge.stop();
}
JS

cd "$root_dir"
ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$scratch/zig-cache}" "$zig_bin" build --prefix "$scratch/prefix"
BRIDGE_MODULE="$workspace_dir/packages/desktop/opencode-app/out/main/misul-bridge.js" \
MISUL_BINARY="$scratch/prefix/bin/misul" \
MISUL_WORKSPACE="$workspace_real" \
MISUL_SYSTEM_USE_BINARY="$scratch/sidecar.mjs" \
OPENAI_API_KEY=fixture \
HOME="$scratch/home" \
node "$scratch/bridge.mjs"

printf '%s\n' "system use bridge to native composition: pass"
