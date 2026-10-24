const std = @import("std");
const builtin = @import("builtin");
const session = @import("session.zig");
const max_summary_bytes: usize = 4 * 1024 * 1024;

pub const Settings = struct {
    enabled: bool = true,
    reserve_tokens: usize = 16_384,
    keep_recent_tokens: usize = 20_000,
};

pub const MeasurementProvenance = struct {
    provider: []const u8 = "",
    model: []const u8 = "",
    canonical_request_schema: []const u8 = "",
    canonical_request_version: u16 = 0,
    token_basis: []const u8 = "",
};

pub const Metrics = struct {
    serialized_bytes: usize,
    provider_input_tokens: usize,
    provenance: MeasurementProvenance = .{},
};

/// A predecessor is one value from the durable epoch projection. Keeping the
/// identifier, folded summary, and provider-derived metrics together prevents
/// callers from pairing an epoch id with an unrelated summary or measurement.
pub const PredecessorEpoch = struct {
    id: []const u8,
    summary: []const u8,
    summary_digest: [32]u8,
    metrics: Metrics,
};

pub const current_message_schema: u16 = 1;

pub const SourceRange = struct {
    first_record: u64,
    last_record: u64,
};

pub const CausalLink = struct {
    first_index: usize,
    second_index: usize,
    unresolved: bool = false,
    unit_id: []const u8 = "",
};

pub const CausalUnit = struct {
    id: []const u8,
    first_index: usize,
    last_index: usize,
    unresolved: bool = false,
};

/// The renderer is the single authority for both canonical bytes and provider
/// input tokens. A plan never accepts independently supplied metric claims.
pub const CanonicalRenderer = *const fn (context: *anyopaque, messages: []const CompactionMessage, summary: ?[]const u8) anyerror!Metrics;

pub const CompactionMessage = struct {
    id: []const u8,
    role: session.Role,
    content: []const u8,
    causal_unit_id: []const u8 = "",
    schema: u16 = current_message_schema,
};

pub const Plan = struct {
    source_range: SourceRange,
    predecessor_epoch_id: ?[]u8,
    first_retained_id: []u8,
    retained_causal_ids: [][]u8,
    stable_block_ids: [][]u8,
    summary: []u8,
    previous_summary_digest: [32]u8,
    predecessor_summary: ?[]u8,
    predecessor_metrics: ?Metrics,
    before: Metrics,
    after: Metrics,
    measurement_binding: [32]u8,
    predecessor_binding: [32]u8,

    pub fn deinit(self: *Plan, allocator: std.mem.Allocator) void {
        if (self.predecessor_epoch_id) |id| allocator.free(id);
        allocator.free(self.first_retained_id);
        freeIds(allocator, self.retained_causal_ids);
        freeIds(allocator, self.stable_block_ids);
        allocator.free(self.summary);
        if (self.predecessor_summary) |summary| allocator.free(summary);
        deinitMetrics(allocator, &self.before);
        deinitMetrics(allocator, &self.after);
        if (self.predecessor_metrics) |*metrics| deinitMetrics(allocator, metrics);
        self.* = undefined;
    }

    pub fn commit(self: Plan, allocator: std.mem.Allocator) !Epoch {
        const expected_previous_digest = summaryDigest(self.predecessor_summary);
        if (!std.mem.eql(u8, &expected_previous_digest, &self.previous_summary_digest)) return error.PredecessorSummaryTampered;
        var expected_predecessor_binding: [32]u8 = undefined;
        predecessorBinding(&expected_predecessor_binding, self.predecessor_epoch_id, self.predecessor_summary, self.previous_summary_digest, self.predecessor_metrics);
        if (!std.mem.eql(u8, &expected_predecessor_binding, &self.predecessor_binding)) return error.PredecessorEpochTampered;
        try validateMetrics(self.before);
        try validateMetrics(self.after);
        if (self.predecessor_metrics) |metrics| try validateMetrics(metrics);
        if (self.after.serialized_bytes >= self.before.serialized_bytes or self.after.provider_input_tokens >= self.before.provider_input_tokens) return error.NonReducingCompaction;
        var expected_binding: [32]u8 = undefined;
        metricsBinding(&expected_binding, self.before, self.after, self.predecessor_metrics);
        if (!std.mem.eql(u8, &expected_binding, &self.measurement_binding)) return error.MetricsTampered;
        const predecessor_epoch_id = if (self.predecessor_epoch_id) |id| try allocator.dupe(u8, id) else null;
        errdefer if (predecessor_epoch_id) |id| allocator.free(id);
        const first_retained_id = try allocator.dupe(u8, self.first_retained_id);
        errdefer allocator.free(first_retained_id);
        const retained_causal_ids = try cloneIds(allocator, self.retained_causal_ids);
        errdefer freeIds(allocator, retained_causal_ids);
        const stable_block_ids = try cloneIds(allocator, self.stable_block_ids);
        errdefer freeIds(allocator, stable_block_ids);
        const summary = try allocator.dupe(u8, self.summary);
        errdefer allocator.free(summary);
        const predecessor_metrics = if (self.predecessor_metrics) |metrics| try cloneMetrics(allocator, metrics) else null;
        errdefer if (predecessor_metrics) |*metrics| deinitMetrics(allocator, metrics);
        var before = try cloneMetrics(allocator, self.before);
        errdefer deinitMetrics(allocator, &before);
        var after = try cloneMetrics(allocator, self.after);
        errdefer deinitMetrics(allocator, &after);
        return .{
            .source_range = self.source_range,
            .predecessor_epoch_id = predecessor_epoch_id,
            .first_retained_id = first_retained_id,
            .retained_causal_ids = retained_causal_ids,
            .stable_block_ids = stable_block_ids,
            .summary = summary,
            .previous_summary_digest = self.previous_summary_digest,
            .predecessor_metrics = predecessor_metrics,
            .before = before,
            .after = after,
        };
    }
};

