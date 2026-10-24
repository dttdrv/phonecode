const std = @import("std");
const action_receipt = @import("action_receipt.zig");
const event_store = @import("event_store.zig");

pub const Phase = enum { requested, resolved, dispatch_begun, settled };
pub const Decision = enum { allow, deny };

pub const Policy = struct {
    pub fn evaluate(_: Policy, descriptor: action_receipt.Descriptor, now_ms: u64, rpc_session_active: bool) Decision {
        const rpc_user = std.mem.startsWith(u8, descriptor.source_request_id, "rpc:") and
            std.mem.eql(u8, descriptor.source_request_id, descriptor.causal_request_id) and
            std.mem.eql(u8, descriptor.source_request_id, descriptor.run_id) and
            descriptor.session_id.len != 0 and std.mem.eql(u8, descriptor.actor_id, "local-user") and
            std.mem.eql(u8, descriptor.provenance, "rpc:local-user");
        const runtime_preflight = descriptor.capability == .system_use_permissions and std.mem.eql(u8, descriptor.actor_id, "runtime") and std.mem.eql(u8, descriptor.provenance, "runtime:system-use-preflight");
        if (descriptor.authority != .local_user or
            (!rpc_user and !runtime_preflight) or (rpc_user and !rpc_session_active) or
            !std.mem.eql(u8, descriptor.owner_id, "builtin:system-use") or
            descriptor.owner_version != 1 or
            descriptor.owner_generation != 1 or
            descriptor.expires_at_ms <= now_ms or
            descriptor.expires_at_ms - now_ms > 30_000)
        {
            return .deny;
        }
        return switch (descriptor.capability) {
            .system_use_permissions, .system_use_list, .system_use_observe => if (descriptor.effect == .inspect) .allow else .deny,
            .system_use_screenshot => if (descriptor.effect == .capture) .allow else .deny,
            .system_use_interact => if (descriptor.effect == .input) .allow else .deny,
            .system_use_wait => if (descriptor.effect == .wait) .allow else .deny,
            .browser_observe => if (descriptor.effect == .inspect) .allow else .deny,
            .browser_interact => if (descriptor.effect == .input) .allow else .deny,
        };
    }
};

pub const Entry = struct {
    action_id: []u8,
    descriptor_hash: action_receipt.Digest,
    phase: Phase,
    decision: ?Decision = null,
    decision_id: ?[]u8 = null,
    decision_provenance: ?[]u8 = null,
    approver_id: ?[]u8 = null,
    authority: ?action_receipt.Authority = null,
    lease_id: ?[]u8 = null,
    status: ?action_receipt.Status = null,
    causal_request_id: ?[]u8 = null,
    source_request_id: ?[]u8 = null,
    run_id: ?[]u8 = null,
    session_id: ?[]u8 = null,
    actor_id: ?[]u8 = null,
    descriptor_provenance: ?[]u8 = null,
    workspace_id: ?[]u8 = null,
    owner_id: ?[]u8 = null,
    target: ?[]u8 = null,
    arguments_hash: action_receipt.Digest = @splat(0),
    owner_version: u32 = 0,
    owner_generation: u64 = 0,
    capability: ?action_receipt.Capability = null,
    effect: ?action_receipt.Effect = null,
    descriptor_authority: ?action_receipt.Authority = null,
    requested_at_ms: u64 = 0,
    decided_at_ms: u64 = 0,
    dispatch_begun_at_ms: u64 = 0,
    expires_at_ms: u64 = 0,
    cancellation_generation: u64 = 0,

    fn deinit(self: *Entry, allocator: std.mem.Allocator) void {
        allocator.free(self.action_id);
        if (self.decision_id) |value| allocator.free(value);
        if (self.decision_provenance) |value| allocator.free(value);
        if (self.approver_id) |value| allocator.free(value);
        if (self.lease_id) |value| allocator.free(value);
        if (self.causal_request_id) |value| allocator.free(value);
        if (self.source_request_id) |value| allocator.free(value);
        if (self.run_id) |value| allocator.free(value);
        if (self.session_id) |value| allocator.free(value);
        if (self.actor_id) |value| allocator.free(value);
        if (self.descriptor_provenance) |value| allocator.free(value);
        if (self.workspace_id) |value| allocator.free(value);
        if (self.owner_id) |value| allocator.free(value);
        if (self.target) |value| allocator.free(value);
        self.* = undefined;
    }
};

