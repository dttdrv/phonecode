#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
bin=${MISUL_BIN:-"$root/zig-out/bin/misul"}
tmp=$(mktemp -d "${TMPDIR:-/tmp}/misul-tui-pty.XXXXXX")
trap 'rm -rf "$tmp"' EXIT INT TERM

python3 - "$bin" "$tmp" <<'PY'
import fcntl
import json
import os
import pty
import re
import select
import signal
import socket
import struct
import subprocess
import sys
import termios
import threading
import time

binary, temporary = sys.argv[1:]
case_index = 0

def run_case(width, height, interact, cwd="/private/tmp", extra_environment=None, terminate_with=None):
    global case_index
    case_index += 1
    master, slave = pty.openpty()
    fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack("HHHH", height, width, 0, 0))
    original = termios.tcgetattr(slave)
    environment = os.environ.copy()
    case_home = os.path.join(temporary, f"home-{case_index}")
    os.mkdir(case_home)
    environment["HOME"] = case_home
    for name in ("MISUL_MODEL_PROFILES_JSON", "MISUL_PROVIDER_CONFIGS_JSON", "MISUL_MODEL", "OPENAI_MODEL", "OPENAI_API_KEY"):
        environment.pop(name, None)
    if extra_environment:
        environment.update(extra_environment)
    process = subprocess.Popen(
        [binary],
        cwd=cwd,
        env=environment,
        stdin=slave,
        stdout=slave,
        stderr=slave,
        close_fds=True,
    )
    output = bytearray()

    def read_until(needle, timeout=10):
        deadline = time.monotonic() + timeout
        while needle not in output:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise AssertionError(f"timed out waiting for {needle!r}; tail={bytes(output[-2000:])!r}")
            ready, _, _ = select.select([master], [], [], remaining)
            if ready:
                output.extend(os.read(master, 65536))

    def read_next_frame(timeout=10):
        target = output.count(b"\x1b[?2026l") + 1
        deadline = time.monotonic() + timeout
        while output.count(b"\x1b[?2026l") < target:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise AssertionError("timed out waiting for the next frame")
            ready, _, _ = select.select([master], [], [], remaining)
            if ready:
                output.extend(os.read(master, 65536))

    try:
        read_until(b"Ready")
        interact(master, output, read_until, read_next_frame)
        if terminate_with is None:
            os.write(master, b"\x03")
        else:
            process.send_signal(terminate_with)
        deadline = time.monotonic() + 10
        while process.poll() is None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise AssertionError("TUI did not exit after Ctrl-C")
            ready, _, _ = select.select([master], [], [], min(remaining, 0.1))
            if ready:
                try:
                    output.extend(os.read(master, 65536))
                except OSError:
                    break
        status = process.wait(timeout=1)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()

    expected_status = 0 if terminate_with is None else -terminate_with
    assert status == expected_status, status
    assert termios.tcgetattr(slave) == original, "terminal attributes were not restored"
    assert b"\x1b[?1049h" in output and b"\x1b[?1049l" in output
    assert b"\x1b[?2004h" in output and b"\x1b[?2004l" in output
    assert b"\x1b[?25l" in output and b"\x1b[?25h" in output
    text = output.decode("utf-8")
    os.close(master)
    os.close(slave)
    return output, text

def fragmented_input(master, output, read_until, read_next_frame):
    os.write(master, "aéz".encode())
    read_next_frame()
    os.write(master, b"\x1b")
    read_next_frame()
    os.write(master, b"[D\x7f\r")
    read_until(b"provider credential is unavailable")
    os.write(master, b"\x1b[20")
    read_next_frame()
    os.write(master, b"0~\x1b[31mPWN\x1b[0m\x1b[2")
    read_next_frame()
    os.write(master, b"01~")
    read_next_frame()

output, text = run_case(72, 24, fragmented_input)
assert "  az" in text, text
assert "Turn failed [authentication]: provider credential is unavailable" in text, text
assert b"\x1b[31mPWN\x1b[0m" not in output
assert "\\x1b[31mPWN\\x1b[0m" in text, text

def narrow_utf8(master, output, read_until, read_next_frame):
    os.write(master, ("a" * 31 + "é").encode())
    read_next_frame()

_, narrow_text = run_case(40, 16, narrow_utf8)
assert "é" in narrow_text, narrow_text

