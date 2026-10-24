const std = @import("std");

pub const hash_bytes = std.crypto.hash.sha2.Sha256.digest_length;
pub const current_schema: u16 = 1;
pub const max_event_id_bytes: usize = 256;
pub const max_provenance_bytes: usize = 4 * 1024;
pub const max_payload_bytes: usize = 16 * 1024 * 1024;
pub const max_frame_bytes: usize = 17 * 1024 * 1024;
const magic = "MISULEVT";
const commit_marker = "MISULCOM";
const header_bytes: usize = 206;
const trailer_bytes: usize = commit_marker.len;
const record_hash_offset: usize = 174;
var identity_counter: std.atomic.Value(u64) = .init(0);

pub const EventKind = enum(u16) {
    session_created = 0,
    session_selection_changed = 1,
    branch_created = 2,
    branch_selected = 3,
    message_appended = 4,
    run_started = 5,
    run_settled = 6,
    tool_started = 7,
    tool_finished = 8,
    cancellation_requested = 9,
    failure = 10,
    compaction_epoch_committed = 11,
    user_constraint_added = 12,
    user_constraint_superseded = 13,
    agent_adaptation_revised = 14,
    agent_adaptation_rolled_back = 15,
    task_created = 16,
    task_relation_added = 17,
    task_message_appended = 18,
    task_lease_acquired = 19,
    task_heartbeat = 20,
    task_execution_started = 21,
    task_state_changed = 22,
    task_result_committed = 23,
    task_result_collected = 24,
    action_requested = 25,
    action_decided = 26,
    action_settled = 27,
    plugin_state_changed = 28,
    task_message_acknowledged = 29,
    task_output_visible = 30,
    action_dispatch_begun = 31,
    runtime_epoch_started = 32,
};

pub const Settlement = enum(u8) {
    pending,
    succeeded,
    failed,
    cancelled,
    interrupted,
};

pub const Event = struct {
    kind: EventKind,
    payload: []const u8,
    event_id: []const u8 = "",
    timestamp: u64 = 0,
};

pub const TailCondition = enum {
    none,
    partial_header,
    partial_event_id,
    partial_provenance,
    partial_payload,
    partial_commit_marker,
    malformed_header,
    unknown_schema,
    unknown_kind,
    invalid_settlement,
    invalid_provenance,
    oversized_record,
    duplicate_event_id,
    duplicate_sequence,
    sequence_gap,
    store_id_mismatch,
    record_id_mismatch,
    previous_hash_mismatch,
    payload_hash_mismatch,
    record_hash_mismatch,
    missing_commit_marker,
};

pub const RecoveryReport = struct {
    verified_end: u64 = 0,
    valid_prefix_end: u64 = 0,
    last_sequence: u64 = 0,
    last_hash: [hash_bytes]u8 = @splat(0),
    record_count: u64 = 0,
    tail: TailCondition = .none,
    tail_offset: u64 = 0,
    writable: bool = true,
};

pub const RecordView = struct {
    store_id: [hash_bytes]u8,
    record_id: [hash_bytes]u8,
    schema: u16,
    kind: EventKind,
    global_sequence: u64,
    timestamp: u64,
    provenance: []const u8,
    settlement: Settlement,
    event_id: []const u8,
    payload: []const u8,
    previous_hash: [hash_bytes]u8,
    payload_hash: [hash_bytes]u8,
    record_hash: [hash_bytes]u8,
    source_store: ?*Store = null,

    /// Verify the complete self-contained wire identity before a projection
    /// accepts a borrowed record.  Store replay also checks the surrounding
    /// chain; this check keeps direct projection calls from mutating state
    /// from an unframed or tampered value.
    pub fn verifyWire(self: RecordView) !void {
        const source_store = self.source_store orelse return error.UnauthorizedRecord;
        if (!source_store.isAuthorizedRecord(self)) return error.UnauthorizedRecord;
        try self.verifyIdentity();
    }

    fn verifyIdentity(self: RecordView) !void {
        if (self.schema != current_schema) return error.UnknownSchema;
        if (self.event_id.len == 0 or self.event_id.len > max_event_id_bytes) return error.InvalidRecord;
        if (self.provenance.len == 0 or self.provenance.len > max_provenance_bytes) return error.InvalidRecord;
        if (self.payload.len > max_payload_bytes) return error.InvalidRecord;
        var expected_payload_hash: [hash_bytes]u8 = undefined;
        std.crypto.hash.sha2.Sha256.hash(self.payload, &expected_payload_hash, .{});
        if (!std.mem.eql(u8, &expected_payload_hash, &self.payload_hash)) return error.InvalidRecord;
        var expected_record_id: [hash_bytes]u8 = undefined;
        hashRecordId(&expected_record_id, self.store_id, self.global_sequence, self.timestamp, expected_payload_hash, self.event_id, self.provenance);
        if (!std.mem.eql(u8, &expected_record_id, &self.record_id)) return error.InvalidRecord;
        var header: [header_bytes]u8 = @splat(0);
        @memcpy(header[0..magic.len], magic);
        std.mem.writeInt(u16, header[8..10], self.schema, .little);
        std.mem.writeInt(u16, header[10..12], @intFromEnum(self.kind), .little);
        header[12] = @intFromEnum(self.settlement);
        std.mem.writeInt(u32, header[14..18], @intCast(self.event_id.len), .little);
        std.mem.writeInt(u32, header[18..22], @intCast(self.provenance.len), .little);
        std.mem.writeInt(u64, header[22..30], @intCast(self.payload.len), .little);
        std.mem.writeInt(u64, header[30..38], self.global_sequence, .little);
        std.mem.writeInt(u64, header[38..46], self.timestamp, .little);
        @memcpy(header[46..78], &self.store_id);
        @memcpy(header[78..110], &self.record_id);
        @memcpy(header[110..142], &self.previous_hash);
        @memcpy(header[142..174], &self.payload_hash);
        var expected_record_hash: [hash_bytes]u8 = undefined;
        hashRecordParts(&expected_record_hash, &header, self.event_id, self.provenance, self.payload);
        if (!std.mem.eql(u8, &expected_record_hash, &self.record_hash)) return error.InvalidRecord;
    }
};

pub const RecordRef = struct {
    store_id: [hash_bytes]u8,
    record_id: [hash_bytes]u8,
    schema: u16,
    kind: EventKind,
    global_sequence: u64,
    timestamp: u64,
    provenance: []u8,
    settlement: Settlement,
    event_id: []u8,
    payload: []u8,
    previous_hash: [hash_bytes]u8,
    payload_hash: [hash_bytes]u8,
    record_hash: [hash_bytes]u8,
    owner_store: ?*Store = null,

    /// Produce a projection-capable borrowed view only for a record returned
    /// by its owning Store. A caller-built or foreign RecordRef has no token.
    pub fn view(self: *const RecordRef) !RecordView {
        if (self.owner_store == null) return error.UnauthorizedRecord;
        return .{
            .store_id = self.store_id,
            .record_id = self.record_id,
            .schema = self.schema,
            .kind = self.kind,
            .global_sequence = self.global_sequence,
            .timestamp = self.timestamp,
            .provenance = self.provenance,
            .settlement = self.settlement,
            .event_id = self.event_id,
            .payload = self.payload,
            .previous_hash = self.previous_hash,
            .payload_hash = self.payload_hash,
            .record_hash = self.record_hash,
            .source_store = self.owner_store,
        };
    }

    pub fn deinit(self: *RecordRef, allocator: std.mem.Allocator) void {
        allocator.free(self.provenance);
        allocator.free(self.event_id);
        allocator.free(self.payload);
        self.* = undefined;
    }
};

fn viewFromRef(store: *Store, ref: *const RecordRef) RecordView {
    return .{
        .store_id = ref.store_id,
        .record_id = ref.record_id,
        .schema = ref.schema,
        .kind = ref.kind,
        .global_sequence = ref.global_sequence,
        .timestamp = ref.timestamp,
        .provenance = ref.provenance,
        .settlement = ref.settlement,
        .event_id = ref.event_id,
        .payload = ref.payload,
        .previous_hash = ref.previous_hash,
        .payload_hash = ref.payload_hash,
        .record_hash = ref.record_hash,
        .source_store = store,
    };
}

pub const Visitor = *const fn (context: *anyopaque, record: RecordView) anyerror!void;
pub const PrepareAppend = *const fn (context: *anyopaque, event: Event, settlement: Settlement) anyerror!void;
pub const ValidateProjection = *const fn (context: *anyopaque) bool;
pub const CommitProjection = *const fn (context: *anyopaque, record: RecordView) void;
pub const ApplyProjection = *const fn (context: *anyopaque, record: RecordView) anyerror!void;
/// Address-stable identity owned by the projection module. Use private data
/// storage, not a function pointer: optimized builds may fold identical
/// functions to one address.
pub const ProjectionAuthority = *const anyopaque;

/// Store-minted identity for one projection cursor. Cursor position and owner
/// remain private Store state; callers can copy the token but cannot retarget
/// it to another projection or Store.
pub const ProjectionCursor = enum(u256) {
    unbound = 0,
    _,
};

const ProjectionCursorState = struct {
    capability: ProjectionCursor,
    owner: *anyopaque,
    authority: ProjectionAuthority,
    next_sequence: u64 = 1,
    last_hash: [hash_bytes]u8 = @splat(0),
    active: bool = true,
};

const StorePrivate = opaque {};

const StorePrivateState = struct {
    prepared_states: std.ArrayList(PreparedState) = .empty,
    projection_cursors: std.ArrayList(ProjectionCursorState) = .empty,
};

const PreparedState = struct {
    capability: PreparedAppend = .invalid,
    active: bool = false,
    allocator: std.mem.Allocator,
    frame: []u8 = &.{},
    provenance: []u8 = &.{},
    event_id: []u8 = &.{},
    indexed_event_id: []u8 = &.{},
    payload: []u8 = &.{},
    store_id: [hash_bytes]u8,
    record_id: [hash_bytes]u8,
    schema: u16,
    kind: EventKind,
    global_sequence: u64,
    timestamp: u64,
    settlement: Settlement,
    previous_hash: [hash_bytes]u8,
    payload_hash: [hash_bytes]u8,
    record_hash: [hash_bytes]u8,
    expected_end_offset: u64,

    fn free(self: *PreparedState) void {
        if (self.frame.len != 0) self.allocator.free(self.frame);
        if (self.provenance.len != 0) self.allocator.free(self.provenance);
        if (self.event_id.len != 0) self.allocator.free(self.event_id);
        if (self.indexed_event_id.len != 0) self.allocator.free(self.indexed_event_id);
        if (self.payload.len != 0) self.allocator.free(self.payload);
        self.frame = &.{};
        self.provenance = &.{};
        self.event_id = &.{};
        self.indexed_event_id = &.{};
        self.payload = &.{};
        self.active = false;
    }
};

/// Opaque, randomly minted identity for one Store-indexed reservation. The
/// value carries no caller-writable Store, slot, or generation fields, so it
/// cannot be retargeted. Copies name the same one-shot reservation.
pub const PreparedAppend = enum(u256) {
    invalid = 0,
    _,

    pub fn deinit(self: *PreparedAppend) void {
        self.* = .invalid;
    }
};

const AppendSnapshot = struct {
    store_id: [hash_bytes]u8,
    previous_hash: [hash_bytes]u8,
    sequence: u64,
    end_offset: u64,
    timestamp: u64,
};

const CommittedSnapshot = struct {
    end_offset: u64,
    last_sequence: u64,
    last_hash: [hash_bytes]u8,
    store_id: [hash_bytes]u8,
    writable: bool,
};

