const std = @import("std");

pub const EffectKind = enum { inspect, mutate, verify, observe };
pub const Enforcement = enum { observe, block, correct };
pub const Effect = struct { kind: EffectKind, resource: []const u8 };
pub const ActionStatus = enum { pending, succeeded, failed, blocked };
pub const ObligationKind = enum { inspect_before_mutation, verify_after_mutation };
pub const ObligationStatus = enum { open, satisfied, invalidated };
pub const ClaimStatus = enum { current, stale };
pub const DiagnosticCode = enum { inspect_before_mutation, verify_after_mutation, repeated_failure };
pub const Diagnostic = struct { code: DiagnosticCode, resource: ?[]const u8 = null, count: usize = 0 };
pub const EvidenceKind = enum { inspect, verify, observe };

pub const DiagnosticList = struct {
    allocator: std.mem.Allocator,
    items: []Diagnostic,
    pub fn deinit(self: *DiagnosticList) void {
        self.allocator.free(self.items);
        self.* = undefined;
    }
};

pub const Evidence = struct {
    id: []u8,
    action_id: []u8,
    kind: EvidenceKind,
    resource: []u8,
    revision: u64,
};
pub const Change = struct { id: []u8, action_id: []u8, resource: []u8, revision: u64 };
pub const Obligation = struct {
    id: []u8,
    kind: ObligationKind,
    resource: []u8,
    revision: u64,
    status: ObligationStatus,
    evidence_id: ?[]const u8 = null,
};
pub const Claim = struct {
    id: []u8,
    statement: []u8,
    evidence_ids: [][]const u8,
    status: ClaimStatus,
};
const OwnedEffect = struct { kind: EffectKind, resource: []u8 };
pub const Action = struct {
    id: []u8,
    tool_name: []u8,
    canonical_input: []u8,
    effects: []OwnedEffect,
    missed_inspections: [][]const u8,
    status: ActionStatus,
    started_at_ms: i64,
    duration_ms: ?u64 = null,
};
pub const Costs = struct { tool_calls: u64 = 0, failures: u64 = 0, blocked_calls: u64 = 0, duration_ms: u64 = 0 };

