const std = @import("std");

pub const schema_version: u16 = 1;
pub const max_manifest_bytes: usize = 16 * 1024;
pub const max_identifier_bytes: usize = 128;
pub const max_string_bytes: usize = 512;
pub const max_dependencies: usize = 32;
pub const max_services: usize = 64;
pub const max_tools: usize = 64;
pub const max_permissions: usize = @typeInfo(Permission).@"enum".fields.len;
pub const max_plugins: usize = 64;
pub const Digest = [std.crypto.hash.sha2.Sha256.digest_length]u8;
const VisitColor = enum { white, gray, black };

pub const Permission = enum(u8) {
    workspace_read,
    workspace_mutation,
    shell_process,
    plugin_lifecycle,
    instruction_lifecycle,
    network,
    delegation,
};

pub const PermissionSet = struct {
    bits: u16 = 0,

    pub fn all() PermissionSet {
        return .{ .bits = (@as(u16, 1) << @intCast(max_permissions)) - 1 };
    }

    pub fn fromSlice(permissions: []const Permission) !PermissionSet {
        var result = PermissionSet{};
        for (permissions) |permission| {
            const bit = @as(u16, 1) << @intCast(@intFromEnum(permission));
            if (result.bits & bit != 0) return error.DuplicatePermission;
            result.bits |= bit;
        }
        return result;
    }

    pub fn contains(self: PermissionSet, permission: Permission) bool {
        return self.bits & (@as(u16, 1) << @intCast(@intFromEnum(permission))) != 0;
    }
};

pub const Version = struct {
    major: u32,
    minor: u32,
    patch: u32,
    prerelease: ?[]const u8 = null,
    build: ?[]const u8 = null,

    pub fn parse(text: []const u8) !Version {
        if (text.len == 0 or text.len > max_string_bytes) return error.InvalidSemanticVersion;

        const plus = std.mem.indexOfScalar(u8, text, '+');
        if (plus) |index| {
            if (std.mem.indexOfScalarPos(u8, text, index + 1, '+') != null) return error.InvalidSemanticVersion;
        }
        const before_build = if (plus) |index| text[0..index] else text;
        const build = if (plus) |index| text[index + 1 ..] else null;
        if (build) |value| try validateIdentifiers(value, true);

        const dash = std.mem.indexOfScalar(u8, before_build, '-');
        const core = if (dash) |index| before_build[0..index] else before_build;
        const prerelease = if (dash) |index| before_build[index + 1 ..] else null;
        if (prerelease) |value| try validateIdentifiers(value, false);

        var parts = std.mem.splitScalar(u8, core, '.');
        const major_text = parts.next() orelse return error.InvalidSemanticVersion;
        const minor_text = parts.next() orelse return error.InvalidSemanticVersion;
        const patch_text = parts.next() orelse return error.InvalidSemanticVersion;
        if (parts.next() != null) return error.InvalidSemanticVersion;
        return .{
            .major = try parseCoreNumber(major_text),
            .minor = try parseCoreNumber(minor_text),
            .patch = try parseCoreNumber(patch_text),
            .prerelease = prerelease,
            .build = build,
        };
    }

    pub fn order(self: Version, other: Version) std.math.Order {
        var result = std.math.order(self.major, other.major);
        if (result != .eq) return result;
        result = std.math.order(self.minor, other.minor);
        if (result != .eq) return result;
        result = std.math.order(self.patch, other.patch);
        if (result != .eq) return result;

        if (self.prerelease == null and other.prerelease == null) return .eq;
        if (self.prerelease == null) return .gt;
        if (other.prerelease == null) return .lt;

        var left = std.mem.splitScalar(u8, self.prerelease.?, '.');
        var right = std.mem.splitScalar(u8, other.prerelease.?, '.');
        while (true) {
            const left_part = left.next();
            const right_part = right.next();
            if (left_part == null and right_part == null) return .eq;
            if (left_part == null) return .lt;
            if (right_part == null) return .gt;
            const left_numeric = isNumeric(left_part.?);
            const right_numeric = isNumeric(right_part.?);
            if (left_numeric and !right_numeric) return .lt;
            if (!left_numeric and right_numeric) return .gt;
            if (left_numeric) {
                result = numericIdentifierOrder(left_part.?, right_part.?);
            } else {
                result = std.mem.order(u8, left_part.?, right_part.?);
            }
            if (result != .eq) return result;
        }
    }

    pub fn validate(self: Version) !void {
        var encoded_len = decimalLength(self.major) + decimalLength(self.minor) + decimalLength(self.patch) + 2;
        if (self.prerelease) |value| {
            if (value.len > max_string_bytes) return error.InvalidSemanticVersion;
            try validateIdentifiers(value, false);
            encoded_len = std.math.add(usize, encoded_len, value.len + 1) catch return error.InvalidSemanticVersion;
        }
        if (self.build) |value| {
            if (value.len > max_string_bytes) return error.InvalidSemanticVersion;
            try validateIdentifiers(value, true);
            encoded_len = std.math.add(usize, encoded_len, value.len + 1) catch return error.InvalidSemanticVersion;
        }
        if (encoded_len > max_string_bytes) return error.InvalidSemanticVersion;
    }
};

