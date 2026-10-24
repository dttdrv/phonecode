const std = @import("std");

pub const plugin_manifest = @import("plugin_manifest.zig");
pub const cordis = @import("cordis.zig");
pub const event_store = @import("event_store.zig");

pub const schema_version: u16 = 1;
pub const max_effects_per_plugin: usize = 64;
pub const max_failure_bytes: usize = 128;
pub const max_lifecycle_payload_bytes: usize = 2 * 1024 * 1024;

pub const LifecycleAction = enum {
    enable,
    disable,
    upgrade,
    reload,
    rollback,
};

pub const EventPhase = enum {
    desired,
    effect_applied,
    active,
    failed,
    rolled_back,
};

pub const EffectKey = struct {
    generation: u64,
    plugin_identity: []const u8,
    effect_id: []const u8,
};

pub const ApplyEffectFn = *const fn (*anyopaque, EffectKey) anyerror!void;
pub const RemoveEffectFn = *const fn (*anyopaque, EffectKey) void;

pub const NativeEffect = struct {
    /// Both callbacks must be idempotent for this generation-keyed identity.
    id: []const u8,
    apply: ApplyEffectFn,
    remove: RemoveEffectFn,
};

pub const PlanDeinitFn = *const fn (std.mem.Allocator, *anyopaque) void;

pub const StagedNativePlugin = struct {
    state: *anyopaque,
    cordis_plugin: cordis.Plugin,
    effects: []const NativeEffect,
    deinit: PlanDeinitFn,
};

pub const StageFn = *const fn (
    context: *anyopaque,
    allocator: std.mem.Allocator,
    manifest: *const plugin_manifest.Manifest,
    generation: u64,
) anyerror!StagedNativePlugin;

/// A statically registered trusted native factory. `stage` builds owned private
/// state and must not install services, tools, processes, or external effects.
pub const NativeFactory = struct {
    entry_point: []const u8,
    implementation_id: []const u8,
    context: *anyopaque,
    stage: StageFn,
};

pub const GenerationSnapshot = struct {
    generation: u64,
    previous_generation: u64,
    target_generation: u64,
    action: LifecycleAction,
    phase: EventPhase,
    plugin_count: usize,
    graph_digest: plugin_manifest.Digest,
};

pub const LifecycleRecord = struct {
    generation: u64,
    previous_generation: u64,
    action: LifecycleAction,
    phase: EventPhase,
    graph_digest: plugin_manifest.Digest,
    target_generation: u64,
    plugin_count: usize,
    ever_active: bool,
    failure: [max_failure_bytes]u8 = @splat(0),
    failure_len: usize = 0,

    pub fn failureCode(self: *const LifecycleRecord) []const u8 {
        return self.failure[0..self.failure_len];
    }
};

const EffectState = struct {
    plugin_index: usize,
    effect_index: usize,
    applied: bool = false,
    reconciled: bool = false,
};

const Generation = struct {
    allocator: std.mem.Allocator,
    number: u64,
    previous_generation: u64,
    target_generation: u64,
    action: LifecycleAction,
    phase: EventPhase = .desired,
    manifests: []plugin_manifest.OwnedManifest,
    manifest_views: []plugin_manifest.Manifest,
    graph: plugin_manifest.ValidatedGraph,
    digest: plugin_manifest.Digest,
    plans: []StagedNativePlugin,
    initialized_plans: usize,
    effects: []EffectState,
    context: cordis.Context,
    context_live: bool = true,
    activated: bool = false,
    rollback_complete: bool = false,
    callback_in_progress: bool = false,
    leases: usize = 0,
    retired: bool = false,
    next_retired: ?*Generation = null,

    fn snapshot(self: *const Generation) GenerationSnapshot {
        return .{
            .generation = self.number,
            .previous_generation = self.previous_generation,
            .target_generation = self.target_generation,
            .action = self.action,
            .phase = self.phase,
            .plugin_count = self.manifest_views.len,
            .graph_digest = self.digest,
        };
    }

    fn effectKey(self: *const Generation, state: EffectState) EffectKey {
        const plan = self.plans[state.plugin_index];
        return .{
            .generation = self.number,
            .plugin_identity = self.manifest_views[state.plugin_index].identity,
            .effect_id = plan.effects[state.effect_index].id,
        };
    }

    fn findEffect(self: *Generation, key: EffectKey) ?usize {
        if (key.generation != self.number) return null;
        for (self.effects, 0..) |state, index| {
            const expected = self.effectKey(state);
            if (std.mem.eql(u8, expected.plugin_identity, key.plugin_identity) and std.mem.eql(u8, expected.effect_id, key.effect_id)) return index;
        }
        return null;
    }

    fn firstIncompleteEffect(self: *const Generation) ?usize {
        for (self.effects, 0..) |state, index| if (!state.applied or !state.reconciled) return index;
        return null;
    }

    fn firstUnappliedEffect(self: *const Generation) ?usize {
        for (self.effects, 0..) |state, index| if (!state.applied) return index;
        return null;
    }

    fn activate(self: *Generation) !void {
        if (self.activated) return;
        for (self.effects) |effect| if (!effect.applied or !effect.reconciled) return error.PluginEffectsIncomplete;
        const plugins = try self.allocator.alloc(cordis.Plugin, self.graph.activation_order.len);
        defer self.allocator.free(plugins);
        for (self.graph.activation_order, 0..) |manifest_index, index| plugins[index] = self.plans[manifest_index].cordis_plugin;
        try self.context.reconcile(plugins);
        self.activated = true;
    }

    fn rollBack(self: *Generation) void {
        if (self.context_live) {
            self.context.deinit();
            self.context_live = false;
            self.activated = false;
        }
        var index = self.effects.len;
        while (index > 0) {
            index -= 1;
            const effect = &self.effects[index];
            if (!effect.applied and !effect.reconciled) continue;
            const plan = self.plans[effect.plugin_index];
            plan.effects[effect.effect_index].remove(plan.state, self.effectKey(effect.*));
            effect.applied = false;
            effect.reconciled = false;
        }
        self.rollback_complete = true;
    }

    fn destroy(self: *Generation) void {
        self.rollBack();
        var plan_index = self.initialized_plans;
        while (plan_index > 0) {
            plan_index -= 1;
            const manifest_index = self.graph.activation_order[plan_index];
            self.plans[manifest_index].deinit(self.allocator, self.plans[manifest_index].state);
        }
        self.allocator.free(self.effects);
        self.allocator.free(self.plans);
        self.graph.deinit(self.allocator);
        self.allocator.free(self.manifest_views);
        for (self.manifests) |*manifest| manifest.deinit();
        self.allocator.free(self.manifests);
        const allocator = self.allocator;
        allocator.destroy(self);
    }
};

