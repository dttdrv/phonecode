const std = @import("std");
const misul_agent = @import("misul_agent");
const cache_telemetry = misul_agent.cache_telemetry;
const event_store = misul_agent.event_store;
const model_catalog = misul_agent.model_catalog;
const openai_compatible = misul_agent.openai_compatible;
const protocol = misul_agent.protocol;
const runtime_module = misul_agent.runtime;

const max_requests = 2;
const request_capacity = 512 * 1024;

const Reply = struct {
    bytes: []const u8,
    fragment: bool = false,
    block_until_peer_close: bool = false,
};

const CapturedRequest = struct {
    bytes: [request_capacity]u8 = undefined,
    len: usize = 0,

    fn slice(self: *const CapturedRequest) []const u8 {
        return self.bytes[0..self.len];
    }
};

const ServerFailure = enum {
    accept,
    request_read,
    request_too_large,
    response_write,
    unexpected_peer_data,
};

const LoopbackServer = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    listener: std.Io.net.Server,
    listener_open: bool = true,
    replies: []const Reply,
    requests: [max_requests]CapturedRequest = .{ .{}, .{} },
    request_count: usize = 0,
    fragment_writes: usize = 0,
    coalesced_sse_writes: usize = 0,
    failure: ?ServerFailure = null,
    stopping: std.atomic.Value(bool) = .init(false),
    request_received: std.atomic.Value(bool) = .init(false),
    peer_closed: std.atomic.Value(bool) = .init(false),
    thread: ?std.Thread = null,

    fn start(allocator: std.mem.Allocator, io: std.Io, replies: []const Reply) !*LoopbackServer {
        if (replies.len == 0 or replies.len > max_requests) return error.InvalidReplyScript;
        const self = try allocator.create(LoopbackServer);
        errdefer allocator.destroy(self);
        var address = std.Io.net.IpAddress{ .ip4 = .loopback(0) };
        const listener = try std.Io.net.IpAddress.listen(&address, io, .{ .reuse_address = true });
        self.* = .{
            .allocator = allocator,
            .io = io,
            .listener = listener,
            .replies = replies,
        };
        errdefer self.listener.deinit(io);
        self.thread = try std.Thread.spawn(.{}, serverMain, .{self});
        return self;
    }

    fn port(self: *const LoopbackServer) u16 {
        return self.listener.socket.address.getPort();
    }

    fn join(self: *LoopbackServer) !void {
        if (self.thread) |thread| {
            thread.join();
            self.thread = null;
        }
        if (self.failure != null) return error.LoopbackServerFailed;
        if (self.request_count != self.replies.len) return error.IncompleteReplyScript;
    }

    fn deinit(self: *LoopbackServer) void {
        self.stopping.store(true, .release);
        if (self.thread != null and self.listener_open) {
            self.listener.deinit(self.io);
            self.listener_open = false;
        }
        if (self.thread) |thread| {
            thread.join();
            self.thread = null;
        }
        if (self.listener_open) self.listener.deinit(self.io);
        const allocator = self.allocator;
        self.* = undefined;
        allocator.destroy(self);
    }
};

fn serverMain(server: *LoopbackServer) void {
    for (server.replies, 0..) |reply, reply_index| {
        var connection = server.listener.accept(server.io) catch {
            if (!server.stopping.load(.acquire)) server.failure = .accept;
            return;
        };
        defer connection.close(server.io);

        readRequest(server, &connection, reply_index) catch |err| {
            server.failure = switch (err) {
                error.RequestTooLarge => .request_too_large,
                else => .request_read,
            };
            return;
        };
        server.request_count += 1;
        server.request_received.store(true, .release);

        var write_storage: [8192]u8 = undefined;
        var writer = connection.writer(server.io, &write_storage);
        if (reply.fragment) {
            const chunk_sizes = [_]usize{ 1, 2, 5, 13, 31, 127 };
            var offset: usize = 0;
            var chunk_index: usize = 0;
            while (offset < reply.bytes.len) : (chunk_index += 1) {
                const end = @min(reply.bytes.len, offset + chunk_sizes[chunk_index % chunk_sizes.len]);
                writer.interface.writeAll(reply.bytes[offset..end]) catch {
                    server.failure = .response_write;
                    return;
                };
                writer.interface.flush() catch {
                    server.failure = .response_write;
                    return;
                };
                server.fragment_writes += 1;
                if (std.mem.count(u8, reply.bytes[offset..end], "\n\n") > 1) server.coalesced_sse_writes += 1;
                offset = end;
            }
        } else {
            writer.interface.writeAll(reply.bytes) catch {
                server.failure = .response_write;
                return;
            };
            writer.interface.flush() catch {
                server.failure = .response_write;
                return;
            };
        }

        if (reply.block_until_peer_close) {
            var read_storage: [64]u8 = undefined;
            var reader = connection.reader(server.io, &read_storage);
            reader.interface.fillMore() catch {
                server.peer_closed.store(true, .release);
                continue;
            };
            if (reader.interface.bufferedLen() == 0) {
                server.peer_closed.store(true, .release);
            } else {
                server.failure = .unexpected_peer_data;
                return;
            }
        }
    }
}

