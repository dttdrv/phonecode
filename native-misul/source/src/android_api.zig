const std = @import("std");
const builtin = @import("builtin");
const model_catalog = @import("model_catalog.zig");
const openai_compatible = @import("openai_compatible.zig");
const rpc_server = @import("rpc_server.zig");
const runtime_module = @import("runtime.zig");
const harness = @import("harness.zig");
const protocol = @import("protocol.zig");
const agent_loop = @import("agent_loop.zig");

pub const abi_version: u32 = 1;
pub const max_config_bytes: usize = 512 * 1024;
pub const max_record_bytes: usize = rpc_server.max_record_bytes;
const max_queued_records: usize = 256;
const max_queued_bytes: usize = 4 * max_record_bytes;
const max_event_wait_ms: u32 = 60_000;
const is_android = builtin.os.tag == .linux and builtin.abi.isAndroid();

extern fn misul_android_signals_acquire() callconv(.c) c_int;
extern fn misul_android_signals_repair() callconv(.c) c_int;
extern fn misul_android_signals_release() callconv(.c) void;

pub const Status = enum(u32) {
    ok = 0,
    invalid_argument = 1,
    invalid_config = 2,
    out_of_memory = 3,
    runtime_error = 4,
    unsupported = 5,
    wrong_owner = 6,
};

pub const Buffer = extern struct {
    data: ?[*]u8 = null,
    len: usize = 0,
};

const OpenConfig = struct {
    workspace_root: []const u8,
    state_root: []const u8,
    system_prompt: []const u8,
    default_selection: runtime_module.ModelSelection,
    model_profiles: []const model_catalog.ModelProfile,
    provider_configs: []const openai_compatible.ProviderConfig,
    maximum_active_runs: usize = 8,
    max_steps: usize = 32,
    allow_mutating_tools: bool = false,
    host_tools: []const HostToolConfig = &.{},
    disabled_tools: []const []const u8 = &.{},
    approval_tools: []const []const u8 = &.{},
};

/// A tool the Android host executes. Calls arrive as `host_tool_request` records and settle
/// through `misul_android_host_response` with a `tool_result` record.
const HostToolConfig = struct {
    name: []const u8,
    description: []const u8,
    parameters: std.json.Value,
    mutating: bool = false,
    /// Runs alone instead of overlapping other calls, for example because it prompts the user.
    sequential: bool = false,
};

const HostTool = struct { handle: *Handle };

const HostResult = struct { content: []u8, is_error: bool };

pub const Handle = struct {
    allocator: std.mem.Allocator,
    threaded: std.Io.Threaded,
    environment: std.process.Environ.Map,
    runtime: *runtime_module.Runtime,
    server: rpc_server.Server,
    output: rpc_server.RecordOutput,
    events: std.ArrayList([]u8),
    queued_event_bytes: usize,
    buffers: std.AutoHashMap(usize, usize),
    approval_mutex: std.Io.Mutex = std.Io.Mutex.init,
    approval_condition: std.Io.Condition = std.Io.Condition.init,
    pending_approval_id: ?[]u8 = null,
    pending_approval_decision: ?bool = null,
    cancel_pending_approval: bool = false,
    closing: bool = false,
    /// Owns host tool definitions and name lists for the runtime's lifetime.
    host_arena: std.heap.ArenaAllocator,
    /// Pending host tool calls by id; a null value is still waiting. Guarded by approval_mutex.
    host_calls: std.StringHashMapUnmanaged(?HostResult) = .empty,
    /// Bumped by every abort so waiting host calls settle as canceled.
    host_abort_generation: u64 = 0,

    fn close(self: *Handle) void {
        self.approval_mutex.lockUncancelable(self.threaded.io());
        self.closing = true;
        self.approval_condition.broadcast(self.threaded.io());
        self.approval_mutex.unlock(self.threaded.io());
        self.server.finishConcurrent(true) catch {};
        self.output.mutex.lockUncancelable(self.threaded.io());
        for (self.events.items) |record| self.allocator.free(record);
        self.events.deinit(self.allocator);
        self.queued_event_bytes = 0;
        self.output.mutex.unlock(self.threaded.io());
        var iterator = self.buffers.iterator();
        while (iterator.next()) |entry| {
            const pointer: [*]u8 = @ptrFromInt(entry.key_ptr.*);
            self.allocator.free(pointer[0..entry.value_ptr.*]);
        }
        self.buffers.deinit();
        self.server.deinit();
        self.runtime.destroy();
        self.host_calls.deinit(self.allocator);
        self.host_arena.deinit();
        self.environment.deinit();
        self.threaded.deinit();
        if (is_android) misul_android_signals_release();
        const allocator = self.allocator;
        self.* = undefined;
        allocator.destroy(self);
    }
};

