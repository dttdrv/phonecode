const std = @import("std");

pub const Severity = enum { @"error", warning, info };

pub const Diagnostic = struct {
    path: []const u8,
    line: u32,
    column: u32,
    severity: Severity,
    message: []const u8,
};

const FileState = struct {
    path: []u8,
    baseline: []u8,
    staged: []u8,
};

pub const Workspace = struct {
    allocator: std.mem.Allocator,
    files: std.StringHashMap(FileState),
    diagnostics: std.ArrayList(OwnedDiagnostic) = .empty,

    pub fn init(allocator: std.mem.Allocator) Workspace {
        return .{ .allocator = allocator, .files = std.StringHashMap(FileState).init(allocator) };
    }

    pub fn deinit(self: *Workspace) void {
        var it = self.files.iterator();
        while (it.next()) |entry| {
            self.allocator.free(entry.key_ptr.*);
            self.allocator.free(entry.value_ptr.path);
            self.allocator.free(entry.value_ptr.baseline);
            self.allocator.free(entry.value_ptr.staged);
        }
        self.files.deinit();
        self.clearDiagnostics();
        self.diagnostics.deinit(self.allocator);
        self.* = undefined;
    }

    pub fn seed(self: *Workspace, path: []const u8, baseline: []const u8) !void {
        if (self.files.getPtr(path)) |file| {
            self.allocator.free(file.baseline);
            file.baseline = try self.allocator.dupe(u8, baseline);
            return;
        }
        const key = try self.allocator.dupe(u8, path);
        errdefer self.allocator.free(key);
        try self.files.put(key, .{
            .path = try self.allocator.dupe(u8, path),
            .baseline = try self.allocator.dupe(u8, baseline),
            .staged = try self.allocator.dupe(u8, baseline),
        });
    }

    pub fn stage(self: *Workspace, path: []const u8, staged: []const u8) !void {
        if (self.files.getPtr(path)) |file| {
            self.allocator.free(file.staged);
            file.staged = try self.allocator.dupe(u8, staged);
            return;
        }
        try self.seed(path, "");
        try self.stage(path, staged);
    }

    pub fn read(self: *const Workspace, path: []const u8) ?[]const u8 {
        const file = self.files.get(path) orelse return null;
        return file.staged;
    }

    pub fn lint(self: *Workspace) ![]const Diagnostic {
        self.clearDiagnostics();
        var it = self.files.iterator();
        while (it.next()) |entry| {
            try lintFile(self, entry.value_ptr);
        }
        const out = try self.allocator.alloc(Diagnostic, self.diagnostics.items.len);
        for (self.diagnostics.items, 0..) |item, index| {
            out[index] = .{
                .path = item.path,
                .line = item.line,
                .column = item.column,
                .severity = item.severity,
                .message = item.message,
            };
        }
        return out;
    }

    pub fn integrity(self: *const Workspace) bool {
        for (self.diagnostics.items) |item| {
            if (item.severity == .@"error") return false;
        }
        return true;
    }

    pub fn apply(self: *Workspace) ![][]const u8 {
        if (!self.integrity()) return error.IntegrityFailed;
        var applied: std.ArrayList([]const u8) = .empty;
        errdefer applied.deinit(self.allocator);
        var it = self.files.iterator();
        while (it.next()) |entry| {
            const file = entry.value_ptr;
            self.allocator.free(file.baseline);
            file.baseline = try self.allocator.dupe(u8, file.staged);
            try applied.append(self.allocator, file.path);
        }
        return applied.toOwnedSlice(self.allocator);
    }

    fn clearDiagnostics(self: *Workspace) void {
        for (self.diagnostics.items) |item| {
            self.allocator.free(item.path);
            self.allocator.free(item.message);
        }
        self.diagnostics.clearRetainingCapacity();
    }
};

const OwnedDiagnostic = struct {
    path: []u8,
    line: u32,
    column: u32,
    severity: Severity,
    message: []u8,
};

