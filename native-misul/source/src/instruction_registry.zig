const std = @import("std");
const event_store = @import("event_store.zig");

pub const max_content_bytes: usize = 4 * 1024 * 1024;
pub const max_provenance_bytes: usize = 4096;
pub const max_identifier_bytes: usize = 256;

pub const Authority = enum {
    user_constraint,
    user_superseded,
    agent_adaptation,
    agent_rollback,
};

pub const ValidationResult = enum { valid, invalid };
pub const PolicyDecision = enum { approved, denied };

/// Runtime-04 supplies the proof-bearing reference and validator.  A plain
/// provenance string is never an authority decision.
pub const UserDecisionRef = struct {
    reference: []const u8,
    proof: *const anyopaque,
    mutation_digest: [32]u8 = @splat(0),
    decision_id: []const u8 = "",
    durable_event_id: []const u8 = "",
    workspace_id: []const u8 = "",
    actor_id: []const u8 = "",
    causal_request_id: []const u8 = "",
    expires_at: u64 = 0,
};

pub const UserDecisionValidator = *const fn (context: *anyopaque, decision: UserDecisionRef, authority: Authority) bool;

pub const Revision = struct {
    instruction_id: []const u8,
    version_id: []const u8,
    predecessor_version_id: ?[]const u8,
    authority: Authority,
    normalized_content: []const u8,
    deterministic_diff: []const u8,
    provenance: []const u8,
    validator: []const u8,
    validator_version: []const u8,
    validation: ValidationResult,
    policy: PolicyDecision,
    rollback_target: ?[]const u8,

    pub fn deinit(self: *Revision, allocator: std.mem.Allocator) void {
        allocator.free(self.instruction_id);
        allocator.free(self.version_id);
        if (self.predecessor_version_id) |id| allocator.free(id);
        allocator.free(self.normalized_content);
        allocator.free(self.deterministic_diff);
        allocator.free(self.provenance);
        allocator.free(self.validator);
        allocator.free(self.validator_version);
        if (self.rollback_target) |id| allocator.free(id);
        self.* = undefined;
    }
};

pub const EffectiveInstruction = struct {
    instruction_id: []const u8,
    version_id: []const u8,
    authority: Authority,
    normalized_content: []const u8,
};

pub const InstructionRevision = Revision;