pub const Epoch = struct {
    source_range: SourceRange,
    predecessor_epoch_id: ?[]u8,
    first_retained_id: []u8,
    retained_causal_ids: [][]u8,
    stable_block_ids: [][]u8,
    summary: []u8,
    previous_summary_digest: [32]u8,
    predecessor_metrics: ?Metrics,
    before: Metrics,
    after: Metrics,

    pub fn deinit(self: *Epoch, allocator: std.mem.Allocator) void {
        if (self.predecessor_epoch_id) |id| allocator.free(id);
        allocator.free(self.first_retained_id);
        freeIds(allocator, self.retained_causal_ids);
        freeIds(allocator, self.stable_block_ids);
        allocator.free(self.summary);
        deinitMetrics(allocator, &self.before);
        deinitMetrics(allocator, &self.after);
        if (self.predecessor_metrics) |*metrics| deinitMetrics(allocator, metrics);
        self.* = undefined;
    }
};

pub const Result = struct {
    summary: []u8,
    first_kept_index: usize,
    tokens_before: usize,
    serialized_bytes_before: usize,
    serialized_bytes_after: usize,
    candidate: bool,

    pub fn deinit(self: *Result, allocator: std.mem.Allocator) void {
        allocator.free(self.summary);
        self.* = undefined;
    }

    pub fn recent(self: Result, transcript: []const session.Message) []const session.Message {
        return transcript[self.first_kept_index..];
    }
};

pub const Context = struct {
    messages: []session.Message,

    pub fn deinit(self: *Context, allocator: std.mem.Allocator) void {
        for (self.messages) |*message| message.deinit(allocator);
        allocator.free(self.messages);
        self.* = undefined;
    }
};

pub fn calculateContextTokens(input: usize, output: usize, cache_read: usize, cache_write: usize) usize {
    return saturatingAdd(saturatingAdd(input, output), saturatingAdd(cache_read, cache_write));
}

pub fn estimateTokens(message: session.Message) usize {
    return message.content.len / 4 + @intFromBool(message.content.len % 4 != 0);
}

pub fn estimateContextTokens(messages: []const session.Message) usize {
    var total: usize = 0;
    for (messages) |message| total = saturatingAdd(total, estimateTokens(message));
    return total;
}

pub fn shouldCompact(context_tokens: usize, context_window: usize, settings: Settings) bool {
    if (!settings.enabled) return false;
    return context_tokens > context_window -| settings.reserve_tokens;
}

/// The digest is part of the durable epoch contract and authenticates which
/// prior provider summary the next summarizer input was based on.
pub fn summaryDigest(summary: ?[]const u8) [32]u8 {
    var digest: [32]u8 = undefined;
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update("misul-compaction-summary-schema-1\x00");
    if (summary) |value| hashLengthAndBytes(&hasher, value) else hashLengthAndBytes(&hasher, "");
    hasher.final(&digest);
    return digest;
}

/// Returns null when no message can be removed or the canonical provider
/// request would not shrink in both bytes and tokens.
fn preparePlan(
    allocator: std.mem.Allocator,
    messages: []const CompactionMessage,
    keep_recent_tokens: usize,
    previous_summary: ?[]const u8,
    source_range: SourceRange,
    predecessor_epoch_id: ?[]const u8,
    causal_links: []const CausalLink,
) !?Plan {
    if (!builtin.is_test) return error.ProviderSummaryRequired;
    if (messages.len == 0) return error.EmptyTranscript;
    const first_kept = try chooseFirstKeptWithLinks(messages, keep_recent_tokens, causal_links);
    if (first_kept == 0) return null;

    const summary = try summarize(allocator, messages[0..first_kept], previous_summary);
    errdefer allocator.free(summary);
    if (summary.len == 0) return null;
    const before: Metrics = .{
        .serialized_bytes = serializedBytes(messages, null),
        .provider_input_tokens = estimateCompactionTokens(messages, null),
        .provenance = .{ .provider = "legacy-test", .model = "legacy-test", .canonical_request_schema = "legacy-test", .canonical_request_version = 1, .token_basis = "legacy-estimate" },
    };
    const after: Metrics = .{
        .serialized_bytes = serializedBytes(messages[first_kept..], summary),
        .provider_input_tokens = estimateCompactionTokens(messages[first_kept..], summary),
        .provenance = before.provenance,
    };
    if (after.serialized_bytes >= before.serialized_bytes or after.provider_input_tokens >= before.provider_input_tokens) {
        allocator.free(summary);
        return null;
    }

    const first_retained_id = try allocator.dupe(u8, messages[first_kept].id);
    errdefer allocator.free(first_retained_id);
    const causal_ids = try causalIds(allocator, messages, first_kept, causal_links);
    errdefer freeIds(allocator, causal_ids);
    const block_ids = try stableBlockIds(allocator, messages[first_kept..], &.{});
    errdefer freeIds(allocator, block_ids);
    var owned_before = try cloneMetrics(allocator, before);
    errdefer deinitMetrics(allocator, &owned_before);
    var owned_after = try cloneMetrics(allocator, after);
    errdefer deinitMetrics(allocator, &owned_after);
    return .{
        .source_range = source_range,
        .predecessor_epoch_id = if (predecessor_epoch_id) |id| try allocator.dupe(u8, id) else null,
        .first_retained_id = first_retained_id,
        .retained_causal_ids = causal_ids,
        .stable_block_ids = block_ids,
        .summary = summary,
        .previous_summary_digest = summaryDigest(previous_summary),
        .predecessor_metrics = null,
        .before = owned_before,
        .after = owned_after,
        .measurement_binding = metricsBindingValue(owned_before, owned_after, null),
        .predecessor_summary = if (previous_summary) |value| try allocator.dupe(u8, value) else null,
        .predecessor_binding = predecessorBindingValue(predecessor_epoch_id, previous_summary, summaryDigest(previous_summary), null),
    };
}

