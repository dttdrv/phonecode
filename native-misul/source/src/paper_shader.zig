const std = @import("std");

pub const width: usize = 128;
pub const height: usize = 128;
const header_bytes: usize = 54;
const pixel_bytes: usize = width * height * 3;
pub const bmp_bytes: usize = header_bytes + pixel_bytes;

pub fn generateBmp(allocator: std.mem.Allocator) ![]u8 {
    const bmp = try allocator.alloc(u8, bmp_bytes);
    @memset(bmp, 0);

    bmp[0] = 'B';
    bmp[1] = 'M';
    std.mem.writeInt(u32, bmp[2..6], @intCast(bmp.len), .little);
    std.mem.writeInt(u32, bmp[10..14], header_bytes, .little);
    std.mem.writeInt(u32, bmp[14..18], 40, .little);
    std.mem.writeInt(u32, bmp[18..22], width, .little);
    std.mem.writeInt(u32, bmp[22..26], height, .little);
    std.mem.writeInt(u16, bmp[26..28], 1, .little);
    std.mem.writeInt(u16, bmp[28..30], 24, .little);
    std.mem.writeInt(u32, bmp[34..38], pixel_bytes, .little);
    std.mem.writeInt(u32, bmp[38..42], 2_835, .little);
    std.mem.writeInt(u32, bmp[42..46], 2_835, .little);

    for (0..height) |y| {
        for (0..width) |x| {
            const color = paperPixel(x, y);
            const offset = header_bytes + (y * width + x) * 3;
            bmp[offset] = color[2];
            bmp[offset + 1] = color[1];
            bmp[offset + 2] = color[0];
        }
    }
    return bmp;
}

pub fn main(init: std.process.Init) !void {
    var args = try std.process.Args.Iterator.initAllocator(init.minimal.args, init.gpa);
    defer args.deinit();
    _ = args.next();
    const output_path = args.next() orelse return error.MissingOutputPath;
    if (args.next() != null) return error.TooManyArguments;

    const bmp = try generateBmp(init.gpa);
    defer init.gpa.free(bmp);
    const file = try std.Io.Dir.cwd().createFile(init.io, output_path, .{});
    defer file.close(init.io);
    try file.writeStreamingAll(init.io, bmp);
    try file.sync(init.io);
}

fn paperPixel(x: usize, y: usize) [3]u8 {
    const tile_x = if (x == width - 1) 0 else x;
    const tile_y = if (y == height - 1) 0 else y;
    const grain = signedSample(noise(tile_x, tile_y), 7);
    const fiber = signedSample(noise(0, tile_y), 5);
    const weave = signedSample(noise(tile_x / 8, tile_y / 16), 3);
    const value = grain + fiber + weave;
    return .{
        channel(12 + value),
        channel(19 + value + @divTrunc(weave, 2)),
        channel(32 + value * 2 + weave),
    };
}

fn noise(x: usize, y: usize) u32 {
    var value: u32 = @intCast(x | (y << 8));
    value +%= 0x9e37_79b9;
    value ^= value >> 16;
    value *%= 0x7feb_352d;
    value ^= value >> 15;
    value *%= 0x846c_a68b;
    return value ^ (value >> 16);
}

fn signedSample(value: u32, span: u32) i16 {
    return @as(i16, @intCast(value % span)) - @as(i16, @intCast(span / 2));
}

fn channel(value: i16) u8 {
    return @intCast(std.math.clamp(value, 0, 255));
}

test "paper texture has a valid 128 by 128 RGB bitmap header" {
    const bmp = try generateBmp(std.testing.allocator);
    defer std.testing.allocator.free(bmp);

    try std.testing.expectEqual(bmp_bytes, bmp.len);
    try std.testing.expectEqualSlices(u8, "BM", bmp[0..2]);
    try std.testing.expectEqual(@as(u32, @intCast(bmp_bytes)), std.mem.readInt(u32, bmp[2..6], .little));
    try std.testing.expectEqual(@as(u32, header_bytes), std.mem.readInt(u32, bmp[10..14], .little));
    try std.testing.expectEqual(@as(u32, width), std.mem.readInt(u32, bmp[18..22], .little));
    try std.testing.expectEqual(@as(u32, height), std.mem.readInt(u32, bmp[22..26], .little));
    try std.testing.expectEqual(@as(u16, 24), std.mem.readInt(u16, bmp[28..30], .little));
    try std.testing.expectEqual(@as(u32, pixel_bytes), std.mem.readInt(u32, bmp[34..38], .little));
}

test "paper texture generation is deterministic" {
    const first = try generateBmp(std.testing.allocator);
    defer std.testing.allocator.free(first);
    const second = try generateBmp(std.testing.allocator);
    defer std.testing.allocator.free(second);

    try std.testing.expectEqualSlices(u8, first, second);
}

test "paper texture is dark low contrast and visibly varied" {
    const bmp = try generateBmp(std.testing.allocator);
    defer std.testing.allocator.free(bmp);

    var minimum: u16 = std.math.maxInt(u16);
    var maximum: u16 = 0;
    var offset: usize = header_bytes;
    while (offset < bmp.len) : (offset += 3) {
        const blue: u16 = bmp[offset];
        const green: u16 = bmp[offset + 1];
        const red: u16 = bmp[offset + 2];
        const luminance = (red * 54 + green * 183 + blue * 19) / 256;
        minimum = @min(minimum, luminance);
        maximum = @max(maximum, luminance);
    }

    try std.testing.expect(minimum >= 10);
    try std.testing.expect(maximum <= 30);
    try std.testing.expect(maximum - minimum >= 4);
}

test "paper texture joins exactly at every repeated tile edge" {
    const bmp = try generateBmp(std.testing.allocator);
    defer std.testing.allocator.free(bmp);
    const pixels = bmp[header_bytes..];

    for (0..height) |y| {
        const left = (y * width) * 3;
        const right = (y * width + width - 1) * 3;
        try std.testing.expectEqualSlices(u8, pixels[left..][0..3], pixels[right..][0..3]);
    }
    for (0..width) |x| {
        const bottom = x * 3;
        const top = ((height - 1) * width + x) * 3;
        try std.testing.expectEqualSlices(u8, pixels[bottom..][0..3], pixels[top..][0..3]);
    }
}