pub const Registry = struct {
    allocator: std.mem.Allocator,
    revisions: std.ArrayList(*Revision) = .empty,
    projection_mutex: std.atomic.Mutex = .unlocked,
    user_decision_context: ?*anyopaque = null,
    user_decision_proof: ?*const anyopaque = null,
    user_decision_validator: ?UserDecisionValidator = null,

    pub fn init(allocator: std.mem.Allocator) Registry {
        return .{ .allocator = allocator };
    }

    fn lockProjection(self: *Registry) void {
        while (!self.projection_mutex.tryLock()) std.atomic.spinLoopHint();
    }

    fn unlockProjection(self: *Registry) void {
        self.projection_mutex.unlock();
    }

    pub fn deinit(self: *Registry) void {
        self.lockProjection();
        for (self.revisions.items) |revision| {
            revision.deinit(self.allocator);
            self.allocator.destroy(revision);
        }
        self.revisions.deinit(self.allocator);
        self.unlockProjection();
        self.* = undefined;
    }

    /// Bind the runtime's proof-bearing user-decision authority once. User
    /// events fail closed until this typed seam is configured.
    pub fn bindUserDecisionAuthority(self: *Registry, context: *anyopaque, proof: *const anyopaque, validator: UserDecisionValidator) !void {
        self.lockProjection();
        defer self.unlockProjection();
        if (self.user_decision_validator != null) return error.UserDecisionAuthorityAlreadyBound;
        self.user_decision_context = context;
        self.user_decision_proof = proof;
        self.user_decision_validator = validator;
    }

    fn addUserConstraint(
        self: *Registry,
        instruction_id: []const u8,
        content: []const u8,
        provenance: []const u8,
        validator: []const u8,
        validator_version: []const u8,
    ) !*const Revision {
        self.lockProjection();
        defer self.unlockProjection();
        try validateIdentity(instruction_id, content, provenance, validator, validator_version);
        if (self.findLatest(instruction_id) != null) return error.DuplicateInstruction;
        return self.appendRevision(.{
            .instruction_id = instruction_id,
            .authority = .user_constraint,
            .content = content,
            .provenance = provenance,
            .validator = validator,
            .validator_version = validator_version,
            .validation = .valid,
            .policy = .approved,
        });
    }

    fn supersedeUserConstraint(
        self: *Registry,
        instruction_id: []const u8,
        content: []const u8,
        provenance: []const u8,
        validator: []const u8,
        validator_version: []const u8,
    ) !*const Revision {
        self.lockProjection();
        defer self.unlockProjection();
        const prior = self.findLatest(instruction_id) orelse return error.InstructionNotFound;
        if (prior.authority != .user_constraint and prior.authority != .user_superseded) return error.NotAUserConstraint;
        try validateIdentity(instruction_id, content, provenance, validator, validator_version);
        return self.appendRevision(.{
            .instruction_id = instruction_id,
            .authority = .user_superseded,
            .content = content,
            .provenance = provenance,
            .validator = validator,
            .validator_version = validator_version,
            .validation = .valid,
            .policy = .approved,
        });
    }

    fn proposeAgent(
        self: *Registry,
        instruction_id: []const u8,
        content: []const u8,
        provenance: []const u8,
        validator: []const u8,
        validator_version: []const u8,
        validation: ValidationResult,
        policy: PolicyDecision,
    ) !*const Revision {
        self.lockProjection();
        defer self.unlockProjection();
        if (self.findLatest(instruction_id) != null) return error.DuplicateInstruction;
        try validateIdentity(instruction_id, content, provenance, validator, validator_version);
        return self.appendRevision(.{
            .instruction_id = instruction_id,
            .authority = .agent_adaptation,
            .content = content,
            .provenance = provenance,
            .validator = validator,
            .validator_version = validator_version,
            .validation = validation,
            .policy = policy,
        });
    }

    fn reviseAgent(
        self: *Registry,
        instruction_id: []const u8,
        content: []const u8,
        provenance: []const u8,
        validator: []const u8,
        validator_version: []const u8,
        validation: ValidationResult,
        policy: PolicyDecision,
    ) !*const Revision {
        self.lockProjection();
        defer self.unlockProjection();
        const prior = self.findLatest(instruction_id) orelse return error.InstructionNotFound;
        if (prior.authority == .user_constraint or prior.authority == .user_superseded) return error.UserConstraintImmutable;
        try validateIdentity(instruction_id, content, provenance, validator, validator_version);
        return self.appendRevision(.{
            .instruction_id = instruction_id,
            .authority = .agent_adaptation,
            .content = content,
            .provenance = provenance,
            .validator = validator,
            .validator_version = validator_version,
            .validation = validation,
            .policy = policy,
        });
    }

    fn rollback(
        self: *Registry,
        instruction_id: []const u8,
        target_version_id: []const u8,
        provenance: []const u8,
    ) !*const Revision {
        self.lockProjection();
        defer self.unlockProjection();
        const prior = self.findLatest(instruction_id) orelse return error.InstructionNotFound;
        if (prior.authority == .user_constraint or prior.authority == .user_superseded) return error.UserConstraintImmutable;
        const target = self.findVersion(instruction_id, target_version_id) orelse return error.RollbackTargetNotFound;
        if (target.validation != .valid or target.policy != .approved) return error.RollbackTargetInactive;
        if (target.authority == .user_constraint or target.authority == .user_superseded) return error.UserConstraintImmutable;
        try validateIdentity(instruction_id, target.normalized_content, provenance, "registry", "rollback-v1");
        return self.appendRevision(.{
            .instruction_id = instruction_id,
            .authority = .agent_rollback,
            .content = target.normalized_content,
            .provenance = provenance,
            .validator = "registry",
            .validator_version = "rollback-v1",
            .validation = .valid,
            .policy = .approved,
            .rollback_target = target.version_id,
        });
    }

    fn rollbackUserConstraint(
        self: *Registry,
        instruction_id: []const u8,
        target_version_id: []const u8,
        provenance: []const u8,
        validator: []const u8,
        validator_version: []const u8,
    ) !*const Revision {
        self.lockProjection();
        defer self.unlockProjection();
        const target = self.findVersion(instruction_id, target_version_id) orelse return error.RollbackTargetNotFound;
        if (target.authority != .user_constraint and target.authority != .user_superseded) return error.NotAUserConstraint;
        try validateIdentity(instruction_id, target.normalized_content, provenance, validator, validator_version);
        return self.appendRevision(.{
            .instruction_id = instruction_id,
            .authority = .user_superseded,
            .content = target.normalized_content,
            .provenance = provenance,
            .validator = validator,
            .validator_version = validator_version,
            .validation = .valid,
            .policy = .approved,
            .rollback_target = target.version_id,
        });
    }

    fn latest(self: *const Registry, instruction_id: []const u8) ?*const Revision {
        const mutable_self = @constCast(self);
        mutable_self.lockProjection();
        defer mutable_self.unlockProjection();
        return self.findLatest(instruction_id);
    }

    fn effectiveContent(self: *const Registry, instruction_id: []const u8) ?[]const u8 {
        const mutable_self = @constCast(self);
        mutable_self.lockProjection();
        defer mutable_self.unlockProjection();
        return if (self.effectiveRevision(instruction_id)) |revision| revision.normalized_content else null;
    }

    pub fn effectiveSet(self: *const Registry, allocator: std.mem.Allocator) ![]EffectiveInstruction {
        const mutable_self = @constCast(self);
        mutable_self.lockProjection();
        defer mutable_self.unlockProjection();
        var ids: std.ArrayList([]const u8) = .empty;
        defer ids.deinit(allocator);
        for (self.revisions.items) |revision| {
            var exists = false;
            for (ids.items) |id| {
                if (std.mem.eql(u8, id, revision.instruction_id)) exists = true;
            }
            if (!exists) try ids.append(allocator, revision.instruction_id);
        }
        std.mem.sort([]const u8, ids.items, {}, lessBytes);
        var result: std.ArrayList(EffectiveInstruction) = .empty;
        errdefer result.deinit(allocator);
        for (ids.items) |id| {
            const revision = self.effectiveRevision(id) orelse continue;
            try result.append(allocator, .{
                .instruction_id = revision.instruction_id,
                .version_id = revision.version_id,
                .authority = revision.authority,
                .normalized_content = revision.normalized_content,
            });
        }
        return result.toOwnedSlice(allocator);
    }

    pub fn serializeEffective(self: *const Registry, allocator: std.mem.Allocator) ![]u8 {
        const effective = try self.effectiveSet(allocator);
        defer allocator.free(effective);
        var output: std.ArrayList(u8) = .empty;
        errdefer output.deinit(allocator);
        for (effective) |instruction| {
            const wire = WireEffective{
                .instruction_id = instruction.instruction_id,
                .version_id = instruction.version_id,
                .authority = instruction.authority,
                .content = instruction.normalized_content,
            };
            const encoded = try std.json.Stringify.valueAlloc(allocator, wire, .{});
            defer allocator.free(encoded);
            try output.appendSlice(allocator, encoded);
            try output.append(allocator, '\n');
        }
        return output.toOwnedSlice(allocator);
    }

    /// Apply only instruction events from the shared durable event stream.
    /// Other runtime/task/action/plugin kinds are intentionally ignored.
    fn apply(self: *Registry, record: event_store.RecordView) !void {
        try record.verifyWire();
        self.lockProjection();
        defer self.unlockProjection();
        const expected_authority = authorityForKind(record.kind) orelse return;
        if (record.settlement != .succeeded) return error.InvalidSettlement;
        const parsed = std.json.parseFromSlice(WireRevision, self.allocator, record.payload, .{}) catch |err| switch (err) {
            error.OutOfMemory => return error.OutOfMemory,
            else => return error.InvalidPersistence,
        };
        defer parsed.deinit();
        const wire = parsed.value;
        if (!std.mem.eql(u8, record.provenance, wire.provenance)) return error.InvalidPersistence;
        try self.appendWire(wire, expected_authority, record.kind, record.settlement, record.event_id);
    }

    pub fn replay(self: *Registry, store: *event_store.Store) !event_store.RecoveryReport {
        return store.replayWithContext(@ptrCast(self), applyVisitor);
    }

    /// Apply one Store-authorized instruction record delivered from a shared
    /// event log. Direct projection mutation remains private; this seam only
    /// accepts a view whose source Store proves committed membership.
    pub fn applyCommittedRecord(self: *Registry, record: event_store.RecordView) !void {
        try record.verifyWire();
        try self.apply(record);
    }

    pub fn appendAndApply(self: *Registry, store: *event_store.Store, event: event_store.Event, provenance: []const u8, settlement: event_store.Settlement) !event_store.RecordRef {
        var prepared = try self.prepare(event, settlement);
        defer prepared.deinit(self.allocator);
        if (!prepared.matchesProvenance(provenance)) return error.InvalidPersistence;
        var context = ProjectionAppendContext{ .registry = self, .prepared = &prepared };
        return store.appendPreparedAndProject(event, provenance, settlement, @ptrCast(&context), noOpPrepare, validateAppendVisitor, commitAppendVisitor);
    }

    fn appendRevision(self: *Registry, request: RevisionRequest) !*const Revision {
        const predecessor = self.findLatest(request.instruction_id);
        const predecessor_id = if (predecessor) |value| value.version_id else null;
        var owned = OwnedRevisionFields{};
        errdefer owned.deinit(self.allocator);
        owned.normalized_content = try normalize(self.allocator, request.content);
        owned.deterministic_diff = try makeDiff(self.allocator, if (predecessor) |value| value.normalized_content else null, owned.normalized_content.?);
        owned.version_id = try makeVersionId(self.allocator, request.instruction_id, predecessor_id, owned.normalized_content.?, request.authority, request.provenance, request.validator, request.validator_version, request.validation, request.policy, request.rollback_target);
        owned.instruction_id = try self.allocator.dupe(u8, request.instruction_id);
        owned.predecessor_version_id = if (predecessor_id) |id| try self.allocator.dupe(u8, id) else null;
        owned.provenance = try self.allocator.dupe(u8, request.provenance);
        owned.validator = try self.allocator.dupe(u8, request.validator);
        owned.validator_version = try self.allocator.dupe(u8, request.validator_version);
        owned.rollback_target = if (request.rollback_target) |id| try self.allocator.dupe(u8, id) else null;
        const version_id = owned.version_id.?;
        if (self.findVersion(request.instruction_id, version_id) != null) return error.DuplicateVersion;
        const revision = try self.allocator.create(Revision);
        revision.* = owned.take(request.authority, request.validation, request.policy);
        self.revisions.append(self.allocator, revision) catch |err| {
            revision.deinit(self.allocator);
            self.allocator.destroy(revision);
            return err;
        };
        return revision;
    }

    fn prepare(self: *Registry, event: event_store.Event, settlement: event_store.Settlement) !Prepared {
        self.lockProjection();
        defer self.unlockProjection();
        const expected_authority = authorityForKind(event.kind) orelse return .{ .fields = .{}, .revision = null, .authority = .agent_adaptation, .validation = .invalid, .policy = .denied, .expected_revision_count = self.revisions.items.len, .owner = self };
        if (settlement != .succeeded) return error.InvalidSettlement;
        const parsed = std.json.parseFromSlice(WireRevision, self.allocator, event.payload, .{}) catch |err| switch (err) {
            error.OutOfMemory => return error.OutOfMemory,
            else => return error.InvalidPersistence,
        };
        defer parsed.deinit();
        return self.prepareWire(parsed.value, expected_authority, event.kind, settlement, event.event_id);
    }

    fn prepareWire(self: *Registry, wire: WireRevision, expected_authority: Authority, kind: event_store.EventKind, settlement: event_store.Settlement, durable_event_id: []const u8) !Prepared {
        if (wire.authority != expected_authority) return error.InvalidPersistence;
        try validateIdentity(wire.instruction_id, wire.content, wire.provenance, wire.validator, wire.validator_version);
        const normalized = normalize(self.allocator, wire.content) catch return error.OutOfMemory;
        defer self.allocator.free(normalized);
        if (!std.mem.eql(u8, normalized, wire.content)) return error.InvalidPersistence;
        const predecessor = self.findLatest(wire.instruction_id);
        if (predecessor) |prior| {
            if (wire.authority == .user_constraint) return error.InvalidPersistence;
            if ((wire.authority == .agent_adaptation or wire.authority == .agent_rollback) and (prior.authority == .user_constraint or prior.authority == .user_superseded)) return error.InvalidPersistence;
            if (wire.authority == .user_superseded and prior.authority != .user_constraint and prior.authority != .user_superseded) return error.InvalidPersistence;
        } else if (wire.authority == .user_superseded) {
            return error.InvalidPersistence;
        }
        if (wire.authority == .agent_rollback and wire.rollback_target == null) return error.InvalidPersistence;
        if (wire.rollback_target != null and wire.authority != .agent_rollback and wire.authority != .user_superseded) return error.InvalidPersistence;
        if (wire.authority == .user_constraint or wire.authority == .user_superseded) {
            const reference = wire.user_decision_ref orelse return error.UserDecisionRequired;
            const decision_id = wire.user_decision_id orelse return error.UserDecisionRequired;
            const workspace_id = wire.workspace_id orelse return error.UserDecisionRequired;
            const actor_id = wire.actor_id orelse return error.UserDecisionRequired;
            const causal_request_id = wire.causal_request_id orelse return error.UserDecisionRequired;
            const expires_at = wire.decision_expires_at orelse return error.UserDecisionRequired;
            if (!validIdentifier(decision_id) or !validIdentifier(workspace_id) or !validIdentifier(actor_id) or
                !validIdentifier(causal_request_id) or expires_at == 0 or !std.mem.eql(u8, reference, decision_id)) return error.UserDecisionRejected;
            const context = self.user_decision_context orelse return error.UserDecisionRequired;
            const proof = self.user_decision_proof orelse return error.UserDecisionRequired;
            const validator = self.user_decision_validator orelse return error.UserDecisionRequired;
            const digest_text = wire.user_decision_digest orelse return error.UserDecisionRequired;
            const mutation_digest = parseDigest(digest_text) orelse return error.InvalidPersistence;
            var expected_digest: [32]u8 = undefined;
            mutationDigest(&expected_digest, wire, kind, settlement, durable_event_id);
            if (!std.mem.eql(u8, &mutation_digest, &expected_digest)) return error.UserDecisionRejected;
            if (!validator(context, .{
                .reference = reference,
                .proof = proof,
                .mutation_digest = mutation_digest,
                .decision_id = decision_id,
                .durable_event_id = durable_event_id,
                .workspace_id = workspace_id,
                .actor_id = actor_id,
                .causal_request_id = causal_request_id,
                .expires_at = expires_at,
            }, wire.authority)) return error.UserDecisionRejected;
        }
        const expected_predecessor = if (predecessor) |value| value.version_id else null;
        if (!optionalEqual(expected_predecessor, wire.predecessor_version_id)) return error.InvalidPersistence;
        const diff = makeDiff(self.allocator, if (predecessor) |value| value.normalized_content else null, wire.content) catch return error.OutOfMemory;
        defer self.allocator.free(diff);
        if (!std.mem.eql(u8, diff, wire.diff)) return error.InvalidPersistence;
        const expected_id = makeVersionId(self.allocator, wire.instruction_id, wire.predecessor_version_id, wire.content, wire.authority, wire.provenance, wire.validator, wire.validator_version, wire.validation, wire.policy, wire.rollback_target) catch return error.OutOfMemory;
        defer self.allocator.free(expected_id);
        if (!std.mem.eql(u8, expected_id, wire.version_id)) return error.InvalidPersistence;
        if (wire.rollback_target) |target| {
            const target_revision = self.findVersion(wire.instruction_id, target) orelse return error.InvalidPersistence;
            if (target_revision.validation != .valid or target_revision.policy != .approved) return error.InvalidPersistence;
            if (wire.authority == .agent_rollback and (target_revision.authority == .user_constraint or target_revision.authority == .user_superseded)) return error.InvalidPersistence;
            if (wire.authority == .user_superseded and target_revision.authority != .user_constraint and target_revision.authority != .user_superseded) return error.InvalidPersistence;
        }
        var fields = OwnedRevisionFields{};
        errdefer fields.deinit(self.allocator);
        fields.normalized_content = try self.allocator.dupe(u8, wire.content);
        fields.deterministic_diff = try self.allocator.dupe(u8, wire.diff);
        fields.version_id = try self.allocator.dupe(u8, wire.version_id);
        fields.instruction_id = try self.allocator.dupe(u8, wire.instruction_id);
        fields.predecessor_version_id = if (wire.predecessor_version_id) |id| try self.allocator.dupe(u8, id) else null;
        fields.provenance = try self.allocator.dupe(u8, wire.provenance);
        fields.validator = try self.allocator.dupe(u8, wire.validator);
        fields.validator_version = try self.allocator.dupe(u8, wire.validator_version);
        fields.rollback_target = if (wire.rollback_target) |id| try self.allocator.dupe(u8, id) else null;
        const revision = try self.allocator.create(Revision);
        errdefer self.allocator.destroy(revision);
        try self.revisions.ensureUnusedCapacity(self.allocator, 1);
        return .{ .fields = fields, .revision = revision, .authority = wire.authority, .validation = wire.validation, .policy = wire.policy, .expected_revision_count = self.revisions.items.len, .owner = self };
    }

    fn validate(self: *const Registry, prepared: *const Prepared) bool {
        const mutable_self = @constCast(self);
        mutable_self.lockProjection();
        defer mutable_self.unlockProjection();
        return prepared.owner == self and prepared.expected_revision_count == self.revisions.items.len;
    }

    fn commitChecked(self: *Registry, prepared: *Prepared, record: event_store.RecordView) !void {
        self.lockProjection();
        defer self.unlockProjection();
        if (prepared.owner != self) return error.PreparedRegistryMismatch;
        if (prepared.expected_revision_count != self.revisions.items.len) return error.PreparedRegistryStale;
        self.commitUnlocked(prepared, record);
    }

    fn commitUnlocked(self: *Registry, prepared: *Prepared, _: event_store.RecordView) void {
        if (prepared.revision) |revision| {
            revision.* = prepared.fields.take(prepared.authority, prepared.validation, prepared.policy);
            self.revisions.appendAssumeCapacity(revision);
            prepared.revision = null;
        }
        prepared.committed = true;
    }

    fn appendWire(self: *Registry, wire: WireRevision, expected_authority: Authority, kind: event_store.EventKind, settlement: event_store.Settlement, durable_event_id: []const u8) !void {
        var prepared = try self.prepareWire(wire, expected_authority, kind, settlement, durable_event_id);
        defer prepared.deinit(self.allocator);
        self.commitUnlocked(&prepared, undefined);
    }

    fn findLatest(self: *const Registry, instruction_id: []const u8) ?*const Revision {
        var index = self.revisions.items.len;
        while (index > 0) {
            index -= 1;
            if (std.mem.eql(u8, self.revisions.items[index].instruction_id, instruction_id)) return self.revisions.items[index];
        }
        return null;
    }

    fn findVersion(self: *const Registry, instruction_id: []const u8, version_id: []const u8) ?*const Revision {
        for (self.revisions.items) |revision| if (std.mem.eql(u8, revision.instruction_id, instruction_id) and std.mem.eql(u8, revision.version_id, version_id)) return revision;
        return null;
    }

    fn effectiveRevision(self: *const Registry, instruction_id: []const u8) ?*const Revision {
        var index = self.revisions.items.len;
        while (index > 0) {
            index -= 1;
            const revision = self.revisions.items[index];
            if (std.mem.eql(u8, revision.instruction_id, instruction_id) and revision.validation == .valid and revision.policy == .approved) return revision;
        }
        return null;
    }
};

