package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"io"
	"os"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

const testNonce = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func framed(t *testing.T, value map[string]any) []byte {
	t.Helper()
	payload, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	frame := make([]byte, 4+len(payload))
	binary.BigEndian.PutUint32(frame, uint32(len(payload)))
	copy(frame[4:], payload)
	return frame
}

func readFrame(t *testing.T, reader io.Reader) map[string]any {
	t.Helper()
	header := make([]byte, 4)
	if _, err := io.ReadFull(reader, header); err != nil {
		t.Fatal(err)
	}
	payload := make([]byte, binary.BigEndian.Uint32(header))
	if _, err := io.ReadFull(reader, payload); err != nil {
		t.Fatal(err)
	}
	var value map[string]any
	if err := json.Unmarshal(payload, &value); err != nil {
		t.Fatal(err)
	}
	return value
}

func hello() map[string]any {
	return map[string]any{"id": 0, "nonce": testNonce, "type": "hello", "v": 1}
}

func TestHandshakeReturnsExactNonceAndCapabilities(t *testing.T) {
	input := bytes.NewReader(framed(t, hello()))
	var output bytes.Buffer

	if err := serve(context.Background(), input, &output, t.TempDir()); err != nil {
		t.Fatal(err)
	}

	ready := readFrame(t, &output)
	if ready["agent"] != "phonecode-guestd" || ready["nonce"] != testNonce {
		t.Fatalf("unexpected ready: %#v", ready)
	}
	capabilities := ready["capabilities"].([]any)
	got := make([]string, len(capabilities))
	for index, value := range capabilities {
		got[index] = value.(string)
	}
	if strings.Join(got, ",") != "exec,shutdown,signal,stdin" {
		t.Fatalf("unexpected capabilities: %v", got)
	}
}

func TestRejectsOversizedAndNonCanonicalFrames(t *testing.T) {
	oversized := make([]byte, 4)
	binary.BigEndian.PutUint32(oversized, maxFramePayload+1)
	if err := serve(context.Background(), bytes.NewReader(oversized), io.Discard, t.TempDir()); err == nil {
		t.Fatal("oversized frame accepted")
	}

	payload := []byte(`{"v":1,"type":"hello","nonce":"` + testNonce + `","id":0}`)
	frame := make([]byte, 4+len(payload))
	binary.BigEndian.PutUint32(frame, uint32(len(payload)))
	copy(frame[4:], payload)
	if err := serve(context.Background(), bytes.NewReader(frame), io.Discard, t.TempDir()); err == nil {
		t.Fatal("non-canonical frame accepted")
	}
}

func TestForegroundExecEmitsOrderedBoundedOutputAndExit(t *testing.T) {
	hostToGuestReader, hostToGuestWriter := io.Pipe()
	guestToHostReader, guestToHostWriter := io.Pipe()
	done := make(chan error, 1)
	go func() {
		done <- serve(context.Background(), hostToGuestReader, guestToHostWriter, t.TempDir())
	}()
	hostToGuestWriter.Write(framed(t, hello()))
	_ = readFrame(t, guestToHostReader)
	hostToGuestWriter.Write(framed(t, map[string]any{
		"background": false,
		"command":    "printf 'first\\nsecond\\n'",
		"cwd":        "/workspace",
		"id":         1,
		"timeout_ms": 1000,
		"type":       "exec",
		"v":          1,
	}))

	started := readFrame(t, guestToHostReader)
	if started["type"] != "started" || started["id"] != float64(1) {
		t.Fatalf("unexpected started: %#v", started)
	}
	var combined []byte
	var expectedSequence float64
	for {
		frame := readFrame(t, guestToHostReader)
		switch frame["type"] {
		case "output":
			if frame["seq"] != expectedSequence {
				t.Fatalf("sequence = %v, want %v", frame["seq"], expectedSequence)
			}
			expectedSequence++
			chunk, err := base64.StdEncoding.DecodeString(frame["data_b64"].(string))
			if err != nil {
				t.Fatal(err)
			}
			if len(chunk) > maxOutputChunk {
				t.Fatalf("output chunk has %d bytes", len(chunk))
			}
			combined = append(combined, chunk...)
		case "exit":
			if frame["status"] != float64(0) {
				t.Fatalf("unexpected exit: %#v", frame)
			}
			if string(combined) != "first\nsecond\n" {
				t.Fatalf("output = %q", combined)
			}
			hostToGuestWriter.Write(framed(t, map[string]any{
				"id": 0, "type": "shutdown", "v": 1,
			}))
			hostToGuestWriter.Close()
			if err := <-done; err != nil {
				t.Fatal(err)
			}
			return
		default:
			t.Fatalf("unexpected frame: %#v", frame)
		}
	}
}

func TestTransportEOFStopsActiveCommands(t *testing.T) {
	var input bytes.Buffer
	input.Write(framed(t, hello()))
	input.Write(framed(t, map[string]any{
		"background": true,
		"command":    "sleep 30",
		"cwd":        "/workspace",
		"id":         5,
		"timeout_ms": 5000,
		"type":       "exec",
		"v":          1,
	}))
	var output bytes.Buffer
	startedAt := time.Now()

	if err := serve(context.Background(), &input, &output, t.TempDir()); err != nil {
		t.Fatal(err)
	}
	if elapsed := time.Since(startedAt); elapsed > time.Second {
		t.Fatalf("transport EOF took %s to stop active command", elapsed)
	}
}

