const std = @import("std");

pub const hash_length = std.crypto.hash.sha2.Sha256.digest_length;
pub const Hash = [hash_length]u8;
pub const HashHex = [hash_length * 2]u8;
pub const max_session_bytes = 256;
pub const max_model_bytes = 256;
pub const max_provider_bytes = 128;

pub const StableBlock = struct {
    id: []const u8,
    bytes: []const u8,
};

pub const StablePrefix = struct {
    bytes: u64,
    hash: Hash,
};

/// Hashes and lengths copied from the exact canonical request rendered for
/// transmission. No request bytes, headers, or credentials are retained.
pub const RequestIdentity = struct {
    request_bytes: u64,
    request_hash: Hash,
    prefix_bytes: u64,
    prefix_hash: Hash,
};

pub const InvalidationReason = enum {
    none,
    cold_start,
    epoch_changed,
    prefix_changed,
    tool_registry_changed,
    project_changed,
    compaction,
    ttl_expired,
    provider_miss,
    dynamic_content,
    other,
};

pub const Call = struct {
    session: []const u8,
    model: []const u8,
    provider: []const u8,
    input: u64,
    cache_read: u64,
    cache_write: u64,
    output: u64,
    request_bytes: u64,
    request_hash: Hash,
    prefix_bytes: u64,
    prefix_hash: Hash,
    epoch: u64,
    latency_ms: u64,
    invalidation_reason: InvalidationReason = .none,

    pub fn normalized(self: Call) Call {
        var result = self;
        result.cache_read = @min(result.cache_read, result.input);
        return result;
    }

    pub fn uncachedInput(self: Call) u64 {
        const value = self.normalized();
        return value.input - value.cache_read;
    }

    pub fn hitRatioBasisPoints(self: Call) u16 {
        const value = self.normalized();
        if (value.input == 0) return 0;
        return @intCast((@as(u128, value.cache_read) * 10_000) / value.input);
    }
};

pub const Aggregate = struct {
    session: []const u8,
    model: []const u8,
    provider: []const u8,
    calls: u64,
    input: u64,
    cache_read: u64,
    cache_write: u64,
    output: u64,
    request_bytes: u64,
    request_hash: Hash,
    prefix_bytes: u64,
    prefix_hash: Hash,
    epoch: u64,
    latency_ms: u64,

    pub fn hitRatioBasisPoints(self: Aggregate) u16 {
        if (self.input == 0) return 0;
        return @intCast((@as(u128, self.cache_read) * 10_000) / self.input);
    }
};

const OwnedAggregate = struct {
    session: []u8,
    model: []u8,
    provider: []u8,
    calls: u64,
    input: u64,
    cache_read: u64,
    cache_write: u64,
    output: u64,
    request_bytes: u64,
    request_hash: Hash,
    prefix_bytes: u64,
    prefix_hash: Hash,
    epoch: u64,
    latency_ms: u64,

    fn view(self: *const OwnedAggregate) Aggregate {
        return .{
            .session = self.session,
            .model = self.model,
            .provider = self.provider,
            .calls = self.calls,
            .input = self.input,
            .cache_read = self.cache_read,
            .cache_write = self.cache_write,
            .output = self.output,
            .request_bytes = self.request_bytes,
            .request_hash = self.request_hash,
            .prefix_bytes = self.prefix_bytes,
            .prefix_hash = self.prefix_hash,
            .epoch = self.epoch,
            .latency_ms = self.latency_ms,
        };
    }
};

