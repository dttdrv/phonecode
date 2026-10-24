const std = @import("std");

pub const MentionKind = enum { file, code, folder, docs, terminal };

pub const SymbolKind = enum { function, type, constant, unknown };

pub const Symbol = struct {
    name: []const u8,
    kind: SymbolKind,
    line: u32,
};

pub const File = struct {
    path: []u8,
    content: []u8,
    symbols: []Symbol,
};

pub const Hit = struct {
    path: []u8,
    kind: MentionKind,
    score: i32,
    snippet: []u8,
    symbol: ?[]u8,
};

pub const QueryResult = struct {
    allocator: std.mem.Allocator,
    hits: []Hit,

    pub fn deinit(self: *QueryResult) void {
        for (self.hits) |hit| {
            self.allocator.free(hit.path);
            self.allocator.free(hit.snippet);
            if (hit.symbol) |name| self.allocator.free(name);
        }
        self.allocator.free(self.hits);
        self.* = undefined;
    }
};

pub const Mention = struct {
    kind: MentionKind,
    value: []const u8,
};

pub const Index = struct {
    allocator: std.mem.Allocator,
    files: std.ArrayList(File) = .empty,
    terminals: std.ArrayList([]u8) = .empty,

    pub fn init(allocator: std.mem.Allocator) Index {
        return .{ .allocator = allocator };
    }

    pub fn deinit(self: *Index) void {
        for (self.files.items) |file| {
            self.allocator.free(file.path);
            self.allocator.free(file.content);
            self.allocator.free(file.symbols);
        }
        self.files.deinit(self.allocator);
        for (self.terminals.items) |buf| self.allocator.free(buf);
        self.terminals.deinit(self.allocator);
        self.* = undefined;
    }

    pub fn addFile(self: *Index, path: []const u8, content: []const u8) !void {
        if (content.len > 1_048_576) return;
        if (std.mem.indexOfScalar(u8, content, 0) != null) return;
        const owned_content = try self.allocator.dupe(u8, content);
        errdefer self.allocator.free(owned_content);
        const symbols = try extractSymbols(self.allocator, owned_content);
        errdefer self.allocator.free(symbols);
        try self.files.append(self.allocator, .{
            .path = try self.allocator.dupe(u8, path),
            .content = owned_content,
            .symbols = symbols,
        });
    }

    pub fn addTerminal(self: *Index, text: []const u8) !void {
        try self.terminals.append(self.allocator, try self.allocator.dupe(u8, text));
    }

    pub fn indexPath(self: *Index, io: std.Io, root: []const u8) !void {
        var dir = try std.Io.Dir.cwd().openDir(io, root, .{ .iterate = true });
        defer dir.close(io);
        var walker = try dir.walk(self.allocator);
        defer walker.deinit();
        var count: usize = 0;
        while (try walker.next(io)) |entry| {
            if (entry.kind != .file) continue;
            if (skipPath(entry.path)) continue;
            const data = dir.readFileAlloc(io, entry.path, self.allocator, .limited(1_048_576)) catch continue;
            defer self.allocator.free(data);
            try self.addFile(entry.path, data);
            count += 1;
            if (count >= 512) break;
        }
    }

    pub fn query(self: *const Index, allocator: std.mem.Allocator, text: []const u8) !QueryResult {
        const mentions = try parseMentions(allocator, text);
        defer allocator.free(mentions);
        const free_text = stripMentions(text);

        var hits: std.ArrayList(Hit) = .empty;
        errdefer {
            for (hits.items) |hit| {
                allocator.free(hit.path);
                allocator.free(hit.snippet);
                if (hit.symbol) |name| allocator.free(name);
            }
            hits.deinit(allocator);
        }

        if (mentions.len == 0) {
            try self.collect(allocator, &hits, .code, free_text);
        } else {
            for (mentions) |mention| {
                try self.collect(allocator, &hits, mention.kind, mention.value);
            }
            if (free_text.len != 0) try self.collect(allocator, &hits, .code, free_text);
        }

        self.expandGraph(allocator, &hits) catch {};
        std.mem.sort(Hit, hits.items, {}, hitMore);
        const limit = @min(hits.items.len, @as(usize, 16));
        if (hits.items.len > limit) {
            for (hits.items[limit..]) |hit| {
                allocator.free(hit.path);
                allocator.free(hit.snippet);
                if (hit.symbol) |name| allocator.free(name);
            }
            hits.items.len = limit;
        }
        return .{ .allocator = allocator, .hits = try hits.toOwnedSlice(allocator) };
    }

    fn collect(
        self: *const Index,
        allocator: std.mem.Allocator,
        hits: *std.ArrayList(Hit),
        kind: MentionKind,
        needle: []const u8,
    ) !void {
        switch (kind) {
            .file => try self.collectFiles(allocator, hits, needle),
            .folder => try self.collectFolder(allocator, hits, needle),
            .docs => try self.collectDocs(allocator, hits, needle),
            .code => try self.collectCode(allocator, hits, needle),
            .terminal => try self.collectTerminal(allocator, hits, needle),
        }
    }

    fn collectFiles(self: *const Index, allocator: std.mem.Allocator, hits: *std.ArrayList(Hit), needle: []const u8) !void {
        for (self.files.items) |file| {
            var score: i32 = 10;
            if (std.mem.eql(u8, file.path, needle)) score += 80;
            if (std.mem.eql(u8, std.fs.path.basename(file.path), std.fs.path.basename(needle))) score += 50;
            if (needle.len != 0 and contains(file.path, needle)) score += 20;
            if (needle.len != 0 and contains(file.content, needle)) score += 8;
            if (score <= 10 and needle.len != 0) continue;
            try pushHit(allocator, hits, file.path, .file, score, snippetAround(file.content, needle), null);
        }
    }

    fn collectFolder(self: *const Index, allocator: std.mem.Allocator, hits: *std.ArrayList(Hit), needle: []const u8) !void {
        const prefix = std.mem.trim(u8, needle, "/");
        for (self.files.items) |file| {
            if (prefix.len == 0 or pathUnder(file.path, prefix)) {
                try pushHit(allocator, hits, file.path, .folder, 40, snippetAround(file.content, ""), null);
            }
        }
    }

    fn collectDocs(self: *const Index, allocator: std.mem.Allocator, hits: *std.ArrayList(Hit), needle: []const u8) !void {
        for (self.files.items) |file| {
            if (!isDocs(file.path)) continue;
            var score: i32 = 30;
            if (needle.len != 0 and contains(file.path, needle)) score += 20;
            if (needle.len != 0 and contains(file.content, needle)) score += 15;
            try pushHit(allocator, hits, file.path, .docs, score, snippetAround(file.content, needle), null);
        }
    }

    fn collectCode(self: *const Index, allocator: std.mem.Allocator, hits: *std.ArrayList(Hit), needle: []const u8) !void {
        for (self.files.items) |file| {
            var score: i32 = 0;
            var matched_symbol: ?[]const u8 = null;
            for (file.symbols) |symbol| {
                if (needle.len == 0) break;
                if (std.mem.eql(u8, symbol.name, needle)) {
                    score += 70;
                    matched_symbol = symbol.name;
                } else if (std.mem.startsWith(u8, symbol.name, needle)) {
                    score += 40;
                    matched_symbol = symbol.name;
                }
            }
            if (needle.len != 0 and contains(file.content, needle)) score += 10;
            if (needle.len != 0 and contains(file.path, needle)) score += 12;
            if (score == 0) continue;
            try pushHit(allocator, hits, file.path, .code, score, snippetAround(file.content, needle), matched_symbol);
        }
    }

    fn collectTerminal(self: *const Index, allocator: std.mem.Allocator, hits: *std.ArrayList(Hit), needle: []const u8) !void {
        for (self.terminals.items, 0..) |buf, i| {
            if (needle.len != 0 and !contains(buf, needle) and !contains(buf, needle)) continue;
            var name_buf: [32]u8 = undefined;
            const name = std.fmt.bufPrint(&name_buf, "terminal:{d}", .{i}) catch "terminal";
            try pushHit(allocator, hits, name, .terminal, 25, snippetAround(buf, needle), null);
        }
    }

    fn expandGraph(self: *const Index, allocator: std.mem.Allocator, hits: *std.ArrayList(Hit)) !void {
        const initial_len = hits.items.len;
        var i: usize = 0;
        while (i < initial_len) : (i += 1) {
            const hit = hits.items[i];
            const symbol = hit.symbol orelse continue;
            for (self.files.items) |file| {
                if (std.mem.eql(u8, file.path, hit.path)) continue;
                if (!contains(file.content, symbol)) continue;
                if (alreadyHas(hits.items, file.path)) continue;
                try pushHit(allocator, hits, file.path, .code, @divTrunc(hit.score, 2), snippetAround(file.content, symbol), symbol);
            }
        }
    }
};

