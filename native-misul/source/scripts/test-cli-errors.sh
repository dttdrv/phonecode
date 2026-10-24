#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
bin=${MISUL_BIN:-"$root/zig-out/bin/misul"}
tmp=$(mktemp -d "${TMPDIR:-/tmp}/misul-cli-errors.XXXXXX")
trap 'rm -rf "$tmp"' EXIT INT TERM

"$bin" run --help >"$tmp/help"
grep -q '^Usage: misul run' "$tmp/help"

expect_error() {
  expected=$1
  shift
  set +e
  env -u OPENAI_API_KEY "$bin" run "$@" >"$tmp/stdout" 2>"$tmp/stderr"
  status=$?
  set -e
  [ "$status" -eq 2 ]
  grep -Fxq "error: $expected" "$tmp/stderr"
  [ ! -s "$tmp/stdout" ]
}

expect_error "missing prompt"
expect_error "missing API key; set OPENAI_API_KEY or pass --api-key" hello
expect_error "missing option value" --model
expect_error "unknown option" --wat hello
expect_error "invalid numeric option" --api-key test --max-steps 0 hello
expect_error "invalid numeric option" --api-key test --max-tokens nope hello

python3 - "$bin" "$tmp" <<'PY'
import os
import socket
import subprocess
import sys
import threading

binary, temporary = sys.argv[1:]
listener = socket.socket()
listener.bind(("127.0.0.1", 0))
listener.listen(1)
listener.settimeout(0.1)
stop = threading.Event()

def provider():
    connection = None
    try:
        while connection is None and not stop.is_set():
            try:
                connection, _ = listener.accept()
            except socket.timeout:
                continue
        if connection is None:
            return
        request = bytearray()
        while b"\r\n\r\n" not in request:
            request.extend(connection.recv(65536))
        body = b'{"error":{"message":"secret-test-key\\u001b[31m was rejected","type":"authentication_error","code":"invalid_api_key"}}'
        response = (
            b"HTTP/1.1 401 Unauthorized\r\n"
            b"Content-Type: application/json\r\n"
            + f"Content-Length: {len(body)}\r\n".encode()
            + b"Connection: close\r\n\r\n"
            + body
        )
        connection.sendall(response)
    finally:
        if connection is not None:
            connection.close()
        listener.close()

thread = threading.Thread(target=provider)
thread.start()
environment = os.environ.copy()
environment.update({
    "HOME": temporary,
    "OPENAI_API_KEY": "secret-test-key",
    "OPENAI_BASE_URL": f"http://127.0.0.1:{listener.getsockname()[1]}/v1",
    "NO_PROXY": "127.0.0.1",
    "no_proxy": "127.0.0.1",
})
for name in ("MISUL_MODEL_PROFILES_JSON", "MISUL_PROVIDER_CONFIGS_JSON", "MISUL_MODEL", "OPENAI_MODEL"):
    environment.pop(name, None)
try:
    result = subprocess.run(
        [binary, "run", "typed failure"],
        cwd="/private/tmp",
        env=environment,
        text=True,
        capture_output=True,
        timeout=10,
    )
finally:
    stop.set()
    thread.join(timeout=2)
assert not thread.is_alive(), "provider fixture did not stop"
assert result.returncode == 1, result
assert result.stdout == "", result.stdout
assert result.stderr.startswith("error: provider request failed [authentication]: "), result.stderr
assert result.stderr.endswith("\n"), result.stderr
assert len(result.stderr.encode()) <= 512, result.stderr
assert "secret-test-key" not in result.stderr, result.stderr
assert "\x1b[31m" not in result.stderr, result.stderr
PY

echo "cli-errors-ok"
