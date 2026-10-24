const std = @import("std");
const protocol = @import("protocol.zig");

pub const max_profile_json_bytes = 64 * 1024;
pub const max_profile_count = 64;

/// Runtime selection is based on this explicit immutable descriptor. Provider
/// behavior must never be inferred from a model name or a remote metadata feed.
pub const ModelProfile = struct {
    id: []const u8,
    name: []const u8,
    provider: []const u8,
    context_window: u64,
    output_limit: u64,
    max_output_tokens: u64,
    reasoning: ?protocol.ThinkingLevel = null,
    cache_retention: protocol.CacheRetention = .none,
    cache_aggressiveness: protocol.CacheAggressiveness = .balanced,
    transport: protocol.Transport = .auto,
};

const ProfileDocument = struct { profiles: []const ModelProfile };

pub const ProfileRegistry = struct {
    allocator: std.mem.Allocator,
    profiles: []ModelProfile,

    pub fn init(allocator: std.mem.Allocator, profiles: []const ModelProfile) !ProfileRegistry {
        if (profiles.len == 0) return error.EmptyModelProfileRegistry;
        if (profiles.len > max_profile_count) return error.TooManyModelProfiles;
        const owned = try allocator.alloc(ModelProfile, profiles.len);
        var initialized: usize = 0;
        errdefer {
            for (owned[0..initialized]) |profile| freeProfile(allocator, profile);
            allocator.free(owned);
        }
        for (profiles, 0..) |profile, index| {
            try validateProfile(profile);
            for (profiles[0..index]) |previous| {
                if (std.mem.eql(u8, previous.id, profile.id)) return error.DuplicateModelProfile;
            }
            owned[index] = try cloneProfile(allocator, profile);
            initialized += 1;
        }
        return .{ .allocator = allocator, .profiles = owned };
    }

    pub fn parseJson(allocator: std.mem.Allocator, json: []const u8) !ProfileRegistry {
        if (json.len == 0 or json.len > max_profile_json_bytes) return error.InvalidModelProfileJson;
        var parsed = std.json.parseFromSlice(ProfileDocument, allocator, json, .{}) catch |err| {
            if (err == error.OutOfMemory) return err;
            return error.InvalidModelProfileJson;
        };
        defer parsed.deinit();
        return init(allocator, parsed.value.profiles);
    }

    pub fn deinit(self: *ProfileRegistry) void {
        for (self.profiles) |profile| freeProfile(self.allocator, profile);
        self.allocator.free(self.profiles);
        self.* = undefined;
    }

    pub fn find(self: *const ProfileRegistry, id: []const u8) ?*const ModelProfile {
        for (self.profiles) |*profile| if (std.mem.eql(u8, profile.id, id)) return profile;
        return null;
    }
};

fn validateProfile(profile: ModelProfile) !void {
    if (profile.id.len == 0 or profile.name.len == 0 or profile.provider.len == 0) return error.InvalidModelProfile;
    if (profile.context_window == 0 or profile.output_limit == 0 or profile.max_output_tokens == 0) return error.InvalidModelProfile;
    if (profile.output_limit > profile.max_output_tokens or profile.max_output_tokens > profile.context_window) return error.InvalidModelProfile;
}

fn cloneProfile(allocator: std.mem.Allocator, profile: ModelProfile) !ModelProfile {
    const id = try allocator.dupe(u8, profile.id);
    errdefer allocator.free(id);
    const name = try allocator.dupe(u8, profile.name);
    errdefer allocator.free(name);
    const provider = try allocator.dupe(u8, profile.provider);
    errdefer allocator.free(provider);
    return .{
        .id = id,
        .name = name,
        .provider = provider,
        .context_window = profile.context_window,
        .output_limit = profile.output_limit,
        .max_output_tokens = profile.max_output_tokens,
        .reasoning = profile.reasoning,
        .cache_retention = profile.cache_retention,
        .cache_aggressiveness = profile.cache_aggressiveness,
        .transport = profile.transport,
    };
}

fn freeProfile(allocator: std.mem.Allocator, profile: ModelProfile) void {
    allocator.free(profile.provider);
    allocator.free(profile.name);
    allocator.free(profile.id);
}

test "model profile registry owns explicit policy and rejects absent metadata" {
    const fixture =
        \\{"profiles":[
        \\  {"id":"compact-sse","name":"Compact SSE","provider":"openai-compatible","context_window":8192,"output_limit":512,"max_output_tokens":1024,"reasoning":"low","cache_retention":"short","cache_aggressiveness":"conservative","transport":"sse"}
        \\]}
    ;
    var registry = try ProfileRegistry.parseJson(std.testing.allocator, fixture);
    defer registry.deinit();
    const compact = registry.find("compact-sse").?;
    try std.testing.expectEqual(@as(u64, 8192), compact.context_window);
    try std.testing.expectEqual(protocol.ThinkingLevel.low, compact.reasoning.?);
    try std.testing.expectEqual(protocol.CacheRetention.short, compact.cache_retention);
    try std.testing.expectEqual(protocol.CacheAggressiveness.conservative, compact.cache_aggressiveness);
    try std.testing.expectEqual(protocol.Transport.sse, compact.transport);
    try std.testing.expect(registry.find("inferred-model") == null);
}

test "model profile registry rejects duplicate and impossible limits" {
    const duplicate =
        \\{"profiles":[
        \\  {"id":"same","name":"First","provider":"openai-compatible","context_window":8192,"output_limit":512,"max_output_tokens":1024},
        \\  {"id":"same","name":"Second","provider":"openai-compatible","context_window":16384,"output_limit":512,"max_output_tokens":1024}
        \\]}
    ;
    try std.testing.expectError(error.DuplicateModelProfile, ProfileRegistry.parseJson(std.testing.allocator, duplicate));

    const impossible =
        \\{"profiles":[{"id":"bad","name":"Bad","provider":"openai-compatible","context_window":8192,"output_limit":2048,"max_output_tokens":1024}]}
    ;
    try std.testing.expectError(error.InvalidModelProfile, ProfileRegistry.parseJson(std.testing.allocator, impossible));
}

test "profile JSON parsing preserves allocation failure at the public boundary" {
    const fixture = "{\"profiles\":[{\"id\":\"m\",\"name\":\"Model\",\"provider\":\"p\",\"context_window\":4096,\"output_limit\":128,\"max_output_tokens\":256}]}";
    var failing = std.testing.FailingAllocator.init(std.testing.allocator, .{ .fail_index = 0 });
    try std.testing.expectError(error.OutOfMemory, ProfileRegistry.parseJson(failing.allocator(), fixture));
}