pub fn parseMentions(allocator: std.mem.Allocator, text: []const u8) ![]Mention {
    var mentions: std.ArrayList(Mention) = .empty;
    errdefer mentions.deinit(allocator);
    var i: usize = 0;
    while (i < text.len) {
        if (text[i] != '@') {
            i += 1;
            continue;
        }
        const kind, const consumed = parseKind(text[i + 1 ..]) orelse {
            i += 1;
            continue;
        };
        var pos = i + 1 + consumed;
        while (pos < text.len and (text[pos] == ' ' or text[pos] == ':')) pos += 1;
        var end = pos;
        if (pos < text.len and text[pos] == '(') {
            pos += 1;
            end = pos;
            while (end < text.len and text[end] != ')') end += 1;
            try mentions.append(allocator, .{ .kind = kind, .value = std.mem.trim(u8, text[pos..end], " \t\"'") });
            i = if (end < text.len) end + 1 else end;
        } else {
            while (end < text.len and !std.ascii.isWhitespace(text[end]) and text[end] != '@') end += 1;
            try mentions.append(allocator, .{ .kind = kind, .value = text[pos..end] });
            i = end;
        }
    }
    return mentions.toOwnedSlice(allocator);
}

pub fn extractSymbols(allocator: std.mem.Allocator, content: []const u8) ![]Symbol {
    var symbols: std.ArrayList(Symbol) = .empty;
    errdefer symbols.deinit(allocator);
    var line_no: u32 = 1;
    var it = std.mem.splitScalar(u8, content, '\n');
    while (it.next()) |line| {
        const trimmed = std.mem.trimStart(u8, line, " \t");
        const rest = stripVis(trimmed);
        if (identAfter(rest, "fn ")) |name| {
            try symbols.append(allocator, .{ .name = name, .kind = .function, .line = line_no });
        } else if (identAfter(rest, "function ")) |name| {
            try symbols.append(allocator, .{ .name = name, .kind = .function, .line = line_no });
        } else if (identAfter(rest, "def ")) |name| {
            try symbols.append(allocator, .{ .name = name, .kind = .function, .line = line_no });
        } else if (identAfter(rest, "struct ")) |name| {
            try symbols.append(allocator, .{ .name = name, .kind = .type, .line = line_no });
        } else if (identAfter(rest, "class ")) |name| {
            try symbols.append(allocator, .{ .name = name, .kind = .type, .line = line_no });
        } else if (identAfter(rest, "enum ")) |name| {
            try symbols.append(allocator, .{ .name = name, .kind = .type, .line = line_no });
        } else if (identAfter(rest, "type ")) |name| {
            try symbols.append(allocator, .{ .name = name, .kind = .type, .line = line_no });
        } else if (identAfter(rest, "const ")) |name| {
            try symbols.append(allocator, .{ .name = name, .kind = .constant, .line = line_no });
        }
        line_no += 1;
    }
    return symbols.toOwnedSlice(allocator);
}