const HistoryEntry = struct {
    generation: u64,
    previous_generation: u64,
    action: LifecycleAction,
    phase: EventPhase,
    graph_digest: plugin_manifest.Digest,
    target_generation: u64,
    plugin_count: usize,
    ever_active: bool = false,
    failure: [max_failure_bytes]u8 = @splat(0),
    failure_len: usize = 0,
};

const WireLifecycle = struct {
    schema_version: u16,
    phase: EventPhase,
    action: LifecycleAction,
    generation: u64,
    previous_generation: u64,
    target_generation: u64,
    graph_sha256: []const u8,
    effect_plugin: []const u8,
    effect_id: []const u8,
    failure: []const u8,
    manifests: []const []const u8,
};

pub const PreparedEvent = struct {
    allocator: std.mem.Allocator,
    owner: *Registry,
    payload_bytes: []u8,
    settlement_value: event_store.Settlement,
    expected_revision: u64,
    phase: EventPhase,
    generation: u64,
    effect_index: ?usize = null,
    replayed_effect: bool = false,
    candidate: ?*Generation = null,
    committed: bool = false,
    reservation_active: bool = false,
    failure: [max_failure_bytes]u8 = @splat(0),
    failure_len: usize = 0,

    pub fn event(self: *const PreparedEvent, event_id: []const u8) event_store.Event {
        return .{ .kind = .plugin_state_changed, .event_id = event_id, .payload = self.payload_bytes };
    }

    pub fn settlement(self: *const PreparedEvent) event_store.Settlement {
        return self.settlement_value;
    }

    pub fn payload(self: *const PreparedEvent) []const u8 {
        return self.payload_bytes;
    }

    pub fn deinit(self: *PreparedEvent) void {
        if (self.reservation_active) self.owner.releasePreparedReservation(self);
        if (self.candidate) |candidate| candidate.destroy();
        self.allocator.free(self.payload_bytes);
        self.* = undefined;
    }
};

pub const DurableAppend = struct {
    registry: *Registry,
    prepared: *PreparedEvent,

    pub fn prepare(context: *anyopaque, event: event_store.Event, settlement: event_store.Settlement) !void {
        const append: *DurableAppend = @ptrCast(@alignCast(context));
        const prepared = append.prepared;
        if (prepared.owner != append.registry or prepared.committed or event.kind != .plugin_state_changed or
            settlement != prepared.settlement_value or !std.mem.eql(u8, event.payload, prepared.payload_bytes)) return error.PreparedPluginEventMismatch;
    }

    pub fn validate(context: *anyopaque) bool {
        const append: *DurableAppend = @ptrCast(@alignCast(context));
        return append.registry.reservePrepared(append.prepared);
    }

    pub fn commit(context: *anyopaque, record: event_store.RecordView) void {
        const append: *DurableAppend = @ptrCast(@alignCast(context));
        append.registry.commitPreparedTrusted(append.prepared, record) catch unreachable;
    }
};

pub const Lease = struct {
    registry: ?*Registry,
    generation: ?*Generation,

    pub fn snapshot(self: *const Lease) GenerationSnapshot {
        return self.generation.?.snapshot();
    }

    /// Borrowed views remain valid only until this lease is released.
    pub fn manifests(self: *const Lease) []const plugin_manifest.Manifest {
        return self.generation.?.manifest_views;
    }

    pub fn deinit(self: *Lease) void {
        const registry = self.registry orelse return;
        const generation = self.generation.?;
        self.registry = null;
        self.generation = null;
        registry.releaseLease(generation);
    }
};

