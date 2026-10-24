#!/bin/sh
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
zig_bin=${ZIG:-/Users/dttdrv/.local/zig-0.16.0/zig}
scratch=$(mktemp -d "${TMPDIR:-/tmp}/misul-browser.XXXXXX")
cleanup() {
  pkill -f "$scratch/stub.py" 2>/dev/null || true
  rm -rf "$scratch"
}
trap cleanup EXIT INT TERM

port=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
cat >"$scratch/stub-driver.sh" <<SH
#!/bin/sh
exec python3 "$scratch/stub.py"
SH
chmod +x "$scratch/stub-driver.sh"

cat >"$scratch/stub.py" <<'PY'
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

record = os.environ["MISUL_BROWSER_RECORD"]
port = int(os.environ["MISUL_BROWSER_PORT"])
snapshot = {"url": "https://example.com/", "title": "Example", "elements": [
    {"i": 0, "role": "link", "name": "More", "tag": "a", "href": "/more", "value": "", "rect": {"x": 8, "y": 9, "width": 40, "height": 20}, "xpath": "//a[1]"},
    {"i": 1, "role": "textbox", "name": "Search", "tag": "input", "href": "", "value": "", "rect": {"x": 8, "y": 40, "width": 200, "height": 24}, "xpath": "//input[1]"},
    {"i": 2, "role": "combobox", "name": "Size", "tag": "select", "href": "", "value": "", "rect": {"x": 8, "y": 70, "width": 120, "height": 24}, "xpath": "//select[1]"},
]}
page_text = "Example page with a More link and a Search box"
png_1x1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
sessions = set()

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        with open(record, "ab") as handle:
            handle.write(self.command.encode() + b" " + self.path.encode() + b" " + raw + b"\n")
        return json.loads(raw or b"{}")

    def reply(self, value, status=200):
        data = json.dumps({"value": value}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/status":
            return self.reply({"ready": True, "message": "stub"})
        if self.path.endswith("/url"):
            return self.reply("https://example.com/")
        if self.path.endswith("/window/handles"):
            return self.reply(["stub-handle-1", "stub-handle-2"])
        if self.path.endswith("/window"):
            return self.reply("stub-handle-1")
        if self.path.endswith("/screenshot"):
            return self.reply(png_1x1)
        if self.path.endswith("/alert/text"):
            return self.reply("Confirm leaving?")
        return self.reply({"error": "unknown command", "message": "stub: " + self.path}, 404)

    def do_POST(self):
        params = self.body()
        if self.path == "/session":
            sessions.add("stub-session-1")
            return self.reply({"sessionId": "stub-session-1"})
        if self.path.endswith("/url"):
            return self.reply(None)
        if self.path.endswith("/element"):
            return self.reply({"element-6066-11e4-a52e-4f735466cecf": "stub-element-1"})
        if self.path.endswith("/execute/sync"):
            script = params.get("script", "")
            if "querySelectorAll" in script:
                return self.reply(snapshot)
            if "innerText" in script:
                return self.reply(page_text)
            if "scrollBy" in script:
                return self.reply({"x": 0, "y": -300})
            if "scrollIntoView" in script:
                return self.reply({"x": 8, "y": 9})
            if "dispatchEvent" in script:
                return self.reply("large")
            return self.reply(snapshot)
        if self.path.endswith("/window/new"):
            return self.reply({"handle": "stub-handle-3"})
        if self.path.endswith("/click") or self.path.endswith("/clear") or self.path.endswith("/value") or self.path.endswith("/back") or self.path.endswith("/forward") or self.path.endswith("/window") or self.path.endswith("/actions") or self.path.endswith("/alert/accept") or self.path.endswith("/alert/dismiss"):
            return self.reply(None)
        return self.reply({"error": "unknown command", "message": "stub: " + self.path}, 404)

    def do_DELETE(self):
        self.body()
        if self.path == "/session/stub-session-1":
            sessions.discard("stub-session-1")
        return self.reply(None)

HTTPServer(("127.0.0.1", port), Handler).serve_forever()
PY

cd "$root_dir"
ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$scratch/zig-cache}" "$zig_bin" build --prefix "$scratch/prefix"

MISUL_BROWSER_BINARY="$scratch/stub-driver.sh" \
MISUL_BROWSER_PORT="$port" \
MISUL_BROWSER_RECORD="$scratch/driver.requests" \
OPENAI_API_KEY=fixture \
HOME="$scratch/home" \
python3 - "$scratch/prefix/bin/misul" <<'PY'
import json
import os
import subprocess
import sys

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

def expect_error(request_id, method, params):
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    while True:
        value = json.loads(process.stdout.readline())
        if value.get("id") == request_id:
            assert "error" in value, value
            return value["error"]