fn stripVis(line: []const u8) []const u8 {
    var rest = line;
    while (true) {
        if (consumePrefix(&rest, "pub ")) continue;
        if (consumePrefix(&rest, "export ")) continue;
        if (consumePrefix(&rest, "async ")) continue;
        if (consumePrefix(&rest, "comptime ")) continue;
        break;
    }
    return rest;
}

fn consumePrefix(rest: *[]const u8, prefix: []const u8) bool {
    if (std.mem.startsWith(u8, rest.*, prefix)) {
        rest.* = rest.*[prefix.len..];
        return true;
    }
    return false;
}

fn identAfter(line: []const u8, keyword: []const u8) ?[]const u8 {
    if (!std.mem.startsWith(u8, line, keyword)) return null;
    var rest = std.mem.trimStart(u8, line[keyword.len..], " \t");
    if (rest.len == 0) return null;
    if (!identStart(rest[0])) return null;
    var end: usize = 1;
    while (end < rest.len and identContinue(rest[end])) end += 1;
    return rest[0..end];
}

fn identStart(c: u8) bool {
    return std.ascii.isAlphabetic(c) or c == '_';
}

fn identContinue(c: u8) bool {
    return std.ascii.isAlphanumeric(c) or c == '_';
}

fn parseKind(text: []const u8) ?struct { MentionKind, usize } {
    const table = [_]struct { []const u8, MentionKind }{
        .{ "Codebase", .code },
        .{ "Terminal", .terminal },
        .{ "Folder", .folder },
        .{ "File", .file },
        .{ "Code", .code },
        .{ "Docs", .docs },
    };
    for (table) |row| {
        if (startsIgnoreCase(text, row[0])) return .{ row[1], row[0].len };
    }
    return null;
}

