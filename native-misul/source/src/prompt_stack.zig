const std = @import("std");
const protocol = @import("protocol.zig");

pub const CacheClass = enum { stable, session };
pub const Registration = struct { serial: u64 };

pub fn PromptStack(comptime Context: type) type {
    return struct {
        const Self = @This();
        pub const RenderFn = *const fn (allocator: std.mem.Allocator, context: Context) anyerror![]u8;
        pub const Content = union(enum) { static: []const u8, dynamic: RenderFn };
        pub const Layer = struct {
            id: []const u8,
            cache: CacheClass,
            order: i32 = 0,
            content: Content,
        };
        const Entry = struct { serial: u64, layer: Layer };

        allocator: std.mem.Allocator,
        entries: std.ArrayList(Entry) = .empty,
        next_serial: u64 = 1,

        pub fn init(allocator: std.mem.Allocator) Self {
            return .{ .allocator = allocator };
        }

        pub fn deinit(self: *Self) void {
            self.entries.deinit(self.allocator);
        }

        pub fn register(self: *Self, layer: Layer) !Registration {
            if (std.mem.trim(u8, layer.id, " \t\r\n").len == 0) return error.EmptyLayerId;
            const registration = Registration{ .serial = self.next_serial };
            self.next_serial += 1;
            try self.entries.append(self.allocator, .{ .serial = registration.serial, .layer = layer });
            return registration;
        }

        pub fn dispose(self: *Self, registration: Registration) void {
            for (self.entries.items, 0..) |entry, index| {
                if (entry.serial == registration.serial) {
                    _ = self.entries.orderedRemove(index);
                    return;
                }
            }
        }

        pub const Rendered = struct {
            stable_prompt: []u8,
            session_prompt: []u8,
            prompt: []u8,
            lane_count: u2,

            pub fn lanes(self: *const Rendered, output: *[2][]const u8) []const []const u8 {
                var count: usize = 0;
                if (self.stable_prompt.len != 0) {
                    output[count] = self.stable_prompt;
                    count += 1;
                }
                if (self.session_prompt.len != 0) {
                    output[count] = self.session_prompt;
                    count += 1;
                }
                return output[0..count];
            }

            pub fn deinit(self: *Rendered, allocator: std.mem.Allocator) void {
                allocator.free(self.stable_prompt);
                allocator.free(self.session_prompt);
                allocator.free(self.prompt);
                self.* = undefined;
            }
        };

        pub fn render(self: *const Self, context: Context) !Rendered {
            var active: std.ArrayList(Entry) = .empty;
            defer active.deinit(self.allocator);
            for (self.entries.items, 0..) |entry, index| {
                var shadowed = false;
                for (self.entries.items[index + 1 ..]) |later| {
                    if (std.mem.eql(u8, entry.layer.id, later.layer.id)) {
                        shadowed = true;
                        break;
                    }
                }
                if (!shadowed) try active.append(self.allocator, entry);
            }
            std.mem.sort(Entry, active.items, {}, lessThan);

            var stable: std.ArrayList(u8) = .empty;
            defer stable.deinit(self.allocator);
            var session: std.ArrayList(u8) = .empty;
            defer session.deinit(self.allocator);
            for (active.items) |entry| {
                const owned = switch (entry.layer.content) {
                    .static => |text| try self.allocator.dupe(u8, text),
                    .dynamic => |render_fn| try render_fn(self.allocator, context),
                };
                defer self.allocator.free(owned);
                if (owned.len == 0) continue;
                var lane = if (entry.layer.cache == .stable) &stable else &session;
                if (lane.items.len != 0) try lane.appendSlice(self.allocator, "\n\n");
                try lane.appendSlice(self.allocator, owned);
            }

            const stable_prompt = try self.allocator.dupe(u8, stable.items);
            errdefer self.allocator.free(stable_prompt);
            const session_prompt = try self.allocator.dupe(u8, session.items);
            errdefer self.allocator.free(session_prompt);
            const prompt = try protocol.joinSystemPromptLanes(self.allocator, stable.items, session.items);
            return .{
                .stable_prompt = stable_prompt,
                .session_prompt = session_prompt,
                .prompt = prompt,
                .lane_count = @intCast(@as(u3, @intFromBool(stable.items.len != 0)) + @as(u3, @intFromBool(session.items.len != 0))),
            };
        }

        fn lessThan(_: void, left: Entry, right: Entry) bool {
            if (left.layer.cache != right.layer.cache) return left.layer.cache == .stable;
            if (left.layer.order != right.layer.order) return left.layer.order < right.layer.order;
            return std.mem.order(u8, left.layer.id, right.layer.id) == .lt;
        }
    };
}

const TestContext = struct { cwd: []const u8, counter: *usize };

fn renderCwd(allocator: std.mem.Allocator, context: TestContext) ![]u8 {
    context.counter.* += 1;
    return std.fmt.allocPrint(allocator, "{s}:{d}", .{ context.cwd, context.counter.* });
}

test "stable and session layers render deterministically" {
    const Stack = PromptStack(TestContext);
    var stack = Stack.init(std.testing.allocator);
    defer stack.deinit();
    _ = try stack.register(.{ .id = "project", .cache = .session, .content = .{ .static = "cwd" } });
    _ = try stack.register(.{ .id = "tools", .cache = .stable, .order = 20, .content = .{ .static = "tools" } });
    _ = try stack.register(.{ .id = "constitution", .cache = .stable, .order = 10, .content = .{ .static = "constitution" } });
    var counter: usize = 0;
    var rendered = try stack.render(.{ .cwd = "/repo", .counter = &counter });
    defer rendered.deinit(std.testing.allocator);
    try std.testing.expectEqualStrings("constitution\n\ntools", rendered.stable_prompt);
    try std.testing.expectEqualStrings("cwd", rendered.session_prompt);
    try std.testing.expect(std.mem.indexOf(u8, rendered.prompt, protocol.dynamic_boundary) != null);
}

test "shadowed registrations restore and dynamic layers rerender" {
    const Stack = PromptStack(TestContext);
    var stack = Stack.init(std.testing.allocator);
    defer stack.deinit();
    _ = try stack.register(.{ .id = "policy", .cache = .stable, .content = .{ .static = "base" } });
    const overlay = try stack.register(.{ .id = "policy", .cache = .session, .content = .{ .static = "temporary" } });
    var counter: usize = 0;
    var first = try stack.render(.{ .cwd = "/repo", .counter = &counter });
    defer first.deinit(std.testing.allocator);
    try std.testing.expectEqualStrings("temporary", first.session_prompt);
    stack.dispose(overlay);
    stack.dispose(overlay);
    const dynamic = try stack.register(.{ .id = "cwd", .cache = .session, .content = .{ .dynamic = renderCwd } });
    defer stack.dispose(dynamic);
    var second = try stack.render(.{ .cwd = "/one", .counter = &counter });
    defer second.deinit(std.testing.allocator);
    var third = try stack.render(.{ .cwd = "/two", .counter = &counter });
    defer third.deinit(std.testing.allocator);
    try std.testing.expectEqualStrings("base", second.stable_prompt);
    try std.testing.expectEqualStrings("/one:1", second.session_prompt);
    try std.testing.expectEqualStrings("/two:2", third.session_prompt);
}

test "empty layer ids are rejected" {
    const Stack = PromptStack(void);
    var stack = Stack.init(std.testing.allocator);
    defer stack.deinit();
    try std.testing.expectError(error.EmptyLayerId, stack.register(.{ .id = " \t", .cache = .stable, .content = .{ .static = "bad" } }));
}
