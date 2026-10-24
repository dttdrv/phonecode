const std = @import("std");
const event_store = @import("event_store.zig");
const protocol = @import("protocol.zig");

pub const Role = enum { user, assistant, tool_result, custom, branch_summary, compaction_summary };

pub const Message = struct {
    role: Role,
    content: []u8,

    pub fn init(allocator: std.mem.Allocator, role: Role, content: []const u8) !Message {
        return .{ .role = role, .content = try allocator.dupe(u8, content) };
    }

    pub fn clone(self: Message, allocator: std.mem.Allocator) !Message {
        return init(allocator, self.role, self.content);
    }

    pub fn deinit(self: *Message, allocator: std.mem.Allocator) void {
        allocator.free(self.content);
        self.* = undefined;
    }
};

pub const Compaction = struct {
    summary: []u8,
    first_kept_id: []u8,
    tokens_before: usize,
};

pub const BranchSummary = struct {
    summary: []u8,
    from_id: []u8,
};

pub const Payload = union(enum) {
    message: Message,
    leaf: ?[]u8,
    compaction: Compaction,
    branch_summary: BranchSummary,
};

pub const Entry = struct {
    id: []u8,
    parent_id: ?[]u8,
    timestamp: u64,
    payload: Payload,

    pub fn deinit(self: *Entry, allocator: std.mem.Allocator) void {
        allocator.free(self.id);
        if (self.parent_id) |parent| allocator.free(parent);
        switch (self.payload) {
            .message => |*message| message.deinit(allocator),
            .leaf => |target| if (target) |id| allocator.free(id),
            .compaction => |compaction| {
                allocator.free(compaction.summary);
                allocator.free(compaction.first_kept_id);
            },
            .branch_summary => |summary| {
                allocator.free(summary.summary);
                allocator.free(summary.from_id);
            },
        }
        self.* = undefined;
    }
};

pub const Metadata = struct {
    id: []u8,
    created_at: u64,
    cwd: []u8,
};

pub const DurableMessage = struct {
    id: []u8,
    branch_id: []u8,
    parent_id: ?[]u8,
    role: Role,
    content: []u8,

    pub fn deinit(self: *DurableMessage, allocator: std.mem.Allocator) void {
        allocator.free(self.id);
        allocator.free(self.branch_id);
        if (self.parent_id) |id| allocator.free(id);
        allocator.free(self.content);
        self.* = undefined;
    }
};

pub const PageOrder = enum { ascending, descending };

pub const DurableMessagePage = struct {
    allocator: std.mem.Allocator,
    messages: []DurableMessage,
    has_more: bool,

    pub fn deinit(self: *DurableMessagePage) void {
        for (self.messages) |*message| message.deinit(self.allocator);
        self.allocator.free(self.messages);
        self.* = undefined;
    }
};

pub const DurableBranch = struct {
    id: []u8,
    parent_branch_id: ?[]u8,
    head_message_id: ?[]u8 = null,

    fn deinit(self: *DurableBranch, allocator: std.mem.Allocator) void {
        allocator.free(self.id);
        if (self.parent_branch_id) |id| allocator.free(id);
        if (self.head_message_id) |id| allocator.free(id);
        self.* = undefined;
    }
};

pub const DurableRun = struct {
    id: []u8,
    /// A replayable reservation even when the run settles before producing an
    /// assistant message. It prevents an exposed live ID from being reused.
    assistant_message_id: ?[]u8 = null,
    settled: bool = false,
    failed: bool = false,
    cancelled: bool = false,
    interrupted: bool = false,
    cancellation_requested: bool = false,
    failure_observed: bool = false,

    fn deinit(self: *DurableRun, allocator: std.mem.Allocator) void {
        allocator.free(self.id);
        if (self.assistant_message_id) |id| allocator.free(id);
        self.* = undefined;
    }
};

pub const DurableTool = struct {
    run_id: []u8,
    tool_id: []u8,
    finished: bool = false,

    fn deinit(self: *DurableTool, allocator: std.mem.Allocator) void {
        allocator.free(self.run_id);
        allocator.free(self.tool_id);
        self.* = undefined;
    }
};

pub const EpochRef = struct {
    id: []u8,
    predecessor_id: ?[]u8,
    source_first_record: u64,
    source_last_record: u64,
    first_retained_id: []u8,
    summary: []u8,
    previous_summary_digest: ?[]u8,
    predecessor_after_serialized_bytes: ?usize,
    predecessor_after_provider_input_tokens: ?usize,
    before_serialized_bytes: usize,
    before_provider_input_tokens: usize,
    after_serialized_bytes: usize,
    after_provider_input_tokens: usize,
    measurement_provider: []u8,
    measurement_model: []u8,
    measurement_schema: []u8,
    measurement_schema_version: u16,
    measurement_token_basis: []u8,
    retained_causal_ids: [][]u8,
    stable_block_ids: [][]u8,

    fn deinit(self: *EpochRef, allocator: std.mem.Allocator) void {
        allocator.free(self.id);
        if (self.predecessor_id) |id| allocator.free(id);
        allocator.free(self.first_retained_id);
        allocator.free(self.summary);
        if (self.previous_summary_digest) |digest| allocator.free(digest);
        allocator.free(self.measurement_provider);
        allocator.free(self.measurement_model);
        allocator.free(self.measurement_schema);
        allocator.free(self.measurement_token_basis);
        freeDurableIds(allocator, self.retained_causal_ids);
        freeDurableIds(allocator, self.stable_block_ids);
        self.* = undefined;
    }
};

const PreparedOperation = enum {
    ignored,
    session_created,
    selection_model,
    branch_created,
    branch_selected,
    message_appended,
    run_started,
    run_settled,
    tool_started,
    tool_finished,
    cancellation_requested,
    failure,
    compaction_epoch_committed,
};

const OwnedInitialSelection = struct {
    selected_model: []u8,
    selection: protocol.ModelSelection,

    fn init(allocator: std.mem.Allocator, wire: DurableWire) !?OwnedInitialSelection {
        const model = wire.model orelse {
            if (wire.provider != null or wire.context_window != null or wire.output_limit != null or wire.reasoning != null or
                wire.cache_retention != null or wire.cache_aggressiveness != null or wire.transport != null) return error.InvalidSessionEvent;
            return null;
        };
        const provider = wire.provider orelse return error.InvalidSessionEvent;
        const context_window = wire.context_window orelse return error.InvalidSessionEvent;
        const output_limit = wire.output_limit orelse return error.InvalidSessionEvent;
        if (!validDurableId(model) or !validDurableId(provider) or context_window == 0 or output_limit == 0 or output_limit > context_window) return error.InvalidSessionEvent;
        const selected_model = try allocator.dupe(u8, model);
        errdefer allocator.free(selected_model);
        const selection_model = try allocator.dupe(u8, model);
        errdefer allocator.free(selection_model);
        const owned_provider = try allocator.dupe(u8, provider);
        return .{
            .selected_model = selected_model,
            .selection = .{
                .provider = owned_provider,
                .model = selection_model,
                .context_window = context_window,
                .output_limit = output_limit,
                .reasoning = wire.reasoning,
                .cache_retention = wire.cache_retention orelse .none,
                .cache_aggressiveness = wire.cache_aggressiveness orelse .balanced,
                .transport = wire.transport orelse .auto,
            },
        };
    }

    fn deinit(self: *OwnedInitialSelection, allocator: std.mem.Allocator) void {
        allocator.free(self.selected_model);
        self.selection.deinit(allocator);
        self.* = undefined;
    }
};

const Prepared = struct {
    operation: PreparedOperation,
    expected_event_count: u64,
    owner: ?*DurableProjection = null,
    session_id: ?[]u8 = null,
    branch_id: ?[]u8 = null,
    parent_branch_id: ?[]u8 = null,
    inherited_head_id: ?[]u8 = null,
    parent_id: ?[]u8 = null,
    head_message_id: ?[]u8 = null,
    message_id: ?[]u8 = null,
    role: Role = .user,
    content: ?[]u8 = null,
    model: ?[]u8 = null,
    selection_model: ?[]u8 = null,
    run_id: ?[]u8 = null,
    assistant_message_id: ?[]u8 = null,
    tool_id: ?[]u8 = null,
    epoch_id: ?[]u8 = null,
    predecessor_epoch_id: ?[]u8 = null,
    first_retained_id: ?[]u8 = null,
    summary: ?[]u8 = null,
    previous_summary_digest: ?[]u8 = null,
    predecessor_after_serialized_bytes: ?usize = null,
    predecessor_after_provider_input_tokens: ?usize = null,
    provider: ?[]u8 = null,
    context_window: u64 = 0,
    output_limit: u64 = 0,
    reasoning: ?protocol.ThinkingLevel = null,
    cache_retention: protocol.CacheRetention = .none,
    cache_aggressiveness: protocol.CacheAggressiveness = .balanced,
    transport: protocol.Transport = .auto,
    retained_causal_ids: ?[][]u8 = null,
    stable_block_ids: ?[][]u8 = null,
    source_first_record: u64 = 0,
    source_last_record: u64 = 0,
    before_serialized_bytes: usize = 0,
    before_provider_input_tokens: usize = 0,
    after_serialized_bytes: usize = 0,
    after_provider_input_tokens: usize = 0,
    measurement_provider: ?[]u8 = null,
    measurement_model: ?[]u8 = null,
    measurement_schema: ?[]u8 = null,
    measurement_schema_version: u16 = 0,
    measurement_token_basis: ?[]u8 = null,
    branch_index: usize = 0,
    run_index: usize = 0,
    tool_index: usize = 0,
    settlement: event_store.Settlement = .pending,
    initial_selection: ?OwnedInitialSelection = null,

    pub fn deinit(self: *Prepared, allocator: std.mem.Allocator) void {
        if (self.session_id) |value| allocator.free(value);
        if (self.branch_id) |value| allocator.free(value);
        if (self.parent_branch_id) |value| allocator.free(value);
        if (self.inherited_head_id) |value| allocator.free(value);
        if (self.parent_id) |value| allocator.free(value);
        if (self.head_message_id) |value| allocator.free(value);
        if (self.message_id) |value| allocator.free(value);
        if (self.content) |value| allocator.free(value);
        if (self.model) |value| allocator.free(value);
        if (self.selection_model) |value| allocator.free(value);
        if (self.run_id) |value| allocator.free(value);
        if (self.assistant_message_id) |value| allocator.free(value);
        if (self.tool_id) |value| allocator.free(value);
        if (self.epoch_id) |value| allocator.free(value);
        if (self.predecessor_epoch_id) |value| allocator.free(value);
        if (self.first_retained_id) |value| allocator.free(value);
        if (self.summary) |value| allocator.free(value);
        if (self.previous_summary_digest) |value| allocator.free(value);
        if (self.provider) |value| allocator.free(value);
        if (self.measurement_provider) |value| allocator.free(value);
        if (self.measurement_model) |value| allocator.free(value);
        if (self.measurement_schema) |value| allocator.free(value);
        if (self.measurement_token_basis) |value| allocator.free(value);
        if (self.retained_causal_ids) |value| freeDurableIds(allocator, value);
        if (self.stable_block_ids) |value| freeDurableIds(allocator, value);
        if (self.initial_selection) |*value| value.deinit(allocator);
        self.* = undefined;
    }
};

