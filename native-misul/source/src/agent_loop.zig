const std = @import("std");
const protocol = @import("protocol.zig");
const session = @import("session.zig");
const compaction = @import("compaction.zig");
const cache_telemetry = @import("cache_telemetry.zig");

pub const ToolOutput = struct {
    content: []const protocol.UserContent,
    details: ?std.json.Value = null,
    is_error: bool = false,
    terminate: bool = false,
};

pub const ToolExecuteFn = *const fn (
    context: *anyopaque,
    call: *const protocol.ToolCall,
    allocator: std.mem.Allocator,
) anyerror!ToolOutput;

pub const ToolBinding = struct {
    context: *anyopaque,
    execute: ToolExecuteFn,
};

pub const ToolLookupFn = *const fn (context: *anyopaque, name: []const u8) ?ToolBinding;
pub const BeforeToolFn = *const fn (context: *anyopaque, call: *const protocol.ToolCall) anyerror!void;
pub const AfterToolFn = *const fn (context: *anyopaque, call: *const protocol.ToolCall, result: *const protocol.ToolResultMessage) anyerror!void;
pub const ParallelToolFn = *const fn (context: *anyopaque, name: []const u8) bool;

pub const Event = union(enum) {
    agent_start,
    step_start: usize,
    stream: protocol.StreamEvent,
    message_added: *const protocol.Message,
    tool_start: *const protocol.ToolCall,
    tool_end: *const protocol.ToolResultMessage,
    compacted: struct { tokens_before: usize, first_kept_index: usize },
    cancellation_requested,
    provider_error: []const u8,
    max_steps: usize,
    agent_end,
};

pub const EventSink = *const fn (context: *anyopaque, event: Event) anyerror!void;
pub const IsCancelledFn = *const fn (context: *anyopaque) bool;
pub const TelemetrySink = *const fn (context: *anyopaque, call: cache_telemetry.Call) void;
pub const TakeRequestIdentityFn = *const fn (context: *anyopaque) ?cache_telemetry.RequestIdentity;
pub const NowFn = *const fn (context: *anyopaque) i64;

pub const Stop = enum { complete, provider_error, terminated, max_steps, cancelled };

pub const Config = struct {
    allocator: std.mem.Allocator,
    model: *const protocol.Model,
    system_prompt: []const u8 = "",
    stable_prompt: []const u8 = "",
    system_prompt_lanes: []const []const u8 = &.{},
    tools: []const protocol.Tool = &.{},
    stream_options: protocol.StreamOptions = .{},
    stream_context: *anyopaque,
    stream: protocol.StreamFn,
    tool_context: ?*anyopaque = null,
    lookup_tool: ?ToolLookupFn = null,
    hook_context: ?*anyopaque = null,
    before_tool: ?BeforeToolFn = null,
    after_tool: ?AfterToolFn = null,
    /// Whether a tool may overlap other calls. Others run alone, after every earlier call of the
    /// same response settles. Null lets every call overlap.
    parallel_context: ?*anyopaque = null,
    parallel_tool: ?ParallelToolFn = null,
    event_context: ?*anyopaque = null,
    emit: ?EventSink = null,
    cancel_context: ?*anyopaque = null,
    is_cancelled: ?IsCancelledFn = null,
    telemetry_context: ?*anyopaque = null,
    telemetry: ?TelemetrySink = null,
    request_identity_context: ?*anyopaque = null,
    take_request_identity: ?TakeRequestIdentityFn = null,
    telemetry_epoch: u64 = 0,
    telemetry_invalidation: cache_telemetry.InvalidationReason = .none,
    now_context: ?*anyopaque = null,
    now: ?NowFn = null,
    max_steps: usize = 32,
    cacheable_message_count: usize = 0,
    compaction_settings: compaction.Settings = .{ .enabled = false },
};

pub const Result = struct {
    parent_allocator: std.mem.Allocator,
    arena: *std.heap.ArenaAllocator,
    messages: []protocol.Message,
    stop: Stop,
    steps: usize,

    pub fn deinit(self: *Result) void {
        self.arena.deinit();
        self.parent_allocator.destroy(self.arena);
        self.* = undefined;
    }
};