pub export fn misul_android_abi_version() callconv(.c) u32 {
    return abi_version;
}

pub export fn misul_android_open(config: ?[*]const u8, config_len: usize, out_handle: ?*?*Handle) callconv(.c) Status {
    const destination = out_handle orelse return .invalid_argument;
    if (destination.* != null) return .invalid_argument;
    const bytes = checkedInput(config, config_len, max_config_bytes) orelse return .invalid_argument;
    destination.* = openWithAllocator(std.heap.smp_allocator, bytes) catch |err| return statusForError(err);
    return .ok;
}

pub export fn misul_android_request(handle: ?*Handle, record: ?[*]const u8, record_len: usize, out_result: ?*Buffer) callconv(.c) Status {
    const active = handle orelse return .invalid_argument;
    const result = out_result orelse return .invalid_argument;
    result.* = .{};
    const bytes = checkedInput(record, record_len, max_record_bytes) orelse return .invalid_argument;
    if (!std.unicode.utf8ValidateSlice(bytes)) return .invalid_argument;

    const asynchronous = isAsynchronousRequest(active.allocator, bytes);
    if (isAbortRequest(active.allocator, bytes)) cancelPendingApproval(active);
    active.server.handleConcurrentLine(bytes, &active.output) catch |err| return statusForError(err);
    if (asynchronous) return .ok;
    return takeQueuedRecord(active, result);
}

pub export fn misul_android_next_event(handle: ?*Handle, timeout_ms: u32, out_result: ?*Buffer) callconv(.c) Status {
    const active = handle orelse return .invalid_argument;
    const result = out_result orelse return .invalid_argument;
    result.* = .{};
    if (timeout_ms > max_event_wait_ms) return .invalid_argument;
    const io = active.threaded.io();
    const deadline = std.Io.Clock.Timestamp.now(io, .awake).addDuration(.{
        .clock = .awake,
        .raw = .fromMilliseconds(timeout_ms),
    });
    while (true) {
        const status = takeQueuedRecord(active, result);
        if (status != .ok or result.data != null or timeout_ms == 0) return status;
        if (std.Io.Clock.Timestamp.now(io, .awake).compare(.gte, deadline)) return .ok;
        std.Io.sleep(io, .fromMilliseconds(1), .awake) catch return .runtime_error;
    }
}

pub export fn misul_android_host_response(handle: ?*Handle, record: ?[*]const u8, record_len: usize) callconv(.c) Status {
    const active = handle orelse return .invalid_argument;
    const bytes = checkedInput(record, record_len, max_record_bytes) orelse return .invalid_argument;
    if (!std.unicode.utf8ValidateSlice(bytes)) return .invalid_argument;
    var parsed = std.json.parseFromSlice(std.json.Value, active.allocator, bytes, .{}) catch return .invalid_argument;
    defer parsed.deinit();
    if (parsed.value != .object) return .invalid_argument;
    const kind = parsed.value.object.get("type") orelse return .invalid_argument;
    const id = parsed.value.object.get("id") orelse return .invalid_argument;
    if (kind == .string and std.mem.eql(u8, kind.string, "tool_result")) return settleHostTool(active, parsed.value.object, id);
    const approved = parsed.value.object.get("approved") orelse return .invalid_argument;
    if (kind != .string or !std.mem.eql(u8, kind.string, "approval") or id != .string or approved != .bool) return .invalid_argument;

    active.approval_mutex.lockUncancelable(active.threaded.io());
    defer active.approval_mutex.unlock(active.threaded.io());
    const pending = active.pending_approval_id orelse return .invalid_argument;
    if (!std.mem.eql(u8, pending, id.string) or active.pending_approval_decision != null) return .invalid_argument;
    active.pending_approval_decision = approved.bool;
    active.approval_condition.broadcast(active.threaded.io());
    return .ok;
}

pub export fn misul_android_buffer_free(handle: ?*Handle, buffer: ?*Buffer) callconv(.c) Status {
    const active = handle orelse return .invalid_argument;
    const owned = buffer orelse return .invalid_argument;
    const data = owned.data orelse {
        if (owned.len != 0) return .invalid_argument;
        return .ok;
    };
    const key = @intFromPtr(data);
    const expected_len = active.buffers.get(key) orelse return .wrong_owner;
    if (expected_len != owned.len) return .wrong_owner;
    _ = active.buffers.remove(key);
    active.allocator.free(data[0..owned.len]);
    owned.* = .{};
    return .ok;
}

