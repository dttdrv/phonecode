const std = @import("std");
const protocol = @import("protocol");
const provider_module = @import("provider");

const canary = "runtime-03-secret-canary";
const header_canary = "runtime-03-header-canary";
const configured_credential_ref = "RUNTIME_03_FIXTURE_KEY";

const Capture = struct {
    text: [256]u8 = undefined,
    text_len: usize = 0,
    thinking: [256]u8 = undefined,
    thinking_len: usize = 0,
    tool_delta_len: usize = 0,
    text_index: ?usize = null,
    thinking_index: ?usize = null,
    tool_index: ?usize = null,
    done_content_ok: bool = false,
    starts: usize = 0,
    tool_starts: usize = 0,
    tool_ends: usize = 0,
    usage_events: usize = 0,
    usage: protocol.Usage = .{},
    done_events: usize = 0,
    failed_events: usize = 0,
    failure_category: ?protocol.ProviderFailureCategory = null,
    failure_status: ?u16 = null,
    failure_retry_after_ms: ?u64 = null,
    failure_message: [protocol.max_provider_failure_message_bytes]u8 = undefined,
    failure_message_len: usize = 0,
};

const ServerState = struct {
    io: std.Io,
    server: *std.Io.net.Server,
    response: []const u8,
    request: [64 * 1024]u8 = undefined,
    request_len: usize = 0,
    request_ok: bool = false,
    failed: bool = false,
    ignore_write_failure: bool = false,
    byte_fragments: bool = false,
};

const ScriptedServerState = struct {
    io: std.Io,
    server: *std.Io.net.Server,
    responses: []const []const u8,
    delay_ms: u64 = 0,
    ignore_write_failure: bool = false,
    requests: usize = 0,
    failed: bool = false,
};

const CancelCapture = struct {
    cancel_after_text: bool,
    starts: usize = 0,
    text: usize = 0,
};

const LiveEventCapture = struct {
    starts: usize = 0,
    text: usize = 0,
};

const BlockedReadServerState = struct {
    allocator: std.mem.Allocator,
    server: *std.Io.net.Server,
    response_prefix: []const u8 = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n",
    accepted: std.atomic.Value(bool) = .init(false),
    request_received: std.atomic.Value(bool) = .init(false),
    peer_closed: std.atomic.Value(bool) = .init(false),
    failed: std.atomic.Value(bool) = .init(false),
    ignore_write_failure: bool = false,
};

const ObservedRequest = struct {
    calls: usize = 0,
    metadata: ?provider_module.RenderedRequestMetadata = null,

    fn receive(raw: *anyopaque, metadata: provider_module.RenderedRequestMetadata) !void {
        const observed: *@This() = @ptrCast(@alignCast(raw));
        observed.calls += 1;
        observed.metadata = metadata;
    }
};

fn sink(raw: *anyopaque, event: protocol.StreamEvent) !void {
    const capture: *Capture = @ptrCast(@alignCast(raw));
    switch (event) {
        .start => capture.starts += 1,
        .text_delta => |delta| {
            if (capture.text_len + delta.delta.len > capture.text.len) return error.CaptureOverflow;
            @memcpy(capture.text[capture.text_len .. capture.text_len + delta.delta.len], delta.delta);
            capture.text_len += delta.delta.len;
        },
        .thinking_delta => |delta| {
            if (capture.thinking_len + delta.delta.len > capture.thinking.len) return error.CaptureOverflow;
            @memcpy(capture.thinking[capture.thinking_len .. capture.thinking_len + delta.delta.len], delta.delta);
            capture.thinking_len += delta.delta.len;
        },
        .text_start => |index| capture.text_index = index,
        .text_end, .thinking_end => {},
        .thinking_start => |index| capture.thinking_index = index,
        .tool_call_start => |index| {
            capture.tool_starts += 1;
            capture.tool_index = index;
        },
        .tool_call_delta => |delta| capture.tool_delta_len += delta.delta.len,
        .tool_call_end => capture.tool_ends += 1,
        .usage => |usage| {
            capture.usage_events += 1;
            capture.usage = usage;
        },
        .done => |done| {
            capture.done_events += 1;
            capture.done_content_ok = true;
            for (done.content, 0..) |content, index| switch (content) {
                .text => {
                    if (capture.text_index == null or capture.text_index.? != index) capture.done_content_ok = false;
                },
                .thinking => {
                    if (capture.thinking_index == null or capture.thinking_index.? != index) capture.done_content_ok = false;
                },
                .tool_call => {
                    if (capture.tool_index == null or capture.tool_index.? != index) capture.done_content_ok = false;
                },
            };
        },
        .failed => |failure| {
            capture.failed_events += 1;
            capture.failure_category = failure.provider_failure.?.category;
            capture.failure_status = failure.provider_failure.?.http_status;
            capture.failure_retry_after_ms = failure.provider_failure.?.retry_after_ms;
            const message = failure.provider_failure.?.redacted_message;
            capture.failure_message_len = @min(message.len, capture.failure_message.len);
            @memcpy(capture.failure_message[0..capture.failure_message_len], message[0..capture.failure_message_len]);
        },
    }
}

fn serverThread(state: *ServerState) void {
    var connection = state.server.accept(state.io) catch {
        state.failed = true;
        return;
    };
    defer connection.close(state.io);

    var response_buffer: [8192]u8 = undefined;
    var writer = connection.writer(state.io, &response_buffer);
    for (state.response, 0..) |_, index| {
        writer.interface.writeAll(state.response[index .. index + 1]) catch {
            if (!state.ignore_write_failure) state.failed = true;
            return;
        };
        writer.interface.flush() catch {
            if (!state.ignore_write_failure) state.failed = true;
            return;
        };
    }
    state.byte_fragments = true;

    var read_storage: [8192]u8 = undefined;
    var reader = connection.reader(state.io, &read_storage);
    var chunk: [4096]u8 = undefined;
    while (state.request_len < state.request.len) {
        const n = reader.interface.readSliceShort(&chunk) catch {
            return;
        };
        if (n == 0) break;
        const copy_len = @min(n, state.request.len - state.request_len);
        @memcpy(state.request[state.request_len .. state.request_len + copy_len], chunk[0..copy_len]);
        state.request_len += copy_len;
        const header_end = std.mem.indexOf(u8, state.request[0..state.request_len], "\r\n\r\n") orelse continue;
        const content_length = findContentLength(state.request[0 .. header_end + 4]);
        if (state.request_len >= header_end + 4 + content_length) break;
    }

    const request = state.request[0..state.request_len];
    state.request_ok = std.mem.indexOf(u8, request, "POST /v1/chat/completions HTTP/1.1") != null;
}

fn scriptedServerThread(state: *ScriptedServerState) void {
    for (state.responses) |response| {
        var connection = state.server.accept(state.io) catch {
            state.failed = true;
            return;
        };
        state.requests += 1;
        var request_storage: [64 * 1024]u8 = undefined;
        var request_len: usize = 0;
        var read_storage: [8192]u8 = undefined;
        var reader = connection.reader(state.io, &read_storage);
        while (request_len < request_storage.len) {
            reader.interface.fillMore() catch break;
            const available = reader.interface.buffered();
            if (available.len == 0) break;
            const copy_len = @min(available.len, request_storage.len - request_len);
            @memcpy(request_storage[request_len .. request_len + copy_len], available[0..copy_len]);
            reader.interface.toss(copy_len);
            request_len += copy_len;
            const header_end = std.mem.indexOf(u8, request_storage[0..request_len], "\r\n\r\n") orelse continue;
            if (request_len >= header_end + 4 + findContentLength(request_storage[0 .. header_end + 4])) break;
        }
        if (request_len == 0) state.failed = true;
        if (state.delay_ms != 0) std.Io.sleep(state.io, .fromMilliseconds(@intCast(state.delay_ms)), .awake) catch {
            connection.close(state.io);
            state.failed = true;
            return;
        };
        var response_buffer: [8192]u8 = undefined;
        var writer = connection.writer(state.io, &response_buffer);
        for (response, 0..) |_, index| {
            writer.interface.writeAll(response[index .. index + 1]) catch {
                connection.close(state.io);
                if (!state.ignore_write_failure) state.failed = true;
                return;
            };
            writer.interface.flush() catch {
                connection.close(state.io);
                if (!state.ignore_write_failure) state.failed = true;
                return;
            };
        }
        connection.close(state.io);
    }
}