pub fn run(initial_messages: []const protocol.Message, config: Config) !Result {
    const arena = try config.allocator.create(std.heap.ArenaAllocator);
    errdefer config.allocator.destroy(arena);
    arena.* = std.heap.ArenaAllocator.init(config.allocator);
    errdefer arena.deinit();
    const allocator = arena.allocator();

    var messages: std.ArrayList(protocol.Message) = .empty;
    for (initial_messages) |message| try messages.append(allocator, try cloneMessage(allocator, message));
    try emit(config, .agent_start);
    for (messages.items) |*message| try emit(config, .{ .message_added = message });

    var stop: Stop = .complete;
    var steps: usize = 0;
    run_loop: while (steps < config.max_steps) {
        if (isCancelled(config)) {
            try emit(config, .cancellation_requested);
            stop = .cancelled;
            break;
        }
        steps += 1;
        try emit(config, .{ .step_start = steps });

        var scratch = std.heap.ArenaAllocator.init(config.allocator);
        defer scratch.deinit();
        const request_messages = try compactRequest(scratch.allocator(), messages.items, config);
        const request_context = protocol.Context{
            .system_prompt = config.system_prompt,
            .system_prompt_lanes = config.system_prompt_lanes,
            .messages = request_messages,
            .cacheable_message_count = config.cacheable_message_count,
            .tools = config.tools,
        };
        try request_context.validate();

        const request_started_ms = now(config);
        var capture = Capture{ .allocator = allocator, .config = config };
        config.stream(config.stream_context, config.model, &request_context, config.stream_options, &capture, captureSink) catch |err| {
            if (err == error.EventCallbackFailed) return err;
            if (err == error.Canceled or isCancelled(config)) {
                try emit(config, .cancellation_requested);
                stop = .cancelled;
                break :run_loop;
            }
            const error_text = try std.fmt.allocPrint(allocator, "provider stream failed: {s}", .{@errorName(err)});
            const failure = try failureMessage(allocator, config, error_text);
            try messages.append(allocator, failure);
            try emit(config, .{ .provider_error = error_text });
            try emit(config, .{ .message_added = &messages.items[messages.items.len - 1] });
            stop = .provider_error;
            break;
        };

        const assistant = capture.assistant orelse {
            const error_text = try allocator.dupe(u8, "provider stream ended without a final message");
            const failure = try failureMessage(allocator, config, error_text);
            try messages.append(allocator, failure);
            try emit(config, .{ .provider_error = error_text });
            try emit(config, .{ .message_added = &messages.items[messages.items.len - 1] });
            stop = .provider_error;
            break;
        };
        recordTelemetry(config, assistant, request_started_ms);
        try messages.append(allocator, .{ .assistant = assistant });
        try emit(config, .{ .message_added = &messages.items[messages.items.len - 1] });
        if (assistant.stop_reason == .@"error" or assistant.stop_reason == .aborted) {
            stop = .provider_error;
            break;
        }

        var call_count: usize = 0;
        for (assistant.content) |part| switch (part) {
            .tool_call => call_count += 1,
            else => {},
        };
        if (call_count == 0) break;

        // Approval hooks and events stay on this thread, in call order. Each overlapping call starts
        // as soon as it clears its hook, so independent reads run concurrently while a later call
        // waits for approval; mutations run alone and in order. Results are appended in call order
        // after every started call settles.
        const slots = try allocator.alloc(ToolSlot, call_count);
        var started: usize = 0;
        defer for (slots[0..started]) |*slot| slot.deinit();
        var cancelled = false;
        for (assistant.content) |*part| switch (part.*) {
            .tool_call => |*call| {
                if (isCancelled(config)) {
                    cancelled = true;
                    break;
                }
                slots[started] = .{ .call = call };
                started += 1;
                const overlap = call_count > 1 and isParallelTool(config, call.name);
                if (!overlap) for (slots[0 .. started - 1]) |*earlier| earlier.join();
                try startTool(allocator, config, &slots[started - 1], overlap);
            },
            else => {},
        };
        for (slots[0..started]) |*slot| slot.join();
        var all_terminate = true;
        for (slots[0..started]) |*slot| {
            const terminate = try finishTool(allocator, &messages, config, slot);
            all_terminate = all_terminate and terminate;
        }
        if (cancelled) {
            try emit(config, .cancellation_requested);
            stop = .cancelled;
            break;
        }
        if (all_terminate) {
            stop = .terminated;
            break;
        }
    } else {
        stop = .max_steps;
        try emit(config, .{ .max_steps = config.max_steps });
    }

    try emit(config, .agent_end);
    return .{
        .parent_allocator = config.allocator,
        .arena = arena,
        .messages = try messages.toOwnedSlice(allocator),
        .stop = stop,
        .steps = steps,
    };
}

fn isParallelTool(config: Config, name: []const u8) bool {
    const check = config.parallel_tool orelse return true;
    return check(config.parallel_context orelse config.stream_context, name);
}

fn isCancelled(config: Config) bool {
    return if (config.is_cancelled) |check|
        check(config.cancel_context orelse config.stream_context)
    else
        false;
}

const ToolSlot = struct {
    call: *const protocol.ToolCall,
    binding: ?ToolBinding = null,
    arena: ?std.heap.ArenaAllocator = null,
    output: ToolOutput = .{ .content = &.{} },
    failure: ?anyerror = null,
    blocked: ?[]const u8 = null,
    thread: ?std.Thread = null,

    fn run(slot: *ToolSlot) void {
        const tool = slot.binding.?;
        slot.output = tool.execute(tool.context, slot.call, slot.arena.?.allocator()) catch |err| {
            slot.failure = err;
            return;
        };
    }

    fn join(slot: *ToolSlot) void {
        if (slot.thread) |thread| thread.join();
        slot.thread = null;
    }

    fn deinit(slot: *ToolSlot) void {
        slot.join();
        if (slot.arena) |*arena| arena.deinit();
        slot.arena = null;
    }
};

