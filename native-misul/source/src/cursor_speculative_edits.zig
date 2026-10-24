const std = @import("std");

pub const Token = struct { start: usize, end: usize };

pub const Engine = struct {
    allocator: std.mem.Allocator,
    result: []u8,
    acc: std.ArrayList(u8) = .empty,

    pub fn init(allocator: std.mem.Allocator, source: []const u8) !Engine {
        return .{ .allocator = allocator, .result = try allocator.dupe(u8, source) };
    }

    pub fn deinit(self: *Engine) void {
        self.allocator.free(self.result);
        self.acc.deinit(self.allocator);
        self.* = undefined;
    }

    pub fn push(self: *Engine, chunk: []const u8) !void {
        try self.acc.appendSlice(self.allocator, chunk);
        try self.drain();
    }

    pub fn finish(self: *Engine) ![]const u8 {
        try self.drain();
        return self.result;
    }

    fn drain(self: *Engine) !void {
        while (true) {
            const block = findBlock(self.acc.items) orelse break;
            const next = try replaceAligned(self.allocator, self.result, block.search, block.replace);
            self.allocator.free(self.result);
            self.result = next;
            const remaining = try self.allocator.dupe(u8, self.acc.items[block.end..]);
            self.acc.deinit(self.allocator);
            self.acc = .empty;
            try self.acc.appendSlice(self.allocator, remaining);
            self.allocator.free(remaining);
        }
    }
};

pub fn tokenize(allocator: std.mem.Allocator, source: []const u8) ![]Token {
    var tokens: std.ArrayList(Token) = .empty;
    errdefer tokens.deinit(allocator);
    var i: usize = 0;
    while (i < source.len) {
        const start = i;
        if (isIdentStart(source[i])) {
            i += 1;
            while (i < source.len and isIdentContinue(source[i])) i += 1;
        } else if (std.ascii.isDigit(source[i])) {
            i += 1;
            while (i < source.len and std.ascii.isDigit(source[i])) i += 1;
        } else if (std.ascii.isWhitespace(source[i])) {
            i += 1;
            while (i < source.len and std.ascii.isWhitespace(source[i])) i += 1;
        } else {
            i += 1;
        }
        try tokens.append(allocator, .{ .start = start, .end = i });
    }
    return tokens.toOwnedSlice(allocator);
}

pub fn alignRange(tokens: []const Token, start: usize, end: usize) struct { start: usize, end: usize } {
    if (start == end) return .{ .start = start, .end = end };
    var lo = start;
    var hi = end;
    for (tokens) |token| {
        if (token.start < end and token.end > start) {
            if (token.start < lo) lo = token.start;
            if (token.end > hi) hi = token.end;
        }
    }
    return .{ .start = lo, .end = hi };
}

pub fn replaceAligned(
    allocator: std.mem.Allocator,
    source: []const u8,
    search: []const u8,
    replacement: []const u8,
) ![]u8 {
    if (search.len == 0) return allocator.dupe(u8, replacement);
    const found = std.mem.indexOf(u8, source, search) orelse return error.SearchNotFound;
    const tokens = try tokenize(allocator, source);
    defer allocator.free(tokens);
    const range = alignRange(tokens, found, found + search.len);
    return splice(allocator, source, range.start, range.end, replacement);
}

fn splice(allocator: std.mem.Allocator, source: []const u8, start: usize, end: usize, replacement: []const u8) ![]u8 {
    const out = try allocator.alloc(u8, source.len - (end - start) + replacement.len);
    @memcpy(out[0..start], source[0..start]);
    @memcpy(out[start .. start + replacement.len], replacement);
    @memcpy(out[start + replacement.len ..], source[end..]);
    return out;
}

fn isIdentStart(c: u8) bool {
    return std.ascii.isAlphabetic(c) or c == '_';
}

fn isIdentContinue(c: u8) bool {
    return std.ascii.isAlphanumeric(c) or c == '_';
}

const search_mark = "<<<<<<< SEARCH";
const mid_mark = "=======";
const replace_mark = ">>>>>>> REPLACE";

const Block = struct { search: []const u8, replace: []const u8, end: usize };

fn findBlock(text: []const u8) ?Block {
    const search_at = indexOfLine(text, search_mark) orelse return null;
    const after_search = lineEnd(text, search_at);
    const mid_at = indexOfLine(text[after_search..], mid_mark) orelse return null;
    const mid = after_search + mid_at;
    const after_mid = lineEnd(text, mid);
    const end_at = indexOfLine(text[after_mid..], replace_mark) orelse return null;
    const end_line = after_mid + end_at;
    return .{
        .search = trimBlock(text[after_search..mid]),
        .replace = trimBlock(text[after_mid..end_line]),
        .end = lineEnd(text, end_line),
    };
}

fn trimBlock(text: []const u8) []const u8 {
    var start: usize = 0;
    var end = text.len;
    if (end > start and text[start] == '\n') start += 1;
    if (end > start and text[end - 1] == '\n') end -= 1;
    if (end > start and text[end - 1] == '\r') end -= 1;
    return text[start..end];
}

fn indexOfLine(text: []const u8, prefix: []const u8) ?usize {
    var i: usize = 0;
    while (i <= text.len) {
        const nl = std.mem.indexOfScalarPos(u8, text, i, '\n') orelse text.len;
        const line = text[i..nl];
        const trimmed = if (line.len > 0 and line[line.len - 1] == '\r') line[0 .. line.len - 1] else line;
        if (std.mem.startsWith(u8, trimmed, prefix)) return i;
        if (nl == text.len) break;
        i = nl + 1;
    }
    return null;
}

fn lineEnd(text: []const u8, start: usize) usize {
    const nl = std.mem.indexOfScalarPos(u8, text, start, '\n') orelse return text.len;
    return nl + 1;
}

test "token-aligned replace expands a partial identifier" {
    const allocator = std.testing.allocator;
    const out = try replaceAligned(allocator, "const foobar = 1;", "foo", "baz");
    defer allocator.free(out);
    try std.testing.expectEqualStrings("const baz = 1;", out);
}

test "token-aligned replace keeps an exact identifier" {
    const allocator = std.testing.allocator;
    const out = try replaceAligned(allocator, "foo foobar", "foo", "bar");
    defer allocator.free(out);
    try std.testing.expectEqualStrings("bar foobar", out);
}

test "streaming SEARCH/REPLACE applies across chunks" {
    const allocator = std.testing.allocator;
    var engine = try Engine.init(allocator, "fn alpha() void {}\nfn beta() void {}\n");
    defer engine.deinit();
    try engine.push("<<<<<<< SEARCH\nfn alpha() void {}\n=======\n");
    try engine.push("fn alpha(x: u32) void {}\n>>>>>>> REPLACE\n");
    const out = try engine.finish();
    try std.testing.expectEqualStrings("fn alpha(x: u32) void {}\nfn beta() void {}\n", out);
}

test "missing search returns an error" {
    try std.testing.expectError(error.SearchNotFound, replaceAligned(std.testing.allocator, "abc", "zzz", "q"));
}
