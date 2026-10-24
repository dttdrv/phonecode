const std = @import("std");
const builtin = @import("builtin");
const protocol = @import("protocol.zig");
const provider_auth = @import("provider_auth.zig");

pub const max_headers = 32;
pub const max_header_name_bytes = 128;
pub const max_header_value_bytes = provider_auth.max_authorization_header_value_bytes;
pub const max_response_body_bytes = 8 * 1024 * 1024;
pub const max_error_body_bytes = 64 * 1024;
pub const max_sse_line_bytes = 64 * 1024;
pub const max_sse_event_bytes = 256 * 1024;
pub const max_tool_calls = 16;
/// Tools advertised per request; separate from the calls one response may make.
pub const max_tools = 128;
pub const max_tool_arguments_bytes = 128 * 1024;
pub const max_rendered_request_bytes = 8 * 1024 * 1024;
pub const max_request_messages = 4096;
pub const max_message_parts = 128;
pub const max_tool_name_bytes = 128;
pub const max_tool_description_bytes = 4096;
pub const max_tool_schema_bytes = 64 * 1024;
pub const max_model_id_bytes = 256;
pub const max_session_id_bytes = 256;

pub const Dialect = enum { openai_chat, openrouter_chat, deepseek_chat, anthropic_messages };
pub const anthropic_version = "2023-06-01";
pub const Header = struct { name: []const u8, value: []const u8 };
pub const CacheRetentionWire = enum {
    prompt_cache_retention_in_memory,
    prompt_cache_retention_24h,
    prompt_cache_options_ttl_30m,
};

pub const ModelCapability = struct {
    id: []const u8,
    context_window: u64,
    output_limit: u64,
    reasoning: bool = false,
    reasoning_efforts: []const protocol.ThinkingLevel = &.{},
    tool_call: bool = true,
    short_cache_retention: ?CacheRetentionWire = null,
    long_cache_retention: ?CacheRetentionWire = null,
};

pub const RetryConfig = struct {
    max_retries: u32 = 2,
    max_retry_delay_ms: u64 = 30_000,
    deadline_ms: ?u64 = null,
};

pub const ProviderConfig = struct {
    id: []const u8,
    endpoint: []const u8,
    credential_ref: []const u8,
    credential_source: provider_auth.Source = .environment,
    headers: []const Header = &.{},
    dialect: Dialect = .openai_chat,
    models: []const ModelCapability = &.{},
    retry: RetryConfig = .{},
    max_response_body_bytes: usize = max_response_body_bytes,
    max_event_bytes: usize = max_sse_event_bytes,
};

pub const RenderedRequest = struct {
    bytes: []u8,
    cache_prefix_len: usize,
    cache_prefix_hash: [std.crypto.hash.sha2.Sha256.digest_length]u8,
    allocator: std.mem.Allocator,

    pub fn deinit(self: *RenderedRequest) void {
        self.allocator.free(self.bytes);
        self.* = undefined;
    }
};

/// A bounded identity of the exact request bytes sent to a provider.  It never
/// contains a request body, header, or credential.
pub const RenderedRequestMetadata = struct {
    request_len: usize,
    request_hash: [std.crypto.hash.sha2.Sha256.digest_length]u8,
    cache_prefix_len: usize,
    cache_prefix_hash: [std.crypto.hash.sha2.Sha256.digest_length]u8,
};

pub const RequestObserver = *const fn (context: *anyopaque, metadata: RenderedRequestMetadata) anyerror!void;

pub const ProviderRegistry = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    environ: *const std.process.Environ.Map,
    providers: []ProviderConfig,

    pub fn init(allocator: std.mem.Allocator, io: std.Io, environ: *const std.process.Environ.Map, configs: []const ProviderConfig) !ProviderRegistry {
        if (configs.len == 0 or configs.len > 16) return error.InvalidProviderRegistry;
        const providers = try allocator.alloc(ProviderConfig, configs.len);
        var initialized: usize = 0;
        errdefer {
            for (providers[0..initialized]) |*config| freeProviderConfig(allocator, config);
            allocator.free(providers);
        }
        for (configs, 0..) |config, index| {
            try validateProviderConfig(config);
            for (configs[0..index]) |previous| if (std.mem.eql(u8, previous.id, config.id)) return error.DuplicateProvider;
            providers[index] = try cloneProviderConfig(allocator, config);
            initialized += 1;
        }
        return .{ .allocator = allocator, .io = io, .environ = environ, .providers = providers };
    }

    pub fn deinit(self: *ProviderRegistry) void {
        for (self.providers) |*config| freeProviderConfig(self.allocator, config);
        self.allocator.free(self.providers);
        self.* = undefined;
    }

    pub fn find(self: *const ProviderRegistry, id: []const u8) ?*const ProviderConfig {
        for (self.providers) |*config| if (std.mem.eql(u8, config.id, id)) return config;
        return null;
    }

    pub fn stream(self: *ProviderRegistry, model: *const protocol.Model, context: *const protocol.Context, options: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
        return self.streamInternal(model, context, options, sink_context, sink, null, null);
    }

    /// The observer runs once after rendering and before transmission.  A
    /// failure prevents transmission and returns `RequestObserverFailed`.
    pub fn streamObserved(self: *ProviderRegistry, model: *const protocol.Model, context: *const protocol.Context, options: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink, observer_context: *anyopaque, observer: RequestObserver) !void {
        return self.streamInternal(model, context, options, sink_context, sink, observer_context, observer);
    }

    fn streamInternal(self: *ProviderRegistry, model: *const protocol.Model, context: *const protocol.Context, options: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink, observer_context: ?*anyopaque, observer: ?RequestObserver) !void {
        const config = self.find(model.provider) orelse {
            try emitFailure(sink_context, sink, model, .unsupported, null, "unknown provider", null, null, null, null);
            return;
        };
        const capability = findCapability(config, model.id) orelse {
            try emitFailure(sink_context, sink, model, .unsupported, null, "model is not configured for provider", null, null, null, null);
            return;
        };
        if (model.context_window != capability.context_window or model.max_tokens > capability.output_limit) {
            try emitFailure(sink_context, sink, model, .invalid_request, null, "model limits do not match provider catalog", null, null, null, null);
            return;
        }
        if (options.max_tokens) |limit| if (limit == 0 or limit > capability.output_limit) {
            try emitFailure(sink_context, sink, model, .invalid_request, null, "requested output limit is unsupported", null, null, null, null);
            return;
        };
        if (options.reasoning) |effort| {
            if (!capability.reasoning or !effortSupported(capability.reasoning_efforts, effort)) {
                try emitFailure(sink_context, sink, model, .unsupported, null, "requested reasoning effort is unsupported", null, null, null, null);
                return;
            }
        }
        if (options.transport == .websocket) {
            try emitFailure(sink_context, sink, model, .unsupported, null, "websocket transport is not supported", null, null, null, null);
            return;
        }
        const cache_retention_wire = cacheRetentionWire(capability, options.cache_retention) orelse if (options.cache_retention != .none) {
            try emitFailure(sink_context, sink, model, .unsupported, null, "requested cache retention is unsupported", null, null, null, null);
            return;
        } else null;
        var effective = options;
        effective.api_key = resolveCredential(self.environ, config) catch {
            try emitFailure(sink_context, sink, model, .authentication, null, "provider credential is unavailable", null, null, null, null);
            return;
        };
        return streamWithConfig(self.allocator, self.io, self.environ, config.endpoint, config.headers, config.dialect, config.retry, config.max_response_body_bytes, config.max_event_bytes, capability.tool_call, cache_retention_wire, model, context, effective, sink_context, sink, observer_context, observer);
    }

    pub fn streamFn(raw: *anyopaque, model: *const protocol.Model, context: *const protocol.Context, options: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
        const self: *ProviderRegistry = @ptrCast(@alignCast(raw));
        return self.stream(model, context, options, sink_context, sink);
    }
};

fn cloneProviderConfig(allocator: std.mem.Allocator, source: ProviderConfig) !ProviderConfig {
    const id = try allocator.dupe(u8, source.id);
    errdefer allocator.free(id);
    const endpoint = try allocator.dupe(u8, source.endpoint);
    errdefer allocator.free(endpoint);
    const credential_ref = try allocator.dupe(u8, source.credential_ref);
    errdefer allocator.free(credential_ref);
    const headers = try allocator.alloc(Header, source.headers.len);
    var header_count: usize = 0;
    errdefer {
        for (headers[0..header_count]) |header| {
            allocator.free(header.name);
            allocator.free(header.value);
        }
        allocator.free(headers);
    }
    for (source.headers, 0..) |header, index| {
        const header_name = try allocator.dupe(u8, header.name);
        errdefer allocator.free(header_name);
        const header_value = try allocator.dupe(u8, header.value);
        headers[index] = .{ .name = header_name, .value = header_value };
        header_count += 1;
    }
    const models = try allocator.alloc(ModelCapability, source.models.len);
    var model_count: usize = 0;
    errdefer {
        for (models[0..model_count]) |model| {
            allocator.free(model.id);
            allocator.free(model.reasoning_efforts);
        }
        allocator.free(models);
    }
    for (source.models, 0..) |model, index| {
        const model_id = try allocator.dupe(u8, model.id);
        errdefer allocator.free(model_id);
        const reasoning_efforts = try allocator.dupe(protocol.ThinkingLevel, model.reasoning_efforts);
        models[index] = .{
            .id = model_id,
            .context_window = model.context_window,
            .output_limit = model.output_limit,
            .reasoning = model.reasoning,
            .reasoning_efforts = reasoning_efforts,
            .tool_call = model.tool_call,
            .short_cache_retention = model.short_cache_retention,
            .long_cache_retention = model.long_cache_retention,
        };
        model_count += 1;
    }
    return .{ .id = id, .endpoint = endpoint, .credential_ref = credential_ref, .credential_source = source.credential_source, .headers = headers, .dialect = source.dialect, .models = models, .retry = source.retry, .max_response_body_bytes = source.max_response_body_bytes, .max_event_bytes = source.max_event_bytes };
}

fn freeProviderConfig(allocator: std.mem.Allocator, config: *ProviderConfig) void {
    allocator.free(config.id);
    allocator.free(config.endpoint);
    allocator.free(config.credential_ref);
    for (config.headers) |header| {
        allocator.free(header.name);
        allocator.free(header.value);
    }
    allocator.free(config.headers);
    for (config.models) |model| {
        allocator.free(model.id);
        allocator.free(model.reasoning_efforts);
    }
    allocator.free(config.models);
}

fn validateProviderConfig(config: ProviderConfig) !void {
    if (config.id.len == 0 or config.endpoint.len == 0 or config.credential_ref.len == 0 or config.models.len == 0) return error.InvalidProviderConfig;
    if (config.models.len > 64 or config.headers.len > max_headers or config.max_response_body_bytes == 0 or config.max_response_body_bytes > max_response_body_bytes or config.max_event_bytes == 0 or config.max_event_bytes > max_sse_event_bytes) return error.InvalidProviderConfig;
    if (config.retry.max_retries > 8 or config.retry.max_retry_delay_ms > 60_000 or config.retry.deadline_ms == null or config.retry.deadline_ms.? == 0 or config.retry.deadline_ms.? > std.math.maxInt(i64)) return error.InvalidProviderConfig;
    const parsed = try std.Uri.parse(config.endpoint);
    if (!(std.ascii.eqlIgnoreCase(parsed.scheme, "http") or std.ascii.eqlIgnoreCase(parsed.scheme, "https")) or parsed.host == null or parsed.host.?.isEmpty() or parsed.user != null or parsed.password != null or parsed.query != null or parsed.fragment != null) return error.InvalidProviderConfig;
    for (config.headers, 0..) |header, index| {
        try validateHeader(header);
        for (config.headers[0..index]) |previous| if (std.ascii.eqlIgnoreCase(previous.name, header.name)) return error.DuplicateHeader;
    }
    for (config.models, 0..) |model, index| {
        if (model.id.len == 0 or model.context_window == 0 or model.output_limit == 0 or model.output_limit > model.context_window or model.reasoning_efforts.len > 16) return error.InvalidModelCapability;
        for (config.models[0..index]) |previous| if (std.mem.eql(u8, previous.id, model.id)) return error.DuplicateModelCapability;
        if (model.reasoning and model.reasoning_efforts.len == 0) return error.InvalidModelCapability;
        if (!cacheRetentionWireMatches(.short, model.short_cache_retention) or !cacheRetentionWireMatches(.long, model.long_cache_retention)) return error.InvalidModelCapability;
        if (config.dialect != .openai_chat and (model.short_cache_retention != null or model.long_cache_retention != null)) return error.InvalidModelCapability;
    }
}
fn validateHeader(header: Header) !void {
    if (header.name.len == 0 or header.name.len > max_header_name_bytes or header.value.len > max_header_value_bytes) return error.InvalidHeader;
    if (std.mem.findAny(u8, header.name, "\r\n") != null or !validHeaderValue(header.value)) return error.InvalidHeader;
    for (header.name) |char| if (!(std.ascii.isAlphanumeric(char) or std.mem.indexOfScalar(u8, "!#$%&'*+-.^_|~", char) != null)) return error.InvalidHeader;
    if (std.ascii.eqlIgnoreCase(header.name, "authorization") or std.ascii.eqlIgnoreCase(header.name, "content-length") or std.ascii.eqlIgnoreCase(header.name, "transfer-encoding") or std.ascii.eqlIgnoreCase(header.name, "host") or std.ascii.eqlIgnoreCase(header.name, "content-type") or std.ascii.eqlIgnoreCase(header.name, "accept") or std.ascii.eqlIgnoreCase(header.name, "connection")) return error.ReservedHeader;
    if (std.ascii.eqlIgnoreCase(header.name, "cookie") or std.ascii.eqlIgnoreCase(header.name, "set-cookie") or std.ascii.eqlIgnoreCase(header.name, "proxy-authorization") or std.ascii.eqlIgnoreCase(header.name, "x-api-key") or std.ascii.eqlIgnoreCase(header.name, "api-key")) return error.SecretHeader;
}
fn findCapability(config: *const ProviderConfig, model_id: []const u8) ?*const ModelCapability {
    for (config.models) |*model| if (std.mem.eql(u8, model.id, model_id)) return model;
    return null;
}
fn effortSupported(efforts: []const protocol.ThinkingLevel, requested: protocol.ThinkingLevel) bool {
    for (efforts) |effort| if (effort == requested) return true;
    return false;
}
fn cacheRetentionWire(capability: *const ModelCapability, requested: protocol.CacheRetention) ?CacheRetentionWire {
    return switch (requested) {
        .none => null,
        .short => capability.short_cache_retention,
        .long => capability.long_cache_retention,
    };
}

fn cacheRetentionWireMatches(requested: protocol.CacheRetention, wire: ?CacheRetentionWire) bool {
    const value = wire orelse return true;
    return switch (requested) {
        .none => false,
        .short => value == .prompt_cache_retention_in_memory or value == .prompt_cache_options_ttl_30m,
        .long => value == .prompt_cache_retention_24h,
    };
}
fn resolveCredential(environ: *const std.process.Environ.Map, config: *const ProviderConfig) ![]const u8 {
    if (config.credential_source == .explicit) {
        try validateCredential(config.credential_ref);
        return config.credential_ref;
    }
    const token = environ.get(config.credential_ref) orelse return error.MissingCredential;
    try validateCredential(token);
    return token;
}

fn validateCredential(token: []const u8) !void {
    provider_auth.validateBearerToken(token) catch return error.InvalidCredential;
}

fn validHeaderValue(value: []const u8) bool {
    for (value) |byte| if ((byte < 0x20 and byte != '\t') or byte == 0x7f) return false;
    return true;
}

fn endpointUrl(allocator: std.mem.Allocator, endpoint: []const u8) ![]u8 {
    const uri = std.Uri.parse(endpoint) catch return error.InvalidBaseUrl;
    if (!(std.ascii.eqlIgnoreCase(uri.scheme, "http") or std.ascii.eqlIgnoreCase(uri.scheme, "https")) or uri.host == null or uri.host.?.isEmpty() or uri.user != null or uri.password != null or uri.query != null or uri.fragment != null) return error.InvalidBaseUrl;
    const suffix = "/chat/completions";
    var endpoint_end = endpoint.len;
    while (endpoint_end > 0 and endpoint[endpoint_end - 1] == '/') : (endpoint_end -= 1) {}
    const slash_trimmed = endpoint[0..endpoint_end];
    if (std.mem.endsWith(u8, slash_trimmed, suffix)) return allocator.dupe(u8, slash_trimmed);
    return std.fmt.allocPrint(allocator, "{s}{s}", .{ slash_trimmed, suffix });
}

/// Anthropic bases are configured with or without the `/v1` version segment.
fn anthropicEndpointUrl(allocator: std.mem.Allocator, endpoint: []const u8) ![]u8 {
    const chat = try endpointUrl(allocator, endpoint);
    defer allocator.free(chat);
    const base = chat[0 .. chat.len - "/chat/completions".len];
    if (std.mem.endsWith(u8, base, "/messages")) return allocator.dupe(u8, base);
    if (std.mem.endsWith(u8, base, "/v1")) return std.fmt.allocPrint(allocator, "{s}/messages", .{base});
    return std.fmt.allocPrint(allocator, "{s}/v1/messages", .{base});
}

fn parseRetryAfter(value: []const u8, now_seconds: i64) ?u64 {
    const trimmed = std.mem.trim(u8, value, " \t");
    if (trimmed.len == 0) return null;
    if (std.fmt.parseInt(u64, trimmed, 10)) |seconds| return @min(seconds *| 1000, max_retry_delay_cap_ms) else |_| {}
    const target = parseHttpDateAt(trimmed, now_seconds) orelse return null;
    if (target <= now_seconds) return 0;
    const delta: u64 = @intCast(target - now_seconds);
    return @min(delta *| 1000, max_retry_delay_cap_ms);
}

fn parseHttpDate(value: []const u8) ?i64 {
    return parseHttpDateAt(value, 0);
}

fn parseHttpDateAt(value: []const u8, now_seconds: i64) ?i64 {
    return parseImfFixdate(value) orelse parseObsoleteRfc850Date(value, now_seconds) orelse parseObsoleteAsctimeDate(value);
}

fn parseImfFixdate(value: []const u8) ?i64 {
    if (value.len != 29 or value[3] != ',' or value[4] != ' ' or value[7] != ' ' or value[11] != ' ' or value[16] != ' ' or value[19] != ':' or value[22] != ':' or value[25] != ' ' or !std.mem.eql(u8, value[26..], "GMT")) return null;
    return httpDateSeconds(
        std.fmt.parseInt(u16, value[12..16], 10) catch return null,
        parseHttpMonth(value[8..11]) orelse return null,
        parseTwoDigits(value[5..7]) orelse return null,
        parseTwoDigits(value[17..19]) orelse return null,
        parseTwoDigits(value[20..22]) orelse return null,
        parseTwoDigits(value[23..25]) orelse return null,
    );
}

fn parseObsoleteRfc850Date(value: []const u8, now_seconds: i64) ?i64 {
    const comma = std.mem.indexOfScalar(u8, value, ',') orelse return null;
    if (comma == 0 or comma + 24 != value.len or value[comma + 1] != ' ') return null;
    const stamp = value[comma + 2 ..];
    if (stamp[2] != '-' or stamp[6] != '-' or stamp[9] != ' ' or stamp[12] != ':' or stamp[15] != ':' or stamp[18] != ' ' or !std.mem.eql(u8, stamp[19..], "GMT")) return null;
    const month = parseHttpMonth(stamp[3..6]) orelse return null;
    const day = parseTwoDigits(stamp[0..2]) orelse return null;
    const hour = parseTwoDigits(stamp[10..12]) orelse return null;
    const minute = parseTwoDigits(stamp[13..15]) orelse return null;
    const second = parseTwoDigits(stamp[16..18]) orelse return null;
    const year = rfc850Year(parseTwoDigits(stamp[7..9]) orelse return null, month, day, hour, minute, second, now_seconds) orelse return null;
    return httpDateSeconds(year, month, day, hour, minute, second);
}

const EpochParts = struct { year: u16, month: std.time.epoch.Month, day: u8, hour: u8, minute: u8, second: u8 };

fn rfc850Year(two_digits: u8, month: std.time.epoch.Month, day: u8, hour: u8, minute: u8, second: u8, now_seconds: i64) ?u16 {
    const now = epochParts(now_seconds) orelse return null;
    var year = (now.year / 100) * 100 + @as(u16, two_digits);
    const candidate = httpDateSeconds(year, month, day, hour, minute, second) orelse return null;
    const future_day = @min(now.day, std.time.epoch.getDaysInMonth(now.year + 50, now.month));
    const future_limit = httpDateSeconds(now.year + 50, now.month, future_day, now.hour, now.minute, now.second) orelse return null;
    if (candidate > future_limit) year -= 100;
    return year;
}

fn epochParts(seconds: i64) ?EpochParts {
    if (seconds < 0) return null;
    var remaining_days: u64 = @intCast(@divFloor(seconds, std.time.epoch.secs_per_day));
    var year: u16 = 1970;
    while (remaining_days >= std.time.epoch.getDaysInYear(year)) : (year += 1) remaining_days -= std.time.epoch.getDaysInYear(year);
    var month: u4 = 1;
    while (remaining_days >= std.time.epoch.getDaysInMonth(year, @enumFromInt(month))) : (month += 1) remaining_days -= std.time.epoch.getDaysInMonth(year, @enumFromInt(month));
    const day_seconds: u64 = @intCast(@mod(seconds, std.time.epoch.secs_per_day));
    return .{ .year = year, .month = @enumFromInt(month), .day = @intCast(remaining_days + 1), .hour = @intCast(day_seconds / 3600), .minute = @intCast((day_seconds % 3600) / 60), .second = @intCast(day_seconds % 60) };
}

fn parseObsoleteAsctimeDate(value: []const u8) ?i64 {
    if (value.len != 24 or value[3] != ' ' or value[7] != ' ' or value[10] != ' ' or value[13] != ':' or value[16] != ':' or value[19] != ' ') return null;
    return httpDateSeconds(
        std.fmt.parseInt(u16, value[20..24], 10) catch return null,
        parseHttpMonth(value[4..7]) orelse return null,
        parseSpacePaddedDay(value[8..10]) orelse return null,
        parseTwoDigits(value[11..13]) orelse return null,
        parseTwoDigits(value[14..16]) orelse return null,
        parseTwoDigits(value[17..19]) orelse return null,
    );
}

fn httpDateSeconds(year: u16, month: std.time.epoch.Month, day: u8, hour: u8, minute: u8, second: u8) ?i64 {
    if (year < 1970 or day == 0 or day > std.time.epoch.getDaysInMonth(year, month) or hour > 23 or minute > 59 or second > 59) return null;
    var total: u64 = 0;
    var current_year: u16 = 1970;
    while (current_year < year) : (current_year += 1) total += std.time.epoch.getDaysInYear(current_year);
    var current_month: u4 = 1;
    while (current_month < @intFromEnum(month)) : (current_month += 1) total += std.time.epoch.getDaysInMonth(year, @enumFromInt(current_month));
    total += day - 1;
    total = total * std.time.epoch.secs_per_day + @as(u64, hour) * 3600 + @as(u64, minute) * 60 + second;
    return @intCast(total);
}

fn parseTwoDigits(value: []const u8) ?u8 {
    if (value.len != 2 or value[0] < '0' or value[0] > '9' or value[1] < '0' or value[1] > '9') return null;
    return (value[0] - '0') * 10 + value[1] - '0';
}

fn parseSpacePaddedDay(value: []const u8) ?u8 {
    if (value.len != 2) return null;
    if (value[0] == ' ' and value[1] >= '1' and value[1] <= '9') return value[1] - '0';
    return parseTwoDigits(value);
}

fn parseHttpMonth(value: []const u8) ?std.time.epoch.Month {
    const names = [_][]const u8{ "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
    for (names, 0..) |name, index| if (std.mem.eql(u8, value, name)) return @enumFromInt(index + 1);
    return null;
}

const max_retries_cap: u32 = 8;
const max_retry_delay_cap_ms: u64 = 60_000;
const initial_retry_delay_ms: u64 = 500;
const max_safe_jcs_integer: i64 = 9_007_199_254_740_991;
const RetryPolicy = struct {
    max_retries: u32,
    max_delay_ms: u64,
    fn fromOptions(options: protocol.StreamOptions, config: RetryConfig) RetryPolicy {
        const configured_retries = @min(config.max_retries, max_retries_cap);
        const configured_delay = @min(config.max_retry_delay_ms, max_retry_delay_cap_ms);
        return .{
            .max_retries = @min(options.max_retries orelse configured_retries, configured_retries),
            .max_delay_ms = @min(options.max_retry_delay_ms orelse configured_delay, configured_delay),
        };
    }
};
const AttemptResult = struct { status: u16, retry_after_ms: ?u64 = null, request_id: ?[]const u8 = null, diagnostic: ?Diagnostic = null };
const Diagnostic = struct {
    category: protocol.ProviderFailureCategory,
    status: ?u16 = null,
    request_id: [protocol.max_provider_failure_identifier_bytes]u8 = undefined,
    request_id_len: usize = 0,
    code: [protocol.max_provider_failure_identifier_bytes]u8 = undefined,
    code_len: usize = 0,
    typ: [protocol.max_provider_failure_identifier_bytes]u8 = undefined,
    type_len: usize = 0,
    param: [protocol.max_provider_failure_identifier_bytes]u8 = undefined,
    param_len: usize = 0,
    message: [protocol.max_provider_failure_message_bytes]u8 = undefined,
    message_len: usize = 0,
    retry_after_ms: ?u64 = null,
    fn requestId(self: *const Diagnostic) ?[]const u8 {
        return if (self.request_id_len == 0) null else self.request_id[0..self.request_id_len];
    }
    fn providerCode(self: *const Diagnostic) ?[]const u8 {
        return if (self.code_len == 0) null else self.code[0..self.code_len];
    }
    fn providerType(self: *const Diagnostic) ?[]const u8 {
        return if (self.type_len == 0) null else self.typ[0..self.type_len];
    }
    fn providerParam(self: *const Diagnostic) ?[]const u8 {
        return if (self.param_len == 0) null else self.param[0..self.param_len];
    }
    fn redactedMessage(self: *const Diagnostic) []const u8 {
        return self.message[0..self.message_len];
    }
};
const StreamAttempt = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    environ: ?*const std.process.Environ.Map,
    endpoint: []const u8,
    headers: []const Header,
    dialect: Dialect,
    model: *const protocol.Model,
    context: *const protocol.Context,
    options: protocol.StreamOptions,
    sink_context: *anyopaque,
    sink: protocol.StreamSink,
    max_response_body: usize,
    max_event_bytes: usize,
    callback_attempted: bool = false,
    deferred_diagnostic: ?Diagnostic = null,
};