fn startTool(allocator: std.mem.Allocator, config: Config, slot: *ToolSlot, concurrent: bool) !void {
    const call = slot.call;
    try emit(config, .{ .tool_start = call });
    if (config.before_tool) |hook| hook(config.hook_context orelse config.stream_context, call) catch |err| {
        slot.blocked = try std.fmt.allocPrint(allocator, "tool {s} was blocked: {s}", .{ call.name, describeToolError(err) });
        return;
    };
    const binding = if (config.lookup_tool) |lookup|
        lookup(config.tool_context orelse config.stream_context, call.name)
    else
        null;
    slot.binding = binding orelse {
        slot.blocked = try std.fmt.allocPrint(allocator, "Unknown tool: {s}", .{call.name});
        return;
    };
    // Tools allocate from a private arena so concurrent calls never share the loop's arena.
    slot.arena = std.heap.ArenaAllocator.init(config.allocator);
    if (concurrent) slot.thread = std.Thread.spawn(.{}, ToolSlot.run, .{slot}) catch null;
    if (slot.thread == null) slot.run();
}

fn finishTool(
    allocator: std.mem.Allocator,
    messages: *std.ArrayList(protocol.Message),
    config: Config,
    slot: *ToolSlot,
) !bool {
    const call = slot.call;
    const generated_error = slot.blocked orelse if (slot.failure) |err|
        try std.fmt.allocPrint(allocator, "tool {s} failed: {s}", .{ call.name, describeToolError(err) })
    else
        null;
    const output = slot.output;

    const content = if (generated_error) |text|
        try singleText(allocator, text)
    else
        try cloneUserContentSlice(allocator, output.content);
    const result = protocol.ToolResultMessage{
        .tool_call_id = try allocator.dupe(u8, call.id),
        .tool_name = try allocator.dupe(u8, call.name),
        .content = content,
        .details = if (generated_error == null and output.details != null) try cloneJson(allocator, output.details.?) else null,
        .is_error = generated_error != null or output.is_error,
        .timestamp_ms = now(config),
    };
    try messages.append(allocator, .{ .tool_result = result });
    const stored = &messages.items[messages.items.len - 1].tool_result;
    if (config.after_tool) |hook| try hook(config.hook_context orelse config.stream_context, call, stored);
    try emit(config, .{ .tool_end = stored });
    try emit(config, .{ .message_added = &messages.items[messages.items.len - 1] });
    return generated_error == null and output.terminate;
}

/// Error names alone do not tell a model how to recover; add the fix for the common ones.
fn describeToolError(err: anyerror) []const u8 {
    return switch (err) {
        error.InvalidPath => "InvalidPath: use a path inside the workspace, relative to its root, without ..",
        error.FileNotFound => "FileNotFound: no such file or directory in the workspace",
        error.NotAFile, error.IsDir => "NotAFile: the path is a directory",
        error.NotDir => "NotDir: a path component is not a directory",
        error.ExpectedOneMatch => "ExpectedOneMatch: old_string must match exactly one location; read the file and include more surrounding text",
        error.EmptyOldString => "EmptyOldString: old_string must not be empty; use write_file to create files",
        error.SymlinkNotAllowed => "SymlinkNotAllowed: symbolic links are not followed",
        error.InputTooLarge => "InputTooLarge: the file or content exceeds the tool limit",
        error.MissingField, error.UnknownField, error.UnexpectedToken, error.InvalidArguments => "InvalidArguments: the arguments do not match this tool's parameter schema",
        error.ToolApprovalDenied => "ToolApprovalDenied: the user denied this call; do not retry it without asking",
        error.Canceled => "Canceled: the call was stopped",
        else => @errorName(err),
    };
}

const Capture = struct {
    allocator: std.mem.Allocator,
    config: Config,
    assistant: ?protocol.AssistantMessage = null,
    usage: ?protocol.Usage = null,
};

fn captureSink(context: *anyopaque, event: protocol.StreamEvent) !void {
    const capture: *Capture = @ptrCast(@alignCast(context));
    switch (event) {
        .done, .failed => |message| if (message.provider_failure) |failure| try failure.validate(),
        else => {},
    }
    try emit(capture.config, .{ .stream = event });
    switch (event) {
        .usage => |usage| capture.usage = usage,
        .done, .failed => |message| {
            var assistant = try cloneAssistant(capture.allocator, message);
            if (usageIsZero(assistant.usage)) {
                if (capture.usage) |usage| assistant.usage = usage;
            }
            capture.assistant = assistant;
        },
        else => {},
    }
}

fn usageIsZero(usage: protocol.Usage) bool {
    return usage.input == 0 and
        usage.output == 0 and
        usage.cache_read == 0 and
        usage.cache_write == 0 and
        usage.total_tokens == 0 and
        usage.cost.input == 0 and
        usage.cost.output == 0 and
        usage.cost.cache_read == 0 and
        usage.cost.cache_write == 0 and
        usage.cost.total == 0;
}

fn compactRequest(allocator: std.mem.Allocator, messages: []const protocol.Message, config: Config) ![]const protocol.Message {
    if (messages.len == 0 or !config.compaction_settings.enabled) return messages;
    const transcript = try allocator.alloc(session.Message, messages.len);
    for (messages, 0..) |message, index| transcript[index] = .{
        .role = sessionRole(message),
        .content = try flattenMessage(allocator, message),
    };
    const tokens = compaction.estimateContextTokens(transcript);
    const window: usize = @intCast(@min(config.model.context_window, std.math.maxInt(usize)));
    if (!compaction.shouldCompact(tokens, window, config.compaction_settings)) return messages;

    return error.ProviderSummaryRequired;
}

fn sessionRole(message: protocol.Message) session.Role {
    return switch (message) {
        .user => .user,
        .assistant => .assistant,
        .tool_result => .tool_result,
    };
}

