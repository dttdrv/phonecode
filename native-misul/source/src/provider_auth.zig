const std = @import("std");
const protocol = @import("protocol.zig");

pub const Source = enum { explicit, environment };
pub const max_authorization_header_value_bytes = 4096;
pub const max_bearer_token_bytes = max_authorization_header_value_bytes - "Bearer ".len;

pub const Credential = struct {
    token: []const u8,
    source: Source,
};

pub fn resolveOpenAi(explicit: ?[]const u8, environ: ?*const std.process.Environ.Map) !Credential {
    if (explicit) |token| {
        try validateBearerToken(token);
        return .{ .token = token, .source = .explicit };
    }
    if (environ) |map| {
        if (map.get("OPENAI_API_KEY")) |token| {
            try validateBearerToken(token);
            return .{ .token = token, .source = .environment };
        }
    }
    return error.MissingCredential;
}

pub fn validateBearerToken(token: []const u8) !void {
    if (token.len == 0 or token.len > max_bearer_token_bytes) return error.InvalidCredential;
    var padding = false;
    var base_len: usize = 0;
    for (token) |byte| {
        if (byte == '=') {
            padding = true;
            continue;
        }
        if (padding or !(std.ascii.isAlphanumeric(byte) or std.mem.indexOfScalar(u8, "-._~+/", byte) != null)) return error.InvalidCredential;
        base_len += 1;
    }
    if (base_len == 0) return error.InvalidCredential;
}

/// The protocol owns provider failure categories.  Keep this alias for
/// additive source compatibility while avoiding a second classification
/// authority in the authentication module.
pub const Failure = protocol.ProviderFailureCategory;

/// Classifies provider failures without inventing a local usage-capacity state.
/// HTTP 429 is rate limiting unless the provider explicitly reports billing or
/// hard-limit exhaustion.
pub fn classifyFailure(status: u16, body: []const u8) Failure {
    return classifyStructuredFailure(status, null, null, body);
}

pub fn classifyStructuredFailure(status: u16, code: ?[]const u8, typ: ?[]const u8, message: []const u8) Failure {
    const semantic_status = if (status >= 200 and status < 300) numericProviderStatus(code) orelse status else status;
    if (semantic_status == 401) return .authentication;
    if (semantic_status == 403) return .permission;
    if (semantic_status == 402) return .quota_exhausted;
    if (containsField(code, typ, "rate_limit")) return .rate_limited;
    if (containsField(code, typ, "context_length") or containsIgnoreCase(message, "context_length") or containsIgnoreCase(message, "maximum context length")) return .context_length;
    if (containsField(code, typ, "insufficient_quota") or containsField(code, typ, "billing_hard_limit") or containsIgnoreCase(message, "insufficient_quota") or containsIgnoreCase(message, "billing hard limit") or containsIgnoreCase(message, "quota exhausted")) return .quota_exhausted;
    if (semantic_status == 404) return .unsupported;
    if (semantic_status == 413) return .context_length;
    if (semantic_status == 408 or semantic_status == 504) return .timeout;
    if (semantic_status == 429) return .rate_limited;
    if (semantic_status == 409 or (semantic_status >= 500 and semantic_status <= 599)) return .transient_provider;
    if (semantic_status >= 400 and semantic_status < 500) return .invalid_request;
    return .provider;
}

fn numericProviderStatus(code: ?[]const u8) ?u16 {
    const value = std.fmt.parseInt(u16, code orelse return null, 10) catch return null;
    return if (value >= 400 and value <= 599) value else null;
}

fn containsField(code: ?[]const u8, typ: ?[]const u8, needle: []const u8) bool {
    return (if (code) |value| containsIgnoreCase(value, needle) else false) or (if (typ) |value| containsIgnoreCase(value, needle) else false);
}

fn containsIgnoreCase(haystack: []const u8, needle: []const u8) bool {
    if (needle.len == 0) return true;
    if (needle.len > haystack.len) return false;
    var index: usize = 0;
    while (index + needle.len <= haystack.len) : (index += 1) {
        if (std.ascii.eqlIgnoreCase(haystack[index .. index + needle.len], needle)) return true;
    }
    return false;
}

test "explicit credentials win and newline injection is rejected" {
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    try environment.put("OPENAI_API_KEY", "environment");
    const credential = try resolveOpenAi("explicit", &environment);
    try std.testing.expectEqual(Source.explicit, credential.source);
    try std.testing.expectEqualStrings("explicit", credential.token);
    try std.testing.expectError(error.InvalidCredential, resolveOpenAi("bad\nheader", &environment));
    try std.testing.expectError(error.InvalidCredential, resolveOpenAi("bad\x00header", &environment));
    try std.testing.expectError(error.InvalidCredential, resolveOpenAi("bad\x7fheader", &environment));
    const too_large = "x" ** (max_bearer_token_bytes + 1);
    try std.testing.expectError(error.InvalidCredential, resolveOpenAi(too_large, &environment));
}

test "bearer credentials use RFC 6750 token syntax for explicit and environment sources" {
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    try environment.put("OPENAI_API_KEY", "valid+/._~-token==");
    try std.testing.expectEqualStrings("valid+/._~-token==", (try resolveOpenAi(null, &environment)).token);
    inline for (.{ "space token", "tab\ttoken", "token=tail", "token!", "=", "==", "===" }) |invalid| {
        try std.testing.expectError(error.InvalidCredential, resolveOpenAi(invalid, &environment));
        try environment.put("OPENAI_API_KEY", invalid);
        try std.testing.expectError(error.InvalidCredential, resolveOpenAi(null, &environment));
    }
}

test "provider failures never turn ordinary rate limits into local capacity exhaustion" {
    try std.testing.expectEqual(Failure.rate_limited, classifyFailure(429, "too many requests"));
    try std.testing.expectEqual(Failure.quota_exhausted, classifyFailure(429, "insufficient_quota"));
    try std.testing.expectEqual(Failure.quota_exhausted, classifyFailure(402, "Insufficient credits"));
    try std.testing.expectEqual(Failure.context_length, classifyFailure(400, "maximum context length exceeded"));
    try std.testing.expectEqual(Failure.authentication, classifyFailure(401, "bad token"));
}

test "structured type remains authoritative when code is unrelated" {
    try std.testing.expectEqual(Failure.quota_exhausted, classifyStructuredFailure(429, "unrelated", "insufficient_quota", "rate limit"));
    try std.testing.expectEqual(Failure.rate_limited, classifyStructuredFailure(200, "429", null, "Too Many Requests"));
    try std.testing.expectEqual(Failure.authentication, classifyStructuredFailure(401, "429", null, "Too Many Requests"));
}
