const std = @import("std");
const event_store = @import("event_store.zig");
const protocol = @import("protocol.zig");
const session = @import("session.zig");

pub const encoding_prefix = "misul-protocol-v1:";
pub const max_encoded_message_bytes = event_store.max_payload_bytes;
pub const max_content_items: usize = 4096;
pub const max_json_nesting: usize = 64;
pub const max_nested_json_bytes: usize = 4 * 1024 * 1024;
const max_message_field_bytes: usize = max_encoded_message_bytes;
const max_identifier_bytes: usize = 256;

/// Encode a typed provider message for a durable message_appended event.
/// The fixed writer bounds the complete wire payload before any bytes are
/// returned to the caller; no truncation is possible.
pub fn encodeMessage(allocator: std.mem.Allocator, message: protocol.Message) ![]u8 {
    try validateMessage(message);
    var count_buffer: [256]u8 = undefined;
    var counting = std.Io.Writer.Discarding.init(&count_buffer);
    counting.writer.writeAll(encoding_prefix) catch unreachable;
    std.json.Stringify.value(message, .{ .emit_null_optional_fields = false }, &counting.writer) catch |err| switch (err) {
        error.WriteFailed => return error.MessageTooLarge,
    };
    const encoded_size = counting.fullCount();
    if (encoded_size > @as(u64, max_encoded_message_bytes)) return error.MessageTooLarge;
    const encoded_size_usize: usize = @intCast(encoded_size);
    const output = try allocator.alloc(u8, encoded_size_usize);
    errdefer allocator.free(output);
    var writer = std.Io.Writer.fixed(output);
    writer.writeAll(encoding_prefix) catch unreachable;
    std.json.Stringify.value(message, .{ .emit_null_optional_fields = false }, &writer) catch |err| switch (err) {
        error.WriteFailed => return error.MessageTooLarge,
    };
    std.debug.assert(writer.end == encoded_size_usize);
    return output;
}

pub const Loaded = struct {
    parent_allocator: std.mem.Allocator,
    arena: *std.heap.ArenaAllocator,
    messages: []protocol.Message,

    pub fn deinit(self: *Loaded) void {
        self.arena.deinit();
        self.parent_allocator.destroy(self.arena);
        self.* = undefined;
    }
};

/// Decode the protocol messages stored in a durable projection branch. The
/// branch slice is borrowed for this call; all decoded values live in the
/// returned arena and are released by Loaded.deinit.
pub fn loadBranchMessages(branch: []const session.DurableMessage, allocator: std.mem.Allocator) !Loaded {
    const arena = try allocator.create(std.heap.ArenaAllocator);
    errdefer allocator.destroy(arena);
    arena.* = std.heap.ArenaAllocator.init(allocator);
    errdefer arena.deinit();
    const scratch = arena.allocator();
    var messages: std.ArrayList(protocol.Message) = .empty;
    for (branch) |message| {
        if (message.role != .custom or !std.mem.startsWith(u8, message.content, encoding_prefix)) continue;
        if (message.content.len > max_encoded_message_bytes) return error.MessageTooLarge;
        const decoded = std.json.parseFromSliceLeaky(protocol.Message, scratch, message.content[encoding_prefix.len..], .{
            .allocate = .alloc_always,
            .max_value_len = max_encoded_message_bytes,
        }) catch return error.InvalidConversation;
        validateMessage(decoded) catch return error.InvalidConversation;
        try messages.append(scratch, decoded);
    }
    return .{
        .parent_allocator = allocator,
        .arena = arena,
        .messages = try messages.toOwnedSlice(scratch),
    };
}