fn flattenMessage(allocator: std.mem.Allocator, message: protocol.Message) ![]u8 {
    var output: std.ArrayList(u8) = .empty;
    switch (message) {
        .user => |item| for (item.content) |part| switch (part) {
            .text => |text| try output.appendSlice(allocator, text.text),
            .image => |image| try output.print(allocator, "[image:{s}:{d}]", .{ image.mime_type, image.data.len }),
        },
        .assistant => |item| for (item.content) |part| switch (part) {
            .text => |text| try output.appendSlice(allocator, text.text),
            .thinking => |thinking| try output.appendSlice(allocator, thinking.thinking),
            .tool_call => |call| try output.print(allocator, "[tool:{s}]", .{call.name}),
        },
        .tool_result => |item| for (item.content) |part| switch (part) {
            .text => |text| try output.appendSlice(allocator, text.text),
            .image => |image| try output.print(allocator, "[image:{s}:{d}]", .{ image.mime_type, image.data.len }),
        },
    }
    return output.toOwnedSlice(allocator);
}

fn failureMessage(allocator: std.mem.Allocator, config: Config, error_text: []const u8) !protocol.Message {
    const content = try allocator.alloc(protocol.AssistantContent, 1);
    content[0] = .{ .text = .{ .text = try allocator.dupe(u8, "") } };
    return .{ .assistant = .{
        .content = content,
        .api = try allocator.dupe(u8, config.model.api),
        .provider = try allocator.dupe(u8, config.model.provider),
        .model = try allocator.dupe(u8, config.model.id),
        .stop_reason = .@"error",
        .error_message = error_text,
        .timestamp_ms = now(config),
    } };
}

fn recordTelemetry(config: Config, assistant: protocol.AssistantMessage, started_ms: i64) void {
    const sink = config.telemetry orelse return;
    const take_identity = config.take_request_identity orelse return;
    const identity = take_identity(config.request_identity_context orelse config.stream_context) orelse return;
    const finished_ms = now(config);
    sink(config.telemetry_context orelse config.stream_context, .{
        .session = config.stream_options.session_id orelse "",
        .model = config.model.id,
        .provider = config.model.provider,
        .input = assistant.usage.input,
        .cache_read = assistant.usage.cache_read,
        .cache_write = assistant.usage.cache_write,
        .output = assistant.usage.output,
        .request_bytes = identity.request_bytes,
        .request_hash = identity.request_hash,
        .prefix_bytes = identity.prefix_bytes,
        .prefix_hash = identity.prefix_hash,
        .epoch = config.telemetry_epoch,
        .latency_ms = if (finished_ms > started_ms) @intCast(finished_ms - started_ms) else 0,
        .invalidation_reason = config.telemetry_invalidation,
    });
}

fn now(config: Config) i64 {
    return if (config.now) |clock| clock(config.now_context orelse config.stream_context) else 0;
}

fn emit(config: Config, event: Event) !void {
    if (config.emit) |sink| try sink(config.event_context orelse config.stream_context, event);
}

fn cloneMessage(allocator: std.mem.Allocator, message: protocol.Message) !protocol.Message {
    return switch (message) {
        .user => |item| .{ .user = .{ .content = try cloneUserContentSlice(allocator, item.content), .timestamp_ms = item.timestamp_ms } },
        .assistant => |item| .{ .assistant = try cloneAssistant(allocator, item) },
        .tool_result => |item| .{ .tool_result = .{
            .tool_call_id = try allocator.dupe(u8, item.tool_call_id),
            .tool_name = try allocator.dupe(u8, item.tool_name),
            .content = try cloneUserContentSlice(allocator, item.content),
            .details = if (item.details) |value| try cloneJson(allocator, value) else null,
            .is_error = item.is_error,
            .timestamp_ms = item.timestamp_ms,
        } },
    };
}

fn cloneAssistant(allocator: std.mem.Allocator, item: protocol.AssistantMessage) !protocol.AssistantMessage {
    const content = try allocator.alloc(protocol.AssistantContent, item.content.len);
    for (item.content, 0..) |part, index| content[index] = switch (part) {
        .text => |text| .{ .text = .{ .text = try allocator.dupe(u8, text.text) } },
        .thinking => |thinking| .{ .thinking = .{
            .thinking = try allocator.dupe(u8, thinking.thinking),
            .signature = if (thinking.signature) |signature| try allocator.dupe(u8, signature) else null,
        } },
        .tool_call => |call| .{ .tool_call = .{
            .id = try allocator.dupe(u8, call.id),
            .name = try allocator.dupe(u8, call.name),
            .arguments = try cloneJson(allocator, call.arguments),
        } },
    };
    return .{
        .content = content,
        .api = try allocator.dupe(u8, item.api),
        .provider = try allocator.dupe(u8, item.provider),
        .model = try allocator.dupe(u8, item.model),
        .stop_reason = item.stop_reason,
        .error_message = if (item.error_message) |message| try allocator.dupe(u8, message) else null,
        .provider_failure = if (item.provider_failure) |failure| try cloneProviderFailure(allocator, failure) else null,
        .timestamp_ms = item.timestamp_ms,
        .usage = item.usage,
    };
}