/// Rebuildable session state. The event log remains authoritative; this
/// projection owns only copies needed by adapters and context assembly.
pub const DurableProjection = struct {
    allocator: std.mem.Allocator,
    routing_session_id: ?[]u8 = null,
    session_id: ?[]u8 = null,
    selected_branch_id: ?[]u8 = null,
    selected_model: ?[]u8 = null,
    selected_selection: ?protocol.ModelSelection = null,
    created_at_ms: u64 = 0,
    updated_at_ms: u64 = 0,
    branches: std.ArrayList(DurableBranch) = .empty,
    messages: std.ArrayList(DurableMessage) = .empty,
    runs: std.ArrayList(DurableRun) = .empty,
    tools: std.ArrayList(DurableTool) = .empty,
    epochs: std.ArrayList(EpochRef) = .empty,
    event_count: u64 = 0,
    projection_store: ?*event_store.Store = null,
    projection_store_id: ?[event_store.hash_bytes]u8 = null,
    projection_cursor: event_store.ProjectionCursor = .unbound,
    applied_global_sequence: u64 = 0,
    applied_global_hash: [event_store.hash_bytes]u8 = @splat(0),
    projection_mutex: std.atomic.Mutex = .unlocked,

    pub fn init(allocator: std.mem.Allocator) DurableProjection {
        return .{ .allocator = allocator };
    }

    fn lockProjection(self: *DurableProjection) void {
        while (!self.projection_mutex.tryLock()) std.atomic.spinLoopHint();
    }

    fn unlockProjection(self: *DurableProjection) void {
        self.projection_mutex.unlock();
    }

    pub fn deinit(self: *DurableProjection) void {
        if (self.projection_store) |store| store.retireProjectionCursor(@ptrCast(self), &self.projection_cursor, @ptrCast(&projection_cursor_authority)) catch {};
        self.deinitContents();
        self.* = undefined;
    }

    fn deinitContents(self: *DurableProjection) void {
        if (self.routing_session_id) |id| self.allocator.free(id);
        if (self.session_id) |id| self.allocator.free(id);
        if (self.selected_branch_id) |id| self.allocator.free(id);
        if (self.selected_model) |id| self.allocator.free(id);
        if (self.selected_selection) |selection| selection.deinit(self.allocator);
        for (self.branches.items) |*branch_value| branch_value.deinit(self.allocator);
        for (self.messages.items) |*message| message.deinit(self.allocator);
        for (self.runs.items) |*run| run.deinit(self.allocator);
        for (self.tools.items) |*tool| tool.deinit(self.allocator);
        for (self.epochs.items) |*epoch| epoch.deinit(self.allocator);
        self.branches.deinit(self.allocator);
        self.messages.deinit(self.allocator);
        self.runs.deinit(self.allocator);
        self.tools.deinit(self.allocator);
        self.epochs.deinit(self.allocator);
    }

    fn applyExact(self: *DurableProjection, record: event_store.RecordView) !void {
        self.lockProjection();
        defer self.unlockProjection();
        try self.applyInPlace(record);
        self.applied_global_sequence = record.global_sequence;
        self.applied_global_hash = record.record_hash;
    }

    fn prepare(self: *DurableProjection, event: event_store.Event, settlement: event_store.Settlement) !Prepared {
        self.lockProjection();
        defer self.unlockProjection();
        const operation: PreparedOperation = switch (event.kind) {
            .session_created => .session_created,
            .session_selection_changed => .selection_model,
            .branch_created => .branch_created,
            .branch_selected => .branch_selected,
            .message_appended => .message_appended,
            .run_started => .run_started,
            .run_settled => .run_settled,
            .tool_started => .tool_started,
            .tool_finished => .tool_finished,
            .cancellation_requested => .cancellation_requested,
            .failure => .failure,
            .compaction_epoch_committed => .compaction_epoch_committed,
            else => return .{ .operation = .ignored, .expected_event_count = self.event_count, .owner = self, .settlement = settlement },
        };
        const parsed = std.json.parseFromSlice(DurableWire, self.allocator, event.payload, .{}) catch |err| switch (err) {
            error.OutOfMemory => return error.OutOfMemory,
            else => return error.InvalidSessionEvent,
        };
        defer parsed.deinit();
        const wire = parsed.value;
        if (wire.session_id.len == 0 or wire.session_id.len > event_store.max_event_id_bytes) return error.InvalidSessionEvent;
        if (self.session_id) |id| {
            if (!std.mem.eql(u8, id, wire.session_id)) return error.SessionMismatch;
        } else if (operation != .session_created) return error.SessionNotCreated;
        var prepared = Prepared{ .operation = operation, .expected_event_count = self.event_count, .owner = self, .settlement = settlement };
        errdefer prepared.deinit(self.allocator);
        switch (operation) {
            .ignored => {},
            .session_created => {
                if (self.branches.items.len != 0 or self.event_count != 0) return error.DuplicateSession;
                const branch_id = wire.branch_id orelse return error.InvalidSessionEvent;
                if (!validDurableId(branch_id)) return error.InvalidSessionEvent;
                prepared.session_id = try self.allocator.dupe(u8, wire.session_id);
                prepared.branch_id = try self.allocator.dupe(u8, branch_id);
                prepared.model = try self.allocator.dupe(u8, branch_id);
                prepared.initial_selection = try OwnedInitialSelection.init(self.allocator, wire);
                try self.branches.ensureUnusedCapacity(self.allocator, 1);
            },
            .selection_model => {
                if (self.branches.items.len == 0) return error.SessionNotCreated;
                const model = wire.model orelse return error.InvalidSessionEvent;
                if (!validDurableId(model)) return error.InvalidSessionEvent;
                prepared.model = try self.allocator.dupe(u8, model);
                prepared.selection_model = try self.allocator.dupe(u8, model);
                const provider = wire.provider orelse "";
                if (provider.len > event_store.max_event_id_bytes) return error.InvalidSessionEvent;
                prepared.provider = try self.allocator.dupe(u8, provider);
                prepared.context_window = wire.context_window orelse 0;
                prepared.output_limit = wire.output_limit orelse 0;
                prepared.reasoning = wire.reasoning;
                prepared.cache_retention = wire.cache_retention orelse .none;
                prepared.cache_aggressiveness = wire.cache_aggressiveness orelse .balanced;
                prepared.transport = wire.transport orelse .auto;
            },
            .branch_created => {
                const branch_id = wire.branch_id orelse return error.InvalidSessionEvent;
                if (!validDurableId(branch_id) or self.findBranch(branch_id) != null) return error.DuplicateBranch;
                const parent = wire.parent_branch_id;
                const parent_branch = if (parent) |id| self.findBranch(id) orelse return error.UnknownParentBranch else null;
                prepared.branch_id = try self.allocator.dupe(u8, branch_id);
                if (parent) |id| prepared.parent_branch_id = try self.allocator.dupe(u8, id);
                if (parent_branch) |value| {
                    if (value.head_message_id) |id| prepared.inherited_head_id = try self.allocator.dupe(u8, id);
                }
                try self.branches.ensureUnusedCapacity(self.allocator, 1);
            },
            .branch_selected => {
                const branch_id = wire.branch_id orelse return error.InvalidSessionEvent;
                if (self.findBranch(branch_id) == null) return error.UnknownBranch;
                prepared.branch_id = try self.allocator.dupe(u8, branch_id);
            },
            .message_appended => {
                const branch_id = wire.branch_id orelse return error.InvalidSessionEvent;
                const message_id = wire.message_id orelse return error.InvalidSessionEvent;
                const role_text = wire.role orelse return error.InvalidSessionEvent;
                const content = wire.content orelse return error.InvalidSessionEvent;
                const branch = self.findBranch(branch_id) orelse return error.UnknownBranch;
                const role = std.meta.stringToEnum(Role, role_text) orelse return error.InvalidSessionEvent;
                if (!validDurableId(message_id) or self.findMessage(message_id) != null) return error.DuplicateMessage;
                if (wire.parent_id) |parent| {
                    if (self.findMessage(parent) == null or branch.head_message_id == null or !std.mem.eql(u8, branch.head_message_id.?, parent)) return error.InvalidParentage;
                } else if (branch.head_message_id != null) return error.MissingParentMessage;
                prepared.branch_index = @intCast(@as(usize, @intCast(branch - self.branches.items.ptr)));
                prepared.message_id = try self.allocator.dupe(u8, message_id);
                prepared.branch_id = try self.allocator.dupe(u8, branch_id);
                if (wire.parent_id) |parent| prepared.parent_id = try self.allocator.dupe(u8, parent);
                prepared.content = try self.allocator.dupe(u8, content);
                prepared.role = role;
                prepared.head_message_id = try self.allocator.dupe(u8, message_id);
                if (self.selected_branch_id == null) prepared.model = try self.allocator.dupe(u8, branch_id);
                try self.messages.ensureUnusedCapacity(self.allocator, 1);
            },
            .run_started => {
                const run_id = wire.run_id orelse return error.InvalidSessionEvent;
                if (!validDurableId(run_id)) return error.InvalidSessionEvent;
                if (self.findRun(run_id) != null) return error.DuplicateRun;
                prepared.run_id = try self.allocator.dupe(u8, run_id);
                if (wire.assistant_message_id) |message_id| {
                    if (!validDurableId(message_id) or self.findMessage(message_id) != null or self.findReservedMessageId(message_id)) return error.DuplicateMessage;
                    prepared.assistant_message_id = try self.allocator.dupe(u8, message_id);
                }
                try self.runs.ensureUnusedCapacity(self.allocator, 1);
            },
            .run_settled, .cancellation_requested, .failure => {
                const run_id = wire.run_id orelse return error.InvalidSessionEvent;
                const run = self.findRun(run_id) orelse return error.UnknownRun;
                prepared.run_id = try self.allocator.dupe(u8, run_id);
                prepared.run_index = @intCast(@as(usize, @intCast(run - self.runs.items.ptr)));
                if (operation == .run_settled) {
                    if (settlement == .pending) return error.InvalidSettlement;
                    if (run.settled) return error.DuplicateSettlement;
                }
            },
            .tool_started => {
                const run_id = wire.run_id orelse return error.InvalidSessionEvent;
                const tool_id = wire.tool_id orelse return error.InvalidSessionEvent;
                if (!validDurableId(run_id) or !validDurableId(tool_id)) return error.InvalidSessionEvent;
                if (self.findRun(run_id) == null) return error.UnknownRun;
                if (self.findTool(run_id, tool_id) != null) return error.DuplicateTool;
                prepared.run_id = try self.allocator.dupe(u8, run_id);
                prepared.tool_id = try self.allocator.dupe(u8, tool_id);
                try self.tools.ensureUnusedCapacity(self.allocator, 1);
            },
            .tool_finished => {
                const run_id = wire.run_id orelse return error.InvalidSessionEvent;
                const tool_id = wire.tool_id orelse return error.InvalidSessionEvent;
                const tool = self.findTool(run_id, tool_id) orelse return error.UnknownTool;
                if (tool.finished) return error.DuplicateToolSettlement;
                prepared.run_index = @intCast(@as(usize, @intCast(tool - self.tools.items.ptr)));
            },
            .compaction_epoch_committed => {
                const epoch_id = wire.epoch_id orelse return error.InvalidSessionEvent;
                const first_retained_id = wire.first_retained_id orelse return error.InvalidSessionEvent;
                const summary = wire.summary orelse return error.InvalidSessionEvent;
                const previous_summary_digest = wire.previous_summary_digest;
                const source_first = wire.source_first_record orelse return error.InvalidSessionEvent;
                const source_last = wire.source_last_record orelse return error.InvalidSessionEvent;
                const before_bytes = wire.before_serialized_bytes orelse return error.InvalidSessionEvent;
                const before_tokens = wire.before_provider_input_tokens orelse return error.InvalidSessionEvent;
                const after_bytes = wire.after_serialized_bytes orelse return error.InvalidSessionEvent;
                const after_tokens = wire.after_provider_input_tokens orelse return error.InvalidSessionEvent;
                const measurement_provider = wire.measurement_provider orelse return error.InvalidSessionEvent;
                const measurement_model = wire.measurement_model orelse return error.InvalidSessionEvent;
                const measurement_schema = wire.measurement_schema orelse return error.InvalidSessionEvent;
                const measurement_schema_version = wire.measurement_schema_version orelse return error.InvalidSessionEvent;
                const measurement_token_basis = wire.measurement_token_basis orelse return error.InvalidSessionEvent;
                if (!validDurableId(epoch_id) or self.findEpoch(epoch_id) != null or source_last < source_first or
                    !validDurableId(first_retained_id) or summary.len == 0 or summary.len > event_store.max_payload_bytes or
                    after_bytes >= before_bytes or after_tokens >= before_tokens) return error.InvalidSessionEvent;
                try validateMeasurement(measurement_provider, measurement_model, measurement_schema, measurement_schema_version, measurement_token_basis);
                if (previous_summary_digest) |digest| if (!validDigest(digest)) return error.InvalidSessionEvent;
                if (wire.predecessor_epoch_id) |predecessor| {
                    try self.validateEpochPredecessor(wire, predecessor);
                } else {
                    if (self.epochs.items.len != 0 or previous_summary_digest == null or wire.predecessor_after_serialized_bytes != null or wire.predecessor_after_provider_input_tokens != null) return error.StaleEpoch;
                    var empty_digest: [32]u8 = undefined;
                    summaryDigest(&empty_digest, "");
                    var empty_text: [64]u8 = undefined;
                    formatDigest(&empty_text, empty_digest);
                    if (!std.mem.eql(u8, previous_summary_digest.?, &empty_text)) return error.StaleEpoch;
                }
                prepared.epoch_id = try self.allocator.dupe(u8, epoch_id);
                if (wire.predecessor_epoch_id) |id| prepared.predecessor_epoch_id = try self.allocator.dupe(u8, id);
                prepared.first_retained_id = try self.allocator.dupe(u8, first_retained_id);
                prepared.summary = try self.allocator.dupe(u8, summary);
                if (previous_summary_digest) |digest| prepared.previous_summary_digest = try self.allocator.dupe(u8, digest);
                prepared.predecessor_after_serialized_bytes = wire.predecessor_after_serialized_bytes;
                prepared.predecessor_after_provider_input_tokens = wire.predecessor_after_provider_input_tokens;
                prepared.retained_causal_ids = try cloneDurableIds(self.allocator, wire.retained_causal_ids orelse return error.InvalidSessionEvent);
                prepared.stable_block_ids = try cloneDurableIds(self.allocator, wire.stable_block_ids orelse return error.InvalidSessionEvent);
                prepared.source_first_record = source_first;
                prepared.source_last_record = source_last;
                prepared.before_serialized_bytes = before_bytes;
                prepared.before_provider_input_tokens = before_tokens;
                prepared.after_serialized_bytes = after_bytes;
                prepared.after_provider_input_tokens = after_tokens;
                prepared.measurement_provider = try self.allocator.dupe(u8, measurement_provider);
                prepared.measurement_model = try self.allocator.dupe(u8, measurement_model);
                prepared.measurement_schema = try self.allocator.dupe(u8, measurement_schema);
                prepared.measurement_schema_version = measurement_schema_version;
                prepared.measurement_token_basis = try self.allocator.dupe(u8, measurement_token_basis);
                try self.epochs.ensureUnusedCapacity(self.allocator, 1);
            },
        }
        return prepared;
    }

    fn validate(self: *const DurableProjection, prepared: *const Prepared) bool {
        const mutable_self = @constCast(self);
        mutable_self.lockProjection();
        defer mutable_self.unlockProjection();
        return prepared.owner == self and prepared.expected_event_count == self.event_count;
    }

    fn commitChecked(self: *DurableProjection, prepared: *Prepared, record: event_store.RecordView) !void {
        self.lockProjection();
        defer self.unlockProjection();
        if (prepared.owner != self) return error.PreparedProjectionMismatch;
        if (prepared.expected_event_count != self.event_count) return error.PreparedProjectionStale;
        self.commitUnlocked(prepared, record);
    }

    fn commitUnlocked(self: *DurableProjection, prepared: *Prepared, record: event_store.RecordView) void {
        switch (prepared.operation) {
            .ignored => {
                self.applied_global_sequence = record.global_sequence;
                self.applied_global_hash = record.record_hash;
                return;
            },
            .session_created => {
                self.branches.appendAssumeCapacity(.{ .id = prepared.branch_id.?, .parent_branch_id = null });
                self.session_id = prepared.session_id.?;
                self.selected_branch_id = prepared.model.?;
                if (prepared.initial_selection) |initial| {
                    self.selected_model = initial.selected_model;
                    self.selected_selection = initial.selection;
                    prepared.initial_selection = null;
                }
                prepared.branch_id = null;
                prepared.session_id = null;
                prepared.model = null;
            },
            .selection_model => {
                if (self.selected_model) |id| self.allocator.free(id);
                self.selected_model = prepared.model.?;
                prepared.model = null;
                if (self.selected_selection) |selection| selection.deinit(self.allocator);
                self.selected_selection = .{
                    .provider = prepared.provider.?,
                    .model = prepared.selection_model.?,
                    .context_window = prepared.context_window,
                    .output_limit = prepared.output_limit,
                    .reasoning = prepared.reasoning,
                    .cache_retention = prepared.cache_retention,
                    .cache_aggressiveness = prepared.cache_aggressiveness,
                    .transport = prepared.transport,
                };
                prepared.provider = null;
                prepared.selection_model = null;
            },
            .branch_created => {
                self.branches.appendAssumeCapacity(.{ .id = prepared.branch_id.?, .parent_branch_id = prepared.parent_branch_id, .head_message_id = prepared.inherited_head_id });
                prepared.branch_id = null;
                prepared.parent_branch_id = null;
                prepared.inherited_head_id = null;
            },
            .branch_selected => {
                if (self.selected_branch_id) |id| self.allocator.free(id);
                self.selected_branch_id = prepared.branch_id.?;
                prepared.branch_id = null;
            },
            .message_appended => {
                self.messages.appendAssumeCapacity(.{ .id = prepared.message_id.?, .branch_id = prepared.branch_id.?, .parent_id = prepared.parent_id, .role = prepared.role, .content = prepared.content.? });
                prepared.message_id = null;
                prepared.branch_id = null;
                prepared.parent_id = null;
                prepared.content = null;
                const branch = &self.branches.items[prepared.branch_index];
                if (branch.head_message_id) |id| self.allocator.free(id);
                branch.head_message_id = prepared.head_message_id.?;
                prepared.head_message_id = null;
                if (self.selected_branch_id == null) {
                    self.selected_branch_id = prepared.model.?;
                    prepared.model = null;
                }
            },
            .run_started => {
                self.runs.appendAssumeCapacity(.{ .id = prepared.run_id.?, .assistant_message_id = prepared.assistant_message_id });
                prepared.run_id = null;
                prepared.assistant_message_id = null;
            },
            .run_settled => {
                const run = &self.runs.items[prepared.run_index];
                run.settled = true;
                run.failed = record.settlement == .failed;
                run.cancelled = record.settlement == .cancelled;
                run.interrupted = record.settlement == .interrupted;
                self.allocator.free(prepared.run_id.?);
                prepared.run_id = null;
            },
            .tool_started => {
                self.tools.appendAssumeCapacity(.{ .run_id = prepared.run_id.?, .tool_id = prepared.tool_id.? });
                prepared.run_id = null;
                prepared.tool_id = null;
            },
            .tool_finished => self.tools.items[prepared.run_index].finished = true,
            .cancellation_requested => {
                self.runs.items[prepared.run_index].cancellation_requested = true;
                self.allocator.free(prepared.run_id.?);
                prepared.run_id = null;
            },
            .failure => {
                self.runs.items[prepared.run_index].failure_observed = true;
                self.allocator.free(prepared.run_id.?);
                prepared.run_id = null;
            },
            .compaction_epoch_committed => {
                self.epochs.appendAssumeCapacity(.{
                    .id = prepared.epoch_id.?,
                    .predecessor_id = prepared.predecessor_epoch_id,
                    .source_first_record = prepared.source_first_record,
                    .source_last_record = prepared.source_last_record,
                    .first_retained_id = prepared.first_retained_id.?,
                    .summary = prepared.summary.?,
                    .previous_summary_digest = prepared.previous_summary_digest,
                    .predecessor_after_serialized_bytes = prepared.predecessor_after_serialized_bytes,
                    .predecessor_after_provider_input_tokens = prepared.predecessor_after_provider_input_tokens,
                    .before_serialized_bytes = prepared.before_serialized_bytes,
                    .before_provider_input_tokens = prepared.before_provider_input_tokens,
                    .after_serialized_bytes = prepared.after_serialized_bytes,
                    .after_provider_input_tokens = prepared.after_provider_input_tokens,
                    .measurement_provider = prepared.measurement_provider.?,
                    .measurement_model = prepared.measurement_model.?,
                    .measurement_schema = prepared.measurement_schema.?,
                    .measurement_schema_version = prepared.measurement_schema_version,
                    .measurement_token_basis = prepared.measurement_token_basis.?,
                    .retained_causal_ids = prepared.retained_causal_ids.?,
                    .stable_block_ids = prepared.stable_block_ids.?,
                });
                prepared.epoch_id = null;
                prepared.predecessor_epoch_id = null;
                prepared.first_retained_id = null;
                prepared.summary = null;
                prepared.previous_summary_digest = null;
                prepared.measurement_provider = null;
                prepared.measurement_model = null;
                prepared.measurement_schema = null;
                prepared.measurement_token_basis = null;
                prepared.retained_causal_ids = null;
                prepared.stable_block_ids = null;
            },
        }
        if (prepared.operation != .ignored) {
            if (prepared.operation == .session_created) self.created_at_ms = record.timestamp;
            self.updated_at_ms = record.timestamp;
            self.event_count += 1;
        }
        self.applied_global_sequence = record.global_sequence;
        self.applied_global_hash = record.record_hash;
    }

    fn applyInPlace(self: *DurableProjection, record: event_store.RecordView) !void {
        switch (record.kind) {
            .task_created, .task_relation_added, .task_message_appended, .task_lease_acquired, .task_heartbeat, .task_execution_started, .task_state_changed, .task_result_committed, .task_result_collected, .action_requested, .action_decided, .action_settled, .plugin_state_changed, .task_message_acknowledged, .task_output_visible, .action_dispatch_begun, .runtime_epoch_started, .user_constraint_added, .user_constraint_superseded, .agent_adaptation_revised, .agent_adaptation_rolled_back => return,
            else => {},
        }
        const parsed = std.json.parseFromSlice(DurableWire, self.allocator, record.payload, .{}) catch |err| switch (err) {
            error.OutOfMemory => return error.OutOfMemory,
            else => return error.InvalidSessionEvent,
        };
        defer parsed.deinit();
        const wire = parsed.value;
        if (wire.session_id.len == 0 or wire.session_id.len > event_store.max_event_id_bytes) return error.InvalidSessionEvent;
        const routed_session_id = self.routing_session_id orelse self.session_id;
        if (routed_session_id) |id| {
            if (!std.mem.eql(u8, id, wire.session_id)) return;
        } else if (record.kind != .session_created) {
            return error.SessionNotCreated;
        }
        switch (record.kind) {
            .session_created => {
                if (self.branches.items.len != 0 or self.event_count != 0) return error.DuplicateSession;
                const branch_id = wire.branch_id orelse return error.InvalidSessionEvent;
                if (!validDurableId(branch_id)) return error.InvalidSessionEvent;
                const owned_session_id = try self.allocator.dupe(u8, wire.session_id);
                errdefer self.allocator.free(owned_session_id);
                const owned_branch_id = try self.allocator.dupe(u8, branch_id);
                errdefer self.allocator.free(owned_branch_id);
                const owned_selected_branch_id = try self.allocator.dupe(u8, branch_id);
                errdefer self.allocator.free(owned_selected_branch_id);
                const owned_routing_session_id = if (self.routing_session_id == null) try self.allocator.dupe(u8, wire.session_id) else null;
                errdefer if (owned_routing_session_id) |id| self.allocator.free(id);
                var initial_selection = try OwnedInitialSelection.init(self.allocator, wire);
                errdefer if (initial_selection) |*value| value.deinit(self.allocator);
                try self.branches.ensureUnusedCapacity(self.allocator, 1);
                self.branches.appendAssumeCapacity(.{ .id = owned_branch_id, .parent_branch_id = null });
                if (owned_routing_session_id) |id| self.routing_session_id = id;
                self.session_id = owned_session_id;
                self.selected_branch_id = owned_selected_branch_id;
                if (initial_selection) |initial| {
                    self.selected_model = initial.selected_model;
                    self.selected_selection = initial.selection;
                    initial_selection = null;
                }
            },
            .session_selection_changed => {
                if (self.branches.items.len == 0) return error.SessionNotCreated;
                try self.setSelection(wire);
            },
            .branch_created => {
                const branch_id = wire.branch_id orelse return error.InvalidSessionEvent;
                if (!validDurableId(branch_id)) return error.InvalidSessionEvent;
                if (self.findBranch(branch_id) != null) return error.DuplicateBranch;
                const parent = wire.parent_branch_id;
                const parent_branch = if (parent) |id| self.findBranch(id) orelse return error.UnknownParentBranch else null;
                const owned_id = try self.allocator.dupe(u8, branch_id);
                errdefer self.allocator.free(owned_id);
                const owned_parent = if (parent) |id| try self.allocator.dupe(u8, id) else null;
                errdefer if (owned_parent) |id| self.allocator.free(id);
                const inherited_head = if (parent_branch) |value| if (value.head_message_id) |id| try self.allocator.dupe(u8, id) else null else null;
                errdefer if (inherited_head) |id| self.allocator.free(id);
                try self.branches.ensureUnusedCapacity(self.allocator, 1);
                self.branches.appendAssumeCapacity(.{ .id = owned_id, .parent_branch_id = owned_parent, .head_message_id = inherited_head });
            },
            .branch_selected => {
                const branch_id = wire.branch_id orelse return error.InvalidSessionEvent;
                if (self.findBranch(branch_id) == null) return error.UnknownBranch;
                try self.setBranch(branch_id);
            },
            .message_appended => {
                const branch_id = wire.branch_id orelse return error.InvalidSessionEvent;
                const message_id = wire.message_id orelse return error.InvalidSessionEvent;
                const role_text = wire.role orelse return error.InvalidSessionEvent;
                const role = std.meta.stringToEnum(Role, role_text) orelse return error.InvalidSessionEvent;
                if (!validDurableId(message_id)) return error.InvalidSessionEvent;
                if (self.findMessage(message_id) != null) return error.DuplicateMessage;
                const branch_value = self.findBranch(branch_id) orelse return error.UnknownBranch;
                if (wire.parent_id) |parent| {
                    if (self.findMessage(parent) == null) return error.UnknownParentMessage;
                    if (branch_value.head_message_id == null or !std.mem.eql(u8, branch_value.head_message_id.?, parent)) return error.InvalidParentage;
                } else if (branch_value.head_message_id != null) {
                    return error.MissingParentMessage;
                }
                const content = wire.content orelse return error.InvalidSessionEvent;
                const owned_selected_branch_id = if (self.selected_branch_id == null) try self.allocator.dupe(u8, branch_id) else null;
                errdefer if (owned_selected_branch_id) |id| self.allocator.free(id);
                const owned_message_id = try self.allocator.dupe(u8, message_id);
                errdefer self.allocator.free(owned_message_id);
                const owned_branch_id = try self.allocator.dupe(u8, branch_value.id);
                errdefer self.allocator.free(owned_branch_id);
                const owned_parent_id = if (wire.parent_id) |id| try self.allocator.dupe(u8, id) else null;
                errdefer if (owned_parent_id) |id| self.allocator.free(id);
                const owned_content = try self.allocator.dupe(u8, content);
                errdefer self.allocator.free(owned_content);
                const owned_head_id = try self.allocator.dupe(u8, message_id);
                errdefer self.allocator.free(owned_head_id);
                try self.messages.ensureUnusedCapacity(self.allocator, 1);
                self.messages.appendAssumeCapacity(.{ .id = owned_message_id, .branch_id = owned_branch_id, .parent_id = owned_parent_id, .role = role, .content = owned_content });
                if (branch_value.head_message_id) |id| self.allocator.free(id);
                branch_value.head_message_id = owned_head_id;
                if (owned_selected_branch_id) |id| self.selected_branch_id = id;
            },
            .run_started => {
                const run_id = wire.run_id orelse return error.InvalidSessionEvent;
                if (!validDurableId(run_id)) return error.InvalidSessionEvent;
                if (self.findRun(run_id) != null) return error.DuplicateRun;
                if (wire.assistant_message_id) |message_id| {
                    if (!validDurableId(message_id) or self.findMessage(message_id) != null or self.findReservedMessageId(message_id)) return error.DuplicateMessage;
                }
                const owned_run_id = try self.allocator.dupe(u8, run_id);
                errdefer self.allocator.free(owned_run_id);
                const owned_assistant_message_id = if (wire.assistant_message_id) |message_id| try self.allocator.dupe(u8, message_id) else null;
                errdefer if (owned_assistant_message_id) |message_id| self.allocator.free(message_id);
                try self.runs.ensureUnusedCapacity(self.allocator, 1);
                self.runs.appendAssumeCapacity(.{ .id = owned_run_id, .assistant_message_id = owned_assistant_message_id });
            },
            .run_settled => {
                const run = self.findRun(wire.run_id orelse return error.InvalidSessionEvent) orelse return error.UnknownRun;
                if (record.settlement == .pending) return error.InvalidSettlement;
                if (run.settled) return error.DuplicateSettlement;
                run.settled = true;
                run.failed = record.settlement == .failed;
                run.cancelled = record.settlement == .cancelled;
                run.interrupted = record.settlement == .interrupted;
            },
            .tool_started => {
                const run_id = wire.run_id orelse return error.InvalidSessionEvent;
                const tool_id = wire.tool_id orelse return error.InvalidSessionEvent;
                if (!validDurableId(run_id) or !validDurableId(tool_id)) return error.InvalidSessionEvent;
                if (self.findRun(run_id) == null) return error.UnknownRun;
                if (self.findTool(run_id, tool_id) != null) return error.DuplicateTool;
                const owned_run_id = try self.allocator.dupe(u8, run_id);
                errdefer self.allocator.free(owned_run_id);
                const owned_tool_id = try self.allocator.dupe(u8, tool_id);
                errdefer self.allocator.free(owned_tool_id);
                try self.tools.ensureUnusedCapacity(self.allocator, 1);
                self.tools.appendAssumeCapacity(.{ .run_id = owned_run_id, .tool_id = owned_tool_id });
            },
            .tool_finished => {
                const tool = self.findTool(wire.run_id orelse return error.InvalidSessionEvent, wire.tool_id orelse return error.InvalidSessionEvent) orelse return error.UnknownTool;
                if (tool.finished) return error.DuplicateToolSettlement;
                tool.finished = true;
            },
            .cancellation_requested => {
                const run = self.findRun(wire.run_id orelse return error.InvalidSessionEvent) orelse return error.UnknownRun;
                run.cancellation_requested = true;
            },
            .failure => {
                const run = self.findRun(wire.run_id orelse return error.InvalidSessionEvent) orelse return error.UnknownRun;
                run.failure_observed = true;
            },
            .compaction_epoch_committed => {
                const epoch_id = wire.epoch_id orelse return error.InvalidSessionEvent;
                if (!validDurableId(epoch_id)) return error.InvalidSessionEvent;
                if (self.findEpoch(epoch_id) != null) return error.DuplicateEpoch;
                if (wire.predecessor_epoch_id) |predecessor| {
                    try self.validateEpochPredecessor(wire, predecessor);
                } else if (self.epochs.items.len != 0) return error.StaleEpoch;
                const source_first_record = wire.source_first_record orelse return error.InvalidSessionEvent;
                const source_last_record = wire.source_last_record orelse return error.InvalidSessionEvent;
                if (source_last_record < source_first_record) return error.InvalidSessionEvent;
                const first_retained_id = wire.first_retained_id orelse return error.InvalidSessionEvent;
                const summary = wire.summary orelse return error.InvalidSessionEvent;
                const previous_summary_digest = wire.previous_summary_digest;
                if (!validDurableId(first_retained_id) or summary.len == 0 or summary.len > event_store.max_payload_bytes) return error.InvalidSessionEvent;
                if (previous_summary_digest) |digest| if (!validDigest(digest)) return error.InvalidSessionEvent;
                const before_serialized_bytes = wire.before_serialized_bytes orelse return error.InvalidSessionEvent;
                const before_provider_input_tokens = wire.before_provider_input_tokens orelse return error.InvalidSessionEvent;
                const after_serialized_bytes = wire.after_serialized_bytes orelse return error.InvalidSessionEvent;
                const after_provider_input_tokens = wire.after_provider_input_tokens orelse return error.InvalidSessionEvent;
                const measurement_provider = wire.measurement_provider orelse return error.InvalidSessionEvent;
                const measurement_model = wire.measurement_model orelse return error.InvalidSessionEvent;
                const measurement_schema = wire.measurement_schema orelse return error.InvalidSessionEvent;
                const measurement_schema_version = wire.measurement_schema_version orelse return error.InvalidSessionEvent;
                const measurement_token_basis = wire.measurement_token_basis orelse return error.InvalidSessionEvent;
                if (after_serialized_bytes >= before_serialized_bytes or after_provider_input_tokens >= before_provider_input_tokens) return error.NonReducingCompaction;
                try validateMeasurement(measurement_provider, measurement_model, measurement_schema, measurement_schema_version, measurement_token_basis);
                if (previous_summary_digest == null) return error.InvalidSessionEvent;
                if (wire.predecessor_epoch_id == null) {
                    if (self.epochs.items.len != 0 or wire.predecessor_after_serialized_bytes != null or wire.predecessor_after_provider_input_tokens != null) return error.StaleEpoch;
                    var empty_digest: [32]u8 = undefined;
                    summaryDigest(&empty_digest, "");
                    var empty_text: [64]u8 = undefined;
                    formatDigest(&empty_text, empty_digest);
                    if (!std.mem.eql(u8, previous_summary_digest.?, &empty_text)) return error.StaleEpoch;
                }
                const retained_causal_ids = try cloneDurableIds(self.allocator, wire.retained_causal_ids orelse return error.InvalidSessionEvent);
                errdefer freeDurableIds(self.allocator, retained_causal_ids);
                const stable_block_ids = try cloneDurableIds(self.allocator, wire.stable_block_ids orelse return error.InvalidSessionEvent);
                errdefer freeDurableIds(self.allocator, stable_block_ids);
                const owned_id = try self.allocator.dupe(u8, epoch_id);
                errdefer self.allocator.free(owned_id);
                const owned_predecessor = if (wire.predecessor_epoch_id) |id| try self.allocator.dupe(u8, id) else null;
                errdefer if (owned_predecessor) |id| self.allocator.free(id);
                const owned_first_retained_id = try self.allocator.dupe(u8, first_retained_id);
                errdefer self.allocator.free(owned_first_retained_id);
                const owned_summary = try self.allocator.dupe(u8, summary);
                errdefer self.allocator.free(owned_summary);
                const owned_previous_summary_digest = if (previous_summary_digest) |digest| try self.allocator.dupe(u8, digest) else null;
                errdefer if (owned_previous_summary_digest) |digest| self.allocator.free(digest);
                const owned_measurement_provider = try self.allocator.dupe(u8, measurement_provider);
                errdefer self.allocator.free(owned_measurement_provider);
                const owned_measurement_model = try self.allocator.dupe(u8, measurement_model);
                errdefer self.allocator.free(owned_measurement_model);
                const owned_measurement_schema = try self.allocator.dupe(u8, measurement_schema);
                errdefer self.allocator.free(owned_measurement_schema);
                const owned_measurement_token_basis = try self.allocator.dupe(u8, measurement_token_basis);
                errdefer self.allocator.free(owned_measurement_token_basis);
                try self.epochs.ensureUnusedCapacity(self.allocator, 1);
                self.epochs.appendAssumeCapacity(.{
                    .id = owned_id,
                    .predecessor_id = owned_predecessor,
                    .source_first_record = source_first_record,
                    .source_last_record = source_last_record,
                    .first_retained_id = owned_first_retained_id,
                    .summary = owned_summary,
                    .previous_summary_digest = owned_previous_summary_digest,
                    .predecessor_after_serialized_bytes = if (wire.predecessor_epoch_id != null) wire.predecessor_after_serialized_bytes else null,
                    .predecessor_after_provider_input_tokens = if (wire.predecessor_epoch_id != null) wire.predecessor_after_provider_input_tokens else null,
                    .before_serialized_bytes = before_serialized_bytes,
                    .before_provider_input_tokens = before_provider_input_tokens,
                    .after_serialized_bytes = after_serialized_bytes,
                    .after_provider_input_tokens = after_provider_input_tokens,
                    .measurement_provider = owned_measurement_provider,
                    .measurement_model = owned_measurement_model,
                    .measurement_schema = owned_measurement_schema,
                    .measurement_schema_version = measurement_schema_version,
                    .measurement_token_basis = owned_measurement_token_basis,
                    .retained_causal_ids = retained_causal_ids,
                    .stable_block_ids = stable_block_ids,
                });
            },
            else => {},
        }
        if (record.kind == .session_created) self.created_at_ms = record.timestamp;
        self.updated_at_ms = record.timestamp;
        self.event_count += 1;
    }

    pub fn replay(self: *DurableProjection, store: *event_store.Store) !event_store.RecoveryReport {
        try self.bindProjectionStore(store, store.store_id);
        const after_sequence = self.cursorSequence();
        if (after_sequence == 0) return store.replayWithContext(@ptrCast(self), applyVisitor);
        return store.visitCommittedFrom(after_sequence, null, @ptrCast(self), applyVisitor);
    }

    /// Apply one Store-authorized record to the exact Store-owned projection
    /// cursor. A gap is inspected before mutation: verified unrelated global
    /// records are consumed in order, while any omitted record for this
    /// session rejects the supplied record. Copies and regressions are rejected
    /// by the Store cursor before applyExact can mutate session state.
    pub fn applyCommittedRecord(self: *DurableProjection, record: event_store.RecordView) !void {
        try record.verifyWire();
        const store = record.source_store orelse return error.UnauthorizedRecord;
        try self.bindProjectionStore(store, record.store_id);

        const after_sequence = self.cursorSequence();
        if (record.global_sequence > after_sequence +| 1) {
            var candidate_target: ?[]u8 = null;
            defer if (candidate_target) |id| self.allocator.free(id);
            if (self.routingSessionId() == null and record.kind == .session_created) {
                candidate_target = try sessionIdFromRecord(self.allocator, record);
            }
            const target = self.routingSessionId() orelse candidate_target;
            var check = RelevantGapCheck{ .allocator = self.allocator, .target_session_id = target };
            _ = try store.visitCommittedFrom(after_sequence, record.global_sequence - 1, @ptrCast(&check), findRelevantGapVisitor);
            if (check.found_relevant) return error.ProjectionRecordSkipped;
            if (candidate_target) |id| {
                try self.bindRoutingSession(id);
            }
            _ = try store.visitCommittedFrom(after_sequence, record.global_sequence - 1, @ptrCast(self), applyVisitor);
        }
        try self.applyCommittedRecordExact(record);
    }

    fn applyCommittedRecordExact(self: *DurableProjection, record: event_store.RecordView) !void {
        const store = record.source_store orelse return error.UnauthorizedRecord;
        try store.applyCommittedToProjection(@ptrCast(self), &self.projection_cursor, @ptrCast(&projection_cursor_authority), record, @ptrCast(self), applyProjectionDelta);
    }

    fn bindProjectionStore(self: *DurableProjection, store: *event_store.Store, store_id: [event_store.hash_bytes]u8) !void {
        self.lockProjection();
        defer self.unlockProjection();
        if (self.projection_store) |bound| {
            if (bound != store or self.projection_store_id == null or !std.mem.eql(u8, &self.projection_store_id.?, &store_id)) return error.ForeignProjectionStore;
            return;
        }
        self.projection_store = store;
        self.projection_store_id = store_id;
    }

    fn bindRoutingSession(self: *DurableProjection, session_id: []const u8) !void {
        self.lockProjection();
        defer self.unlockProjection();
        if (self.routing_session_id) |bound| {
            if (!std.mem.eql(u8, bound, session_id)) return error.SessionMismatch;
            return;
        }
        self.routing_session_id = try self.allocator.dupe(u8, session_id);
    }

    fn routingSessionId(self: *DurableProjection) ?[]const u8 {
        self.lockProjection();
        defer self.unlockProjection();
        return self.routing_session_id orelse self.session_id;
    }

    fn cursorSequence(self: *DurableProjection) u64 {
        self.lockProjection();
        defer self.unlockProjection();
        return self.applied_global_sequence;
    }

    /// Convert starts left without a terminal event by a process crash into
    /// explicit durable interrupted settlements.  Callers invoke this after a
    /// successful replay, before accepting new work for the session.
    pub fn settleInterruptedRuns(self: *DurableProjection, store: *event_store.Store, provenance: []const u8) !usize {
        var pending = try self.collectUnsettledRunIds();
        defer {
            for (pending.items) |id| self.allocator.free(id);
            pending.deinit(self.allocator);
        }
        for (pending.items) |run_id| {
            const payload = try std.json.Stringify.valueAlloc(self.allocator, .{ .session_id = self.session_id orelse return error.SessionNotCreated, .run_id = run_id }, .{});
            defer self.allocator.free(payload);
            var event_id_buffer: [event_store.max_event_id_bytes]u8 = undefined;
            const event_id = interruptedSettlementEventId(&event_id_buffer, run_id);
            var ref = try self.appendAndApply(store, .{ .kind = .run_settled, .event_id = event_id, .payload = payload }, provenance, .interrupted);
            ref.deinit(self.allocator);
        }
        return pending.items.len;
    }

    fn collectUnsettledRunIds(self: *DurableProjection) !std.ArrayList([]u8) {
        self.lockProjection();
        defer self.unlockProjection();
        var pending: std.ArrayList([]u8) = .empty;
        errdefer {
            for (pending.items) |id| self.allocator.free(id);
            pending.deinit(self.allocator);
        }
        for (self.runs.items) |run| {
            if (!run.settled) {
                const id = try self.allocator.dupe(u8, run.id);
                pending.append(self.allocator, id) catch |err| {
                    self.allocator.free(id);
                    return err;
                };
            }
        }
        return pending;
    }

    pub fn appendAndApply(self: *DurableProjection, store: *event_store.Store, event: event_store.Event, provenance: []const u8, settlement: event_store.Settlement) !event_store.RecordRef {
        var prepared = try self.prepare(event, settlement);
        defer prepared.deinit(self.allocator);
        try self.bindProjectionStore(store, store.store_id);
        if (self.routingSessionId() == null) {
            if (prepared.operation != .session_created) return error.SessionNotCreated;
            try self.bindRoutingSession(prepared.session_id.?);
        }
        const after_sequence = self.cursorSequence();
        _ = try store.visitCommittedFrom(after_sequence, null, @ptrCast(self), applyVisitor);
        var context = ProjectionAppendContext{ .projection = self, .prepared = &prepared };
        return store.appendPreparedAndProjectAtCursor(event, provenance, settlement, @ptrCast(self), &self.projection_cursor, @ptrCast(&projection_cursor_authority), @ptrCast(&context), noOpPrepare, validateAppendVisitor, commitAppendVisitor);
    }

    pub fn branchMessages(self: *const DurableProjection, allocator: std.mem.Allocator, branch_id: []const u8) ![]DurableMessage {
        const mutable_self = @constCast(self);
        mutable_self.lockProjection();
        defer mutable_self.unlockProjection();
        var result: std.ArrayList(DurableMessage) = .empty;
        errdefer {
            for (result.items) |*message| message.deinit(allocator);
            result.deinit(allocator);
        }
        const branch = self.findBranchConst(branch_id) orelse return error.UnknownBranch;
        var path: std.ArrayList(*const DurableMessage) = .empty;
        defer path.deinit(allocator);
        var current_id = branch.head_message_id;
        while (current_id) |message_id| {
            const message = self.findMessageConst(message_id) orelse return error.InvalidSessionEvent;
            try path.append(allocator, message);
            current_id = message.parent_id;
        }
        std.mem.reverse(*const DurableMessage, path.items);
        for (path.items) |message_value| {
            const id = try allocator.dupe(u8, message_value.id);
            errdefer allocator.free(id);
            const message_branch_id = try allocator.dupe(u8, message_value.branch_id);
            errdefer allocator.free(message_branch_id);
            const parent_id = if (message_value.parent_id) |source| try allocator.dupe(u8, source) else null;
            errdefer if (parent_id) |owned| allocator.free(owned);
            const content = try allocator.dupe(u8, message_value.content);
            errdefer allocator.free(content);
            try result.ensureUnusedCapacity(allocator, 1);
            result.appendAssumeCapacity(.{ .id = id, .branch_id = message_branch_id, .parent_id = parent_id, .role = message_value.role, .content = content });
        }
        return result.toOwnedSlice(allocator);
    }

    pub fn containsMessageId(self: *const DurableProjection, message_id: []const u8) bool {
        const mutable_self = @constCast(self);
        mutable_self.lockProjection();
        defer mutable_self.unlockProjection();
        return self.findMessageConst(message_id) != null or self.findReservedMessageId(message_id);
    }

    /// Return a bounded chronological page from one exact branch chain. The
    /// cursor is the last durable message observed by the caller; unrelated
    /// branch messages are never included.
    pub fn branchMessagesPage(
        self: *const DurableProjection,
        allocator: std.mem.Allocator,
        branch_id: []const u8,
        after_message_id: ?[]const u8,
        limit: usize,
    ) !DurableMessagePage {
        return self.branchMessagesPageBounded(allocator, branch_id, after_message_id, limit, std.math.maxInt(usize));
    }

    pub fn branchMessagesPageBounded(
        self: *const DurableProjection,
        allocator: std.mem.Allocator,
        branch_id: []const u8,
        after_message_id: ?[]const u8,
        limit: usize,
        maximum_bytes: usize,
    ) !DurableMessagePage {
        return self.branchMessagesPageOrderedBounded(allocator, branch_id, after_message_id, limit, maximum_bytes, .ascending);
    }

    /// Return one bounded page in chronological or reverse-chronological
    /// order. The cursor is exclusive in the requested order.
    pub fn branchMessagesPageOrderedBounded(
        self: *const DurableProjection,
        allocator: std.mem.Allocator,
        branch_id: []const u8,
        cursor_message_id: ?[]const u8,
        limit: usize,
        maximum_payload_bytes: usize,
        order: PageOrder,
    ) !DurableMessagePage {
        if (limit == 0) return error.InvalidHistoryLimit;
        if (maximum_payload_bytes == 0) return error.InvalidHistoryByteLimit;
        const mutable_self = @constCast(self);
        mutable_self.lockProjection();
        defer mutable_self.unlockProjection();
        const branch = self.findBranchConst(branch_id) orelse return error.UnknownBranch;

        var total: usize = 0;
        var cursor_distance: ?usize = null;
        var current_id = branch.head_message_id;
        while (current_id) |message_id| : (total += 1) {
            const message = self.findMessageConst(message_id) orelse return error.InvalidSessionEvent;
            if (cursor_message_id) |cursor| {
                if (std.mem.eql(u8, cursor, message.id)) cursor_distance = total;
            }
            current_id = message.parent_id;
        }
        if (cursor_message_id != null and cursor_distance == null) return error.UnknownMessageCursor;

        const first_distance, const available = switch (order) {
            .ascending => .{ @as(usize, 0), cursor_distance orelse total },
            .descending => desc: {
                const first = if (cursor_distance) |distance| distance + 1 else 0;
                break :desc .{ first, total - first };
            },
        };
        const take = @min(limit, available);
        var path: std.ArrayList(*const DurableMessage) = .empty;
        defer path.deinit(allocator);
        try path.ensureTotalCapacityPrecise(allocator, take);
        var result: std.ArrayList(DurableMessage) = .empty;
        errdefer {
            for (result.items) |*message| message.deinit(allocator);
            result.deinit(allocator);
        }
        try result.ensureTotalCapacityPrecise(allocator, take);
        if (take != 0) {
            const lowest_distance, const highest_distance = switch (order) {
                .ascending => .{ available - take, available - 1 },
                .descending => .{ first_distance, first_distance + take - 1 },
            };
            var distance: usize = 0;
            current_id = branch.head_message_id;
            while (current_id) |message_id| : (distance += 1) {
                const message = self.findMessageConst(message_id) orelse return error.InvalidSessionEvent;
                if (distance >= lowest_distance and distance <= highest_distance) {
                    path.appendAssumeCapacity(message);
                }
                current_id = message.parent_id;
            }
            if (order == .ascending) std.mem.reverse(*const DurableMessage, path.items);
            var used_bytes: usize = 0;
            for (path.items) |message| {
                const parent_bytes = if (message.parent_id) |parent| parent.len else 0;
                const message_bytes = message.id.len +| message.branch_id.len +| parent_bytes +| message.content.len;
                if (message_bytes > maximum_payload_bytes - used_bytes) {
                    if (result.items.len == 0) return error.HistoryMessageTooLarge;
                    break;
                }
                const id = try allocator.dupe(u8, message.id);
                errdefer allocator.free(id);
                const message_branch_id = try allocator.dupe(u8, message.branch_id);
                errdefer allocator.free(message_branch_id);
                const parent_id = if (message.parent_id) |source| try allocator.dupe(u8, source) else null;
                errdefer if (parent_id) |owned| allocator.free(owned);
                const content = try allocator.dupe(u8, message.content);
                errdefer allocator.free(content);
                result.appendAssumeCapacity(.{
                    .id = id,
                    .branch_id = message_branch_id,
                    .parent_id = parent_id,
                    .role = message.role,
                    .content = content,
                });
                used_bytes += message_bytes;
            }
        }
        const returned_count = result.items.len;
        return .{
            .allocator = allocator,
            .messages = try result.toOwnedSlice(allocator),
            .has_more = available > returned_count,
        };
    }

    fn setSelection(self: *DurableProjection, wire: DurableWire) !void {
        const model = wire.model orelse return error.InvalidSessionEvent;
        if (!validDurableId(model)) return error.InvalidSessionEvent;
        const provider = wire.provider orelse "";
        if (provider.len > event_store.max_event_id_bytes) return error.InvalidSessionEvent;
        const owned_model = try self.allocator.dupe(u8, model);
        errdefer self.allocator.free(owned_model);
        const owned_selection_model = try self.allocator.dupe(u8, model);
        errdefer self.allocator.free(owned_selection_model);
        const owned_provider = try self.allocator.dupe(u8, provider);
        errdefer self.allocator.free(owned_provider);
        const selection: protocol.ModelSelection = .{
            .provider = owned_provider,
            .model = owned_selection_model,
            .context_window = wire.context_window orelse 0,
            .output_limit = wire.output_limit orelse 0,
            .reasoning = wire.reasoning,
            .cache_retention = wire.cache_retention orelse .none,
            .cache_aggressiveness = wire.cache_aggressiveness orelse .balanced,
            .transport = wire.transport orelse .auto,
        };
        if (self.selected_model) |id| self.allocator.free(id);
        if (self.selected_selection) |old| old.deinit(self.allocator);
        self.selected_model = owned_model;
        self.selected_selection = selection;
    }

    fn setBranch(self: *DurableProjection, branch_id: []const u8) !void {
        const owned = try self.allocator.dupe(u8, branch_id);
        if (self.selected_branch_id) |id| self.allocator.free(id);
        self.selected_branch_id = owned;
    }

    fn findBranch(self: *DurableProjection, branch_id: []const u8) ?*DurableBranch {
        for (self.branches.items) |*branch_value| if (std.mem.eql(u8, branch_value.id, branch_id)) return branch_value;
        return null;
    }

    fn findBranchConst(self: *const DurableProjection, branch_id: []const u8) ?*const DurableBranch {
        for (self.branches.items) |*branch_value| if (std.mem.eql(u8, branch_value.id, branch_id)) return branch_value;
        return null;
    }

    fn findMessage(self: *DurableProjection, message_id: []const u8) ?*DurableMessage {
        for (self.messages.items) |*message_value| if (std.mem.eql(u8, message_value.id, message_id)) return message_value;
        return null;
    }

    fn findMessageConst(self: *const DurableProjection, message_id: []const u8) ?*const DurableMessage {
        for (self.messages.items) |*message_value| if (std.mem.eql(u8, message_value.id, message_id)) return message_value;
        return null;
    }

    fn findRun(self: *DurableProjection, run_id: []const u8) ?*DurableRun {
        for (self.runs.items) |*run_value| if (std.mem.eql(u8, run_value.id, run_id)) return run_value;
        return null;
    }

    fn findReservedMessageId(self: *const DurableProjection, message_id: []const u8) bool {
        for (self.runs.items) |run_value| {
            if (run_value.assistant_message_id) |reserved| {
                if (std.mem.eql(u8, reserved, message_id)) return true;
            }
        }
        return false;
    }

    fn findTool(self: *DurableProjection, run_id: []const u8, tool_id: []const u8) ?*DurableTool {
        for (self.tools.items) |*tool_value| if (std.mem.eql(u8, tool_value.run_id, run_id) and std.mem.eql(u8, tool_value.tool_id, tool_id)) return tool_value;
        return null;
    }

    fn findEpoch(self: *DurableProjection, epoch_id: []const u8) ?*EpochRef {
        for (self.epochs.items) |*epoch_value| if (std.mem.eql(u8, epoch_value.id, epoch_id)) return epoch_value;
        return null;
    }

    fn validateEpochPredecessor(self: *const DurableProjection, wire: DurableWire, predecessor: []const u8) !void {
        if (self.epochs.items.len == 0) return error.StaleEpoch;
        const prior = self.epochs.items[self.epochs.items.len - 1];
        if (!std.mem.eql(u8, predecessor, prior.id)) return error.StaleEpoch;
        const digest_text = wire.previous_summary_digest orelse return error.InvalidSessionEvent;
        var expected_digest: [32]u8 = undefined;
        summaryDigest(&expected_digest, prior.summary);
        var expected_text: [64]u8 = undefined;
        formatDigest(&expected_text, expected_digest);
        if (!std.mem.eql(u8, digest_text, expected_text[0..])) return error.StaleEpoch;
        if (wire.predecessor_after_serialized_bytes == null or wire.predecessor_after_provider_input_tokens == null or
            wire.predecessor_after_serialized_bytes.? != prior.after_serialized_bytes or
            wire.predecessor_after_provider_input_tokens.? != prior.after_provider_input_tokens or
            !std.mem.eql(u8, wire.measurement_provider orelse "", prior.measurement_provider) or
            !std.mem.eql(u8, wire.measurement_model orelse "", prior.measurement_model) or
            !std.mem.eql(u8, wire.measurement_schema orelse "", prior.measurement_schema) or
            (wire.measurement_schema_version orelse 0) != prior.measurement_schema_version or
            !std.mem.eql(u8, wire.measurement_token_basis orelse "", prior.measurement_token_basis)) return error.StaleEpoch;
    }
};

