const std = @import("std");
const builtin = @import("builtin");
const harness = @import("harness.zig");
const agent_loop = @import("agent_loop.zig");
const protocol = @import("protocol.zig");

pub const max_read_bytes: usize = 256 * 1024;
pub const max_write_bytes: usize = 8 * 1024 * 1024;
pub const max_search_file_bytes: usize = 8 * 1024 * 1024;
pub const max_output_bytes: usize = 1024 * 1024;
pub const max_results: usize = 1000;
pub const default_shell_timeout_ms: u64 = 30_000;
pub const max_shell_timeout_ms: u64 = 120_000;

const schema_texts = [_][]const u8{
    \\{"type":"object","properties":{"path":{"type":"string"},"old_string":{"type":"string"},"new_string":{"type":"string"}},"required":["path","old_string","new_string"],"additionalProperties":false}
    ,
    \\{"type":"object","properties":{"pattern":{"type":"string"},"path":{"type":"string","default":"."},"max_results":{"type":"integer","minimum":1,"maximum":1000}},"required":["pattern"],"additionalProperties":false}
    ,
    \\{"type":"object","properties":{"pattern":{"type":"string"},"path":{"type":"string","default":"."},"max_results":{"type":"integer","minimum":1,"maximum":1000}},"required":["pattern"],"additionalProperties":false}
    ,
    \\{"type":"object","properties":{"pattern":{"type":"string"},"path":{"type":"string","default":"."},"include":{"type":"string"},"case_insensitive":{"type":"boolean","default":false},"max_results":{"type":"integer","minimum":1,"maximum":1000}},"required":["pattern"],"additionalProperties":false}
    ,
    \\{"type":"object","properties":{"path":{"type":"string","default":"."},"max_results":{"type":"integer","minimum":1,"maximum":1000}},"additionalProperties":false}
    ,
    \\{"type":"object","properties":{"path":{"type":"string"},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":262144},"start_line":{"type":"integer","minimum":1},"line_count":{"type":"integer","minimum":1,"maximum":2000}},"required":["path"],"additionalProperties":false}
    ,
    \\{"type":"object","properties":{"command":{"type":"string"},"timeout_ms":{"type":"integer","minimum":1,"maximum":120000},"max_output_bytes":{"type":"integer","minimum":1,"maximum":1048576}},"required":["command"],"additionalProperties":false}
    ,
    \\{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"create_parent_dirs":{"type":"boolean","default":true}},"required":["path","content"],"additionalProperties":false}
};

const tool_names = [_][]const u8{
    "edit_file",
    "find_files",
    "glob_files",
    "grep_files",
    "list_files",
    "read_file",
    "shell",
    "write_file",
};

const tool_descriptions = [_][]const u8{
    "Replace exactly one occurrence of a string in a workspace file.",
    "Find workspace files whose paths match a glob pattern.",
    "Find workspace files whose paths match a glob pattern.",
    "Search workspace files for a literal string and return sorted path:line matches.",
    "List one directory level in deterministic name order.",
    "Read a bounded byte or line range from a workspace file.",
    "Run a shell command in the workspace with a timeout and bounded output.",
    "Atomically create or replace a workspace file.",
};

pub fn isMutatingTool(name: []const u8) bool {
    return std.mem.eql(u8, name, "edit_file") or
        std.mem.eql(u8, name, "shell") or
        std.mem.eql(u8, name, "write_file");
}

pub fn isSupportedOnAndroid(name: []const u8) bool {
    return !std.mem.eql(u8, name, "shell");
}

test "Android tool policy distinguishes reads, mutations, and unavailable shell" {
    try std.testing.expect(!isMutatingTool("read_file"));
    try std.testing.expect(isMutatingTool("edit_file"));
    try std.testing.expect(isMutatingTool("write_file"));
    try std.testing.expect(isMutatingTool("shell"));
    try std.testing.expect(isSupportedOnAndroid("read_file"));
    try std.testing.expect(!isSupportedOnAndroid("shell"));
}

const execute_fns = [_]agent_loop.ToolExecuteFn{
    executeEditFile,
    executeFindFiles,
    executeGlobFiles,
    executeGrepFiles,
    executeListFiles,
    executeReadFile,
    executeShell,
    executeWriteFile,
};

pub const Runtime = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    root: std.Io.Dir,
    workspace_root: []u8,
    /// The configured root before symlink resolution, as shown to the model.
    configured_root: []u8,

    pub fn init(allocator: std.mem.Allocator, io: std.Io, workspace_root: []const u8) !Runtime {
        var root = try std.Io.Dir.cwd().openDir(io, workspace_root, .{ .iterate = true });
        errdefer root.close(io);
        var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
        const path_len = try root.realPath(io, &path_buffer);
        const resolved = try allocator.dupe(u8, path_buffer[0..path_len]);
        errdefer allocator.free(resolved);
        return .{
            .allocator = allocator,
            .io = io,
            .root = root,
            .workspace_root = resolved,
            .configured_root = try allocator.dupe(u8, std.mem.trimEnd(u8, workspace_root, "/")),
        };
    }

    pub fn deinit(self: *Runtime) void {
        self.root.close(self.io);
        self.allocator.free(self.workspace_root);
        self.allocator.free(self.configured_root);
        self.* = undefined;
    }

    pub fn readFile(self: *Runtime, allocator: std.mem.Allocator, path: []const u8, offset: usize, limit: usize) ![]u8 {
        if (limit == 0 or limit > max_read_bytes) return error.InvalidLimit;
        var file = try self.openFile(path, .read_only);
        defer file.close(self.io);
        const stat = try file.stat(self.io);
        if (stat.kind != .file) return error.NotAFile;
        if (offset >= stat.size) return allocator.alloc(u8, 0);
        const available: usize = @intCast(@min(stat.size - offset, limit));
        const data = try allocator.alloc(u8, available);
        errdefer allocator.free(data);
        const read_len = try file.readPositionalAll(self.io, data, offset);
        return allocator.realloc(data, read_len);
    }

    pub fn writeFile(self: *Runtime, path: []const u8, content: []const u8, create_parent_dirs: bool) !void {
        if (content.len > max_write_bytes) return error.InputTooLarge;
        var target = try self.openTarget(path, create_parent_dirs);
        defer target.dir.close(self.io);

        var permissions: std.Io.File.Permissions = .default_file;
        if (target.dir.statFile(self.io, target.basename, .{ .follow_symlinks = false })) |stat| {
            if (stat.kind == .sym_link) return error.SymlinkNotAllowed;
            if (stat.kind != .file) return error.NotAFile;
            permissions = stat.permissions;
        } else |err| switch (err) {
            error.FileNotFound => {},
            else => |e| return e,
        }

        var atomic = try target.dir.createFileAtomic(self.io, target.basename, .{
            .permissions = permissions,
            .replace = true,
        });
        defer atomic.deinit(self.io);
        try atomic.file.writeStreamingAll(self.io, content);
        try atomic.replace(self.io);
    }

    pub fn editFile(self: *Runtime, allocator: std.mem.Allocator, path: []const u8, old_string: []const u8, new_string: []const u8) !void {
        if (old_string.len == 0) return error.EmptyOldString;
        const original = try self.readWholeFile(allocator, path, max_write_bytes);
        defer allocator.free(original);
        const first = std.mem.indexOf(u8, original, old_string) orelse return error.ExpectedOneMatch;
        if (std.mem.indexOfPos(u8, original, first + old_string.len, old_string) != null) return error.ExpectedOneMatch;
        const new_len = std.math.sub(usize, original.len, old_string.len) catch return error.InputTooLarge;
        const final_len = std.math.add(usize, new_len, new_string.len) catch return error.InputTooLarge;
        if (final_len > max_write_bytes) return error.InputTooLarge;
        const replacement = try allocator.alloc(u8, final_len);
        defer allocator.free(replacement);
        @memcpy(replacement[0..first], original[0..first]);
        @memcpy(replacement[first..][0..new_string.len], new_string);
        @memcpy(replacement[first + new_string.len ..], original[first + old_string.len ..]);
        try self.writeFile(path, replacement, false);
    }

    fn readWholeFile(self: *Runtime, allocator: std.mem.Allocator, path: []const u8, limit: usize) ![]u8 {
        var file = try self.openFile(path, .read_only);
        defer file.close(self.io);
        const stat = try file.stat(self.io);
        if (stat.kind != .file) return error.NotAFile;
        if (stat.size > limit) return error.InputTooLarge;
        const size: usize = @intCast(stat.size);
        const data = try allocator.alloc(u8, size);
        errdefer allocator.free(data);
        const read_len = try file.readPositionalAll(self.io, data, 0);
        if (read_len != size) return error.UnexpectedEndOfFile;
        return data;
    }

    fn openFile(self: *Runtime, path: []const u8, mode: std.Io.Dir.OpenFileOptions.Mode) !std.Io.File {
        var target = try self.openTarget(path, false);
        defer target.dir.close(self.io);
        return target.dir.openFile(self.io, target.basename, .{
            .mode = mode,
            .allow_directory = false,
            .follow_symlinks = false,
            .resolve_beneath = true,
        });
    }

    /// Models often echo the absolute workspace path they were shown, or the /workspace mount
    /// used by the command runtime. Map both onto the workspace root; anything else stays invalid.
    fn workspacePath(self: *const Runtime, path: []const u8) []const u8 {
        for ([_][]const u8{ self.workspace_root, self.configured_root, "/workspace" }) |root| {
            if (!std.fs.path.isAbsolute(root)) continue;
            if (!std.mem.startsWith(u8, path, root)) continue;
            const rest = path[root.len..];
            if (rest.len != 0 and !isSeparator(rest[0])) continue;
            const local = std.mem.trimStart(u8, rest, "/");
            return if (local.len == 0) "." else local;
        }
        return path;
    }

    fn openDirPath(self: *Runtime, raw_path: []const u8, create: bool, iterate: bool) !std.Io.Dir {
        const path = self.workspacePath(raw_path);
        try validateRelativePath(path, true);
        var current = try self.root.openDir(self.io, ".", .{ .iterate = iterate });
        errdefer current.close(self.io);
        var components = PathComponents.init(path);
        while (components.next()) |component| {
            const next = current.openDir(self.io, component, .{
                .iterate = iterate,
                .follow_symlinks = false,
            }) catch |err| switch (err) {
                error.FileNotFound => if (create) blk: {
                    try current.createDir(self.io, component, .default_dir);
                    break :blk try current.openDir(self.io, component, .{
                        .iterate = iterate,
                        .follow_symlinks = false,
                    });
                } else return err,
                else => |e| return e,
            };
            current.close(self.io);
            current = next;
        }
        return current;
    }

    const Target = struct {
        dir: std.Io.Dir,
        basename: []const u8,
    };

    fn openTarget(self: *Runtime, raw_path: []const u8, create_parent_dirs: bool) !Target {
        const path = self.workspacePath(raw_path);
        try validateRelativePath(path, false);
        const basename = std.fs.path.basename(path);
        if (basename.len == 0 or std.mem.eql(u8, basename, ".") or std.mem.eql(u8, basename, "..")) return error.InvalidPath;
        const parent = std.fs.path.dirname(path) orelse ".";
        return .{
            .dir = try self.openDirPath(parent, create_parent_dirs, false),
            .basename = basename,
        };
    }
};

