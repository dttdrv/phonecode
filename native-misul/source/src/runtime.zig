const std = @import("std");
const builtin = @import("builtin");
const protocol = @import("protocol.zig");
const openai_compatible = @import("openai_compatible.zig");
const skills = @import("skills.zig");
const skill_tool = @import("skill_tool.zig");
const builtin_tools = @import("builtin_tools.zig");
const harness_module = @import("harness.zig");
const cordis = @import("cordis.zig");
const agent_loop = @import("agent_loop.zig");
const causal_runtime = @import("causal_runtime.zig");
const event_store = @import("event_store.zig");
const action_receipt = @import("action_receipt.zig");
const browser = @import("browser.zig");
const permission_broker = @import("permission_broker.zig");
const instruction_registry = @import("instruction_registry.zig");
const session = @import("session.zig");
const conversation = @import("conversation.zig");
const cache_telemetry = @import("cache_telemetry.zig");
const model_catalog = @import("model_catalog.zig");

const NativeHarness = harness_module.Harness(void);
const RunFuture = std.Io.Future(anyerror!void);
const PublisherFuture = std.Io.Future(anyerror!void);
const completed_run_limit = 64;
const default_publication_capacity = 128;
const default_subscription_capacity = 256;
const maximum_publication_capacity = 4096;
const maximum_subscription_capacity = 4096;
const maximum_subscriptions = 64;
const event_log_name = "misul.events";
const maximum_system_use_record_bytes = 1024 * 1024;
const system_use_evidence_capacity = 512;
const system_use_timestamp_placeholder: u64 = 9_999_999_999_999_999_999;

pub const SystemUseResult = struct {
    allocator: std.mem.Allocator,
    json: []u8,
    duration_us: u64 = 0,

    pub fn deinit(self: *SystemUseResult) void {
        self.allocator.free(self.json);
        self.* = undefined;
    }
};

pub const SystemUseWaitRequest = struct {
    target_json: []const u8,
    wait_id: ?u64 = null,
    text: ?[]const u8 = null,
    element_id: ?[]const u8 = null,
    present: bool = true,
    timeout_ms: u64 = 5_000,
    poll_interval_ms: u64 = 50,
    external_cancel: ?*const std.atomic.Value(bool) = null,
    install_notice: ?*std.atomic.Value(bool) = null,
};

pub const SystemUseRequestContext = struct {
    source_request_id: []const u8,
    causal_request_id: []const u8,
    run_id: []const u8,
    session_id: []const u8 = "",
    actor_id: []const u8,
    provenance: []const u8,
};

const GovernedAction = struct {
    allocator: std.mem.Allocator,
    runtime: *Runtime,
    action_id: []u8,
    causal_request_id: []u8,
    source_request_id: []u8,
    actor_id: []u8,
    decision_id: []u8,
    lease_id: []u8,
    descriptor_hash: action_receipt.Digest,
    requested_at_ms: u64,
    decided_at_ms: u64,
    dispatch_begun_at_ms: u64,
    prepared_settlements: PreparedSettlements,
    settled: bool = false,

    fn deinit(self: *GovernedAction) void {
        self.prepared_settlements.deinit(self.runtime);
        self.allocator.free(self.lease_id);
        self.allocator.free(self.decision_id);
        self.allocator.free(self.source_request_id);
        self.allocator.free(self.actor_id);
        self.allocator.free(self.causal_request_id);
        self.allocator.free(self.action_id);
        self.* = undefined;
    }
};

const settlement_statuses = [_]action_receipt.Status{
    .provenance_failure,
    .authorization_failure,
    .policy_denial,
    .cancelled,
    .timeout,
    .external_failure,
    .success,
    .outcome_unknown,
};

const PreparedSettlement = struct {
    status: action_receipt.Status,
    append: event_store.PreparedAppend,
    node: ?*PublishedNode,
    settled_at_offset: usize,
    evidence_offset: usize,
};

const PreparedSettlements = struct {
    items: [settlement_statuses.len]PreparedSettlement,
    publication_reserved: bool = false,

    fn deinit(self: *PreparedSettlements, runtime: *Runtime) void {
        for (&self.items) |*item| {
            if (item.append != .invalid) runtime.event_store.discardPrepared(&item.append);
            if (item.node) |node| runtime.allocator.destroy(node);
            item.node = null;
        }
        if (self.publication_reserved) runtime.releasePublicationReservationLocked();
        self.publication_reserved = false;
    }
};

const SystemUseFailureEvidence = struct {
    sidecar_error_hash: ?action_receipt.Digest = null,
};

const SystemUseService = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    environ: *const std.process.Environ.Map,
    binary: []u8,
    mutex: std.Io.Mutex = .init,
    child: ?std.process.Child = null,
    next_request_id: u64 = 1,

    fn init(
        allocator: std.mem.Allocator,
        io: std.Io,
        environ: *const std.process.Environ.Map,
        binary: []const u8,
    ) !SystemUseService {
        if (binary.len == 0) return error.InvalidSystemUseBinary;
        return .{
            .allocator = allocator,
            .io = io,
            .environ = environ,
            .binary = try allocator.dupe(u8, binary),
        };
    }

    fn deinit(self: *SystemUseService) void {
        self.mutex.lockUncancelable(self.io);
        self.stopLocked();
        self.mutex.unlock(self.io);
        self.allocator.free(self.binary);
        self.* = undefined;
    }

    fn stopLocked(self: *SystemUseService) void {
        if (self.child) |*child| child.kill(self.io);
        self.child = null;
    }

    fn retire(self: *SystemUseService) void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        self.stopLocked();
    }

    fn ensureStartedLocked(self: *SystemUseService) !void {
        if (self.child != null) return;
        self.child = try std.process.spawn(self.io, .{
            .argv = &.{self.binary},
            .environ_map = self.environ,
            .stdin = .pipe,
            .stdout = .pipe,
            .stderr = .ignore,
        });
        errdefer self.stopLocked();
        var failure: SystemUseFailureEvidence = .{};
        var duration_us: u64 = 0;
        var ping = try self.callLocked("ping", "{}", &failure, &duration_us);
        defer ping.deinit();
        const version = ping.value.object.get("version") orelse return error.InvalidSystemUseHandshake;
        if (version != .integer or version.integer != 1) return error.InvalidSystemUseHandshake;
    }

    const WatchdogResult = enum { timeout, cancelled };
    const RequestRace = union(enum) {
        response: anyerror!SystemUseResult,
        watchdog: WatchdogResult,
    };

    fn request(self: *SystemUseService, method: []const u8, params_json: []const u8, failure: *SystemUseFailureEvidence) !SystemUseResult {
        return self.requestControlled(method, params_json, 10_000, null, failure);
    }

    fn requestControlled(self: *SystemUseService, method: []const u8, params_json: []const u8, timeout_ms: u64, cancel: ?*const std.atomic.Value(bool), failure: *SystemUseFailureEvidence) !SystemUseResult {
        var results: [2]RequestRace = undefined;
        var select = std.Io.Select(RequestRace).init(self.io, &results);
        failure.* = .{};
        try select.concurrent(.response, requestTask, .{ self, method, params_json, failure });
        try select.concurrent(.watchdog, requestWatchdog, .{ self.io, timeout_ms, cancel });
        const first = try select.await();
        switch (first) {
            .response => |response| {
                select.cancelDiscard();
                return response;
            },
            .watchdog => |reason| {
                while (select.cancel()) |pending| switch (pending) {
                    .response => |response| {
                        var mutable_response = response;
                        if (mutable_response) |*value| value.deinit() else |_| {}
                    },
                    .watchdog => {},
                };
                return switch (reason) {
                    .timeout => error.SystemUseTimeout,
                    .cancelled => error.Canceled,
                };
            },
        }
    }

    fn requestTask(self: *SystemUseService, method: []const u8, params_json: []const u8, failure: *SystemUseFailureEvidence) anyerror!SystemUseResult {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        try self.ensureStartedLocked();
        var duration_us: u64 = 0;
        var parsed = self.callLocked(method, params_json, failure, &duration_us) catch |err| {
            self.stopLocked();
            return err;
        };
        defer parsed.deinit();
        return .{
            .allocator = self.allocator,
            .json = try std.json.Stringify.valueAlloc(self.allocator, parsed.value, .{}),
            .duration_us = duration_us,
        };
    }

    fn requestWatchdog(io: std.Io, timeout_ms: u64, cancel: ?*const std.atomic.Value(bool)) WatchdogResult {
        const deadline = std.Io.Clock.Timestamp.now(io, .awake).addDuration(.{ .clock = .awake, .raw = .fromMilliseconds(@intCast(timeout_ms)) });
        while (true) {
            if (cancel) |flag| if (flag.load(.acquire)) return .cancelled;
            if (std.Io.Clock.Timestamp.now(io, .awake).compare(.gte, deadline)) return .timeout;
            std.Io.sleep(io, .fromMilliseconds(5), .awake) catch return .cancelled;
        }
    }

    fn preflight(self: *SystemUseService) !void {
        var failure: SystemUseFailureEvidence = .{};
        var result = try self.request("permissions", "{}", &failure);
        defer result.deinit();
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, result.json, .{}) catch return error.InvalidSystemUseResponse;
        defer parsed.deinit();
        if (parsed.value != .object) return error.InvalidSystemUseResponse;
        const accessibility = parsed.value.object.get("accessibility") orelse return error.InvalidSystemUseResponse;
        const screen_recording = parsed.value.object.get("screenRecording") orelse return error.InvalidSystemUseResponse;
        if (accessibility != .bool or screen_recording != .bool) return error.InvalidSystemUseResponse;
        if (!accessibility.bool or !screen_recording.bool) return error.SystemUsePermissionUnavailable;
    }

    fn callLocked(self: *SystemUseService, method: []const u8, params_json: []const u8, failure: *SystemUseFailureEvidence, duration_us_out: *u64) !std.json.Parsed(std.json.Value) {
        if (params_json.len < 2 or params_json[0] != '{' or params_json[params_json.len - 1] != '}') return error.InvalidSystemUseParameters;
        const request_id = self.next_request_id;
        self.next_request_id +%= 1;
        if (self.next_request_id == 0) self.next_request_id = 1;
        const suffix = std.mem.trim(u8, params_json[1 .. params_json.len - 1], " \t\r\n");
        const record = if (suffix.len == 0)
            try std.fmt.allocPrint(self.allocator, "{{\"id\":{d},\"method\":{f}}}\n", .{ request_id, std.json.fmt(method, .{}) })
        else
            try std.fmt.allocPrint(self.allocator, "{{\"id\":{d},\"method\":{f},{s}}}\n", .{ request_id, std.json.fmt(method, .{}), suffix });
        defer self.allocator.free(record);
        if (record.len > maximum_system_use_record_bytes) return error.SystemUseRecordTooLarge;
        const child = &(self.child orelse return error.SystemUseUnavailable);
        child.stdin.?.writeStreamingAll(self.io, record) catch return error.SystemUseTransportFailed;

        var read_buffer: [4096]u8 = undefined;
        var reader = child.stdout.?.readerStreaming(self.io, &read_buffer);
        var output: std.Io.Writer.Allocating = .init(self.allocator);
        defer output.deinit();
        _ = reader.interface.streamDelimiterLimit(&output.writer, '\n', .limited(maximum_system_use_record_bytes)) catch |err| switch (err) {
            error.StreamTooLong => return error.SystemUseRecordTooLarge,
            else => return error.SystemUseTransportFailed,
        };
        if (reader.interface.bufferedLen() == 0 or reader.interface.buffered()[0] != '\n') return error.SystemUseTransportFailed;
        reader.interface.toss(1);
        var response = std.json.parseFromSlice(std.json.Value, self.allocator, output.written(), .{}) catch return error.InvalidSystemUseResponse;
        errdefer response.deinit();
        if (response.value != .object) return error.InvalidSystemUseResponse;
        const response_id = response.value.object.get("id") orelse return error.InvalidSystemUseResponse;
        if (response_id != .integer or response_id.integer < 0 or @as(u64, @intCast(response_id.integer)) != request_id) return error.InvalidSystemUseResponse;
        const ok = response.value.object.get("ok") orelse return error.InvalidSystemUseResponse;
        if (ok != .bool) return error.InvalidSystemUseResponse;
        const duration_us = response.value.object.get("duration_us") orelse return error.InvalidSystemUseResponse;
        if (duration_us != .integer or duration_us.integer < 0) return error.InvalidSystemUseResponse;
        duration_us_out.* = @intCast(duration_us.integer);
        if (!ok.bool) {
            const diagnostic = response.value.object.get("error") orelse return error.InvalidSystemUseResponse;
            if (diagnostic != .string and diagnostic != .object) return error.InvalidSystemUseResponse;
            var digest: action_receipt.Digest = undefined;
            std.crypto.hash.sha2.Sha256.hash(output.written(), &digest, .{});
            failure.sidecar_error_hash = digest;
            return error.SystemUseExternalFailure;
        }
        const result = response.value.object.get("result") orelse return error.InvalidSystemUseResponse;
        const owned = try cloneJsonValue(self.allocator, result);
        response.deinit();
        return owned;
    }
};

pub const maximum_session_list_items: usize = 100;
pub const maximum_session_history_items: usize = 256;
pub const maximum_session_history_payload_bytes: usize = 512 * 1024;

pub const PageOrder = enum { ascending, descending };

pub const RunId = u64;

pub const ModelSelection = protocol.ModelSelection;

pub const SessionSummary = struct {
    session_id: []u8,
    branch_id: []u8,
    title: ?[]u8,
    model: ?[]u8,
    created_at_ms: u64,
    updated_at_ms: u64,

    fn deinit(self: *SessionSummary, allocator: std.mem.Allocator) void {
        allocator.free(self.session_id);
        allocator.free(self.branch_id);
        if (self.title) |value| allocator.free(value);
        if (self.model) |value| allocator.free(value);
        self.* = undefined;
    }
};

pub const SessionList = struct {
    allocator: std.mem.Allocator,
    sessions: []SessionSummary,
    has_more: bool,

    pub fn deinit(self: *SessionList) void {
        for (self.sessions) |*item| item.deinit(self.allocator);
        self.allocator.free(self.sessions);
        self.* = undefined;
    }
};

pub const SessionHistory = struct {
    allocator: std.mem.Allocator,
    session_id: []u8,
    branch_id: []u8,
    page: session.DurableMessagePage,
    decoded: conversation.Loaded,

    pub fn deinit(self: *SessionHistory) void {
        self.decoded.deinit();
        self.page.deinit();
        self.allocator.free(self.branch_id);
        self.allocator.free(self.session_id);
        self.* = undefined;
    }
};

pub const Options = struct {
    workspace_root: []const u8,
    state_root: []const u8,
    system_prompt: []const u8,
    default_selection: ModelSelection,
    model_profiles: []const model_catalog.ModelProfile = &.{},
    provider_configs: []const openai_compatible.ProviderConfig = &.{},
    maximum_active_runs: usize = 8,
    max_steps: usize = 32,
    allow_mutating_tools: bool = true,
    approval_context: ?*anyopaque = null,
    approve_tool: ?ToolApprovalFn = null,
    /// Host-provided tools, added after the built-ins and replacing any with the same name. The
    /// caller keeps these and the name lists below alive for the runtime's lifetime.
    host_tools: []const harness_module.Tool = &.{},
    /// Host tools that mutate state and pass through the same approval as built-in mutations.
    mutating_tools: []const []const u8 = &.{},
    /// Host tools that must not overlap other calls. Mutating tools never overlap.
    serial_tools: []const []const u8 = &.{},
    /// Tools left out of every request.
    disabled_tools: []const []const u8 = &.{},
    /// Tools that always need approval, even when mutating tools are allowed.
    approval_tools: []const []const u8 = &.{},
    publication_capacity: usize = default_publication_capacity,
    subscription_capacity: usize = default_subscription_capacity,
    telemetry_context: ?*anyopaque = null,
    telemetry: ?agent_loop.TelemetrySink = null,
    telemetry_epoch: u64 = 0,
    telemetry_invalidation: cache_telemetry.InvalidationReason = .none,
};

pub const ToolApprovalFn = *const fn (
    context: *anyopaque,
    call: *const protocol.ToolCall,
    cancel_requested: *const std.atomic.Value(bool),
) anyerror!bool;

pub const PromptRequest = struct {
    session_id: []const u8,
    prompt: []const u8,
    input_id: ?[]const u8 = null,
    system_override: ?[]const u8 = null,
    event_context: ?*anyopaque = null,
    emit: ?EventSink = null,
};

pub const FailureCategory = enum { none, provider, canceled, max_steps, callback, internal };
pub const StopReason = enum { complete, terminated, max_steps, failed, canceled };

pub const TypedFailure = struct {
    category: FailureCategory,
    provider_failure: ?protocol.ProviderFailure = null,
};

pub const Settlement = struct {
    run_id: RunId,
    /// Borrowed from Runtime session state and valid until exclusive destroy.
    session_id: []const u8,
    stop_reason: StopReason,
    step_count: usize,
    usage: protocol.Usage,
    failure: FailureCategory = .none,
    /// Borrowed from Runtime. Provider diagnostics remain valid while this run
    /// is retained in Runtime's bounded idempotency window.
    provider_failure: ?protocol.ProviderFailure = null,
};

pub const EventPayload = union(enum) {
    run_started: struct {
        input_message_id: []const u8,
        /// Reserved durably by run_started and used by the first assistant
        /// message. A run canceled before output leaves a reservation, not a
        /// fabricated history message.
        assistant_message_id: []const u8,
    },
    provider_stream: protocol.StreamEvent,
    message_added: *const protocol.Message,
    tool_started: *const protocol.ToolCall,
    tool_finished: *const protocol.ToolResultMessage,
    compaction_observed: struct { tokens_before: usize, first_kept_index: usize },
    cancellation_requested,
    typed_failure: TypedFailure,
    settled: Settlement,
};

pub const Event = struct {
    run_id: RunId,
    session_id: []const u8,
    sequence: u64,
    payload: EventPayload,
};

pub const EventSink = *const fn (context: *anyopaque, event: Event) anyerror!void;

const SessionState = struct {
    projection: session.DurableProjection,
    title: ?[]u8 = null,

    fn deinit(self: *SessionState) void {
        if (self.title) |value| self.projection.allocator.free(value);
        self.projection.deinit();
        self.* = undefined;
    }
};

const OwnedRequest = struct {
    session_id: []u8,
    prompt: []u8,
    system_override: ?[]u8,
    event_context: ?*anyopaque,
    emit: ?EventSink,
};

const Run = struct {
    runtime: *Runtime,
    id: RunId,
    durable_id: []u8,
    input_message_id: []u8,
    assistant_message_id: []u8,
    assistant_message_id_pending: bool = true,
    request: OwnedRequest,
    selection: ModelSelection,
    provider_dialect: ?openai_compatible.Dialect,
    model: protocol.Model,
    causal: causal_runtime.Runtime,
    future: ?RunFuture = null,
    future_mutex: std.Io.Mutex = std.Io.Mutex.init,
    cancel_requested: std.atomic.Value(bool) = .init(false),
    finalizing: bool = false,
    canceling: bool = false,
    reaping: bool = false,
    sequence: u64 = 0,
    initial_message_events_remaining: usize = 0,
    latest_request_identity: ?cache_telemetry.RequestIdentity = null,
    durable_cancel_requested: bool = false,
    durable_failure_observed: bool = false,
    settlement: ?Settlement = null,
    terminal_error: ?anyerror = null,
    owned_provider_failure: ?protocol.ProviderFailure = null,
};

const CompletedRun = struct {
    settlement: Settlement,
};

const PublishedNode = struct {
    allocator: std.mem.Allocator,
    references: std.atomic.Value(usize) = .init(1),
    record: event_store.RecordRef,

    fn retain(self: *PublishedNode) void {
        _ = self.references.fetchAdd(1, .acq_rel);
    }

    fn release(self: *PublishedNode) void {
        if (self.references.fetchSub(1, .acq_rel) != 1) return;
        self.record.deinit(self.allocator);
        const allocator = self.allocator;
        self.* = undefined;
        allocator.destroy(self);
    }
};

pub const Subscription = struct {
    runtime: *Runtime,
    id: u64,
    ring: []?*PublishedNode,
    head: usize = 0,
    count: usize = 0,
    active: bool = true,
    consumer_active: bool = false,

    fn enqueueAssumeCapacity(self: *Subscription, node: *PublishedNode) void {
        std.debug.assert(self.count < self.ring.len);
        const index = (self.head + self.count) % self.ring.len;
        node.retain();
        self.ring[index] = node;
        self.count += 1;
    }

    fn pop(self: *Subscription) *PublishedNode {
        std.debug.assert(self.count != 0);
        const node = self.ring[self.head].?;
        self.ring[self.head] = null;
        self.head = (self.head + 1) % self.ring.len;
        self.count -= 1;
        return node;
    }

    fn drain(self: *Subscription) void {
        while (self.count != 0) self.pop().release();
    }
};

const Publisher = struct {
    mutex: std.Io.Mutex = .init,
    condition: std.Io.Condition = .init,
    queue: []?*PublishedNode,
    head: usize = 0,
    count: usize = 0,
    reserved: usize = 0,
    closing: bool = false,
    high_water: u64 = 0,
    next_subscription_id: u64 = 1,
    subscriptions: std.ArrayList(*Subscription) = .empty,
    future: ?PublisherFuture = null,
};

pub const DurableSink = event_store.Visitor;