pub const Broker = struct {
    allocator: std.mem.Allocator,
    policy: Policy = .{},
    entries: std.ArrayList(Entry) = .empty,

    pub fn init(allocator: std.mem.Allocator) Broker {
        return .{ .allocator = allocator };
    }

    pub fn deinit(self: *Broker) void {
        for (self.entries.items) |*entry| entry.deinit(self.allocator);
        self.entries.deinit(self.allocator);
        self.* = undefined;
    }

    pub fn evaluate(self: *const Broker, descriptor: action_receipt.Descriptor, now_ms: u64, rpc_session_active: bool) !Decision {
        try descriptor.validate();
        return self.policy.evaluate(descriptor, now_ms, rpc_session_active);
    }

    pub fn find(self: *Broker, action_id: []const u8) ?*Entry {
        for (self.entries.items) |*entry| if (std.mem.eql(u8, entry.action_id, action_id)) return entry;
        return null;
    }

    pub fn applyRequested(self: *Broker, action_id: []const u8, descriptor_hash: action_receipt.Digest) !void {
        if (self.find(action_id) != null) return error.ActionAlreadyExists;
        try self.entries.append(self.allocator, .{
            .action_id = try self.allocator.dupe(u8, action_id),
            .descriptor_hash = descriptor_hash,
            .phase = .requested,
        });
    }

    pub fn applyDecided(self: *Broker, action_id: []const u8, descriptor_hash: action_receipt.Digest, decision_id: []const u8, decision: Decision, authority: action_receipt.Authority, approver_id: []const u8, provenance: []const u8) !void {
        const entry = self.find(action_id) orelse return error.ActionNotRequested;
        if (entry.phase != .requested or !std.mem.eql(u8, &entry.descriptor_hash, &descriptor_hash) or decision_id.len == 0 or approver_id.len == 0 or provenance.len == 0) return error.ActionStateMismatch;
        entry.decision_id = try self.allocator.dupe(u8, decision_id);
        errdefer {
            self.allocator.free(entry.decision_id.?);
            entry.decision_id = null;
        }
        entry.decision_provenance = try self.allocator.dupe(u8, provenance);
        errdefer {
            self.allocator.free(entry.decision_provenance.?);
            entry.decision_provenance = null;
        }
        entry.approver_id = try self.allocator.dupe(u8, approver_id);
        entry.authority = authority;
        entry.decision = decision;
        entry.phase = .resolved;
    }

    pub fn applyDispatchBegun(self: *Broker, action_id: []const u8, descriptor_hash: action_receipt.Digest, lease_id: []const u8) !void {
        const entry = self.find(action_id) orelse return error.ActionNotRequested;
        if (entry.phase != .resolved or entry.decision != .allow or !std.mem.eql(u8, &entry.descriptor_hash, &descriptor_hash)) return error.ActionStateMismatch;
        entry.lease_id = try self.allocator.dupe(u8, lease_id);
        entry.phase = .dispatch_begun;
    }

    pub fn applySettled(self: *Broker, action_id: []const u8, descriptor_hash: action_receipt.Digest, status: action_receipt.Status) !void {
        const entry = self.find(action_id) orelse return error.ActionNotRequested;
        if (!std.mem.eql(u8, &entry.descriptor_hash, &descriptor_hash)) return error.ActionStateMismatch;
        if (entry.phase == .settled) return error.ActionAlreadySettled;
        if (entry.phase == .requested) return error.ActionStateMismatch;
        if (entry.phase == .resolved and entry.decision == .allow and status != .cancelled and status != .timeout and status != .outcome_unknown) return error.ActionStateMismatch;
        if (entry.phase == .resolved and entry.decision == .deny and status != .policy_denial and status != .provenance_failure and status != .authorization_failure and status != .cancelled and status != .timeout) return error.ActionStateMismatch;
        entry.phase = .settled;
        entry.status = status;
    }

    pub fn validateReserve(self: *Broker, action_id: []const u8, descriptor_hash: action_receipt.Digest, decision_id: []const u8, authority: action_receipt.Authority, approver_id: []const u8, provenance: []const u8, now_ms: u64, cancellation_generation: u64, owner_generation: u64) !void {
        const entry = self.find(action_id) orelse return error.ActionNotRequested;
        if (entry.phase != .resolved or entry.decision != .allow or entry.decision_id == null or
            !std.mem.eql(u8, entry.decision_id.?, decision_id) or
            entry.authority == null or entry.authority.? != authority or entry.approver_id == null or
            !std.mem.eql(u8, entry.approver_id.?, approver_id) or entry.decision_provenance == null or
            !std.mem.eql(u8, entry.decision_provenance.?, provenance) or
            !std.mem.eql(u8, &entry.descriptor_hash, &descriptor_hash) or
            entry.expires_at_ms <= now_ms or entry.cancellation_generation != cancellation_generation or
            entry.owner_generation != owner_generation) return error.ActionNotAuthorized;
    }

    pub fn orphanedDispatchCount(self: *const Broker) usize {
        var count: usize = 0;
        for (self.entries.items) |entry| {
            if (entry.phase == .dispatch_begun) count += 1;
        }
        return count;
    }

    pub fn applyCommittedRecord(self: *Broker, record: event_store.RecordView) !void {
        try record.verifyWire();
        switch (record.kind) {
            .action_requested => {
                var parsed = try std.json.parseFromSlice(RequestedWire, self.allocator, record.payload, .{});
                defer parsed.deinit();
                if (parsed.value.schema != action_receipt.schema) return error.ActionReceiptMismatch;
                if (parsed.value.requested_at_ms == 0 or parsed.value.requested_at_ms >= parsed.value.expires_at_ms) return error.ActionReceiptMismatch;
                const canonical = try action_receipt.canonicalizeArguments(self.allocator, parsed.value.canonical_arguments);
                defer self.allocator.free(canonical);
                if (!std.mem.eql(u8, canonical, parsed.value.canonical_arguments)) return error.ActionReceiptMismatch;
                const arguments_hash = try parseDigest(parsed.value.arguments_hash);
                var computed_arguments_hash: action_receipt.Digest = undefined;
                std.crypto.hash.sha2.Sha256.hash(parsed.value.canonical_arguments, &computed_arguments_hash, .{});
                if (!std.mem.eql(u8, &arguments_hash, &computed_arguments_hash)) return error.ActionReceiptMismatch;
                const descriptor = action_receipt.Descriptor{
                    .action_id = parsed.value.action_id,
                    .causal_request_id = parsed.value.causal_request_id,
                    .source_request_id = parsed.value.source_request_id,
                    .run_id = parsed.value.run_id,
                    .session_id = parsed.value.session_id,
                    .actor_id = parsed.value.actor_id,
                    .provenance = parsed.value.provenance,
                    .workspace_id = parsed.value.workspace_id,
                    .owner_id = parsed.value.owner_id,
                    .owner_version = parsed.value.owner_version,
                    .owner_generation = parsed.value.owner_generation,
                    .capability = parsed.value.capability,
                    .effect = parsed.value.effect,
                    .target = parsed.value.target,
                    .canonical_arguments = parsed.value.canonical_arguments,
                    .authority = parsed.value.authority,
                    .expires_at_ms = parsed.value.expires_at_ms,
                    .cancellation_generation = parsed.value.cancellation_generation,
                };
                const descriptor_hash = try parseDigest(parsed.value.descriptor_hash);
                if (!std.mem.eql(u8, &descriptor_hash, &(try descriptor.digest()))) return error.ActionReceiptMismatch;
                try self.applyRequested(parsed.value.action_id, descriptor_hash);
                const entry = self.find(parsed.value.action_id).?;
                entry.causal_request_id = try self.allocator.dupe(u8, parsed.value.causal_request_id);
                entry.source_request_id = try self.allocator.dupe(u8, parsed.value.source_request_id);
                entry.run_id = try self.allocator.dupe(u8, parsed.value.run_id);
                entry.session_id = try self.allocator.dupe(u8, parsed.value.session_id);
                entry.actor_id = try self.allocator.dupe(u8, parsed.value.actor_id);
                entry.descriptor_provenance = try self.allocator.dupe(u8, parsed.value.provenance);
                entry.workspace_id = try self.allocator.dupe(u8, parsed.value.workspace_id);
                entry.owner_id = try self.allocator.dupe(u8, parsed.value.owner_id);
                entry.target = try self.allocator.dupe(u8, parsed.value.target);
                entry.arguments_hash = arguments_hash;
                entry.owner_version = parsed.value.owner_version;
                entry.owner_generation = parsed.value.owner_generation;
                entry.capability = parsed.value.capability;
                entry.effect = parsed.value.effect;
                entry.descriptor_authority = parsed.value.authority;
                entry.requested_at_ms = parsed.value.requested_at_ms;
                entry.expires_at_ms = parsed.value.expires_at_ms;
                entry.cancellation_generation = parsed.value.cancellation_generation;
            },
            .action_decided => {
                var parsed = try std.json.parseFromSlice(DecidedWire, self.allocator, record.payload, .{});
                defer parsed.deinit();
                if (parsed.value.schema != action_receipt.schema) return error.ActionReceiptMismatch;
                const entry = self.find(parsed.value.action_id) orelse return error.ActionNotRequested;
                if (entry.descriptor_authority == null or entry.descriptor_authority.? != parsed.value.authority or
                    entry.descriptor_provenance == null or !std.mem.eql(u8, entry.descriptor_provenance.?, parsed.value.provenance) or
                    parsed.value.decided_at_ms < entry.requested_at_ms) return error.ActionReceiptMismatch;
                try self.applyDecided(parsed.value.action_id, try parseDigest(parsed.value.descriptor_hash), parsed.value.decision_id, parsed.value.decision, parsed.value.authority, parsed.value.approver_id, parsed.value.provenance);
                self.find(parsed.value.action_id).?.decided_at_ms = parsed.value.decided_at_ms;
            },
            .action_dispatch_begun => {
                var parsed = try std.json.parseFromSlice(DispatchWire, self.allocator, record.payload, .{});
                defer parsed.deinit();
                if (parsed.value.schema != action_receipt.schema) return error.ActionReceiptMismatch;
                const entry = self.find(parsed.value.action_id) orelse return error.ActionNotRequested;
                if (entry.decision_id == null or !std.mem.eql(u8, entry.decision_id.?, parsed.value.decision_id) or
                    entry.owner_generation != parsed.value.owner_generation or entry.expires_at_ms != parsed.value.expires_at_ms or
                    entry.cancellation_generation != parsed.value.cancellation_generation or parsed.value.dispatch_begun_at_ms < entry.decided_at_ms or
                    parsed.value.dispatch_begun_at_ms >= entry.expires_at_ms) return error.ActionStateMismatch;
                try self.applyDispatchBegun(parsed.value.action_id, try parseDigest(parsed.value.descriptor_hash), parsed.value.lease_id);
                self.find(parsed.value.action_id).?.dispatch_begun_at_ms = parsed.value.dispatch_begun_at_ms;
            },
            .action_settled => {
                var parsed = try std.json.parseFromSlice(SettledWire, self.allocator, record.payload, .{});
                defer parsed.deinit();
                if (parsed.value.schema != action_receipt.schema) return error.ActionReceiptMismatch;
                const entry = self.find(parsed.value.action_id) orelse return error.ActionNotRequested;
                if (entry.decision_id == null or !std.mem.eql(u8, entry.decision_id.?, parsed.value.decision_id) or
                    !std.mem.eql(u8, entry.causal_request_id orelse "", parsed.value.causal_request_id) or
                    !std.mem.eql(u8, entry.actor_id orelse "", parsed.value.actor_id) or
                    entry.owner_generation != parsed.value.owner_generation or
                    entry.requested_at_ms != parsed.value.requested_at_ms or
                    entry.decided_at_ms != parsed.value.decided_at_ms or
                    entry.dispatch_begun_at_ms != parsed.value.dispatch_begun_at_ms or
                    (entry.lease_id != null and !std.mem.eql(u8, entry.lease_id.?, parsed.value.lease_id)) or
                    (entry.lease_id == null and parsed.value.lease_id.len != 0)) return error.ActionReceiptMismatch;
                const evidence = std.mem.trimEnd(u8, parsed.value.evidence, " ");
                try (action_receipt.Receipt{
                    .action_id = parsed.value.action_id,
                    .descriptor_hash = try parseDigest(parsed.value.descriptor_hash),
                    .decision_id = parsed.value.decision_id,
                    .lease_id = parsed.value.lease_id,
                    .actor_id = parsed.value.actor_id,
                    .owner_generation = parsed.value.owner_generation,
                    .causal_request_id = parsed.value.causal_request_id,
                    .requested_at_ms = parsed.value.requested_at_ms,
                    .decided_at_ms = parsed.value.decided_at_ms,
                    .dispatch_begun_at_ms = parsed.value.dispatch_begun_at_ms,
                    .settled_at_ms = parsed.value.settled_at_ms,
                    .status = parsed.value.status,
                    .evidence = evidence,
                }).validate();
                if (parsed.value.status == .success) try validateSuccessMethod(entry.capability orelse return error.ActionReceiptMismatch, evidence);
                try self.applySettled(parsed.value.action_id, try parseDigest(parsed.value.descriptor_hash), parsed.value.status);
            },
            else => return,
        }
    }
};