pub const InstructionRegistry = Registry;

fn authorityForKind(kind: event_store.EventKind) ?Authority {
    return switch (kind) {
        .user_constraint_added => .user_constraint,
        .user_constraint_superseded => .user_superseded,
        .agent_adaptation_revised => .agent_adaptation,
        .agent_adaptation_rolled_back => .agent_rollback,
        else => null,
    };
}

const RevisionRequest = struct {
    instruction_id: []const u8,
    authority: Authority,
    content: []const u8,
    provenance: []const u8,
    validator: []const u8,
    validator_version: []const u8,
    validation: ValidationResult,
    policy: PolicyDecision,
    rollback_target: ?[]const u8 = null,
};

const OwnedRevisionFields = struct {
    instruction_id: ?[]u8 = null,
    version_id: ?[]u8 = null,
    predecessor_version_id: ?[]u8 = null,
    normalized_content: ?[]u8 = null,
    deterministic_diff: ?[]u8 = null,
    provenance: ?[]u8 = null,
    validator: ?[]u8 = null,
    validator_version: ?[]u8 = null,
    rollback_target: ?[]u8 = null,

    fn deinit(self: *OwnedRevisionFields, allocator: std.mem.Allocator) void {
        if (self.instruction_id) |value| allocator.free(value);
        if (self.version_id) |value| allocator.free(value);
        if (self.predecessor_version_id) |value| allocator.free(value);
        if (self.normalized_content) |value| allocator.free(value);
        if (self.deterministic_diff) |value| allocator.free(value);
        if (self.provenance) |value| allocator.free(value);
        if (self.validator) |value| allocator.free(value);
        if (self.validator_version) |value| allocator.free(value);
        if (self.rollback_target) |value| allocator.free(value);
        self.* = .{};
    }

    fn take(self: *OwnedRevisionFields, authority: Authority, validation: ValidationResult, policy: PolicyDecision) Revision {
        const revision: Revision = .{
            .instruction_id = self.instruction_id.?,
            .version_id = self.version_id.?,
            .predecessor_version_id = self.predecessor_version_id,
            .authority = authority,
            .normalized_content = self.normalized_content.?,
            .deterministic_diff = self.deterministic_diff.?,
            .provenance = self.provenance.?,
            .validator = self.validator.?,
            .validator_version = self.validator_version.?,
            .validation = validation,
            .policy = policy,
            .rollback_target = self.rollback_target,
        };
        self.* = .{};
        return revision;
    }
};