pub const Runtime = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    environ: *const std.process.Environ.Map,
    mutex: std.Io.Mutex = std.Io.Mutex.init,
    writer_mutex: std.Io.Mutex = std.Io.Mutex.init,
    condition: std.Io.Condition = std.Io.Condition.init,
    closing: bool = false,
    next_run_id: RunId = 1,
    next_durable_id: u64 = 1,
    epoch_id: [32]u8,
    maximum_active_runs: usize,
    subscription_capacity: usize,
    workspace_root: []u8,
    state_root: []u8,
    system_prompt: []u8,
    default_selection: ModelSelection,
    model_profiles: model_catalog.ProfileRegistry,
    provider_registry: ?openai_compatible.ProviderRegistry,
    max_steps: usize,
    allow_mutating_tools: bool,
    approval_context: ?*anyopaque,
    approve_tool: ?ToolApprovalFn,
    mutating_tools: []const []const u8,
    serial_tools: []const []const u8,
    approval_tools: []const []const u8,
    telemetry_context: ?*anyopaque,
    telemetry: ?agent_loop.TelemetrySink,
    telemetry_mutex: std.Io.Mutex = std.Io.Mutex.init,
    telemetry_epoch: u64,
    telemetry_invalidation: cache_telemetry.InvalidationReason,
    cwd_prompt: []u8,
    skill_catalog_prompt: ?[]u8,
    prompt_stack: NativeHarness.PromptStack,
    skill_catalog: skills.Catalog,
    builtin_registry: builtin_tools.Registry,
    skill_runtime: skill_tool.Runtime,
    tool_stack: harness_module.ToolStack,
    context: cordis.Context,
    services: NativeHarness.Services,
    harness: NativeHarness,
    test_stream_context: ?*anyopaque,
    test_stream: ?protocol.StreamFn,
    event_store: event_store.Store,
    instructions: instruction_registry.Registry,
    permission_broker: permission_broker.Broker,
    system_use: ?SystemUseService,
    browser: browser.Browser,
    system_use_ready: bool = false,
    system_use_wait_mutex: std.Io.Mutex = .init,
    system_use_wait_cancel: std.atomic.Value(bool) = .init(false),
    active_system_use_wait: ?u64 = null,
    next_system_use_wait: u64 = 1,
    publisher: Publisher,
    sessions: std.StringHashMap(*SessionState),
    active_sessions: std.StringHashMap(RunId),
    runs: std.ArrayList(*Run) = .empty,
    completed_runs: std.ArrayList(CompletedRun) = .empty,

    pub fn create(
        allocator: std.mem.Allocator,
        io: std.Io,
        environ: *const std.process.Environ.Map,
        options: Options,
    ) !*Runtime {
        return createInternal(allocator, io, environ, options, null, null);
    }

    pub fn createForTest(
        allocator: std.mem.Allocator,
        io: std.Io,
        environ: *const std.process.Environ.Map,
        options: Options,
        stream_context: *anyopaque,
        stream: protocol.StreamFn,
    ) !*Runtime {
        if (!builtin.is_test) return error.TestOnly;
        return createInternal(allocator, io, environ, options, stream_context, stream);
    }

    fn createInternal(
        allocator: std.mem.Allocator,
        io: std.Io,
        environ: *const std.process.Environ.Map,
        options: Options,
        test_stream_context: ?*anyopaque,
        test_stream: ?protocol.StreamFn,
    ) !*Runtime {
        if (options.maximum_active_runs == 0) return error.InvalidMaximumActiveRuns;
        if (options.publication_capacity == 0 or options.publication_capacity > maximum_publication_capacity) return error.InvalidPublicationCapacity;
        if (options.subscription_capacity == 0 or options.subscription_capacity > maximum_subscription_capacity) return error.InvalidSubscriptionCapacity;
        if (options.workspace_root.len == 0 or options.state_root.len == 0 or options.system_prompt.len == 0) return error.InvalidRuntimeOptions;
        const self = try allocator.create(Runtime);
        errdefer allocator.destroy(self);

        const workspace_root = try allocator.dupe(u8, options.workspace_root);
        errdefer allocator.free(workspace_root);
        const state_root = try allocator.dupe(u8, options.state_root);
        errdefer allocator.free(state_root);
        const system_prompt = try allocator.dupe(u8, options.system_prompt);
        errdefer allocator.free(system_prompt);
        const default_selection = try cloneSelection(allocator, options.default_selection);
        errdefer deinitSelection(allocator, default_selection);
        const test_profile = model_catalog.ModelProfile{
            .id = options.default_selection.model,
            .name = options.default_selection.model,
            .provider = options.default_selection.provider,
            .context_window = options.default_selection.context_window,
            .output_limit = options.default_selection.output_limit,
            .max_output_tokens = options.default_selection.output_limit,
            .reasoning = options.default_selection.reasoning,
            .cache_retention = options.default_selection.cache_retention,
            .cache_aggressiveness = options.default_selection.cache_aggressiveness,
            .transport = options.default_selection.transport,
        };
        const configured_profiles = if (options.model_profiles.len != 0)
            options.model_profiles
        else if (test_stream != null)
            @as([]const model_catalog.ModelProfile, &.{test_profile})
        else
            return error.EmptyModelProfileRegistry;
        var model_profiles = try model_catalog.ProfileRegistry.init(allocator, configured_profiles);
        errdefer model_profiles.deinit();
        try validateSelection(&model_profiles, options.default_selection);
        var provider_registry: ?openai_compatible.ProviderRegistry = if (test_stream == null)
            try openai_compatible.ProviderRegistry.init(allocator, io, environ, options.provider_configs)
        else
            null;
        errdefer if (provider_registry) |*registry| registry.deinit();
        if (provider_registry) |*registry| try validateProviderConfiguration(&model_profiles, registry);
        const cwd_prompt = try std.fmt.allocPrint(allocator, "Current working directory: {s}", .{workspace_root});
        errdefer allocator.free(cwd_prompt);
        try std.Io.Dir.cwd().createDirPath(io, state_root);
        const event_path = try std.fs.path.join(allocator, &.{ state_root, event_log_name });
        defer allocator.free(event_path);
        var opened = try event_store.Store.open(allocator, io, event_path, .{});
        var store_moved = false;
        errdefer if (!store_moved) opened.close();
        const publication_queue = try allocator.alloc(?*PublishedNode, options.publication_capacity);
        errdefer allocator.free(publication_queue);
        @memset(publication_queue, null);
        var epoch_entropy: [16]u8 = undefined;
        io.random(&epoch_entropy);
        const epoch_id = std.fmt.bytesToHex(epoch_entropy, .lower);
        var system_use: ?SystemUseService = null;
        if (environ.get("MISUL_SYSTEM_USE_BINARY")) |binary| {
            system_use = try SystemUseService.init(allocator, io, environ, binary);
        }
        errdefer if (system_use) |*service| service.deinit();

        self.* = .{
            .allocator = allocator,
            .io = io,
            .environ = environ,
            .maximum_active_runs = options.maximum_active_runs,
            .subscription_capacity = options.subscription_capacity,
            .epoch_id = epoch_id,
            .max_steps = options.max_steps,
            .allow_mutating_tools = options.allow_mutating_tools,
            .approval_context = options.approval_context,
            .approve_tool = options.approve_tool,
            .mutating_tools = options.mutating_tools,
            .serial_tools = options.serial_tools,
            .approval_tools = options.approval_tools,
            .telemetry_context = options.telemetry_context,
            .telemetry = options.telemetry,
            .telemetry_epoch = options.telemetry_epoch,
            .telemetry_invalidation = options.telemetry_invalidation,
            .workspace_root = workspace_root,
            .state_root = state_root,
            .system_prompt = system_prompt,
            .default_selection = default_selection,
            .model_profiles = model_profiles,
            .provider_registry = provider_registry,
            .cwd_prompt = cwd_prompt,
            .skill_catalog_prompt = null,
            .prompt_stack = NativeHarness.PromptStack.init(allocator),
            .skill_catalog = skills.Catalog.init(allocator),
            .builtin_registry = undefined,
            .skill_runtime = undefined,
            .tool_stack = harness_module.ToolStack.init(allocator),
            .context = cordis.Context.init(allocator, null),
            .services = undefined,
            .harness = undefined,
            .test_stream_context = test_stream_context,
            .test_stream = test_stream,
            .event_store = opened.store,
            .instructions = instruction_registry.Registry.init(allocator),
            .permission_broker = permission_broker.Broker.init(allocator),
            .system_use = system_use,
            .browser = browser.Browser.init(allocator, io, environ),
            .publisher = .{ .queue = publication_queue },
            .sessions = std.StringHashMap(*SessionState).init(allocator),
            .active_sessions = std.StringHashMap(RunId).init(allocator),
        };
        system_use = null;
        store_moved = true;
        provider_registry = null;
        errdefer self.prompt_stack.deinit();
        errdefer self.skill_catalog.deinit();
        errdefer self.tool_stack.deinit();
        errdefer self.context.deinit();
        errdefer deinitSessions(self);
        errdefer self.active_sessions.deinit();
        errdefer self.instructions.deinit();
        errdefer self.permission_broker.deinit();
        errdefer self.event_store.close();
        errdefer if (self.provider_registry) |*registry| registry.deinit();

        _ = try self.prompt_stack.register(.{ .id = "constitution", .cache = .stable, .content = .{ .static = self.system_prompt } });
        _ = try self.prompt_stack.register(.{ .id = "cwd", .cache = .session, .content = .{ .static = self.cwd_prompt } });
        const project_skills = try std.fs.path.join(allocator, &.{ self.workspace_root, ".misul", "skills" });
        defer allocator.free(project_skills);
        try addSkillRoot(&self.skill_catalog, io, project_skills);
        if (environ.get("HOME")) |home| {
            const user_skills = try std.fs.path.join(allocator, &.{ home, ".config", "misul", "skills" });
            defer allocator.free(user_skills);
            try addSkillRoot(&self.skill_catalog, io, user_skills);
        }
        const catalog_prompt = try self.skill_catalog.renderCompact(allocator);
        defer allocator.free(catalog_prompt);
        if (catalog_prompt.len != 0) {
            self.skill_catalog_prompt = try allocator.dupe(u8, catalog_prompt);
            errdefer allocator.free(self.skill_catalog_prompt.?);
            _ = try self.prompt_stack.register(.{ .id = "skills", .cache = .stable, .content = .{ .static = self.skill_catalog_prompt.? } });
        }

        self.builtin_registry = try builtin_tools.Registry.init(allocator, io, self.workspace_root);
        errdefer self.builtin_registry.deinit();
        try self.skill_runtime.init(allocator, io, &self.skill_catalog);
        errdefer self.skill_runtime.deinit();
        var base_tools: std.ArrayList(harness_module.Tool) = .empty;
        defer base_tools.deinit(allocator);
        for (self.builtin_registry.tools()) |tool| {
            if (!options.allow_mutating_tools and options.approve_tool == null and builtin_tools.isMutatingTool(tool.name)) continue;
            if (builtin.abi.isAndroid() and !builtin_tools.isSupportedOnAndroid(tool.name)) continue;
            if (containsName(options.disabled_tools, tool.name)) continue;
            try base_tools.append(allocator, tool);
        }
        if (!containsName(options.disabled_tools, "skill")) try base_tools.append(allocator, self.skill_runtime.tool());
        for (options.host_tools) |tool| {
            if (containsName(options.disabled_tools, tool.name)) continue;
            if (!options.allow_mutating_tools and options.approve_tool == null and containsName(options.mutating_tools, tool.name)) continue;
            try base_tools.append(allocator, tool);
        }
        try self.tool_stack.setBase(base_tools.items);
        self.services = .{ .prompts = &self.prompt_stack, .tools = &self.tool_stack };
        _ = try self.context.mount(NativeHarness.plugin("native-harness", 1, &self.services));
        self.harness = try NativeHarness.init(allocator, &self.context);
        const recovery = try self.event_store.replayWithContext(@ptrCast(self), replayRuntimeRecord);
        self.publisher.high_water = recovery.last_sequence;
        // A durable session may reference a model removed from the current
        // configuration. Keep its history recoverable; startPrompt validates
        // the selection before provider dispatch, and setSelection is the
        // explicit repair path.
        self.publisher.future = try std.Io.concurrent(io, publisherTask, .{self});
        errdefer stopPublisher(self);
        try self.reconcileOrphanedSystemUseActions();
        self.writer_mutex.lockUncancelable(io);
        self.appendRuntimeEpochLocked() catch |err| {
            self.writer_mutex.unlock(io);
            return err;
        };
        self.settleInterruptedRunsLocked() catch |err| {
            self.writer_mutex.unlock(io);
            return err;
        };
        self.writer_mutex.unlock(io);
        self.preflightSystemUse() catch {
            if (self.system_use) |*service| service.deinit();
            self.system_use = null;
            self.system_use_ready = false;
        };
        return self;
    }

    /// Exclusive. Call only after every external caller has joined and will no
    /// longer call startPrompt, wait, cancel, setSelection, selection, or activeRun.
    pub fn destroy(self: *Runtime) void {
        self.mutex.lockUncancelable(self.io);
        self.closing = true;
        self.mutex.unlock(self.io);

        for (self.runs.items) |run| {
            self.mutex.lockUncancelable(self.io);
            const active = run.settlement == null;
            self.mutex.unlock(self.io);
            if (active) {
                self.requestCancellation(run) catch {
                    run.cancel_requested.store(true, .release);
                };
                run.future_mutex.lockUncancelable(self.io);
                if (run.future) |*future| _ = future.cancel(self.io) catch {};
                run.future_mutex.unlock(self.io);
            }
            releaseFuture(self, run);
        }

        stopPublisher(self);
        for (self.runs.items) |run| self.deinitRun(run);
        self.runs.deinit(self.allocator);
        for (self.completed_runs.items) |completed| {
            if (completed.settlement.provider_failure) |failure| deinitProviderFailure(self.allocator, failure);
        }
        self.completed_runs.deinit(self.allocator);
        self.active_sessions.deinit();
        deinitSessions(self);
        self.instructions.deinit();
        self.permission_broker.deinit();
        if (self.system_use) |*service| service.deinit();
        self.browser.deinit();
        self.context.deinit();
        self.tool_stack.deinit();
        self.skill_runtime.deinit();
        self.builtin_registry.deinit();
        self.skill_catalog.deinit();
        self.prompt_stack.deinit();
        if (self.skill_catalog_prompt) |value| self.allocator.free(value);
        self.allocator.free(self.cwd_prompt);
        deinitSelection(self.allocator, self.default_selection);
        self.model_profiles.deinit();
        if (self.provider_registry) |*registry| registry.deinit();
        deinitPublisher(self);
        self.event_store.close();
        self.allocator.free(self.system_prompt);
        self.allocator.free(self.state_root);
        self.allocator.free(self.workspace_root);
        const allocator = self.allocator;
        self.* = undefined;
        allocator.destroy(self);
    }

    pub fn startPrompt(self: *Runtime, request: PromptRequest) !RunId {
        if (request.session_id.len == 0 or request.session_id.len > event_store.max_event_id_bytes or request.prompt.len == 0) return error.InvalidPromptRequest;
        if (request.input_id) |input_id| {
            if (input_id.len == 0 or input_id.len > event_store.max_event_id_bytes) return error.InvalidInputMessageId;
        }
        self.mutex.lockUncancelable(self.io);
        if (self.closing) {
            self.mutex.unlock(self.io);
            return error.RuntimeClosing;
        }
        if (self.active_sessions.contains(request.session_id)) {
            self.mutex.unlock(self.io);
            return error.SessionBusy;
        }
        if (self.active_sessions.count() >= self.maximum_active_runs) {
            self.mutex.unlock(self.io);
            return error.TooManyActiveRuns;
        }
        self.mutex.unlock(self.io);

        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        const existing_state = self.sessions.get(request.session_id);
        if (request.input_id) |input_id| {
            if (self.containsMessageIdLocked(input_id, null)) return error.MessageIdAlreadyExists;
        }
        const selected = if (existing_state) |state|
            state.projection.selected_selection orelse self.default_selection
        else
            self.default_selection;
        try validateSelection(&self.model_profiles, selected);
        if (self.provider_registry) |*registry| try validateProviderSelection(registry, selected);
        const provider_dialect: ?openai_compatible.Dialect = if (self.provider_registry) |*registry|
            (registry.find(selected.provider) orelse return error.ProviderNotConfigured).dialect
        else
            null;
        const publication_slots: usize = if (existing_state) |state|
            if (state.projection.selected_selection == null) 3 else 2
        else
            3;
        try self.preflightPublicationSlotsLocked(publication_slots);
        const run = try self.allocator.create(Run);
        var run_initialized = false;
        errdefer if (!run_initialized) self.allocator.destroy(run);
        var durable_id: ?[]u8 = try self.allocDurableIdLocked("run");
        errdefer if (durable_id) |value| self.allocator.free(value);
        var owned_input_message_id: ?[]u8 = if (request.input_id) |input_id|
            try self.allocator.dupe(u8, input_id)
        else
            try self.allocUniqueMessageIdLocked(null);
        errdefer if (owned_input_message_id) |value| self.allocator.free(value);
        var owned_assistant_message_id: ?[]u8 = try self.allocUniqueMessageIdLocked(owned_input_message_id.?);
        errdefer if (owned_assistant_message_id) |value| self.allocator.free(value);
        var owned_session_id: ?[]u8 = try self.allocator.dupe(u8, request.session_id);
        errdefer if (owned_session_id) |value| self.allocator.free(value);
        var owned_prompt: ?[]u8 = try self.allocator.dupe(u8, request.prompt);
        errdefer if (owned_prompt) |value| self.allocator.free(value);
        var owned_system_override: ?[]u8 = if (request.system_override) |value| try self.allocator.dupe(u8, value) else null;
        errdefer if (owned_system_override) |value| self.allocator.free(value);
        var owned_selection: ?ModelSelection = try cloneSelection(self.allocator, selected);
        errdefer if (owned_selection) |value| deinitSelection(self.allocator, value);
        var owned_causal: ?causal_runtime.Runtime = try causal_runtime.Runtime.init(self.allocator, .observe);
        errdefer if (owned_causal) |*value| value.deinit();
        const state = try self.ensureSessionLockedWriter(request.session_id, selected);
        const run_id = self.next_run_id;
        run.* = .{
            .runtime = self,
            .id = run_id,
            .durable_id = durable_id.?,
            .input_message_id = owned_input_message_id.?,
            .assistant_message_id = owned_assistant_message_id.?,
            .request = .{
                .session_id = owned_session_id.?,
                .prompt = owned_prompt.?,
                .system_override = owned_system_override,
                .event_context = request.event_context,
                .emit = request.emit,
            },
            .selection = owned_selection.?,
            .provider_dialect = provider_dialect,
            .model = undefined,
            .causal = owned_causal.?,
        };
        durable_id = null;
        owned_input_message_id = null;
        owned_assistant_message_id = null;
        owned_session_id = null;
        owned_prompt = null;
        owned_system_override = null;
        owned_selection = null;
        owned_causal = null;
        run_initialized = true;
        errdefer self.deinitRun(run);
        run.model = .{
            .id = run.selection.model,
            .api = if (run.provider_dialect) |dialect| apiNameForDialect(dialect) else run.selection.provider,
            .provider = run.selection.provider,
            .context_window = run.selection.context_window,
            .max_tokens = run.selection.output_limit,
            .reasoning = run.selection.reasoning != null,
        };
        self.mutex.lockUncancelable(self.io);
        if (self.closing or self.active_sessions.contains(request.session_id) or self.active_sessions.count() >= self.maximum_active_runs) {
            const admission_error: anyerror = if (self.closing)
                error.RuntimeClosing
            else if (self.active_sessions.contains(request.session_id))
                error.SessionBusy
            else
                error.TooManyActiveRuns;
            self.mutex.unlock(self.io);
            return admission_error;
        }
        self.runs.append(self.allocator, run) catch |err| {
            self.mutex.unlock(self.io);
            return err;
        };
        self.active_sessions.put(run.request.session_id, run.id) catch |err| {
            _ = self.runs.pop();
            self.mutex.unlock(self.io);
            return err;
        };
        self.mutex.unlock(self.io);

        self.appendRunStartedLocked(state, run) catch |err| {
            self.removeUnstartedRun(run);
            return err;
        };
        self.appendProtocolMessageLocked(state, run, .{ .user = .{
            .content = &.{.{ .text = .{ .text = run.request.prompt } }},
            .timestamp_ms = std.Io.Clock.real.now(self.io).toMilliseconds(),
        } }, run.input_message_id) catch |err| {
            self.settleUnstartedRunLocked(state, run, .failed);
            self.removeUnstartedRun(run);
            return err;
        };
        run.future = std.Io.concurrent(self.io, runTask, .{ self, run }) catch |err| {
            self.settleUnstartedRunLocked(state, run, .failed);
            self.removeUnstartedRun(run);
            return err;
        };
        self.next_run_id += 1;
        return run.id;
    }

    pub fn wait(self: *Runtime, run_id: RunId) !Settlement {
        while (true) {
            self.mutex.lockUncancelable(self.io);
            if (self.completedSettlementLocked(run_id)) |settlement| {
                self.mutex.unlock(self.io);
                return settlement;
            }
            const run = self.findRunLocked(run_id) catch |err| {
                self.mutex.unlock(self.io);
                return err;
            };
            if (run.terminal_error) |terminal_error| {
                self.mutex.unlock(self.io);
                releaseFuture(self, run);
                return terminal_error;
            }
            if (run.settlement != null and !run.canceling and !run.reaping) {
                run.reaping = true;
                self.mutex.unlock(self.io);
                releaseFuture(self, run);
                return self.retainSettlement(run);
            }
            self.condition.waitUncancelable(self.io, &self.mutex);
            self.mutex.unlock(self.io);
        }
    }

    pub fn cancel(self: *Runtime, run_id: RunId) !Settlement {
        self.mutex.lockUncancelable(self.io);
        if (self.completedSettlementLocked(run_id)) |settlement| {
            self.mutex.unlock(self.io);
            return settlement;
        }
        const run = self.findRunLocked(run_id) catch |err| {
            self.mutex.unlock(self.io);
            return err;
        };
        if (run.settlement != null or run.finalizing or run.canceling or run.cancel_requested.load(.acquire)) {
            self.mutex.unlock(self.io);
            return self.wait(run_id);
        }
        run.canceling = true;
        self.mutex.unlock(self.io);

        self.writer_mutex.lockUncancelable(self.io);
        const state = self.sessions.get(run.request.session_id) orelse {
            self.writer_mutex.unlock(self.io);
            self.clearCanceling(run);
            return error.SessionNotFound;
        };
        self.preflightPublicationSlotsLocked(2) catch |err| {
            self.writer_mutex.unlock(self.io);
            self.clearCanceling(run);
            return err;
        };
        self.appendCancellationLocked(state, run) catch |err| {
            self.writer_mutex.unlock(self.io);
            self.clearCanceling(run);
            return err;
        };
        const canceled = Settlement{
            .run_id = run.id,
            .session_id = run.request.session_id,
            .stop_reason = .canceled,
            .step_count = 0,
            .usage = .{},
            .failure = .canceled,
        };
        self.appendRunSettlementLocked(state, run, .cancelled) catch |err| {
            self.writer_mutex.unlock(self.io);
            self.clearCanceling(run);
            return err;
        };

        run.cancel_requested.store(true, .release);
        self.mutex.lockUncancelable(self.io);
        run.settlement = canceled;
        _ = self.active_sessions.remove(run.request.session_id);
        self.condition.broadcast(self.io);
        self.mutex.unlock(self.io);

        // The writer acts as a cancellation barrier: every task-side durable
        // path rechecks the flag after acquiring it, so no new write can begin.
        self.writer_mutex.unlock(self.io);
        run.future_mutex.lockUncancelable(self.io);
        if (run.future) |*future| _ = future.cancel(self.io) catch {};
        run.future_mutex.unlock(self.io);

        self.emit(run, .cancellation_requested) catch {};
        self.emit(run, .{ .settled = canceled }) catch {};
        self.clearCanceling(run);
        return self.wait(run_id);
    }

    pub fn setSelection(self: *Runtime, session_id: []const u8, value: ModelSelection) !void {
        if (session_id.len == 0 or session_id.len > event_store.max_event_id_bytes) return error.InvalidSessionId;
        try validateSelection(&self.model_profiles, value);
        if (self.provider_registry) |*registry| try validateProviderSelection(registry, value);
        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        self.mutex.lockUncancelable(self.io);
        const closing = self.closing;
        self.mutex.unlock(self.io);
        if (closing) return error.RuntimeClosing;
        const existing = self.sessions.get(session_id);
        if (existing) |state| if (state.projection.selected_selection) |current| {
            if (selectionEqual(current, value)) return;
        };
        const needs_initial_selection = existing == null or existing.?.projection.selected_selection == null;
        try self.preflightPublicationSlotsLocked(1);
        const state = try self.ensureSessionLockedWriter(session_id, value);
        if (!needs_initial_selection) try self.appendSelectionLocked(state, session_id, value);
    }

    pub fn selection(self: *Runtime, session_id: []const u8) !ModelSelection {
        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        const state = self.sessions.get(session_id) orelse return error.SessionNotFound;
        return cloneSelection(self.allocator, state.projection.selected_selection orelse return error.SessionSelectionMissing);
    }

    pub fn selectionForModel(self: *Runtime, model_id: []const u8, output_limit: ?u64) !ModelSelection {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        if (self.closing) return error.RuntimeClosing;
        const profile = self.model_profiles.find(model_id) orelse return error.ModelNotConfigured;
        const selected_limit = output_limit orelse profile.output_limit;
        if (selected_limit == 0 or selected_limit > profile.max_output_tokens) return error.OutputLimitExceedsProfile;
        return cloneSelection(self.allocator, .{
            .provider = profile.provider,
            .model = profile.id,
            .context_window = profile.context_window,
            .output_limit = selected_limit,
            .reasoning = profile.reasoning,
            .cache_retention = profile.cache_retention,
            .cache_aggressiveness = profile.cache_aggressiveness,
            .transport = profile.transport,
        });
    }

    /// Immutable Runtime-owned profiles. Borrowed until exclusive destroy.
    pub fn profiles(self: *const Runtime) []const model_catalog.ModelProfile {
        return self.model_profiles.profiles;
    }

    pub fn activeRun(self: *Runtime, session_id: []const u8) ?RunId {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        return self.active_sessions.get(session_id);
    }

    pub fn systemUseAvailable(self: *const Runtime) bool {
        return self.system_use != null and self.system_use_ready and self.system_use.?.child != null;
    }

    pub fn ensureSystemUseAvailable(self: *Runtime) !void {
        if (self.systemUseAvailable()) return;
        if (self.system_use == null) return error.SystemUseUnavailable;
        self.system_use_ready = false;
        try self.preflightSystemUse();
    }

    pub fn systemUsePermissions(self: *Runtime, context: SystemUseRequestContext) !SystemUseResult {
        try self.ensureSystemUseAvailable();
        return self.systemUseInvoke(context, "permissions", "{}", .system_use_permissions, .inspect, "system-use-service");
    }

    pub fn systemUseList(self: *Runtime, context: SystemUseRequestContext) !SystemUseResult {
        try self.ensureSystemUseAvailable();
        var raw = try self.systemUseInvoke(context, "targets", "{}", .system_use_list, .inspect, "visible-targets");
        defer raw.deinit();
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"targets\":{s}}}", .{raw.json}),
        };
    }

    pub fn systemUseObserve(self: *Runtime, context: SystemUseRequestContext, params_json: []const u8, screenshot: bool) !SystemUseResult {
        try self.ensureSystemUseAvailable();
        const target = try systemUseTargetId(self.allocator, params_json);
        defer self.allocator.free(target);
        return self.systemUseInvoke(context, "observe", params_json, if (screenshot) .system_use_screenshot else .system_use_observe, if (screenshot) .capture else .inspect, target);
    }

    pub fn systemUseAction(self: *Runtime, context: SystemUseRequestContext, method: []const u8, params_json: []const u8) !SystemUseResult {
        if (!std.mem.eql(u8, method, "click") and !std.mem.eql(u8, method, "setValue") and !std.mem.eql(u8, method, "type") and !std.mem.eql(u8, method, "scroll")) return error.UnsupportedSystemUseAction;
        try self.ensureSystemUseAvailable();
        const target = try systemUseTargetId(self.allocator, params_json);
        defer self.allocator.free(target);
        var action = try self.beginSystemUseAction(context, .system_use_interact, .input, target, params_json);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        const service = &(self.system_use orelse return error.SystemUseUnavailable);
        var failure: SystemUseFailureEvidence = .{};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        var raw = service.request(method, params_json, &failure) catch |err| {
            self.system_use_ready = false;
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), systemUseFailureEvidence(&evidence_buffer, method, err, failure));
            return err;
        };
        defer raw.deinit();
        validateSystemUseResult(self.allocator, method, params_json, raw.json, false) catch |err| {
            self.system_use_ready = false;
            service.retire();
            try self.settleSystemUseAction(&action, .outcome_unknown, systemUseFailureEvidence(&evidence_buffer, method, err, failure));
            return err;
        };
        const success_evidence = try systemUseSuccessEvidence(&evidence_buffer, method, .system_use_interact, raw);
        try self.settleSystemUseAction(&action, .success, success_evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"result\":{s}}}", .{ std.json.fmt(action.action_id, .{}), raw.json }),
        };
    }

    pub fn systemUseWait(self: *Runtime, context: SystemUseRequestContext, request: SystemUseWaitRequest) !SystemUseResult {
        if ((request.text == null) == (request.element_id == null)) return error.InvalidSystemUseWait;
        if (request.timeout_ms == 0 or request.timeout_ms > 30_000 or request.poll_interval_ms == 0 or request.poll_interval_ms > 1_000) return error.InvalidSystemUseWait;
        try self.ensureSystemUseAvailable();
        const service = &(self.system_use orelse return error.SystemUseUnavailable);
        const target = try systemUseTargetId(self.allocator, request.target_json);
        defer self.allocator.free(target);
        const wait_arguments = try std.json.Stringify.valueAlloc(self.allocator, .{
            .target = request.target_json,
            .wait_id = request.wait_id,
            .text = request.text,
            .element_id = request.element_id,
            .present = request.present,
            .timeout_ms = request.timeout_ms,
            .poll_interval_ms = request.poll_interval_ms,
        }, .{});
        defer self.allocator.free(wait_arguments);
        self.system_use_wait_mutex.lockUncancelable(self.io);
        if (self.active_system_use_wait != null) {
            self.system_use_wait_mutex.unlock(self.io);
            return error.SystemUseWaitBusy;
        }
        const wait_id = request.wait_id orelse self.next_system_use_wait;
        if (wait_id == 0) {
            self.system_use_wait_mutex.unlock(self.io);
            return error.InvalidSystemUseWait;
        }
        if (request.wait_id == null) {
            self.next_system_use_wait +%= 1;
            if (self.next_system_use_wait == 0) self.next_system_use_wait = 1;
        }
        self.active_system_use_wait = wait_id;
        self.system_use_wait_cancel.store(false, .release);
        if (request.install_notice) |notice| notice.store(true, .release);
        self.system_use_wait_mutex.unlock(self.io);
        defer {
            self.system_use_wait_mutex.lockUncancelable(self.io);
            self.active_system_use_wait = null;
            self.system_use_wait_cancel.store(false, .release);
            self.system_use_wait_mutex.unlock(self.io);
        }
        var governed = try self.beginSystemUseAction(context, .system_use_wait, .wait, target, wait_arguments);
        defer governed.deinit();
        errdefer if (!governed.settled) self.settleSystemUseAction(&governed, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-wait") catch {};
        if (request.external_cancel) |cancel_flag| if (cancel_flag.load(.acquire)) {
            try self.settleSystemUseAction(&governed, .cancelled, "v1;status=cancelled;stage=accepted-before-first-observation");
            return error.Canceled;
        };

        const started: std.Io.Clock.Timestamp = .now(self.io, .awake);
        const deadline = started.addDuration(.{ .clock = .awake, .raw = .fromMilliseconds(@intCast(request.timeout_ms)) });
        var samples: u64 = 0;
        var last: ?SystemUseResult = null;
        var failure: SystemUseFailureEvidence = .{};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        defer if (last) |*value| value.deinit();
        while (true) {
            if (self.system_use_wait_cancel.load(.acquire)) {
                try self.settleSystemUseAction(&governed, .cancelled, "v1;status=cancelled;stage=before-observation");
                return error.Canceled;
            }
            const before_observation: std.Io.Clock.Timestamp = .now(self.io, .awake);
            if (samples != 0 and before_observation.compare(.gte, deadline)) break;
            if (last) |*value| value.deinit();
            last = null;
            const remaining_ms = @max(@as(i64, 1), before_observation.durationTo(deadline).raw.toMilliseconds());
            last = service.requestControlled("observe", request.target_json, @intCast(remaining_ms), &self.system_use_wait_cancel, &failure) catch |err| {
                self.system_use_ready = false;
                try self.settleSystemUseAction(&governed, systemUseFailureStatus(err), systemUseFailureEvidence(&evidence_buffer, "wait.observe", err, failure));
                return err;
            };
            samples += 1;
            if (self.system_use_wait_cancel.load(.acquire)) {
                try self.settleSystemUseAction(&governed, .cancelled, "v1;status=cancelled;stage=after-observation");
                return error.Canceled;
            }
            validateSystemUseResult(self.allocator, "observe", request.target_json, last.?.json, false) catch |err| {
                self.system_use_ready = false;
                service.retire();
                try self.settleSystemUseAction(&governed, .outcome_unknown, systemUseFailureEvidence(&evidence_buffer, "wait.observe", err, failure));
                return err;
            };
            var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, last.?.json, .{}) catch return error.InvalidSystemUseResponse;
            defer parsed.deinit();
            const semantic = try systemUseSemanticElements(parsed.value);
            const found = if (request.element_id) |element_id| jsonElementsContainId(semantic, element_id) else jsonContainsString(semantic, request.text.?);
            if (found == request.present) break;
            const now: std.Io.Clock.Timestamp = .now(self.io, .awake);
            if (now.compare(.gte, deadline)) break;
            const remaining_poll_ms: u64 = @intCast(@max(@as(i64, 1), now.durationTo(deadline).raw.toMilliseconds()));
            sleepSystemUsePoll(self.io, @min(request.poll_interval_ms, remaining_poll_ms), &self.system_use_wait_cancel) catch |err| {
                try self.settleSystemUseAction(&governed, .cancelled, "v1;status=cancelled;stage=poll-sleep");
                return err;
            };
        }
        const elapsed = started.durationTo(.now(self.io, .awake)).raw.toMilliseconds();
        var parsed_last = try std.json.parseFromSlice(std.json.Value, self.allocator, last.?.json, .{});
        defer parsed_last.deinit();
        const semantic = try systemUseSemanticElements(parsed_last.value);
        const matched_value = if (request.element_id) |element_id| jsonElementsContainId(semantic, element_id) else jsonContainsString(semantic, request.text.?);
        const matched = matched_value == request.present;
        const wait_evidence = try systemUseWaitEvidence(&evidence_buffer, last.?.json, elapsed, samples, matched, request.element_id != null);
        try self.settleSystemUseAction(&governed, if (matched) .success else .timeout, wait_evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"wait_id\":\"wait-{d}\",\"matched\":{},\"elapsed_ms\":{d},\"samples\":{d},\"observation\":{s}}}", .{ wait_id, matched, elapsed, samples, last.?.json }),
        };
    }

    pub fn cancelSystemUseWait(self: *Runtime, wait_id: u64) !void {
        self.system_use_wait_mutex.lockUncancelable(self.io);
        defer self.system_use_wait_mutex.unlock(self.io);
        if (self.active_system_use_wait != wait_id) return error.SystemUseWaitNotFound;
        self.system_use_wait_cancel.store(true, .release);
    }

    pub fn browserAvailable(self: *Runtime) bool {
        return self.browser.available();
    }

    fn browserTarget(self: *Runtime) []const u8 {
        if (self.browser.session_id) |id| return id;
        return "browser";
    }

    fn browserElapsedUs(self: *Runtime, start_ms: u64) u64 {
        const now_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        return (now_ms -| start_ms) * 1000;
    }

    pub fn browserStart(self: *Runtime, context: SystemUseRequestContext) !SystemUseResult {
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), "{\"op\":\"start\"}");
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const id = self.browser.startSession() catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "start", err));
            return err;
        };
        defer self.allocator.free(id);
        var digest: action_receipt.Digest = undefined;
        std.crypto.hash.sha2.Sha256.hash(id, &digest, .{});
        const hex = std.fmt.bytesToHex(digest, .lower);
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_start;result_sha256={s};duration_us={d}", .{ &hex, self.browserElapsedUs(start_ms) }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"session_id\":{f}}}", .{ std.json.fmt(action.action_id, .{}), std.json.fmt(id, .{}) }),
        };
    }

    pub fn browserNavigate(self: *Runtime, context: SystemUseRequestContext, url: []const u8) !SystemUseResult {
        try browser.validateUrl(url);
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":\"navigate\",\"url\":{f}}}", .{std.json.fmt(url, .{})});
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const current = self.browser.navigate(url) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "navigate", err));
            return err;
        };
        defer self.allocator.free(current);
        var digest: action_receipt.Digest = undefined;
        std.crypto.hash.sha2.Sha256.hash(current, &digest, .{});
        const hex = std.fmt.bytesToHex(digest, .lower);
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_navigate;result_sha256={s};duration_us={d}", .{ &hex, self.browserElapsedUs(start_ms) }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"url\":{f}}}", .{ std.json.fmt(action.action_id, .{}), std.json.fmt(current, .{}) }),
        };
    }

    pub fn browserSnapshot(self: *Runtime, context: SystemUseRequestContext) !SystemUseResult {
        var action = try self.beginSystemUseAction(context, .browser_observe, .inspect, self.browserTarget(), "{\"op\":\"snapshot\"}");
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const snap = self.browser.snapshot() catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "snapshot", err));
            return err;
        };
        defer self.allocator.free(snap);
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, snap, .{}) catch return error.BrowserInvalidResponse;
        defer parsed.deinit();
        const count = parsed.value.object.get("elements").?.array.items.len;
        var digest: action_receipt.Digest = undefined;
        std.crypto.hash.sha2.Sha256.hash(snap, &digest, .{});
        const hex = std.fmt.bytesToHex(digest, .lower);
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_snapshot;result_sha256={s};duration_us={d};elements={d}", .{ &hex, self.browserElapsedUs(start_ms), count }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"snapshot\":{s}}}", .{ std.json.fmt(action.action_id, .{}), snap }),
        };
    }

    pub fn browserClick(self: *Runtime, context: SystemUseRequestContext, index: i64) !SystemUseResult {
        if (index < 0 or index >= browser.maximum_snapshot_elements) return error.BrowserBadIndex;
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":\"click\",\"index\":{d}}}", .{index});
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const xpath = self.browser.resolveIndex(index) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "click", err));
            return err;
        };
        defer self.allocator.free(xpath);
        self.browser.click(index) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "click", err));
            return err;
        };
        const evidence = browser.evidenceForIndex(&evidence_buffer, "browser_click", xpath, index, xpath, self.browserElapsedUs(start_ms)) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"index\":{d}}}", .{ std.json.fmt(action.action_id, .{}), index }),
        };
    }

    pub fn browserFill(self: *Runtime, context: SystemUseRequestContext, index: i64, text: []const u8) !SystemUseResult {
        if (index < 0 or index >= browser.maximum_snapshot_elements) return error.BrowserBadIndex;
        if (text.len == 0 or text.len > browser.maximum_text_bytes) return error.BrowserInvalidParams;
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":\"fill\",\"index\":{d}}}", .{index});
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const xpath = self.browser.resolveIndex(index) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "fill", err));
            return err;
        };
        defer self.allocator.free(xpath);
        self.browser.fill(index, text) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "fill", err));
            return err;
        };
        const evidence = browser.evidenceForIndex(&evidence_buffer, "browser_fill", xpath, index, xpath, self.browserElapsedUs(start_ms)) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"index\":{d}}}", .{ std.json.fmt(action.action_id, .{}), index }),
        };
    }

    pub fn browserBack(self: *Runtime, context: SystemUseRequestContext) !SystemUseResult {
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), "{\"op\":\"back\"}");
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        self.browser.back() catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "back", err));
            return err;
        };
        var digest: action_receipt.Digest = undefined;
        std.crypto.hash.sha2.Sha256.hash(self.browserTarget(), &digest, .{});
        const hex = std.fmt.bytesToHex(digest, .lower);
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_back;result_sha256={s};duration_us={d}", .{ &hex, self.browserElapsedUs(start_ms) }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\"}}", .{std.json.fmt(action.action_id, .{})}),
        };
    }

    pub fn browserForward(self: *Runtime, context: SystemUseRequestContext) !SystemUseResult {
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), "{\"op\":\"forward\"}");
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        self.browser.forward() catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "forward", err));
            return err;
        };
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_forward;result_sha256={s};duration_us={d}", .{ &shaOf(self.browserTarget()), self.browserElapsedUs(start_ms) }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\"}}", .{std.json.fmt(action.action_id, .{})}),
        };
    }

    pub fn browserTabs(self: *Runtime, context: SystemUseRequestContext) !SystemUseResult {
        var action = try self.beginSystemUseAction(context, .browser_observe, .inspect, self.browserTarget(), "{\"op\":\"tabs\"}");
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const handles_json = self.browser.tabHandles() catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "tabs", err));
            return err;
        };
        defer self.allocator.free(handles_json);
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_tabs;result_sha256={s};duration_us={d}", .{ &shaOf(handles_json), self.browserElapsedUs(start_ms) }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"tabs\":{s}}}", .{ std.json.fmt(action.action_id, .{}), handles_json }),
        };
    }

    pub fn browserTabNew(self: *Runtime, context: SystemUseRequestContext, url: ?[]const u8) !SystemUseResult {
        if (url) |u| try browser.validateUrl(u);
        const args = if (url) |u| try std.fmt.allocPrint(self.allocator, "{{\"op\":\"tab_new\",\"url\":{f}}}", .{std.json.fmt(u, .{})}) else try self.allocator.dupe(u8, "{\"op\":\"tab_new\"}");
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const handle = self.browser.newTab(url) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "tab_new", err));
            return err;
        };
        defer self.allocator.free(handle);
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_tab_new;result_sha256={s};duration_us={d}", .{ &shaOf(handle), self.browserElapsedUs(start_ms) }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"handle\":{f}}}", .{ std.json.fmt(action.action_id, .{}), std.json.fmt(handle, .{}) }),
        };
    }

    pub fn browserTabSwitch(self: *Runtime, context: SystemUseRequestContext, handle: []const u8) !SystemUseResult {
        if (handle.len == 0 or handle.len > 256) return error.BrowserInvalidParams;
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":\"tab_switch\"}}", .{});
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        self.browser.switchTab(handle) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "tab_switch", err));
            return err;
        };
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_tab_switch;result_sha256={s};duration_us={d}", .{ &shaOf(handle), self.browserElapsedUs(start_ms) }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\"}}", .{std.json.fmt(action.action_id, .{})}),
        };
    }

    pub fn browserTabClose(self: *Runtime, context: SystemUseRequestContext, handle: ?[]const u8) !SystemUseResult {
        if (handle) |h| if (h.len == 0 or h.len > 256) return error.BrowserInvalidParams;
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":\"tab_close\"}}", .{});
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const fact = handle orelse self.browserTarget();
        self.browser.closeTab(handle) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "tab_close", err));
            return err;
        };
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_tab_close;result_sha256={s};duration_us={d}", .{ &shaOf(fact), self.browserElapsedUs(start_ms) }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\"}}", .{std.json.fmt(action.action_id, .{})}),
        };
    }

    pub fn browserScreenshot(self: *Runtime, context: SystemUseRequestContext) !SystemUseResult {
        var action = try self.beginSystemUseAction(context, .browser_observe, .inspect, self.browserTarget(), "{\"op\":\"screenshot\"}");
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const png = self.browser.screenshot() catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "screenshot", err));
            return err;
        };
        defer self.allocator.free(png);
        const dims = pngDimensions(self.allocator, png) catch {
            try self.settleSystemUseAction(&action, .outcome_unknown, browserFailureEvidence(&evidence_buffer, "screenshot", error.BrowserInvalidResponse));
            return error.BrowserInvalidResponse;
        };
        validateSystemUsePng(self.allocator, png, dims[0], dims[1]) catch {
            try self.settleSystemUseAction(&action, .outcome_unknown, browserFailureEvidence(&evidence_buffer, "screenshot", error.BrowserInvalidResponse));
            return error.BrowserInvalidResponse;
        };
        var digest: action_receipt.Digest = undefined;
        std.crypto.hash.sha2.Sha256.hash(png, &digest, .{});
        const hex = std.fmt.bytesToHex(digest, .lower);
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_screenshot;result_sha256={s};duration_us={d};width={d};height={d}", .{ &hex, self.browserElapsedUs(start_ms), dims[0], dims[1] }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"screenshot\":{{\"mimeType\":\"image/png\",\"data\":{f},\"width\":{d},\"height\":{d}}}}}", .{ std.json.fmt(action.action_id, .{}), std.json.fmt(png, .{}), dims[0], dims[1] }),
        };
    }

    pub fn browserKey(self: *Runtime, context: SystemUseRequestContext, key: []const u8, modifiers: []const []const u8) !SystemUseResult {
        _ = try browser.keyCode(key);
        for (modifiers) |name| _ = try browser.keyCode(name);
        if (modifiers.len > 3) return error.BrowserInvalidParams;
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":\"key\",\"key\":{f}}}", .{std.json.fmt(key, .{})});
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        self.browser.pressKey(key, modifiers) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "key", err));
            return err;
        };
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_key;result_sha256={s};duration_us={d}", .{ &shaOf(key), self.browserElapsedUs(start_ms) }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\"}}", .{std.json.fmt(action.action_id, .{})}),
        };
    }

    pub fn browserSelect(self: *Runtime, context: SystemUseRequestContext, index: i64, value: []const u8) !SystemUseResult {
        if (index < 0 or index >= browser.maximum_snapshot_elements) return error.BrowserBadIndex;
        if (value.len == 0 or value.len > 1024) return error.BrowserInvalidParams;
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":\"select\",\"index\":{d}}}", .{index});
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const selected = self.browser.selectOption(index, value) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "select", err));
            return err;
        };
        defer self.allocator.free(selected);
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_select;result_sha256={s};duration_us={d};index={d}", .{ &shaOf(selected), self.browserElapsedUs(start_ms), index }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"index\":{d}}}", .{ std.json.fmt(action.action_id, .{}), index }),
        };
    }

    pub fn browserUpload(self: *Runtime, context: SystemUseRequestContext, index: i64, path: []const u8) !SystemUseResult {
        if (index < 0 or index >= browser.maximum_snapshot_elements) return error.BrowserBadIndex;
        if (!std.fs.path.isAbsolute(path) or path.len == 0 or path.len > 1024) return error.BrowserInvalidParams;
        std.Io.Dir.accessAbsolute(self.io, path, .{}) catch return error.BrowserInvalidParams;
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":\"upload\",\"index\":{d}}}", .{index});
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const xpath = self.browser.resolveIndex(index) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "upload", err));
            return err;
        };
        defer self.allocator.free(xpath);
        self.browser.fillPath(index, path) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "upload", err));
            return err;
        };
        const evidence = browser.evidenceForIndex(&evidence_buffer, "browser_upload", path, index, xpath, self.browserElapsedUs(start_ms)) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"index\":{d}}}", .{ std.json.fmt(action.action_id, .{}), index }),
        };
    }

    pub fn browserDialog(self: *Runtime, context: SystemUseRequestContext, accept: bool) !SystemUseResult {
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":\"dialog\",\"accept\":{}}}", .{accept});
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        self.browser.dialogRespond(accept) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "dialog", err));
            return err;
        };
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_dialog;result_sha256={s};duration_us={d};accept={}", .{ &shaOf(if (accept) "accept" else "dismiss"), self.browserElapsedUs(start_ms), accept }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\"}}", .{std.json.fmt(action.action_id, .{})}),
        };
    }

    pub fn browserScrollPage(self: *Runtime, context: SystemUseRequestContext, x: i64, y: i64, index: ?i64) !SystemUseResult {
        if (x < -5000 or x > 5000 or y < -5000 or y > 5000) return error.BrowserInvalidParams;
        if (index) |idx| if (idx < 0 or idx >= browser.maximum_snapshot_elements) return error.BrowserBadIndex;
        if (x == 0 and y == 0 and index == null) return error.BrowserInvalidParams;
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":\"scroll\",\"x\":{d},\"y\":{d}}}", .{ x, y });
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        var scrolled: []u8 = undefined;
        var owned = false;
        if (index) |idx| {
            scrolled = self.browser.scrollElementIntoView(idx) catch |err| {
                try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "scroll", err));
                return err;
            };
            owned = true;
        }
        defer if (owned) self.allocator.free(scrolled);
        if (x != 0 or y != 0) {
            const moved = self.browser.pageScroll(x, y) catch |err| {
                try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "scroll", err));
                return err;
            };
            if (owned) self.allocator.free(scrolled);
            scrolled = moved;
            owned = true;
        }
        var digest: action_receipt.Digest = undefined;
        std.crypto.hash.sha2.Sha256.hash(if (owned) scrolled else "{}", &digest, .{});
        const hex = std.fmt.bytesToHex(digest, .lower);
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_scroll;result_sha256={s};duration_us={d}", .{ &hex, self.browserElapsedUs(start_ms) }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\"}}", .{std.json.fmt(action.action_id, .{})}),
        };
    }

    pub fn browserHover(self: *Runtime, context: SystemUseRequestContext, index: i64) !SystemUseResult {
        return self.browserPointerOp(context, "hover", index, 0, 0);
    }

    pub fn browserDoubleClick(self: *Runtime, context: SystemUseRequestContext, index: i64) !SystemUseResult {
        return self.browserPointerOp(context, "double_click", index, 0, 2);
    }

    pub fn browserRightClick(self: *Runtime, context: SystemUseRequestContext, index: i64) !SystemUseResult {
        return self.browserPointerOp(context, "right_click", index, 2, 1);
    }

    fn browserPointerOp(self: *Runtime, context: SystemUseRequestContext, op: []const u8, index: i64, button: u8, clicks: u8) !SystemUseResult {
        if (index < 0 or index >= browser.maximum_snapshot_elements) return error.BrowserBadIndex;
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":{f},\"index\":{d}}}", .{ std.json.fmt(op, .{}), index });
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const xpath = self.browser.resolveIndex(index) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, op, err));
            return err;
        };
        defer self.allocator.free(xpath);
        if (std.mem.eql(u8, op, "hover")) {
            self.browser.hover(index) catch |err| {
                try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, op, err));
                return err;
            };
        } else {
            self.browser.pointerClick(index, button, clicks) catch |err| {
                try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, op, err));
                return err;
            };
        }
        const method_name = try std.fmt.allocPrint(self.allocator, "browser_{s}", .{op});
        defer self.allocator.free(method_name);
        const evidence = browser.evidenceForIndex(&evidence_buffer, method_name, xpath, index, xpath, self.browserElapsedUs(start_ms)) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"index\":{d}}}", .{ std.json.fmt(action.action_id, .{}), index }),
        };
    }

    pub fn browserDrag(self: *Runtime, context: SystemUseRequestContext, from_index: i64, to_index: i64) !SystemUseResult {
        if (from_index < 0 or from_index >= browser.maximum_snapshot_elements or to_index < 0 or to_index >= browser.maximum_snapshot_elements) return error.BrowserBadIndex;
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":\"drag\",\"from\":{d},\"to\":{d}}}", .{ from_index, to_index });
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        self.browser.drag(from_index, to_index) catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "drag", err));
            return err;
        };
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_drag;result_sha256={s};duration_us={d};from={d};to={d}", .{ &shaOf(args), self.browserElapsedUs(start_ms), from_index, to_index }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\"}}", .{std.json.fmt(action.action_id, .{})}),
        };
    }

    pub fn browserWaitText(self: *Runtime, context: SystemUseRequestContext, text: []const u8, timeout_ms: u64) !SystemUseResult {
        if (text.len == 0 or text.len > 1024 or timeout_ms == 0 or timeout_ms > 30_000) return error.BrowserInvalidParams;
        const args = try std.fmt.allocPrint(self.allocator, "{{\"op\":\"wait\"}}", .{});
        defer self.allocator.free(args);
        var action = try self.beginSystemUseAction(context, .browser_observe, .inspect, self.browserTarget(), args);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const deadline = std.Io.Clock.Timestamp.now(self.io, .awake).addDuration(.{ .clock = .awake, .raw = .fromMilliseconds(@intCast(timeout_ms)) });
        var samples: u64 = 0;
        var matched = false;
        while (true) {
            samples += 1;
            const page = self.browser.pageText() catch |err| {
                try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "wait", err));
                return err;
            };
            defer self.allocator.free(page);
            if (std.mem.indexOf(u8, page, text) != null) {
                matched = true;
                break;
            }
            if (std.Io.Clock.Timestamp.now(self.io, .awake).compare(.gte, deadline)) break;
            std.Io.sleep(self.io, .fromMilliseconds(100), .awake) catch {
                try self.settleSystemUseAction(&action, .cancelled, "v1;status=cancelled;stage=browser-wait-sleep");
                return error.Canceled;
            };
        }
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_wait;result_sha256={s};duration_us={d};matched={};samples={d}", .{ &shaOf(text), self.browserElapsedUs(start_ms), matched, samples }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"matched\":{},\"samples\":{d}}}", .{ std.json.fmt(action.action_id, .{}), matched, samples }),
        };
    }

    pub fn browserExtract(self: *Runtime, context: SystemUseRequestContext) !SystemUseResult {
        var action = try self.beginSystemUseAction(context, .browser_observe, .inspect, self.browserTarget(), "{\"op\":\"extract\"}");
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const text = self.browser.pageText() catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "extract", err));
            return err;
        };
        defer self.allocator.free(text);
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_extract;result_sha256={s};duration_us={d};chars={d}", .{ &shaOf(text), self.browserElapsedUs(start_ms), text.len }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\",\"text\":{f}}}", .{ std.json.fmt(action.action_id, .{}), std.json.fmt(text, .{}) }),
        };
    }

    pub fn browserClose(self: *Runtime, context: SystemUseRequestContext) !SystemUseResult {
        var action = try self.beginSystemUseAction(context, .browser_interact, .input, self.browserTarget(), "{\"op\":\"close\"}");
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-action") catch {};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        const start_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const target = self.browserTarget();
        self.browser.closeSession() catch |err| {
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), browserFailureEvidence(&evidence_buffer, "close", err));
            return err;
        };
        var digest: action_receipt.Digest = undefined;
        std.crypto.hash.sha2.Sha256.hash(target, &digest, .{});
        const hex = std.fmt.bytesToHex(digest, .lower);
        const evidence = std.fmt.bufPrint(&evidence_buffer, "v1;method=browser_close;result_sha256={s};duration_us={d}", .{ &hex, self.browserElapsedUs(start_ms) }) catch return error.ActionReceiptMismatch;
        try self.settleSystemUseAction(&action, .success, evidence);
        return .{
            .allocator = self.allocator,
            .json = try std.fmt.allocPrint(self.allocator, "{{\"action_id\":{f},\"status\":\"succeeded\"}}", .{std.json.fmt(action.action_id, .{})}),
        };
    }

    fn systemUseInvoke(self: *Runtime, context: SystemUseRequestContext, method: []const u8, params_json: []const u8, capability: action_receipt.Capability, effect: action_receipt.Effect, target: []const u8) !SystemUseResult {
        const service = &(self.system_use orelse return error.SystemUseUnavailable);
        var action = try self.beginSystemUseAction(context, capability, effect, target, params_json);
        defer action.deinit();
        errdefer if (!action.settled) self.settleSystemUseAction(&action, .outcome_unknown, "v1;status=outcome_unknown;stage=post-dispatch-operation") catch {};
        var failure: SystemUseFailureEvidence = .{};
        var evidence_buffer: [system_use_evidence_capacity]u8 = undefined;
        var result = service.request(method, params_json, &failure) catch |err| {
            self.system_use_ready = false;
            try self.settleSystemUseAction(&action, systemUseFailureStatus(err), systemUseFailureEvidence(&evidence_buffer, method, err, failure));
            return err;
        };
        errdefer result.deinit();
        validateSystemUseResult(self.allocator, method, params_json, result.json, capability == .system_use_screenshot) catch |err| {
            self.system_use_ready = false;
            service.retire();
            try self.settleSystemUseAction(&action, .outcome_unknown, systemUseFailureEvidence(&evidence_buffer, method, err, failure));
            return err;
        };
        const success_evidence = try systemUseSuccessEvidence(&evidence_buffer, method, capability, result);
        try self.settleSystemUseAction(&action, .success, success_evidence);
        return result;
    }

    fn preflightSystemUse(self: *Runtime) !void {
        if (self.system_use == null) return;
        const context = SystemUseRequestContext{ .source_request_id = "runtime-startup", .causal_request_id = "runtime-startup", .run_id = "runtime-startup", .session_id = "runtime-startup", .actor_id = "runtime", .provenance = "runtime:system-use-preflight" };
        var result = try self.systemUseInvoke(context, "permissions", "{}", .system_use_permissions, .inspect, "system-use-service");
        defer result.deinit();
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, result.json, .{}) catch return error.InvalidSystemUseResponse;
        defer parsed.deinit();
        if (parsed.value != .object) return error.InvalidSystemUseResponse;
        const accessibility = parsed.value.object.get("accessibility") orelse return error.InvalidSystemUseResponse;
        const screen_recording = parsed.value.object.get("screenRecording") orelse return error.InvalidSystemUseResponse;
        if (accessibility != .bool or screen_recording != .bool) return error.InvalidSystemUseResponse;
        if (!accessibility.bool or !screen_recording.bool) return error.SystemUsePermissionUnavailable;
        self.system_use_ready = true;
    }

    fn beginSystemUseAction(self: *Runtime, context: SystemUseRequestContext, capability: action_receipt.Capability, effect: action_receipt.Effect, target: []const u8, canonical_arguments: []const u8) !GovernedAction {
        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        const normalized_arguments = try action_receipt.canonicalizeArguments(self.allocator, canonical_arguments);
        defer self.allocator.free(normalized_arguments);
        const now_ms: u64 = @intCast(std.Io.Clock.real.now(self.io).toMilliseconds());
        const action_id = try self.allocDurableIdLocked("action");
        errdefer self.allocator.free(action_id);
        const causal_request_id = try self.allocator.dupe(u8, context.causal_request_id);
        errdefer self.allocator.free(causal_request_id);
        const source_request_id = try self.allocator.dupe(u8, context.source_request_id);
        errdefer self.allocator.free(source_request_id);
        const actor_id = try self.allocator.dupe(u8, context.actor_id);
        errdefer self.allocator.free(actor_id);
        const decision_id = try self.allocDurableIdLocked("decision");
        errdefer self.allocator.free(decision_id);
        const lease_id = try self.allocDurableIdLocked("lease");
        errdefer self.allocator.free(lease_id);
        var workspace_hash: action_receipt.Digest = undefined;
        std.crypto.hash.sha2.Sha256.hash(self.workspace_root, &workspace_hash, .{});
        const workspace_hex = std.fmt.bytesToHex(workspace_hash, .lower);
        const descriptor = action_receipt.Descriptor{
            .action_id = action_id,
            .causal_request_id = causal_request_id,
            .source_request_id = source_request_id,
            .run_id = context.run_id,
            .session_id = context.session_id,
            .actor_id = context.actor_id,
            .provenance = context.provenance,
            .workspace_id = &workspace_hex,
            .owner_id = "builtin:system-use",
            .owner_version = 1,
            .owner_generation = 1,
            .capability = capability,
            .effect = effect,
            .target = target,
            .canonical_arguments = normalized_arguments,
            .authority = .local_user,
            .expires_at_ms = now_ms + 30_000,
            .cancellation_generation = self.next_system_use_wait,
        };
        const descriptor_hash = try descriptor.digest();
        const arguments_hash = descriptor.argumentsHash();
        const descriptor_hex = std.fmt.bytesToHex(descriptor_hash, .lower);
        const arguments_hex = std.fmt.bytesToHex(arguments_hash, .lower);
        const requested_payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .schema = action_receipt.schema,
            .action_id = action_id,
            .descriptor_hash = &descriptor_hex,
            .causal_request_id = causal_request_id,
            .source_request_id = source_request_id,
            .run_id = descriptor.run_id,
            .session_id = descriptor.session_id,
            .actor_id = descriptor.actor_id,
            .provenance = descriptor.provenance,
            .workspace_id = descriptor.workspace_id,
            .owner_id = descriptor.owner_id,
            .owner_version = descriptor.owner_version,
            .owner_generation = descriptor.owner_generation,
            .capability = descriptor.capability,
            .effect = descriptor.effect,
            .target = descriptor.target,
            .canonical_arguments = descriptor.canonical_arguments,
            .arguments_hash = &arguments_hex,
            .authority = descriptor.authority,
            .expires_at_ms = descriptor.expires_at_ms,
            .cancellation_generation = descriptor.cancellation_generation,
            .requested_at_ms = now_ms,
        }, .{});
        defer self.allocator.free(requested_payload);
        try self.appendBrokerEventLocked(.action_requested, requested_payload, "permission:request", .pending);

        const rpc_session_active = !std.mem.eql(u8, descriptor.actor_id, "local-user") or self.sessions.contains(descriptor.session_id);
        const decision = try self.permission_broker.evaluate(descriptor, now_ms, rpc_session_active);
        const decided_at_ms = wallTimeAtLeast(self.io, now_ms);
        const decided_payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .schema = action_receipt.schema,
            .action_id = action_id,
            .descriptor_hash = &descriptor_hex,
            .decision_id = decision_id,
            .decision = decision,
            .authority = descriptor.authority,
            .approver_id = "runtime-policy",
            .provenance = descriptor.provenance,
            .decided_at_ms = decided_at_ms,
        }, .{});
        defer self.allocator.free(decided_payload);
        try self.appendBrokerEventLocked(.action_decided, decided_payload, "permission:automatic-decision", if (decision == .allow) .succeeded else .failed);
        if (decision == .deny) {
            try self.appendSystemUseSettlementLocked(action_id, descriptor_hash, decision_id, "", causal_request_id, actor_id, .policy_denial, now_ms, decided_at_ms, 0, "v1;status=policy_denial;source=broker");
            return error.SystemUsePolicyDenied;
        }
        try self.permission_broker.validateReserve(action_id, descriptor_hash, decision_id, descriptor.authority, "runtime-policy", descriptor.provenance, now_ms, descriptor.cancellation_generation, descriptor.owner_generation);
        const dispatch_begun_at_ms = wallTimeAtLeast(self.io, decided_at_ms);
        if (dispatch_begun_at_ms >= descriptor.expires_at_ms) return error.ActionNotAuthorized;
        var prepared_settlements = try self.prepareSystemUseSettlementsLocked(action_id, descriptor_hash, decision_id, lease_id, causal_request_id, actor_id, now_ms, decided_at_ms, dispatch_begun_at_ms);
        errdefer prepared_settlements.deinit(self);
        try self.reservePublicationSlotLocked();
        prepared_settlements.publication_reserved = true;
        const dispatch_payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .schema = action_receipt.schema,
            .action_id = action_id,
            .descriptor_hash = &descriptor_hex,
            .decision_id = decision_id,
            .lease_id = lease_id,
            .owner_generation = descriptor.owner_generation,
            .expires_at_ms = descriptor.expires_at_ms,
            .cancellation_generation = descriptor.cancellation_generation,
            .dispatch_begun_at_ms = dispatch_begun_at_ms,
        }, .{});
        defer self.allocator.free(dispatch_payload);
        try self.appendBrokerEventLocked(.action_dispatch_begun, dispatch_payload, "permission:dispatch", .pending);
        return .{
            .allocator = self.allocator,
            .runtime = self,
            .action_id = action_id,
            .causal_request_id = causal_request_id,
            .source_request_id = source_request_id,
            .actor_id = actor_id,
            .decision_id = decision_id,
            .lease_id = lease_id,
            .descriptor_hash = descriptor_hash,
            .requested_at_ms = now_ms,
            .decided_at_ms = decided_at_ms,
            .dispatch_begun_at_ms = dispatch_begun_at_ms,
            .prepared_settlements = prepared_settlements,
        };
    }

    fn settleSystemUseAction(self: *Runtime, action: *GovernedAction, status: action_receipt.Status, evidence: []const u8) !void {
        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        if (action.settled) return error.ActionAlreadySettled;
        var selected: ?*PreparedSettlement = null;
        for (&action.prepared_settlements.items) |*item| {
            if (item.status == status) {
                selected = item;
                break;
            }
        }
        const prepared = selected orelse return error.ActionReceiptMismatch;
        var timestamp_bytes: [19]u8 = undefined;
        const settled_at_ms = wallTimeAtLeast(self.io, @max(action.decided_at_ms, action.dispatch_begun_at_ms));
        try (action_receipt.Receipt{
            .action_id = action.action_id,
            .descriptor_hash = action.descriptor_hash,
            .decision_id = action.decision_id,
            .lease_id = action.lease_id,
            .actor_id = action.actor_id,
            .owner_generation = 1,
            .causal_request_id = action.causal_request_id,
            .requested_at_ms = action.requested_at_ms,
            .decided_at_ms = action.decided_at_ms,
            .dispatch_begun_at_ms = action.dispatch_begun_at_ms,
            .settled_at_ms = settled_at_ms,
            .status = status,
            .evidence = evidence,
        }).validate();
        _ = std.fmt.bufPrint(&timestamp_bytes, "{d: >19}", .{settled_at_ms}) catch unreachable;
        var evidence_bytes: [system_use_evidence_capacity]u8 = @splat(' ');
        const evidence_len = @min(evidence.len, evidence_bytes.len);
        for (evidence[0..evidence_len], 0..) |byte, index| evidence_bytes[index] = if (byte >= 0x20 and byte <= 0x7e and byte != '"' and byte != '\\') byte else '?';
        try self.event_store.patchPreparedPayload(prepared.append, prepared.settled_at_offset, &timestamp_bytes);
        try self.event_store.patchPreparedPayload(prepared.append, prepared.evidence_offset, &evidence_bytes);
        var record = try self.event_store.commitPrepared(&prepared.append);
        errdefer record.deinit(self.allocator);
        self.permission_broker.applySettled(action.action_id, action.descriptor_hash, status) catch |err| {
            self.event_store.failClosed();
            return err;
        };
        const node = prepared.node orelse return error.ActionReceiptMismatch;
        node.* = .{ .allocator = self.allocator, .record = record };
        prepared.node = null;
        prepared.append.deinit();
        action.settled = true;
        self.enqueueReservedPublishedLocked(node);
        action.prepared_settlements.publication_reserved = false;
    }

    fn prepareSystemUseSettlementsLocked(self: *Runtime, action_id: []const u8, descriptor_hash: action_receipt.Digest, decision_id: []const u8, lease_id: []const u8, causal_request_id: []const u8, actor_id: []const u8, requested_at_ms: u64, decided_at_ms: u64, dispatch_begun_at_ms: u64) !PreparedSettlements {
        var result: PreparedSettlements = undefined;
        result.publication_reserved = false;
        var initialized: usize = 0;
        errdefer {
            for (result.items[0..initialized]) |*item| {
                self.event_store.discardPrepared(&item.append);
                self.allocator.destroy(item.node.?);
            }
        }
        const descriptor_hex = std.fmt.bytesToHex(descriptor_hash, .lower);
        const evidence_placeholder = [_]u8{' '} ** system_use_evidence_capacity;
        for (settlement_statuses, 0..) |status, index| {
            const payload = try std.json.Stringify.valueAlloc(self.allocator, .{
                .schema = action_receipt.schema,
                .action_id = action_id,
                .descriptor_hash = &descriptor_hex,
                .decision_id = decision_id,
                .lease_id = lease_id,
                .actor_id = actor_id,
                .owner_generation = 1,
                .causal_request_id = causal_request_id,
                .requested_at_ms = requested_at_ms,
                .decided_at_ms = decided_at_ms,
                .dispatch_begun_at_ms = dispatch_begun_at_ms,
                .settled_at_ms = system_use_timestamp_placeholder,
                .status = status,
                .evidence = &evidence_placeholder,
            }, .{});
            defer self.allocator.free(payload);
            const timestamp_marker = "\"settled_at_ms\":" ++ std.fmt.comptimePrint("{d}", .{system_use_timestamp_placeholder});
            const timestamp_marker_offset = std.mem.indexOf(u8, payload, timestamp_marker) orelse return error.ActionReceiptMismatch;
            const settled_at_offset = timestamp_marker_offset + "\"settled_at_ms\":".len;
            const evidence_marker = "\"evidence\":\"";
            const evidence_marker_offset = std.mem.indexOf(u8, payload, evidence_marker) orelse return error.ActionReceiptMismatch;
            const evidence_offset = evidence_marker_offset + evidence_marker.len;
            const event_id = try self.allocDurableIdLocked("event");
            defer self.allocator.free(event_id);
            const node = try self.allocator.create(PublishedNode);
            errdefer self.allocator.destroy(node);
            const prepared = try self.event_store.prepareAppend(.{ .kind = .action_settled, .event_id = event_id, .payload = payload }, "permission:settlement", switch (status) {
                .success => .succeeded,
                .cancelled => .cancelled,
                .outcome_unknown => .interrupted,
                else => .failed,
            });
            result.items[index] = .{ .status = status, .append = prepared, .node = node, .settled_at_offset = settled_at_offset, .evidence_offset = evidence_offset };
            initialized += 1;
        }
        return result;
    }

    fn appendSystemUseSettlementLocked(self: *Runtime, action_id: []const u8, descriptor_hash: action_receipt.Digest, decision_id: []const u8, lease_id: []const u8, causal_request_id: []const u8, actor_id: []const u8, status: action_receipt.Status, requested_at_ms: u64, decided_at_ms: u64, dispatch_begun_at_ms: u64, evidence: []const u8) !void {
        const descriptor_hex = std.fmt.bytesToHex(descriptor_hash, .lower);
        const payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .schema = action_receipt.schema,
            .action_id = action_id,
            .descriptor_hash = &descriptor_hex,
            .decision_id = decision_id,
            .lease_id = lease_id,
            .actor_id = actor_id,
            .owner_generation = 1,
            .causal_request_id = causal_request_id,
            .requested_at_ms = requested_at_ms,
            .decided_at_ms = decided_at_ms,
            .dispatch_begun_at_ms = dispatch_begun_at_ms,
            .settled_at_ms = wallTimeAtLeast(self.io, @max(decided_at_ms, dispatch_begun_at_ms)),
            .status = status,
            .evidence = evidence,
        }, .{});
        defer self.allocator.free(payload);
        try self.appendBrokerEventLocked(.action_settled, payload, "permission:settlement", switch (status) {
            .success => .succeeded,
            .cancelled => .cancelled,
            .outcome_unknown => .interrupted,
            else => .failed,
        });
    }

    fn appendBrokerEventLocked(self: *Runtime, kind: event_store.EventKind, payload: []const u8, provenance: []const u8, settlement: event_store.Settlement) !void {
        const event_id = try self.allocDurableIdLocked("event");
        defer self.allocator.free(event_id);
        const node = try self.allocator.create(PublishedNode);
        var node_initialized = false;
        defer if (!node_initialized) self.allocator.destroy(node);
        try self.preflightPublicationLocked();
        var record = try self.event_store.append(.{ .kind = kind, .event_id = event_id, .payload = payload }, provenance, settlement);
        errdefer record.deinit(self.allocator);
        self.permission_broker.applyCommittedRecord(try record.view()) catch |err| {
            self.event_store.failClosed();
            return err;
        };
        node.* = .{ .allocator = self.allocator, .record = record };
        node_initialized = true;
        self.enqueuePublishedLocked(node);
    }

    fn reconcileOrphanedSystemUseActions(self: *Runtime) !void {
        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        const original_count = self.permission_broker.entries.items.len;
        var index: usize = 0;
        while (index < original_count) : (index += 1) {
            const phase = self.permission_broker.entries.items[index].phase;
            if (phase == .settled) continue;
            const action_id = try self.allocator.dupe(u8, self.permission_broker.entries.items[index].action_id);
            defer self.allocator.free(action_id);
            const descriptor_hash = self.permission_broker.entries.items[index].descriptor_hash;
            const decision_id: []u8 = if (self.permission_broker.entries.items[index].decision_id) |value|
                try self.allocator.dupe(u8, value)
            else
                try self.allocDurableIdLocked("decision");
            defer self.allocator.free(decision_id);
            if (phase == .requested) {
                const descriptor_hex = std.fmt.bytesToHex(descriptor_hash, .lower);
                const decided_payload = try std.json.Stringify.valueAlloc(self.allocator, .{
                    .schema = action_receipt.schema,
                    .action_id = action_id,
                    .descriptor_hash = &descriptor_hex,
                    .decision_id = decision_id,
                    .decision = permission_broker.Decision.deny,
                    .authority = action_receipt.Authority.local_user,
                    .approver_id = "runtime-recovery",
                    .provenance = self.permission_broker.entries.items[index].descriptor_provenance orelse return error.ActionReceiptMismatch,
                    .decided_at_ms = wallTimeAtLeast(self.io, self.permission_broker.entries.items[index].requested_at_ms),
                }, .{});
                defer self.allocator.free(decided_payload);
                try self.appendBrokerEventLocked(.action_decided, decided_payload, "permission:recovery", .failed);
            }
            const current = self.permission_broker.find(action_id) orelse return error.ActionNotRequested;
            const lease_id = if (current.lease_id) |value| value else "";
            const causal_request_id = current.causal_request_id orelse return error.ActionReceiptMismatch;
            const actor_id = current.actor_id orelse return error.ActionReceiptMismatch;
            const status: action_receipt.Status = if (phase == .dispatch_begun) .outcome_unknown else .cancelled;
            try self.appendSystemUseSettlementLocked(action_id, descriptor_hash, decision_id, lease_id, causal_request_id, actor_id, status, current.requested_at_ms, current.decided_at_ms, current.dispatch_begun_at_ms, if (phase == .dispatch_begun) "v1;status=outcome_unknown;stage=restart-orphan;replayed=false" else "v1;status=cancelled;stage=restart-pre-dispatch;invoked=false");
        }
    }

    pub fn subscribe(self: *Runtime, after_sequence: u64, capacity: ?usize) !*Subscription {
        const requested_capacity = capacity orelse self.subscription_capacity;
        if (requested_capacity == 0 or requested_capacity > maximum_subscription_capacity) return error.InvalidSubscriptionCapacity;
        const subscription = try self.allocator.create(Subscription);
        errdefer self.allocator.destroy(subscription);
        const ring = try self.allocator.alloc(?*PublishedNode, requested_capacity);
        errdefer self.allocator.free(ring);
        @memset(ring, null);
        subscription.* = .{ .runtime = self, .id = 0, .ring = ring };
        errdefer subscription.drain();

        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        self.publisher.mutex.lockUncancelable(self.io);
        if (self.publisher.closing) {
            self.publisher.mutex.unlock(self.io);
            return error.PublisherClosed;
        }
        const high_water = self.publisher.high_water;
        if (after_sequence > high_water) {
            self.publisher.mutex.unlock(self.io);
            return error.CursorAhead;
        }
        if (self.publisher.subscriptions.items.len == maximum_subscriptions) {
            self.publisher.mutex.unlock(self.io);
            return error.TooManySubscriptions;
        }
        self.publisher.subscriptions.ensureUnusedCapacity(self.allocator, 1) catch |err| {
            self.publisher.mutex.unlock(self.io);
            return err;
        };
        subscription.id = self.publisher.next_subscription_id;
        self.publisher.next_subscription_id +%= 1;
        if (self.publisher.next_subscription_id == 0) self.publisher.next_subscription_id = 1;
        self.publisher.mutex.unlock(self.io);

        var catchup = CatchupContext{ .runtime = self, .subscription = subscription };
        _ = try self.event_store.visitCommittedFrom(after_sequence, high_water, @ptrCast(&catchup), catchupRecord);

        self.publisher.mutex.lockUncancelable(self.io);
        defer self.publisher.mutex.unlock(self.io);
        if (self.publisher.closing or self.publisher.high_water != high_water) return error.SubscriptionSnapshotChanged;
        self.publisher.subscriptions.appendAssumeCapacity(subscription);
        return subscription;
    }

    pub fn nextDurable(self: *Runtime, subscription: *Subscription, context: *anyopaque, sink: DurableSink) !void {
        if (subscription.runtime != self) return error.InvalidSubscription;
        self.publisher.mutex.lockUncancelable(self.io);
        if (subscription.consumer_active) {
            self.publisher.mutex.unlock(self.io);
            return error.SubscriptionBusy;
        }
        subscription.consumer_active = true;
        while (subscription.count == 0 and subscription.active and !self.publisher.closing) {
            self.publisher.condition.waitUncancelable(self.io, &self.publisher.mutex);
        }
        if (subscription.count == 0) {
            subscription.consumer_active = false;
            self.publisher.condition.broadcast(self.io);
            self.publisher.mutex.unlock(self.io);
            return error.SubscriptionClosed;
        }
        const node = subscription.pop();
        self.publisher.mutex.unlock(self.io);

        const callback_result = sink(context, viewRecord(&node.record));
        node.release();

        self.publisher.mutex.lockUncancelable(self.io);
        subscription.consumer_active = false;
        if (callback_result) |_| {} else |_| {
            subscription.active = false;
            subscription.drain();
        }
        self.publisher.condition.broadcast(self.io);
        self.publisher.mutex.unlock(self.io);
        try callback_result;
    }

    /// Exclusive with nextDurable for this subscription. The pointer is invalid
    /// after this call returns and must not be used from inside its callback.
    pub fn unsubscribe(self: *Runtime, subscription: *Subscription) void {
        if (subscription.runtime != self) return;
        self.publisher.mutex.lockUncancelable(self.io);
        while (subscription.consumer_active) self.publisher.condition.waitUncancelable(self.io, &self.publisher.mutex);
        var index: ?usize = null;
        for (self.publisher.subscriptions.items, 0..) |candidate, candidate_index| {
            if (candidate == subscription) {
                index = candidate_index;
                break;
            }
        }
        if (index == null) {
            self.publisher.mutex.unlock(self.io);
            return;
        }
        subscription.active = false;
        subscription.drain();
        _ = self.publisher.subscriptions.orderedRemove(index.?);
        self.publisher.condition.broadcast(self.io);
        self.publisher.mutex.unlock(self.io);
        self.allocator.free(subscription.ring);
        self.allocator.destroy(subscription);
    }

    pub fn highWater(self: *Runtime) u64 {
        self.publisher.mutex.lockUncancelable(self.io);
        defer self.publisher.mutex.unlock(self.io);
        return self.publisher.high_water;
    }

    pub fn sessionMessages(self: *Runtime, session_id: []const u8, allocator: std.mem.Allocator) !conversation.Loaded {
        self.writer_mutex.lockUncancelable(self.io);
        const state = self.sessions.get(session_id) orelse {
            self.writer_mutex.unlock(self.io);
            return error.SessionNotFound;
        };
        const branch_id = state.projection.selected_branch_id orelse {
            self.writer_mutex.unlock(self.io);
            return error.UnknownBranch;
        };
        const branch = state.projection.branchMessages(allocator, branch_id) catch |err| {
            self.writer_mutex.unlock(self.io);
            return err;
        };
        self.writer_mutex.unlock(self.io);
        defer {
            for (branch) |*message| message.deinit(allocator);
            allocator.free(branch);
        }
        return conversation.loadBranchMessages(branch, allocator);
    }

    /// Allocate a collision-free identifier owned by the current runtime
    /// epoch without creating durable state. The caller owns the result.
    pub fn allocateSessionId(self: *Runtime, allocator: std.mem.Allocator) ![]u8 {
        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        self.mutex.lockUncancelable(self.io);
        const closing = self.closing;
        self.mutex.unlock(self.io);
        if (closing) return error.RuntimeClosing;
        const first_candidate = self.next_durable_id;
        while (true) {
            const candidate = try self.allocDurableIdLocked("session");
            if (self.sessions.contains(candidate)) {
                self.allocator.free(candidate);
                if (self.next_durable_id == first_candidate) return error.SessionIdSpaceExhausted;
                continue;
            }
            defer self.allocator.free(candidate);
            return allocator.dupe(u8, candidate);
        }
    }

    /// Create one durable session with an already allocated identifier. A
    /// collision fails before the event append instead of selecting or
    /// modifying the existing session.
    pub fn createSessionWithId(self: *Runtime, session_id: []const u8, value: ModelSelection) !void {
        if (session_id.len == 0 or session_id.len > event_store.max_event_id_bytes) return error.InvalidSessionId;
        try validateSelection(&self.model_profiles, value);
        if (self.provider_registry) |*registry| try validateProviderSelection(registry, value);
        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        self.mutex.lockUncancelable(self.io);
        const closing = self.closing;
        self.mutex.unlock(self.io);
        if (closing) return error.RuntimeClosing;
        if (self.sessions.contains(session_id)) return error.SessionAlreadyExists;
        try self.preflightPublicationSlotsLocked(1);
        _ = try self.ensureSessionLockedWriter(session_id, value);
    }

    /// Import a bounded historical transcript without replaying tools or provider calls.
    /// Existing session identifiers are an idempotent no-op.
    pub fn importSession(self: *Runtime, session_id: []const u8, value: ModelSelection, messages: []const protocol.Message) !bool {
        if (session_id.len == 0 or session_id.len > event_store.max_event_id_bytes) return error.InvalidSessionId;
        if (messages.len > maximum_session_history_items) return error.InvalidSessionHistoryLimit;
        try validateSelection(&self.model_profiles, value);
        if (self.provider_registry) |*registry| try validateProviderSelection(registry, value);

        // Validate every message before the first durable write. This prevents malformed
        // migration input from leaving a partially imported transcript.
        for (messages) |message| {
            const encoded = try conversation.encodeMessage(self.allocator, message);
            self.allocator.free(encoded);
        }

        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        self.mutex.lockUncancelable(self.io);
        const closing = self.closing;
        self.mutex.unlock(self.io);
        if (closing) return error.RuntimeClosing;
        if (self.sessions.contains(session_id)) return false;
        try self.preflightPublicationSlotsLocked(messages.len + 1);
        const state = try self.ensureSessionLockedWriter(session_id, value);
        for (messages) |message| try self.appendProtocolMessageForSessionLocked(state, session_id, message, null, null);
        return true;
    }

    /// Create one durable session with an identifier owned by the runtime
    /// epoch. Existing recovered identifiers are checked before any event is
    /// appended, so a restarted adapter cannot recreate an old session ID.
    pub fn createSession(self: *Runtime, value: ModelSelection, allocator: std.mem.Allocator) ![]u8 {
        const session_id = try self.allocateSessionId(allocator);
        errdefer allocator.free(session_id);
        try self.createSessionWithId(session_id, value);
        return session_id;
    }

    /// Return at most `limit` summaries in stable identifier order. The
    /// runtime scans the rebuildable projection but allocates only one page.
    pub fn listSessions(self: *Runtime, allocator: std.mem.Allocator, after_session_id: ?[]const u8, limit: usize) !SessionList {
        return self.listSessionsOrdered(allocator, after_session_id, limit, .ascending);
    }

    /// The cursor is exclusive in the requested order. Ascending pages keep
    /// the existing forward `after` behavior; descending pages use `before`.
    pub fn listSessionsOrdered(self: *Runtime, allocator: std.mem.Allocator, cursor_session_id: ?[]const u8, limit: usize, order: PageOrder) !SessionList {
        if (limit == 0 or limit > maximum_session_list_items) return error.InvalidSessionListLimit;
        if (cursor_session_id) |cursor| if (cursor.len == 0 or cursor.len > event_store.max_event_id_bytes) return error.InvalidSessionId;
        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);

        var result: std.ArrayList(SessionSummary) = .empty;
        errdefer {
            for (result.items) |*item| item.deinit(allocator);
            result.deinit(allocator);
        }
        try result.ensureTotalCapacityPrecise(allocator, limit);
        var iterator = self.sessions.iterator();
        while (iterator.next()) |entry| {
            const session_id = entry.key_ptr.*;
            if (cursor_session_id) |cursor| if (!sessionIdPrecedes(order, cursor, session_id)) continue;

            if (result.items.len == limit) {
                var last_index: usize = 0;
                for (result.items[1..], 1..) |item, index| {
                    if (sessionIdPrecedes(order, result.items[last_index].session_id, item.session_id)) last_index = index;
                }
                if (!sessionIdPrecedes(order, session_id, result.items[last_index].session_id)) continue;
                const replacement = try cloneSessionSummary(allocator, session_id, entry.value_ptr.*);
                result.items[last_index].deinit(allocator);
                result.items[last_index] = replacement;
                continue;
            }
            result.appendAssumeCapacity(try cloneSessionSummary(allocator, session_id, entry.value_ptr.*));
        }
        std.mem.sort(SessionSummary, result.items, order, lessSessionSummary);

        var has_more = false;
        if (result.items.len != 0) {
            const last = result.items[result.items.len - 1].session_id;
            var remainder = self.sessions.keyIterator();
            while (remainder.next()) |candidate| {
                if (sessionIdPrecedes(order, last, candidate.*)) {
                    has_more = true;
                    break;
                }
            }
        }
        return .{ .allocator = allocator, .sessions = try result.toOwnedSlice(allocator), .has_more = has_more };
    }

    /// Snapshot and decode one bounded page from the selected durable branch.
    /// Durable identifiers remain paired with their exact decoded message.
    pub fn sessionHistory(
        self: *Runtime,
        session_id: []const u8,
        after_message_id: ?[]const u8,
        limit: usize,
        allocator: std.mem.Allocator,
    ) !SessionHistory {
        return self.sessionHistoryOrdered(session_id, after_message_id, limit, allocator, .ascending);
    }

    pub fn sessionHistoryOrdered(
        self: *Runtime,
        session_id: []const u8,
        cursor_message_id: ?[]const u8,
        limit: usize,
        allocator: std.mem.Allocator,
        order: PageOrder,
    ) !SessionHistory {
        if (limit == 0 or limit > maximum_session_history_items) return error.InvalidSessionHistoryLimit;
        self.writer_mutex.lockUncancelable(self.io);
        const state = self.sessions.get(session_id) orelse {
            self.writer_mutex.unlock(self.io);
            return error.SessionNotFound;
        };
        const branch_id = state.projection.selected_branch_id orelse {
            self.writer_mutex.unlock(self.io);
            return error.UnknownBranch;
        };
        const owned_session_id = allocator.dupe(u8, session_id) catch |err| {
            self.writer_mutex.unlock(self.io);
            return err;
        };
        errdefer allocator.free(owned_session_id);
        const owned_branch_id = allocator.dupe(u8, branch_id) catch |err| {
            self.writer_mutex.unlock(self.io);
            return err;
        };
        errdefer allocator.free(owned_branch_id);
        const message_order: session.PageOrder = switch (order) {
            .ascending => .ascending,
            .descending => .descending,
        };
        var page = state.projection.branchMessagesPageOrderedBounded(allocator, branch_id, cursor_message_id, limit, maximum_session_history_payload_bytes, message_order) catch |err| {
            self.writer_mutex.unlock(self.io);
            return err;
        };
        self.writer_mutex.unlock(self.io);
        errdefer page.deinit();

        var decoded = try conversation.loadBranchMessages(page.messages, allocator);
        errdefer decoded.deinit();
        if (decoded.messages.len != page.messages.len) return error.InvalidConversation;
        return .{
            .allocator = allocator,
            .session_id = owned_session_id,
            .branch_id = owned_branch_id,
            .page = page,
            .decoded = decoded,
        };
    }

    fn ensureSessionLockedWriter(self: *Runtime, session_id: []const u8, initial_selection: ModelSelection) !*SessionState {
        if (self.sessions.get(session_id)) |state| {
            if (state.projection.selected_selection == null) try self.appendSelectionLocked(state, session_id, initial_selection);
            return state;
        }
        const key = try self.allocator.dupe(u8, session_id);
        var key_inserted = false;
        errdefer if (!key_inserted) self.allocator.free(key);
        const state = try self.allocator.create(SessionState);
        var state_inserted = false;
        errdefer if (!state_inserted) self.allocator.destroy(state);
        state.* = .{ .projection = session.DurableProjection.init(self.allocator) };
        errdefer if (!state_inserted) state.deinit();
        try self.sessions.ensureUnusedCapacity(1);
        const payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .session_id = session_id,
            .branch_id = "main",
            .provider = initial_selection.provider,
            .model = initial_selection.model,
            .context_window = initial_selection.context_window,
            .output_limit = initial_selection.output_limit,
            .reasoning = initial_selection.reasoning,
            .cache_retention = initial_selection.cache_retention,
            .cache_aggressiveness = initial_selection.cache_aggressiveness,
            .transport = initial_selection.transport,
        }, .{ .emit_null_optional_fields = false });
        defer self.allocator.free(payload);
        try self.appendSessionEventLocked(state, .session_created, payload, .succeeded, "runtime:session-create");
        self.sessions.putAssumeCapacity(key, state);
        key_inserted = true;
        state_inserted = true;
        return state;
    }

    fn findRunLocked(self: *Runtime, run_id: RunId) !*Run {
        if (run_id == 0) return error.RunNotFound;
        for (self.runs.items) |run| if (run.id == run_id) return run;
        return error.RunNotFound;
    }

    fn completedSettlementLocked(self: *Runtime, run_id: RunId) ?Settlement {
        for (self.completed_runs.items) |completed| if (completed.settlement.run_id == run_id) return completed.settlement;
        return null;
    }

    fn retainSettlement(self: *Runtime, run: *Run) !Settlement {
        self.mutex.lockUncancelable(self.io);
        const session_id = self.sessions.getKey(run.settlement.?.session_id) orelse {
            run.reaping = false;
            self.condition.broadcast(self.io);
            self.mutex.unlock(self.io);
            return error.SessionNotFound;
        };
        var run_index: ?usize = null;
        for (self.runs.items, 0..) |candidate, index| if (candidate == run) {
            run_index = index;
            break;
        };
        if (run_index == null) {
            run.reaping = false;
            self.condition.broadcast(self.io);
            self.mutex.unlock(self.io);
            return error.RunNotFound;
        }
        if (self.completed_runs.items.len == completed_run_limit) {
            const evicted = self.completed_runs.orderedRemove(0);
            if (evicted.settlement.provider_failure) |failure| deinitProviderFailure(self.allocator, failure);
        }
        var settlement = run.settlement.?;
        settlement.session_id = session_id;
        self.completed_runs.append(self.allocator, .{ .settlement = settlement }) catch |err| {
            run.reaping = false;
            self.condition.broadcast(self.io);
            self.mutex.unlock(self.io);
            return err;
        };
        if (settlement.provider_failure != null) run.owned_provider_failure = null;
        _ = self.runs.orderedRemove(run_index.?);
        self.condition.broadcast(self.io);
        self.mutex.unlock(self.io);

        self.deinitRun(run);
        return settlement;
    }

    fn executeRun(self: *Runtime, run: *Run) !void {
        try self.emit(run, .{ .run_started = .{
            .input_message_id = run.input_message_id,
            .assistant_message_id = run.assistant_message_id,
        } });
        self.writer_mutex.lockUncancelable(self.io);
        if (run.cancel_requested.load(.acquire)) {
            self.writer_mutex.unlock(self.io);
            return error.Canceled;
        }
        const state = self.sessions.get(run.request.session_id) orelse {
            self.writer_mutex.unlock(self.io);
            return error.SessionNotFound;
        };
        const branch_id = state.projection.selected_branch_id orelse {
            self.writer_mutex.unlock(self.io);
            return error.UnknownBranch;
        };
        const branch = state.projection.branchMessages(self.allocator, branch_id) catch |err| {
            self.writer_mutex.unlock(self.io);
            return err;
        };
        self.writer_mutex.unlock(self.io);
        defer {
            for (branch) |*message| message.deinit(self.allocator);
            self.allocator.free(branch);
        }
        var initial = try conversation.loadBranchMessages(branch, self.allocator);
        defer initial.deinit();
        run.initial_message_events_remaining = initial.messages.len;
        const provider_session_id = if (run.provider_dialect) |dialect| sessionIdForDialect(dialect, run.request.session_id) else run.request.session_id;

        var result = try self.harness.run({}, initial.messages, run.request.prompt, .{
            .causal = &run.causal,
            .system_override = run.request.system_override,
        }, .{
            .allocator = self.allocator,
            .model = &run.model,
            .stream_context = run,
            .stream = runtimeStream,
            .stream_options = .{
                .max_tokens = run.selection.output_limit,
                .reasoning = run.selection.reasoning,
                .cache_retention = run.selection.cache_retention,
                .cache_aggressiveness = run.selection.cache_aggressiveness,
                .transport = run.selection.transport,
                .session_id = provider_session_id,
            },
            .telemetry_context = self,
            .telemetry = if (self.telemetry != null) runtimeTelemetry else null,
            .request_identity_context = run,
            .take_request_identity = takeRunRequestIdentity,
            .telemetry_epoch = self.telemetry_epoch,
            .telemetry_invalidation = self.telemetry_invalidation,
            .event_context = run,
            .emit = runEvent,
            .hook_context = run,
            .before_tool = if (self.approve_tool != null) approveRuntimeTool else null,
            .parallel_context = self,
            .parallel_tool = isParallelRuntimeTool,
            .cancel_context = run,
            .is_cancelled = isRunCancelled,
            .max_steps = self.max_steps,
            .compaction_settings = .{ .enabled = false },
        });
        defer result.deinit();

        const settlement = try settlementFromResult(self.allocator, run, result, initial.messages.len);
        try self.finishRun(run, settlement);
    }

    fn failRun(self: *Runtime, run: *Run, category: FailureCategory) void {
        self.writer_mutex.lockUncancelable(self.io);
        if (run.cancel_requested.load(.acquire)) {
            self.writer_mutex.unlock(self.io);
            return;
        }
        const state = self.sessions.get(run.request.session_id);
        if (state) |session_state| self.appendFailureLocked(session_state, run) catch |err| {
            self.writer_mutex.unlock(self.io);
            self.abortRun(run, err);
            return;
        };
        self.writer_mutex.unlock(self.io);
        self.emit(run, .{ .typed_failure = .{ .category = category } }) catch {};
        const reason: StopReason = if (category == .canceled) .canceled else .failed;
        self.finishRun(run, .{
            .run_id = run.id,
            .session_id = run.request.session_id,
            .stop_reason = reason,
            .step_count = 0,
            .usage = .{},
            .failure = category,
        }) catch |err| self.abortRun(run, err);
    }

    fn finishRun(self: *Runtime, run: *Run, settlement: Settlement) !void {
        var final = settlement;
        self.mutex.lockUncancelable(self.io);
        while (run.canceling and run.settlement == null) self.condition.waitUncancelable(self.io, &self.mutex);
        if (run.settlement != null or run.finalizing) {
            self.mutex.unlock(self.io);
            return;
        }
        run.finalizing = true;
        if (run.cancel_requested.load(.acquire) and final.stop_reason != .canceled) {
            final.stop_reason = .canceled;
            final.failure = .canceled;
            final.provider_failure = null;
        }
        self.mutex.unlock(self.io);

        self.writer_mutex.lockUncancelable(self.io);
        const state = self.sessions.get(run.request.session_id) orelse {
            self.writer_mutex.unlock(self.io);
            self.clearFinalizingWithError(run, error.SessionNotFound);
            return error.SessionNotFound;
        };
        self.appendRunSettlementLocked(state, run, settlementState(final)) catch |err| {
            self.writer_mutex.unlock(self.io);
            self.clearFinalizingWithError(run, err);
            return err;
        };
        self.writer_mutex.unlock(self.io);

        // The durable settlement is linearized before borrowed callback delivery.
        self.emit(run, .{ .settled = final }) catch {};

        self.mutex.lockUncancelable(self.io);
        run.terminal_error = null;
        run.settlement = final;
        run.finalizing = false;
        _ = self.active_sessions.remove(run.request.session_id);
        self.condition.broadcast(self.io);
        self.mutex.unlock(self.io);
    }

    fn clearFinalizingWithError(self: *Runtime, run: *Run, err: anyerror) void {
        self.mutex.lockUncancelable(self.io);
        run.finalizing = false;
        run.terminal_error = err;
        _ = self.active_sessions.remove(run.request.session_id);
        self.condition.broadcast(self.io);
        self.mutex.unlock(self.io);
    }

    fn abortRun(self: *Runtime, run: *Run, err: anyerror) void {
        self.mutex.lockUncancelable(self.io);
        if (run.settlement == null) run.terminal_error = err;
        run.finalizing = false;
        run.canceling = false;
        _ = self.active_sessions.remove(run.request.session_id);
        self.condition.broadcast(self.io);
        self.mutex.unlock(self.io);
    }

    fn allocDurableIdLocked(self: *Runtime, prefix: []const u8) ![]u8 {
        const id = try std.fmt.allocPrint(self.allocator, "{s}-{s}-{d}", .{ prefix, self.epoch_id, self.next_durable_id });
        if (id.len > event_store.max_event_id_bytes) {
            self.allocator.free(id);
            return error.DurableIdTooLong;
        }
        self.next_durable_id +%= 1;
        if (self.next_durable_id == 0) self.next_durable_id = 1;
        return id;
    }

    fn containsMessageIdLocked(self: *Runtime, message_id: []const u8, reservation_owner: ?*Run) bool {
        var iterator = self.sessions.valueIterator();
        while (iterator.next()) |state| {
            if (state.*.projection.containsMessageId(message_id)) return true;
        }
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        for (self.runs.items) |run| {
            if (run == reservation_owner) continue;
            if (std.mem.eql(u8, message_id, run.input_message_id) or std.mem.eql(u8, message_id, run.assistant_message_id)) return true;
        }
        return false;
    }

    fn allocUniqueMessageIdLocked(self: *Runtime, prohibited: ?[]const u8) ![]u8 {
        const first_counter = self.next_durable_id;
        while (true) {
            const candidate = try self.allocDurableIdLocked("message");
            const collides = self.containsMessageIdLocked(candidate, null);
            if (!collides and (prohibited == null or !std.mem.eql(u8, candidate, prohibited.?))) return candidate;
            self.allocator.free(candidate);
            if (self.next_durable_id == first_counter) return error.DurableIdExhausted;
        }
    }

    fn appendRuntimeEpochLocked(self: *Runtime) !void {
        var workspace_hash: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
        std.crypto.hash.sha2.Sha256.hash(self.workspace_root, &workspace_hash, .{});
        const workspace_hash_hex = std.fmt.bytesToHex(workspace_hash, .lower);
        const payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .workspace_hash = &workspace_hash_hex,
            .epoch_id = &self.epoch_id,
        }, .{});
        defer self.allocator.free(payload);
        const event_id = try std.fmt.allocPrint(self.allocator, "runtime-epoch-{s}", .{self.epoch_id});
        defer self.allocator.free(event_id);
        try self.appendUnprojectedLocked(.{
            .kind = .runtime_epoch_started,
            .event_id = event_id,
            .payload = payload,
        }, "runtime:startup", .succeeded);
    }

    fn settleInterruptedRunsLocked(self: *Runtime) !void {
        var iterator = self.sessions.iterator();
        while (iterator.next()) |entry| {
            const state = entry.value_ptr.*;
            for (state.projection.runs.items) |*durable_run| {
                if (durable_run.settled) continue;
                const payload = try std.json.Stringify.valueAlloc(self.allocator, .{
                    .session_id = entry.key_ptr.*,
                    .run_id = durable_run.id,
                }, .{});
                defer self.allocator.free(payload);
                try self.appendSessionEventLocked(state, .run_settled, payload, .interrupted, "runtime:recovery");
            }
        }
    }

    fn appendSelectionLocked(self: *Runtime, state: *SessionState, session_id: []const u8, value: ModelSelection) !void {
        const payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .session_id = session_id,
            .provider = value.provider,
            .model = value.model,
            .context_window = value.context_window,
            .output_limit = value.output_limit,
            .reasoning = value.reasoning,
            .cache_retention = value.cache_retention,
            .cache_aggressiveness = value.cache_aggressiveness,
            .transport = value.transport,
        }, .{ .emit_null_optional_fields = false });
        defer self.allocator.free(payload);
        try self.appendSessionEventLocked(state, .session_selection_changed, payload, .succeeded, "runtime:selection");
    }

    fn appendRunStartedLocked(self: *Runtime, state: *SessionState, run: *Run) !void {
        const payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .session_id = run.request.session_id,
            .run_id = run.durable_id,
            .assistant_message_id = run.assistant_message_id,
        }, .{});
        defer self.allocator.free(payload);
        try self.appendSessionEventLocked(state, .run_started, payload, .succeeded, "runtime:run-start");
    }

    fn appendProtocolMessageLocked(self: *Runtime, state: *SessionState, run: *Run, message: protocol.Message, explicit_message_id: ?[]const u8) !void {
        return self.appendProtocolMessageForSessionLocked(state, run.request.session_id, message, explicit_message_id, run);
    }

    fn appendProtocolMessageForSessionLocked(
        self: *Runtime,
        state: *SessionState,
        session_id: []const u8,
        message: protocol.Message,
        explicit_message_id: ?[]const u8,
        reservation_owner: ?*Run,
    ) !void {
        const branch_id = state.projection.selected_branch_id orelse return error.UnknownBranch;
        var parent_id: ?[]const u8 = null;
        for (state.projection.branches.items) |branch| {
            if (std.mem.eql(u8, branch.id, branch_id)) {
                parent_id = branch.head_message_id;
                break;
            }
        }
        const encoded = try conversation.encodeMessage(self.allocator, message);
        defer self.allocator.free(encoded);
        var prepared_title = if (state.title == null) try allocSessionTitle(self.allocator, message) else null;
        errdefer if (prepared_title) |title| self.allocator.free(title);
        var owned_message_id: ?[]u8 = null;
        defer if (owned_message_id) |message_id| self.allocator.free(message_id);
        const message_id = if (explicit_message_id) |value| value else blk: {
            owned_message_id = try self.allocUniqueMessageIdLocked(null);
            break :blk owned_message_id.?;
        };
        const uses_owned_assistant_reservation = if (reservation_owner) |run|
            explicit_message_id != null and std.mem.eql(u8, message_id, run.assistant_message_id)
        else
            false;
        if (!uses_owned_assistant_reservation and self.containsMessageIdLocked(message_id, reservation_owner)) return error.MessageIdAlreadyExists;
        const payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .session_id = session_id,
            .branch_id = branch_id,
            .message_id = message_id,
            .parent_id = parent_id,
            .role = "custom",
            .content = encoded,
        }, .{ .emit_null_optional_fields = false });
        defer self.allocator.free(payload);
        try self.appendSessionEventLocked(state, .message_appended, payload, .succeeded, "runtime:message");
        if (prepared_title) |title| {
            state.title = title;
            prepared_title = null;
        }
    }

    fn appendToolLocked(self: *Runtime, state: *SessionState, run: *Run, tool_id: []const u8, kind: event_store.EventKind) !void {
        const payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .session_id = run.request.session_id,
            .run_id = run.durable_id,
            .tool_id = tool_id,
        }, .{});
        defer self.allocator.free(payload);
        try self.appendSessionEventLocked(state, kind, payload, .succeeded, "runtime:tool");
    }

    fn appendFailureLocked(self: *Runtime, state: *SessionState, run: *Run) !void {
        if (run.durable_failure_observed) return;
        const payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .session_id = run.request.session_id,
            .run_id = run.durable_id,
        }, .{});
        defer self.allocator.free(payload);
        try self.appendSessionEventLocked(state, .failure, payload, .failed, "runtime:failure");
        run.durable_failure_observed = true;
    }

    fn appendCancellationLocked(self: *Runtime, state: *SessionState, run: *Run) !void {
        if (run.durable_cancel_requested) return;
        const payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .session_id = run.request.session_id,
            .run_id = run.durable_id,
        }, .{});
        defer self.allocator.free(payload);
        try self.appendSessionEventLocked(state, .cancellation_requested, payload, .cancelled, "runtime:cancellation");
        run.durable_cancel_requested = true;
    }

    fn appendRunSettlementLocked(self: *Runtime, state: *SessionState, run: *Run, settlement: event_store.Settlement) !void {
        const payload = try std.json.Stringify.valueAlloc(self.allocator, .{
            .session_id = run.request.session_id,
            .run_id = run.durable_id,
        }, .{});
        defer self.allocator.free(payload);
        try self.appendSessionEventLocked(state, .run_settled, payload, settlement, "runtime:run-settle");
    }

    fn appendSessionEventLocked(
        self: *Runtime,
        state: *SessionState,
        kind: event_store.EventKind,
        payload: []const u8,
        settlement: event_store.Settlement,
        provenance: []const u8,
    ) !void {
        const event_id = try self.allocDurableIdLocked("event");
        defer self.allocator.free(event_id);
        const event = event_store.Event{ .kind = kind, .event_id = event_id, .payload = payload };
        const node = try self.allocator.create(PublishedNode);
        var node_initialized = false;
        defer if (!node_initialized) self.allocator.destroy(node);
        try self.preflightPublicationLocked();
        const record = try state.projection.appendAndApply(&self.event_store, event, provenance, settlement);
        node.* = .{ .allocator = self.allocator, .record = record };
        node_initialized = true;
        self.enqueuePublishedLocked(node);
    }

    fn appendUnprojectedLocked(self: *Runtime, event: event_store.Event, provenance: []const u8, settlement: event_store.Settlement) !void {
        const node = try self.allocator.create(PublishedNode);
        var node_initialized = false;
        defer if (!node_initialized) self.allocator.destroy(node);
        try self.preflightPublicationLocked();
        const record = try self.event_store.append(event, provenance, settlement);
        node.* = .{ .allocator = self.allocator, .record = record };
        node_initialized = true;
        self.enqueuePublishedLocked(node);
    }

    fn preflightPublicationLocked(self: *Runtime) !void {
        return self.preflightPublicationSlotsLocked(1);
    }

    fn preflightPublicationSlotsLocked(self: *Runtime, needed: usize) !void {
        self.publisher.mutex.lockUncancelable(self.io);
        defer self.publisher.mutex.unlock(self.io);
        if (self.publisher.closing) return error.PublisherClosed;
        if (!publicationSlotsAvailable(self.publisher.queue.len, self.publisher.count, self.publisher.reserved, needed)) return error.PublicationQueueFull;
    }

    fn reservePublicationSlotLocked(self: *Runtime) !void {
        self.publisher.mutex.lockUncancelable(self.io);
        defer self.publisher.mutex.unlock(self.io);
        if (self.publisher.closing) return error.PublisherClosed;
        if (!publicationSlotsAvailable(self.publisher.queue.len, self.publisher.count, self.publisher.reserved, 1)) return error.PublicationQueueFull;
        self.publisher.reserved += 1;
    }

    fn releasePublicationReservationLocked(self: *Runtime) void {
        self.publisher.mutex.lockUncancelable(self.io);
        defer self.publisher.mutex.unlock(self.io);
        std.debug.assert(self.publisher.reserved != 0);
        self.publisher.reserved -= 1;
    }

    fn enqueuePublishedLocked(self: *Runtime, node: *PublishedNode) void {
        self.publisher.mutex.lockUncancelable(self.io);
        defer self.publisher.mutex.unlock(self.io);
        std.debug.assert(!self.publisher.closing);
        std.debug.assert(self.publisher.count < self.publisher.queue.len);
        const index = (self.publisher.head + self.publisher.count) % self.publisher.queue.len;
        self.publisher.queue[index] = node;
        self.publisher.count += 1;
        self.publisher.high_water = node.record.global_sequence;
        self.publisher.condition.broadcast(self.io);
    }

    fn enqueueReservedPublishedLocked(self: *Runtime, node: *PublishedNode) void {
        self.publisher.mutex.lockUncancelable(self.io);
        defer self.publisher.mutex.unlock(self.io);
        std.debug.assert(!self.publisher.closing);
        std.debug.assert(self.publisher.reserved != 0);
        std.debug.assert(self.publisher.count < self.publisher.queue.len);
        self.publisher.reserved -= 1;
        const index = (self.publisher.head + self.publisher.count) % self.publisher.queue.len;
        self.publisher.queue[index] = node;
        self.publisher.count += 1;
        self.publisher.high_water = node.record.global_sequence;
        self.publisher.condition.broadcast(self.io);
    }

    fn recordMessage(self: *Runtime, run: *Run, message: protocol.Message) !void {
        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        if (run.cancel_requested.load(.acquire)) return error.Canceled;
        const state = self.sessions.get(run.request.session_id) orelse return error.SessionNotFound;
        const use_preallocated_assistant_id = switch (message) {
            .assistant => run.assistant_message_id_pending,
            else => false,
        };
        try self.appendProtocolMessageLocked(state, run, message, if (use_preallocated_assistant_id) run.assistant_message_id else null);
        if (use_preallocated_assistant_id) run.assistant_message_id_pending = false;
    }

    fn recordTool(self: *Runtime, run: *Run, tool_id: []const u8, kind: event_store.EventKind) !void {
        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        if (run.cancel_requested.load(.acquire)) return error.Canceled;
        const state = self.sessions.get(run.request.session_id) orelse return error.SessionNotFound;
        try self.appendToolLocked(state, run, tool_id, kind);
    }

    fn recordFailure(self: *Runtime, run: *Run) !void {
        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        if (run.cancel_requested.load(.acquire)) return error.Canceled;
        const state = self.sessions.get(run.request.session_id) orelse return error.SessionNotFound;
        try self.appendFailureLocked(state, run);
    }

    fn requestCancellation(self: *Runtime, run: *Run) !void {
        self.writer_mutex.lockUncancelable(self.io);
        defer self.writer_mutex.unlock(self.io);
        const state = self.sessions.get(run.request.session_id) orelse return error.SessionNotFound;
        try self.appendCancellationLocked(state, run);
    }

    fn settleUnstartedRunLocked(self: *Runtime, state: *SessionState, run: *Run, settlement: event_store.Settlement) void {
        self.appendFailureLocked(state, run) catch {};
        self.appendRunSettlementLocked(state, run, settlement) catch {};
    }

    fn removeUnstartedRun(self: *Runtime, run: *Run) void {
        self.mutex.lockUncancelable(self.io);
        _ = self.active_sessions.remove(run.request.session_id);
        for (self.runs.items, 0..) |candidate, index| if (candidate == run) {
            _ = self.runs.orderedRemove(index);
            break;
        };
        self.condition.broadcast(self.io);
        self.mutex.unlock(self.io);
    }

    fn clearCanceling(self: *Runtime, run: *Run) void {
        self.mutex.lockUncancelable(self.io);
        run.canceling = false;
        self.condition.broadcast(self.io);
        self.mutex.unlock(self.io);
    }

    fn emit(self: *Runtime, run: *Run, payload: EventPayload) !void {
        _ = self;
        run.sequence += 1;
        if (run.request.emit) |sink| sink(run.request.event_context orelse @ptrCast(run), .{
            .run_id = run.id,
            .session_id = run.request.session_id,
            .sequence = run.sequence,
            .payload = payload,
        }) catch return error.EventCallbackFailed;
    }

    fn deinitRun(self: *Runtime, run: *Run) void {
        if (run.owned_provider_failure) |failure| deinitProviderFailure(self.allocator, failure);
        run.causal.deinit();
        deinitSelection(self.allocator, run.selection);
        if (run.request.system_override) |value| self.allocator.free(value);
        self.allocator.free(run.request.prompt);
        self.allocator.free(run.request.session_id);
        self.allocator.free(run.assistant_message_id);
        self.allocator.free(run.input_message_id);
        self.allocator.free(run.durable_id);
        self.allocator.destroy(run);
    }
};