const RequestedWire = struct {
    schema: u16,
    action_id: []const u8,
    descriptor_hash: []const u8,
    causal_request_id: []const u8,
    source_request_id: []const u8,
    run_id: []const u8,
    session_id: []const u8,
    actor_id: []const u8,
    provenance: []const u8,
    workspace_id: []const u8,
    owner_id: []const u8,
    owner_version: u32,
    owner_generation: u64,
    capability: action_receipt.Capability,
    effect: action_receipt.Effect,
    target: []const u8,
    canonical_arguments: []const u8,
    arguments_hash: []const u8,
    authority: action_receipt.Authority,
    requested_at_ms: u64,
    expires_at_ms: u64,
    cancellation_generation: u64,
};
const DecidedWire = struct { schema: u16, action_id: []const u8, descriptor_hash: []const u8, decision_id: []const u8, decision: Decision, authority: action_receipt.Authority, approver_id: []const u8, provenance: []const u8, decided_at_ms: u64 };
const DispatchWire = struct {
    schema: u16,
    action_id: []const u8,
    descriptor_hash: []const u8,
    decision_id: []const u8,
    lease_id: []const u8,
    owner_generation: u64,
    expires_at_ms: u64,
    cancellation_generation: u64,
    dispatch_begun_at_ms: u64,
};
const SettledWire = struct {
    schema: u16,
    action_id: []const u8,
    descriptor_hash: []const u8,
    decision_id: []const u8,
    lease_id: []const u8,
    causal_request_id: []const u8,
    actor_id: []const u8,
    owner_generation: u64,
    requested_at_ms: u64,
    decided_at_ms: u64,
    dispatch_begun_at_ms: u64,
    settled_at_ms: u64,
    status: action_receipt.Status,
    evidence: []const u8,
};