fn cloneProviderFailure(allocator: std.mem.Allocator, failure: protocol.ProviderFailure) !protocol.ProviderFailure {
    try failure.validate();
    return .{
        .category = failure.category,
        .http_status = failure.http_status,
        .request_id = if (failure.request_id) |value| try allocator.dupe(u8, value) else null,
        .provider_code = if (failure.provider_code) |value| try allocator.dupe(u8, value) else null,
        .provider_type = if (failure.provider_type) |value| try allocator.dupe(u8, value) else null,
        .provider_param = if (failure.provider_param) |value| try allocator.dupe(u8, value) else null,
        .redacted_message = try allocator.dupe(u8, failure.redacted_message),
        .retry_after_ms = failure.retry_after_ms,
    };
}

fn cloneUserContentSlice(allocator: std.mem.Allocator, input: []const protocol.UserContent) ![]const protocol.UserContent {
    const content = try allocator.alloc(protocol.UserContent, input.len);
    for (input, 0..) |part, index| content[index] = switch (part) {
        .text => |text| .{ .text = .{ .text = try allocator.dupe(u8, text.text) } },
        .image => |image| .{ .image = .{
            .data = try allocator.dupe(u8, image.data),
            .mime_type = try allocator.dupe(u8, image.mime_type),
        } },
    };
    return content;
}

fn singleText(allocator: std.mem.Allocator, text: []const u8) ![]const protocol.UserContent {
    const content = try allocator.alloc(protocol.UserContent, 1);
    content[0] = .{ .text = .{ .text = try allocator.dupe(u8, text) } };
    return content;
}

fn cloneJson(allocator: std.mem.Allocator, value: std.json.Value) !std.json.Value {
    const encoded = try std.json.Stringify.valueAlloc(allocator, value, .{});
    return std.json.parseFromSliceLeaky(std.json.Value, allocator, encoded, .{});
}

const test_model = protocol.Model{ .id = "fake", .api = "test", .provider = "fake", .context_window = 1024, .max_tokens = 128 };

fn assistantText(content: []const protocol.AssistantContent, reason: protocol.StopReason) protocol.AssistantMessage {
    return .{
        .content = content,
        .api = "test",
        .provider = "fake",
        .model = "fake",
        .stop_reason = reason,
        .timestamp_ms = 1,
    };
}

fn normalStream(_: *anyopaque, _: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    const content = [_]protocol.AssistantContent{.{ .text = .{ .text = "done" } }};
    try sink(sink_context, .{ .done = assistantText(&content, .stop) });
}

fn usageThenDoneStream(_: *anyopaque, _: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    try sink(sink_context, .{ .usage = .{ .input = 7, .output = 3, .cache_read = 2, .total_tokens = 10 } });
    const content = [_]protocol.AssistantContent{.{ .text = .{ .text = "accounted" } }};
    try sink(sink_context, .{ .done = assistantText(&content, .stop) });
}

fn borrowedFailureStream(_: *anyopaque, _: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    var request_id = [_]u8{ 'r', 'e', 'q', '-', '1' };
    var redacted_message = [_]u8{ 'r', 'a', 't', 'e', ' ', 'l', 'i', 'm', 'i', 't' };
    try sink(sink_context, .{ .failed = .{
        .content = &.{},
        .api = "test",
        .provider = "fake",
        .model = "fake",
        .stop_reason = .@"error",
        .provider_failure = .{
            .category = .rate_limited,
            .http_status = 429,
            .request_id = &request_id,
            .redacted_message = &redacted_message,
            .retry_after_ms = 250,
        },
        .timestamp_ms = 1,
    } });
    @memset(&request_id, 'x');
    @memset(&redacted_message, 'x');
}

fn usageThenAccountedDoneStream(_: *anyopaque, _: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    try sink(sink_context, .{ .usage = .{ .input = 7, .output = 3, .total_tokens = 10 } });
    const content = [_]protocol.AssistantContent{.{ .text = .{ .text = "terminal wins" } }};
    var message = assistantText(&content, .stop);
    message.usage = .{ .input = 2, .output = 1, .total_tokens = 3 };
    try sink(sink_context, .{ .done = message });
}

const MalformedFailureProbe = struct {
    failed_stream_events: usize = 0,
};

fn observeMalformedFailure(raw: *anyopaque, event: Event) !void {
    const probe: *MalformedFailureProbe = @ptrCast(@alignCast(raw));
    if (event == .stream and event.stream == .failed) probe.failed_stream_events += 1;
}

fn malformedFailureStream(_: *anyopaque, _: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    var oversized: [protocol.max_provider_failure_message_bytes + 1]u8 = @splat('x');
    try sink(sink_context, .{ .failed = .{
        .content = &.{},
        .api = "test",
        .provider = "fake",
        .model = "fake",
        .stop_reason = .@"error",
        .provider_failure = .{ .category = .provider, .redacted_message = &oversized },
        .timestamp_ms = 1,
    } });
}

test "normal response is accumulated as owned data" {
    var context: u8 = 0;
    var result = try run(&.{}, .{ .allocator = std.testing.allocator, .model = &test_model, .stream_context = &context, .stream = normalStream });
    defer result.deinit();
    try std.testing.expectEqual(Stop.complete, result.stop);
    try std.testing.expectEqual(@as(usize, 1), result.messages.len);
    try std.testing.expectEqualStrings("done", result.messages[0].assistant.content[0].text.text);
}

