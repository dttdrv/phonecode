const std = @import("std");
const protocol = @import("protocol.zig");
const agent_loop = @import("agent_loop.zig");
const harness = @import("harness.zig");
const skills = @import("skills.zig");

const schema_source =
    \\{"type":"object","properties":{"name":{"type":"string","description":"Exact skill name from the stable catalog"}},"required":["name"],"additionalProperties":false}
;

pub const Runtime = struct {
    io: std.Io,
    catalog: *const skills.Catalog,
    arena: std.heap.ArenaAllocator,
    schema: std.json.Value,

    pub fn init(self: *Runtime, allocator: std.mem.Allocator, io: std.Io, catalog: *const skills.Catalog) !void {
        self.* = .{
            .io = io,
            .catalog = catalog,
            .arena = std.heap.ArenaAllocator.init(allocator),
            .schema = .null,
        };
        errdefer self.arena.deinit();
        self.schema = try std.json.parseFromSliceLeaky(std.json.Value, self.arena.allocator(), schema_source, .{});
    }

    pub fn deinit(self: *Runtime) void {
        self.arena.deinit();
        self.* = undefined;
    }

    pub fn tool(self: *Runtime) harness.Tool {
        return .{
            .name = "skill",
            .description = "Load one skill body by exact name from the advertised catalog.",
            .parameters = self.schema,
            .context = self,
            .execute = execute,
        };
    }

    fn execute(raw: *anyopaque, call: *const protocol.ToolCall, allocator: std.mem.Allocator) !agent_loop.ToolOutput {
        const self: *Runtime = @ptrCast(@alignCast(raw));
        if (call.arguments != .object) return error.InvalidArguments;
        const value = call.arguments.object.get("name") orelse return error.InvalidArguments;
        if (value != .string) return error.InvalidArguments;
        const body = try self.catalog.load(self.io, allocator, value.string);
        const content = try allocator.alloc(protocol.UserContent, 1);
        content[0] = .{ .text = .{ .text = body } };
        return .{ .content = content };
    }
};

test "skill tool loads an exact catalog entry" {
    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    try tmp.dir.createDirPath(std.testing.io, "review");
    try tmp.dir.writeFile(std.testing.io, .{ .sub_path = "review/SKILL.md", .data = "---\nname: review\ndescription: Review code\n---\nCheck invariants.\n" });
    var buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const length = try tmp.dir.realPath(std.testing.io, &buffer);
    var catalog = skills.Catalog.init(allocator);
    defer catalog.deinit();
    try catalog.addRoot(std.testing.io, buffer[0..length]);
    var runtime: Runtime = undefined;
    try runtime.init(allocator, std.testing.io, &catalog);
    defer runtime.deinit();
    var arguments_arena = std.heap.ArenaAllocator.init(allocator);
    defer arguments_arena.deinit();
    const arguments = try std.json.parseFromSliceLeaky(std.json.Value, arguments_arena.allocator(), "{\"name\":\"review\"}", .{});
    const call = protocol.ToolCall{ .id = "1", .name = "skill", .arguments = arguments };
    const output = try Runtime.execute(&runtime, &call, allocator);
    defer {
        allocator.free(output.content[0].text.text);
        allocator.free(output.content);
    }
    try std.testing.expect(std.mem.indexOf(u8, output.content[0].text.text, "Check invariants") != null);
}