const RuntimeEpochWire = struct {
    workspace_hash: []const u8,
    epoch_id: []const u8,
};

const CatchupContext = struct {
    runtime: *Runtime,
    subscription: *Subscription,
};

fn viewRecord(record: *const event_store.RecordRef) event_store.RecordView {
    return .{
        .store_id = record.store_id,
        .record_id = record.record_id,
        .schema = record.schema,
        .kind = record.kind,
        .global_sequence = record.global_sequence,
        .timestamp = record.timestamp,
        .provenance = record.provenance,
        .settlement = record.settlement,
        .event_id = record.event_id,
        .payload = record.payload,
        .previous_hash = record.previous_hash,
        .payload_hash = record.payload_hash,
        .record_hash = record.record_hash,
    };
}

fn containsName(names: []const []const u8, name: []const u8) bool {
    for (names) |candidate| if (std.mem.eql(u8, candidate, name)) return true;
    return false;
}

fn isParallelRuntimeTool(raw: *anyopaque, name: []const u8) bool {
    const runtime: *Runtime = @ptrCast(@alignCast(raw));
    return !builtin_tools.isMutatingTool(name) and
        !containsName(runtime.mutating_tools, name) and
        !containsName(runtime.serial_tools, name);
}

fn approveRuntimeTool(raw: *anyopaque, call: *const protocol.ToolCall) !void {
    const run: *Run = @ptrCast(@alignCast(raw));
    const runtime = run.runtime;
    const mutating = builtin_tools.isMutatingTool(call.name) or containsName(runtime.mutating_tools, call.name);
    const always = containsName(runtime.approval_tools, call.name);
    if (!always and (!mutating or runtime.allow_mutating_tools)) return;
    const approve = runtime.approve_tool orelse return error.ToolApprovalUnavailable;
    if (!try approve(runtime.approval_context orelse raw, call, &run.cancel_requested)) {
        return error.ToolApprovalDenied;
    }
}