test "usage emitted before done is retained exactly once on the assistant" {
    var context: u8 = 0;
    var result = try run(&.{}, .{ .allocator = std.testing.allocator, .model = &test_model, .stream_context = &context, .stream = usageThenDoneStream });
    defer result.deinit();
    const usage = result.messages[0].assistant.usage;
    try std.testing.expectEqual(@as(u64, 7), usage.input);
    try std.testing.expectEqual(@as(u64, 3), usage.output);
    try std.testing.expectEqual(@as(u64, 2), usage.cache_read);
    try std.testing.expectEqual(@as(u64, 10), usage.total_tokens);
}

test "provider failure diagnostics outlive callback storage" {
    var context: u8 = 0;
    var result = try run(&.{}, .{ .allocator = std.testing.allocator, .model = &test_model, .stream_context = &context, .stream = borrowedFailureStream });
    defer result.deinit();
    try std.testing.expectEqual(Stop.provider_error, result.stop);
    const failure = result.messages[0].assistant.provider_failure.?;
    try std.testing.expectEqual(protocol.ProviderFailureCategory.rate_limited, failure.category);
    try std.testing.expectEqual(@as(?u16, 429), failure.http_status);
    try std.testing.expectEqualStrings("req-1", failure.request_id.?);
    try std.testing.expectEqualStrings("rate limit", failure.redacted_message);
    try std.testing.expectEqual(@as(?u64, 250), failure.retry_after_ms);
}

test "terminal usage is not added to a preceding usage snapshot" {
    var context: u8 = 0;
    var result = try run(&.{}, .{ .allocator = std.testing.allocator, .model = &test_model, .stream_context = &context, .stream = usageThenAccountedDoneStream });
    defer result.deinit();
    const usage = result.messages[0].assistant.usage;
    try std.testing.expectEqual(@as(u64, 2), usage.input);
    try std.testing.expectEqual(@as(u64, 1), usage.output);
    try std.testing.expectEqual(@as(u64, 3), usage.total_tokens);
}

test "malformed provider failure is rejected before event delivery" {
    var stream_context: u8 = 0;
    var probe = MalformedFailureProbe{};
    var result = try run(&.{}, .{
        .allocator = std.testing.allocator,
        .model = &test_model,
        .stream_context = &stream_context,
        .stream = malformedFailureStream,
        .event_context = &probe,
        .emit = observeMalformedFailure,
    });
    defer result.deinit();
    try std.testing.expectEqual(@as(usize, 0), probe.failed_stream_events);
    try std.testing.expectEqual(Stop.provider_error, result.stop);
    try std.testing.expect(result.messages[0].assistant.provider_failure == null);
}

const TelemetryTest = struct { calls: usize = 0, last: ?cache_telemetry.Call = null };

const RequestIdentityTest = struct {
    calls: usize = 0,
    identity: cache_telemetry.RequestIdentity,
};

fn takeRequestIdentity(raw: *anyopaque) ?cache_telemetry.RequestIdentity {
    const state: *RequestIdentityTest = @ptrCast(@alignCast(raw));
    state.calls += 1;
    return state.identity;
}

fn captureTelemetry(raw: *anyopaque, call: cache_telemetry.Call) void {
    const state: *TelemetryTest = @ptrCast(@alignCast(raw));
    state.calls += 1;
    state.last = call;
}

test "provider calls emit cache telemetry from the exact transmitted request identity" {
    var stream_context: u8 = 0;
    var telemetry = TelemetryTest{};
    const request_hash = [_]u8{0x42} ** cache_telemetry.hash_length;
    const prefix_hash = [_]u8{0x24} ** cache_telemetry.hash_length;
    var identity = RequestIdentityTest{ .identity = .{
        .request_bytes = 321,
        .request_hash = request_hash,
        .prefix_bytes = 123,
        .prefix_hash = prefix_hash,
    } };
    var result = try run(&.{}, .{
        .allocator = std.testing.allocator,
        .model = &test_model,
        .stable_prompt = "stable-only",
        .system_prompt = "stable-only dynamic",
        .stream_context = &stream_context,
        .stream = normalStream,
        .stream_options = .{ .session_id = "session" },
        .request_identity_context = &identity,
        .take_request_identity = takeRequestIdentity,
        .telemetry_context = &telemetry,
        .telemetry = captureTelemetry,
    });
    defer result.deinit();
    try std.testing.expectEqual(@as(usize, 1), telemetry.calls);
    try std.testing.expectEqual(@as(usize, 1), identity.calls);
    try std.testing.expectEqualStrings("session", telemetry.last.?.session);
    try std.testing.expectEqual(@as(u64, 321), telemetry.last.?.request_bytes);
    try std.testing.expectEqual(request_hash, telemetry.last.?.request_hash);
    try std.testing.expectEqual(@as(u64, 123), telemetry.last.?.prefix_bytes);
    try std.testing.expectEqual(prefix_hash, telemetry.last.?.prefix_hash);
}

test "cache telemetry does not invent a surrogate request identity" {
    var stream_context: u8 = 0;
    var telemetry = TelemetryTest{};
    var result = try run(&.{}, .{
        .allocator = std.testing.allocator,
        .model = &test_model,
        .stable_prompt = "not-the-wire-request",
        .stream_context = &stream_context,
        .stream = normalStream,
        .telemetry_context = &telemetry,
        .telemetry = captureTelemetry,
    });
    defer result.deinit();
    try std.testing.expectEqual(@as(usize, 0), telemetry.calls);
}