pub const VersionRange = struct {
    minimum: Version,
    maximum_exclusive: Version,

    pub fn contains(self: VersionRange, version: Version) bool {
        return self.minimum.order(version) != .gt and version.order(self.maximum_exclusive) == .lt;
    }
};

pub const Dependency = struct {
    identity: []const u8,
    versions: VersionRange,
};

pub const Tool = struct {
    name: []const u8,
    permission: Permission,
};

pub const Manifest = struct {
    schema: u16,
    identity: []const u8,
    version: Version,
    entry_point: []const u8,
    compatibility: VersionRange,
    dependencies: []const Dependency,
    services: []const []const u8,
    tools: []const Tool,
    permissions: []const Permission,

    pub fn permissionSet(self: Manifest) PermissionSet {
        return PermissionSet.fromSlice(self.permissions) catch unreachable;
    }
};

pub const OwnedManifest = struct {
    arena: std.heap.ArenaAllocator,
    value: Manifest,

    pub fn deinit(self: *OwnedManifest) void {
        self.arena.deinit();
        self.* = undefined;
    }
};

pub const ValidationContext = struct {
    host_version: Version,
    permission_ceiling: PermissionSet,
    reserved_core_tools: []const []const u8,
};

pub const ValidatedGraph = struct {
    activation_order: []usize,
    canonical_order: []usize,
    digest: Digest,

    pub fn deinit(self: *ValidatedGraph, allocator: std.mem.Allocator) void {
        allocator.free(self.activation_order);
        allocator.free(self.canonical_order);
        self.* = undefined;
    }
};

const WireRange = struct {
    minimum: []const u8,
    maximum_exclusive: []const u8,
};

const WireDependency = struct {
    identity: []const u8,
    minimum: []const u8,
    maximum_exclusive: []const u8,
};

const WireTool = struct {
    name: []const u8,
    permission: Permission,
};

const WireManifest = struct {
    schema_version: u16,
    identity: []const u8,
    version: []const u8,
    entry_point: []const u8,
    compatibility: WireRange,
    dependencies: []const WireDependency,
    services: []const []const u8,
    tools: []const WireTool,
    permissions: []const Permission,
};