/// Build a production compaction plan from a summary already produced by the
/// provider-facing summarizer.  This function does not synthesize or truncate
/// summary text and trusts only exact renderer/token measurements supplied by
/// the caller after validating them.
pub fn preparePlanWithProviderSummary(
    allocator: std.mem.Allocator,
    messages: []const CompactionMessage,
    keep_recent_tokens: usize,
    source_range: SourceRange,
    predecessor: ?PredecessorEpoch,
    causal_units: []const CausalUnit,
    candidate_summary: []const u8,
    renderer_context: *anyopaque,
    renderer: CanonicalRenderer,
) !?Plan {
    if (messages.len == 0) return error.EmptyTranscript;
    if (candidate_summary.len == 0) return error.EmptySummary;
    if (candidate_summary.len > max_summary_bytes) return error.SummaryTooLarge;
    const previous_summary = if (predecessor) |value| value.summary else null;
    const previous_summary_digest = summaryDigest(previous_summary);
    if (predecessor) |value| {
        if (!std.mem.eql(u8, &value.summary_digest, &previous_summary_digest)) return error.SummaryDigestMismatch;
    }
    const first_kept = try chooseFirstKeptWithUnits(messages, keep_recent_tokens, causal_units);
    if (first_kept == 0) return null;
    const before = try renderer(renderer_context, messages, null);
    const after = try renderer(renderer_context, messages[first_kept..], candidate_summary);
    try validateMetrics(before);
    try validateMetrics(after);
    if (!sameProvenance(before.provenance, after.provenance)) return error.MeasurementProvenanceMismatch;
    if (predecessor) |value| {
        try validateMetrics(value.metrics);
        if (!sameProvenance(value.metrics.provenance, before.provenance)) return error.MeasurementProvenanceMismatch;
    }
    if (after.serialized_bytes >= before.serialized_bytes or after.provider_input_tokens >= before.provider_input_tokens) return null;
    const first_retained_id = try allocator.dupe(u8, messages[first_kept].id);
    errdefer allocator.free(first_retained_id);
    const retained_causal_ids = try retainedUnitIds(allocator, causal_units, first_kept);
    errdefer freeIds(allocator, retained_causal_ids);
    const relative_units = try relativeCausalUnits(allocator, causal_units, first_kept);
    defer allocator.free(relative_units);
    const block_ids = try stableBlockIds(allocator, messages[first_kept..], relative_units);
    errdefer freeIds(allocator, block_ids);
    const summary = try allocator.dupe(u8, candidate_summary);
    errdefer allocator.free(summary);
    const owned_predecessor_summary = if (previous_summary) |value| try allocator.dupe(u8, value) else null;
    errdefer if (owned_predecessor_summary) |value| allocator.free(value);
    var owned_before = try cloneMetrics(allocator, before);
    errdefer deinitMetrics(allocator, &owned_before);
    var owned_after = try cloneMetrics(allocator, after);
    errdefer deinitMetrics(allocator, &owned_after);
    var owned_predecessor_metrics = if (predecessor) |value| try cloneMetrics(allocator, value.metrics) else null;
    errdefer if (owned_predecessor_metrics) |*metrics| deinitMetrics(allocator, metrics);
    return .{
        .source_range = source_range,
        .predecessor_epoch_id = if (predecessor) |value| try allocator.dupe(u8, value.id) else null,
        .first_retained_id = first_retained_id,
        .retained_causal_ids = retained_causal_ids,
        .stable_block_ids = block_ids,
        .summary = summary,
        .previous_summary_digest = previous_summary_digest,
        .predecessor_summary = owned_predecessor_summary,
        .predecessor_metrics = owned_predecessor_metrics,
        .before = owned_before,
        .after = owned_after,
        .measurement_binding = metricsBindingValue(owned_before, owned_after, owned_predecessor_metrics),
        .predecessor_binding = predecessorBindingValue(
            if (predecessor) |value| value.id else null,
            previous_summary,
            previous_summary_digest,
            if (predecessor) |value| value.metrics else null,
        ),
    };
}

fn compactLocal(
    allocator: std.mem.Allocator,
    transcript: []const session.Message,
    keep_recent_tokens: usize,
    previous_summary: ?[]const u8,
) !Result {
    if (!builtin.is_test) return error.ProviderSummaryRequired;
    if (transcript.len == 0) return error.EmptyTranscript;
    const specs = try allocator.alloc(CompactionMessage, transcript.len);
    defer allocator.free(specs);
    const id_buffers = try allocator.alloc([32]u8, transcript.len);
    defer allocator.free(id_buffers);
    for (transcript, 0..) |message, index| {
        const id = std.fmt.bufPrint(&id_buffers[index], "message-{d}", .{index}) catch unreachable;
        specs[index] = .{ .id = id, .role = message.role, .content = message.content };
    }
    const first_kept = chooseFirstKept(specs, keep_recent_tokens);
    const summary = if (first_kept == 0)
        try allocator.dupe(u8, "")
    else
        try summarize(allocator, specs[0..first_kept], previous_summary);
    const before_bytes = serializedBytes(specs, null);
    const after_bytes = serializedBytes(specs[first_kept..], if (summary.len == 0) null else summary);
    return .{
        .summary = summary,
        .first_kept_index = first_kept,
        .tokens_before = estimateContextTokens(transcript),
        .serialized_bytes_before = before_bytes,
        .serialized_bytes_after = after_bytes,
        .candidate = first_kept != 0 and summary.len != 0 and after_bytes < before_bytes and estimateCompactionTokens(specs[first_kept..], summary) < estimateCompactionTokens(specs, null),
    };
}

pub fn buildContext(allocator: std.mem.Allocator, result: Result, transcript: []const session.Message) !Context {
    if (!result.candidate) {
        const messages = try allocator.alloc(session.Message, transcript.len);
        var initialized: usize = 0;
        errdefer {
            for (messages[0..initialized]) |*message| message.deinit(allocator);
            allocator.free(messages);
        }
        for (transcript, 0..) |message, index| {
            messages[index] = try message.clone(allocator);
            initialized += 1;
        }
        return .{ .messages = messages };
    }
    const recent = result.recent(transcript);
    const messages = try allocator.alloc(session.Message, recent.len + 1);
    var initialized: usize = 0;
    errdefer {
        for (messages[0..initialized]) |*message| message.deinit(allocator);
        allocator.free(messages);
    }
    messages[0] = try session.Message.init(allocator, .compaction_summary, result.summary);
    initialized = 1;
    for (recent, 1..) |message, index| {
        messages[index] = try message.clone(allocator);
        initialized += 1;
    }
    return .{ .messages = messages };
}