fn blockedReadServerThread(state: *BlockedReadServerState) void {
    var threaded: std.Io.Threaded = .init(state.allocator, .{});
    defer threaded.deinit();
    const io = threaded.io();
    var connection = state.server.accept(io) catch {
        state.failed.store(true, .release);
        return;
    };
    defer connection.close(io);
    state.accepted.store(true, .release);
    var request_storage: [64 * 1024]u8 = undefined;
    var request_len: usize = 0;
    var read_storage: [8192]u8 = undefined;
    var reader = connection.reader(io, &read_storage);
    while (request_len < request_storage.len) {
        reader.interface.fillMore() catch {
            state.failed.store(true, .release);
            return;
        };
        const available = reader.interface.buffered();
        if (available.len == 0) {
            state.failed.store(true, .release);
            return;
        }
        const copy_len = @min(available.len, request_storage.len - request_len);
        @memcpy(request_storage[request_len .. request_len + copy_len], available[0..copy_len]);
        reader.interface.toss(copy_len);
        request_len += copy_len;
        const header_end = std.mem.indexOf(u8, request_storage[0..request_len], "\r\n\r\n") orelse continue;
        if (request_len >= header_end + 4 + findContentLength(request_storage[0 .. header_end + 4])) break;
    }
    state.request_received.store(true, .release);
    var response_storage: [128]u8 = undefined;
    var writer = connection.writer(io, &response_storage);
    writer.interface.writeAll(state.response_prefix) catch {
        if (state.ignore_write_failure) state.peer_closed.store(true, .release) else state.failed.store(true, .release);
        return;
    };
    writer.interface.flush() catch {
        if (state.ignore_write_failure) state.peer_closed.store(true, .release) else state.failed.store(true, .release);
        return;
    };
    reader.interface.fillMore() catch {
        state.peer_closed.store(true, .release);
        return;
    };
    if (reader.interface.bufferedLen() == 0) state.peer_closed.store(true, .release) else state.failed.store(true, .release);
}

fn cancelSink(raw: *anyopaque, event: protocol.StreamEvent) !void {
    const capture: *CancelCapture = @ptrCast(@alignCast(raw));
    switch (event) {
        .start => {
            capture.starts += 1;
            if (!capture.cancel_after_text) return error.Canceled;
        },
        .text_delta => {
            capture.text += 1;
            if (capture.cancel_after_text) return error.Canceled;
        },
        else => {},
    }
}

fn liveEventSink(raw: *anyopaque, event: protocol.StreamEvent) !void {
    const capture: *LiveEventCapture = @ptrCast(@alignCast(raw));
    switch (event) {
        .start => capture.starts += 1,
        .text_delta => {
            capture.text += 1;
            return error.Canceled;
        },
        else => {},
    }
}

fn findContentLength(headers: []const u8) usize {
    const marker = "content-length:";
    const relative = std.mem.indexOf(u8, headers, marker) orelse return 0;
    const begin = relative + marker.len;
    const end = std.mem.indexOfScalarPos(u8, headers, begin, '\r') orelse headers.len;
    return std.fmt.parseInt(usize, std.mem.trim(u8, headers[begin..end], " \t"), 10) catch 0;
}

fn findHeader(headers: []const u8, name: []const u8) ?[]const u8 {
    var lines = std.mem.splitSequence(u8, headers, "\r\n");
    _ = lines.next();
    while (lines.next()) |line| {
        const colon = std.mem.indexOfScalar(u8, line, ':') orelse continue;
        if (std.ascii.eqlIgnoreCase(line[0..colon], name)) return std.mem.trim(u8, line[colon + 1 ..], " \t");
    }
    return null;
}

fn initFixtureRegistry(init: std.process.Init, environment: *const std.process.Environ.Map, endpoint: []const u8, dialect: provider_module.Dialect, retry: provider_module.RetryConfig, headers: []const provider_module.Header, models: []const provider_module.ModelCapability) !provider_module.ProviderRegistry {
    return provider_module.ProviderRegistry.init(init.gpa, init.io, environment, &.{.{ .id = "fixture", .endpoint = endpoint, .credential_ref = configured_credential_ref, .headers = headers, .dialect = dialect, .models = models, .retry = retry }});
}

fn printLine(io: std.Io, value: []const u8) !void {
    var storage: [256]u8 = undefined;
    var writer = std.Io.File.stdout().writer(io, &storage);
    try writer.interface.writeAll(value);
    try writer.interface.writeByte('\n');
    try writer.interface.flush();
}

fn runStream(init: std.process.Init, request_controls: bool) !void {
    const response_body = "\xef\xbb\xbf:fixture\r\nevent:message\r\ndata:{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\r\n\r\ndata:{\"choices\":[{\"delta\":{\"reasoning\":\"think\"}}]}\r\n\r\ndata:{\"choices\":[{\"delta\":{\"content\":\"streamed\"}}]}\n\n\ndata:{\"choices\":[{\"delta\":{\"content\":\" answer\"}}]}\n\n\ndata:{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"x\\\":\"}}]}}]}\n\ndata:{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"1}\"}}]}}]}\r\n\r\ndata:{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata:{\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10},\"choices\":[]}\n\ndata:[DONE]\n\n";
    const response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " ++ std.fmt.comptimePrint("{d}", .{response_body.len}) ++ "\r\nConnection: close\r\nX-Request-Id: fixture-req\r\n\r\n" ++ response_body;
    var address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var server = try std.Io.net.IpAddress.listen(&address, init.io, .{ .reuse_address = true });
    defer server.deinit(init.io);
    var state = ServerState{ .io = init.io, .server = &server, .response = response };
    var thread = try std.Thread.spawn(.{}, serverThread, .{&state});

    const endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{server.socket.address.getPort()});
    defer init.gpa.free(endpoint);
    const headers = [_]provider_module.Header{.{ .name = "x-fixture", .value = "runtime-03" }};
    var environment = std.process.Environ.Map.init(init.gpa);
    defer environment.deinit();
    try environment.put(configured_credential_ref, canary);
    const efforts = [_]protocol.ThinkingLevel{.high};
    const capabilities = [_]provider_module.ModelCapability{.{ .id = "fixture-model", .context_window = 4096, .output_limit = 128, .reasoning = true, .reasoning_efforts = &efforts, .short_cache_retention = if (request_controls) .prompt_cache_options_ttl_30m else null }};
    var registry = try initFixtureRegistry(init, &environment, endpoint, if (request_controls) .openai_chat else .openrouter_chat, .{ .max_retries = 0, .deadline_ms = 5_000 }, &headers, &capabilities);
    defer registry.deinit();
    const model = protocol.Model{ .id = "fixture-model", .api = "openai-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128, .reasoning = true };
    const user_content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &user_content, .timestamp_ms = 0 } }};
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const schema = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), "{\"type\":\"object\",\"properties\":{\"\\uE000\":{\"type\":\"integer\"},\"\\uD800\\uDC00\":{\"type\":\"integer\"}}}", .{});
    const tools = [_]protocol.Tool{.{ .name = "echo", .description = "fixture", .parameters = schema }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages, .cacheable_message_count = 1, .tools = &tools };
    var capture = Capture{};
    const options = protocol.StreamOptions{ .api_key = canary, .max_tokens = 64, .reasoning = .high, .cache_retention = if (request_controls) .short else .none, .transport = .sse, .timeout_ms = 5_000 };
    var stream_error: ?anyerror = null;
    registry.stream(&model, &context, options, &capture, sink) catch |err| {
        stream_error = err;
    };
    thread.join();
    if (stream_error) |err| return err;
    const thinking_ok = if (request_controls) capture.thinking_len == 0 else std.mem.eql(u8, capture.thinking[0..capture.thinking_len], "think");
    if (state.failed or !state.request_ok or capture.starts != 1 or capture.done_events != 1 or !capture.done_content_ok or capture.failed_events != 0 or capture.tool_starts != 1 or capture.tool_ends != 1 or capture.usage_events != 1 or !std.mem.eql(u8, capture.text[0..capture.text_len], "streamed answer") or !thinking_ok) {
        return error.ProviderFixtureFailed;
    }
    if (request_controls) {
        const request = state.request[0..state.request_len];
        const non_bmp_index = std.mem.indexOf(u8, request, "\"\xf0\x90\x80\x80\":{\"type\":\"integer\"}") orelse return error.ProviderRequestControlsFailed;
        const e000 = std.mem.indexOf(u8, request, "\"\xee\x80\x80\":{\"type\":\"integer\"}") orelse return error.ProviderRequestControlsFailed;
        if (std.mem.indexOf(u8, request, "x-fixture: runtime-03") == null or std.mem.indexOf(u8, request, "\"reasoning_effort\":\"high\"") == null or std.mem.indexOf(u8, request, "\"max_completion_tokens\":64") == null or std.mem.indexOf(u8, request, "\"prompt_cache_retention\"") != null or std.mem.indexOf(u8, request, "\"prompt_cache_options\":{\"mode\":\"implicit\",\"ttl\":\"30m\"}") == null or std.mem.indexOf(u8, request, "\"stream\":true") == null or std.mem.indexOf(u8, request, "transfer-encoding:") != null or std.mem.count(u8, request, "content-length:") != 1 or non_bmp_index >= e000) return error.ProviderRequestControlsFailed;
    }
}

