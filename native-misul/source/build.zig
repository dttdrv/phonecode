const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const root_module = b.createModule(.{
        .root_source_file = b.path("src/root.zig"),
        .target = target,
        .optimize = optimize,
    });

    const exe = b.addExecutable(.{
        .name = "misul",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/main.zig"),
            .target = target,
            .optimize = optimize,
            .imports = &.{.{ .name = "misul_agent", .module = root_module }},
        }),
    });
    b.installArtifact(exe);

    const tests = b.addTest(.{ .root_module = root_module });
    const run_tests = b.addRunArtifact(tests);
    const test_step = b.step("test", "Run native tests");
    test_step.dependOn(&run_tests.step);

    const android_api_module = b.createModule(.{
        .root_source_file = b.path("src/android_api.zig"),
        .target = target,
        .optimize = optimize,
    });
    const android_api_tests = b.addTest(.{ .root_module = android_api_module });
    const run_android_api_tests = b.addRunArtifact(android_api_tests);
    b.step("android-api-test", "Run the Android native ABI contract tests").dependOn(&run_android_api_tests.step);

    const android_library = b.addLibrary(.{
        .name = "misul",
        .linkage = .dynamic,
        .root_module = android_api_module,
    });
    if (target.result.os.tag == .linux and target.result.abi == .android) {
        android_library.root_module.addCSourceFile(.{
            .file = b.path("src/android_signals.c"),
            .flags = &.{ "-std=c11", "-Wall", "-Wextra", "-Werror" },
        });
        android_library.root_module.linkSystemLibrary("c", .{});
        const build_id_hex = b.option([]const u8, "misul-build-id", "Hex build ID derived from the authenticated source manifest") orelse
            @panic("Android library builds require -Dmisul-build-id");
        if (build_id_hex.len == 0 or build_id_hex.len > 64 or build_id_hex.len % 2 != 0) @panic("invalid -Dmisul-build-id");
        var build_id_bytes: [32]u8 = undefined;
        const build_id = std.fmt.hexToBytes(build_id_bytes[0 .. build_id_hex.len / 2], build_id_hex) catch
            @panic("invalid -Dmisul-build-id");
        android_library.build_id = std.zig.BuildId.initHexString(build_id);
        android_library.link_z_common_page_size = 16 * 1024;
        android_library.link_z_max_page_size = 16 * 1024;
    } else {
        android_library.build_id = .sha1;
    }
    const install_android_library = b.addInstallArtifact(android_library, .{});
    b.step("android-library", "Build the Misul Android shared library").dependOn(&install_android_library.step);

    const run = b.addRunArtifact(exe);
    run.step.dependOn(b.getInstallStep());
    if (b.args) |args| run.addArgs(args);
    b.step("run", "Run Misul Agent").dependOn(&run.step);
}