try:
    handshake = request(1, "rpc/handshake", {"protocol_major": 1, "protocol_minor": 0})
    assert handshake["capabilities"]["browser"] is True
    def record_hits():
        path = os.environ["MISUL_BROWSER_RECORD"]
        return len(open(path, encoding="utf-8").readlines()) if os.path.exists(path) else 0
    hits_before_bad = record_hits()
    bad = expect_error(2, "browser/navigate", {"url": "file:///etc/passwd"})
    assert bad["code"] == -32602, bad
    assert record_hits() == hits_before_bad
    started = request(3, "browser/start", {})
    assert started["status"] == "succeeded" and started["session_id"] == "stub-session-1"
    again = request(4, "browser/start", {})
    assert again["session_id"] == "stub-session-1"
    no_snapshot = expect_error(5, "browser/click", {"index": 0})
    assert no_snapshot["message"] == "No browser snapshot: call browser/snapshot first", no_snapshot
    bad_index = expect_error(6, "browser/click", {"index": 500})
    assert bad_index["code"] == -32602, bad_index
    missing_text = expect_error(7, "browser/fill", {"index": 1})
    assert missing_text["code"] == -32602, missing_text
    navigated = request(8, "browser/navigate", {"url": "https://example.com/"})
    assert navigated["status"] == "succeeded" and navigated["url"] == "https://example.com/"
    snap = request(9, "browser/snapshot", {})
    assert snap["status"] == "succeeded" and len(snap["snapshot"]["elements"]) == 3
    assert snap["snapshot"]["elements"][0]["role"] == "link"
    assert snap["snapshot"]["elements"][0]["rect"]["width"] == 40
    assert snap["snapshot"]["dialog"] == "Confirm leaving?"
    clicked = request(10, "browser/click", {"index": 0})
    assert clicked["status"] == "succeeded" and clicked["index"] == 0
    filled = request(11, "browser/fill", {"index": 1, "text": "hello"})
    assert filled["status"] == "succeeded" and filled["index"] == 1
    backed = request(12, "browser/back", {})
    assert backed["status"] == "succeeded"
    forwarded = request(14, "browser/forward", {})
    assert forwarded["status"] == "succeeded"
    tabs = request(15, "browser/tabs", {})
    assert tabs["status"] == "succeeded" and tabs["tabs"] == ["stub-handle-1", "stub-handle-2"]
    new_tab = request(16, "browser/tab_new", {"url": "https://example.com/more"})
    assert new_tab["status"] == "succeeded" and new_tab["handle"] == "stub-handle-3"
    switched = request(17, "browser/tab_switch", {"handle": "stub-handle-1"})
    assert switched["status"] == "succeeded"
    tab_closed = request(18, "browser/tab_close", {"handle": "stub-handle-2"})
    assert tab_closed["status"] == "succeeded"
    shot = request(19, "browser/screenshot", {})
    assert shot["status"] == "succeeded" and shot["screenshot"]["mimeType"] == "image/png"
    assert shot["screenshot"]["width"] == 1 and shot["screenshot"]["height"] == 1
    keyed = request(20, "browser/key", {"key": "enter", "modifiers": ["ctrl"]})
    assert keyed["status"] == "succeeded"
    bad_key = expect_error(21, "browser/key", {"key": "hyper"})
    assert bad_key["code"] == -32602, bad_key
    selected = request(22, "browser/select", {"index": 2, "value": "large"})
    assert selected["status"] == "succeeded" and selected["index"] == 2
    upload_path = os.path.join(os.environ["HOME"], "upload.txt")
    os.makedirs(os.environ["HOME"], exist_ok=True)
    open(upload_path, "w").write("fixture")
    uploaded = request(23, "browser/upload", {"index": 1, "path": upload_path})
    assert uploaded["status"] == "succeeded"
    relative_upload = expect_error(24, "browser/upload", {"index": 1, "path": "relative.txt"})
    assert relative_upload["code"] == -32602, relative_upload
    dialog = request(25, "browser/dialog", {"accept": True})
    assert dialog["status"] == "succeeded"
    scrolled = request(26, "browser/scroll", {"y": -300})
    assert scrolled["status"] == "succeeded"
    scrolled_el = request(27, "browser/scroll", {"index": 0})
    assert scrolled_el["status"] == "succeeded"
    bad_scroll = expect_error(28, "browser/scroll", {})
    assert bad_scroll["code"] == -32602, bad_scroll
    hovered = request(29, "browser/hover", {"index": 0})
    assert hovered["status"] == "succeeded"
    doubled = request(30, "browser/double_click", {"index": 0})
    assert doubled["status"] == "succeeded"
    right = request(31, "browser/right_click", {"index": 0})
    assert right["status"] == "succeeded"
    dragged = request(32, "browser/drag", {"from_index": 0, "to_index": 1})
    assert dragged["status"] == "succeeded"
    waited = request(33, "browser/wait", {"text": "More", "timeout_ms": 2000})
    assert waited["status"] == "succeeded" and waited["matched"] is True and waited["samples"] >= 1
    missed = request(34, "browser/wait", {"text": "never-appears-xyz", "timeout_ms": 200})
    assert missed["status"] == "succeeded" and missed["matched"] is False
    extracted = request(35, "browser/extract", {})
    assert extracted["status"] == "succeeded" and "More" in extracted["text"]
    closed = request(13, "browser/close", {})
    assert closed["status"] == "succeeded"
finally:
    if process.poll() is None:
        process.terminate()
    process.wait(timeout=5)

event_logs = list((__import__("pathlib").Path(os.environ["HOME"]) / ".local" / "state" / "misul" / "workspaces").glob("*/misul.events"))
assert len(event_logs) == 1, event_logs
durable = event_logs[0].read_bytes()
for witness in (b'"capability":"browser_observe"', b'"capability":"browser_interact"', b'"decision":"allow"', b'"status":"success"', b'v1;method=browser_snapshot;result_sha256=', b'v1;method=browser_click;', b'v1;method=browser_fill;', b'v1;method=browser_navigate;', b'v1;method=browser_tabs;', b'v1;method=browser_key;', b'v1;method=browser_dialog;', b'v1;method=browser_wait;', b'v1;method=browser_extract;', b'v1;method=browser_screenshot;'):
    assert witness in durable, witness
print("browser integration: pass")
PY