pub const Registry = struct {
    allocator: std.mem.Allocator,
    parent_context: *cordis.Context,
    factories: []const NativeFactory,
    validation: plugin_manifest.ValidationContext,
    active: ?*Generation = null,
    staged: ?*Generation = null,
    retired: ?*Generation = null,
    history: std.ArrayList(HistoryEntry) = .empty,
    next_generation: u64 = 1,
    revision: u64 = 0,
    staging_in_progress: bool = false,
    reserved_prepared: ?*PreparedEvent = null,
    projection_mutex: std.atomic.Mutex = .unlocked,

    /// `parent_context`, `factories`, and validation-owned slices must outlive
    /// the registry. Runtime must serialize every DurableAppend for this registry.
    pub fn init(
        allocator: std.mem.Allocator,
        parent_context: *cordis.Context,
        factories: []const NativeFactory,
        validation: plugin_manifest.ValidationContext,
    ) Registry {
        return .{
            .allocator = allocator,
            .parent_context = parent_context,
            .factories = factories,
            .validation = validation,
        };
    }

    fn lock(self: *Registry) void {
        while (!self.projection_mutex.tryLock()) std.atomic.spinLoopHint();
    }

    fn unlock(self: *Registry) void {
        self.projection_mutex.unlock();
    }

    pub fn deinit(self: *Registry) void {
        self.lock();
        std.debug.assert(!self.staging_in_progress);
        std.debug.assert(self.reserved_prepared == null);
        const active = self.active;
        const staged = self.staged;
        const retired = self.retired;
        if (active) |generation| std.debug.assert(!generation.callback_in_progress);
        if (staged) |generation| std.debug.assert(!generation.callback_in_progress);
        self.active = null;
        self.staged = null;
        self.retired = null;
        self.history.deinit(self.allocator);
        self.unlock();
        if (staged) |generation| std.debug.assert(generation.leases == 0);
        if (active) |generation| std.debug.assert(generation.leases == 0);
        if (staged) |generation| generation.destroy();
        if (active) |generation| generation.destroy();
        var cursor = retired;
        while (cursor) |generation| {
            const next = generation.next_retired;
            std.debug.assert(generation.leases == 0);
            generation.destroy();
            cursor = next;
        }
        self.* = undefined;
    }

    pub fn prepareDesired(self: *Registry, action: LifecycleAction, target_generation: u64, manifests: []const plugin_manifest.Manifest) !PreparedEvent {
        const reservation = blk: {
            self.lock();
            defer self.unlock();
            if (self.staged != null or self.staging_in_progress) return error.PluginReconcileInProgress;
            if (self.next_generation == std.math.maxInt(u64)) return error.PluginGenerationExhausted;
            try self.validateAction(action, target_generation, manifests);
            try self.history.ensureUnusedCapacity(self.allocator, 1);
            self.staging_in_progress = true;
            break :blk .{
                .generation = self.next_generation,
                .previous_generation = if (self.active) |active| active.number else 0,
                .revision = self.revision,
            };
        };
        defer {
            self.lock();
            std.debug.assert(self.staging_in_progress);
            self.staging_in_progress = false;
            self.unlock();
        }
        const candidate = try buildGeneration(
            self.allocator,
            self.parent_context,
            self.factories,
            self.validation,
            reservation.generation,
            reservation.previous_generation,
            target_generation,
            action,
            manifests,
        );
        errdefer candidate.destroy();
        self.lock();
        const current = self.staged == null and self.next_generation == reservation.generation and self.revision == reservation.revision and
            (if (self.active) |active| active.number else 0) == reservation.previous_generation;
        self.unlock();
        if (!current) return error.PluginProjectionChangedDuringStage;
        const payload = try encodeLifecyclePayload(self.allocator, candidate, .desired, null, "");
        return .{
            .allocator = self.allocator,
            .owner = self,
            .payload_bytes = payload,
            .settlement_value = .succeeded,
            .expected_revision = reservation.revision,
            .phase = .desired,
            .generation = candidate.number,
            .candidate = candidate,
        };
    }

    pub fn durableAppend(self: *Registry, prepared: *PreparedEvent) DurableAppend {
        return .{ .registry = self, .prepared = prepared };
    }

    pub fn prepareEffectApplied(self: *Registry, key: EffectKey) !PreparedEvent {
        return self.prepareEffectAppliedInternal(key, false);
    }

    fn prepareEffectAppliedInternal(self: *Registry, key: EffectKey, replaying: bool) !PreparedEvent {
        self.lock();
        defer self.unlock();
        const generation = self.staged orelse return error.NoStagedPluginGeneration;
        if (!canReconcile(generation.phase) or generation.callback_in_progress) return error.PluginGenerationNotReconcilable;
        const effect_index = generation.findEffect(key) orelse return error.UnknownPluginEffect;
        if (generation.effects[effect_index].applied) return error.PluginEffectAlreadyRecorded;
        if (generation.firstUnappliedEffect() != effect_index) return error.PluginEffectOutOfOrder;
        if (!replaying and !generation.effects[effect_index].reconciled) return error.PluginEffectNotReconciled;
        const payload = try encodeLifecyclePayload(self.allocator, generation, .effect_applied, key, "");
        return .{
            .allocator = self.allocator,
            .owner = self,
            .payload_bytes = payload,
            .settlement_value = .succeeded,
            .expected_revision = self.revision,
            .phase = .effect_applied,
            .generation = generation.number,
            .effect_index = effect_index,
            .replayed_effect = replaying,
        };
    }

    pub fn prepareActive(self: *Registry, generation_number: u64) !PreparedEvent {
        self.lock();
        defer self.unlock();
        const generation = self.staged orelse return error.NoStagedPluginGeneration;
        if (generation.number != generation_number) return error.StalePluginGeneration;
        if (!canReconcile(generation.phase) or generation.callback_in_progress) return error.PluginGenerationNotReconcilable;
        if (!generation.activated) return error.PluginGenerationNotActivated;
        const payload = try encodeLifecyclePayload(self.allocator, generation, .active, null, "");
        return .{
            .allocator = self.allocator,
            .owner = self,
            .payload_bytes = payload,
            .settlement_value = .succeeded,
            .expected_revision = self.revision,
            .phase = .active,
            .generation = generation.number,
        };
    }

    pub fn prepareFailed(self: *Registry, generation_number: u64, failure: []const u8) !PreparedEvent {
        try validateFailure(failure);
        self.lock();
        defer self.unlock();
        const generation = self.staged orelse return error.NoStagedPluginGeneration;
        if (generation.number != generation_number) return error.StalePluginGeneration;
        if (!canReconcile(generation.phase) or generation.callback_in_progress) return error.PluginGenerationNotReconcilable;
        if (generation.activated) return error.PluginGenerationAlreadyActivated;
        const payload = try encodeLifecyclePayload(self.allocator, generation, .failed, null, failure);
        var prepared = PreparedEvent{
            .allocator = self.allocator,
            .owner = self,
            .payload_bytes = payload,
            .settlement_value = .failed,
            .expected_revision = self.revision,
            .phase = .failed,
            .generation = generation.number,
        };
        @memcpy(prepared.failure[0..failure.len], failure);
        prepared.failure_len = failure.len;
        return prepared;
    }

    pub fn rollbackStaged(self: *Registry, generation_number: u64) void {
        self.lock();
        if (self.reserved_prepared != null) {
            self.unlock();
            return;
        }
        const generation = self.staged orelse {
            self.unlock();
            return;
        };
        if (generation.number != generation_number or generation.phase != .failed or generation.callback_in_progress) {
            self.unlock();
            return;
        }
        generation.callback_in_progress = true;
        self.unlock();
        generation.rollBack();
        self.lock();
        std.debug.assert(self.staged == generation and generation.callback_in_progress);
        generation.callback_in_progress = false;
        self.unlock();
    }

    pub fn prepareRolledBack(self: *Registry, generation_number: u64) !PreparedEvent {
        self.lock();
        defer self.unlock();
        const generation = self.staged orelse return error.NoStagedPluginGeneration;
        if (generation.number != generation_number) return error.StalePluginGeneration;
        if (generation.callback_in_progress) return error.PluginCallbackInProgress;
        if (generation.phase != .failed or !generation.rollback_complete) return error.PluginRollbackIncomplete;
        const payload = try encodeLifecyclePayload(self.allocator, generation, .rolled_back, null, "");
        return .{
            .allocator = self.allocator,
            .owner = self,
            .payload_bytes = payload,
            .settlement_value = .succeeded,
            .expected_revision = self.revision,
            .phase = .rolled_back,
            .generation = generation.number,
        };
    }

    pub fn nextEffect(self: *Registry, generation_number: u64) ?EffectKey {
        self.lock();
        defer self.unlock();
        const generation = self.staged orelse return null;
        if (generation.number != generation_number or !canReconcile(generation.phase) or generation.callback_in_progress) return null;
        const index = generation.firstIncompleteEffect() orelse return null;
        return generation.effectKey(generation.effects[index]);
    }

    pub fn effectReceiptRequired(self: *Registry, key: EffectKey) bool {
        self.lock();
        defer self.unlock();
        const generation = self.staged orelse return false;
        if (!canReconcile(generation.phase) or generation.callback_in_progress) return false;
        const index = generation.findEffect(key) orelse return false;
        return !generation.effects[index].applied;
    }

    pub fn executeEffect(self: *Registry, key: EffectKey) !void {
        self.lock();
        if (self.reserved_prepared != null) {
            self.unlock();
            return error.PluginAppendInProgress;
        }
        const generation = self.staged orelse {
            self.unlock();
            return error.NoStagedPluginGeneration;
        };
        if (!canReconcile(generation.phase)) {
            self.unlock();
            return error.PluginGenerationNotReconcilable;
        }
        if (generation.callback_in_progress) {
            self.unlock();
            return error.PluginCallbackInProgress;
        }
        const index = generation.findEffect(key) orelse {
            self.unlock();
            return error.UnknownPluginEffect;
        };
        if (generation.effects[index].reconciled) {
            self.unlock();
            return;
        }
        if (generation.firstIncompleteEffect() != index) {
            self.unlock();
            return error.PluginEffectOutOfOrder;
        }
        const state = generation.effects[index];
        const plan = generation.plans[state.plugin_index];
        const exact_key = generation.effectKey(state);
        generation.callback_in_progress = true;
        self.unlock();

        plan.effects[state.effect_index].apply(plan.state, exact_key) catch |err| {
            plan.effects[state.effect_index].remove(plan.state, exact_key);
            self.lock();
            std.debug.assert(self.staged == generation and generation.callback_in_progress);
            generation.callback_in_progress = false;
            self.unlock();
            return err;
        };

        self.lock();
        defer self.unlock();
        std.debug.assert(self.staged == generation and generation.callback_in_progress);
        generation.effects[index].reconciled = true;
        generation.callback_in_progress = false;
    }

    pub fn activateStaged(self: *Registry, generation_number: u64) !void {
        self.lock();
        if (self.reserved_prepared != null) {
            self.unlock();
            return error.PluginAppendInProgress;
        }
        const generation = self.staged orelse {
            self.unlock();
            return error.NoStagedPluginGeneration;
        };
        if (generation.number != generation_number) {
            self.unlock();
            return error.StalePluginGeneration;
        }
        if (!canReconcile(generation.phase)) {
            self.unlock();
            return error.PluginGenerationNotReconcilable;
        }
        if (generation.callback_in_progress) {
            self.unlock();
            return error.PluginCallbackInProgress;
        }
        generation.callback_in_progress = true;
        self.unlock();

        const result = generation.activate();
        self.lock();
        std.debug.assert(self.staged == generation and generation.callback_in_progress);
        generation.callback_in_progress = false;
        self.unlock();
        return result;
    }

    pub fn commitPrepared(self: *Registry, prepared: *PreparedEvent, record: event_store.RecordView) !void {
        try record.verifyWire();
        return self.commitPreparedTrusted(prepared, record);
    }

    fn commitPreparedTrusted(self: *Registry, prepared: *PreparedEvent, record: event_store.RecordView) !void {
        if (prepared.owner != self) return error.PreparedPluginRegistryMismatch;
        if (prepared.committed) return error.PreparedPluginEventConsumed;
        if (record.kind != .plugin_state_changed or record.settlement != prepared.settlement_value or !std.mem.eql(u8, record.payload, prepared.payload_bytes)) return error.PreparedPluginEventMismatch;
        self.lock();
        defer self.unlock();
        if (self.reserved_prepared) |reserved| if (reserved != prepared) return error.PreparedPluginEventStale;
        if (prepared.expected_revision != self.revision) return error.PreparedPluginEventStale;
        switch (prepared.phase) {
            .desired => {
                if (self.staged != null or prepared.candidate == null) return error.PreparedPluginEventStale;
                const candidate = prepared.candidate.?;
                if (candidate.number != self.next_generation) return error.PreparedPluginEventStale;
                self.staged = candidate;
                prepared.candidate = null;
                self.history.appendAssumeCapacity(.{
                    .generation = candidate.number,
                    .previous_generation = candidate.previous_generation,
                    .action = candidate.action,
                    .phase = .desired,
                    .graph_digest = candidate.digest,
                    .target_generation = candidate.target_generation,
                    .plugin_count = candidate.manifest_views.len,
                });
                self.next_generation += 1;
            },
            .effect_applied => {
                const generation = self.staged orelse return error.PreparedPluginEventStale;
                if (generation.number != prepared.generation or !canReconcile(generation.phase) or generation.callback_in_progress) return error.PreparedPluginEventStale;
                const effect_index = prepared.effect_index.?;
                if (generation.effects[effect_index].applied or generation.firstUnappliedEffect() != effect_index or (!prepared.replayed_effect and !generation.effects[effect_index].reconciled)) return error.PreparedPluginEventStale;
                generation.effects[effect_index].applied = true;
                generation.phase = .effect_applied;
                self.findHistory(generation.number).?.phase = .effect_applied;
            },
            .active => {
                const generation = self.staged orelse return error.PreparedPluginEventStale;
                if (generation.number != prepared.generation or !canReconcile(generation.phase) or generation.callback_in_progress or !generation.activated) return error.PreparedPluginEventStale;
                if (self.active) |prior| self.retire(prior);
                generation.phase = .active;
                self.active = generation;
                self.staged = null;
                const history = self.findHistory(generation.number).?;
                history.phase = .active;
                history.ever_active = true;
            },
            .failed => {
                const generation = self.staged orelse return error.PreparedPluginEventStale;
                if (generation.number != prepared.generation or !canReconcile(generation.phase) or generation.callback_in_progress or generation.activated) return error.PreparedPluginEventStale;
                generation.phase = .failed;
                const history = self.findHistory(generation.number).?;
                history.phase = .failed;
                @memcpy(history.failure[0..prepared.failure_len], prepared.failure[0..prepared.failure_len]);
                history.failure_len = prepared.failure_len;
            },
            .rolled_back => {
                const generation = self.staged orelse return error.PreparedPluginEventStale;
                if (generation.number != prepared.generation or generation.callback_in_progress or generation.phase != .failed or !generation.rollback_complete) return error.PreparedPluginEventStale;
                generation.phase = .rolled_back;
                self.findHistory(generation.number).?.phase = .rolled_back;
                self.staged = null;
                self.retire(generation);
            },
        }
        self.revision += 1;
        prepared.committed = true;
        if (self.reserved_prepared == prepared) {
            self.reserved_prepared = null;
            prepared.reservation_active = false;
        }
    }

    fn reservePrepared(self: *Registry, prepared: *PreparedEvent) bool {
        self.lock();
        defer self.unlock();
        if (self.reserved_prepared != null or !self.preparedIsCurrentLocked(prepared)) return false;
        self.reserved_prepared = prepared;
        prepared.reservation_active = true;
        return true;
    }

    fn releasePreparedReservation(self: *Registry, prepared: *PreparedEvent) void {
        self.lock();
        defer self.unlock();
        if (self.reserved_prepared == prepared) self.reserved_prepared = null;
        prepared.reservation_active = false;
    }

    fn preparedIsCurrentLocked(self: *Registry, prepared: *const PreparedEvent) bool {
        if (prepared.owner != self or prepared.committed or prepared.expected_revision != self.revision) return false;
        return switch (prepared.phase) {
            .desired => self.staged == null and prepared.candidate != null and prepared.candidate.?.number == self.next_generation,
            .effect_applied => if (self.staged) |generation| generation.number == prepared.generation and canReconcile(generation.phase) and !generation.callback_in_progress and prepared.effect_index != null and prepared.effect_index.? < generation.effects.len and !generation.effects[prepared.effect_index.?].applied and generation.firstUnappliedEffect() == prepared.effect_index.? and (prepared.replayed_effect or generation.effects[prepared.effect_index.?].reconciled) else false,
            .active => if (self.staged) |generation| generation.number == prepared.generation and canReconcile(generation.phase) and !generation.callback_in_progress and generation.activated else false,
            .failed => if (self.staged) |generation| generation.number == prepared.generation and canReconcile(generation.phase) and !generation.callback_in_progress and !generation.activated else false,
            .rolled_back => if (self.staged) |generation| generation.number == prepared.generation and !generation.callback_in_progress and generation.phase == .failed and generation.rollback_complete else false,
        };
    }

    pub fn activeSnapshot(self: *Registry) ?GenerationSnapshot {
        self.lock();
        defer self.unlock();
        return if (self.active) |generation| generation.snapshot() else null;
    }

    pub fn stagedSnapshot(self: *Registry) ?GenerationSnapshot {
        self.lock();
        defer self.unlock();
        return if (self.staged) |generation| generation.snapshot() else null;
    }

    pub fn listHistory(self: *Registry, allocator: std.mem.Allocator) ![]LifecycleRecord {
        self.lock();
        defer self.unlock();
        const records = try allocator.alloc(LifecycleRecord, self.history.items.len);
        for (self.history.items, 0..) |entry, index| {
            records[index] = .{
                .generation = entry.generation,
                .previous_generation = entry.previous_generation,
                .action = entry.action,
                .phase = entry.phase,
                .graph_digest = entry.graph_digest,
                .target_generation = entry.target_generation,
                .plugin_count = entry.plugin_count,
                .ever_active = entry.ever_active,
                .failure = entry.failure,
                .failure_len = entry.failure_len,
            };
        }
        return records;
    }

    pub fn acquireLease(self: *Registry) !Lease {
        self.lock();
        defer self.unlock();
        const generation = self.active orelse return error.NoActivePluginGeneration;
        if (generation.leases == std.math.maxInt(usize)) return error.PluginLeaseExhausted;
        generation.leases += 1;
        return .{ .registry = self, .generation = generation };
    }

    fn releaseLease(self: *Registry, generation: *Generation) void {
        self.lock();
        std.debug.assert(generation.leases > 0);
        generation.leases -= 1;
        const destroy = generation.leases == 0 and generation.retired and self.unlinkRetired(generation);
        self.unlock();
        if (destroy) generation.destroy();
    }

    pub fn collectRetired(self: *Registry) void {
        while (true) {
            self.lock();
            var link = &self.retired;
            var found: ?*Generation = null;
            while (link.*) |generation| {
                if (generation.leases == 0) {
                    link.* = generation.next_retired;
                    generation.next_retired = null;
                    found = generation;
                    break;
                }
                link = &generation.next_retired;
            }
            self.unlock();
            const generation = found orelse return;
            generation.destroy();
        }
    }

    pub fn replay(self: *Registry, store: *event_store.Store) !event_store.RecoveryReport {
        self.lock();
        const empty = self.active == null and self.staged == null and self.retired == null and self.history.items.len == 0 and self.revision == 0 and self.next_generation == 1;
        self.unlock();
        if (!empty) return error.PluginProjectionNotEmpty;
        return store.replayWithContext(@ptrCast(self), replayVisitor);
    }

    pub fn applyCommittedRecord(self: *Registry, record: event_store.RecordView) !void {
        try record.verifyWire();
        if (record.kind != .plugin_state_changed) return;
        var prepared = try self.prepareRecordedPayload(record.payload, record.settlement);
        defer prepared.deinit();
        try self.commitPrepared(&prepared, record);
    }

    fn prepareRecordedPayload(self: *Registry, payload: []const u8, settlement: event_store.Settlement) !PreparedEvent {
        if (payload.len == 0 or payload.len > max_lifecycle_payload_bytes) return error.InvalidPluginPersistence;
        var parsed = std.json.parseFromSlice(WireLifecycle, self.allocator, payload, .{
            .allocate = .alloc_always,
            .max_value_len = plugin_manifest.max_manifest_bytes * 2 + max_failure_bytes,
        }) catch |err| switch (err) {
            error.OutOfMemory => return error.OutOfMemory,
            error.UnknownField => return error.UnknownPluginEventField,
            error.DuplicateField => return error.DuplicatePluginEventField,
            else => return error.InvalidPluginPersistence,
        };
        defer parsed.deinit();
        const wire = parsed.value;
        if (wire.schema_version != schema_version or wire.generation == 0 or wire.manifests.len > plugin_manifest.max_plugins) return error.InvalidPluginPersistence;
        _ = parseDigest(wire.graph_sha256) orelse return error.InvalidPluginPersistence;
        switch (wire.phase) {
            .desired => {
                if (settlement != .succeeded or wire.effect_plugin.len != 0 or wire.effect_id.len != 0 or wire.failure.len != 0) return error.InvalidPluginPersistence;
                const owned = try self.allocator.alloc(plugin_manifest.OwnedManifest, wire.manifests.len);
                var owned_count: usize = 0;
                defer {
                    for (owned[0..owned_count]) |*manifest| manifest.deinit();
                    self.allocator.free(owned);
                }
                const views = try self.allocator.alloc(plugin_manifest.Manifest, wire.manifests.len);
                defer self.allocator.free(views);
                for (wire.manifests, 0..) |document, index| {
                    owned[index] = try plugin_manifest.parseOwned(self.allocator, document);
                    owned_count += 1;
                    views[index] = owned[index].value;
                }
                var prepared = try self.prepareDesired(wire.action, wire.target_generation, views);
                errdefer prepared.deinit();
                if (prepared.generation != wire.generation or !std.mem.eql(u8, prepared.payload_bytes, payload)) return error.InvalidPluginPersistence;
                return prepared;
            },
            .effect_applied => {
                if (settlement != .succeeded or wire.effect_plugin.len == 0 or wire.effect_id.len == 0 or wire.failure.len != 0 or wire.manifests.len != 0) return error.InvalidPluginPersistence;
                var prepared = try self.prepareEffectAppliedInternal(.{
                    .generation = wire.generation,
                    .plugin_identity = wire.effect_plugin,
                    .effect_id = wire.effect_id,
                }, true);
                errdefer prepared.deinit();
                if (!std.mem.eql(u8, prepared.payload_bytes, payload)) return error.InvalidPluginPersistence;
                return prepared;
            },
            .active => {
                if (settlement != .succeeded or wire.effect_plugin.len != 0 or wire.effect_id.len != 0 or wire.failure.len != 0 or wire.manifests.len != 0) return error.InvalidPluginPersistence;
                try self.verifyCanonicalStagedPayload(wire.generation, .active, null, "", payload);
                try self.reconcileRecordedEffects(wire.generation);
                try self.activateStaged(wire.generation);
                var prepared = try self.prepareActive(wire.generation);
                errdefer prepared.deinit();
                if (!std.mem.eql(u8, prepared.payload_bytes, payload)) return error.InvalidPluginPersistence;
                return prepared;
            },
            .failed => {
                if (settlement != .failed or wire.effect_plugin.len != 0 or wire.effect_id.len != 0 or wire.manifests.len != 0) return error.InvalidPluginPersistence;
                var prepared = try self.prepareFailed(wire.generation, wire.failure);
                errdefer prepared.deinit();
                if (!std.mem.eql(u8, prepared.payload_bytes, payload)) return error.InvalidPluginPersistence;
                return prepared;
            },
            .rolled_back => {
                if (settlement != .succeeded or wire.effect_plugin.len != 0 or wire.effect_id.len != 0 or wire.failure.len != 0 or wire.manifests.len != 0) return error.InvalidPluginPersistence;
                try self.verifyCanonicalStagedPayload(wire.generation, .rolled_back, null, "", payload);
                self.rollbackStaged(wire.generation);
                var prepared = try self.prepareRolledBack(wire.generation);
                errdefer prepared.deinit();
                if (!std.mem.eql(u8, prepared.payload_bytes, payload)) return error.InvalidPluginPersistence;
                return prepared;
            },
        }
    }

    fn verifyCanonicalStagedPayload(self: *Registry, generation_number: u64, phase: EventPhase, effect: ?EffectKey, failure: []const u8, payload: []const u8) !void {
        self.lock();
        defer self.unlock();
        const generation = self.staged orelse return error.InvalidPluginPersistence;
        if (generation.number != generation_number) return error.InvalidPluginPersistence;
        const canonical = try encodeLifecyclePayload(self.allocator, generation, phase, effect, failure);
        defer self.allocator.free(canonical);
        if (!std.mem.eql(u8, canonical, payload)) return error.InvalidPluginPersistence;
    }

    fn reconcileRecordedEffects(self: *Registry, generation_number: u64) !void {
        while (true) {
            self.lock();
            const generation = self.staged orelse {
                self.unlock();
                return error.NoStagedPluginGeneration;
            };
            if (generation.number != generation_number) {
                self.unlock();
                return error.StalePluginGeneration;
            }
            if (!canReconcile(generation.phase) or generation.callback_in_progress) {
                self.unlock();
                return error.PluginGenerationNotReconcilable;
            }
            var next: ?EffectKey = null;
            for (generation.effects) |state| {
                if (!state.applied) {
                    self.unlock();
                    return error.InvalidPluginPersistence;
                }
                if (!state.reconciled and next == null) next = generation.effectKey(state);
            }
            self.unlock();
            const key = next orelse return;
            try self.executeEffect(key);
        }
    }

    fn retire(self: *Registry, generation: *Generation) void {
        generation.retired = true;
        generation.next_retired = self.retired;
        self.retired = generation;
    }

    fn unlinkRetired(self: *Registry, target: *Generation) bool {
        var link = &self.retired;
        while (link.*) |generation| {
            if (generation == target) {
                link.* = generation.next_retired;
                generation.next_retired = null;
                return true;
            }
            link = &generation.next_retired;
        }
        return false;
    }

    fn findHistory(self: *Registry, generation: u64) ?*HistoryEntry {
        for (self.history.items) |*entry| if (entry.generation == generation) return entry;
        return null;
    }

    fn validateAction(self: *Registry, action: LifecycleAction, target_generation: u64, manifests: []const plugin_manifest.Manifest) !void {
        if (action != .rollback and target_generation != 0) return error.UnexpectedRollbackTarget;
        try validateFactoryTable(self.factories);
        var graph = try plugin_manifest.validateGraph(self.allocator, manifests, self.validation);
        defer graph.deinit(self.allocator);
        for (manifests) |manifest| _ = findFactory(self.factories, manifest.entry_point) orelse return error.UnknownNativeFactory;
        const digest = executableGraphDigest(manifests, &graph, self.factories);
        switch (action) {
            .enable => {
                if (manifests.len == 0) return error.InvalidEnableGraph;
                if (self.active) |active| {
                    if (manifests.len <= active.manifest_views.len or !try containsUnchangedGraph(self.allocator, manifests, active.manifest_views)) return error.InvalidEnableGraph;
                }
            },
            .disable => {
                const active = self.active orelse return error.InvalidDisableGraph;
                if (manifests.len >= active.manifest_views.len or !try containsUnchangedGraph(self.allocator, active.manifest_views, manifests)) return error.InvalidDisableGraph;
            },
            .upgrade => {
                const active = self.active orelse return error.InvalidUpgradeGraph;
                if (manifests.len != active.manifest_views.len) return error.InvalidUpgradeGraph;
                var changed = false;
                for (active.manifest_views) |current| {
                    const candidate = findManifest(manifests, current.identity) orelse return error.InvalidUpgradeGraph;
                    switch (current.version.order(candidate.version)) {
                        .lt => changed = true,
                        .eq => if (!try manifestsEqual(self.allocator, current, candidate)) return error.InvalidUpgradeGraph,
                        .gt => return error.InvalidUpgradeGraph,
                    }
                }
                if (!changed) return error.InvalidUpgradeGraph;
            },
            .reload => {
                const active = self.active orelse return error.InvalidReloadGraph;
                if (!std.mem.eql(u8, &active.digest, &digest)) return error.InvalidReloadGraph;
            },
            .rollback => {
                if (target_generation == 0) return error.InvalidRollbackTarget;
                const active = self.active orelse return error.InvalidRollbackTarget;
                if (active.number == target_generation) return error.InvalidRollbackTarget;
                const target = self.findHistory(target_generation) orelse return error.InvalidRollbackTarget;
                if (!target.ever_active or !std.mem.eql(u8, &target.graph_digest, &digest)) return error.InvalidRollbackTarget;
            },
        }
    }
};

