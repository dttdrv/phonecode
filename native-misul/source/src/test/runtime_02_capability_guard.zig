const std = @import("std");
const event_store = @import("event_store");

test "external caller cannot construct or retarget a live prepared append" {
    try std.testing.expect(!@hasField(event_store.PreparedAppend, "owner_store"));
    try std.testing.expect(!@hasField(event_store.PreparedAppend, "owner_generation"));
    try std.testing.expect(!@hasField(event_store.PreparedAppend, "reservation_id"));
    try std.testing.expect(!@hasField(event_store.PreparedAppend, "token"));
    try std.testing.expect(!@hasField(event_store.Store, "prepared_states"));
    try std.testing.expect(!@hasField(event_store.Store, "projection_cursors"));

    const allocator = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "opaque-capability.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();

    var prepared = try opened.store.prepareAppend(.{ .kind = .message_appended, .event_id = "protected", .payload = "payload" }, "external-test", .succeeded);
    var changed_raw = @intFromEnum(prepared) ^ 1;
    if (changed_raw == 0) changed_raw = 1;
    var caller_chosen: event_store.PreparedAppend = @enumFromInt(changed_raw);
    try std.testing.expectError(error.PreparedStoreMismatch, opened.store.commitPrepared(&caller_chosen));
    try std.testing.expectEqual(@as(u64, 0), try opened.store.file.length(std.testing.io));

    var committed = try opened.store.commitPrepared(&prepared);
    defer committed.deinit(allocator);
    try std.testing.expectEqualStrings("protected", committed.event_id);
    try std.testing.expectEqual(@as(u64, 1), committed.global_sequence);
}