const Prepared = struct {
    fields: OwnedRevisionFields,
    revision: ?*Revision,
    owner: ?*Registry = null,
    authority: Authority,
    validation: ValidationResult,
    policy: PolicyDecision,
    expected_revision_count: usize,
    committed: bool = false,

    pub fn matchesProvenance(self: *const Prepared, provenance: []const u8) bool {
        const expected = self.fields.provenance orelse return true;
        return std.mem.eql(u8, expected, provenance);
    }

    pub fn deinit(self: *Prepared, allocator: std.mem.Allocator) void {
        if (self.committed) return;
        self.fields.deinit(allocator);
        if (self.revision) |revision| allocator.destroy(revision);
        self.* = undefined;
    }
};

const WireRevision = struct {
    instruction_id: []const u8,
    version_id: []const u8,
    predecessor_version_id: ?[]const u8 = null,
    authority: Authority,
    content: []const u8,
    diff: []const u8,
    provenance: []const u8,
    validator: []const u8,
    validator_version: []const u8,
    validation: ValidationResult,
    policy: PolicyDecision,
    rollback_target: ?[]const u8 = null,
    user_decision_ref: ?[]const u8 = null,
    user_decision_digest: ?[]const u8 = null,
    user_decision_id: ?[]const u8 = null,
    workspace_id: ?[]const u8 = null,
    actor_id: ?[]const u8 = null,
    causal_request_id: ?[]const u8 = null,
    decision_expires_at: ?u64 = null,

    fn from(revision: *const Revision) WireRevision {
        return .{
            .instruction_id = revision.instruction_id,
            .version_id = revision.version_id,
            .predecessor_version_id = revision.predecessor_version_id,
            .authority = revision.authority,
            .content = revision.normalized_content,
            .diff = revision.deterministic_diff,
            .provenance = revision.provenance,
            .validator = revision.validator,
            .validator_version = revision.validator_version,
            .validation = revision.validation,
            .policy = revision.policy,
            .rollback_target = revision.rollback_target,
        };
    }
};