fn cloneRecord(allocator: std.mem.Allocator, record: event_store.RecordView) !event_store.RecordRef {
    const provenance = try allocator.dupe(u8, record.provenance);
    errdefer allocator.free(provenance);
    const event_id = try allocator.dupe(u8, record.event_id);
    errdefer allocator.free(event_id);
    const payload = try allocator.dupe(u8, record.payload);
    return .{
        .store_id = record.store_id,
        .record_id = record.record_id,
        .schema = record.schema,
        .kind = record.kind,
        .global_sequence = record.global_sequence,
        .timestamp = record.timestamp,
        .provenance = provenance,
        .settlement = record.settlement,
        .event_id = event_id,
        .payload = payload,
        .previous_hash = record.previous_hash,
        .payload_hash = record.payload_hash,
        .record_hash = record.record_hash,
    };
}

fn catchupRecord(raw: *anyopaque, record: event_store.RecordView) !void {
    const context: *CatchupContext = @ptrCast(@alignCast(raw));
    if (context.subscription.count == context.subscription.ring.len) return error.SubscriptionBacklogTooLarge;
    const node = try context.runtime.allocator.create(PublishedNode);
    errdefer context.runtime.allocator.destroy(node);
    node.* = .{ .allocator = context.runtime.allocator, .record = try cloneRecord(context.runtime.allocator, record) };
    context.subscription.enqueueAssumeCapacity(node);
    node.release();
}