pub export fn misul_android_close(handle: ?*?*Handle) callconv(.c) Status {
    const owner = handle orelse return .invalid_argument;
    const active = owner.* orelse return .ok;
    owner.* = null;
    active.close();
    return .ok;
}

fn checkedInput(pointer: ?[*]const u8, len: usize, maximum: usize) ?[]const u8 {
    if (len == 0 or len > maximum) return null;
    return (pointer orelse return null)[0..len];
}

fn openWithAllocator(allocator: std.mem.Allocator, config_json: []const u8) !*Handle {
    if (!std.unicode.utf8ValidateSlice(config_json)) return error.InvalidConfig;
    var parsed = std.json.parseFromSlice(OpenConfig, allocator, config_json, .{}) catch |err| switch (err) {
        error.OutOfMemory => return err,
        else => return error.InvalidConfig,
    };
    defer parsed.deinit();

    const handle = try allocator.create(Handle);
    errdefer allocator.destroy(handle);
    handle.allocator = allocator;
    if (is_android) {
        if (misul_android_signals_acquire() == 0) return error.AndroidRuntimeBusy;
    }
    errdefer if (is_android) misul_android_signals_release();
    handle.threaded = std.Io.Threaded.init(allocator, .{});
    errdefer handle.threaded.deinit();
    if (is_android and misul_android_signals_repair() == 0) return error.AndroidSignalSetupFailed;
    handle.approval_mutex = std.Io.Mutex.init;
    handle.approval_condition = std.Io.Condition.init;
    handle.host_arena = std.heap.ArenaAllocator.init(allocator);
    errdefer handle.host_arena.deinit();
    handle.host_calls = .empty;
    handle.host_abort_generation = 0;
    const host = try hostToolOptions(handle, parsed.value);
    handle.pending_approval_id = null;
    handle.pending_approval_decision = null;
    handle.cancel_pending_approval = false;
    handle.closing = false;
    handle.environment = std.process.Environ.Map.init(allocator);
    errdefer handle.environment.deinit();
    const io = handle.threaded.io();
    handle.runtime = runtime_module.Runtime.create(allocator, io, &handle.environment, .{
        .workspace_root = parsed.value.workspace_root,
        .state_root = parsed.value.state_root,
        .system_prompt = parsed.value.system_prompt,
        .default_selection = parsed.value.default_selection,
        .model_profiles = parsed.value.model_profiles,
        .provider_configs = parsed.value.provider_configs,
        .maximum_active_runs = parsed.value.maximum_active_runs,
        .max_steps = parsed.value.max_steps,
        .allow_mutating_tools = parsed.value.allow_mutating_tools,
        .approval_context = handle,
        .approve_tool = requestToolApproval,
        .host_tools = host.tools,
        .mutating_tools = host.mutating,
        .serial_tools = host.serial,
        .disabled_tools = host.disabled,
        .approval_tools = host.approval,
    }) catch |err| switch (err) {
        error.OutOfMemory => return err,
        else => return error.InvalidConfig,
    };
    errdefer handle.runtime.destroy();
    handle.server = try rpc_server.Server.init(allocator, handle.runtime);
    errdefer handle.server.deinit();
    handle.events = .empty;
    errdefer handle.events.deinit(allocator);
    handle.queued_event_bytes = 0;
    handle.output = .{ .io = io, .context = handle, .write = enqueueRecord };
    handle.buffers = std.AutoHashMap(usize, usize).init(allocator);
    return handle;
}

fn requestToolApproval(
    raw: *anyopaque,
    call: *const @import("protocol.zig").ToolCall,
    cancel_requested: *const std.atomic.Value(bool),
) !bool {
    const handle: *Handle = @ptrCast(@alignCast(raw));
    const io = handle.threaded.io();
    handle.approval_mutex.lockUncancelable(io);
    if (handle.pending_approval_id != null or handle.closing) {
        handle.approval_mutex.unlock(io);
        return error.ApprovalUnavailable;
    }
    handle.pending_approval_id = try handle.allocator.dupe(u8, call.id);
    handle.pending_approval_decision = null;
    handle.cancel_pending_approval = false;
    handle.approval_mutex.unlock(io);
    errdefer clearPendingApproval(handle);

    const notification = try std.json.Stringify.valueAlloc(handle.allocator, .{
        .jsonrpc = "2.0",
        .method = "approval_request",
        .params = .{
            .id = call.id,
            .name = call.name,
            .input = call.arguments,
        },
    }, .{});
    defer handle.allocator.free(notification);
    try handle.output.writeAtomic(notification);

    handle.approval_mutex.lockUncancelable(io);
    while (handle.pending_approval_decision == null and
        !handle.cancel_pending_approval and
        !handle.closing and
        !cancel_requested.load(.acquire))
    {
        handle.approval_condition.waitUncancelable(io, &handle.approval_mutex);
    }
    const canceled = handle.cancel_pending_approval or handle.closing or cancel_requested.load(.acquire);
    const approved = handle.pending_approval_decision orelse false;
    const owned_id = handle.pending_approval_id.?;
    handle.pending_approval_id = null;
    handle.pending_approval_decision = null;
    handle.cancel_pending_approval = false;
    handle.approval_mutex.unlock(io);
    handle.allocator.free(owned_id);
    if (canceled) return error.Canceled;
    return approved;
}