fn parseDigest(text: []const u8) !action_receipt.Digest {
    if (text.len != @sizeOf(action_receipt.Digest) * 2) return error.InvalidDescriptorHash;
    var digest: action_receipt.Digest = undefined;
    _ = std.fmt.hexToBytes(&digest, text) catch return error.InvalidDescriptorHash;
    return digest;
}

fn validateSuccessMethod(capability: action_receipt.Capability, evidence: []const u8) !void {
    const expected = switch (capability) {
        .system_use_permissions => "v1;method=permissions;",
        .system_use_list => "v1;method=targets;",
        .system_use_observe, .system_use_screenshot => "v1;method=observe;",
        .system_use_interact => if (std.mem.startsWith(u8, evidence, "v1;method=click;")) "v1;method=click;" else if (std.mem.startsWith(u8, evidence, "v1;method=type;")) "v1;method=type;" else if (std.mem.startsWith(u8, evidence, "v1;method=scroll;")) "v1;method=scroll;" else "v1;method=setValue;",
        .system_use_wait => "v1;method=wait;",
        .browser_observe => blk: {
            if (std.mem.startsWith(u8, evidence, "v1;method=browser_snapshot;")) break :blk "v1;method=browser_snapshot;";
            if (std.mem.startsWith(u8, evidence, "v1;method=browser_wait;")) break :blk "v1;method=browser_wait;";
            if (std.mem.startsWith(u8, evidence, "v1;method=browser_extract;")) break :blk "v1;method=browser_extract;";
            if (std.mem.startsWith(u8, evidence, "v1;method=browser_screenshot;")) break :blk "v1;method=browser_screenshot;";
            if (std.mem.startsWith(u8, evidence, "v1;method=browser_tabs;")) break :blk "v1;method=browser_tabs;";
            break :blk "v1;method=browser_snapshot;";
        },
        .browser_interact => "v1;method=browser_",
    };
    if (!std.mem.startsWith(u8, evidence, expected)) return error.ActionReceiptMismatch;
}

