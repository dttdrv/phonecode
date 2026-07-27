package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"sync"
	"syscall"
	"time"
)

const (
	protocolVersion        = int64(1)
	maxFramePayload        = uint32(65_536)
	maxCommandBytes        = 32_768
	maxOutputChunk         = 12_000
	maxTimeoutMilliseconds = int64(1_800_000)
	maxConcurrentCommands  = 4
	maxPendingStdinFrames  = 8
)

var noncePattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

type protocolObject map[string]json.RawMessage

type daemon struct {
	context   context.Context
	workspace string
	output    io.Writer

	writeMu  sync.Mutex
	active   sync.Map
	wait     sync.WaitGroup
	slotOnce sync.Once
	slots    chan struct{}
}

type runningProcess struct {
	command    *exec.Cmd
	cancel     context.CancelFunc
	stdin      io.WriteCloser
	stdinQueue chan stdinChunk
	stdinDone  chan struct{}
	stdinOnce  sync.Once

	outputMu sync.Mutex
	seq      int64
}

type stdinChunk struct {
	data []byte
	eof  bool
}

func main() {
	if err := serve(context.Background(), os.Stdin, os.Stdout, "/workspace"); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func serve(ctx context.Context, input io.Reader, output io.Writer, workspace string) error {
	first, err := readObject(input)
	if err != nil {
		return fmt.Errorf("read HELLO: %w", err)
	}
	if err := validateEnvelope(first, "hello", 0, "id", "nonce", "type", "v"); err != nil {
		return fmt.Errorf("invalid HELLO: %w", err)
	}
	nonce, err := stringField(first, "nonce")
	if err != nil || !noncePattern.MatchString(nonce) {
		return errors.New("invalid HELLO nonce")
	}

	server := &daemon{context: ctx, workspace: workspace, output: output}
	if err := server.write(map[string]any{
		"agent":        "phonecode-guestd",
		"capabilities": []string{"exec", "shutdown", "signal", "stdin"},
		"id":           0,
		"nonce":        nonce,
		"type":         "ready",
		"v":            1,
	}); err != nil {
		return err
	}

	for {
		frame, err := readObject(input)
		if errors.Is(err, io.EOF) {
			server.stopAll()
			return nil
		}
		if err != nil {
			server.stopAll()
			return err
		}
		frameType, err := stringField(frame, "type")
		if err != nil {
			server.stopAll()
			return err
		}
		switch frameType {
		case "exec":
			err = server.exec(frame)
		case "stdin":
			err = server.stdin(frame)
		case "signal":
			err = server.signal(frame)
		case "shutdown":
			err = validateEnvelope(frame, "shutdown", 0, "id", "type", "v")
			server.stopAll()
			if err == nil {
				return nil
			}
		default:
			err = fmt.Errorf("unsupported frame type %q", frameType)
		}
		if err != nil {
			server.stopAll()
			return err
		}
	}
}

func (server *daemon) exec(frame protocolObject) error {
	if err := requireFields(
		frame,
		"background", "command", "cwd", "id", "timeout_ms", "type", "v",
	); err != nil {
		return err
	}
	if err := validateVersionAndType(frame, "exec"); err != nil {
		return err
	}
	requestID, err := positiveID(frame)
	if err != nil {
		return err
	}
	commandText, err := stringField(frame, "command")
	if err != nil || len(commandText) == 0 || len([]byte(commandText)) > maxCommandBytes ||
		bytes.IndexByte([]byte(commandText), 0) >= 0 {
		return errors.New("exec command is outside the protocol limit")
	}
	cwd, err := stringField(frame, "cwd")
	if err != nil || cwd != "/workspace" {
		return errors.New("exec cwd must be /workspace")
	}
	timeoutMilliseconds, err := integerField(frame, "timeout_ms")
	if err != nil || timeoutMilliseconds < 1 || timeoutMilliseconds > maxTimeoutMilliseconds {
		return errors.New("exec timeout is outside the protocol limit")
	}
	if _, err := boolField(frame, "background"); err != nil {
		return err
	}
	if _, loaded := server.active.Load(requestID); loaded {
		return errors.New("duplicate live request id")
	}
	if !server.acquireCommandSlot() {
		return errors.New("active command concurrency limit reached")
	}

	processContext, cancel := context.WithTimeout(
		server.context,
		time.Duration(timeoutMilliseconds)*time.Millisecond,
	)
	command := exec.CommandContext(processContext, "/bin/sh", "-c", commandText)
	command.Dir = server.workspace
	command.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	stdin, err := command.StdinPipe()
	if err != nil {
		server.releaseCommandSlot()
		cancel()
		return err
	}
	process := &runningProcess{command: command, cancel: cancel, stdin: stdin}
	command.Cancel = func() error {
		return process.stop(syscall.SIGKILL)
	}
	command.WaitDelay = time.Second
	output := &processOutput{server: server, process: process, requestID: requestID}
	command.Stdout = output
	command.Stderr = output
	if err := command.Start(); err != nil {
		server.releaseCommandSlot()
		cancel()
		return server.writeError(requestID, "EXEC_START", "failed to start command")
	}
	process.startInputPump()
	if _, loaded := server.active.LoadOrStore(requestID, process); loaded {
		process.stop(syscall.SIGKILL)
		_ = command.Wait()
		process.stopInputPump()
		server.releaseCommandSlot()
		cancel()
		return errors.New("duplicate live request id")
	}
	if err := server.write(map[string]any{
		"id": requestID, "pid": command.Process.Pid, "type": "started", "v": 1,
	}); err != nil {
		process.stop(syscall.SIGKILL)
		_ = command.Wait()
		process.stopInputPump()
		server.active.Delete(requestID)
		server.releaseCommandSlot()
		cancel()
		return err
	}

	server.wait.Add(1)
	go func() {
		defer server.wait.Done()
		defer server.releaseCommandSlot()
		waitErr := command.Wait()
		process.terminateAndReapGroup()
		process.stopInputPump()
		status := exitStatus(waitErr)
		server.active.Delete(requestID)
		cancel()
		_ = server.write(map[string]any{
			"id": requestID, "status": status, "truncated": false, "type": "exit", "v": 1,
		})
	}()
	return nil
}

func (server *daemon) acquireCommandSlot() bool {
	server.slotOnce.Do(func() {
		server.slots = make(chan struct{}, maxConcurrentCommands)
	})
	select {
	case server.slots <- struct{}{}:
		return true
	default:
		return false
	}
}

func (server *daemon) releaseCommandSlot() {
	<-server.slots
}

func (process *runningProcess) startInputPump() {
	process.stdinQueue = make(chan stdinChunk, maxPendingStdinFrames)
	process.stdinDone = make(chan struct{})
	go func() {
		for {
			select {
			case <-process.stdinDone:
				return
			case chunk := <-process.stdinQueue:
				if len(chunk.data) > 0 {
					if _, err := process.stdin.Write(chunk.data); err != nil {
						process.stopInputPump()
						return
					}
				}
				if chunk.eof {
					_ = process.stdin.Close()
					process.stopInputPump()
					return
				}
			}
		}
	}()
}

func (process *runningProcess) stopInputPump() {
	process.stdinOnce.Do(func() {
		close(process.stdinDone)
	})
}

func (process *runningProcess) enqueueInput(data []byte, eof bool) error {
	chunk := stdinChunk{data: append([]byte(nil), data...), eof: eof}
	select {
	case <-process.stdinDone:
		return errors.New("command stdin is closed")
	default:
	}
	select {
	case process.stdinQueue <- chunk:
		return nil
	case <-process.stdinDone:
		return errors.New("command stdin is closed")
	default:
		return errors.New("command stdin backpressure limit reached")
	}
}

func (server *daemon) stdin(frame protocolObject) error {
	if err := requireFields(frame, "data_b64", "eof", "id", "type", "v"); err != nil {
		return err
	}
	if err := validateVersionAndType(frame, "stdin"); err != nil {
		return err
	}
	requestID, err := positiveID(frame)
	if err != nil {
		return err
	}
	encoded, err := stringField(frame, "data_b64")
	if err != nil {
		return err
	}
	data, err := base64.StdEncoding.Strict().DecodeString(encoded)
	if err != nil || base64.StdEncoding.EncodeToString(data) != encoded || len(data) > maxOutputChunk {
		return errors.New("stdin data is outside the protocol limit")
	}
	eof, err := boolField(frame, "eof")
	if err != nil {
		return err
	}
	value, ok := server.active.Load(requestID)
	if !ok {
		return errors.New("stdin request is not active")
	}
	process := value.(*runningProcess)
	return process.enqueueInput(data, eof)
}

func (server *daemon) signal(frame protocolObject) error {
	if err := requireFields(frame, "id", "signal", "type", "v"); err != nil {
		return err
	}
	if err := validateVersionAndType(frame, "signal"); err != nil {
		return err
	}
	requestID, err := positiveID(frame)
	if err != nil {
		return err
	}
	signalName, err := stringField(frame, "signal")
	if err != nil {
		return err
	}
	var selected syscall.Signal
	switch signalName {
	case "INT":
		selected = syscall.SIGINT
	case "KILL":
		selected = syscall.SIGKILL
	case "TERM":
		selected = syscall.SIGTERM
	default:
		return errors.New("unsupported signal")
	}
	value, ok := server.active.Load(requestID)
	if !ok {
		return errors.New("signal request is not active")
	}
	return value.(*runningProcess).stop(selected)
}

func (server *daemon) stopAll() {
	server.active.Range(func(_, value any) bool {
		process := value.(*runningProcess)
		_ = process.stop(syscall.SIGKILL)
		process.cancel()
		return true
	})
	server.wait.Wait()
}

func (process *runningProcess) stop(signal syscall.Signal) error {
	if process.command.Process == nil {
		return nil
	}
	err := syscall.Kill(-process.command.Process.Pid, signal)
	if errors.Is(err, syscall.ESRCH) {
		return nil
	}
	return err
}

func (process *runningProcess) terminateAndReapGroup() {
	if process.command.Process == nil {
		return
	}
	processGroup := process.command.Process.Pid
	_ = syscall.Kill(-processGroup, syscall.SIGKILL)
	deadline := time.Now().Add(time.Second)
	for {
		var status syscall.WaitStatus
		pid, err := syscall.Wait4(-processGroup, &status, syscall.WNOHANG, nil)
		if pid > 0 {
			continue
		}
		if errors.Is(err, syscall.EINTR) {
			continue
		}
		if errors.Is(err, syscall.ECHILD) &&
			errors.Is(syscall.Kill(-processGroup, 0), syscall.ESRCH) {
			return
		}
		if time.Now().After(deadline) {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
}

type processOutput struct {
	server    *daemon
	process   *runningProcess
	requestID int64
}

func (output *processOutput) Write(data []byte) (int, error) {
	output.process.outputMu.Lock()
	defer output.process.outputMu.Unlock()
	total := len(data)
	for len(data) > 0 {
		count := min(len(data), maxOutputChunk)
		chunk := data[:count]
		if err := output.server.write(map[string]any{
			"data_b64": base64.StdEncoding.EncodeToString(chunk),
			"id":       output.requestID,
			"seq":      output.process.seq,
			"type":     "output",
			"v":        1,
		}); err != nil {
			return total - len(data), err
		}
		output.process.seq++
		data = data[count:]
	}
	return total, nil
}

func (server *daemon) writeError(id int64, code, message string) error {
	if writeErr := server.write(map[string]any{
		"code": code, "id": id, "message": message, "type": "error", "v": 1,
	}); writeErr != nil {
		return writeErr
	}
	return errors.New(message)
}

func (server *daemon) write(value map[string]any) error {
	payload, err := json.Marshal(value)
	if err != nil {
		return err
	}
	if len(payload) < 1 || len(payload) > int(maxFramePayload) {
		return errors.New("outgoing frame is outside the protocol limit")
	}
	header := make([]byte, 4)
	binary.BigEndian.PutUint32(header, uint32(len(payload)))
	server.writeMu.Lock()
	defer server.writeMu.Unlock()
	if err := writeAll(server.output, header); err != nil {
		return err
	}
	return writeAll(server.output, payload)
}

func readObject(reader io.Reader) (protocolObject, error) {
	header := make([]byte, 4)
	if _, err := io.ReadFull(reader, header); err != nil {
		return nil, err
	}
	length := binary.BigEndian.Uint32(header)
	if length < 1 || length > maxFramePayload {
		return nil, errors.New("incoming frame is outside the protocol limit")
	}
	payload := make([]byte, length)
	if _, err := io.ReadFull(reader, payload); err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(bytes.NewReader(payload))
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return nil, errors.New("frame must be a JSON object")
	}
	object := make(protocolObject)
	for decoder.More() {
		token, err := decoder.Token()
		if err != nil {
			return nil, errors.New("invalid JSON object key")
		}
		key, ok := token.(string)
		if !ok {
			return nil, errors.New("invalid JSON object key")
		}
		if _, duplicate := object[key]; duplicate {
			return nil, errors.New("duplicate JSON object key")
		}
		var value json.RawMessage
		if err := decoder.Decode(&value); err != nil {
			return nil, errors.New("invalid JSON object value")
		}
		object[key] = value
	}
	if _, err := decoder.Token(); err != nil {
		return nil, errors.New("unterminated JSON object")
	}
	if decoder.More() {
		return nil, errors.New("trailing JSON data")
	}
	canonical, err := json.Marshal(object)
	if err != nil || !bytes.Equal(canonical, payload) {
		return nil, errors.New("frame JSON is not canonical")
	}
	return object, nil
}

func validateEnvelope(
	frame protocolObject,
	expectedType string,
	expectedID int64,
	fields ...string,
) error {
	if err := requireFields(frame, fields...); err != nil {
		return err
	}
	if err := validateVersionAndType(frame, expectedType); err != nil {
		return err
	}
	id, err := integerField(frame, "id")
	if err != nil || id != expectedID {
		return errors.New("invalid lifecycle id")
	}
	return nil
}

func validateVersionAndType(frame protocolObject, expectedType string) error {
	version, err := integerField(frame, "v")
	if err != nil || version != protocolVersion {
		return errors.New("unsupported protocol version")
	}
	frameType, err := stringField(frame, "type")
	if err != nil || frameType != expectedType {
		return errors.New("unexpected frame type")
	}
	return nil
}

func positiveID(frame protocolObject) (int64, error) {
	id, err := integerField(frame, "id")
	if err != nil || id < 1 {
		return 0, errors.New("request id must be positive")
	}
	return id, nil
}

func requireFields(frame protocolObject, expected ...string) error {
	if len(frame) != len(expected) {
		return errors.New("frame has undeclared or missing fields")
	}
	for _, name := range expected {
		if _, ok := frame[name]; !ok {
			return errors.New("frame has undeclared or missing fields")
		}
	}
	return nil
}

func stringField(frame protocolObject, name string) (string, error) {
	var value string
	if err := json.Unmarshal(frame[name], &value); err != nil {
		return "", fmt.Errorf("%s must be a string", name)
	}
	return value, nil
}

func integerField(frame protocolObject, name string) (int64, error) {
	raw, ok := frame[name]
	if !ok {
		return 0, fmt.Errorf("%s is missing", name)
	}
	value, err := strconv.ParseInt(string(raw), 10, 64)
	if err != nil {
		return 0, fmt.Errorf("%s must be an integer", name)
	}
	return value, nil
}

func boolField(frame protocolObject, name string) (bool, error) {
	var value bool
	if err := json.Unmarshal(frame[name], &value); err != nil {
		return false, fmt.Errorf("%s must be a boolean", name)
	}
	return value, nil
}

func writeAll(writer io.Writer, data []byte) error {
	for len(data) > 0 {
		count, err := writer.Write(data)
		if err != nil {
			return err
		}
		if count == 0 {
			return io.ErrShortWrite
		}
		data = data[count:]
	}
	return nil
}

func exitStatus(err error) int {
	if err == nil {
		return 0
	}
	var exitError *exec.ExitError
	if errors.As(err, &exitError) {
		if status, ok := exitError.Sys().(syscall.WaitStatus); ok {
			if status.Signaled() {
				return 128 + int(status.Signal())
			}
			if code := status.ExitStatus(); code >= 0 && code <= 255 {
				return code
			}
		}
	}
	return 255
}
