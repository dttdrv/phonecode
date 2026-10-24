const std = @import("std");

pub const Rule = struct {
    source: []u8,
    description: []u8,
    globs: [][]u8,
    always_apply: bool,
    body: []u8,

    pub fn deinit(self: *Rule, allocator: std.mem.Allocator) void {
        allocator.free(self.source);
        allocator.free(self.description);
        for (self.globs) |glob| allocator.free(glob);
        allocator.free(self.globs);
        allocator.free(self.body);
        self.* = undefined;
    }
};

pub const Set = struct {
    allocator: std.mem.Allocator,
    rules: std.ArrayList(Rule) = .empty,

    pub fn init(allocator: std.mem.Allocator) Set {
        return .{ .allocator = allocator };
    }

    pub fn deinit(self: *Set) void {
        for (self.rules.items) |*rule| rule.deinit(self.allocator);
        self.rules.deinit(self.allocator);
        self.* = undefined;
    }

    pub fn addParsed(self: *Set, path: []const u8, content: []const u8) !void {
        const rule = try parseFile(self.allocator, path, content);
        try self.rules.append(self.allocator, rule);
    }

    pub fn loadPath(self: *Set, io: std.Io, root: []const u8) !void {
        try self.loadNamed(io, root, ".cursorrules");
        try self.loadNamed(io, root, "AGENTS.md");
        const rules_dir = try std.fs.path.join(self.allocator, &.{ root, ".cursor", "rules" });
        defer self.allocator.free(rules_dir);
        var dir = std.Io.Dir.cwd().openDir(io, rules_dir, .{ .iterate = true }) catch return;
        defer dir.close(io);
        var it = dir.iterate();
        while (try it.next(io)) |entry| {
            if (entry.kind != .file) continue;
            const ext = std.fs.path.extension(entry.name);
            if (!std.mem.eql(u8, ext, ".mdc") and !std.mem.eql(u8, ext, ".md")) continue;
            const rel = try std.fs.path.join(self.allocator, &.{ ".cursor/rules", entry.name });
            defer self.allocator.free(rel);
            const data = dir.readFileAlloc(io, entry.name, self.allocator, .limited(1_048_576)) catch continue;
            defer self.allocator.free(data);
            try self.addParsed(rel, data);
        }
    }

    pub fn matching(self: *const Set, target: []const u8, allocator: std.mem.Allocator) ![]const *const Rule {
        var out: std.ArrayList(*const Rule) = .empty;
        errdefer out.deinit(allocator);
        for (self.rules.items) |*rule| {
            if (applies(rule.*, target)) try out.append(allocator, rule);
        }
        return out.toOwnedSlice(allocator);
    }

    pub fn inject(self: *const Set, allocator: std.mem.Allocator, target: []const u8) ![]u8 {
        const matched = try self.matching(target, allocator);
        defer allocator.free(matched);
        var out: std.ArrayList(u8) = .empty;
        errdefer out.deinit(allocator);
        for (matched, 0..) |rule, index| {
            if (index != 0) try out.appendSlice(allocator, "\n\n");
            try out.appendSlice(allocator, "# Rule: ");
            try out.appendSlice(allocator, rule.source);
            if (rule.description.len != 0) {
                try out.appendSlice(allocator, "\n# ");
                try out.appendSlice(allocator, rule.description);
            }
            try out.appendSlice(allocator, "\n");
            try out.appendSlice(allocator, rule.body);
        }
        return out.toOwnedSlice(allocator);
    }

    fn loadNamed(self: *Set, io: std.Io, root: []const u8, name: []const u8) !void {
        const path = try std.fs.path.join(self.allocator, &.{ root, name });
        defer self.allocator.free(path);
        const data = std.Io.Dir.cwd().readFileAlloc(io, path, self.allocator, .limited(1_048_576)) catch return;
        defer self.allocator.free(data);
        try self.addParsed(name, data);
    }
};