fn chooseFirstKept(messages: []const CompactionMessage, keep_recent_tokens: usize) usize {
    var accumulated: usize = 0;
    var first_kept = messages.len;
    var index = messages.len;
    while (index > 0) {
        index -= 1;
        first_kept = index;
        accumulated = saturatingAdd(accumulated, messages[index].content.len / 4 + @intFromBool(messages[index].content.len % 4 != 0));
        if (accumulated >= keep_recent_tokens) break;
    }
    return first_kept;
}

fn chooseFirstKeptWithLinks(messages: []const CompactionMessage, keep_recent_tokens: usize, causal_links: []const CausalLink) !usize {
    var first_kept = chooseFirstKept(messages, keep_recent_tokens);
    // Compatibility callers may not have causal IDs yet. Keep a contiguous
    // assistant/tool-result unit together while they migrate to CausalUnit.
    if (first_kept > 0 and first_kept < messages.len and messages[first_kept].role == .tool_result) {
        var unit_start = first_kept;
        while (unit_start > 0 and messages[unit_start - 1].role == .tool_result) unit_start -= 1;
        if (unit_start > 0 and messages[unit_start - 1].role == .assistant) first_kept = unit_start - 1;
    }
    for (causal_links) |link| {
        if (link.first_index >= messages.len or link.second_index >= messages.len) return error.InvalidCausalLink;
        const low = @min(link.first_index, link.second_index);
        const high = @max(link.first_index, link.second_index);
        if (low < first_kept and first_kept <= high) first_kept = low;
        if (link.unresolved and low < first_kept) first_kept = low;
    }
    return first_kept;
}

fn chooseFirstKeptWithUnits(messages: []const CompactionMessage, keep_recent_tokens: usize, units: []const CausalUnit) !usize {
    var first_kept = chooseFirstKept(messages, keep_recent_tokens);
    for (units) |unit| {
        if (unit.id.len == 0 or unit.first_index > unit.last_index or unit.last_index >= messages.len) {
            return error.InvalidCausalUnit;
        }
        if (unit.first_index < first_kept and first_kept <= unit.last_index) {
            first_kept = unit.first_index;
        }
        // An unresolved unit is authoritative state, not summarizable history.
        // Keep it verbatim even when the token boundary falls after it.
        if (unit.unresolved and unit.last_index < first_kept) first_kept = unit.first_index;
    }
    if (first_kept > 0 and first_kept < messages.len and messages[first_kept].role == .tool_result and
        messages[first_kept - 1].role == .assistant)
    {
        return error.CausalUnitRequired;
    }
    return first_kept;
}

fn retainedUnitIds(allocator: std.mem.Allocator, units: []const CausalUnit, first_kept: usize) ![][]u8 {
    var ids: std.ArrayList([]u8) = .empty;
    errdefer {
        for (ids.items) |id| allocator.free(id);
        ids.deinit(allocator);
    }
    for (units) |unit| {
        if (unit.last_index < first_kept) continue;
        const id = try allocator.dupe(u8, unit.id);
        ids.append(allocator, id) catch |err| {
            allocator.free(id);
            return err;
        };
    }
    return ids.toOwnedSlice(allocator);
}

fn relativeCausalUnits(allocator: std.mem.Allocator, units: []const CausalUnit, first_kept: usize) ![]CausalUnit {
    var count: usize = 0;
    for (units) |unit| {
        if (unit.last_index >= first_kept) count += 1;
    }
    const result = try allocator.alloc(CausalUnit, count);
    var initialized: usize = 0;
    errdefer allocator.free(result);
    for (units) |unit| {
        if (unit.last_index < first_kept) continue;
        if (unit.first_index < first_kept) return error.InvalidCausalUnit;
        result[initialized] = .{
            .id = unit.id,
            .first_index = unit.first_index - first_kept,
            .last_index = unit.last_index - first_kept,
            .unresolved = unit.unresolved,
        };
        initialized += 1;
    }
    return result;
}

fn summarize(allocator: std.mem.Allocator, prefix_messages: []const CompactionMessage, previous_summary: ?[]const u8) ![]u8 {
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);
    if (previous_summary) |previous| if (previous.len != 0) {
        try output.appendSlice(allocator, "prior: ");
        try appendBounded(allocator, &output, previous, 256);
        try output.append(allocator, '\n');
    };
    for (prefix_messages) |message| {
        try output.append(allocator, '[');
        try output.appendSlice(allocator, @tagName(message.role));
        try output.appendSlice(allocator, "] ");
        try appendBounded(allocator, &output, message.content, 256);
        try output.append(allocator, '\n');
    }
    return output.toOwnedSlice(allocator);
}

fn appendBounded(allocator: std.mem.Allocator, output: *std.ArrayList(u8), bytes: []const u8, limit: usize) !void {
    const amount = @min(bytes.len, limit);
    try output.appendSlice(allocator, bytes[0..amount]);
    if (amount != bytes.len) try output.appendSlice(allocator, "…");
}

fn hashLengthAndBytes(hasher: *std.crypto.hash.sha2.Sha256, bytes: []const u8) void {
    var length: [8]u8 = undefined;
    std.mem.writeInt(u64, &length, @intCast(bytes.len), .little);
    hasher.update(&length);
    hasher.update(bytes);
}

fn metricsBindingValue(before: Metrics, after: Metrics, predecessor: ?Metrics) [32]u8 {
    var digest: [32]u8 = undefined;
    metricsBinding(&digest, before, after, predecessor);
    return digest;
}

fn predecessorBindingValue(id: ?[]const u8, summary: ?[]const u8, summary_digest: [32]u8, metrics: ?Metrics) [32]u8 {
    var digest: [32]u8 = undefined;
    predecessorBinding(&digest, id, summary, summary_digest, metrics);
    return digest;
}

fn predecessorBinding(output: *[32]u8, id: ?[]const u8, summary: ?[]const u8, summary_digest: [32]u8, metrics: ?Metrics) void {
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update("misul-compaction-predecessor-v1\x00");
    if (id) |value| {
        hasher.update(&[_]u8{1});
        hashLengthAndBytes(&hasher, value);
    } else {
        hasher.update(&[_]u8{0});
    }
    if (summary) |value| {
        hasher.update(&[_]u8{1});
        hashLengthAndBytes(&hasher, value);
    } else {
        hasher.update(&[_]u8{0});
    }
    hasher.update(&summary_digest);
    if (metrics) |value| {
        hasher.update(&[_]u8{1});
        var number: [8]u8 = undefined;
        std.mem.writeInt(u64, &number, @intCast(value.serialized_bytes), .little);
        hasher.update(&number);
        std.mem.writeInt(u64, &number, @intCast(value.provider_input_tokens), .little);
        hasher.update(&number);
        hashProvenance(&hasher, value.provenance);
    } else {
        hasher.update(&[_]u8{0});
    }
    hasher.final(output);
}