const WireEffective = struct {
    instruction_id: []const u8,
    version_id: []const u8,
    authority: Authority,
    content: []const u8,
};

fn refView(ref: event_store.RecordRef) event_store.RecordView {
    return ref.view() catch unreachable;
}

fn applyVisitor(context: *anyopaque, record: event_store.RecordView) !void {
    const registry: *Registry = @ptrCast(@alignCast(context));
    try registry.applyCommittedRecord(record);
}

const ProjectionAppendContext = struct {
    registry: *Registry,
    prepared: *Prepared,
};

fn noOpPrepare(_: *anyopaque, _: event_store.Event, _: event_store.Settlement) !void {}

fn validateAppendVisitor(context: *anyopaque) bool {
    const append_context: *ProjectionAppendContext = @ptrCast(@alignCast(context));
    return append_context.registry.validate(append_context.prepared);
}

fn commitAppendVisitor(context: *anyopaque, record: event_store.RecordView) void {
    const append_context: *ProjectionAppendContext = @ptrCast(@alignCast(context));
    append_context.registry.commitChecked(append_context.prepared, record) catch unreachable;
}

fn validateIdentity(instruction_id: []const u8, content: []const u8, provenance: []const u8, validator: []const u8, validator_version: []const u8) !void {
    if (instruction_id.len == 0 or instruction_id.len > max_identifier_bytes) return error.InvalidInstructionId;
    if (content.len == 0 or content.len > max_content_bytes) return error.InvalidContent;
    if (provenance.len == 0 or provenance.len > max_provenance_bytes) return error.InvalidProvenance;
    if (validator.len == 0 or validator.len > max_identifier_bytes) return error.InvalidValidator;
    if (validator_version.len == 0 or validator_version.len > max_identifier_bytes) return error.InvalidValidatorVersion;
}

fn normalize(allocator: std.mem.Allocator, content: []const u8) ![]u8 {
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);
    var lines = std.mem.splitScalar(u8, content, '\n');
    var first = true;
    while (lines.next()) |line| {
        if (!first) try output.append(allocator, '\n');
        first = false;
        try output.appendSlice(allocator, std.mem.trimEnd(u8, line, " \t\r"));
    }
    return output.toOwnedSlice(allocator);
}

fn makeDiff(allocator: std.mem.Allocator, previous: ?[]const u8, current: []const u8) ![]u8 {
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);
    if (previous) |old| {
        try output.appendSlice(allocator, "--- previous\n");
        try output.appendSlice(allocator, old);
        try output.appendSlice(allocator, "\n+++ current\n");
    } else {
        try output.appendSlice(allocator, "+++ current\n");
    }
    try output.appendSlice(allocator, current);
    return output.toOwnedSlice(allocator);
}

fn makeVersionId(
    allocator: std.mem.Allocator,
    instruction_id: []const u8,
    predecessor: ?[]const u8,
    content: []const u8,
    authority: Authority,
    provenance: []const u8,
    validator: []const u8,
    validator_version: []const u8,
    validation: ValidationResult,
    policy: PolicyDecision,
    rollback_target: ?[]const u8,
) ![]u8 {
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update("misul-instruction-v1\x00");
    hashLengthAndBytes(&hasher, instruction_id);
    hashLengthAndBytes(&hasher, predecessor orelse "");
    hashLengthAndBytes(&hasher, content);
    hashLengthAndBytes(&hasher, @tagName(authority));
    hashLengthAndBytes(&hasher, provenance);
    hashLengthAndBytes(&hasher, validator);
    hashLengthAndBytes(&hasher, validator_version);
    hashLengthAndBytes(&hasher, @tagName(validation));
    hashLengthAndBytes(&hasher, @tagName(policy));
    if (rollback_target) |target| {
        hasher.update(&[_]u8{1});
        hashLengthAndBytes(&hasher, target);
    } else {
        hasher.update(&[_]u8{0});
    }
    var digest: [32]u8 = undefined;
    hasher.final(&digest);
    const encoded = try allocator.alloc(u8, digest.len * 2);
    const hex = "0123456789abcdef";
    for (digest, 0..) |byte, index| {
        encoded[index * 2] = hex[byte >> 4];
        encoded[index * 2 + 1] = hex[byte & 0x0f];
    }
    return encoded;
}

fn hashLengthAndBytes(hasher: *std.crypto.hash.sha2.Sha256, bytes: []const u8) void {
    var length: [8]u8 = undefined;
    std.mem.writeInt(u64, &length, @intCast(bytes.len), .little);
    hasher.update(&length);
    hasher.update(bytes);
}