pub const Registry = struct {
    allocator: std.mem.Allocator,
    runtime_ptr: *Runtime,
    schemas: [schema_texts.len]std.json.Parsed(std.json.Value),
    tool_items: [tool_names.len]harness.Tool,

    pub fn init(allocator: std.mem.Allocator, io: std.Io, workspace_root: []const u8) !Registry {
        const runtime_ptr = try allocator.create(Runtime);
        errdefer allocator.destroy(runtime_ptr);
        runtime_ptr.* = try Runtime.init(allocator, io, workspace_root);
        errdefer runtime_ptr.deinit();

        var schemas: [schema_texts.len]std.json.Parsed(std.json.Value) = undefined;
        var schema_count: usize = 0;
        errdefer for (schemas[0..schema_count]) |schema| schema.deinit();
        for (schema_texts, 0..) |text, index| {
            schemas[index] = try std.json.parseFromSlice(std.json.Value, allocator, text, .{});
            schema_count += 1;
        }

        var tool_items: [tool_names.len]harness.Tool = undefined;
        for (0..tool_items.len) |index| tool_items[index] = .{
            .name = tool_names[index],
            .description = tool_descriptions[index],
            .parameters = schemas[index].value,
            .context = runtime_ptr,
            .execute = execute_fns[index],
        };

        return .{
            .allocator = allocator,
            .runtime_ptr = runtime_ptr,
            .schemas = schemas,
            .tool_items = tool_items,
        };
    }

    pub fn deinit(self: *Registry) void {
        for (&self.schemas) |*schema| schema.deinit();
        self.runtime_ptr.deinit();
        self.allocator.destroy(self.runtime_ptr);
        self.* = undefined;
    }

    pub fn runtime(self: *Registry) *Runtime {
        return self.runtime_ptr;
    }

    pub fn tools(self: *const Registry) []const harness.Tool {
        return &self.tool_items;
    }

    pub fn values(self: *const Registry) []const harness.Tool {
        return self.tools();
    }

    pub fn get(self: *const Registry, name: []const u8) ?harness.Tool {
        for (self.tool_items) |tool| if (std.mem.eql(u8, tool.name, name)) return tool;
        return null;
    }
};