fn failingStream(_: *anyopaque, _: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, _: *anyopaque, _: protocol.StreamSink) !void {
    return error.TransportFailure;
}

test "provider errors become graceful assistant failures" {
    var context: u8 = 0;
    var result = try run(&.{}, .{ .allocator = std.testing.allocator, .model = &test_model, .stream_context = &context, .stream = failingStream });
    defer result.deinit();
    try std.testing.expectEqual(Stop.provider_error, result.stop);
    try std.testing.expectEqual(protocol.StopReason.@"error", result.messages[0].assistant.stop_reason);
    try std.testing.expectEqualStrings("provider stream failed: TransportFailure", result.messages[0].assistant.error_message.?);
}

const ToolTest = struct { requests: usize = 0, executions: usize = 0 };

fn toolStream(raw: *anyopaque, _: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    const state: *ToolTest = @ptrCast(@alignCast(raw));
    state.requests += 1;
    if (state.requests == 1) {
        const call = protocol.ToolCall{ .id = "call-1", .name = "echo", .arguments = .{ .string = "hi" } };
        const message = protocol.AssistantMessage{
            .content = &.{.{ .tool_call = call }},
            .api = "test",
            .provider = "fake",
            .model = "fake",
            .stop_reason = .tool_use,
            .timestamp_ms = 1,
        };
        try sink(sink_context, .{ .done = message });
    } else {
        const content = [_]protocol.AssistantContent{.{ .text = .{ .text = "after tool" } }};
        try sink(sink_context, .{ .done = assistantText(&content, .stop) });
    }
}

fn lookupEcho(raw: *anyopaque, name: []const u8) ?ToolBinding {
    if (!std.mem.eql(u8, name, "echo")) return null;
    return .{ .context = raw, .execute = executeEcho };
}

fn executeEcho(raw: *anyopaque, _: *const protocol.ToolCall, _: std.mem.Allocator) !ToolOutput {
    const state: *ToolTest = @ptrCast(@alignCast(raw));
    state.executions += 1;
    return .{ .content = &.{.{ .text = .{ .text = "echoed" } }} };
}

test "tool calls execute sequentially and continue the provider loop" {
    var state = ToolTest{};
    var result = try run(&.{}, .{
        .allocator = std.testing.allocator,
        .model = &test_model,
        .stream_context = &state,
        .stream = toolStream,
        .tool_context = &state,
        .lookup_tool = lookupEcho,
    });
    defer result.deinit();
    try std.testing.expectEqual(@as(usize, 2), state.requests);
    try std.testing.expectEqual(@as(usize, 1), state.executions);
    try std.testing.expectEqual(@as(usize, 3), result.messages.len);
    try std.testing.expectEqualStrings("echoed", result.messages[1].tool_result.content[0].text.text);
}

test "unknown tools become error results and continue" {
    var state = ToolTest{};
    var result = try run(&.{}, .{ .allocator = std.testing.allocator, .model = &test_model, .stream_context = &state, .stream = toolStream });
    defer result.deinit();
    try std.testing.expect(result.messages[1].tool_result.is_error);
    try std.testing.expectEqualStrings("Unknown tool: echo", result.messages[1].tool_result.content[0].text.text);
    try std.testing.expectEqual(@as(usize, 2), state.requests);
}

test "tool continuation is bounded by max steps" {
    var state = ToolTest{};
    var result = try run(&.{}, .{
        .allocator = std.testing.allocator,
        .model = &test_model,
        .stream_context = &state,
        .stream = toolStream,
        .tool_context = &state,
        .lookup_tool = lookupEcho,
        .max_steps = 1,
    });
    defer result.deinit();
    try std.testing.expectEqual(Stop.max_steps, result.stop);
    try std.testing.expectEqual(@as(usize, 1), result.steps);
}

const CompactTest = struct { saw_summary: bool = false };
fn compactStream(raw: *anyopaque, _: *const protocol.Model, context: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    const state: *CompactTest = @ptrCast(@alignCast(raw));
    state.saw_summary = context.messages.len > 0 and context.messages[0] == .user and
        std.mem.startsWith(u8, context.messages[0].user.content[0].text.text, "[compaction_summary]");
    const content = [_]protocol.AssistantContent{.{ .text = .{ .text = "compact" } }};
    try sink(sink_context, .{ .done = assistantText(&content, .stop) });
}

test "compaction threshold fails closed without a provider summary" {
    const tiny_model = protocol.Model{ .id = "fake", .api = "test", .provider = "fake", .context_window = 8, .max_tokens = 4 };
    const initial = protocol.Message{ .user = .{ .content = &.{.{ .text = .{ .text = "old old old old old old old old" } }}, .timestamp_ms = 1 } };
    var state = CompactTest{};
    try std.testing.expectError(error.ProviderSummaryRequired, run(&.{initial}, .{
        .allocator = std.testing.allocator,
        .model = &tiny_model,
        .stream_context = &state,
        .stream = compactStream,
        .compaction_settings = .{ .reserve_tokens = 2, .keep_recent_tokens = 1 },
    }));
    try std.testing.expect(!state.saw_summary);
}