fn lintFile(self: *Workspace, file: *FileState) !void {
    var paren: i32 = 0;
    var brace: i32 = 0;
    var bracket: i32 = 0;
    var line: u32 = 1;
    var column: u32 = 1;
    var in_string = false;
    var escaped = false;
    var string_char: u8 = 0;

    for (file.staged) |c| {
        if (in_string) {
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == string_char) {
                in_string = false;
            }
        } else if (c == '"' or c == '\'') {
            in_string = true;
            string_char = c;
        } else switch (c) {
            '(' => paren += 1,
            ')' => paren -= 1,
            '{' => brace += 1,
            '}' => brace -= 1,
            '[' => bracket += 1,
            ']' => bracket -= 1,
            else => {},
        }
        if (paren < 0 or brace < 0 or bracket < 0) {
            try pushDiag(self, file.path, line, column, .@"error", "unmatched closer");
            paren = @max(paren, 0);
            brace = @max(brace, 0);
            bracket = @max(bracket, 0);
        }
        if (c == '\n') {
            line += 1;
            column = 1;
        } else {
            column += 1;
        }
    }
    if (in_string) try pushDiag(self, file.path, line, column, .@"error", "unterminated string");
    if (paren != 0 or brace != 0 or bracket != 0) {
        try pushDiag(self, file.path, line, column, .@"error", "unbalanced delimiters");
    }
    if (std.mem.indexOf(u8, file.staged, "<<<<<<<") != null or std.mem.indexOf(u8, file.staged, ">>>>>>>") != null) {
        try pushDiag(self, file.path, 1, 1, .@"error", "conflict marker");
    }
    if (file.staged.len == 0) {
        try pushDiag(self, file.path, 1, 1, .warning, "empty staged file");
    }
}

fn pushDiag(self: *Workspace, path: []const u8, line: u32, column: u32, severity: Severity, message: []const u8) !void {
    try self.diagnostics.append(self.allocator, .{
        .path = try self.allocator.dupe(u8, path),
        .line = line,
        .column = column,
        .severity = severity,
        .message = try self.allocator.dupe(u8, message),
    });
}

test "shadow workspace stages in memory until apply" {
    const allocator = std.testing.allocator;
    var ws = Workspace.init(allocator);
    defer ws.deinit();
    try ws.seed("a.zig", "const x = 1;\n");
    try ws.stage("a.zig", "const x = 2;\n");
    try std.testing.expectEqualStrings("const x = 2;\n", ws.read("a.zig").?);
    try std.testing.expectEqualStrings("const x = 1;\n", ws.files.get("a.zig").?.baseline);
    const diags = try ws.lint();
    defer allocator.free(diags);
    try std.testing.expect(ws.integrity());
    const applied = try ws.apply();
    defer allocator.free(applied);
    try std.testing.expectEqual(@as(usize, 1), applied.len);
    try std.testing.expectEqualStrings("const x = 2;\n", ws.files.get("a.zig").?.baseline);
}

test "unbalanced braces fail integrity before apply" {
    const allocator = std.testing.allocator;
    var ws = Workspace.init(allocator);
    defer ws.deinit();
    try ws.seed("a.zig", "fn f() void {}\n");
    try ws.stage("a.zig", "fn f() void {\n");
    const diags = try ws.lint();
    defer allocator.free(diags);
    try std.testing.expect(!ws.integrity());
    try std.testing.expectError(error.IntegrityFailed, ws.apply());
}

test "conflict markers are diagnostics" {
    const allocator = std.testing.allocator;
    var ws = Workspace.init(allocator);
    defer ws.deinit();
    try ws.stage("a.zig", "<<<<<<< SEARCH\nfoo\n");
    const diags = try ws.lint();
    defer allocator.free(diags);
    try std.testing.expect(diags.len >= 1);
    try std.testing.expect(!ws.integrity());
}