fn runRegistryCredential(init: std.process.Init, observe_request: bool) !void {
    const response_body = "data:{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\ndata:{\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n";
    const response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " ++ std.fmt.comptimePrint("{d}", .{response_body.len}) ++ "\r\nConnection: close\r\n\r\n" ++ response_body;
    var address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var server = try std.Io.net.IpAddress.listen(&address, init.io, .{ .reuse_address = true });
    defer server.deinit(init.io);
    var state = ServerState{ .io = init.io, .server = &server, .response = response };
    var thread = try std.Thread.spawn(.{}, serverThread, .{&state});
    const endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{server.socket.address.getPort()});
    defer init.gpa.free(endpoint);

    var environment = std.process.Environ.Map.init(init.gpa);
    defer environment.deinit();
    try environment.put("RUNTIME_03_CONFIGURED_KEY", "registry-configured-token");
    const capabilities = [_]provider_module.ModelCapability{.{ .id = "fixture-model", .context_window = 4096, .output_limit = 128 }};
    const configs = [_]provider_module.ProviderConfig{.{ .id = "fixture", .endpoint = endpoint, .credential_ref = "RUNTIME_03_CONFIGURED_KEY", .models = &capabilities, .retry = .{ .max_retries = 0, .deadline_ms = 5_000 } }};
    var registry = try provider_module.ProviderRegistry.init(init.gpa, init.io, &environment, &configs);
    defer registry.deinit();
    const model = protocol.Model{ .id = "fixture-model", .api = "openai-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages };
    var capture = Capture{};
    var observed = ObservedRequest{};
    var stream_error: ?anyerror = null;
    if (observe_request) {
        registry.streamObserved(&model, &context, .{ .api_key = "caller-override-token", .timeout_ms = 60_000 }, &capture, sink, &observed, ObservedRequest.receive) catch |err| {
            stream_error = err;
        };
    } else {
        registry.stream(&model, &context, .{ .api_key = "caller-override-token", .timeout_ms = 60_000 }, &capture, sink) catch |err| {
            stream_error = err;
        };
    }
    thread.join();
    if (stream_error) |err| return err;
    const request = state.request[0..state.request_len];
    const authorization = findHeader(request, "authorization") orelse return error.ProviderCredentialOverrideAccepted;
    if (state.failed or !state.request_ok or capture.done_events != 1 or !std.mem.eql(u8, authorization, "Bearer registry-configured-token") or std.mem.indexOf(u8, request, "caller-override-token") != null) return error.ProviderCredentialOverrideAccepted;
    if (observe_request) {
        const metadata = observed.metadata orelse return error.ProviderMetadataNotObserved;
        const body_start = (std.mem.indexOf(u8, request, "\r\n\r\n") orelse return error.ProviderMetadataNotObserved) + 4;
        const body = request[body_start..];
        var hash: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
        std.crypto.hash.sha2.Sha256.hash(body, &hash, .{});
        if (metadata.cache_prefix_len > body.len) return error.ProviderMetadataNotObserved;
        var prefix_hash: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
        std.crypto.hash.sha2.Sha256.hash(body[0..metadata.cache_prefix_len], &prefix_hash, .{});
        if (observed.calls != 1 or metadata.request_len != body.len or !std.mem.eql(u8, &metadata.request_hash, &hash) or metadata.cache_prefix_len == 0 or !std.mem.eql(u8, &metadata.cache_prefix_hash, &prefix_hash)) return error.ProviderMetadataNotObserved;
    }
}