pub const Store = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    path: []u8,
    file: std.Io.File,
    store_id: [hash_bytes]u8,
    end_offset: u64 = 0,
    next_sequence: u64 = 1,
    previous_hash: [hash_bytes]u8 = @splat(0),
    writable: bool = true,
    permanently_read_only: bool = false,
    closed: bool = false,
    visitor_context: ?*anyopaque = null,
    event_ids: std.ArrayList([]u8) = .empty,
    committed_hashes: std.ArrayList([hash_bytes]u8) = .empty,
    private_state: *StorePrivate,
    reserved_event_slots: usize = 0,
    // One lock serializes all durable ordering and projection callbacks.  A
    // callback is never invoked without this lock, so append-and-project
    // callers observe the same order as the hash chain.
    mutex: std.Io.Mutex = .init,
    // Reservations are made before the durable writer is acquired.  The
    // commit path only consumes an already-reserved ArrayList slot.
    reservation_mutex: std.Io.Mutex = .init,

    pub const OpenOptions = struct {
        provenance: []const u8 = "",
    };

    pub const Result = struct {
        store: Store,
        recovery: RecoveryReport,

        pub fn close(self: *Result) void {
            self.store.close();
        }
    };

    pub fn open(
        allocator: std.mem.Allocator,
        io: std.Io,
        path: []const u8,
        options: OpenOptions,
    ) !Result {
        _ = options;
        const owned_path = try allocator.dupe(u8, path);
        errdefer allocator.free(owned_path);
        const file = try std.Io.Dir.cwd().createFile(io, path, .{
            .read = true,
            .truncate = false,
            .lock = .exclusive,
        });
        var store_id = newStoreIdentity(io);
        const existing_size = try file.length(io);
        if (existing_size >= header_bytes) {
            var first_header: [header_bytes]u8 = undefined;
            if (try readExact(file, io, &first_header, 0) == first_header.len and
                std.mem.eql(u8, first_header[0..magic.len], magic))
            {
                // Existing bytes are authoritative.  This keeps a moved file
                // valid even when the caller spells its path differently.
                @memcpy(&store_id, first_header[46..78]);
            }
        }
        const private_state = allocator.create(StorePrivateState) catch |err| {
            file.close(io);
            return err;
        };
        private_state.* = .{};
        var store = Store{
            .allocator = allocator,
            .io = io,
            .path = owned_path,
            .file = file,
            .store_id = store_id,
            .private_state = @ptrCast(private_state),
        };
        errdefer store.close();
        const recovery = try store.replay(null);
        return .{ .store = store, .recovery = recovery };
    }

    pub fn close(self: *Store) void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        self.closeUnlocked();
    }

    fn closeUnlocked(self: *Store) void {
        if (self.closed) return;
        self.file.close(self.io);
        self.reservation_mutex.lockUncancelable(self.io);
        const private_state = self.privateState();
        for (private_state.prepared_states.items) |*prepared| prepared.free();
        private_state.prepared_states.deinit(self.allocator);
        self.reserved_event_slots = 0;
        for (self.event_ids.items) |event_id| self.allocator.free(event_id);
        self.event_ids.deinit(self.allocator);
        self.committed_hashes.deinit(self.allocator);
        private_state.projection_cursors.deinit(self.allocator);
        self.reservation_mutex.unlock(self.io);
        self.allocator.destroy(private_state);
        self.allocator.free(self.path);
        self.closed = true;
    }

    fn privateState(self: *Store) *StorePrivateState {
        return @ptrCast(@alignCast(self.private_state));
    }

    pub fn isWritable(self: *Store) bool {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        return self.isWritableUnlocked();
    }

    fn isWritableUnlocked(self: *const Store) bool {
        return self.writable and !self.permanently_read_only and !self.closed;
    }

    /// Fence this handle after a downstream projection rejects a verified
    /// event.  The bytes remain authoritative for a fresh replay.
    pub fn failClosed(self: *Store) void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        self.failClosedUnlocked();
    }

    fn failClosedUnlocked(self: *Store) void {
        self.writable = false;
        self.permanently_read_only = true;
    }

    pub fn containsEventId(self: *Store, event_id: []const u8) bool {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        self.reservation_mutex.lockUncancelable(self.io);
        defer self.reservation_mutex.unlock(self.io);
        return self.containsEventIdUnlocked(event_id);
    }

    fn containsEventIdUnlocked(self: *const Store, event_id: []const u8) bool {
        for (self.event_ids.items) |known| if (std.mem.eql(u8, known, event_id)) return true;
        return false;
    }

    fn isAuthorizedRecord(self: *Store, record: RecordView) bool {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        return self.isAuthorizedRecordUnlocked(record);
    }

    fn isAuthorizedRecordUnlocked(self: *const Store, record: RecordView) bool {
        if (self.closed or record.global_sequence == 0 or record.global_sequence > self.committed_hashes.items.len) return false;
        return std.mem.eql(u8, &self.store_id, &record.store_id) and
            std.mem.eql(u8, &self.committed_hashes.items[record.global_sequence - 1], &record.record_hash);
    }

    /// Prepare all frame and return-value bytes before the durable writer is
    /// acquired. A snapshot check in commitPrepared rejects a stale prepare.
    pub fn prepareAppend(self: *Store, event: Event, provenance: []const u8, settlement: Settlement) !PreparedAppend {
        const snapshot = blk: {
            self.mutex.lockUncancelable(self.io);
            defer self.mutex.unlock(self.io);
            self.reservation_mutex.lockUncancelable(self.io);
            defer self.reservation_mutex.unlock(self.io);
            if (!self.isWritableUnlocked()) return error.StoreReadOnly;
            if (provenance.len == 0 or provenance.len > max_provenance_bytes) return error.InvalidProvenance;
            if (event.event_id.len == 0) return error.MissingEventId;
            if (event.event_id.len > max_event_id_bytes) return error.EventIdTooLong;
            if (self.containsEventIdUnlocked(event.event_id)) return error.DuplicateEventId;
            if (event.payload.len > max_payload_bytes) return error.PayloadTooLarge;
            const timestamp = if (event.timestamp == 0) nowMillis(self.io) else event.timestamp;
            const variable_len = event.event_id.len + provenance.len + event.payload.len;
            const frame_len = header_bytes + variable_len + trailer_bytes;
            if (frame_len > max_frame_bytes) return error.FrameTooLarge;
            break :blk AppendSnapshot{
                .store_id = self.store_id,
                .previous_hash = self.previous_hash,
                .sequence = self.next_sequence,
                .end_offset = self.end_offset,
                .timestamp = timestamp,
            };
        };
        var state = try buildPrepared(self.allocator, event, provenance, settlement, snapshot);
        errdefer state.free();
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        self.reservation_mutex.lockUncancelable(self.io);
        defer self.reservation_mutex.unlock(self.io);
        if (!self.isWritableUnlocked()) return error.StoreReadOnly;
        try self.event_ids.ensureUnusedCapacity(self.allocator, self.reserved_event_slots +| 1);
        try self.committed_hashes.ensureUnusedCapacity(self.allocator, self.reserved_event_slots +| 1);
        const handle = try self.installPreparedStateUnlocked(&state);
        state = undefined;
        return handle;
    }

    /// Release a prepared append that will not be committed. PreparedAppend
    /// deliberately carries no Store pointer, so callers must discard through
    /// the Store before invalidating their local value with deinit.
    pub fn discardPrepared(self: *Store, prepared: *PreparedAppend) void {
        self.releasePreparedReservation(prepared) catch {};
        prepared.deinit();
    }

    pub fn discardPreparedChecked(self: *Store, prepared: *PreparedAppend) !void {
        try self.releasePreparedReservation(prepared);
        prepared.deinit();
    }

    /// Replace bytes in an already reserved payload without allocating. This
    /// is used for bounded evidence known only after an external dispatch.
    pub fn patchPreparedPayload(self: *Store, prepared: PreparedAppend, offset: usize, replacement: []const u8) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        self.reservation_mutex.lockUncancelable(self.io);
        defer self.reservation_mutex.unlock(self.io);
        if (prepared == .invalid) return error.PreparedAppendMismatch;
        const state = self.findPreparedStateUnlocked(prepared) orelse return error.PreparedStoreMismatch;
        if (!state.active) return error.PreparedAppendConsumed;
        if (offset > state.payload.len or replacement.len > state.payload.len - offset) return error.PreparedPayloadBounds;
        @memcpy(state.payload[offset..][0..replacement.len], replacement);
        const frame_payload_offset = header_bytes + state.event_id.len + state.provenance.len;
        @memcpy(state.frame[frame_payload_offset + offset ..][0..replacement.len], replacement);
        std.crypto.hash.sha2.Sha256.hash(state.payload, &state.payload_hash, .{});
        @memcpy(state.frame[142..174], &state.payload_hash);
        hashRecordId(&state.record_id, state.store_id, state.global_sequence, state.timestamp, state.payload_hash, state.event_id, state.provenance);
        @memcpy(state.frame[78..110], &state.record_id);
        hashRecordBytes(&state.record_hash, state.frame, state.frame[header_bytes .. state.frame.len - trailer_bytes]);
        @memcpy(state.frame[record_hash_offset .. record_hash_offset + hash_bytes], &state.record_hash);
    }

    fn releasePreparedReservation(self: *Store, prepared: *const PreparedAppend) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        if (self.closed) return;
        self.reservation_mutex.lockUncancelable(self.io);
        defer self.reservation_mutex.unlock(self.io);
        return self.releasePreparedReservationUnlocked(prepared);
    }

    fn releasePreparedReservationUnlocked(self: *Store, prepared: *const PreparedAppend) !void {
        if (prepared.* == .invalid) return error.PreparedAppendMismatch;
        const state = self.findPreparedStateUnlocked(prepared.*) orelse return error.PreparedStoreMismatch;
        if (!state.active) return;
        state.free();
        std.debug.assert(self.reserved_event_slots != 0);
        self.reserved_event_slots -= 1;
    }

    fn findPreparedStateUnlocked(self: *Store, capability: PreparedAppend) ?*PreparedState {
        for (self.privateState().prepared_states.items) |*state| {
            if (state.capability == capability) return state;
        }
        return null;
    }

    fn installPreparedStateUnlocked(self: *Store, state: *PreparedState) !PreparedAppend {
        const capability = self.mintPreparedCapabilityUnlocked();
        state.capability = capability;
        state.active = true;
        const prepared_states = &self.privateState().prepared_states;
        for (prepared_states.items) |*slot| {
            if (!slot.active) {
                slot.* = state.*;
                self.reserved_event_slots += 1;
                return capability;
            }
        }
        prepared_states.append(self.allocator, state.*) catch |err| {
            state.active = false;
            return err;
        };
        self.reserved_event_slots += 1;
        return capability;
    }

    fn mintPreparedCapabilityUnlocked(self: *Store) PreparedAppend {
        while (true) {
            var bytes: [@sizeOf(u256)]u8 = undefined;
            self.io.random(&bytes);
            const raw: u256 = @bitCast(bytes);
            if (raw == 0) continue;
            const capability: PreparedAppend = @enumFromInt(raw);
            if (self.findPreparedStateUnlocked(capability) == null) return capability;
        }
    }

    fn mintProjectionCapabilityUnlocked(self: *Store) ProjectionCursor {
        while (true) {
            var bytes: [@sizeOf(u256)]u8 = undefined;
            self.io.random(&bytes);
            const raw: u256 = @bitCast(bytes);
            if (raw == 0) continue;
            const capability: ProjectionCursor = @enumFromInt(raw);
            if (self.findProjectionCursorByCapabilityUnlocked(capability) == null) return capability;
        }
    }

    fn findProjectionCursorByCapabilityUnlocked(self: *Store, capability: ProjectionCursor) ?*ProjectionCursorState {
        for (self.privateState().projection_cursors.items) |*state| {
            if (state.capability == capability) return state;
        }
        return null;
    }

    fn findProjectionCursorByOwnerUnlocked(self: *Store, owner: *anyopaque) ?*ProjectionCursorState {
        for (self.privateState().projection_cursors.items) |*state| {
            if (state.active and state.owner == owner) return state;
        }
        return null;
    }

    fn projectionCursorStateUnlocked(self: *Store, owner: *anyopaque, cursor: *ProjectionCursor, authority: ProjectionAuthority) !*ProjectionCursorState {
        if (cursor.* == .unbound) {
            if (self.findProjectionCursorByOwnerUnlocked(owner)) |state| {
                if (state.authority != authority) return error.ProjectionCursorMismatch;
                cursor.* = state.capability;
                return state;
            }
            const capability = self.mintProjectionCapabilityUnlocked();
            const projection_cursors = &self.privateState().projection_cursors;
            try projection_cursors.append(self.allocator, .{ .capability = capability, .owner = owner, .authority = authority });
            cursor.* = capability;
            return &projection_cursors.items[projection_cursors.items.len - 1];
        }
        const state = self.findProjectionCursorByCapabilityUnlocked(cursor.*) orelse return error.ProjectionCursorMismatch;
        if (!state.active or state.owner != owner or state.authority != authority) return error.ProjectionCursorMismatch;
        return state;
    }

    /// Consume exactly the next committed record for one Store-owned
    /// projection cursor. The callback completes before the cursor advances;
    /// duplicate, regressed, skipped, copied, and foreign records fail before
    /// projection mutation.
    pub fn applyCommittedToProjection(
        self: *Store,
        owner: *anyopaque,
        cursor: *ProjectionCursor,
        authority: ProjectionAuthority,
        record: RecordView,
        context: *anyopaque,
        apply_projection: ApplyProjection,
    ) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        if (self.closed) return error.StoreClosed;
        if (record.source_store != self or !self.isAuthorizedRecordUnlocked(record)) return error.UnauthorizedRecord;
        try record.verifyIdentity();
        const state = try self.projectionCursorStateUnlocked(owner, cursor, authority);
        if (record.global_sequence < state.next_sequence) {
            if (record.global_sequence +| 1 == state.next_sequence) return error.DuplicateProjectionRecord;
            return error.ProjectionRecordRegressed;
        }
        if (record.global_sequence > state.next_sequence) return error.ProjectionRecordSkipped;
        if (!std.mem.eql(u8, &record.previous_hash, &state.last_hash)) return error.ProjectionCursorMismatch;
        try apply_projection(context, record);
        state.next_sequence += 1;
        state.last_hash = record.record_hash;
    }

    pub fn retireProjectionCursor(self: *Store, owner: *anyopaque, cursor: *ProjectionCursor, authority: ProjectionAuthority) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        if (self.closed) {
            cursor.* = .unbound;
            return;
        }
        if (cursor.* == .unbound) return;
        const state = self.findProjectionCursorByCapabilityUnlocked(cursor.*) orelse return error.ProjectionCursorMismatch;
        if (!state.active or state.owner != owner or state.authority != authority) return error.ProjectionCursorMismatch;
        state.active = false;
        cursor.* = .unbound;
    }

    fn consumePreparedUnlocked(self: *Store, state: *PreparedState) void {
        state.active = false;
        std.debug.assert(self.reserved_event_slots != 0);
        self.reserved_event_slots -= 1;
    }

    fn freePreparedStateAfterCommit(state: *PreparedState) void {
        if (state.frame.len != 0) state.allocator.free(state.frame);
        state.frame = &.{};
        state.active = false;
    }

    fn buildPrepared(allocator: std.mem.Allocator, event: Event, provenance: []const u8, settlement: Settlement, snapshot: AppendSnapshot) !PreparedState {
        const sequence = snapshot.sequence;
        const end_offset = snapshot.end_offset;
        const previous_hash = snapshot.previous_hash;
        const store_id = snapshot.store_id;
        const timestamp = snapshot.timestamp;
        const frame_len = header_bytes + event.event_id.len + provenance.len + event.payload.len + trailer_bytes;
        const owned_event_id = try allocator.dupe(u8, event.event_id);
        errdefer allocator.free(owned_event_id);
        const indexed_event_id = try allocator.dupe(u8, event.event_id);
        errdefer allocator.free(indexed_event_id);
        const owned_provenance = try allocator.dupe(u8, provenance);
        errdefer allocator.free(owned_provenance);
        const owned_payload = try allocator.dupe(u8, event.payload);
        errdefer allocator.free(owned_payload);
        var frame = try allocator.alloc(u8, frame_len);
        errdefer allocator.free(frame);
        @memset(frame, 0);
        @memcpy(frame[0..magic.len], magic);
        std.mem.writeInt(u16, frame[8..10], current_schema, .little);
        std.mem.writeInt(u16, frame[10..12], @intFromEnum(event.kind), .little);
        frame[12] = @intFromEnum(settlement);
        frame[13] = 0;
        std.mem.writeInt(u32, frame[14..18], @intCast(event.event_id.len), .little);
        std.mem.writeInt(u32, frame[18..22], @intCast(provenance.len), .little);
        std.mem.writeInt(u64, frame[22..30], @intCast(event.payload.len), .little);
        std.mem.writeInt(u64, frame[30..38], sequence, .little);
        std.mem.writeInt(u64, frame[38..46], timestamp, .little);
        @memcpy(frame[46..78], &store_id);
        @memcpy(frame[110..142], &previous_hash);
        var payload_hash: [hash_bytes]u8 = undefined;
        std.crypto.hash.sha2.Sha256.hash(event.payload, &payload_hash, .{});
        @memcpy(frame[142..174], &payload_hash);
        var record_id: [hash_bytes]u8 = undefined;
        hashRecordId(&record_id, store_id, sequence, timestamp, payload_hash, event.event_id, provenance);
        @memcpy(frame[78..110], &record_id);
        var variable_offset = header_bytes;
        @memcpy(frame[variable_offset..][0..event.event_id.len], event.event_id);
        variable_offset += event.event_id.len;
        @memcpy(frame[variable_offset..][0..provenance.len], provenance);
        variable_offset += provenance.len;
        @memcpy(frame[variable_offset..][0..event.payload.len], event.payload);
        @memcpy(frame[frame_len - trailer_bytes ..], commit_marker);
        var record_hash: [hash_bytes]u8 = undefined;
        hashRecordBytes(&record_hash, frame, frame[header_bytes .. frame_len - trailer_bytes]);
        @memcpy(frame[record_hash_offset .. record_hash_offset + hash_bytes], &record_hash);
        return .{
            .allocator = allocator,
            .frame = frame,
            .provenance = owned_provenance,
            .event_id = owned_event_id,
            .indexed_event_id = indexed_event_id,
            .payload = owned_payload,
            .store_id = store_id,
            .record_id = record_id,
            .schema = current_schema,
            .kind = event.kind,
            .global_sequence = sequence,
            .timestamp = timestamp,
            .settlement = settlement,
            .previous_hash = previous_hash,
            .payload_hash = payload_hash,
            .record_hash = record_hash,
            .expected_end_offset = end_offset,
        };
    }

    pub fn commitPrepared(self: *Store, prepared: *PreparedAppend) !RecordRef {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        return self.commitPreparedUnlocked(prepared);
    }

    fn commitPreparedUnlocked(self: *Store, prepared: *PreparedAppend) !RecordRef {
        if (!self.isWritableUnlocked()) return error.StoreReadOnly;
        self.reservation_mutex.lockUncancelable(self.io);
        defer self.reservation_mutex.unlock(self.io);
        if (prepared.* == .invalid) return error.PreparedAppendMismatch;
        const state = self.findPreparedStateUnlocked(prepared.*) orelse return error.PreparedStoreMismatch;
        if (!state.active) return error.PreparedAppendConsumed;
        if (self.containsEventIdUnlocked(state.event_id)) {
            state.free();
            self.consumePreparedUnlocked(state);
            return error.DuplicateEventId;
        }
        if (self.event_ids.items.len == self.event_ids.capacity) {
            state.free();
            self.consumePreparedUnlocked(state);
            return error.PreparedAppendCapacity;
        }
        if (self.end_offset != state.expected_end_offset or self.next_sequence != state.global_sequence or
            !std.mem.eql(u8, &self.previous_hash, &state.previous_hash) or
            !std.mem.eql(u8, &self.store_id, &state.store_id)) self.retargetPrepared(state);
        self.file.writePositionalAll(self.io, state.frame, self.end_offset) catch |err| {
            self.failClosedUnlocked();
            state.free();
            self.consumePreparedUnlocked(state);
            return err;
        };
        self.file.sync(self.io) catch |err| {
            self.failClosedUnlocked();
            state.free();
            self.consumePreparedUnlocked(state);
            return err;
        };
        self.end_offset += state.frame.len;
        self.next_sequence += 1;
        self.previous_hash = state.record_hash;
        self.event_ids.appendAssumeCapacity(state.indexed_event_id);
        self.committed_hashes.appendAssumeCapacity(state.record_hash);
        state.indexed_event_id = &.{};
        const result = RecordRef{
            .store_id = state.store_id,
            .record_id = state.record_id,
            .schema = state.schema,
            .kind = state.kind,
            .global_sequence = state.global_sequence,
            .timestamp = state.timestamp,
            .provenance = state.provenance,
            .settlement = state.settlement,
            .event_id = state.event_id,
            .payload = state.payload,
            .previous_hash = state.previous_hash,
            .payload_hash = state.payload_hash,
            .record_hash = state.record_hash,
            .owner_store = self,
        };
        state.provenance = &.{};
        state.event_id = &.{};
        state.payload = &.{};
        freePreparedStateAfterCommit(state);
        self.consumePreparedUnlocked(state);
        return result;
    }

    fn retargetPrepared(self: *Store, state: *PreparedState) void {
        const frame = state.frame;
        std.mem.writeInt(u64, frame[30..38], self.next_sequence, .little);
        std.mem.writeInt(u64, frame[38..46], state.timestamp, .little);
        @memcpy(frame[46..78], &self.store_id);
        @memcpy(frame[110..142], &self.previous_hash);
        var record_id: [hash_bytes]u8 = undefined;
        hashRecordId(&record_id, self.store_id, self.next_sequence, state.timestamp, state.payload_hash, state.event_id, state.provenance);
        @memcpy(frame[78..110], &record_id);
        var record_hash: [hash_bytes]u8 = undefined;
        hashRecordBytes(&record_hash, frame, frame[header_bytes .. frame.len - trailer_bytes]);
        @memcpy(frame[record_hash_offset .. record_hash_offset + hash_bytes], &record_hash);
        state.store_id = self.store_id;
        state.record_id = record_id;
        state.global_sequence = self.next_sequence;
        state.previous_hash = self.previous_hash;
        state.record_hash = record_hash;
        state.expected_end_offset = self.end_offset;
    }

    /// Prepare projection state and all allocations before acquiring the store
    /// writer. The writer then snapshots the current frame position, checks the
    /// projection version, commits the durable frame, and invokes only the
    /// allocation-free projection delta while the writer is held. The runtime
    /// owns serialization of a projection context across callers.
    pub fn appendPreparedAndProject(
        self: *Store,
        event: Event,
        provenance: []const u8,
        settlement: Settlement,
        context: *anyopaque,
        prepare: PrepareAppend,
        validate: ValidateProjection,
        commit: CommitProjection,
    ) !RecordRef {
        return self.appendPreparedAndProjectInternal(event, provenance, settlement, null, null, null, context, prepare, validate, commit);
    }

    /// Append through a projection that has consumed the Store's complete
    /// current prefix. The Store checks and advances the opaque cursor in the
    /// same writer critical section as the durable append and infallible
    /// projection commit.
    pub fn appendPreparedAndProjectAtCursor(
        self: *Store,
        event: Event,
        provenance: []const u8,
        settlement: Settlement,
        projection_owner: *anyopaque,
        cursor: *ProjectionCursor,
        authority: ProjectionAuthority,
        context: *anyopaque,
        prepare: PrepareAppend,
        validate: ValidateProjection,
        commit: CommitProjection,
    ) !RecordRef {
        return self.appendPreparedAndProjectInternal(event, provenance, settlement, projection_owner, cursor, authority, context, prepare, validate, commit);
    }

    fn appendPreparedAndProjectInternal(
        self: *Store,
        event: Event,
        provenance: []const u8,
        settlement: Settlement,
        projection_owner: ?*anyopaque,
        cursor: ?*ProjectionCursor,
        authority: ?ProjectionAuthority,
        context: *anyopaque,
        prepare: PrepareAppend,
        validate: ValidateProjection,
        commit: CommitProjection,
    ) !RecordRef {
        try prepare(context, event, settlement);
        var prepared = try self.prepareAppend(event, provenance, settlement);
        defer self.discardPrepared(&prepared);
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        if (!validate(context)) {
            self.reservation_mutex.lockUncancelable(self.io);
            self.releasePreparedReservationUnlocked(&prepared) catch {};
            self.reservation_mutex.unlock(self.io);
            return error.ProjectionChanged;
        }
        const cursor_state = if (cursor) |projection_cursor| blk: {
            const owner = projection_owner orelse return error.ProjectionCursorMismatch;
            const cursor_authority = authority orelse return error.ProjectionCursorMismatch;
            const state = try self.projectionCursorStateUnlocked(owner, projection_cursor, cursor_authority);
            if (state.next_sequence != self.next_sequence or !std.mem.eql(u8, &state.last_hash, &self.previous_hash)) return error.ProjectionRecordSkipped;
            break :blk state;
        } else null;
        var ref = try self.commitPreparedUnlocked(&prepared);
        commit(context, viewFromRef(self, &ref));
        if (cursor_state) |state| {
            std.debug.assert(ref.global_sequence == state.next_sequence);
            state.next_sequence += 1;
            state.last_hash = ref.record_hash;
        }
        return ref;
    }

    pub fn append(self: *Store, event: Event, provenance: []const u8, settlement: Settlement) !RecordRef {
        var prepared = try self.prepareAppend(event, provenance, settlement);
        defer self.discardPrepared(&prepared);
        return self.commitPrepared(&prepared);
    }

    pub fn replay(self: *Store, visitor: ?Visitor) !RecoveryReport {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        return self.replayUnlocked(visitor);
    }

    fn replayUnlocked(self: *Store, visitor: ?Visitor) !RecoveryReport {
        if (self.closed) return error.StoreClosed;
        self.reservation_mutex.lockUncancelable(self.io);
        defer self.reservation_mutex.unlock(self.io);
        self.end_offset = 0;
        self.next_sequence = 1;
        self.previous_hash = @splat(0);
        self.writable = !self.permanently_read_only;
        for (self.event_ids.items) |event_id| self.allocator.free(event_id);
        self.event_ids.clearRetainingCapacity();
        self.committed_hashes.clearRetainingCapacity();
        var report = RecoveryReport{ .writable = self.writable };
        const file_size = self.file.length(self.io) catch |err| {
            self.writable = false;
            self.permanently_read_only = true;
            return err;
        };
        var offset: u64 = 0;
        while (offset < file_size) {
            const remaining = file_size - offset;
            if (remaining < header_bytes) {
                return self.finishTail(&report, offset, .partial_header);
            }
            var header: [header_bytes]u8 = undefined;
            const header_read = readExact(self.file, self.io, &header, offset) catch |err| {
                self.writable = false;
                self.permanently_read_only = true;
                return err;
            };
            if (header_read != header.len) {
                return self.finishTail(&report, offset, .partial_header);
            }
            const lengths = parseLengths(header) catch |err| {
                self.permanently_read_only = true;
                self.writable = false;
                report.tail = switch (err) {
                    error.UnknownSchema => .unknown_schema,
                    error.UnknownKind => .unknown_kind,
                    error.InvalidSettlement => .invalid_settlement,
                    error.InvalidProvenance => .invalid_provenance,
                    error.OversizedRecord => .oversized_record,
                    else => .malformed_header,
                };
                report.tail_offset = offset;
                report.verified_end = offset;
                report.valid_prefix_end = offset;
                report.writable = false;
                self.end_offset = offset;
                self.next_sequence = report.last_sequence + 1;
                self.previous_hash = report.last_hash;
                return report;
            };
            const frame_len = header_bytes + lengths.event_id_len + lengths.provenance_len + lengths.payload_len + trailer_bytes;
            if (remaining < frame_len) {
                const data_end = header_bytes + lengths.event_id_len + lengths.provenance_len + lengths.payload_len;
                const available = @as(usize, @intCast(remaining));
                const condition: TailCondition = if (available < header_bytes + lengths.event_id_len)
                    .partial_event_id
                else if (available < header_bytes + lengths.event_id_len + lengths.provenance_len)
                    .partial_provenance
                else if (available < data_end)
                    .partial_payload
                else
                    .partial_commit_marker;
                return self.finishTail(&report, offset, condition);
            }

            const data_len = frame_len - header_bytes;
            var data = try self.allocator.alloc(u8, data_len);
            defer self.allocator.free(data);
            const data_read = readExact(self.file, self.io, data, offset + header_bytes) catch |err| {
                self.writable = false;
                self.permanently_read_only = true;
                return err;
            };
            if (data_read != data.len) {
                return self.finishTail(&report, offset, .partial_payload);
            }
            const trailer = data[data.len - trailer_bytes ..];
            if (!std.mem.eql(u8, trailer, commit_marker)) {
                self.permanently_read_only = true;
                self.writable = false;
                report.tail = .missing_commit_marker;
                report.tail_offset = offset;
                report.verified_end = offset;
                report.valid_prefix_end = offset;
                report.writable = false;
                self.end_offset = offset;
                return report;
            }

            const sequence = std.mem.readInt(u64, header[30..38], .little);
            if (sequence != report.last_sequence + 1) {
                self.permanently_read_only = true;
                self.writable = false;
                report.tail = if (sequence <= report.last_sequence) .duplicate_sequence else .sequence_gap;
                report.tail_offset = offset;
                report.verified_end = offset;
                report.valid_prefix_end = offset;
                report.writable = false;
                self.end_offset = offset;
                return report;
            }
            if (!std.mem.eql(u8, header[46..78], &self.store_id)) {
                return self.invalid(&report, offset, .store_id_mismatch);
            }
            if (!std.mem.eql(u8, header[110..142], &self.previous_hash)) {
                return self.invalid(&report, offset, .previous_hash_mismatch);
            }
            const event_id = data[0..lengths.event_id_len];
            if (self.containsEventIdUnlocked(event_id)) return self.invalid(&report, offset, .duplicate_event_id);
            const provenance = data[lengths.event_id_len .. lengths.event_id_len + lengths.provenance_len];
            const payload_start = lengths.event_id_len + lengths.provenance_len;
            const payload = data[payload_start .. payload_start + lengths.payload_len];
            var expected_payload_hash: [hash_bytes]u8 = undefined;
            std.crypto.hash.sha2.Sha256.hash(payload, &expected_payload_hash, .{});
            if (!std.mem.eql(u8, header[142..174], &expected_payload_hash)) {
                return self.invalid(&report, offset, .payload_hash_mismatch);
            }
            var expected_record_id: [hash_bytes]u8 = undefined;
            const timestamp = std.mem.readInt(u64, header[38..46], .little);
            hashRecordId(&expected_record_id, self.store_id, sequence, timestamp, expected_payload_hash, event_id, provenance);
            if (!std.mem.eql(u8, header[78..110], &expected_record_id)) {
                return self.invalid(&report, offset, .record_id_mismatch);
            }
            var expected_record_hash: [hash_bytes]u8 = undefined;
            hashRecordBytes(&expected_record_hash, &header, data[0 .. data.len - trailer_bytes]);
            if (!std.mem.eql(u8, header[record_hash_offset .. record_hash_offset + hash_bytes], &expected_record_hash)) {
                return self.invalid(&report, offset, .record_hash_mismatch);
            }
            const record = makeRecordView(self, header, event_id, provenance, payload, expected_payload_hash, expected_record_hash) catch |err| {
                self.permanently_read_only = true;
                self.writable = false;
                report.tail = if (err == error.InvalidSettlement) .invalid_settlement else .malformed_header;
                report.tail_offset = offset;
                report.verified_end = offset;
                report.valid_prefix_end = offset;
                report.writable = false;
                self.end_offset = offset;
                return report;
            };
            self.event_ids.ensureUnusedCapacity(self.allocator, self.reserved_event_slots +| 1) catch |err| {
                self.writable = false;
                self.permanently_read_only = true;
                return err;
            };
            self.committed_hashes.ensureUnusedCapacity(self.allocator, self.committed_hashes.items.len + 1) catch |err| {
                self.writable = false;
                self.permanently_read_only = true;
                return err;
            };
            const indexed_event_id = self.allocator.dupe(u8, event_id) catch |err| {
                self.writable = false;
                self.permanently_read_only = true;
                return err;
            };
            self.event_ids.appendAssumeCapacity(indexed_event_id);
            self.committed_hashes.appendAssumeCapacity(expected_record_hash);
            if (visitor) |visit| {
                visit(self.visitor_context orelse @ptrCast(self), record) catch |err| {
                    // The record is valid, but the projection did not accept
                    // it.  Refuse continuation on this handle rather than
                    // allowing a retry to overwrite the durable event.
                    self.writable = false;
                    self.permanently_read_only = true;
                    self.end_offset = offset + frame_len;
                    self.next_sequence = sequence + 1;
                    self.previous_hash = expected_record_hash;
                    return err;
                };
            }

            offset += frame_len;
            report.verified_end = offset;
            report.valid_prefix_end = offset;
            report.last_sequence = sequence;
            report.last_hash = expected_record_hash;
            report.record_count += 1;
            self.end_offset = offset;
            self.next_sequence = sequence + 1;
            self.previous_hash = expected_record_hash;
        }
        report.writable = self.writable;
        report.tail = .none;
        report.tail_offset = self.end_offset;
        return report;
    }

    pub fn replayWithContext(self: *Store, context: *anyopaque, visitor: Visitor) !RecoveryReport {
        // First rebuild and verify the authoritative Store indexes under the
        // writer lock. Then deliver that fixed prefix through the independent
        // range reader so a Store-owned projection cursor may lock the Store
        // for each exact record without a recursive writer lock.
        const report = try self.replay(null);
        if (report.last_sequence == 0) return report;
        _ = self.visitCommittedFrom(0, report.last_sequence, context, visitor) catch |err| {
            self.failClosed();
            return err;
        };
        return report;
    }

    /// Visit a bounded committed range without holding the durable writer
    /// while callbacks run. The high-water mark is snapshotted first; records
    /// appended after that point are never observed by this call.
    pub fn visitCommittedFrom(
        self: *Store,
        after_sequence: u64,
        through_sequence: ?u64,
        context: *anyopaque,
        visitor: Visitor,
    ) !RecoveryReport {
        const snapshot_and_path = blk: {
            self.mutex.lockUncancelable(self.io);
            defer self.mutex.unlock(self.io);
            if (self.closed) return error.StoreClosed;
            const path = try self.allocator.dupe(u8, self.path);
            break :blk .{
                .snapshot = CommittedSnapshot{
                    .end_offset = self.end_offset,
                    .last_sequence = self.next_sequence -| 1,
                    .last_hash = self.previous_hash,
                    .store_id = self.store_id,
                    .writable = self.writable,
                },
                .path = path,
            };
        };
        defer self.allocator.free(snapshot_and_path.path);
        const snapshot = snapshot_and_path.snapshot;
        const target = @min(through_sequence orelse snapshot.last_sequence, snapshot.last_sequence);
        if (after_sequence > target) return error.InvalidRecordRange;
        if (target == 0) return .{
            .last_sequence = target,
            .last_hash = if (target == snapshot.last_sequence) snapshot.last_hash else @splat(0),
            .tail = .none,
            .writable = snapshot.writable,
        };

        const file = if (std.fs.path.isAbsolute(snapshot_and_path.path))
            try std.Io.Dir.openFileAbsolute(self.io, snapshot_and_path.path, .{ .mode = .read_only, .allow_directory = false, .lock = .none })
        else
            try std.Io.Dir.cwd().openFile(self.io, snapshot_and_path.path, .{ .mode = .read_only, .allow_directory = false, .lock = .none });
        defer file.close(self.io);
        if (try file.length(self.io) < snapshot.end_offset) return error.SnapshotChanged;
        return visitCommittedRange(self, self.allocator, file, self.io, snapshot, after_sequence, target, context, visitor);
    }

    fn invalid(self: *Store, report: *RecoveryReport, offset: u64, condition: TailCondition) RecoveryReport {
        self.permanently_read_only = true;
        self.writable = false;
        report.tail = condition;
        report.tail_offset = offset;
        report.verified_end = offset;
        report.valid_prefix_end = offset;
        report.writable = false;
        self.end_offset = offset;
        self.next_sequence = report.last_sequence + 1;
        self.previous_hash = report.last_hash;
        return report.*;
    }

    fn finishTail(self: *Store, report: *RecoveryReport, offset: u64, condition: TailCondition) !RecoveryReport {
        report.tail = condition;
        report.tail_offset = offset;
        report.verified_end = offset;
        report.valid_prefix_end = offset;
        report.last_hash = self.previous_hash;
        report.writable = !self.permanently_read_only;
        if (self.writable and !self.permanently_read_only) {
            self.file.setLength(self.io, offset) catch |err| {
                self.writable = false;
                self.permanently_read_only = true;
                return err;
            };
            self.file.sync(self.io) catch |err| {
                self.writable = false;
                self.permanently_read_only = true;
                return err;
            };
            self.end_offset = offset;
            self.next_sequence = report.last_sequence + 1;
        } else {
            self.end_offset = offset;
        }
        return report.*;
    }
};