pub fn parseOwned(allocator: std.mem.Allocator, input: []const u8) !OwnedManifest {
    if (input.len == 0 or input.len > max_manifest_bytes) return error.ManifestTooLarge;
    var arena = std.heap.ArenaAllocator.init(allocator);
    errdefer arena.deinit();
    const arena_allocator = arena.allocator();
    const wire = std.json.parseFromSliceLeaky(WireManifest, arena_allocator, input, .{
        .allocate = .alloc_always,
        .max_value_len = max_string_bytes,
    }) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        error.UnknownField => return error.UnknownManifestField,
        error.DuplicateField => return error.DuplicateManifestField,
        else => return error.InvalidManifest,
    };
    if (wire.schema_version != schema_version) return error.UnsupportedManifestSchema;
    if (wire.dependencies.len > max_dependencies or wire.services.len > max_services or wire.tools.len > max_tools or wire.permissions.len > max_permissions) return error.ManifestLimitExceeded;
    try validateIdentifier(wire.identity);
    try validateIdentifier(wire.entry_point);

    const version = try Version.parse(wire.version);
    const compatibility = VersionRange{
        .minimum = try Version.parse(wire.compatibility.minimum),
        .maximum_exclusive = try Version.parse(wire.compatibility.maximum_exclusive),
    };
    if (compatibility.minimum.order(compatibility.maximum_exclusive) != .lt) return error.InvalidVersionRange;

    const dependencies = try arena_allocator.alloc(Dependency, wire.dependencies.len);
    for (wire.dependencies, 0..) |dependency, index| {
        try validateIdentifier(dependency.identity);
        if (std.mem.eql(u8, dependency.identity, wire.identity)) return error.SelfDependency;
        dependencies[index] = .{
            .identity = dependency.identity,
            .versions = .{
                .minimum = try Version.parse(dependency.minimum),
                .maximum_exclusive = try Version.parse(dependency.maximum_exclusive),
            },
        };
        if (dependencies[index].versions.minimum.order(dependencies[index].versions.maximum_exclusive) != .lt) return error.InvalidVersionRange;
    }
    sortDependencies(dependencies);
    if (dependencies.len > 1) {
        for (dependencies[1..], dependencies[0 .. dependencies.len - 1]) |current, previous| {
            if (std.mem.eql(u8, current.identity, previous.identity)) return error.DuplicateDependency;
        }
    }

    const services = try arena_allocator.dupe([]const u8, wire.services);
    for (services) |service| try validateIdentifier(service);
    sortStrings(services);
    if (services.len > 1) {
        for (services[1..], services[0 .. services.len - 1]) |current, previous| {
            if (std.mem.eql(u8, current, previous)) return error.DuplicateService;
        }
    }

    const permissions = try arena_allocator.dupe(Permission, wire.permissions);
    sortPermissions(permissions);
    _ = try PermissionSet.fromSlice(permissions);

    const tools = try arena_allocator.alloc(Tool, wire.tools.len);
    for (wire.tools, 0..) |tool, index| {
        try validateIdentifier(tool.name);
        tools[index] = .{ .name = tool.name, .permission = tool.permission };
    }
    sortTools(tools);
    if (tools.len > 1) {
        for (tools[1..], tools[0 .. tools.len - 1]) |current, previous| {
            if (std.mem.eql(u8, current.name, previous.name)) return error.DuplicateTool;
        }
    }
    const permission_set = try PermissionSet.fromSlice(permissions);
    for (tools) |tool| if (!permission_set.contains(tool.permission)) return error.UndeclaredToolPermission;

    return .{
        .arena = arena,
        .value = .{
            .schema = wire.schema_version,
            .identity = wire.identity,
            .version = version,
            .entry_point = wire.entry_point,
            .compatibility = compatibility,
            .dependencies = dependencies,
            .services = services,
            .tools = tools,
            .permissions = permissions,
        },
    };
}