fn interruptedSettlementEventId(buffer: *[event_store.max_event_id_bytes]u8, run_id: []const u8) []const u8 {
    const prefix = "interrupted-settlement-v1-";
    @memcpy(buffer[0..prefix.len], prefix);
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update("misul-interrupted-settlement-v1\x00");
    var length: [8]u8 = undefined;
    std.mem.writeInt(u64, &length, @intCast(run_id.len), .little);
    hasher.update(&length);
    hasher.update(run_id);
    var digest: [32]u8 = undefined;
    hasher.final(&digest);
    const hex = "0123456789abcdef";
    for (digest, 0..) |byte, index| {
        buffer[prefix.len + index * 2] = hex[byte >> 4];
        buffer[prefix.len + index * 2 + 1] = hex[byte & 0x0f];
    }
    return buffer[0 .. prefix.len + digest.len * 2];
}

pub const SessionProjection = DurableProjection;

const DurableWire = struct {
    session_id: []const u8,
    branch_id: ?[]const u8 = null,
    parent_branch_id: ?[]const u8 = null,
    parent_id: ?[]const u8 = null,
    message_id: ?[]const u8 = null,
    role: ?[]const u8 = null,
    content: ?[]const u8 = null,
    model: ?[]const u8 = null,
    provider: ?[]const u8 = null,
    context_window: ?u64 = null,
    output_limit: ?u64 = null,
    reasoning: ?protocol.ThinkingLevel = null,
    cache_retention: ?protocol.CacheRetention = null,
    cache_aggressiveness: ?protocol.CacheAggressiveness = null,
    transport: ?protocol.Transport = null,
    run_id: ?[]const u8 = null,
    assistant_message_id: ?[]const u8 = null,
    tool_id: ?[]const u8 = null,
    epoch_id: ?[]const u8 = null,
    predecessor_epoch_id: ?[]const u8 = null,
    source_first_record: ?u64 = null,
    source_last_record: ?u64 = null,
    first_retained_id: ?[]const u8 = null,
    summary: ?[]const u8 = null,
    previous_summary_digest: ?[]const u8 = null,
    predecessor_after_serialized_bytes: ?usize = null,
    predecessor_after_provider_input_tokens: ?usize = null,
    before_serialized_bytes: ?usize = null,
    before_provider_input_tokens: ?usize = null,
    after_serialized_bytes: ?usize = null,
    after_provider_input_tokens: ?usize = null,
    measurement_provider: ?[]const u8 = null,
    measurement_model: ?[]const u8 = null,
    measurement_schema: ?[]const u8 = null,
    measurement_schema_version: ?u16 = null,
    measurement_token_basis: ?[]const u8 = null,
    retained_causal_ids: ?[][]const u8 = null,
    stable_block_ids: ?[][]const u8 = null,
};