fn containsUnchangedGraph(allocator: std.mem.Allocator, container: []const plugin_manifest.Manifest, subset: []const plugin_manifest.Manifest) !bool {
    for (subset) |wanted| {
        var found = false;
        for (container) |candidate| {
            if (std.mem.eql(u8, wanted.identity, candidate.identity) and try manifestsEqual(allocator, wanted, candidate)) {
                found = true;
                break;
            }
        }
        if (!found) return false;
    }
    return true;
}

fn manifestsEqual(allocator: std.mem.Allocator, left: plugin_manifest.Manifest, right: plugin_manifest.Manifest) !bool {
    const left_bytes = try plugin_manifest.encodeCanonical(allocator, left);
    defer allocator.free(left_bytes);
    const right_bytes = try plugin_manifest.encodeCanonical(allocator, right);
    defer allocator.free(right_bytes);
    return std.mem.eql(u8, left_bytes, right_bytes);
}

fn findManifest(manifests: []const plugin_manifest.Manifest, identity: []const u8) ?plugin_manifest.Manifest {
    for (manifests) |manifest| if (std.mem.eql(u8, manifest.identity, identity)) return manifest;
    return null;
}

fn buildGeneration(
    allocator: std.mem.Allocator,
    parent_context: *cordis.Context,
    factories: []const NativeFactory,
    validation: plugin_manifest.ValidationContext,
    number: u64,
    previous_generation: u64,
    target_generation: u64,
    action: LifecycleAction,
    source_manifests: []const plugin_manifest.Manifest,
) !*Generation {
    try validateFactoryTable(factories);
    const manifests = try allocator.alloc(plugin_manifest.OwnedManifest, source_manifests.len);
    var manifest_count: usize = 0;
    errdefer {
        for (manifests[0..manifest_count]) |*manifest| manifest.deinit();
        allocator.free(manifests);
    }
    for (source_manifests, 0..) |manifest, index| {
        manifests[index] = try plugin_manifest.cloneOwned(allocator, manifest);
        manifest_count += 1;
    }
    const views = try allocator.alloc(plugin_manifest.Manifest, manifests.len);
    errdefer allocator.free(views);
    for (manifests, 0..) |manifest, index| views[index] = manifest.value;
    var graph = try plugin_manifest.validateGraph(allocator, views, validation);
    errdefer graph.deinit(allocator);

    for (views) |manifest| _ = findFactory(factories, manifest.entry_point) orelse return error.UnknownNativeFactory;
    const digest = executableGraphDigest(views, &graph, factories);

    const plans = try allocator.alloc(StagedNativePlugin, views.len);
    var plan_count: usize = 0;
    errdefer {
        var order_index = plan_count;
        while (order_index > 0) {
            order_index -= 1;
            const manifest_index = graph.activation_order[order_index];
            plans[manifest_index].deinit(allocator, plans[manifest_index].state);
        }
        allocator.free(plans);
    }
    var total_effects: usize = 0;
    for (graph.activation_order) |manifest_index| {
        const manifest = &views[manifest_index];
        const factory = findFactory(factories, manifest.entry_point).?;
        plans[manifest_index] = try factory.stage(factory.context, allocator, manifest, number);
        plan_count += 1;
        try validateStagedPlan(manifest, number, plans[manifest_index]);
        total_effects = std.math.add(usize, total_effects, plans[manifest_index].effects.len) catch return error.PluginEffectLimitExceeded;
        if (total_effects > plugin_manifest.max_plugins * max_effects_per_plugin) return error.PluginEffectLimitExceeded;
    }

    const effects = try allocator.alloc(EffectState, total_effects);
    errdefer allocator.free(effects);
    var effect_count: usize = 0;
    for (graph.activation_order) |plugin_index| {
        const plan = plans[plugin_index];
        for (plan.effects, 0..) |_, effect_index| {
            effects[effect_count] = .{ .plugin_index = plugin_index, .effect_index = effect_index };
            effect_count += 1;
        }
    }
    const generation = try allocator.create(Generation);
    generation.* = .{
        .allocator = allocator,
        .number = number,
        .previous_generation = previous_generation,
        .target_generation = target_generation,
        .action = action,
        .manifests = manifests,
        .manifest_views = views,
        .graph = graph,
        .digest = digest,
        .plans = plans,
        .initialized_plans = plan_count,
        .effects = effects,
        .context = parent_context.child(),
    };
    return generation;
}