fn streamWithConfig(allocator: std.mem.Allocator, io: std.Io, environ: ?*const std.process.Environ.Map, endpoint: []const u8, headers: []const Header, dialect: Dialect, retry_config: RetryConfig, max_body: usize, max_event: usize, allow_tools: bool, cache_retention_wire: ?CacheRetentionWire, model: *const protocol.Model, context: *const protocol.Context, options: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink, observer_context: ?*anyopaque, observer: ?RequestObserver) !void {
    if ((observer_context == null) != (observer == null)) return error.InvalidRequestObserver;
    context.validate() catch {
        try emitFailure(sink_context, sink, model, .invalid_request, null, "invalid request context", null, null, null, null);
        return;
    };
    if (options.transport == .websocket) {
        try emitFailure(sink_context, sink, model, .unsupported, null, "websocket transport is not supported", null, null, null, null);
        return;
    }
    if (model.id.len == 0 or model.max_tokens == 0 or model.context_window == 0) {
        try emitFailure(sink_context, sink, model, .invalid_request, null, "model capabilities are incomplete", null, null, null, null);
        return;
    }
    if (options.max_tokens) |limit| if (limit == 0 or limit > model.max_tokens) {
        try emitFailure(sink_context, sink, model, .invalid_request, null, "requested output limit exceeds model capability", null, null, null, null);
        return;
    };
    if (options.reasoning != null and !model.reasoning) {
        try emitFailure(sink_context, sink, model, .unsupported, null, "reasoning is not supported by selected model", null, null, null, null);
        return;
    }
    if (max_body == 0 or max_body > max_response_body_bytes or max_event == 0 or max_event > max_sse_event_bytes) {
        try emitFailure(sink_context, sink, model, .invalid_request, null, "provider resource bounds are invalid", null, null, null, null);
        return;
    }
    for (headers) |header| validateHeader(header) catch {
        try emitFailure(sink_context, sink, model, .invalid_request, null, "provider header is invalid", null, null, null, null);
        return;
    };
    const api_key = options.api_key orelse {
        try emitFailure(sink_context, sink, model, .authentication, null, "provider credential is unavailable", null, null, null, null);
        return;
    };
    validateCredential(api_key) catch {
        try emitFailure(sink_context, sink, model, .authentication, null, "provider credential is invalid", null, null, null, null);
        return;
    };
    var rendered = renderRequestWithCapabilities(allocator, model, context, options, dialect, allow_tools, cache_retention_wire) catch |err| {
        if (err == error.OutOfMemory or err == error.EventCallbackFailed or err == error.Canceled) return err;
        if (err == error.UnsupportedOption or err == error.UnsupportedTransport) {
            try emitFailure(sink_context, sink, model, .unsupported, null, "requested provider option is unsupported", null, null, null, null);
        } else {
            try emitFailure(sink_context, sink, model, .invalid_request, null, "request rendering failed", null, null, null, null);
        }
        return;
    };
    defer rendered.deinit();
    if (observer) |callback| try observeRenderedRequest(&rendered, observer_context.?, callback);
    const request_endpoint = (if (dialect == .anthropic_messages) anthropicEndpointUrl(allocator, endpoint) else endpointUrl(allocator, endpoint)) catch |err| {
        if (err == error.InvalidBaseUrl) {
            try emitFailure(sink_context, sink, model, .invalid_request, null, "provider endpoint is invalid", null, null, null, null);
            return;
        }
        return err;
    };
    defer allocator.free(request_endpoint);
    var attempt = StreamAttempt{ .allocator = allocator, .io = io, .environ = environ, .endpoint = request_endpoint, .headers = headers, .dialect = dialect, .model = model, .context = context, .options = options, .sink_context = sink_context, .sink = sink, .max_response_body = max_body, .max_event_bytes = max_event };
    const retry = RetryPolicy.fromOptions(options, retry_config);
    const deadline_ms = effectiveDeadlineMilliseconds(retry_config.deadline_ms, options.timeout_ms) catch {
        try emitFailure(sink_context, sink, model, .invalid_request, null, "provider deadline is invalid", null, null, null, null);
        return;
    };
    const deadline = if (deadline_ms) |ms| std.Io.Clock.Timestamp.now(io, .awake).addDuration(.{ .clock = .awake, .raw = std.Io.Duration.fromMilliseconds(@intCast(@min(ms, @as(u64, std.math.maxInt(i64))))) }) else null;
    var retries: u32 = 0;
    while (true) {
        if (deadline) |limit| if (std.Io.Clock.Timestamp.now(io, .awake).compare(.gte, limit)) {
            try emitFailure(sink_context, sink, model, .timeout, null, "provider deadline exceeded", null, null, null, null);
            return;
        };
        attempt.callback_attempted = false;
        attempt.deferred_diagnostic = null;
        const remaining_ms = if (deadline) |limit| remainingMilliseconds(io, limit) else null;
        const result = runStreamAttempt(&attempt, rendered.bytes, api_key, remaining_ms) catch |err| {
            if (err == error.Canceled) return error.Canceled;
            if (err == error.EventCallbackFailed) return err;
            if (err == error.OutOfMemory) return err;
            if (isTransientTransport(err) and !attempt.callback_attempted and retries < retry.max_retries and deadlineAllows(io, deadline)) {
                sleepRetry(io, retryDelayMs(retry, retries, null), deadline) catch |sleep_err| {
                    if (sleep_err == error.Timeout) {
                        try emitFailure(sink_context, sink, model, .timeout, null, "provider retry deadline exceeded", null, null, null, null);
                        return;
                    }
                    return sleep_err;
                };
                retries += 1;
                continue;
            }
            const category: protocol.ProviderFailureCategory = if (err == error.Timeout) .timeout else if (err == error.InvalidCompressedResponse or err == error.InvalidHttpResponseBody) .malformed_response else if (err == error.UnsupportedCompressionMethod) .unsupported else .connection;
            const message: []const u8 = if (err == error.Timeout) "provider request timed out" else if (err == error.InvalidCompressedResponse) "provider response compression was invalid" else if (err == error.InvalidHttpResponseBody) "provider response framing was invalid" else if (err == error.UnsupportedCompressionMethod) "provider response compression is unsupported" else "provider connection failed";
            try emitFailure(sink_context, sink, model, category, null, message, null, null, null, null);
            return;
        };
        if (result.diagnostic) |diagnostic| {
            if (isRetryableDiagnostic(result.diagnostic.?) and !attempt.callback_attempted and retries < retry.max_retries and deadlineAllows(io, deadline)) {
                sleepRetry(io, retryDelayMs(retry, retries, result.retry_after_ms), deadline) catch |sleep_err| {
                    if (sleep_err == error.Timeout) {
                        try emitFailure(sink_context, sink, model, .timeout, null, "provider retry deadline exceeded", null, null, null, null);
                        return;
                    }
                    return sleep_err;
                };
                retries += 1;
                continue;
            }
            try emitDiagnostic(sink_context, sink, model, diagnostic);
            return;
        }
        return;
    }
}

fn observeRenderedRequest(rendered: *const RenderedRequest, context: *anyopaque, observer: RequestObserver) !void {
    var request_hash: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash(rendered.bytes, &request_hash, .{});
    observer(context, .{
        .request_len = rendered.bytes.len,
        .request_hash = request_hash,
        .cache_prefix_len = rendered.cache_prefix_len,
        .cache_prefix_hash = rendered.cache_prefix_hash,
    }) catch return error.RequestObserverFailed;
}
fn remainingMilliseconds(io: std.Io, deadline: std.Io.Clock.Timestamp) u64 {
    const ms = std.Io.Clock.Timestamp.now(io, .awake).durationTo(deadline).raw.toMilliseconds();
    return if (ms <= 0) 0 else @intCast(ms);
}
fn effectiveDeadlineMilliseconds(configured: ?u64, requested: ?u64) !?u64 {
    const maximum: u64 = std.math.maxInt(i64);
    if (configured) |value| if (value == 0 or value > maximum) return error.InvalidDeadline;
    if (requested) |value| if (value == 0 or value > maximum) return error.InvalidDeadline;
    if (configured) |ceiling| return if (requested) |value| @min(ceiling, value) else ceiling;
    return requested;
}
fn deadlineAllows(io: std.Io, deadline: ?std.Io.Clock.Timestamp) bool {
    return if (deadline) |limit| remainingMilliseconds(io, limit) > 0 else true;
}
fn sleepRetry(io: std.Io, delay_ms: u64, deadline: ?std.Io.Clock.Timestamp) !void {
    const bounded = if (deadline) |limit| @min(delay_ms, remainingMilliseconds(io, limit)) else delay_ms;
    if (bounded == 0) {
        if (deadline) |limit| if (!deadlineAllows(io, limit)) return error.Timeout;
        return;
    }
    try std.Io.sleep(io, .fromMilliseconds(@intCast(@min(bounded, @as(u64, std.math.maxInt(i64))))), .awake);
}
fn runStreamAttempt(attempt: *StreamAttempt, payload: []const u8, api_key: []const u8, timeout_ms: ?u64) !AttemptResult {
    if (timeout_ms) |ms| {
        if (ms == 0) return error.Timeout;
        var timeout_context = TimeoutContext{ .io = attempt.io, .timeout_ms = ms };
        var call = StreamAttemptCall{ .attempt = attempt, .payload = payload, .api_key = api_key };
        return runWithTimeout(attempt.io, &call, streamAttemptOpaque, &timeout_context, timeoutTask);
    }
    return streamAttempt(attempt, payload, api_key);
}
const StreamAttemptCall = struct { attempt: *StreamAttempt, payload: []const u8, api_key: []const u8 };
fn streamAttemptOpaque(raw: *anyopaque) !AttemptResult {
    const call: *StreamAttemptCall = @ptrCast(@alignCast(raw));
    return streamAttempt(call.attempt, call.payload, call.api_key);
}

fn streamAttempt(attempt: *StreamAttempt, payload: []const u8, api_key: []const u8) !AttemptResult {
    var client = std.http.Client{ .allocator = attempt.allocator, .io = attempt.io };
    defer client.deinit();
    if (attempt.environ) |environ| client.initDefaultProxies(attempt.allocator, environ) catch |err| if (err == error.OutOfMemory) return err else return error.ConnectionFailure;
    const uri = std.Uri.parse(attempt.endpoint) catch return error.InvalidBaseUrl;
    const authorization = try std.fmt.allocPrint(attempt.allocator, "Bearer {s}", .{api_key});
    defer attempt.allocator.free(authorization);
    var extra: [max_headers + 3]std.http.Header = undefined;
    for (attempt.headers, 0..) |header, index| extra[index] = .{ .name = header.name, .value = header.value };
    var extra_len = attempt.headers.len;
    extra[extra_len] = .{ .name = "accept", .value = "text/event-stream" };
    extra_len += 1;
    if (attempt.dialect == .anthropic_messages) {
        extra[extra_len] = .{ .name = "x-api-key", .value = api_key };
        extra_len += 1;
        const versioned = for (attempt.headers) |header| {
            if (std.ascii.eqlIgnoreCase(header.name, "anthropic-version")) break true;
        } else false;
        if (!versioned) {
            extra[extra_len] = .{ .name = "anthropic-version", .value = anthropic_version };
            extra_len += 1;
        }
    } else {
        extra[extra_len] = .{ .name = "authorization", .value = authorization };
        extra_len += 1;
    }
    var android_connection: ?*std.http.Client.Connection = null;
    if (comptime builtin.target.abi.isAndroid()) {
        var original_host_buffer: [std.Io.net.HostName.max_len]u8 = undefined;
        const original_host = uri.getHost(&original_host_buffer) catch return error.InvalidBaseUrl;
        var host_z_buffer: [std.Io.net.HostName.max_len + 1]u8 = undefined;
        const host_z = std.fmt.bufPrintZ(&host_z_buffer, "{s}", .{original_host.bytes}) catch return error.InvalidBaseUrl;
        var address_buffer: [4096]u8 = undefined;
        if (misul_android_resolve_addresses(host_z.ptr, &address_buffer, address_buffer.len) == 0) return error.NameServerFailure;
        const connection_protocol = std.http.Client.Protocol.fromUri(uri) orelse return error.InvalidBaseUrl;
        const now = std.Io.Clock.real.now(attempt.io);
        client.ca_bundle.rescan(client.allocator, attempt.io, now) catch return error.CertificateBundleLoadFailure;
        client.now = now;
        android_connection = connectAndroidAddresses(
            &client,
            std.mem.sliceTo(&address_buffer, 0),
            uri.port orelse switch (connection_protocol) { .plain => 80, .tls => 443 },
            connection_protocol,
            original_host,
        ) catch |err| switch (err) {
            error.Canceled => return error.Canceled,
            error.OutOfMemory => return error.OutOfMemory,
            else => return error.ConnectionFailure,
        };
    }
    var request = client.request(.POST, uri, .{ .connection = android_connection, .redirect_behavior = .unhandled, .headers = .{ .content_type = .{ .override = "application/json" }, .authorization = .{ .omit = {} } }, .extra_headers = extra[0..extra_len] }) catch |err| switch (err) {
        error.Canceled => return error.Canceled,
        error.OutOfMemory => return error.OutOfMemory,
        else => return error.ConnectionFailure,
    };
    defer request.deinit();
    request.transfer_encoding = .{ .content_length = payload.len };
    var request_body = request.sendBodyUnflushed(&.{}) catch return error.TransportWriteFailed;
    request_body.writer.writeAll(payload) catch |err| if (err == error.OutOfMemory) return err else return error.TransportWriteFailed;
    request_body.end() catch |err| if (err == error.OutOfMemory) return err else return error.TransportWriteFailed;
    request.connection.?.flush() catch |err| if (err == error.OutOfMemory) return err else return error.TransportWriteFailed;
    var response = request.receiveHead(&.{}) catch |err| return switch (err) {
        error.Canceled => error.Canceled,
        error.OutOfMemory => error.OutOfMemory,
        else => error.ConnectionFailure,
    };
    const status = @intFromEnum(response.head.status);
    var request_id_storage: [protocol.max_provider_failure_identifier_bytes]u8 = undefined;
    var request_id_len: usize = 0;
    var retry_after_ms: ?u64 = null;
    var header_it = response.head.iterateHeaders();
    while (header_it.next()) |header| if (std.ascii.eqlIgnoreCase(header.name, "x-request-id") or std.ascii.eqlIgnoreCase(header.name, "x-openrouter-request-id") or std.ascii.eqlIgnoreCase(header.name, "request-id")) {
        request_id_len = copyBounded(request_id_storage[0..], header.value);
        break;
    };
    var retry_header_it = response.head.iterateHeaders();
    while (retry_header_it.next()) |header| if (std.ascii.eqlIgnoreCase(header.name, "retry-after")) {
        retry_after_ms = parseRetryAfter(header.value, std.Io.Clock.real.now(attempt.io).toSeconds());
        break;
    };
    const request_id = if (request_id_len == 0) null else request_id_storage[0..request_id_len];
    const encoding = response.head.content_encoding;
    if (status < 200 or status >= 300) {
        var transfer_buffer: [4096]u8 = undefined;
        var decompress_buffer: []u8 = &.{};
        if (encoding == .zstd) decompress_buffer = try attempt.allocator.alloc(u8, std.compress.zstd.default_window_len) else if (encoding == .gzip or encoding == .deflate) decompress_buffer = try attempt.allocator.alloc(u8, std.compress.flate.max_window_len);
        defer if (decompress_buffer.len != 0) attempt.allocator.free(decompress_buffer);
        var decompress: std.http.Decompress = undefined;
        const reader = response.readerDecompressing(&transfer_buffer, &decompress, decompress_buffer);
        var body: std.Io.Writer.Allocating = std.Io.Writer.Allocating.initCapacity(attempt.allocator, 4096) catch return error.OutOfMemory;
        defer body.deinit();
        readBounded(reader, &body, max_error_body_bytes) catch |err| {
            if (err == error.ResponseBodyTooLarge) {
                var diagnostic = diagnosticForStatusSecrets(status, request_id, "provider error response exceeded bound", api_key, attempt.headers);
                diagnostic.retry_after_ms = retry_after_ms;
                return .{ .status = status, .retry_after_ms = retry_after_ms, .request_id = request_id, .diagnostic = diagnostic };
            }
            if (err == error.ReadFailed and decompressionFailed(&decompress)) return error.InvalidCompressedResponse;
            if (err == error.ReadFailed and response.bodyErr() != null) return error.InvalidHttpResponseBody;
            return err;
        };
        var diagnostic = try parseDiagnostic(attempt.allocator, status, body.writer.buffered(), request_id, api_key, attempt.headers);
        diagnostic.retry_after_ms = retry_after_ms;
        return .{ .status = status, .retry_after_ms = retry_after_ms, .request_id = request_id, .diagnostic = diagnostic };
    }
    if (encoding != .identity and encoding != .gzip and encoding != .deflate and encoding != .zstd) return error.UnsupportedCompressionMethod;
    var transfer_buffer: [4096]u8 = undefined;
    var decompress_buffer: []u8 = &.{};
    if (encoding == .zstd) decompress_buffer = try attempt.allocator.alloc(u8, std.compress.zstd.default_window_len) else if (encoding == .gzip or encoding == .deflate) decompress_buffer = try attempt.allocator.alloc(u8, std.compress.flate.max_window_len);
    defer if (decompress_buffer.len != 0) attempt.allocator.free(decompress_buffer);
    var decompress: std.http.Decompress = undefined;
    const reader = response.readerDecompressing(&transfer_buffer, &decompress, decompress_buffer);
    var decoder = StreamDecoder.init(attempt.allocator, attempt.model, apiName(attempt.dialect), attempt.dialect, attempt.max_event_bytes, attempt.max_response_body, status, request_id, api_key, attempt.headers, attempt.sink_context, attempt.sink, &attempt.callback_attempted);
    defer decoder.deinit();
    decoder.deferred_diagnostic = &attempt.deferred_diagnostic;
    decoder.retry_after_ms = retry_after_ms;
    var parser = SseParser.initWithBounds(attempt.allocator, attempt.max_event_bytes, attempt.max_response_body, &decoder, decoderEvent);
    defer parser.deinit();
    while (true) {
        var byte: [1]u8 = undefined;
        var writer = std.Io.Writer.fixed(&byte);
        const n = reader.stream(&writer, .limited(1)) catch |err| switch (err) {
            error.EndOfStream => break,
            error.WriteFailed => unreachable,
            error.ReadFailed => if (decompressionFailed(&decompress)) return error.InvalidCompressedResponse else if (response.bodyErr() != null) return error.InvalidHttpResponseBody else return error.ReadFailed,
        };
        if (n == 0) continue;
        parser.feed(writer.buffer[0..writer.end]) catch |err| {
            if (err == error.Canceled or err == error.EventCallbackFailed or err == error.OutOfMemory) return err;
            if (!decoder.failed) try decoder.fail(.malformed_response, if (err == error.ResponseBodyTooLarge) "provider response exceeded bound" else if (err == error.EventTooLarge) "provider event exceeded bound" else "provider event framing was invalid");
            return .{ .status = status, .retry_after_ms = retry_after_ms, .request_id = request_id, .diagnostic = attempt.deferred_diagnostic };
        };
        if (decoder.failed) return .{ .status = status, .retry_after_ms = retry_after_ms, .request_id = request_id, .diagnostic = attempt.deferred_diagnostic };
        if (decoder.terminated) {
            parser.finish() catch |err| {
                if (err == error.Canceled or err == error.EventCallbackFailed or err == error.OutOfMemory) return err;
                if (!decoder.failed) try decoder.fail(.malformed_response, "truncated provider event stream");
                return .{ .status = status, .retry_after_ms = retry_after_ms, .request_id = request_id, .diagnostic = attempt.deferred_diagnostic };
            };
            decoder.finish() catch |err| {
                if (err == error.Canceled or err == error.EventCallbackFailed or err == error.OutOfMemory) return err;
                if (!decoder.failed) try decoder.fail(.malformed_response, "provider stream did not terminate with a valid completion");
            };
            return .{ .status = status, .retry_after_ms = retry_after_ms, .request_id = request_id, .diagnostic = attempt.deferred_diagnostic };
        }
    }
    parser.finish() catch |err| {
        if (err == error.Canceled or err == error.EventCallbackFailed or err == error.OutOfMemory) return err;
        if (!decoder.failed) try decoder.fail(.malformed_response, "truncated provider event stream");
        return .{ .status = status, .retry_after_ms = retry_after_ms, .request_id = request_id, .diagnostic = attempt.deferred_diagnostic };
    };
    decoder.finish() catch |err| {
        if (err == error.Canceled or err == error.EventCallbackFailed or err == error.OutOfMemory) return err;
        if (!decoder.failed) try decoder.fail(.malformed_response, "provider stream did not terminate with a valid completion");
    };
    return .{ .status = status, .retry_after_ms = retry_after_ms, .request_id = request_id, .diagnostic = attempt.deferred_diagnostic };
}

extern fn misul_android_resolve_addresses(host: [*:0]const u8, output: [*]u8, output_size: usize) c_int;

const AndroidConnectionResult = std.http.Client.ConnectTcpError!*std.http.Client.Connection;

fn connectAndroidAddresses(
    client: *std.http.Client,
    resolved_addresses: []const u8,
    port: u16,
    connection_protocol: std.http.Client.Protocol,
    original_host: std.Io.net.HostName,
) std.http.Client.ConnectTcpError!*std.http.Client.Connection {
    var result_buffer: [32]AndroidConnectionResult = undefined;
    var result_queue: std.Io.Queue(AndroidConnectionResult) = .init(&result_buffer);
    var group: std.Io.Group = .init;
    var launched: usize = 0;
    var addresses = std.mem.splitScalar(u8, resolved_addresses, '\n');
    while (addresses.next()) |address| {
        if (address.len == 0) continue;
        if (launched == result_buffer.len) break;
        group.async(client.io, enqueueAndroidConnection, .{
            client,
            std.Io.net.HostName{ .bytes = address },
            port,
            connection_protocol,
            original_host,
            &result_queue,
        });
        launched += 1;
    }
    if (launched == 0) return error.UnknownHostName;
    defer {
        group.cancel(client.io);
        result_queue.close(client.io);
        while (result_queue.getOneUncancelable(client.io)) |loser| {
            discardAndroidConnection(client, loser);
        } else |err| switch (err) {
            error.Closed => {},
        }
    }
    var remaining = launched;
    while (remaining > 0) : (remaining -= 1) {
        const result = result_queue.getOne(client.io) catch |err| switch (err) {
            error.Canceled => return error.Canceled,
            error.Closed => return error.Unexpected,
        };
        if (result) |connection| return connection else |_| {}
    }
    return error.ConnectionRefused;
}

fn enqueueAndroidConnection(
    client: *std.http.Client,
    address: std.Io.net.HostName,
    port: u16,
    connection_protocol: std.http.Client.Protocol,
    original_host: std.Io.net.HostName,
    result_queue: *std.Io.Queue(AndroidConnectionResult),
) std.Io.Cancelable!void {
    const result: AndroidConnectionResult = client.connectTcpOptions(.{
        .host = address,
        .port = port,
        .protocol = connection_protocol,
        .proxied_host = original_host,
    }) catch |err| switch (err) {
        error.Canceled => return error.Canceled,
        else => |connection_error| connection_error,
    };
    result_queue.putOne(client.io, result) catch |err| switch (err) {
        error.Canceled => {
            discardAndroidConnection(client, result);
            return error.Canceled;
        },
        error.Closed => {
            discardAndroidConnection(client, result);
            return;
        },
    };
}

fn discardAndroidConnection(client: *std.http.Client, result: AndroidConnectionResult) void {
    if (result) |connection| {
        connection.closing = true;
        client.connection_pool.release(connection, client.io);
    } else |_| {}
}
fn readBounded(reader: *std.Io.Reader, output: *std.Io.Writer.Allocating, maximum: usize) !void {
    var buffer: [4096]u8 = undefined;
    while (true) {
        const n = reader.readSliceShort(&buffer) catch |err| switch (err) {
            error.ReadFailed => return error.ReadFailed,
        };
        if (n == 0) return;
        if (output.writer.end + n > maximum) return error.ResponseBodyTooLarge;
        try output.writer.writeAll(buffer[0..n]);
    }
}
fn decompressionFailed(decompress: *const std.http.Decompress) bool {
    return switch (decompress.*) {
        .flate => |state| state.err != null,
        .zstd => |state| state.err != null,
        .none => false,
    };
}
fn apiName(dialect: Dialect) []const u8 {
    return switch (dialect) {
        .openai_chat => "openai-chat",
        .openrouter_chat => "openrouter-chat",
        .deepseek_chat => "deepseek-chat",
        .anthropic_messages => "anthropic-messages",
    };
}

pub const SseEvent = struct { event: []const u8, data: []const u8 };
pub const SseParser = struct {
    allocator: std.mem.Allocator,
    max_event_bytes: usize,
    max_response_bytes: usize,
    stream_bytes: usize = 0,
    data_seen: bool = false,
    line: std.ArrayList(u8) = .empty,
    data: std.ArrayList(u8) = .empty,
    event_name: std.ArrayList(u8) = .empty,
    prefix: [3]u8 = undefined,
    prefix_len: usize = 0,
    bom_done: bool = false,
    pending_cr: bool = false,
    context: *anyopaque,
    callback: *const fn (*anyopaque, SseEvent) anyerror!void,
    pub fn init(allocator: std.mem.Allocator, max_event_bytes: usize, context: *anyopaque, callback: *const fn (*anyopaque, SseEvent) anyerror!void) SseParser {
        return initWithBounds(allocator, max_event_bytes, max_response_body_bytes, context, callback);
    }
    pub fn initWithBounds(allocator: std.mem.Allocator, max_event_bytes: usize, max_response_bytes: usize, context: *anyopaque, callback: *const fn (*anyopaque, SseEvent) anyerror!void) SseParser {
        return .{ .allocator = allocator, .max_event_bytes = @min(max_event_bytes, max_sse_event_bytes), .max_response_bytes = @min(max_response_bytes, max_response_body_bytes), .context = context, .callback = callback };
    }
    pub fn deinit(self: *SseParser) void {
        self.line.deinit(self.allocator);
        self.data.deinit(self.allocator);
        self.event_name.deinit(self.allocator);
        self.* = undefined;
    }
    pub fn feed(self: *SseParser, bytes: []const u8) !void {
        var index: usize = 0;
        if (!self.bom_done) {
            while (index < bytes.len and self.prefix_len < 3) : (index += 1) {
                if (self.stream_bytes >= self.max_response_bytes) return error.ResponseBodyTooLarge;
                self.stream_bytes += 1;
                self.prefix[self.prefix_len] = bytes[index];
                self.prefix_len += 1;
            }
            if (self.prefix_len == 3) {
                self.bom_done = true;
                if (!std.mem.eql(u8, &self.prefix, "\xef\xbb\xbf")) for (self.prefix) |byte| try self.processByte(byte);
            } else return;
        }
        while (index < bytes.len) : (index += 1) try self.feedByte(bytes[index]);
    }
    pub fn finish(self: *SseParser) !void {
        if (!self.bom_done) {
            self.bom_done = true;
            for (self.prefix[0..self.prefix_len]) |byte| try self.processByte(byte);
        }
        self.pending_cr = false;
        if (self.line.items.len != 0 or self.data_seen or self.event_name.items.len != 0) return error.TruncatedProviderResponse;
    }
    fn feedByte(self: *SseParser, byte: u8) !void {
        if (self.stream_bytes >= self.max_response_bytes) return error.ResponseBodyTooLarge;
        self.stream_bytes += 1;
        try self.processByte(byte);
    }
    fn processByte(self: *SseParser, byte: u8) !void {
        if (self.pending_cr) {
            self.pending_cr = false;
            if (byte == '\n') return;
        }
        if (byte == '\r') {
            try self.processLine();
            self.pending_cr = true;
        } else if (byte == '\n') try self.processLine() else {
            if (self.line.items.len >= max_sse_line_bytes) return error.EventTooLarge;
            try self.line.append(self.allocator, byte);
        }
    }
    fn processLine(self: *SseParser) !void {
        defer self.line.clearRetainingCapacity();
        if (self.line.items.len == 0) {
            try self.dispatch();
            return;
        }
        if (self.line.items[0] == ':') return;
        const colon = std.mem.indexOfScalar(u8, self.line.items, ':');
        const field = if (colon) |index| self.line.items[0..index] else self.line.items;
        if (field.len == 0) return;
        var value: []const u8 = if (colon) |index| self.line.items[index + 1 ..] else &.{};
        if (value.len != 0 and value[0] == ' ') value = value[1..];
        if (std.mem.eql(u8, field, "data")) {
            const separator: usize = if (self.data_seen) 1 else 0;
            if (separator > self.max_event_bytes or self.data.items.len > self.max_event_bytes - separator or value.len > self.max_event_bytes - separator - self.data.items.len) return error.EventTooLarge;
            if (self.data_seen) try self.data.append(self.allocator, '\n');
            try self.data.appendSlice(self.allocator, value);
            self.data_seen = true;
        } else if (std.mem.eql(u8, field, "event")) {
            self.event_name.clearRetainingCapacity();
            if (value.len > max_sse_line_bytes) return error.EventTooLarge;
            try self.event_name.appendSlice(self.allocator, value);
        }
    }
    fn dispatch(self: *SseParser) !void {
        defer {
            self.data.clearRetainingCapacity();
            self.event_name.clearRetainingCapacity();
            self.data_seen = false;
        }
        if (!self.data_seen) return;
        try self.callback(self.context, .{ .event = if (self.event_name.items.len == 0) "message" else self.event_name.items, .data = self.data.items });
    }
};

