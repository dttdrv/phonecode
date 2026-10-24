#!/bin/sh
set -eu

if [ "$(uname -s)" != Darwin ]; then
  printf '%s\n' "system use macOS integration: skipped (Darwin required)"
  exit 0
fi

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
workspace_dir=$(CDPATH= cd -- "$root_dir/.." && pwd)
automation_dir="$workspace_dir/packages/macos-automation"
zig_bin=${ZIG:-/tmp/misul-zig-0.16.0/zig}
scratch=$(mktemp -d "${TMPDIR:-/tmp}/misul-system-use-macos.XXXXXX")
fixture_pid=
focus_pid_file="$scratch/focus.pid"
cleanup() {
  if [ -f "$focus_pid_file" ]; then
    focus_pid=$(sed -n '1p' "$focus_pid_file")
    case "$focus_pid" in
      ''|*[!0-9]*) ;;
      *) kill "$focus_pid" 2>/dev/null || true ;;
    esac
  fi
  if [ -n "$fixture_pid" ]; then
    kill "$fixture_pid" 2>/dev/null || true
    wait "$fixture_pid" 2>/dev/null || true
  fi
  rm -rf "$scratch"
}
trap cleanup EXIT INT TERM

sdk_path=$(xcrun --show-sdk-path)
swiftc -O -whole-module-optimization -sdk "$sdk_path" -target "$(uname -m)-apple-macos13.0" \
  -o "$scratch/misul-automation" "$automation_dir"/Sources/MisulAutomation/*.swift
cat >"$scratch/Fixture.swift" <<'SWIFT'
import AppKit

final class FixtureDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow?
    private var decoyWindow: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let frame = NSRect(x: 160, y: 160, width: 420, height: 180)
        let window = NSWindow(contentRect: frame, styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Misul SystemUse Fixture"
        window.setAccessibilityElement(true)
        window.setAccessibilityRole(.window)
        window.setAccessibilityTitle("Misul SystemUse Fixture")
        let field = NSTextField(frame: NSRect(x: 30, y: 100, width: 360, height: 28))
        field.placeholderString = "Misul Fixture Field"
        field.stringValue = "Initial Value"
        let button = NSButton(frame: NSRect(x: 150, y: 45, width: 120, height: 32))
        button.title = "Misul Fixture Button"
        button.bezelStyle = .rounded
        window.contentView?.addSubview(field)
        window.contentView?.addSubview(button)
        window.makeKeyAndOrderFront(nil)
        let decoy = NSWindow(contentRect: NSRect(x: 650, y: 160, width: 320, height: 180), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        decoy.title = "Misul SystemUse Decoy"
        decoy.setAccessibilityElement(true)
        decoy.setAccessibilityRole(.window)
        decoy.setAccessibilityTitle("Misul SystemUse Decoy")
        let decoyButton = NSButton(frame: NSRect(x: 90, y: 65, width: 140, height: 32))
        decoyButton.title = "Decoy Button"
        decoy.contentView?.addSubview(decoyButton)
        decoy.orderFront(nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(200)) {
            window.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
        }
        self.window = window
        self.decoyWindow = decoy
    }

    func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool { true }
}

let app = NSApplication.shared
let delegate = FixtureDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
SWIFT
cat >"$scratch/FocusKeeper.swift" <<'SWIFT'
import AppKit

final class FocusKeeperDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let window = NSWindow(
            contentRect: NSRect(x: 1020, y: 520, width: 260, height: 120),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "Misul Focus Keeper"
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        self.window = window
    }

    func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool { true }
}

let app = NSApplication.shared
let delegate = FocusKeeperDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
SWIFT
cat >"$scratch/Frontmost.swift" <<'SWIFT'
import AppKit
import Foundation

guard let pid = NSWorkspace.shared.frontmostApplication?.processIdentifier else { exit(1) }
print(pid)
SWIFT
mkdir -p "$scratch/MisulSystemUseFixture.app/Contents/MacOS"
cat >"$scratch/MisulSystemUseFixture.app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>MisulSystemUseFixture</string>
<key>CFBundleIdentifier</key><string>com.misul.systemuse.fixture</string>
<key>CFBundleName</key><string>MisulSystemUseFixture</string>
<key>CFBundlePackageType</key><string>APPL</string>
</dict></plist>
PLIST
swiftc -O -sdk "$sdk_path" -target "$(uname -m)-apple-macos13.0" -o "$scratch/MisulSystemUseFixture.app/Contents/MacOS/MisulSystemUseFixture" "$scratch/Fixture.swift"
mkdir -p "$scratch/MisulSystemUseFocusKeeper.app/Contents/MacOS"
cat >"$scratch/MisulSystemUseFocusKeeper.app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>MisulSystemUseFocusKeeper</string>
<key>CFBundleIdentifier</key><string>com.misul.systemuse.focuskeeper</string>
<key>CFBundleName</key><string>MisulSystemUseFocusKeeper</string>
<key>CFBundlePackageType</key><string>APPL</string>
</dict></plist>
PLIST
swiftc -O -sdk "$sdk_path" -target "$(uname -m)-apple-macos13.0" -o "$scratch/MisulSystemUseFocusKeeper.app/Contents/MacOS/MisulSystemUseFocusKeeper" "$scratch/FocusKeeper.swift"
swiftc -O -sdk "$sdk_path" -target "$(uname -m)-apple-macos13.0" -o "$scratch/frontmost" "$scratch/Frontmost.swift"
open -n "$scratch/MisulSystemUseFixture.app"
fixture_pid=
attempt=0
while [ "$attempt" -lt 50 ]; do
  fixture_pid=$(pgrep -x MisulSystemUseFixture || true)
  [ -n "$fixture_pid" ] && break
  attempt=$((attempt + 1))
  sleep 0.02
done
[ -n "$fixture_pid" ] || { printf '%s\n' "fixture did not launch" >&2; exit 1; }
cd "$root_dir"
ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$scratch/zig-cache}" "$zig_bin" build --prefix "$scratch/prefix"

MISUL_SYSTEM_USE_BINARY="$scratch/misul-automation" \
OPENAI_API_KEY=fixture \
HOME="$scratch/home" \
python3 - "$scratch/prefix/bin/misul" "$fixture_pid" "$scratch/MisulSystemUseFocusKeeper.app" "$scratch/frontmost" "$focus_pid_file" <<'PY'
import json
import os
import subprocess
import sys
import time

process = subprocess.Popen(
    [sys.argv[1], "rpc"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    text=True, env=os.environ,
)
assert process.stdin and process.stdout
fixture_pid = int(sys.argv[2])
focus_keeper_pid = None

def request(request_id, method, params, allow_error=False):
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    while True:
        line = process.stdout.readline()
        assert line, process.stderr.read()
        value = json.loads(line)
        if value.get("id") == request_id:
            if not allow_error:
                assert "error" not in value, value
            return value

try:
    handshake = request(1, "rpc/handshake", {"protocol_major": 1, "protocol_minor": 0})["result"]
    assert handshake["capabilities"]["system_use"] is True
    permissions = request(2, "system_use/permissions", {})["result"]
    assert isinstance(permissions["accessibility"], bool)
    assert isinstance(permissions["screenRecording"], bool)
    assert permissions == {"accessibility": True, "screenRecording": True}, permissions
    target = None
    decoy = None
    for _ in range(50):
        targets = request(3, "system_use/list", {})["result"]["targets"]
        target = next((item for item in targets if item.get("metadata", {}).get("pid") == fixture_pid and item.get("label", "").endswith(" - Misul SystemUse Fixture")), None)
        decoy = next((item for item in targets if item.get("metadata", {}).get("pid") == fixture_pid and item.get("label", "").endswith(" - Misul SystemUse Decoy")), None)
        if target is not None and decoy is not None:
            break
        time.sleep(0.02)
    assert target is not None and decoy is not None, targets
    observed = request(4, "system_use/observe", {
        "target": target, "max_depth": 4, "max_elements": 64,
        "include_actions": True, "screenshot": True,
    })["result"]
    elements = observed["semantic"]["elements"]
    assert len(elements) <= 64
    assert observed["screenshot"]["mimeType"] == "image/png"
    assert observed["screenshot"]["width"] > 0 and observed["screenshot"]["height"] > 0
    field = next(item for item in elements if item.get("role") == "AXTextField")
    button = next(item for item in elements if item.get("role") == "AXButton" and item.get("title") == "Misul Fixture Button")
    refreshed = request(12, "system_use/observe", {
        "target": target, "max_depth": 4, "max_elements": 64,
        "include_actions": True, "screenshot": False,
    })["result"]
    refreshed_button = next(item for item in refreshed["semantic"]["elements"] if item.get("role") == "AXButton" and item.get("title") == "Misul Fixture Button")
    assert refreshed_button["id"] == button["id"], (button, refreshed_button)
    changed = request(5, "system_use/action", {
        "target": target, "name": "set_value", "element_id": field["id"],
        "value": "Native Value", "focus_mode": "background",
    })["result"]
    assert changed["status"] == "succeeded" and changed["result"]["focusMode"] == "background"
    clicked = request(6, "system_use/action", {
        "target": target, "name": "click", "element_id": button["id"], "focus_mode": "background",
    })["result"]
    assert clicked["status"] == "succeeded" and clicked["result"]["focusMode"] == "background"
    waited_element = request(13, "system_use/wait", {
        "wait_id": "wait-13", "target": target, "element_id": button["id"],
        "state": "present", "timeout_ms": 1000, "poll_interval_ms": 10,
    })["result"]
    assert waited_element["matched"] is True
    target_frame = target["metadata"]["frame"]
    focused = request(10, "system_use/action", {
        "target": target, "name": "click",
        "x": int(target_frame["x"] + target_frame["width"] / 2),
        "y": int(target_frame["y"] + target_frame["height"] / 2),
        "focus_mode": "activate",
    })["result"]
    assert focused["status"] == "succeeded" and focused["result"]["activated"] is True
    decoy_frame = decoy["metadata"]["frame"]
    escaped = request(11, "system_use/action", {
        "target": target, "name": "click",
        "x": int(decoy_frame["x"] + decoy_frame["width"] / 2),
        "y": int(decoy_frame["y"] + decoy_frame["height"] / 2),
        "focus_mode": "activate",
    }, allow_error=True)
    assert escaped["error"]["message"] == "SystemUse platform operation failed", escaped
    waited = request(7, "system_use/wait", {
        "wait_id": "wait-7", "target": target, "text": "Native Value",
        "state": "present", "timeout_ms": 1000, "poll_interval_ms": 10,
    })["result"]
    assert waited["matched"] is True
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 8, "method": "system_use/wait", "params": {"wait_id": "wait-8", "target": target, "text": "Value That Never Exists", "timeout_ms": 5000, "poll_interval_ms": 10}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    time.sleep(0.05)
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 9, "method": "system_use/cancel", "params": {"wait_id": "wait-8"}}, separators=(",", ":")) + "\n")
    process.stdin.flush()
    cancelled = {}
    while len(cancelled) < 2:
        response = json.loads(process.stdout.readline())
        if response.get("id") in (8, 9):
            cancelled[response["id"]] = response
    assert cancelled[9]["result"]["status"] == "requested"
    assert cancelled[8]["error"]["message"] == "SystemUse wait was canceled"

    current = request(16, "system_use/observe", {
        "target": target, "max_depth": 4, "max_elements": 64,
        "include_actions": True, "screenshot": False,
    })["result"]
    current_field = next(item for item in current["semantic"]["elements"] if item.get("role") == "AXTextField")

    subprocess.run(["open", "-n", sys.argv[3]], check=True)
    for _ in range(50):
        found = subprocess.run(["pgrep", "-x", "MisulSystemUseFocusKeeper"], text=True, capture_output=True)
        pids = [int(line) for line in found.stdout.splitlines() if line]
        if len(pids) == 1:
            focus_keeper_pid = pids[0]
            __import__("pathlib").Path(sys.argv[5]).write_text(f"{focus_keeper_pid}\n", encoding="ascii")
            break
        time.sleep(0.02)
    assert focus_keeper_pid is not None, "focus keeper did not launch"
    for _ in range(50):
        frontmost_pid = int(subprocess.check_output([sys.argv[4]], text=True).strip())
        if frontmost_pid == focus_keeper_pid:
            break
        time.sleep(0.02)
    assert frontmost_pid == focus_keeper_pid, (frontmost_pid, focus_keeper_pid)
    background_targets = request(15, "system_use/list", {})["result"]["targets"]
    assert any(item["id"] == target["id"] for item in background_targets), (target, background_targets)
    background = request(14, "system_use/action", {
        "target": target, "name": "set_value", "element_id": current_field["id"], "value": "Background Value", "focus_mode": "background",
    })["result"]
    assert background["status"] == "succeeded" and background["result"]["activated"] is False
    assert int(subprocess.check_output([sys.argv[4]], text=True).strip()) == focus_keeper_pid
finally:
    process.terminate()
    process.wait(timeout=2)
    if focus_keeper_pid is not None:
        os.kill(focus_keeper_pid, 15)
        for _ in range(50):
            try:
                os.kill(focus_keeper_pid, 0)
            except ProcessLookupError:
                break
            time.sleep(0.02)
        if os.path.exists(sys.argv[5]):
            os.unlink(sys.argv[5])
PY

printf '%s\n' "system use macOS integration: pass"
