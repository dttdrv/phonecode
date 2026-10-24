const std = @import("std");

pub const dynamic_boundary = "\n\n<!--misul-dynamic-->\n\n";

pub const ThinkingLevel = enum { minimal, low, medium, high, xhigh, max };
pub const Transport = enum { sse, websocket, auto };
pub const CacheRetention = enum { none, short, long };
pub const CacheAggressiveness = enum { conservative, balanced, aggressive };
pub const StopReason = enum { stop, length, tool_use, @"error", aborted };

pub const ModelSelection = struct {
    provider: []const u8,
    model: []const u8,
    context_window: u64,
    output_limit: u64,
    reasoning: ?ThinkingLevel = null,
    cache_retention: CacheRetention = .none,
    cache_aggressiveness: CacheAggressiveness = .balanced,
    transport: Transport = .auto,

    pub fn deinit(self: ModelSelection, allocator: std.mem.Allocator) void {
        allocator.free(self.model);
        allocator.free(self.provider);
    }
};

pub const max_provider_failure_message_bytes = 1024;
pub const max_provider_failure_identifier_bytes = 256;

pub const ProviderFailureCategory = enum {
    authentication,
    permission,
    rate_limited,
    quota_exhausted,
    context_length,
    invalid_request,
    unsupported,
    timeout,
    connection,
    malformed_response,
    transient_provider,
    provider,
};

/// Structured provider diagnostics are borrowed by stream callbacks. Consumers
/// that retain them must copy the bounded fields before the callback returns.
pub const ProviderFailure = struct {
    category: ProviderFailureCategory,
    http_status: ?u16 = null,
    request_id: ?[]const u8 = null,
    provider_code: ?[]const u8 = null,
    provider_type: ?[]const u8 = null,
    provider_param: ?[]const u8 = null,
    redacted_message: []const u8,
    retry_after_ms: ?u64 = null,

    pub fn validate(self: ProviderFailure) !void {
        if (self.redacted_message.len == 0) return error.ProviderFailureMessageMissing;
        if (self.redacted_message.len > max_provider_failure_message_bytes) return error.ProviderFailureFieldTooLong;
        inline for (.{ self.request_id, self.provider_code, self.provider_type, self.provider_param }) |field| {
            if (field) |value| {
                if (value.len > max_provider_failure_identifier_bytes) return error.ProviderFailureFieldTooLong;
            }
        }
        if (self.http_status) |status| {
            if (status < 100 or status > 599) return error.InvalidProviderFailureHttpStatus;
        }
    }
};

pub const TextContent = struct { text: []const u8 };
pub const ImageContent = struct { data: []const u8, mime_type: []const u8 };
pub const ThinkingContent = struct { thinking: []const u8, signature: ?[]const u8 = null };
pub const ToolCall = struct { id: []const u8, name: []const u8, arguments: std.json.Value };
pub const AssistantContent = union(enum) {
    text: TextContent,
    thinking: ThinkingContent,
    tool_call: ToolCall,
};
pub const UserContent = union(enum) { text: TextContent, image: ImageContent };

pub const Cost = struct {
    input: f64 = 0,
    output: f64 = 0,
    cache_read: f64 = 0,
    cache_write: f64 = 0,
    total: f64 = 0,
};

pub const Usage = struct {
    input: u64 = 0,
    output: u64 = 0,
    cache_read: u64 = 0,
    cache_write: u64 = 0,
    total_tokens: u64 = 0,
    cost: Cost = .{},
};

pub const UserMessage = struct { content: []const UserContent, timestamp_ms: i64 };
pub const AssistantMessage = struct {
    content: []const AssistantContent,
    api: []const u8,
    provider: []const u8,
    model: []const u8,
    stop_reason: StopReason,
    error_message: ?[]const u8 = null,
    provider_failure: ?ProviderFailure = null,
    timestamp_ms: i64,
    usage: Usage = .{},
};
pub const ToolResultMessage = struct {
    tool_call_id: []const u8,
    tool_name: []const u8,
    content: []const UserContent,
    details: ?std.json.Value = null,
    is_error: bool,
    timestamp_ms: i64,
};
pub const Message = union(enum) {
    user: UserMessage,
    assistant: AssistantMessage,
    tool_result: ToolResultMessage,
};

pub const Tool = struct { name: []const u8, description: []const u8, parameters: std.json.Value };
pub const Context = struct {
    system_prompt: []const u8,
    system_prompt_lanes: []const []const u8 = &.{},
    messages: []const Message,
    cacheable_message_count: usize = 0,
    tools: []const Tool = &.{},

    pub fn validate(self: Context) !void {
        if (self.cacheable_message_count > self.messages.len) return error.InvalidCacheableMessageCount;
    }
};
pub const StreamOptions = struct {
    api_key: ?[]const u8 = null,
    max_tokens: ?u64 = null,
    reasoning: ?ThinkingLevel = null,
    timeout_ms: ?u64 = null,
    max_retries: ?u32 = null,
    max_retry_delay_ms: ?u64 = null,
    cache_retention: CacheRetention = .none,
    cache_aggressiveness: CacheAggressiveness = .balanced,
    transport: Transport = .auto,
    session_id: ?[]const u8 = null,
};

