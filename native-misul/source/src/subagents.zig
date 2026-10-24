const std = @import("std");
const agent_loop = @import("agent_loop.zig");
const protocol = @import("protocol.zig");

pub const ChildId = u64;

pub const State = enum {
    ready,
    running,
    completed,
    cancelled,
    failed,
};

/// Borrowed stable prompt lanes. The caller must keep the slice and its lane
/// contents alive until every manager that uses this prefix is deinitialized.
pub const Prefix = struct {
    stable_lanes: []const []const u8,

    pub fn init(stable_lanes: []const []const u8) Prefix {
        return .{ .stable_lanes = stable_lanes };
    }
};

pub const ChildSpec = struct {
    role: []const u8,
    tool_allowlist: []const []const u8 = &.{},
};

pub const ChildInfo = struct {
    id: ChildId,
    state: State,
    prefix: Prefix,
    role: []const u8,
    tool_allowlist: []const []const u8,
};

const Child = struct {
    id: ChildId,
    state: State = .ready,
    role: []u8,
    tool_allowlist: [][]u8,
    result: ?agent_loop.Result = null,

    fn deinit(self: *Child, allocator: std.mem.Allocator) void {
        if (self.result) |*result| result.deinit();
        allocator.free(self.role);
        for (self.tool_allowlist) |name| allocator.free(name);
        allocator.free(self.tool_allowlist);
        self.* = undefined;
    }
};