fn readRequest(server: *LoopbackServer, connection: *std.Io.net.Stream, index: usize) !void {
    var read_storage: [8192]u8 = undefined;
    var reader = connection.reader(server.io, &read_storage);
    const capture = &server.requests[index];
    var expected_total: ?usize = null;
    while (capture.len < capture.bytes.len) {
        try reader.interface.fillMore();
        const available = reader.interface.buffered();
        if (available.len == 0) return error.EndOfStream;
        const copy_len = @min(available.len, capture.bytes.len - capture.len);
        @memcpy(capture.bytes[capture.len .. capture.len + copy_len], available[0..copy_len]);
        reader.interface.toss(copy_len);
        capture.len += copy_len;
        if (expected_total == null) {
            if (std.mem.indexOf(u8, capture.slice(), "\r\n\r\n")) |header_end| {
                expected_total = header_end + 4 + (contentLength(capture.bytes[0 .. header_end + 4]) orelse return error.MissingContentLength);
                if (expected_total.? > capture.bytes.len) return error.RequestTooLarge;
            }
        }
        if (expected_total) |total| {
            if (capture.len >= total) {
                capture.len = total;
                return;
            }
        }
    }
    return error.RequestTooLarge;
}

fn contentLength(headers: []const u8) ?usize {
    var lines = std.mem.splitSequence(u8, headers, "\r\n");
    _ = lines.next();
    while (lines.next()) |line| {
        const colon = std.mem.indexOfScalar(u8, line, ':') orelse continue;
        if (!std.ascii.eqlIgnoreCase(line[0..colon], "content-length")) continue;
        return std.fmt.parseInt(usize, std.mem.trim(u8, line[colon + 1 ..], " \t"), 10) catch null;
    }
    return null;
}

fn headerValue(request: []const u8, name: []const u8) ?[]const u8 {
    const header_end = std.mem.indexOf(u8, request, "\r\n\r\n") orelse return null;
    var lines = std.mem.splitSequence(u8, request[0..header_end], "\r\n");
    _ = lines.next();
    while (lines.next()) |line| {
        const colon = std.mem.indexOfScalar(u8, line, ':') orelse continue;
        if (std.ascii.eqlIgnoreCase(line[0..colon], name)) return std.mem.trim(u8, line[colon + 1 ..], " \t");
    }
    return null;
}

fn requestBody(request: []const u8) ![]const u8 {
    const start = (std.mem.indexOf(u8, request, "\r\n\r\n") orelse return error.InvalidHttpRequest) + 4;
    return request[start..];
}