fn runFailure(init: std.process.Init) !void {
    const body = "{\"error\":{\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\",\"param\":\"model\",\"message\":\"quota " ++ canary ++ " " ++ header_canary ++ "\"}}";
    const response = "HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\nContent-Length: " ++ std.fmt.comptimePrint("{d}", .{body.len}) ++ "\r\nConnection: close\r\nRetry-After: 1\r\nX-Request-Id: failure-req\r\n\r\n" ++ body;
    var address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var server = try std.Io.net.IpAddress.listen(&address, init.io, .{ .reuse_address = true });
    defer server.deinit(init.io);
    var state = ServerState{ .io = init.io, .server = &server, .response = response };
    var thread = try std.Thread.spawn(.{}, serverThread, .{&state});
    const endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{server.socket.address.getPort()});
    defer init.gpa.free(endpoint);
    const headers = [_]provider_module.Header{.{ .name = "x-fixture-secret", .value = header_canary }};
    var environment = std.process.Environ.Map.init(init.gpa);
    defer environment.deinit();
    try environment.put(configured_credential_ref, canary);
    const capabilities = [_]provider_module.ModelCapability{.{ .id = "fixture-model", .context_window = 4096, .output_limit = 128 }};
    var registry = try initFixtureRegistry(init, &environment, endpoint, .openai_chat, .{ .max_retries = 0, .deadline_ms = 5_000 }, &headers, &capabilities);
    defer registry.deinit();
    const model = protocol.Model{ .id = "fixture-model", .api = "openai-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages };
    var capture = Capture{};
    var stream_error: ?anyerror = null;
    registry.stream(&model, &context, .{ .api_key = canary, .timeout_ms = 5_000 }, &capture, sink) catch |err| {
        stream_error = err;
    };
    thread.join();
    if (stream_error) |err| return err;
    if (state.failed or !state.request_ok or capture.failed_events != 1 or capture.failure_category != .quota_exhausted or capture.failure_status != 429 or capture.failure_retry_after_ms != 1_000 or std.mem.indexOf(u8, capture.failure_message[0..capture.failure_message_len], canary) != null or std.mem.indexOf(u8, capture.failure_message[0..capture.failure_message_len], header_canary) != null) return error.ProviderFailureFixtureFailed;

    const auth_body = "{\"error\":{\"code\":429,\"message\":\"Too Many Requests\"}}";
    const auth_response = "HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: " ++ std.fmt.comptimePrint("{d}", .{auth_body.len}) ++ "\r\nConnection: close\r\n\r\n" ++ auth_body;
    var auth_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var auth_server = try std.Io.net.IpAddress.listen(&auth_address, init.io, .{ .reuse_address = true });
    defer auth_server.deinit(init.io);
    var auth_state = ServerState{ .io = init.io, .server = &auth_server, .response = auth_response };
    var auth_thread = try std.Thread.spawn(.{}, serverThread, .{&auth_state});
    const auth_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{auth_server.socket.address.getPort()});
    defer init.gpa.free(auth_endpoint);
    var auth_registry = try initFixtureRegistry(init, &environment, auth_endpoint, .openrouter_chat, .{ .max_retries = 0, .deadline_ms = 5_000 }, &headers, &capabilities);
    defer auth_registry.deinit();
    const auth_model = protocol.Model{ .id = "fixture-model", .api = "openrouter-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    var auth_capture = Capture{};
    try auth_registry.stream(&auth_model, &context, .{ .timeout_ms = 5_000 }, &auth_capture, sink);
    auth_thread.join();
    if (auth_state.failed or !auth_state.request_ok or auth_capture.failed_events != 1 or auth_capture.failure_category != .authentication or auth_capture.failure_status != 401) return error.ProviderAuthenticationAuthorityFixtureFailed;

    const credits_body = "{\"error\":{\"message\":\"Insufficient credits\"}}";
    const credits_response = "HTTP/1.1 402 Payment Required\r\nContent-Type: application/json\r\nContent-Length: " ++ std.fmt.comptimePrint("{d}", .{credits_body.len}) ++ "\r\nConnection: close\r\n\r\n" ++ credits_body;
    var credits_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var credits_server = try std.Io.net.IpAddress.listen(&credits_address, init.io, .{ .reuse_address = true });
    defer credits_server.deinit(init.io);
    var credits_state = ServerState{ .io = init.io, .server = &credits_server, .response = credits_response };
    var credits_thread = try std.Thread.spawn(.{}, serverThread, .{&credits_state});
    const credits_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{credits_server.socket.address.getPort()});
    defer init.gpa.free(credits_endpoint);
    var credits_registry = try initFixtureRegistry(init, &environment, credits_endpoint, .openrouter_chat, .{ .max_retries = 0, .deadline_ms = 5_000 }, &headers, &capabilities);
    defer credits_registry.deinit();
    const credits_model = protocol.Model{ .id = "fixture-model", .api = "openrouter-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    var credits_capture = Capture{};
    try credits_registry.stream(&credits_model, &context, .{ .timeout_ms = 5_000 }, &credits_capture, sink);
    credits_thread.join();
    if (credits_state.failed or !credits_state.request_ok or credits_capture.failed_events != 1 or credits_capture.failure_category != .quota_exhausted or credits_capture.failure_status != 402) return error.ProviderCreditsFailureFixtureFailed;

    const oversized_body = try init.gpa.alloc(u8, provider_module.max_error_body_bytes + 1);
    defer init.gpa.free(oversized_body);
    @memset(oversized_body, 'x');
    const oversized_response = try std.fmt.allocPrint(init.gpa, "HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\nContent-Length: {d}\r\nConnection: close\r\nRetry-After: 1\r\n\r\n{s}", .{ oversized_body.len, oversized_body });
    defer init.gpa.free(oversized_response);
    var oversized_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var oversized_server = try std.Io.net.IpAddress.listen(&oversized_address, init.io, .{ .reuse_address = true });
    defer oversized_server.deinit(init.io);
    var oversized_state = ServerState{ .io = init.io, .server = &oversized_server, .response = oversized_response, .ignore_write_failure = true };
    var oversized_thread = try std.Thread.spawn(.{}, serverThread, .{&oversized_state});
    const oversized_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{oversized_server.socket.address.getPort()});
    defer init.gpa.free(oversized_endpoint);
    var oversized_registry = try initFixtureRegistry(init, &environment, oversized_endpoint, .openai_chat, .{ .max_retries = 0, .deadline_ms = 5_000 }, &headers, &capabilities);
    defer oversized_registry.deinit();
    var oversized_capture = Capture{};
    oversized_registry.stream(&model, &context, .{ .api_key = canary, .timeout_ms = 5_000 }, &oversized_capture, sink) catch |err| return err;
    oversized_thread.join();
    if (oversized_state.failed or oversized_capture.failed_events != 1 or oversized_capture.failure_category != .rate_limited or oversized_capture.failure_status != 429 or oversized_capture.failure_retry_after_ms != 1_000) return error.ProviderOversizedFailureFixtureFailed;

    const sse_error_body = "data:{\"error\":{\"code\":429,\"message\":\"Too Many Requests\"}}\n\n";
    const sse_error_response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " ++ std.fmt.comptimePrint("{d}", .{sse_error_body.len}) ++ "\r\nConnection: close\r\nRetry-After: 2\r\n\r\n" ++ sse_error_body;
    var sse_error_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var sse_error_server = try std.Io.net.IpAddress.listen(&sse_error_address, init.io, .{ .reuse_address = true });
    defer sse_error_server.deinit(init.io);
    const sse_error_responses = [_][]const u8{sse_error_response};
    var sse_error_state = ScriptedServerState{ .io = init.io, .server = &sse_error_server, .responses = &sse_error_responses, .ignore_write_failure = true };
    var sse_error_thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&sse_error_state});
    const sse_error_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{sse_error_server.socket.address.getPort()});
    defer init.gpa.free(sse_error_endpoint);
    var sse_error_registry = try initFixtureRegistry(init, &environment, sse_error_endpoint, .openrouter_chat, .{ .max_retries = 0, .deadline_ms = 5_000 }, &headers, &capabilities);
    defer sse_error_registry.deinit();
    var sse_error_capture = Capture{};
    try sse_error_registry.stream(&credits_model, &context, .{ .timeout_ms = 5_000 }, &sse_error_capture, sink);
    sse_error_thread.join();
    if (sse_error_state.failed or sse_error_state.requests != 1 or sse_error_capture.failed_events != 1 or sse_error_capture.failure_category != .rate_limited or sse_error_capture.failure_status != 200 or sse_error_capture.failure_retry_after_ms != 2_000 or sse_error_capture.done_events != 0) return error.ProviderSseRetryAfterFixtureFailed;

    const resource_body = "data:{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_cache_hit_tokens\":7,\"prompt_cache_miss_tokens\":3,\"total_tokens\":12},\"choices\":[{\"delta\":{},\"finish_reason\":\"insufficient_system_resource\"}]}\n\ndata:[DONE]\n\n";
    const resource_response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " ++ std.fmt.comptimePrint("{d}", .{resource_body.len}) ++ "\r\nConnection: close\r\n\r\n" ++ resource_body;
    var resource_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var resource_server = try std.Io.net.IpAddress.listen(&resource_address, init.io, .{ .reuse_address = true });
    defer resource_server.deinit(init.io);
    const resource_responses = [_][]const u8{resource_response};
    var resource_state = ScriptedServerState{ .io = init.io, .server = &resource_server, .responses = &resource_responses, .ignore_write_failure = true };
    var resource_thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&resource_state});
    const resource_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{resource_server.socket.address.getPort()});
    defer init.gpa.free(resource_endpoint);
    var resource_registry = try initFixtureRegistry(init, &environment, resource_endpoint, .deepseek_chat, .{ .max_retries = 0, .deadline_ms = 5_000 }, &headers, &capabilities);
    defer resource_registry.deinit();
    const resource_model = protocol.Model{ .id = "fixture-model", .api = "deepseek-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    var resource_capture = Capture{};
    try resource_registry.stream(&resource_model, &context, .{ .timeout_ms = 5_000 }, &resource_capture, sink);
    resource_thread.join();
    if (resource_state.failed or resource_state.requests != 1 or resource_capture.starts != 0 or resource_capture.usage_events != 0 or resource_capture.failed_events != 1 or resource_capture.failure_category != .transient_provider or resource_capture.failure_status != 200 or resource_capture.done_events != 0) return error.ProviderDeepSeekResourceFixtureFailed;

    const malformed_bodies = [_][]const u8{
        "bad\xffmessage",
        "{\"error\":{\"message\":\"bad\xffmessage\"}}",
    };
    for (malformed_bodies) |malformed_body| {
        const malformed_response = try std.fmt.allocPrint(init.gpa, "HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: {d}\r\nConnection: close\r\n\r\n{s}", .{ malformed_body.len, malformed_body });
        defer init.gpa.free(malformed_response);
        var malformed_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
        var malformed_server = try std.Io.net.IpAddress.listen(&malformed_address, init.io, .{ .reuse_address = true });
        defer malformed_server.deinit(init.io);
        var malformed_state = ServerState{ .io = init.io, .server = &malformed_server, .response = malformed_response };
        var malformed_thread = try std.Thread.spawn(.{}, serverThread, .{&malformed_state});
        const malformed_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{malformed_server.socket.address.getPort()});
        defer init.gpa.free(malformed_endpoint);
        var malformed_registry = try initFixtureRegistry(init, &environment, malformed_endpoint, .openai_chat, .{ .max_retries = 0, .deadline_ms = 5_000 }, &headers, &capabilities);
        defer malformed_registry.deinit();
        var malformed_capture = Capture{};
        try malformed_registry.stream(&model, &context, .{ .timeout_ms = 5_000 }, &malformed_capture, sink);
        malformed_thread.join();
        if (malformed_state.failed or !malformed_state.request_ok or malformed_capture.failed_events != 1 or malformed_capture.failure_category != .invalid_request or !std.unicode.utf8ValidateSlice(malformed_capture.failure_message[0..malformed_capture.failure_message_len])) return error.ProviderMalformedDiagnosticUtf8FixtureFailed;
    }
}