fn publisherTask(self: *Runtime) anyerror!void {
    while (true) {
        self.publisher.mutex.lockUncancelable(self.io);
        while (self.publisher.count == 0 and !self.publisher.closing) {
            self.publisher.condition.waitUncancelable(self.io, &self.publisher.mutex);
        }
        if (self.publisher.count == 0 and self.publisher.closing) {
            self.publisher.mutex.unlock(self.io);
            return;
        }
        const node = self.publisher.queue[self.publisher.head].?;
        self.publisher.queue[self.publisher.head] = null;
        self.publisher.head = (self.publisher.head + 1) % self.publisher.queue.len;
        self.publisher.count -= 1;
        for (self.publisher.subscriptions.items) |subscription| {
            if (!subscription.active) continue;
            if (subscription.count == subscription.ring.len) {
                subscription.active = false;
                subscription.drain();
                continue;
            }
            subscription.enqueueAssumeCapacity(node);
        }
        self.publisher.condition.broadcast(self.io);
        self.publisher.mutex.unlock(self.io);
        node.release();
    }
}

fn stopPublisher(self: *Runtime) void {
    self.publisher.mutex.lockUncancelable(self.io);
    self.publisher.closing = true;
    self.publisher.condition.broadcast(self.io);
    self.publisher.mutex.unlock(self.io);
    if (self.publisher.future) |*future| {
        _ = future.await(self.io) catch {};
        self.publisher.future = null;
    }
}

fn deinitPublisher(self: *Runtime) void {
    while (self.publisher.count != 0) {
        const node = self.publisher.queue[self.publisher.head].?;
        self.publisher.queue[self.publisher.head] = null;
        self.publisher.head = (self.publisher.head + 1) % self.publisher.queue.len;
        self.publisher.count -= 1;
        node.release();
    }
    for (self.publisher.subscriptions.items) |subscription| {
        subscription.active = false;
        subscription.drain();
        self.allocator.free(subscription.ring);
        self.allocator.destroy(subscription);
    }
    self.publisher.subscriptions.deinit(self.allocator);
    self.allocator.free(self.publisher.queue);
    self.publisher.queue = &.{};
}

fn deinitSessions(self: *Runtime) void {
    var iterator = self.sessions.iterator();
    while (iterator.next()) |entry| {
        entry.value_ptr.*.deinit();
        self.allocator.destroy(entry.value_ptr.*);
        self.allocator.free(entry.key_ptr.*);
    }
    self.sessions.deinit();
}

fn cloneSessionSummary(allocator: std.mem.Allocator, session_id: []const u8, state: *const SessionState) !SessionSummary {
    const projection = &state.projection;
    const owned_session_id = try allocator.dupe(u8, session_id);
    errdefer allocator.free(owned_session_id);
    const owned_branch_id = try allocator.dupe(u8, projection.selected_branch_id orelse return error.UnknownBranch);
    errdefer allocator.free(owned_branch_id);
    const owned_title = if (state.title) |value| try allocator.dupe(u8, value) else null;
    errdefer if (owned_title) |value| allocator.free(value);
    const owned_model = if (projection.selected_model) |value| try allocator.dupe(u8, value) else null;
    return .{
        .session_id = owned_session_id,
        .branch_id = owned_branch_id,
        .title = owned_title,
        .model = owned_model,
        .created_at_ms = projection.created_at_ms,
        .updated_at_ms = projection.updated_at_ms,
    };
}

fn sessionIdPrecedes(order: PageOrder, left: []const u8, right: []const u8) bool {
    return switch (order) {
        .ascending => std.mem.order(u8, left, right) == .lt,
        .descending => std.mem.order(u8, left, right) == .gt,
    };
}

fn lessSessionSummary(order: PageOrder, left: SessionSummary, right: SessionSummary) bool {
    return sessionIdPrecedes(order, left.session_id, right.session_id);
}

const maximum_session_title_bytes: usize = 96;

fn allocSessionTitle(allocator: std.mem.Allocator, message: protocol.Message) !?[]u8 {
    const user = switch (message) {
        .user => |value| value,
        else => return null,
    };
    for (user.content) |content| switch (content) {
        .text => |value| {
            const trimmed = std.mem.trim(u8, value.text, " \t\r\n");
            if (trimmed.len == 0) continue;
            var end = @min(trimmed.len, maximum_session_title_bytes);
            while (end < trimmed.len and end > 0 and trimmed[end] & 0xc0 == 0x80) end -= 1;
            if (end == 0) continue;
            const source = trimmed[0..end];
            var length: usize = 0;
            var separated = false;
            for (source) |byte| {
                if (std.ascii.isWhitespace(byte)) {
                    separated = length != 0;
                    continue;
                }
                if (separated) {
                    length += 1;
                    separated = false;
                }
                length += 1;
            }
            if (length == 0) continue;
            const title = try allocator.alloc(u8, length);
            var index: usize = 0;
            separated = false;
            for (source) |byte| {
                if (std.ascii.isWhitespace(byte)) {
                    separated = index != 0;
                    continue;
                }
                if (separated) {
                    title[index] = ' ';
                    index += 1;
                    separated = false;
                }
                title[index] = byte;
                index += 1;
            }
            std.debug.assert(index == title.len);
            return title;
        },
        .image => {},
    };
    return null;
}

fn allocSessionTitleFromRecord(allocator: std.mem.Allocator, record: event_store.RecordView) !?[]u8 {
    if (record.kind != .message_appended) return null;
    const Wire = struct { content: ?[]const u8 = null };
    var wire = std.json.parseFromSlice(Wire, allocator, record.payload, .{ .ignore_unknown_fields = true }) catch return null;
    defer wire.deinit();
    const content = wire.value.content orelse return null;
    if (!std.mem.startsWith(u8, content, conversation.encoding_prefix)) return null;
    var parsed = std.json.parseFromSlice(protocol.Message, allocator, content[conversation.encoding_prefix.len..], .{
        .allocate = .alloc_always,
        .max_value_len = conversation.max_encoded_message_bytes,
    }) catch return null;
    defer parsed.deinit();
    return allocSessionTitle(allocator, parsed.value);
}

fn replayRuntimeRecord(raw: *anyopaque, record: event_store.RecordView) !void {
    const self: *Runtime = @ptrCast(@alignCast(raw));
    switch (record.kind) {
        .action_requested, .action_decided, .action_dispatch_begun, .action_settled => {
            try self.permission_broker.applyCommittedRecord(record);
            return;
        },
        else => {},
    }
    if (record.kind == .runtime_epoch_started) {
        var parsed = std.json.parseFromSlice(RuntimeEpochWire, self.allocator, record.payload, .{}) catch return error.InvalidRuntimeEpoch;
        defer parsed.deinit();
        var hash: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
        std.crypto.hash.sha2.Sha256.hash(self.workspace_root, &hash, .{});
        const expected = std.fmt.bytesToHex(hash, .lower);
        if (!std.mem.eql(u8, parsed.value.workspace_hash, &expected) or parsed.value.epoch_id.len != self.epoch_id.len) return error.SessionWorkspaceMismatch;
        return;
    }
    if (try session.extractSessionId(self.allocator, record)) |session_id| {
        defer self.allocator.free(session_id);
        var state = self.sessions.get(session_id);
        if (state == null) {
            if (record.kind != .session_created) return error.SessionNotCreated;
            const key = try self.allocator.dupe(u8, session_id);
            var inserted = false;
            errdefer if (!inserted) self.allocator.free(key);
            const created = try self.allocator.create(SessionState);
            errdefer if (!inserted) self.allocator.destroy(created);
            created.* = .{ .projection = session.DurableProjection.init(self.allocator) };
            errdefer if (!inserted) created.deinit();
            try self.sessions.put(key, created);
            inserted = true;
            state = created;
        }
        var prepared_title = if (state.?.title == null) try allocSessionTitleFromRecord(self.allocator, record) else null;
        errdefer if (prepared_title) |title| self.allocator.free(title);
        try state.?.projection.applyCommittedRecord(record);
        if (prepared_title) |title| {
            state.?.title = title;
            prepared_title = null;
        }
        return;
    }
    try self.instructions.applyCommittedRecord(record);
}

fn systemUseFailureStatus(err: anyerror) action_receipt.Status {
    return switch (err) {
        error.Canceled => .cancelled,
        error.SystemUseTimeout, error.BrowserTimeout => .timeout,
        error.SystemUseExternalFailure, error.BrowserExternalFailure => .external_failure,
        else => .outcome_unknown,
    };
}

fn browserFailureEvidence(buffer: *[system_use_evidence_capacity]u8, method: []const u8, err: anyerror) []const u8 {
    return std.fmt.bufPrint(buffer, "v1;status=failure;method=browser_{s};error={s}", .{ method, @errorName(err) }) catch "v1;status=failure;error=evidence-overflow";
}

fn shaHex(input: []const u8) [64]u8 {
    var digest: action_receipt.Digest = undefined;
    std.crypto.hash.sha2.Sha256.hash(input, &digest, .{});
    return std.fmt.bytesToHex(digest, .lower);
}

fn shaOf(input: []const u8) [64]u8 {
    return shaHex(input);
}

fn pngDimensions(allocator: std.mem.Allocator, encoded: []const u8) ![2]u32 {
    const decoded_len = std.base64.standard.Decoder.calcSizeForSlice(encoded) catch return error.BrowserInvalidResponse;
    if (decoded_len < 33 or decoded_len > 4 * 1024 * 1024) return error.BrowserInvalidResponse;
    const decoded = try allocator.alloc(u8, decoded_len);
    defer allocator.free(decoded);
    std.base64.standard.Decoder.decode(decoded, encoded) catch return error.BrowserInvalidResponse;
    if (!std.mem.eql(u8, decoded[0..8], "\x89PNG\r\n\x1a\n")) return error.BrowserInvalidResponse;
    if (!std.mem.eql(u8, decoded[12..16], "IHDR")) return error.BrowserInvalidResponse;
    return .{
        std.mem.readInt(u32, decoded[16..20], .big),
        std.mem.readInt(u32, decoded[20..24], .big),
    };
}