fn isSessionEventKind(kind: event_store.EventKind) bool {
    return switch (kind) {
        .session_created,
        .session_selection_changed,
        .branch_created,
        .branch_selected,
        .message_appended,
        .run_started,
        .run_settled,
        .tool_started,
        .tool_finished,
        .cancellation_requested,
        .failure,
        .compaction_epoch_committed,
        => true,
        else => false,
    };
}

fn sessionIdFromRecord(allocator: std.mem.Allocator, record: event_store.RecordView) ![]u8 {
    const SessionIdWire = struct { session_id: []const u8 };
    const parsed = std.json.parseFromSlice(SessionIdWire, allocator, record.payload, .{ .ignore_unknown_fields = true }) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        else => return error.InvalidSessionEvent,
    };
    defer parsed.deinit();
    if (!validDurableId(parsed.value.session_id)) return error.InvalidSessionEvent;
    return try allocator.dupe(u8, parsed.value.session_id);
}

/// Return an owned session identifier for a session event in a shared log.
/// Non-session event families are ignored without parsing their payload. The
/// returned identifier is bounded by the event-store identifier limit and is
/// owned by the caller.
pub fn extractSessionId(allocator: std.mem.Allocator, record: event_store.RecordView) !?[]u8 {
    if (!isSessionEventKind(record.kind)) return null;
    try record.verifyWire();
    return try sessionIdFromRecord(allocator, record);
}