pub const Manager = struct {
    allocator: std.mem.Allocator,
    prefix: Prefix,
    max_children: usize,
    next_id: ChildId = 1,
    children: std.ArrayList(Child) = .empty,

    pub fn init(allocator: std.mem.Allocator, prefix: Prefix, max_children: usize) Manager {
        return .{
            .allocator = allocator,
            .prefix = prefix,
            .max_children = max_children,
        };
    }

    pub fn deinit(self: *Manager) void {
        for (self.children.items) |*child| child.deinit(self.allocator);
        self.children.deinit(self.allocator);
        self.* = undefined;
    }

    pub fn create(self: *Manager, spec: ChildSpec) !ChildId {
        if (self.children.items.len >= self.max_children) return error.ChildLimitReached;
        if (std.mem.trim(u8, spec.role, " \t\r\n").len == 0) return error.EmptyRole;

        const role = try self.allocator.dupe(u8, spec.role);
        errdefer self.allocator.free(role);
        const allowlist = try cloneAllowlist(self.allocator, spec.tool_allowlist);
        errdefer freeAllowlist(self.allocator, allowlist);

        const id = self.next_id;
        self.next_id = std.math.add(ChildId, self.next_id, 1) catch return error.ChildIdExhausted;
        try self.children.append(self.allocator, .{
            .id = id,
            .role = role,
            .tool_allowlist = allowlist,
        });
        return id;
    }

    pub fn info(self: *const Manager, id: ChildId) !ChildInfo {
        const child = self.findConst(id) orelse return error.UnknownChild;
        return .{
            .id = child.id,
            .state = child.state,
            .prefix = self.prefix,
            .role = child.role,
            .tool_allowlist = child.tool_allowlist,
        };
    }

    pub fn state(self: *const Manager, id: ChildId) !State {
        return (self.findConst(id) orelse return error.UnknownChild).state;
    }

    pub fn cancel(self: *Manager, id: ChildId) !void {
        const child = self.find(id) orelse return error.UnknownChild;
        switch (child.state) {
            .ready, .running => child.state = .cancelled,
            .cancelled => {},
            .completed, .failed => return error.ChildFinished,
        }
    }

    /// Runs one child synchronously. The supplied system prompt lanes are
    /// treated as dynamic suffix lanes. The role is always the final lane.
    pub fn run(
        self: *Manager,
        id: ChildId,
        initial_messages: []const protocol.Message,
        base_config: agent_loop.Config,
    ) !void {
        const index = self.findIndex(id) orelse return error.UnknownChild;
        switch (self.children.items[index].state) {
            .ready => {},
            .cancelled => return error.ChildCancelled,
            .running => return error.ChildRunning,
            .completed, .failed => return error.ChildFinished,
        }

        self.children.items[index].state = .running;
        errdefer if (self.find(id)) |child| {
            if (child.state == .running) child.state = .failed;
        };
        const role = self.children.items[index].role;
        const allowlist = self.children.items[index].tool_allowlist;

        const suffix = if (base_config.system_prompt_lanes.len != 0)
            base_config.system_prompt_lanes
        else if (base_config.system_prompt.len != 0)
            @as([]const []const u8, &.{base_config.system_prompt})
        else
            @as([]const []const u8, &.{});

        const lanes = try self.allocator.alloc([]const u8, self.prefix.stable_lanes.len + suffix.len + 1);
        defer self.allocator.free(lanes);
        @memcpy(lanes[0..self.prefix.stable_lanes.len], self.prefix.stable_lanes);
        @memcpy(lanes[self.prefix.stable_lanes.len .. self.prefix.stable_lanes.len + suffix.len], suffix);
        lanes[lanes.len - 1] = role;

        const stable_prompt = try std.mem.join(self.allocator, "\n\n", self.prefix.stable_lanes);
        defer self.allocator.free(stable_prompt);
        const dynamic_prompt = try std.mem.join(self.allocator, "\n\n", lanes[self.prefix.stable_lanes.len..]);
        defer self.allocator.free(dynamic_prompt);
        const prompt = try protocol.joinSystemPromptLanes(self.allocator, stable_prompt, dynamic_prompt);
        defer self.allocator.free(prompt);

        const tools = try filterTools(self.allocator, base_config.tools, allowlist);
        defer self.allocator.free(tools);
        var lookup = LookupAdapter{
            .allowlist = allowlist,
            .context = base_config.tool_context orelse base_config.stream_context,
            .lookup = base_config.lookup_tool,
        };
        var events = EventAdapter{
            .manager = self,
            .id = id,
            .context = base_config.event_context orelse base_config.stream_context,
            .emit = base_config.emit,
        };
        var config = base_config;
        config.system_prompt = prompt;
        config.system_prompt_lanes = lanes;
        config.tools = tools;
        config.tool_context = &lookup;
        config.lookup_tool = filteredLookup;
        config.event_context = &events;
        config.emit = cancelAwareEmit;

        const agent_result = agent_loop.run(initial_messages, config) catch |err| {
            if (try self.state(id) == .cancelled) return error.ChildCancelled;
            return err;
        };
        const child = self.find(id) orelse {
            var orphaned_result = agent_result;
            orphaned_result.deinit();
            return error.UnknownChild;
        };
        if (child.state == .cancelled) {
            var cancelled_result = agent_result;
            cancelled_result.deinit();
            return error.ChildCancelled;
        }
        child.result = agent_result;
        child.state = .completed;
    }

    pub fn result(self: *const Manager, id: ChildId) !?*const agent_loop.Result {
        const child = self.findConst(id) orelse return error.UnknownChild;
        return if (child.result) |*result_value| result_value else null;
    }

    /// Transfers result ownership to the caller.
    pub fn takeResult(self: *Manager, id: ChildId) !agent_loop.Result {
        const child = self.find(id) orelse return error.UnknownChild;
        if (child.state != .completed) return error.ResultNotReady;
        const result_value = child.result orelse return error.ResultNotReady;
        child.result = null;
        return result_value;
    }

    pub fn remove(self: *Manager, id: ChildId) !void {
        const index = self.findIndex(id) orelse return error.UnknownChild;
        if (self.children.items[index].state == .running) return error.ChildRunning;
        var child = self.children.orderedRemove(index);
        child.deinit(self.allocator);
    }

    fn findIndex(self: *const Manager, id: ChildId) ?usize {
        for (self.children.items, 0..) |child, index| if (child.id == id) return index;
        return null;
    }

    fn find(self: *Manager, id: ChildId) ?*Child {
        const index = self.findIndex(id) orelse return null;
        return &self.children.items[index];
    }

    fn findConst(self: *const Manager, id: ChildId) ?*const Child {
        const index = self.findIndex(id) orelse return null;
        return &self.children.items[index];
    }
};