pub const SessionAggregator = struct {
    allocator: std.mem.Allocator,
    capacity: usize,
    entries: std.ArrayList(OwnedAggregate) = .empty,

    pub fn init(allocator: std.mem.Allocator, capacity: usize) SessionAggregator {
        return .{ .allocator = allocator, .capacity = capacity };
    }

    pub fn deinit(self: *SessionAggregator) void {
        for (self.entries.items) |entry| self.freeIdentity(entry);
        self.entries.deinit(self.allocator);
        self.* = undefined;
    }

    pub fn len(self: *const SessionAggregator) usize {
        return self.entries.items.len;
    }

    pub fn record(self: *SessionAggregator, raw_call: Call) !void {
        if (self.capacity == 0) return error.ZeroCapacity;
        if (raw_call.session.len > max_session_bytes or raw_call.model.len > max_model_bytes or raw_call.provider.len > max_provider_bytes) return error.IdentityTooLong;
        const call = raw_call.normalized();
        if (self.find(call.session, call.model, call.provider)) |entry| {
            entry.calls +|= 1;
            entry.input +|= call.input;
            entry.cache_read +|= call.cache_read;
            entry.cache_write +|= call.cache_write;
            entry.output +|= call.output;
            entry.latency_ms +|= call.latency_ms;
            entry.request_bytes = call.request_bytes;
            entry.request_hash = call.request_hash;
            entry.prefix_bytes = call.prefix_bytes;
            entry.prefix_hash = call.prefix_hash;
            entry.epoch = call.epoch;
            return;
        }

        const session = try self.allocator.dupe(u8, call.session);
        errdefer self.allocator.free(session);
        const model = try self.allocator.dupe(u8, call.model);
        errdefer self.allocator.free(model);
        const provider = try self.allocator.dupe(u8, call.provider);
        errdefer self.allocator.free(provider);

        if (self.entries.items.len == self.capacity) {
            const evicted = self.entries.orderedRemove(0);
            self.freeIdentity(evicted);
        }
        try self.entries.append(self.allocator, .{
            .session = session,
            .model = model,
            .provider = provider,
            .calls = 1,
            .input = call.input,
            .cache_read = call.cache_read,
            .cache_write = call.cache_write,
            .output = call.output,
            .request_bytes = call.request_bytes,
            .request_hash = call.request_hash,
            .prefix_bytes = call.prefix_bytes,
            .prefix_hash = call.prefix_hash,
            .epoch = call.epoch,
            .latency_ms = call.latency_ms,
        });
    }

    pub fn get(self: *const SessionAggregator, session: []const u8, model: []const u8, provider: []const u8) ?Aggregate {
        for (self.entries.items) |entry| {
            if (sameIdentity(&entry, session, model, provider)) return entry.view();
        }
        return null;
    }

    fn find(self: *SessionAggregator, session: []const u8, model: []const u8, provider: []const u8) ?*OwnedAggregate {
        for (self.entries.items) |*entry| {
            if (sameIdentity(entry, session, model, provider)) return entry;
        }
        return null;
    }

    fn freeIdentity(self: *SessionAggregator, entry: OwnedAggregate) void {
        self.allocator.free(entry.session);
        self.allocator.free(entry.model);
        self.allocator.free(entry.provider);
    }
};

pub fn hashBlock(allocator: std.mem.Allocator, block: StableBlock) !Hash {
    const normalized = try normalizeStableBytes(allocator, block.bytes);
    defer allocator.free(normalized);

    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update("misul-stable-block-v1\x00");
    hashLengthAndBytes(&hasher, block.id);
    hashLengthAndBytes(&hasher, normalized);
    var digest: Hash = undefined;
    hasher.final(&digest);
    return digest;
}

pub fn hashStablePrefix(allocator: std.mem.Allocator, blocks: []const StableBlock) !StablePrefix {
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update("misul-stable-prefix-v1\x00");
    var byte_count: u64 = 0;
    for (blocks) |block| {
        const normalized = try normalizeStableBytes(allocator, block.bytes);
        defer allocator.free(normalized);
        byte_count +|= @intCast(normalized.len);

        var block_hasher = std.crypto.hash.sha2.Sha256.init(.{});
        block_hasher.update("misul-stable-block-v1\x00");
        hashLengthAndBytes(&block_hasher, block.id);
        hashLengthAndBytes(&block_hasher, normalized);
        var block_digest: Hash = undefined;
        block_hasher.final(&block_digest);
        hasher.update(&block_digest);
    }
    var digest: Hash = undefined;
    hasher.final(&digest);
    return .{ .bytes = byte_count, .hash = digest };
}

pub fn hashPromptStablePrefix(allocator: std.mem.Allocator, stable_blocks: []const StableBlock, dynamic_suffix: []const u8) !StablePrefix {
    _ = dynamic_suffix;
    return hashStablePrefix(allocator, stable_blocks);
}

pub fn hashHex(hash: Hash) HashHex {
    return std.fmt.bytesToHex(hash, .lower);
}