fn refView(ref: event_store.RecordRef) event_store.RecordView {
    return ref.view() catch unreachable;
}

fn applyVisitor(context: *anyopaque, record: event_store.RecordView) !void {
    const projection: *DurableProjection = @ptrCast(@alignCast(context));
    try projection.applyCommittedRecord(record);
}

fn applyProjectionDelta(context: *anyopaque, record: event_store.RecordView) !void {
    const projection: *DurableProjection = @ptrCast(@alignCast(context));
    try projection.applyExact(record);
}

var projection_cursor_authority: u8 = 0;

const RelevantGapCheck = struct {
    allocator: std.mem.Allocator,
    target_session_id: ?[]const u8,
    found_relevant: bool = false,
};

fn findRelevantGapVisitor(context: *anyopaque, record: event_store.RecordView) !void {
    const check: *RelevantGapCheck = @ptrCast(@alignCast(context));
    if (!isSessionEventKind(record.kind)) return;
    const session_id = try sessionIdFromRecord(check.allocator, record);
    defer check.allocator.free(session_id);
    if (check.target_session_id) |target| {
        if (std.mem.eql(u8, target, session_id)) check.found_relevant = true;
    } else {
        check.found_relevant = true;
    }
}

const ProjectionAppendContext = struct {
    projection: *DurableProjection,
    prepared: *Prepared,
};

fn noOpPrepare(_: *anyopaque, _: event_store.Event, _: event_store.Settlement) !void {}

fn validateAppendVisitor(context: *anyopaque) bool {
    const append_context: *ProjectionAppendContext = @ptrCast(@alignCast(context));
    return append_context.projection.validate(append_context.prepared);
}

fn commitAppendVisitor(context: *anyopaque, record: event_store.RecordView) void {
    const append_context: *ProjectionAppendContext = @ptrCast(@alignCast(context));
    append_context.projection.commitChecked(append_context.prepared, record) catch unreachable;
}

fn validDurableId(id: []const u8) bool {
    return id.len != 0 and id.len <= event_store.max_event_id_bytes;
}

fn validDigest(digest: []const u8) bool {
    if (digest.len != 64) return false;
    for (digest) |byte| {
        if (!((byte >= '0' and byte <= '9') or (byte >= 'a' and byte <= 'f') or (byte >= 'A' and byte <= 'F'))) return false;
    }
    return true;
}

fn validateMeasurement(provider: []const u8, model: []const u8, schema: []const u8, version: u16, token_basis: []const u8) !void {
    if (!validDurableId(provider) or !validDurableId(model) or !validDurableId(schema) or version == 0 or !validDurableId(token_basis)) return error.InvalidSessionEvent;
}

fn summaryDigest(output: *[32]u8, summary: []const u8) void {
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update("misul-compaction-summary-schema-1\x00");
    hashLengthAndBytes(&hasher, summary);
    hasher.final(output);
}

fn formatDigest(output: *[64]u8, digest: [32]u8) void {
    const hex = "0123456789abcdef";
    for (digest, 0..) |byte, index| {
        output[index * 2] = hex[byte >> 4];
        output[index * 2 + 1] = hex[byte & 0x0f];
    }
}