fn wallTimeAtLeast(io: std.Io, floor: u64) u64 {
    const current: u64 = @intCast(std.Io.Clock.real.now(io).toMilliseconds());
    return @max(current, floor);
}

fn publicationSlotsAvailable(capacity: usize, count: usize, reserved: usize, needed: usize) bool {
    if (count > capacity or reserved > capacity - count) return false;
    return needed <= capacity - count - reserved;
}

fn sleepSystemUsePoll(io: std.Io, duration_ms: u64, cancel: *const std.atomic.Value(bool)) !void {
    const deadline = std.Io.Clock.Timestamp.now(io, .awake).addDuration(.{ .clock = .awake, .raw = .fromMilliseconds(@intCast(duration_ms)) });
    while (true) {
        if (cancel.load(.acquire)) return error.Canceled;
        const now = std.Io.Clock.Timestamp.now(io, .awake);
        if (now.compare(.gte, deadline)) return;
        const remaining_ms = @max(@as(i64, 1), now.durationTo(deadline).raw.toMilliseconds());
        try std.Io.sleep(io, .fromMilliseconds(@intCast(@min(@as(i64, 5), remaining_ms))), .awake);
    }
}

fn systemUseFailureEvidence(buffer: *[system_use_evidence_capacity]u8, method: []const u8, err: anyerror, failure: SystemUseFailureEvidence) []const u8 {
    if (failure.sidecar_error_hash) |digest| {
        const hex = std.fmt.bytesToHex(digest, .lower);
        return std.fmt.bufPrint(buffer, "v1;status=failure;method={s};error={s};sidecar_sha256={s}", .{ method, @errorName(err), &hex }) catch "v1;status=failure;error=evidence-overflow";
    }
    return std.fmt.bufPrint(buffer, "v1;status=failure;method={s};error={s}", .{ method, @errorName(err) }) catch "v1;status=failure;error=evidence-overflow";
}

fn systemUseSuccessEvidence(buffer: *[system_use_evidence_capacity]u8, method: []const u8, capability: action_receipt.Capability, result: SystemUseResult) ![]const u8 {
    var result_digest: action_receipt.Digest = undefined;
    std.crypto.hash.sha2.Sha256.hash(result.json, &result_digest, .{});
    const result_hex = std.fmt.bytesToHex(result_digest, .lower);
    var parsed = std.json.parseFromSlice(std.json.Value, result.allocator, result.json, .{}) catch return error.InvalidSystemUseResponse;
    defer parsed.deinit();
    const value = parsed.value;
    if (std.mem.eql(u8, method, "permissions")) {
        const accessibility = value.object.get("accessibility").?.bool;
        const screen_recording = value.object.get("screenRecording").?.bool;
        return std.fmt.bufPrint(buffer, "v1;method=permissions;result_sha256={s};duration_us={d};accessibility={};screen_recording={}", .{ &result_hex, result.duration_us, accessibility, screen_recording }) catch error.ActionReceiptMismatch;
    }
    if (std.mem.eql(u8, method, "targets")) {
        return std.fmt.bufPrint(buffer, "v1;method=targets;result_sha256={s};duration_us={d};count={d}", .{ &result_hex, result.duration_us, value.array.items.len }) catch error.ActionReceiptMismatch;
    }
    if (std.mem.eql(u8, method, "observe")) {
        const semantic = value.object.get("semantic").?.object;
        const target_id = semantic.get("target").?.object.get("id").?.string;
        var target_digest: action_receipt.Digest = undefined;
        std.crypto.hash.sha2.Sha256.hash(target_id, &target_digest, .{});
        const target_hex = std.fmt.bytesToHex(target_digest, .lower);
        const count = semantic.get("elements").?.array.items.len;
        if (capability == .system_use_screenshot) {
            const screenshot = value.object.get("screenshot").?.object;
            var png_digest: action_receipt.Digest = undefined;
            std.crypto.hash.sha2.Sha256.hash(screenshot.get("data").?.string, &png_digest, .{});
            const png_hex = std.fmt.bytesToHex(png_digest, .lower);
            return std.fmt.bufPrint(buffer, "v1;method=observe;result_sha256={s};duration_us={d};target_sha256={s};elements={d};png_sha256={s};width={d};height={d}", .{ &result_hex, result.duration_us, &target_hex, count, &png_hex, screenshot.get("width").?.integer, screenshot.get("height").?.integer }) catch error.ActionReceiptMismatch;
        }
        return std.fmt.bufPrint(buffer, "v1;method=observe;result_sha256={s};duration_us={d};target_sha256={s};elements={d}", .{ &result_hex, result.duration_us, &target_hex, count }) catch error.ActionReceiptMismatch;
    }
    if (std.mem.eql(u8, method, "click")) {
        const actual_method = value.object.get("method").?.string;
        const focus = value.object.get("focusMode").?.string;
        const activated = value.object.get("activated").?.bool;
        return std.fmt.bufPrint(buffer, "v1;method=click;result_sha256={s};duration_us={d};actual={s};focus={s};activated={}", .{ &result_hex, result.duration_us, actual_method, focus, activated }) catch error.ActionReceiptMismatch;
    }
    if (std.mem.eql(u8, method, "type")) {
        return std.fmt.bufPrint(buffer, "v1;method=type;result_sha256={s};duration_us={d};focus=activate;activated=true", .{ &result_hex, result.duration_us }) catch error.ActionReceiptMismatch;
    }
    if (std.mem.eql(u8, method, "scroll")) {
        return std.fmt.bufPrint(buffer, "v1;method=scroll;result_sha256={s};duration_us={d};focus=activate;activated=true", .{ &result_hex, result.duration_us }) catch error.ActionReceiptMismatch;
    }
    if (std.mem.eql(u8, method, "setValue")) {
        var element_digest: action_receipt.Digest = undefined;
        var value_digest: action_receipt.Digest = undefined;
        std.crypto.hash.sha2.Sha256.hash(value.object.get("elementId").?.string, &element_digest, .{});
        std.crypto.hash.sha2.Sha256.hash(value.object.get("actualValue").?.string, &value_digest, .{});
        const element_hex = std.fmt.bytesToHex(element_digest, .lower);
        const value_hex = std.fmt.bytesToHex(value_digest, .lower);
        return std.fmt.bufPrint(buffer, "v1;method=setValue;result_sha256={s};duration_us={d};element_sha256={s};value_sha256={s}", .{ &result_hex, result.duration_us, &element_hex, &value_hex }) catch error.ActionReceiptMismatch;
    }
    return error.InvalidSystemUseResponse;
}

fn systemUseWaitEvidence(buffer: *[system_use_evidence_capacity]u8, observation: []const u8, elapsed_ms: i64, samples: u64, matched: bool, element_predicate: bool) ![]const u8 {
    var digest: action_receipt.Digest = undefined;
    std.crypto.hash.sha2.Sha256.hash(observation, &digest, .{});
    const hex = std.fmt.bytesToHex(digest, .lower);
    return std.fmt.bufPrint(buffer, "v1;method=wait;result_sha256={s};duration_us={d};matched={};samples={d};predicate={s}", .{ &hex, @as(u64, @intCast(@max(@as(i64, 0), elapsed_ms))) * 1000, matched, samples, if (element_predicate) "element" else "text" }) catch error.ActionReceiptMismatch;
}

fn systemUseTargetId(allocator: std.mem.Allocator, params_json: []const u8) ![]u8 {
    var parsed = std.json.parseFromSlice(std.json.Value, allocator, params_json, .{}) catch return error.InvalidSystemUseParameters;
    defer parsed.deinit();
    if (parsed.value != .object) return error.InvalidSystemUseParameters;
    const target = parsed.value.object.get("target") orelse return error.InvalidSystemUseParameters;
    if (target != .object) return error.InvalidSystemUseParameters;
    const id = target.object.get("id") orelse return error.InvalidSystemUseParameters;
    if (id != .string or id.string.len == 0 or id.string.len > action_receipt.maximum_identifier_bytes) return error.InvalidSystemUseParameters;
    return allocator.dupe(u8, id.string);
}

fn validateSystemUseResult(allocator: std.mem.Allocator, method: []const u8, params_json: []const u8, json: []const u8, screenshot_required: bool) !void {
    var parsed = std.json.parseFromSlice(std.json.Value, allocator, json, .{}) catch return error.InvalidSystemUseResponse;
    defer parsed.deinit();
    const value = parsed.value;
    if (std.mem.eql(u8, method, "permissions")) {
        if (value != .object) return error.InvalidSystemUseResponse;
        const accessibility = value.object.get("accessibility") orelse return error.InvalidSystemUseResponse;
        const screen = value.object.get("screenRecording") orelse return error.InvalidSystemUseResponse;
        if (accessibility != .bool or screen != .bool) return error.InvalidSystemUseResponse;
        return;
    }
    if (std.mem.eql(u8, method, "targets")) {
        if (value != .array or value.array.items.len > 5_000) return error.InvalidSystemUseResponse;
        for (value.array.items, 0..) |target, index| {
            const id = try validateSystemUseTarget(target);
            for (value.array.items[0..index]) |prior| {
                if (std.mem.eql(u8, id, try validateSystemUseTarget(prior))) return error.InvalidSystemUseResponse;
            }
        }
        return;
    }
    if (std.mem.eql(u8, method, "observe")) {
        if (value != .object) return error.InvalidSystemUseResponse;
        var params = std.json.parseFromSlice(std.json.Value, allocator, params_json, .{}) catch return error.InvalidSystemUseResponse;
        defer params.deinit();
        if (params.value != .object) return error.InvalidSystemUseResponse;
        const requested_target = params.value.object.get("target") orelse return error.InvalidSystemUseResponse;
        const requested_target_id = try validateSystemUseTarget(requested_target);
        const max_depth_value = params.value.object.get("maxDepth") orelse return error.InvalidSystemUseResponse;
        const max_elements_value = params.value.object.get("maxElements") orelse return error.InvalidSystemUseResponse;
        if (max_depth_value != .integer or max_depth_value.integer < 0 or max_depth_value.integer > 30 or
            max_elements_value != .integer or max_elements_value.integer < 1 or max_elements_value.integer > 5_000) return error.InvalidSystemUseResponse;
        const semantic_value = value.object.get("semantic") orelse return error.InvalidSystemUseResponse;
        if (semantic_value != .object) return error.InvalidSystemUseResponse;
        const actual_target = semantic_value.object.get("target") orelse return error.InvalidSystemUseResponse;
        if (!std.mem.eql(u8, requested_target_id, try validateSystemUseTarget(actual_target))) return error.InvalidSystemUseResponse;
        const elements = semantic_value.object.get("elements") orelse return error.InvalidSystemUseResponse;
        if (elements != .array or elements.array.items.len > @as(usize, @intCast(max_elements_value.integer))) return error.InvalidSystemUseResponse;
        const truncated = semantic_value.object.get("truncated") orelse return error.InvalidSystemUseResponse;
        if (truncated != .bool) return error.InvalidSystemUseResponse;
        for (elements.array.items, 0..) |element, index| {
            if (element != .object) return error.InvalidSystemUseResponse;
            const element_id = element.object.get("id") orelse return error.InvalidSystemUseResponse;
            const depth = element.object.get("depth") orelse return error.InvalidSystemUseResponse;
            const role = element.object.get("role") orelse return error.InvalidSystemUseResponse;
            if (element_id != .string or element_id.string.len == 0 or element_id.string.len > action_receipt.maximum_identifier_bytes or
                depth != .integer or depth.integer < 0 or depth.integer > max_depth_value.integer or
                role != .string or role.string.len == 0 or role.string.len > action_receipt.maximum_identifier_bytes) return error.InvalidSystemUseResponse;
            for (elements.array.items[0..index]) |prior| {
                const prior_id = prior.object.get("id").?.string;
                if (std.mem.eql(u8, prior_id, element_id.string)) return error.InvalidSystemUseResponse;
            }
        }
        if (screenshot_required) {
            const screenshot = value.object.get("screenshot") orelse return error.InvalidSystemUseResponse;
            if (screenshot != .object) return error.InvalidSystemUseResponse;
            const mime = screenshot.object.get("mimeType") orelse return error.InvalidSystemUseResponse;
            const data = screenshot.object.get("data") orelse return error.InvalidSystemUseResponse;
            const width = screenshot.object.get("width") orelse return error.InvalidSystemUseResponse;
            const height = screenshot.object.get("height") orelse return error.InvalidSystemUseResponse;
            if (mime != .string or !std.mem.eql(u8, mime.string, "image/png") or data != .string or data.string.len == 0 or width != .integer or width.integer <= 0 or height != .integer or height.integer <= 0) return error.InvalidSystemUseResponse;
            try validateSystemUsePng(allocator, data.string, @intCast(width.integer), @intCast(height.integer));
        }
        return;
    }
    if (std.mem.eql(u8, method, "click")) {
        if (value != .object) return error.InvalidSystemUseResponse;
        var params = std.json.parseFromSlice(std.json.Value, allocator, params_json, .{}) catch return error.InvalidSystemUseResponse;
        defer params.deinit();
        if (params.value != .object) return error.InvalidSystemUseResponse;
        const requested_focus = params.value.object.get("focusMode") orelse return error.InvalidSystemUseResponse;
        const action_method = value.object.get("method") orelse return error.InvalidSystemUseResponse;
        const focus = value.object.get("focusMode") orelse return error.InvalidSystemUseResponse;
        const activated = value.object.get("activated") orelse return error.InvalidSystemUseResponse;
        const requested_element = params.value.object.get("elementId");
        const requested_x = params.value.object.get("x");
        const requested_y = params.value.object.get("y");
        if ((requested_element == null) == (requested_x == null) or (requested_x == null) != (requested_y == null)) return error.InvalidSystemUseResponse;
        if (requested_focus != .string or action_method != .string or
            (!std.mem.eql(u8, action_method.string, "accessibility") and !std.mem.eql(u8, action_method.string, "event")) or
            focus != .string or !std.mem.eql(u8, focus.string, requested_focus.string) or activated != .bool or
            (std.mem.eql(u8, focus.string, "background") and activated.bool) or
            (std.mem.eql(u8, focus.string, "activate") and !activated.bool) or
            (std.mem.eql(u8, action_method.string, "event") and !activated.bool)) return error.InvalidSystemUseResponse;
        if (requested_element) |requested| {
            if (requested != .string) return error.InvalidSystemUseResponse;
            const actual_element = value.object.get("elementId") orelse return error.InvalidSystemUseResponse;
            if (actual_element != .string or !std.mem.eql(u8, actual_element.string, requested.string)) return error.InvalidSystemUseResponse;
        } else {
            if (requested_x.? != .integer or requested_y.? != .integer) return error.InvalidSystemUseResponse;
            const point = value.object.get("point") orelse return error.InvalidSystemUseResponse;
            if (point != .object or !jsonNumberEqualsInteger(point.object.get("x") orelse return error.InvalidSystemUseResponse, requested_x.?.integer) or
                !jsonNumberEqualsInteger(point.object.get("y") orelse return error.InvalidSystemUseResponse, requested_y.?.integer)) return error.InvalidSystemUseResponse;
        }
        return;
    }
    if (std.mem.eql(u8, method, "type")) {
        if (value != .object) return error.InvalidSystemUseResponse;
        var params = std.json.parseFromSlice(std.json.Value, allocator, params_json, .{}) catch return error.InvalidSystemUseResponse;
        defer params.deinit();
        if (params.value != .object) return error.InvalidSystemUseResponse;
        const requested_focus = params.value.object.get("focusMode") orelse return error.InvalidSystemUseResponse;
        const action_method = value.object.get("method") orelse return error.InvalidSystemUseResponse;
        const focus = value.object.get("focusMode") orelse return error.InvalidSystemUseResponse;
        const activated = value.object.get("activated") orelse return error.InvalidSystemUseResponse;
        if (requested_focus != .string or !std.mem.eql(u8, requested_focus.string, "activate") or
            action_method != .string or !std.mem.eql(u8, action_method.string, "event") or
            focus != .string or !std.mem.eql(u8, focus.string, "activate") or activated != .bool or !activated.bool) return error.InvalidSystemUseResponse;
        return;
    }
    if (std.mem.eql(u8, method, "scroll")) {
        if (value != .object) return error.InvalidSystemUseResponse;
        var params = std.json.parseFromSlice(std.json.Value, allocator, params_json, .{}) catch return error.InvalidSystemUseResponse;
        defer params.deinit();
        if (params.value != .object) return error.InvalidSystemUseResponse;
        const requested_focus = params.value.object.get("focusMode") orelse return error.InvalidSystemUseResponse;
        const action_method = value.object.get("method") orelse return error.InvalidSystemUseResponse;
        const focus = value.object.get("focusMode") orelse return error.InvalidSystemUseResponse;
        const activated = value.object.get("activated") orelse return error.InvalidSystemUseResponse;
        if (requested_focus != .string or !std.mem.eql(u8, requested_focus.string, "activate") or
            action_method != .string or !std.mem.eql(u8, action_method.string, "event") or
            focus != .string or !std.mem.eql(u8, focus.string, "activate") or activated != .bool or !activated.bool) return error.InvalidSystemUseResponse;
        return;
    }
    if (std.mem.eql(u8, method, "setValue")) {
        if (value != .object) return error.InvalidSystemUseResponse;
        var params = std.json.parseFromSlice(std.json.Value, allocator, params_json, .{}) catch return error.InvalidSystemUseResponse;
        defer params.deinit();
        if (params.value != .object) return error.InvalidSystemUseResponse;
        const requested_element = params.value.object.get("elementId") orelse return error.InvalidSystemUseResponse;
        const element = value.object.get("elementId") orelse return error.InvalidSystemUseResponse;
        const focus = value.object.get("focusMode") orelse return error.InvalidSystemUseResponse;
        const activated = value.object.get("activated") orelse return error.InvalidSystemUseResponse;
        const requested_value = params.value.object.get("value") orelse return error.InvalidSystemUseResponse;
        const actual_value = value.object.get("actualValue") orelse return error.InvalidSystemUseResponse;
        if (requested_element != .string or element != .string or !std.mem.eql(u8, element.string, requested_element.string) or
            requested_value != .string or actual_value != .string or !std.mem.eql(u8, actual_value.string, requested_value.string) or
            focus != .string or !std.mem.eql(u8, focus.string, "background") or activated != .bool or activated.bool) return error.InvalidSystemUseResponse;
        return;
    }
    return error.InvalidSystemUseResponse;
}

fn validateSystemUseTarget(value: std.json.Value) ![]const u8 {
    if (value != .object) return error.InvalidSystemUseResponse;
    const adapter = value.object.get("adapter") orelse return error.InvalidSystemUseResponse;
    const id = value.object.get("id") orelse return error.InvalidSystemUseResponse;
    const kind = value.object.get("kind") orelse return error.InvalidSystemUseResponse;
    const label = value.object.get("label") orelse return error.InvalidSystemUseResponse;
    if (adapter != .string or !std.mem.eql(u8, adapter.string, "macos") or id != .string or id.string.len == 0 or id.string.len > action_receipt.maximum_identifier_bytes or
        kind != .string or !std.mem.eql(u8, kind.string, "window") or label != .string or label.string.len == 0 or label.string.len > action_receipt.maximum_identifier_bytes) return error.InvalidSystemUseResponse;
    return id.string;
}

fn jsonNumberEqualsInteger(value: std.json.Value, expected: i64) bool {
    return switch (value) {
        .integer => |number| number == expected,
        .float => |number| std.math.isFinite(number) and number == @as(f64, @floatFromInt(expected)),
        else => false,
    };
}

fn validateSystemUsePng(allocator: std.mem.Allocator, encoded: []const u8, expected_width: u32, expected_height: u32) !void {
    const decoded_len = std.base64.standard.Decoder.calcSizeForSlice(encoded) catch return error.InvalidSystemUseResponse;
    if (decoded_len < 33 or decoded_len > maximum_system_use_record_bytes) return error.InvalidSystemUseResponse;
    const decoded = try allocator.alloc(u8, decoded_len);
    defer allocator.free(decoded);
    std.base64.standard.Decoder.decode(decoded, encoded) catch return error.InvalidSystemUseResponse;
    const png_signature = "\x89PNG\r\n\x1a\n";
    if (!std.mem.eql(u8, decoded[0..png_signature.len], png_signature)) return error.InvalidSystemUseResponse;
    var offset: usize = png_signature.len;
    var chunk_index: usize = 0;
    var saw_idat = false;
    var saw_iend = false;
    while (offset < decoded.len) : (chunk_index += 1) {
        if (decoded.len - offset < 12) return error.InvalidSystemUseResponse;
        const data_len_u32 = std.mem.readInt(u32, @ptrCast(decoded[offset..].ptr), .big);
        const data_len: usize = @intCast(data_len_u32);
        if (data_len > decoded.len - offset - 12) return error.InvalidSystemUseResponse;
        const kind = decoded[offset + 4 .. offset + 8];
        const data_start = offset + 8;
        const crc_start = data_start + data_len;
        const expected_crc = std.mem.readInt(u32, @ptrCast(decoded[crc_start..].ptr), .big);
        if (std.hash.Crc32.hash(decoded[offset + 4 .. crc_start]) != expected_crc) return error.InvalidSystemUseResponse;
        if (chunk_index == 0) {
            if (!std.mem.eql(u8, kind, "IHDR") or data_len != 13 or
                std.mem.readInt(u32, @ptrCast(decoded[data_start..].ptr), .big) != expected_width or
                std.mem.readInt(u32, @ptrCast(decoded[data_start + 4 ..].ptr), .big) != expected_height or
                decoded[data_start + 10] != 0 or decoded[data_start + 11] != 0 or decoded[data_start + 12] > 1) return error.InvalidSystemUseResponse;
        } else if (std.mem.eql(u8, kind, "IHDR")) {
            return error.InvalidSystemUseResponse;
        }
        if (std.mem.eql(u8, kind, "IDAT")) saw_idat = true;
        if (std.mem.eql(u8, kind, "IEND")) {
            if (data_len != 0 or !saw_idat or crc_start + 4 != decoded.len) return error.InvalidSystemUseResponse;
            saw_iend = true;
        }
        offset = crc_start + 4;
        if (saw_iend) break;
    }
    if (!saw_iend or offset != decoded.len) return error.InvalidSystemUseResponse;
}

fn settlementState(settlement: Settlement) event_store.Settlement {
    return switch (settlement.stop_reason) {
        .complete, .terminated => .succeeded,
        .max_steps, .failed => .failed,
        .canceled => .cancelled,
    };
}

fn validateProviderConfiguration(profiles: *const model_catalog.ProfileRegistry, registry: *const openai_compatible.ProviderRegistry) !void {
    for (profiles.profiles) |profile| {
        const selection = ModelSelection{
            .provider = profile.provider,
            .model = profile.id,
            .context_window = profile.context_window,
            .output_limit = profile.output_limit,
            .reasoning = profile.reasoning,
            .cache_retention = profile.cache_retention,
            .cache_aggressiveness = profile.cache_aggressiveness,
            .transport = profile.transport,
        };
        try validateProviderSelection(registry, selection);
    }
}

fn validateProviderSelection(registry: *const openai_compatible.ProviderRegistry, selection: ModelSelection) !void {
    const provider = registry.find(selection.provider) orelse return error.ProviderNotConfigured;
    var capability: ?*const openai_compatible.ModelCapability = null;
    for (provider.models) |*candidate| if (std.mem.eql(u8, candidate.id, selection.model)) {
        capability = candidate;
        break;
    };
    const model = capability orelse return error.ProviderModelNotConfigured;
    if (selection.context_window != model.context_window or selection.output_limit == 0 or selection.output_limit > model.output_limit) return error.InvalidProviderModelLimits;
    if (selection.reasoning) |effort| {
        if (!model.reasoning) return error.UnsupportedReasoning;
        var supported = false;
        for (model.reasoning_efforts) |candidate| if (candidate == effort) {
            supported = true;
            break;
        };
        if (!supported) return error.UnsupportedReasoning;
    }
    if (selection.cache_retention == .short and model.short_cache_retention == null) return error.UnsupportedCacheRetention;
    if (selection.cache_retention == .long and model.long_cache_retention == null) return error.UnsupportedCacheRetention;
    if (selection.transport == .websocket) return error.UnsupportedTransport;
}

fn cloneJsonValue(allocator: std.mem.Allocator, value: std.json.Value) !std.json.Parsed(std.json.Value) {
    const encoded = try std.json.Stringify.valueAlloc(allocator, value, .{});
    defer allocator.free(encoded);
    return std.json.parseFromSlice(std.json.Value, allocator, encoded, .{});
}

fn jsonContainsString(value: std.json.Value, needle: []const u8) bool {
    return switch (value) {
        .string => |text| std.mem.indexOf(u8, text, needle) != null,
        .array => |items| for (items.items) |item| {
            if (jsonContainsString(item, needle)) break true;
        } else false,
        .object => |fields| blk: {
            var iterator = fields.iterator();
            while (iterator.next()) |entry| if (jsonContainsString(entry.value_ptr.*, needle)) break :blk true;
            break :blk false;
        },
        else => false,
    };
}

fn systemUseSemanticElements(value: std.json.Value) !std.json.Value {
    if (value != .object) return error.InvalidSystemUseResponse;
    const semantic = value.object.get("semantic") orelse return error.InvalidSystemUseResponse;
    if (semantic != .object) return error.InvalidSystemUseResponse;
    return semantic.object.get("elements") orelse return error.InvalidSystemUseResponse;
}

fn jsonElementsContainId(elements: std.json.Value, expected: []const u8) bool {
    if (elements != .array) return false;
    for (elements.array.items) |element| {
        if (element != .object) continue;
        const id = element.object.get("id") orelse continue;
        if (id == .string and std.mem.eql(u8, id.string, expected)) return true;
    }
    return false;
}

fn jsonContainsExactString(value: std.json.Value, expected: []const u8) bool {
    return switch (value) {
        .string => |text| std.mem.eql(u8, text, expected),
        .array => |items| for (items.items) |item| {
            if (jsonContainsExactString(item, expected)) break true;
        } else false,
        .object => |fields| blk: {
            var iterator = fields.iterator();
            while (iterator.next()) |entry| if (jsonContainsExactString(entry.value_ptr.*, expected)) break :blk true;
            break :blk false;
        },
        else => false,
    };
}

fn runTask(self: *Runtime, run: *Run) anyerror!void {
    self.executeRun(run) catch |err| {
        self.mutex.lockUncancelable(self.io);
        const already_settled = run.settlement != null;
        self.mutex.unlock(self.io);
        if (already_settled) return;
        const category: FailureCategory = switch (err) {
            error.Canceled => .canceled,
            error.EventCallbackFailed => .callback,
            else => .internal,
        };
        self.failRun(run, category);
    };
}

fn runEvent(raw: *anyopaque, event: agent_loop.Event) !void {
    const run: *Run = @ptrCast(@alignCast(raw));
    const runtime = run.runtime;
    switch (event) {
        .agent_start, .step_start, .agent_end => {},
        .stream => |stream| {
            try runtime.emit(run, .{ .provider_stream = stream });
            switch (stream) {
                .failed => |message| {
                    try runtime.recordFailure(run);
                    if (message.provider_failure) |failure| try runtime.emit(run, .{ .typed_failure = .{
                        .category = .provider,
                        .provider_failure = failure,
                    } });
                },
                else => {},
            }
        },
        .message_added => |message| {
            if (run.initial_message_events_remaining != 0) {
                run.initial_message_events_remaining -= 1;
                if (run.initial_message_events_remaining == 0) try runtime.emit(run, .{ .message_added = message });
                return;
            }
            try runtime.recordMessage(run, message.*);
            try runtime.emit(run, .{ .message_added = message });
        },
        .tool_start => |call| {
            try runtime.recordTool(run, call.id, .tool_started);
            try runtime.emit(run, .{ .tool_started = call });
        },
        .tool_end => |result| {
            try runtime.recordTool(run, result.tool_call_id, .tool_finished);
            try runtime.emit(run, .{ .tool_finished = result });
        },
        .compacted => |value| try runtime.emit(run, .{ .compaction_observed = .{
            .tokens_before = value.tokens_before,
            .first_kept_index = value.first_kept_index,
        } }),
        .cancellation_requested => {
            if (run.cancel_requested.load(.acquire)) return;
            try runtime.requestCancellation(run);
            try runtime.emit(run, .cancellation_requested);
        },
        .provider_error => {
            try runtime.recordFailure(run);
            try runtime.emit(run, .{ .typed_failure = .{ .category = .provider } });
        },
        .max_steps => {
            try runtime.recordFailure(run);
            try runtime.emit(run, .{ .typed_failure = .{ .category = .max_steps } });
        },
    }
}

fn runtimeStream(raw: *anyopaque, model: *const protocol.Model, context: *const protocol.Context, options: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    const run: *Run = @ptrCast(@alignCast(raw));
    const runtime = run.runtime;
    if (runtime.test_stream) |stream| return stream(runtime.test_stream_context orelse raw, model, context, options, sink_context, sink);
    const registry = if (runtime.provider_registry) |*value| value else return error.ProviderRegistryUnavailable;
    return registry.streamObserved(model, context, options, sink_context, sink, run, observeRunRequest);
}