const DispatchProbe = struct { calls: usize = 0 };

fn countDispatch(raw: *anyopaque, _: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, _: *anyopaque, _: protocol.StreamSink) !void {
    const probe: *DispatchProbe = @ptrCast(@alignCast(raw));
    probe.calls += 1;
}

test "compaction cannot dispatch a stale cacheable message count" {
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "old old old old old old old old" } }};
    const initial = [_]protocol.Message{
        .{ .user = .{ .content = &content, .timestamp_ms = 1 } },
        .{ .user = .{ .content = &content, .timestamp_ms = 2 } },
        .{ .user = .{ .content = &content, .timestamp_ms = 3 } },
        .{ .user = .{ .content = &content, .timestamp_ms = 4 } },
    };
    const tiny_model = protocol.Model{ .id = "fake", .api = "test", .provider = "fake", .context_window = 8, .max_tokens = 4 };
    var probe = DispatchProbe{};
    try std.testing.expectError(error.ProviderSummaryRequired, run(&initial, .{
        .allocator = std.testing.allocator,
        .model = &tiny_model,
        .stream_context = &probe,
        .stream = countDispatch,
        .cacheable_message_count = initial.len,
        .compaction_settings = .{ .reserve_tokens = 2, .keep_recent_tokens = 1 },
    }));
    try std.testing.expectEqual(@as(usize, 0), probe.calls);
}

const BarrierTest = struct {
    requests: usize = 0,
    arrived: std.atomic.Value(usize) = .init(0),
};

fn barrierStream(raw: *anyopaque, _: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    const state: *BarrierTest = @ptrCast(@alignCast(raw));
    state.requests += 1;
    if (state.requests == 1) {
        const message = protocol.AssistantMessage{
            .content = &.{
                .{ .tool_call = .{ .id = "first", .name = "meet", .arguments = .null } },
                .{ .tool_call = .{ .id = "second", .name = "meet", .arguments = .null } },
            },
            .api = "test",
            .provider = "fake",
            .model = "fake",
            .stop_reason = .tool_use,
            .timestamp_ms = 1,
        };
        try sink(sink_context, .{ .done = message });
    } else {
        const content = [_]protocol.AssistantContent{.{ .text = .{ .text = "met" } }};
        try sink(sink_context, .{ .done = assistantText(&content, .stop) });
    }
}

fn lookupMeet(raw: *anyopaque, _: []const u8) ?ToolBinding {
    return .{ .context = raw, .execute = executeMeet };
}

/// Returns only after both calls are running at once; serial dispatch would time out.
fn executeMeet(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !ToolOutput {
    const state: *BarrierTest = @ptrCast(@alignCast(raw));
    _ = state.arrived.fetchAdd(1, .acq_rel);
    var spins: usize = 0;
    while (state.arrived.load(.acquire) < 2) : (spins += 1) {
        if (spins > 2_000_000) return error.NotConcurrent;
        std.Thread.yield() catch {};
    }
    const content = try allocator.alloc(protocol.UserContent, 1);
    content[0] = .{ .text = .{ .text = try allocator.dupe(u8, call.id) } };
    return .{ .content = content };
}

test "independent tool calls run concurrently and settle in call order" {
    var state = BarrierTest{};
    var result = try run(&.{}, .{
        .allocator = std.testing.allocator,
        .model = &test_model,
        .stream_context = &state,
        .stream = barrierStream,
        .tool_context = &state,
        .lookup_tool = lookupMeet,
    });
    defer result.deinit();
    try std.testing.expectEqual(@as(usize, 4), result.messages.len);
    try std.testing.expect(!result.messages[1].tool_result.is_error);
    try std.testing.expectEqualStrings("first", result.messages[1].tool_result.content[0].text.text);
    try std.testing.expectEqualStrings("second", result.messages[2].tool_result.content[0].text.text);
}

fn noOverlap(_: *anyopaque, _: []const u8) bool {
    return false;
}

fn lookupRecord(raw: *anyopaque, _: []const u8) ?ToolBinding {
    return .{ .context = raw, .execute = executeRecord };
}

/// The first call is slow; it still finishes first when calls may not overlap.
fn executeRecord(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !ToolOutput {
    const state: *BarrierTest = @ptrCast(@alignCast(raw));
    if (std.mem.eql(u8, call.id, "first")) {
        var spins: usize = 0;
        while (spins < 200_000) : (spins += 1) std.Thread.yield() catch {};
    }
    const position = state.arrived.fetchAdd(1, .acq_rel);
    const content = try allocator.alloc(protocol.UserContent, 1);
    content[0] = .{ .text = .{ .text = try std.fmt.allocPrint(allocator, "{d}", .{position}) } };
    return .{ .content = content };
}

test "tools that may not overlap run alone and in call order" {
    var state = BarrierTest{};
    var result = try run(&.{}, .{
        .allocator = std.testing.allocator,
        .model = &test_model,
        .stream_context = &state,
        .stream = barrierStream,
        .tool_context = &state,
        .lookup_tool = lookupRecord,
        .parallel_tool = noOverlap,
    });
    defer result.deinit();
    try std.testing.expectEqualStrings("0", result.messages[1].tool_result.content[0].text.text);
    try std.testing.expectEqualStrings("1", result.messages[2].tool_result.content[0].text.text);
}