fn digestHex(allocator: std.mem.Allocator, summary: []const u8) ![]u8 {
    var digest: [32]u8 = undefined;
    summaryDigest(&digest, summary);
    var formatted: [64]u8 = undefined;
    formatDigest(&formatted, digest);
    return try allocator.dupe(u8, &formatted);
}

fn hashLengthAndBytes(hasher: *std.crypto.hash.sha2.Sha256, bytes: []const u8) void {
    var length: [8]u8 = undefined;
    std.mem.writeInt(u64, &length, @intCast(bytes.len), .little);
    hasher.update(&length);
    hasher.update(bytes);
}

fn cloneDurableIds(allocator: std.mem.Allocator, ids: []const []const u8) ![][]u8 {
    const result = try allocator.alloc([]u8, ids.len);
    var initialized: usize = 0;
    errdefer {
        for (result[0..initialized]) |id| allocator.free(id);
        allocator.free(result);
    }
    for (ids, 0..) |id, index| {
        if (!validDurableId(id)) return error.InvalidSessionEvent;
        result[index] = try allocator.dupe(u8, id);
        initialized += 1;
    }
    return result;
}

fn freeDurableIds(allocator: std.mem.Allocator, ids: [][]u8) void {
    for (ids) |id| allocator.free(id);
    allocator.free(ids);
}

/// Deterministic UUID generator. Production callers should choose a random seed;
/// tests and replay code can reuse a seed to reproduce identifiers exactly.
pub const IdGenerator = struct {
    seed: u64,
    next: u64 = 0,

    pub fn generate(self: *IdGenerator, allocator: std.mem.Allocator) ![]u8 {
        var bytes: [16]u8 = undefined;
        const sequence = self.next;
        const timestamp = self.seed +% sequence;
        const random = splitMix64(self.seed ^ sequence);
        self.next +%= 1;
        bytes[0] = @truncate(timestamp >> 40);
        bytes[1] = @truncate(timestamp >> 32);
        bytes[2] = @truncate(timestamp >> 24);
        bytes[3] = @truncate(timestamp >> 16);
        bytes[4] = @truncate(timestamp >> 8);
        bytes[5] = @truncate(timestamp);
        bytes[6] = 0x70 | @as(u8, @truncate(random >> 60));
        bytes[7] = @truncate(random >> 52);
        std.mem.writeInt(u64, bytes[8..16], splitMix64(random), .big);
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        var text: [36]u8 = undefined;
        const hex = "0123456789abcdef";
        var source: usize = 0;
        var dest: usize = 0;
        while (source < bytes.len) : (source += 1) {
            if (dest == 8 or dest == 13 or dest == 18 or dest == 23) {
                text[dest] = '-';
                dest += 1;
            }
            text[dest] = hex[bytes[source] >> 4];
            text[dest + 1] = hex[bytes[source] & 0x0f];
            dest += 2;
        }
        return allocator.dupe(u8, &text);
    }
};

fn splitMix64(value: u64) u64 {
    var z = value +% 0x9e3779b97f4a7c15;
    z = (z ^ (z >> 30)) *% 0xbf58476d1ce4e5b9;
    z = (z ^ (z >> 27)) *% 0x94d049bb133111eb;
    return z ^ (z >> 31);
}

// Legacy in-memory history is retained only for same-module compatibility
// tests; production callers use DurableProjection plus Store.
const Session = struct {
    allocator: std.mem.Allocator,
    metadata: Metadata,
    entries: std.ArrayList(Entry) = .empty,
    leaf_id: ?[]const u8 = null,
    ids: IdGenerator,

    pub fn init(
        allocator: std.mem.Allocator,
        seed: u64,
        session_id: []const u8,
        cwd: []const u8,
        created_at: u64,
    ) !Session {
        return .{
            .allocator = allocator,
            .metadata = .{
                .id = try allocator.dupe(u8, session_id),
                .created_at = created_at,
                .cwd = try allocator.dupe(u8, cwd),
            },
            .ids = .{ .seed = seed },
        };
    }

    pub fn deinit(self: *Session) void {
        for (self.entries.items) |*entry| entry.deinit(self.allocator);
        self.entries.deinit(self.allocator);
        self.allocator.free(self.metadata.id);
        self.allocator.free(self.metadata.cwd);
        self.* = undefined;
    }

    pub fn appendMessage(self: *Session, role: Role, content: []const u8) ![]const u8 {
        const id = try self.ids.generate(self.allocator);
        errdefer self.allocator.free(id);
        var message = try Message.init(self.allocator, role, content);
        errdefer message.deinit(self.allocator);
        try self.appendOwned(.{
            .id = id,
            .parent_id = try self.copyLeaf(),
            .timestamp = self.ids.next,
            .payload = .{ .message = message },
        });
        return self.entries.items[self.entries.items.len - 1].id;
    }

    pub fn moveTo(self: *Session, target_id: ?[]const u8, summary: ?[]const u8) !?[]const u8 {
        if (target_id) |id| if (self.findEntry(id) == null) return error.EntryNotFound;
        const marker_id = try self.ids.generate(self.allocator);
        errdefer self.allocator.free(marker_id);
        try self.appendOwned(.{
            .id = marker_id,
            .parent_id = try self.copyLeaf(),
            .timestamp = self.ids.next,
            .payload = .{ .leaf = if (target_id) |id| try self.allocator.dupe(u8, id) else null },
        });
        if (summary) |text| {
            const id = try self.ids.generate(self.allocator);
            errdefer self.allocator.free(id);
            try self.appendOwned(.{
                .id = id,
                .parent_id = if (target_id) |target| try self.allocator.dupe(u8, target) else null,
                .timestamp = self.ids.next,
                .payload = .{ .branch_summary = .{
                    .summary = try self.allocator.dupe(u8, text),
                    .from_id = try self.allocator.dupe(u8, target_id orelse "root"),
                } },
            });
            return self.entries.items[self.entries.items.len - 1].id;
        }
        return null;
    }

    pub fn appendCompaction(
        self: *Session,
        summary: []const u8,
        first_kept_id: []const u8,
        tokens_before: usize,
    ) ![]const u8 {
        if (self.findEntry(first_kept_id) == null) return error.EntryNotFound;
        const id = try self.ids.generate(self.allocator);
        errdefer self.allocator.free(id);
        try self.appendOwned(.{
            .id = id,
            .parent_id = try self.copyLeaf(),
            .timestamp = self.ids.next,
            .payload = .{ .compaction = .{
                .summary = try self.allocator.dupe(u8, summary),
                .first_kept_id = try self.allocator.dupe(u8, first_kept_id),
                .tokens_before = tokens_before,
            } },
        });
        return self.entries.items[self.entries.items.len - 1].id;
    }

    pub fn findEntry(self: *const Session, id: []const u8) ?*const Entry {
        for (self.entries.items) |*entry| {
            if (std.mem.eql(u8, entry.id, id)) return entry;
        }
        return null;
    }

    pub fn branch(self: *const Session, allocator: std.mem.Allocator, from_id: ?[]const u8) ![]*const Entry {
        const leaf = from_id orelse self.leaf_id orelse return allocator.alloc(*const Entry, 0);
        var result: std.ArrayList(*const Entry) = .empty;
        errdefer result.deinit(allocator);
        var current = self.findEntry(leaf) orelse return error.EntryNotFound;
        while (true) {
            try result.append(allocator, current);
            const parent_id = current.parent_id orelse break;
            current = self.findEntry(parent_id) orelse return error.InvalidSession;
        }
        std.mem.reverse(*const Entry, result.items);
        return result.toOwnedSlice(allocator);
    }

    fn copyLeaf(self: *Session) !?[]u8 {
        return if (self.leaf_id) |id| try self.allocator.dupe(u8, id) else null;
    }

    fn appendOwned(self: *Session, entry: Entry) !void {
        if (entry.parent_id) |parent| if (self.findEntry(parent) == null) return error.InvalidSession;
        if (self.findEntry(entry.id) != null) return error.DuplicateId;
        switch (entry.payload) {
            .leaf => |target| if (target) |id| if (self.findEntry(id) == null) return error.InvalidSession,
            else => {},
        }
        try self.entries.append(self.allocator, entry);
        const stored = &self.entries.items[self.entries.items.len - 1];
        switch (stored.payload) {
            .leaf => |target| self.leaf_id = target,
            else => self.leaf_id = stored.id,
        }
    }
};

fn expectMessage(entry: *const Entry, role: Role, content: []const u8) !void {
    switch (entry.payload) {
        .message => |message| {
            try std.testing.expectEqual(role, message.role);
            try std.testing.expectEqualStrings(content, message.content);
        },
        else => return error.TestUnexpectedResult,
    }
}

test "deterministic UUIDs and owned in-memory branching" {
    const allocator = std.testing.allocator;
    var first = try Session.init(allocator, 42, "session", "/tmp", 1);
    defer first.deinit();
    var second = try Session.init(allocator, 42, "session", "/tmp", 1);
    defer second.deinit();
    const root = try first.appendMessage(.user, "one");
    const same_root = try second.appendMessage(.user, "one");
    try std.testing.expectEqualStrings(root, same_root);
    try std.testing.expectEqual(@as(u8, '7'), root[14]);
    try std.testing.expect(std.mem.indexOfScalar(u8, "89ab", root[19]) != null);
    _ = try first.appendMessage(.assistant, "discarded branch");
    _ = try first.moveTo(root, "earlier work remains relevant");
    _ = try first.appendMessage(.assistant, "new branch");
    const path = try first.branch(allocator, null);
    defer allocator.free(path);
    try std.testing.expectEqual(@as(usize, 3), path.len);
    try expectMessage(path[0], .user, "one");
    try expectMessage(path[2], .assistant, "new branch");
}

test "shared-log session identity extraction is bounded and typed" {
    const allocator = std.testing.allocator;
    const non_session: event_store.RecordView = .{
        .store_id = @splat(0),
        .record_id = @splat(0),
        .schema = event_store.current_schema,
        .kind = .task_created,
        .global_sequence = 1,
        .timestamp = 0,
        .provenance = "runtime",
        .settlement = .succeeded,
        .event_id = "task",
        .payload = "not-json",
        .previous_hash = @splat(0),
        .payload_hash = @splat(0),
        .record_hash = @splat(0),
    };
    try std.testing.expect((try extractSessionId(allocator, non_session)) == null);
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "session-id.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var session_ref = try opened.store.append(.{ .kind = .message_appended, .event_id = "message", .payload = "{\"session_id\":\"session-a\",\"branch_id\":\"main\"}" }, "runtime", .succeeded);
    defer session_ref.deinit(allocator);
    const session_record = try session_ref.view();
    const extracted = (try extractSessionId(allocator, session_record)).?;
    defer allocator.free(extracted);
    try std.testing.expectEqualStrings("session-a", extracted);
    var malformed_ref = try opened.store.append(.{ .kind = .message_appended, .event_id = "malformed", .payload = "{\"branch_id\":\"main\"}" }, "runtime", .succeeded);
    defer malformed_ref.deinit(allocator);
    const malformed = try malformed_ref.view();
    try std.testing.expectError(error.InvalidSessionEvent, extractSessionId(allocator, malformed));
}