pub fn validateGraph(allocator: std.mem.Allocator, manifests: []const Manifest, context: ValidationContext) !ValidatedGraph {
    if (manifests.len > max_plugins) return error.PluginGraphLimitExceeded;
    try context.host_version.validate();
    for (context.reserved_core_tools) |name| try validateIdentifier(name);

    for (manifests, 0..) |current, index| {
        try validateManifest(current);
        if (!current.compatibility.contains(context.host_version)) return error.IncompatibleHostVersion;
        for (current.permissions) |permission| if (!context.permission_ceiling.contains(permission)) return error.PluginOverprivileged;
        for (manifests[0..index]) |previous| {
            if (std.mem.eql(u8, current.identity, previous.identity)) return error.DuplicatePluginIdentity;
        }
        for (current.tools) |tool| {
            for (context.reserved_core_tools) |reserved| {
                if (std.mem.eql(u8, tool.name, reserved)) return error.ReservedCoreToolShadowed;
            }
            for (manifests[0..index]) |previous| {
                for (previous.tools) |other| if (std.mem.eql(u8, tool.name, other.name)) return error.DuplicateGraphTool;
            }
        }
        for (current.services) |service| {
            for (manifests[0..index]) |previous| {
                for (previous.services) |other| if (std.mem.eql(u8, service, other)) return error.DuplicateGraphService;
            }
        }
    }

    for (manifests) |current| {
        for (current.dependencies) |dependency| {
            const dependency_index = findManifest(manifests, dependency.identity) orelse return error.MissingPluginDependency;
            if (!dependency.versions.contains(manifests[dependency_index].version)) return error.IncompatiblePluginDependency;
        }
    }

    const canonical_order = try allocator.alloc(usize, manifests.len);
    errdefer allocator.free(canonical_order);
    for (canonical_order, 0..) |*slot, index| slot.* = index;
    sortManifestIndices(canonical_order, manifests);

    const activation_order = try allocator.alloc(usize, manifests.len);
    errdefer allocator.free(activation_order);
    const colors = try allocator.alloc(VisitColor, manifests.len);
    defer allocator.free(colors);
    @memset(colors, .white);
    var activation_count: usize = 0;
    for (canonical_order) |index| try visitDependency(index, manifests, colors, activation_order, &activation_count);
    std.debug.assert(activation_count == manifests.len);

    var hasher = std.crypto.hash.sha2.Sha256.init(.{});
    hashInt(&hasher, u16, schema_version);
    hashInt(&hasher, u16, @intCast(manifests.len));
    for (canonical_order) |index| {
        const encoded = try encodeCanonical(allocator, manifests[index]);
        defer allocator.free(encoded);
        hashInt(&hasher, u32, @intCast(encoded.len));
        hasher.update(encoded);
    }
    var digest: Digest = undefined;
    hasher.final(&digest);
    return .{ .activation_order = activation_order, .canonical_order = canonical_order, .digest = digest };
}

pub fn encodeCanonical(allocator: std.mem.Allocator, value: Manifest) ![]u8 {
    try validateManifest(value);
    var output: std.Io.Writer.Allocating = .init(allocator);
    errdefer output.deinit();
    writeCanonical(&output.writer, value) catch return error.OutOfMemory;
    if (output.written().len > max_manifest_bytes) return error.ManifestTooLarge;
    return output.toOwnedSlice();
}

fn writeCanonical(writer: *std.Io.Writer, value: Manifest) !void {
    try writer.writeAll("{\"schema_version\":");
    try writer.print("{d}", .{value.schema});
    try writer.writeAll(",\"identity\":");
    try writeJsonString(writer, value.identity);
    try writer.writeAll(",\"version\":\"");
    try writeVersion(writer, value.version);
    try writer.writeAll("\",\"entry_point\":");
    try writeJsonString(writer, value.entry_point);
    try writer.writeAll(",\"compatibility\":{\"minimum\":\"");
    try writeVersion(writer, value.compatibility.minimum);
    try writer.writeAll("\",\"maximum_exclusive\":\"");
    try writeVersion(writer, value.compatibility.maximum_exclusive);
    try writer.writeAll("\"},\"dependencies\":[");
    for (value.dependencies, 0..) |dependency, index| {
        if (index != 0) try writer.writeByte(',');
        try writer.writeAll("{\"identity\":");
        try writeJsonString(writer, dependency.identity);
        try writer.writeAll(",\"minimum\":\"");
        try writeVersion(writer, dependency.versions.minimum);
        try writer.writeAll("\",\"maximum_exclusive\":\"");
        try writeVersion(writer, dependency.versions.maximum_exclusive);
        try writer.writeAll("\"}");
    }
    try writer.writeAll("],\"services\":[");
    for (value.services, 0..) |service, index| {
        if (index != 0) try writer.writeByte(',');
        try writeJsonString(writer, service);
    }
    try writer.writeAll("],\"tools\":[");
    for (value.tools, 0..) |tool, index| {
        if (index != 0) try writer.writeByte(',');
        try writer.writeAll("{\"name\":");
        try writeJsonString(writer, tool.name);
        try writer.writeAll(",\"permission\":");
        try writeJsonString(writer, @tagName(tool.permission));
        try writer.writeByte('}');
    }
    try writer.writeAll("],\"permissions\":[");
    for (value.permissions, 0..) |permission, index| {
        if (index != 0) try writer.writeByte(',');
        try writeJsonString(writer, @tagName(permission));
    }
    try writer.writeAll("]}");
}