fn cloneMetrics(allocator: std.mem.Allocator, metrics: Metrics) !Metrics {
    var result = metrics;
    result.provenance = .{};
    errdefer deinitMetrics(allocator, &result);
    result.provenance.provider = try allocator.dupe(u8, metrics.provenance.provider);
    result.provenance.model = try allocator.dupe(u8, metrics.provenance.model);
    result.provenance.canonical_request_schema = try allocator.dupe(u8, metrics.provenance.canonical_request_schema);
    result.provenance.canonical_request_version = metrics.provenance.canonical_request_version;
    result.provenance.token_basis = try allocator.dupe(u8, metrics.provenance.token_basis);
    return result;
}

fn deinitMetrics(allocator: std.mem.Allocator, metrics: *const Metrics) void {
    if (metrics.provenance.provider.len != 0) allocator.free(metrics.provenance.provider);
    if (metrics.provenance.model.len != 0) allocator.free(metrics.provenance.model);
    if (metrics.provenance.canonical_request_schema.len != 0) allocator.free(metrics.provenance.canonical_request_schema);
    if (metrics.provenance.token_basis.len != 0) allocator.free(metrics.provenance.token_basis);
}

fn metricsBinding(output: *[32]u8, before: Metrics, after: Metrics, predecessor: ?Metrics) void {
    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hasher.update("misul-compaction-metrics-v1\x00");
    var number: [8]u8 = undefined;
    const metrics = [_]usize{ before.serialized_bytes, before.provider_input_tokens, after.serialized_bytes, after.provider_input_tokens };
    for (metrics) |value| {
        std.mem.writeInt(u64, &number, @intCast(value), .little);
        hasher.update(&number);
    }
    if (predecessor) |value| {
        hasher.update(&[_]u8{1});
        std.mem.writeInt(u64, &number, @intCast(value.serialized_bytes), .little);
        hasher.update(&number);
        std.mem.writeInt(u64, &number, @intCast(value.provider_input_tokens), .little);
        hasher.update(&number);
        hashProvenance(&hasher, value.provenance);
    } else {
        hasher.update(&[_]u8{0});
    }
    hashProvenance(&hasher, before.provenance);
    hashProvenance(&hasher, after.provenance);
    hasher.final(output);
}

fn validateMetrics(metrics: Metrics) !void {
    const provenance = metrics.provenance;
    if (provenance.provider.len == 0 or provenance.model.len == 0 or provenance.canonical_request_schema.len == 0 or
        provenance.canonical_request_version == 0 or provenance.token_basis.len == 0 or
        provenance.provider.len > 256 or provenance.model.len > 256 or provenance.canonical_request_schema.len > 256 or provenance.token_basis.len > 256)
        return error.MeasurementProvenanceRequired;
}

fn sameProvenance(left: MeasurementProvenance, right: MeasurementProvenance) bool {
    return std.mem.eql(u8, left.provider, right.provider) and
        std.mem.eql(u8, left.model, right.model) and
        std.mem.eql(u8, left.canonical_request_schema, right.canonical_request_schema) and
        left.canonical_request_version == right.canonical_request_version and
        std.mem.eql(u8, left.token_basis, right.token_basis);
}

fn hashProvenance(hasher: *std.crypto.hash.sha2.Sha256, provenance: MeasurementProvenance) void {
    hashLengthAndBytes(hasher, provenance.provider);
    hashLengthAndBytes(hasher, provenance.model);
    hashLengthAndBytes(hasher, provenance.canonical_request_schema);
    var version: [2]u8 = undefined;
    std.mem.writeInt(u16, &version, provenance.canonical_request_version, .little);
    hasher.update(&version);
    hashLengthAndBytes(hasher, provenance.token_basis);
}

fn serializedBytes(messages: []const CompactionMessage, summary: ?[]const u8) usize {
    var total: usize = 0;
    if (summary) |value| total = saturatingAdd(total, value.len + 24);
    for (messages) |message| total = saturatingAdd(total, @tagName(message.role).len + message.content.len + 16);
    return total;
}

fn estimateCompactionTokens(messages: []const CompactionMessage, summary: ?[]const u8) usize {
    var total: usize = 0;
    if (summary) |value| total = saturatingAdd(total, value.len / 4 + @intFromBool(value.len % 4 != 0));
    for (messages) |message| total = saturatingAdd(total, message.content.len / 4 + @intFromBool(message.content.len % 4 != 0));
    return total;
}

fn causalIds(allocator: std.mem.Allocator, messages: []const CompactionMessage, first_kept: usize, links: []const CausalLink) ![][]u8 {
    var ids: std.ArrayList([]u8) = .empty;
    errdefer {
        for (ids.items) |id| allocator.free(id);
        ids.deinit(allocator);
    }
    for (links) |link| {
        if (link.first_index < first_kept or link.second_index < first_kept) continue;
        if (link.unit_id.len != 0) {
            const id = try allocator.dupe(u8, link.unit_id);
            ids.append(allocator, id) catch |err| {
                allocator.free(id);
                return err;
            };
            continue;
        }
        const first_id = try allocator.dupe(u8, messages[link.first_index].id);
        ids.append(allocator, first_id) catch |err| {
            allocator.free(first_id);
            return err;
        };
        if (link.second_index != link.first_index) {
            const second_id = try allocator.dupe(u8, messages[link.second_index].id);
            ids.append(allocator, second_id) catch |err| {
                allocator.free(second_id);
                return err;
            };
        }
    }
    return ids.toOwnedSlice(allocator);
}