test "typed protocol messages encode with a bounded durable prefix" {
    const allocator = std.testing.allocator;
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const encoded = try encodeMessage(allocator, .{ .user = .{ .content = &content, .timestamp_ms = 1 } });
    defer allocator.free(encoded);
    try std.testing.expect(std.mem.startsWith(u8, encoded, encoding_prefix));
    try std.testing.expect(encoded.len <= max_encoded_message_bytes);
    const oversized_content = try allocator.alloc(u8, max_encoded_message_bytes);
    defer allocator.free(oversized_content);
    @memset(oversized_content, 'x');
    const oversized_user = [_]protocol.UserContent{.{ .text = .{ .text = oversized_content } }};
    try std.testing.expectError(error.MessageTooLarge, encodeMessage(allocator, .{ .user = .{ .content = &oversized_user, .timestamp_ms = 1 } }));
}

test "protocol messages round trip through durable projection messages" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "conversation-events.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var projection = session.DurableProjection.init(allocator);
    defer projection.deinit();

    const created_payload = try std.json.Stringify.valueAlloc(allocator, .{
        .session_id = "conversation",
        .branch_id = "main",
    }, .{});
    defer allocator.free(created_payload);
    var created_session = try projection.appendAndApply(&opened.store, .{ .kind = .session_created, .event_id = "session-1", .payload = created_payload }, "conversation:test", .succeeded);
    defer created_session.deinit(allocator);

    const user_content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const user = try encodeMessage(allocator, .{ .user = .{ .content = &user_content, .timestamp_ms = 1 } });
    defer allocator.free(user);
    const assistant_content = [_]protocol.AssistantContent{.{ .tool_call = .{
        .id = "call-1",
        .name = "read",
        .arguments = .{ .string = "file.txt" },
    } }};
    const assistant = try encodeMessage(allocator, .{ .assistant = .{
        .content = &assistant_content,
        .api = "test-api",
        .provider = "test-provider",
        .model = "test-model",
        .stop_reason = .tool_use,
        .timestamp_ms = 2,
    } });
    defer allocator.free(assistant);
    const failure = protocol.ProviderFailure{
        .category = .rate_limited,
        .http_status = 429,
        .request_id = "request-1",
        .provider_code = "rate_limit",
        .redacted_message = "try later",
        .retry_after_ms = 100,
    };
    const result_content = [_]protocol.UserContent{.{ .text = .{ .text = "error" } }};
    const result = try encodeMessage(allocator, .{ .tool_result = .{
        .tool_call_id = "call-1",
        .tool_name = "read",
        .content = &result_content,
        .details = .{ .string = "details" },
        .is_error = true,
        .timestamp_ms = 3,
    } });
    defer allocator.free(result);
    const assistant_failed = try encodeMessage(allocator, .{ .assistant = .{
        .content = &.{},
        .api = "test-api",
        .provider = "test-provider",
        .model = "test-model",
        .stop_reason = .@"error",
        .error_message = "provider failed",
        .provider_failure = failure,
        .timestamp_ms = 4,
    } });
    defer allocator.free(assistant_failed);

    var user_record = try appendDurableMessage(&projection, &opened.store, "m1", null, user);
    defer user_record.deinit(allocator);
    var tool_call = try appendDurableMessage(&projection, &opened.store, "m2", "m1", assistant);
    defer tool_call.deinit(allocator);
    var tool_result = try appendDurableMessage(&projection, &opened.store, "m3", "m2", result);
    defer tool_result.deinit(allocator);
    var failed = try appendDurableMessage(&projection, &opened.store, "m4", "m3", assistant_failed);
    defer failed.deinit(allocator);

    const branch = try projection.branchMessages(allocator, "main");
    var branch_owned = true;
    defer if (branch_owned) {
        for (branch) |*message| message.deinit(allocator);
        allocator.free(branch);
    };
    var loaded = try loadBranchMessages(branch, allocator);
    defer loaded.deinit();
    for (branch) |*message| message.deinit(allocator);
    allocator.free(branch);
    branch_owned = false;
    try std.testing.expectEqual(@as(usize, 4), loaded.messages.len);
    try std.testing.expectEqualStrings("hello", loaded.messages[0].user.content[0].text.text);
    try std.testing.expectEqualStrings("read", loaded.messages[1].assistant.content[0].tool_call.name);
    try std.testing.expectEqualStrings("file.txt", loaded.messages[1].assistant.content[0].tool_call.arguments.string);
    try std.testing.expectEqualStrings("call-1", loaded.messages[2].tool_result.tool_call_id);
    try std.testing.expect(loaded.messages[2].tool_result.is_error);
    try std.testing.expectEqual(protocol.ProviderFailureCategory.rate_limited, loaded.messages[3].assistant.provider_failure.?.category);
    try std.testing.expectEqualStrings("request-1", loaded.messages[3].assistant.provider_failure.?.request_id.?);
}

