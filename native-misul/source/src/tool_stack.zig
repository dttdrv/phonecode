const std = @import("std");

pub const Registration = struct { serial: u64 };

pub fn ToolStack(comptime Tool: type) type {
    if (!@hasField(Tool, "name")) @compileError("ToolStack tools require a name field");
    return struct {
        const Self = @This();
        const Entry = struct { serial: u64, tool: Tool };

        allocator: std.mem.Allocator,
        base: std.ArrayList(Tool) = .empty,
        overlays: std.ArrayList(Entry) = .empty,
        next_serial: u64 = 1,

        pub fn init(allocator: std.mem.Allocator) Self {
            return .{ .allocator = allocator };
        }

        pub fn deinit(self: *Self) void {
            self.base.deinit(self.allocator);
            self.overlays.deinit(self.allocator);
        }

        pub fn setBase(self: *Self, tools: []const Tool) !void {
            self.base.clearRetainingCapacity();
            for (tools) |tool| {
                if (findByName(self.base.items, tool.name)) |index| {
                    self.base.items[index] = tool;
                } else {
                    try self.base.append(self.allocator, tool);
                }
            }
        }

        pub fn baseValues(self: *const Self, allocator: std.mem.Allocator) ![]Tool {
            return allocator.dupe(Tool, self.base.items);
        }

        pub fn register(self: *Self, tool: Tool) !Registration {
            const registration = Registration{ .serial = self.next_serial };
            self.next_serial += 1;
            try self.overlays.append(self.allocator, .{ .serial = registration.serial, .tool = tool });
            return registration;
        }

        pub fn dispose(self: *Self, registration: Registration) void {
            for (self.overlays.items, 0..) |entry, index| {
                if (entry.serial == registration.serial) {
                    _ = self.overlays.orderedRemove(index);
                    return;
                }
            }
        }

        pub fn get(self: *const Self, name: []const u8) ?Tool {
            var index = self.overlays.items.len;
            while (index > 0) {
                index -= 1;
                const entry = self.overlays.items[index];
                if (std.mem.eql(u8, entry.tool.name, name)) return entry.tool;
            }
            if (findByName(self.base.items, name)) |base_index| return self.base.items[base_index];
            return null;
        }

        pub fn has(self: *const Self, name: []const u8) bool {
            return self.get(name) != null;
        }

        pub fn values(self: *const Self, allocator: std.mem.Allocator) ![]Tool {
            var names: std.ArrayList([]const u8) = .empty;
            defer names.deinit(allocator);
            for (self.base.items) |tool| try appendUnique(&names, allocator, tool.name);
            for (self.overlays.items) |entry| try appendUnique(&names, allocator, entry.tool.name);
            std.mem.sort([]const u8, names.items, {}, stringLessThan);
            const result = try allocator.alloc(Tool, names.items.len);
            errdefer allocator.free(result);
            for (names.items, 0..) |name, index| result[index] = self.get(name).?;
            return result;
        }

        fn findByName(tools: []const Tool, name: []const u8) ?usize {
            for (tools, 0..) |tool, index| if (std.mem.eql(u8, tool.name, name)) return index;
            return null;
        }

        fn appendUnique(names: *std.ArrayList([]const u8), allocator: std.mem.Allocator, name: []const u8) !void {
            for (names.items) |current| if (std.mem.eql(u8, current, name)) return;
            try names.append(allocator, name);
        }

        fn stringLessThan(_: void, left: []const u8, right: []const u8) bool {
            return std.mem.order(u8, left, right) == .lt;
        }
    };
}

const TestTool = struct { name: []const u8, version: []const u8 };

test "tools resolve by name and shadowed registrations restore" {
    const Stack = ToolStack(TestTool);
    var stack = Stack.init(std.testing.allocator);
    defer stack.deinit();
    try stack.setBase(&.{ .{ .name = "write", .version = "base" }, .{ .name = "read", .version = "base" } });
    const first = try stack.register(.{ .name = "read", .version = "first" });
    const second = try stack.register(.{ .name = "read", .version = "second" });
    const resolved = try stack.values(std.testing.allocator);
    defer std.testing.allocator.free(resolved);
    try std.testing.expectEqualStrings("read", resolved[0].name);
    try std.testing.expectEqualStrings("second", resolved[0].version);
    try std.testing.expectEqualStrings("write", resolved[1].name);
    stack.dispose(second);
    try std.testing.expectEqualStrings("first", stack.get("read").?.version);
    stack.dispose(first);
    stack.dispose(first);
    try std.testing.expectEqualStrings("base", stack.get("read").?.version);
}

test "base replacement preserves overlays and values are detached" {
    const Stack = ToolStack(TestTool);
    var stack = Stack.init(std.testing.allocator);
    defer stack.deinit();
    try stack.setBase(&.{.{ .name = "read", .version = "base-1" }});
    const overlay = try stack.register(.{ .name = "read", .version = "plugin" });
    try stack.setBase(&.{.{ .name = "read", .version = "base-2" }});
    var values = try stack.values(std.testing.allocator);
    values[0].version = "changed-copy";
    std.testing.allocator.free(values);
    try std.testing.expectEqualStrings("plugin", stack.get("read").?.version);
    stack.dispose(overlay);
    try std.testing.expectEqualStrings("base-2", stack.get("read").?.version);
}