pub const OpenResult = Store.Result;

const Lengths = struct {
    event_id_len: usize,
    provenance_len: usize,
    payload_len: usize,
};

fn parseLengths(header: [header_bytes]u8) !Lengths {
    if (!std.mem.eql(u8, header[0..magic.len], magic)) return error.MalformedHeader;
    if (std.mem.readInt(u16, header[8..10], .little) != current_schema) return error.UnknownSchema;
    if (header[13] != 0) return error.MalformedHeader;
    _ = std.enums.fromInt(EventKind, std.mem.readInt(u16, header[10..12], .little)) orelse return error.UnknownKind;
    _ = std.enums.fromInt(Settlement, header[12]) orelse return error.InvalidSettlement;
    const event_id_len: usize = @intCast(std.mem.readInt(u32, header[14..18], .little));
    const provenance_len: usize = @intCast(std.mem.readInt(u32, header[18..22], .little));
    const payload_len: usize = @intCast(std.mem.readInt(u64, header[22..30], .little));
    if (event_id_len == 0) return error.MalformedHeader;
    if (provenance_len == 0) return error.InvalidProvenance;
    if (event_id_len > max_event_id_bytes or provenance_len > max_provenance_bytes or payload_len > max_payload_bytes) return error.OversizedRecord;
    const variable_len = std.math.add(usize, event_id_len, provenance_len) catch return error.OversizedRecord;
    _ = std.math.add(usize, variable_len, payload_len) catch return error.OversizedRecord;
    return .{ .event_id_len = event_id_len, .provenance_len = provenance_len, .payload_len = payload_len };
}

