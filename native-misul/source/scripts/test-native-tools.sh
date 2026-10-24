#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/misul-tools-test.XXXXXX")
server_pid=
cleanup() {
  if [ -n "$server_pid" ]; then kill "$server_pid" 2>/dev/null || true; fi
  rm -rf "$tmp"
}
trap cleanup EXIT INT TERM
mkdir "$tmp/workspace"

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
        if request_number == 1:
            delta = {"tool_calls": [{
                "index": 0, "id": "write-1", "type": "function",
                "function": {"name": "write_file", "arguments": json.dumps({"path": "proof.txt", "content": "native tools work\n"})},
            }]}
            reason = "tool_calls"
        elif request_number == 2:
            delta = {"tool_calls": [{
                "index": 0, "id": "read-1", "type": "function",
                "function": {"name": "read_file", "arguments": json.dumps({"path": "proof.txt"})},
            }]}
            reason = "tool_calls"
        else:
            delta = {"content": "native-tools-ok"}
            reason = "stop"
        chunks = [
            {"choices": [{"index": 0, "delta": delta}]},
            {"choices": [{"index": 0, "delta": {}, "finish_reason": reason}]},
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
for _ in range(3):
    server.handle_request()
PY
python3 "$tmp/server.py" "$tmp" &
server_pid=$!
while [ ! -s "$tmp/port" ]; do sleep 0.02; done
port=$(cat "$tmp/port")

output=$(
  cd "$tmp/workspace"
  MISUL_CACHE_DEBUG=1 "$root/zig-out/bin/misul" run \
    --base-url "http://127.0.0.1:$port/v1" \
    --api-key test-key \
    --model test-model \
    --system-prompt "TOOLS_TEST_POLICY" \
    "create and inspect proof.txt" \
    2>"$tmp/cache.jsonl"
)
wait "$server_pid"
server_pid=
[ "$output" = "native-tools-ok" ]
[ "$(cat "$tmp/workspace/proof.txt")" = "native tools work" ]

python3 - "$tmp" <<'PY'
import json
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
requests = [json.loads(root.joinpath(f"request-{i}.json").read_text()) for i in range(1, 4)]
telemetry = [json.loads(line) for line in root.joinpath("cache.jsonl").read_text().splitlines()]
assert len(telemetry) == 3, telemetry
assert len({call["prefix_hash"] for call in telemetry}) == 1, telemetry
assert all(call["session"] == "" and call["prefix_bytes"] > len("TOOLS_TEST_POLICY") for call in telemetry), telemetry
names = [tool["function"]["name"] for tool in requests[0]["tools"]]
assert names == sorted(names), names
assert names == ["edit_file", "find_files", "glob_files", "grep_files", "list_files", "read_file", "shell", "skill", "write_file"], names
second = requests[1]["messages"]
assert second[-2]["tool_calls"][0]["function"]["name"] == "write_file"
assert second[-1]["role"] == "tool" and "wrote 18 bytes to proof.txt" in second[-1]["content"], second[-1]
third = requests[2]["messages"]
assert third[-2]["tool_calls"][0]["function"]["name"] == "read_file"
assert third[-1]["role"] == "tool" and "native tools work" in third[-1]["content"], third[-1]
PY

echo native-tools-ok