const ReadArgs = struct {
    path: []const u8,
    offset: usize = 0,
    limit: usize = max_read_bytes,
    start_line: ?usize = null,
    line_count: ?usize = null,
};

const WriteArgs = struct {
    path: []const u8,
    content: []const u8,
    create_parent_dirs: bool = true,
};

const EditArgs = struct {
    path: []const u8,
    old_string: []const u8,
    new_string: []const u8,
};

const SearchArgs = struct {
    pattern: []const u8,
    path: []const u8 = ".",
    include: ?[]const u8 = null,
    case_insensitive: bool = false,
    max_results: usize = 100,
};

const GlobArgs = struct {
    pattern: []const u8,
    path: []const u8 = ".",
    max_results: usize = 100,
};

const ListArgs = struct {
    path: []const u8 = ".",
    max_results: usize = 200,
};

const ShellArgs = struct {
    command: []const u8,
    timeout_ms: u64 = default_shell_timeout_ms,
    max_output_bytes: usize = max_output_bytes,
};

fn executeReadFile(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !agent_loop.ToolOutput {
    const runtime: *Runtime = @ptrCast(@alignCast(raw));
    var parsed = try std.json.parseFromValue(ReadArgs, allocator, call.arguments, .{ .ignore_unknown_fields = true });
    defer parsed.deinit();
    const args = parsed.value;
    if (args.start_line) |start_line| {
        if (args.offset != 0) return error.ConflictingRange;
        const whole = try runtime.readWholeFile(allocator, args.path, max_read_bytes);
        defer allocator.free(whole);
        const selected = try selectLines(allocator, whole, start_line, args.line_count orelse 200);
        defer allocator.free(selected);
        return textOutput(allocator, selected, false);
    }
    const data = try runtime.readFile(allocator, args.path, args.offset, args.limit);
    defer allocator.free(data);
    return textOutput(allocator, data, false);
}

fn executeWriteFile(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !agent_loop.ToolOutput {
    const runtime: *Runtime = @ptrCast(@alignCast(raw));
    var parsed = try std.json.parseFromValue(WriteArgs, allocator, call.arguments, .{ .ignore_unknown_fields = true });
    defer parsed.deinit();
    try runtime.writeFile(parsed.value.path, parsed.value.content, parsed.value.create_parent_dirs);
    const message = try std.fmt.allocPrint(allocator, "wrote {d} bytes to {s}", .{ parsed.value.content.len, parsed.value.path });
    defer allocator.free(message);
    return textOutput(allocator, message, false);
}

fn executeEditFile(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !agent_loop.ToolOutput {
    const runtime: *Runtime = @ptrCast(@alignCast(raw));
    var parsed = try std.json.parseFromValue(EditArgs, allocator, call.arguments, .{ .ignore_unknown_fields = true });
    defer parsed.deinit();
    try runtime.editFile(allocator, parsed.value.path, parsed.value.old_string, parsed.value.new_string);
    const message = try std.fmt.allocPrint(allocator, "edited {s}", .{parsed.value.path});
    defer allocator.free(message);
    return textOutput(allocator, message, false);
}

fn executeGrepFiles(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !agent_loop.ToolOutput {
    const runtime: *Runtime = @ptrCast(@alignCast(raw));
    var parsed = try std.json.parseFromValue(SearchArgs, allocator, call.arguments, .{ .ignore_unknown_fields = true });
    defer parsed.deinit();
    if (parsed.value.pattern.len == 0) return error.EmptyPattern;
    const limit = boundedResultLimit(parsed.value.max_results);
    const output = try grepFiles(runtime, allocator, parsed.value, limit);
    defer allocator.free(output);
    return textOutput(allocator, output, false);
}

fn executeGlobFiles(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !agent_loop.ToolOutput {
    return executeGlob(raw, call, allocator);
}

fn executeFindFiles(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !agent_loop.ToolOutput {
    return executeGlob(raw, call, allocator);
}

fn executeGlob(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !agent_loop.ToolOutput {
    const runtime: *Runtime = @ptrCast(@alignCast(raw));
    var parsed = try std.json.parseFromValue(GlobArgs, allocator, call.arguments, .{ .ignore_unknown_fields = true });
    defer parsed.deinit();
    if (parsed.value.pattern.len == 0) return error.EmptyPattern;
    const output = try globFiles(runtime, allocator, parsed.value.path, parsed.value.pattern, boundedResultLimit(parsed.value.max_results));
    defer allocator.free(output);
    return textOutput(allocator, output, false);
}

fn executeListFiles(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !agent_loop.ToolOutput {
    const runtime: *Runtime = @ptrCast(@alignCast(raw));
    var parsed = try std.json.parseFromValue(ListArgs, allocator, call.arguments, .{ .ignore_unknown_fields = true });
    defer parsed.deinit();
    const output = try listFiles(runtime, allocator, parsed.value.path, boundedResultLimit(parsed.value.max_results));
    defer allocator.free(output);
    return textOutput(allocator, output, false);
}

fn executeShell(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !agent_loop.ToolOutput {
    const runtime: *Runtime = @ptrCast(@alignCast(raw));
    var parsed = try std.json.parseFromValue(ShellArgs, allocator, call.arguments, .{ .ignore_unknown_fields = true });
    defer parsed.deinit();
    const args = parsed.value;
    if (args.command.len == 0) return error.EmptyCommand;
    if (args.timeout_ms == 0 or args.timeout_ms > max_shell_timeout_ms) return error.InvalidTimeout;
    if (args.max_output_bytes == 0 or args.max_output_bytes > max_output_bytes) return error.InvalidLimit;

    const argv: []const []const u8 = if (builtin.os.tag == .windows)
        &.{ "cmd.exe", "/d", "/s", "/c", args.command }
    else
        &.{ "/bin/sh", "-c", args.command };
    const result = try std.process.run(allocator, runtime.io, .{
        .argv = argv,
        .cwd = .{ .dir = runtime.root },
        .stdout_limit = .limited(args.max_output_bytes),
        .stderr_limit = .limited(args.max_output_bytes),
        .timeout = .{ .duration = .{
            .clock = .awake,
            .raw = .fromMilliseconds(@intCast(args.timeout_ms)),
        } },
    });
    defer allocator.free(result.stdout);
    defer allocator.free(result.stderr);

    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);
    const term = switch (result.term) {
        .exited => |code| try std.fmt.allocPrint(allocator, "exit_code: {d}\n", .{code}),
        .signal => |signal| try std.fmt.allocPrint(allocator, "signal: {d}\n", .{@intFromEnum(signal)}),
        .stopped => |signal| try std.fmt.allocPrint(allocator, "stopped: {d}\n", .{@intFromEnum(signal)}),
        .unknown => |code| try std.fmt.allocPrint(allocator, "status: {d}\n", .{code}),
    };
    defer allocator.free(term);
    try appendBounded(&output, allocator, term, args.max_output_bytes);
    if (result.stdout.len != 0) {
        try appendBounded(&output, allocator, "stdout:\n", args.max_output_bytes);
        try appendBounded(&output, allocator, result.stdout, args.max_output_bytes);
        if (result.stdout[result.stdout.len - 1] != '\n') try appendBounded(&output, allocator, "\n", args.max_output_bytes);
    }
    if (result.stderr.len != 0) {
        try appendBounded(&output, allocator, "stderr:\n", args.max_output_bytes);
        try appendBounded(&output, allocator, result.stderr, args.max_output_bytes);
        if (result.stderr[result.stderr.len - 1] != '\n') try appendBounded(&output, allocator, "\n", args.max_output_bytes);
    }
    return textOutput(allocator, output.items, switch (result.term) {
        .exited => |code| code != 0,
        else => true,
    });
}

fn grepFiles(runtime: *Runtime, allocator: std.mem.Allocator, args: SearchArgs, limit: usize) ![]u8 {
    var paths = try collectFiles(runtime, allocator, args.path);
    defer freeStrings(allocator, &paths);
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);
    var found: usize = 0;
    for (paths.items) |path| {
        if (args.include) |pattern| if (!globMatch(pattern, path) and !globMatch(pattern, std.fs.path.basename(path))) continue;
        const data = runtime.readWholeFile(allocator, path, max_search_file_bytes) catch |err| switch (err) {
            error.InputTooLarge, error.AccessDenied => continue,
            else => |e| return e,
        };
        defer allocator.free(data);
        var lines = std.mem.splitScalar(u8, data, '\n');
        var line_number: usize = 1;
        while (lines.next()) |line| : (line_number += 1) {
            const matches = if (args.case_insensitive)
                std.ascii.findIgnoreCase(line, args.pattern) != null
            else
                std.mem.indexOf(u8, line, args.pattern) != null;
            if (!matches) continue;
            const prefix = try std.fmt.allocPrint(allocator, "{s}:{d}:", .{ path, line_number });
            defer allocator.free(prefix);
            try appendBounded(&output, allocator, prefix, max_output_bytes);
            try appendBounded(&output, allocator, line, max_output_bytes);
            try appendBounded(&output, allocator, "\n", max_output_bytes);
            found += 1;
            if (found == limit) return output.toOwnedSlice(allocator);
        }
    }
    return output.toOwnedSlice(allocator);
}

fn globFiles(runtime: *Runtime, allocator: std.mem.Allocator, base_path: []const u8, pattern: []const u8, limit: usize) ![]u8 {
    var paths = try collectFiles(runtime, allocator, base_path);
    defer freeStrings(allocator, &paths);
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);
    var found: usize = 0;
    for (paths.items) |path| {
        if (!globMatch(pattern, path) and !globMatch(pattern, std.fs.path.basename(path))) continue;
        try appendBounded(&output, allocator, path, max_output_bytes);
        try appendBounded(&output, allocator, "\n", max_output_bytes);
        found += 1;
        if (found == limit) break;
    }
    return output.toOwnedSlice(allocator);
}

fn listFiles(runtime: *Runtime, allocator: std.mem.Allocator, path: []const u8, limit: usize) ![]u8 {
    var dir = try runtime.openDirPath(path, false, true);
    defer dir.close(runtime.io);
    var names: std.ArrayList([]u8) = .empty;
    defer freeStrings(allocator, &names);
    var iterator = dir.iterate();
    while (try iterator.next(runtime.io)) |entry| {
        const suffix: []const u8 = if (entry.kind == .directory) "/" else "";
        try names.append(allocator, try std.mem.concat(allocator, u8, &.{ entry.name, suffix }));
        if (names.items.len > max_results * 4) return error.TooManyEntries;
    }
    std.mem.sort([]u8, names.items, {}, stringLessThan);
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);
    for (names.items[0..@min(names.items.len, limit)]) |name| {
        try appendBounded(&output, allocator, name, max_output_bytes);
        try appendBounded(&output, allocator, "\n", max_output_bytes);
    }
    return output.toOwnedSlice(allocator);
}

fn collectFiles(runtime: *Runtime, allocator: std.mem.Allocator, base_path: []const u8) !std.ArrayList([]u8) {
    var result: std.ArrayList([]u8) = .empty;
    errdefer freeStrings(allocator, &result);

    if (runtime.openFile(base_path, .read_only)) |file_value| {
        var file = file_value;
        file.close(runtime.io);
        try result.append(allocator, try normalizeJoinedPath(allocator, ".", base_path));
        return result;
    } else |err| switch (err) {
        error.IsDir, error.NotDir => {},
        else => {},
    }

    var dir = try runtime.openDirPath(base_path, false, true);
    defer dir.close(runtime.io);
    var walker = try dir.walk(allocator);
    defer walker.deinit();
    while (try walker.next(runtime.io)) |entry| {
        if (entry.kind != .file) continue;
        try result.append(allocator, try normalizeJoinedPath(allocator, base_path, entry.path));
        if (result.items.len > max_results * 16) return error.TooManyEntries;
    }
    std.mem.sort([]u8, result.items, {}, stringLessThan);
    return result;
}

fn selectLines(allocator: std.mem.Allocator, data: []const u8, start_line: usize, line_count: usize) ![]u8 {
    if (start_line == 0 or line_count == 0 or line_count > 2000) return error.InvalidLineRange;
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);
    var lines = std.mem.splitScalar(u8, data, '\n');
    var number: usize = 1;
    var selected: usize = 0;
    while (lines.next()) |line| : (number += 1) {
        if (number < start_line) continue;
        if (selected == line_count) break;
        try appendBounded(&output, allocator, line, max_read_bytes);
        if (lines.index != null or data.len != 0) try appendBounded(&output, allocator, "\n", max_read_bytes);
        selected += 1;
    }
    return output.toOwnedSlice(allocator);
}

fn textOutput(allocator: std.mem.Allocator, text: []const u8, is_error: bool) !agent_loop.ToolOutput {
    if (text.len > max_output_bytes) return error.OutputTooLarge;
    const owned = try allocator.dupe(u8, text);
    errdefer allocator.free(owned);
    const content = try allocator.alloc(protocol.UserContent, 1);
    content[0] = .{ .text = .{ .text = owned } };
    return .{ .content = content, .is_error = is_error };
}

fn appendBounded(list: *std.ArrayList(u8), allocator: std.mem.Allocator, bytes: []const u8, limit: usize) !void {
    if (bytes.len > limit -| list.items.len) return error.OutputTooLarge;
    try list.appendSlice(allocator, bytes);
}

fn boundedResultLimit(value: usize) usize {
    return @max(1, @min(value, max_results));
}

fn validateRelativePath(path: []const u8, allow_dot: bool) !void {
    if (path.len == 0 or std.fs.path.isAbsolute(path) or std.mem.indexOfScalar(u8, path, 0) != null) return error.InvalidPath;
    var raw_index: usize = 0;
    var count: usize = 0;
    while (raw_index < path.len) {
        while (raw_index < path.len and isSeparator(path[raw_index])) raw_index += 1;
        if (raw_index == path.len) break;
        const start = raw_index;
        while (raw_index < path.len and !isSeparator(path[raw_index])) raw_index += 1;
        const component = path[start..raw_index];
        if (std.mem.eql(u8, component, "..")) return error.InvalidPath;
        if (!std.mem.eql(u8, component, ".")) count += 1;
    }
    if (count == 0 and !allow_dot) return error.InvalidPath;
}

const PathComponents = struct {
    path: []const u8,
    index: usize = 0,

    fn init(path: []const u8) PathComponents {
        return .{ .path = path };
    }

    fn next(self: *PathComponents) ?[]const u8 {
        while (self.index < self.path.len) {
            while (self.index < self.path.len and isSeparator(self.path[self.index])) self.index += 1;
            if (self.index == self.path.len) return null;
            const start = self.index;
            while (self.index < self.path.len and !isSeparator(self.path[self.index])) self.index += 1;
            const component = self.path[start..self.index];
            if (std.mem.eql(u8, component, ".")) continue;
            if (std.mem.eql(u8, component, "..")) return null;
            return component;
        }
        return null;
    }
};

fn isSeparator(byte: u8) bool {
    return byte == '/' or (builtin.os.tag == .windows and byte == '\\');
}

fn normalizeJoinedPath(allocator: std.mem.Allocator, base: []const u8, child: []const u8) ![]u8 {
    const joined = if (std.mem.eql(u8, base, "."))
        try allocator.dupe(u8, child)
    else
        try std.fs.path.join(allocator, &.{ base, child });
    if (builtin.os.tag == .windows) {
        for (joined) |*byte| {
            if (byte.* == '\\') byte.* = '/';
        }
    }
    return joined;
}

fn globMatch(pattern: []const u8, path: []const u8) bool {
    const pattern_segments = SegmentIterator.init(pattern);
    const path_segments = SegmentIterator.init(path);
    return matchSegments(pattern_segments, path_segments);
}

fn matchSegments(pattern: SegmentIterator, path: SegmentIterator) bool {
    var patterns = pattern;
    var paths = path;
    const pattern_segment = patterns.next() orelse return paths.next() == null;
    if (std.mem.eql(u8, pattern_segment, "**")) {
        if (matchSegments(patterns, paths)) return true;
        var advanced = paths;
        while (advanced.next() != null) if (matchSegments(patterns, advanced)) return true;
        return false;
    }
    const path_segment = paths.next() orelse return false;
    return matchSegment(pattern_segment, path_segment) and matchSegments(patterns, paths);
}

fn matchSegment(pattern: []const u8, text: []const u8) bool {
    var pattern_index: usize = 0;
    var text_index: usize = 0;
    var star_index: ?usize = null;
    var star_text_index: usize = 0;
    while (text_index < text.len) {
        if (pattern_index < pattern.len and (pattern[pattern_index] == '?' or pattern[pattern_index] == text[text_index])) {
            pattern_index += 1;
            text_index += 1;
        } else if (pattern_index < pattern.len and pattern[pattern_index] == '*') {
            star_index = pattern_index;
            pattern_index += 1;
            star_text_index = text_index;
        } else if (star_index) |star| {
            pattern_index = star + 1;
            star_text_index += 1;
            text_index = star_text_index;
        } else return false;
    }
    while (pattern_index < pattern.len and pattern[pattern_index] == '*') pattern_index += 1;
    return pattern_index == pattern.len;
}

const SegmentIterator = struct {
    text: []const u8,
    index: usize = 0,

    fn init(text: []const u8) SegmentIterator {
        return .{ .text = text };
    }

    fn next(self: *SegmentIterator) ?[]const u8 {
        while (self.index < self.text.len and isSeparator(self.text[self.index])) self.index += 1;
        if (self.index == self.text.len) return null;
        const start = self.index;
        while (self.index < self.text.len and !isSeparator(self.text[self.index])) self.index += 1;
        return self.text[start..self.index];
    }
};

fn stringLessThan(_: void, left: []u8, right: []u8) bool {
    return std.mem.order(u8, left, right) == .lt;
}

fn freeStrings(allocator: std.mem.Allocator, list: *std.ArrayList([]u8)) void {
    for (list.items) |item| allocator.free(item);
    list.deinit(allocator);
}

fn freeOutput(allocator: std.mem.Allocator, output: agent_loop.ToolOutput) void {
    for (output.content) |part| switch (part) {
        .text => |text| allocator.free(text.text),
        .image => |image| {
            allocator.free(image.data);
            allocator.free(image.mime_type);
        },
    };
    allocator.free(output.content);
}

fn parseValue(allocator: std.mem.Allocator, text: []const u8) !std.json.Parsed(std.json.Value) {
    return std.json.parseFromSlice(std.json.Value, allocator, text, .{});
}

test "registry exposes deterministic native tools" {
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();
    var root_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    var registry = try Registry.init(std.testing.allocator, std.testing.io, root_buffer[0..root_len]);
    defer registry.deinit();

    try std.testing.expectEqual(@as(usize, 8), registry.tools().len);
    for (registry.tools(), 0..) |tool, index| try std.testing.expectEqualStrings(tool_names[index], tool.name);
}

test "write edit and read stay inside workspace" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();
    var root_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    var registry = try Registry.init(allocator, std.testing.io, root_buffer[0..root_len]);
    defer registry.deinit();

    var write_args = try parseValue(allocator, "{\"path\":\"src/a.txt\",\"content\":\"one two\"}");
    defer write_args.deinit();
    const write_call = protocol.ToolCall{ .id = "1", .name = "write_file", .arguments = write_args.value };
    const write_output = try registry.get("write_file").?.execute(registry.runtime_ptr, &write_call, allocator);
    defer freeOutput(allocator, write_output);

    var edit_args = try parseValue(allocator, "{\"path\":\"src/a.txt\",\"old_string\":\"two\",\"new_string\":\"three\"}");
    defer edit_args.deinit();
    const edit_call = protocol.ToolCall{ .id = "2", .name = "edit_file", .arguments = edit_args.value };
    const edit_output = try registry.get("edit_file").?.execute(registry.runtime_ptr, &edit_call, allocator);
    defer freeOutput(allocator, edit_output);

    const contents = try registry.runtime().readFile(allocator, "src/a.txt", 0, max_read_bytes);
    defer allocator.free(contents);
    try std.testing.expectEqualStrings("one three", contents);
    try std.testing.expectError(error.InvalidPath, registry.runtime().writeFile("../escape.txt", "bad", true));
}

test "edit requires exactly one match and preserves the file on failure" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();
    var root_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    var runtime = try Runtime.init(allocator, std.testing.io, root_buffer[0..root_len]);
    defer runtime.deinit();

    try runtime.writeFile("dup.txt", "x x", false);
    try std.testing.expectError(error.ExpectedOneMatch, runtime.editFile(allocator, "dup.txt", "x", "y"));
    const contents = try runtime.readFile(allocator, "dup.txt", 0, max_read_bytes);
    defer allocator.free(contents);
    try std.testing.expectEqualStrings("x x", contents);
}

test "grep and glob results are sorted and bounded" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();
    var root_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    var runtime = try Runtime.init(allocator, std.testing.io, root_buffer[0..root_len]);
    defer runtime.deinit();

    try runtime.writeFile("z.txt", "needle\n", false);
    try runtime.writeFile("a.txt", "first\nneedle\n", false);
    const globbed = try globFiles(&runtime, allocator, ".", "*.txt", 10);
    defer allocator.free(globbed);
    try std.testing.expectEqualStrings("a.txt\nz.txt\n", globbed);
    const grepped = try grepFiles(&runtime, allocator, .{ .pattern = "needle" }, 10);
    defer allocator.free(grepped);
    try std.testing.expectEqualStrings("a.txt:2:needle\nz.txt:1:needle\n", grepped);
    try std.testing.expectError(error.OutputTooLarge, appendBoundedTest(allocator));
}

fn appendBoundedTest(allocator: std.mem.Allocator) !void {
    var list: std.ArrayList(u8) = .empty;
    defer list.deinit(allocator);
    try appendBounded(&list, allocator, "123", 2);
}

test "shell uses workspace cwd and reports exit status" {
    if (builtin.os.tag == .windows) return error.SkipZigTest;
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();
    var root_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    var registry = try Registry.init(allocator, std.testing.io, root_buffer[0..root_len]);
    defer registry.deinit();

    var args = try parseValue(allocator, "{\"command\":\"printf ok\",\"timeout_ms\":1000,\"max_output_bytes\":128}");
    defer args.deinit();
    const call = protocol.ToolCall{ .id = "1", .name = "shell", .arguments = args.value };
    const output = try registry.get("shell").?.execute(registry.runtime_ptr, &call, allocator);
    defer freeOutput(allocator, output);
    try std.testing.expect(!output.is_error);
    try std.testing.expectEqualStrings("exit_code: 0\nstdout:\nok\n", output.content[0].text.text);

    var bounded_args = try parseValue(allocator, "{\"command\":\"printf 12345\",\"timeout_ms\":1000,\"max_output_bytes\":4}");
    defer bounded_args.deinit();
    const bounded_call = protocol.ToolCall{ .id = "2", .name = "shell", .arguments = bounded_args.value };
    try std.testing.expectError(error.StreamTooLong, registry.get("shell").?.execute(registry.runtime_ptr, &bounded_call, allocator));

    var timeout_args = try parseValue(allocator, "{\"command\":\"sleep 1\",\"timeout_ms\":10,\"max_output_bytes\":128}");
    defer timeout_args.deinit();
    const timeout_call = protocol.ToolCall{ .id = "3", .name = "shell", .arguments = timeout_args.value };
    try std.testing.expectError(error.Timeout, registry.get("shell").?.execute(registry.runtime_ptr, &timeout_call, allocator));
}

test "workspace traversal rejects parent components and symlinks" {
    if (builtin.os.tag == .windows) return error.SkipZigTest;
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();
    var root_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const root_len = try tmp.dir.realPath(std.testing.io, &root_buffer);
    var runtime = try Runtime.init(allocator, std.testing.io, root_buffer[0..root_len]);
    defer runtime.deinit();

    try std.testing.expectError(error.InvalidPath, runtime.writeFile("safe/../escape.txt", "bad", true));
    try tmp.dir.symLink(std.testing.io, "/etc/passwd", "outside", .{});
    if (runtime.readFile(allocator, "outside", 0, 64)) |data| {
        allocator.free(data);
        return error.TestUnexpectedResult;
    } else |_| {}
}

test "absolute paths inside the workspace resolve to workspace-relative paths" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root = buffer[0..try tmp.dir.realPath(std.testing.io, &buffer)];
    var runtime = try Runtime.init(std.testing.allocator, std.testing.io, root);
    defer runtime.deinit();
    const inside = try std.fs.path.join(std.testing.allocator, &.{ root, "src/main.zig" });
    defer std.testing.allocator.free(inside);
    try std.testing.expectEqualStrings("src/main.zig", runtime.workspacePath(inside));
    try std.testing.expectEqualStrings(".", runtime.workspacePath(root));
    try std.testing.expectEqualStrings("a.txt", runtime.workspacePath("/workspace/a.txt"));
    try std.testing.expectEqualStrings("/workspacex/a.txt", runtime.workspacePath("/workspacex/a.txt"));
    try std.testing.expectEqualStrings("/etc/passwd", runtime.workspacePath("/etc/passwd"));
}