fn validateFactoryTable(factories: []const NativeFactory) !void {
    for (factories, 0..) |factory, index| {
        try validateName(factory.entry_point);
        try validateName(factory.implementation_id);
        for (factories[0..index]) |previous| if (std.mem.eql(u8, factory.entry_point, previous.entry_point)) return error.DuplicateNativeFactory;
    }
}

fn executableGraphDigest(manifests: []const plugin_manifest.Manifest, graph: *const plugin_manifest.ValidatedGraph, factories: []const NativeFactory) plugin_manifest.Digest {
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update("misul-native-plugin-generation-v1");
    hasher.update(&graph.digest);
    for (graph.canonical_order) |manifest_index| {
        const manifest = manifests[manifest_index];
        const factory = findFactory(factories, manifest.entry_point).?;
        hashDigestString(&hasher, factory.entry_point);
        hashDigestString(&hasher, factory.implementation_id);
    }
    var digest: plugin_manifest.Digest = undefined;
    hasher.final(&digest);
    return digest;
}

fn hashDigestString(hasher: *std.crypto.hash.sha2.Sha256, value: []const u8) void {
    var length: [4]u8 = undefined;
    std.mem.writeInt(u32, &length, @intCast(value.len), .little);
    hasher.update(&length);
    hasher.update(value);
}