fn observeRunRequest(raw: *anyopaque, metadata: openai_compatible.RenderedRequestMetadata) !void {
    const run: *Run = @ptrCast(@alignCast(raw));
    run.latest_request_identity = .{
        .request_bytes = metadata.request_len,
        .request_hash = metadata.request_hash,
        .prefix_bytes = metadata.cache_prefix_len,
        .prefix_hash = metadata.cache_prefix_hash,
    };
}

fn takeRunRequestIdentity(raw: *anyopaque) ?cache_telemetry.RequestIdentity {
    const run: *Run = @ptrCast(@alignCast(raw));
    const identity = run.latest_request_identity;
    run.latest_request_identity = null;
    return identity;
}

fn isRunCancelled(raw: *anyopaque) bool {
    const run: *Run = @ptrCast(@alignCast(raw));
    return run.cancel_requested.load(.acquire);
}

fn runtimeTelemetry(raw: *anyopaque, call: cache_telemetry.Call) void {
    const runtime: *Runtime = @ptrCast(@alignCast(raw));
    const sink = runtime.telemetry orelse return;
    runtime.telemetry_mutex.lockUncancelable(runtime.io);
    defer runtime.telemetry_mutex.unlock(runtime.io);
    sink(runtime.telemetry_context orelse raw, call);
}

fn settlementFromResult(allocator: std.mem.Allocator, run: *Run, result: agent_loop.Result, current_run_start: usize) !Settlement {
    if (current_run_start > result.messages.len) return error.InvalidRunMessageBoundary;
    var usage: protocol.Usage = .{};
    var borrowed_provider_failure: ?protocol.ProviderFailure = null;
    for (result.messages[current_run_start..]) |message| switch (message) {
        .assistant => |assistant| {
            usage.input += assistant.usage.input;
            usage.output += assistant.usage.output;
            usage.cache_read += assistant.usage.cache_read;
            usage.cache_write += assistant.usage.cache_write;
            usage.total_tokens += assistant.usage.total_tokens;
            usage.cost.input += assistant.usage.cost.input;
            usage.cost.output += assistant.usage.cost.output;
            usage.cost.cache_read += assistant.usage.cost.cache_read;
            usage.cost.cache_write += assistant.usage.cost.cache_write;
            usage.cost.total += assistant.usage.cost.total;
            if (assistant.provider_failure) |provider_failure| borrowed_provider_failure = provider_failure;
        },
        else => {},
    };
    const stop_reason: StopReason = switch (result.stop) {
        .complete => .complete,
        .terminated => .terminated,
        .max_steps => .max_steps,
        .provider_error => .failed,
        .cancelled => .canceled,
    };
    const failure: FailureCategory = switch (result.stop) {
        .provider_error => .provider,
        .max_steps => .max_steps,
        .cancelled => .canceled,
        else => .none,
    };
    const owned_provider_failure = if (borrowed_provider_failure) |provider_failure|
        try cloneProviderFailure(allocator, provider_failure)
    else
        null;
    run.owned_provider_failure = owned_provider_failure;
    return .{
        .run_id = run.id,
        .session_id = run.request.session_id,
        .stop_reason = stop_reason,
        .step_count = result.steps,
        .usage = usage,
        .failure = failure,
        .provider_failure = owned_provider_failure,
    };
}

fn cloneProviderFailure(allocator: std.mem.Allocator, failure: protocol.ProviderFailure) !protocol.ProviderFailure {
    try failure.validate();
    const redacted_message = try allocator.dupe(u8, failure.redacted_message);
    errdefer allocator.free(redacted_message);
    const request_id = if (failure.request_id) |value| try allocator.dupe(u8, value) else null;
    errdefer if (request_id) |value| allocator.free(value);
    const provider_code = if (failure.provider_code) |value| try allocator.dupe(u8, value) else null;
    errdefer if (provider_code) |value| allocator.free(value);
    const provider_type = if (failure.provider_type) |value| try allocator.dupe(u8, value) else null;
    errdefer if (provider_type) |value| allocator.free(value);
    const provider_param = if (failure.provider_param) |value| try allocator.dupe(u8, value) else null;
    return .{
        .category = failure.category,
        .http_status = failure.http_status,
        .request_id = request_id,
        .provider_code = provider_code,
        .provider_type = provider_type,
        .provider_param = provider_param,
        .redacted_message = redacted_message,
        .retry_after_ms = failure.retry_after_ms,
    };
}

fn deinitProviderFailure(allocator: std.mem.Allocator, failure: protocol.ProviderFailure) void {
    if (failure.provider_param) |value| allocator.free(value);
    if (failure.provider_type) |value| allocator.free(value);
    if (failure.provider_code) |value| allocator.free(value);
    if (failure.request_id) |value| allocator.free(value);
    allocator.free(failure.redacted_message);
}

fn releaseFuture(runtime: *Runtime, run: *Run) void {
    run.future_mutex.lockUncancelable(runtime.io);
    if (run.future) |*future| {
        _ = future.await(runtime.io) catch {};
        run.future = null;
    }
    run.future_mutex.unlock(runtime.io);
}

fn cloneSelection(allocator: std.mem.Allocator, value: ModelSelection) !ModelSelection {
    const provider = try allocator.dupe(u8, value.provider);
    errdefer allocator.free(provider);
    const model = try allocator.dupe(u8, value.model);
    return .{
        .provider = provider,
        .model = model,
        .context_window = value.context_window,
        .output_limit = value.output_limit,
        .reasoning = value.reasoning,
        .cache_retention = value.cache_retention,
        .cache_aggressiveness = value.cache_aggressiveness,
        .transport = value.transport,
    };
}

fn selectionEqual(left: ModelSelection, right: ModelSelection) bool {
    return std.mem.eql(u8, left.provider, right.provider) and
        std.mem.eql(u8, left.model, right.model) and
        left.context_window == right.context_window and
        left.output_limit == right.output_limit and
        left.reasoning == right.reasoning and
        left.cache_retention == right.cache_retention and
        left.cache_aggressiveness == right.cache_aggressiveness and
        left.transport == right.transport;
}

fn sessionIdForDialect(dialect: openai_compatible.Dialect, session_id: []const u8) ?[]const u8 {
    return if (dialect == .openrouter_chat) session_id else null;
}

fn apiNameForDialect(dialect: openai_compatible.Dialect) []const u8 {
    return switch (dialect) {
        .openai_chat => "openai-chat",
        .openrouter_chat => "openrouter-chat",
        .deepseek_chat => "deepseek-chat",
        .anthropic_messages => "anthropic-messages",
    };
}

fn validateSelection(registry: *const model_catalog.ProfileRegistry, value: ModelSelection) !void {
    const profile = registry.find(value.model) orelse return error.ModelNotConfigured;
    if (!std.mem.eql(u8, value.provider, profile.provider) or
        value.context_window != profile.context_window or
        value.reasoning != profile.reasoning or
        value.cache_retention != profile.cache_retention or
        value.cache_aggressiveness != profile.cache_aggressiveness or
        value.transport != profile.transport)
    {
        return error.InvalidModelSelection;
    }
    if (value.output_limit == 0 or value.output_limit > profile.max_output_tokens) return error.OutputLimitExceedsProfile;
}

pub fn deinitSelection(allocator: std.mem.Allocator, value: ModelSelection) void {
    value.deinit(allocator);
}

fn addSkillRoot(catalog: *skills.Catalog, io: std.Io, path: []const u8) !void {
    catalog.addRoot(io, path) catch |err| switch (err) {
        error.FileNotFound, error.NotDir => {},
        else => |other| return other,
    };
}

const TestStreamState = struct {
    io: std.Io,
    started: std.atomic.Value(bool) = .init(false),
    started_mutex: std.Io.Mutex = std.Io.Mutex.init,
    started_condition: std.Io.Condition = std.Io.Condition.init,
    environ: ?std.process.Environ.Map = null,
    seen_prompt: std.ArrayList(u8) = .empty,
    seen_model: std.ArrayList(u8) = .empty,
    seen_options: protocol.StreamOptions = .{},
    seen_message_count: usize = 0,
    saw_tool_call: bool = false,
    saw_tool_result: bool = false,
    block: bool = false,
    usage: protocol.Usage = .{},

    fn deinit(self: *TestStreamState, allocator: std.mem.Allocator) void {
        self.seen_prompt.deinit(allocator);
        self.seen_model.deinit(allocator);
        if (self.environ) |*environ| environ.deinit();
    }
};

const InterruptedRunProbe = struct {
    count: usize = 0,
    session_id: [32]u8 = undefined,
    session_id_len: usize = 0,
    run_id: [32]u8 = undefined,
    run_id_len: usize = 0,
};

const DurableCapture = struct {
    runtime: *Runtime,
    sequences: std.ArrayList(u64) = .empty,

    fn deinit(self: *DurableCapture) void {
        self.sequences.deinit(std.testing.allocator);
    }
};

const RunStartedMessageIdCapture = struct {
    input: [event_store.max_event_id_bytes]u8 = undefined,
    input_len: usize = 0,
    assistant: [event_store.max_event_id_bytes]u8 = undefined,
    assistant_len: usize = 0,
};

fn captureRunStartedMessageIds(raw: *anyopaque, event: Event) !void {
    const capture: *RunStartedMessageIdCapture = @ptrCast(@alignCast(raw));
    switch (event.payload) {
        .run_started => |started| {
            if (started.input_message_id.len > capture.input.len or started.assistant_message_id.len > capture.assistant.len) return error.OversizedFixtureIdentity;
            @memcpy(capture.input[0..started.input_message_id.len], started.input_message_id);
            @memcpy(capture.assistant[0..started.assistant_message_id.len], started.assistant_message_id);
            capture.input_len = started.input_message_id.len;
            capture.assistant_len = started.assistant_message_id.len;
        },
        else => {},
    }
}

const ShutdownSettlementProbe = struct {
    canceled: usize = 0,
    interrupted: usize = 0,
};

fn inspectInterruptedRun(raw: *anyopaque, record: event_store.RecordView) !void {
    if (record.kind != .run_settled or record.settlement != .interrupted) return;
    const probe: *InterruptedRunProbe = @ptrCast(@alignCast(raw));
    const Wire = struct { session_id: []const u8, run_id: []const u8 };
    var parsed = try std.json.parseFromSlice(Wire, std.testing.allocator, record.payload, .{});
    defer parsed.deinit();
    if (parsed.value.session_id.len > probe.session_id.len or parsed.value.run_id.len > probe.run_id.len) return error.OversizedFixtureIdentity;
    @memcpy(probe.session_id[0..parsed.value.session_id.len], parsed.value.session_id);
    @memcpy(probe.run_id[0..parsed.value.run_id.len], parsed.value.run_id);
    probe.session_id_len = parsed.value.session_id.len;
    probe.run_id_len = parsed.value.run_id.len;
    probe.count += 1;
}

fn captureDurable(raw: *anyopaque, record: event_store.RecordView) !void {
    const capture: *DurableCapture = @ptrCast(@alignCast(raw));
    try std.testing.expect(capture.runtime.highWater() >= record.global_sequence);
    try capture.sequences.append(std.testing.allocator, record.global_sequence);
}

fn rejectDurable(_: *anyopaque, _: event_store.RecordView) !void {
    return error.DeliberateSubscriberFailure;
}

fn inspectShutdownSettlement(raw: *anyopaque, record: event_store.RecordView) !void {
    if (record.kind != .run_settled) return;
    const probe: *ShutdownSettlementProbe = @ptrCast(@alignCast(raw));
    switch (record.settlement) {
        .cancelled => probe.canceled += 1,
        .interrupted => probe.interrupted += 1,
        else => {},
    }
}

fn testStream(raw: *anyopaque, model: *const protocol.Model, context: *const protocol.Context, options: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    const state: *TestStreamState = @ptrCast(@alignCast(raw));
    state.started_mutex.lockUncancelable(state.io);
    state.started.store(true, .release);
    state.started_condition.signal(state.io);
    state.started_mutex.unlock(state.io);
    try state.seen_model.appendSlice(std.testing.allocator, model.id);
    state.seen_options = options;
    state.seen_message_count = context.messages.len;
    for (context.messages) |message| switch (message) {
        .assistant => |assistant| for (assistant.content) |part| switch (part) {
            .tool_call => state.saw_tool_call = true,
            else => {},
        },
        .tool_result => state.saw_tool_result = true,
        else => {},
    };
    const prompt = context.messages[context.messages.len - 1].user.content[0].text.text;
    try state.seen_prompt.appendSlice(std.testing.allocator, prompt);
    if (state.block) try std.Io.sleep(state.io, .fromMilliseconds(60_000), .awake);
    const content = [_]protocol.AssistantContent{.{ .text = .{ .text = prompt } }};
    const message = protocol.AssistantMessage{
        .content = &content,
        .api = model.api,
        .provider = model.provider,
        .model = model.id,
        .stop_reason = .stop,
        .timestamp_ms = 1,
        .usage = state.usage,
    };
    try sink(sink_context, .{ .text_delta = .{ .content_index = 0, .delta = prompt } });
    try sink(sink_context, .{ .done = message });
}

fn typedFailureStream(_: *anyopaque, model: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    var request_id = [_]u8{ 'r', 'e', 'q', '-', '9' };
    var provider_message = [_]u8{ 'q', 'u', 'o', 't', 'a', ' ', 'u', 's', 'e', 'd' };
    try sink(sink_context, .{ .failed = .{
        .content = &.{},
        .api = model.api,
        .provider = model.provider,
        .model = model.id,
        .stop_reason = .@"error",
        .provider_failure = .{
            .category = .quota_exhausted,
            .http_status = 429,
            .request_id = &request_id,
            .provider_code = "insufficient_quota",
            .redacted_message = &provider_message,
        },
        .timestamp_ms = 1,
    } });
    @memset(&request_id, 'x');
    @memset(&provider_message, 'x');
}

fn testRuntime(allocator: std.mem.Allocator, tmp: *std.testing.TmpDir, state: *TestStreamState) !*Runtime {
    var path: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const len = try tmp.dir.realPath(std.testing.io, &path);
    state.environ = std.process.Environ.Map.init(allocator);
    const profiles = [_]model_catalog.ModelProfile{
        .{ .id = "default", .name = "Default", .provider = "test", .context_window = 1024, .output_limit = 128, .max_output_tokens = 128, .cache_retention = .none, .cache_aggressiveness = .balanced, .transport = .auto },
        .{ .id = "selected", .name = "Selected", .provider = "test-provider", .context_window = 4096, .output_limit = 77, .max_output_tokens = 77, .reasoning = .high, .cache_retention = .long, .cache_aggressiveness = .aggressive, .transport = .websocket },
    };
    return Runtime.createInternal(allocator, std.testing.io, &state.environ.?, .{
        .workspace_root = path[0..len],
        .state_root = path[0..len],
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "default", .context_window = 1024, .output_limit = 128 },
        .model_profiles = &profiles,
    }, state, testStream);
}

test "SystemUse automatic denial is durably settled without dispatch" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();

    const context = SystemUseRequestContext{ .source_request_id = "rpc:test", .causal_request_id = "rpc:test", .run_id = "rpc:test", .session_id = "session:test", .actor_id = "local-user", .provenance = "rpc:local-user" };
    try std.testing.expectError(error.SystemUsePolicyDenied, runtime.beginSystemUseAction(context, .system_use_interact, .inspect, "pid:42", "{}"));
    try std.testing.expect(runtime.permission_broker.entries.items.len != 0);
    const entry = runtime.permission_broker.entries.items[runtime.permission_broker.entries.items.len - 1];
    try std.testing.expectEqual(permission_broker.Phase.settled, entry.phase);
    try std.testing.expectEqual(action_receipt.Status.policy_denial, entry.status.?);
    try std.testing.expect(entry.lease_id == null);
}

test "SystemUse success validation is method and request specific" {
    const element_request = "{\"elementId\":\"boot:e1\",\"focusMode\":\"background\"}";
    try validateSystemUseResult(std.testing.allocator, "click", element_request, "{\"method\":\"accessibility\",\"elementId\":\"boot:e1\",\"point\":null,\"focusMode\":\"background\",\"activated\":false}", false);
    try std.testing.expectError(error.InvalidSystemUseResponse, validateSystemUseResult(std.testing.allocator, "click", element_request, "{\"method\":\"claimed\",\"elementId\":\"boot:e1\",\"focusMode\":\"background\",\"activated\":false}", false));
    try std.testing.expectError(error.InvalidSystemUseResponse, validateSystemUseResult(std.testing.allocator, "click", "{\"x\":4,\"y\":5,\"focusMode\":\"activate\"}", "{\"method\":\"event\",\"point\":{\"x\":999,\"y\":5},\"focusMode\":\"activate\",\"activated\":true}", false));
    try std.testing.expectError(error.InvalidSystemUseResponse, validateSystemUseResult(std.testing.allocator, "click", "{\"elementId\":\"boot:e1\",\"x\":4,\"y\":5,\"focusMode\":\"activate\"}", "{\"method\":\"event\",\"point\":{\"x\":4,\"y\":5},\"focusMode\":\"activate\",\"activated\":true}", false));
    try validateSystemUseResult(std.testing.allocator, "click", "{\"x\":4,\"y\":5,\"focusMode\":\"activate\"}", "{\"method\":\"event\",\"point\":{\"x\":4,\"y\":5},\"focusMode\":\"activate\",\"activated\":true}", false);
    try validateSystemUseResult(std.testing.allocator, "setValue", "{\"elementId\":\"boot:e1\",\"value\":\"new\"}", "{\"elementId\":\"boot:e1\",\"actualValue\":\"new\",\"focusMode\":\"background\",\"activated\":false}", false);
    try std.testing.expectError(error.InvalidSystemUseResponse, validateSystemUseResult(std.testing.allocator, "setValue", "{\"elementId\":\"boot:e1\",\"value\":\"new\"}", "{\"elementId\":\"boot:e1\",\"actualValue\":\"old\",\"focusMode\":\"background\",\"activated\":false}", false));
    try validateSystemUseResult(std.testing.allocator, "type", "{\"value\":\"hi\",\"focusMode\":\"activate\"}", "{\"method\":\"event\",\"focusMode\":\"activate\",\"activated\":true}", false);
    try std.testing.expectError(error.InvalidSystemUseResponse, validateSystemUseResult(std.testing.allocator, "type", "{\"value\":\"hi\",\"focusMode\":\"activate\"}", "{\"method\":\"event\",\"focusMode\":\"activate\",\"activated\":false}", false));
    try validateSystemUseResult(std.testing.allocator, "scroll", "{\"elementId\":\"boot:e1\",\"deltaY\":-300,\"focusMode\":\"activate\"}", "{\"method\":\"event\",\"focusMode\":\"activate\",\"activated\":true}", false);
    try std.testing.expectError(error.InvalidSystemUseResponse, validateSystemUseResult(std.testing.allocator, "scroll", "{\"elementId\":\"boot:e1\",\"deltaY\":-300,\"focusMode\":\"activate\"}", "{\"method\":\"accessibility\",\"focusMode\":\"activate\",\"activated\":true}", false));
    const observe_params = "{\"target\":{\"adapter\":\"macos\",\"id\":\"pid:1:window:2\",\"kind\":\"window\",\"label\":\"Fixture\"},\"maxDepth\":2,\"maxElements\":2}";
    const observe_valid = "{\"semantic\":{\"target\":{\"adapter\":\"macos\",\"id\":\"pid:1:window:2\",\"kind\":\"window\",\"label\":\"Fixture\"},\"elements\":[{\"id\":\"boot:e1\",\"depth\":0,\"role\":\"AXWindow\"}],\"truncated\":false}}";
    try validateSystemUseResult(std.testing.allocator, "observe", observe_params, observe_valid, false);
    try std.testing.expectError(error.InvalidSystemUseResponse, validateSystemUseResult(std.testing.allocator, "observe", observe_params, "{\"semantic\":{\"target\":{\"adapter\":\"macos\",\"id\":\"pid:9:window:9\",\"kind\":\"window\",\"label\":\"Other\"},\"elements\":[],\"truncated\":false}}", false));
    try std.testing.expectError(error.InvalidSystemUseResponse, validateSystemUseResult(std.testing.allocator, "observe", observe_params, "{\"semantic\":{\"target\":{\"adapter\":\"macos\",\"id\":\"pid:1:window:2\",\"kind\":\"window\",\"label\":\"Fixture\"},\"elements\":[{\"id\":\"same\",\"depth\":0,\"role\":\"AXWindow\"},{\"id\":\"same\",\"depth\":1,\"role\":\"AXButton\"}],\"truncated\":false}}", false));
    try std.testing.expectError(error.InvalidSystemUseResponse, validateSystemUseResult(std.testing.allocator, "observe", observe_params, "{\"semantic\":{\"target\":{\"adapter\":\"macos\",\"id\":\"pid:1:window:2\",\"kind\":\"window\",\"label\":\"Fixture\"},\"elements\":[],\"truncated\":false},\"screenshot\":{\"mimeType\":\"image/png\",\"data\":\"eA==\",\"width\":1,\"height\":1}}", true));

    const valid_png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    var decoded: [128]u8 = undefined;
    const decoded_len = try std.base64.standard.Decoder.calcSizeForSlice(valid_png);
    try std.base64.standard.Decoder.decode(decoded[0..decoded_len], valid_png);
    decoded[29] ^= 1;
    var corrupt_encoded: [256]u8 = undefined;
    const corrupt = std.base64.standard.Encoder.encode(&corrupt_encoded, decoded[0..decoded_len]);
    try std.testing.expectError(error.InvalidSystemUseResponse, validateSystemUsePng(std.testing.allocator, corrupt, 1, 1));
    const truncated = std.base64.standard.Encoder.encode(&corrupt_encoded, decoded[0..33]);
    try std.testing.expectError(error.InvalidSystemUseResponse, validateSystemUsePng(std.testing.allocator, truncated, 1, 1));
}

test "SystemUse settlement reservation excludes concurrent publisher capacity" {
    try std.testing.expect(publicationSlotsAvailable(4, 2, 1, 1));
    try std.testing.expect(!publicationSlotsAvailable(4, 2, 2, 1));
    try std.testing.expect(!publicationSlotsAvailable(4, 4, 0, 1));
    try std.testing.expect(!publicationSlotsAvailable(4, 3, 2, 1));
}

test "SystemUse failed publication reservation does not release unowned capacity" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();

    runtime.writer_mutex.lockUncancelable(runtime.io);
    var prepared = try runtime.prepareSystemUseSettlementsLocked("action-reservation", @splat(1), "decision-reservation", "lease-reservation", "rpc:reservation", "local-user", 10, 11, 12);
    runtime.publisher.mutex.lockUncancelable(runtime.io);
    runtime.publisher.reserved = runtime.publisher.queue.len;
    runtime.publisher.mutex.unlock(runtime.io);
    try std.testing.expectError(error.PublicationQueueFull, runtime.reservePublicationSlotLocked());
    prepared.deinit(runtime);
    runtime.publisher.mutex.lockUncancelable(runtime.io);
    try std.testing.expectEqual(runtime.publisher.queue.len, runtime.publisher.reserved);
    runtime.publisher.reserved = 0;
    runtime.publisher.mutex.unlock(runtime.io);
    runtime.writer_mutex.unlock(runtime.io);
}

const EventCapture = struct {
    sequences: std.ArrayList(u64) = .empty,
    text: std.ArrayList(u8) = .empty,
    settled_count: usize = 0,

    fn deinit(self: *EventCapture, allocator: std.mem.Allocator) void {
        self.sequences.deinit(allocator);
        self.text.deinit(allocator);
    }
};

const ProviderFailureEventCapture = struct {
    typed_count: usize = 0,
    request_id: std.ArrayList(u8) = .empty,
    redacted_message: std.ArrayList(u8) = .empty,

    fn deinit(self: *ProviderFailureEventCapture, allocator: std.mem.Allocator) void {
        self.request_id.deinit(allocator);
        self.redacted_message.deinit(allocator);
    }
};

fn captureProviderFailure(raw: *anyopaque, event: Event) !void {
    const capture: *ProviderFailureEventCapture = @ptrCast(@alignCast(raw));
    switch (event.payload) {
        .typed_failure => |typed| if (typed.provider_failure) |failure| {
            capture.typed_count += 1;
            if (failure.request_id) |request_id| try capture.request_id.appendSlice(std.testing.allocator, request_id);
            try capture.redacted_message.appendSlice(std.testing.allocator, failure.redacted_message);
        },
        else => {},
    }
}

fn rejectSettledEvent(_: *anyopaque, event: Event) !void {
    if (event.payload == .settled) return error.CallbackRejected;
}

const SettledCallbackProbe = struct {
    observed: usize = 0,
};

fn observeSettledThenReject(raw: *anyopaque, event: Event) !void {
    const probe: *SettledCallbackProbe = @ptrCast(@alignCast(raw));
    if (event.payload == .settled) {
        probe.observed += 1;
        return error.CallbackRejected;
    }
}

fn rejectStartedEvent(_: *anyopaque, event: Event) !void {
    switch (event.payload) {
        .run_started => return error.CallbackRejected,
        else => {},
    }
}

fn rejectProviderStreamEvent(_: *anyopaque, event: Event) !void {
    if (event.payload == .provider_stream) return error.CallbackRejected;
}

fn captureEvent(raw: *anyopaque, event: Event) !void {
    const capture: *EventCapture = @ptrCast(@alignCast(raw));
    try capture.sequences.append(std.testing.allocator, event.sequence);
    switch (event.payload) {
        .provider_stream => |stream| switch (stream) {
            .text_delta => |delta| try capture.text.appendSlice(std.testing.allocator, delta.delta),
            else => {},
        },
        .settled => capture.settled_count += 1,
        else => {},
    }
}

test "runtime restart settles an interrupted durable run exactly once" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    const root = root_buffer[0..root_len];
    const event_path = try std.fs.path.join(allocator, &.{ root, event_log_name });
    defer allocator.free(event_path);

    var seeded = try event_store.Store.open(allocator, std.testing.io, event_path, .{});
    var created = try seeded.store.append(.{
        .kind = .session_created,
        .event_id = "seed-session",
        .payload = "{\"session_id\":\"crashed\",\"branch_id\":\"main\"}",
    }, "fixture:crash", .succeeded);
    created.deinit(allocator);
    var selected = try seeded.store.append(.{
        .kind = .session_selection_changed,
        .event_id = "seed-selection",
        .payload = "{\"session_id\":\"crashed\",\"model\":\"test-model\",\"provider\":\"test-provider\",\"context_window\":4096,\"output_limit\":256,\"cache_retention\":\"none\",\"cache_aggressiveness\":\"balanced\",\"transport\":\"auto\"}",
    }, "fixture:crash", .succeeded);
    selected.deinit(allocator);
    var started = try seeded.store.append(.{
        .kind = .run_started,
        .event_id = "seed-run-event",
        .payload = "{\"session_id\":\"crashed\",\"run_id\":\"run-before-crash\"}",
    }, "fixture:crash", .pending);
    started.deinit(allocator);
    seeded.close();

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
    var stream = TestStreamState{ .io = std.testing.io };
    defer stream.deinit(allocator);
    const runtime = try Runtime.createForTest(allocator, std.testing.io, &environment, .{
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
    }, &stream, testStream);
    runtime.destroy();

    var reopened = try event_store.Store.open(allocator, std.testing.io, event_path, .{});
    defer reopened.close();
    var probe = InterruptedRunProbe{};
    const report = try reopened.store.replayWithContext(@ptrCast(&probe), inspectInterruptedRun);
    try std.testing.expectEqual(@as(usize, 5), report.record_count);
    try std.testing.expectEqual(@as(usize, 1), probe.count);
    try std.testing.expectEqualStrings("crashed", probe.session_id[0..probe.session_id_len]);
    try std.testing.expectEqualStrings("run-before-crash", probe.run_id[0..probe.run_id_len]);
}

test "durable subscribers preserve global order and one failed subscriber is isolated" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream = TestStreamState{ .io = std.testing.io };
    defer stream.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream);
    defer runtime.destroy();
    const cursor = runtime.highWater();
    const rejected = try runtime.subscribe(cursor, 16);
    const healthy = try runtime.subscribe(cursor, 16);
    var capture = DurableCapture{ .runtime = runtime };
    defer capture.deinit();

    _ = try runtime.wait(try runtime.startPrompt(.{ .session_id = "published-a", .prompt = "alpha" }));
    try std.testing.expectError(error.DeliberateSubscriberFailure, runtime.nextDurable(rejected, &capture, rejectDurable));
    try std.testing.expectError(error.SubscriptionClosed, runtime.nextDurable(rejected, &capture, rejectDurable));
    runtime.unsubscribe(rejected);
    for (0..5) |_| try runtime.nextDurable(healthy, &capture, captureDurable);

    _ = try runtime.wait(try runtime.startPrompt(.{ .session_id = "published-b", .prompt = "beta" }));
    for (0..5) |_| try runtime.nextDurable(healthy, &capture, captureDurable);
    try std.testing.expectEqual(@as(usize, 10), capture.sequences.items.len);
    for (capture.sequences.items, 0..) |sequence, index| {
        try std.testing.expectEqual(cursor + index + 1, sequence);
    }
    runtime.unsubscribe(healthy);
}

test "durable subscription slots are reusable after exclusive unsubscribe" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream = TestStreamState{ .io = std.testing.io };
    defer stream.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream);
    defer runtime.destroy();
    for (0..maximum_subscriptions * 2) |_| {
        const subscription = try runtime.subscribe(runtime.highWater(), 1);
        runtime.unsubscribe(subscription);
    }
    const final = try runtime.subscribe(runtime.highWater(), 1);
    runtime.unsubscribe(final);
}