const ToolState = struct {
    wire_index: usize,
    content_index: usize = 0,
    id: ?[]u8 = null,
    name: ?[]u8 = null,
    arguments: std.ArrayList(u8) = .empty,
    started: bool = false,
    fn deinit(self: *ToolState, allocator: std.mem.Allocator) void {
        if (self.id) |value| allocator.free(value);
        if (self.name) |value| allocator.free(value);
        self.arguments.deinit(allocator);
        self.* = undefined;
    }
};
const WireBlock = struct {
    wire_index: u64,
    content_index: usize,
    thinking: bool,
    text: std.ArrayList(u8) = .empty,
    signature: std.ArrayList(u8) = .empty,
    fn deinit(self: *WireBlock, allocator: std.mem.Allocator) void {
        self.text.deinit(allocator);
        self.signature.deinit(allocator);
        self.* = undefined;
    }
};
const StreamDecoder = struct {
    allocator: std.mem.Allocator,
    model: *const protocol.Model,
    api: []const u8,
    dialect: Dialect,
    max_event_bytes: usize,
    max_response_body: usize,
    status: u16,
    request_id: ?[]const u8,
    retry_after_ms: ?u64 = null,
    api_key: []const u8,
    headers: []const Header,
    sink_context: *anyopaque,
    sink: protocol.StreamSink,
    callback_attempted: *bool,
    deferred_diagnostic: ?*?Diagnostic = null,
    text: std.ArrayList(u8) = .empty,
    thinking: std.ArrayList(u8) = .empty,
    tools: std.ArrayList(ToolState) = .empty,
    text_started: bool = false,
    thinking_started: bool = false,
    text_index: ?usize = null,
    thinking_index: ?usize = null,
    next_content_index: usize = 0,
    started: bool = false,
    finish_reason: ?protocol.StopReason = null,
    terminated: bool = false,
    failed: bool = false,
    usage_seen: bool = false,
    usage: protocol.Usage = .{},
    response_bytes: usize = 0,
    /// Anthropic text and thinking blocks; each keeps its own content index and signature.
    blocks: std.ArrayList(WireBlock) = .empty,
    anthropic_usage: [4]u64 = .{ 0, 0, 0, 0 },
    fn init(allocator: std.mem.Allocator, model: *const protocol.Model, api: []const u8, dialect: Dialect, max_event_bytes: usize, max_response_body: usize, status: u16, request_id: ?[]const u8, api_key: []const u8, headers: []const Header, sink_context: *anyopaque, sink: protocol.StreamSink, callback_attempted: *bool) StreamDecoder {
        return .{ .allocator = allocator, .model = model, .api = api, .dialect = dialect, .max_event_bytes = max_event_bytes, .max_response_body = max_response_body, .status = status, .request_id = request_id, .api_key = api_key, .headers = headers, .sink_context = sink_context, .sink = sink, .callback_attempted = callback_attempted };
    }
    fn deinit(self: *StreamDecoder) void {
        self.text.deinit(self.allocator);
        self.thinking.deinit(self.allocator);
        for (self.tools.items) |*tool| tool.deinit(self.allocator);
        self.tools.deinit(self.allocator);
        for (self.blocks.items) |*block| block.deinit(self.allocator);
        self.blocks.deinit(self.allocator);
        self.* = undefined;
    }
    fn emit(self: *StreamDecoder, event: protocol.StreamEvent) !void {
        self.callback_attempted.* = true;
        self.sink(self.sink_context, event) catch |err| {
            if (err == error.Canceled) return error.Canceled;
            return error.EventCallbackFailed;
        };
    }
    fn start(self: *StreamDecoder) !void {
        if (self.started) return;
        self.started = true;
        try self.emit(.{ .start = .{ .content = &.{}, .api = self.api, .provider = self.model.provider, .model = self.model.id, .stop_reason = .stop, .timestamp_ms = 0 } });
    }
    fn fail(self: *StreamDecoder, category: protocol.ProviderFailureCategory, message: []const u8) !void {
        var diagnostic = diagnosticForStatusSecrets(self.status, self.request_id, message, self.api_key, self.headers);
        diagnostic.category = category;
        return self.failDiagnostic(diagnostic);
    }
    fn failDiagnostic(self: *StreamDecoder, diagnostic: Diagnostic) !void {
        if (self.failed) return;
        self.failed = true;
        var effective_diagnostic = diagnostic;
        if (effective_diagnostic.retry_after_ms == null) effective_diagnostic.retry_after_ms = self.retry_after_ms;
        if (!self.callback_attempted.*) if (self.deferred_diagnostic) |destination| {
            destination.* = effective_diagnostic;
            return;
        };
        const failure = protocol.ProviderFailure{ .category = effective_diagnostic.category, .http_status = effective_diagnostic.status, .request_id = effective_diagnostic.requestId(), .provider_code = effective_diagnostic.providerCode(), .provider_type = effective_diagnostic.providerType(), .provider_param = effective_diagnostic.providerParam(), .redacted_message = effective_diagnostic.redactedMessage(), .retry_after_ms = effective_diagnostic.retry_after_ms };
        try failure.validate();
        try self.emit(.{ .failed = .{ .content = &.{}, .api = self.api, .provider = self.model.provider, .model = self.model.id, .stop_reason = .@"error", .provider_failure = failure, .timestamp_ms = 0 } });
    }
    fn finish(self: *StreamDecoder) !void {
        if (self.failed) return;
        if (!self.terminated or self.finish_reason == null) return error.TruncatedProviderResponse;
        if (self.text_started) try self.emit(.{ .text_end = .{ .content_index = self.text_index.?, .content = self.text.items } });
        if (self.thinking_started) try self.emit(.{ .thinking_end = .{ .content_index = self.thinking_index.?, .content = self.thinking.items } });
        const content_count = self.next_content_index;
        const content = try self.allocator.alloc(protocol.AssistantContent, content_count);
        defer self.allocator.free(content);
        if (self.text_started) content[self.text_index.?] = .{ .text = .{ .text = self.text.items } };
        if (self.thinking_started) content[self.thinking_index.?] = .{ .thinking = .{ .thinking = self.thinking.items } };
        for (self.blocks.items) |block| {
            if (block.thinking) {
                try self.emit(.{ .thinking_end = .{ .content_index = block.content_index, .content = block.text.items } });
                content[block.content_index] = .{ .thinking = .{ .thinking = block.text.items, .signature = if (block.signature.items.len == 0) null else block.signature.items } };
            } else {
                try self.emit(.{ .text_end = .{ .content_index = block.content_index, .content = block.text.items } });
                content[block.content_index] = .{ .text = .{ .text = block.text.items } };
            }
        }
        var arguments_arena = std.heap.ArenaAllocator.init(self.allocator);
        defer arguments_arena.deinit();
        var active_tools: usize = 0;
        for (self.tools.items, 0..) |*tool, tool_index| {
            if (!tool.started) continue;
            const id = tool.id orelse return self.fail(.malformed_response, "provider tool call id was missing");
            const name = tool.name orelse return self.fail(.malformed_response, "provider tool call name was missing");
            for (self.tools.items[0..tool_index]) |previous| if (previous.started) if (previous.id) |previous_id| {
                if (std.mem.eql(u8, previous_id, id)) return self.fail(.malformed_response, "provider tool call id was duplicated");
            };
            // Several providers stream no argument text for a call without parameters.
            const argument_text = if (std.mem.trim(u8, tool.arguments.items, " \t\r\n").len == 0) "{}" else tool.arguments.items;
            const arguments = std.json.parseFromSliceLeaky(std.json.Value, arguments_arena.allocator(), argument_text, .{}) catch |err| switch (err) {
                error.OutOfMemory => return err,
                else => return self.fail(.malformed_response, "provider tool arguments were invalid"),
            };
            const call = protocol.ToolCall{ .id = id, .name = name, .arguments = arguments };
            content[tool.content_index] = .{ .tool_call = call };
            active_tools += 1;
        }
        const finish_reason = self.finish_reason.?;
        // Gemini-, Qwen-, and Kimi-backed routes report "stop" for completed tool calls. A call
        // cut off by the output limit still fails above when its arguments do not parse.
        if (active_tools == 0 and finish_reason == .tool_use) return self.fail(.malformed_response, "provider finish reason required missing tool calls");
        for (self.tools.items) |tool| if (tool.started) {
            const call = switch (content[tool.content_index]) {
                .tool_call => |value| value,
                else => unreachable,
            };
            try self.emit(.{ .tool_call_end = .{ .content_index = tool.content_index, .tool_call = call } });
        };
        const stop = if (active_tools != 0) protocol.StopReason.tool_use else finish_reason;
        try self.emit(.{ .done = .{ .content = content, .api = self.api, .provider = self.model.provider, .model = self.model.id, .stop_reason = stop, .timestamp_ms = 0, .usage = self.usage } });
    }
};
fn decoderEvent(raw: *anyopaque, event: SseEvent) !void {
    const decoder: *StreamDecoder = @ptrCast(@alignCast(raw));
    if (decoder.failed) return;
    if (decoder.terminated) return decoder.fail(.malformed_response, "provider sent data after completion");
    if (decoder.dialect == .anthropic_messages) return anthropicEvent(decoder, event);
    if (std.mem.eql(u8, event.data, "[DONE]")) {
        decoder.terminated = true;
        return;
    }
    if (event.data.len > decoder.max_event_bytes) return decoder.fail(.malformed_response, "provider event exceeded bound");
    if (decoder.response_bytes > decoder.max_response_body or event.data.len > decoder.max_response_body - decoder.response_bytes) return decoder.fail(.malformed_response, "provider response exceeded bound");
    decoder.response_bytes += event.data.len;
    var arena = std.heap.ArenaAllocator.init(decoder.allocator);
    defer arena.deinit();
    const value = std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), event.data, .{}) catch |err| switch (err) {
        error.OutOfMemory => return err,
        else => return decoder.fail(.malformed_response, "provider event was not valid JSON"),
    };
    if (value != .object) return decoder.fail(.malformed_response, "provider event was not an object");
    if (value.object.get("error")) |wire_error| {
        _ = wire_error;
        return decoder.failDiagnostic(try parseDiagnostic(decoder.allocator, decoder.status, event.data, decoder.request_id, decoder.api_key, decoder.headers));
    }
    var pending_usage: ?protocol.Usage = null;
    if (value.object.get("usage")) |usage| {
        if (usage != .null) {
            if (usage != .object) return decoder.fail(.malformed_response, "provider usage was not an object");
            const parsed_usage = parseUsage(decoder.dialect, usage.object) catch return decoder.fail(.malformed_response, "provider usage was invalid");
            // Usage events replace earlier reports rather than accumulate, so a provider that
            // repeats usage (identical: skipped) or refines it (different: latest wins) cannot
            // corrupt accounting and must not fail an otherwise complete response.
            if (!decoder.usage_seen or !usageEqual(decoder.usage, parsed_usage)) pending_usage = parsed_usage;
        }
    }
    const raw_choices = value.object.get("choices");
    if (raw_choices == null or raw_choices.? == .null) {
        // Usage-only and metadata-only chunks carry no output. Emit any usage they report and
        // ignore the rest, before or after the finish reason.
        if (pending_usage) |usage| try emitUsage(decoder, usage);
        return;
    }
    const choices = raw_choices.?;
    if (choices != .array) return decoder.fail(.malformed_response, "provider choice count was invalid");
    if (decoder.finish_reason) |finish_reason| {
        // OpenAI-compatible providers (OpenRouter in particular) may repeat the finished choice,
        // with a content-free delta, on the final usage chunk or as a bare duplicate. Accept only
        // inert tails: new text, reasoning, tool calls, or a changed finish reason still fail.
        if (choices.array.items.len > 1) return decoder.fail(.malformed_response, "provider sent choices after finish reason");
        if (choices.array.items.len == 1) switch (classifyTailChoice(choices.array.items[0], finish_reason)) {
            .inert => {},
            .error_finish => return decoder.fail(.provider, provider_error_finish_message),
            .changed_finish => return decoder.fail(.malformed_response, "provider finish reason changed"),
            .active => return decoder.fail(.malformed_response, "provider sent choices after finish reason"),
        };
        if (pending_usage) |usage| try emitUsage(decoder, usage);
        return;
    }
    if (choices.array.items.len == 0) {
        if (pending_usage) |usage| try emitUsage(decoder, usage);
        return;
    }
    if (choices.array.items.len != 1) return decoder.fail(.malformed_response, "provider choice count was invalid");
    const choice = choices.array.items[0];
    if (choice != .object) return decoder.fail(.malformed_response, "provider choice was not an object");
    if (choice.object.get("index")) |index| {
        const choice_index = uintField(index) orelse return decoder.fail(.malformed_response, "provider choice index was invalid");
        if (choice_index != 0) return decoder.fail(.malformed_response, "provider choice index was invalid");
    }
    if (choice.object.get("finish_reason")) |reason| {
        if (reason == .null) {} else if (reason == .string) {
            if (decoder.dialect == .deepseek_chat and std.mem.eql(u8, reason.string, "insufficient_system_resource")) return decoder.fail(.transient_provider, "provider inference resources were insufficient");
            if (std.mem.eql(u8, reason.string, "content_filter")) return decoder.fail(.provider, "provider content filter blocked output");
            if (std.mem.eql(u8, reason.string, "error")) return decoder.fail(.provider, provider_error_finish_message);
            const finish = mapStopReason(reason.string) orelse return decoder.fail(.malformed_response, "provider finish reason was invalid");
            if (decoder.finish_reason) |previous| if (previous != finish) return decoder.fail(.malformed_response, "provider finish reason changed");
            decoder.finish_reason = finish;
        } else return decoder.fail(.malformed_response, "provider finish reason was invalid");
    }
    const delta = choice.object.get("delta") orelse return decoder.fail(.malformed_response, "provider choice was missing a delta");
    if (delta != .object) return decoder.fail(.malformed_response, "provider delta was not an object");
    if (decoder.dialect == .openai_chat) if (delta.object.get("refusal")) |refusal| switch (refusal) {
        .null => {},
        .string => |message| if (message.len != 0) return decoder.fail(.provider, "provider refused the request"),
        else => return decoder.fail(.malformed_response, "provider refusal was invalid"),
    };
    if (delta.object.get("role")) |role| if (role != .null and (role != .string or !std.mem.eql(u8, role.string, "assistant"))) return decoder.fail(.malformed_response, "provider delta role was invalid");
    const content = delta.object.get("content");
    if (content) |content_value| if (content_value != .null and content_value != .string) return decoder.fail(.malformed_response, "provider content delta was invalid");
    const reasoning = switch (decoder.dialect) {
        .openai_chat => null,
        .openrouter_chat => delta.object.get("reasoning"),
        .deepseek_chat => delta.object.get("reasoning_content"),
        .anthropic_messages => unreachable,
    };
    if (reasoning) |reasoning_value| if (reasoning_value != .null and reasoning_value != .string) return decoder.fail(.malformed_response, "provider reasoning delta was invalid");
    const tool_calls = delta.object.get("tool_calls");
    if (tool_calls) |calls| {
        if (calls != .array) return decoder.fail(.malformed_response, "provider tool calls were not an array");
        for (calls.array.items) |call| {
            validateToolDeltaShape(call) catch return decoder.fail(.malformed_response, "provider tool delta was invalid");
        }
    }
    if (pending_usage) |usage| try emitUsage(decoder, usage);
    try decoder.start();
    if (content) |content_value| if (content_value == .string) {
        if (!decoder.text_started) {
            decoder.text_started = true;
            decoder.text_index = decoder.next_content_index;
            decoder.next_content_index += 1;
            try decoder.emit(.{ .text_start = decoder.text_index.? });
        }
        try appendBounded(decoder.allocator, &decoder.text, content_value.string, decoder.max_response_body);
        try decoder.emit(.{ .text_delta = .{ .content_index = decoder.text_index.?, .delta = content_value.string } });
    };
    switch (decoder.dialect) {
        .openai_chat => {},
        .openrouter_chat => {
            if (reasoning) |reasoning_value| if (reasoning_value == .string) try emitThinking(decoder, reasoning_value.string);
        },
        .deepseek_chat => {
            if (reasoning) |reasoning_value| if (reasoning_value == .string) try emitThinking(decoder, reasoning_value.string);
        },
        .anthropic_messages => unreachable,
    }
    if (tool_calls) |calls| {
        for (calls.array.items) |call| try emitToolDelta(decoder, call);
    }
}
/// Anthropic Messages SSE: message_start, content_block_start/delta/stop, message_delta,
/// message_stop, ping, and error. Unknown event types are ignored as the API documents.
fn anthropicEvent(decoder: *StreamDecoder, event: SseEvent) !void {
    if (event.data.len > decoder.max_event_bytes) return decoder.fail(.malformed_response, "provider event exceeded bound");
    if (decoder.response_bytes > decoder.max_response_body or event.data.len > decoder.max_response_body - decoder.response_bytes) return decoder.fail(.malformed_response, "provider response exceeded bound");
    decoder.response_bytes += event.data.len;
    var arena = std.heap.ArenaAllocator.init(decoder.allocator);
    defer arena.deinit();
    const value = std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), event.data, .{}) catch |err| switch (err) {
        error.OutOfMemory => return err,
        else => return decoder.fail(.malformed_response, "provider event was not valid JSON"),
    };
    if (value != .object) return decoder.fail(.malformed_response, "provider event was not an object");
    const kind = stringField(value.object, "type") orelse return decoder.fail(.malformed_response, "provider event type was missing");
    if (std.mem.eql(u8, kind, "ping")) return;
    if (std.mem.eql(u8, kind, "error")) {
        var diagnostic = try parseDiagnostic(decoder.allocator, decoder.status, event.data, decoder.request_id, decoder.api_key, decoder.headers);
        if (std.mem.eql(u8, diagnostic.providerType() orelse "", "overloaded_error") or std.mem.eql(u8, diagnostic.providerType() orelse "", "api_error")) diagnostic.category = .transient_provider;
        return decoder.failDiagnostic(diagnostic);
    }
    if (std.mem.eql(u8, kind, "message_start")) {
        const message = value.object.get("message") orelse return decoder.fail(.malformed_response, "provider message was missing");
        if (message != .object) return decoder.fail(.malformed_response, "provider message was not an object");
        try decoder.start();
        if (message.object.get("usage")) |usage| try anthropicUsage(decoder, usage);
        return;
    }
    if (std.mem.eql(u8, kind, "message_delta")) {
        if (value.object.get("delta")) |delta| if (delta == .object) if (delta.object.get("stop_reason")) |reason| if (reason != .null) {
            if (reason != .string) return decoder.fail(.malformed_response, "provider finish reason was invalid");
            if (std.mem.eql(u8, reason.string, "refusal")) return decoder.fail(.provider, "provider refused the request");
            const finish = anthropicStopReason(reason.string) orelse return decoder.fail(.malformed_response, "provider finish reason was invalid");
            if (decoder.finish_reason) |previous| if (previous != finish) return decoder.fail(.malformed_response, "provider finish reason changed");
            decoder.finish_reason = finish;
        };
        if (value.object.get("usage")) |usage| try anthropicUsage(decoder, usage);
        return;
    }
    if (std.mem.eql(u8, kind, "message_stop")) {
        decoder.terminated = true;
        return;
    }
    const is_start = std.mem.eql(u8, kind, "content_block_start");
    const is_delta = std.mem.eql(u8, kind, "content_block_delta");
    if (!is_start and !is_delta) return;
    if (!decoder.started) return decoder.fail(.malformed_response, "provider content preceded message start");
    const index = uintField(value.object.get("index") orelse .null) orelse return decoder.fail(.malformed_response, "provider content index was invalid");
    const payload = value.object.get(if (is_start) "content_block" else "delta") orelse return decoder.fail(.malformed_response, "provider content block was missing");
    if (payload != .object) return decoder.fail(.malformed_response, "provider content block was not an object");
    const block_type = stringField(payload.object, "type") orelse return decoder.fail(.malformed_response, "provider content block type was missing");
    if (is_start) {
        if (findWireBlock(decoder, index) != null or findWireTool(decoder, index) != null) return decoder.fail(.malformed_response, "provider content index was duplicated");
        if (std.mem.eql(u8, block_type, "redacted_thinking")) return;
        if (std.mem.eql(u8, block_type, "tool_use")) {
            const id = stringField(payload.object, "id") orelse "";
            const name = stringField(payload.object, "name") orelse "";
            if (id.len == 0 or name.len == 0) return decoder.fail(.malformed_response, "provider tool identity was invalid");
            if (decoder.tools.items.len >= max_tool_calls) return decoder.fail(.malformed_response, "provider tool call count exceeded bound");
            try decoder.tools.append(decoder.allocator, .{ .wire_index = @intCast(@min(index, std.math.maxInt(usize))), .content_index = decoder.next_content_index, .started = true });
            const tool = &decoder.tools.items[decoder.tools.items.len - 1];
            decoder.next_content_index += 1;
            tool.id = try decoder.allocator.dupe(u8, id);
            tool.name = try decoder.allocator.dupe(u8, name);
            try decoder.emit(.{ .tool_call_start = tool.content_index });
            // Non-streamed input arrives whole on the start event; streamed input starts as {}.
            if (payload.object.get("input")) |input| if (input == .object and input.object.count() != 0) {
                var rendered = std.Io.Writer.Allocating.init(decoder.allocator);
                defer rendered.deinit();
                try writeJson(&rendered.writer, input);
                try appendBounded(decoder.allocator, &tool.arguments, rendered.writer.buffered(), max_tool_arguments_bytes);
            };
            return;
        }
        const thinking = std.mem.eql(u8, block_type, "thinking");
        if (!thinking and !std.mem.eql(u8, block_type, "text")) return decoder.fail(.unsupported, "provider content block type is unsupported");
        try decoder.blocks.append(decoder.allocator, .{ .wire_index = index, .content_index = decoder.next_content_index, .thinking = thinking });
        decoder.next_content_index += 1;
        const block = &decoder.blocks.items[decoder.blocks.items.len - 1];
        try decoder.emit(if (thinking) .{ .thinking_start = block.content_index } else .{ .text_start = block.content_index });
        if (stringField(payload.object, if (thinking) "thinking" else "text")) |initial| if (initial.len != 0) try appendWireText(decoder, block, initial);
        if (thinking) if (stringField(payload.object, "signature")) |signature| try appendBounded(decoder.allocator, &block.signature, signature, decoder.max_response_body);
        return;
    }
    if (std.mem.eql(u8, block_type, "input_json_delta")) {
        const tool = findWireTool(decoder, index) orelse return decoder.fail(.malformed_response, "provider tool delta had no tool block");
        const partial = stringField(payload.object, "partial_json") orelse return decoder.fail(.malformed_response, "provider tool arguments were invalid");
        appendBounded(decoder.allocator, &tool.arguments, partial, max_tool_arguments_bytes) catch |err| if (err == error.OutOfMemory) return err else return decoder.fail(.malformed_response, "provider tool arguments exceeded bound");
        if (partial.len != 0) try decoder.emit(.{ .tool_call_delta = .{ .content_index = tool.content_index, .delta = partial } });
        return;
    }
    if (std.mem.eql(u8, block_type, "citations_delta")) return;
    const block = findWireBlock(decoder, index) orelse return decoder.fail(.malformed_response, "provider delta had no content block");
    if (std.mem.eql(u8, block_type, "signature_delta") and block.thinking) {
        const signature = stringField(payload.object, "signature") orelse return decoder.fail(.malformed_response, "provider signature was invalid");
        return appendBounded(decoder.allocator, &block.signature, signature, decoder.max_response_body);
    }
    const field = if (block.thinking) "thinking" else "text";
    if (!std.mem.eql(u8, block_type, if (block.thinking) "thinking_delta" else "text_delta")) return decoder.fail(.malformed_response, "provider delta type did not match its block");
    const text = stringField(payload.object, field) orelse return decoder.fail(.malformed_response, "provider content delta was invalid");
    if (text.len != 0) try appendWireText(decoder, block, text);
}
fn appendWireText(decoder: *StreamDecoder, block: *WireBlock, text: []const u8) !void {
    try appendBounded(decoder.allocator, &block.text, text, decoder.max_response_body);
    try decoder.emit(if (block.thinking) .{ .thinking_delta = .{ .content_index = block.content_index, .delta = text } } else .{ .text_delta = .{ .content_index = block.content_index, .delta = text } });
}
fn findWireBlock(decoder: *StreamDecoder, index: u64) ?*WireBlock {
    for (decoder.blocks.items) |*block| if (block.wire_index == index) return block;
    return null;
}
fn findWireTool(decoder: *StreamDecoder, index: u64) ?*ToolState {
    for (decoder.tools.items) |*tool| if (tool.wire_index == index) return tool;
    return null;
}
fn anthropicStopReason(value: []const u8) ?protocol.StopReason {
    if (std.mem.eql(u8, value, "end_turn") or std.mem.eql(u8, value, "stop_sequence") or std.mem.eql(u8, value, "pause_turn")) return .stop;
    if (std.mem.eql(u8, value, "max_tokens") or std.mem.eql(u8, value, "model_context_window_exceeded")) return .length;
    if (std.mem.eql(u8, value, "tool_use")) return .tool_use;
    return null;
}
/// Anthropic reports uncached, cache-read, and cache-write input separately; Misul's input
/// counts all prompt tokens, as OpenAI's prompt_tokens does. Later reports refine earlier ones.
fn anthropicUsage(decoder: *StreamDecoder, value: std.json.Value) !void {
    if (value == .null) return;
    if (value != .object) return decoder.fail(.malformed_response, "provider usage was not an object");
    const keys = [_][]const u8{ "input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens", "output_tokens" };
    for (keys, 0..) |key, slot| if (value.object.get(key)) |field| if (field != .null) {
        decoder.anthropic_usage[slot] = uintField(field) orelse return decoder.fail(.malformed_response, "provider usage was invalid");
    };
    const counts = decoder.anthropic_usage;
    const input = std.math.add(u64, counts[0], counts[1]) catch return decoder.fail(.malformed_response, "provider usage was invalid");
    const prompt = std.math.add(u64, input, counts[2]) catch return decoder.fail(.malformed_response, "provider usage was invalid");
    const total = std.math.add(u64, prompt, counts[3]) catch return decoder.fail(.malformed_response, "provider usage was invalid");
    const usage = protocol.Usage{ .input = prompt, .output = counts[3], .cache_read = counts[1], .cache_write = counts[2], .total_tokens = total };
    if (!decoder.usage_seen or !usageEqual(decoder.usage, usage)) try emitUsage(decoder, usage);
}
const provider_error_finish_message = "The provider ended the response with an error. Try again or choose another model.";
const TailChoice = enum { inert, active, changed_finish, error_finish };
fn classifyTailChoice(choice: std.json.Value, finish_reason: protocol.StopReason) TailChoice {
    if (choice != .object) return .active;
    if (choice.object.get("index")) |index| if ((uintField(index) orelse return .active) != 0) return .active;
    if (choice.object.get("error")) |wire_error| if (wire_error != .null) return .error_finish;
    if (choice.object.get("finish_reason")) |reason| switch (reason) {
        .null => {},
        .string => |text| {
            if (std.mem.eql(u8, text, "error")) return .error_finish;
            if ((mapStopReason(text) orelse return .changed_finish) != finish_reason) return .changed_finish;
        },
        else => return .active,
    };
    const delta = choice.object.get("delta") orelse return .inert;
    if (delta == .null) return .inert;
    if (delta != .object) return .active;
    if (delta.object.get("role")) |role| switch (role) {
        .null => {},
        .string => |text| if (!std.mem.eql(u8, text, "assistant")) return .active,
        else => return .active,
    };
    inline for (.{ "content", "reasoning", "reasoning_content", "refusal" }) |key| if (delta.object.get(key)) |field| switch (field) {
        .null => {},
        .string => |text| if (text.len != 0) return .active,
        else => return .active,
    };
    inline for (.{ "tool_calls", "reasoning_details" }) |key| if (delta.object.get(key)) |field| switch (field) {
        .null => {},
        .array => |items| if (items.items.len != 0) return .active,
        else => return .active,
    };
    return .inert;
}
fn emitUsage(decoder: *StreamDecoder, usage: protocol.Usage) !void {
    decoder.usage = usage;
    decoder.usage_seen = true;
    try decoder.start();
    try decoder.emit(.{ .usage = decoder.usage });
}
fn emitThinking(decoder: *StreamDecoder, text: []const u8) !void {
    if (!decoder.thinking_started) {
        decoder.thinking_started = true;
        decoder.thinking_index = decoder.next_content_index;
        decoder.next_content_index += 1;
        try decoder.emit(.{ .thinking_start = decoder.thinking_index.? });
    }
    try appendBounded(decoder.allocator, &decoder.thinking, text, decoder.max_response_body);
    try decoder.emit(.{ .thinking_delta = .{ .content_index = decoder.thinking_index.?, .delta = text } });
}
fn emitToolDelta(decoder: *StreamDecoder, value: std.json.Value) !void {
    if (value != .object) return decoder.fail(.malformed_response, "provider tool delta was not an object");
    if (value.object.get("type")) |raw| if (raw != .null and (raw != .string or !std.mem.eql(u8, raw.string, "function"))) return decoder.fail(.malformed_response, "provider tool type was invalid");
    const raw_index = uintField(value.object.get("index") orelse return decoder.fail(.malformed_response, "provider tool index was missing")) orelse return decoder.fail(.malformed_response, "provider tool index was invalid");
    if (raw_index > std.math.maxInt(usize)) return decoder.fail(.malformed_response, "provider tool index was invalid");
    const index: usize = @intCast(raw_index);
    if (index >= max_tool_calls) return decoder.fail(.malformed_response, "provider tool call count exceeded bound");
    while (decoder.tools.items.len <= index) try decoder.tools.append(decoder.allocator, .{ .wire_index = decoder.tools.items.len });
    var tool = &decoder.tools.items[index];
    if (!tool.started) {
        tool.started = true;
        tool.content_index = decoder.next_content_index;
        decoder.next_content_index += 1;
        try decoder.emit(.{ .tool_call_start = tool.content_index });
    }
    if (value.object.get("id")) |raw| {
        if (raw != .null) {
            if (raw != .string or raw.string.len == 0) return decoder.fail(.malformed_response, "provider tool id was invalid");
            try setIdentity(decoder.allocator, &tool.id, raw.string);
        }
    }
    const function = value.object.get("function") orelse return;
    if (function == .null) return;
    if (function != .object) return decoder.fail(.malformed_response, "provider function delta was not an object");
    if (function.object.get("name")) |raw| {
        if (raw != .null) {
            if (raw != .string or raw.string.len == 0) return decoder.fail(.malformed_response, "provider tool name was invalid");
            try setIdentity(decoder.allocator, &tool.name, raw.string);
        }
    }
    if (function.object.get("arguments")) |raw| {
        if (raw != .null) {
            if (raw != .string) return decoder.fail(.malformed_response, "provider tool arguments were invalid");
            try appendBounded(decoder.allocator, &tool.arguments, raw.string, max_tool_arguments_bytes);
            try decoder.emit(.{ .tool_call_delta = .{ .content_index = tool.content_index, .delta = raw.string } });
        }
    }
}
fn validateToolDeltaShape(value: std.json.Value) !void {
    if (value != .object) return error.InvalidToolDelta;
    const index = uintField(value.object.get("index") orelse return error.InvalidToolDelta) orelse return error.InvalidToolDelta;
    if (index >= max_tool_calls) return error.InvalidToolDelta;
    if (value.object.get("type")) |raw| if (raw != .null and (raw != .string or !std.mem.eql(u8, raw.string, "function"))) return error.InvalidToolDelta;
    if (value.object.get("id")) |raw| if (raw != .null and (raw != .string or raw.string.len == 0)) return error.InvalidToolDelta;
    const function = value.object.get("function") orelse return;
    if (function == .null) return;
    if (function != .object) return error.InvalidToolDelta;
    if (function.object.get("name")) |raw| if (raw != .null and (raw != .string or raw.string.len == 0)) return error.InvalidToolDelta;
    if (function.object.get("arguments")) |raw| if (raw != .null and (raw != .string or raw.string.len > max_tool_arguments_bytes)) return error.InvalidToolDelta;
}
fn setIdentity(allocator: std.mem.Allocator, destination: *?[]u8, value: []const u8) !void {
    if (destination.*) |existing| {
        if (!std.mem.eql(u8, existing, value)) return error.InvalidToolIdentity;
        return;
    }
    destination.* = try allocator.dupe(u8, value);
}
fn appendBounded(allocator: std.mem.Allocator, list: *std.ArrayList(u8), bytes: []const u8, maximum: usize) !void {
    if (list.items.len > maximum or bytes.len > maximum - list.items.len) return error.EventTooLarge;
    try list.appendSlice(allocator, bytes);
}
fn mapStopReason(value: []const u8) ?protocol.StopReason {
    if (std.mem.eql(u8, value, "stop")) return .stop;
    if (std.mem.eql(u8, value, "length")) return .length;
    if (std.mem.eql(u8, value, "tool_calls")) return .tool_use;
    return null;
}
fn parseUsage(dialect: Dialect, object: std.json.ObjectMap) !protocol.Usage {
    var usage = protocol.Usage{
        .input = try usageField(object, "prompt_tokens"),
        .output = try usageField(object, "completion_tokens"),
    };
    if (usage.input > std.math.maxInt(u64) - usage.output) return error.InvalidUsage;
    // Routed providers do not always report total_tokens as prompt + completion. Keep the
    // counters that drive accounting and derive a consistent total; a non-numeric total is
    // still malformed.
    if (object.get("total_tokens")) |total| if (total != .null) {
        _ = uintField(total) orelse return error.InvalidUsage;
    };
    usage.total_tokens = usage.input + usage.output;
    var openai_cache_read: ?u64 = null;
    if (object.get("prompt_tokens_details")) |details| if (details != .null) {
        if (details != .object) return error.InvalidUsage;
        if (details.object.get("cached_tokens")) |cached_tokens| if (cached_tokens != .null) {
            openai_cache_read = uintField(cached_tokens) orelse return error.InvalidUsage;
        };
    };
    switch (dialect) {
        .deepseek_chat => {
            const deepseek_cache_read = uintField(object.get("prompt_cache_hit_tokens") orelse return error.InvalidUsage) orelse return error.InvalidUsage;
            const deepseek_cache_miss = uintField(object.get("prompt_cache_miss_tokens") orelse return error.InvalidUsage) orelse return error.InvalidUsage;
            if (deepseek_cache_read > std.math.maxInt(u64) - deepseek_cache_miss or deepseek_cache_read + deepseek_cache_miss != usage.input) return error.InvalidUsage;
            if (openai_cache_read) |openai_value| if (openai_value != deepseek_cache_read) return error.InvalidUsage;
            usage.cache_read = deepseek_cache_read;
        },
        else => {
            if (object.get("prompt_cache_hit_tokens") != null or object.get("prompt_cache_miss_tokens") != null) return error.InvalidUsage;
            usage.cache_read = openai_cache_read orelse 0;
        },
    }
    return usage;
}
fn usageField(object: std.json.ObjectMap, key: []const u8) !u64 {
    const value = object.get(key) orelse return error.InvalidUsage;
    return uintField(value) orelse error.InvalidUsage;
}
fn usageEqual(left: protocol.Usage, right: protocol.Usage) bool {
    return left.input == right.input and left.output == right.output and left.cache_read == right.cache_read and left.cache_write == right.cache_write and left.total_tokens == right.total_tokens;
}
fn writeJson(writer: *std.Io.Writer, value: anytype) !void {
    try std.json.Stringify.value(value, .{}, writer);
}
pub fn renderRequest(allocator: std.mem.Allocator, model: *const protocol.Model, context: *const protocol.Context, options: protocol.StreamOptions, dialect: Dialect) !RenderedRequest {
    return renderRequestWithCapabilities(allocator, model, context, options, dialect, true, null);
}