fn startsIgnoreCase(text: []const u8, prefix: []const u8) bool {
    if (text.len < prefix.len) return false;
    return std.ascii.eqlIgnoreCase(text[0..prefix.len], prefix);
}

fn stripMentions(text: []const u8) []const u8 {
    var i: usize = 0;
    while (i < text.len) {
        if (text[i] == '@') {
            const kind = parseKind(text[i + 1 ..]);
            if (kind != null) {
                i += 1;
                continue;
            }
        }
        if (!std.ascii.isWhitespace(text[i]) and text[i] != '@' and text[i] != '(' and text[i] != ')' and text[i] != ':') {
            return std.mem.trim(u8, text[i..], " \t\r\n");
        }
        i += 1;
    }
    return "";
}

fn skipPath(path: []const u8) bool {
    const skipped = [_][]const u8{ ".git", "node_modules", ".zig-cache", "zig-out", "dist", ".jspace" };
    var it = std.mem.splitScalar(u8, path, '/');
    while (it.next()) |part| {
        for (skipped) |name| if (std.mem.eql(u8, part, name)) return true;
    }
    const base = std.fs.path.basename(path);
    if (base.len != 0 and base[0] == '.') return true;
    return false;
}

fn isDocs(path: []const u8) bool {
    const ext = std.fs.path.extension(path);
    inline for (.{ ".md", ".mdx", ".rst", ".txt" }) |ok| {
        if (std.ascii.eqlIgnoreCase(ext, ok)) return true;
    }
    return contains(path, "docs/") or contains(path, "doc/");
}

fn pathUnder(path: []const u8, prefix: []const u8) bool {
    if (std.mem.eql(u8, path, prefix)) return true;
    if (path.len <= prefix.len) return false;
    if (!std.mem.startsWith(u8, path, prefix)) return false;
    return path[prefix.len] == '/';
}

fn contains(hay: []const u8, needle: []const u8) bool {
    if (needle.len == 0) return false;
    return std.ascii.indexOfIgnoreCase(hay, needle) != null;
}

fn snippetAround(content: []const u8, needle: []const u8) []const u8 {
    if (content.len == 0) return content;
    const at = if (needle.len == 0) 0 else (std.ascii.indexOfIgnoreCase(content, needle) orelse 0);
    const start = at -| 40;
    const end = @min(content.len, at + 80);
    return content[start..end];
}