fn visitCommittedRange(
    source_store: *Store,
    allocator: std.mem.Allocator,
    file: std.Io.File,
    io: std.Io,
    snapshot: CommittedSnapshot,
    after_sequence: u64,
    target: u64,
    context: *anyopaque,
    visitor: Visitor,
) !RecoveryReport {
    var report = RecoveryReport{ .writable = snapshot.writable };
    var offset: u64 = 0;
    while (report.last_sequence < target) {
        if (offset >= snapshot.end_offset or snapshot.end_offset - offset < header_bytes) return error.SnapshotChanged;
        var header: [header_bytes]u8 = undefined;
        const header_read = readExact(file, io, &header, offset) catch return error.SnapshotChanged;
        if (header_read != header.len) return error.SnapshotChanged;
        const lengths = parseLengths(header) catch return error.SnapshotChanged;
        const frame_len = header_bytes + lengths.event_id_len + lengths.provenance_len + lengths.payload_len + trailer_bytes;
        if (frame_len > snapshot.end_offset - offset) return error.SnapshotChanged;
        const data_len = frame_len - header_bytes;
        const data = allocator.alloc(u8, data_len) catch return error.OutOfMemory;
        defer allocator.free(data);
        const data_read = readExact(file, io, data, offset + header_bytes) catch return error.SnapshotChanged;
        if (data_read != data.len) return error.SnapshotChanged;
        if (!std.mem.eql(u8, data[data.len - trailer_bytes ..], commit_marker)) return error.SnapshotChanged;

        const sequence = std.mem.readInt(u64, header[30..38], .little);
        if (sequence != report.last_sequence + 1) return error.SnapshotChanged;
        if (!std.mem.eql(u8, header[46..78], &snapshot.store_id)) return error.SnapshotChanged;
        if (!std.mem.eql(u8, header[110..142], &report.last_hash)) return error.SnapshotChanged;
        const event_id = data[0..lengths.event_id_len];
        const provenance = data[lengths.event_id_len .. lengths.event_id_len + lengths.provenance_len];
        const payload_start = lengths.event_id_len + lengths.provenance_len;
        const payload = data[payload_start .. payload_start + lengths.payload_len];
        var expected_payload_hash: [hash_bytes]u8 = undefined;
        std.crypto.hash.sha2.Sha256.hash(payload, &expected_payload_hash, .{});
        if (!std.mem.eql(u8, header[142..174], &expected_payload_hash)) return error.SnapshotChanged;
        var expected_record_id: [hash_bytes]u8 = undefined;
        const timestamp = std.mem.readInt(u64, header[38..46], .little);
        hashRecordId(&expected_record_id, snapshot.store_id, sequence, timestamp, expected_payload_hash, event_id, provenance);
        if (!std.mem.eql(u8, header[78..110], &expected_record_id)) return error.SnapshotChanged;
        var expected_record_hash: [hash_bytes]u8 = undefined;
        hashRecordBytes(&expected_record_hash, &header, data[0 .. data.len - trailer_bytes]);
        if (!std.mem.eql(u8, header[record_hash_offset .. record_hash_offset + hash_bytes], &expected_record_hash)) return error.SnapshotChanged;
        const record = makeRecordView(source_store, header, event_id, provenance, payload, expected_payload_hash, expected_record_hash) catch return error.SnapshotChanged;
        if (sequence > after_sequence) try visitor(context, record);
        offset += frame_len;
        report.last_sequence = sequence;
        report.last_hash = expected_record_hash;
        report.record_count += 1;
        report.verified_end = offset;
        report.valid_prefix_end = offset;
    }
    if (target == snapshot.last_sequence and (offset != snapshot.end_offset or !std.mem.eql(u8, &report.last_hash, &snapshot.last_hash))) return error.SnapshotChanged;
    report.tail = .none;
    report.tail_offset = offset;
    return report;
}