fn renderRequestWithCapabilities(allocator: std.mem.Allocator, model: *const protocol.Model, context: *const protocol.Context, options: protocol.StreamOptions, dialect: Dialect, allow_tools: bool, cache_retention_wire: ?CacheRetentionWire) !RenderedRequest {
    try context.validate();
    if (options.transport == .websocket) return error.UnsupportedTransport;
    if (options.max_tokens) |limit| if (limit == 0 or limit > model.max_tokens) return error.InvalidOutputLimit;
    if (options.reasoning != null and !model.reasoning) return error.UnsupportedOption;
    if (options.cache_aggressiveness != .balanced) return error.UnsupportedOption;
    if (!allow_tools and (context.tools.len != 0 or contextUsesToolWire(context))) return error.UnsupportedOption;
    if (!cacheRetentionWireMatches(options.cache_retention, cache_retention_wire) or (options.cache_retention != .none and cache_retention_wire == null) or (cache_retention_wire != null and dialect != .openai_chat)) return error.UnsupportedOption;
    if (options.session_id) |session_id| {
        if (dialect != .openrouter_chat) return error.UnsupportedOption;
        try validateWireText(session_id);
        if (session_id.len > max_session_id_bytes) return error.RequestTooLarge;
    }
    try validateRenderBounds(context, model);
    if (dialect == .anthropic_messages) return renderAnthropicRequest(allocator, model, context, options);
    var output = std.Io.Writer.Allocating.initCapacity(allocator, 4096) catch return error.OutOfMemory;
    errdefer output.deinit();
    const writer = &output.writer;
    try writer.writeAll("{\"model\":");
    try writeJson(writer, model.id);
    if (context.tools.len != 0) try writeTools(writer, allocator, context.tools);
    try writer.writeAll(",\"messages\":[");
    var message_written = false;
    if (context.system_prompt_lanes.len != 0) {
        for (context.system_prompt_lanes) |lane| {
            if (lane.len == 0) continue;
            if (message_written) try writer.writeByte(',');
            try writer.writeAll("{\"role\":\"system\",\"content\":");
            try writeJson(writer, lane);
            try writer.writeByte('}');
            message_written = true;
        }
    } else if (context.system_prompt.len != 0) {
        try writer.writeAll("{\"role\":\"system\",\"content\":");
        try writeJson(writer, context.system_prompt);
        try writer.writeByte('}');
        message_written = true;
    }
    var prefix_len: usize = writer.buffered().len;
    for (context.messages, 0..) |message, index| {
        if (message_written) try writer.writeByte(',');
        try writeMessage(writer, message, allocator, dialect);
        message_written = true;
        if (index + 1 == context.cacheable_message_count) prefix_len = writer.buffered().len;
    }
    try writer.writeByte(']');
    switch (dialect) {
        .openai_chat => try writer.print(",\"max_completion_tokens\":{d},\"stream\":true", .{options.max_tokens orelse model.max_tokens}),
        .openrouter_chat, .deepseek_chat => try writer.print(",\"max_tokens\":{d},\"stream\":true", .{options.max_tokens orelse model.max_tokens}),
        .anthropic_messages => unreachable,
    }
    if (options.reasoning) |effort| switch (dialect) {
        .openai_chat => {
            try writer.writeAll(",\"reasoning_effort\":");
            try writeJson(writer, @tagName(effort));
        },
        .openrouter_chat => {
            try writer.writeAll(",\"reasoning\":{\"effort\":");
            try writeJson(writer, @tagName(effort));
            try writer.writeByte('}');
        },
        .deepseek_chat => {
            try writer.writeAll(",\"thinking\":{\"type\":\"enabled\"},\"reasoning_effort\":");
            try writeJson(writer, deepSeekEffort(effort));
        },
        .anthropic_messages => unreachable,
    };
    if (cache_retention_wire) |wire| switch (wire) {
        .prompt_cache_retention_in_memory => try writer.writeAll(",\"prompt_cache_retention\":\"in_memory\""),
        .prompt_cache_retention_24h => try writer.writeAll(",\"prompt_cache_retention\":\"24h\""),
        .prompt_cache_options_ttl_30m => try writer.writeAll(",\"prompt_cache_options\":{\"mode\":\"implicit\",\"ttl\":\"30m\"}"),
    };
    if (options.session_id) |session_id| if (dialect == .openrouter_chat) {
        try writer.writeAll(",\"session_id\":");
        try writeJson(writer, session_id);
    };
    if (dialect == .openai_chat or dialect == .openrouter_chat) try writer.writeAll(",\"stream_options\":{\"include_usage\":true}");
    try writer.writeByte('}');
    const bytes = output.toOwnedSlice() catch return error.OutOfMemory;
    if (bytes.len > max_rendered_request_bytes) {
        allocator.free(bytes);
        return error.RequestTooLarge;
    }
    var hash: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash(bytes[0..prefix_len], &hash, .{});
    return .{ .bytes = bytes, .cache_prefix_len = prefix_len, .cache_prefix_hash = hash, .allocator = allocator };
}

/// Anthropic Messages body: tools, system lanes as text blocks, then strictly alternating
/// turns. Tool results and user text share one user turn; signed thinking is replayed
/// unchanged and unsigned (foreign) thinking is dropped. Callers ran the shared validation.
fn renderAnthropicRequest(allocator: std.mem.Allocator, model: *const protocol.Model, context: *const protocol.Context, options: protocol.StreamOptions) !RenderedRequest {
    var output = std.Io.Writer.Allocating.initCapacity(allocator, 4096) catch return error.OutOfMemory;
    errdefer output.deinit();
    const writer = &output.writer;
    try writer.writeAll("{\"model\":");
    try writeJson(writer, model.id);
    if (context.tools.len != 0) {
        try writer.writeAll(",\"tools\":[");
        const sorted = try allocator.alloc(*const protocol.Tool, context.tools.len);
        defer allocator.free(sorted);
        for (context.tools, 0..) |*tool, index| sorted[index] = tool;
        std.mem.sort(*const protocol.Tool, sorted, {}, lessTool);
        for (sorted, 0..) |tool, index| {
            if (index != 0) try writer.writeByte(',');
            try writer.writeAll("{\"name\":");
            try writeJson(writer, tool.name);
            try writer.writeAll(",\"description\":");
            try writeJson(writer, tool.description);
            try writer.writeAll(",\"input_schema\":");
            try writeCanonicalValue(writer, tool.parameters, allocator);
            try writer.writeByte('}');
        }
        try writer.writeByte(']');
    }
    const single_lane = [_][]const u8{context.system_prompt};
    const lanes: []const []const u8 = if (context.system_prompt_lanes.len != 0) context.system_prompt_lanes else &single_lane;
    var lane_written = false;
    for (lanes) |lane| {
        if (lane.len == 0) continue;
        try writer.writeAll(if (lane_written) "," else ",\"system\":[");
        try writer.writeAll("{\"type\":\"text\",\"text\":");
        try writeJson(writer, lane);
        try writer.writeByte('}');
        lane_written = true;
    }
    if (lane_written) try writer.writeByte(']');
    try writer.writeAll(",\"messages\":[");
    var prefix_len: usize = writer.buffered().len;
    var turn_written = false;
    var user_open = false;
    var user_blocks: usize = 0;
    for (context.messages, 0..) |message, index| {
        switch (message) {
            .user, .tool_result => {
                if (!user_open) {
                    if (turn_written) try writer.writeByte(',');
                    try writer.writeAll("{\"role\":\"user\",\"content\":[");
                    turn_written = true;
                    user_open = true;
                    user_blocks = 0;
                }
                switch (message) {
                    .user => |user| for (user.content) |part| switch (part) {
                        .text => |text| if (text.text.len != 0) {
                            if (user_blocks != 0) try writer.writeByte(',');
                            try writer.writeAll("{\"type\":\"text\",\"text\":");
                            try writeJson(writer, text.text);
                            try writer.writeByte('}');
                            user_blocks += 1;
                        },
                        .image => return error.UnsupportedImage,
                    },
                    .tool_result => |result| {
                        if (user_blocks != 0) try writer.writeByte(',');
                        try writer.writeAll("{\"type\":\"tool_result\",\"tool_use_id\":");
                        try writeJson(writer, result.tool_call_id);
                        var text = std.Io.Writer.Allocating.init(allocator);
                        defer text.deinit();
                        for (result.content) |part| switch (part) {
                            .text => |value| try text.writer.writeAll(value.text),
                            .image => return error.UnsupportedImage,
                        };
                        if (text.writer.buffered().len != 0) {
                            try writer.writeAll(",\"content\":");
                            try writeJson(writer, text.writer.buffered());
                        }
                        if (result.is_error) try writer.writeAll(",\"is_error\":true");
                        try writer.writeByte('}');
                        user_blocks += 1;
                    },
                    .assistant => unreachable,
                }
            },
            .assistant => |assistant| {
                if (user_open) try writer.writeAll("]}");
                user_open = false;
                if (turn_written) try writer.writeByte(',');
                try writer.writeAll("{\"role\":\"assistant\",\"content\":[");
                turn_written = true;
                var blocks: usize = 0;
                for (assistant.content) |part| {
                    switch (part) {
                        .thinking => |thinking| {
                            const signature = thinking.signature orelse continue;
                            if (blocks != 0) try writer.writeByte(',');
                            try writer.writeAll("{\"type\":\"thinking\",\"thinking\":");
                            try writeJson(writer, thinking.thinking);
                            try writer.writeAll(",\"signature\":");
                            try writeJson(writer, signature);
                            try writer.writeByte('}');
                        },
                        .text => |text| {
                            if (text.text.len == 0) continue;
                            if (blocks != 0) try writer.writeByte(',');
                            try writer.writeAll("{\"type\":\"text\",\"text\":");
                            try writeJson(writer, text.text);
                            try writer.writeByte('}');
                        },
                        .tool_call => |call| {
                            if (blocks != 0) try writer.writeByte(',');
                            try writer.writeAll("{\"type\":\"tool_use\",\"id\":");
                            try writeJson(writer, call.id);
                            try writer.writeAll(",\"name\":");
                            try writeJson(writer, call.name);
                            try writer.writeAll(",\"input\":");
                            try writeCanonicalValue(writer, call.arguments, allocator);
                            try writer.writeByte('}');
                        },
                    }
                    blocks += 1;
                }
                // An assistant turn with no replayable content still needs a non-empty body.
                if (blocks == 0) try writer.writeAll("{\"type\":\"text\",\"text\":\"(no content)\"}");
                try writer.writeAll("]}");
            },
        }
        if (index + 1 == context.cacheable_message_count) prefix_len = writer.buffered().len;
    }
    if (user_open) try writer.writeAll("]}");
    try writer.print("],\"max_tokens\":{d},\"stream\":true", .{options.max_tokens orelse model.max_tokens});
    if (options.reasoning) |effort| {
        try writer.writeAll(",\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":");
        try writeJson(writer, if (effort == .minimal) "low" else @tagName(effort));
        try writer.writeByte('}');
    }
    // Automatic prompt caching: the API places the breakpoint on the last cacheable block.
    try writer.writeAll(",\"cache_control\":{\"type\":\"ephemeral\"}}");
    const bytes = output.toOwnedSlice() catch return error.OutOfMemory;
    if (bytes.len > max_rendered_request_bytes) {
        allocator.free(bytes);
        return error.RequestTooLarge;
    }
    var hash: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash(bytes[0..prefix_len], &hash, .{});
    return .{ .bytes = bytes, .cache_prefix_len = prefix_len, .cache_prefix_hash = hash, .allocator = allocator };
}

fn contextUsesToolWire(context: *const protocol.Context) bool {
    for (context.messages) |message| switch (message) {
        .assistant => |assistant| for (assistant.content) |part| switch (part) {
            .tool_call => return true,
            else => {},
        },
        .tool_result => return true,
        else => {},
    };
    return false;
}

fn deepSeekEffort(effort: protocol.ThinkingLevel) []const u8 {
    return switch (effort) {
        .minimal, .low => "low",
        .medium, .high, .xhigh => "high",
        .max => "max",
    };
}

fn validateRenderBounds(context: *const protocol.Context, model: *const protocol.Model) !void {
    var estimate: usize = 512;
    if (model.id.len == 0 or model.id.len > max_model_id_bytes or context.messages.len > max_request_messages) return error.RequestTooLarge;
    try validateWireText(model.id);
    try validateWireText(context.system_prompt);
    if (!addEstimate(&estimate, context.messages.len *| 128)) return error.RequestTooLarge;
    if (!addEstimate(&estimate, model.id.len *| 6) or !addEstimate(&estimate, context.system_prompt.len *| 6) or !addEstimate(&estimate, 64)) return error.RequestTooLarge;
    if (context.system_prompt_lanes.len > 64) return error.RequestTooLarge;
    for (context.system_prompt_lanes) |lane| {
        try validateWireText(lane);
        if (!addEstimate(&estimate, lane.len *| 6) or !addEstimate(&estimate, 64)) return error.RequestTooLarge;
    }
    if (context.tools.len > max_tools) return error.RequestTooLarge;
    for (context.messages) |message| {
        switch (message) {
            .user => |user| {
                if (user.content.len > max_message_parts or !addEstimate(&estimate, user.content.len *| 32)) return error.RequestTooLarge;
                for (user.content) |part| switch (part) {
                    .text => |text| {
                        try validateWireText(text.text);
                        if (!addEstimate(&estimate, text.text.len *| 6)) return error.RequestTooLarge;
                    },
                    .image => |image| {
                        try validateWireText(image.data);
                        try validateWireText(image.mime_type);
                        if (!addEstimate(&estimate, (image.data.len +| image.mime_type.len) *| 6)) return error.RequestTooLarge;
                    },
                };
            },
            .tool_result => |result| {
                if (result.content.len > max_message_parts or !addEstimate(&estimate, result.content.len *| 32)) return error.RequestTooLarge;
                try validateWireText(result.tool_call_id);
                try validateWireText(result.tool_name);
                if (!addEstimate(&estimate, result.tool_call_id.len *| 6)) return error.RequestTooLarge;
                for (result.content) |part| switch (part) {
                    .text => |text| {
                        try validateWireText(text.text);
                        if (!addEstimate(&estimate, text.text.len *| 6)) return error.RequestTooLarge;
                    },
                    .image => |image| {
                        try validateWireText(image.data);
                        try validateWireText(image.mime_type);
                        if (!addEstimate(&estimate, (image.data.len +| image.mime_type.len) *| 6)) return error.RequestTooLarge;
                    },
                };
            },
            .assistant => |assistant| {
                if (assistant.content.len > max_message_parts or !addEstimate(&estimate, assistant.content.len *| 32)) return error.RequestTooLarge;
                for (assistant.content) |part| switch (part) {
                    .text => |text| {
                        try validateWireText(text.text);
                        if (!addEstimate(&estimate, text.text.len *| 6)) return error.RequestTooLarge;
                    },
                    .thinking => |thinking| {
                        try validateWireText(thinking.thinking);
                        if (thinking.signature) |signature| try validateWireText(signature);
                        if (!addEstimate(&estimate, thinking.thinking.len *| 6)) return error.RequestTooLarge;
                    },
                    .tool_call => |call| {
                        try validateWireText(call.id);
                        try validateWireText(call.name);
                        try validateCanonicalValue(call.arguments, 0);
                        if (!addEstimate(&estimate, (call.id.len +| call.name.len +| estimateJsonValue(call.arguments, 0)) *| 6)) return error.RequestTooLarge;
                    },
                };
            },
        }
    }
    for (context.tools, 0..) |tool, index| {
        if (tool.name.len == 0 or tool.name.len > max_tool_name_bytes or tool.description.len > max_tool_description_bytes or estimateJsonValue(tool.parameters, 0) > max_tool_schema_bytes * 6) return error.RequestTooLarge;
        try validateWireText(tool.name);
        try validateWireText(tool.description);
        try validateCanonicalValue(tool.parameters, 0);
        for (context.tools[0..index]) |previous| if (std.mem.eql(u8, previous.name, tool.name)) return error.DuplicateToolName;
        if (!addEstimate(&estimate, (tool.name.len +| tool.description.len) *| 6) or !addEstimate(&estimate, 128)) return error.RequestTooLarge;
        if (!addEstimate(&estimate, estimateJsonValue(tool.parameters, 0) *| 6)) return error.RequestTooLarge;
    }
}

fn validateWireText(value: []const u8) !void {
    if (!std.unicode.utf8ValidateSlice(value)) return error.InvalidRequestText;
}

fn addEstimate(total: *usize, amount: usize) bool {
    if (amount > max_rendered_request_bytes -| total.*) return false;
    total.* += amount;
    return true;
}

fn estimateJsonValue(value: std.json.Value, depth: usize) usize {
    if (depth > 64) return max_rendered_request_bytes;
    return switch (value) {
        .string => |string| string.len *| 6 +| 2,
        .array => |array| blk: {
            var total: usize = 2;
            for (array.items) |item| {
                const child = estimateJsonValue(item, depth + 1);
                if (child > max_rendered_request_bytes -| total -| 1) break :blk max_rendered_request_bytes;
                total += child + 1;
            }
            break :blk total;
        },
        .object => |object| blk: {
            var total: usize = 2;
            var iterator = object.iterator();
            while (iterator.next()) |entry| {
                const key_cost = entry.key_ptr.*.len *| 6 +| 4;
                const child = estimateJsonValue(entry.value_ptr.*, depth + 1);
                if (key_cost > max_rendered_request_bytes -| total -| 1 or child > max_rendered_request_bytes -| total -| key_cost -| 1) break :blk max_rendered_request_bytes;
                total += key_cost + child + 1;
            }
            break :blk total;
        },
        else => 32,
    };
}

fn writeTools(writer: *std.Io.Writer, allocator: std.mem.Allocator, tools: []const protocol.Tool) !void {
    try writer.writeAll(",\"tools\":[");
    const sorted = try allocator.alloc(*const protocol.Tool, tools.len);
    defer allocator.free(sorted);
    for (tools, 0..) |*tool, index| sorted[index] = tool;
    std.mem.sort(*const protocol.Tool, sorted, {}, lessTool);
    for (sorted, 0..) |tool, index| {
        if (index != 0) try writer.writeByte(',');
        try writer.writeAll("{\"type\":\"function\",\"function\":{\"name\":");
        try writeJson(writer, tool.name);
        try writer.writeAll(",\"description\":");
        try writeJson(writer, tool.description);
        try writer.writeAll(",\"parameters\":");
        try writeCanonicalValue(writer, tool.parameters, allocator);
        try writer.writeAll("}}");
    }
    try writer.writeByte(']');
}

fn writeMessage(writer: *std.Io.Writer, message: protocol.Message, allocator: std.mem.Allocator, dialect: Dialect) !void {
    switch (message) {
        .user => |user| {
            try writer.writeAll("{\"role\":\"user\",\"content\":");
            try writeContent(writer, user.content, allocator);
            try writer.writeByte('}');
        },
        .tool_result => |result| {
            try writer.writeAll("{\"role\":\"tool\",\"tool_call_id\":");
            try writeJson(writer, result.tool_call_id);
            try writer.writeAll(",\"content\":");
            try writeContent(writer, result.content, allocator);
            try writer.writeByte('}');
        },
        .assistant => |assistant| {
            try writer.writeAll("{\"role\":\"assistant\"");
            var calls: usize = 0;
            for (assistant.content) |part| switch (part) {
                .tool_call => calls += 1,
                else => {},
            };
            var text = std.Io.Writer.Allocating.init(allocator);
            defer text.deinit();
            var text_count: usize = 0;
            for (assistant.content) |part| switch (part) {
                .text => |value| {
                    try text.writer.writeAll(value.text);
                    text_count += 1;
                },
                else => {},
            };
            if (text_count != 0) {
                try writer.writeAll(",\"content\":");
                try writeJson(writer, text.writer.buffered());
            }
            if (text_count == 0) {
                if (calls == 0) try writer.writeAll(",\"content\":null") else if (dialect == .deepseek_chat) try writer.writeAll(",\"content\":\"\"");
            }
            var thinking = std.Io.Writer.Allocating.init(allocator);
            defer thinking.deinit();
            var thinking_count: usize = 0;
            for (assistant.content) |part| switch (part) {
                .thinking => |value| {
                    try thinking.writer.writeAll(value.thinking);
                    thinking_count += 1;
                },
                else => {},
            };
            if (thinking_count != 0) switch (dialect) {
                .deepseek_chat => {
                    try writer.writeAll(",\"reasoning_content\":");
                    try writeJson(writer, thinking.writer.buffered());
                },
                .openrouter_chat => {
                    try writer.writeAll(",\"reasoning\":");
                    try writeJson(writer, thinking.writer.buffered());
                },
                .openai_chat, .anthropic_messages => {},
            };
            if (calls != 0) {
                try writer.writeAll(",\"tool_calls\":[");
                var index: usize = 0;
                for (assistant.content) |part| switch (part) {
                    .tool_call => |call| {
                        if (index != 0) try writer.writeByte(',');
                        try writer.writeAll("{\"id\":");
                        try writeJson(writer, call.id);
                        try writer.writeAll(",\"type\":\"function\",\"function\":{\"name\":");
                        try writeJson(writer, call.name);
                        try writer.writeAll(",\"arguments\":");
                        var args = std.Io.Writer.Allocating.init(allocator);
                        defer args.deinit();
                        try writeCanonicalValue(&args.writer, call.arguments, allocator);
                        try writeJson(writer, args.writer.buffered());
                        try writer.writeAll("}}");
                        index += 1;
                    },
                    else => {},
                };
                try writer.writeByte(']');
            }
            try writer.writeByte('}');
        },
    }
}
fn writeContent(writer: *std.Io.Writer, content: []const protocol.UserContent, allocator: std.mem.Allocator) !void {
    var text = std.Io.Writer.Allocating.init(allocator);
    defer text.deinit();
    for (content) |part| switch (part) {
        .text => |value| try text.writer.writeAll(value.text),
        .image => return error.UnsupportedImage,
    };
    try writeJson(writer, text.writer.buffered());
}
fn writeCanonicalValue(writer: *std.Io.Writer, value: std.json.Value, allocator: std.mem.Allocator) !void {
    switch (value) {
        .object => |object| {
            const keys = try allocator.alloc([]const u8, object.count());
            defer allocator.free(keys);
            var index: usize = 0;
            var iterator = object.iterator();
            while (iterator.next()) |entry| {
                keys[index] = entry.key_ptr.*;
                index += 1;
            }
            std.mem.sort([]const u8, keys, {}, lessUtf16CodeUnits);
            try writer.writeByte('{');
            for (keys, 0..) |key, key_index| {
                if (key_index != 0) try writer.writeByte(',');
                try writeJson(writer, key);
                try writer.writeByte(':');
                try writeCanonicalValue(writer, object.get(key).?, allocator);
            }
            try writer.writeByte('}');
        },
        .array => |array| {
            try writer.writeByte('[');
            for (array.items, 0..) |item, index| {
                if (index != 0) try writer.writeByte(',');
                try writeCanonicalValue(writer, item, allocator);
            }
            try writer.writeByte(']');
        },
        .string => |string| try writeJson(writer, string),
        .integer => |integer| {
            if (!safeJcsInteger(integer)) return error.UnsupportedCanonicalNumber;
            try writeJson(writer, integer);
        },
        .null, .bool => try writeJson(writer, value),
        .float, .number_string => return error.UnsupportedCanonicalNumber,
    }
}

fn validateCanonicalValue(value: std.json.Value, depth: usize) !void {
    if (depth > 64) return error.RequestTooLarge;
    switch (value) {
        .null, .bool => {},
        .integer => |integer| if (!safeJcsInteger(integer)) return error.UnsupportedCanonicalNumber,
        .float, .number_string => return error.UnsupportedCanonicalNumber,
        .string => |string| if (!std.unicode.utf8ValidateSlice(string)) return error.InvalidCanonicalJsonString,
        .array => |array| for (array.items) |item| try validateCanonicalValue(item, depth + 1),
        .object => |object| {
            var iterator = object.iterator();
            while (iterator.next()) |entry| {
                if (!std.unicode.utf8ValidateSlice(entry.key_ptr.*)) return error.InvalidCanonicalJsonString;
                try validateCanonicalValue(entry.value_ptr.*, depth + 1);
            }
        },
    }
}

fn safeJcsInteger(value: i64) bool {
    return value >= -max_safe_jcs_integer and value <= max_safe_jcs_integer;
}

fn nextUtf16CodeUnit(bytes: []const u8, index: *usize, pending: *?u16) ?u16 {
    if (pending.*) |unit| {
        pending.* = null;
        return unit;
    }
    if (index.* == bytes.len) return null;
    const sequence_len = std.unicode.utf8ByteSequenceLength(bytes[index.*]) catch unreachable;
    const end = index.* + sequence_len;
    const scalar = std.unicode.utf8Decode(bytes[index.*..end]) catch unreachable;
    index.* = end;
    if (scalar <= 0xffff) return @intCast(scalar);
    const supplementary = scalar - 0x10000;
    pending.* = @intCast(0xdc00 + (supplementary & 0x3ff));
    return @intCast(0xd800 + (supplementary >> 10));
}

fn lessUtf16CodeUnits(_: void, left: []const u8, right: []const u8) bool {
    var left_index: usize = 0;
    var right_index: usize = 0;
    var left_pending: ?u16 = null;
    var right_pending: ?u16 = null;
    while (true) {
        const left_unit = nextUtf16CodeUnit(left, &left_index, &left_pending);
        const right_unit = nextUtf16CodeUnit(right, &right_index, &right_pending);
        if (left_unit == null) return right_unit != null;
        if (right_unit == null) return false;
        if (left_unit.? != right_unit.?) return left_unit.? < right_unit.?;
    }
}
fn lessTool(_: void, left: *const protocol.Tool, right: *const protocol.Tool) bool {
    return std.mem.lessThan(u8, left.name, right.name);
}
fn emitFailure(context: *anyopaque, sink: protocol.StreamSink, model: *const protocol.Model, category: protocol.ProviderFailureCategory, status: ?u16, message: []const u8, request_id: ?[]const u8, code: ?[]const u8, typ: ?[]const u8, param: ?[]const u8) !void {
    var diagnostic = diagnosticForStatus(status orelse 0, request_id, message, null);
    diagnostic.status = status;
    diagnostic.category = category;
    if (code) |value| diagnostic.code_len = copyBounded(diagnostic.code[0..], value);
    if (typ) |value| diagnostic.type_len = copyBounded(diagnostic.typ[0..], value);
    if (param) |value| diagnostic.param_len = copyBounded(diagnostic.param[0..], value);
    try emitDiagnostic(context, sink, model, diagnostic);
}
fn emitDiagnostic(context: *anyopaque, sink: protocol.StreamSink, model: *const protocol.Model, diagnostic: Diagnostic) !void {
    const failure = protocol.ProviderFailure{ .category = diagnostic.category, .http_status = diagnostic.status, .request_id = diagnostic.requestId(), .provider_code = diagnostic.providerCode(), .provider_type = diagnostic.providerType(), .provider_param = diagnostic.providerParam(), .redacted_message = diagnostic.redactedMessage(), .retry_after_ms = diagnostic.retry_after_ms };
    try failure.validate();
    sink(context, .{ .failed = .{ .content = &.{}, .api = "provider", .provider = model.provider, .model = model.id, .stop_reason = .@"error", .provider_failure = failure, .timestamp_ms = 0 } }) catch |err| if (err == error.Canceled) return error.Canceled else return error.EventCallbackFailed;
}
fn diagnosticForStatus(status: u16, request_id: ?[]const u8, message: []const u8, secret: ?[]const u8) Diagnostic {
    return diagnosticForStatusSecrets(status, request_id, message, secret, &.{});
}
fn diagnosticForStatusSecrets(status: u16, request_id: ?[]const u8, message: []const u8, primary_secret: ?[]const u8, headers: []const Header) Diagnostic {
    var result = Diagnostic{ .category = provider_auth.classifyFailure(status, message), .status = if (status >= 100 and status <= 599) status else null };
    if (request_id) |value| result.request_id_len = copyRedactedMany(result.request_id[0..], value, primary_secret, headers);
    result.message_len = copyRedactedMany(result.message[0..], message, primary_secret, headers);
    if (result.message_len == 0) result.message_len = copyBounded(result.message[0..], "provider request failed");
    return result;
}
fn parseDiagnostic(allocator: std.mem.Allocator, status: u16, body: []const u8, request_id: ?[]const u8, secret: ?[]const u8, headers: []const Header) !Diagnostic {
    var diagnostic = diagnosticForStatusSecrets(status, request_id, "provider request failed", secret, headers);
    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();
    const parsed = std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), body, .{}) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        else => {
            diagnostic.message_len = copyRedactedMany(diagnostic.message[0..], body, secret, headers);
            if (diagnostic.message_len == 0) diagnostic.message_len = copyBounded(diagnostic.message[0..], "provider request failed");
            diagnostic.category = provider_auth.classifyFailure(status, body);
            return diagnostic;
        },
    };
    if (parsed == .object) if (parsed.object.get("error")) |wire| if (wire == .object) {
        var numeric_code: [32]u8 = undefined;
        const raw_code = if (wire.object.get("code")) |value| diagnosticIdentifier(value, &numeric_code) else null;
        const raw_type = stringField(wire.object, "type");
        const raw_param = stringField(wire.object, "param");
        const raw_message = stringField(wire.object, "message");
        if (raw_code) |value| diagnostic.code_len = copyRedactedMany(diagnostic.code[0..], value, secret, headers);
        if (raw_type) |value| diagnostic.type_len = copyRedactedMany(diagnostic.typ[0..], value, secret, headers);
        if (raw_param) |value| diagnostic.param_len = copyRedactedMany(diagnostic.param[0..], value, secret, headers);
        if (raw_message) |value| diagnostic.message_len = copyRedactedMany(diagnostic.message[0..], value, secret, headers);
        diagnostic.category = provider_auth.classifyStructuredFailure(status, raw_code, raw_type, raw_message orelse "provider request failed");
    };
    return diagnostic;
}
fn diagnosticIdentifier(value: std.json.Value, numeric_storage: []u8) ?[]const u8 {
    return switch (value) {
        .string => |text| text,
        .integer => |number| blk: {
            const rendered = std.fmt.bufPrint(numeric_storage, "{d}", .{number}) catch return null;
            break :blk rendered;
        },
        else => null,
    };
}
fn stringField(object: std.json.ObjectMap, key: []const u8) ?[]const u8 {
    const value = object.get(key) orelse return null;
    return if (value == .string) value.string else null;
}
fn uintField(value: std.json.Value) ?u64 {
    return switch (value) {
        .integer => |number| if (number >= 0) @intCast(number) else null,
        else => null,
    };
}
fn copyBounded(destination: []u8, source: []const u8) usize {
    const count = @min(destination.len, source.len);
    @memcpy(destination[0..count], source[0..count]);
    return count;
}
fn copyRedacted(destination: []u8, source: []const u8, secret: ?[]const u8) usize {
    return copyRedactedMany(destination, source, secret, &.{});
}
fn copyRedactedMany(destination: []u8, source: []const u8, primary_secret: ?[]const u8, headers: []const Header) usize {
    var output: usize = 0;
    var index: usize = 0;
    while (index < source.len and output < destination.len) {
        var secret: ?[]const u8 = null;
        if (primary_secret) |value| {
            if (value.len != 0 and source.len - index >= value.len and std.mem.eql(u8, source[index .. index + value.len], value)) secret = value;
        }
        for (headers) |header| if (header.value.len != 0 and source.len - index >= header.value.len and std.mem.eql(u8, source[index .. index + header.value.len], header.value)) {
            if (secret == null or header.value.len > secret.?.len) secret = header.value;
        };
        if (secret) |value| {
            const marker = "[REDACTED]";
            const count = @min(marker.len, destination.len - output);
            @memcpy(destination[output .. output + count], marker[0..count]);
            output += count;
            index += value.len;
        } else {
            const char = source[index];
            if (char < 0x20 or char == 0x7f) {
                destination[output] = ' ';
                output += 1;
                index += 1;
                continue;
            }
            if (char < 0x80) {
                destination[output] = char;
                output += 1;
                index += 1;
                continue;
            }
            const sequence_len = std.unicode.utf8ByteSequenceLength(char) catch {
                destination[output] = '?';
                output += 1;
                index += 1;
                continue;
            };
            if (sequence_len > source.len - index or !std.unicode.utf8ValidateSlice(source[index .. index + sequence_len])) {
                destination[output] = '?';
                output += 1;
                index += 1;
                continue;
            }
            if (sequence_len > destination.len - output) break;
            @memcpy(destination[output .. output + sequence_len], source[index .. index + sequence_len]);
            output += sequence_len;
            index += sequence_len;
        }
    }
    return output;
}
fn isRetryableDiagnostic(diagnostic: Diagnostic) bool {
    return diagnostic.category == .rate_limited or diagnostic.category == .transient_provider or (diagnostic.category == .timeout and (diagnostic.status == 408 or diagnostic.status == 504));
}
fn isTransientTransport(err: anyerror) bool {
    return switch (err) {
        error.Timeout, error.ConnectionFailure, error.ConnectionAborted, error.ConnectionRefused, error.ConnectionResetByPeer, error.HostUnreachable, error.NetworkDown, error.NetworkUnreachable, error.SocketUnconnected, error.NameServerFailure, error.DetectingNetworkConfigurationFailed, error.TransportWriteFailed, error.ReadFailed, error.EndOfStream => true,
        else => false,
    };
}
fn retryDelayMs(policy: RetryPolicy, retry_index: u32, retry_after_ms: ?u64) u64 {
    if (retry_after_ms) |delay| return @min(delay, policy.max_delay_ms);
    var delay: u64 = @min(initial_retry_delay_ms, policy.max_delay_ms);
    var index: u32 = 0;
    while (index < retry_index) : (index += 1) delay = @min(delay *| 2, policy.max_delay_ms);
    return delay;
}
const TimeoutContext = struct { io: std.Io, timeout_ms: u64 };
const TimeoutFn = *const fn (*anyopaque) anyerror!void;
fn timeoutTask(raw: *anyopaque) !void {
    const context: *TimeoutContext = @ptrCast(@alignCast(raw));
    try std.Io.sleep(context.io, .fromMilliseconds(@intCast(@min(context.timeout_ms, @as(u64, std.math.maxInt(i64))))), .awake);
}
const AttemptFn = *const fn (*anyopaque) anyerror!AttemptResult;
fn runWithTimeout(io: std.Io, attempt_context: *anyopaque, attempt: AttemptFn, timeout_context: *anyopaque, timeout: TimeoutFn) !AttemptResult {
    const TimedResult = union(enum) { request: anyerror!AttemptResult, timeout: anyerror!void };
    const AttemptCall = struct { context: *anyopaque, function: AttemptFn };
    const TimeoutCall = struct { context: *anyopaque, function: TimeoutFn };
    const callAttempt = struct {
        fn call(value: *AttemptCall) anyerror!AttemptResult {
            return value.function(value.context);
        }
    }.call;
    const callTimeout = struct {
        fn call(value: *TimeoutCall) anyerror!void {
            return value.function(value.context);
        }
    }.call;
    var attempt_call = AttemptCall{ .context = attempt_context, .function = attempt };
    var timeout_call = TimeoutCall{ .context = timeout_context, .function = timeout };
    var events: [2]TimedResult = undefined;
    var select = std.Io.Select(TimedResult).init(io, &events);
    try select.concurrent(.request, callAttempt, .{&attempt_call});
    select.concurrent(.timeout, callTimeout, .{&timeout_call}) catch |err| {
        select.cancelDiscard();
        return err;
    };
    defer select.cancelDiscard();
    return switch (try select.await()) {
        .request => |result| result,
        .timeout => |result| {
            try result;
            return error.Timeout;
        },
    };
}

