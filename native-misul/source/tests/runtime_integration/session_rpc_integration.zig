const std = @import("std");
const misul = @import("misul_agent");

const allocator = std.testing.allocator;

const StreamState = struct {
    calls: usize = 0,
};

fn testStream(
    raw: *anyopaque,
    model: *const misul.protocol.Model,
    _: *const misul.protocol.Context,
    _: misul.protocol.StreamOptions,
    sink_context: *anyopaque,
    sink: misul.protocol.StreamSink,
) !void {
    const state: *StreamState = @ptrCast(@alignCast(raw));
    state.calls += 1;
    const content = [_]misul.protocol.AssistantContent{.{ .text = .{ .text = "native response" } }};
    try sink(sink_context, .{ .done = .{
        .content = &content,
        .api = model.api,
        .provider = model.provider,
        .model = model.id,
        .stop_reason = .stop,
        .timestamp_ms = 2,
    } });
}

fn createRuntime(state_root: []const u8, environment: *const std.process.Environ.Map, stream: *StreamState) !*misul.runtime.Runtime {
    return misul.runtime.Runtime.createForTest(allocator, std.testing.io, environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{
            .provider = "test-provider",
            .model = "test-model",
            .context_window = 4096,
            .output_limit = 256,
        },
    }, stream, testStream);
}

fn call(server: *misul.rpc_server.Server, output: *std.ArrayList(u8), request: []const u8) !std.json.Parsed(std.json.Value) {
    output.clearRetainingCapacity();
    try server.handleLine(request, output);
    return std.json.parseFromSlice(std.json.Value, allocator, output.items, .{});
}

fn handshake(server: *misul.rpc_server.Server, output: *std.ArrayList(u8)) !void {
    var response = try call(server, output, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc/handshake\",\"params\":{\"protocol_major\":1,\"protocol_minor\":0}}\n");
    defer response.deinit();
    const result = response.value.object.get("result").?;
    const capabilities = result.object.get("capabilities").?;
    try std.testing.expect(capabilities.object.get("session_switch").?.bool);
    try std.testing.expect(capabilities.object.get("session_list").?.bool);
    try std.testing.expect(capabilities.object.get("session_history").?.bool);
    const limits = result.object.get("limits").?;
    try std.testing.expectEqual(@as(i64, 512 * 1024), limits.object.get("max_session_history_payload_bytes").?.integer);
    try std.testing.expect(limits.object.get("max_session_history_bytes") == null);
}

fn sessionExists(sessions: std.json.Array, session_id: []const u8) bool {
    for (sessions.items) |item| {
        const candidate = item.object.get("session_id") orelse continue;
        if (candidate == .string and std.mem.eql(u8, candidate.string, session_id)) return true;
    }
    return false;
}

fn expectErrorCode(response: std.json.Value, expected: i32) !void {
    const rpc_error = response.object.get("error") orelse return error.TestExpectedRpcError;
    const code = rpc_error.object.get("code") orelse return error.TestExpectedRpcErrorCode;
    try std.testing.expectEqual(expected, @as(i32, @intCast(code.integer)));
}

fn expectHistory(response: std.json.Value, prompt: []const u8) !void {
    const result = response.object.get("result").?;
    const messages = result.object.get("messages").?.array;
    try std.testing.expectEqual(@as(usize, 2), messages.items.len);

    const user = messages.items[0].object;
    const assistant = messages.items[1].object;
    const user_id = user.get("id").?.string;
    const assistant_id = assistant.get("id").?.string;
    try std.testing.expect(user_id.len != 0);
    try std.testing.expect(assistant_id.len != 0);
    try std.testing.expect(!std.mem.eql(u8, user_id, assistant_id));
    try std.testing.expect(user.get("parent_id") == null);
    try std.testing.expectEqualStrings("main", user.get("branch_id").?.string);
    try std.testing.expectEqualStrings(user_id, assistant.get("parent_id").?.string);
    try std.testing.expectEqualStrings("main", assistant.get("branch_id").?.string);

    const user_message = user.get("message").?.object.get("user").?;
    try std.testing.expectEqualStrings(prompt, user_message.object.get("content").?.array.items[0].object.get("text").?.object.get("text").?.string);
    const assistant_message = assistant.get("message").?.object.get("assistant").?;
    try std.testing.expectEqualStrings("native response", assistant_message.object.get("content").?.array.items[0].object.get("text").?.object.get("text").?.string);
}

