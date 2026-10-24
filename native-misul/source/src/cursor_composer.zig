const std = @import("std");

pub const HunkLine = struct { tag: u8, text: []const u8 };

pub const Hunk = struct {
    old_start: u32,
    old_count: u32,
    new_start: u32,
    new_count: u32,
    affected_old_start: u32,
    affected_old_end: u32,
    lines: []HunkLine,
};

pub const Conflict = struct {
    path: []const u8,
    reason: []const u8,
};

pub const FileEdit = struct {
    path: []u8,
    original: []u8,
    proposed: []u8,
    hunks: []Hunk,
};

pub const Composer = struct {
    allocator: std.mem.Allocator,
    files: std.ArrayList(FileEdit) = .empty,
    conflicts: std.ArrayList(Conflict) = .empty,

    pub fn init(allocator: std.mem.Allocator) Composer {
        return .{ .allocator = allocator };
    }

    pub fn deinit(self: *Composer) void {
        for (self.files.items) |*file| deinitFile(file, self.allocator);
        self.files.deinit(self.allocator);
        self.conflicts.deinit(self.allocator);
        self.* = undefined;
    }

    pub fn speculate(self: *Composer, path: []const u8, original: []const u8, proposed: []const u8) !void {
        const hunks = try generateHunks(self.allocator, original, proposed);
        errdefer freeHunks(self.allocator, hunks);

        if (self.findFile(path)) |index| {
            const existing = &self.files.items[index];
            if (!std.mem.eql(u8, existing.original, original)) {
                freeHunks(self.allocator, hunks);
                try self.conflicts.append(self.allocator, .{ .path = existing.path, .reason = "baseline mismatch" });
                return;
            }
            if (rangesOverlap(existing.hunks, hunks)) {
                freeHunks(self.allocator, hunks);
                try self.conflicts.append(self.allocator, .{ .path = existing.path, .reason = "overlapping hunks" });
                return;
            }
            const merged = try mergeProposed(self.allocator, original, existing.hunks, hunks);
            freeHunks(self.allocator, hunks);
            self.allocator.free(existing.proposed);
            existing.proposed = merged;
            freeHunks(self.allocator, existing.hunks);
            existing.hunks = try generateHunks(self.allocator, original, existing.proposed);
            return;
        }

        try self.files.append(self.allocator, .{
            .path = try self.allocator.dupe(u8, path),
            .original = try self.allocator.dupe(u8, original),
            .proposed = try self.allocator.dupe(u8, proposed),
            .hunks = hunks,
        });
    }

    pub fn unifiedDiff(self: *const Composer, allocator: std.mem.Allocator) ![]u8 {
        var out: std.ArrayList(u8) = .empty;
        errdefer out.deinit(allocator);
        for (self.files.items, 0..) |file, index| {
            if (index != 0) try out.append(allocator, '\n');
            try writeFileDiff(allocator, &out, file);
        }
        return out.toOwnedSlice(allocator);
    }

    fn findFile(self: *Composer, path: []const u8) ?usize {
        for (self.files.items, 0..) |file, index| {
            if (std.mem.eql(u8, file.path, path)) return index;
        }
        return null;
    }
};

pub fn generateHunks(allocator: std.mem.Allocator, original: []const u8, proposed: []const u8) ![]Hunk {
    const old_lines = try splitLines(allocator, original);
    defer allocator.free(old_lines);
    const new_lines = try splitLines(allocator, proposed);
    defer allocator.free(new_lines);
    const script = try buildScript(allocator, old_lines, new_lines);
    defer allocator.free(script);
    return clusterHunks(allocator, script);
}

fn deinitFile(file: *FileEdit, allocator: std.mem.Allocator) void {
    allocator.free(file.path);
    allocator.free(file.original);
    allocator.free(file.proposed);
    freeHunks(allocator, file.hunks);
}

fn freeHunks(allocator: std.mem.Allocator, hunks: []Hunk) void {
    for (hunks) |hunk| allocator.free(hunk.lines);
    allocator.free(hunks);
}

const ScriptLine = struct {
    tag: u8,
    old_line: u32,
    new_line: u32,
    text: []const u8,
};