test "SSE parser handles BOM, comments, CRLF split, repeated data, unknown fields, and arbitrary fragments" {
    const Capture = struct { allocator: std.mem.Allocator, copies: std.ArrayList([]u8) = .empty };
    var capture_state = Capture{ .allocator = std.testing.allocator };
    defer {
        for (capture_state.copies.items) |copy| capture_state.allocator.free(copy);
        capture_state.copies.deinit(capture_state.allocator);
    }
    const callback = struct {
        fn call(raw: *anyopaque, event: SseEvent) !void {
            const state: *Capture = @ptrCast(@alignCast(raw));
            try state.copies.append(state.allocator, try state.allocator.dupe(u8, event.data));
        }
    }.call;
    var parser = SseParser.init(std.testing.allocator, 4096, &capture_state, callback);
    defer parser.deinit();
    const fixture = "\xef\xbb\xbf:hello\r\nevent:message\r\ndata:one\r\ndata:two\r\nunknown:x\r\n\r\ndata:[DONE]\n\n";
    for (fixture, 0..) |_, index| try parser.feed(fixture[index .. index + 1]);
    try parser.finish();
    try std.testing.expectEqual(@as(usize, 2), capture_state.copies.items.len);
    try std.testing.expectEqualStrings("one\ntwo", capture_state.copies.items[0]);
    try std.testing.expectEqualStrings("[DONE]", capture_state.copies.items[1]);
}
test "SSE parser rejects incomplete event at EOF and bounds accumulation" {
    const callback = struct {
        fn call(_: *anyopaque, _: SseEvent) !void {}
    }.call;
    var parser = SseParser.init(std.testing.allocator, 4, undefined, callback);
    defer parser.deinit();
    try parser.feed("data:1234\n");
    try std.testing.expectError(error.EventTooLarge, parser.feed("data:5\n"));
    var incomplete = SseParser.init(std.testing.allocator, 64, undefined, callback);
    defer incomplete.deinit();
    try incomplete.feed("data:partial");
    try std.testing.expectError(error.TruncatedProviderResponse, incomplete.finish());
}
test "SSE parser preserves repeated empty data fields" {
    const Capture = struct { value: [8]u8 = undefined, len: usize = 0 };
    var capture = Capture{};
    const callback = struct {
        fn call(raw: *anyopaque, event: SseEvent) !void {
            const state: *Capture = @ptrCast(@alignCast(raw));
            state.len = event.data.len;
            @memcpy(state.value[0..state.len], event.data);
        }
    }.call;
    var parser = SseParser.init(std.testing.allocator, 64, &capture, callback);
    defer parser.deinit();
    try parser.feed("data:\ndata:\n\n");
    try std.testing.expectEqualStrings("\n", capture.value[0..capture.len]);
}

test "SSE response bound includes the BOM and non-data bytes" {
    const callback = struct {
        fn call(_: *anyopaque, _: SseEvent) !void {}
    }.call;
    var parser = SseParser.initWithBounds(std.testing.allocator, 64, 2, undefined, callback);
    defer parser.deinit();
    try std.testing.expectError(error.ResponseBodyTooLarge, parser.feed("\xef\xbb\xbf"));
    var exact = SseParser.initWithBounds(std.testing.allocator, 64, 3, undefined, callback);
    defer exact.deinit();
    try exact.feed("\xef\xbb\xbf");
    try exact.finish();
}

test "decoder interleaves reasoning text and multiple tools with stable indexes" {
    const Capture = struct {
        text_index: ?usize = null,
        thinking_index: ?usize = null,
        tool_indexes: [2]?usize = .{ null, null },
        done_count: usize = 0,
        usage_count: usize = 0,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            switch (event) {
                .text_start => |index| capture.text_index = index,
                .thinking_start => |index| capture.thinking_index = index,
                .tool_call_start => |index| {
                    if (capture.tool_indexes[0] == null) capture.tool_indexes[0] = index else capture.tool_indexes[1] = index;
                },
                .usage => capture.usage_count += 1,
                .done => |done| {
                    capture.done_count += 1;
                    try std.testing.expectEqual(@as(usize, 4), done.content.len);
                    try std.testing.expect(capture.thinking_index.? == 0 and capture.text_index.? == 1 and capture.tool_indexes[0].? == 2 and capture.tool_indexes[1].? == 3);
                },
                else => {},
            }
        }
    };
    const model = protocol.Model{ .id = "m", .api = "openrouter-chat", .provider = "p", .context_window = 4096, .max_tokens = 256, .reasoning = true };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "openrouter-chat", .openrouter_chat, 4096, 64 * 1024, 200, "req", "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    var parser = SseParser.init(std.testing.allocator, 4096, &decoder, decoderEvent);
    defer parser.deinit();
    const fixture = "data:{\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"think\"}}]}\n\ndata:{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"text\"}}]}\n\ndata:{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"one\",\"function\":{\"name\":\"first\",\"arguments\":\"{}\"}}]}}]}\n\ndata:{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"two\",\"function\":{\"name\":\"second\",\"arguments\":\"{}\"}}]}}]}\n\ndata:{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata:{\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3},\"choices\":[]}\n\ndata:[DONE]\n\n";
    for (fixture, 0..) |_, index| try parser.feed(fixture[index .. index + 1]);
    try parser.finish();
    try decoder.finish();
    try std.testing.expectEqual(@as(usize, 1), capture.done_count);
    try std.testing.expectEqual(@as(usize, 1), capture.usage_count);
}

test "decoder rejects conflicting usage and malformed tool fields" {
    const sink = struct {
        fn receive(_: *anyopaque, _: protocol.StreamEvent) !void {}
    }.receive;
    const model = protocol.Model{ .id = "m", .api = "openrouter-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "openrouter-chat", .openrouter_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, undefined, sink, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2,\"total_tokens\":11}}" });
    try std.testing.expect(!decoder.failed);
    try std.testing.expectEqual(@as(u64, 9), decoder.usage.input);
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":\"2\",\"total_tokens\":11}}" });
    try std.testing.expect(decoder.failed);

    var malformed = StreamDecoder.init(std.testing.allocator, &model, "openrouter-chat", .openrouter_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, undefined, sink, &callback_attempted);
    defer malformed.deinit();
    try decoderEvent(&malformed, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":1}}]}}]}" });
    try std.testing.expect(malformed.failed);

    var invalid_usage = StreamDecoder.init(std.testing.allocator, &model, "openrouter-chat", .openrouter_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, undefined, sink, &callback_attempted);
    defer invalid_usage.deinit();
    try decoderEvent(&invalid_usage, .{ .event = "message", .data = "{\"usage\":{\"prompt_tokens\":1.5,\"completion_tokens\":2,\"total_tokens\":3}}" });
    try std.testing.expect(invalid_usage.failed);
}

test "OpenAI refusal deltas settle as failed instead of successful completion" {
    const Capture = struct {
        failed: usize = 0,
        done: usize = 0,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            switch (event) {
                .failed => capture.failed += 1,
                .done => capture.done += 1,
                else => {},
            }
        }
    };
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "openai-chat", .openai_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"refusal\":\"cannot comply\"}}]}" });
    try std.testing.expect(decoder.failed);
    try std.testing.expectEqual(@as(usize, 1), capture.failed);
    try std.testing.expectEqual(@as(usize, 0), capture.done);
}

test "OpenAI null refusal deltas remain valid streaming content" {
    const Capture = struct {
        done: usize = 0,
        failed: usize = 0,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            switch (event) {
                .done => capture.done += 1,
                .failed => capture.failed += 1,
                else => {},
            }
        }
    };
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "openai-chat", .openai_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"refusal\":null,\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "[DONE]" });
    try decoder.finish();
    try std.testing.expectEqual(@as(usize, 1), capture.done);
    try std.testing.expectEqual(@as(usize, 0), capture.failed);
}

test "stream-event JSON parsing preserves out of memory" {
    const sink = struct {
        fn receive(_: *anyopaque, _: protocol.StreamEvent) !void {}
    }.receive;
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var failing = std.testing.FailingAllocator.init(std.testing.allocator, .{ .fail_index = 0 });
    var callback_attempted = false;
    var decoder = StreamDecoder.init(failing.allocator(), &model, "openai-chat", .openai_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, undefined, sink, &callback_attempted);
    defer decoder.deinit();
    try std.testing.expectError(error.OutOfMemory, decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}" }));
}

test "tool-argument JSON parsing preserves out of memory" {
    const sink = struct {
        fn receive(_: *anyopaque, _: protocol.StreamEvent) !void {}
    }.receive;
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "openai-chat", .openai_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, undefined, sink, &callback_attempted);
    defer decoder.deinit();
    var tool = ToolState{ .wire_index = 0, .started = true, .id = try std.testing.allocator.dupe(u8, "call_1"), .name = try std.testing.allocator.dupe(u8, "echo") };
    try tool.arguments.appendSlice(std.testing.allocator, "{\"x\":1}");
    try decoder.tools.append(std.testing.allocator, tool);
    decoder.finish_reason = .tool_use;
    decoder.terminated = true;
    var failing = std.testing.FailingAllocator.init(std.testing.allocator, .{ .fail_index = 0 });
    decoder.allocator = failing.allocator();
    try std.testing.expectError(error.OutOfMemory, decoder.finish());
}

test "public stream boundary preserves transport allocation failure" {
    const Capture = struct {
        failed: usize = 0,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            if (event == .failed) capture.failed += 1;
        }
    };
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages };
    var baseline = std.testing.FailingAllocator.init(std.testing.allocator, .{ .fail_index = std.math.maxInt(usize) });
    var rendered = try renderRequestWithCapabilities(baseline.allocator(), &model, &context, .{ .api_key = "secret" }, .openai_chat, true, null);
    rendered.deinit();
    var failing = std.testing.FailingAllocator.init(std.testing.allocator, .{ .fail_index = baseline.alloc_index + 3 });
    var capture = Capture{};
    try std.testing.expectError(error.OutOfMemory, streamWithConfig(failing.allocator(), std.testing.io, null, "http://127.0.0.1:1/v1", &.{}, .openai_chat, .{ .max_retries = 0 }, max_response_body_bytes, max_sse_event_bytes, true, null, &model, &context, .{ .api_key = "secret" }, &capture, Capture.receive, null, null));
    try std.testing.expectEqual(@as(usize, 0), capture.failed);
}

test "sink cancellation and callback errors remain distinct" {
    const cancel_sink = struct {
        fn receive(_: *anyopaque, _: protocol.StreamEvent) !void {
            return error.Canceled;
        }
    }.receive;
    const callback_sink = struct {
        fn receive(_: *anyopaque, _: protocol.StreamEvent) !void {
            return error.SinkBroken;
        }
    }.receive;
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var attempted = false;
    var canceled = StreamDecoder.init(std.testing.allocator, &model, "openai-chat", .openai_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, undefined, cancel_sink, &attempted);
    defer canceled.deinit();
    try std.testing.expectError(error.Canceled, decoderEvent(&canceled, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"content\":\"visible\"}}]}" }));
    try std.testing.expect(attempted);

    attempted = false;
    var failed = StreamDecoder.init(std.testing.allocator, &model, "openai-chat", .openai_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, undefined, callback_sink, &attempted);
    defer failed.deinit();
    try std.testing.expectError(error.EventCallbackFailed, decoderEvent(&failed, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"content\":\"visible\"}}]}" }));
}
test "canonical requests sort tools and schema keys and hash exact prefix bytes" {
    const allocator = std.testing.allocator;
    var schema_arena = std.heap.ArenaAllocator.init(allocator);
    defer schema_arena.deinit();
    const schema = std.json.parseFromSliceLeaky(std.json.Value, schema_arena.allocator(), "{\"z\":1,\"a\":{\"y\":true,\"b\":null}}", .{}) catch unreachable;
    const tools = [_]protocol.Tool{ .{ .name = "zeta", .description = "z", .parameters = schema }, .{ .name = "alpha", .description = "a", .parameters = .null } };
    const contents = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &contents, .timestamp_ms = 0 } }};
    const model = protocol.Model{ .id = "model", .api = "openai", .provider = "p", .context_window = 4096, .max_tokens = 128 };
    var first = try renderRequest(allocator, &model, &.{ .system_prompt = "", .messages = &messages, .cacheable_message_count = 1, .tools = &tools }, .{}, .openai_chat);
    defer first.deinit();
    var second = try renderRequest(allocator, &model, &.{ .system_prompt = "", .messages = &messages, .cacheable_message_count = 1, .tools = &tools }, .{}, .openai_chat);
    defer second.deinit();
    try std.testing.expectEqualSlices(u8, &first.cache_prefix_hash, &second.cache_prefix_hash);
    try std.testing.expectEqualSlices(u8, first.bytes, second.bytes);
    try std.testing.expect(std.mem.indexOf(u8, first.bytes, "alpha").? < std.mem.indexOf(u8, first.bytes, "zeta").?);
    try std.testing.expect(std.mem.indexOf(u8, first.bytes, "\"a\":{\"b\":null,\"y\":true}") != null);
    try std.testing.expect(std.mem.indexOf(u8, first.bytes, "\"stream\":true") != null);
}

test "canonical schema uses RFC 8785 UTF-16 key order and rejects non-JCS numbers" {
    const allocator = std.testing.allocator;
    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();
    const model = protocol.Model{ .id = "model", .api = "openai", .provider = "p", .context_window = 4096, .max_tokens = 128 };
    const unicode_schema = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), "{\"\\uE000\":1,\"\\uD800\\uDC00\":2}", .{});
    const unicode_tools = [_]protocol.Tool{.{ .name = "unicode", .description = "unicode", .parameters = unicode_schema }};
    var unicode = try renderRequest(allocator, &model, &.{ .system_prompt = "", .messages = &.{}, .tools = &unicode_tools }, .{}, .openai_chat);
    defer unicode.deinit();
    const non_bmp_index = std.mem.indexOf(u8, unicode.bytes, "\"\xf0\x90\x80\x80\":2") orelse return error.ExpectedUnicodeSchemaKey;
    const e000 = std.mem.indexOf(u8, unicode.bytes, "\"\xee\x80\x80\":1") orelse return error.ExpectedUnicodeSchemaKey;
    try std.testing.expect(non_bmp_index < e000);

    inline for (.{ "{\"value\":1e30}", "{\"value\":1e-27}", "{\"value\":-0.0}" }) |source| {
        const schema = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), source, .{});
        const tools = [_]protocol.Tool{.{ .name = "numeric", .description = "numeric", .parameters = schema }};
        try std.testing.expectError(error.UnsupportedCanonicalNumber, renderRequest(allocator, &model, &.{ .system_prompt = "", .messages = &.{}, .tools = &tools }, .{}, .openai_chat));
    }
    inline for (.{ "{\"value\":9007199254740991}", "{\"value\":-9007199254740991}" }) |source| {
        const schema = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), source, .{});
        const tools = [_]protocol.Tool{.{ .name = "integer", .description = "integer", .parameters = schema }};
        var rendered = try renderRequest(allocator, &model, &.{ .system_prompt = "", .messages = &.{}, .tools = &tools }, .{}, .openai_chat);
        rendered.deinit();
    }
    inline for (.{ "{\"value\":9007199254740992}", "{\"value\":-9007199254740992}" }) |source| {
        const schema = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), source, .{});
        const tools = [_]protocol.Tool{.{ .name = "unsafe", .description = "unsafe", .parameters = schema }};
        try std.testing.expectError(error.UnsupportedCanonicalNumber, renderRequest(allocator, &model, &.{ .system_prompt = "", .messages = &.{}, .tools = &tools }, .{}, .openai_chat));
    }
}