fn makeRecordView(
    source_store: ?*Store,
    header: [header_bytes]u8,
    event_id: []const u8,
    provenance: []const u8,
    payload: []const u8,
    payload_hash: [hash_bytes]u8,
    record_hash: [hash_bytes]u8,
) !RecordView {
    var store_id: [hash_bytes]u8 = undefined;
    var record_id: [hash_bytes]u8 = undefined;
    var previous_hash: [hash_bytes]u8 = undefined;
    @memcpy(&store_id, header[46..78]);
    @memcpy(&record_id, header[78..110]);
    @memcpy(&previous_hash, header[110..142]);
    return .{
        .store_id = store_id,
        .record_id = record_id,
        .schema = std.mem.readInt(u16, header[8..10], .little),
        .kind = std.enums.fromInt(EventKind, std.mem.readInt(u16, header[10..12], .little)) orelse return error.UnknownKind,
        .global_sequence = std.mem.readInt(u64, header[30..38], .little),
        .timestamp = std.mem.readInt(u64, header[38..46], .little),
        .provenance = provenance,
        .settlement = std.enums.fromInt(Settlement, header[12]) orelse return error.InvalidSettlement,
        .event_id = event_id,
        .payload = payload,
        .previous_hash = previous_hash,
        .payload_hash = payload_hash,
        .record_hash = record_hash,
        .source_store = source_store,
    };
}

fn readExact(file: std.Io.File, io: std.Io, buffer: []u8, offset: u64) !usize {
    return file.readPositionalAll(io, buffer, offset);
}

fn newStoreIdentity(io: std.Io) [hash_bytes]u8 {
    var entropy: [hash_bytes]u8 = undefined;
    io.random(&entropy);
    var counter_bytes: [8]u8 = undefined;
    std.mem.writeInt(u64, &counter_bytes, identity_counter.fetchAdd(1, .monotonic), .little);
    var result: [hash_bytes]u8 = undefined;
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update("misul-store-v2\x00");
    hasher.update(&entropy);
    hasher.update(&counter_bytes);
    hasher.final(&result);
    return result;
}

fn hashRecordId(
    output: *[hash_bytes]u8,
    store_id: [hash_bytes]u8,
    sequence: u64,
    timestamp: u64,
    payload_hash: [hash_bytes]u8,
    event_id: []const u8,
    provenance: []const u8,
) void {
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update("misul-record-id-v1\x00");
    hasher.update(&store_id);
    var number: [8]u8 = undefined;
    std.mem.writeInt(u64, &number, sequence, .little);
    hasher.update(&number);
    std.mem.writeInt(u64, &number, timestamp, .little);
    hasher.update(&number);
    hasher.update(&payload_hash);
    hashLengthAndBytes(&hasher, event_id);
    hashLengthAndBytes(&hasher, provenance);
    hasher.final(output);
}

fn hashRecordBytes(
    output: *[hash_bytes]u8,
    header: []const u8,
    data: []const u8,
) void {
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update(header[0..record_hash_offset]);
    hasher.update(header[record_hash_offset + hash_bytes .. header_bytes]);
    hasher.update(data);
    hasher.final(output);
}

fn hashRecordParts(
    output: *[hash_bytes]u8,
    header: []const u8,
    event_id: []const u8,
    provenance: []const u8,
    payload: []const u8,
) void {
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update(header[0..record_hash_offset]);
    hasher.update(header[record_hash_offset + hash_bytes .. header_bytes]);
    hasher.update(event_id);
    hasher.update(provenance);
    hasher.update(payload);
    hasher.final(output);
}