pub const Model = struct {
    id: []const u8,
    name: ?[]const u8 = null,
    api: []const u8,
    provider: []const u8,
    context_window: u64,
    max_tokens: u64,
    reasoning: bool = false,
};

pub const StreamEvent = union(enum) {
    start: AssistantMessage,
    text_start: usize,
    text_delta: struct { content_index: usize, delta: []const u8 },
    text_end: struct { content_index: usize, content: []const u8 },
    thinking_start: usize,
    thinking_delta: struct { content_index: usize, delta: []const u8 },
    thinking_end: struct { content_index: usize, content: []const u8 },
    tool_call_start: usize,
    tool_call_delta: struct { content_index: usize, delta: []const u8 },
    tool_call_end: struct { content_index: usize, tool_call: ToolCall },
    usage: Usage,
    done: AssistantMessage,
    failed: AssistantMessage,
};
pub const StreamSink = *const fn (context: *anyopaque, event: StreamEvent) anyerror!void;
pub const StreamFn = *const fn (
    provider_context: *anyopaque,
    model: *const Model,
    context: *const Context,
    options: StreamOptions,
    sink_context: *anyopaque,
    sink: StreamSink,
) anyerror!void;

pub fn joinSystemPromptLanes(allocator: std.mem.Allocator, stable: []const u8, session: []const u8) ![]u8 {
    if (stable.len == 0) return allocator.dupe(u8, session);
    if (session.len == 0) return allocator.dupe(u8, stable);
    return std.mem.concat(allocator, u8, &.{ stable, dynamic_boundary, session });
}

pub const SplitPrompt = struct {
    stable: []const u8,
    session: []const u8,

    pub fn lanes(self: SplitPrompt, output: *[2][]const u8) []const []const u8 {
        var count: usize = 0;
        if (self.stable.len != 0) {
            output[count] = self.stable;
            count += 1;
        }
        if (self.session.len != 0) {
            output[count] = self.session;
            count += 1;
        }
        return output[0..count];
    }
};

pub fn splitSystemPromptLanes(prompt: []const u8) SplitPrompt {
    const index = std.mem.indexOf(u8, prompt, dynamic_boundary) orelse return .{ .stable = prompt, .session = "" };
    return .{
        .stable = prompt[0..index],
        .session = prompt[index + dynamic_boundary.len ..],
    };
}

test "system prompt lanes preserve a deterministic stable prefix" {
    const allocator = std.testing.allocator;
    const joined = try joinSystemPromptLanes(allocator, "stable", "session");
    defer allocator.free(joined);
    try std.testing.expectEqualStrings("stable\n\n<!--misul-dynamic-->\n\nsession", joined);

    const split = splitSystemPromptLanes(joined);
    try std.testing.expectEqualStrings("stable", split.stable);
    try std.testing.expectEqualStrings("session", split.session);
    var storage: [2][]const u8 = undefined;
    const lanes = split.lanes(&storage);
    try std.testing.expectEqual(@as(usize, 2), lanes.len);
}

test "empty prompt lanes do not add a boundary" {
    const allocator = std.testing.allocator;
    const stable = try joinSystemPromptLanes(allocator, "stable", "");
    defer allocator.free(stable);
    try std.testing.expectEqualStrings("stable", stable);
    const session = try joinSystemPromptLanes(allocator, "", "session");
    defer allocator.free(session);
    try std.testing.expectEqualStrings("session", session);
}

test "provider failure diagnostics are typed and bounded" {
    const failure = ProviderFailure{
        .category = .rate_limited,
        .http_status = 429,
        .request_id = "req-123",
        .provider_code = "rate_limit_exceeded",
        .provider_type = "requests",
        .provider_param = "model",
        .redacted_message = "request rate limit reached",
        .retry_after_ms = 250,
    };
    try failure.validate();

    var oversized: [max_provider_failure_message_bytes + 1]u8 = @splat('x');
    var invalid = failure;
    invalid.redacted_message = &oversized;
    try std.testing.expectError(error.ProviderFailureFieldTooLong, invalid.validate());
}

test "context rejects a cacheable prefix beyond its message list" {
    const content = [_]UserContent{.{ .text = .{ .text = "one" } }};
    const messages = [_]Message{.{ .user = .{ .content = &content, .timestamp_ms = 1 } }};
    const valid = Context{
        .system_prompt = "stable",
        .messages = &messages,
        .cacheable_message_count = 1,
    };
    try valid.validate();

    var invalid = valid;
    invalid.cacheable_message_count = 2;
    try std.testing.expectError(error.InvalidCacheableMessageCount, invalid.validate());
}

test "stream usage may precede a terminal message" {
    const event = StreamEvent{ .usage = .{ .input = 7, .output = 3, .total_tokens = 10 } };
    try std.testing.expectEqual(@as(u64, 10), event.usage.total_tokens);
}