pub fn serializeLine(allocator: std.mem.Allocator, raw_call: Call) ![]u8 {
    const call = raw_call.normalized();
    const ratio = call.hitRatioBasisPoints();
    const request_hash = hashHex(call.request_hash);
    const prefix_hash = hashHex(call.prefix_hash);
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);

    try output.appendSlice(allocator, "{\"session\":");
    try appendJsonString(allocator, &output, call.session);
    try output.appendSlice(allocator, ",\"model\":");
    try appendJsonString(allocator, &output, call.model);
    try output.appendSlice(allocator, ",\"provider\":");
    try appendJsonString(allocator, &output, call.provider);
    try output.print(allocator, ",\"input\":{d},\"cache_read\":{d},\"uncached_input\":{d},\"cache_write\":{d},\"output\":{d},\"hit_ratio_bp\":{d},\"request_bytes\":{d},\"request_hash\":\"{s}\",\"prefix_bytes\":{d},\"prefix_hash\":\"{s}\",\"epoch\":{d},\"latency_ms\":{d},\"invalidation_reason\":\"{s}\"}}\n", .{
        call.input,
        call.cache_read,
        call.uncachedInput(),
        call.cache_write,
        call.output,
        ratio,
        call.request_bytes,
        request_hash,
        call.prefix_bytes,
        prefix_hash,
        call.epoch,
        call.latency_ms,
        @tagName(call.invalidation_reason),
    });
    return output.toOwnedSlice(allocator);
}

pub fn normalizeStableBytes(allocator: std.mem.Allocator, input: []const u8) ![]u8 {
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);
    var index: usize = 0;
    var pending_blank = false;
    var wrote_line = false;

    while (index < input.len) {
        const line_start = index;
        while (index < input.len and input[index] != '\n' and input[index] != '\r') : (index += 1) {}
        var line_end = index;
        while (line_end > line_start and (input[line_end - 1] == ' ' or input[line_end - 1] == '\t')) line_end -= 1;

        if (line_end == line_start) {
            if (wrote_line) pending_blank = true;
        } else {
            if (wrote_line) try output.append(allocator, '\n');
            if (pending_blank) try output.append(allocator, '\n');
            try output.appendSlice(allocator, input[line_start..line_end]);
            wrote_line = true;
            pending_blank = false;
        }

        if (index < input.len and input[index] == '\r') {
            index += 1;
            if (index < input.len and input[index] == '\n') index += 1;
        } else if (index < input.len) {
            index += 1;
        }
    }
    return output.toOwnedSlice(allocator);
}

fn hashLengthAndBytes(hasher: *std.crypto.hash.sha2.Sha256, bytes: []const u8) void {
    var length: [8]u8 = undefined;
    std.mem.writeInt(u64, &length, @intCast(bytes.len), .big);
    hasher.update(&length);
    hasher.update(bytes);
}

fn sameIdentity(entry: *const OwnedAggregate, session: []const u8, model: []const u8, provider: []const u8) bool {
    return std.mem.eql(u8, entry.session, session) and std.mem.eql(u8, entry.model, model) and std.mem.eql(u8, entry.provider, provider);
}

fn appendJsonString(allocator: std.mem.Allocator, output: *std.ArrayList(u8), value: []const u8) !void {
    try output.append(allocator, '"');
    for (value) |byte| switch (byte) {
        '"' => try output.appendSlice(allocator, "\\\""),
        '\\' => try output.appendSlice(allocator, "\\\\"),
        '\n' => try output.appendSlice(allocator, "\\n"),
        '\r' => try output.appendSlice(allocator, "\\r"),
        '\t' => try output.appendSlice(allocator, "\\t"),
        0...0x08, 0x0b...0x0c, 0x0e...0x1f => try output.print(allocator, "\\u00{x:0>2}", .{byte}),
        else => try output.append(allocator, byte),
    };
    try output.append(allocator, '"');
}

test "dynamic suffix does not affect stable prefix hash" {
    const allocator = std.testing.allocator;
    const blocks = [_]StableBlock{
        .{ .id = "system", .bytes = "policy  \r\n\r\n\r\nagent" },
        .{ .id = "tools", .bytes = "sorted-tools" },
    };
    const first = try hashPromptStablePrefix(allocator, &blocks, "task one at 2026-08-27");
    const second = try hashPromptStablePrefix(allocator, &blocks, "different task and tool output");
    try std.testing.expectEqual(first.bytes, second.bytes);
    try std.testing.expectEqualSlices(u8, &first.hash, &second.hash);
}