test "server initialization does not create or grow durable sessions" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    var stream = StreamState{};
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);
    const runtime = try createRuntime(state_root, &environment, &stream);
    defer runtime.destroy();
    const before_init = runtime.highWater();

    {
        var server = try misul.rpc_server.Server.init(allocator, runtime);
        defer server.deinit();
    }
    {
        var server = try misul.rpc_server.Server.init(allocator, runtime);
        defer server.deinit();
    }

    var empty = try runtime.listSessions(allocator, null, 10);
    defer empty.deinit();
    try std.testing.expectEqual(before_init, runtime.highWater());
    try std.testing.expectEqual(@as(usize, 0), empty.sessions.len);

    {
        var server = try misul.rpc_server.Server.init(allocator, runtime);
        defer server.deinit();
        try handshake(&server, &output);
        var started = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/start\",\"params\":{\"session_id\":\"explicit-session\",\"model\":\"test-model\"}}\n");
        defer started.deinit();
    }
    {
        var server = try misul.rpc_server.Server.init(allocator, runtime);
        defer server.deinit();
    }

    var created = try runtime.listSessions(allocator, null, 10);
    defer created.deinit();
    try std.testing.expectEqual(@as(usize, 1), created.sessions.len);
    try std.testing.expectEqualStrings("explicit-session", created.sessions[0].session_id);
}

test "fresh servers use distinct default sessions and explicit resume stays stable" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    var stream = StreamState{};
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);
    var first_default_id: ?[]u8 = null;
    defer if (first_default_id) |value| allocator.free(value);

    {
        const first_runtime = try createRuntime(state_root, &environment, &stream);
        defer first_runtime.destroy();
        var first_server = try misul.rpc_server.Server.init(allocator, first_runtime);
        defer first_server.deinit();
        first_default_id = try allocator.dupe(u8, first_server.session_id);
        try handshake(&first_server, &output);
        var first_prompt = try call(&first_server, &output, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"first default\"}}\n");
        defer first_prompt.deinit();
    }

    const second_runtime = try createRuntime(state_root, &environment, &stream);
    defer second_runtime.destroy();
    var second_server = try misul.rpc_server.Server.init(allocator, second_runtime);
    defer second_server.deinit();
    try std.testing.expect(!std.mem.eql(u8, first_default_id.?, second_server.session_id));
    const second_default_id = try allocator.dupe(u8, second_server.session_id);
    defer allocator.free(second_default_id);
    try handshake(&second_server, &output);
    var second_prompt = try call(&second_server, &output, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"second default\"}}\n");
    defer second_prompt.deinit();

    var two_sessions = try second_runtime.listSessions(allocator, null, 10);
    defer two_sessions.deinit();
    try std.testing.expectEqual(@as(usize, 2), two_sessions.sessions.len);
    var found_first = false;
    var found_second = false;
    for (two_sessions.sessions) |summary| {
        found_first = found_first or std.mem.eql(u8, summary.session_id, first_default_id.?);
        found_second = found_second or std.mem.eql(u8, summary.session_id, second_default_id);
    }
    try std.testing.expect(found_first);
    try std.testing.expect(found_second);

    const resume_request = try std.fmt.allocPrint(allocator, "{{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/start\",\"params\":{{\"session_id\":\"{s}\",\"model\":\"test-model\"}}}}\n", .{first_default_id.?});
    defer allocator.free(resume_request);
    var resumed = try call(&second_server, &output, resume_request);
    defer resumed.deinit();
    try std.testing.expectEqualStrings(first_default_id.?, resumed.value.object.get("result").?.object.get("session_id").?.string);
    try std.testing.expectEqualStrings(first_default_id.?, second_server.session_id);

    var after_resume = try second_runtime.listSessions(allocator, null, 10);
    defer after_resume.deinit();
    try std.testing.expectEqual(@as(usize, 2), after_resume.sessions.len);
}

