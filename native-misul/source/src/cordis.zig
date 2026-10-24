const std = @import("std");

pub const CleanupFn = *const fn (*anyopaque) void;
pub const StartFn = *const fn (*Context, *Fiber, *anyopaque) anyerror!void;

pub const Plugin = struct {
    name: []const u8,
    revision: u64 = 0,
    requires: []const []const u8 = &.{},
    state: *anyopaque,
    start: StartFn,
};

const Service = struct {
    serial: u64,
    key: []const u8,
    value: *anyopaque,
};

const Effect = struct {
    state: *anyopaque,
    cleanup: CleanupFn,
};

const ServiceEffect = struct {
    context: *Context,
    serial: u64,
};

pub const Context = struct {
    allocator: std.mem.Allocator,
    parent: ?*Context = null,
    services: std.ArrayList(Service) = .empty,
    fibers: std.ArrayList(*Fiber) = .empty,
    next_serial: u64 = 1,

    pub fn init(allocator: std.mem.Allocator, parent: ?*Context) Context {
        return .{ .allocator = allocator, .parent = parent };
    }

    pub fn deinit(self: *Context) void {
        while (self.fibers.items.len != 0) self.fibers.items[self.fibers.items.len - 1].dispose();
        self.fibers.deinit(self.allocator);
        self.services.deinit(self.allocator);
        self.* = undefined;
    }

    pub fn child(self: *Context) Context {
        return init(self.allocator, self);
    }

    pub fn serviceKey(comptime T: type) []const u8 {
        return @typeName(T);
    }

    pub fn get(self: *const Context, comptime T: type) ?*T {
        const raw = self.getByKey(serviceKey(T)) orelse return null;
        return @ptrCast(@alignCast(raw));
    }

    pub fn require(self: *const Context, comptime T: type) !*T {
        return self.get(T) orelse error.MissingService;
    }

    pub fn getByKey(self: *const Context, key: []const u8) ?*anyopaque {
        var index = self.services.items.len;
        while (index > 0) {
            index -= 1;
            const service = self.services.items[index];
            if (std.mem.eql(u8, service.key, key)) return service.value;
        }
        return if (self.parent) |parent| parent.getByKey(key) else null;
    }

    pub fn mount(self: *Context, plugin: Plugin) !*Fiber {
        if (self.findFiber(plugin.name) != null) return error.PluginAlreadyMounted;
        return self.mountCandidate(plugin);
    }

    pub fn replace(self: *Context, plugin: Plugin) !*Fiber {
        const old = self.findFiber(plugin.name) orelse return self.mountCandidate(plugin);
        if (old.plugin.revision == plugin.revision) return old;
        const candidate = try self.mountCandidate(plugin);
        old.dispose();
        return candidate;
    }

    pub fn unmount(self: *Context, name: []const u8) bool {
        const fiber = self.findFiber(name) orelse return false;
        fiber.dispose();
        return true;
    }

    /// Reconciles in declaration order so dependencies can be mounted earlier in the list.
    /// Existing plugins with the same name and revision are retained; stale plugins are
    /// removed in reverse mount order after additions and replacements succeed.
    pub fn reconcile(self: *Context, desired: []const Plugin) !void {
        for (desired, 0..) |plugin, index| {
            for (desired[0..index]) |earlier| {
                if (std.mem.eql(u8, earlier.name, plugin.name)) return error.DuplicatePlugin;
            }
            _ = try self.replace(plugin);
        }

        var index = self.fibers.items.len;
        while (index > 0) {
            index -= 1;
            const fiber = self.fibers.items[index];
            var wanted = false;
            for (desired) |plugin| {
                if (std.mem.eql(u8, plugin.name, fiber.plugin.name)) {
                    wanted = true;
                    break;
                }
            }
            if (!wanted) fiber.dispose();
        }
    }

    fn mountCandidate(self: *Context, plugin: Plugin) !*Fiber {
        if (std.mem.trim(u8, plugin.name, " \t\r\n").len == 0) return error.EmptyPluginName;
        for (plugin.requires) |key| {
            if (self.getByKey(key) == null) return error.MissingDependency;
        }

        const fiber = try self.allocator.create(Fiber);
        fiber.* = .{ .context = self, .plugin = plugin };
        errdefer self.allocator.destroy(fiber);
        errdefer fiber.effects.deinit(self.allocator);
        errdefer fiber.rollback();
        try plugin.start(self, fiber, plugin.state);
        try self.fibers.append(self.allocator, fiber);
        return fiber;
    }

    fn findFiber(self: *const Context, name: []const u8) ?*Fiber {
        var index = self.fibers.items.len;
        while (index > 0) {
            index -= 1;
            const fiber = self.fibers.items[index];
            if (std.mem.eql(u8, fiber.plugin.name, name)) return fiber;
        }
        return null;
    }

    fn removeService(self: *Context, serial: u64) void {
        for (self.services.items, 0..) |service, index| {
            if (service.serial == serial) {
                _ = self.services.orderedRemove(index);
                return;
            }
        }
    }

    fn removeFiber(self: *Context, target: *Fiber) void {
        for (self.fibers.items, 0..) |fiber, index| {
            if (fiber == target) {
                _ = self.fibers.orderedRemove(index);
                return;
            }
        }
    }
};