pub fn cloneOwned(allocator: std.mem.Allocator, value: Manifest) !OwnedManifest {
    const encoded = try encodeCanonical(allocator, value);
    defer allocator.free(encoded);
    return parseOwned(allocator, encoded);
}

fn validateManifest(value: Manifest) !void {
    if (value.schema != schema_version) return error.UnsupportedManifestSchema;
    if (value.dependencies.len > max_dependencies or value.services.len > max_services or value.tools.len > max_tools or value.permissions.len > max_permissions) return error.ManifestLimitExceeded;
    try validateIdentifier(value.identity);
    try validateIdentifier(value.entry_point);
    try value.version.validate();
    try value.compatibility.minimum.validate();
    try value.compatibility.maximum_exclusive.validate();
    if (value.compatibility.minimum.order(value.compatibility.maximum_exclusive) != .lt) return error.InvalidVersionRange;
    _ = try PermissionSet.fromSlice(value.permissions);
    for (value.dependencies, 0..) |dependency, index| {
        try validateIdentifier(dependency.identity);
        try dependency.versions.minimum.validate();
        try dependency.versions.maximum_exclusive.validate();
        if (std.mem.eql(u8, dependency.identity, value.identity)) return error.SelfDependency;
        if (dependency.versions.minimum.order(dependency.versions.maximum_exclusive) != .lt) return error.InvalidVersionRange;
        for (value.dependencies[0..index]) |previous| if (std.mem.eql(u8, dependency.identity, previous.identity)) return error.DuplicateDependency;
        if (index != 0 and std.mem.order(u8, value.dependencies[index - 1].identity, dependency.identity) != .lt) return error.NonCanonicalManifest;
    }
    for (value.services, 0..) |service, index| {
        try validateIdentifier(service);
        for (value.services[0..index]) |previous| if (std.mem.eql(u8, service, previous)) return error.DuplicateService;
        if (index != 0 and std.mem.order(u8, value.services[index - 1], service) != .lt) return error.NonCanonicalManifest;
    }
    const permissions = try PermissionSet.fromSlice(value.permissions);
    for (value.permissions, 0..) |permission, index| {
        if (index != 0 and @intFromEnum(value.permissions[index - 1]) >= @intFromEnum(permission)) return error.NonCanonicalManifest;
    }
    for (value.tools, 0..) |tool, index| {
        try validateIdentifier(tool.name);
        if (!permissions.contains(tool.permission)) return error.UndeclaredToolPermission;
        for (value.tools[0..index]) |previous| if (std.mem.eql(u8, tool.name, previous.name)) return error.DuplicateTool;
        if (index != 0 and std.mem.order(u8, value.tools[index - 1].name, tool.name) != .lt) return error.NonCanonicalManifest;
    }
}

fn findManifest(manifests: []const Manifest, identity: []const u8) ?usize {
    for (manifests, 0..) |manifest, index| if (std.mem.eql(u8, manifest.identity, identity)) return index;
    return null;
}

fn visitDependency(index: usize, manifests: []const Manifest, colors: []VisitColor, output: []usize, count: *usize) !void {
    switch (colors[index]) {
        .black => return,
        .gray => return error.PluginDependencyCycle,
        .white => {},
    }
    colors[index] = .gray;
    for (manifests[index].dependencies) |dependency| {
        const dependency_index = findManifest(manifests, dependency.identity) orelse return error.MissingPluginDependency;
        try visitDependency(dependency_index, manifests, colors, output, count);
    }
    colors[index] = .black;
    output[count.*] = index;
    count.* += 1;
}

fn sortManifestIndices(indices: []usize, manifests: []const Manifest) void {
    var index: usize = 1;
    while (index < indices.len) : (index += 1) {
        var cursor = index;
        while (cursor > 0 and std.mem.order(u8, manifests[indices[cursor]].identity, manifests[indices[cursor - 1]].identity) == .lt) : (cursor -= 1) {
            std.mem.swap(usize, &indices[cursor], &indices[cursor - 1]);
        }
    }
}

fn writeJsonString(writer: *std.Io.Writer, value: []const u8) !void {
    try writer.print("{f}", .{std.json.fmt(value, .{})});
}