const EventAdapter = struct {
    manager: *Manager,
    id: ChildId,
    context: *anyopaque,
    emit: ?agent_loop.EventSink,
};

fn cancelAwareEmit(raw: *anyopaque, event: agent_loop.Event) !void {
    const adapter: *EventAdapter = @ptrCast(@alignCast(raw));
    if (try adapter.manager.state(adapter.id) == .cancelled) return error.ChildCancelled;
    if (adapter.emit) |emit| try emit(adapter.context, event);
    if (try adapter.manager.state(adapter.id) == .cancelled) return error.ChildCancelled;
}

const LookupAdapter = struct {
    allowlist: []const []const u8,
    context: *anyopaque,
    lookup: ?agent_loop.ToolLookupFn,
};

fn filteredLookup(raw: *anyopaque, name: []const u8) ?agent_loop.ToolBinding {
    const adapter: *LookupAdapter = @ptrCast(@alignCast(raw));
    if (!isAllowed(adapter.allowlist, name)) return null;
    const lookup = adapter.lookup orelse return null;
    return lookup(adapter.context, name);
}

fn filterTools(allocator: std.mem.Allocator, tools: []const protocol.Tool, allowlist: []const []const u8) ![]protocol.Tool {
    var count: usize = 0;
    for (tools) |tool| count += @intFromBool(isAllowed(allowlist, tool.name));
    const filtered = try allocator.alloc(protocol.Tool, count);
    var index: usize = 0;
    for (tools) |tool| {
        if (!isAllowed(allowlist, tool.name)) continue;
        filtered[index] = tool;
        index += 1;
    }
    return filtered;
}

fn isAllowed(allowlist: []const []const u8, name: []const u8) bool {
    for (allowlist) |allowed| if (std.mem.eql(u8, allowed, name)) return true;
    return false;
}

fn cloneAllowlist(allocator: std.mem.Allocator, source: []const []const u8) ![][]u8 {
    const result = try allocator.alloc([]u8, source.len);
    errdefer allocator.free(result);
    var initialized: usize = 0;
    errdefer for (result[0..initialized]) |name| allocator.free(name);
    for (source, 0..) |name, index| {
        if (std.mem.trim(u8, name, " \t\r\n").len == 0) return error.EmptyToolName;
        for (source[0..index]) |earlier| if (std.mem.eql(u8, earlier, name)) return error.DuplicateToolName;
        result[index] = try allocator.dupe(u8, name);
        initialized += 1;
    }
    return result;
}

fn freeAllowlist(allocator: std.mem.Allocator, allowlist: [][]u8) void {
    for (allowlist) |name| allocator.free(name);
    allocator.free(allowlist);
}

const test_model = protocol.Model{
    .id = "subagent-test",
    .api = "test",
    .provider = "test",
    .context_window = 1024,
    .max_tokens = 64,
};

const TestStreamState = struct {
    expected_prefix: Prefix,
    expected_suffix: []const []const u8 = &.{},
    expected_role: []const u8,
    expected_tools: []const []const u8 = &.{},
    tool_call: ?[]const u8 = null,
    calls: usize = 0,
    saw_expected: bool = false,
};