fn hashLengthAndBytes(hasher: *std.crypto.hash.sha2.Sha256, bytes: []const u8) void {
    var length: [8]u8 = undefined;
    std.mem.writeInt(u64, &length, @intCast(bytes.len), .little);
    hasher.update(&length);
    hasher.update(bytes);
}

fn nowMillis(io: std.Io) u64 {
    const value = std.Io.Clock.real.now(io).toMilliseconds();
    return if (value < 0) 0 else @intCast(value);
}

fn expectPayload(context: *anyopaque, record: RecordView) !void {
    const state: *ReplayState = @ptrCast(@alignCast(context));
    state.count += 1;
    try state.sequences.append(state.allocator, record.global_sequence);
    try state.payloads.append(state.allocator, try state.allocator.dupe(u8, record.payload));
}

fn failReplayVisitor(_: *anyopaque, _: RecordView) !void {
    return error.ProjectionRejected;
}

fn recordOrderVisitor(context: *anyopaque, record: RecordView) !void {
    const state: *ReplayState = @ptrCast(@alignCast(context));
    try state.sequences.append(state.allocator, record.global_sequence);
}

fn noOpPrepare(_: *anyopaque, _: Event, _: Settlement) !void {}

fn alwaysValid(_: *anyopaque) bool {
    return true;
}

const PrepareProbe = struct {
    store: *Store,
    observed_writable: bool = false,
};

fn probePrepare(context: *anyopaque, _: Event, _: Settlement) !void {
    const probe: *PrepareProbe = @ptrCast(@alignCast(context));
    probe.observed_writable = probe.store.isWritable();
}

fn probeCommit(_: *anyopaque, _: RecordView) void {}

var projection_authority_a: u8 = 0;
var projection_authority_b: u8 = 0;

fn noOpProjectionApply(_: *anyopaque, _: RecordView) !void {}

fn recordOrderCommit(context: *anyopaque, record: RecordView) void {
    const state: *ReplayState = @ptrCast(@alignCast(context));
    state.sequences.appendAssumeCapacity(record.global_sequence);
}

const ReplayState = struct {
    allocator: std.mem.Allocator,
    count: usize = 0,
    sequences: std.ArrayList(u64) = .empty,
    payloads: std.ArrayList([]const u8) = .empty,

    fn deinit(self: *ReplayState) void {
        self.sequences.deinit(self.allocator);
        for (self.payloads.items) |payload| self.allocator.free(payload);
        self.payloads.deinit(self.allocator);
    }
};

const ConcurrentAppendContext = struct {
    store: *Store,
    projected: *ReplayState,
    failures: std.atomic.Value(u32) = .init(0),
};

fn concurrentAppendWorker(context: *ConcurrentAppendContext, worker: usize, count: usize) void {
    for (0..count) |index| {
        var event_id_buffer: [64]u8 = undefined;
        const event_id = std.fmt.bufPrint(&event_id_buffer, "writer-{d}-event-{d}", .{ worker, index }) catch unreachable;
        var payload_buffer: [64]u8 = undefined;
        const payload = std.fmt.bufPrint(&payload_buffer, "payload-{d}-{d}", .{ worker, index }) catch unreachable;
        var ref = context.store.appendPreparedAndProject(.{ .kind = .message_appended, .event_id = event_id, .payload = payload }, "runtime:concurrent", .succeeded, @ptrCast(context.projected), noOpPrepare, alwaysValid, recordOrderCommit) catch {
            _ = context.failures.fetchAdd(1, .monotonic);
            continue;
        };
        ref.deinit(std.heap.smp_allocator);
    }
}

test "append and replay retain hashes and leave committed prefix unchanged" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "events.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    var store = &opened.store;
    defer store.close();
    var first = try store.append(.{ .kind = .session_created, .event_id = "evt-1", .payload = "session" }, "user", .succeeded);
    defer first.deinit(allocator);
    const stat_before = try store.file.stat(std.testing.io);
    const prefix = try allocator.alloc(u8, @intCast(stat_before.size));
    defer allocator.free(prefix);
    _ = try store.file.readPositionalAll(std.testing.io, prefix, 0);
    var second = try store.append(.{ .kind = .message_appended, .event_id = "evt-2", .payload = "hello" }, "runtime", .pending);
    defer second.deinit(allocator);
    const prefix_after = try allocator.alloc(u8, prefix.len);
    defer allocator.free(prefix_after);
    _ = try store.file.readPositionalAll(std.testing.io, prefix_after, 0);
    try std.testing.expectEqualSlices(u8, prefix, prefix_after);
    try std.testing.expectEqual(@as(u64, 2), second.global_sequence);
    store.close();
    var reopened = try Store.open(allocator, std.testing.io, path, .{});
    defer reopened.close();
    try std.testing.expectEqual(@as(u64, 2), reopened.recovery.last_sequence);
    try std.testing.expect(reopened.recovery.writable);
    var state = ReplayState{ .allocator = allocator };
    defer state.deinit();
    _ = try reopened.store.replayWithContext(@ptrCast(&state), expectPayload);
    try std.testing.expectEqual(@as(usize, 2), state.count);
    try std.testing.expectEqualSlices(u8, "hello", state.payloads.items[1]);
}

test "schema one event kinds retain explicit wire numbers" {
    try std.testing.expectEqual(@as(u16, 0), @intFromEnum(EventKind.session_created));
    try std.testing.expectEqual(@as(u16, 1), @intFromEnum(EventKind.session_selection_changed));
    try std.testing.expectEqual(@as(u16, 2), @intFromEnum(EventKind.branch_created));
    try std.testing.expectEqual(@as(u16, 3), @intFromEnum(EventKind.branch_selected));
    try std.testing.expectEqual(@as(u16, 4), @intFromEnum(EventKind.message_appended));
    try std.testing.expectEqual(@as(u16, 5), @intFromEnum(EventKind.run_started));
    try std.testing.expectEqual(@as(u16, 6), @intFromEnum(EventKind.run_settled));
    try std.testing.expectEqual(@as(u16, 7), @intFromEnum(EventKind.tool_started));
    try std.testing.expectEqual(@as(u16, 8), @intFromEnum(EventKind.tool_finished));
    try std.testing.expectEqual(@as(u16, 9), @intFromEnum(EventKind.cancellation_requested));
    try std.testing.expectEqual(@as(u16, 10), @intFromEnum(EventKind.failure));
    try std.testing.expectEqual(@as(u16, 11), @intFromEnum(EventKind.compaction_epoch_committed));
    try std.testing.expectEqual(@as(u16, 12), @intFromEnum(EventKind.user_constraint_added));
    try std.testing.expectEqual(@as(u16, 13), @intFromEnum(EventKind.user_constraint_superseded));
    try std.testing.expectEqual(@as(u16, 14), @intFromEnum(EventKind.agent_adaptation_revised));
    try std.testing.expectEqual(@as(u16, 15), @intFromEnum(EventKind.agent_adaptation_rolled_back));
    try std.testing.expectEqual(@as(u16, 16), @intFromEnum(EventKind.task_created));
    try std.testing.expectEqual(@as(u16, 17), @intFromEnum(EventKind.task_relation_added));
    try std.testing.expectEqual(@as(u16, 18), @intFromEnum(EventKind.task_message_appended));
    try std.testing.expectEqual(@as(u16, 19), @intFromEnum(EventKind.task_lease_acquired));
    try std.testing.expectEqual(@as(u16, 20), @intFromEnum(EventKind.task_heartbeat));
    try std.testing.expectEqual(@as(u16, 21), @intFromEnum(EventKind.task_execution_started));
    try std.testing.expectEqual(@as(u16, 22), @intFromEnum(EventKind.task_state_changed));
    try std.testing.expectEqual(@as(u16, 23), @intFromEnum(EventKind.task_result_committed));
    try std.testing.expectEqual(@as(u16, 24), @intFromEnum(EventKind.task_result_collected));
    try std.testing.expectEqual(@as(u16, 25), @intFromEnum(EventKind.action_requested));
    try std.testing.expectEqual(@as(u16, 26), @intFromEnum(EventKind.action_decided));
    try std.testing.expectEqual(@as(u16, 27), @intFromEnum(EventKind.action_settled));
    try std.testing.expectEqual(@as(u16, 28), @intFromEnum(EventKind.plugin_state_changed));
    try std.testing.expectEqual(@as(u16, 29), @intFromEnum(EventKind.task_message_acknowledged));
    try std.testing.expectEqual(@as(u16, 30), @intFromEnum(EventKind.task_output_visible));
    try std.testing.expectEqual(@as(u16, 31), @intFromEnum(EventKind.action_dispatch_begun));
    try std.testing.expectEqual(@as(u16, 32), @intFromEnum(EventKind.runtime_epoch_started));
}

test "projection preparation runs before the durable writer" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "prepare-order.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var probe = PrepareProbe{ .store = &opened.store };
    var ref = try opened.store.appendPreparedAndProject(.{ .kind = .session_created, .event_id = "prepare-order", .payload = "payload" }, "test", .succeeded, @ptrCast(&probe), probePrepare, alwaysValid, probeCommit);
    defer ref.deinit(allocator);
    try std.testing.expect(probe.observed_writable);
}

test "projection cursor authority cannot be replaced or replayed after retirement" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "projection-authority.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var first = try opened.store.append(.{ .kind = .runtime_epoch_started, .event_id = "authority-1", .payload = "{}" }, "test", .succeeded);
    defer first.deinit(allocator);
    var second = try opened.store.append(.{ .kind = .runtime_epoch_started, .event_id = "authority-2", .payload = "{}" }, "test", .succeeded);
    defer second.deinit(allocator);
    var owner: u8 = 0;
    var cursor: ProjectionCursor = .unbound;
    try opened.store.applyCommittedToProjection(@ptrCast(&owner), &cursor, @ptrCast(&projection_authority_a), try first.view(), @ptrCast(&owner), noOpProjectionApply);
    try std.testing.expectError(error.ProjectionCursorMismatch, opened.store.applyCommittedToProjection(@ptrCast(&owner), &cursor, @ptrCast(&projection_authority_b), try second.view(), @ptrCast(&owner), noOpProjectionApply));
    try std.testing.expectError(error.ProjectionCursorMismatch, opened.store.retireProjectionCursor(@ptrCast(&owner), &cursor, @ptrCast(&projection_authority_b)));
    const stale = cursor;
    try opened.store.applyCommittedToProjection(@ptrCast(&owner), &cursor, @ptrCast(&projection_authority_a), try second.view(), @ptrCast(&owner), noOpProjectionApply);
    try opened.store.retireProjectionCursor(@ptrCast(&owner), &cursor, @ptrCast(&projection_authority_a));
    var copied_stale = stale;
    try std.testing.expectError(error.ProjectionCursorMismatch, opened.store.applyCommittedToProjection(@ptrCast(&owner), &copied_stale, @ptrCast(&projection_authority_a), try first.view(), @ptrCast(&owner), noOpProjectionApply));
}

test "prepared appends cannot cross store reservation ownership" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path_a = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "owner-a.bin" });
    defer allocator.free(path_a);
    const path_b = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "owner-b.bin" });
    defer allocator.free(path_b);
    var opened_a = try Store.open(allocator, std.testing.io, path_a, .{});
    defer opened_a.close();
    var opened_b = try Store.open(allocator, std.testing.io, path_b, .{});
    defer opened_b.close();
    var prepared = try opened_a.store.prepareAppend(.{ .kind = .message_appended, .event_id = "foreign", .payload = "payload" }, "test", .succeeded);
    try std.testing.expectError(error.PreparedStoreMismatch, opened_b.store.commitPrepared(&prepared));
    try std.testing.expectError(error.PreparedStoreMismatch, opened_b.store.discardPreparedChecked(&prepared));
    try std.testing.expectEqual(@as(usize, 0), opened_b.store.event_ids.items.len);
    try std.testing.expectEqual(@as(usize, 1), opened_a.store.reserved_event_slots);
    try opened_a.store.discardPreparedChecked(&prepared);
    try std.testing.expectEqual(@as(usize, 0), opened_a.store.reserved_event_slots);
}