fn response(allocator: std.mem.Allocator, body: []const u8) ![]u8 {
    return std.fmt.allocPrint(allocator, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: {d}\r\nConnection: close\r\n\r\n{s}", .{ body.len, body });
}

fn endpoint(allocator: std.mem.Allocator, server: *const LoopbackServer, path: []const u8) ![]u8 {
    return std.fmt.allocPrint(allocator, "http://127.0.0.1:{d}/{s}", .{ server.port(), path });
}

const OwnedTelemetry = struct {
    session: [256]u8 = undefined,
    session_len: usize = 0,
    model: [256]u8 = undefined,
    model_len: usize = 0,
    provider: [128]u8 = undefined,
    provider_len: usize = 0,
    request_bytes: u64 = 0,
    request_hash: cache_telemetry.Hash = [_]u8{0} ** cache_telemetry.hash_length,
    prefix_bytes: u64 = 0,
    prefix_hash: cache_telemetry.Hash = [_]u8{0} ** cache_telemetry.hash_length,

    fn copyFrom(self: *OwnedTelemetry, call: cache_telemetry.Call) bool {
        if (call.session.len > self.session.len or call.model.len > self.model.len or call.provider.len > self.provider.len) return false;
        @memcpy(self.session[0..call.session.len], call.session);
        @memcpy(self.model[0..call.model.len], call.model);
        @memcpy(self.provider[0..call.provider.len], call.provider);
        self.session_len = call.session.len;
        self.model_len = call.model.len;
        self.provider_len = call.provider.len;
        self.request_bytes = call.request_bytes;
        self.request_hash = call.request_hash;
        self.prefix_bytes = call.prefix_bytes;
        self.prefix_hash = call.prefix_hash;
        return true;
    }
};

const TelemetryCapture = struct {
    items: [8]OwnedTelemetry = [_]OwnedTelemetry{.{}} ** 8,
    len: usize = 0,
    overflow: bool = false,
};

fn captureTelemetry(raw: *anyopaque, call: cache_telemetry.Call) void {
    const capture: *TelemetryCapture = @ptrCast(@alignCast(raw));
    if (capture.len == capture.items.len or !capture.items[capture.len].copyFrom(call)) {
        capture.overflow = true;
        return;
    }
    capture.len += 1;
}

fn assertTelemetryMatchesRequest(item: *const OwnedTelemetry, request: []const u8, provider: []const u8, model: []const u8) !void {
    const body = try requestBody(request);
    try std.testing.expectEqualStrings(provider, item.provider[0..item.provider_len]);
    try std.testing.expectEqualStrings(model, item.model[0..item.model_len]);
    try std.testing.expectEqual(@as(u64, @intCast(body.len)), item.request_bytes);
    try std.testing.expect(item.prefix_bytes != 0 and item.prefix_bytes <= body.len);
    var request_hash: cache_telemetry.Hash = undefined;
    std.crypto.hash.sha2.Sha256.hash(body, &request_hash, .{});
    try std.testing.expectEqualSlices(u8, &request_hash, &item.request_hash);
    var prefix_hash: cache_telemetry.Hash = undefined;
    std.crypto.hash.sha2.Sha256.hash(body[0..@intCast(item.prefix_bytes)], &prefix_hash, .{});
    try std.testing.expectEqualSlices(u8, &prefix_hash, &item.prefix_hash);
}

fn assertRequest(
    request: []const u8,
    path: []const u8,
    credential: []const u8,
    fixture_header: []const u8,
    required: []const []const u8,
    forbidden: []const []const u8,
) !void {
    const request_line = try std.fmt.allocPrint(std.testing.allocator, "POST /{s}/chat/completions HTTP/1.1\r\n", .{path});
    defer std.testing.allocator.free(request_line);
    try std.testing.expect(std.mem.startsWith(u8, request, request_line));
    const authorization = headerValue(request, "authorization") orelse return error.MissingAuthorization;
    const expected_authorization = try std.fmt.allocPrint(std.testing.allocator, "Bearer {s}", .{credential});
    defer std.testing.allocator.free(expected_authorization);
    try std.testing.expectEqualStrings(expected_authorization, authorization);
    try std.testing.expectEqualStrings(fixture_header, headerValue(request, "x-misul-fixture") orelse return error.MissingFixtureHeader);
    const body = try requestBody(request);
    for (required) |needle| try std.testing.expect(std.mem.indexOf(u8, body, needle) != null);
    for (forbidden) |needle| try std.testing.expect(std.mem.indexOf(u8, body, needle) == null);
}

fn rootPath(tmp: *std.testing.TmpDir, buffer: []u8) ![]const u8 {
    const len = try tmp.dir.realPath(std.testing.io, buffer);
    return buffer[0..len];
}

test "production Runtime routes configured OpenAI OpenRouter and DeepSeek providers with exact wire metadata" {
    const allocator = std.testing.allocator;
    const openai_body = "data:{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\ndata:{\"choices\":[{\"delta\":{\"content\":\"openai-ok\"},\"finish_reason\":\"stop\"}]}\n\ndata:{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12},\"choices\":[]}\n\ndata:[DONE]\n\n";
    // Realistic OpenRouter free-router stream: processing comments, reasoning deltas that also
    // carry an empty content string, and a finish chunk repeated on the final usage chunk with a
    // role/content-free delta, native_finish_reason, logprobs, and cost fields.
    const router_body = ": OPENROUTER PROCESSING\n\n" ++
        "data: {\"id\":\"gen-1\",\"provider\":\"Chutes\",\"model\":\"router-model\",\"object\":\"chat.completion.chunk\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":\"route\",\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"route\",\"format\":\"unknown\",\"index\":0}]},\"finish_reason\":null,\"native_finish_reason\":null,\"logprobs\":null}]}\n\n" ++
        ": OPENROUTER PROCESSING\n\n" ++
        "data: {\"id\":\"gen-1\",\"provider\":\"Chutes\",\"model\":\"router-model\",\"object\":\"chat.completion.chunk\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"router-ok\",\"reasoning\":null,\"reasoning_details\":[]},\"finish_reason\":null,\"native_finish_reason\":null,\"logprobs\":null}]}\n\n" ++
        "data: {\"id\":\"gen-1\",\"provider\":\"Chutes\",\"model\":\"router-model\",\"object\":\"chat.completion.chunk\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":null,\"reasoning_details\":[]},\"finish_reason\":\"stop\",\"native_finish_reason\":\"stop\",\"logprobs\":null}]}\n\n" ++
        "data: {\"id\":\"gen-1\",\"provider\":\"Chutes\",\"model\":\"router-model\",\"object\":\"chat.completion.chunk\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":null,\"reasoning_details\":[]},\"finish_reason\":\"stop\",\"native_finish_reason\":\"stop\",\"logprobs\":null}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3,\"total_tokens\":14,\"cost\":0,\"is_byok\":false,\"prompt_tokens_details\":{\"cached_tokens\":0,\"audio_tokens\":0},\"cost_details\":{\"upstream_inference_cost\":null,\"upstream_inference_prompt_cost\":0,\"upstream_inference_completion_cost\":0},\"completion_tokens_details\":{\"reasoning_tokens\":1,\"image_tokens\":0}}}\n\n" ++
        "data: [DONE]\n\n";
    const deepseek_body = "data:{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4,\"prompt_cache_hit_tokens\":5,\"prompt_cache_miss_tokens\":7,\"total_tokens\":16},\"choices\":[{\"delta\":{\"content\":\"deepseek-ok\"},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n";
    const openai_response = try response(allocator, openai_body);
    defer allocator.free(openai_response);
    const router_response = try response(allocator, router_body);
    defer allocator.free(router_response);
    const deepseek_response = try response(allocator, deepseek_body);
    defer allocator.free(deepseek_response);
    const openai_replies = [_]Reply{.{ .bytes = openai_response }};
    const router_replies = [_]Reply{.{ .bytes = router_response }};
    const deepseek_replies = [_]Reply{.{ .bytes = deepseek_response }};
    const openai_server = try LoopbackServer.start(allocator, std.testing.io, &openai_replies);
    defer openai_server.deinit();
    const router_server = try LoopbackServer.start(allocator, std.testing.io, &router_replies);
    defer router_server.deinit();
    const deepseek_server = try LoopbackServer.start(allocator, std.testing.io, &deepseek_replies);
    defer deepseek_server.deinit();
    const openai_endpoint = try endpoint(allocator, openai_server, "openai-base");
    defer allocator.free(openai_endpoint);
    const router_endpoint = try endpoint(allocator, router_server, "router-base");
    defer allocator.free(router_endpoint);
    const deepseek_endpoint = try endpoint(allocator, deepseek_server, "deepseek-base");
    defer allocator.free(deepseek_endpoint);

    const high_effort = [_]protocol.ThinkingLevel{.high};
    const medium_effort = [_]protocol.ThinkingLevel{.medium};
    const openai_models = [_]openai_compatible.ModelCapability{.{ .id = "openai-model", .context_window = 4096, .output_limit = 128, .reasoning = true, .reasoning_efforts = &high_effort, .short_cache_retention = .prompt_cache_options_ttl_30m }};
    const router_models = [_]openai_compatible.ModelCapability{.{ .id = "router-model", .context_window = 8192, .output_limit = 128, .reasoning = true, .reasoning_efforts = &medium_effort }};
    const deepseek_models = [_]openai_compatible.ModelCapability{.{ .id = "deepseek-model", .context_window = 16_384, .output_limit = 128, .reasoning = true, .reasoning_efforts = &high_effort }};
    const openai_headers = [_]openai_compatible.Header{.{ .name = "x-misul-fixture", .value = "openai-header" }};
    const router_headers = [_]openai_compatible.Header{.{ .name = "x-misul-fixture", .value = "router-header" }};
    const deepseek_headers = [_]openai_compatible.Header{.{ .name = "x-misul-fixture", .value = "deepseek-header" }};
    const providers = [_]openai_compatible.ProviderConfig{
        .{ .id = "provider-openai", .endpoint = openai_endpoint, .credential_ref = "openai-token", .credential_source = .explicit, .headers = &openai_headers, .dialect = .openai_chat, .models = &openai_models, .retry = .{ .max_retries = 0, .deadline_ms = 5_000 } },
        .{ .id = "provider-router", .endpoint = router_endpoint, .credential_ref = "router-token", .credential_source = .explicit, .headers = &router_headers, .dialect = .openrouter_chat, .models = &router_models, .retry = .{ .max_retries = 0, .deadline_ms = 5_000 } },
        .{ .id = "provider-deepseek", .endpoint = deepseek_endpoint, .credential_ref = "deepseek-token", .credential_source = .explicit, .headers = &deepseek_headers, .dialect = .deepseek_chat, .models = &deepseek_models, .retry = .{ .max_retries = 0, .deadline_ms = 5_000 } },
    };
    const profiles = [_]model_catalog.ModelProfile{
        .{ .id = "openai-model", .name = "OpenAI fixture", .provider = "provider-openai", .context_window = 4096, .output_limit = 77, .max_output_tokens = 128, .reasoning = .high, .cache_retention = .short, .transport = .sse },
        .{ .id = "router-model", .name = "OpenRouter fixture", .provider = "provider-router", .context_window = 8192, .output_limit = 66, .max_output_tokens = 128, .reasoning = .medium, .transport = .sse },
        .{ .id = "deepseek-model", .name = "DeepSeek fixture", .provider = "provider-deepseek", .context_window = 16_384, .output_limit = 55, .max_output_tokens = 128, .reasoning = .high, .transport = .sse },
    };

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root = try rootPath(&tmp, &root_buffer);
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    var telemetry = TelemetryCapture{};
    const runtime = try runtime_module.Runtime.create(allocator, std.testing.io, &environment, .{
        .workspace_root = root,
        .state_root = root,
        .system_prompt = "stable provider fixture",
        .default_selection = .{ .provider = "provider-openai", .model = "openai-model", .context_window = 4096, .output_limit = 77, .reasoning = .high, .cache_retention = .short, .transport = .sse },
        .model_profiles = &profiles,
        .provider_configs = &providers,
        .telemetry_context = &telemetry,
        .telemetry = captureTelemetry,
    });
    defer runtime.destroy();

    const openai_settlement = try runtime.wait(try runtime.startPrompt(.{ .session_id = "session-openai", .prompt = "route openai" }));
    try runtime.setSelection("session-router", .{ .provider = "provider-router", .model = "router-model", .context_window = 8192, .output_limit = 66, .reasoning = .medium, .transport = .sse });
    const router_settlement = try runtime.wait(try runtime.startPrompt(.{ .session_id = "session-router", .prompt = "route router" }));
    try runtime.setSelection("session-deepseek", .{ .provider = "provider-deepseek", .model = "deepseek-model", .context_window = 16_384, .output_limit = 55, .reasoning = .high, .transport = .sse });
    const deepseek_settlement = try runtime.wait(try runtime.startPrompt(.{ .session_id = "session-deepseek", .prompt = "route deepseek" }));
    try openai_server.join();
    try router_server.join();
    try deepseek_server.join();

    try std.testing.expectEqual(runtime_module.StopReason.complete, openai_settlement.stop_reason);
    try std.testing.expectEqual(runtime_module.StopReason.complete, router_settlement.stop_reason);
    try std.testing.expectEqual(runtime_module.StopReason.complete, deepseek_settlement.stop_reason);
    var openai_messages = try runtime.sessionMessages("session-openai", allocator);
    defer openai_messages.deinit();
    var router_messages = try runtime.sessionMessages("session-router", allocator);
    defer router_messages.deinit();
    var deepseek_messages = try runtime.sessionMessages("session-deepseek", allocator);
    defer deepseek_messages.deinit();
    try std.testing.expectEqual(@as(usize, 2), openai_messages.messages.len);
    try std.testing.expectEqual(@as(usize, 2), router_messages.messages.len);
    try std.testing.expectEqual(@as(usize, 2), deepseek_messages.messages.len);
    try std.testing.expectEqualStrings("openai-chat", openai_messages.messages[1].assistant.api);
    try std.testing.expectEqualStrings("provider-openai", openai_messages.messages[1].assistant.provider);
    try std.testing.expectEqualStrings("openai-model", openai_messages.messages[1].assistant.model);
    try std.testing.expectEqualStrings("openrouter-chat", router_messages.messages[1].assistant.api);
    try std.testing.expectEqualStrings("provider-router", router_messages.messages[1].assistant.provider);
    try std.testing.expectEqualStrings("router-model", router_messages.messages[1].assistant.model);
    const router_assistant = router_messages.messages[1].assistant;
    var router_text: ?[]const u8 = null;
    var router_thinking: ?[]const u8 = null;
    for (router_assistant.content) |block| switch (block) {
        .text => |text| router_text = text.text,
        .thinking => |thinking| router_thinking = thinking.thinking,
        else => {},
    };
    try std.testing.expectEqualStrings("router-ok", router_text.?);
    try std.testing.expectEqualStrings("route", router_thinking.?);
    try std.testing.expectEqual(@as(u64, 11), router_assistant.usage.input);
    try std.testing.expectEqual(@as(u64, 3), router_assistant.usage.output);
    try std.testing.expectEqual(@as(u64, 14), router_assistant.usage.total_tokens);
    try std.testing.expectEqualStrings("deepseek-chat", deepseek_messages.messages[1].assistant.api);
    try std.testing.expectEqualStrings("provider-deepseek", deepseek_messages.messages[1].assistant.provider);
    try std.testing.expectEqualStrings("deepseek-model", deepseek_messages.messages[1].assistant.model);
    try std.testing.expect(!telemetry.overflow);
    try std.testing.expectEqual(@as(usize, 3), telemetry.len);
    try assertRequest(openai_server.requests[0].slice(), "openai-base", "openai-token", "openai-header", &.{ "\"model\":\"openai-model\"", "\"max_completion_tokens\":77", "\"reasoning_effort\":\"high\"", "\"prompt_cache_options\":{\"mode\":\"implicit\",\"ttl\":\"30m\"}", "\"stream_options\":{\"include_usage\":true}" }, &.{ "\"session_id\"", "\"max_tokens\":", "\"thinking\":" });
    try assertRequest(router_server.requests[0].slice(), "router-base", "router-token", "router-header", &.{ "\"model\":\"router-model\"", "\"max_tokens\":66", "\"reasoning\":{\"effort\":\"medium\"}", "\"session_id\":\"session-router\"", "\"stream_options\":{\"include_usage\":true}" }, &.{ "\"max_completion_tokens\"", "\"prompt_cache_", "\"thinking\":" });
    try assertRequest(deepseek_server.requests[0].slice(), "deepseek-base", "deepseek-token", "deepseek-header", &.{ "\"model\":\"deepseek-model\"", "\"max_tokens\":55", "\"thinking\":{\"type\":\"enabled\"}", "\"reasoning_effort\":\"high\"" }, &.{ "\"session_id\"", "\"stream_options\"", "\"max_completion_tokens\"", "\"prompt_cache_" });
    try assertTelemetryMatchesRequest(&telemetry.items[0], openai_server.requests[0].slice(), "provider-openai", "openai-model");
    try assertTelemetryMatchesRequest(&telemetry.items[1], router_server.requests[0].slice(), "provider-router", "router-model");
    try assertTelemetryMatchesRequest(&telemetry.items[2], deepseek_server.requests[0].slice(), "provider-deepseek", "deepseek-model");
}

const RunCapture = struct {
    tool_starts: usize = 0,
    tool_finishes: usize = 0,
    settlements: usize = 0,
    text: [128]u8 = undefined,
    text_len: usize = 0,
    tool_result: [128]u8 = undefined,
    tool_result_len: usize = 0,
};

fn captureRunEvent(raw: *anyopaque, event: runtime_module.Event) !void {
    const capture: *RunCapture = @ptrCast(@alignCast(raw));
    switch (event.payload) {
        .provider_stream => |stream| switch (stream) {
            .text_delta => |delta| {
                if (capture.text_len + delta.delta.len > capture.text.len) return error.CaptureOverflow;
                @memcpy(capture.text[capture.text_len .. capture.text_len + delta.delta.len], delta.delta);
                capture.text_len += delta.delta.len;
            },
            else => {},
        },
        .tool_started => |call| {
            try std.testing.expectEqualStrings("read_file", call.name);
            capture.tool_starts += 1;
        },
        .tool_finished => |result| {
            capture.tool_finishes += 1;
            try std.testing.expect(!result.is_error);
            const text = result.content[0].text.text;
            if (text.len > capture.tool_result.len) return error.CaptureOverflow;
            @memcpy(capture.tool_result[0..text.len], text);
            capture.tool_result_len = text.len;
        },
        .settled => capture.settlements += 1,
        else => {},
    }
}

const DurableCounts = struct {
    settlements: usize = 0,
    cancellations: usize = 0,
    tool_starts: usize = 0,
    tool_finishes: usize = 0,
    settlement_state: ?event_store.Settlement = null,
};

fn countDurable(raw: *anyopaque, record: event_store.RecordView) !void {
    const counts: *DurableCounts = @ptrCast(@alignCast(raw));
    switch (record.kind) {
        .run_settled => {
            counts.settlements += 1;
            counts.settlement_state = record.settlement;
        },
        .cancellation_requested => counts.cancellations += 1,
        .tool_started => counts.tool_starts += 1,
        .tool_finished => counts.tool_finishes += 1,
        else => {},
    }
}

fn collectDurable(runtime: *runtime_module.Runtime) !DurableCounts {
    const high_water = runtime.highWater();
    if (high_water == 0 or high_water > 4096) return error.InvalidFixtureHighWater;
    const subscription = try runtime.subscribe(0, @intCast(high_water));
    defer runtime.unsubscribe(subscription);
    var counts = DurableCounts{};
    for (0..high_water) |_| try runtime.nextDurable(subscription, &counts, countDurable);
    return counts;
}

test "production Runtime executes fragmented SSE read_file turn and settles once" {
    const allocator = std.testing.allocator;
    const first_body = "data:{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\ndata:{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_read\",\"type\":\"function\"}]}}]}\n\ndata:{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"pa\"}}]}}]}\n\ndata:{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\":\\\"fixture.txt\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata:{\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":4,\"total_tokens\":24},\"choices\":[]}\n\ndata:[DONE]\n\n";
    const second_body = "data:{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\ndata:{\"choices\":[{\"delta\":{\"content\":\"tool turn complete\"},\"finish_reason\":\"stop\"}]}\n\ndata:{\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":3,\"total_tokens\":33},\"choices\":[]}\n\ndata:[DONE]\n\n";
    const first_response = try response(allocator, first_body);
    defer allocator.free(first_response);
    const second_response = try response(allocator, second_body);
    defer allocator.free(second_response);
    const replies = [_]Reply{ .{ .bytes = first_response, .fragment = true }, .{ .bytes = second_response } };
    const server = try LoopbackServer.start(allocator, std.testing.io, &replies);
    defer server.deinit();
    const provider_endpoint = try endpoint(allocator, server, "tool-base");
    defer allocator.free(provider_endpoint);

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    try tmp.dir.writeFile(std.testing.io, .{ .sub_path = "fixture.txt", .data = "tool-result-from-disk\n" });
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root = try rootPath(&tmp, &root_buffer);
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    const capabilities = [_]openai_compatible.ModelCapability{.{ .id = "tool-model", .context_window = 4096, .output_limit = 128 }};
    const providers = [_]openai_compatible.ProviderConfig{.{ .id = "provider-tool", .endpoint = provider_endpoint, .credential_ref = "tool-token", .credential_source = .explicit, .dialect = .openai_chat, .models = &capabilities, .retry = .{ .max_retries = 0, .deadline_ms = 5_000 } }};
    const profiles = [_]model_catalog.ModelProfile{.{ .id = "tool-model", .name = "Tool fixture", .provider = "provider-tool", .context_window = 4096, .output_limit = 64, .max_output_tokens = 128, .transport = .sse }};
    const runtime = try runtime_module.Runtime.create(allocator, std.testing.io, &environment, .{
        .workspace_root = root,
        .state_root = root,
        .system_prompt = "stable tool fixture",
        .default_selection = .{ .provider = "provider-tool", .model = "tool-model", .context_window = 4096, .output_limit = 64, .transport = .sse },
        .model_profiles = &profiles,
        .provider_configs = &providers,
    });
    defer runtime.destroy();
    var capture = RunCapture{};
    const run_id = try runtime.startPrompt(.{ .session_id = "tool-session", .prompt = "read fixture.txt", .event_context = &capture, .emit = captureRunEvent });
    const settlement = try runtime.wait(run_id);
    const high_water = runtime.highWater();
    const repeated = try runtime.wait(run_id);
    try server.join();

    try std.testing.expectEqual(runtime_module.StopReason.complete, settlement.stop_reason);
    try std.testing.expectEqual(settlement.run_id, repeated.run_id);
    try std.testing.expectEqual(high_water, runtime.highWater());
    try std.testing.expectEqual(@as(usize, 2), settlement.step_count);
    try std.testing.expect(server.fragment_writes > 10);
    try std.testing.expect(server.coalesced_sse_writes != 0);
    try std.testing.expectEqual(@as(usize, 2), server.request_count);
    try std.testing.expect(std.mem.indexOf(u8, try requestBody(server.requests[0].slice()), "\"name\":\"read_file\"") != null);
    const second_request = try requestBody(server.requests[1].slice());
    try std.testing.expect(std.mem.indexOf(u8, second_request, "\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_read\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"fixture.txt\\\"}\"}}]") != null);
    try std.testing.expect(std.mem.indexOf(u8, second_request, "\"role\":\"tool\",\"tool_call_id\":\"call_read\",\"content\":\"tool-result-from-disk\\n\"") != null);
    try std.testing.expectEqual(@as(usize, 1), capture.tool_starts);
    try std.testing.expectEqual(@as(usize, 1), capture.tool_finishes);
    try std.testing.expectEqual(@as(usize, 1), capture.settlements);
    try std.testing.expectEqualStrings("tool-result-from-disk\n", capture.tool_result[0..capture.tool_result_len]);
    try std.testing.expectEqualStrings("tool turn complete", capture.text[0..capture.text_len]);

    var messages = try runtime.sessionMessages("tool-session", allocator);
    defer messages.deinit();
    try std.testing.expectEqual(@as(usize, 4), messages.messages.len);
    try std.testing.expectEqualStrings("read_file", messages.messages[1].assistant.content[0].tool_call.name);
    try std.testing.expectEqualStrings("tool-result-from-disk\n", messages.messages[2].tool_result.content[0].text.text);
    try std.testing.expectEqualStrings("tool turn complete", messages.messages[3].assistant.content[0].text.text);
    const durable = try collectDurable(runtime);
    try std.testing.expectEqual(@as(usize, 1), durable.settlements);
    try std.testing.expectEqual(@as(usize, 1), durable.tool_starts);
    try std.testing.expectEqual(@as(usize, 1), durable.tool_finishes);
    try std.testing.expectEqual(event_store.Settlement.succeeded, durable.settlement_state.?);
}

fn waitForRequest(server: *LoopbackServer) !void {
    for (0..5_000) |_| {
        if (server.request_received.load(.acquire)) return;
        try std.Io.sleep(std.testing.io, .fromMilliseconds(1), .awake);
    }
    return error.ServerRequestTimeout;
}

test "production Runtime external cancellation interrupts a blocked provider read and joins" {
    const allocator = std.testing.allocator;
    const head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n";
    const replies = [_]Reply{.{ .bytes = head, .block_until_peer_close = true }};
    const server = try LoopbackServer.start(allocator, std.testing.io, &replies);
    defer server.deinit();
    const provider_endpoint = try endpoint(allocator, server, "blocked-base");
    defer allocator.free(provider_endpoint);
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root = try rootPath(&tmp, &root_buffer);
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    const capabilities = [_]openai_compatible.ModelCapability{.{ .id = "blocked-model", .context_window = 4096, .output_limit = 128 }};
    const providers = [_]openai_compatible.ProviderConfig{.{ .id = "provider-blocked", .endpoint = provider_endpoint, .credential_ref = "blocked-token", .credential_source = .explicit, .dialect = .openai_chat, .models = &capabilities, .retry = .{ .max_retries = 0, .deadline_ms = 10_000 } }};
    const profiles = [_]model_catalog.ModelProfile{.{ .id = "blocked-model", .name = "Blocked fixture", .provider = "provider-blocked", .context_window = 4096, .output_limit = 64, .max_output_tokens = 128, .transport = .sse }};
    const runtime = try runtime_module.Runtime.create(allocator, std.testing.io, &environment, .{
        .workspace_root = root,
        .state_root = root,
        .system_prompt = "stable cancellation fixture",
        .default_selection = .{ .provider = "provider-blocked", .model = "blocked-model", .context_window = 4096, .output_limit = 64, .transport = .sse },
        .model_profiles = &profiles,
        .provider_configs = &providers,
    });
    defer runtime.destroy();
    const run_id = try runtime.startPrompt(.{ .session_id = "cancel-session", .prompt = "wait for cancellation" });
    try waitForRequest(server);
    const settlement = try runtime.cancel(run_id);
    try server.join();
    try std.testing.expect(server.peer_closed.load(.acquire));
    try std.testing.expectEqual(runtime_module.StopReason.canceled, settlement.stop_reason);
    try std.testing.expectEqual(runtime_module.FailureCategory.canceled, settlement.failure);
    try std.testing.expect(runtime.activeRun("cancel-session") == null);
    const high_water = runtime.highWater();
    const repeated = try runtime.cancel(run_id);
    try std.testing.expectEqual(settlement.run_id, repeated.run_id);
    try std.testing.expectEqual(high_water, runtime.highWater());
    const durable = try collectDurable(runtime);
    try std.testing.expectEqual(@as(usize, 1), durable.cancellations);
    try std.testing.expectEqual(@as(usize, 1), durable.settlements);
    try std.testing.expectEqual(event_store.Settlement.cancelled, durable.settlement_state.?);
}
