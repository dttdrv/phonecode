const std = @import("std");

pub const schema: u16 = 1;
pub const Digest = [std.crypto.hash.sha2.Sha256.digest_length]u8;
pub const maximum_identifier_bytes: usize = 256;
pub const maximum_arguments_bytes: usize = 64 * 1024;
pub const maximum_evidence_bytes: usize = 4 * 1024;

pub const Capability = enum {
    system_use_permissions,
    system_use_list,
    system_use_observe,
    system_use_screenshot,
    system_use_interact,
    system_use_wait,
    browser_observe,
    browser_interact,
};

pub const Effect = enum { inspect, capture, input, wait };
pub const Authority = enum { local_user };

pub const Descriptor = struct {
    action_id: []const u8,
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
    capability: Capability,
    effect: Effect,
    target: []const u8,
    canonical_arguments: []const u8,
    authority: Authority,
    expires_at_ms: u64,
    cancellation_generation: u64,

    pub fn validate(self: Descriptor) !void {
        inline for (.{ self.action_id, self.causal_request_id, self.source_request_id, self.run_id, self.session_id, self.actor_id, self.provenance, self.workspace_id, self.owner_id, self.target }) |value| {
            if (value.len == 0 or value.len > maximum_identifier_bytes) return error.InvalidActionDescriptor;
        }
        if (self.canonical_arguments.len == 0 or self.canonical_arguments.len > maximum_arguments_bytes or self.owner_version == 0 or self.owner_generation == 0 or self.expires_at_ms == 0) return error.InvalidActionDescriptor;
        var arena = std.heap.ArenaAllocator.init(std.heap.page_allocator);
        defer arena.deinit();
        var parsed = std.json.parseFromSlice(std.json.Value, arena.allocator(), self.canonical_arguments, .{}) catch return error.InvalidActionDescriptor;
        defer parsed.deinit();
        if (parsed.value != .object) return error.InvalidActionDescriptor;
    }

    pub fn argumentsHash(self: Descriptor) Digest {
        var value: Digest = undefined;
        std.crypto.hash.sha2.Sha256.hash(self.canonical_arguments, &value, .{});
        return value;
    }

    pub fn digest(self: Descriptor) !Digest {
        try self.validate();
        var hasher = std.crypto.hash.sha2.Sha256.init(.{});
        hashInt(&hasher, u16, schema);
        hashString(&hasher, self.action_id);
        hashString(&hasher, self.causal_request_id);
        hashString(&hasher, self.source_request_id);
        hashString(&hasher, self.run_id);
        hashString(&hasher, self.session_id);
        hashString(&hasher, self.actor_id);
        hashString(&hasher, self.provenance);
        hashString(&hasher, self.workspace_id);
        hashString(&hasher, self.owner_id);
        hashInt(&hasher, u32, self.owner_version);
        hashInt(&hasher, u64, self.owner_generation);
        hashInt(&hasher, u16, @intFromEnum(self.capability));
        hashInt(&hasher, u16, @intFromEnum(self.effect));
        hashString(&hasher, self.target);
        hashString(&hasher, self.canonical_arguments);
        hashInt(&hasher, u16, @intFromEnum(self.authority));
        hashInt(&hasher, u64, self.expires_at_ms);
        hashInt(&hasher, u64, self.cancellation_generation);
        var digest_value: Digest = undefined;
        hasher.final(&digest_value);
        return digest_value;
    }
};

pub const Status = enum {
    provenance_failure,
    authorization_failure,
    policy_denial,
    cancelled,
    timeout,
    external_failure,
    success,
    outcome_unknown,
};

pub const Receipt = struct {
    action_id: []const u8,
    descriptor_hash: Digest,
    decision_id: []const u8,
    lease_id: []const u8,
    actor_id: []const u8,
    owner_generation: u64,
    causal_request_id: []const u8,
    requested_at_ms: u64,
    decided_at_ms: u64,
    dispatch_begun_at_ms: u64,
    settled_at_ms: u64,
    status: Status,
    evidence: []const u8,

    pub fn validate(self: Receipt) !void {
        inline for (.{ self.action_id, self.decision_id, self.actor_id, self.causal_request_id }) |value| {
            if (value.len == 0 or value.len > maximum_identifier_bytes) return error.InvalidActionReceipt;
        }
        if (self.lease_id.len > maximum_identifier_bytes or self.owner_generation == 0 or self.requested_at_ms == 0 or self.decided_at_ms < self.requested_at_ms or self.settled_at_ms < self.decided_at_ms or self.evidence.len == 0 or self.evidence.len > maximum_evidence_bytes) return error.InvalidActionReceipt;
        if ((self.dispatch_begun_at_ms == 0) != (self.lease_id.len == 0) or
            (self.dispatch_begun_at_ms != 0 and (self.dispatch_begun_at_ms < self.decided_at_ms or self.settled_at_ms < self.dispatch_begun_at_ms))) return error.InvalidActionReceipt;
        if (self.status == .success and (self.lease_id.len == 0 or self.dispatch_begun_at_ms == 0)) return error.InvalidActionReceipt;
        for (self.evidence) |byte| if (byte < 0x20 or byte > 0x7e) return error.InvalidActionReceipt;
        if (!std.mem.startsWith(u8, self.evidence, "v1;")) return error.InvalidActionReceipt;
        if (self.status == .success) try validateSuccessEvidence(self.evidence);
    }
};