test "telemetry hashing and serialization do not mutate prompt bytes" {
    const allocator = std.testing.allocator;
    var prompt = [_]u8{ 's', 't', 'a', 'b', 'l', 'e', ' ', ' ', '\r', '\n' };
    const before = prompt;
    const prefix = try hashStablePrefix(allocator, &.{.{ .id = "system", .bytes = &prompt }});
    const line = try serializeLine(allocator, .{
        .session = "session-1",
        .model = "model-1",
        .provider = "provider-1",
        .input = 100,
        .cache_read = 80,
        .cache_write = 20,
        .output = 10,
        .request_bytes = 256,
        .request_hash = prefix.hash,
        .prefix_bytes = prefix.bytes,
        .prefix_hash = prefix.hash,
        .epoch = 3,
        .latency_ms = 42,
    });
    defer allocator.free(line);
    try std.testing.expectEqualSlices(u8, &before, &prompt);
    try std.testing.expect(std.mem.endsWith(u8, line, "\n"));
}

test "normalization and line serialization are deterministic" {
    const allocator = std.testing.allocator;
    const normalized = try normalizeStableBytes(allocator, "a  \r\n\r\n\r\nb\t\n\n");
    defer allocator.free(normalized);
    try std.testing.expectEqualStrings("a\n\nb", normalized);

    const call = Call{
        .session = "s\"1",
        .model = "m",
        .provider = "p",
        .input = 10,
        .cache_read = 20,
        .cache_write = 2,
        .output = 3,
        .request_bytes = 8,
        .request_hash = [_]u8{0xcd} ** hash_length,
        .prefix_bytes = 4,
        .prefix_hash = [_]u8{0xab} ** hash_length,
        .epoch = 5,
        .latency_ms = 6,
        .invalidation_reason = .prefix_changed,
    };
    const first = try serializeLine(allocator, call);
    defer allocator.free(first);
    const second = try serializeLine(allocator, call);
    defer allocator.free(second);
    try std.testing.expectEqualStrings(first, second);
    try std.testing.expect(std.mem.indexOf(u8, first, "\"cache_read\":10,\"uncached_input\":0") != null);
    try std.testing.expect(std.mem.indexOf(u8, first, "\"hit_ratio_bp\":10000") != null);
}

test "session aggregation is bounded and owns identities" {
    const allocator = std.testing.allocator;
    var aggregator = SessionAggregator.init(allocator, 2);
    defer aggregator.deinit();
    const zero_hash = [_]u8{0} ** hash_length;

    var session = [_]u8{'a'};
    try aggregator.record(.{ .session = &session, .model = "m", .provider = "p", .input = 10, .cache_read = 5, .cache_write = 1, .output = 2, .request_bytes = 4, .request_hash = zero_hash, .prefix_bytes = 3, .prefix_hash = zero_hash, .epoch = 1, .latency_ms = 7 });
    session[0] = 'x';
    try aggregator.record(.{ .session = "a", .model = "m", .provider = "p", .input = 10, .cache_read = 10, .cache_write = 0, .output = 1, .request_bytes = 4, .request_hash = zero_hash, .prefix_bytes = 3, .prefix_hash = zero_hash, .epoch = 1, .latency_ms = 8 });
    try aggregator.record(.{ .session = "b", .model = "m", .provider = "p", .input = 1, .cache_read = 0, .cache_write = 0, .output = 1, .request_bytes = 2, .request_hash = zero_hash, .prefix_bytes = 1, .prefix_hash = zero_hash, .epoch = 1, .latency_ms = 1 });
    try aggregator.record(.{ .session = "c", .model = "m", .provider = "p", .input = 1, .cache_read = 0, .cache_write = 0, .output = 1, .request_bytes = 2, .request_hash = zero_hash, .prefix_bytes = 1, .prefix_hash = zero_hash, .epoch = 1, .latency_ms = 1 });

    try std.testing.expectEqual(@as(usize, 2), aggregator.len());
    try std.testing.expect(aggregator.get("a", "m", "p") == null);
    try std.testing.expect(aggregator.get("b", "m", "p") != null);
    try std.testing.expect(aggregator.get("c", "m", "p") != null);
}