fn mutationDigest(
    output: *[32]u8,
    wire: WireRevision,
    kind: event_store.EventKind,
    settlement: event_store.Settlement,
    durable_event_id: []const u8,
) void {
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update("misul-instruction-mutation-v1\x00");
    var number: [8]u8 = undefined;
    std.mem.writeInt(u16, number[0..2], @intFromEnum(kind), .little);
    hasher.update(number[0..2]);
    hasher.update(&[_]u8{@intFromEnum(settlement)});
    hashLengthAndBytes(&hasher, durable_event_id);
    hashLengthAndBytes(&hasher, wire.instruction_id);
    hashLengthAndBytes(&hasher, wire.version_id);
    hashLengthAndBytes(&hasher, wire.predecessor_version_id orelse "");
    hashLengthAndBytes(&hasher, @tagName(wire.authority));
    hashLengthAndBytes(&hasher, wire.content);
    hashLengthAndBytes(&hasher, wire.diff);
    hashLengthAndBytes(&hasher, wire.provenance);
    hashLengthAndBytes(&hasher, wire.validator);
    hashLengthAndBytes(&hasher, wire.validator_version);
    hashLengthAndBytes(&hasher, @tagName(wire.validation));
    hashLengthAndBytes(&hasher, @tagName(wire.policy));
    hashLengthAndBytes(&hasher, wire.rollback_target orelse "");
    hashLengthAndBytes(&hasher, wire.user_decision_ref orelse "");
    hashLengthAndBytes(&hasher, wire.user_decision_id orelse "");
    hashLengthAndBytes(&hasher, wire.workspace_id orelse "");
    hashLengthAndBytes(&hasher, wire.actor_id orelse "");
    hashLengthAndBytes(&hasher, wire.causal_request_id orelse "");
    std.mem.writeInt(u64, &number, wire.decision_expires_at orelse 0, .little);
    hasher.update(&number);
    hasher.final(output);
}

fn parseDigest(text: []const u8) ?[32]u8 {
    if (text.len != 64) return null;
    var result: [32]u8 = undefined;
    for (0..32) |index| {
        const high = hexNibble(text[index * 2]) orelse return null;
        const low = hexNibble(text[index * 2 + 1]) orelse return null;
        result[index] = (high << 4) | low;
    }
    return result;
}

fn hexNibble(byte: u8) ?u8 {
    return switch (byte) {
        '0'...'9' => byte - '0',
        'a'...'f' => byte - 'a' + 10,
        'A'...'F' => byte - 'A' + 10,
        else => null,
    };
}

fn formatDigest(buffer: *[64]u8, digest: [32]u8) []const u8 {
    const hex = "0123456789abcdef";
    for (digest, 0..) |byte, index| {
        buffer[index * 2] = hex[byte >> 4];
        buffer[index * 2 + 1] = hex[byte & 0x0f];
    }
    return buffer;
}

fn optionalEqual(left: ?[]const u8, right: ?[]const u8) bool {
    if (left) |value| {
        if (right) |other| return std.mem.eql(u8, value, other);
        return false;
    }
    return right == null;
}

fn validIdentifier(value: []const u8) bool {
    return value.len != 0 and value.len <= max_identifier_bytes;
}

fn lessBytes(_: void, left: []const u8, right: []const u8) bool {
    return std.mem.order(u8, left, right) == .lt;
}

test "user constraints and agent adaptations retain immutable provenance and deterministic diffs" {
    const allocator = std.testing.allocator;
    var registry = Registry.init(allocator);
    defer registry.deinit();
    const user = try registry.addUserConstraint("safety", "never leak secrets\n", "user:alice", "policy", "1");
    try std.testing.expectEqual(Authority.user_constraint, user.authority);
    try std.testing.expectEqualStrings("never leak secrets\n", user.normalized_content);
    try std.testing.expectError(error.UserConstraintImmutable, registry.reviseAgent("safety", "leak", "agent:x", "policy", "1", .valid, .approved));
    const user_update = try registry.supersedeUserConstraint("safety", "never leak credentials", "user:alice", "policy", "1");
    try std.testing.expectEqual(Authority.user_superseded, user_update.authority);
    try std.testing.expectEqualStrings("never leak credentials", registry.effectiveContent("safety").?);
    const adaptation = try registry.proposeAgent("tone", "concise", "agent:planner", "policy", "1", .valid, .approved);
    _ = try registry.reviseAgent("tone", "concise and clear", "agent:planner", "policy", "1", .valid, .approved);
    try std.testing.expect(std.mem.indexOf(u8, adaptation.deterministic_diff, "+++ current") != null);
}

test "agent rollback restores exact prior effective content with authenticated target" {
    const allocator = std.testing.allocator;
    var registry = Registry.init(allocator);
    defer registry.deinit();
    _ = try registry.addUserConstraint("tone", "short", "user", "policy", "1");
    const first = try registry.proposeAgent("style", "plain", "agent", "policy", "1", .valid, .approved);
    const adaptation = try registry.reviseAgent("style", "elaborate", "agent", "policy", "1", .valid, .approved);
    const rollback = try registry.rollback("style", first.version_id, "agent");
    try std.testing.expectEqual(Authority.agent_rollback, rollback.authority);
    try std.testing.expectEqualStrings("plain", registry.effectiveContent("style").?);
    try std.testing.expectEqualStrings(first.version_id, rollback.rollback_target.?);
    try std.testing.expect(!std.mem.eql(u8, adaptation.version_id, rollback.version_id));
    const effective = try registry.serializeEffective(allocator);
    defer allocator.free(effective);
    try std.testing.expect(std.mem.indexOf(u8, effective, "plain") != null);
}

test "denied or invalid adaptation never replaces the effective revision" {
    const allocator = std.testing.allocator;
    var registry = Registry.init(allocator);
    defer registry.deinit();
    const first = try registry.proposeAgent("tone", "plain", "agent", "policy", "1", .valid, .approved);
    const denied = try registry.reviseAgent("tone", "new", "agent", "policy", "1", .invalid, .denied);
    try std.testing.expectEqualStrings("plain", registry.effectiveContent("tone").?);
    try std.testing.expectError(error.RollbackTargetInactive, registry.rollback("tone", denied.version_id, "agent"));
    _ = first;
    _ = try registry.addUserConstraint("safety", "never leak", "user", "policy", "1");
    try std.testing.expectError(error.UserConstraintImmutable, registry.reviseAgent("safety", "new", "agent", "policy", "1", .invalid, .denied));
}

test "revision allocation failures do not leak partially owned fields" {
    const allocator = std.testing.allocator;
    var fail_index: usize = 0;
    while (fail_index < 16) : (fail_index += 1) {
        var failing = std.testing.FailingAllocator.init(allocator, .{ .fail_index = fail_index });
        var registry = Registry.init(failing.allocator());
        const result = registry.addUserConstraint("safety", "never leak", "user", "policy", "1");
        if (result) |revision| {
            _ = revision;
        } else |err| {
            try std.testing.expectEqual(error.OutOfMemory, err);
        }
        registry.deinit();
    }
}