fn pushHit(
    allocator: std.mem.Allocator,
    hits: *std.ArrayList(Hit),
    path: []const u8,
    kind: MentionKind,
    score: i32,
    snippet: []const u8,
    symbol: ?[]const u8,
) !void {
    try hits.append(allocator, .{
        .path = try allocator.dupe(u8, path),
        .kind = kind,
        .score = score,
        .snippet = try allocator.dupe(u8, snippet),
        .symbol = if (symbol) |name| try allocator.dupe(u8, name) else null,
    });
}

fn alreadyHas(hits: []const Hit, path: []const u8) bool {
    for (hits) |hit| if (std.mem.eql(u8, hit.path, path)) return true;
    return false;
}

fn hitMore(_: void, left: Hit, right: Hit) bool {
    if (left.score != right.score) return left.score > right.score;
    return std.mem.order(u8, left.path, right.path) == .lt;
}

test "extracts zig symbols and ranks @Code hits" {
    const allocator = std.testing.allocator;
    var index = Index.init(allocator);
    defer index.deinit();
    try index.addFile("src/index.zig", "pub const Index = struct {};\npub fn query() void {}\n");
    try index.addFile("src/other.zig", "const x = Index{};\n");
    var result = try index.query(allocator, "@Code Index");
    defer result.deinit();
    try std.testing.expect(result.hits.len >= 1);
    try std.testing.expectEqualStrings("src/index.zig", result.hits[0].path);
    try std.testing.expect(result.hits[0].symbol != null);
}

test "@File ranks the named path first" {
    const allocator = std.testing.allocator;
    var index = Index.init(allocator);
    defer index.deinit();
    try index.addFile("src/a.zig", "fn unused() void {}\n");
    try index.addFile("src/main.zig", "pub fn main() void {}\n");
    var result = try index.query(allocator, "@File src/main.zig");
    defer result.deinit();
    try std.testing.expectEqualStrings("src/main.zig", result.hits[0].path);
    try std.testing.expectEqual(MentionKind.file, result.hits[0].kind);
}

test "@Folder expands child files" {
    const allocator = std.testing.allocator;
    var index = Index.init(allocator);
    defer index.deinit();
    try index.addFile("src/a.zig", "a\n");
    try index.addFile("src/b.zig", "b\n");
    try index.addFile("pkg/c.zig", "c\n");
    var result = try index.query(allocator, "@Folder src");
    defer result.deinit();
    try std.testing.expectEqual(@as(usize, 2), result.hits.len);
}

test "@Docs prefers markdown and @Terminal searches buffers" {
    const allocator = std.testing.allocator;
    var index = Index.init(allocator);
    defer index.deinit();
    try index.addFile("README.md", "install zig\n");
    try index.addFile("src/a.zig", "install nothing\n");
    try index.addTerminal("zig build test passed\n");
    var docs = try index.query(allocator, "@Docs install");
    defer docs.deinit();
    try std.testing.expectEqualStrings("README.md", docs.hits[0].path);
    var term = try index.query(allocator, "@Terminal zig build");
    defer term.deinit();
    try std.testing.expectEqual(MentionKind.terminal, term.hits[0].kind);
}

test "graph expander adds referencing files" {
    const allocator = std.testing.allocator;
    var index = Index.init(allocator);
    defer index.deinit();
    try index.addFile("src/index.zig", "pub const Index = struct {};\n");
    try index.addFile("src/use.zig", "fn use(i: Index) void { _ = i; }\n");
    var result = try index.query(allocator, "@Code Index");
    defer result.deinit();
    try std.testing.expect(result.hits.len >= 2);
}

test "indexPath walks a local tree" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();
    try tmp.dir.writeFile(std.testing.io, .{ .sub_path = "main.zig", .data = "pub fn main() void {}\n" });
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    var index = Index.init(allocator);
    defer index.deinit();
    try index.indexPath(std.testing.io, path_buffer[0..path_len]);
    try std.testing.expect(index.files.items.len >= 1);
}