fn runInvalidDeltaFields(init: std.process.Init) !void {
    const Case = struct {
        api: []const u8,
        dialect: provider_module.Dialect,
        event: []const u8,
    };
    const cases = [_]Case{
        .{ .api = "openai-chat", .dialect = .openai_chat, .event = "{\"choices\":[{\"delta\":{\"content\":123},\"finish_reason\":\"stop\"}]}" },
        .{ .api = "openrouter-chat", .dialect = .openrouter_chat, .event = "{\"choices\":[{\"delta\":{\"reasoning\":123},\"finish_reason\":\"stop\"}]}" },
        .{ .api = "deepseek-chat", .dialect = .deepseek_chat, .event = "{\"choices\":[{\"delta\":{\"reasoning_content\":123},\"finish_reason\":\"stop\"}]}" },
        .{ .api = "openai-chat", .dialect = .openai_chat, .event = "{\"choices\":[{\"delta\":{\"role\":\"user\"}}]}" },
        .{ .api = "openai-chat", .dialect = .openai_chat, .event = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"type\":\"other\",\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]}}]}" },
        .{ .api = "openai-chat", .dialect = .openai_chat, .event = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":123}]}}]}" },
        .{ .api = "openai-chat", .dialect = .openai_chat, .event = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":null}]}}]}" },
        .{ .api = "openai-chat", .dialect = .openai_chat, .event = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":null,\"function\":null}]}}]}" },
        .{ .api = "openai-chat", .dialect = .openai_chat, .event = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":16,\"function\":null}]}}]}" },
        .{ .api = "openai-chat", .dialect = .openai_chat, .event = "{\"choices\":[{\"finish_reason\":\"stop\"}]}" },
    };
    var environment = std.process.Environ.Map.init(init.gpa);
    defer environment.deinit();
    try environment.put(configured_credential_ref, canary);
    const capabilities = [_]provider_module.ModelCapability{.{ .id = "fixture-model", .context_window = 4096, .output_limit = 128 }};
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages };
    for (cases) |case| {
        const response_body = try std.fmt.allocPrint(init.gpa, "data:{s}\n\ndata:[DONE]\n\n", .{case.event});
        defer init.gpa.free(response_body);
        const response = try std.fmt.allocPrint(init.gpa, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: {d}\r\nConnection: close\r\n\r\n{s}", .{ response_body.len, response_body });
        defer init.gpa.free(response);
        var address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
        var server = try std.Io.net.IpAddress.listen(&address, init.io, .{ .reuse_address = true });
        defer server.deinit(init.io);
        const responses = [_][]const u8{response};
        var state = ScriptedServerState{ .io = init.io, .server = &server, .responses = &responses, .ignore_write_failure = true };
        var thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&state});
        const endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{server.socket.address.getPort()});
        defer init.gpa.free(endpoint);
        var registry = try initFixtureRegistry(init, &environment, endpoint, case.dialect, .{ .max_retries = 0, .deadline_ms = 5_000 }, &.{}, &capabilities);
        defer registry.deinit();
        const model = protocol.Model{ .id = "fixture-model", .api = case.api, .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
        var capture = Capture{};
        try registry.stream(&model, &context, .{ .timeout_ms = 5_000 }, &capture, sink);
        thread.join();
        if (state.failed or state.requests != 1 or capture.starts != 0 or capture.text_len != 0 or capture.thinking_len != 0 or capture.tool_starts != 0 or capture.usage_events != 0 or capture.done_events != 0 or capture.failed_events != 1 or capture.failure_category != .malformed_response) return error.ProviderInvalidDeltaFixtureFailed;
    }
}

fn runToolFragments(init: std.process.Init) !void {
    const Case = struct { api: []const u8, dialect: provider_module.Dialect };
    const cases = [_]Case{
        .{ .api = "openai-chat", .dialect = .openai_chat },
        .{ .api = "openrouter-chat", .dialect = .openrouter_chat },
    };
    const response_body = "data:{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\"}]}}]}\n\ndata:{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]}}]}\n\ndata:{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata:[DONE]\n\n";
    var environment = std.process.Environ.Map.init(init.gpa);
    defer environment.deinit();
    try environment.put(configured_credential_ref, canary);
    const capabilities = [_]provider_module.ModelCapability{.{ .id = "fixture-model", .context_window = 4096, .output_limit = 128 }};
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages };
    for (cases) |case| {
        const response = try std.fmt.allocPrint(init.gpa, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: {d}\r\nConnection: close\r\n\r\n{s}", .{ response_body.len, response_body });
        defer init.gpa.free(response);
        var address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
        var server = try std.Io.net.IpAddress.listen(&address, init.io, .{ .reuse_address = true });
        defer server.deinit(init.io);
        const responses = [_][]const u8{response};
        var state = ScriptedServerState{ .io = init.io, .server = &server, .responses = &responses };
        var thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&state});
        const endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{server.socket.address.getPort()});
        defer init.gpa.free(endpoint);
        var registry = try initFixtureRegistry(init, &environment, endpoint, case.dialect, .{ .max_retries = 0, .deadline_ms = 5_000 }, &.{}, &capabilities);
        defer registry.deinit();
        const model = protocol.Model{ .id = "fixture-model", .api = case.api, .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
        var capture = Capture{};
        try registry.stream(&model, &context, .{ .timeout_ms = 5_000 }, &capture, sink);
        thread.join();
        if (state.failed or state.requests != 1 or capture.starts != 1 or capture.tool_starts != 1 or capture.tool_delta_len != 2 or capture.tool_ends != 1 or capture.done_events != 1 or capture.failed_events != 0) return error.ProviderToolFragmentsFixtureFailed;
    }

    const duplicate_body = "data:{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"duplicate\",\"function\":{\"name\":\"one\",\"arguments\":\"{}\"}},{\"index\":1,\"id\":\"duplicate\",\"function\":{\"name\":\"two\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata:[DONE]\n\n";
    const duplicate_response = try std.fmt.allocPrint(init.gpa, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: {d}\r\nConnection: close\r\n\r\n{s}", .{ duplicate_body.len, duplicate_body });
    defer init.gpa.free(duplicate_response);
    var duplicate_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var duplicate_server = try std.Io.net.IpAddress.listen(&duplicate_address, init.io, .{ .reuse_address = true });
    defer duplicate_server.deinit(init.io);
    const duplicate_responses = [_][]const u8{duplicate_response};
    var duplicate_state = ScriptedServerState{ .io = init.io, .server = &duplicate_server, .responses = &duplicate_responses };
    var duplicate_thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&duplicate_state});
    const duplicate_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{duplicate_server.socket.address.getPort()});
    defer init.gpa.free(duplicate_endpoint);
    var duplicate_registry = try initFixtureRegistry(init, &environment, duplicate_endpoint, .openai_chat, .{ .max_retries = 0, .deadline_ms = 5_000 }, &.{}, &capabilities);
    defer duplicate_registry.deinit();
    const duplicate_model = protocol.Model{ .id = "fixture-model", .api = "openai-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    var duplicate_capture = Capture{};
    try duplicate_registry.stream(&duplicate_model, &context, .{ .timeout_ms = 5_000 }, &duplicate_capture, sink);
    duplicate_thread.join();
    if (duplicate_state.failed or duplicate_state.requests != 1 or duplicate_capture.starts != 1 or duplicate_capture.tool_starts != 2 or duplicate_capture.tool_ends != 0 or duplicate_capture.done_events != 0 or duplicate_capture.failed_events != 1 or duplicate_capture.failure_category != .malformed_response) return error.ProviderDuplicateToolIdentityFixtureFailed;
}