pub fn parseFile(allocator: std.mem.Allocator, path: []const u8, content: []const u8) !Rule {
    const base = std.fs.path.basename(path);
    if (std.mem.eql(u8, base, ".cursorrules") or std.mem.eql(u8, base, "AGENTS.md")) {
        return alwaysRule(allocator, path, "", content);
    }
    if (std.mem.eql(u8, std.fs.path.extension(path), ".mdc") or std.mem.startsWith(u8, content, "---")) {
        return parseMdc(allocator, path, content);
    }
    return alwaysRule(allocator, path, "", content);
}

pub fn globMatch(pattern: []const u8, path: []const u8) bool {
    if (std.mem.indexOfScalar(u8, pattern, '/') == null and std.mem.indexOf(u8, pattern, "**") == null) {
        return globAt(pattern, 0, std.fs.path.basename(path), 0) or globAt(pattern, 0, path, 0);
    }
    return globAt(pattern, 0, path, 0);
}

fn alwaysRule(allocator: std.mem.Allocator, path: []const u8, description: []const u8, body: []const u8) !Rule {
    return .{
        .source = try allocator.dupe(u8, path),
        .description = try allocator.dupe(u8, description),
        .globs = try allocator.alloc([]u8, 0),
        .always_apply = true,
        .body = try allocator.dupe(u8, std.mem.trim(u8, body, " \t\r\n")),
    };
}

fn parseMdc(allocator: std.mem.Allocator, path: []const u8, content: []const u8) !Rule {
    const trimmed = std.mem.trimStart(u8, content, " \t\r\n");
    if (!std.mem.startsWith(u8, trimmed, "---")) return alwaysRule(allocator, path, "", content);
    const after_open = trimmed[3..];
    const close_rel = std.mem.indexOf(u8, after_open, "\n---") orelse return alwaysRule(allocator, path, "", content);
    const front = after_open[0..close_rel];
    var body = after_open[close_rel + 4 ..];
    if (body.len > 0 and body[0] == '\n') body = body[1..];

    var description: []const u8 = "";
    var always_apply = false;
    var glob_store: std.ArrayList([]u8) = .empty;
    errdefer {
        for (glob_store.items) |g| allocator.free(g);
        glob_store.deinit(allocator);
    }

    var it = std.mem.splitScalar(u8, front, '\n');
    var in_globs = false;
    while (it.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0) continue;
        if (in_globs) {
            if (std.mem.startsWith(u8, line, "- ")) {
                try glob_store.append(allocator, try allocator.dupe(u8, std.mem.trim(u8, line[2..], " \t\"'")));
                continue;
            }
            in_globs = false;
        }
        if (keyVal(line, "description")) |value| {
            description = value;
        } else if (keyVal(line, "alwaysApply") orelse keyVal(line, "always_apply")) |value| {
            always_apply = std.ascii.eqlIgnoreCase(value, "true");
        } else if (keyVal(line, "globs")) |value| {
            if (value.len == 0) {
                in_globs = true;
            } else {
                var glob_it = std.mem.splitScalar(u8, value, ',');
                while (glob_it.next()) |glob| {
                    const g = std.mem.trim(u8, glob, " \t\"'");
                    if (g.len != 0) try glob_store.append(allocator, try allocator.dupe(u8, g));
                }
            }
        }
    }

    return .{
        .source = try allocator.dupe(u8, path),
        .description = try allocator.dupe(u8, description),
        .globs = try glob_store.toOwnedSlice(allocator),
        .always_apply = always_apply,
        .body = try allocator.dupe(u8, std.mem.trim(u8, body, " \t\r\n")),
    };
}

fn keyVal(line: []const u8, key: []const u8) ?[]const u8 {
    if (!std.mem.startsWith(u8, line, key)) return null;
    var rest = line[key.len..];
    rest = std.mem.trimStart(u8, rest, " \t");
    if (rest.len == 0 or rest[0] != ':') return null;
    return std.mem.trim(u8, rest[1..], " \t\"'");
}