const HostToolOptions = struct {
    tools: []harness.Tool,
    mutating: []const []const u8,
    serial: []const []const u8,
    disabled: []const []const u8,
    approval: []const []const u8,
};

fn hostToolOptions(handle: *Handle, config: OpenConfig) !HostToolOptions {
    const arena = handle.host_arena.allocator();
    const tools = try arena.alloc(harness.Tool, config.host_tools.len);
    var mutating: std.ArrayList([]const u8) = .empty;
    var serial: std.ArrayList([]const u8) = .empty;
    const context = try arena.create(HostTool);
    context.* = .{ .handle = handle };
    for (config.host_tools, tools) |spec, *tool| {
        if (spec.name.len == 0) return error.InvalidConfig;
        const name = try arena.dupe(u8, spec.name);
        tool.* = .{
            .name = name,
            .description = try arena.dupe(u8, spec.description),
            .parameters = try cloneValue(arena, spec.parameters),
            .context = context,
            .execute = executeHostTool,
        };
        if (spec.mutating) try mutating.append(arena, name);
        if (spec.sequential) try serial.append(arena, name);
    }
    return .{
        .tools = tools,
        .mutating = mutating.items,
        .serial = serial.items,
        .disabled = try dupeNames(arena, config.disabled_tools),
        .approval = try dupeNames(arena, config.approval_tools),
    };
}

fn dupeNames(arena: std.mem.Allocator, names: []const []const u8) ![]const []const u8 {
    const copy = try arena.alloc([]const u8, names.len);
    for (names, copy) |name, *slot| slot.* = try arena.dupe(u8, name);
    return copy;
}

fn cloneValue(arena: std.mem.Allocator, value: std.json.Value) !std.json.Value {
    const text = try std.json.Stringify.valueAlloc(arena, value, .{});
    return std.json.parseFromSliceLeaky(std.json.Value, arena, text, .{});
}