fn runTerminalSemantics(init: std.process.Init) !void {
    const Case = struct {
        api: []const u8,
        dialect: provider_module.Dialect,
        body: []const u8,
        category: protocol.ProviderFailureCategory,
        starts: usize,
        text: []const u8,
    };
    const cases = [_]Case{
        .{ .api = "openai-chat", .dialect = .openai_chat, .body = "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\ndata:[DONE]\n\n", .category = .provider, .starts = 0, .text = "" },
        .{ .api = "openrouter-chat", .dialect = .openrouter_chat, .body = "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\ndata:[DONE]\n\n", .category = .provider, .starts = 0, .text = "" },
        .{ .api = "deepseek-chat", .dialect = .deepseek_chat, .body = "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\ndata:[DONE]\n\n", .category = .provider, .starts = 0, .text = "" },
        .{ .api = "openai-chat", .dialect = .openai_chat, .body = "data:{\"choices\":[{\"delta\":{\"content\":\"first\"},\"finish_reason\":\"stop\"}]}\n\ndata:{\"choices\":[{\"delta\":{\"content\":\"smuggled\"}}]}\n\ndata:[DONE]\n\n", .category = .malformed_response, .starts = 1, .text = "first" },
    };
    var environment = std.process.Environ.Map.init(init.gpa);
    defer environment.deinit();
    try environment.put(configured_credential_ref, canary);
    const capabilities = [_]provider_module.ModelCapability{.{ .id = "fixture-model", .context_window = 4096, .output_limit = 128 }};
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages };
    for (cases) |case| {
        const response = try std.fmt.allocPrint(init.gpa, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: {d}\r\nConnection: close\r\n\r\n{s}", .{ case.body.len, case.body });
        defer init.gpa.free(response);
        var address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
        var server = try std.Io.net.IpAddress.listen(&address, init.io, .{ .reuse_address = true });
        defer server.deinit(init.io);
        const responses = [_][]const u8{response};
        var state = ScriptedServerState{ .io = init.io, .server = &server, .responses = &responses, .ignore_write_failure = true };
        var thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&state});
        const endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{server.socket.address.getPort()});
        defer init.gpa.free(endpoint);
        var registry = try initFixtureRegistry(init, &environment, endpoint, case.dialect, .{ .max_retries = 0, .deadline_ms = 5_000 }, &.{}, &capabilities);
        defer registry.deinit();
        const model = protocol.Model{ .id = "fixture-model", .api = case.api, .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
        var capture = Capture{};
        try registry.stream(&model, &context, .{ .timeout_ms = 5_000 }, &capture, sink);
        thread.join();
        if (state.failed or state.requests != 1 or capture.failed_events != 1 or capture.failure_category != case.category or capture.done_events != 0 or capture.starts != case.starts or !std.mem.eql(u8, capture.text[0..capture.text_len], case.text)) return error.ProviderTerminalSemanticsFixtureFailed;
    }
}

fn runDeepSeekUsage(init: std.process.Init) !void {
    const response_body = "data:{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_cache_hit_tokens\":7,\"prompt_cache_miss_tokens\":3,\"total_tokens\":12},\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n";
    const response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " ++ std.fmt.comptimePrint("{d}", .{response_body.len}) ++ "\r\nConnection: close\r\n\r\n" ++ response_body;
    var address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var server = try std.Io.net.IpAddress.listen(&address, init.io, .{ .reuse_address = true });
    defer server.deinit(init.io);
    const responses = [_][]const u8{response};
    var state = ScriptedServerState{ .io = init.io, .server = &server, .responses = &responses };
    var thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&state});
    const endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{server.socket.address.getPort()});
    defer init.gpa.free(endpoint);
    var environment = std.process.Environ.Map.init(init.gpa);
    defer environment.deinit();
    try environment.put(configured_credential_ref, canary);
    const capabilities = [_]provider_module.ModelCapability{.{ .id = "fixture-model", .context_window = 4096, .output_limit = 128 }};
    var registry = try initFixtureRegistry(init, &environment, endpoint, .deepseek_chat, .{ .max_retries = 0, .deadline_ms = 5_000 }, &.{}, &capabilities);
    defer registry.deinit();
    const model = protocol.Model{ .id = "fixture-model", .api = "deepseek-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages };
    var capture = Capture{};
    try registry.stream(&model, &context, .{ .timeout_ms = 5_000 }, &capture, sink);
    thread.join();
    if (state.failed or state.requests != 1 or capture.failed_events != 0 or capture.done_events != 1 or capture.usage_events != 1 or capture.usage.input != 10 or capture.usage.output != 2 or capture.usage.cache_read != 7 or capture.usage.cache_write != 0 or capture.usage.total_tokens != 12) return error.ProviderDeepSeekUsageFixtureFailed;

    const invalid_body = "data:{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_cache_hit_tokens\":7,\"prompt_cache_miss_tokens\":999,\"total_tokens\":12},\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n";
    const invalid_response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " ++ std.fmt.comptimePrint("{d}", .{invalid_body.len}) ++ "\r\nConnection: close\r\n\r\n" ++ invalid_body;
    var invalid_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var invalid_server = try std.Io.net.IpAddress.listen(&invalid_address, init.io, .{ .reuse_address = true });
    defer invalid_server.deinit(init.io);
    const invalid_responses = [_][]const u8{invalid_response};
    var invalid_state = ScriptedServerState{ .io = init.io, .server = &invalid_server, .responses = &invalid_responses, .ignore_write_failure = true };
    var invalid_thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&invalid_state});
    const invalid_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{invalid_server.socket.address.getPort()});
    defer init.gpa.free(invalid_endpoint);
    var invalid_registry = try initFixtureRegistry(init, &environment, invalid_endpoint, .deepseek_chat, .{ .max_retries = 0, .deadline_ms = 5_000 }, &.{}, &capabilities);
    defer invalid_registry.deinit();
    var invalid_capture = Capture{};
    try invalid_registry.stream(&model, &context, .{ .timeout_ms = 5_000 }, &invalid_capture, sink);
    invalid_thread.join();
    if (invalid_state.failed or invalid_state.requests != 1 or invalid_capture.failed_events != 1 or invalid_capture.failure_category != .malformed_response or invalid_capture.done_events != 0 or invalid_capture.usage_events != 0) return error.ProviderDeepSeekUsageFixtureFailed;
}

