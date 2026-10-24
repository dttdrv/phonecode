const std = @import("std");
const misul_agent = @import("misul_agent");
const event_store = misul_agent.event_store;
const model_catalog = misul_agent.model_catalog;
const protocol = misul_agent.protocol;
const runtime_module = misul_agent.runtime;

const StreamState = struct {
    calls: usize = 0,
};

const ConcurrentStreamState = struct {
    io: std.Io,
    entered: std.atomic.Value(usize) = .init(0),
};

const SequenceCapture = struct {
    sequences: std.ArrayList(u64) = .empty,

    fn deinit(self: *SequenceCapture, allocator: std.mem.Allocator) void {
        self.sequences.deinit(allocator);
    }
};

fn echoStream(
    raw: *anyopaque,
    model: *const protocol.Model,
    context: *const protocol.Context,
    _: protocol.StreamOptions,
    sink_context: *anyopaque,
    sink: protocol.StreamSink,
) !void {
    const state: *StreamState = @ptrCast(@alignCast(raw));
    state.calls += 1;
    const prompt = context.messages[context.messages.len - 1].user.content[0].text.text;
    const content = [_]protocol.AssistantContent{.{ .text = .{ .text = prompt } }};
    try sink(sink_context, .{ .done = .{
        .content = &content,
        .api = model.api,
        .provider = model.provider,
        .model = model.id,
        .stop_reason = .stop,
        .timestamp_ms = 2,
    } });
}

fn concurrentEchoStream(
    raw: *anyopaque,
    model: *const protocol.Model,
    context: *const protocol.Context,
    _: protocol.StreamOptions,
    sink_context: *anyopaque,
    sink: protocol.StreamSink,
) !void {
    const state: *ConcurrentStreamState = @ptrCast(@alignCast(raw));
    _ = state.entered.fetchAdd(1, .acq_rel);
    var attempts: usize = 0;
    while (state.entered.load(.acquire) != 2 and attempts < 10_000) : (attempts += 1) {
        try std.Io.sleep(state.io, .fromMilliseconds(1), .awake);
    }
    if (state.entered.load(.acquire) != 2) return error.ConcurrentStreamBarrierTimeout;
    const prompt = context.messages[context.messages.len - 1].user.content[0].text.text;
    const content = [_]protocol.AssistantContent{.{ .text = .{ .text = prompt } }};
    try sink(sink_context, .{ .done = .{
        .content = &content,
        .api = model.api,
        .provider = model.provider,
        .model = model.id,
        .stop_reason = .stop,
        .timestamp_ms = 2,
    } });
}

fn captureSequence(raw: *anyopaque, record: event_store.RecordView) !void {
    const capture: *SequenceCapture = @ptrCast(@alignCast(raw));
    try capture.sequences.append(std.testing.allocator, record.global_sequence);
}

test "runtime restarts from one durable log with exact typed messages" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    const root = root_buffer[0..root_len];
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    const profiles = [_]model_catalog.ModelProfile{.{
        .id = "test-model",
        .name = "Test model",
        .provider = "test-provider",
        .context_window = 4096,
        .output_limit = 256,
        .max_output_tokens = 256,
    }};
    const options = runtime_module.Options{
        .workspace_root = root,
        .state_root = root,
        .system_prompt = "stable",
        .default_selection = .{
            .provider = "test-provider",
            .model = "test-model",
            .context_window = 4096,
            .output_limit = 256,
        },
        .model_profiles = &profiles,
    };

    var first_stream = StreamState{};
    const first = try runtime_module.Runtime.createForTest(
        allocator,
        std.testing.io,
        &environment,
        options,
        &first_stream,
        echoStream,
    );
    const settlement = try first.wait(try first.startPrompt(.{ .session_id = "session-a", .prompt = "persist me" }));
    try std.testing.expectEqual(runtime_module.StopReason.complete, settlement.stop_reason);
    try std.testing.expect(first.highWater() >= 6);
    first.destroy();

    var second_stream = StreamState{};
    const second = try runtime_module.Runtime.createForTest(
        allocator,
        std.testing.io,
        &environment,
        options,
        &second_stream,
        echoStream,
    );
    defer second.destroy();
    var messages = try second.sessionMessages("session-a", allocator);
    defer messages.deinit();
    try std.testing.expectEqual(@as(usize, 2), messages.messages.len);
    try std.testing.expectEqualStrings("persist me", messages.messages[0].user.content[0].text.text);
    try std.testing.expectEqualStrings("persist me", messages.messages[1].assistant.content[0].text.text);
    try std.testing.expectEqual(@as(usize, 0), second_stream.calls);
}