pub fn validateSuccessEvidence(evidence: []const u8) !void {
    if (!std.mem.startsWith(u8, evidence, "v1;method=")) return error.InvalidActionReceipt;
    const digest_marker = ";result_sha256=";
    const digest_start = (std.mem.indexOf(u8, evidence, digest_marker) orelse return error.InvalidActionReceipt) + digest_marker.len;
    if (digest_start + 64 > evidence.len) return error.InvalidActionReceipt;
    var digest: Digest = undefined;
    _ = std.fmt.hexToBytes(&digest, evidence[digest_start .. digest_start + 64]) catch return error.InvalidActionReceipt;
    const duration_marker = ";duration_us=";
    const duration_start = (std.mem.indexOf(u8, evidence, duration_marker) orelse return error.InvalidActionReceipt) + duration_marker.len;
    const duration_end = std.mem.indexOfScalarPos(u8, evidence, duration_start, ';') orelse evidence.len;
    if (duration_end == duration_start) return error.InvalidActionReceipt;
    _ = std.fmt.parseUnsigned(u64, evidence[duration_start..duration_end], 10) catch return error.InvalidActionReceipt;
}

pub fn canonicalizeArguments(allocator: std.mem.Allocator, input: []const u8) ![]u8 {
    if (input.len == 0 or input.len > maximum_arguments_bytes) return error.InvalidActionDescriptor;
    var parsed = std.json.parseFromSlice(std.json.Value, allocator, input, .{}) catch return error.InvalidActionDescriptor;
    defer parsed.deinit();
    if (parsed.value != .object) return error.InvalidActionDescriptor;
    var output: std.Io.Writer.Allocating = .init(allocator);
    errdefer output.deinit();
    try writeCanonicalValue(allocator, &output.writer, parsed.value);
    return output.toOwnedSlice();
}

fn writeCanonicalValue(allocator: std.mem.Allocator, writer: *std.Io.Writer, value: std.json.Value) !void {
    switch (value) {
        .null => try writer.writeAll("null"),
        .bool => |boolean| try writer.writeAll(if (boolean) "true" else "false"),
        .integer => |integer| try writer.print("{d}", .{integer}),
        .float, .number_string => return error.InvalidActionDescriptor,
        .string => |string| try writer.print("{f}", .{std.json.fmt(string, .{})}),
        .array => |array| {
            try writer.writeByte('[');
            for (array.items, 0..) |item, index| {
                if (index != 0) try writer.writeByte(',');
                try writeCanonicalValue(allocator, writer, item);
            }
            try writer.writeByte(']');
        },
        .object => |object| {
            const keys = try allocator.alloc([]const u8, object.count());
            defer allocator.free(keys);
            var iterator = object.iterator();
            var index: usize = 0;
            while (iterator.next()) |entry| : (index += 1) keys[index] = entry.key_ptr.*;
            std.sort.pdq([]const u8, keys, {}, struct {
                fn lessThan(_: void, left: []const u8, right: []const u8) bool {
                    return std.mem.order(u8, left, right) == .lt;
                }
            }.lessThan);
            try writer.writeByte('{');
            for (keys, 0..) |key, key_index| {
                if (key_index != 0) try writer.writeByte(',');
                try writer.print("{f}:", .{std.json.fmt(key, .{})});
                try writeCanonicalValue(allocator, writer, object.get(key).?);
            }
            try writer.writeByte('}');
        },
    }
}

fn hashString(hasher: *std.crypto.hash.sha2.Sha256, value: []const u8) void {
    hashInt(hasher, u32, @intCast(value.len));
    hasher.update(value);
}