test "decoded protocol messages own nested strings after the durable source is freed" {
    const allocator = std.testing.allocator;
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "owned after replay" } }};
    const encoded = try encodeMessage(allocator, .{ .user = .{ .content = &content, .timestamp_ms = 9 } });
    var branch = [_]session.DurableMessage{.{
        .id = @constCast("m1"),
        .branch_id = @constCast("main"),
        .parent_id = null,
        .role = .custom,
        .content = encoded,
    }};
    var loaded = try loadBranchMessages(&branch, allocator);
    defer loaded.deinit();
    allocator.free(encoded);
    branch[0].content = &[_]u8{};
    try std.testing.expectEqualStrings("owned after replay", loaded.messages[0].user.content[0].text.text);
}

test "protocol message validation rejects invalid provider diagnostics and nested bounds" {
    const allocator = std.testing.allocator;
    const content = [_]protocol.AssistantContent{.{ .text = .{ .text = "failure" } }};
    const invalid_status = protocol.ProviderFailure{
        .category = .provider,
        .http_status = 99,
        .redacted_message = "failure",
    };
    try std.testing.expectError(error.InvalidProviderFailureHttpStatus, encodeMessage(allocator, .{ .assistant = .{
        .content = &content,
        .api = "api",
        .provider = "provider",
        .model = "model",
        .stop_reason = .@"error",
        .provider_failure = invalid_status,
        .timestamp_ms = 1,
    } }));

    const empty_failure = protocol.ProviderFailure{ .category = .provider, .redacted_message = "" };
    try std.testing.expectError(error.ProviderFailureMessageMissing, encodeMessage(allocator, .{ .assistant = .{
        .content = &content,
        .api = "api",
        .provider = "provider",
        .model = "model",
        .stop_reason = .@"error",
        .provider_failure = empty_failure,
        .timestamp_ms = 1,
    } }));

    var long_request_id: [protocol.max_provider_failure_identifier_bytes + 1]u8 = @splat('r');
    const oversized_failure = protocol.ProviderFailure{
        .category = .provider,
        .request_id = &long_request_id,
        .redacted_message = "failure",
    };
    try std.testing.expectError(error.ProviderFailureFieldTooLong, encodeMessage(allocator, .{ .assistant = .{
        .content = &content,
        .api = "api",
        .provider = "provider",
        .model = "model",
        .stop_reason = .@"error",
        .provider_failure = oversized_failure,
        .timestamp_ms = 1,
    } }));

    var too_many: [max_content_items + 1]protocol.UserContent = undefined;
    for (&too_many) |*item| item.* = .{ .text = .{ .text = "x" } };
    try std.testing.expectError(error.TooManyContentItems, encodeMessage(allocator, .{ .user = .{ .content = &too_many, .timestamp_ms = 1 } }));
}

test "non-protocol durable messages are ignored" {
    const allocator = std.testing.allocator;
    var branch = [_]session.DurableMessage{.{
        .id = @constCast("m1"),
        .branch_id = @constCast("main"),
        .parent_id = null,
        .role = .user,
        .content = @constCast("legacy text"),
    }};
    var loaded = try loadBranchMessages(&branch, allocator);
    defer loaded.deinit();
    try std.testing.expectEqual(@as(usize, 0), loaded.messages.len);
}