pub const Fiber = struct {
    context: *Context,
    plugin: Plugin,
    effects: std.ArrayList(Effect) = .empty,
    disposed: bool = false,

    pub fn provide(self: *Fiber, comptime T: type, value: *T) !void {
        try self.provideAs(Context.serviceKey(T), value);
    }

    pub fn provideAs(self: *Fiber, key: []const u8, value: *anyopaque) !void {
        if (self.disposed) return error.FiberDisposed;
        if (std.mem.trim(u8, key, " \t\r\n").len == 0) return error.EmptyServiceKey;

        const serial = self.context.next_serial;
        self.context.next_serial += 1;
        try self.context.services.append(self.context.allocator, .{ .serial = serial, .key = key, .value = value });
        errdefer self.context.removeService(serial);

        const state = try self.context.allocator.create(ServiceEffect);
        state.* = .{ .context = self.context, .serial = serial };
        errdefer self.context.allocator.destroy(state);
        try self.effect(state, cleanupService);
    }

    pub fn effect(self: *Fiber, state: *anyopaque, cleanup: CleanupFn) !void {
        if (self.disposed) return error.FiberDisposed;
        try self.effects.append(self.context.allocator, .{ .state = state, .cleanup = cleanup });
    }

    pub fn dispose(self: *Fiber) void {
        if (self.disposed) return;
        self.disposed = true;
        self.context.removeFiber(self);
        self.rollback();
        const allocator = self.context.allocator;
        self.effects.deinit(allocator);
        allocator.destroy(self);
    }

    fn rollback(self: *Fiber) void {
        var index = self.effects.items.len;
        while (index > 0) {
            index -= 1;
            const entry = self.effects.items[index];
            entry.cleanup(entry.state);
        }
        self.effects.clearRetainingCapacity();
    }
};

fn cleanupService(raw: *anyopaque) void {
    const state: *ServiceEffect = @ptrCast(@alignCast(raw));
    const allocator = state.context.allocator;
    state.context.removeService(state.serial);
    allocator.destroy(state);
}

const TestService = struct { value: u8 };
const OtherService = struct { value: u8 };
const TestState = struct {
    service: TestService,
    cleanup_log: *std.ArrayList(u8),
    fail: bool = false,
};

fn appendCleanup(raw: *anyopaque) void {
    const state: *TestState = @ptrCast(@alignCast(raw));
    state.cleanup_log.append(std.testing.allocator, state.service.value) catch unreachable;
}

fn startTest(_: *Context, fiber: *Fiber, raw: *anyopaque) !void {
    const state: *TestState = @ptrCast(@alignCast(raw));
    try fiber.effect(state, appendCleanup);
    try fiber.provide(TestService, &state.service);
    if (state.fail) return error.StartFailed;
}

fn startOther(_: *Context, fiber: *Fiber, raw: *anyopaque) !void {
    const service: *OtherService = @ptrCast(@alignCast(raw));
    try fiber.provide(OtherService, service);
}

const OrderState = struct { log: *std.ArrayList(u8), value: u8 };

fn recordOrder(raw: *anyopaque) void {
    const state: *OrderState = @ptrCast(@alignCast(raw));
    state.log.append(std.testing.allocator, state.value) catch unreachable;
}