fn completeStream(
    raw: *anyopaque,
    _: *const protocol.Model,
    context: *const protocol.Context,
    _: protocol.StreamOptions,
    sink_context: *anyopaque,
    sink: protocol.StreamSink,
) !void {
    const state: *TestStreamState = @ptrCast(@alignCast(raw));
    state.calls += 1;
    var valid = context.system_prompt_lanes.len == state.expected_prefix.stable_lanes.len + state.expected_suffix.len + 1;
    for (state.expected_prefix.stable_lanes, 0..) |lane, index| {
        valid = valid and context.system_prompt_lanes[index].ptr == lane.ptr;
        valid = valid and std.mem.eql(u8, context.system_prompt_lanes[index], lane);
    }
    for (state.expected_suffix, 0..) |lane, index| {
        valid = valid and std.mem.eql(u8, context.system_prompt_lanes[state.expected_prefix.stable_lanes.len + index], lane);
    }
    valid = valid and std.mem.eql(u8, context.system_prompt_lanes[context.system_prompt_lanes.len - 1], state.expected_role);
    valid = valid and context.tools.len == state.expected_tools.len;
    for (state.expected_tools, 0..) |name, index| valid = valid and std.mem.eql(u8, context.tools[index].name, name);
    state.saw_expected = state.saw_expected or valid;

    if (state.tool_call) |name| {
        if (state.calls == 1) {
            try sink(sink_context, .{ .done = .{
                .content = &.{.{ .tool_call = .{ .id = "call", .name = name, .arguments = .null } }},
                .api = "test",
                .provider = "test",
                .model = "subagent-test",
                .stop_reason = .tool_use,
                .timestamp_ms = 1,
            } });
            return;
        }
    }
    try sink(sink_context, .{ .done = .{
        .content = &.{.{ .text = .{ .text = "owned result" } }},
        .api = "test",
        .provider = "test",
        .model = "subagent-test",
        .stop_reason = .stop,
        .timestamp_ms = 1,
    } });
}

fn configFor(state: *TestStreamState) agent_loop.Config {
    return .{
        .allocator = std.testing.allocator,
        .model = &test_model,
        .stream_context = state,
        .stream = completeStream,
        .max_steps = 2,
    };
}

test "children share stable prefix identity and content" {
    const stable = [_][]const u8{ "provider", "project" };
    const prefix = Prefix.init(&stable);
    var manager = Manager.init(std.testing.allocator, prefix, 2);
    defer manager.deinit();
    const first = try manager.create(.{ .role = "reviewer" });
    const second = try manager.create(.{ .role = "tester" });
    const first_info = try manager.info(first);
    const second_info = try manager.info(second);
    try std.testing.expect(first_info.prefix.stable_lanes.ptr == prefix.stable_lanes.ptr);
    try std.testing.expect(second_info.prefix.stable_lanes.ptr == prefix.stable_lanes.ptr);
    try std.testing.expectEqualStrings("project", second_info.prefix.stable_lanes[1]);
}

test "role overlay is placed after stable and dynamic lanes" {
    const stable = [_][]const u8{"stable"};
    const dynamic = [_][]const u8{"task"};
    const prefix = Prefix.init(&stable);
    var manager = Manager.init(std.testing.allocator, prefix, 1);
    defer manager.deinit();
    const id = try manager.create(.{ .role = "role" });
    var state = TestStreamState{ .expected_prefix = prefix, .expected_suffix = &dynamic, .expected_role = "role" };
    var config = configFor(&state);
    config.system_prompt_lanes = &dynamic;
    try manager.run(id, &.{}, config);
    const result_value = (try manager.result(id)).?;
    try std.testing.expect(state.saw_expected);
    try std.testing.expectEqual(@as(usize, 1), result_value.steps);
}

const ToolState = struct { executed: bool = false };

fn testTool(raw: *anyopaque, _: *const protocol.ToolCall, _: std.mem.Allocator) !agent_loop.ToolOutput {
    const state: *ToolState = @ptrCast(@alignCast(raw));
    state.executed = true;
    return .{ .content = &.{.{ .text = .{ .text = "ok" } }}, .terminate = true };
}