test "native RPC lists, reads, switches, and recovers durable sessions" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    var stream = StreamState{};
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);

    var session_b: []u8 = undefined;
    var session_a_assistant_message_id: ?[]u8 = null;
    defer if (session_a_assistant_message_id) |value| allocator.free(value);
    var session_a_created_at_ms: i64 = 0;
    var session_a_updated_at_ms: i64 = 0;
    var first_runtime = try createRuntime(state_root, &environment, &stream);
    var first_runtime_live = true;
    defer if (first_runtime_live) first_runtime.destroy();
    {
        var server = try misul.rpc_server.Server.init(allocator, first_runtime);
        defer server.deinit();
        try handshake(&server, &output);

        const before_invalid_new = first_runtime.highWater();
        var invalid_new_params = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"session/new\",\"params\":7}\n");
        defer invalid_new_params.deinit();
        try expectErrorCode(invalid_new_params.value, misul.rpc_server.ErrorCode.invalid_params);
        var invalid_new_model = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"session/new\",\"params\":{\"model\":42}}\n");
        defer invalid_new_model.deinit();
        try expectErrorCode(invalid_new_model.value, misul.rpc_server.ErrorCode.invalid_params);
        try std.testing.expectEqual(before_invalid_new, first_runtime.highWater());

        const before_invalid_start = first_runtime.highWater();
        var invalid_start_params = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":16,\"method\":\"session/start\",\"params\":7}\n");
        defer invalid_start_params.deinit();
        try expectErrorCode(invalid_start_params.value, misul.rpc_server.ErrorCode.invalid_params);
        var invalid_start_session = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"session/start\",\"params\":{\"session_id\":7}}\n");
        defer invalid_start_session.deinit();
        try expectErrorCode(invalid_start_session.value, misul.rpc_server.ErrorCode.invalid_params);
        try std.testing.expectEqual(before_invalid_start, first_runtime.highWater());

        var started_a = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/start\",\"params\":{\"session_id\":\"session-a\",\"model\":\"test-model\"}}\n");
        defer started_a.deinit();
        try std.testing.expectEqualStrings("session-a", started_a.value.object.get("result").?.object.get("session_id").?.string);

        var prompted_a = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"prompt a\",\"input_id\":\"client-input-a\"}}\n");
        defer prompted_a.deinit();
        const prompted_a_result = prompted_a.value.object.get("result").?;
        try std.testing.expectEqualStrings("completed", prompted_a_result.object.get("status").?.string);
        try std.testing.expect(prompted_a_result.object.get("input_message_id") != null);
        try std.testing.expect(prompted_a_result.object.get("assistant_message_id") != null);
        try std.testing.expectEqualStrings("client-input-a", prompted_a_result.object.get("input_message_id").?.string);
        const assistant_message_id_a = prompted_a_result.object.get("assistant_message_id").?.string;
        try std.testing.expect(assistant_message_id_a.len != 0);
        try std.testing.expect(!std.mem.eql(u8, "client-input-a", assistant_message_id_a));
        session_a_assistant_message_id = try allocator.dupe(u8, assistant_message_id_a);

        var created_b = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/new\",\"params\":{\"model\":\"test-model\"}}\n");
        defer created_b.deinit();
        session_b = try allocator.dupe(u8, created_b.value.object.get("result").?.object.get("session_id").?.string);
        errdefer allocator.free(session_b);
        try std.testing.expect(!std.mem.eql(u8, "session-a", session_b));

        var prompted_b = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"prompt b\"}}\n");
        defer prompted_b.deinit();
        const prompted_b_result = prompted_b.value.object.get("result").?;
        try std.testing.expect(prompted_b_result.object.get("input_message_id").?.string.len != 0);
        try std.testing.expect(prompted_b_result.object.get("assistant_message_id").?.string.len != 0);
        try std.testing.expectEqual(@as(usize, 2), stream.calls);

        var listed = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"session/list\",\"params\":{\"limit\":100}}\n");
        defer listed.deinit();
        const sessions = listed.value.object.get("result").?.object.get("sessions").?.array;
        try std.testing.expect(sessionExists(sessions, "session-a"));
        try std.testing.expect(sessionExists(sessions, session_b));
        try std.testing.expectEqual(@as(usize, 2), sessions.items.len);
        const ascending_first_session_id = sessions.items[0].object.get("session_id").?.string;
        const ascending_last_session_id = sessions.items[sessions.items.len - 1].object.get("session_id").?.string;
        for (sessions.items) |item| {
            const summary = item.object;
            try std.testing.expect(summary.get("created_at_ms") != null);
            try std.testing.expect(summary.get("updated_at_ms") != null);
            const created_at_ms = summary.get("created_at_ms").?.integer;
            const updated_at_ms = summary.get("updated_at_ms").?.integer;
            try std.testing.expect(created_at_ms > 0);
            try std.testing.expect(updated_at_ms >= created_at_ms);
            if (std.mem.eql(u8, summary.get("session_id").?.string, "session-a")) {
                session_a_created_at_ms = created_at_ms;
                session_a_updated_at_ms = updated_at_ms;
            }
        }
        try std.testing.expect(session_a_created_at_ms != 0);

        var first_page = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session/list\",\"params\":{\"limit\":1}}\n");
        defer first_page.deinit();
        const first_result = first_page.value.object.get("result").?;
        const first_sessions = first_result.object.get("sessions").?.array;
        try std.testing.expectEqual(@as(usize, 1), first_sessions.items.len);
        try std.testing.expect(first_result.object.get("has_more").?.bool);
        const list_cursor = first_result.object.get("next_after").?.string;
        const next_list_request = try std.fmt.allocPrint(allocator, "{{\"jsonrpc\":\"2.0\",\"id\":71,\"method\":\"session/list\",\"params\":{{\"limit\":1,\"after\":\"{s}\"}}}}\n", .{list_cursor});
        defer allocator.free(next_list_request);
        var second_page = try call(&server, &output, next_list_request);
        defer second_page.deinit();
        const second_sessions = second_page.value.object.get("result").?.object.get("sessions").?.array;
        try std.testing.expectEqual(@as(usize, 1), second_sessions.items.len);
        try std.testing.expect(!std.mem.eql(u8, first_sessions.items[0].object.get("session_id").?.string, second_sessions.items[0].object.get("session_id").?.string));

        var descending_first_page = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":72,\"method\":\"session/list\",\"params\":{\"order\":\"desc\",\"limit\":1}}\n");
        defer descending_first_page.deinit();
        const descending_first_result = descending_first_page.value.object.get("result").?;
        const descending_first_sessions = descending_first_result.object.get("sessions").?.array;
        try std.testing.expectEqual(@as(usize, 1), descending_first_sessions.items.len);
        try std.testing.expectEqualStrings(ascending_last_session_id, descending_first_sessions.items[0].object.get("session_id").?.string);
        try std.testing.expect(descending_first_result.object.get("has_more").?.bool);
        try std.testing.expect(descending_first_result.object.get("next_after") == null);
        const list_before_cursor = descending_first_result.object.get("next_before").?.string;
        const previous_list_request = try std.fmt.allocPrint(allocator, "{{\"jsonrpc\":\"2.0\",\"id\":73,\"method\":\"session/list\",\"params\":{{\"order\":\"desc\",\"limit\":1,\"before_session_id\":\"{s}\"}}}}\n", .{list_before_cursor});
        defer allocator.free(previous_list_request);
        var descending_second_page = try call(&server, &output, previous_list_request);
        defer descending_second_page.deinit();
        const descending_second_result = descending_second_page.value.object.get("result").?;
        const descending_second_sessions = descending_second_result.object.get("sessions").?.array;
        try std.testing.expectEqual(@as(usize, 1), descending_second_sessions.items.len);
        try std.testing.expectEqualStrings(ascending_first_session_id, descending_second_sessions.items[0].object.get("session_id").?.string);
        try std.testing.expect(!descending_second_result.object.get("has_more").?.bool);
        try std.testing.expect(!std.mem.eql(u8, list_before_cursor, descending_second_sessions.items[0].object.get("session_id").?.string));

        var ambiguous_list_cursor = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":74,\"method\":\"session/list\",\"params\":{\"order\":\"desc\",\"after\":\"session-a\",\"limit\":1}}\n");
        defer ambiguous_list_cursor.deinit();
        try expectErrorCode(ambiguous_list_cursor.value, misul.rpc_server.ErrorCode.invalid_params);
        var invalid_list_order = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":75,\"method\":\"session/list\",\"params\":{\"order\":\"sideways\",\"limit\":1}}\n");
        defer invalid_list_order.deinit();
        try expectErrorCode(invalid_list_order.value, misul.rpc_server.ErrorCode.invalid_params);

        var history_a = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"session/history\",\"params\":{\"session_id\":\"session-a\",\"limit\":10}}\n");
        defer history_a.deinit();
        try expectHistory(history_a.value, "prompt a");
        const ascending_history_messages = history_a.value.object.get("result").?.object.get("messages").?.array;
        try std.testing.expectEqualStrings("client-input-a", ascending_history_messages.items[0].object.get("id").?.string);
        try std.testing.expectEqualStrings(session_a_assistant_message_id.?, ascending_history_messages.items[1].object.get("id").?.string);
        const ascending_oldest_message_id = ascending_history_messages.items[0].object.get("id").?.string;
        const ascending_newest_message_id = ascending_history_messages.items[ascending_history_messages.items.len - 1].object.get("id").?.string;

        var history_first_page = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":81,\"method\":\"session/history\",\"params\":{\"session_id\":\"session-a\",\"limit\":1}}\n");
        defer history_first_page.deinit();
        const history_first_result = history_first_page.value.object.get("result").?;
        const history_first_messages = history_first_result.object.get("messages").?.array;
        try std.testing.expectEqual(@as(usize, 1), history_first_messages.items.len);
        try std.testing.expect(history_first_result.object.get("has_more").?.bool);
        const message_cursor = history_first_result.object.get("next_after").?.string;
        const next_history_request = try std.fmt.allocPrint(allocator, "{{\"jsonrpc\":\"2.0\",\"id\":82,\"method\":\"session/history\",\"params\":{{\"session_id\":\"session-a\",\"limit\":1,\"after_message_id\":\"{s}\"}}}}\n", .{message_cursor});
        defer allocator.free(next_history_request);
        var history_second_page = try call(&server, &output, next_history_request);
        defer history_second_page.deinit();
        const history_second_result = history_second_page.value.object.get("result").?;
        const history_second_messages = history_second_result.object.get("messages").?.array;
        try std.testing.expectEqual(@as(usize, 1), history_second_messages.items.len);
        try std.testing.expect(!history_second_result.object.get("has_more").?.bool);
        try std.testing.expectEqualStrings(message_cursor, history_second_messages.items[0].object.get("parent_id").?.string);

        var descending_history_first_page = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":83,\"method\":\"session/history\",\"params\":{\"session_id\":\"session-a\",\"order\":\"desc\",\"limit\":1}}\n");
        defer descending_history_first_page.deinit();
        const descending_history_first_result = descending_history_first_page.value.object.get("result").?;
        const descending_history_first_messages = descending_history_first_result.object.get("messages").?.array;
        try std.testing.expectEqual(@as(usize, 1), descending_history_first_messages.items.len);
        try std.testing.expectEqualStrings(ascending_newest_message_id, descending_history_first_messages.items[0].object.get("id").?.string);
        try std.testing.expect(descending_history_first_result.object.get("has_more").?.bool);
        try std.testing.expect(descending_history_first_result.object.get("next_after") == null);
        const message_before_cursor = descending_history_first_result.object.get("next_before").?.string;
        const previous_history_request = try std.fmt.allocPrint(allocator, "{{\"jsonrpc\":\"2.0\",\"id\":84,\"method\":\"session/history\",\"params\":{{\"session_id\":\"session-a\",\"order\":\"desc\",\"limit\":1,\"before_message_id\":\"{s}\"}}}}\n", .{message_before_cursor});
        defer allocator.free(previous_history_request);
        var descending_history_second_page = try call(&server, &output, previous_history_request);
        defer descending_history_second_page.deinit();
        const descending_history_second_result = descending_history_second_page.value.object.get("result").?;
        const descending_history_second_messages = descending_history_second_result.object.get("messages").?.array;
        try std.testing.expectEqual(@as(usize, 1), descending_history_second_messages.items.len);
        try std.testing.expectEqualStrings(ascending_oldest_message_id, descending_history_second_messages.items[0].object.get("id").?.string);
        try std.testing.expect(!descending_history_second_result.object.get("has_more").?.bool);
        try std.testing.expect(!std.mem.eql(u8, message_before_cursor, descending_history_second_messages.items[0].object.get("id").?.string));

        var ambiguous_history_cursor = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":85,\"method\":\"session/history\",\"params\":{\"session_id\":\"session-a\",\"order\":\"desc\",\"after_message_id\":\"missing\",\"limit\":1}}\n");
        defer ambiguous_history_cursor.deinit();
        try expectErrorCode(ambiguous_history_cursor.value, misul.rpc_server.ErrorCode.invalid_params);
        var invalid_history_order = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":86,\"method\":\"session/history\",\"params\":{\"session_id\":\"session-a\",\"order\":\"sideways\",\"limit\":1}}\n");
        defer invalid_history_order.deinit();
        try expectErrorCode(invalid_history_order.value, misul.rpc_server.ErrorCode.invalid_params);

        const before_invalid_input = first_runtime.highWater();
        var invalid_input_type = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":861,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"must not run\",\"input_id\":7}}\n");
        defer invalid_input_type.deinit();
        try expectErrorCode(invalid_input_type.value, misul.rpc_server.ErrorCode.invalid_params);
        var empty_input = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":862,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"must not run\",\"input_id\":\"\"}}\n");
        defer empty_input.deinit();
        try expectErrorCode(empty_input.value, misul.rpc_server.ErrorCode.invalid_params);
        var oversized_input_id: [misul.event_store.max_event_id_bytes + 1]u8 = undefined;
        @memset(&oversized_input_id, 'x');
        const oversized_input_request = try std.fmt.allocPrint(allocator, "{{\"jsonrpc\":\"2.0\",\"id\":863,\"method\":\"session/prompt\",\"params\":{{\"prompt\":\"must not run\",\"input_id\":\"{s}\"}}}}\n", .{&oversized_input_id});
        defer allocator.free(oversized_input_request);
        var oversized_input = try call(&server, &output, oversized_input_request);
        defer oversized_input.deinit();
        try expectErrorCode(oversized_input.value, misul.rpc_server.ErrorCode.invalid_params);
        try std.testing.expectEqual(before_invalid_input, first_runtime.highWater());

        const before_duplicate_input = first_runtime.highWater();
        const calls_before_duplicate_input = stream.calls;
        var duplicate_input = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":87,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"must not run\",\"input_id\":\"client-input-a\"}}\n");
        defer duplicate_input.deinit();
        try expectErrorCode(duplicate_input.value, misul.rpc_server.ErrorCode.invalid_params);
        try std.testing.expectEqual(before_duplicate_input, first_runtime.highWater());
        try std.testing.expectEqual(calls_before_duplicate_input, stream.calls);

        var switched = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"session/switch\",\"params\":{\"session_id\":\"session-a\"}}\n");
        defer switched.deinit();
        try std.testing.expectEqualStrings("session-a", switched.value.object.get("result").?.object.get("session_id").?.string);

        const before_missing_switch = first_runtime.highWater();
        var missing = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"session/switch\",\"params\":{\"session_id\":\"must-not-exist\"}}\n");
        defer missing.deinit();
        try std.testing.expect(missing.value.object.get("error") != null);
        try std.testing.expectEqual(before_missing_switch, first_runtime.highWater());
    }
    first_runtime.destroy();
    first_runtime_live = false;

    var second_runtime = try createRuntime(state_root, &environment, &stream);
    defer second_runtime.destroy();
    var server = try misul.rpc_server.Server.init(allocator, second_runtime);
    defer server.deinit();
    try handshake(&server, &output);

    var listed_after_restart = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"session/list\",\"params\":{\"limit\":100}}\n");
    defer listed_after_restart.deinit();
    const restarted_sessions = listed_after_restart.value.object.get("result").?.object.get("sessions").?.array;
    try std.testing.expectEqual(@as(usize, 2), restarted_sessions.items.len);
    try std.testing.expect(sessionExists(restarted_sessions, "session-a"));
    try std.testing.expect(sessionExists(restarted_sessions, session_b));
    for (restarted_sessions.items) |item| {
        const summary = item.object;
        if (!std.mem.eql(u8, summary.get("session_id").?.string, "session-a")) continue;
        try std.testing.expectEqual(session_a_created_at_ms, summary.get("created_at_ms").?.integer);
        try std.testing.expectEqual(session_a_updated_at_ms, summary.get("updated_at_ms").?.integer);
    }

    var history_after_restart = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"session/history\",\"params\":{\"session_id\":\"session-a\",\"limit\":10}}\n");
    defer history_after_restart.deinit();
    try expectHistory(history_after_restart.value, "prompt a");
    const restarted_messages = history_after_restart.value.object.get("result").?.object.get("messages").?.array;
    try std.testing.expectEqualStrings("client-input-a", restarted_messages.items[0].object.get("id").?.string);
    try std.testing.expectEqualStrings(session_a_assistant_message_id.?, restarted_messages.items[1].object.get("id").?.string);
    try std.testing.expectEqual(@as(usize, 2), stream.calls);

    var created_after_restart = try call(&server, &output, "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"session/new\",\"params\":{\"model\":\"test-model\"}}\n");
    defer created_after_restart.deinit();
    const session_c = created_after_restart.value.object.get("result").?.object.get("session_id").?.string;
    try std.testing.expect(!std.mem.eql(u8, session_c, "session-a"));
    try std.testing.expect(!std.mem.eql(u8, session_c, session_b));
    allocator.free(session_b);
}