test "bounded publication admission fails before durable session mutation" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    const root = root_buffer[0..root_len];
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    const profiles = [_]model_catalog.ModelProfile{.{
        .id = "test-model",
        .name = "Test model",
        .provider = "test-provider",
        .context_window = 4096,
        .output_limit = 256,
        .max_output_tokens = 256,
    }};
    var stream = StreamState{};
    const runtime = try runtime_module.Runtime.createForTest(
        allocator,
        std.testing.io,
        &environment,
        .{
            .workspace_root = root,
            .state_root = root,
            .system_prompt = "stable",
            .default_selection = .{
                .provider = "test-provider",
                .model = "test-model",
                .context_window = 4096,
                .output_limit = 256,
            },
            .model_profiles = &profiles,
            .publication_capacity = 3,
        },
        &stream,
        echoStream,
    );
    defer runtime.destroy();

    const before = runtime.highWater();
    try std.testing.expectError(error.PublicationQueueFull, runtime.startPrompt(.{
        .session_id = "must-not-exist",
        .prompt = "do not commit",
    }));
    try std.testing.expectEqual(before, runtime.highWater());
    try std.testing.expectError(error.SessionNotFound, runtime.sessionMessages("must-not-exist", allocator));
}

test "selection admission fails before durable session mutation" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    const root = root_buffer[0..root_len];
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    const profiles = [_]model_catalog.ModelProfile{.{
        .id = "test-model",
        .name = "Test model",
        .provider = "test-provider",
        .context_window = 4096,
        .output_limit = 256,
        .max_output_tokens = 256,
    }};
    var stream = StreamState{};
    const runtime = try runtime_module.Runtime.createForTest(
        allocator,
        std.testing.io,
        &environment,
        .{
            .workspace_root = root,
            .state_root = root,
            .system_prompt = "stable",
            .default_selection = .{
                .provider = "test-provider",
                .model = "test-model",
                .context_window = 4096,
                .output_limit = 256,
            },
            .model_profiles = &profiles,
            .publication_capacity = 1,
        },
        &stream,
        echoStream,
    );
    defer runtime.destroy();

    const before = runtime.highWater();
    try std.testing.expectError(error.PublicationQueueFull, runtime.setSelection("must-not-exist", .{
        .provider = "test-provider",
        .model = "test-model",
        .context_window = 4096,
        .output_limit = 256,
    }));
    try std.testing.expectEqual(before, runtime.highWater());
    try std.testing.expectError(error.SessionNotFound, runtime.selection("must-not-exist"));
}

test "concurrent sessions publish one contiguous durable sequence" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    const root = root_buffer[0..root_len];
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    const profiles = [_]model_catalog.ModelProfile{.{
        .id = "test-model",
        .name = "Test model",
        .provider = "test-provider",
        .context_window = 4096,
        .output_limit = 256,
        .max_output_tokens = 256,
    }};
    var stream = ConcurrentStreamState{ .io = std.testing.io };
    const runtime = try runtime_module.Runtime.createForTest(
        allocator,
        std.testing.io,
        &environment,
        .{
            .workspace_root = root,
            .state_root = root,
            .system_prompt = "stable",
            .default_selection = .{
                .provider = "test-provider",
                .model = "test-model",
                .context_window = 4096,
                .output_limit = 256,
            },
            .model_profiles = &profiles,
        },
        &stream,
        concurrentEchoStream,
    );
    defer runtime.destroy();

    const cursor = runtime.highWater();
    const subscription = try runtime.subscribe(cursor, 16);
    defer runtime.unsubscribe(subscription);
    const first = try runtime.startPrompt(.{ .session_id = "concurrent-a", .prompt = "alpha" });
    const second = try runtime.startPrompt(.{ .session_id = "concurrent-b", .prompt = "beta" });
    _ = try runtime.wait(first);
    _ = try runtime.wait(second);

    var capture = SequenceCapture{};
    defer capture.deinit(allocator);
    for (0..12) |_| try runtime.nextDurable(subscription, &capture, captureSequence);
    try std.testing.expectEqual(@as(usize, 12), capture.sequences.items.len);
    for (capture.sequences.items, 0..) |sequence, index| {
        try std.testing.expectEqual(cursor + index + 1, sequence);
    }
}