fn applies(rule: Rule, target: []const u8) bool {
    if (rule.always_apply) return true;
    if (rule.globs.len == 0) return true;
    for (rule.globs) |pattern| {
        if (globMatch(pattern, target)) return true;
    }
    return false;
}

fn globAt(pat: []const u8, pi: usize, s: []const u8, si: usize) bool {
    if (pi == pat.len) return si == s.len;
    if (pi + 1 < pat.len and pat[pi] == '*' and pat[pi + 1] == '*') {
        var after = pi + 2;
        if (after < pat.len and pat[after] == '/') after += 1;
        if (after == pat.len) return true;
        var i = si;
        while (true) {
            if (globAt(pat, after, s, i)) return true;
            if (i == s.len) return false;
            if (std.mem.indexOfScalar(u8, s[i..], '/')) |rel| {
                i += rel + 1;
            } else {
                return globAt(pat, after, s, s.len);
            }
        }
    }
    if (pat[pi] == '*') {
        var i = si;
        while (true) {
            if (globAt(pat, pi + 1, s, i)) return true;
            if (i == s.len or s[i] == '/') return false;
            i += 1;
        }
    }
    if (pat[pi] == '?') {
        if (si == s.len or s[si] == '/') return false;
        return globAt(pat, pi + 1, s, si + 1);
    }
    if (si == s.len or pat[pi] != s[si]) return false;
    return globAt(pat, pi + 1, s, si + 1);
}

test "parses .cursorrules and AGENTS.md as always-on rules" {
    const allocator = std.testing.allocator;
    var set = Set.init(allocator);
    defer set.deinit();
    try set.addParsed(".cursorrules", "use zig fmt\n");
    try set.addParsed("AGENTS.md", "do not cut corners\n");
    const injected = try set.inject(allocator, "src/foo.ts");
    defer allocator.free(injected);
    try std.testing.expect(std.mem.indexOf(u8, injected, "use zig fmt") != null);
    try std.testing.expect(std.mem.indexOf(u8, injected, "do not cut corners") != null);
}

test "mdc frontmatter globs select the target path" {
    const allocator = std.testing.allocator;
    var set = Set.init(allocator);
    defer set.deinit();
    try set.addParsed(".cursor/rules/zig.mdc",
        \\---
        \\description: Zig style
        \\globs: "**/*.zig"
        \\alwaysApply: false
        \\---
        \\prefer explicit allocators
    );
    const zig = try set.inject(allocator, "src/root.zig");
    defer allocator.free(zig);
    try std.testing.expect(std.mem.indexOf(u8, zig, "prefer explicit allocators") != null);
    const ts = try set.inject(allocator, "src/root.ts");
    defer allocator.free(ts);
    try std.testing.expectEqual(@as(usize, 0), ts.len);
}

test "glob matcher covers basename, nested, and double-star" {
    try std.testing.expect(globMatch("*.zig", "src/root.zig"));
    try std.testing.expect(globMatch("**/*.mdc", ".cursor/rules/a.mdc"));
    try std.testing.expect(globMatch("src/**", "src/a/b.zig"));
    try std.testing.expect(!globMatch("src/**", "pkg/a.zig"));
}

test "loadPath reads cursor rules from a workspace tree" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();
    try tmp.dir.writeFile(std.testing.io, .{ .sub_path = "AGENTS.md", .data = "native first\n" });
    try tmp.dir.createDirPath(std.testing.io, ".cursor/rules");
    try tmp.dir.writeFile(std.testing.io, .{
        .sub_path = ".cursor/rules/zig.mdc",
        .data = "---\nalwaysApply: true\n---\nkeep tests golden\n",
    });
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    var set = Set.init(allocator);
    defer set.deinit();
    try set.loadPath(std.testing.io, path_buffer[0..path_len]);
    const injected = try set.inject(allocator, "src/a.zig");
    defer allocator.free(injected);
    try std.testing.expect(std.mem.indexOf(u8, injected, "native first") != null);
    try std.testing.expect(std.mem.indexOf(u8, injected, "keep tests golden") != null);
}