test "cache prefix identity remains append-stable for every chat dialect" {
    const allocator = std.testing.allocator;
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    const first_a = [_]protocol.UserContent{.{ .text = .{ .text = "alpha" } }};
    const later = [_]protocol.UserContent{.{ .text = .{ .text = "later" } }};
    const messages_a = [_]protocol.Message{.{ .user = .{ .content = &first_a, .timestamp_ms = 0 } }};
    const messages_b = [_]protocol.Message{
        .{ .user = .{ .content = &first_a, .timestamp_ms = 0 } },
        .{ .user = .{ .content = &later, .timestamp_ms = 0 } },
    };
    var zero_a = try renderRequest(allocator, &model, &.{ .system_prompt = "stable", .messages = &messages_a, .cacheable_message_count = 0 }, .{}, .openai_chat);
    defer zero_a.deinit();
    var zero_b = try renderRequest(allocator, &model, &.{ .system_prompt = "stable", .messages = &messages_b, .cacheable_message_count = 0 }, .{}, .openai_chat);
    defer zero_b.deinit();
    try std.testing.expectEqual(zero_a.cache_prefix_len, zero_b.cache_prefix_len);
    try std.testing.expectEqual(zero_a.cache_prefix_hash, zero_b.cache_prefix_hash);
    try std.testing.expect(std.mem.eql(u8, zero_a.bytes[0..zero_a.cache_prefix_len], zero_b.bytes[0..zero_b.cache_prefix_len]));
    try std.testing.expect(!std.mem.eql(u8, zero_a.bytes, zero_b.bytes));

    inline for (.{ Dialect.openai_chat, Dialect.openrouter_chat, Dialect.deepseek_chat }) |dialect| {
        var one_a = try renderRequest(allocator, &model, &.{ .system_prompt = "stable", .messages = &messages_a, .cacheable_message_count = 1 }, .{}, dialect);
        defer one_a.deinit();
        var one_b = try renderRequest(allocator, &model, &.{ .system_prompt = "stable", .messages = &messages_b, .cacheable_message_count = 1 }, .{}, dialect);
        defer one_b.deinit();
        try std.testing.expectEqual(one_a.cache_prefix_len, one_b.cache_prefix_len);
        try std.testing.expectEqualSlices(u8, &one_a.cache_prefix_hash, &one_b.cache_prefix_hash);
        try std.testing.expectEqualSlices(u8, one_a.bytes[0..one_a.cache_prefix_len], one_b.bytes[0..one_b.cache_prefix_len]);
    }

    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();
    const schema = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), "{\"type\":\"object\"}", .{});
    const tool_a = [_]protocol.Tool{.{ .name = "echo", .description = "tool", .parameters = schema }};
    const tool_b = [_]protocol.Tool{.{ .name = "zcho", .description = "tool", .parameters = schema }};
    var tool_render_a = try renderRequest(allocator, &model, &.{ .system_prompt = "stable", .messages = &.{}, .tools = &tool_a }, .{}, .openai_chat);
    defer tool_render_a.deinit();
    var tool_render_b = try renderRequest(allocator, &model, &.{ .system_prompt = "stable", .messages = &.{}, .tools = &tool_b }, .{}, .openai_chat);
    defer tool_render_b.deinit();
    try std.testing.expect(!std.mem.eql(u8, tool_render_a.bytes[0..tool_render_a.cache_prefix_len], tool_render_b.bytes[0..tool_render_b.cache_prefix_len]));
}

test "renderer rejects excessive message structure before payload allocation" {
    const part = [_]protocol.UserContent{.{ .text = .{ .text = "x" } }};
    var messages: [4097]protocol.Message = undefined;
    for (&messages) |*message| message.* = .{ .user = .{ .content = &part, .timestamp_ms = 0 } };
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 8192, .max_tokens = 256 };
    const result = renderRequest(std.testing.allocator, &model, &.{ .system_prompt = "", .messages = &messages }, .{}, .openai_chat);
    if (result) |rendered| {
        var owned = rendered;
        owned.deinit();
        return error.ExpectedRequestStructureLimit;
    } else |err| try std.testing.expectEqual(error.RequestTooLarge, err);
}

test "renderer preflight bounds structural system-lane JSON before allocation" {
    var lane: [21_844]u8 = undefined;
    @memset(&lane, 0);
    var lanes: [64][]const u8 = undefined;
    for (&lanes) |*entry| entry.* = &lane;
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 8192, .max_tokens = 256 };
    var failing = std.testing.FailingAllocator.init(std.testing.allocator, .{ .fail_index = 0 });
    try std.testing.expectError(error.RequestTooLarge, renderRequest(failing.allocator(), &model, &.{ .system_prompt = "", .system_prompt_lanes = &lanes, .messages = &.{} }, .{}, .openai_chat));
}

test "renderer rejects duplicate tool names and canonicalizes distinct tool order" {
    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    const schema = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), "{\"type\":\"object\"}", .{});
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    const duplicates = [_]protocol.Tool{
        .{ .name = "duplicate", .description = "first", .parameters = schema },
        .{ .name = "duplicate", .description = "second", .parameters = schema },
    };
    const duplicate_result = renderRequest(std.testing.allocator, &model, &.{ .system_prompt = "stable", .messages = &.{}, .tools = &duplicates }, .{}, .openai_chat);
    if (duplicate_result) |rendered| {
        var owned = rendered;
        owned.deinit();
        return error.ExpectedDuplicateToolName;
    } else |err| try std.testing.expectEqual(error.DuplicateToolName, err);

    const ordered = [_]protocol.Tool{
        .{ .name = "alpha", .description = "first", .parameters = schema },
        .{ .name = "zeta", .description = "second", .parameters = schema },
    };
    const reversed = [_]protocol.Tool{ ordered[1], ordered[0] };
    var first = try renderRequest(std.testing.allocator, &model, &.{ .system_prompt = "stable", .messages = &.{}, .tools = &ordered }, .{}, .openai_chat);
    defer first.deinit();
    var second = try renderRequest(std.testing.allocator, &model, &.{ .system_prompt = "stable", .messages = &.{}, .tools = &reversed }, .{}, .openai_chat);
    defer second.deinit();
    try std.testing.expectEqualStrings(first.bytes, second.bytes);
    try std.testing.expectEqual(first.cache_prefix_len, second.cache_prefix_len);
    try std.testing.expect(std.mem.eql(u8, &first.cache_prefix_hash, &second.cache_prefix_hash));
}

test "provider registry deep copies explicit dialect, safe headers, and capabilities" {
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    const efforts = [_]protocol.ThinkingLevel{.low};
    const models = [_]ModelCapability{.{ .id = "m", .context_window = 4096, .output_limit = 128, .reasoning = true, .reasoning_efforts = &efforts }};
    const headers = [_]Header{.{ .name = "x-client", .value = "test" }};
    var registry = try ProviderRegistry.init(std.testing.allocator, std.testing.io, &environment, &.{.{ .id = "p", .endpoint = "http://localhost:1/v1", .credential_ref = "P_KEY", .headers = &headers, .dialect = .openrouter_chat, .models = &models, .retry = .{ .deadline_ms = 5_000 } }});
    defer registry.deinit();
    try std.testing.expectEqual(Dialect.openrouter_chat, registry.find("p").?.dialect);
    try std.testing.expectEqualStrings("x-client", registry.find("p").?.headers[0].name);
    try std.testing.expectError(error.ReservedHeader, ProviderRegistry.init(std.testing.allocator, std.testing.io, &environment, &.{.{ .id = "p", .endpoint = "http://localhost:1", .credential_ref = "P_KEY", .headers = &.{.{ .name = "authorization", .value = "x" }}, .models = &models, .retry = .{ .deadline_ms = 5_000 } }}));
}

test "provider registry rejects configurations without an absolute deadline" {
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    const models = [_]ModelCapability{.{ .id = "m", .context_window = 4096, .output_limit = 128 }};
    const result = ProviderRegistry.init(std.testing.allocator, std.testing.io, &environment, &.{.{ .id = "p", .endpoint = "http://localhost:1/v1", .credential_ref = "P_KEY", .models = &models }});
    if (result) |registry_value| {
        var registry = registry_value;
        registry.deinit();
        return error.ExpectedDeadlineValidation;
    } else |err| try std.testing.expectEqual(error.InvalidProviderConfig, err);
}

test "provider registry rejects model capabilities whose output exceeds context" {
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    const impossible = [_]ModelCapability{.{ .id = "m", .context_window = 1, .output_limit = 2 }};
    try std.testing.expectError(error.InvalidModelCapability, ProviderRegistry.init(std.testing.allocator, std.testing.io, &environment, &.{.{ .id = "p", .endpoint = "http://localhost:1/v1", .credential_ref = "P_KEY", .models = &impossible, .retry = .{ .deadline_ms = 5_000 } }}));
}

test "provider endpoints are origin-only and reject URI userinfo" {
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    const models = [_]ModelCapability{.{ .id = "m", .context_window = 4096, .output_limit = 128 }};
    const result = ProviderRegistry.init(std.testing.allocator, std.testing.io, &environment, &.{.{ .id = "p", .endpoint = "http://url-user:url-password@127.0.0.1:1/v1", .credential_ref = "P_KEY", .models = &models, .retry = .{ .deadline_ms = 5_000 } }});
    if (result) |registry_value| {
        var registry = registry_value;
        registry.deinit();
        return error.ExpectedEndpointUserinfoRejection;
    } else |err| try std.testing.expectEqual(error.InvalidProviderConfig, err);
    const endpoint_result = endpointUrl(std.testing.allocator, "http://url-user:url-password@127.0.0.1:1/v1");
    if (endpoint_result) |url| {
        defer std.testing.allocator.free(url);
        return error.ExpectedEndpointUserinfoRejection;
    } else |err| try std.testing.expectEqual(error.InvalidBaseUrl, err);
}

test "provider config cloning rolls back every allocation boundary" {
    const efforts = [_]protocol.ThinkingLevel{ .low, .high };
    const headers = [_]Header{.{ .name = "x-client", .value = "client-value" }};
    const models = [_]ModelCapability{.{ .id = "model-id", .context_window = 4096, .output_limit = 512, .reasoning = true, .reasoning_efforts = &efforts }};
    const configs = [_]ProviderConfig{.{ .id = "provider-id", .endpoint = "http://127.0.0.1:1/v1", .credential_ref = "TOKEN", .credential_source = .explicit, .headers = &headers, .models = &models, .retry = .{ .deadline_ms = 5_000 } }};
    var fail_index: usize = 0;
    while (fail_index < 32) : (fail_index += 1) {
        var failing = std.testing.FailingAllocator.init(std.testing.allocator, .{ .fail_index = fail_index });
        const result = ProviderRegistry.init(failing.allocator(), std.testing.io, undefined, &configs);
        if (result) |registry_value| {
            var registry = registry_value;
            registry.deinit();
        } else |err| {
            try std.testing.expectEqual(error.OutOfMemory, err);
        }
    }
}

test "request controls obey config ceilings and preserve dialect wire fields" {
    const model = protocol.Model{ .id = "deepseek-reasoner", .api = "deepseek-chat", .provider = "deepseek", .context_window = 8192, .max_tokens = 1024, .reasoning = true };
    const context = protocol.Context{ .system_prompt = "stable", .messages = &.{} };
    var rendered = try renderRequest(std.testing.allocator, &model, &context, .{ .max_tokens = 64, .reasoning = .xhigh }, .deepseek_chat);
    defer rendered.deinit();
    try std.testing.expect(std.mem.indexOf(u8, rendered.bytes, "\"max_tokens\":64") != null);
    try std.testing.expect(std.mem.indexOf(u8, rendered.bytes, "\"thinking\":{\"type\":\"enabled\"}") != null);
    try std.testing.expect(std.mem.indexOf(u8, rendered.bytes, "\"reasoning_effort\":\"high\"") != null);
    try std.testing.expect(std.mem.indexOf(u8, rendered.bytes, "max_completion_tokens") == null);

    var openai = try renderRequest(std.testing.allocator, &model, &context, .{ .max_tokens = 64 }, .openai_chat);
    defer openai.deinit();
    try std.testing.expect(std.mem.indexOf(u8, openai.bytes, "\"max_completion_tokens\":64") != null);
    try std.testing.expect(std.mem.indexOf(u8, openai.bytes, "\"max_tokens\":64") == null);
}

test "direct rendering rejects cache retention without a configured model wire mode" {
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    const context = protocol.Context{ .system_prompt = "stable", .messages = &.{} };
    try std.testing.expectError(error.UnsupportedOption, renderRequest(std.testing.allocator, &model, &context, .{ .cache_retention = .short }, .openai_chat));
}

test "configured model capability renders only its explicit cache wire mode" {
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    const context = protocol.Context{ .system_prompt = "stable", .messages = &.{} };
    var rendered = try renderRequestWithCapabilities(std.testing.allocator, &model, &context, .{ .cache_retention = .short }, .openai_chat, true, .prompt_cache_options_ttl_30m);
    defer rendered.deinit();
    try std.testing.expect(std.mem.indexOf(u8, rendered.bytes, "\"prompt_cache_options\":{\"mode\":\"implicit\",\"ttl\":\"30m\"}") != null);
    try std.testing.expect(std.mem.indexOf(u8, rendered.bytes, "\"prompt_cache_retention\"") == null);
}

test "model capability rejects retention wires outside their exact request mode" {
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    const models = [_]ModelCapability{.{ .id = "m", .context_window = 4096, .output_limit = 128, .short_cache_retention = .prompt_cache_retention_24h }};
    try std.testing.expectError(error.InvalidModelCapability, ProviderRegistry.init(std.testing.allocator, std.testing.io, &environment, &.{.{ .id = "p", .endpoint = "http://localhost:1/v1", .credential_ref = "P_KEY", .models = &models, .retry = .{ .deadline_ms = 5_000 } }}));
}

test "configured deadline caps caller timeout and rejects invalid durations" {
    try std.testing.expectEqual(@as(?u64, 5_000), try effectiveDeadlineMilliseconds(5_000, 60_000));
    try std.testing.expectEqual(@as(?u64, 500), try effectiveDeadlineMilliseconds(5_000, 500));
    try std.testing.expectError(error.InvalidDeadline, effectiveDeadlineMilliseconds(5_000, 0));
    try std.testing.expectError(error.InvalidDeadline, effectiveDeadlineMilliseconds(5_000, std.math.maxInt(u64)));
}

test "request observer failure prevents a request identity from being accepted" {
    const FailingObserver = struct {
        fn receive(_: *anyopaque, _: RenderedRequestMetadata) !void {
            return error.ObserverRejected;
        }
    };
    const bytes = try std.testing.allocator.dupe(u8, "{}");
    var cache_hash: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash(bytes, &cache_hash, .{});
    var rendered = RenderedRequest{ .bytes = bytes, .cache_prefix_len = bytes.len, .cache_prefix_hash = cache_hash, .allocator = std.testing.allocator };
    defer rendered.deinit();
    try std.testing.expectError(error.RequestObserverFailed, observeRenderedRequest(&rendered, @ptrFromInt(1), FailingObserver.receive));
}

test "chat dialects reject unsupported cache aggressiveness before rendering" {
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    const context = protocol.Context{ .system_prompt = "stable", .messages = &.{} };
    if (renderRequest(std.testing.allocator, &model, &context, .{ .cache_aggressiveness = .aggressive }, .openai_chat)) |rendered| {
        var owned = rendered;
        owned.deinit();
        return error.ExpectedUnsupportedCacheAggressiveness;
    } else |err| try std.testing.expectEqual(error.UnsupportedOption, err);
}

test "registry refuses historical tool wire for a tool-disabled model before observation or transport" {
    const Capture = struct {
        failed: usize = 0,
        category: ?protocol.ProviderFailureCategory = null,
        observed: usize = 0,

        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            if (event == .failed) {
                capture.failed += 1;
                capture.category = event.failed.provider_failure.?.category;
            }
        }

        fn observe(raw: *anyopaque, _: RenderedRequestMetadata) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            capture.observed += 1;
        }
    };
    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    const arguments = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), "{}", .{});
    const assistant_parts = [_]protocol.AssistantContent{.{ .tool_call = .{ .id = "call_1", .name = "echo", .arguments = arguments } }};
    const result_content = [_]protocol.UserContent{.{ .text = .{ .text = "ok" } }};
    const messages = [_]protocol.Message{
        .{ .assistant = .{ .content = &assistant_parts, .api = "openai-chat", .provider = "p", .model = "m", .stop_reason = .tool_use, .timestamp_ms = 0 } },
        .{ .tool_result = .{ .tool_call_id = "call_1", .tool_name = "echo", .content = &result_content, .is_error = false, .timestamp_ms = 0 } },
    };
    const context = protocol.Context{ .system_prompt = "", .messages = &messages };
    try std.testing.expect(contextUsesToolWire(&context));
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 128 };
    const capabilities = [_]ModelCapability{.{ .id = "m", .context_window = 4096, .output_limit = 128, .tool_call = false }};
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    var registry = try ProviderRegistry.init(std.testing.allocator, std.testing.io, &environment, &.{.{ .id = "p", .endpoint = "http://127.0.0.1:1/v1", .credential_ref = "token", .credential_source = .explicit, .models = &capabilities, .retry = .{ .deadline_ms = 5_000 } }});
    defer registry.deinit();
    var capture = Capture{};
    try registry.streamObserved(&model, &context, .{}, &capture, Capture.receive, &capture, Capture.observe);
    try std.testing.expectEqual(@as(usize, 1), capture.failed);
    try std.testing.expectEqual(protocol.ProviderFailureCategory.unsupported, capture.category.?);
    try std.testing.expectEqual(@as(usize, 0), capture.observed);
}

test "request wire rejects invalid UTF-8 before rendering, observation, or transport" {
    const Capture = struct {
        failed: usize = 0,
        category: ?protocol.ProviderFailureCategory = null,
        observed: usize = 0,

        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            if (event == .failed) {
                capture.failed += 1;
                capture.category = event.failed.provider_failure.?.category;
            }
        }

        fn observe(raw: *anyopaque, _: RenderedRequestMetadata) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            capture.observed += 1;
        }
    };
    const invalid = [_]u8{0xff};
    const model = protocol.Model{ .id = "m", .api = "openrouter-chat", .provider = "p", .context_window = 4096, .max_tokens = 128 };
    const text_content = [_]protocol.UserContent{.{ .text = .{ .text = &invalid } }};
    const text_messages = [_]protocol.Message{.{ .user = .{ .content = &text_content, .timestamp_ms = 0 } }};
    const text_context = protocol.Context{ .system_prompt = "", .messages = &text_messages };
    try std.testing.expectError(error.InvalidRequestText, renderRequest(std.testing.allocator, &model, &text_context, .{}, .openrouter_chat));
    try std.testing.expectError(error.InvalidRequestText, renderRequest(std.testing.allocator, &model, &.{ .system_prompt = &invalid, .messages = &.{} }, .{}, .openrouter_chat));
    try std.testing.expectError(error.InvalidRequestText, renderRequest(std.testing.allocator, &.{ .id = &invalid, .api = "openrouter-chat", .provider = "p", .context_window = 4096, .max_tokens = 128 }, &.{ .system_prompt = "", .messages = &.{} }, .{}, .openrouter_chat));
    try std.testing.expectError(error.InvalidRequestText, renderRequest(std.testing.allocator, &model, &.{ .system_prompt = "", .messages = &.{} }, .{ .session_id = &invalid }, .openrouter_chat));

    const capabilities = [_]ModelCapability{.{ .id = "m", .context_window = 4096, .output_limit = 128 }};
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    var registry = try ProviderRegistry.init(std.testing.allocator, std.testing.io, &environment, &.{.{ .id = "p", .endpoint = "http://127.0.0.1:1/v1", .credential_ref = "token", .credential_source = .explicit, .dialect = .openrouter_chat, .models = &capabilities, .retry = .{ .deadline_ms = 5_000 } }});
    defer registry.deinit();
    var capture = Capture{};
    try registry.streamObserved(&model, &text_context, .{}, &capture, Capture.receive, &capture, Capture.observe);
    try std.testing.expectEqual(@as(usize, 1), capture.failed);
    try std.testing.expectEqual(protocol.ProviderFailureCategory.invalid_request, capture.category.?);
    try std.testing.expectEqual(@as(usize, 0), capture.observed);
}

test "request retry controls are bounded by immutable provider policy" {
    const policy = RetryPolicy.fromOptions(.{ .max_retries = 0, .max_retry_delay_ms = 1 }, .{ .max_retries = 3, .max_retry_delay_ms = 4_000 });
    try std.testing.expectEqual(@as(u32, 0), policy.max_retries);
    try std.testing.expectEqual(@as(u64, 1), policy.max_delay_ms);
    const ceiling = RetryPolicy.fromOptions(.{ .max_retries = 9, .max_retry_delay_ms = 60_001 }, .{ .max_retries = 2, .max_retry_delay_ms = 4_000 });
    try std.testing.expectEqual(@as(u32, 2), ceiling.max_retries);
    try std.testing.expectEqual(@as(u64, 4_000), ceiling.max_delay_ms);
}

test "assistant text parts are joined and DeepSeek reasoning is replayed" {
    const parts = [_]protocol.AssistantContent{
        .{ .text = .{ .text = "one" } },
        .{ .thinking = .{ .thinking = "private" } },
        .{ .text = .{ .text = " two" } },
    };
    const messages = [_]protocol.Message{.{ .assistant = .{ .content = &parts, .api = "deepseek-chat", .provider = "deepseek", .model = "m", .stop_reason = .stop, .timestamp_ms = 0 } }};
    const model = protocol.Model{ .id = "m", .api = "deepseek-chat", .provider = "deepseek", .context_window = 4096, .max_tokens = 256, .reasoning = true };
    const context = protocol.Context{ .system_prompt = "", .messages = &messages };
    var rendered = try renderRequest(std.testing.allocator, &model, &context, .{}, .deepseek_chat);
    defer rendered.deinit();
    try std.testing.expectEqual(@as(usize, 1), std.mem.count(u8, rendered.bytes, "\"content\""));
    try std.testing.expect(std.mem.indexOf(u8, rendered.bytes, "\"content\":\"one two\"") != null);
    try std.testing.expect(std.mem.indexOf(u8, rendered.bytes, "\"reasoning_content\":\"private\"") != null);
}

test "OpenRouter tool-call replay retains assistant reasoning" {
    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    const arguments = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), "{\"path\":\"README.md\"}", .{});
    const parts = [_]protocol.AssistantContent{
        .{ .thinking = .{ .thinking = "inspect file" } },
        .{ .tool_call = .{ .id = "call_1", .name = "read_file", .arguments = arguments } },
    };
    const messages = [_]protocol.Message{.{ .assistant = .{ .content = &parts, .api = "openrouter-chat", .provider = "openrouter", .model = "m", .stop_reason = .tool_use, .timestamp_ms = 0 } }};
    const model = protocol.Model{ .id = "m", .api = "openrouter-chat", .provider = "openrouter", .context_window = 4096, .max_tokens = 256, .reasoning = true };
    var rendered = try renderRequest(std.testing.allocator, &model, &.{ .system_prompt = "", .messages = &messages }, .{}, .openrouter_chat);
    defer rendered.deinit();
    try std.testing.expect(std.mem.indexOf(u8, rendered.bytes, "\"reasoning\":\"inspect file\"") != null);
    try std.testing.expect(std.mem.indexOf(u8, rendered.bytes, "\"tool_calls\":[{\"id\":\"call_1\"") != null);
}

test "endpoint normalization does not duplicate chat completion paths" {
    const allocator = std.testing.allocator;
    const existing = try endpointUrl(allocator, "https://example.test/v1/chat/completions/");
    defer allocator.free(existing);
    try std.testing.expectEqualStrings("https://example.test/v1/chat/completions", existing);
    const base = try endpointUrl(allocator, "https://example.test/v1/");
    defer allocator.free(base);
    try std.testing.expectEqualStrings("https://example.test/v1/chat/completions", base);
}

test "diagnostics redact configured header secrets as well as credentials" {
    const headers = [_]Header{.{ .name = "x-client-secret", .value = "header-canary" }};
    const diagnostic = diagnosticForStatusSecrets(500, "request-header-canary", "failure header-canary bearer-canary", "bearer-canary", &headers);
    try std.testing.expect(std.mem.indexOf(u8, diagnostic.redactedMessage(), "header-canary") == null);
    try std.testing.expect(std.mem.indexOf(u8, diagnostic.redactedMessage(), "bearer-canary") == null);
}

test "diagnostic redaction chooses the longest overlapping configured secret" {
    const primary_prefix = "prefix";
    const header_suffix = "prefix-sensitive-suffix";
    const prefix_headers = [_]Header{.{ .name = "x-header-secret", .value = header_suffix }};
    const prefix_diagnostic = diagnosticForStatusSecrets(500, null, "leak prefix-sensitive-suffix", primary_prefix, &prefix_headers);
    try std.testing.expect(std.mem.indexOf(u8, prefix_diagnostic.redactedMessage(), primary_prefix) == null);
    try std.testing.expect(std.mem.indexOf(u8, prefix_diagnostic.redactedMessage(), "sensitive-suffix") == null);

    const primary_suffix = "prefix-sensitive-suffix";
    const header_prefix = "prefix";
    const suffix_headers = [_]Header{.{ .name = "x-header-prefix", .value = header_prefix }};
    const suffix_diagnostic = diagnosticForStatusSecrets(500, null, "leak prefix-sensitive-suffix", primary_suffix, &suffix_headers);
    try std.testing.expect(std.mem.indexOf(u8, suffix_diagnostic.redactedMessage(), header_prefix) == null);
    try std.testing.expect(std.mem.indexOf(u8, suffix_diagnostic.redactedMessage(), "sensitive-suffix") == null);

    const headers = [_]Header{
        .{ .name = "x-short-secret", .value = "shared" },
        .{ .name = "x-long-secret", .value = "shared-secret" },
    };
    const diagnostic = diagnosticForStatusSecrets(500, null, "leak shared-secret", null, &headers);
    try std.testing.expect(std.mem.indexOf(u8, diagnostic.redactedMessage(), "shared") == null);
    try std.testing.expect(std.mem.indexOf(u8, diagnostic.redactedMessage(), "secret") == null);
}

test "structured provider error code outranks conflicting message text" {
    const diagnostic = try parseDiagnostic(std.testing.allocator, 429, "{\"error\":{\"code\":\"rate_limit_exceeded\",\"type\":\"requests\",\"message\":\"insufficient_quota\"}}", null, null, &.{});
    try std.testing.expectEqual(protocol.ProviderFailureCategory.rate_limited, diagnostic.category);
    try std.testing.expectEqualStrings("rate_limit_exceeded", diagnostic.providerCode().?);
    try std.testing.expectEqualStrings("requests", diagnostic.providerType().?);
}

test "numeric provider error codes remain bounded identifiers" {
    const diagnostic = try parseDiagnostic(std.testing.allocator, 404, "{\"error\":{\"code\":404,\"message\":\"No endpoints found\"}}", null, null, &.{});
    try std.testing.expectEqualStrings("404", diagnostic.providerCode().?);
}

test "plain provider error bodies become bounded redacted diagnostic messages" {
    const diagnostic = try parseDiagnostic(std.testing.allocator, 429, "insufficient_quota quota", null, "quota", &.{});
    try std.testing.expectEqual(protocol.ProviderFailureCategory.quota_exhausted, diagnostic.category);
    try std.testing.expect(std.mem.indexOf(u8, diagnostic.redactedMessage(), "insufficient_[REDACTED]") != null);
    try std.testing.expect(std.mem.indexOf(u8, diagnostic.redactedMessage(), "quota") == null);
}