def cursor_viewport(master, output, read_until, read_next_frame):
    os.write(master, b"x" * 240)
    read_next_frame()
    os.write(master, b"\x1b[D" * 239)
    read_next_frame()
    final_frame = output[output.rfind(b"\x1b[?2026h"):]
    assert "▏".encode() in final_frame, final_frame

_, _ = run_case(40, 16, cursor_viewport)

def escape_recovery(master, output, read_until, read_next_frame):
    os.write(master, b"\x1b")
    read_next_frame()
    os.write(master, "é".encode())
    read_next_frame()
    os.write(master, b"\x1b[")
    read_next_frame()
    os.write(master, "é".encode())
    read_next_frame()
    os.write(master, b"\x1b[")
    read_next_frame()

_, recovery_text = run_case(72, 24, escape_recovery)
assert "éé" in recovery_text, recovery_text
assert "\\xa9" not in recovery_text, recovery_text

cwd_payload = "\x1b]52;c;CWD\x07"
hostile_cwd = os.path.join(temporary, cwd_payload)
os.mkdir(hostile_cwd)

def metadata_safety(master, output, read_until, read_next_frame):
    assert b"Ready" in output

metadata_output, metadata_text = run_case(72, 24, metadata_safety, hostile_cwd)
assert cwd_payload.encode() not in metadata_output
assert "\\x1b]52;c;CWD\\x07" in metadata_text, metadata_text

_, _ = run_case(72, 24, metadata_safety, terminate_with=signal.SIGTERM)

long_model = "m" * 200
override_output, _ = run_case(80, 24, metadata_safety, extra_environment={
    "MISUL_MODEL": long_model,
    "MISUL_MODEL_PROFILES_JSON": json.dumps({"profiles": [{
        "id": long_model,
        "name": "Long model",
        "provider": "openai-compatible",
        "context_window": 8192,
        "output_limit": 512,
        "max_output_tokens": 1024,
    }]}, separators=(",", ":")),
})
ansi = re.compile(rb"\x1b\[[0-?]*[ -/]*[@-~]")
status_rows = []
for frame in override_output.split(b"\x1b[?2026l"):
    plain = ansi.sub(b"", frame).decode("utf-8")
    status_rows.extend(row for row in plain.splitlines() if "enter send" in row)
assert status_rows, "long-model status row was not rendered"
assert all(len(row) <= 80 for row in status_rows), status_rows
assert all(long_model not in row for row in status_rows), status_rows
assert any("…" in row for row in status_rows), status_rows

listener = socket.socket()
listener.bind(("127.0.0.1", 0))
listener.listen(1)
listener.settimeout(0.1)
provider_done = threading.Event()

def hanging_provider():
    connection = None
    try:
        while connection is None and not provider_done.is_set():
            try:
                connection, _ = listener.accept()
            except socket.timeout:
                continue
        if connection is None:
            return
        request = bytearray()
        while b"\r\n\r\n" not in request:
            request.extend(connection.recv(65536))
        head, initial_body = request.split(b"\r\n\r\n", 1)
        body = bytearray(initial_body)
        content_length = 0
        for line in head.split(b"\r\n")[1:]:
            if line.lower().startswith(b"content-length:"):
                content_length = int(line.split(b":", 1)[1])
        while len(body) < content_length:
            body.extend(connection.recv(65536))
        event = b'data:{"choices":[{"index":0,"delta":{"content":"stream-visible"}}]}\n\n'
        connection.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" + event)
        provider_done.wait(10)
    finally:
        if connection is not None:
            connection.close()
        listener.close()

provider_thread = threading.Thread(target=hanging_provider)
provider_thread.start()

def live_stream_and_cancel(master, output, read_until, read_next_frame):
    os.write(master, b"show streaming\r")
    read_until(b"Thinking")
    read_until(b"stream-visible")

try:
    _, streaming_text = run_case(72, 24, live_stream_and_cancel, extra_environment={
        "OPENAI_API_KEY": "test-key",
        "OPENAI_BASE_URL": f"http://127.0.0.1:{listener.getsockname()[1]}/v1",
        "NO_PROXY": "127.0.0.1",
        "no_proxy": "127.0.0.1",
    })
    assert "stream-visible" in streaming_text, streaming_text
finally:
    provider_done.set()
    provider_thread.join(timeout=2)
assert not provider_thread.is_alive(), "provider fixture did not stop"
PY

echo tui-pty-ok