test "durable projection appends, rebuilds branches, and resumes after restart" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "durable-events.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    defer opened.close();

    var created = try appendProjectionEvent(&projection, &opened.store, .session_created, "{\"session_id\":\"s\",\"branch_id\":\"main\"}");
    defer created.deinit(allocator);
    var selected = try appendProjectionEvent(&projection, &opened.store, .session_selection_changed, "{\"session_id\":\"s\",\"provider\":\"openai\",\"model\":\"model-a\",\"context_window\":128000,\"output_limit\":4096,\"reasoning\":\"high\",\"cache_retention\":\"long\",\"cache_aggressiveness\":\"aggressive\",\"transport\":\"sse\"}");
    defer selected.deinit(allocator);
    var first = try appendProjectionEvent(&projection, &opened.store, .message_appended, "{\"session_id\":\"s\",\"branch_id\":\"main\",\"message_id\":\"m1\",\"role\":\"user\",\"content\":\"hello\"}");
    defer first.deinit(allocator);
    var second = try appendProjectionEvent(&projection, &opened.store, .message_appended, "{\"session_id\":\"s\",\"branch_id\":\"main\",\"message_id\":\"m2\",\"parent_id\":\"m1\",\"role\":\"assistant\",\"content\":\"world\"}");
    defer second.deinit(allocator);
    var branch = try appendProjectionEvent(&projection, &opened.store, .branch_created, "{\"session_id\":\"s\",\"branch_id\":\"experiment\",\"parent_branch_id\":\"main\"}");
    defer branch.deinit(allocator);
    var branch_selected = try appendProjectionEvent(&projection, &opened.store, .branch_selected, "{\"session_id\":\"s\",\"branch_id\":\"experiment\"}");
    defer branch_selected.deinit(allocator);
    var experiment_message = try appendProjectionEvent(&projection, &opened.store, .message_appended, "{\"session_id\":\"s\",\"branch_id\":\"experiment\",\"message_id\":\"m3\",\"parent_id\":\"m2\",\"role\":\"assistant\",\"content\":\"experiment\"}");
    defer experiment_message.deinit(allocator);
    var run = try appendProjectionEvent(&projection, &opened.store, .run_started, "{\"session_id\":\"s\",\"run_id\":\"run-1\"}");
    defer run.deinit(allocator);
    var tool = try appendProjectionEvent(&projection, &opened.store, .tool_started, "{\"session_id\":\"s\",\"run_id\":\"run-1\",\"tool_id\":\"tool-1\"}");
    defer tool.deinit(allocator);
    var tool_finished = try appendProjectionEvent(&projection, &opened.store, .tool_finished, "{\"session_id\":\"s\",\"run_id\":\"run-1\",\"tool_id\":\"tool-1\"}");
    defer tool_finished.deinit(allocator);
    var cancel = try appendProjectionEvent(&projection, &opened.store, .cancellation_requested, "{\"session_id\":\"s\",\"run_id\":\"run-1\"}");
    defer cancel.deinit(allocator);
    var settled = try appendProjectionEventWithSettlement(&projection, &opened.store, .run_settled, "{\"session_id\":\"s\",\"run_id\":\"run-1\"}", .cancelled);
    defer settled.deinit(allocator);
    var failed_run = try appendProjectionEvent(&projection, &opened.store, .run_started, "{\"session_id\":\"s\",\"run_id\":\"run-2\"}");
    defer failed_run.deinit(allocator);
    var failure = try appendProjectionEvent(&projection, &opened.store, .failure, "{\"session_id\":\"s\",\"run_id\":\"run-2\"}");
    defer failure.deinit(allocator);
    var failed_settlement = try appendProjectionEventWithSettlement(&projection, &opened.store, .run_settled, "{\"session_id\":\"s\",\"run_id\":\"run-2\"}", .failed);
    defer failed_settlement.deinit(allocator);
    var raced_run = try appendProjectionEvent(&projection, &opened.store, .run_started, "{\"session_id\":\"s\",\"run_id\":\"run-3\"}");
    defer raced_run.deinit(allocator);
    var raced_cancel = try appendProjectionEvent(&projection, &opened.store, .cancellation_requested, "{\"session_id\":\"s\",\"run_id\":\"run-3\"}");
    defer raced_cancel.deinit(allocator);
    var raced_success = try appendProjectionEventWithSettlement(&projection, &opened.store, .run_settled, "{\"session_id\":\"s\",\"run_id\":\"run-3\"}", .succeeded);
    defer raced_success.deinit(allocator);
    const previous_digest = try digestHex(allocator, "old context");
    defer allocator.free(previous_digest);
    const empty_digest = try digestHex(allocator, "");
    defer allocator.free(empty_digest);
    const epoch_one_payload = try std.fmt.allocPrint(allocator, "{{\"session_id\":\"s\",\"epoch_id\":\"epoch-1\",\"source_first_record\":1,\"source_last_record\":12,\"first_retained_id\":\"m2\",\"summary\":\"old context\",\"previous_summary_digest\":\"{s}\",\"before_serialized_bytes\":1000,\"before_provider_input_tokens\":250,\"after_serialized_bytes\":500,\"after_provider_input_tokens\":120,\"measurement_provider\":\"openai\",\"measurement_model\":\"model-a\",\"measurement_schema\":\"canonical-v1\",\"measurement_schema_version\":1,\"measurement_token_basis\":\"provider\",\"retained_causal_ids\":[\"m2\"],\"stable_block_ids\":[\"block-2\"]}}", .{empty_digest});
    defer allocator.free(epoch_one_payload);
    var epoch_one = try appendProjectionEvent(&projection, &opened.store, .compaction_epoch_committed, epoch_one_payload);
    defer epoch_one.deinit(allocator);
    const epoch_two_payload = try std.fmt.allocPrint(allocator, "{{\"session_id\":\"s\",\"epoch_id\":\"epoch-2\",\"predecessor_epoch_id\":\"epoch-1\",\"source_first_record\":13,\"source_last_record\":17,\"first_retained_id\":\"m3\",\"summary\":\"new context\",\"previous_summary_digest\":\"{s}\",\"predecessor_after_serialized_bytes\":500,\"predecessor_after_provider_input_tokens\":120,\"before_serialized_bytes\":500,\"before_provider_input_tokens\":120,\"after_serialized_bytes\":300,\"after_provider_input_tokens\":80,\"measurement_provider\":\"openai\",\"measurement_model\":\"model-a\",\"measurement_schema\":\"canonical-v1\",\"measurement_schema_version\":1,\"measurement_token_basis\":\"provider\",\"retained_causal_ids\":[\"m3\"],\"stable_block_ids\":[\"block-3\"]}}", .{previous_digest});
    defer allocator.free(epoch_two_payload);
    var epoch_two = try appendProjectionEvent(&projection, &opened.store, .compaction_epoch_committed, epoch_two_payload);
    defer epoch_two.deinit(allocator);
    const before_rejected_epoch = try opened.store.file.length(std.testing.io);
    const epoch_three_digest = try digestHex(allocator, "new context");
    defer allocator.free(epoch_three_digest);
    const invalid_epoch_payload = try std.fmt.allocPrint(allocator, "{{\"session_id\":\"s\",\"epoch_id\":\"epoch-3\",\"predecessor_epoch_id\":\"epoch-2\",\"source_first_record\":18,\"source_last_record\":20,\"first_retained_id\":\"m4\",\"summary\":\"third context\",\"previous_summary_digest\":\"{s}\",\"predecessor_after_serialized_bytes\":301,\"predecessor_after_provider_input_tokens\":80,\"before_serialized_bytes\":300,\"before_provider_input_tokens\":80,\"after_serialized_bytes\":200,\"after_provider_input_tokens\":60,\"measurement_provider\":\"openai\",\"measurement_model\":\"model-a\",\"measurement_schema\":\"canonical-v1\",\"measurement_schema_version\":1,\"measurement_token_basis\":\"provider\",\"retained_causal_ids\":[\"m4\"],\"stable_block_ids\":[\"block-4\"]}}", .{epoch_three_digest});
    defer allocator.free(invalid_epoch_payload);
    try std.testing.expectError(error.StaleEpoch, appendProjectionEvent(&projection, &opened.store, .compaction_epoch_committed, invalid_epoch_payload));
    try std.testing.expectEqual(before_rejected_epoch, try opened.store.file.length(std.testing.io));
    try std.testing.expectEqualStrings("model-a", projection.selected_model.?);
    try std.testing.expectEqualStrings("openai", projection.selected_selection.?.provider);
    try std.testing.expectEqual(@as(u64, 128000), projection.selected_selection.?.context_window);
    try std.testing.expectEqual(@as(u64, 4096), projection.selected_selection.?.output_limit);
    try std.testing.expectEqual(protocol.ThinkingLevel.high, projection.selected_selection.?.reasoning.?);
    try std.testing.expectEqualStrings("experiment", projection.selected_branch_id.?);
    try std.testing.expectEqual(@as(usize, 3), projection.messages.items.len);
    try std.testing.expectEqualStrings("m2", projection.branches.items[0].head_message_id.?);
    try std.testing.expectEqualStrings("m3", projection.branches.items[1].head_message_id.?);
    try std.testing.expect(projection.runs.items[0].settled and projection.runs.items[0].cancelled);
    try std.testing.expect(projection.runs.items[1].settled and projection.runs.items[1].failed);
    try std.testing.expect(projection.runs.items[0].cancellation_requested);
    try std.testing.expect(projection.runs.items[1].failure_observed);
    try std.testing.expect(projection.runs.items[2].settled and !projection.runs.items[2].cancelled and projection.runs.items[2].cancellation_requested);
    try std.testing.expect(projection.tools.items[0].finished);
    try std.testing.expectEqual(@as(usize, 2), projection.epochs.items.len);
    const experiment_path = try projection.branchMessages(allocator, "experiment");
    defer {
        for (experiment_path) |*message| message.deinit(allocator);
        allocator.free(experiment_path);
    }
    try std.testing.expectEqual(@as(usize, 3), experiment_path.len);
    try std.testing.expectEqualStrings("m1", experiment_path[0].id);
    try std.testing.expectEqualStrings("m2", experiment_path[1].id);
    try std.testing.expectEqualStrings("m3", experiment_path[2].id);
    var first_page = try projection.branchMessagesPage(allocator, "experiment", null, 2);
    defer first_page.deinit();
    try std.testing.expect(first_page.has_more);
    try std.testing.expectEqual(@as(usize, 2), first_page.messages.len);
    try std.testing.expectEqualStrings("m1", first_page.messages[0].id);
    try std.testing.expectEqualStrings("m2", first_page.messages[1].id);
    var second_page = try projection.branchMessagesPage(allocator, "experiment", "m2", 2);
    defer second_page.deinit();
    try std.testing.expect(!second_page.has_more);
    try std.testing.expectEqual(@as(usize, 1), second_page.messages.len);
    try std.testing.expectEqualStrings("m3", second_page.messages[0].id);
    try std.testing.expectError(error.UnknownMessageCursor, projection.branchMessagesPage(allocator, "experiment", "main-only", 2));
    try std.testing.expectError(error.HistoryMessageTooLarge, projection.branchMessagesPageBounded(allocator, "experiment", null, 2, 3));

    opened.store.close();
    var reopened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer reopened.close();
    var rebuilt = DurableProjection.init(allocator);
    defer rebuilt.deinit();
    const report = try rebuilt.replay(&reopened.store);
    try std.testing.expectEqual(@as(u64, 20), report.last_sequence);
    try std.testing.expectEqualStrings("model-a", rebuilt.selected_model.?);
    try std.testing.expectEqualStrings("openai", rebuilt.selected_selection.?.provider);
    try std.testing.expectEqual(protocol.CacheRetention.long, rebuilt.selected_selection.?.cache_retention);
    try std.testing.expectEqualStrings("experiment", rebuilt.selected_branch_id.?);
    try std.testing.expectEqual(@as(usize, 3), rebuilt.messages.items.len);
    try std.testing.expectEqualStrings("m2", rebuilt.branches.items[0].head_message_id.?);
    try std.testing.expectEqualStrings("m3", rebuilt.branches.items[1].head_message_id.?);
    try std.testing.expectEqual(@as(usize, 2), rebuilt.epochs.items.len);
    try std.testing.expectEqualStrings("epoch-1", rebuilt.epochs.items[1].predecessor_id.?);
    try std.testing.expectEqualStrings(empty_digest, rebuilt.epochs.items[0].previous_summary_digest.?);
    try std.testing.expectEqualStrings(previous_digest, rebuilt.epochs.items[1].previous_summary_digest.?);
}

test "projection OOM is rejected before append and leaves the durable prefix" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "projection-oom.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    defer opened.close();
    var created = try appendProjectionEvent(&projection, &opened.store, .session_created, "{\"session_id\":\"s\",\"branch_id\":\"main\"}");
    defer created.deinit(allocator);

    var failing = std.testing.FailingAllocator.init(allocator, .{ .fail_index = 0 });
    projection.allocator = failing.allocator();
    const branch_result = projection.appendAndApply(&opened.store, .{ .kind = .branch_created, .event_id = "projection-2", .payload = "{\"session_id\":\"s\",\"branch_id\":\"experiment\",\"parent_branch_id\":\"main\"}" }, "runtime:test", .succeeded);
    projection.allocator = allocator;
    try std.testing.expectError(error.OutOfMemory, branch_result);
    try std.testing.expect(opened.store.isWritable());
    opened.store.close();

    var reopened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer reopened.close();
    var rebuilt = DurableProjection.init(allocator);
    defer rebuilt.deinit();
    _ = try rebuilt.replay(&reopened.store);
    try std.testing.expectEqual(@as(usize, 1), rebuilt.branches.items.len);
}

test "every failed projection allocation leaves the projection byte-equivalent" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    var fail_index: usize = 0;
    while (fail_index < 10) : (fail_index += 1) {
        const path = try std.fmt.allocPrint(allocator, "{s}/projection-fault-{d}.bin", .{ path_buffer[0..path_len], fail_index });
        defer allocator.free(path);
        var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
        var projection = DurableProjection.init(allocator);
        var created = try appendProjectionEvent(&projection, &opened.store, .session_created, "{\"session_id\":\"s\",\"branch_id\":\"main\"}");
        created.deinit(allocator);
        const prefix_size = try opened.store.file.length(std.testing.io);
        var failing = std.testing.FailingAllocator.init(allocator, .{ .fail_index = fail_index });
        projection.allocator = failing.allocator();
        const result = projection.appendAndApply(&opened.store, .{ .kind = .branch_created, .event_id = "projection-2", .payload = "{\"session_id\":\"s\",\"branch_id\":\"experiment\",\"parent_branch_id\":\"main\"}" }, "runtime:test", .succeeded);
        projection.allocator = allocator;
        if (result) |ref| {
            var owned = ref;
            owned.deinit(allocator);
            try std.testing.expectEqual(@as(usize, 2), projection.branches.items.len);
            try std.testing.expectEqual(@as(u64, 2), projection.event_count);
        } else |err| {
            try std.testing.expectEqual(error.OutOfMemory, err);
            try std.testing.expectEqual(prefix_size, try opened.store.file.length(std.testing.io));
            try std.testing.expectEqual(@as(usize, 1), projection.branches.items.len);
            try std.testing.expectEqual(@as(u64, 1), projection.event_count);
            try std.testing.expectEqualStrings("main", projection.branches.items[0].id);
            try std.testing.expectEqualStrings("s", projection.session_id.?);
            try std.testing.expect(opened.store.isWritable());
        }
        projection.deinit();
        opened.close();
    }
}

test "replayed unsettled runs receive explicit interrupted settlement" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "interrupted.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    var created = try projection.appendAndApply(&opened.store, .{ .kind = .session_created, .event_id = "interrupted-1", .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}" }, "runtime:test", .succeeded);
    defer created.deinit(allocator);
    var run = try projection.appendAndApply(&opened.store, .{ .kind = .run_started, .event_id = "interrupted-2", .payload = "{\"session_id\":\"s\",\"run_id\":\"run-1\"}" }, "runtime:test", .succeeded);
    run.deinit(allocator);
    try std.testing.expectEqual(@as(usize, 1), try projection.settleInterruptedRuns(&opened.store, "runtime:recovery"));
    try std.testing.expect(projection.runs.items[0].settled);
    try std.testing.expect(projection.runs.items[0].interrupted);
    try std.testing.expectEqual(@as(usize, 0), try projection.settleInterruptedRuns(&opened.store, "runtime:recovery"));
}

test "maximum length unsettled run settles by bounded stable id across restart" {
    const allocator = std.testing.allocator;
    const run_id = "r" ** event_store.max_event_id_bytes;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "maximum-run-id.bin" });
    defer allocator.free(path);

    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    var created = try projection.appendAndApply(&opened.store, .{ .kind = .session_created, .event_id = "maximum-1", .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}" }, "runtime:test", .succeeded);
    created.deinit(allocator);
    const run_payload = try std.json.Stringify.valueAlloc(allocator, .{ .session_id = "s", .run_id = run_id }, .{});
    defer allocator.free(run_payload);
    var run = try projection.appendAndApply(&opened.store, .{ .kind = .run_started, .event_id = "maximum-2", .payload = run_payload }, "runtime:test", .succeeded);
    run.deinit(allocator);
    opened.close();

    var reopened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    var rebuilt = DurableProjection.init(allocator);
    defer rebuilt.deinit();
    _ = try rebuilt.replay(&reopened.store);
    try std.testing.expectEqualStrings(run_id, rebuilt.runs.items[0].id);
    try std.testing.expectEqual(@as(usize, 1), try rebuilt.settleInterruptedRuns(&reopened.store, "runtime:recovery"));
    try std.testing.expect(rebuilt.runs.items[0].settled and rebuilt.runs.items[0].interrupted);
    reopened.close();

    var restarted_store = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer restarted_store.close();
    var restarted_projection = DurableProjection.init(allocator);
    defer restarted_projection.deinit();
    _ = try restarted_projection.replay(&restarted_store.store);
    try std.testing.expect(restarted_projection.runs.items[0].settled);
    const size = try restarted_store.store.file.length(std.testing.io);
    try std.testing.expectEqual(@as(usize, 0), try restarted_projection.settleInterruptedRuns(&restarted_store.store, "runtime:recovery"));
    try std.testing.expectEqual(size, try restarted_store.store.file.length(std.testing.io));
}