fn runReliability(init: std.process.Init) !void {
    const response_body = "data:{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\ndata:{\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n";
    const success = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " ++ std.fmt.comptimePrint("{d}", .{response_body.len}) ++ "\r\nConnection: close\r\n\r\n" ++ response_body;
    const transient = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    const model = protocol.Model{ .id = "fixture-model", .api = "openai-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages };
    var environment = std.process.Environ.Map.init(init.gpa);
    defer environment.deinit();
    try environment.put(configured_credential_ref, canary);
    const capabilities = [_]provider_module.ModelCapability{.{ .id = "fixture-model", .context_window = 4096, .output_limit = 128 }};

    var retry_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var retry_server = try std.Io.net.IpAddress.listen(&retry_address, init.io, .{ .reuse_address = true });
    defer retry_server.deinit(init.io);
    const retry_responses = [_][]const u8{ transient, success };
    var retry_state = ScriptedServerState{ .io = init.io, .server = &retry_server, .responses = &retry_responses };
    var retry_thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&retry_state});
    const retry_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{retry_server.socket.address.getPort()});
    defer init.gpa.free(retry_endpoint);
    var retry_registry = try initFixtureRegistry(init, &environment, retry_endpoint, .openai_chat, .{ .max_retries = 1, .max_retry_delay_ms = 1, .deadline_ms = 2_000 }, &.{}, &capabilities);
    defer retry_registry.deinit();
    var retry_capture = Capture{};
    try retry_registry.stream(&model, &context, .{ .api_key = "caller-override-token", .timeout_ms = 60_000, .max_retries = 1, .max_retry_delay_ms = 1 }, &retry_capture, sink);
    retry_thread.join();
    if (retry_state.failed or retry_state.requests != 2 or retry_capture.done_events != 1 or retry_capture.failed_events != 0) return error.ProviderRetryFixtureFailed;

    const sse_retry_body = "data:{\"error\":{\"code\":429,\"message\":\"Too Many Requests\"}}\n\n";
    const sse_retry = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " ++ std.fmt.comptimePrint("{d}", .{sse_retry_body.len}) ++ "\r\nConnection: close\r\nRetry-After: 0\r\n\r\n" ++ sse_retry_body;
    var sse_retry_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var sse_retry_server = try std.Io.net.IpAddress.listen(&sse_retry_address, init.io, .{ .reuse_address = true });
    defer sse_retry_server.deinit(init.io);
    const sse_retry_responses = [_][]const u8{ sse_retry, success };
    var sse_retry_state = ScriptedServerState{ .io = init.io, .server = &sse_retry_server, .responses = &sse_retry_responses };
    var sse_retry_thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&sse_retry_state});
    const sse_retry_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{sse_retry_server.socket.address.getPort()});
    defer init.gpa.free(sse_retry_endpoint);
    var sse_retry_registry = try initFixtureRegistry(init, &environment, sse_retry_endpoint, .openrouter_chat, .{ .max_retries = 1, .max_retry_delay_ms = 1, .deadline_ms = 2_000 }, &.{}, &capabilities);
    defer sse_retry_registry.deinit();
    const sse_retry_model = protocol.Model{ .id = "fixture-model", .api = "openrouter-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    var sse_retry_capture = Capture{};
    try sse_retry_registry.stream(&sse_retry_model, &context, .{ .timeout_ms = 60_000, .max_retries = 1, .max_retry_delay_ms = 1 }, &sse_retry_capture, sink);
    sse_retry_thread.join();
    if (sse_retry_state.failed or sse_retry_state.requests != 2 or sse_retry_capture.done_events != 1 or sse_retry_capture.failed_events != 0) return error.ProviderSseRetryFixtureFailed;

    const resource_retry_body = "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"insufficient_system_resource\"}]}\n\n";
    const resource_retry = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " ++ std.fmt.comptimePrint("{d}", .{resource_retry_body.len}) ++ "\r\nConnection: close\r\n\r\n" ++ resource_retry_body;
    var resource_retry_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var resource_retry_server = try std.Io.net.IpAddress.listen(&resource_retry_address, init.io, .{ .reuse_address = true });
    defer resource_retry_server.deinit(init.io);
    const resource_retry_responses = [_][]const u8{ resource_retry, success };
    var resource_retry_state = ScriptedServerState{ .io = init.io, .server = &resource_retry_server, .responses = &resource_retry_responses };
    var resource_retry_thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&resource_retry_state});
    const resource_retry_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{resource_retry_server.socket.address.getPort()});
    defer init.gpa.free(resource_retry_endpoint);
    var resource_retry_registry = try initFixtureRegistry(init, &environment, resource_retry_endpoint, .deepseek_chat, .{ .max_retries = 1, .max_retry_delay_ms = 1, .deadline_ms = 2_000 }, &.{}, &capabilities);
    defer resource_retry_registry.deinit();
    const resource_retry_model = protocol.Model{ .id = "fixture-model", .api = "deepseek-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    var resource_retry_capture = Capture{};
    try resource_retry_registry.stream(&resource_retry_model, &context, .{ .timeout_ms = 60_000, .max_retries = 1, .max_retry_delay_ms = 1 }, &resource_retry_capture, sink);
    resource_retry_thread.join();
    if (resource_retry_state.failed or resource_retry_state.requests != 2 or resource_retry_capture.done_events != 1 or resource_retry_capture.failed_events != 0) return error.ProviderSseRetryFixtureFailed;

    var timeout_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var timeout_server = try std.Io.net.IpAddress.listen(&timeout_address, init.io, .{ .reuse_address = true });
    defer timeout_server.deinit(init.io);
    const no_response = [_][]const u8{""};
    var timeout_state = ScriptedServerState{ .io = init.io, .server = &timeout_server, .responses = &no_response, .delay_ms = 50 };
    var timeout_thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&timeout_state});
    const timeout_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{timeout_server.socket.address.getPort()});
    defer init.gpa.free(timeout_endpoint);
    var timeout_registry = try initFixtureRegistry(init, &environment, timeout_endpoint, .openai_chat, .{ .max_retries = 0, .deadline_ms = 10 }, &.{}, &capabilities);
    defer timeout_registry.deinit();
    var timeout_capture = Capture{};
    try timeout_registry.stream(&model, &context, .{ .api_key = "caller-override-token", .timeout_ms = 60_000 }, &timeout_capture, sink);
    timeout_thread.join();
    if (timeout_state.failed or timeout_state.requests != 1 or timeout_capture.failed_events != 1 or timeout_capture.failure_category != .timeout) return error.ProviderTimeoutFixtureFailed;

    for ([_]bool{ false, true }) |cancel_after_text| {
        var cancel_address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
        var cancel_server = try std.Io.net.IpAddress.listen(&cancel_address, init.io, .{ .reuse_address = true });
        defer cancel_server.deinit(init.io);
        const cancel_responses = [_][]const u8{success};
        var cancel_state = ScriptedServerState{ .io = init.io, .server = &cancel_server, .responses = &cancel_responses, .ignore_write_failure = true };
        var cancel_thread = try std.Thread.spawn(.{}, scriptedServerThread, .{&cancel_state});
        const cancel_endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{cancel_server.socket.address.getPort()});
        defer init.gpa.free(cancel_endpoint);
        var cancel_registry = try initFixtureRegistry(init, &environment, cancel_endpoint, .openai_chat, .{ .max_retries = 1, .max_retry_delay_ms = 1, .deadline_ms = 2_000 }, &.{}, &capabilities);
        defer cancel_registry.deinit();
        var cancel_capture = CancelCapture{ .cancel_after_text = cancel_after_text };
        const cancel_result = cancel_registry.stream(&model, &context, .{ .api_key = "caller-override-token", .max_retries = 1 }, &cancel_capture, cancelSink);
        if (cancel_result) |_| {
            return error.ProviderCancelFixtureFailed;
        } else |err| if (err != error.Canceled) return err;
        cancel_thread.join();
        if (cancel_state.failed or cancel_state.requests != 1 or cancel_capture.starts != 1 or (cancel_after_text and cancel_capture.text == 0)) return error.ProviderCancelFixtureFailed;
    }
}

fn runBlockedReadCancellation(init: std.process.Init) !void {
    var address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var server = try std.Io.net.IpAddress.listen(&address, init.io, .{ .reuse_address = true });
    defer server.deinit(init.io);
    var state = BlockedReadServerState{ .allocator = init.gpa, .server = &server };
    var thread = try std.Thread.spawn(.{}, blockedReadServerThread, .{&state});
    const endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{server.socket.address.getPort()});
    defer init.gpa.free(endpoint);
    var environment = std.process.Environ.Map.init(init.gpa);
    defer environment.deinit();
    try environment.put(configured_credential_ref, canary);
    const capabilities = [_]provider_module.ModelCapability{.{ .id = "fixture-model", .context_window = 4096, .output_limit = 128 }};
    var registry = try initFixtureRegistry(init, &environment, endpoint, .openai_chat, .{ .max_retries = 0, .deadline_ms = 100 }, &.{}, &capabilities);
    defer registry.deinit();
    const model = protocol.Model{ .id = "fixture-model", .api = "openai-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages };
    var capture = Capture{};
    const started = std.Io.Clock.Timestamp.now(init.io, .awake);
    var stream_error: ?anyerror = null;
    registry.stream(&model, &context, .{ .api_key = "caller-override-token", .timeout_ms = 60_000 }, &capture, sink) catch |err| {
        stream_error = err;
    };
    thread.join();
    const elapsed_ms = started.durationTo(std.Io.Clock.Timestamp.now(init.io, .awake)).raw.toMilliseconds();
    if (stream_error) |err| return err;
    if (elapsed_ms < 0 or elapsed_ms > 1_000 or state.failed.load(.acquire) or !state.request_received.load(.acquire) or !state.peer_closed.load(.acquire) or capture.starts != 0 or capture.text_len != 0 or capture.thinking_len != 0 or capture.tool_starts != 0 or capture.usage_events != 0 or capture.done_events != 0 or capture.failed_events != 1 or capture.failure_category != .timeout) return error.ProviderBlockedReadCancellationFailed;
}