test "prepared append capability has no caller-writable routing fields" {
    try std.testing.expect(!@hasField(PreparedAppend, "owner_store"));
    try std.testing.expect(!@hasField(PreparedAppend, "owner_generation"));
    try std.testing.expect(!@hasField(PreparedAppend, "reservation_id"));
    try std.testing.expect(!@hasField(PreparedAppend, "token"));
}

test "copied prepared appends are one-shot and deinit is harmless" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "prepared-copy.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var first = try opened.store.prepareAppend(.{ .kind = .message_appended, .event_id = "copy", .payload = "payload" }, "test", .succeeded);
    var second = first;
    var record = try opened.store.commitPrepared(&first);
    defer record.deinit(allocator);
    try std.testing.expectError(error.PreparedAppendConsumed, opened.store.commitPrepared(&second));
    try opened.store.discardPreparedChecked(&second);
    second.deinit();
    first.deinit();
    try std.testing.expectEqual(@as(usize, 0), opened.store.reserved_event_slots);
}

test "prepared payload patch is allocation-free and preserves record hashes" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "prepared-patch.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var prepared = try opened.store.prepareAppend(.{ .kind = .action_settled, .event_id = "patched", .payload = "before" }, "test", .succeeded);
    try opened.store.patchPreparedPayload(prepared, 0, "after ");
    try std.testing.expectError(error.PreparedPayloadBounds, opened.store.patchPreparedPayload(prepared, 5, "too-long"));
    var record = try opened.store.commitPrepared(&prepared);
    defer record.deinit(allocator);
    try std.testing.expectEqualStrings("after ", record.payload);
    try (try record.view()).verifyWire();
}

test "prepared capability cannot survive a Store reopen at the same address" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "same-address.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    var stale = try opened.store.prepareAppend(.{ .kind = .message_appended, .event_id = "stale", .payload = "payload" }, "test", .succeeded);
    opened.close();
    opened = try Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    try std.testing.expectError(error.PreparedStoreMismatch, opened.store.commitPrepared(&stale));
    stale.deinit();
    var current = try opened.store.prepareAppend(.{ .kind = .message_appended, .event_id = "current", .payload = "payload" }, "test", .succeeded);
    var record = try opened.store.commitPrepared(&current);
    defer record.deinit(allocator);
    try std.testing.expectEqualStrings("current", record.event_id);
}

test "partial tails at every framing region recover the exact valid prefix" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const base_path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "base.bin" });
    defer allocator.free(base_path);
    var base = try Store.open(allocator, std.testing.io, base_path, .{});
    var first = try base.store.append(.{ .kind = .message_appended, .payload = "prefix", .event_id = "evt-1" }, "test", .succeeded);
    defer first.deinit(allocator);
    var second = try base.store.append(.{ .kind = .message_appended, .payload = "committed suffix", .event_id = "evt-2" }, "test", .pending);
    defer second.deinit(allocator);
    const first_frame_len = header_bytes + "evt-1".len + "test".len + "prefix".len + trailer_bytes;
    const stat = try base.store.file.stat(std.testing.io);
    const full = try allocator.alloc(u8, @intCast(stat.size));
    defer allocator.free(full);
    _ = try base.store.file.readPositionalAll(std.testing.io, full, 0);
    base.close();
    var cut: usize = 1;
    while (cut < full.len) : (cut += 1) {
        const path = try std.fmt.allocPrint(allocator, "{s}.cut-{d}", .{ base_path, cut });
        defer allocator.free(path);
        try std.Io.Dir.cwd().writeFile(std.testing.io, .{ .sub_path = path, .data = full[0..cut] });
        var opened = try Store.open(allocator, std.testing.io, path, .{});
        const expected_sequence: u64 = if (cut >= first_frame_len) 1 else 0;
        const expected_prefix: u64 = if (cut >= first_frame_len) first_frame_len else 0;
        try std.testing.expectEqual(expected_sequence, opened.recovery.last_sequence);
        if (cut == first_frame_len) {
            try std.testing.expectEqual(TailCondition.none, opened.recovery.tail);
        } else {
            try std.testing.expect(opened.recovery.tail != .none);
        }
        try std.testing.expect(opened.recovery.writable);
        try std.testing.expectEqual(expected_prefix, opened.recovery.valid_prefix_end);
        try std.testing.expectEqual(expected_prefix, try opened.store.file.length(std.testing.io));
        opened.close();
    }
}

test "tampered committed payload refuses writable continuation" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "tampered.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    var record = try opened.store.append(.{ .kind = .message_appended, .event_id = "evt-safe", .payload = "safe" }, "test", .succeeded);
    defer record.deinit(allocator);
    const payload_offset = header_bytes;
    try opened.store.file.writePositionalAll(std.testing.io, "x", payload_offset);
    try opened.store.file.sync(std.testing.io);
    opened.close();
    var damaged = try Store.open(allocator, std.testing.io, path, .{});
    defer damaged.close();
    try std.testing.expect(!damaged.recovery.writable);
    try std.testing.expectError(error.StoreReadOnly, damaged.store.append(.{ .kind = .failure, .event_id = "evt-refuse", .payload = "must refuse" }, "test", .failed));
}

test "committed corruption and sequence faults preserve the verified prefix" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const base_path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "faults.bin" });
    defer allocator.free(base_path);

    var base = try Store.open(allocator, std.testing.io, base_path, .{});
    var first = try base.store.append(.{ .kind = .message_appended, .event_id = "first", .payload = "prefix" }, "test", .succeeded);
    defer first.deinit(allocator);
    var second = try base.store.append(.{ .kind = .message_appended, .event_id = "other", .payload = "suffix" }, "test", .pending);
    defer second.deinit(allocator);
    const first_frame_len = header_bytes + "first".len + "test".len + "prefix".len + trailer_bytes;
    const stat = try base.store.file.stat(std.testing.io);
    const full = try allocator.alloc(u8, @intCast(stat.size));
    defer allocator.free(full);
    _ = try base.store.file.readPositionalAll(std.testing.io, full, 0);
    base.close();

    const Fault = struct {
        tag: enum { malformed, unknown_schema, oversized, duplicate, event_id, gap, store_id, record_id, previous_hash, payload_hash, record_hash, settlement, kind, commit, provenance },
        expected: TailCondition,
        second: bool,
    };
    const faults = [_]Fault{
        .{ .tag = .malformed, .expected = .malformed_header, .second = false },
        .{ .tag = .unknown_schema, .expected = .unknown_schema, .second = false },
        .{ .tag = .oversized, .expected = .oversized_record, .second = false },
        .{ .tag = .duplicate, .expected = .duplicate_sequence, .second = true },
        .{ .tag = .event_id, .expected = .duplicate_event_id, .second = true },
        .{ .tag = .gap, .expected = .sequence_gap, .second = true },
        .{ .tag = .store_id, .expected = .store_id_mismatch, .second = true },
        .{ .tag = .record_id, .expected = .record_id_mismatch, .second = true },
        .{ .tag = .previous_hash, .expected = .previous_hash_mismatch, .second = true },
        .{ .tag = .payload_hash, .expected = .payload_hash_mismatch, .second = true },
        .{ .tag = .record_hash, .expected = .record_hash_mismatch, .second = true },
        .{ .tag = .settlement, .expected = .invalid_settlement, .second = true },
        .{ .tag = .kind, .expected = .unknown_kind, .second = true },
        .{ .tag = .commit, .expected = .missing_commit_marker, .second = true },
        .{ .tag = .provenance, .expected = .invalid_provenance, .second = true },
    };
    for (faults) |fault| {
        // Keep the original path so the path-derived store identity remains
        // valid; each case rewrites the closed fixture before reopening it.
        const path = base_path;
        const damaged = try allocator.dupe(u8, full);
        defer allocator.free(damaged);
        const offset = if (fault.second) first_frame_len else 0;
        switch (fault.tag) {
            .malformed => damaged[offset] ^= 1,
            .unknown_schema => std.mem.writeInt(u16, @ptrCast(&damaged[offset + 8]), current_schema + 1, .little),
            .oversized => std.mem.writeInt(u64, @ptrCast(&damaged[offset + 22]), @as(u64, max_payload_bytes + 1), .little),
            .duplicate => std.mem.writeInt(u64, @ptrCast(&damaged[offset + 30]), 1, .little),
            .event_id => {
                @memcpy(damaged[offset + header_bytes ..][0.."first".len], "first");
                var store_id: [hash_bytes]u8 = undefined;
                var payload_hash: [hash_bytes]u8 = undefined;
                var sequence_bytes: [8]u8 = undefined;
                var timestamp_bytes: [8]u8 = undefined;
                @memcpy(&store_id, damaged[offset + 46 .. offset + 78]);
                @memcpy(&payload_hash, damaged[offset + 142 .. offset + 174]);
                @memcpy(&sequence_bytes, damaged[offset + 30 .. offset + 38]);
                @memcpy(&timestamp_bytes, damaged[offset + 38 .. offset + 46]);
                var record_id: [hash_bytes]u8 = undefined;
                hashRecordId(&record_id, store_id, std.mem.readInt(u64, &sequence_bytes, .little), std.mem.readInt(u64, &timestamp_bytes, .little), payload_hash, damaged[offset + header_bytes ..][0.."first".len], damaged[offset + header_bytes + "first".len ..][0.."test".len]);
                @memcpy(damaged[offset + 78 .. offset + 110], &record_id);
                var record_hash: [hash_bytes]u8 = undefined;
                const second_frame_len = full.len - first_frame_len;
                hashRecordBytes(&record_hash, damaged[offset .. offset + header_bytes], damaged[offset + header_bytes .. offset + second_frame_len - trailer_bytes]);
                @memcpy(damaged[offset + record_hash_offset .. offset + record_hash_offset + hash_bytes], &record_hash);
            },
            .gap => std.mem.writeInt(u64, @ptrCast(&damaged[offset + 30]), 3, .little),
            .store_id => damaged[offset + 46] ^= 1,
            .record_id => damaged[offset + 78] ^= 1,
            .previous_hash => damaged[offset + 110] ^= 1,
            .payload_hash => damaged[offset + 142] ^= 1,
            .record_hash => damaged[offset + record_hash_offset] ^= 1,
            .settlement => damaged[offset + 12] = 0xff,
            .kind => std.mem.writeInt(u16, @ptrCast(&damaged[offset + 10]), 0xffff, .little),
            .commit => damaged[offset + (full.len - first_frame_len) - trailer_bytes] ^= 1,
            .provenance => std.mem.writeInt(u32, @ptrCast(&damaged[offset + 18]), 0, .little),
        }
        try std.Io.Dir.cwd().writeFile(std.testing.io, .{ .sub_path = path, .data = damaged });
        var opened = try Store.open(allocator, std.testing.io, path, .{});
        defer opened.close();
        try std.testing.expectEqual(fault.expected, opened.recovery.tail);
        try std.testing.expect(!opened.recovery.writable);
        const expected_prefix: u64 = if (fault.second) first_frame_len else 0;
        try std.testing.expectEqual(expected_prefix, opened.recovery.valid_prefix_end);
        try std.testing.expectEqual(expected_prefix, opened.recovery.verified_end);
        try std.testing.expectError(error.StoreReadOnly, opened.store.append(.{ .kind = .failure, .event_id = "evt-refuse", .payload = "refuse" }, "test", .failed));
    }
}