test "automatic allow and deny use the same state transitions" {
    var broker = Broker.init(std.testing.allocator);
    defer broker.deinit();
    const allowed_hash: action_receipt.Digest = @splat(1);
    try broker.applyRequested("allow", allowed_hash);
    broker.find("allow").?.expires_at_ms = 100;
    broker.find("allow").?.cancellation_generation = 1;
    broker.find("allow").?.owner_generation = 1;
    try broker.applyDecided("allow", allowed_hash, "decision-allow", .allow, .local_user, "runtime-policy", "rpc:local-user");
    try broker.validateReserve("allow", allowed_hash, "decision-allow", .local_user, "runtime-policy", "rpc:local-user", 1, 1, 1);
    try broker.applyDispatchBegun("allow", allowed_hash, "lease-allow");
    try broker.applySettled("allow", allowed_hash, .success);
    try std.testing.expectEqual(Phase.settled, broker.find("allow").?.phase);

    const denied_hash: action_receipt.Digest = @splat(2);
    try broker.applyRequested("deny", denied_hash);
    try broker.applyDecided("deny", denied_hash, "decision-deny", .deny, .local_user, "runtime-policy", "rpc:local-user");
    try std.testing.expectError(error.ActionStateMismatch, broker.applySettled("deny", denied_hash, .success));
    try broker.applySettled("deny", denied_hash, .policy_denial);
    try std.testing.expectError(error.ActionNotAuthorized, broker.validateReserve("deny", denied_hash, "decision-deny", .local_user, "runtime-policy", "rpc:local-user", 1, 1, 1));
}