pub const Runtime = struct {
    allocator: std.mem.Allocator,
    enforcement: Enforcement,
    goal: []u8,
    actions: std.ArrayList(Action) = .empty,
    evidence: std.ArrayList(Evidence) = .empty,
    changes: std.ArrayList(Change) = .empty,
    obligations: std.ArrayList(Obligation) = .empty,
    claims: std.ArrayList(Claim) = .empty,
    costs: Costs = .{},

    pub fn init(allocator: std.mem.Allocator, enforcement: Enforcement) !Runtime {
        return .{ .allocator = allocator, .enforcement = enforcement, .goal = try allocator.dupe(u8, "") };
    }

    pub fn deinit(self: *Runtime) void {
        self.clear();
        self.actions.deinit(self.allocator);
        self.evidence.deinit(self.allocator);
        self.changes.deinit(self.allocator);
        self.obligations.deinit(self.allocator);
        self.claims.deinit(self.allocator);
    }

    pub fn start(self: *Runtime, goal: []const u8) !void {
        self.clear();
        self.goal = try self.allocator.dupe(u8, std.mem.trim(u8, goal, " \t\r\n"));
    }

    fn clear(self: *Runtime) void {
        self.allocator.free(self.goal);
        for (self.actions.items) |*item| deinitAction(self.allocator, item);
        for (self.evidence.items) |*item| deinitEvidence(self.allocator, item);
        for (self.changes.items) |*item| deinitChange(self.allocator, item);
        for (self.obligations.items) |*item| deinitObligation(self.allocator, item);
        for (self.claims.items) |*item| deinitClaim(self.allocator, item);
        self.actions.clearRetainingCapacity();
        self.evidence.clearRetainingCapacity();
        self.changes.clearRetainingCapacity();
        self.obligations.clearRetainingCapacity();
        self.claims.clearRetainingCapacity();
        self.costs = .{};
        self.goal = &.{};
    }

    pub fn beforeAction(self: *Runtime, id: []const u8, tool_name: []const u8, input_json: []const u8, effects: []const Effect, started_at_ms: i64) !DiagnosticList {
        if (self.findAction(id) != null) return error.DuplicateActionId;
        const canonical = try canonicalJson(self.allocator, input_json);
        errdefer self.allocator.free(canonical);

        var owned_effects: std.ArrayList(OwnedEffect) = .empty;
        errdefer {
            for (owned_effects.items) |item| self.allocator.free(item.resource);
            owned_effects.deinit(self.allocator);
        }
        for (effects, 0..) |effect, index| {
            if (effect.resource.len == 0) return error.EmptyEffectResource;
            var duplicate = false;
            for (effects[0..index]) |prior| {
                if (prior.kind == effect.kind and std.mem.eql(u8, prior.resource, effect.resource)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) try owned_effects.append(self.allocator, .{ .kind = effect.kind, .resource = try self.allocator.dupe(u8, effect.resource) });
        }

        var diagnostics: std.ArrayList(Diagnostic) = .empty;
        errdefer diagnostics.deinit(self.allocator);
        var missed: std.ArrayList([]const u8) = .empty;
        errdefer missed.deinit(self.allocator);
        for (owned_effects.items) |effect| {
            if (effect.kind != .mutate or self.hasCurrentEvidence(.inspect, effect.resource)) continue;
            const revision = self.currentRevision(effect.resource);
            try self.ensureObligation(.inspect_before_mutation, effect.resource, revision);
            try diagnostics.append(self.allocator, .{ .code = .inspect_before_mutation, .resource = effect.resource });
            try missed.append(self.allocator, effect.resource);
        }
        const blocked = diagnostics.items.len != 0 and self.enforcement == .block;
        try self.actions.append(self.allocator, .{
            .id = try self.allocator.dupe(u8, id),
            .tool_name = try self.allocator.dupe(u8, tool_name),
            .canonical_input = canonical,
            .effects = try owned_effects.toOwnedSlice(self.allocator),
            .missed_inspections = try missed.toOwnedSlice(self.allocator),
            .status = if (blocked) .blocked else .pending,
            .started_at_ms = started_at_ms,
        });
        self.costs.tool_calls += 1;
        if (blocked) self.costs.blocked_calls += 1;
        return .{ .allocator = self.allocator, .items = try diagnostics.toOwnedSlice(self.allocator) };
    }

    pub fn afterAction(self: *Runtime, id: []const u8, is_error: bool, finished_at_ms: i64) !DiagnosticList {
        const index = self.findAction(id) orelse return error.UnknownActionId;
        var action = &self.actions.items[index];
        if (action.status == .blocked) return .{ .allocator = self.allocator, .items = try self.allocator.alloc(Diagnostic, 0) };
        if (action.status != .pending) return error.UnknownActionId;
        const elapsed: u64 = if (finished_at_ms > action.started_at_ms) @intCast(finished_at_ms - action.started_at_ms) else 0;
        action.duration_ms = elapsed;
        self.costs.duration_ms += elapsed;
        var diagnostics: std.ArrayList(Diagnostic) = .empty;
        errdefer diagnostics.deinit(self.allocator);
        for (action.missed_inspections) |resource| try diagnostics.append(self.allocator, .{ .code = .inspect_before_mutation, .resource = resource });
        if (is_error) {
            action.status = .failed;
            self.costs.failures += 1;
            const count = self.failureCount(action.tool_name, action.canonical_input);
            if (count >= 2) try diagnostics.append(self.allocator, .{ .code = .repeated_failure, .count = count });
        } else {
            action.status = .succeeded;
            for (action.effects) |effect| try self.applyEffect(action.id, effect);
        }
        return .{ .allocator = self.allocator, .items = try diagnostics.toOwnedSlice(self.allocator) };
    }

    pub fn completionDiagnostics(self: *Runtime) !DiagnosticList {
        var diagnostics: std.ArrayList(Diagnostic) = .empty;
        errdefer diagnostics.deinit(self.allocator);
        for (self.obligations.items) |obligation| {
            if (obligation.kind == .verify_after_mutation and obligation.status == .open)
                try diagnostics.append(self.allocator, .{ .code = .verify_after_mutation, .resource = obligation.resource });
        }
        return .{ .allocator = self.allocator, .items = try diagnostics.toOwnedSlice(self.allocator) };
    }

    pub fn addClaim(self: *Runtime, id: []const u8, statement: []const u8, evidence_ids: []const []const u8) !void {
        for (self.claims.items) |claim| if (std.mem.eql(u8, claim.id, id)) return error.DuplicateClaimId;
        if (evidence_ids.len == 0) return error.ClaimRequiresEvidence;
        var ids: std.ArrayList([]const u8) = .empty;
        defer ids.deinit(self.allocator);
        for (evidence_ids) |evidence_id| {
            const item = self.findEvidence(evidence_id) orelse return error.MissingOrStaleEvidence;
            if (item.revision != self.currentRevision(item.resource)) return error.MissingOrStaleEvidence;
            var duplicate = false;
            for (ids.items) |existing| if (std.mem.eql(u8, existing, evidence_id)) {
                duplicate = true;
                break;
            };
            if (!duplicate) try ids.append(self.allocator, item.id);
        }
        try self.claims.append(self.allocator, .{
            .id = try self.allocator.dupe(u8, id),
            .statement = try self.allocator.dupe(u8, std.mem.trim(u8, statement, " \t\r\n")),
            .evidence_ids = try ids.toOwnedSlice(self.allocator),
            .status = .current,
        });
    }

    fn applyEffect(self: *Runtime, action_id: []const u8, effect: OwnedEffect) !void {
        if (effect.kind == .verify and std.mem.eql(u8, effect.resource, "*")) {
            for (self.obligations.items) |obligation| {
                if (obligation.kind == .verify_after_mutation and obligation.status == .open)
                    try self.addEvidence(action_id, .verify, obligation.resource);
            }
            return;
        }
        switch (effect.kind) {
            .mutate => {
                const revision = self.currentRevision(effect.resource) + 1;
                for (self.claims.items) |*claim| {
                    for (claim.evidence_ids) |evidence_id| {
                        const item = self.findEvidence(evidence_id).?;
                        if (std.mem.eql(u8, item.resource, effect.resource) and item.revision != revision) claim.status = .stale;
                    }
                }
                for (self.obligations.items) |*obligation| {
                    if (std.mem.eql(u8, obligation.resource, effect.resource) and obligation.status != .invalidated)
                        obligation.status = .invalidated;
                }
                try self.changes.append(self.allocator, .{
                    .id = try std.fmt.allocPrint(self.allocator, "change:{d}", .{self.changes.items.len + 1}),
                    .action_id = try self.allocator.dupe(u8, action_id),
                    .resource = try self.allocator.dupe(u8, effect.resource),
                    .revision = revision,
                });
                try self.ensureObligation(.verify_after_mutation, effect.resource, revision);
            },
            .inspect => try self.addEvidence(action_id, .inspect, effect.resource),
            .verify => try self.addEvidence(action_id, .verify, effect.resource),
            .observe => try self.addEvidence(action_id, .observe, effect.resource),
        }
    }

    fn addEvidence(self: *Runtime, action_id: []const u8, kind: EvidenceKind, resource: []const u8) !void {
        try self.evidence.append(self.allocator, .{
            .id = try std.fmt.allocPrint(self.allocator, "evidence:{d}", .{self.evidence.items.len + 1}),
            .action_id = try self.allocator.dupe(u8, action_id),
            .kind = kind,
            .resource = try self.allocator.dupe(u8, resource),
            .revision = self.currentRevision(resource),
        });
        const evidence = &self.evidence.items[self.evidence.items.len - 1];
        const target_kind: ?ObligationKind = switch (kind) {
            .inspect => .inspect_before_mutation,
            .verify => .verify_after_mutation,
            .observe => null,
        };
        if (target_kind) |wanted| for (self.obligations.items) |*obligation| {
            if (obligation.kind == wanted and obligation.status == .open and obligation.revision == evidence.revision and std.mem.eql(u8, obligation.resource, resource)) {
                obligation.status = .satisfied;
                obligation.evidence_id = evidence.id;
            }
        };
    }

    fn ensureObligation(self: *Runtime, kind: ObligationKind, resource: []const u8, revision: u64) !void {
        for (self.obligations.items) |obligation| {
            if (obligation.kind == kind and obligation.status == .open and obligation.revision == revision and std.mem.eql(u8, obligation.resource, resource)) return;
        }
        const prefix = if (kind == .inspect_before_mutation) "inspect" else "verify";
        try self.obligations.append(self.allocator, .{
            .id = try std.fmt.allocPrint(self.allocator, "{s}:{s}:{d}", .{ prefix, resource, revision }),
            .kind = kind,
            .resource = try self.allocator.dupe(u8, resource),
            .revision = revision,
            .status = .open,
        });
    }

    fn hasCurrentEvidence(self: *const Runtime, kind: EvidenceKind, resource: []const u8) bool {
        for (self.evidence.items) |item| if (item.kind == kind and item.revision == self.currentRevision(resource) and std.mem.eql(u8, item.resource, resource)) return true;
        return false;
    }
    fn currentRevision(self: *const Runtime, resource: []const u8) u64 {
        var result: u64 = 0;
        for (self.changes.items) |item| {
            if (std.mem.eql(u8, item.resource, resource)) result = @max(result, item.revision);
        }
        return result;
    }
    fn findAction(self: *const Runtime, id: []const u8) ?usize {
        for (self.actions.items, 0..) |item, index| if (std.mem.eql(u8, item.id, id)) return index;
        return null;
    }
    fn findEvidence(self: *const Runtime, id: []const u8) ?*const Evidence {
        for (self.evidence.items) |*item| if (std.mem.eql(u8, item.id, id)) return item;
        return null;
    }
    fn failureCount(self: *const Runtime, tool_name: []const u8, canonical_input: []const u8) usize {
        var count: usize = 0;
        var index = self.actions.items.len;
        while (index > 0) {
            index -= 1;
            const action = self.actions.items[index];
            if (!std.mem.eql(u8, action.tool_name, tool_name) or !std.mem.eql(u8, action.canonical_input, canonical_input)) continue;
            if (action.status == .succeeded) break;
            if (action.status == .failed) count += 1;
        }
        return count;
    }
};

pub const CommonEffects = struct {
    storage: [2]Effect = undefined,
    count: u2 = 0,
    pub fn slice(self: *const CommonEffects) []const Effect {
        return self.storage[0..self.count];
    }
    fn add(self: *CommonEffects, kind: EffectKind, resource: ?[]const u8) void {
        if (resource) |value| if (value.len != 0) {
            self.storage[self.count] = .{ .kind = kind, .resource = value };
            self.count += 1;
        };
    }
};

pub fn resolveCommonToolEffects(tool_name: []const u8, input: std.json.Value) CommonEffects {
    var result = CommonEffects{};
    if (isOneOf(tool_name, &.{ "test", "verify", "typecheck", "build", "lint", "check" })) {
        result.add(.verify, "*");
        return result;
    }
    if (input != .object) return result;
    const path = firstString(input.object, &.{ "path", "file_path", "filePath" });
    if (isOneOf(tool_name, &.{ "read", "read_file", "grep", "grep_files", "glob", "glob_files", "list", "list_files" })) result.add(.inspect, path) else if (isOneOf(tool_name, &.{ "edit", "edit_file", "write", "write_file", "delete", "delete_file", "create_folder" })) result.add(.mutate, path) else if (isOneOf(tool_name, &.{ "rename", "rename_file" })) {
        result.add(.mutate, firstString(input.object, &.{"old_path"}));
        result.add(.mutate, firstString(input.object, &.{"new_path"}));
    } else if (isOneOf(tool_name, &.{ "copy", "copy_file" })) {
        result.add(.inspect, firstString(input.object, &.{"source"}));
        result.add(.mutate, firstString(input.object, &.{"destination"}));
    }
    return result;
}

fn canonicalJson(allocator: std.mem.Allocator, source: []const u8) ![]u8 {
    const parsed = try std.json.parseFromSlice(std.json.Value, allocator, source, .{});
    defer parsed.deinit();
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);
    try appendCanonical(allocator, &output, parsed.value);
    return output.toOwnedSlice(allocator);
}

