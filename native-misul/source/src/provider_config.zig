const std = @import("std");
const openai_compatible = @import("openai_compatible.zig");

pub const max_json_bytes: usize = 256 * 1024;

const Document = struct {
    providers: []const openai_compatible.ProviderConfig,
};

pub const Parsed = struct {
    document: std.json.Parsed(Document),

    pub fn deinit(self: *Parsed) void {
        self.document.deinit();
        self.* = undefined;
    }

    pub fn configs(self: *const Parsed) []const openai_compatible.ProviderConfig {
        return self.document.value.providers;
    }
};

pub fn parseJson(allocator: std.mem.Allocator, json: []const u8) !Parsed {
    if (json.len == 0 or json.len > max_json_bytes) return error.InvalidProviderConfigJson;
    var document = std.json.parseFromSlice(Document, allocator, json, .{}) catch |err| {
        if (err == error.OutOfMemory) return err;
        return error.InvalidProviderConfigJson;
    };
    errdefer document.deinit();
    if (document.value.providers.len == 0 or document.value.providers.len > 16) return error.InvalidProviderConfigJson;
    return .{ .document = document };
}

test "provider configuration JSON preserves explicit transport authority" {
    const fixture =
        \\{"providers":[{
        \\  "id":"openrouter",
        \\  "endpoint":"https://openrouter.ai/api/v1",
        \\  "credential_ref":"OPENROUTER_API_KEY",
        \\  "credential_source":"environment",
        \\  "headers":[{"name":"x-title","value":"Misul"}],
        \\  "dialect":"openrouter_chat",
        \\  "models":[{"id":"model-a","context_window":8192,"output_limit":512,"reasoning":true,"reasoning_efforts":["high"],"tool_call":true}],
        \\  "retry":{"max_retries":1,"max_retry_delay_ms":1000,"deadline_ms":5000},
        \\  "max_response_body_bytes":65536,
        \\  "max_event_bytes":16384
        \\}]}
    ;
    var parsed = try parseJson(std.testing.allocator, fixture);
    defer parsed.deinit();

    const configs = parsed.configs();
    try std.testing.expectEqual(@as(usize, 1), configs.len);
    try std.testing.expectEqualStrings("openrouter", configs[0].id);
    try std.testing.expectEqual(openai_compatible.Dialect.openrouter_chat, configs[0].dialect);
    try std.testing.expectEqualStrings("OPENROUTER_API_KEY", configs[0].credential_ref);
    try std.testing.expectEqual(@as(usize, 1), configs[0].headers.len);
    try std.testing.expectEqualStrings("model-a", configs[0].models[0].id);
    try std.testing.expectEqual(@as(u64, 5_000), configs[0].retry.deadline_ms.?);
}