test "durable revision metadata is authenticated during replay" {
    const allocator = std.testing.allocator;
    var source = Registry.init(allocator);
    defer source.deinit();
    const revision = try source.addUserConstraint("safety", "never leak", "user", "policy", "1");
    const payload = try std.json.Stringify.valueAlloc(allocator, WireRevision.from(revision), .{});
    defer allocator.free(payload);
    const provenance = std.mem.indexOf(u8, payload, "user") orelse return error.TestUnexpectedResult;
    payload[provenance] = 'U';
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "instruction-events.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var raw = try opened.store.append(.{ .kind = .user_constraint_added, .event_id = "tampered-instruction", .payload = payload }, "user", .succeeded);
    defer raw.deinit(allocator);
    var target = Registry.init(allocator);
    defer target.deinit();
    // The modified JSON is rejected as invalid persistence before any
    // authority decision can be considered.  This keeps metadata tampering a
    // distinct red from the missing typed user-decision proof below.
    try std.testing.expectError(error.InvalidPersistence, target.replay(&opened.store));
}

test "durable user revision requires typed decision authority" {
    const allocator = std.testing.allocator;
    var source = Registry.init(allocator);
    defer source.deinit();
    const revision = try source.addUserConstraint("safety", "never leak", "user", "policy", "1");
    const payload = try std.json.Stringify.valueAlloc(allocator, WireRevision.from(revision), .{});
    defer allocator.free(payload);
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "instruction-events.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var raw = try opened.store.append(.{ .kind = .user_constraint_added, .event_id = "missing-decision", .payload = payload }, "user", .succeeded);
    defer raw.deinit(allocator);
    var target = Registry.init(allocator);
    defer target.deinit();
    try std.testing.expectError(error.UserDecisionRequired, target.replay(&opened.store));
}

fn acceptUserDecision(context: *anyopaque, decision: UserDecisionRef, authority: Authority) bool {
    return authority == .user_constraint and std.mem.eql(u8, decision.reference, "decision-1") and
        std.mem.eql(u8, decision.decision_id, "decision-1") and
        std.mem.eql(u8, decision.durable_event_id, "instruction-1") and
        std.mem.eql(u8, decision.workspace_id, "workspace-a") and
        std.mem.eql(u8, decision.actor_id, "actor-a") and
        std.mem.eql(u8, decision.causal_request_id, "request-1") and
        decision.expires_at == 1234 and decision.proof == @as(*const anyopaque, @ptrCast(context));
}

const ExactDecisionProof = struct {
    mutation_digest: [32]u8,
};

fn acceptExactUserDecision(context: *anyopaque, decision: UserDecisionRef, authority: Authority) bool {
    const proof: *const ExactDecisionProof = @ptrCast(@alignCast(context));
    return authority == .user_constraint and decision.proof == @as(*const anyopaque, @ptrCast(context)) and
        std.mem.eql(u8, &decision.mutation_digest, &proof.mutation_digest);
}

test "registry uses the shared event stream and ignores unrelated kinds" {
    const allocator = std.testing.allocator;
    var source = Registry.init(allocator);
    defer source.deinit();
    const revision = try source.addUserConstraint("safety", "never leak", "user", "policy", "1");
    var wire = WireRevision.from(revision);
    wire.user_decision_ref = "decision-1";
    wire.user_decision_id = "decision-1";
    wire.workspace_id = "workspace-a";
    wire.actor_id = "actor-a";
    wire.causal_request_id = "request-1";
    wire.decision_expires_at = 1234;
    var digest_buffer: [64]u8 = undefined;
    var digest: [32]u8 = undefined;
    mutationDigest(&digest, wire, .user_constraint_added, .succeeded, "instruction-1");
    wire.user_decision_digest = formatDigest(&digest_buffer, digest);
    const payload = try std.json.Stringify.valueAlloc(allocator, wire, .{});
    defer allocator.free(payload);
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "instruction-events.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    var target = Registry.init(allocator);
    defer target.deinit();
    var proof: u8 = 0;
    try target.bindUserDecisionAuthority(@ptrCast(&proof), @ptrCast(&proof), acceptUserDecision);
    var first = try target.appendAndApply(&opened.store, .{ .kind = .user_constraint_added, .event_id = "instruction-1", .payload = payload }, "user", .succeeded);
    defer first.deinit(allocator);
    var unrelated = try opened.store.append(.{ .kind = .task_created, .event_id = "task-1", .payload = "not an instruction" }, "scheduler", .succeeded);
    defer unrelated.deinit(allocator);
    opened.close();
    var reopened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer reopened.close();
    var replayed = Registry.init(allocator);
    defer replayed.deinit();
    try replayed.bindUserDecisionAuthority(@ptrCast(&proof), @ptrCast(&proof), acceptUserDecision);
    const report = try replayed.replay(&reopened.store);
    try std.testing.expectEqual(@as(u64, 2), report.record_count);
    try std.testing.expectEqualStrings("never leak", replayed.effectiveContent("safety").?);
}

test "user decision digest rejects an otherwise valid but unrelated authority claim" {
    const allocator = std.testing.allocator;
    var source = Registry.init(allocator);
    defer source.deinit();
    const revision = try source.addUserConstraint("safety", "never leak", "user", "policy", "1");
    var wire = WireRevision.from(revision);
    wire.user_decision_ref = "decision-1";
    wire.user_decision_id = "decision-1";
    wire.workspace_id = "workspace-a";
    wire.actor_id = "actor-a";
    wire.causal_request_id = "request-1";
    wire.decision_expires_at = 1234;
    wire.user_decision_digest = "0000000000000000000000000000000000000000000000000000000000000000";
    const payload = try std.json.Stringify.valueAlloc(allocator, wire, .{});
    defer allocator.free(payload);
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "authority-digest.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var raw = try opened.store.append(.{ .kind = .user_constraint_added, .event_id = "authority-digest", .payload = payload }, "user", .succeeded);
    defer raw.deinit(allocator);
    var target = Registry.init(allocator);
    defer target.deinit();
    var proof: u8 = 0;
    try target.bindUserDecisionAuthority(@ptrCast(&proof), @ptrCast(&proof), acceptUserDecision);
    try std.testing.expectError(error.UserDecisionRejected, target.replay(&opened.store));
    try std.testing.expectEqual(@as(usize, 0), target.revisions.items.len);
}

