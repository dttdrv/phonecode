const std = @import("std");

pub const max_skill_bytes = 256 * 1024;

pub const Entry = struct {
    name: []u8,
    description: []u8,
    path: []u8,
};

pub const Catalog = struct {
    allocator: std.mem.Allocator,
    entries: std.ArrayList(Entry) = .empty,

    pub fn init(allocator: std.mem.Allocator) Catalog {
        return .{ .allocator = allocator };
    }

    pub fn deinit(self: *Catalog) void {
        for (self.entries.items) |entry| {
            self.allocator.free(entry.name);
            self.allocator.free(entry.description);
            self.allocator.free(entry.path);
        }
        self.entries.deinit(self.allocator);
        self.* = undefined;
    }

    /// Discovers one directory level of skill folders. Symlinks and entries
    /// without SKILL.md are ignored so discovery cannot escape the root.
    pub fn addRoot(self: *Catalog, io: std.Io, root: []const u8) !void {
        var dir = try std.Io.Dir.cwd().openDir(io, root, .{ .iterate = true });
        defer dir.close(io);
        var iterator = dir.iterate();
        while (try iterator.next(io)) |child| {
            if (child.kind != .directory) continue;
            const relative = try std.fs.path.join(self.allocator, &.{ child.name, "SKILL.md" });
            defer self.allocator.free(relative);
            const body = dir.readFileAlloc(io, relative, self.allocator, .limited(max_skill_bytes)) catch |err| switch (err) {
                error.FileNotFound, error.NotDir => continue,
                else => return err,
            };
            defer self.allocator.free(body);
            const metadata = parseMetadata(body);
            const name = metadata.name orelse child.name;
            if (!validName(name)) return error.InvalidSkillName;
            if (self.find(name) != null) return error.DuplicateSkill;
            const full_path = try std.fs.path.join(self.allocator, &.{ root, relative });
            errdefer self.allocator.free(full_path);
            try self.entries.append(self.allocator, .{
                .name = try self.allocator.dupe(u8, name),
                .description = try self.allocator.dupe(u8, metadata.description orelse ""),
                .path = full_path,
            });
        }
        std.mem.sort(Entry, self.entries.items, {}, lessThan);
    }

    pub fn find(self: *const Catalog, name: []const u8) ?*const Entry {
        for (self.entries.items) |*entry| if (std.mem.eql(u8, entry.name, name)) return entry;
        return null;
    }

    /// Renders only stable trigger metadata. Skill bodies remain out of the
    /// prompt until load is called explicitly.
    pub fn renderCompact(self: *const Catalog, allocator: std.mem.Allocator) ![]u8 {
        var output: std.ArrayList(u8) = .empty;
        errdefer output.deinit(allocator);
        for (self.entries.items) |entry| {
            try output.appendSlice(allocator, entry.name);
            if (entry.description.len != 0) {
                try output.appendSlice(allocator, ": ");
                try output.appendSlice(allocator, entry.description);
            }
            try output.append(allocator, '\n');
        }
        return output.toOwnedSlice(allocator);
    }

    pub fn load(self: *const Catalog, io: std.Io, allocator: std.mem.Allocator, name: []const u8) ![]u8 {
        if (!validName(name)) return error.InvalidSkillName;
        const entry = self.find(name) orelse return error.SkillNotFound;
        return std.Io.Dir.cwd().readFileAlloc(io, entry.path, allocator, .limited(max_skill_bytes));
    }
};

const Metadata = struct { name: ?[]const u8 = null, description: ?[]const u8 = null };

fn parseMetadata(body: []const u8) Metadata {
    if (!std.mem.startsWith(u8, body, "---\n")) return .{};
    const end = std.mem.indexOfPos(u8, body, 4, "\n---") orelse return .{};
    var result = Metadata{};
    var lines = std.mem.splitScalar(u8, body[4..end], '\n');
    while (lines.next()) |line| {
        if (field(line, "name:")) |value| result.name = unquote(value) else if (field(line, "description:")) |value| result.description = unquote(value);
    }
    return result;
}

fn field(line: []const u8, key: []const u8) ?[]const u8 {
    const trimmed = std.mem.trim(u8, line, " \t\r");
    if (!std.mem.startsWith(u8, trimmed, key)) return null;
    return std.mem.trim(u8, trimmed[key.len..], " \t\r");
}

fn unquote(value: []const u8) []const u8 {
    if (value.len >= 2 and ((value[0] == '"' and value[value.len - 1] == '"') or (value[0] == '\'' and value[value.len - 1] == '\''))) return value[1 .. value.len - 1];
    return value;
}

fn validName(name: []const u8) bool {
    if (name.len == 0 or name.len > 128 or std.mem.eql(u8, name, ".") or std.mem.eql(u8, name, "..")) return false;
    for (name) |byte| if (!(std.ascii.isAlphanumeric(byte) or byte == '-' or byte == '_')) return false;
    return true;
}

fn lessThan(_: void, left: Entry, right: Entry) bool {
    return std.mem.order(u8, left.name, right.name) == .lt;
}

fn makeSkill(io: std.Io, root: std.Io.Dir, name: []const u8, body: []const u8) !void {
    try root.createDirPath(io, name);
    const path = try std.fs.path.join(std.testing.allocator, &.{ name, "SKILL.md" });
    defer std.testing.allocator.free(path);
    try root.writeFile(io, .{ .sub_path = path, .data = body });
}

test "catalog is sorted and skill bodies load only by exact name" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    try makeSkill(std.testing.io, tmp.dir, "zeta", "---\nname: zeta\ndescription: Last skill\n---\nsecret procedure\n");
    try makeSkill(std.testing.io, tmp.dir, "alpha", "---\nname: alpha\ndescription: First skill\n---\nfirst body\n");
    var path_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPath(std.testing.io, &path_buffer);

    var catalog = Catalog.init(allocator);
    defer catalog.deinit();
    try catalog.addRoot(std.testing.io, path_buffer[0..path_len]);
    try std.testing.expectEqual(@as(usize, 2), catalog.entries.items.len);
    try std.testing.expectEqualStrings("alpha", catalog.entries.items[0].name);
    const compact = try catalog.renderCompact(allocator);
    defer allocator.free(compact);
    try std.testing.expect(std.mem.indexOf(u8, compact, "first body") == null);
    const body = try catalog.load(std.testing.io, allocator, "alpha");
    defer allocator.free(body);
    try std.testing.expect(std.mem.indexOf(u8, body, "first body") != null);
    try std.testing.expectError(error.InvalidSkillName, catalog.load(std.testing.io, allocator, "../alpha"));
}

test "duplicate names and oversized bodies fail closed" {
    const allocator = std.testing.allocator;
    var first = std.testing.tmpDir(.{});
    defer first.cleanup();
    var second = std.testing.tmpDir(.{});
    defer second.cleanup();
    try makeSkill(std.testing.io, first.dir, "one", "---\nname: shared\n---\na\n");
    try makeSkill(std.testing.io, second.dir, "two", "---\nname: shared\n---\nb\n");
    var first_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    var second_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const first_len = try first.dir.realPath(std.testing.io, &first_buffer);
    const second_len = try second.dir.realPath(std.testing.io, &second_buffer);
    var catalog = Catalog.init(allocator);
    defer catalog.deinit();
    try catalog.addRoot(std.testing.io, first_buffer[0..first_len]);
    try std.testing.expectError(error.DuplicateSkill, catalog.addRoot(std.testing.io, second_buffer[0..second_len]));
}