fn runMalformedThenStall(init: std.process.Init) !void {
    var address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var server = try std.Io.net.IpAddress.listen(&address, init.io, .{ .reuse_address = true });
    defer server.deinit(init.io);
    var malformed_body: [16 * 1024]u8 = undefined;
    @memset(&malformed_body, ':');
    @memcpy(malformed_body[0.."data:{not valid json}\n\n".len], "data:{not valid json}\n\n");
    const malformed_prefix = try std.fmt.allocPrint(init.gpa, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n{x}\r\n{s}\r\n", .{ malformed_body.len, malformed_body });
    defer init.gpa.free(malformed_prefix);
    var state = BlockedReadServerState{ .allocator = init.gpa, .server = &server, .response_prefix = malformed_prefix, .ignore_write_failure = true };
    var thread = try std.Thread.spawn(.{}, blockedReadServerThread, .{&state});
    const endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{server.socket.address.getPort()});
    defer init.gpa.free(endpoint);
    var environment = std.process.Environ.Map.init(init.gpa);
    defer environment.deinit();
    try environment.put(configured_credential_ref, canary);
    const capabilities = [_]provider_module.ModelCapability{.{ .id = "fixture-model", .context_window = 4096, .output_limit = 128 }};
    var registry = try initFixtureRegistry(init, &environment, endpoint, .openai_chat, .{ .max_retries = 0, .deadline_ms = 100 }, &.{}, &capabilities);
    defer registry.deinit();
    const model = protocol.Model{ .id = "fixture-model", .api = "openai-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages };
    var capture = Capture{};
    const started = std.Io.Clock.Timestamp.now(init.io, .awake);
    try registry.stream(&model, &context, .{ .timeout_ms = 60_000 }, &capture, sink);
    thread.join();
    const elapsed_ms = started.durationTo(std.Io.Clock.Timestamp.now(init.io, .awake)).raw.toMilliseconds();
    if (elapsed_ms < 0 or elapsed_ms > 1_000) return error.ProviderMalformedStallTimingFailed;
    if (state.failed.load(.acquire) or !state.request_received.load(.acquire) or !state.peer_closed.load(.acquire)) return error.ProviderMalformedStallServerFailed;
    if (capture.starts != 0 or capture.text_len != 0 or capture.done_events != 0) return error.ProviderMalformedStallVisibilityFailed;
    if (capture.failed_events != 1 or capture.failure_category != .malformed_response) return error.ProviderMalformedStallSettlementFailed;
}

fn runLiveSmallEvent(init: std.process.Init) !void {
    var address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var server = try std.Io.net.IpAddress.listen(&address, init.io, .{ .reuse_address = true });
    defer server.deinit(init.io);
    const event = "data:{\"choices\":[{\"delta\":{\"content\":\"visible\"}}]}\n\n";
    const response_prefix = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n" ++ std.fmt.comptimePrint("{x}\r\n{s}\r\n", .{ event.len, event });
    var state = BlockedReadServerState{ .allocator = init.gpa, .server = &server, .response_prefix = response_prefix, .ignore_write_failure = true };
    var thread = try std.Thread.spawn(.{}, blockedReadServerThread, .{&state});
    const endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{server.socket.address.getPort()});
    defer init.gpa.free(endpoint);
    var environment = std.process.Environ.Map.init(init.gpa);
    defer environment.deinit();
    try environment.put(configured_credential_ref, canary);
    const capabilities = [_]provider_module.ModelCapability{.{ .id = "fixture-model", .context_window = 4096, .output_limit = 128 }};
    var registry = try initFixtureRegistry(init, &environment, endpoint, .openai_chat, .{ .max_retries = 0, .deadline_ms = 100 }, &.{}, &capabilities);
    defer registry.deinit();
    const model = protocol.Model{ .id = "fixture-model", .api = "openai-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages };
    var capture = LiveEventCapture{};
    const started = std.Io.Clock.Timestamp.now(init.io, .awake);
    const result = registry.stream(&model, &context, .{ .timeout_ms = 60_000 }, &capture, liveEventSink);
    thread.join();
    const elapsed_ms = started.durationTo(std.Io.Clock.Timestamp.now(init.io, .awake)).raw.toMilliseconds();
    if (result) |_| return error.ProviderLiveEventWasNotCanceled else |err| if (err != error.Canceled) return err;
    if (elapsed_ms < 0 or elapsed_ms > 1_000 or state.failed.load(.acquire) or !state.request_received.load(.acquire) or !state.peer_closed.load(.acquire) or capture.starts != 1 or capture.text != 1) return error.ProviderLiveEventFixtureFailed;
}

fn runDoneKeepOpen(init: std.process.Init) !void {
    var address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
    var server = try std.Io.net.IpAddress.listen(&address, init.io, .{ .reuse_address = true });
    defer server.deinit(init.io);
    const events = "data:{\"choices\":[{\"delta\":{\"content\":\"visible\"},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n";
    const response_prefix = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n" ++ std.fmt.comptimePrint("{x}\r\n{s}\r\n", .{ events.len, events });
    var state = BlockedReadServerState{ .allocator = init.gpa, .server = &server, .response_prefix = response_prefix, .ignore_write_failure = true };
    var thread = try std.Thread.spawn(.{}, blockedReadServerThread, .{&state});
    const endpoint = try std.fmt.allocPrint(init.gpa, "http://127.0.0.1:{d}/v1", .{server.socket.address.getPort()});
    defer init.gpa.free(endpoint);
    var environment = std.process.Environ.Map.init(init.gpa);
    defer environment.deinit();
    try environment.put(configured_credential_ref, canary);
    const capabilities = [_]provider_module.ModelCapability{.{ .id = "fixture-model", .context_window = 4096, .output_limit = 128 }};
    var registry = try initFixtureRegistry(init, &environment, endpoint, .openai_chat, .{ .max_retries = 0, .deadline_ms = 100 }, &.{}, &capabilities);
    defer registry.deinit();
    const model = protocol.Model{ .id = "fixture-model", .api = "openai-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128 };
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages };
    var capture = Capture{};
    const started = std.Io.Clock.Timestamp.now(init.io, .awake);
    try registry.stream(&model, &context, .{ .timeout_ms = 60_000 }, &capture, sink);
    thread.join();
    const elapsed_ms = started.durationTo(std.Io.Clock.Timestamp.now(init.io, .awake)).raw.toMilliseconds();
    if (elapsed_ms < 0 or elapsed_ms > 1_000 or state.failed.load(.acquire) or !state.accepted.load(.acquire) or !state.request_received.load(.acquire) or !state.peer_closed.load(.acquire) or capture.starts != 1 or !std.mem.eql(u8, capture.text[0..capture.text_len], "visible") or capture.done_events != 1 or capture.failed_events != 0) return error.ProviderDoneKeepOpenFixtureFailed;
}

pub fn main(init: std.process.Init) !void {
    var args = try std.process.Args.Iterator.initAllocator(init.minimal.args, init.gpa);
    defer args.deinit();
    _ = args.next();
    const mode = args.next() orelse "--stream";
    if (std.mem.eql(u8, mode, "--stream")) {
        try runStream(init, false);
        try printLine(init.io, "provider streaming: pass");
    } else if (std.mem.eql(u8, mode, "--request-controls")) {
        try runStream(init, true);
        try printLine(init.io, "provider request controls: pass");
    } else if (std.mem.eql(u8, mode, "--failures")) {
        try runFailure(init);
        try printLine(init.io, "provider failure semantics: pass");
    } else if (std.mem.eql(u8, mode, "--invalid-delta-fields")) {
        try runInvalidDeltaFields(init);
        try printLine(init.io, "provider invalid delta fields: pass");
    } else if (std.mem.eql(u8, mode, "--terminal-semantics")) {
        try runTerminalSemantics(init);
        try printLine(init.io, "provider terminal semantics: pass");
    } else if (std.mem.eql(u8, mode, "--tool-fragments")) {
        try runToolFragments(init);
        try printLine(init.io, "provider nullable tool fragments: pass");
    } else if (std.mem.eql(u8, mode, "--deepseek-usage")) {
        try runDeepSeekUsage(init);
        try printLine(init.io, "provider DeepSeek usage: pass");
    } else if (std.mem.eql(u8, mode, "--reliability")) {
        try runReliability(init);
        try printLine(init.io, "provider reliability semantics: pass");
    } else if (std.mem.eql(u8, mode, "--blocked-read-deadline")) {
        try runBlockedReadCancellation(init);
        try printLine(init.io, "provider blocked read deadline interruption: pass");
    } else if (std.mem.eql(u8, mode, "--malformed-stall")) {
        try runMalformedThenStall(init);
        try printLine(init.io, "provider malformed stream settlement: pass");
    } else if (std.mem.eql(u8, mode, "--live-small-event")) {
        try runLiveSmallEvent(init);
        try printLine(init.io, "provider live small SSE delivery: pass");
    } else if (std.mem.eql(u8, mode, "--done-keep-open")) {
        try runDoneKeepOpen(init);
        try printLine(init.io, "provider DONE keep-open settlement: pass");
    } else if (std.mem.eql(u8, mode, "--registry-credential")) {
        try runRegistryCredential(init, false);
        try printLine(init.io, "provider registry credential boundary: pass");
    } else if (std.mem.eql(u8, mode, "--metadata")) {
        try runRegistryCredential(init, true);
        try printLine(init.io, "provider rendered request metadata: pass");
    } else return error.UnknownMode;
}