fn findFactory(factories: []const NativeFactory, entry_point: []const u8) ?NativeFactory {
    for (factories) |factory| if (std.mem.eql(u8, factory.entry_point, entry_point)) return factory;
    return null;
}

fn validateStagedPlan(manifest: *const plugin_manifest.Manifest, generation: u64, plan: StagedNativePlugin) !void {
    if (!std.mem.eql(u8, plan.cordis_plugin.name, manifest.identity) or plan.cordis_plugin.revision != generation) return error.InvalidNativeFactoryPlan;
    if (plan.effects.len > max_effects_per_plugin) return error.PluginEffectLimitExceeded;
    for (plan.effects, 0..) |effect, index| {
        try validateName(effect.id);
        for (plan.effects[0..index]) |previous| if (std.mem.eql(u8, effect.id, previous.id)) return error.DuplicatePluginEffect;
    }
}

fn validateName(value: []const u8) !void {
    if (value.len == 0 or value.len > plugin_manifest.max_identifier_bytes or !std.ascii.isLower(value[0])) return error.InvalidPluginName;
    for (value[1..]) |byte| {
        if (!std.ascii.isLower(byte) and !std.ascii.isDigit(byte) and byte != '.' and byte != '-' and byte != '_') return error.InvalidPluginName;
    }
}