test "runtime owns requests and emits ordered typed events" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();
    var capture = EventCapture{};
    defer capture.deinit(std.testing.allocator);
    var prompt = [_]u8{ 'o', 'w', 'n', 'e', 'd' };
    const run_id = try runtime.startPrompt(.{ .session_id = "s", .prompt = &prompt, .event_context = &capture, .emit = captureEvent });
    @memset(&prompt, 'x');
    const settlement = try runtime.wait(run_id);
    try std.testing.expectEqualStrings("owned", stream_state.seen_prompt.items);
    try std.testing.expectEqual(StopReason.complete, settlement.stop_reason);
    try std.testing.expectEqual(@as(usize, 1), capture.settled_count);
    for (capture.sequences.items, 1..) |sequence, expected| try std.testing.expectEqual(@as(u64, @intCast(expected)), sequence);
}

test "runtime settlements report usage from only the current turn" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io, .usage = .{ .input = 7, .output = 3, .total_tokens = 10 } };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();

    const first = try runtime.wait(try runtime.startPrompt(.{ .session_id = "usage", .prompt = "first" }));
    const second = try runtime.wait(try runtime.startPrompt(.{ .session_id = "usage", .prompt = "second" }));
    try std.testing.expectEqual(@as(u64, 7), first.usage.input);
    try std.testing.expectEqual(@as(u64, 3), first.usage.output);
    try std.testing.expectEqual(@as(u64, 10), first.usage.total_tokens);
    try std.testing.expectEqual(@as(u64, 7), second.usage.input);
    try std.testing.expectEqual(@as(u64, 3), second.usage.output);
    try std.testing.expectEqual(@as(u64, 10), second.usage.total_tokens);
}

test "runtime settlement retains typed provider diagnostics" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPath(std.testing.io, &path);
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    const runtime = try Runtime.createForTest(std.testing.allocator, std.testing.io, &environment, .{
        .workspace_root = path[0..path_len],
        .state_root = path[0..path_len],
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "default", .context_window = 1024, .output_limit = 128 },
    }, &environment, typedFailureStream);
    defer runtime.destroy();

    var capture = ProviderFailureEventCapture{};
    defer capture.deinit(std.testing.allocator);
    const run_id = try runtime.startPrompt(.{
        .session_id = "typed-failure",
        .prompt = "fail",
        .event_context = &capture,
        .emit = captureProviderFailure,
    });
    const settlement = try runtime.wait(run_id);
    try std.testing.expectEqual(StopReason.failed, settlement.stop_reason);
    try std.testing.expectEqual(FailureCategory.provider, settlement.failure);
    const failure = settlement.provider_failure.?;
    try std.testing.expectEqual(protocol.ProviderFailureCategory.quota_exhausted, failure.category);
    try std.testing.expectEqual(@as(?u16, 429), failure.http_status);
    try std.testing.expectEqualStrings("req-9", failure.request_id.?);
    try std.testing.expectEqualStrings("insufficient_quota", failure.provider_code.?);
    try std.testing.expectEqualStrings("quota used", failure.redacted_message);
    try std.testing.expectEqual(@as(usize, 1), capture.typed_count);
    try std.testing.expectEqualStrings("req-9", capture.request_id.items);
    try std.testing.expectEqualStrings("quota used", capture.redacted_message.items);
    try std.testing.expectEqualDeep(settlement, try runtime.wait(run_id));
}

test "provider diagnostic cloning rolls back every allocation boundary" {
    const failure = protocol.ProviderFailure{
        .category = .invalid_request,
        .http_status = 400,
        .request_id = "request",
        .provider_code = "code",
        .provider_type = "type",
        .provider_param = "param",
        .redacted_message = "invalid request",
    };
    for (0..6) |fail_index| {
        var failing = std.testing.FailingAllocator.init(std.testing.allocator, .{ .fail_index = fail_index });
        const allocator = failing.allocator();
        if (cloneProviderFailure(allocator, failure)) |owned| {
            deinitProviderFailure(allocator, owned);
        } else |err| {
            try std.testing.expectEqual(error.OutOfMemory, err);
        }
        try std.testing.expectEqual(failing.allocated_bytes, failing.freed_bytes);
    }
}

test "bounded completed settlements release owned provider diagnostics" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPath(std.testing.io, &path);
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    const runtime = try Runtime.createForTest(std.testing.allocator, std.testing.io, &environment, .{
        .workspace_root = path[0..path_len],
        .state_root = path[0..path_len],
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "default", .context_window = 1024, .output_limit = 128 },
    }, &environment, typedFailureStream);
    defer runtime.destroy();

    for (0..70) |index| {
        var session_buffer: [32]u8 = undefined;
        const session_id = try std.fmt.bufPrint(&session_buffer, "failure-{d}", .{index});
        const settlement = try runtime.wait(try runtime.startPrompt(.{ .session_id = session_id, .prompt = "fail" }));
        try std.testing.expect(settlement.provider_failure != null);
    }
    try std.testing.expectEqual(completed_run_limit, runtime.completed_runs.items.len);
}

test "runtime routes per-session model configuration and rejects concurrent writers" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io, .block = true };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();
    try runtime.setSelection("s", .{
        .provider = "test-provider",
        .model = "selected",
        .context_window = 4096,
        .output_limit = 77,
        .reasoning = .high,
        .cache_retention = .long,
        .cache_aggressiveness = .aggressive,
        .transport = .websocket,
    });
    const run_id = try runtime.startPrompt(.{ .session_id = "s", .prompt = "first" });
    try std.testing.expectError(error.SessionBusy, runtime.startPrompt(.{ .session_id = "s", .prompt = "second" }));
    stream_state.started_mutex.lockUncancelable(std.testing.io);
    while (!stream_state.started.load(.acquire)) stream_state.started_condition.waitUncancelable(std.testing.io, &stream_state.started_mutex);
    stream_state.started_mutex.unlock(std.testing.io);
    _ = try runtime.cancel(run_id);
    try std.testing.expectEqualStrings("selected", stream_state.seen_model.items);
    try std.testing.expectEqual(@as(?u64, 77), stream_state.seen_options.max_tokens);
    try std.testing.expectEqual(protocol.ThinkingLevel.high, stream_state.seen_options.reasoning.?);
    try std.testing.expectEqual(protocol.CacheRetention.long, stream_state.seen_options.cache_retention);
    try std.testing.expectEqual(protocol.Transport.websocket, stream_state.seen_options.transport);
}

test "advertised assistant message identifiers remain reserved while active after reap and after restart" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io, .block = true };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    var runtime_live = true;
    defer if (runtime_live) runtime.destroy();
    var capture = RunStartedMessageIdCapture{};
    const first = try runtime.startPrompt(.{
        .session_id = "reservation-a",
        .prompt = "first",
        .event_context = &capture,
        .emit = captureRunStartedMessageIds,
    });
    stream_state.started_mutex.lockUncancelable(std.testing.io);
    while (!stream_state.started.load(.acquire)) stream_state.started_condition.waitUncancelable(std.testing.io, &stream_state.started_mutex);
    stream_state.started_mutex.unlock(std.testing.io);
    try std.testing.expect(capture.assistant_len != 0);
    const before_collision = runtime.highWater();
    try std.testing.expectError(error.MessageIdAlreadyExists, runtime.startPrompt(.{
        .session_id = "reservation-b",
        .prompt = "must not write",
        .input_id = capture.assistant[0..capture.assistant_len],
    }));
    try std.testing.expectEqual(before_collision, runtime.highWater());
    _ = try runtime.cancel(first);

    const before_reaped_collision = runtime.highWater();
    try std.testing.expectError(error.MessageIdAlreadyExists, runtime.startPrompt(.{
        .session_id = "reservation-after-reap",
        .prompt = "must not write",
        .input_id = capture.assistant[0..capture.assistant_len],
    }));
    try std.testing.expectEqual(before_reaped_collision, runtime.highWater());
    runtime.destroy();
    runtime_live = false;

    var reopened_stream_state = TestStreamState{ .io = std.testing.io };
    defer reopened_stream_state.deinit(std.testing.allocator);
    const reopened = try testRuntime(std.testing.allocator, &tmp, &reopened_stream_state);
    defer reopened.destroy();
    const before_restart_collision = reopened.highWater();
    try std.testing.expectError(error.MessageIdAlreadyExists, reopened.startPrompt(.{
        .session_id = "reservation-after-restart",
        .prompt = "must not write",
        .input_id = capture.assistant[0..capture.assistant_len],
    }));
    try std.testing.expectEqual(before_restart_collision, reopened.highWater());
}

test "two runtime-owned profiles reach StreamOptions without policy defaults" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPath(std.testing.io, &path);
    var stream_state = TestStreamState{ .io = std.testing.io, .environ = std.process.Environ.Map.init(std.testing.allocator) };
    defer stream_state.deinit(std.testing.allocator);
    const profiles = [_]model_catalog.ModelProfile{
        .{ .id = "small-sse", .name = "Small SSE", .provider = "provider-a", .context_window = 8192, .output_limit = 512, .max_output_tokens = 1024, .reasoning = .low, .cache_retention = .short, .cache_aggressiveness = .conservative, .transport = .sse },
        .{ .id = "large-ws", .name = "Large WS", .provider = "provider-b", .context_window = 65536, .output_limit = 4096, .max_output_tokens = 8192, .reasoning = .high, .cache_retention = .long, .cache_aggressiveness = .aggressive, .transport = .websocket },
    };
    const runtime = try Runtime.createForTest(std.testing.allocator, std.testing.io, &stream_state.environ.?, .{
        .workspace_root = path[0..path_len],
        .state_root = path[0..path_len],
        .system_prompt = "stable",
        .default_selection = .{ .provider = "provider-a", .model = "small-sse", .context_window = 8192, .output_limit = 512, .reasoning = .low, .cache_retention = .short, .cache_aggressiveness = .conservative, .transport = .sse },
        .model_profiles = &profiles,
    }, &stream_state, testStream);
    defer runtime.destroy();

    _ = try runtime.wait(try runtime.startPrompt(.{ .session_id = "profile-a", .prompt = "first" }));
    try std.testing.expectEqualStrings("small-sse", stream_state.seen_model.items);
    try std.testing.expectEqual(@as(?u64, 512), stream_state.seen_options.max_tokens);
    try std.testing.expectEqual(protocol.ThinkingLevel.low, stream_state.seen_options.reasoning.?);
    try std.testing.expectEqual(protocol.CacheRetention.short, stream_state.seen_options.cache_retention);
    try std.testing.expectEqual(protocol.CacheAggressiveness.conservative, stream_state.seen_options.cache_aggressiveness);
    try std.testing.expectEqual(protocol.Transport.sse, stream_state.seen_options.transport);

    stream_state.seen_model.clearRetainingCapacity();
    stream_state.seen_prompt.clearRetainingCapacity();
    const second = try runtime.selectionForModel("large-ws", null);
    defer deinitSelection(std.testing.allocator, second);
    try runtime.setSelection("profile-b", second);
    _ = try runtime.wait(try runtime.startPrompt(.{ .session_id = "profile-b", .prompt = "second" }));
    try std.testing.expectEqualStrings("large-ws", stream_state.seen_model.items);
    try std.testing.expectEqual(@as(?u64, 4096), stream_state.seen_options.max_tokens);
    try std.testing.expectEqual(protocol.ThinkingLevel.high, stream_state.seen_options.reasoning.?);
    try std.testing.expectEqual(protocol.CacheRetention.long, stream_state.seen_options.cache_retention);
    try std.testing.expectEqual(protocol.CacheAggressiveness.aggressive, stream_state.seen_options.cache_aggressiveness);
    try std.testing.expectEqual(protocol.Transport.websocket, stream_state.seen_options.transport);
}

test "runtime profile lookup fails closed for an unknown model" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();
    try std.testing.expectError(error.ModelNotConfigured, runtime.selectionForModel("adversarial-8k", null));
}

test "runtime validates selected output against profile capability" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();
    try std.testing.expectError(error.OutputLimitExceedsProfile, runtime.selectionForModel("selected", 78));
    const selected = try runtime.selectionForModel("selected", 76);
    defer deinitSelection(std.testing.allocator, selected);
    try std.testing.expectEqual(@as(u64, 76), selected.output_limit);
}

test "runtime rejects policy fields not supplied by its profile registry" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();
    try std.testing.expectError(error.InvalidModelSelection, runtime.setSelection("tamper", .{
        .provider = "test-provider",
        .model = "selected",
        .context_window = 4096,
        .output_limit = 77,
        .reasoning = .high,
        .cache_retention = .none,
        .cache_aggressiveness = .aggressive,
        .transport = .websocket,
    }));
    try std.testing.expectError(error.SessionNotFound, runtime.selection("tamper"));
}

test "selection lookup does not create a durable session" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();

    const before = runtime.highWater();
    try std.testing.expectError(error.SessionNotFound, runtime.selection("missing"));
    try std.testing.expectEqual(before, runtime.highWater());
    try std.testing.expectError(error.SessionNotFound, runtime.sessionMessages("missing", std.testing.allocator));
}

test "new durable session commits identity and initial selection in one record" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    var runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    var runtime_live = true;
    defer if (runtime_live) runtime.destroy();

    const before = runtime.highWater();
    const initial = try runtime.selectionForModel("selected", null);
    defer deinitSelection(std.testing.allocator, initial);
    const session_id = try runtime.createSession(initial, std.testing.allocator);
    defer std.testing.allocator.free(session_id);
    try std.testing.expectEqual(before + 1, runtime.highWater());
    const selected = try runtime.selection(session_id);
    defer deinitSelection(std.testing.allocator, selected);
    try std.testing.expectEqualStrings(initial.model, selected.model);
    try std.testing.expectEqualStrings(initial.provider, selected.provider);
    runtime.destroy();
    runtime_live = false;

    runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    runtime_live = true;
    const recovered = try runtime.selection(session_id);
    defer deinitSelection(std.testing.allocator, recovered);
    try std.testing.expectEqualStrings("selected", recovered.model);
    try std.testing.expectEqualStrings("test-provider", recovered.provider);
}

test "session summaries keep the first user prompt as a durable title" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream = TestStreamState{ .io = std.testing.io };
    defer stream.deinit(allocator);
    var runtime = try testRuntime(allocator, &tmp, &stream);
    var runtime_live = true;
    defer if (runtime_live) runtime.destroy();

    _ = try runtime.wait(try runtime.startPrompt(.{
        .session_id = "titled-session",
        .prompt = "  First prompt\nwith details  ",
    }));
    _ = try runtime.wait(try runtime.startPrompt(.{
        .session_id = "titled-session",
        .prompt = "later prompt must not replace the title",
    }));

    var live = try runtime.listSessions(allocator, null, maximum_session_list_items);
    defer live.deinit();
    try std.testing.expectEqual(@as(usize, 1), live.sessions.len);
    try std.testing.expectEqualStrings("First prompt with details", live.sessions[0].title.?);

    runtime.destroy();
    runtime_live = false;
    runtime = try testRuntime(allocator, &tmp, &stream);
    runtime_live = true;

    var recovered = try runtime.listSessions(allocator, null, maximum_session_list_items);
    defer recovered.deinit();
    try std.testing.expectEqual(@as(usize, 1), recovered.sessions.len);
    try std.testing.expectEqualStrings("First prompt with details", recovered.sessions[0].title.?);
}

test "runtime preserves sessions when a recovered model is no longer configured" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    const root = root_buffer[0..root_len];
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    var stream = TestStreamState{ .io = std.testing.io };
    defer stream.deinit(allocator);

    const original_profiles = [_]model_catalog.ModelProfile{
        .{ .id = "default", .name = "Default", .provider = "test", .context_window = 1024, .output_limit = 128, .max_output_tokens = 128 },
        .{ .id = "retired", .name = "Retired", .provider = "legacy", .context_window = 8192, .output_limit = 512, .max_output_tokens = 512 },
    };
    var runtime = try Runtime.createForTest(allocator, std.testing.io, &environment, .{
        .workspace_root = root,
        .state_root = root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "default", .context_window = 1024, .output_limit = 128 },
        .model_profiles = &original_profiles,
    }, &stream, testStream);
    const retired = try runtime.selectionForModel("retired", null);
    defer deinitSelection(allocator, retired);
    try runtime.createSessionWithId("recovered-retired", retired);
    runtime.destroy();

    const current_profiles = [_]model_catalog.ModelProfile{.{
        .id = "default",
        .name = "Default",
        .provider = "test",
        .context_window = 1024,
        .output_limit = 128,
        .max_output_tokens = 128,
    }};
    runtime = try Runtime.createForTest(allocator, std.testing.io, &environment, .{
        .workspace_root = root,
        .state_root = root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "default", .context_window = 1024, .output_limit = 128 },
        .model_profiles = &current_profiles,
    }, &stream, testStream);
    defer runtime.destroy();

    var listed = try runtime.listSessions(allocator, null, maximum_session_list_items);
    defer listed.deinit();
    try std.testing.expectEqual(@as(usize, 1), listed.sessions.len);
    try std.testing.expectEqualStrings("recovered-retired", listed.sessions[0].session_id);
    try std.testing.expectEqualStrings("retired", listed.sessions[0].model.?);
    try std.testing.expectError(error.ModelNotConfigured, runtime.startPrompt(.{
        .session_id = "recovered-retired",
        .prompt = "must fail before provider dispatch",
    }));

    try runtime.setSelection("recovered-retired", runtime.default_selection);
    _ = try runtime.wait(try runtime.startPrompt(.{
        .session_id = "recovered-retired",
        .prompt = "repaired",
    }));
    try std.testing.expectEqualStrings("default", stream.seen_model.items);
}

test "setting the current selection is a durable no-op" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();

    try runtime.setSelection("same", runtime.default_selection);
    const after_create = runtime.highWater();
    try runtime.setSelection("same", runtime.default_selection);
    try std.testing.expectEqual(after_create, runtime.highWater());
}

test "runtime model selection is the canonical protocol type" {
    comptime {
        if (ModelSelection != protocol.ModelSelection) @compileError("runtime ModelSelection must alias protocol.ModelSelection");
    }
}

test "provider session identifiers are emitted only for the supported dialect" {
    try std.testing.expectEqualStrings("session", sessionIdForDialect(.openrouter_chat, "session").?);
    try std.testing.expectEqual(@as(?[]const u8, null), sessionIdForDialect(.openai_chat, "session"));
    try std.testing.expectEqual(@as(?[]const u8, null), sessionIdForDialect(.deepseek_chat, "session"));
}

test "runtime provider API identity follows the configured dialect" {
    try std.testing.expectEqualStrings("openai-chat", apiNameForDialect(.openai_chat));
    try std.testing.expectEqualStrings("openrouter-chat", apiNameForDialect(.openrouter_chat));
    try std.testing.expectEqualStrings("deepseek-chat", apiNameForDialect(.deepseek_chat));
    try std.testing.expectEqualStrings("anthropic-messages", apiNameForDialect(.anthropic_messages));
}

test "runtime cancellation is idempotent and cannot settle as success" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io, .block = true };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();
    var capture = EventCapture{};
    defer capture.deinit(std.testing.allocator);
    const run_id = try runtime.startPrompt(.{ .session_id = "cancel", .prompt = "wait", .event_context = &capture, .emit = captureEvent });
    const first = try runtime.cancel(run_id);
    const second = try runtime.cancel(run_id);
    try std.testing.expectEqual(StopReason.canceled, first.stop_reason);
    try std.testing.expectEqual(first.stop_reason, second.stop_reason);
    try std.testing.expectEqual(@as(usize, 1), capture.settled_count);
}

test "runtime settles and releases the session when an event callback fails" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();
    var probe = SettledCallbackProbe{};
    const run_id = try runtime.startPrompt(.{ .session_id = "callback", .prompt = "finish", .event_context = &probe, .emit = observeSettledThenReject });
    const settlement = try runtime.wait(run_id);
    try std.testing.expectEqual(@as(?RunId, null), runtime.activeRun("callback"));
    try std.testing.expectEqual(StopReason.complete, settlement.stop_reason);
    try std.testing.expectEqual(FailureCategory.none, settlement.failure);
    try std.testing.expectEqual(@as(usize, 1), probe.observed);
    try std.testing.expectEqualDeep(settlement, try runtime.wait(run_id));
}

test "runtime maps a non-settled callback rejection to a typed callback failure" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();
    const run_id = try runtime.startPrompt(.{ .session_id = "callback-start", .prompt = "finish", .emit = rejectStartedEvent });
    const settlement = try runtime.wait(run_id);
    try std.testing.expectEqual(StopReason.failed, settlement.stop_reason);
    try std.testing.expectEqual(FailureCategory.callback, settlement.failure);
    try std.testing.expectEqual(@as(?RunId, null), runtime.activeRun("callback-start"));
    try std.testing.expectEqualDeep(settlement, try runtime.wait(run_id));
}

test "runtime maps provider stream callback rejection to callback failure" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();
    const run_id = try runtime.startPrompt(.{ .session_id = "callback-stream", .prompt = "finish", .emit = rejectProviderStreamEvent });
    const settlement = try runtime.wait(run_id);
    try std.testing.expectEqual(StopReason.failed, settlement.stop_reason);
    try std.testing.expectEqual(FailureCategory.callback, settlement.failure);
    try std.testing.expectEqualDeep(settlement, try runtime.wait(run_id));
}

test "completed runs keep only a bounded idempotency window" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    defer runtime.destroy();

    var first_settlement: ?Settlement = null;
    var last_id: RunId = 0;
    for (0..70) |index| {
        var session_buffer: [32]u8 = undefined;
        const session_id = try std.fmt.bufPrint(&session_buffer, "retained-{d}", .{index});
        last_id = try runtime.startPrompt(.{ .session_id = session_id, .prompt = "small" });
        const settlement = try runtime.wait(last_id);
        if (index == 0) first_settlement = settlement;
    }
    try std.testing.expectEqual(@as(usize, 0), runtime.runs.items.len);
    const last = try runtime.wait(last_id);
    try std.testing.expectEqualDeep(last, try runtime.cancel(last_id));
    try std.testing.expectError(error.RunNotFound, runtime.wait(first_settlement.?.run_id));
    try std.testing.expectEqualStrings("retained-0", first_settlement.?.session_id);
}

const TelemetryConcurrencyProbe = struct {
    io: std.Io,
    calls: std.atomic.Value(usize) = .init(0),
    inside: std.atomic.Value(bool) = .init(false),
    overlap: std.atomic.Value(bool) = .init(false),
};

fn concurrentTelemetryStream(raw: *anyopaque, model: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    const started: *std.atomic.Value(usize) = @ptrCast(@alignCast(raw));
    _ = started.fetchAdd(1, .acq_rel);
    while (started.load(.acquire) != 2) try std.Io.sleep(std.testing.io, .fromMilliseconds(1), .awake);
    const message = protocol.AssistantMessage{
        .content = &.{.{ .text = .{ .text = "done" } }},
        .api = model.api,
        .provider = model.provider,
        .model = model.id,
        .stop_reason = .stop,
        .timestamp_ms = 1,
    };
    try sink(sink_context, .{ .done = message });
}

fn detectConcurrentTelemetry(raw: *anyopaque, _: cache_telemetry.Call) void {
    const probe: *TelemetryConcurrencyProbe = @ptrCast(@alignCast(raw));
    if (probe.inside.swap(true, .acq_rel)) probe.overlap.store(true, .release);
    _ = probe.calls.fetchAdd(1, .acq_rel);
    std.Io.sleep(probe.io, .fromMilliseconds(20), .awake) catch {};
    probe.inside.store(false, .release);
}

test "runtime does not invent cache telemetry for injected streams" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPath(std.testing.io, &path);
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    var started: std.atomic.Value(usize) = .init(0);
    var probe = TelemetryConcurrencyProbe{ .io = std.testing.io };
    const runtime = try Runtime.createInternal(std.testing.allocator, std.testing.io, &environment, .{
        .workspace_root = path[0..path_len],
        .state_root = path[0..path_len],
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "default", .context_window = 1024, .output_limit = 128 },
        .telemetry_context = &probe,
        .telemetry = detectConcurrentTelemetry,
    }, &started, concurrentTelemetryStream);
    defer runtime.destroy();

    const first = try runtime.startPrompt(.{ .session_id = "telemetry-a", .prompt = "one" });
    const second = try runtime.startPrompt(.{ .session_id = "telemetry-b", .prompt = "two" });
    _ = try runtime.wait(first);
    _ = try runtime.wait(second);
    try std.testing.expectEqual(@as(usize, 0), probe.calls.load(.acquire));
    try std.testing.expect(!probe.overlap.load(.acquire));
}

const IndependentRunProbe = struct {
    io: std.Io,
    release_first: std.atomic.Value(bool) = .init(false),
};

fn independentRunStream(raw: *anyopaque, model: *const protocol.Model, _: *const protocol.Context, options: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    const probe: *IndependentRunProbe = @ptrCast(@alignCast(raw));
    if (std.mem.eql(u8, options.session_id.?, "independent-a")) {
        while (!probe.release_first.load(.acquire)) try std.Io.sleep(probe.io, .fromMilliseconds(1), .awake);
    } else {
        try std.Io.sleep(probe.io, .fromSeconds(60), .awake);
    }
    const message = protocol.AssistantMessage{
        .content = &.{.{ .text = .{ .text = "done" } }},
        .api = model.api,
        .provider = model.provider,
        .model = model.id,
        .stop_reason = .stop,
        .timestamp_ms = 1,
    };
    try sink(sink_context, .{ .done = message });
}

const RuntimeCallProbe = struct {
    runtime: *Runtime,
    run_id: RunId,
    entered: std.atomic.Value(bool) = .init(false),
    returned: std.atomic.Value(bool) = .init(false),
};

fn waitForRun(probe: *RuntimeCallProbe) !void {
    probe.entered.store(true, .release);
    _ = try probe.runtime.wait(probe.run_id);
    probe.returned.store(true, .release);
}

fn cancelRun(probe: *RuntimeCallProbe) !void {
    probe.entered.store(true, .release);
    _ = try probe.runtime.cancel(probe.run_id);
    probe.returned.store(true, .release);
}

test "waiting on one session does not block cancellation of another" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPath(std.testing.io, &path);
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    var stream_probe = IndependentRunProbe{ .io = std.testing.io };
    const runtime = try Runtime.createInternal(std.testing.allocator, std.testing.io, &environment, .{
        .workspace_root = path[0..path_len],
        .state_root = path[0..path_len],
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "default", .context_window = 1024, .output_limit = 128 },
    }, &stream_probe, independentRunStream);
    defer runtime.destroy();

    const first = try runtime.startPrompt(.{ .session_id = "independent-a", .prompt = "wait" });
    const second = try runtime.startPrompt(.{ .session_id = "independent-b", .prompt = "cancel" });
    var waiter = RuntimeCallProbe{ .runtime = runtime, .run_id = first };
    var canceler = RuntimeCallProbe{ .runtime = runtime, .run_id = second };
    var wait_future = try std.Io.concurrent(std.testing.io, waitForRun, .{&waiter});
    while (!waiter.entered.load(.acquire)) try std.Io.sleep(std.testing.io, .fromMilliseconds(1), .awake);
    try std.Io.sleep(std.testing.io, .fromMilliseconds(10), .awake);
    var cancel_future = try std.Io.concurrent(std.testing.io, cancelRun, .{&canceler});
    while (!canceler.entered.load(.acquire)) try std.Io.sleep(std.testing.io, .fromMilliseconds(1), .awake);
    var attempts: usize = 0;
    while (!canceler.returned.load(.acquire) and attempts < 1000) : (attempts += 1) {
        try std.Io.sleep(std.testing.io, .fromMilliseconds(1), .awake);
    }
    const canceled_independently = canceler.returned.load(.acquire);
    stream_probe.release_first.store(true, .release);
    try wait_future.await(std.testing.io);
    try cancel_future.await(std.testing.io);
    try std.testing.expect(canceled_independently);
}

test "runtime destroy cancels active futures and releases owned state" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var stream_state = TestStreamState{ .io = std.testing.io, .block = true };
    defer stream_state.deinit(std.testing.allocator);
    const runtime = try testRuntime(std.testing.allocator, &tmp, &stream_state);
    _ = try runtime.startPrompt(.{ .session_id = "shutdown", .prompt = "wait" });
    runtime.destroy();
}

test "runtime destroy durably cancels an active run before closing the store" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    const root = root_buffer[0..root_len];
    const event_path = try std.fs.path.join(allocator, &.{ root, event_log_name });
    defer allocator.free(event_path);
    var stream = TestStreamState{ .io = std.testing.io, .block = true };
    defer stream.deinit(allocator);
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    const runtime = try Runtime.createForTest(allocator, std.testing.io, &environment, .{
        .workspace_root = root,
        .state_root = root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "default", .context_window = 1024, .output_limit = 128 },
    }, &stream, testStream);
    _ = try runtime.startPrompt(.{ .session_id = "shutdown-durable", .prompt = "wait" });
    runtime.destroy();

    var reopened = try event_store.Store.open(allocator, std.testing.io, event_path, .{});
    defer reopened.close();
    var probe = ShutdownSettlementProbe{};
    const report = try reopened.store.replayWithContext(@ptrCast(&probe), inspectShutdownSettlement);
    try std.testing.expect(report.writable);
    try std.testing.expectEqual(@as(usize, 1), probe.canceled);
    try std.testing.expectEqual(@as(usize, 0), probe.interrupted);
}