fn stableBlockIds(allocator: std.mem.Allocator, messages: []const CompactionMessage, causal_units: []const CausalUnit) ![][]u8 {
    var ids: std.ArrayList([]u8) = .empty;
    errdefer {
        for (ids.items) |id| allocator.free(id);
        ids.deinit(allocator);
    }
    for (messages, 0..) |message, index| {
        if (message.schema != current_message_schema) return error.UnknownMessageSchema;
        var digest: [32]u8 = undefined;
        var hasher = std.crypto.hash.sha2.Sha256.init(.{});
        hasher.update("misul-compaction-block-schema-1\x00");
        var schema_bytes: [2]u8 = undefined;
        std.mem.writeInt(u16, &schema_bytes, message.schema, .little);
        hasher.update(&schema_bytes);
        hashLengthAndBytes(&hasher, @tagName(message.role));
        hashLengthAndBytes(&hasher, message.id);
        hashLengthAndBytes(&hasher, message.content);
        hashLengthAndBytes(&hasher, message.causal_unit_id);
        for (causal_units) |unit| {
            if (index >= unit.first_index and index <= unit.last_index) {
                hashLengthAndBytes(&hasher, unit.id);
                hasher.update(&[_]u8{@intFromBool(unit.unresolved)});
            }
        }
        hasher.final(&digest);
        const id = try allocator.alloc(u8, 64);
        const hex = "0123456789abcdef";
        for (digest, 0..) |byte, digest_index| {
            id[digest_index * 2] = hex[byte >> 4];
            id[digest_index * 2 + 1] = hex[byte & 0x0f];
        }
        ids.append(allocator, id) catch |err| {
            allocator.free(id);
            return err;
        };
    }
    return ids.toOwnedSlice(allocator);
}

fn cloneIds(allocator: std.mem.Allocator, ids: []const []u8) ![][]u8 {
    const result = try allocator.alloc([]u8, ids.len);
    var initialized: usize = 0;
    errdefer {
        for (result[0..initialized]) |id| allocator.free(id);
        allocator.free(result);
    }
    for (ids, 0..) |id, index| {
        result[index] = try allocator.dupe(u8, id);
        initialized += 1;
    }
    return result;
}

fn freeIds(allocator: std.mem.Allocator, ids: []const []u8) void {
    for (ids) |id| allocator.free(id);
    allocator.free(ids);
}

fn saturatingAdd(left: usize, right: usize) usize {
    return left +| right;
}

fn makeMessages(allocator: std.mem.Allocator, specs: []const struct { session.Role, []const u8 }) ![]session.Message {
    const messages = try allocator.alloc(session.Message, specs.len);
    var initialized: usize = 0;
    errdefer {
        for (messages[0..initialized]) |*message| message.deinit(allocator);
        allocator.free(messages);
    }
    for (specs, 0..) |spec, index| {
        messages[index] = try session.Message.init(allocator, spec[0], spec[1]);
        initialized += 1;
    }
    return messages;
}

fn freeMessages(allocator: std.mem.Allocator, messages: []session.Message) void {
    for (messages) |*message| message.deinit(allocator);
    allocator.free(messages);
}

test "compaction preserves a contiguous verbatim suffix and does not add an empty summary" {
    const allocator = std.testing.allocator;
    const messages = try makeMessages(allocator, &.{
        .{ .user, "old request" },
        .{ .assistant, "old answer" },
        .{ .user, "recent request" },
        .{ .assistant, "recent answer" },
    });
    defer freeMessages(allocator, messages);
    var result = try compactLocal(allocator, messages, 6, null);
    defer result.deinit(allocator);
    try std.testing.expectEqual(@as(usize, 2), result.first_kept_index);
    try std.testing.expect(!result.candidate);
    var context = try buildContext(allocator, result, messages);
    defer context.deinit(allocator);
    try std.testing.expectEqual(@as(usize, 4), context.messages.len);

    var no_op = try compactLocal(allocator, messages, 100, null);
    defer no_op.deinit(allocator);
    try std.testing.expectEqual(@as(usize, 0), no_op.first_kept_index);
    try std.testing.expectEqual(@as(usize, 0), no_op.summary.len);
}

test "long compaction plan shrinks canonical bytes and tokens and carries epoch metadata" {
    const allocator = std.testing.allocator;
    const long = "a" ** 1024;
    const messages = [_]CompactionMessage{
        .{ .id = "m1", .role = .user, .content = long },
        .{ .id = "m2", .role = .assistant, .content = long },
        .{ .id = "m3", .role = .user, .content = "recent" },
        .{ .id = "m4", .role = .assistant, .content = "answer" },
    };
    var plan = (try preparePlan(allocator, &messages, 4, "old summary", .{ .first_record = 10, .last_record = 20 }, "epoch-0", &.{})).?;
    defer plan.deinit(allocator);
    try std.testing.expect(plan.after.serialized_bytes < plan.before.serialized_bytes);
    try std.testing.expect(plan.after.provider_input_tokens < plan.before.provider_input_tokens);
    try std.testing.expectEqualStrings("epoch-0", plan.predecessor_epoch_id.?);
    var epoch = try plan.commit(allocator);
    defer epoch.deinit(allocator);
    try std.testing.expectEqual(@as(u64, 10), epoch.source_range.first_record);
}

fn testCanonicalRenderer(context: *anyopaque, messages: []const CompactionMessage, summary: ?[]const u8) anyerror!Metrics {
    _ = context;
    var bytes: usize = 0;
    if (summary) |value| bytes += 12 + value.len;
    for (messages) |message| {
        bytes += 8 + message.id.len + @tagName(message.role).len + message.content.len + message.causal_unit_id.len;
    }
    return .{ .serialized_bytes = bytes, .provider_input_tokens = (bytes + 3) / 4, .provenance = .{ .provider = "test-provider", .model = "test-model", .canonical_request_schema = "test-request", .canonical_request_version = 1, .token_basis = "test-tokens" } };
}

const MutableMetricContext = struct {
    provider: [8]u8 = .{ 'p', 'r', 'o', 'v', 'i', 'd', 'e', 'r' },
    model: [5]u8 = .{ 'm', 'o', 'd', 'e', 'l' },
    schema: [10]u8 = .{ 'r', 'e', 'q', 'u', 'e', 's', 't', '-', 'v', '1' },
    basis: [6]u8 = .{ 't', 'o', 'k', 'e', 'n', 's' },
};