fn hashInt(hasher: *std.crypto.hash.sha2.Sha256, comptime T: type, value: T) void {
    var bytes: [@divExact(@typeInfo(T).int.bits, 8)]u8 = undefined;
    std.mem.writeInt(T, &bytes, value, .little);
    hasher.update(&bytes);
}

fn fixture(arguments: []const u8) Descriptor {
    return .{
        .action_id = "action-1",
        .causal_request_id = "causal-1",
        .source_request_id = "rpc-1",
        .run_id = "rpc-1",
        .session_id = "session-1",
        .actor_id = "local-user",
        .provenance = "rpc:local-user",
        .workspace_id = "workspace-1",
        .owner_id = "builtin:system-use",
        .owner_version = 1,
        .owner_generation = 1,
        .capability = .system_use_interact,
        .effect = .input,
        .target = "pid:42",
        .canonical_arguments = arguments,
        .authority = .local_user,
        .expires_at_ms = 1000,
        .cancellation_generation = 7,
    };
}

test "descriptor digest binds exact arguments target expiry and cancellation generation" {
    const original = fixture("{\"action\":\"press\"}");
    const digest = try original.digest();
    var changed = original;
    changed.canonical_arguments = "{\"action\":\"delete\"}";
    try std.testing.expect(!std.mem.eql(u8, &digest, &(try changed.digest())));
    changed = original;
    changed.target = "pid:43";
    try std.testing.expect(!std.mem.eql(u8, &digest, &(try changed.digest())));
    changed = original;
    changed.expires_at_ms += 1;
    try std.testing.expect(!std.mem.eql(u8, &digest, &(try changed.digest())));
    changed = original;
    changed.cancellation_generation += 1;
    try std.testing.expect(!std.mem.eql(u8, &digest, &(try changed.digest())));
}

test "descriptor rejects unbounded and non-object arguments" {
    var descriptor = fixture("[]");
    try std.testing.expectError(error.InvalidActionDescriptor, descriptor.validate());
    descriptor = fixture("{}");
    descriptor.actor_id = "";
    try std.testing.expectError(error.InvalidActionDescriptor, descriptor.validate());
}

test "canonical arguments sort map keys and reject floating point values" {
    const first = try canonicalizeArguments(std.testing.allocator, " { \"z\" : 1, \"a\": {\"b\":2,\"a\":3} } ");
    defer std.testing.allocator.free(first);
    const second = try canonicalizeArguments(std.testing.allocator, "{\"a\":{\"a\":3,\"b\":2},\"z\":1}");
    defer std.testing.allocator.free(second);
    try std.testing.expectEqualStrings("{\"a\":{\"a\":3,\"b\":2},\"z\":1}", first);
    try std.testing.expectEqualStrings(first, second);
    try std.testing.expectError(error.InvalidActionDescriptor, canonicalizeArguments(std.testing.allocator, "{\"value\":1.5}"));
}

test "receipt rejects invalid dispatch settlement order and evidence bounds" {
    var receipt = Receipt{
        .action_id = "action-1",
        .descriptor_hash = @splat(1),
        .decision_id = "decision-1",
        .lease_id = "lease-1",
        .actor_id = "local-user",
        .owner_generation = 1,
        .causal_request_id = "rpc-1",
        .requested_at_ms = 10,
        .decided_at_ms = 11,
        .dispatch_begun_at_ms = 12,
        .settled_at_ms = 13,
        .status = .success,
        .evidence = "v1;method=click;result_sha256=0000000000000000000000000000000000000000000000000000000000000000;duration_us=1",
    };
    try receipt.validate();
    receipt.settled_at_ms = 11;
    try std.testing.expectError(error.InvalidActionReceipt, receipt.validate());
    receipt.settled_at_ms = 13;
    receipt.dispatch_begun_at_ms = 10;
    try std.testing.expectError(error.InvalidActionReceipt, receipt.validate());
    receipt.dispatch_begun_at_ms = 12;
    receipt.lease_id = "";
    try std.testing.expectError(error.InvalidActionReceipt, receipt.validate());
    receipt.lease_id = "lease-1";
    receipt.evidence = "x" ** (maximum_evidence_bytes + 1);
    try std.testing.expectError(error.InvalidActionReceipt, receipt.validate());
    receipt.evidence = "";
    try std.testing.expectError(error.InvalidActionReceipt, receipt.validate());
    receipt.evidence = "v1;method=click;result_sha256=0000000000000000000000000000000000000000000000000000000000000000;duration_us=1";
    try receipt.validate();
}