fn writeVersion(writer: *std.Io.Writer, version: Version) !void {
    try writer.print("{d}.{d}.{d}", .{ version.major, version.minor, version.patch });
    if (version.prerelease) |value| try writer.print("-{s}", .{value});
    if (version.build) |value| try writer.print("+{s}", .{value});
}

fn hashInt(hasher: *std.crypto.hash.sha2.Sha256, comptime T: type, value: T) void {
    var bytes: [@sizeOf(T)]u8 = undefined;
    std.mem.writeInt(T, &bytes, value, .little);
    hasher.update(&bytes);
}

fn parseCoreNumber(text: []const u8) !u32 {
    if (!isNumeric(text) or (text.len > 1 and text[0] == '0')) return error.InvalidSemanticVersion;
    return std.fmt.parseUnsigned(u32, text, 10) catch return error.InvalidSemanticVersion;
}

fn decimalLength(value: u32) usize {
    var remaining = value;
    var length: usize = 1;
    while (remaining >= 10) : (length += 1) remaining /= 10;
    return length;
}

fn validateIdentifiers(text: []const u8, allow_numeric_leading_zero: bool) !void {
    if (text.len == 0) return error.InvalidSemanticVersion;
    var parts = std.mem.splitScalar(u8, text, '.');
    while (parts.next()) |part| {
        if (part.len == 0) return error.InvalidSemanticVersion;
        var numeric = true;
        for (part) |byte| {
            if (!std.ascii.isAlphanumeric(byte) and byte != '-') return error.InvalidSemanticVersion;
            numeric = numeric and std.ascii.isDigit(byte);
        }
        if (!allow_numeric_leading_zero and numeric and part.len > 1 and part[0] == '0') return error.InvalidSemanticVersion;
    }
}

fn isNumeric(text: []const u8) bool {
    if (text.len == 0) return false;
    for (text) |byte| if (!std.ascii.isDigit(byte)) return false;
    return true;
}

fn numericIdentifierOrder(left: []const u8, right: []const u8) std.math.Order {
    const length_order = std.math.order(left.len, right.len);
    return if (length_order == .eq) std.mem.order(u8, left, right) else length_order;
}

fn validateIdentifier(value: []const u8) !void {
    if (value.len == 0 or value.len > max_identifier_bytes or !std.ascii.isLower(value[0])) return error.InvalidIdentifier;
    for (value[1..]) |byte| {
        if (!std.ascii.isLower(byte) and !std.ascii.isDigit(byte) and byte != '.' and byte != '-' and byte != '_') return error.InvalidIdentifier;
    }
}

fn sortDependencies(values: []Dependency) void {
    var index: usize = 1;
    while (index < values.len) : (index += 1) {
        var cursor = index;
        while (cursor > 0 and std.mem.order(u8, values[cursor].identity, values[cursor - 1].identity) == .lt) : (cursor -= 1) {
            std.mem.swap(Dependency, &values[cursor], &values[cursor - 1]);
        }
    }
}

fn sortStrings(values: [][]const u8) void {
    var index: usize = 1;
    while (index < values.len) : (index += 1) {
        var cursor = index;
        while (cursor > 0 and std.mem.order(u8, values[cursor], values[cursor - 1]) == .lt) : (cursor -= 1) {
            std.mem.swap([]const u8, &values[cursor], &values[cursor - 1]);
        }
    }
}

fn sortPermissions(values: []Permission) void {
    var index: usize = 1;
    while (index < values.len) : (index += 1) {
        var cursor = index;
        while (cursor > 0 and @intFromEnum(values[cursor]) < @intFromEnum(values[cursor - 1])) : (cursor -= 1) {
            std.mem.swap(Permission, &values[cursor], &values[cursor - 1]);
        }
    }
}

fn sortTools(values: []Tool) void {
    var index: usize = 1;
    while (index < values.len) : (index += 1) {
        var cursor = index;
        while (cursor > 0 and std.mem.order(u8, values[cursor].name, values[cursor - 1].name) == .lt) : (cursor -= 1) {
            std.mem.swap(Tool, &values[cursor], &values[cursor - 1]);
        }
    }
}
