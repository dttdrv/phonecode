const std = @import("std");
const compaction = @import("compaction");

fn render(_: *anyopaque, messages: []const compaction.CompactionMessage, summary: ?[]const u8) !compaction.Metrics {
    var bytes: usize = if (summary) |value| value.len + 16 else 0;
    for (messages) |message| bytes += 16 + message.id.len + @tagName(message.role).len + message.content.len + message.causal_unit_id.len;
    return .{ .serialized_bytes = bytes, .provider_input_tokens = (bytes + 3) / 4, .provenance = .{ .provider = "guard-provider", .model = "guard-model", .canonical_request_schema = "guard-request-v1", .canonical_request_version = 1, .token_basis = "guard-provider-count" } };
}

pub fn main() !void {
    const allocator = std.heap.page_allocator;
    const long = "x" ** 1024;
    const messages = [_]compaction.CompactionMessage{
        .{ .id = "m1", .role = .user, .content = long },
        .{ .id = "m2", .role = .assistant, .content = long },
        .{ .id = "m3", .role = .user, .content = "recent" },
    };
    var context: u8 = 0;
    const prior = "provider prior summary";
    var plan = (try compaction.preparePlanWithProviderSummary(
        allocator,
        &messages,
        200,
        .{ .first_record = 1, .last_record = 3 },
        .{ .id = "epoch-0", .summary = prior, .summary_digest = compaction.summaryDigest(prior), .metrics = .{ .serialized_bytes = 1200, .provider_input_tokens = 300, .provenance = .{ .provider = "guard-provider", .model = "guard-model", .canonical_request_schema = "guard-request-v1", .canonical_request_version = 1, .token_basis = "guard-provider-count" } } },
        &.{},
        "provider prior summary\nnew facts",
        &context,
        render,
    )).?;
    defer plan.deinit(allocator);
    if (!std.mem.eql(u8, plan.summary, "provider prior summary\nnew facts")) return error.ProviderSummaryWasNotFolded;
    if (plan.after.serialized_bytes >= plan.before.serialized_bytes or plan.after.provider_input_tokens >= plan.before.provider_input_tokens) return error.NonReducingProviderPlan;
    var epoch = try plan.commit(allocator);
    defer epoch.deinit(allocator);
    if (epoch.predecessor_metrics == null or !std.mem.eql(u8, epoch.summary, plan.summary)) return error.IncompleteDurableEpoch;
}