fn splitLines(allocator: std.mem.Allocator, text: []const u8) ![][]const u8 {
    if (text.len == 0) return allocator.alloc([]const u8, 0);
    var lines: std.ArrayList([]const u8) = .empty;
    errdefer lines.deinit(allocator);
    var it = std.mem.splitScalar(u8, text, '\n');
    while (it.next()) |line| try lines.append(allocator, line);
    if (lines.items.len > 0 and lines.items[lines.items.len - 1].len == 0 and std.mem.endsWith(u8, text, "\n")) {
        _ = lines.pop();
    }
    return lines.toOwnedSlice(allocator);
}

fn buildScript(allocator: std.mem.Allocator, a: []const []const u8, b: []const []const u8) ![]ScriptLine {
    const a_keep = try lcsKeep(allocator, a, b);
    defer allocator.free(a_keep.a);
    defer allocator.free(a_keep.b);

    var script: std.ArrayList(ScriptLine) = .empty;
    errdefer script.deinit(allocator);
    var i: usize = 0;
    var j: usize = 0;
    var old_line: u32 = 1;
    var new_line: u32 = 1;
    while (i < a.len or j < b.len) {
        if (i < a.len and j < b.len and a_keep.a[i] and a_keep.b[j] and std.mem.eql(u8, a[i], b[j])) {
            try script.append(allocator, .{ .tag = ' ', .old_line = old_line, .new_line = new_line, .text = a[i] });
            i += 1;
            j += 1;
            old_line += 1;
            new_line += 1;
        } else if (i < a.len and !a_keep.a[i]) {
            try script.append(allocator, .{ .tag = '-', .old_line = old_line, .new_line = new_line, .text = a[i] });
            i += 1;
            old_line += 1;
        } else if (j < b.len and !a_keep.b[j]) {
            try script.append(allocator, .{ .tag = '+', .old_line = old_line, .new_line = new_line, .text = b[j] });
            j += 1;
            new_line += 1;
        } else if (i < a.len) {
            try script.append(allocator, .{ .tag = '-', .old_line = old_line, .new_line = new_line, .text = a[i] });
            i += 1;
            old_line += 1;
        } else {
            try script.append(allocator, .{ .tag = '+', .old_line = old_line, .new_line = new_line, .text = b[j] });
            j += 1;
            new_line += 1;
        }
    }
    return script.toOwnedSlice(allocator);
}

const Keep = struct { a: []bool, b: []bool };

fn lcsKeep(allocator: std.mem.Allocator, a: []const []const u8, b: []const []const u8) !Keep {
    const n = a.len;
    const m = b.len;
    var a_keep = try allocator.alloc(bool, n);
    errdefer allocator.free(a_keep);
    var b_keep = try allocator.alloc(bool, m);
    errdefer allocator.free(b_keep);
    @memset(a_keep, false);
    @memset(b_keep, false);
    if (n == 0 or m == 0) return .{ .a = a_keep, .b = b_keep };

    // ponytail: O(nm) LCS; switch to Myers if files grow past a few thousand lines.
    if (n > 2048 or m > 2048) {
        return .{ .a = a_keep, .b = b_keep };
    }

    const cols = m + 1;
    const dp = try allocator.alloc(u32, (n + 1) * cols);
    defer allocator.free(dp);
    @memset(dp, 0);
    var i: usize = 1;
    while (i <= n) : (i += 1) {
        var j: usize = 1;
        while (j <= m) : (j += 1) {
            dp[i * cols + j] = if (std.mem.eql(u8, a[i - 1], b[j - 1]))
                dp[(i - 1) * cols + (j - 1)] + 1
            else
                @max(dp[(i - 1) * cols + j], dp[i * cols + (j - 1)]);
        }
    }
    i = n;
    var j = m;
    while (i > 0 and j > 0) {
        if (std.mem.eql(u8, a[i - 1], b[j - 1])) {
            a_keep[i - 1] = true;
            b_keep[j - 1] = true;
            i -= 1;
            j -= 1;
        } else if (dp[(i - 1) * cols + j] >= dp[i * cols + (j - 1)]) {
            i -= 1;
        } else {
            j -= 1;
        }
    }
    return .{ .a = a_keep, .b = b_keep };
}