fn testLookup(raw: *anyopaque, name: []const u8) ?agent_loop.ToolBinding {
    if (!std.mem.eql(u8, name, "read")) return null;
    return .{ .context = raw, .execute = testTool };
}

test "tool definitions and lookup are isolated per child" {
    const stable = [_][]const u8{"stable"};
    const allowed = [_][]const u8{"read"};
    const expected = [_][]const u8{"read"};
    const definitions = [_]protocol.Tool{
        .{ .name = "read", .description = "read", .parameters = .null },
        .{ .name = "write", .description = "write", .parameters = .null },
    };
    const prefix = Prefix.init(&stable);
    var manager = Manager.init(std.testing.allocator, prefix, 1);
    defer manager.deinit();
    const id = try manager.create(.{ .role = "reader", .tool_allowlist = &allowed });
    var stream_state = TestStreamState{
        .expected_prefix = prefix,
        .expected_role = "reader",
        .expected_tools = &expected,
        .tool_call = "write",
    };
    var tool_state = ToolState{};
    var config = configFor(&stream_state);
    config.tools = &definitions;
    config.tool_context = &tool_state;
    config.lookup_tool = testLookup;
    try manager.run(id, &.{}, config);
    try std.testing.expect(stream_state.saw_expected);
    try std.testing.expect(!tool_state.executed);
    const result_value = (try manager.result(id)).?;
    try std.testing.expect(result_value.messages[1].tool_result.is_error);
}

const CancelState = struct { manager: *Manager, id: ChildId };

fn cancelOnStart(raw: *anyopaque, event: agent_loop.Event) !void {
    const state: *CancelState = @ptrCast(@alignCast(raw));
    switch (event) {
        .agent_start => try state.manager.cancel(state.id),
        else => {},
    }
}

test "lifecycle and cancellation are explicit" {
    const stable = [_][]const u8{"stable"};
    var manager = Manager.init(std.testing.allocator, Prefix.init(&stable), 1);
    defer manager.deinit();
    const first = try manager.create(.{ .role = "worker" });
    try std.testing.expectEqual(State.ready, try manager.state(first));
    try manager.cancel(first);
    try manager.cancel(first);
    try std.testing.expectEqual(State.cancelled, try manager.state(first));
    var state = TestStreamState{ .expected_prefix = manager.prefix, .expected_role = "worker" };
    try std.testing.expectError(error.ChildCancelled, manager.run(first, &.{}, configFor(&state)));

    try manager.remove(first);
    const second = try manager.create(.{ .role = "worker" });
    var cancel_state = CancelState{ .manager = &manager, .id = second };
    var config = configFor(&state);
    config.event_context = &cancel_state;
    config.emit = cancelOnStart;
    try std.testing.expectError(error.ChildCancelled, manager.run(second, &.{}, config));
    try std.testing.expectEqual(State.cancelled, try manager.state(second));
}

test "child limit is released by remove" {
    const stable = [_][]const u8{"stable"};
    var manager = Manager.init(std.testing.allocator, Prefix.init(&stable), 1);
    defer manager.deinit();
    const first = try manager.create(.{ .role = "first" });
    try std.testing.expectError(error.ChildLimitReached, manager.create(.{ .role = "second" }));
    try manager.remove(first);
    const second = try manager.create(.{ .role = "second" });
    try std.testing.expect(second != first);
}

test "taken result remains owned after manager deinit" {
    const stable = [_][]const u8{"stable"};
    const prefix = Prefix.init(&stable);
    var manager = Manager.init(std.testing.allocator, prefix, 1);
    const id = try manager.create(.{ .role = "worker" });
    var state = TestStreamState{ .expected_prefix = prefix, .expected_role = "worker" };
    try manager.run(id, &.{}, configFor(&state));
    var owned = try manager.takeResult(id);
    manager.deinit();
    defer owned.deinit();
    try std.testing.expectEqualStrings("owned result", owned.messages[0].assistant.content[0].text.text);
}