test "policy decision is capability specific rather than provenance authority" {
    const descriptor = action_receipt.Descriptor{
        .action_id = "action-policy",
        .causal_request_id = "rpc:policy",
        .source_request_id = "rpc:policy",
        .run_id = "rpc:policy",
        .session_id = "session-policy",
        .actor_id = "local-user",
        .provenance = "rpc:local-user",
        .workspace_id = "workspace-policy",
        .owner_id = "builtin:system-use",
        .owner_version = 1,
        .owner_generation = 1,
        .capability = .system_use_interact,
        .effect = .input,
        .target = "pid:42",
        .canonical_arguments = "{}",
        .authority = .local_user,
        .expires_at_ms = 20_000,
        .cancellation_generation = 1,
    };
    const policy = Policy{};
    try std.testing.expectEqual(Decision.allow, policy.evaluate(descriptor, 1_000, true));
    var mismatched = descriptor;
    mismatched.effect = .inspect;
    try std.testing.expectEqual(Decision.deny, policy.evaluate(mismatched, 1_000, true));
    mismatched = descriptor;
    mismatched.provenance = "provider:model";
    try std.testing.expectEqual(Decision.deny, policy.evaluate(mismatched, 1_000, true));
    mismatched = descriptor;
    mismatched.expires_at_ms = 40_001;
    try std.testing.expectEqual(Decision.deny, policy.evaluate(mismatched, 1_000, true));
    mismatched = descriptor;
    mismatched.causal_request_id = "rpc:other";
    try std.testing.expectEqual(Decision.deny, policy.evaluate(mismatched, 1_000, true));
    mismatched = descriptor;
    mismatched.run_id = "rpc:other";
    try std.testing.expectEqual(Decision.deny, policy.evaluate(mismatched, 1_000, true));
    try std.testing.expectEqual(Decision.deny, policy.evaluate(descriptor, 1_000, false));
}

test "modified descriptor token replay and duplicate settlement fail closed" {
    var broker = Broker.init(std.testing.allocator);
    defer broker.deinit();
    const digest: action_receipt.Digest = @splat(3);
    const changed: action_receipt.Digest = @splat(4);
    try broker.applyRequested("action", digest);
    broker.find("action").?.expires_at_ms = 100;
    broker.find("action").?.cancellation_generation = 1;
    broker.find("action").?.owner_generation = 1;
    try broker.applyDecided("action", digest, "decision", .allow, .local_user, "runtime-policy", "rpc:local-user");
    try std.testing.expectError(error.ActionNotAuthorized, broker.validateReserve("action", changed, "decision", .local_user, "runtime-policy", "rpc:local-user", 1, 1, 1));
    try std.testing.expectError(error.ActionNotAuthorized, broker.validateReserve("action", digest, "wrong-decision", .local_user, "runtime-policy", "rpc:local-user", 1, 1, 1));
    try std.testing.expectError(error.ActionNotAuthorized, broker.validateReserve("action", digest, "decision", .local_user, "other-policy", "rpc:local-user", 1, 1, 1));
    try std.testing.expectError(error.ActionNotAuthorized, broker.validateReserve("action", digest, "decision", .local_user, "runtime-policy", "runtime:forged", 1, 1, 1));
    try std.testing.expectError(error.ActionNotAuthorized, broker.validateReserve("action", digest, "decision", .local_user, "runtime-policy", "rpc:local-user", 100, 1, 1));
    try std.testing.expectError(error.ActionNotAuthorized, broker.validateReserve("action", digest, "decision", .local_user, "runtime-policy", "rpc:local-user", 1, 2, 1));
    try std.testing.expectError(error.ActionNotAuthorized, broker.validateReserve("action", digest, "decision", .local_user, "runtime-policy", "rpc:local-user", 1, 1, 2));
    try broker.applyDispatchBegun("action", digest, "lease");
    try std.testing.expectError(error.ActionNotAuthorized, broker.validateReserve("action", digest, "decision", .local_user, "runtime-policy", "rpc:local-user", 1, 1, 1));
    try broker.applySettled("action", digest, .external_failure);
    try std.testing.expectError(error.ActionAlreadySettled, broker.applySettled("action", digest, .success));
}