test "diagnostic redaction preserves UTF-8 for malformed plain and structured provider bodies" {
    const plain = [_]u8{ 'b', 'a', 'd', 0xff, 'm', 'e', 's', 's', 'a', 'g', 'e' };
    const plain_diagnostic = try parseDiagnostic(std.testing.allocator, 400, &plain, null, null, &.{});
    try std.testing.expect(std.unicode.utf8ValidateSlice(plain_diagnostic.redactedMessage()));
    try std.testing.expect(std.mem.indexOfScalar(u8, plain_diagnostic.redactedMessage(), 0xff) == null);

    const structured = [_]u8{ '{', '"', 'e', 'r', 'r', 'o', 'r', '"', ':', '{', '"', 'm', 'e', 's', 's', 'a', 'g', 'e', '"', ':', '"', 'b', 'a', 'd', 0xff, '"', '}', '}' };
    const structured_diagnostic = try parseDiagnostic(std.testing.allocator, 400, &structured, null, null, &.{});
    try std.testing.expect(std.unicode.utf8ValidateSlice(structured_diagnostic.redactedMessage()));
    try std.testing.expect(std.mem.indexOfScalar(u8, structured_diagnostic.redactedMessage(), 0xff) == null);
}

test "credentials cannot exceed the Authorization header value bound" {
    const maximum = "x" ** (max_header_value_bytes - "Bearer ".len);
    const too_large = "x" ** (max_header_value_bytes - "Bearer ".len + 1);
    try validateCredential(maximum);
    try std.testing.expectError(error.InvalidCredential, validateCredential(too_large));

    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    try environment.put("PROVIDER_TOKEN", too_large);
    const model = [_]ModelCapability{.{ .id = "m", .context_window = 4096, .output_limit = 128 }};
    const config = ProviderConfig{ .id = "p", .endpoint = "http://localhost:1/v1", .credential_ref = "PROVIDER_TOKEN", .models = &model, .retry = .{ .deadline_ms = 5_000 } };
    try std.testing.expectError(error.InvalidCredential, resolveCredential(&environment, &config));
}

test "configured header values and credentials reject forbidden control bytes" {
    try validateHeader(.{ .name = "x-visible", .value = "visible\t\x80" });
    try std.testing.expectError(error.InvalidHeader, validateHeader(.{ .name = "x-nul", .value = "bad\x00value" }));
    try std.testing.expectError(error.InvalidHeader, validateHeader(.{ .name = "x-unit-separator", .value = "bad\x1fvalue" }));
    try std.testing.expectError(error.InvalidHeader, validateHeader(.{ .name = "x-delete", .value = "bad\x7fvalue" }));
    try std.testing.expectError(error.InvalidCredential, validateCredential("bad\x00token"));
    try std.testing.expectError(error.InvalidCredential, validateCredential("bad\x7ftoken"));
}

test "provider configuration reserves transport-owned headers" {
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    const models = [_]ModelCapability{.{ .id = "m", .context_window = 4096, .output_limit = 128 }};
    inline for (.{ "authorization", "content-length", "transfer-encoding", "host", "content-type", "accept", "connection" }) |name| {
        try std.testing.expectError(error.ReservedHeader, ProviderRegistry.init(std.testing.allocator, std.testing.io, &environment, &.{.{ .id = "p", .endpoint = "http://localhost:1/v1", .credential_ref = "P_KEY", .headers = &.{.{ .name = name, .value = "x" }}, .models = &models, .retry = .{ .deadline_ms = 5_000 } }}));
    }
}

test "Retry-After accepts every RFC HTTP-date form" {
    const target = parseHttpDate("Sun, 06 Nov 1994 08:49:37 GMT").?;
    try std.testing.expectEqual(@as(?u64, 2_000), parseRetryAfter("Sun, 06 Nov 1994 08:49:37 GMT", target - 2));
    try std.testing.expectEqual(@as(?u64, 2_000), parseRetryAfter("Sunday, 06-Nov-94 08:49:37 GMT", target - 2));
    try std.testing.expectEqual(@as(?u64, 2_000), parseRetryAfter("Sun Nov  6 08:49:37 1994", target - 2));
}

test "RFC850 Retry-After years use the dynamic fifty-year century window" {
    const reference = httpDateSeconds(2026, .jan, 1, 0, 0, 0).?;
    try std.testing.expectEqual(httpDateSeconds(2070, .jan, 1, 0, 0, 0), parseHttpDateAt("Thursday, 01-Jan-70 00:00:00 GMT", reference));
    try std.testing.expectEqual(httpDateSeconds(2076, .jan, 1, 0, 0, 0), parseHttpDateAt("Thursday, 01-Jan-76 00:00:00 GMT", reference));
    try std.testing.expectEqual(httpDateSeconds(1976, .jan, 2, 0, 0, 0), parseHttpDateAt("Thursday, 02-Jan-76 00:00:00 GMT", reference));
}

test "OpenAI usage chunks accept null on deltas and an empty final choice array" {
    const Capture = struct {
        usage: usize = 0,
        done: usize = 0,
        failed: usize = 0,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            switch (event) {
                .usage => capture.usage += 1,
                .done => capture.done += 1,
                .failed => capture.failed += 1,
                else => {},
            }
        }
    };
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "openai-chat", .openai_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"usage\":null,\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"usage\":null,\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3},\"choices\":[]}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "[DONE]" });
    try decoder.finish();
    try std.testing.expectEqual(@as(usize, 1), capture.usage);
    try std.testing.expectEqual(@as(usize, 1), capture.done);
    try std.testing.expectEqual(@as(usize, 0), capture.failed);
}

test "OpenRouter repeated final choice carries usage without duplicating output" {
    const Capture = struct {
        text: usize = 0,
        usage: usize = 0,
        done: usize = 0,
        failed: usize = 0,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            switch (event) {
                .text_delta => capture.text += 1,
                .usage => capture.usage += 1,
                .done => capture.done += 1,
                .failed => capture.failed += 1,
                else => {},
            }
        }
    };
    const model = protocol.Model{ .id = "m", .api = "openrouter-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "openrouter-chat", .openrouter_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3},\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "[DONE]" });
    try decoder.finish();
    try std.testing.expectEqual(@as(usize, 1), capture.text);
    try std.testing.expectEqual(@as(usize, 1), capture.usage);
    try std.testing.expectEqual(@as(usize, 1), capture.done);
    try std.testing.expectEqual(@as(usize, 0), capture.failed);
}

test "DeepSeek usage preserves cache hits and rejects conflicting wire representations" {
    const Capture = struct {
        failed: usize = 0,
        done: usize = 0,
        usage: protocol.Usage = .{},
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            switch (event) {
                .failed => capture.failed += 1,
                .done => |done| {
                    capture.done += 1;
                    capture.usage = done.usage;
                },
                else => {},
            }
        }
    };
    const model = protocol.Model{ .id = "m", .api = "deepseek-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "deepseek-chat", .deepseek_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_cache_hit_tokens\":7,\"prompt_cache_miss_tokens\":3,\"total_tokens\":12},\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "[DONE]" });
    try decoder.finish();
    try std.testing.expectEqual(@as(usize, 0), capture.failed);
    try std.testing.expectEqual(@as(usize, 1), capture.done);
    try std.testing.expectEqual(@as(u64, 10), capture.usage.input);
    try std.testing.expectEqual(@as(u64, 2), capture.usage.output);
    try std.testing.expectEqual(@as(u64, 7), capture.usage.cache_read);
    try std.testing.expectEqual(@as(u64, 0), capture.usage.cache_write);
    try std.testing.expectEqual(@as(u64, 12), capture.usage.total_tokens);

    var conflict_capture = Capture{};
    var conflict_callback_attempted = false;
    var conflict = StreamDecoder.init(std.testing.allocator, &model, "deepseek-chat", .deepseek_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &conflict_capture, Capture.receive, &conflict_callback_attempted);
    defer conflict.deinit();
    try decoderEvent(&conflict, .{ .event = "message", .data = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":6},\"prompt_cache_hit_tokens\":7,\"total_tokens\":12},\"choices\":[]}" });
    try std.testing.expectEqual(@as(usize, 1), conflict_capture.failed);
}

test "usage objects require coherent required provider counters" {
    const Case = struct { dialect: Dialect, source: []const u8 };
    inline for (.{
        Case{ .dialect = .openai_chat, .source = "{}" },
        Case{ .dialect = .openrouter_chat, .source = "{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":-3}" },
        Case{ .dialect = .deepseek_chat, .source = "{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}" },
        Case{ .dialect = .deepseek_chat, .source = "{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_cache_hit_tokens\":7,\"prompt_cache_miss_tokens\":999,\"total_tokens\":12}" },
        Case{ .dialect = .deepseek_chat, .source = "{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_cache_hit_tokens\":7,\"prompt_cache_miss_tokens\":3,\"total_tokens\":1.5}" },
    }) |case| {
        var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
        defer arena.deinit();
        const value = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), case.source, .{});
        try std.testing.expectError(error.InvalidUsage, parseUsage(case.dialect, value.object));
    }
}

test "DeepSeek inference-resource terminal failure never emits done" {
    const Capture = struct {
        failed: usize = 0,
        done: usize = 0,
        category: ?protocol.ProviderFailureCategory = null,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            switch (event) {
                .failed => |failure| {
                    capture.failed += 1;
                    capture.category = failure.provider_failure.?.category;
                },
                .done => capture.done += 1,
                else => {},
            }
        }
    };
    const model = protocol.Model{ .id = "m", .api = "deepseek-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "deepseek-chat", .deepseek_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"insufficient_system_resource\"}]}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "[DONE]" });
    try decoder.finish();
    try std.testing.expectEqual(@as(usize, 1), capture.failed);
    try std.testing.expectEqual(protocol.ProviderFailureCategory.transient_provider, capture.category.?);
    try std.testing.expectEqual(@as(usize, 0), capture.done);
}

test "terminal usage events do not become visible before their choice is validated" {
    const Capture = struct {
        starts: usize = 0,
        usage: usize = 0,
        failed: usize = 0,
        category: ?protocol.ProviderFailureCategory = null,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            switch (event) {
                .start => capture.starts += 1,
                .usage => capture.usage += 1,
                .failed => |failure| {
                    capture.failed += 1;
                    capture.category = failure.provider_failure.?.category;
                },
                else => {},
            }
        }
    };
    const model = protocol.Model{ .id = "m", .api = "deepseek-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "deepseek-chat", .deepseek_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_cache_hit_tokens\":7,\"prompt_cache_miss_tokens\":3,\"total_tokens\":12},\"choices\":[{\"delta\":{},\"finish_reason\":\"insufficient_system_resource\"}]}" });
    try std.testing.expectEqual(@as(usize, 0), capture.starts);
    try std.testing.expectEqual(@as(usize, 0), capture.usage);
    try std.testing.expectEqual(@as(usize, 1), capture.failed);
    try std.testing.expectEqual(protocol.ProviderFailureCategory.transient_provider, capture.category.?);

    const openai_model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var malformed_capture = Capture{};
    var malformed_callback_attempted = false;
    var malformed = StreamDecoder.init(std.testing.allocator, &openai_model, "openai-chat", .openai_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &malformed_capture, Capture.receive, &malformed_callback_attempted);
    defer malformed.deinit();
    try decoderEvent(&malformed, .{ .event = "message", .data = "{\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3},\"choices\":[{\"delta\":{\"content\":123},\"finish_reason\":\"stop\"}]}" });
    try std.testing.expectEqual(@as(usize, 0), malformed_capture.starts);
    try std.testing.expectEqual(@as(usize, 0), malformed_capture.usage);
    try std.testing.expectEqual(@as(usize, 1), malformed_capture.failed);
    try std.testing.expectEqual(protocol.ProviderFailureCategory.malformed_response, malformed_capture.category.?);
}

test "content filters and post-finish choices are non-success terminals" {
    const Capture = struct {
        failed: usize = 0,
        done: usize = 0,
        text: usize = 0,
        category: ?protocol.ProviderFailureCategory = null,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            switch (event) {
                .failed => |failure| {
                    capture.failed += 1;
                    capture.category = failure.provider_failure.?.category;
                },
                .done => capture.done += 1,
                .text_delta => capture.text += 1,
                else => {},
            }
        }
    };
    const Case = struct { dialect: Dialect, api: []const u8 };
    inline for (.{
        Case{ .dialect = .openai_chat, .api = "openai-chat" },
        Case{ .dialect = .openrouter_chat, .api = "openrouter-chat" },
        Case{ .dialect = .deepseek_chat, .api = "deepseek-chat" },
    }) |case| {
        const model = protocol.Model{ .id = "m", .api = case.api, .provider = "p", .context_window = 4096, .max_tokens = 256 };
        var capture = Capture{};
        var callback_attempted = false;
        var decoder = StreamDecoder.init(std.testing.allocator, &model, case.api, case.dialect, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
        defer decoder.deinit();
        try decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}" });
        try decoderEvent(&decoder, .{ .event = "message", .data = "[DONE]" });
        try decoder.finish();
        try std.testing.expectEqual(@as(usize, 1), capture.failed);
        try std.testing.expectEqual(protocol.ProviderFailureCategory.provider, capture.category.?);
        try std.testing.expectEqual(@as(usize, 0), capture.done);
    }

    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "openai-chat", .openai_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"content\":\"first\"},\"finish_reason\":\"stop\"}]}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"content\":\"smuggled\"}}]}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "[DONE]" });
    try decoder.finish();
    try std.testing.expectEqual(@as(usize, 1), capture.failed);
    try std.testing.expectEqual(protocol.ProviderFailureCategory.malformed_response, capture.category.?);
    try std.testing.expectEqual(@as(usize, 0), capture.done);
    try std.testing.expectEqual(@as(usize, 1), capture.text);
}

test "delta content and reasoning fields require nullable strings" {
    const Capture = struct {
        failed: usize = 0,
        malformed: bool = false,
        done: usize = 0,
        starts: usize = 0,
        text: usize = 0,
        thinking: usize = 0,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            switch (event) {
                .failed => |failure| {
                    capture.failed += 1;
                    capture.malformed = failure.provider_failure.?.category == .malformed_response;
                },
                .done => capture.done += 1,
                .start => capture.starts += 1,
                .text_delta => capture.text += 1,
                .thinking_delta => capture.thinking += 1,
                else => {},
            }
        }
    };
    const Case = struct { dialect: Dialect, api: []const u8, invalid: []const u8, nullable: []const u8, string: []const u8, expects_thinking: bool };
    const cases = [_]Case{
        .{ .dialect = .openai_chat, .api = "openai-chat", .invalid = "{\"choices\":[{\"delta\":{\"content\":123},\"finish_reason\":\"stop\"}]}", .nullable = "{\"choices\":[{\"delta\":{\"content\":null}}]}", .string = "{\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}", .expects_thinking = false },
        .{ .dialect = .openrouter_chat, .api = "openrouter-chat", .invalid = "{\"choices\":[{\"delta\":{\"reasoning\":123},\"finish_reason\":\"stop\"}]}", .nullable = "{\"choices\":[{\"delta\":{\"reasoning\":null}}]}", .string = "{\"choices\":[{\"delta\":{\"reasoning\":\"think\"},\"finish_reason\":\"stop\"}]}", .expects_thinking = true },
        .{ .dialect = .deepseek_chat, .api = "deepseek-chat", .invalid = "{\"choices\":[{\"delta\":{\"reasoning_content\":123},\"finish_reason\":\"stop\"}]}", .nullable = "{\"choices\":[{\"delta\":{\"reasoning_content\":null}}]}", .string = "{\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"},\"finish_reason\":\"stop\"}]}", .expects_thinking = true },
    };
    for (cases) |case| {
        const model = protocol.Model{ .id = "m", .api = case.api, .provider = "p", .context_window = 4096, .max_tokens = 256 };
        var invalid_capture = Capture{};
        var invalid_callback_attempted = false;
        var invalid_decoder = StreamDecoder.init(std.testing.allocator, &model, case.api, case.dialect, 4096, 64 * 1024, 200, null, "secret", &.{}, &invalid_capture, Capture.receive, &invalid_callback_attempted);
        defer invalid_decoder.deinit();
        try decoderEvent(&invalid_decoder, .{ .event = "message", .data = case.invalid });
        try std.testing.expectEqual(@as(usize, 1), invalid_capture.failed);
        try std.testing.expect(invalid_capture.malformed);
        try std.testing.expectEqual(@as(usize, 0), invalid_capture.done);
        try std.testing.expectEqual(@as(usize, 0), invalid_capture.starts);

        var role_capture = Capture{};
        var role_callback_attempted = false;
        var role_decoder = StreamDecoder.init(std.testing.allocator, &model, case.api, case.dialect, 4096, 64 * 1024, 200, null, "secret", &.{}, &role_capture, Capture.receive, &role_callback_attempted);
        defer role_decoder.deinit();
        try decoderEvent(&role_decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"role\":\"user\"}}]}" });
        try std.testing.expectEqual(@as(usize, 1), role_capture.failed);
        try std.testing.expect(role_capture.malformed);
        try std.testing.expectEqual(@as(usize, 0), role_capture.done);
        try std.testing.expectEqual(@as(usize, 0), role_capture.starts);

        var tool_type_capture = Capture{};
        var tool_type_callback_attempted = false;
        var tool_type_decoder = StreamDecoder.init(std.testing.allocator, &model, case.api, case.dialect, 4096, 64 * 1024, 200, null, "secret", &.{}, &tool_type_capture, Capture.receive, &tool_type_callback_attempted);
        defer tool_type_decoder.deinit();
        try decoderEvent(&tool_type_decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"type\":\"other\",\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]}}]}" });
        try std.testing.expectEqual(@as(usize, 1), tool_type_capture.failed);
        try std.testing.expect(tool_type_capture.malformed);
        try std.testing.expectEqual(@as(usize, 0), tool_type_capture.done);
        try std.testing.expectEqual(@as(usize, 0), tool_type_capture.starts);

        var missing_capture = Capture{};
        var missing_callback_attempted = false;
        var missing_decoder = StreamDecoder.init(std.testing.allocator, &model, case.api, case.dialect, 4096, 64 * 1024, 200, null, "secret", &.{}, &missing_capture, Capture.receive, &missing_callback_attempted);
        defer missing_decoder.deinit();
        try decoderEvent(&missing_decoder, .{ .event = "message", .data = "{\"choices\":[{\"finish_reason\":\"stop\"}]}" });
        try std.testing.expectEqual(@as(usize, 1), missing_capture.failed);
        try std.testing.expect(missing_capture.malformed);
        try std.testing.expectEqual(@as(usize, 0), missing_capture.done);

        var valid_capture = Capture{};
        var valid_callback_attempted = false;
        var valid_decoder = StreamDecoder.init(std.testing.allocator, &model, case.api, case.dialect, 4096, 64 * 1024, 200, null, "secret", &.{}, &valid_capture, Capture.receive, &valid_callback_attempted);
        defer valid_decoder.deinit();
        try decoderEvent(&valid_decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}" });
        try decoderEvent(&valid_decoder, .{ .event = "message", .data = case.nullable });
        try decoderEvent(&valid_decoder, .{ .event = "message", .data = case.string });
        try decoderEvent(&valid_decoder, .{ .event = "message", .data = "[DONE]" });
        try valid_decoder.finish();
        try std.testing.expectEqual(@as(usize, 0), valid_capture.failed);
        try std.testing.expectEqual(@as(usize, 1), valid_capture.done);
        try std.testing.expectEqual(@as(usize, 1), if (case.expects_thinking) valid_capture.thinking else valid_capture.text);
    }
}

test "tool fragments accept nullable optional fields and reject malformed shapes before visibility" {
    const Capture = struct {
        starts: usize = 0,
        tool_starts: usize = 0,
        tool_deltas: usize = 0,
        tool_ends: usize = 0,
        done: usize = 0,
        failed: usize = 0,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            switch (event) {
                .start => capture.starts += 1,
                .tool_call_start => capture.tool_starts += 1,
                .tool_call_delta => capture.tool_deltas += 1,
                .tool_call_end => capture.tool_ends += 1,
                .done => capture.done += 1,
                .failed => capture.failed += 1,
                else => {},
            }
        }
    };
    const Case = struct { dialect: Dialect, api: []const u8 };
    inline for (.{
        Case{ .dialect = .openai_chat, .api = "openai-chat" },
        Case{ .dialect = .openrouter_chat, .api = "openrouter-chat" },
    }) |case| {
        const model = protocol.Model{ .id = "m", .api = case.api, .provider = "p", .context_window = 4096, .max_tokens = 256 };
        var capture = Capture{};
        var callback_attempted = false;
        var decoder = StreamDecoder.init(std.testing.allocator, &model, case.api, case.dialect, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
        defer decoder.deinit();
        try decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\"}]}}]}" });
        try decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]}}]}" });
        try decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}" });
        try decoderEvent(&decoder, .{ .event = "message", .data = "[DONE]" });
        try decoder.finish();
        try std.testing.expectEqual(@as(usize, 1), capture.starts);
        try std.testing.expectEqual(@as(usize, 1), capture.tool_starts);
        try std.testing.expectEqual(@as(usize, 1), capture.tool_deltas);
        try std.testing.expectEqual(@as(usize, 1), capture.tool_ends);
        try std.testing.expectEqual(@as(usize, 1), capture.done);
        try std.testing.expectEqual(@as(usize, 0), capture.failed);

        var nullable_capture = Capture{};
        var nullable_callback_attempted = false;
        var nullable = StreamDecoder.init(std.testing.allocator, &model, case.api, case.dialect, 4096, 64 * 1024, 200, null, "secret", &.{}, &nullable_capture, Capture.receive, &nullable_callback_attempted);
        defer nullable.deinit();
        try decoderEvent(&nullable, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":null,\"type\":null,\"function\":null}]}}]}" });
        try decoderEvent(&nullable, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]}}]}" });
        try decoderEvent(&nullable, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}" });
        try decoderEvent(&nullable, .{ .event = "message", .data = "[DONE]" });
        try nullable.finish();
        try std.testing.expectEqual(@as(usize, 0), nullable_capture.failed);
        try std.testing.expectEqual(@as(usize, 1), nullable_capture.done);

        var duplicate_capture = Capture{};
        var duplicate_callback_attempted = false;
        var duplicate = StreamDecoder.init(std.testing.allocator, &model, case.api, case.dialect, 4096, 64 * 1024, 200, null, "secret", &.{}, &duplicate_capture, Capture.receive, &duplicate_callback_attempted);
        defer duplicate.deinit();
        try decoderEvent(&duplicate, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"duplicate\",\"function\":{\"name\":\"one\",\"arguments\":\"{}\"}},{\"index\":1,\"id\":\"duplicate\",\"function\":{\"name\":\"two\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}" });
        try decoderEvent(&duplicate, .{ .event = "message", .data = "[DONE]" });
        try duplicate.finish();
        try std.testing.expectEqual(@as(usize, 2), duplicate_capture.tool_starts);
        try std.testing.expectEqual(@as(usize, 0), duplicate_capture.tool_ends);
        try std.testing.expectEqual(@as(usize, 0), duplicate_capture.done);
        try std.testing.expectEqual(@as(usize, 1), duplicate_capture.failed);

        inline for (.{
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":123}]}}]}",
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":null}]}}]}",
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":null,\"function\":null}]}}]}",
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":16,\"function\":null}]}}]}",
        }) |malformed_event| {
            var malformed_capture = Capture{};
            var malformed_callback_attempted = false;
            var malformed = StreamDecoder.init(std.testing.allocator, &model, case.api, case.dialect, 4096, 64 * 1024, 200, null, "secret", &.{}, &malformed_capture, Capture.receive, &malformed_callback_attempted);
            defer malformed.deinit();
            try decoderEvent(&malformed, .{ .event = "message", .data = malformed_event });
            try std.testing.expectEqual(@as(usize, 1), malformed_capture.failed);
            try std.testing.expectEqual(@as(usize, 0), malformed_capture.starts);
            try std.testing.expectEqual(@as(usize, 0), malformed_capture.tool_starts);
        }
    }
}

test "structured SSE errors retain code and type for classification" {
    const Capture = struct {
        category: ?protocol.ProviderFailureCategory = null,
        code_ok: bool = false,
        type_ok: bool = false,
        status: ?u16 = null,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            if (event == .failed) {
                const failure = event.failed.provider_failure.?;
                capture.category = failure.category;
                capture.code_ok = std.mem.eql(u8, failure.provider_code.?, "rate_limit_exceeded");
                capture.type_ok = std.mem.eql(u8, failure.provider_type.?, "rate_limit_error");
                capture.status = failure.http_status;
            }
        }
    };
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "openai-chat", .openai_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"error\":{\"code\":\"rate_limit_exceeded\",\"type\":\"rate_limit_error\",\"message\":\"retry later\"}}" });
    try std.testing.expectEqual(protocol.ProviderFailureCategory.rate_limited, capture.category.?);
    try std.testing.expect(capture.code_ok and capture.type_ok);
    try std.testing.expectEqual(@as(?u16, 200), capture.status);
}

test "SSE diagnostics preserve the Retry-After response header" {
    const Capture = struct {
        retry_after_ms: ?u64 = null,
        category: ?protocol.ProviderFailureCategory = null,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            if (event == .failed) {
                capture.retry_after_ms = event.failed.provider_failure.?.retry_after_ms;
                capture.category = event.failed.provider_failure.?.category;
            }
        }
    };
    const model = protocol.Model{ .id = "m", .api = "openrouter-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "openrouter-chat", .openrouter_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    decoder.retry_after_ms = 2_000;
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"error\":{\"code\":429,\"message\":\"Too Many Requests\"}}" });
    try std.testing.expectEqual(protocol.ProviderFailureCategory.rate_limited, capture.category.?);
    try std.testing.expectEqual(@as(?u64, 2_000), capture.retry_after_ms);
}

test "numeric SSE provider codes classify without replacing transport status" {
    const Capture = struct {
        category: ?protocol.ProviderFailureCategory = null,
        code_ok: bool = false,
        status: ?u16 = null,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            if (event == .failed) {
                const failure = event.failed.provider_failure.?;
                capture.category = failure.category;
                capture.code_ok = std.mem.eql(u8, failure.provider_code.?, "429");
                capture.status = failure.http_status;
            }
        }
    };
    const model = protocol.Model{ .id = "m", .api = "openrouter-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "openrouter-chat", .openrouter_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"error\":{\"code\":429,\"message\":\"Too Many Requests\"}}" });
    try std.testing.expectEqual(protocol.ProviderFailureCategory.rate_limited, capture.category.?);
    try std.testing.expect(capture.code_ok);
    try std.testing.expectEqual(@as(?u16, 200), capture.status);
}

test "Retry-After permits immediate retry and parses IMF dates against an absolute time" {
    const target = parseHttpDate("Sun, 06 Nov 1994 08:49:37 GMT") orelse return error.ExpectedImfDate;
    try std.testing.expectEqual(@as(?u64, 0), parseRetryAfter("0", target));
    try std.testing.expectEqual(@as(?u64, 2_000), parseRetryAfter("Sun, 06 Nov 1994 08:49:37 GMT", target - 2));
    try sleepRetry(std.testing.io, retryDelayMs(.{ .max_retries = 1, .max_delay_ms = 1_000 }, 0, 0), null);
}

test "DeepSeek tool replay keeps an explicit empty content field without text" {
    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    const arguments = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), "{\"path\":\"README.md\"}", .{});
    const parts = [_]protocol.AssistantContent{
        .{ .thinking = .{ .thinking = "inspect file" } },
        .{ .tool_call = .{ .id = "call_1", .name = "read_file", .arguments = arguments } },
    };
    const messages = [_]protocol.Message{.{ .assistant = .{ .content = &parts, .api = "deepseek-chat", .provider = "deepseek", .model = "m", .stop_reason = .tool_use, .timestamp_ms = 0 } }};
    const model = protocol.Model{ .id = "m", .api = "deepseek-chat", .provider = "deepseek", .context_window = 4096, .max_tokens = 256, .reasoning = true };
    var rendered = try renderRequest(std.testing.allocator, &model, &.{ .system_prompt = "", .messages = &messages }, .{}, .deepseek_chat);
    defer rendered.deinit();
    try std.testing.expectEqualStrings(
        "{\"model\":\"m\",\"messages\":[{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"inspect file\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}}]}],\"max_tokens\":256,\"stream\":true}",
        rendered.bytes,
    );
}

test "OpenRouter session ids obey the documented 256 byte input limit" {
    const session_id = "x" ** 257;
    const model = protocol.Model{ .id = "m", .api = "openrouter-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    try std.testing.expectError(error.RequestTooLarge, renderRequest(std.testing.allocator, &model, &.{ .system_prompt = "", .messages = &.{} }, .{ .session_id = session_id }, .openrouter_chat));
}

test "local provider failures do not fabricate an HTTP success status" {
    const Capture = struct {
        status: ?u16 = 999,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            if (event == .failed) capture.status = event.failed.provider_failure.?.http_status;
        }
    };
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    try emitFailure(&capture, Capture.receive, &model, .authentication, null, "provider credential is unavailable", null, null, null, null);
    try std.testing.expectEqual(@as(?u16, null), capture.status);
}