test "durable subscription hands off from catchup to live delivery without a gap" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    const root = root_buffer[0..root_len];
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    const profiles = [_]model_catalog.ModelProfile{.{
        .id = "test-model",
        .name = "Test model",
        .provider = "test-provider",
        .context_window = 4096,
        .output_limit = 256,
        .max_output_tokens = 256,
    }};
    var stream = StreamState{};
    const runtime = try runtime_module.Runtime.createForTest(
        allocator,
        std.testing.io,
        &environment,
        .{
            .workspace_root = root,
            .state_root = root,
            .system_prompt = "stable",
            .default_selection = .{
                .provider = "test-provider",
                .model = "test-model",
                .context_window = 4096,
                .output_limit = 256,
            },
            .model_profiles = &profiles,
        },
        &stream,
        echoStream,
    );
    defer runtime.destroy();

    const cursor = runtime.highWater();
    _ = try runtime.wait(try runtime.startPrompt(.{ .session_id = "catchup", .prompt = "before" }));
    const subscription = try runtime.subscribe(cursor, 16);
    defer runtime.unsubscribe(subscription);
    var capture = SequenceCapture{};
    defer capture.deinit(allocator);
    for (0..6) |_| try runtime.nextDurable(subscription, &capture, captureSequence);

    _ = try runtime.wait(try runtime.startPrompt(.{ .session_id = "live", .prompt = "after" }));
    for (0..6) |_| try runtime.nextDurable(subscription, &capture, captureSequence);
    try std.testing.expectEqual(@as(usize, 12), capture.sequences.items.len);
    for (capture.sequences.items, 0..) |sequence, index| {
        try std.testing.expectEqual(cursor + index + 1, sequence);
    }
}

test "multiple sessions and selections recover from the shared log" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    const root = root_buffer[0..root_len];
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    const profiles = [_]model_catalog.ModelProfile{
        .{
            .id = "model-a",
            .name = "Model A",
            .provider = "provider-a",
            .context_window = 4096,
            .output_limit = 256,
            .max_output_tokens = 256,
        },
        .{
            .id = "model-b",
            .name = "Model B",
            .provider = "provider-b",
            .context_window = 8192,
            .output_limit = 512,
            .max_output_tokens = 512,
            .reasoning = .high,
        },
    };
    const options = runtime_module.Options{
        .workspace_root = root,
        .state_root = root,
        .system_prompt = "stable",
        .default_selection = .{
            .provider = "provider-a",
            .model = "model-a",
            .context_window = 4096,
            .output_limit = 256,
        },
        .model_profiles = &profiles,
    };

    var first_stream = StreamState{};
    const first = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &environment, options, &first_stream, echoStream);
    _ = try first.wait(try first.startPrompt(.{ .session_id = "session-a", .prompt = "alpha" }));
    try first.setSelection("session-b", .{
        .provider = "provider-b",
        .model = "model-b",
        .context_window = 8192,
        .output_limit = 512,
        .reasoning = .high,
    });
    _ = try first.wait(try first.startPrompt(.{ .session_id = "session-b", .prompt = "beta" }));
    first.destroy();

    var second_stream = StreamState{};
    const second = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &environment, options, &second_stream, echoStream);
    defer second.destroy();
    const selection_a = try second.selection("session-a");
    defer allocator.free(selection_a.provider);
    defer allocator.free(selection_a.model);
    const selection_b = try second.selection("session-b");
    defer allocator.free(selection_b.provider);
    defer allocator.free(selection_b.model);
    try std.testing.expectEqualStrings("model-a", selection_a.model);
    try std.testing.expectEqualStrings("model-b", selection_b.model);
    try std.testing.expectEqual(protocol.ThinkingLevel.high, selection_b.reasoning.?);

    var messages_a = try second.sessionMessages("session-a", allocator);
    defer messages_a.deinit();
    var messages_b = try second.sessionMessages("session-b", allocator);
    defer messages_b.deinit();
    try std.testing.expectEqualStrings("alpha", messages_a.messages[1].assistant.content[0].text.text);
    try std.testing.expectEqualStrings("beta", messages_b.messages[1].assistant.content[0].text.text);
    try std.testing.expectEqual(@as(usize, 0), second_stream.calls);
}