func TestBackgroundExecAcceptsStdinAndSignal(t *testing.T) {
	hostToGuestReader, hostToGuestWriter := io.Pipe()
	guestToHostReader, guestToHostWriter := io.Pipe()
	done := make(chan error, 1)
	workspace := t.TempDir()
	go func() {
		done <- serve(context.Background(), hostToGuestReader, guestToHostWriter, workspace)
	}()

	hostToGuestWriter.Write(framed(t, hello()))
	_ = readFrame(t, guestToHostReader)
	hostToGuestWriter.Write(framed(t, map[string]any{
		"background": true,
		"command":    "read line; printf '%s' \"$line\"",
		"cwd":        "/workspace",
		"id":         7,
		"timeout_ms": 5000,
		"type":       "exec",
		"v":          1,
	}))
	_ = readFrame(t, guestToHostReader)
	hostToGuestWriter.Write(framed(t, map[string]any{
		"data_b64": base64.StdEncoding.EncodeToString([]byte("from-stdin\n")),
		"eof":      true,
		"id":       7,
		"type":     "stdin",
		"v":        1,
	}))

	var output []byte
	for {
		frame := readFrame(t, guestToHostReader)
		if frame["type"] == "output" {
			chunk, _ := base64.StdEncoding.DecodeString(frame["data_b64"].(string))
			output = append(output, chunk...)
		}
		if frame["type"] == "exit" {
			break
		}
	}
	if string(output) != "from-stdin" {
		t.Fatalf("stdin output = %q", output)
	}

	hostToGuestWriter.Write(framed(t, map[string]any{"id": 0, "type": "shutdown", "v": 1}))
	hostToGuestWriter.Close()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("shutdown did not complete")
	}
}

func TestSignalStopsTheCompleteCommandProcessGroup(t *testing.T) {
	hostToGuestReader, hostToGuestWriter := io.Pipe()
	guestToHostReader, guestToHostWriter := io.Pipe()
	done := make(chan error, 1)
	go func() {
		done <- serve(context.Background(), hostToGuestReader, guestToHostWriter, t.TempDir())
	}()

	hostToGuestWriter.Write(framed(t, hello()))
	_ = readFrame(t, guestToHostReader)
	hostToGuestWriter.Write(framed(t, map[string]any{
		"background": true, "command": "sleep 30", "cwd": "/workspace",
		"id": 11, "timeout_ms": 5000, "type": "exec", "v": 1,
	}))
	_ = readFrame(t, guestToHostReader)
	hostToGuestWriter.Write(framed(t, map[string]any{
		"id": 11, "signal": "TERM", "type": "signal", "v": 1,
	}))

	exit := readFrame(t, guestToHostReader)
	if exit["type"] != "exit" || exit["status"] != float64(143) {
		t.Fatalf("unexpected signalled exit: %#v", exit)
	}
	hostToGuestWriter.Write(framed(t, map[string]any{"id": 0, "type": "shutdown", "v": 1}))
	hostToGuestWriter.Close()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestTimeoutKillsTheCompleteCommandProcessGroup(t *testing.T) {
	workspace := t.TempDir()
	hostToGuestReader, hostToGuestWriter := io.Pipe()
	guestToHostReader, guestToHostWriter := io.Pipe()
	done := make(chan error, 1)
	go func() {
		done <- serve(context.Background(), hostToGuestReader, guestToHostWriter, workspace)
	}()
	hostToGuestWriter.Write(framed(t, hello()))
	_ = readFrame(t, guestToHostReader)
	hostToGuestWriter.Write(framed(t, map[string]any{
		"background": false,
		"command":    "sleep 30 & echo $! > child.pid; wait",
		"cwd":        "/workspace",
		"id":         13,
		"timeout_ms": 100,
		"type":       "exec",
		"v":          1,
	}))

	startedAt := time.Now()
	_ = readFrame(t, guestToHostReader)
	exit := readFrame(t, guestToHostReader)
	if elapsed := time.Since(startedAt); elapsed > 2*time.Second {
		t.Fatalf("timeout took %s instead of stopping the process group", elapsed)
	}
	if exit["type"] != "exit" || exit["status"] != float64(137) {
		t.Fatalf("unexpected timeout exit: %#v", exit)
	}
	pidBytes, err := os.ReadFile(workspace + "/child.pid")
	if err != nil {
		t.Fatal(err)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(pidBytes)))
	if err != nil {
		t.Fatal(err)
	}
	if err := syscall.Kill(pid, 0); err == nil {
		_ = syscall.Kill(pid, syscall.SIGKILL)
		t.Fatalf("timeout left child process %d running", pid)
	}
	hostToGuestWriter.Write(framed(t, map[string]any{"id": 0, "type": "shutdown", "v": 1}))
	hostToGuestWriter.Close()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestRejectsCommandAndFrameShapeOutsideProtocolLimits(t *testing.T) {
	cases := []map[string]any{
		{
			"background": false, "command": strings.Repeat("x", maxCommandBytes+1),
			"cwd": "/workspace", "id": 1, "timeout_ms": 1, "type": "exec", "v": 1,
		},
		{
			"background": false, "command": "true", "cwd": "/tmp",
			"id": 1, "timeout_ms": 1, "type": "exec", "v": 1,
		},
		{
			"background": false, "command": "true", "cwd": "/workspace",
			"id": 1, "timeout_ms": maxTimeoutMilliseconds + 1, "type": "exec", "v": 1,
		},
	}
	for _, invalid := range cases {
		var input bytes.Buffer
		input.Write(framed(t, hello()))
		input.Write(framed(t, invalid))
		if err := serve(context.Background(), &input, io.Discard, t.TempDir()); err == nil {
			t.Fatalf("accepted invalid frame: %#v", invalid)
		}
	}
}