fn startOrdered(_: *Context, fiber: *Fiber, raw: *anyopaque) !void {
    const states: *[3]OrderState = @ptrCast(@alignCast(raw));
    for (states) |*state| try fiber.effect(state, recordOrder);
}

test "fiber effects clean up once in reverse registration order" {
    var log: std.ArrayList(u8) = .empty;
    defer log.deinit(std.testing.allocator);
    var context = Context.init(std.testing.allocator, null);
    defer context.deinit();
    var states = [_]OrderState{
        .{ .log = &log, .value = 1 },
        .{ .log = &log, .value = 2 },
        .{ .log = &log, .value = 3 },
    };
    const fiber = try context.mount(.{ .name = "ordered", .state = &states, .start = startOrdered });
    fiber.dispose();
    try std.testing.expectEqualSlices(u8, &.{ 3, 2, 1 }, log.items);
}

test "child contexts inherit and shadow typed services" {
    var log: std.ArrayList(u8) = .empty;
    defer log.deinit(std.testing.allocator);
    var parent = Context.init(std.testing.allocator, null);
    defer parent.deinit();
    var parent_state = TestState{ .service = .{ .value = 1 }, .cleanup_log = &log };
    _ = try parent.mount(.{ .name = "parent", .state = &parent_state, .start = startTest });

    var child = parent.child();
    defer child.deinit();
    try std.testing.expectEqual(@as(u8, 1), (try child.require(TestService)).value);
    var child_state = TestState{ .service = .{ .value = 2 }, .cleanup_log = &log };
    const overlay = try child.mount(.{ .name = "overlay", .state = &child_state, .start = startTest });
    try std.testing.expectEqual(@as(u8, 2), (try child.require(TestService)).value);
    overlay.dispose();
    try std.testing.expectEqual(@as(u8, 1), (try child.require(TestService)).value);
}

test "failed hot reload rolls back and successful reload cleans old fiber" {
    var log: std.ArrayList(u8) = .empty;
    defer log.deinit(std.testing.allocator);
    var context = Context.init(std.testing.allocator, null);
    defer context.deinit();
    var first = TestState{ .service = .{ .value = 1 }, .cleanup_log = &log };
    _ = try context.mount(.{ .name = "service", .revision = 1, .state = &first, .start = startTest });

    var broken = TestState{ .service = .{ .value = 2 }, .cleanup_log = &log, .fail = true };
    try std.testing.expectError(error.StartFailed, context.replace(.{ .name = "service", .revision = 2, .state = &broken, .start = startTest }));
    try std.testing.expectEqual(@as(u8, 1), (try context.require(TestService)).value);
    try std.testing.expectEqualSlices(u8, &.{2}, log.items);

    var replacement = TestState{ .service = .{ .value = 3 }, .cleanup_log = &log };
    _ = try context.replace(.{ .name = "service", .revision = 3, .state = &replacement, .start = startTest });
    try std.testing.expectEqual(@as(u8, 3), (try context.require(TestService)).value);
    try std.testing.expectEqualSlices(u8, &.{ 2, 1 }, log.items);
}

test "dependencies, reconciliation, and reverse cleanup are deterministic" {
    var log: std.ArrayList(u8) = .empty;
    defer log.deinit(std.testing.allocator);
    var context = Context.init(std.testing.allocator, null);
    defer context.deinit();
    var test_state = TestState{ .service = .{ .value = 7 }, .cleanup_log = &log };
    var other = OtherService{ .value = 9 };
    const plugins = [_]Plugin{
        .{ .name = "test", .state = &test_state, .start = startTest },
        .{ .name = "other", .requires = &.{Context.serviceKey(TestService)}, .state = &other, .start = startOther },
    };
    try context.reconcile(&plugins);
    try std.testing.expectEqual(@as(u8, 9), (try context.require(OtherService)).value);
    try context.reconcile(plugins[0..1]);
    try std.testing.expect(context.get(OtherService) == null);
    try std.testing.expectEqual(@as(u8, 7), (try context.require(TestService)).value);
    try context.reconcile(&.{});
    try std.testing.expectEqualSlices(u8, &.{7}, log.items);
}