test "dispatch begun remains consumed and is discoverable for restart reconciliation" {
    var broker = Broker.init(std.testing.allocator);
    defer broker.deinit();
    const digest: action_receipt.Digest = @splat(5);
    try broker.applyRequested("orphan", digest);
    broker.find("orphan").?.expires_at_ms = 100;
    broker.find("orphan").?.cancellation_generation = 1;
    broker.find("orphan").?.owner_generation = 1;
    try broker.applyDecided("orphan", digest, "decision", .allow, .local_user, "runtime-policy", "rpc:local-user");
    try broker.applyDispatchBegun("orphan", digest, "lease");
    try std.testing.expectEqual(@as(usize, 1), broker.orphanedDispatchCount());
    try std.testing.expectError(error.ActionNotAuthorized, broker.validateReserve("orphan", digest, "decision", .local_user, "runtime-policy", "rpc:local-user", 1, 1, 1));
}

fn replayFixtureDescriptor(action_id: []const u8) action_receipt.Descriptor {
    return .{
        .action_id = action_id,
        .causal_request_id = "rpc:replay",
        .source_request_id = "rpc:replay",
        .run_id = "rpc:replay",
        .session_id = "session-replay",
        .actor_id = "local-user",
        .provenance = "rpc:local-user",
        .workspace_id = "workspace-replay",
        .owner_id = "builtin:system-use",
        .owner_version = 1,
        .owner_generation = 1,
        .capability = .system_use_interact,
        .effect = .input,
        .target = "pid:42:window:7",
        .canonical_arguments = "{}",
        .authority = .local_user,
        .expires_at_ms = 100,
        .cancellation_generation = 3,
    };
}

fn requestedReplayPayload(allocator: std.mem.Allocator, descriptor: action_receipt.Descriptor, target: []const u8) ![]u8 {
    const descriptor_hash = try descriptor.digest();
    const arguments_hash = descriptor.argumentsHash();
    const descriptor_hex = std.fmt.bytesToHex(descriptor_hash, .lower);
    const arguments_hex = std.fmt.bytesToHex(arguments_hash, .lower);
    return std.json.Stringify.valueAlloc(allocator, .{
        .schema = action_receipt.schema,
        .action_id = descriptor.action_id,
        .descriptor_hash = &descriptor_hex,
        .causal_request_id = descriptor.causal_request_id,
        .source_request_id = descriptor.source_request_id,
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
        .target = target,
        .canonical_arguments = descriptor.canonical_arguments,
        .arguments_hash = &arguments_hex,
        .authority = descriptor.authority,
        .expires_at_ms = descriptor.expires_at_ms,
        .cancellation_generation = descriptor.cancellation_generation,
        .requested_at_ms = @as(u64, 10),
    }, .{});
}