fn appendCanonical(allocator: std.mem.Allocator, output: *std.ArrayList(u8), value: std.json.Value) !void {
    switch (value) {
        .null => try output.appendSlice(allocator, "null"),
        .bool => |item| try output.appendSlice(allocator, if (item) "true" else "false"),
        .integer => |item| try output.print(allocator, "{d}", .{item}),
        .float => |item| try output.print(allocator, "{d}", .{item}),
        .number_string => |item| try output.appendSlice(allocator, item),
        .string => |item| try appendJsonString(allocator, output, item),
        .array => |items| {
            try output.append(allocator, '[');
            for (items.items, 0..) |item, index| {
                if (index != 0) try output.append(allocator, ',');
                try appendCanonical(allocator, output, item);
            }
            try output.append(allocator, ']');
        },
        .object => |object| {
            var keys: std.ArrayList([]const u8) = .empty;
            defer keys.deinit(allocator);
            var iterator = object.iterator();
            while (iterator.next()) |entry| try keys.append(allocator, entry.key_ptr.*);
            std.mem.sort([]const u8, keys.items, {}, lessString);
            try output.append(allocator, '{');
            for (keys.items, 0..) |key, index| {
                if (index != 0) try output.append(allocator, ',');
                try appendJsonString(allocator, output, key);
                try output.append(allocator, ':');
                try appendCanonical(allocator, output, object.get(key).?);
            }
            try output.append(allocator, '}');
        },
    }
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

fn isOneOf(value: []const u8, choices: []const []const u8) bool {
    for (choices) |choice| if (std.mem.eql(u8, value, choice)) return true;
    return false;
}
fn firstString(object: std.json.ObjectMap, keys: []const []const u8) ?[]const u8 {
    for (keys) |key| if (object.get(key)) |value| if (value == .string and value.string.len != 0) return value.string;
    return null;
}
fn lessString(_: void, left: []const u8, right: []const u8) bool {
    return std.mem.order(u8, left, right) == .lt;
}
fn deinitAction(allocator: std.mem.Allocator, item: *Action) void {
    allocator.free(item.id);
    allocator.free(item.tool_name);
    allocator.free(item.canonical_input);
    for (item.effects) |effect| allocator.free(effect.resource);
    allocator.free(item.effects);
    allocator.free(item.missed_inspections);
}
fn deinitEvidence(allocator: std.mem.Allocator, item: *Evidence) void {
    allocator.free(item.id);
    allocator.free(item.action_id);
    allocator.free(item.resource);
}
fn deinitChange(allocator: std.mem.Allocator, item: *Change) void {
    allocator.free(item.id);
    allocator.free(item.action_id);
    allocator.free(item.resource);
}
fn deinitObligation(allocator: std.mem.Allocator, item: *Obligation) void {
    allocator.free(item.id);
    allocator.free(item.resource);
}
fn deinitClaim(allocator: std.mem.Allocator, item: *Claim) void {
    allocator.free(item.id);
    allocator.free(item.statement);
    allocator.free(item.evidence_ids);
}

fn succeed(runtime: *Runtime, id: []const u8, tool: []const u8, input: []const u8, effects: []const Effect) !void {
    var before = try runtime.beforeAction(id, tool, input, effects, 10);
    before.deinit();
    var after = try runtime.afterAction(id, false, 10);
    after.deinit();
}

test "inspect mutate verify obligations and claims follow revisions" {
    var runtime = try Runtime.init(std.testing.allocator, .correct);
    defer runtime.deinit();
    try runtime.start(" change the file ");
    try succeed(&runtime, "read-1", "inspect", "{\"resource\":\"a.ts\"}", &.{.{ .kind = .inspect, .resource = "a.ts" }});
    try runtime.addClaim("claim-1", " file exists ", &.{ "evidence:1", "evidence:1" });
    try std.testing.expectEqual(@as(usize, 1), runtime.claims.items[0].evidence_ids.len);
    try succeed(&runtime, "edit-1", "mutate", "{}", &.{.{ .kind = .mutate, .resource = "a.ts" }});
    try std.testing.expectEqual(ClaimStatus.stale, runtime.claims.items[0].status);
    var completion = try runtime.completionDiagnostics();
    defer completion.deinit();
    try std.testing.expectEqual(@as(usize, 1), completion.items.len);
    try std.testing.expectEqual(DiagnosticCode.verify_after_mutation, completion.items[0].code);
    try succeed(&runtime, "test-1", "verify", "{}", &.{.{ .kind = .verify, .resource = "a.ts" }});
    var done = try runtime.completionDiagnostics();
    defer done.deinit();
    try std.testing.expectEqual(@as(usize, 0), done.items.len);
}

test "block mode requires inspection and wildcard verification covers resources" {
    var runtime = try Runtime.init(std.testing.allocator, .block);
    defer runtime.deinit();
    try runtime.start("goal");
    var blocked = try runtime.beforeAction("edit-a", "mutate", "{}", &.{.{ .kind = .mutate, .resource = "a.ts" }}, 0);
    defer blocked.deinit();
    try std.testing.expectEqual(ActionStatus.blocked, runtime.actions.items[0].status);
    try std.testing.expectEqual(@as(usize, 1), blocked.items.len);
    try succeed(&runtime, "read-a", "inspect", "{}", &.{.{ .kind = .inspect, .resource = "a.ts" }});
    try succeed(&runtime, "edit-a-2", "mutate", "{}", &.{.{ .kind = .mutate, .resource = "a.ts" }});
    try succeed(&runtime, "read-b", "inspect", "{}", &.{.{ .kind = .inspect, .resource = "b.ts" }});
    try succeed(&runtime, "edit-b", "mutate", "{}", &.{.{ .kind = .mutate, .resource = "b.ts" }});
    try succeed(&runtime, "test-all", "verify", "{}", &.{.{ .kind = .verify, .resource = "*" }});
    var completion = try runtime.completionDiagnostics();
    defer completion.deinit();
    try std.testing.expectEqual(@as(usize, 0), completion.items.len);
}

test "canonical object ordering detects repeated failures and success resets it" {
    var runtime = try Runtime.init(std.testing.allocator, .correct);
    defer runtime.deinit();
    try runtime.start("goal");
    var before_one = try runtime.beforeAction("one", "read", "{\"resource\":\"a.ts\",\"z\":1}", &.{.{ .kind = .inspect, .resource = "a.ts" }}, 0);
    before_one.deinit();
    var one = try runtime.afterAction("one", true, 0);
    one.deinit();
    var before_two = try runtime.beforeAction("two", "read", "{\"z\":1,\"resource\":\"a.ts\"}", &.{.{ .kind = .inspect, .resource = "a.ts" }}, 0);
    before_two.deinit();
    var two = try runtime.afterAction("two", true, 0);
    defer two.deinit();
    try std.testing.expectEqual(DiagnosticCode.repeated_failure, two.items[0].code);
    try succeed(&runtime, "three", "read", "{\"resource\":\"a.ts\",\"z\":1}", &.{.{ .kind = .inspect, .resource = "a.ts" }});
    var before_four = try runtime.beforeAction("four", "read", "{\"z\":1,\"resource\":\"a.ts\"}", &.{.{ .kind = .inspect, .resource = "a.ts" }}, 0);
    before_four.deinit();
    var four = try runtime.afterAction("four", true, 0);
    defer four.deinit();
    try std.testing.expectEqual(@as(usize, 0), four.items.len);
}

test "common tool effects use exact names and structured path fields" {
    const allocator = std.testing.allocator;
    const parsed = try std.json.parseFromSlice(std.json.Value, allocator, "{\"old_path\":\"a.ts\",\"new_path\":\"b.ts\"}", .{});
    defer parsed.deinit();
    const rename = resolveCommonToolEffects("rename_file", parsed.value);
    try std.testing.expectEqual(@as(usize, 2), rename.slice().len);
    try std.testing.expectEqual(EffectKind.mutate, rename.slice()[0].kind);
    const ignored = resolveCommonToolEffects("terminal", parsed.value);
    try std.testing.expectEqual(@as(usize, 0), ignored.slice().len);
    const verify = resolveCommonToolEffects("build", .null);
    try std.testing.expectEqualStrings("*", verify.slice()[0].resource);
}