test "append allocation failures happen before the durable commit" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "oom.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();

    // open allocates the path once; append stages the returned event-id,
    // indexed id, provenance, payload, and frame before reserving an index slot.
    var fail_index: usize = 0;
    while (fail_index < 6) : (fail_index += 1) {
        var failing = std.testing.FailingAllocator.init(allocator, .{ .fail_index = fail_index });
        opened.store.allocator = failing.allocator();
        const result = opened.store.append(.{ .kind = .message_appended, .event_id = "evt", .payload = "payload" }, "test", .succeeded);
        opened.store.allocator = allocator;
        try std.testing.expectError(error.OutOfMemory, result);
        try std.testing.expect(opened.store.isWritable());
        try std.testing.expectEqual(@as(u64, 0), try opened.store.file.length(std.testing.io));
    }
    var record = try opened.store.append(.{ .kind = .message_appended, .event_id = "evt", .payload = "payload" }, "test", .succeeded);
    defer record.deinit(allocator);
    try std.testing.expectEqual(@as(u64, 1), record.global_sequence);
}

test "close fences a prepared append before any index access" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "close-race.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    var prepared = try opened.store.prepareAppend(.{ .kind = .message_appended, .event_id = "prepared", .payload = "payload" }, "test", .succeeded);
    opened.store.close();
    try std.testing.expectError(error.StoreReadOnly, opened.store.commitPrepared(&prepared));
    prepared.deinit();
}

test "stable event identifiers reject semantic retries after reopen" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "dedupe.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    var first = try opened.store.append(.{ .kind = .message_appended, .event_id = "stable-id", .payload = "once" }, "test", .succeeded);
    defer first.deinit(allocator);
    try std.testing.expect(opened.store.containsEventId("stable-id"));
    try std.testing.expectError(error.DuplicateEventId, opened.store.append(.{ .kind = .message_appended, .event_id = "stable-id", .payload = "retry" }, "test", .succeeded));
    opened.close();
    var reopened = try Store.open(allocator, std.testing.io, path, .{});
    defer reopened.close();
    try std.testing.expect(reopened.store.containsEventId("stable-id"));
    try std.testing.expectError(error.DuplicateEventId, reopened.store.append(.{ .kind = .message_appended, .event_id = "stable-id", .payload = "retry" }, "test", .succeeded));
}

test "existing bytes own store identity across moves and recreation gets a new identity" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "identity.bin" });
    defer allocator.free(path);
    const moved_path = try std.fmt.allocPrint(allocator, "{s}.moved", .{path});
    defer allocator.free(moved_path);

    var opened = try Store.open(allocator, std.testing.io, path, .{});
    var first = try opened.store.append(.{ .kind = .session_created, .event_id = "identity-1", .payload = "identity" }, "test", .succeeded);
    const original_id = first.store_id;
    const stat = try opened.store.file.stat(std.testing.io);
    const bytes = try allocator.alloc(u8, @intCast(stat.size));
    defer allocator.free(bytes);
    _ = try opened.store.file.readPositionalAll(std.testing.io, bytes, 0);
    opened.close();
    defer first.deinit(allocator);

    try std.Io.Dir.cwd().writeFile(std.testing.io, .{ .sub_path = moved_path, .data = bytes });
    var moved = try Store.open(allocator, std.testing.io, moved_path, .{});
    try std.testing.expectEqual(original_id, moved.store.store_id);
    var moved_record = try moved.store.append(.{ .kind = .message_appended, .event_id = "identity-2", .payload = "moved" }, "test", .pending);
    defer moved_record.deinit(allocator);
    moved.close();

    try std.Io.Dir.deleteFileAbsolute(std.testing.io, path);
    var recreated = try Store.open(allocator, std.testing.io, path, .{});
    defer recreated.close();
    var fresh = try recreated.store.append(.{ .kind = .session_created, .event_id = "identity-new", .payload = "new" }, "test", .succeeded);
    defer fresh.deinit(allocator);
    try std.testing.expect(!std.mem.eql(u8, &original_id, &fresh.store_id));
}

test "visitor failure closes the write path without overwriting a verified record" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "visitor.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var first = try opened.store.append(.{ .kind = .message_appended, .event_id = "visitor-1", .payload = "verified" }, "test", .succeeded);
    defer first.deinit(allocator);
    const size_before = try opened.store.file.length(std.testing.io);
    var context: u8 = 0;
    try std.testing.expectError(error.ProjectionRejected, opened.store.replayWithContext(@ptrCast(&context), failReplayVisitor));
    try std.testing.expect(!opened.store.isWritable());
    try std.testing.expectEqual(size_before, try opened.store.file.length(std.testing.io));
    try std.testing.expectError(error.StoreReadOnly, opened.store.append(.{ .kind = .failure, .event_id = "evt-overwrite", .payload = "overwrite" }, "test", .failed));
}

test "replay handles a valid log larger than sixty four MiB incrementally" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "large.bin" });
    defer allocator.free(path);
    const payload = try allocator.alloc(u8, 13 * 1024 * 1024);
    defer allocator.free(payload);
    @memset(payload, 'x');

    var opened = try Store.open(allocator, std.testing.io, path, .{});
    var index: usize = 0;
    while (index < 5) : (index += 1) {
        var event_id_buffer: [32]u8 = undefined;
        const event_id = std.fmt.bufPrint(&event_id_buffer, "large-{d}", .{index}) catch unreachable;
        var record = try opened.store.append(.{ .kind = .message_appended, .event_id = event_id, .payload = payload, .timestamp = @intCast(index + 1) }, "test", .succeeded);
        record.deinit(allocator);
    }
    const stat = try opened.store.file.stat(std.testing.io);
    try std.testing.expect(stat.size > 64 * 1024 * 1024);
    opened.close();

    var replayed = try Store.open(allocator, std.testing.io, path, .{});
    defer replayed.close();
    try std.testing.expectEqual(@as(u64, 5), replayed.recovery.record_count);
    try std.testing.expectEqual(TailCondition.none, replayed.recovery.tail);
}

test "concurrent append serializes contiguous hash-chain order" {
    const path_allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(path_allocator, &.{ path_buffer[0..path_len], "concurrent.bin" });
    defer path_allocator.free(path);
    var opened = try Store.open(std.heap.smp_allocator, std.testing.io, path, .{});
    defer opened.close();
    var projected = ReplayState{ .allocator = std.heap.smp_allocator };
    defer projected.deinit();
    try projected.sequences.ensureTotalCapacity(std.heap.smp_allocator, 8 * 32);
    var context = ConcurrentAppendContext{ .store = &opened.store, .projected = &projected };
    const worker_count = 8;
    const events_per_worker = 32;
    var threads: [worker_count]std.Thread = undefined;
    for (&threads, 0..) |*thread, worker| {
        thread.* = try std.Thread.spawn(.{}, concurrentAppendWorker, .{ &context, worker, events_per_worker });
    }
    for (threads) |thread| thread.join();
    try std.testing.expectEqual(@as(u32, 0), context.failures.load(.acquire));
    try std.testing.expectEqual(@as(usize, worker_count * events_per_worker), projected.sequences.items.len);
    for (projected.sequences.items, 0..) |sequence, index| {
        try std.testing.expectEqual(@as(u64, index + 1), sequence);
    }

    var replayed = ReplayState{ .allocator = std.heap.smp_allocator };
    defer replayed.deinit();
    _ = try opened.store.replayWithContext(@ptrCast(&replayed), expectPayload);
    try std.testing.expectEqual(@as(usize, worker_count * events_per_worker), replayed.count);
    for (replayed.sequences.items, 0..) |sequence, index| {
        try std.testing.expectEqual(@as(u64, index + 1), sequence);
    }
}

const RangeVisitContext = struct {
    store: *Store,
    count: usize = 0,
};

fn appendDuringRangeVisit(context: *anyopaque, _: RecordView) !void {
    const state: *RangeVisitContext = @ptrCast(@alignCast(context));
    _ = state.store.isWritable();
    state.count += 1;
    if (state.count == 1) {
        var ref = try state.store.append(.{ .kind = .task_heartbeat, .event_id = "range-4", .payload = "during" }, "range", .succeeded);
        ref.deinit(state.store.allocator);
    }
}

test "range replay snapshots committed high water without holding writer" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "range.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    inline for (.{ "range-1", "range-2", "range-3" }) |event_id| {
        var ref = try opened.store.append(.{ .kind = .message_appended, .event_id = event_id, .payload = "prefix" }, "range", .succeeded);
        ref.deinit(allocator);
    }
    var context = RangeVisitContext{ .store = &opened.store };
    const report = try opened.store.visitCommittedFrom(0, 3, @ptrCast(&context), appendDuringRangeVisit);
    try std.testing.expectEqual(@as(usize, 3), context.count);
    try std.testing.expectEqual(@as(u64, 3), report.last_sequence);
    try std.testing.expectEqual(@as(u64, 3), report.record_count);
    try std.testing.expect(opened.store.containsEventId("range-4"));

    // A reconnect already caught up to the snapshot still verifies the full
    // prefix; it merely delivers no duplicate callback.
    const caught_up = try opened.store.visitCommittedFrom(3, 3, @ptrCast(&context), appendDuringRangeVisit);
    try std.testing.expectEqual(@as(usize, 3), context.count);
    try std.testing.expectEqual(@as(u64, 3), caught_up.last_sequence);
    try std.testing.expectEqual(@as(u64, 3), caught_up.record_count);
}

const CloseRangeContext = struct {
    store: *Store,
    entered: *std.atomic.Value(bool),
    release: *std.atomic.Value(bool),
    count: *std.atomic.Value(usize),
};

fn closeRangeVisitor(context: *anyopaque, _: RecordView) !void {
    const state: *CloseRangeContext = @ptrCast(@alignCast(context));
    _ = state.count.fetchAdd(1, .acq_rel);
    state.entered.store(true, .release);
    while (!state.release.load(.acquire)) std.atomic.spinLoopHint();
}

const CloseRangeResult = struct {
    context: *CloseRangeContext,
    report: ?RecoveryReport = null,
    failure: ?anyerror = null,
};

fn closeRangeWorker(result: *CloseRangeResult) void {
    result.report = result.context.store.visitCommittedFrom(0, 2, @ptrCast(result.context), closeRangeVisitor) catch |err| {
        result.failure = err;
        return;
    };
}

test "range replay remains valid when the owning store closes after snapshot" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "range-close.bin" });
    defer allocator.free(path);
    var opened = try Store.open(allocator, std.testing.io, path, .{});
    inline for (.{ "close-range-1", "close-range-2" }) |event_id| {
        var record = try opened.store.append(.{ .kind = .message_appended, .event_id = event_id, .payload = "prefix" }, "range", .succeeded);
        record.deinit(allocator);
    }
    var entered: std.atomic.Value(bool) = .init(false);
    var release: std.atomic.Value(bool) = .init(false);
    var count: std.atomic.Value(usize) = .init(0);
    var context = CloseRangeContext{ .store = &opened.store, .entered = &entered, .release = &release, .count = &count };
    var result = CloseRangeResult{ .context = &context };
    var worker = try std.Thread.spawn(.{}, closeRangeWorker, .{&result});
    while (!entered.load(.acquire)) std.atomic.spinLoopHint();
    opened.store.close();
    release.store(true, .release);
    worker.join();
    if (result.failure) |err| return err;
    try std.testing.expectEqual(@as(usize, 2), count.load(.acquire));
    try std.testing.expectEqual(@as(u64, 2), result.report.?.last_sequence);
    try std.testing.expectError(error.StoreClosed, opened.store.visitCommittedFrom(0, 2, @ptrCast(&context), closeRangeVisitor));
}