fn mutableMetricRenderer(context: *anyopaque, messages: []const CompactionMessage, summary: ?[]const u8) anyerror!Metrics {
    const state: *MutableMetricContext = @ptrCast(@alignCast(context));
    var bytes: usize = if (summary) |value| value.len + 12 else 0;
    for (messages) |message| bytes += message.id.len + message.content.len + 8;
    return .{
        .serialized_bytes = bytes,
        .provider_input_tokens = (bytes + 3) / 4,
        .provenance = .{
            .provider = &state.provider,
            .model = &state.model,
            .canonical_request_schema = &state.schema,
            .canonical_request_version = 1,
            .token_basis = &state.basis,
        },
    };
}

test "provider summary plan uses exact renderer metrics and explicit causal units" {
    const allocator = std.testing.allocator;
    const long = "x" ** 1024;
    const messages = [_]CompactionMessage{
        .{ .id = "m1", .role = .user, .content = long },
        .{ .id = "m2", .role = .assistant, .content = long, .causal_unit_id = "tool-1" },
        .{ .id = "m3", .role = .tool_result, .content = long, .causal_unit_id = "tool-1" },
        .{ .id = "m4", .role = .user, .content = "recent" },
    };
    const units = [_]CausalUnit{.{ .id = "tool-1", .first_index = 1, .last_index = 2 }};
    var context: u8 = 0;
    const previous_summary = "old summary";
    const candidate_summary = "old summary\nprovider folded summary";
    const before = try testCanonicalRenderer(&context, &messages, null);
    const after = try testCanonicalRenderer(&context, messages[1..], candidate_summary);
    var plan = (try preparePlanWithProviderSummary(
        allocator,
        &messages,
        300,
        .{ .first_record = 10, .last_record = 40 },
        .{ .id = "epoch-0", .summary = previous_summary, .summary_digest = summaryDigest(previous_summary), .metrics = before },
        &units,
        candidate_summary,
        &context,
        testCanonicalRenderer,
    )).?;
    defer plan.deinit(allocator);
    try std.testing.expectEqualStrings(candidate_summary, plan.summary);
    try std.testing.expectEqualStrings("m2", plan.first_retained_id);
    try std.testing.expectEqualStrings("tool-1", plan.retained_causal_ids[0]);
    try std.testing.expectEqual(before.serialized_bytes, plan.before.serialized_bytes);
    try std.testing.expectEqual(after.serialized_bytes, plan.after.serialized_bytes);
    try std.testing.expectEqual(summaryDigest(previous_summary), plan.previous_summary_digest);
    try std.testing.expectEqual(before.serialized_bytes, plan.predecessor_metrics.?.serialized_bytes);
    try std.testing.expectEqual(before.provider_input_tokens, plan.predecessor_metrics.?.provider_input_tokens);
    try std.testing.expectEqualStrings(before.provenance.provider, plan.predecessor_metrics.?.provenance.provider);
    try std.testing.expectEqualStrings(before.provenance.model, plan.predecessor_metrics.?.provenance.model);
    try std.testing.expectEqualStrings(before.provenance.canonical_request_schema, plan.predecessor_metrics.?.provenance.canonical_request_schema);
    try std.testing.expectEqual(before.provenance.canonical_request_version, plan.predecessor_metrics.?.provenance.canonical_request_version);
    try std.testing.expectEqualStrings(before.provenance.token_basis, plan.predecessor_metrics.?.provenance.token_basis);
    try std.testing.expectError(error.SummaryDigestMismatch, preparePlanWithProviderSummary(
        allocator,
        &messages,
        300,
        .{ .first_record = 10, .last_record = 40 },
        .{ .id = "epoch-0", .summary = "different prior summary", .summary_digest = summaryDigest(previous_summary), .metrics = before },
        &units,
        candidate_summary,
        &context,
        testCanonicalRenderer,
    ));
}

test "prepared and committed epochs own callback metric provenance" {
    const allocator = std.testing.allocator;
    const long = "o" ** 1024;
    const messages = [_]CompactionMessage{
        .{ .id = "m1", .role = .user, .content = long },
        .{ .id = "m2", .role = .assistant, .content = "recent" },
    };
    var context = MutableMetricContext{};
    const before = try mutableMetricRenderer(@ptrCast(&context), &messages, null);
    var plan = (try preparePlanWithProviderSummary(
        allocator,
        &messages,
        1,
        .{ .first_record = 1, .last_record = 2 },
        .{ .id = "epoch-0", .summary = "prior", .summary_digest = summaryDigest("prior"), .metrics = before },
        &.{},
        "folded prior",
        @ptrCast(&context),
        mutableMetricRenderer,
    )).?;
    context.provider[0] = 'X';
    context.model[0] = 'X';
    context.schema[0] = 'X';
    context.basis[0] = 'X';
    try std.testing.expectEqualStrings("provider", plan.before.provenance.provider);
    try std.testing.expectEqualStrings("model", plan.before.provenance.model);
    try std.testing.expectEqualStrings("request-v1", plan.before.provenance.canonical_request_schema);
    try std.testing.expectEqualStrings("tokens", plan.before.provenance.token_basis);
    var epoch = try plan.commit(allocator);
    plan.deinit(allocator);
    defer epoch.deinit(allocator);
    try std.testing.expectEqualStrings("provider", epoch.before.provenance.provider);
    try std.testing.expectEqualStrings("model", epoch.before.provenance.model);
    try std.testing.expectEqualStrings("request-v1", epoch.before.provenance.canonical_request_schema);
    try std.testing.expectEqualStrings("tokens", epoch.before.provenance.token_basis);
}

test "provider summary rejects a split tool unit without an explicit unit" {
    const allocator = std.testing.allocator;
    const messages = [_]CompactionMessage{
        .{ .id = "m1", .role = .assistant, .content = "call" },
        .{ .id = "m2", .role = .tool_result, .content = "result" },
        .{ .id = "m3", .role = .user, .content = "recent" },
    };
    var context: u8 = 0;
    try std.testing.expectError(error.CausalUnitRequired, preparePlanWithProviderSummary(
        allocator,
        &messages,
        3,
        .{ .first_record = 1, .last_record = 3 },
        null,
        &.{},
        "summary",
        &context,
        testCanonicalRenderer,
    ));
}