fn validateFailure(value: []const u8) !void {
    if (value.len == 0 or value.len > max_failure_bytes or !std.ascii.isLower(value[0])) return error.InvalidPluginFailure;
    for (value[1..]) |byte| {
        if (!std.ascii.isLower(byte) and !std.ascii.isDigit(byte) and byte != '.' and byte != '-' and byte != '_') return error.InvalidPluginFailure;
    }
}

fn canReconcile(phase: EventPhase) bool {
    return phase == .desired or phase == .effect_applied;
}

fn encodeLifecyclePayload(
    allocator: std.mem.Allocator,
    generation: *const Generation,
    phase: EventPhase,
    effect: ?EffectKey,
    failure: []const u8,
) ![]u8 {
    if (failure.len > max_failure_bytes) return error.PluginFailureTooLarge;
    var output: std.Io.Writer.Allocating = .init(allocator);
    errdefer output.deinit();
    writeLifecyclePayload(allocator, &output.writer, generation, phase, effect, failure) catch |err| switch (err) {
        error.ManifestTooLarge => return error.ManifestTooLarge,
        else => return error.OutOfMemory,
    };
    if (output.written().len > max_lifecycle_payload_bytes) return error.PluginPayloadTooLarge;
    return output.toOwnedSlice();
}

fn writeLifecyclePayload(
    allocator: std.mem.Allocator,
    writer: *std.Io.Writer,
    generation: *const Generation,
    phase: EventPhase,
    effect: ?EffectKey,
    failure: []const u8,
) !void {
    const digest_text = std.fmt.bytesToHex(generation.digest, .lower);
    try writer.writeAll("{\"schema_version\":");
    try writer.print("{d}", .{schema_version});
    try writer.writeAll(",\"phase\":");
    try writeJsonString(writer, @tagName(phase));
    try writer.writeAll(",\"action\":");
    try writeJsonString(writer, @tagName(generation.action));
    try writer.print(",\"generation\":{d},\"previous_generation\":{d},\"target_generation\":{d},\"graph_sha256\":\"{s}\",\"effect_plugin\":", .{
        generation.number,
        generation.previous_generation,
        generation.target_generation,
        digest_text,
    });
    try writeJsonString(writer, if (effect) |key| key.plugin_identity else "");
    try writer.writeAll(",\"effect_id\":");
    try writeJsonString(writer, if (effect) |key| key.effect_id else "");
    try writer.writeAll(",\"failure\":");
    try writeJsonString(writer, failure);
    try writer.writeAll(",\"manifests\":[");
    if (phase == .desired) {
        for (generation.graph.canonical_order, 0..) |manifest_index, index| {
            if (index != 0) try writer.writeByte(',');
            const encoded = try plugin_manifest.encodeCanonical(allocator, generation.manifest_views[manifest_index]);
            defer allocator.free(encoded);
            try writeJsonString(writer, encoded);
        }
    }
    try writer.writeAll("]}");
}

fn writeJsonString(writer: *std.Io.Writer, value: []const u8) !void {
    try writer.print("{f}", .{std.json.fmt(value, .{})});
}

fn parseDigest(value: []const u8) ?plugin_manifest.Digest {
    if (value.len != @sizeOf(plugin_manifest.Digest) * 2) return null;
    var digest: plugin_manifest.Digest = undefined;
    _ = std.fmt.hexToBytes(&digest, value) catch return null;
    return digest;
}

fn replayVisitor(context: *anyopaque, record: event_store.RecordView) !void {
    const registry: *Registry = @ptrCast(@alignCast(context));
    try registry.applyCommittedRecord(record);
    registry.collectRetired();
}