/// Runs on a tool worker thread: publish the call, then wait for the host's result, an abort,
/// or close. Several calls may wait at once; each settles by its own id.
fn executeHostTool(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !agent_loop.ToolOutput {
    const handle = (@as(*HostTool, @ptrCast(@alignCast(raw)))).handle;
    const io = handle.threaded.io();
    const key = try handle.allocator.dupe(u8, call.id);
    var key_owned = true;
    defer if (key_owned) handle.allocator.free(key);

    handle.approval_mutex.lockUncancelable(io);
    if (handle.closing) {
        handle.approval_mutex.unlock(io);
        return error.Canceled;
    }
    const generation = handle.host_abort_generation;
    const entry = handle.host_calls.getOrPut(handle.allocator, key) catch |err| {
        handle.approval_mutex.unlock(io);
        return err;
    };
    if (entry.found_existing) {
        handle.approval_mutex.unlock(io);
        return error.DuplicateToolCall;
    }
    entry.value_ptr.* = null;
    key_owned = false;
    handle.approval_mutex.unlock(io);

    const notification = std.json.Stringify.valueAlloc(allocator, .{
        .jsonrpc = "2.0",
        .method = "host_tool_request",
        .params = .{ .id = call.id, .name = call.name, .input = call.arguments },
    }, .{});
    const published = if (notification) |record| handle.output.writeAtomic(record) else |err| err;

    handle.approval_mutex.lockUncancelable(io);
    if (published) |_| {
        while (handle.host_calls.get(call.id).? == null and !handle.closing and generation == handle.host_abort_generation) {
            handle.approval_condition.waitUncancelable(io, &handle.approval_mutex);
        }
    } else |_| {}
    const settled = handle.host_calls.fetchRemove(call.id).?;
    handle.approval_mutex.unlock(io);
    handle.allocator.free(settled.key);
    try published;
    const result = settled.value orelse return error.Canceled;
    defer handle.allocator.free(result.content);
    const content = try allocator.alloc(protocol.UserContent, 1);
    content[0] = .{ .text = .{ .text = try allocator.dupe(u8, result.content) } };
    return .{ .content = content, .is_error = result.is_error };
}

fn settleHostTool(handle: *Handle, record: std.json.ObjectMap, id: std.json.Value) Status {
    const content = record.get("content") orelse return .invalid_argument;
    const is_error = record.get("is_error") orelse return .invalid_argument;
    if (id != .string or content != .string or is_error != .bool) return .invalid_argument;
    const io = handle.threaded.io();
    handle.approval_mutex.lockUncancelable(io);
    defer handle.approval_mutex.unlock(io);
    const pending = handle.host_calls.getPtr(id.string) orelse return .invalid_argument;
    if (pending.* != null) return .invalid_argument;
    pending.* = .{
        .content = handle.allocator.dupe(u8, content.string) catch return .out_of_memory,
        .is_error = is_error.bool,
    };
    handle.approval_condition.broadcast(io);
    return .ok;
}

fn clearPendingApproval(handle: *Handle) void {
    handle.approval_mutex.lockUncancelable(handle.threaded.io());
    if (handle.pending_approval_id) |id| handle.allocator.free(id);
    handle.pending_approval_id = null;
    handle.pending_approval_decision = null;
    handle.cancel_pending_approval = false;
    handle.approval_mutex.unlock(handle.threaded.io());
}

fn cancelPendingApproval(handle: *Handle) void {
    handle.approval_mutex.lockUncancelable(handle.threaded.io());
    handle.cancel_pending_approval = true;
    handle.host_abort_generation +%= 1;
    handle.approval_condition.broadcast(handle.threaded.io());
    handle.approval_mutex.unlock(handle.threaded.io());
}

fn enqueueRecord(raw: *anyopaque, bytes: []const u8) !void {
    const handle: *Handle = @ptrCast(@alignCast(raw));
    if (bytes.len == 0 or bytes.len > max_record_bytes) return error.InvalidRecord;
    if (handle.events.items.len >= max_queued_records or bytes.len > max_queued_bytes - handle.queued_event_bytes) {
        return error.Backpressure;
    }
    const owned = try handle.allocator.dupe(u8, bytes);
    errdefer handle.allocator.free(owned);
    try handle.events.append(handle.allocator, owned);
    handle.queued_event_bytes += owned.len;
}

fn isAsynchronousRequest(allocator: std.mem.Allocator, bytes: []const u8) bool {
    var parsed = std.json.parseFromSlice(std.json.Value, allocator, bytes, .{}) catch return false;
    defer parsed.deinit();
    if (parsed.value != .object) return false;
    const method = parsed.value.object.get("method") orelse return false;
    if (method != .string) return false;
    return std.mem.eql(u8, method.string, "session/prompt") or
        std.mem.eql(u8, method.string, "session/abort") or
        std.mem.eql(u8, method.string, "system_use/wait") or
        std.mem.eql(u8, method.string, "system_use/cancel");
}

fn isAbortRequest(allocator: std.mem.Allocator, bytes: []const u8) bool {
    var parsed = std.json.parseFromSlice(std.json.Value, allocator, bytes, .{}) catch return false;
    defer parsed.deinit();
    if (parsed.value != .object) return false;
    const method = parsed.value.object.get("method") orelse return false;
    return method == .string and std.mem.eql(u8, method.string, "session/abort");
}

fn takeQueuedRecord(handle: *Handle, result: *Buffer) Status {
    handle.output.mutex.lockUncancelable(handle.threaded.io());
    defer handle.output.mutex.unlock(handle.threaded.io());
    if (handle.events.items.len == 0) return .ok;
    const owned = handle.events.orderedRemove(0);
    handle.queued_event_bytes -= owned.len;
    handle.buffers.put(@intFromPtr(owned.ptr), owned.len) catch {
        handle.allocator.free(owned);
        return .out_of_memory;
    };
    result.* = .{ .data = owned.ptr, .len = owned.len };
    return .ok;
}

fn statusForError(err: anyerror) Status {
    return switch (err) {
        error.OutOfMemory => .out_of_memory,
        error.InvalidConfig => .invalid_config,
        else => .runtime_error,
    };
}

fn testConfig(allocator: std.mem.Allocator, workspace: []const u8, state: []const u8) ![]u8 {
    return std.fmt.allocPrint(allocator,
        \\{{"workspace_root":"{s}","state_root":"{s}","system_prompt":"test","default_selection":{{"provider":"test-provider","model":"test-model","context_window":4096,"output_limit":256}},"model_profiles":[{{"id":"test-model","name":"Test model","provider":"test-provider","context_window":4096,"output_limit":256,"max_output_tokens":512}}],"provider_configs":[{{"id":"test-provider","endpoint":"http://127.0.0.1:1/v1","credential_ref":"TEST_KEY","models":[{{"id":"test-model","context_window":4096,"output_limit":512}}],"retry":{{"deadline_ms":1000}}}}]}}
    , .{ workspace, state });
}

test "Android ABI opens the shared runtime and serves handshake and model list" {
    var workspace_tmp = std.testing.tmpDir(.{});
    defer workspace_tmp.cleanup();
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var workspace_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const workspace = workspace_buffer[0..try workspace_tmp.dir.realPath(std.testing.io, &workspace_buffer)];
    const state = state_buffer[0..try state_tmp.dir.realPath(std.testing.io, &state_buffer)];
    const config = try testConfig(std.testing.allocator, workspace, state);
    defer std.testing.allocator.free(config);

    var handle: ?*Handle = try openWithAllocator(std.testing.allocator, config);
    defer _ = misul_android_close(&handle);
    try std.testing.expectEqual(abi_version, misul_android_abi_version());

    var response = Buffer{};
    const handshake = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc/handshake\",\"params\":{\"protocol_major\":1,\"protocol_minor\":0}}";
    try std.testing.expectEqual(Status.ok, misul_android_request(handle, handshake.ptr, handshake.len, &response));
    defer _ = misul_android_buffer_free(handle, &response);
    try std.testing.expect(std.mem.indexOf(u8, response.data.?[0..response.len], "\"major\":1") != null);
    try std.testing.expectEqual(Status.ok, misul_android_buffer_free(handle, &response));

    const list = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"model/list\",\"params\":{}}";
    try std.testing.expectEqual(Status.ok, misul_android_request(handle, list.ptr, list.len, &response));
    try std.testing.expect(std.mem.indexOf(u8, response.data.?[0..response.len], "\"id\":\"test-model\"") != null);
    try std.testing.expectEqual(Status.ok, misul_android_buffer_free(handle, &response));
    try std.testing.expectEqual(Status.ok, misul_android_close(&handle));
    try std.testing.expect(handle == null);
    try std.testing.expectEqual(Status.ok, misul_android_close(&handle));
}

test "Android ABI accepts the PhoneCode controller configuration shape" {
    var workspace_tmp = std.testing.tmpDir(.{});
    defer workspace_tmp.cleanup();
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var workspace_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const workspace = workspace_buffer[0..try workspace_tmp.dir.realPath(std.testing.io, &workspace_buffer)];
    const state = state_buffer[0..try state_tmp.dir.realPath(std.testing.io, &state_buffer)];
    const config = try std.fmt.allocPrint(std.testing.allocator,
        \\{{"workspace_root":"{s}","state_root":"{s}","system_prompt":"PhoneCode","default_selection":{{"provider":"p","model":"m","context_window":4096,"output_limit":256}},"model_profiles":[{{"id":"m","name":"Model","provider":"p","context_window":4096,"output_limit":256,"max_output_tokens":256}}],"provider_configs":[{{"id":"p","endpoint":"http://127.0.0.1:1/v1","credential_ref":"fixture-token","credential_source":"explicit","dialect":"openai_chat","headers":[],"models":[{{"id":"m","context_window":4096,"output_limit":256,"reasoning":false,"tool_call":true}}],"retry":{{"max_retries":2,"deadline_ms":120000}}}}],"maximum_active_runs":1,"max_steps":32}}
    , .{ workspace, state });
    defer std.testing.allocator.free(config);

    var handle: ?*Handle = try openWithAllocator(std.testing.allocator, config);
    try std.testing.expectEqual(Status.ok, misul_android_close(&handle));
}

test "Android ABI rejects hostile input and tracks buffer ownership" {
    var unused_handle: ?*Handle = null;
    const malformed_config = "{}";
    try std.testing.expectEqual(Status.invalid_argument, misul_android_open(null, 1, null));
    try std.testing.expectEqual(Status.invalid_argument, misul_android_open(malformed_config.ptr, max_config_bytes + 1, &unused_handle));
    try std.testing.expectEqual(Status.invalid_config, misul_android_open(malformed_config.ptr, malformed_config.len, &unused_handle));
    const invalid_utf8 = [_]u8{0xff};
    try std.testing.expectEqual(Status.invalid_config, misul_android_open(&invalid_utf8, invalid_utf8.len, &unused_handle));
    try std.testing.expect(unused_handle == null);
    try std.testing.expectEqual(Status.invalid_argument, misul_android_request(null, null, 0, null));
    try std.testing.expectEqual(Status.invalid_argument, misul_android_close(null));

    var workspace_tmp = std.testing.tmpDir(.{});
    defer workspace_tmp.cleanup();
    var first_state_tmp = std.testing.tmpDir(.{});
    defer first_state_tmp.cleanup();
    var second_state_tmp = std.testing.tmpDir(.{});
    defer second_state_tmp.cleanup();
    var workspace_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    var first_state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    var second_state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const workspace = workspace_buffer[0..try workspace_tmp.dir.realPath(std.testing.io, &workspace_buffer)];
    const first_state = first_state_buffer[0..try first_state_tmp.dir.realPath(std.testing.io, &first_state_buffer)];
    const second_state = second_state_buffer[0..try second_state_tmp.dir.realPath(std.testing.io, &second_state_buffer)];
    const first_config = try testConfig(std.testing.allocator, workspace, first_state);
    defer std.testing.allocator.free(first_config);
    const second_config = try testConfig(std.testing.allocator, workspace, second_state);
    defer std.testing.allocator.free(second_config);
    var first: ?*Handle = try openWithAllocator(std.testing.allocator, first_config);
    defer _ = misul_android_close(&first);
    var second: ?*Handle = try openWithAllocator(std.testing.allocator, second_config);
    defer _ = misul_android_close(&second);

    var response = Buffer{};
    const oversized = "x";
    try std.testing.expectEqual(Status.invalid_argument, misul_android_request(first, oversized.ptr, max_record_bytes + 1, &response));
    try std.testing.expectEqual(Status.invalid_argument, misul_android_request(first, &invalid_utf8, invalid_utf8.len, &response));
    const malformed_record = "{";
    try std.testing.expectEqual(Status.ok, misul_android_request(first, malformed_record.ptr, malformed_record.len, &response));
    try std.testing.expect(std.mem.indexOf(u8, response.data.?[0..response.len], "-32700") != null);
    try std.testing.expectEqual(Status.wrong_owner, misul_android_buffer_free(second, &response));
    response.len += 1;
    try std.testing.expectEqual(Status.wrong_owner, misul_android_buffer_free(first, &response));
    response.len -= 1;
    try std.testing.expectEqual(Status.ok, misul_android_buffer_free(first, &response));
    try std.testing.expectEqual(Status.ok, misul_android_buffer_free(first, &response));
    try std.testing.expectEqual(Status.ok, misul_android_next_event(first, 0, &response));
    try std.testing.expect(response.data == null and response.len == 0);
    try std.testing.expectEqual(Status.invalid_argument, misul_android_host_response(first, malformed_record.ptr, malformed_record.len));
}

test "Android ABI submits prompts asynchronously and drains runtime records" {
    var workspace_tmp = std.testing.tmpDir(.{});
    defer workspace_tmp.cleanup();
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var workspace_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const workspace = workspace_buffer[0..try workspace_tmp.dir.realPath(std.testing.io, &workspace_buffer)];
    const state = state_buffer[0..try state_tmp.dir.realPath(std.testing.io, &state_buffer)];
    const config = try testConfig(std.testing.allocator, workspace, state);
    defer std.testing.allocator.free(config);

    var handle: ?*Handle = try openWithAllocator(std.testing.allocator, config);
    defer _ = misul_android_close(&handle);
    var response = Buffer{};
    const handshake = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc/handshake\",\"params\":{\"protocol_major\":1,\"protocol_minor\":0}}";
    try std.testing.expectEqual(Status.ok, misul_android_request(handle, handshake.ptr, handshake.len, &response));
    try std.testing.expect(response.len != 0);
    try std.testing.expectEqual(Status.ok, misul_android_buffer_free(handle, &response));

    const prompt = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"exercise async Android transport\"}}";
    try std.testing.expectEqual(Status.ok, misul_android_request(handle, prompt.ptr, prompt.len, &response));
    try std.testing.expect(response.data == null and response.len == 0);
    const abort = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/abort\",\"params\":{}}";
    try std.testing.expectEqual(Status.ok, misul_android_request(handle, abort.ptr, abort.len, &response));
    try std.testing.expect(response.data == null and response.len == 0);

    var saw_start = false;
    var saw_terminal_response = false;
    var saw_abort_response = false;
    for (0..16) |_| {
        try std.testing.expectEqual(Status.ok, misul_android_next_event(handle, 2_000, &response));
        if (response.data == null) continue;
        const record = response.data.?[0..response.len];
        saw_start = saw_start or std.mem.indexOf(u8, record, "\"method\":\"agent_start\"") != null;
        saw_terminal_response = saw_terminal_response or std.mem.indexOf(u8, record, "\"id\":2") != null;
        saw_abort_response = saw_abort_response or std.mem.indexOf(u8, record, "\"id\":3") != null;
        try std.testing.expectEqual(Status.ok, misul_android_buffer_free(handle, &response));
        if (saw_start and saw_terminal_response and saw_abort_response) break;
    }
    try std.testing.expect(saw_start);
    try std.testing.expect(saw_terminal_response);
    try std.testing.expect(saw_abort_response);
    try std.testing.expectEqual(Status.ok, misul_android_next_event(handle, 0, &response));
    try std.testing.expect(response.data == null and response.len == 0);
}

test "Android ABI preserves allocation failure" {
    var failing = std.testing.FailingAllocator.init(std.testing.allocator, .{ .fail_index = 0 });
    try std.testing.expectError(error.OutOfMemory, openWithAllocator(failing.allocator(), "{}"));
}

const HostCallProbe = struct {
    handle: *Handle,
    call: protocol.ToolCall,
    arena: std.heap.ArenaAllocator,
    output: ?agent_loop.ToolOutput = null,
    failure: ?anyerror = null,

    fn run(probe: *HostCallProbe) void {
        var tool = HostTool{ .handle = probe.handle };
        probe.output = executeHostTool(&tool, &probe.call, probe.arena.allocator()) catch |err| {
            probe.failure = err;
            return;
        };
    }
};

fn awaitHostRequest(handle: ?*Handle, id: []const u8) !void {
    var response = Buffer{};
    for (0..200) |_| {
        try std.testing.expectEqual(Status.ok, misul_android_next_event(handle, 50, &response));
        if (response.data == null) continue;
        defer _ = misul_android_buffer_free(handle, &response);
        const record = response.data.?[0..response.len];
        if (std.mem.indexOf(u8, record, "\"method\":\"host_tool_request\"") != null and std.mem.indexOf(u8, record, id) != null) return;
    }
    return error.HostRequestMissing;
}

test "Android ABI exposes host tools and settles their calls by id" {
    var workspace_tmp = std.testing.tmpDir(.{});
    defer workspace_tmp.cleanup();
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var workspace_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const workspace = workspace_buffer[0..try workspace_tmp.dir.realPath(std.testing.io, &workspace_buffer)];
    const state = state_buffer[0..try state_tmp.dir.realPath(std.testing.io, &state_buffer)];
    const base = try testConfig(std.testing.allocator, workspace, state);
    defer std.testing.allocator.free(base);
    const config = try std.mem.concat(std.testing.allocator, u8, &.{
        base[0 .. base.len - 1],
        \\,"host_tools":[{"name":"host_echo","description":"Echo on the host","parameters":{"type":"object","properties":{"text":{"type":"string"}}},"mutating":true}],"disabled_tools":["write_file"],"approval_tools":["read_file"]}
    });
    defer std.testing.allocator.free(config);

    var handle: ?*Handle = try openWithAllocator(std.testing.allocator, config);
    defer _ = misul_android_close(&handle);
    const runtime = handle.?.runtime;
    try std.testing.expect(runtime.tool_stack.has("host_echo"));
    try std.testing.expect(!runtime.tool_stack.has("write_file"));
    try std.testing.expect(runtime.tool_stack.has("read_file"));

    var probe = HostCallProbe{
        .handle = handle.?,
        .call = .{ .id = "call-host", .name = "host_echo", .arguments = .null },
        .arena = std.heap.ArenaAllocator.init(std.testing.allocator),
    };
    defer probe.arena.deinit();
    const thread = try std.Thread.spawn(.{}, HostCallProbe.run, .{&probe});
    try awaitHostRequest(handle, "call-host");
    const unknown = "{\"type\":\"tool_result\",\"id\":\"other\",\"content\":\"x\",\"is_error\":false}";
    try std.testing.expectEqual(Status.invalid_argument, misul_android_host_response(handle, unknown.ptr, unknown.len));
    const settle = "{\"type\":\"tool_result\",\"id\":\"call-host\",\"content\":\"echoed on host\",\"is_error\":true}";
    try std.testing.expectEqual(Status.ok, misul_android_host_response(handle, settle.ptr, settle.len));
    thread.join();
    try std.testing.expect(probe.failure == null);
    try std.testing.expect(probe.output.?.is_error);
    try std.testing.expectEqualStrings("echoed on host", probe.output.?.content[0].text.text);

    var aborted = HostCallProbe{
        .handle = handle.?,
        .call = .{ .id = "call-aborted", .name = "host_echo", .arguments = .null },
        .arena = std.heap.ArenaAllocator.init(std.testing.allocator),
    };
    defer aborted.arena.deinit();
    const waiting = try std.Thread.spawn(.{}, HostCallProbe.run, .{&aborted});
    try awaitHostRequest(handle, "call-aborted");
    cancelPendingApproval(handle.?);
    waiting.join();
    try std.testing.expectEqual(@as(?anyerror, error.Canceled), aborted.failure);
}