fn appendProjectionEvent(
    projection: *DurableProjection,
    store: *event_store.Store,
    kind: event_store.EventKind,
    payload: []const u8,
) !event_store.RecordRef {
    var event_id_buffer: [48]u8 = undefined;
    const event_id = std.fmt.bufPrint(&event_id_buffer, "projection-{d}", .{projection.event_count + 1}) catch unreachable;
    return projection.appendAndApply(store, .{ .kind = kind, .event_id = event_id, .payload = payload }, "runtime:test", .succeeded);
}

fn appendProjectionEventWithSettlement(
    projection: *DurableProjection,
    store: *event_store.Store,
    kind: event_store.EventKind,
    payload: []const u8,
    settlement: event_store.Settlement,
) !event_store.RecordRef {
    var event_id_buffer: [48]u8 = undefined;
    const event_id = std.fmt.bufPrint(&event_id_buffer, "projection-{d}", .{projection.event_count + 1}) catch unreachable;
    return projection.appendAndApply(store, .{ .kind = kind, .event_id = event_id, .payload = payload }, "runtime:test", settlement);
}

const ConcurrentSessionContext = struct {
    store: *event_store.Store,
    projection: *DurableProjection,
    ready: std.atomic.Value(u32) = .init(0),
    start: std.atomic.Value(u32) = .init(0),
    successes: std.atomic.Value(u32) = .init(0),
    failures: std.atomic.Value(u32) = .init(0),
};

fn concurrentSessionWorker(context: *ConcurrentSessionContext, event_id: []const u8, message_id: []const u8) void {
    _ = context.ready.fetchAdd(1, .release);
    while (context.start.load(.acquire) == 0) std.atomic.spinLoopHint();
    const payload = if (std.mem.eql(u8, message_id, "m2"))
        "{\"session_id\":\"s\",\"branch_id\":\"main\",\"message_id\":\"m2\",\"parent_id\":\"m1\",\"role\":\"assistant\",\"content\":\"first\"}"
    else
        "{\"session_id\":\"s\",\"branch_id\":\"main\",\"message_id\":\"m3\",\"parent_id\":\"m1\",\"role\":\"assistant\",\"content\":\"second\"}";
    var ref = context.projection.appendAndApply(context.store, .{ .kind = .message_appended, .event_id = event_id, .payload = payload }, "runtime:concurrent", .succeeded) catch {
        _ = context.failures.fetchAdd(1, .release);
        return;
    };
    ref.deinit(std.heap.smp_allocator);
    _ = context.successes.fetchAdd(1, .release);
}

test "concurrent projection append keeps each prepared delta isolated" {
    const allocator = std.heap.smp_allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fmt.allocPrint(std.testing.allocator, "{s}/projection-concurrent.bin", .{path_buffer[0..path_len]});
    defer std.testing.allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    var created = try appendProjectionEvent(&projection, &opened.store, .session_created, "{\"session_id\":\"s\",\"branch_id\":\"main\"}");
    created.deinit(allocator);
    var first = try appendProjectionEvent(&projection, &opened.store, .message_appended, "{\"session_id\":\"s\",\"branch_id\":\"main\",\"message_id\":\"m1\",\"role\":\"user\",\"content\":\"base\"}");
    first.deinit(allocator);
    var context = ConcurrentSessionContext{ .store = &opened.store, .projection = &projection };
    var first_thread = try std.Thread.spawn(.{}, concurrentSessionWorker, .{ &context, "concurrent-m2", "m2" });
    var second_thread = try std.Thread.spawn(.{}, concurrentSessionWorker, .{ &context, "concurrent-m3", "m3" });
    while (context.ready.load(.acquire) != 2) std.atomic.spinLoopHint();
    context.start.store(1, .release);
    first_thread.join();
    second_thread.join();
    try std.testing.expectEqual(@as(u32, 1), context.successes.load(.acquire));
    try std.testing.expectEqual(@as(u32, 1), context.failures.load(.acquire));
    try std.testing.expectEqual(@as(usize, 2), projection.messages.items.len);
    try std.testing.expectEqual(@as(u64, 3), projection.event_count);
}

test "prepared projections reject foreign and stale commits" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "prepared-owner.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var first_projection = DurableProjection.init(allocator);
    defer first_projection.deinit();
    var foreign_projection = DurableProjection.init(allocator);
    defer foreign_projection.deinit();
    const event = event_store.Event{ .kind = .session_created, .event_id = "prepared-owner", .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}" };
    var first = try first_projection.prepare(event, .succeeded);
    defer first.deinit(allocator);
    var stale = try first_projection.prepare(event, .succeeded);
    defer stale.deinit(allocator);
    try std.testing.expectError(error.PreparedProjectionMismatch, foreign_projection.commitChecked(&first, undefined));
    var ref = try opened.store.append(event, "runtime:test", .succeeded);
    defer ref.deinit(allocator);
    try first_projection.commitChecked(&first, refView(ref));
    try std.testing.expectError(error.PreparedProjectionStale, first_projection.commitChecked(&stale, refView(ref)));
    try std.testing.expectEqual(@as(usize, 1), first_projection.branches.items.len);
    try std.testing.expectEqual(@as(u64, 1), first_projection.event_count);
}

test "direct projection application rejects an unauthenticated record" {
    const allocator = std.testing.allocator;
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    const record = event_store.RecordView{
        .store_id = @splat(0),
        .record_id = @splat(0),
        .schema = event_store.current_schema,
        .kind = .session_created,
        .global_sequence = 1,
        .timestamp = 1,
        .provenance = "runtime",
        .settlement = .succeeded,
        .event_id = "forged",
        .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}",
        .previous_hash = @splat(0),
        .payload_hash = @splat(0),
        .record_hash = @splat(0),
    };
    try std.testing.expectError(error.UnauthorizedRecord, projection.applyCommittedRecord(record));
    try std.testing.expectEqual(@as(u64, 0), projection.event_count);
}

test "self-consistent but uncommitted records cannot mutate a projection" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "uncommitted-record.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var stored = try opened.store.append(.{ .kind = .session_created, .event_id = "committed", .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}" }, "runtime", .succeeded);
    defer stored.deinit(allocator);
    const forged: event_store.RecordView = .{
        .store_id = stored.store_id,
        .record_id = stored.record_id,
        .schema = stored.schema,
        .kind = stored.kind,
        .global_sequence = stored.global_sequence,
        .timestamp = stored.timestamp,
        .provenance = stored.provenance,
        .settlement = stored.settlement,
        .event_id = stored.event_id,
        .payload = stored.payload,
        .previous_hash = stored.previous_hash,
        .payload_hash = stored.payload_hash,
        .record_hash = stored.record_hash,
    };
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    try std.testing.expectError(error.UnauthorizedRecord, projection.applyCommittedRecord(forged));
    try std.testing.expectEqual(@as(u64, 0), projection.event_count);
}

test "shared-log per-record ingestion accepts only a Store-authorized view" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "per-record.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var stored = try opened.store.append(.{ .kind = .session_created, .event_id = "per-record", .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}" }, "runtime", .succeeded);
    defer stored.deinit(allocator);
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    try projection.applyCommittedRecord(try stored.view());
    try std.testing.expectEqual(@as(u64, 1), projection.event_count);
    try std.testing.expectEqualStrings("s", projection.session_id.?);
}

test "copied committed record cannot mutate a projection twice" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "duplicate-projection-record.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var created = try opened.store.append(.{ .kind = .session_created, .event_id = "duplicate-created", .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}" }, "runtime", .succeeded);
    defer created.deinit(allocator);
    var selected = try opened.store.append(.{ .kind = .session_selection_changed, .event_id = "duplicate-selected", .payload = "{\"session_id\":\"s\",\"provider\":\"openai\",\"model\":\"model-a\",\"context_window\":128000,\"output_limit\":4096,\"reasoning\":\"high\",\"cache_retention\":\"long\",\"cache_aggressiveness\":\"aggressive\",\"transport\":\"sse\"}" }, "runtime", .succeeded);
    defer selected.deinit(allocator);
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    try projection.applyCommittedRecord(try created.view());
    const copied = try selected.view();
    try projection.applyCommittedRecord(copied);
    try std.testing.expectError(error.DuplicateProjectionRecord, projection.applyCommittedRecord(copied));
    try std.testing.expectEqual(@as(u64, 2), projection.event_count);
}

test "projection rejects a skipped relevant record before mutation" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "skipped-relevant.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var created = try opened.store.append(.{ .kind = .session_created, .event_id = "skip-created", .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}" }, "runtime", .succeeded);
    defer created.deinit(allocator);
    var selected = try opened.store.append(.{ .kind = .session_selection_changed, .event_id = "skip-selected", .payload = "{\"session_id\":\"s\",\"provider\":\"openai\",\"model\":\"model-a\",\"context_window\":128000,\"output_limit\":4096,\"reasoning\":\"high\",\"cache_retention\":\"long\",\"cache_aggressiveness\":\"aggressive\",\"transport\":\"sse\"}" }, "runtime", .succeeded);
    defer selected.deinit(allocator);
    var branch = try opened.store.append(.{ .kind = .branch_created, .event_id = "skip-branch", .payload = "{\"session_id\":\"s\",\"branch_id\":\"experiment\",\"parent_branch_id\":\"main\"}" }, "runtime", .succeeded);
    defer branch.deinit(allocator);
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    try projection.applyCommittedRecord(try created.view());
    try std.testing.expectError(error.ProjectionRecordSkipped, projection.applyCommittedRecord(try branch.view()));
    try std.testing.expectEqual(@as(u64, 1), projection.event_count);
    try std.testing.expectEqual(@as(usize, 1), projection.branches.items.len);
    try projection.applyCommittedRecord(try selected.view());
    try projection.applyCommittedRecord(try branch.view());
    try std.testing.expectEqual(@as(u64, 3), projection.event_count);
}

test "projection advances its cursor across verified irrelevant global records" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "irrelevant-gap.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var created = try opened.store.append(.{ .kind = .session_created, .event_id = "irrelevant-created", .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}" }, "runtime", .succeeded);
    defer created.deinit(allocator);
    var unrelated = try opened.store.append(.{ .kind = .task_heartbeat, .event_id = "irrelevant-task", .payload = "{}" }, "runtime", .succeeded);
    defer unrelated.deinit(allocator);
    var selected = try opened.store.append(.{ .kind = .session_selection_changed, .event_id = "irrelevant-selected", .payload = "{\"session_id\":\"s\",\"provider\":\"openai\",\"model\":\"model-a\",\"context_window\":128000,\"output_limit\":4096,\"reasoning\":\"high\",\"cache_retention\":\"long\",\"cache_aggressiveness\":\"aggressive\",\"transport\":\"sse\"}" }, "runtime", .succeeded);
    defer selected.deinit(allocator);
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    try projection.applyCommittedRecord(try created.view());
    try projection.applyCommittedRecord(try selected.view());
    try std.testing.expectEqual(@as(u64, 2), projection.event_count);
    try std.testing.expectEqual(@as(u64, 3), projection.applied_global_sequence);
}

test "projection rejects out of order and foreign committed records" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path_a = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "ordered-a.bin" });
    defer allocator.free(path_a);
    const path_b = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "ordered-b.bin" });
    defer allocator.free(path_b);
    var opened_a = try event_store.Store.open(allocator, std.testing.io, path_a, .{});
    defer opened_a.close();
    var opened_b = try event_store.Store.open(allocator, std.testing.io, path_b, .{});
    defer opened_b.close();
    var created = try opened_a.store.append(.{ .kind = .session_created, .event_id = "ordered-created", .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}" }, "runtime", .succeeded);
    defer created.deinit(allocator);
    var selected = try opened_a.store.append(.{ .kind = .session_selection_changed, .event_id = "ordered-selected", .payload = "{\"session_id\":\"s\",\"provider\":\"openai\",\"model\":\"model-a\",\"context_window\":128000,\"output_limit\":4096,\"reasoning\":\"high\",\"cache_retention\":\"long\",\"cache_aggressiveness\":\"aggressive\",\"transport\":\"sse\"}" }, "runtime", .succeeded);
    defer selected.deinit(allocator);
    var foreign = try opened_b.store.append(.{ .kind = .session_created, .event_id = "foreign-created", .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}" }, "runtime", .succeeded);
    defer foreign.deinit(allocator);
    var out_of_order = DurableProjection.init(allocator);
    defer out_of_order.deinit();
    try std.testing.expectError(error.ProjectionRecordSkipped, out_of_order.applyCommittedRecord(try selected.view()));
    try std.testing.expectEqual(@as(u64, 0), out_of_order.event_count);
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    try projection.applyCommittedRecord(try created.view());
    try std.testing.expectError(error.ForeignProjectionStore, projection.applyCommittedRecord(try foreign.view()));
    try std.testing.expectEqual(@as(u64, 1), projection.event_count);
}

test "closed Store record cannot bind or mutate a projection" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "closed-projection-record.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    var created = try opened.store.append(.{ .kind = .session_created, .event_id = "closed-created", .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}" }, "runtime", .succeeded);
    defer created.deinit(allocator);
    const view = try created.view();
    opened.close();
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    try std.testing.expectError(error.UnauthorizedRecord, projection.applyCommittedRecord(view));
    try std.testing.expectEqual(@as(u64, 0), projection.event_count);
}

test "projection cursor resumes across bounded committed ranges" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "range-resume.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var created = try opened.store.append(.{ .kind = .session_created, .event_id = "range-created", .payload = "{\"session_id\":\"s\",\"branch_id\":\"main\"}" }, "runtime", .succeeded);
    defer created.deinit(allocator);
    var unrelated = try opened.store.append(.{ .kind = .runtime_epoch_started, .event_id = "range-runtime", .payload = "{}" }, "runtime", .succeeded);
    defer unrelated.deinit(allocator);
    var selected = try opened.store.append(.{ .kind = .session_selection_changed, .event_id = "range-selected", .payload = "{\"session_id\":\"s\",\"provider\":\"openai\",\"model\":\"model-a\",\"context_window\":128000,\"output_limit\":4096,\"reasoning\":\"high\",\"cache_retention\":\"long\",\"cache_aggressiveness\":\"aggressive\",\"transport\":\"sse\"}" }, "runtime", .succeeded);
    defer selected.deinit(allocator);
    var projection = DurableProjection.init(allocator);
    defer projection.deinit();
    const prefix = try opened.store.visitCommittedFrom(0, 2, @ptrCast(&projection), applyVisitor);
    try std.testing.expectEqual(@as(u64, 2), prefix.last_sequence);
    try std.testing.expectEqual(@as(u64, 2), projection.applied_global_sequence);
    const suffix = try opened.store.visitCommittedFrom(2, 3, @ptrCast(&projection), applyVisitor);
    try std.testing.expectEqual(@as(u64, 3), suffix.last_sequence);
    try std.testing.expectEqual(@as(u64, 3), projection.applied_global_sequence);
    try std.testing.expectEqual(@as(u64, 2), projection.event_count);
}
