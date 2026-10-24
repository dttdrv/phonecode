const std = @import("std");
const protocol = @import("protocol.zig");
const cordis = @import("cordis.zig");
const prompt_stack = @import("prompt_stack.zig");
const tool_stack = @import("tool_stack.zig");
const causal_runtime = @import("causal_runtime.zig");
const agent_loop = @import("agent_loop.zig");

pub const Tool = struct {
    name: []const u8,
    description: []const u8,
    parameters: std.json.Value,
    context: *anyopaque,
    execute: agent_loop.ToolExecuteFn,

    fn definition(self: Tool) protocol.Tool {
        return .{ .name = self.name, .description = self.description, .parameters = self.parameters };
    }
};

pub const ToolStack = tool_stack.ToolStack(Tool);

pub fn Harness(comptime PromptContext: type) type {
    return struct {
        const Self = @This();
        pub const PromptStack = prompt_stack.PromptStack(PromptContext);
        pub const Services = struct {
            prompts: *PromptStack,
            tools: *ToolStack,
        };

        pub const RunConfig = struct {
            causal: ?*causal_runtime.Runtime = null,
            system_override: ?[]const u8 = null,
        };

        allocator: std.mem.Allocator,
        prompts: *PromptStack,
        tools: *ToolStack,

        pub fn plugin(name: []const u8, revision: u64, services: *Services) cordis.Plugin {
            return .{ .name = name, .revision = revision, .state = services, .start = mountServices };
        }

        fn mountServices(_: *cordis.Context, fiber: *cordis.Fiber, raw: *anyopaque) !void {
            const services: *Services = @ptrCast(@alignCast(raw));
            try fiber.provide(PromptStack, services.prompts);
            try fiber.provide(ToolStack, services.tools);
        }

        /// Resolves the harness capabilities from a native Cordis context. Parent
        /// contexts are inherited, so a session can override either stack safely.
        pub fn init(allocator: std.mem.Allocator, context: *cordis.Context) !Self {
            return .{
                .allocator = allocator,
                .prompts = try context.require(PromptStack),
                .tools = try context.require(ToolStack),
            };
        }

        /// Renders prompt lanes and resolves tool overlays for one run. The prompt and
        /// tool stacks remain the only implementations of ordering and shadowing.
        pub fn run(
            self: *Self,
            context: PromptContext,
            initial_messages: []const protocol.Message,
            goal: []const u8,
            run_config: RunConfig,
            base_config: agent_loop.Config,
        ) !agent_loop.Result {
            var rendered = try self.prompts.render(context);
            defer rendered.deinit(self.allocator);
            const prompt = if (run_config.system_override) |stable|
                try protocol.joinSystemPromptLanes(self.allocator, stable, rendered.session_prompt)
            else
                rendered.prompt;
            defer if (run_config.system_override != null) self.allocator.free(prompt);
            var lane_storage: [2][]const u8 = undefined;
            var lane_count: usize = 0;
            const stable_prompt = run_config.system_override orelse rendered.stable_prompt;
            if (stable_prompt.len != 0) {
                lane_storage[lane_count] = stable_prompt;
                lane_count += 1;
            }
            if (rendered.session_prompt.len != 0) {
                lane_storage[lane_count] = rendered.session_prompt;
                lane_count += 1;
            }
            const lanes = lane_storage[0..lane_count];

            const active_tools = try self.tools.values(self.allocator);
            defer self.allocator.free(active_tools);
            const definitions = try self.allocator.alloc(protocol.Tool, active_tools.len);
            defer self.allocator.free(definitions);
            for (active_tools, 0..) |tool, index| definitions[index] = tool.definition();

            if (run_config.causal) |runtime| try runtime.start(goal);
            var adapter = Adapter{
                .tools = active_tools,
                .causal = run_config.causal,
                .user_hook_context = base_config.hook_context,
                .user_before_tool = base_config.before_tool,
                .user_after_tool = base_config.after_tool,
                .user_event_context = base_config.event_context,
                .user_emit = base_config.emit,
            };
            var config = base_config;
            config.allocator = self.allocator;
            config.system_prompt = prompt;
            config.stable_prompt = stable_prompt;
            config.system_prompt_lanes = lanes;
            config.tools = definitions;
            config.tool_context = &adapter;
            config.lookup_tool = lookupTool;
            config.hook_context = &adapter;
            config.before_tool = beforeTool;
            config.after_tool = afterTool;
            config.event_context = &adapter;
            config.emit = forwardEvent;
            return agent_loop.run(initial_messages, config);
        }
    };
}

const Adapter = struct {
    tools: []const Tool,
    causal: ?*causal_runtime.Runtime,
    user_hook_context: ?*anyopaque,
    user_before_tool: ?agent_loop.BeforeToolFn,
    user_after_tool: ?agent_loop.AfterToolFn,
    user_event_context: ?*anyopaque,
    user_emit: ?agent_loop.EventSink,
};

