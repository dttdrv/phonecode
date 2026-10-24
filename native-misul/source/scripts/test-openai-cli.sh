#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/misul-openai-test.XXXXXX")
server_pid=
cleanup() {
  if [ -n "$server_pid" ]; then kill "$server_pid" 2>/dev/null || true; fi
  rm -rf "$tmp"
}
trap cleanup EXIT INT TERM
mkdir "$tmp/home"

cat >"$tmp/server.py" <<'PY'
import json
import pathlib
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

root = pathlib.Path(sys.argv[1])
request_number = 0
class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        global request_number
        request_number += 1
        body = self.rfile.read(int(self.headers["content-length"]))
        root.joinpath(f"request-{request_number}.json").write_bytes(body)
        chunks = [
            {"choices": [{"index": 0, "delta": {"content": f"native transport ok {request_number}"}}]},
            {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]},
            {"choices": [], "usage": {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}},
        ]
        response = b"".join(b"data:" + json.dumps(chunk).encode() + b"\n\n" for chunk in chunks) + b"data:[DONE]\n\n"
        self.send_response(200)
        self.send_header("content-type", "text/event-stream")
        self.send_header("content-length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)
    def log_message(self, *_):
        pass

server = HTTPServer(("127.0.0.1", 0), Handler)
root.joinpath("port").write_text(str(server.server_port))
for _ in range(2):
    server.handle_request()
PY
python3 "$tmp/server.py" "$tmp" &
server_pid=$!
while [ ! -s "$tmp/port" ]; do sleep 0.02; done
port=$(cat "$tmp/port")

run() {
  cd "$root"
  HOME="$tmp/home" ./zig-out/bin/misul run \
    --base-url "http://127.0.0.1:$port/v1" \
    --api-key test-key \
    --model test-model \
    --system-prompt "STABLE_TEST_POLICY" \
    --session "openai-cli-test" \
    "$1"
}
first=$(run "inspect the native harness")
second=$(run "continue with verification")
wait "$server_pid"
server_pid=
[ "$first" = "native transport ok 1" ]
[ "$second" = "native transport ok 2" ]

python3 - "$tmp" "$root" <<'PY'
import json
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
cwd = sys.argv[2]
first = json.loads(root.joinpath("request-1.json").read_text())
second = json.loads(root.joinpath("request-2.json").read_text())
systems = [
    {"role": "system", "content": "STABLE_TEST_POLICY"},
    {"role": "system", "content": "Current working directory: " + cwd},
]
assert first["model"] == "test-model"
assert first["messages"] == systems + [{"role": "user", "content": "inspect the native harness"}], first["messages"]
assert first["max_completion_tokens"] == 4096
assert second["messages"] == systems + [
    {"role": "user", "content": "inspect the native harness"},
    {"role": "assistant", "content": "native transport ok 1"},
    {"role": "user", "content": "continue with verification"},
], second["messages"]
PY

echo "openai-cli-ok"