test "replay recomputes complete descriptor and rejects field tampering" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(std.testing.allocator, &.{ path_buffer[0..path_len], "events.bin" });
    defer std.testing.allocator.free(path);
    var opened = try event_store.Store.open(std.testing.allocator, std.testing.io, path, .{});
    defer opened.store.close();
    const descriptor = replayFixtureDescriptor("action-replay");
    const valid_payload = try requestedReplayPayload(std.testing.allocator, descriptor, descriptor.target);
    defer std.testing.allocator.free(valid_payload);
    var valid_record = try opened.store.append(.{ .kind = .action_requested, .event_id = "requested-valid", .payload = valid_payload }, "permission:test", .pending);
    defer valid_record.deinit(std.testing.allocator);
    var broker = Broker.init(std.testing.allocator);
    defer broker.deinit();
    try broker.applyCommittedRecord(try valid_record.view());
    try std.testing.expectEqual(Phase.requested, broker.find(descriptor.action_id).?.phase);

    const tampered_payload = try requestedReplayPayload(std.testing.allocator, descriptor, "pid:99:window:1");
    defer std.testing.allocator.free(tampered_payload);
    var tampered_record = try opened.store.append(.{ .kind = .action_requested, .event_id = "requested-tampered", .payload = tampered_payload }, "permission:test", .pending);
    defer tampered_record.deinit(std.testing.allocator);
    var tampered_broker = Broker.init(std.testing.allocator);
    defer tampered_broker.deinit();
    try std.testing.expectError(error.ActionReceiptMismatch, tampered_broker.applyCommittedRecord(try tampered_record.view()));
}

test "replay rejects inverted decided and orphan dispatch timestamps" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(std.testing.allocator, &.{ path_buffer[0..path_len], "events.bin" });
    defer std.testing.allocator.free(path);
    var opened = try event_store.Store.open(std.testing.allocator, std.testing.io, path, .{});
    defer opened.store.close();
    const descriptor = replayFixtureDescriptor("action-order");
    const descriptor_hash = try descriptor.digest();
    const descriptor_hex = std.fmt.bytesToHex(descriptor_hash, .lower);
    const requested_payload = try requestedReplayPayload(std.testing.allocator, descriptor, descriptor.target);
    defer std.testing.allocator.free(requested_payload);
    var requested = try opened.store.append(.{ .kind = .action_requested, .event_id = "order-requested", .payload = requested_payload }, "permission:test", .pending);
    defer requested.deinit(std.testing.allocator);
    var broker = Broker.init(std.testing.allocator);
    defer broker.deinit();
    try broker.applyCommittedRecord(try requested.view());

    const early_decided_payload = try std.json.Stringify.valueAlloc(std.testing.allocator, .{ .schema = action_receipt.schema, .action_id = descriptor.action_id, .descriptor_hash = &descriptor_hex, .decision_id = "decision-order", .decision = Decision.allow, .authority = descriptor.authority, .approver_id = "runtime-policy", .provenance = descriptor.provenance, .decided_at_ms = @as(u64, 9) }, .{});
    defer std.testing.allocator.free(early_decided_payload);
    var early_decided = try opened.store.append(.{ .kind = .action_decided, .event_id = "order-early-decision", .payload = early_decided_payload }, "permission:test", .succeeded);
    defer early_decided.deinit(std.testing.allocator);
    try std.testing.expectError(error.ActionReceiptMismatch, broker.applyCommittedRecord(try early_decided.view()));

    const decided_payload = try std.json.Stringify.valueAlloc(std.testing.allocator, .{ .schema = action_receipt.schema, .action_id = descriptor.action_id, .descriptor_hash = &descriptor_hex, .decision_id = "decision-order", .decision = Decision.allow, .authority = descriptor.authority, .approver_id = "runtime-policy", .provenance = descriptor.provenance, .decided_at_ms = @as(u64, 11) }, .{});
    defer std.testing.allocator.free(decided_payload);
    var decided = try opened.store.append(.{ .kind = .action_decided, .event_id = "order-decision", .payload = decided_payload }, "permission:test", .succeeded);
    defer decided.deinit(std.testing.allocator);
    try broker.applyCommittedRecord(try decided.view());

    const dispatch_payload = try std.json.Stringify.valueAlloc(std.testing.allocator, .{ .schema = action_receipt.schema, .action_id = descriptor.action_id, .descriptor_hash = &descriptor_hex, .decision_id = "decision-order", .lease_id = "lease-order", .owner_generation = descriptor.owner_generation, .expires_at_ms = descriptor.expires_at_ms, .cancellation_generation = descriptor.cancellation_generation, .dispatch_begun_at_ms = @as(u64, 10) }, .{});
    defer std.testing.allocator.free(dispatch_payload);
    var dispatch = try opened.store.append(.{ .kind = .action_dispatch_begun, .event_id = "order-dispatch", .payload = dispatch_payload }, "permission:test", .pending);
    defer dispatch.deinit(std.testing.allocator);
    try std.testing.expectError(error.ActionStateMismatch, broker.applyCommittedRecord(try dispatch.view()));
}