test "genesis plan uses the canonical empty predecessor and commit rechecks it" {
    const allocator = std.testing.allocator;
    const long = "g" ** 1024;
    const messages = [_]CompactionMessage{
        .{ .id = "m1", .role = .user, .content = long },
        .{ .id = "m2", .role = .assistant, .content = "recent" },
    };
    var context: u8 = 0;
    var plan = (try preparePlanWithProviderSummary(
        allocator,
        &messages,
        1,
        .{ .first_record = 1, .last_record = 2 },
        null,
        &.{},
        "provider folded summary",
        &context,
        testCanonicalRenderer,
    )).?;
    defer plan.deinit(allocator);
    try std.testing.expectEqual(summaryDigest(null), plan.previous_summary_digest);
    try std.testing.expect(plan.predecessor_summary == null);
    var tampered = plan;
    tampered.previous_summary_digest[0] ^= 1;
    try std.testing.expectError(error.PredecessorSummaryTampered, tampered.commit(allocator));
    var epoch = try plan.commit(allocator);
    defer epoch.deinit(allocator);
    try std.testing.expectEqual(summaryDigest(null), epoch.previous_summary_digest);
}

test "plan commit rejects changed provider metrics and predecessor summary" {
    const allocator = std.testing.allocator;
    const long = "p" ** 1024;
    const messages = [_]CompactionMessage{
        .{ .id = "m1", .role = .user, .content = long },
        .{ .id = "m2", .role = .assistant, .content = "recent" },
    };
    var context: u8 = 0;
    const prior = "folded prior";
    const before = try testCanonicalRenderer(&context, &messages, null);
    var plan = (try preparePlanWithProviderSummary(
        allocator,
        &messages,
        1,
        .{ .first_record = 1, .last_record = 2 },
        .{ .id = "epoch-0", .summary = prior, .summary_digest = summaryDigest(prior), .metrics = before },
        &.{},
        "prior plus new facts",
        &context,
        testCanonicalRenderer,
    )).?;
    defer plan.deinit(allocator);
    var summary_tampered = plan;
    summary_tampered.previous_summary_digest[0] ^= 1;
    try std.testing.expectError(error.PredecessorSummaryTampered, summary_tampered.commit(allocator));
    var predecessor_tampered = plan;
    predecessor_tampered.predecessor_epoch_id = @constCast("epoch-other");
    try std.testing.expectError(error.PredecessorEpochTampered, predecessor_tampered.commit(allocator));
    plan.after.serialized_bytes -= 1;
    try std.testing.expectError(error.MetricsTampered, plan.commit(allocator));
}

test "stable block identity authenticates schema role and causal metadata" {
    const allocator = std.testing.allocator;
    const user = [_]CompactionMessage{.{ .id = "same", .role = .user, .content = "content" }};
    const assistant = [_]CompactionMessage{.{ .id = "same", .role = .assistant, .content = "content" }};
    const user_id = try stableBlockIds(allocator, &user, &.{});
    defer freeIds(allocator, user_id);
    const assistant_id = try stableBlockIds(allocator, &assistant, &.{});
    defer freeIds(allocator, assistant_id);
    try std.testing.expect(!std.mem.eql(u8, user_id[0], assistant_id[0]));
    const unknown_schema = [_]CompactionMessage{.{ .id = "same", .role = .user, .content = "content", .schema = current_message_schema + 1 }};
    try std.testing.expectError(error.UnknownMessageSchema, stableBlockIds(allocator, &unknown_schema, &.{}));
}

test "causal links move the retention boundary without splitting pairs" {
    const allocator = std.testing.allocator;
    const messages = [_]CompactionMessage{
        .{ .id = "m1", .role = .user, .content = "old" },
        .{ .id = "m2", .role = .assistant, .content = "tool call" },
        .{ .id = "m3", .role = .tool_result, .content = "tool result" },
        .{ .id = "m4", .role = .assistant, .content = "new" },
    };
    const links = [_]CausalLink{.{ .first_index = 1, .second_index = 2 }};
    const plan = try preparePlan(allocator, &messages, 1, null, .{ .first_record = 1, .last_record = 4 }, null, &links);
    if (plan) |candidate| {
        var owned = candidate;
        defer owned.deinit(allocator);
        try std.testing.expectEqualStrings("m2", owned.first_retained_id);
    }
}

test "tool call and result stay together without an explicit causal link" {
    const allocator = std.testing.allocator;
    const long = "b" ** 1024;
    const messages = [_]CompactionMessage{
        .{ .id = "m1", .role = .user, .content = long },
        .{ .id = "m2", .role = .assistant, .content = long },
        .{ .id = "m3", .role = .tool_result, .content = "result" },
        .{ .id = "m4", .role = .user, .content = "kept" },
    };
    var plan = (try preparePlan(allocator, &messages, 2, null, .{ .first_record = 1, .last_record = 4 }, null, &.{})).?;
    defer plan.deinit(allocator);
    try std.testing.expectEqualStrings("m2", plan.first_retained_id);
}

test "token arithmetic saturates instead of overflowing" {
    try std.testing.expectEqual(std.math.maxInt(usize), calculateContextTokens(std.math.maxInt(usize), 1, 2, 3));
    try std.testing.expect(shouldCompact(1, 0, .{}));
    try std.testing.expect(!shouldCompact(std.math.maxInt(usize), std.math.maxInt(usize), .{ .enabled = false }));
}

test "local summary preserves chronological evidence ordering" {
    const allocator = std.testing.allocator;
    const messages = try makeMessages(allocator, &.{
        .{ .user, "first evidence" },
        .{ .assistant, "second evidence" },
        .{ .user, "kept suffix" },
    });
    defer freeMessages(allocator, messages);
    var result = try compactLocal(allocator, messages, 1, "prior evidence");
    defer result.deinit(allocator);
    const prior = std.mem.indexOf(u8, result.summary, "prior evidence").?;
    const first = std.mem.indexOf(u8, result.summary, "first evidence").?;
    const second = std.mem.indexOf(u8, result.summary, "second evidence").?;
    try std.testing.expect(prior < first);
    try std.testing.expect(first < second);
    try std.testing.expect(std.mem.indexOf(u8, result.summary, "kept suffix") == null);
}