fn lookupTool(raw: *anyopaque, name: []const u8) ?agent_loop.ToolBinding {
    const adapter: *Adapter = @ptrCast(@alignCast(raw));
    for (adapter.tools) |tool| if (std.mem.eql(u8, tool.name, name)) return .{
        .context = tool.context,
        .execute = tool.execute,
    };
    return null;
}

fn beforeTool(raw: *anyopaque, call: *const protocol.ToolCall) !void {
    const adapter: *Adapter = @ptrCast(@alignCast(raw));
    if (adapter.causal) |runtime| {
        const input = try std.json.Stringify.valueAlloc(runtime.allocator, call.arguments, .{});
        defer runtime.allocator.free(input);
        const effects = causal_runtime.resolveCommonToolEffects(call.name, call.arguments);
        var diagnostics = try runtime.beforeAction(call.id, call.name, input, effects.slice(), 0);
        defer diagnostics.deinit();
        if (runtime.enforcement == .block and diagnostics.items.len != 0) return error.CausalRuntimeBlocked;
    }
    if (adapter.user_before_tool) |hook| try hook(adapter.user_hook_context orelse raw, call);
}

fn afterTool(raw: *anyopaque, call: *const protocol.ToolCall, result: *const protocol.ToolResultMessage) !void {
    const adapter: *Adapter = @ptrCast(@alignCast(raw));
    if (adapter.causal) |runtime| {
        var diagnostics = try runtime.afterAction(call.id, result.is_error, 0);
        defer diagnostics.deinit();
    }
    if (adapter.user_after_tool) |hook| try hook(adapter.user_hook_context orelse raw, call, result);
}

fn forwardEvent(raw: *anyopaque, event: agent_loop.Event) !void {
    const adapter: *Adapter = @ptrCast(@alignCast(raw));
    if (adapter.user_emit) |sink| try sink(adapter.user_event_context orelse raw, event);
}

const TestContext = struct {};

const TestState = struct { saw_lanes: bool = false, executed: bool = false };
const test_model = protocol.Model{ .id = "fake", .api = "test", .provider = "fake", .context_window = 1024, .max_tokens = 128 };

fn harnessStream(raw: *anyopaque, _: *const protocol.Model, context: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    const state: *TestState = @ptrCast(@alignCast(raw));
    state.saw_lanes = context.system_prompt_lanes.len == 2 and
        std.mem.eql(u8, context.system_prompt_lanes[0], "stable") and
        std.mem.eql(u8, context.system_prompt_lanes[1], "session");
    const call = protocol.ToolCall{ .id = "call", .name = "read_file", .arguments = .null };
    const assistant = protocol.AssistantMessage{
        .content = &.{.{ .tool_call = call }},
        .api = "test",
        .provider = "fake",
        .model = "fake",
        .stop_reason = .tool_use,
        .timestamp_ms = 1,
    };
    try sink(sink_context, .{ .done = assistant });
}

fn testTool(raw: *anyopaque, _: *const protocol.ToolCall, _: std.mem.Allocator) !agent_loop.ToolOutput {
    const state: *TestState = @ptrCast(@alignCast(raw));
    state.executed = true;
    return .{ .content = &.{.{ .text = .{ .text = "ok" } }}, .terminate = true };
}

test "harness composes prompt and tool stacks with causal hooks" {
    const allocator = std.testing.allocator;
    const Stack = prompt_stack.PromptStack(TestContext);
    var prompts = Stack.init(allocator);
    defer prompts.deinit();
    _ = try prompts.register(.{ .id = "stable", .cache = .stable, .content = .{ .static = "stable" } });
    _ = try prompts.register(.{ .id = "session", .cache = .session, .content = .{ .static = "session" } });

    var state = TestState{};
    var tools = ToolStack.init(allocator);
    defer tools.deinit();
    try tools.setBase(&.{.{
        .name = "read_file",
        .description = "read",
        .parameters = .null,
        .context = &state,
        .execute = testTool,
    }});
    var context = cordis.Context.init(allocator, null);
    defer context.deinit();
    const NativeHarness = Harness(TestContext);
    var services = NativeHarness.Services{ .prompts = &prompts, .tools = &tools };
    _ = try context.mount(NativeHarness.plugin("harness-services", 1, &services));

    var runtime = try causal_runtime.Runtime.init(allocator, .observe);
    defer runtime.deinit();
    var harness = try Harness(TestContext).init(allocator, &context);
    var result = try harness.run(.{}, &.{}, "inspect", .{ .causal = &runtime }, .{
        .allocator = allocator,
        .model = &test_model,
        .stream_context = &state,
        .stream = harnessStream,
        .max_steps = 1,
    });
    defer result.deinit();
    try std.testing.expect(state.saw_lanes);
    try std.testing.expect(state.executed);
    try std.testing.expectEqual(@as(usize, 1), runtime.actions.items.len);
}