test "recomputed mutation cannot reuse an exact decision reference" {
    const allocator = std.testing.allocator;
    var source = Registry.init(allocator);
    defer source.deinit();
    const revision = try source.addUserConstraint("safety", "never leak", "user", "policy", "1");
    var wire = WireRevision.from(revision);
    wire.user_decision_ref = "decision-1";
    wire.user_decision_id = "decision-1";
    wire.workspace_id = "workspace-a";
    wire.actor_id = "actor-a";
    wire.causal_request_id = "request-1";
    wire.decision_expires_at = 1234;
    var original_digest: [32]u8 = undefined;
    mutationDigest(&original_digest, wire, .user_constraint_added, .succeeded, "instruction-original");

    wire.content = "never leak credentials";
    const changed_diff = try makeDiff(allocator, null, wire.content);
    defer allocator.free(changed_diff);
    wire.diff = changed_diff;
    const changed_version = try makeVersionId(allocator, wire.instruction_id, null, wire.content, wire.authority, wire.provenance, wire.validator, wire.validator_version, wire.validation, wire.policy, wire.rollback_target);
    defer allocator.free(changed_version);
    wire.version_id = changed_version;
    var changed_digest: [32]u8 = undefined;
    mutationDigest(&changed_digest, wire, .user_constraint_added, .succeeded, "instruction-forged");
    var digest_buffer: [64]u8 = undefined;
    wire.user_decision_digest = formatDigest(&digest_buffer, changed_digest);
    const payload = try std.json.Stringify.valueAlloc(allocator, wire, .{});
    defer allocator.free(payload);

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "decision-reuse.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var raw = try opened.store.append(.{ .kind = .user_constraint_added, .event_id = "instruction-forged", .payload = payload }, "user", .succeeded);
    defer raw.deinit(allocator);
    var target = Registry.init(allocator);
    defer target.deinit();
    var proof = ExactDecisionProof{ .mutation_digest = original_digest };
    try target.bindUserDecisionAuthority(@ptrCast(&proof), @ptrCast(&proof), acceptExactUserDecision);
    try std.testing.expectError(error.UserDecisionRejected, target.replay(&opened.store));
    try std.testing.expectEqual(@as(usize, 0), target.revisions.items.len);
}

test "stale prepared revision cannot overwrite a committed preparation" {
    const allocator = std.testing.allocator;
    var source = Registry.init(allocator);
    defer source.deinit();
    _ = try source.proposeAgent("tone", "plain", "agent", "policy", "1", .valid, .approved);
    const candidate = try source.reviseAgent("tone", "clear", "agent", "policy", "1", .valid, .approved);
    const payload = try std.json.Stringify.valueAlloc(allocator, WireRevision.from(candidate), .{});
    defer allocator.free(payload);

    var target = Registry.init(allocator);
    defer target.deinit();
    _ = try target.proposeAgent("tone", "plain", "agent", "policy", "1", .valid, .approved);
    var first = try target.prepare(.{ .kind = .agent_adaptation_revised, .event_id = "revision-1", .payload = payload }, .succeeded);
    defer first.deinit(allocator);
    var second = try target.prepare(.{ .kind = .agent_adaptation_revised, .event_id = "revision-2", .payload = payload }, .succeeded);
    defer second.deinit(allocator);
    var foreign = Registry.init(allocator);
    defer foreign.deinit();
    try std.testing.expectError(error.PreparedRegistryMismatch, foreign.commitChecked(&first, undefined));
    try std.testing.expect(target.validate(&first));
    try std.testing.expect(target.validate(&second));
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "prepared-revision.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();
    var committed = try opened.store.append(.{ .kind = .agent_adaptation_revised, .event_id = "revision-1", .payload = payload }, "agent", .succeeded);
    defer committed.deinit(allocator);
    try target.commitChecked(&first, refView(committed));
    try std.testing.expect(!target.validate(&second));
    try std.testing.expectError(error.PreparedRegistryStale, target.commitChecked(&second, undefined));
    try std.testing.expectEqual(@as(usize, 2), target.revisions.items.len);
    try std.testing.expectEqualStrings("clear", target.effectiveContent("tone").?);
}

test "durable instruction append binds kind provenance and settlement before writing" {
    const allocator = std.testing.allocator;
    var source = Registry.init(allocator);
    defer source.deinit();
    const revision = try source.proposeAgent("tone", "plain", "agent", "policy", "1", .valid, .approved);
    const payload = try std.json.Stringify.valueAlloc(allocator, WireRevision.from(revision), .{});
    defer allocator.free(payload);
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "instruction-append-contract.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();

    const before = try opened.store.file.length(std.testing.io);
    try std.testing.expectError(error.InvalidPersistence, source.appendAndApply(
        &opened.store,
        .{ .kind = .user_constraint_added, .event_id = "wrong-kind", .payload = payload },
        "agent",
        .succeeded,
    ));
    try std.testing.expectEqual(before, try opened.store.file.length(std.testing.io));

    try std.testing.expectError(error.InvalidPersistence, source.appendAndApply(
        &opened.store,
        .{ .kind = .agent_adaptation_revised, .event_id = "wrong-provenance", .payload = payload },
        "runtime:forged",
        .succeeded,
    ));
    try std.testing.expectEqual(before, try opened.store.file.length(std.testing.io));

    try std.testing.expectError(error.InvalidSettlement, source.appendAndApply(
        &opened.store,
        .{ .kind = .agent_adaptation_revised, .event_id = "wrong-settlement", .payload = payload },
        "agent",
        .failed,
    ));
    try std.testing.expectEqual(before, try opened.store.file.length(std.testing.io));
}

const RegistryReadContext = struct {
    registry: *Registry,
    done: *std.atomic.Value(bool),
    failures: *std.atomic.Value(usize),
};

fn registryReadWorker(context: *RegistryReadContext) void {
    while (!context.done.load(.acquire)) {
        if (context.registry.latest("tone") == null or context.registry.effectiveContent("tone") == null) {
            _ = context.failures.fetchAdd(1, .acq_rel);
            continue;
        }
        const effective = context.registry.effectiveSet(std.heap.smp_allocator) catch {
            _ = context.failures.fetchAdd(1, .acq_rel);
            continue;
        };
        defer std.heap.smp_allocator.free(effective);
        if (effective.len != 1 or !std.mem.eql(u8, effective[0].instruction_id, "tone")) {
            _ = context.failures.fetchAdd(1, .acq_rel);
        }
    }
}

test "registry readers are serialized with concurrent durable projection commits" {
    var registry = Registry.init(std.heap.smp_allocator);
    defer registry.deinit();
    _ = try registry.proposeAgent("tone", "revision-0", "agent", "policy", "1", .valid, .approved);
    var done: std.atomic.Value(bool) = .init(false);
    var failures: std.atomic.Value(usize) = .init(0);
    var context = RegistryReadContext{ .registry = &registry, .done = &done, .failures = &failures };
    var reader = try std.Thread.spawn(.{}, registryReadWorker, .{&context});
    var index: usize = 1;
    while (index <= 256) : (index += 1) {
        var content_buffer: [32]u8 = undefined;
        const content = try std.fmt.bufPrint(&content_buffer, "revision-{d}", .{index});
        _ = try registry.reviseAgent("tone", content, "agent", "policy", "1", .valid, .approved);
    }
    done.store(true, .release);
    reader.join();
    try std.testing.expectEqual(@as(usize, 0), failures.load(.acquire));
    try std.testing.expectEqualStrings("revision-256", registry.effectiveContent("tone").?);
}