fn appendDurableMessage(
    projection: *session.DurableProjection,
    store: *event_store.Store,
    message_id: []const u8,
    parent_id: ?[]const u8,
    content: []const u8,
) !event_store.RecordRef {
    const payload = try std.json.Stringify.valueAlloc(std.testing.allocator, .{
        .session_id = "conversation",
        .branch_id = "main",
        .message_id = message_id,
        .parent_id = parent_id,
        .role = "custom",
        .content = content,
    }, .{ .emit_null_optional_fields = false });
    defer std.testing.allocator.free(payload);
    return projection.appendAndApply(store, .{ .kind = .message_appended, .event_id = message_id, .payload = payload }, "conversation:test", .succeeded);
}

fn validateMessage(message: protocol.Message) !void {
    switch (message) {
        .user => |user| {
            try validateContentCount(user.content.len);
            for (user.content) |item| try validateUserContent(item);
        },
        .assistant => |assistant| {
            try validateField(assistant.api);
            try validateField(assistant.provider);
            try validateField(assistant.model);
            if (assistant.error_message) |error_message| try validateField(error_message);
            if (assistant.provider_failure) |failure| try failure.validate();
            try validateContentCount(assistant.content.len);
            for (assistant.content) |item| try validateAssistantContent(item);
        },
        .tool_result => |result| {
            try validateIdentifier(result.tool_call_id);
            try validateIdentifier(result.tool_name);
            try validateContentCount(result.content.len);
            for (result.content) |item| try validateUserContent(item);
            if (result.details) |details| try validateJson(details);
        },
    }
}

fn validateUserContent(content: protocol.UserContent) !void {
    switch (content) {
        .text => |text| try validateField(text.text),
        .image => |image| {
            try validateField(image.data);
            try validateField(image.mime_type);
        },
    }
}

fn validateAssistantContent(content: protocol.AssistantContent) !void {
    switch (content) {
        .text => |text| try validateField(text.text),
        .thinking => |thinking| {
            try validateField(thinking.thinking);
            if (thinking.signature) |signature| try validateField(signature);
        },
        .tool_call => |call| {
            try validateIdentifier(call.id);
            try validateIdentifier(call.name);
            try validateJson(call.arguments);
        },
    }
}

fn validateContentCount(count: usize) !void {
    if (count > max_content_items) return error.TooManyContentItems;
}

fn validateIdentifier(value: []const u8) !void {
    if (value.len == 0 or value.len > max_identifier_bytes) return error.InvalidMessageIdentifier;
}

fn validateField(value: []const u8) !void {
    if (value.len > max_message_field_bytes) return error.MessageFieldTooLarge;
}

fn validateJson(value: std.json.Value) !void {
    try validateJsonShape(value, 0);
    var count_buffer: [256]u8 = undefined;
    var counting = std.Io.Writer.Discarding.init(&count_buffer);
    std.json.Stringify.value(value, .{}, &counting.writer) catch return error.InvalidNestedJson;
    if (counting.fullCount() > max_nested_json_bytes) return error.NestedJsonTooLarge;
}

fn validateJsonShape(value: std.json.Value, depth: usize) !void {
    if (depth > max_json_nesting) return error.JsonNestingTooDeep;
    switch (value) {
        .array => |array| {
            if (array.items.len > max_content_items) return error.TooManyJsonItems;
            for (array.items) |item| try validateJsonShape(item, depth + 1);
        },
        .object => |object| {
            if (object.count() > max_content_items) return error.TooManyJsonItems;
            var iterator = object.iterator();
            while (iterator.next()) |entry| {
                try validateField(entry.key_ptr.*);
                try validateJsonShape(entry.value_ptr.*, depth + 1);
            }
        },
        .string => |string| try validateField(string),
        .number_string => |number| try validateField(number),
        else => {},
    }
}