fn clusterHunks(allocator: std.mem.Allocator, script: []const ScriptLine) ![]Hunk {
    var hunks: std.ArrayList(Hunk) = .empty;
    errdefer {
        for (hunks.items) |hunk| allocator.free(hunk.lines);
        hunks.deinit(allocator);
    }
    const ctx: usize = 3;
    var i: usize = 0;
    while (i < script.len) {
        while (i < script.len and script[i].tag == ' ') i += 1;
        if (i == script.len) break;
        const change_lo = i;
        var change_hi = i + 1;
        while (change_hi < script.len) {
            var peek = change_hi;
            var eq: usize = 0;
            while (peek < script.len and script[peek].tag == ' ') {
                eq += 1;
                peek += 1;
            }
            if (eq > ctx * 2 or peek == script.len) break;
            if (peek < script.len and script[peek].tag != ' ') {
                change_hi = peek + 1;
            } else break;
        }
        while (change_hi < script.len and script[change_hi].tag != ' ') change_hi += 1;

        const start = change_lo -| ctx;
        var end = change_hi + ctx;
        if (end > script.len) end = script.len;

        var lines: std.ArrayList(HunkLine) = .empty;
        errdefer lines.deinit(allocator);
        var old_count: u32 = 0;
        var new_count: u32 = 0;
        var affected_start: ?u32 = null;
        var affected_end: u32 = 0;
        for (script[start..end]) |line| {
            try lines.append(allocator, .{ .tag = line.tag, .text = line.text });
            if (line.tag != '+') old_count += 1;
            if (line.tag != '-') new_count += 1;
            if (line.tag != ' ') {
                if (affected_start == null) affected_start = line.old_line -| 1;
                affected_end = if (line.tag == '-') line.old_line else line.old_line -| 1;
            }
        }
        const first = script[start];
        try hunks.append(allocator, .{
            .old_start = if (old_count == 0) first.old_line -| 1 else first.old_line,
            .old_count = old_count,
            .new_start = if (new_count == 0) first.new_line -| 1 else first.new_line,
            .new_count = new_count,
            .affected_old_start = affected_start orelse (first.old_line -| 1),
            .affected_old_end = affected_end,
            .lines = try lines.toOwnedSlice(allocator),
        });
        i = change_hi;
    }
    return hunks.toOwnedSlice(allocator);
}

fn rangesOverlap(left: []const Hunk, right: []const Hunk) bool {
    for (left) |a| {
        for (right) |b| {
            if (intervalOverlap(a.affected_old_start, a.affected_old_end, b.affected_old_start, b.affected_old_end)) return true;
        }
    }
    return false;
}

fn intervalOverlap(a0: u32, a1: u32, b0: u32, b1: u32) bool {
    if (a0 == a1 and b0 == b1) return a0 == b0;
    if (a0 == a1) return a0 >= b0 and a0 < b1;
    if (b0 == b1) return b0 >= a0 and b0 < a1;
    return a0 < b1 and b0 < a1;
}

fn mergeProposed(allocator: std.mem.Allocator, original: []const u8, first: []const Hunk, second: []const Hunk) ![]u8 {
    const old_lines = try splitLines(allocator, original);
    defer allocator.free(old_lines);
    var current: std.ArrayList([]const u8) = .empty;
    defer current.deinit(allocator);
    for (old_lines) |line| try current.append(allocator, line);

    var all: std.ArrayList(Hunk) = .empty;
    defer all.deinit(allocator);
    try all.appendSlice(allocator, first);
    try all.appendSlice(allocator, second);
    std.mem.sort(Hunk, all.items, {}, hunkAfter);

    for (all.items) |hunk| {
        const start = hunk.affected_old_start;
        const end = hunk.affected_old_end;
        var inserted: std.ArrayList([]const u8) = .empty;
        defer inserted.deinit(allocator);
        for (hunk.lines) |line| {
            if (line.tag == '+') try inserted.append(allocator, line.text);
        }
        const start_idx: usize = @intCast(start);
        const end_idx: usize = @intCast(end);
        if (end_idx > current.items.len or start_idx > current.items.len) return error.Conflict;
        try current.replaceRange(allocator, start_idx, end_idx - start_idx, inserted.items);
    }

    var out: std.ArrayList(u8) = .empty;
    errdefer out.deinit(allocator);
    for (current.items, 0..) |line, index| {
        if (index != 0) try out.append(allocator, '\n');
        try out.appendSlice(allocator, line);
    }
    if (original.len > 0 and original[original.len - 1] == '\n') try out.append(allocator, '\n');
    return out.toOwnedSlice(allocator);
}