test "provider configuration rejects OpenAI cache wire modes on other dialects" {
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    const models = [_]ModelCapability{.{ .id = "m", .context_window = 4096, .output_limit = 128, .short_cache_retention = .prompt_cache_options_ttl_30m }};
    try std.testing.expectError(error.InvalidModelCapability, ProviderRegistry.init(std.testing.allocator, std.testing.io, &environment, &.{.{ .id = "p", .endpoint = "http://localhost:1/v1", .credential_ref = "P_KEY", .dialect = .deepseek_chat, .models = &models, .retry = .{ .deadline_ms = 5_000 } }}));
}

test "literal OpenAI OpenRouter and DeepSeek request snapshots pin exact bytes and cache identity" {
    const Snapshot = struct { dialect: Dialect, bytes: []const u8, prefix_len: usize, prefix_hash: []const u8, request_hash: []const u8 };
    const content = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const messages = [_]protocol.Message{.{ .user = .{ .content = &content, .timestamp_ms = 0 } }};
    const model = protocol.Model{ .id = "snapshot-model", .api = "openai-chat", .provider = "fixture", .context_window = 4096, .max_tokens = 128, .reasoning = true };
    const context = protocol.Context{ .system_prompt = "stable", .messages = &messages, .cacheable_message_count = 1 };
    const options = protocol.StreamOptions{ .max_tokens = 64, .reasoning = .high };
    const snapshots = [_]Snapshot{
        .{ .dialect = .openai_chat, .bytes = "{\"model\":\"snapshot-model\",\"messages\":[{\"role\":\"system\",\"content\":\"stable\"},{\"role\":\"user\",\"content\":\"hello\"}],\"max_completion_tokens\":64,\"stream\":true,\"reasoning_effort\":\"high\",\"stream_options\":{\"include_usage\":true}}", .prefix_len = 108, .prefix_hash = "d4bf83c2d964fb1799cf0b86efc28aa41c452ad7f1d9a6ce15b88e20cb1d97e3", .request_hash = "0a698af91eab0278f26e6aac8a52b5f20e10d9d400383d7ad8523e024c18c88a" },
        .{ .dialect = .openrouter_chat, .bytes = "{\"model\":\"snapshot-model\",\"messages\":[{\"role\":\"system\",\"content\":\"stable\"},{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":64,\"stream\":true,\"reasoning\":{\"effort\":\"high\"},\"stream_options\":{\"include_usage\":true}}", .prefix_len = 108, .prefix_hash = "d4bf83c2d964fb1799cf0b86efc28aa41c452ad7f1d9a6ce15b88e20cb1d97e3", .request_hash = "e2d50036e24c5a2afd4c53b07102c3823dc35a6a039de065cc5efe1110b7e942" },
        .{ .dialect = .deepseek_chat, .bytes = "{\"model\":\"snapshot-model\",\"messages\":[{\"role\":\"system\",\"content\":\"stable\"},{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":64,\"stream\":true,\"thinking\":{\"type\":\"enabled\"},\"reasoning_effort\":\"high\"}", .prefix_len = 108, .prefix_hash = "d4bf83c2d964fb1799cf0b86efc28aa41c452ad7f1d9a6ce15b88e20cb1d97e3", .request_hash = "8ebd59d178c91a79412abcd65df451fc8ded2c0e02c42048cf4bee1158a27eeb" },
    };
    for (snapshots) |snapshot| {
        var rendered = try renderRequest(std.testing.allocator, &model, &context, options, snapshot.dialect);
        defer rendered.deinit();
        var request_hash: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
        std.crypto.hash.sha2.Sha256.hash(rendered.bytes, &request_hash, .{});
        var expected_prefix_hash: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
        var expected_request_hash: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
        _ = try std.fmt.hexToBytes(&expected_prefix_hash, snapshot.prefix_hash);
        _ = try std.fmt.hexToBytes(&expected_request_hash, snapshot.request_hash);
        try std.testing.expectEqualStrings(snapshot.bytes, rendered.bytes);
        try std.testing.expectEqual(snapshot.prefix_len, rendered.cache_prefix_len);
        try std.testing.expectEqualSlices(u8, &expected_prefix_hash, &rendered.cache_prefix_hash);
        try std.testing.expectEqualSlices(u8, &expected_request_hash, &request_hash);
    }
}

const TailCapture = struct {
    text: std.ArrayList(u8) = .empty,
    thinking: std.ArrayList(u8) = .empty,
    usage_events: usize = 0,
    done: usize = 0,
    failed: usize = 0,
    category: ?protocol.ProviderFailureCategory = null,
    message: [256]u8 = undefined,
    message_len: usize = 0,
    usage: protocol.Usage = .{},
    stop: ?protocol.StopReason = null,
    fn deinit(self: *@This()) void {
        self.text.deinit(std.testing.allocator);
        self.thinking.deinit(std.testing.allocator);
    }
    fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
        const capture: *@This() = @ptrCast(@alignCast(raw));
        switch (event) {
            .text_delta => |delta| try capture.text.appendSlice(std.testing.allocator, delta.delta),
            .thinking_delta => |delta| try capture.thinking.appendSlice(std.testing.allocator, delta.delta),
            .usage => capture.usage_events += 1,
            .done => |done| {
                capture.done += 1;
                capture.usage = done.usage;
                capture.stop = done.stop_reason;
            },
            .failed => |failure| {
                capture.failed += 1;
                capture.category = failure.provider_failure.?.category;
                const message = failure.provider_failure.?.redacted_message;
                capture.message_len = copyBounded(&capture.message, message);
            },
            else => {},
        }
    }
};

fn runTailFixture(dialect: Dialect, fixture: []const u8, capture: *TailCapture) !void {
    const api = apiName(dialect);
    const model = protocol.Model{ .id = "m", .api = api, .provider = "p", .context_window = 4096, .max_tokens = 256, .reasoning = true };
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, api, dialect, 64 * 1024, 64 * 1024, 200, null, "secret", &.{}, capture, TailCapture.receive, &callback_attempted);
    defer decoder.deinit();
    var parser = SseParser.init(std.testing.allocator, 64 * 1024, &decoder, decoderEvent);
    defer parser.deinit();
    try parser.feed(fixture);
    try parser.finish();
    try decoder.finish();
}

// Shape captured from OpenRouter's documented streaming contract (final usage chunk repeats a
// content-free choice with the finish reason) and from real OpenRouter SSE bodies, which carry
// processing comments, `reasoning`/`reasoning_details`, `native_finish_reason`, `logprobs`, and
// usage cost fields on every tail chunk.
const openrouter_free_router_stream =
    ": OPENROUTER PROCESSING\n\n" ++
    ": OPENROUTER PROCESSING\n\n" ++
    "data: {\"id\":\"gen-1\",\"provider\":\"Chutes\",\"model\":\"deepseek/deepseek-r1-0528:free\",\"object\":\"chat.completion.chunk\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":\"Let me think.\",\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"Let me think.\",\"format\":\"unknown\",\"index\":0}]},\"finish_reason\":null,\"native_finish_reason\":null,\"logprobs\":null}]}\n\n" ++
    "data: {\"id\":\"gen-1\",\"provider\":\"Chutes\",\"model\":\"deepseek/deepseek-r1-0528:free\",\"object\":\"chat.completion.chunk\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hello\",\"reasoning\":null,\"reasoning_details\":[]},\"finish_reason\":null,\"native_finish_reason\":null,\"logprobs\":null}]}\n\n" ++
    ": OPENROUTER PROCESSING\n\n" ++
    "data: {\"id\":\"gen-1\",\"provider\":\"Chutes\",\"model\":\"deepseek/deepseek-r1-0528:free\",\"object\":\"chat.completion.chunk\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\" world\",\"reasoning\":null,\"reasoning_details\":[]},\"finish_reason\":null,\"native_finish_reason\":null,\"logprobs\":null}]}\n\n" ++
    "data: {\"id\":\"gen-1\",\"provider\":\"Chutes\",\"model\":\"deepseek/deepseek-r1-0528:free\",\"object\":\"chat.completion.chunk\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":null,\"reasoning_details\":[]},\"finish_reason\":\"stop\",\"native_finish_reason\":\"stop\",\"logprobs\":null}]}\n\n" ++
    "data: {\"id\":\"gen-1\",\"provider\":\"Chutes\",\"model\":\"deepseek/deepseek-r1-0528:free\",\"object\":\"chat.completion.chunk\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":null,\"reasoning_details\":[]},\"finish_reason\":\"stop\",\"native_finish_reason\":\"stop\",\"logprobs\":null}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":20,\"total_tokens\":32,\"cost\":0,\"is_byok\":false,\"prompt_tokens_details\":{\"cached_tokens\":0,\"audio_tokens\":0},\"cost_details\":{\"upstream_inference_cost\":null,\"upstream_inference_prompt_cost\":0,\"upstream_inference_completion_cost\":0},\"completion_tokens_details\":{\"reasoning_tokens\":8,\"image_tokens\":0}}}\n\n" ++
    "data: [DONE]\n\n";

test "OpenRouter free router tail with role and empty content completes with usage" {
    var capture = TailCapture{};
    defer capture.deinit();
    try runTailFixture(.openrouter_chat, openrouter_free_router_stream, &capture);
    try std.testing.expectEqual(@as(usize, 0), capture.failed);
    try std.testing.expectEqual(@as(usize, 1), capture.done);
    try std.testing.expectEqualStrings("Hello world", capture.text.items);
    try std.testing.expectEqualStrings("Let me think.", capture.thinking.items);
    try std.testing.expectEqual(@as(usize, 1), capture.usage_events);
    try std.testing.expectEqual(@as(u64, 12), capture.usage.input);
    try std.testing.expectEqual(@as(u64, 20), capture.usage.output);
    try std.testing.expectEqual(@as(u64, 32), capture.usage.total_tokens);
    try std.testing.expectEqual(protocol.StopReason.stop, capture.stop.?);
}

test "inert post-finish chunks are accepted for every OpenAI-compatible dialect" {
    const prefix = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";
    const deepseek_usage = "{\"prompt_tokens\":3,\"completion_tokens\":2,\"prompt_cache_hit_tokens\":1,\"prompt_cache_miss_tokens\":2,\"total_tokens\":5}";
    const plain_usage = "{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}";
    const tails = [_][]const u8{
        // Repeated finish without usage, then the usage chunk with a content-free role delta.
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null,\"reasoning\":null,\"reasoning_content\":null,\"reasoning_details\":null,\"tool_calls\":[],\"refusal\":null},\"finish_reason\":\"stop\",\"native_finish_reason\":\"STOP\",\"logprobs\":null}],\"usage\":USAGE}\n\ndata: [DONE]\n\n",
        // Choice without a delta and without an index, finish reason null.
        "data: {\"choices\":[{\"finish_reason\":null}],\"usage\":USAGE}\n\ndata: [DONE]\n\n",
        // Usage-only chunk with no choices key, then a metadata-only chunk.
        "data: {\"id\":\"gen-1\",\"usage\":USAGE}\n\ndata: {\"id\":\"gen-1\",\"provider\":\"x\"}\n\ndata: [DONE]\n\n",
        // Empty choices plus usage, followed by an identical duplicate.
        "data: {\"choices\":[],\"usage\":USAGE}\n\ndata: {\"choices\":[],\"usage\":USAGE}\n\ndata: [DONE]\n\n",
    };
    inline for (.{ Dialect.openai_chat, Dialect.openrouter_chat, Dialect.deepseek_chat }) |dialect| {
        const usage = if (dialect == .deepseek_chat) deepseek_usage else plain_usage;
        for (tails) |tail| {
            const expanded = try std.mem.replaceOwned(u8, std.testing.allocator, tail, "USAGE", usage);
            defer std.testing.allocator.free(expanded);
            const fixture = try std.mem.concat(std.testing.allocator, u8, &.{ prefix, expanded });
            defer std.testing.allocator.free(fixture);
            var capture = TailCapture{};
            defer capture.deinit();
            try runTailFixture(dialect, fixture, &capture);
            try std.testing.expectEqual(@as(usize, 0), capture.failed);
            try std.testing.expectEqual(@as(usize, 1), capture.done);
            try std.testing.expectEqualStrings("ok", capture.text.items);
            try std.testing.expectEqual(@as(usize, 1), capture.usage_events);
            try std.testing.expectEqual(@as(u64, 3), capture.usage.input);
            try std.testing.expectEqual(@as(u64, 2), capture.usage.output);
            try std.testing.expectEqual(@as(u64, 5), capture.usage.total_tokens);
        }
    }
}

test "post-finish chunks that carry new output or a changed finish reason stay malformed" {
    const prefix = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";
    const usage = ",\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}";
    const tails = [_][]const u8{
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"smuggled\"},\"finish_reason\":\"stop\"}]" ++ usage ++ "}\n\ndata: [DONE]\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"late\"}}]}\n\ndata: [DONE]\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"late\"}}]}\n\ndata: [DONE]\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"late\"}]}}]}\n\ndata: [DONE]\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"t\",\"function\":{\"name\":\"n\",\"arguments\":\"{}\"}}]}}]}\n\ndata: [DONE]\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"user\"}}]}\n\ndata: [DONE]\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]" ++ usage ++ "}\n\ndata: [DONE]\n\n",
        "data: {\"choices\":[{\"index\":1,\"delta\":{},\"finish_reason\":\"stop\"}]" ++ usage ++ "}\n\ndata: [DONE]\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{}},{\"index\":0,\"delta\":{}}]}\n\ndata: [DONE]\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":\"text\"}]}\n\ndata: [DONE]\n\n",
        "data: {\"choices\":{}}\n\ndata: [DONE]\n\n",
    };
    inline for (.{ Dialect.openai_chat, Dialect.openrouter_chat, Dialect.deepseek_chat }) |dialect| {
        for (tails) |tail| {
            const fixture = try std.mem.concat(std.testing.allocator, u8, &.{ prefix, tail });
            defer std.testing.allocator.free(fixture);
            var capture = TailCapture{};
            defer capture.deinit();
            try runTailFixture(dialect, fixture, &capture);
            try std.testing.expectEqual(@as(usize, 1), capture.failed);
            try std.testing.expectEqual(protocol.ProviderFailureCategory.malformed_response, capture.category.?);
            try std.testing.expectEqual(@as(usize, 0), capture.done);
            try std.testing.expectEqualStrings("ok", capture.text.items);
        }
    }
}

test "error finish reasons surface as provider failures instead of unreadable responses" {
    const streams = [_][]const u8{
        // Normalized OpenRouter finish reason without a top-level error object.
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":\"error\",\"native_finish_reason\":\"error\"}]}\n\ndata: [DONE]\n\n",
        // Error reported only in the post-finish tail.
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"stop\"}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":\"error\"}]}\n\ndata: [DONE]\n\n",
    };
    inline for (.{ Dialect.openai_chat, Dialect.openrouter_chat, Dialect.deepseek_chat }) |dialect| {
        for (streams) |stream| {
            var capture = TailCapture{};
            defer capture.deinit();
            try runTailFixture(dialect, stream, &capture);
            try std.testing.expectEqual(@as(usize, 1), capture.failed);
            try std.testing.expectEqual(protocol.ProviderFailureCategory.provider, capture.category.?);
            try std.testing.expectEqual(@as(usize, 0), capture.done);
            try std.testing.expect(std.mem.indexOf(u8, capture.message[0..capture.message_len], "ended the response with an error") != null);
        }
    }
}

test "usage totals and later usage reports follow the provider instead of failing" {
    const stream =
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":99}}\n\n" ++
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" ++
        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"prompt_tokens_details\":null}}\n\n" ++
        "data: [DONE]\n\n";
    var capture = TailCapture{};
    defer capture.deinit();
    try runTailFixture(.openrouter_chat, stream, &capture);
    try std.testing.expectEqual(@as(usize, 0), capture.failed);
    try std.testing.expectEqual(@as(usize, 2), capture.usage_events);
    try std.testing.expectEqual(@as(u64, 3), capture.usage.input);
    try std.testing.expectEqual(@as(u64, 4), capture.usage.output);
    try std.testing.expectEqual(@as(u64, 7), capture.usage.total_tokens);

    inline for (.{
        "{\"prompt_tokens\":\"3\",\"completion_tokens\":1,\"total_tokens\":4}",
        "{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":\"4\"}",
        "{\"prompt_tokens\":3,\"total_tokens\":4}",
    }) |source| {
        var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
        defer arena.deinit();
        const value = try std.json.parseFromSliceLeaky(std.json.Value, arena.allocator(), source, .{});
        try std.testing.expectError(error.InvalidUsage, parseUsage(.openrouter_chat, value.object));
    }
}

test "tool calls with empty arguments or a stop finish reason still settle as tool use" {
    const Capture = struct {
        stop: ?protocol.StopReason = null,
        arguments_are_object: bool = false,
        failed: usize = 0,
        fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
            const capture: *@This() = @ptrCast(@alignCast(raw));
            switch (event) {
                .done => |message| {
                    capture.stop = message.stop_reason;
                    for (message.content) |part| switch (part) {
                        .tool_call => |call| capture.arguments_are_object = call.arguments == .object,
                        else => {},
                    };
                },
                .failed => capture.failed += 1,
                else => {},
            }
        }
    };
    const model = protocol.Model{ .id = "m", .api = "openai-chat", .provider = "p", .context_window = 4096, .max_tokens = 256 };
    var capture = Capture{};
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "openai-chat", .openai_chat, 4096, 64 * 1024, 200, null, "secret", &.{}, &capture, Capture.receive, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"list_files\",\"arguments\":\"\"}}]},\"finish_reason\":\"stop\"}]}" });
    try decoderEvent(&decoder, .{ .event = "message", .data = "[DONE]" });
    try decoder.finish();
    try std.testing.expectEqual(@as(usize, 0), capture.failed);
    try std.testing.expectEqual(protocol.StopReason.tool_use, capture.stop.?);
    try std.testing.expect(capture.arguments_are_object);
}

const AnthropicCapture = struct {
    text: std.ArrayList(u8) = .empty,
    thinking: std.ArrayList(u8) = .empty,
    tool_arguments: std.ArrayList(u8) = .empty,
    content_kinds: [8]u8 = undefined,
    content_count: usize = 0,
    signature_ok: bool = false,
    tool_ok: bool = false,
    usage: protocol.Usage = .{},
    stop: ?protocol.StopReason = null,
    failed: usize = 0,
    category: ?protocol.ProviderFailureCategory = null,
    fn deinit(self: *@This()) void {
        self.text.deinit(std.testing.allocator);
        self.thinking.deinit(std.testing.allocator);
        self.tool_arguments.deinit(std.testing.allocator);
    }
    fn receive(raw: *anyopaque, event: protocol.StreamEvent) !void {
        const capture: *@This() = @ptrCast(@alignCast(raw));
        switch (event) {
            .text_delta => |delta| try capture.text.appendSlice(std.testing.allocator, delta.delta),
            .thinking_delta => |delta| try capture.thinking.appendSlice(std.testing.allocator, delta.delta),
            .tool_call_delta => |delta| try capture.tool_arguments.appendSlice(std.testing.allocator, delta.delta),
            .usage => |usage| capture.usage = usage,
            .done => |message| {
                capture.stop = message.stop_reason;
                capture.content_count = message.content.len;
                for (message.content, 0..) |part, index| switch (part) {
                    .text => capture.content_kinds[index] = 'x',
                    .thinking => |thinking| {
                        capture.content_kinds[index] = 't';
                        capture.signature_ok = std.mem.eql(u8, thinking.signature orelse "", "sig-1sig-2");
                    },
                    .tool_call => |call| {
                        capture.content_kinds[index] = 'c';
                        capture.tool_ok = std.mem.eql(u8, call.id, "toolu_1") and std.mem.eql(u8, call.name, "read_file") and std.mem.eql(u8, call.arguments.object.get("path").?.string, "a.txt");
                    },
                };
            },
            .failed => |message| {
                capture.failed += 1;
                capture.category = message.provider_failure.?.category;
            },
            else => {},
        }
    }
};

test "Anthropic stream decodes thinking, text, streamed tool input, stop reason, and cache usage" {
    const model = protocol.Model{ .id = "claude-test", .api = "anthropic-messages", .provider = "anthropic", .context_window = 200000, .max_tokens = 1024 };
    var capture = AnthropicCapture{};
    defer capture.deinit();
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "anthropic-messages", .anthropic_messages, 64 * 1024, 1024 * 1024, 200, null, "secret", &.{}, &capture, AnthropicCapture.receive, &callback_attempted);
    defer decoder.deinit();
    var parser = SseParser.init(std.testing.allocator, 64 * 1024, &decoder, decoderEvent);
    defer parser.deinit();
    const fixture =
        "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-test\",\"stop_reason\":null,\"usage\":{\"input_tokens\":10,\"cache_creation_input_tokens\":5,\"cache_read_input_tokens\":100,\"output_tokens\":1}}}\n\n" ++
        "event: ping\ndata: {\"type\":\"ping\"}\n\n" ++
        "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}\n\n" ++
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"plan\"}}\n\n" ++
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig-1\"}}\n\n" ++
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig-2\"}}\n\n" ++
        "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" ++
        "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" ++
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}\n\n" ++
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}\n\n" ++
        "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":1}\n\n" ++
        "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_file\",\"input\":{}}}\n\n" ++
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\"}}\n\n" ++
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\": \\\"a\"}}\n\n" ++
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\".txt\\\"}\"}}\n\n" ++
        "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":2}\n\n" ++
        "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":42}}\n\n" ++
        "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
    for (fixture, 0..) |_, index| try parser.feed(fixture[index .. index + 1]);
    try std.testing.expect(decoder.terminated);
    try parser.finish();
    try decoder.finish();
    try std.testing.expectEqual(@as(usize, 0), capture.failed);
    try std.testing.expectEqual(protocol.StopReason.tool_use, capture.stop.?);
    try std.testing.expectEqualStrings("Hello", capture.text.items);
    try std.testing.expectEqualStrings("plan", capture.thinking.items);
    try std.testing.expectEqualStrings("{\"path\": \"a.txt\"}", capture.tool_arguments.items);
    try std.testing.expectEqualStrings("txc", capture.content_kinds[0..capture.content_count]);
    try std.testing.expect(capture.signature_ok and capture.tool_ok);
    try std.testing.expectEqual(protocol.Usage{ .input = 115, .output = 42, .cache_read = 100, .cache_write = 5, .total_tokens = 157 }, capture.usage);
}

test "Anthropic stream errors, refusals, and truncation settle as failures" {
    const model = protocol.Model{ .id = "claude-test", .api = "anthropic-messages", .provider = "anthropic", .context_window = 200000, .max_tokens = 1024 };
    const start = "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}";
    const Case = struct { events: []const []const u8, category: protocol.ProviderFailureCategory };
    const cases = [_]Case{
        .{ .events = &.{ start, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}" }, .category = .transient_provider },
        .{ .events = &.{ start, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"},\"usage\":{\"output_tokens\":0}}" }, .category = .provider },
        .{ .events = &.{ start, "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"orphan\"}}" }, .category = .malformed_response },
        .{ .events = &.{ start, "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}" }, .category = .malformed_response },
    };
    for (cases) |case| {
        var capture = AnthropicCapture{};
        defer capture.deinit();
        var callback_attempted = false;
        var decoder = StreamDecoder.init(std.testing.allocator, &model, "anthropic-messages", .anthropic_messages, 64 * 1024, 1024 * 1024, 200, null, "secret", &.{}, &capture, AnthropicCapture.receive, &callback_attempted);
        defer decoder.deinit();
        for (case.events) |data| try decoderEvent(&decoder, .{ .event = "message", .data = data });
        try std.testing.expectEqual(@as(usize, 1), capture.failed);
        try std.testing.expectEqual(case.category, capture.category.?);
    }
    var capture = AnthropicCapture{};
    defer capture.deinit();
    var callback_attempted = false;
    var decoder = StreamDecoder.init(std.testing.allocator, &model, "anthropic-messages", .anthropic_messages, 64 * 1024, 1024 * 1024, 200, null, "secret", &.{}, &capture, AnthropicCapture.receive, &callback_attempted);
    defer decoder.deinit();
    try decoderEvent(&decoder, .{ .event = "message", .data = start });
    try std.testing.expectError(error.TruncatedProviderResponse, decoder.finish());
}

test "Anthropic request renders system blocks, merged tool results, signed thinking replay, and effort" {
    const schema = try std.json.parseFromSlice(std.json.Value, std.testing.allocator, "{\"type\":\"object\"}", .{});
    defer schema.deinit();
    const tools = [_]protocol.Tool{.{ .name = "read_file", .description = "Read a file", .parameters = schema.value }};
    const arguments = try std.json.parseFromSlice(std.json.Value, std.testing.allocator, "{\"path\":\"a.txt\"}", .{});
    defer arguments.deinit();
    const hello = [_]protocol.UserContent{.{ .text = .{ .text = "hello" } }};
    const next = [_]protocol.UserContent{.{ .text = .{ .text = "next" } }};
    const first_result = [_]protocol.UserContent{.{ .text = .{ .text = "A" } }};
    const second_result = [_]protocol.UserContent{.{ .text = .{ .text = "denied" } }};
    const assistant = [_]protocol.AssistantContent{
        .{ .thinking = .{ .thinking = "foreign reasoning" } },
        .{ .thinking = .{ .thinking = "plan", .signature = "sig" } },
        .{ .text = .{ .text = "Reading." } },
        .{ .tool_call = .{ .id = "toolu_1", .name = "read_file", .arguments = arguments.value } },
        .{ .tool_call = .{ .id = "toolu_2", .name = "read_file", .arguments = arguments.value } },
    };
    const messages = [_]protocol.Message{
        .{ .user = .{ .content = &hello, .timestamp_ms = 0 } },
        .{ .assistant = .{ .content = &assistant, .api = "anthropic-messages", .provider = "anthropic", .model = "claude-test", .stop_reason = .tool_use, .timestamp_ms = 0 } },
        .{ .tool_result = .{ .tool_call_id = "toolu_1", .tool_name = "read_file", .content = &first_result, .is_error = false, .timestamp_ms = 0 } },
        .{ .tool_result = .{ .tool_call_id = "toolu_2", .tool_name = "read_file", .content = &second_result, .is_error = true, .timestamp_ms = 0 } },
        .{ .user = .{ .content = &next, .timestamp_ms = 0 } },
    };
    const lanes = [_][]const u8{ "stable", "", "session" };
    const model = protocol.Model{ .id = "claude-test", .api = "anthropic-messages", .provider = "anthropic", .context_window = 200000, .max_tokens = 1024, .reasoning = true };
    const context = protocol.Context{ .system_prompt = "unused", .system_prompt_lanes = &lanes, .messages = &messages, .cacheable_message_count = 3, .tools = &tools };
    var rendered = try renderRequest(std.testing.allocator, &model, &context, .{ .max_tokens = 512, .reasoning = .xhigh }, .anthropic_messages);
    defer rendered.deinit();
    const expected =
        "{\"model\":\"claude-test\",\"tools\":[{\"name\":\"read_file\",\"description\":\"Read a file\",\"input_schema\":{\"type\":\"object\"}}]," ++
        "\"system\":[{\"type\":\"text\",\"text\":\"stable\"},{\"type\":\"text\",\"text\":\"session\"}]," ++
        "\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}," ++
        "{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"plan\",\"signature\":\"sig\"},{\"type\":\"text\",\"text\":\"Reading.\"},{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_file\",\"input\":{\"path\":\"a.txt\"}},{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"read_file\",\"input\":{\"path\":\"a.txt\"}}]}," ++
        "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"A\"},{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_2\",\"content\":\"denied\",\"is_error\":true},{\"type\":\"text\",\"text\":\"next\"}]}]," ++
        "\"max_tokens\":512,\"stream\":true,\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"xhigh\"},\"cache_control\":{\"type\":\"ephemeral\"}}";
    try std.testing.expectEqualStrings(expected, rendered.bytes);
    // The cache prefix ends after the first tool result, before the user turn closes, so a
    // later tool result or user text appends to it.
    const prefix_end = std.mem.indexOf(u8, expected, "\"content\":\"A\"}").? + "\"content\":\"A\"}".len;
    try std.testing.expectEqual(prefix_end, rendered.cache_prefix_len);
}

test "Anthropic endpoints accept bases with or without the version segment" {
    const allocator = std.testing.allocator;
    inline for (.{ "https://api.anthropic.com", "https://api.anthropic.com/", "https://api.anthropic.com/v1", "https://api.anthropic.com/v1/messages/" }) |base| {
        const url = try anthropicEndpointUrl(allocator, base);
        defer allocator.free(url);
        try std.testing.expectEqualStrings("https://api.anthropic.com/v1/messages", url);
    }
}