fn hunkAfter(_: void, left: Hunk, right: Hunk) bool {
    return left.affected_old_start > right.affected_old_start;
}

fn writeFileDiff(allocator: std.mem.Allocator, out: *std.ArrayList(u8), file: FileEdit) !void {
    try out.appendSlice(allocator, "--- a/");
    try out.appendSlice(allocator, file.path);
    try out.appendSlice(allocator, "\n+++ b/");
    try out.appendSlice(allocator, file.path);
    for (file.hunks) |hunk| {
        const header = try std.fmt.allocPrint(allocator, "\n@@ -{d},{d} +{d},{d} @@\n", .{
            hunk.old_start, hunk.old_count, hunk.new_start, hunk.new_count,
        });
        defer allocator.free(header);
        try out.appendSlice(allocator, header);
        for (hunk.lines) |line| {
            try out.append(allocator, line.tag);
            try out.appendSlice(allocator, line.text);
            try out.append(allocator, '\n');
        }
    }
}

test "composer emits a unified diff for one file" {
    const allocator = std.testing.allocator;
    var composer = Composer.init(allocator);
    defer composer.deinit();
    try composer.speculate("src/a.zig", "const x = 1;\nconst y = 2;\n", "const x = 1;\nconst y = 3;\n");
    const diff = try composer.unifiedDiff(allocator);
    defer allocator.free(diff);
    try std.testing.expect(std.mem.indexOf(u8, diff, "--- a/src/a.zig") != null);
    try std.testing.expect(std.mem.indexOf(u8, diff, "-const y = 2;") != null);
    try std.testing.expect(std.mem.indexOf(u8, diff, "+const y = 3;") != null);
    try std.testing.expectEqual(@as(usize, 0), composer.conflicts.items.len);
}

test "composer speculates across multiple files" {
    const allocator = std.testing.allocator;
    var composer = Composer.init(allocator);
    defer composer.deinit();
    try composer.speculate("a.zig", "one\n", "two\n");
    try composer.speculate("b.zig", "old\n", "new\n");
    const diff = try composer.unifiedDiff(allocator);
    defer allocator.free(diff);
    try std.testing.expect(std.mem.indexOf(u8, diff, "--- a/a.zig") != null);
    try std.testing.expect(std.mem.indexOf(u8, diff, "--- a/b.zig") != null);
}

test "overlapping hunks on the same baseline become conflicts" {
    const allocator = std.testing.allocator;
    var composer = Composer.init(allocator);
    defer composer.deinit();
    try composer.speculate("a.zig", "alpha\nbeta\ngamma\n", "ALPHA\nbeta\ngamma\n");
    try composer.speculate("a.zig", "alpha\nbeta\ngamma\n", "ALSO\nbeta\ngamma\n");
    try std.testing.expectEqual(@as(usize, 1), composer.conflicts.items.len);
    try std.testing.expectEqualStrings("overlapping hunks", composer.conflicts.items[0].reason);
}

test "non-overlapping hunks merge in memory" {
    const allocator = std.testing.allocator;
    var composer = Composer.init(allocator);
    defer composer.deinit();
    try composer.speculate("a.zig", "alpha\nbeta\ngamma\n", "ALPHA\nbeta\ngamma\n");
    try composer.speculate("a.zig", "alpha\nbeta\ngamma\n", "alpha\nbeta\nGAMMA\n");
    try std.testing.expectEqual(@as(usize, 0), composer.conflicts.items.len);
    try std.testing.expectEqualStrings("ALPHA\nbeta\nGAMMA\n", composer.files.items[0].proposed);
}
