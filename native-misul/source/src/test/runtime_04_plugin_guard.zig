const std = @import("std");
const plugin_registry = @import("plugin_registry");
const plugin_manifest = plugin_registry.plugin_manifest;
const registry_manifest = plugin_manifest;
const cordis = plugin_registry.cordis;
const event_store = plugin_registry.event_store;

const base_manifest =
    \\{
    \\  "schema_version": 1,
    \\  "identity": "acme.echo",
    \\  "version": "1.2.3-alpha.1+build.7",
    \\  "entry_point": "acme.echo",
    \\  "compatibility": { "minimum": "1.0.0", "maximum_exclusive": "2.0.0" },
    \\  "dependencies": [],
    \\  "services": ["acme.echo.service"],
    \\  "tools": [{ "name": "echo", "permission": "workspace_read" }],
    \\  "permissions": ["workspace_read"]
    \\}
;

const dependency_manifest =
    \\{"schema_version":1,"identity":"acme.zdep","version":"1.0.0","entry_point":"acme.echo","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[],"services":[],"tools":[],"permissions":[]}
;

const consumer_manifest =
    \\{"schema_version":1,"identity":"acme.consumer","version":"1.0.0","entry_point":"acme.echo","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[{"identity":"acme.zdep","minimum":"1.0.0","maximum_exclusive":"2.0.0"}],"services":[],"tools":[],"permissions":[]}
;

test "bounded manifest parses strict semantic versions" {
    var owned = try plugin_manifest.parseOwned(std.testing.allocator, base_manifest);
    defer owned.deinit();

    try std.testing.expectEqualStrings("acme.echo", owned.value.identity);
    try std.testing.expectEqual(@as(u32, 1), owned.value.version.major);
    try std.testing.expectEqualStrings("alpha.1", owned.value.version.prerelease.?);
    try std.testing.expectEqualStrings("build.7", owned.value.version.build.?);
    try std.testing.expectError(error.InvalidSemanticVersion, plugin_manifest.Version.parse("01.2.3"));
    const prerelease = try plugin_manifest.Version.parse("1.2.3-alpha.1");
    const release = try plugin_manifest.Version.parse("1.2.3");
    try std.testing.expectEqual(std.math.Order.lt, prerelease.order(release));
    const beta_two = try plugin_manifest.Version.parse("1.2.3-beta.2+first");
    const beta_eleven = try plugin_manifest.Version.parse("1.2.3-beta.11+second");
    try std.testing.expectEqual(std.math.Order.lt, beta_two.order(beta_eleven));
    try std.testing.expectEqual(std.math.Order.eq, release.order(try plugin_manifest.Version.parse("1.2.3+ignored")));

    var forged = owned.value;
    forged.version.prerelease = "invalid!";
    const context = plugin_manifest.ValidationContext{
        .host_version = try plugin_manifest.Version.parse("1.5.0"),
        .permission_ceiling = plugin_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{},
    };
    try std.testing.expectError(error.InvalidSemanticVersion, plugin_manifest.validateGraph(std.testing.allocator, &.{forged}, context));
    var long_prerelease: [plugin_manifest.max_string_bytes]u8 = @splat('a');
    forged.version.prerelease = &long_prerelease;
    try std.testing.expectError(error.InvalidSemanticVersion, plugin_manifest.validateGraph(std.testing.allocator, &.{forged}, context));
}

test "full graph validation rejects invalid and overprivileged graphs" {
    const allocator = std.testing.allocator;
    const base_json =
        \\{"schema_version":1,"identity":"acme.base","version":"1.4.0","entry_point":"acme.base","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[],"services":["acme.base.service"],"tools":[],"permissions":[]}
    ;
    const consumer_json =
        \\{"schema_version":1,"identity":"acme.consumer","version":"2.0.0","entry_point":"acme.consumer","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[{"identity":"acme.base","minimum":"1.2.0","maximum_exclusive":"2.0.0"}],"services":[],"tools":[{"name":"consumer.read","permission":"workspace_read"}],"permissions":["workspace_read"]}
    ;
    var base = try plugin_manifest.parseOwned(allocator, base_json);
    defer base.deinit();
    var consumer = try plugin_manifest.parseOwned(allocator, consumer_json);
    defer consumer.deinit();
    const context = plugin_manifest.ValidationContext{
        .host_version = try plugin_manifest.Version.parse("1.5.0"),
        .permission_ceiling = try plugin_manifest.PermissionSet.fromSlice(&.{.workspace_read}),
        .reserved_core_tools = &.{ "shell", "read_file" },
    };
    const valid = [_]plugin_manifest.Manifest{ consumer.value, base.value };
    var graph = try plugin_manifest.validateGraph(allocator, &valid, context);
    defer graph.deinit(allocator);
    try std.testing.expectEqual(@as(usize, 1), graph.activation_order[0]);
    try std.testing.expectEqual(@as(usize, 0), graph.activation_order[1]);
    const incompatible_host = plugin_manifest.ValidationContext{
        .host_version = try plugin_manifest.Version.parse("2.0.0"),
        .permission_ceiling = context.permission_ceiling,
        .reserved_core_tools = context.reserved_core_tools,
    };
    try std.testing.expectError(error.IncompatibleHostVersion, plugin_manifest.validateGraph(allocator, &valid, incompatible_host));

    var noncanonical = base.value;
    noncanonical.services = &.{ "z.service", "a.service" };
    try std.testing.expectError(error.NonCanonicalManifest, plugin_manifest.validateGraph(allocator, &.{noncanonical}, context));

    const duplicate = [_]plugin_manifest.Manifest{ base.value, base.value };
    try std.testing.expectError(error.DuplicatePluginIdentity, plugin_manifest.validateGraph(allocator, &duplicate, context));

    const missing_json =
        \\{"schema_version":1,"identity":"acme.missing","version":"1.0.0","entry_point":"acme.missing","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[{"identity":"acme.absent","minimum":"1.0.0","maximum_exclusive":"2.0.0"}],"services":[],"tools":[],"permissions":[]}
    ;
    var missing = try plugin_manifest.parseOwned(allocator, missing_json);
    defer missing.deinit();
    try std.testing.expectError(error.MissingPluginDependency, plugin_manifest.validateGraph(allocator, &.{missing.value}, context));

    const mismatched_json =
        \\{"schema_version":1,"identity":"acme.mismatch","version":"1.0.0","entry_point":"acme.mismatch","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[{"identity":"acme.base","minimum":"1.5.0","maximum_exclusive":"2.0.0"}],"services":[],"tools":[],"permissions":[]}
    ;
    var mismatched = try plugin_manifest.parseOwned(allocator, mismatched_json);
    defer mismatched.deinit();
    try std.testing.expectError(error.IncompatiblePluginDependency, plugin_manifest.validateGraph(allocator, &.{ base.value, mismatched.value }, context));

    const cycle_a_json =
        \\{"schema_version":1,"identity":"acme.cycle_a","version":"1.0.0","entry_point":"acme.cycle_a","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[{"identity":"acme.cycle_b","minimum":"1.0.0","maximum_exclusive":"2.0.0"}],"services":[],"tools":[],"permissions":[]}
    ;
    const cycle_b_json =
        \\{"schema_version":1,"identity":"acme.cycle_b","version":"1.0.0","entry_point":"acme.cycle_b","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[{"identity":"acme.cycle_a","minimum":"1.0.0","maximum_exclusive":"2.0.0"}],"services":[],"tools":[],"permissions":[]}
    ;
    var cycle_a = try plugin_manifest.parseOwned(allocator, cycle_a_json);
    defer cycle_a.deinit();
    var cycle_b = try plugin_manifest.parseOwned(allocator, cycle_b_json);
    defer cycle_b.deinit();
    try std.testing.expectError(error.PluginDependencyCycle, plugin_manifest.validateGraph(allocator, &.{ cycle_a.value, cycle_b.value }, context));

    const privileged_json =
        \\{"schema_version":1,"identity":"acme.privileged","version":"1.0.0","entry_point":"acme.privileged","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[],"services":[],"tools":[{"name":"net","permission":"network"}],"permissions":["network"]}
    ;
    var privileged = try plugin_manifest.parseOwned(allocator, privileged_json);
    defer privileged.deinit();
    try std.testing.expectError(error.PluginOverprivileged, plugin_manifest.validateGraph(allocator, &.{privileged.value}, context));

    const shadow_json =
        \\{"schema_version":1,"identity":"acme.shadow","version":"1.0.0","entry_point":"acme.shadow","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[],"services":[],"tools":[{"name":"shell","permission":"workspace_read"}],"permissions":["workspace_read"]}
    ;
    var shadow = try plugin_manifest.parseOwned(allocator, shadow_json);
    defer shadow.deinit();
    try std.testing.expectError(error.ReservedCoreToolShadowed, plugin_manifest.validateGraph(allocator, &.{shadow.value}, context));

    const unknown =
        \\{"schema_version":1,"identity":"acme.unknown","version":"1.0.0","entry_point":"acme.unknown","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[],"services":[],"tools":[],"permissions":[],"executable":"/tmp/plugin"}
    ;
    try std.testing.expectError(error.UnknownManifestField, plugin_manifest.parseOwned(allocator, unknown));

    const too_large = try allocator.alloc(u8, plugin_manifest.max_manifest_bytes + 1);
    defer allocator.free(too_large);
    @memset(too_large, ' ');
    try std.testing.expectError(error.ManifestTooLarge, plugin_manifest.parseOwned(allocator, too_large));
}

const EffectLedger = struct {
    applied: [16]bool = @splat(false),
    apply_calls: usize = 0,
    live_effects: usize = 0,
    removals: usize = 0,
    cordis_starts: usize = 0,
    cordis_cleanups: usize = 0,
    fail_generation: u64 = 0,
    fail_apply_generation: u64 = 0,
    stage_calls: usize = 0,
    stage_identities: [16][]const u8 = @splat(""),
    reentry_registry: ?*plugin_registry.Registry = null,
    stage_observed_active: u64 = 0,
    effect_observed_active: u64 = 0,
    activation_observed_active: u64 = 0,
};

const FixturePlanState = struct {
    ledger: *EffectLedger,
    generation: u64,
    effects: [1]plugin_registry.NativeEffect,
};

fn fixtureStage(raw: *anyopaque, allocator: std.mem.Allocator, manifest: *const registry_manifest.Manifest, generation: u64) !plugin_registry.StagedNativePlugin {
    const ledger: *EffectLedger = @ptrCast(@alignCast(raw));
    if (ledger.reentry_registry) |registry| ledger.stage_observed_active = if (registry.activeSnapshot()) |snapshot| snapshot.generation else 0;
    ledger.stage_identities[ledger.stage_calls] = manifest.identity;
    ledger.stage_calls += 1;
    const state = try allocator.create(FixturePlanState);
    state.* = .{
        .ledger = ledger,
        .generation = generation,
        .effects = .{.{ .id = "install", .apply = fixtureApply, .remove = fixtureRemove }},
    };
    return .{
        .state = state,
        .cordis_plugin = .{ .name = manifest.identity, .revision = generation, .state = state, .start = fixtureCordisStart },
        .effects = &state.effects,
        .deinit = fixturePlanDeinit,
    };
}

fn fixtureApply(raw: *anyopaque, key: plugin_registry.EffectKey) !void {
    const state: *FixturePlanState = @ptrCast(@alignCast(raw));
    if (state.ledger.reentry_registry) |registry| state.ledger.effect_observed_active = if (registry.activeSnapshot()) |snapshot| snapshot.generation else 0;
    state.ledger.apply_calls += 1;
    const index: usize = @intCast(key.generation);
    if (!state.ledger.applied[index]) {
        state.ledger.applied[index] = true;
        state.ledger.live_effects += 1;
    }
    if (state.ledger.fail_apply_generation == key.generation) return error.FixtureApplyFailed;
}

fn fixtureRemove(raw: *anyopaque, key: plugin_registry.EffectKey) void {
    const state: *FixturePlanState = @ptrCast(@alignCast(raw));
    const index: usize = @intCast(key.generation);
    if (state.ledger.applied[index]) {
        state.ledger.applied[index] = false;
        state.ledger.live_effects -= 1;
        state.ledger.removals += 1;
    }
}

fn fixtureCordisStart(_: *cordis.Context, fiber: *cordis.Fiber, raw: *anyopaque) !void {
    const state: *FixturePlanState = @ptrCast(@alignCast(raw));
    if (state.ledger.reentry_registry) |registry| state.ledger.activation_observed_active = if (registry.activeSnapshot()) |snapshot| snapshot.generation else 0;
    state.ledger.cordis_starts += 1;
    try fiber.effect(state, fixtureCordisCleanup);
    if (state.ledger.fail_generation == state.generation) return error.FixtureActivationFailed;
}

fn fixtureCordisCleanup(raw: *anyopaque) void {
    const state: *FixturePlanState = @ptrCast(@alignCast(raw));
    state.ledger.cordis_cleanups += 1;
}

fn fixturePlanDeinit(allocator: std.mem.Allocator, raw: *anyopaque) void {
    const state: *FixturePlanState = @ptrCast(@alignCast(raw));
    allocator.destroy(state);
}

fn appendPrepared(registry: *plugin_registry.Registry, store: *event_store.Store, prepared: *plugin_registry.PreparedEvent, event_id: []const u8) !void {
    var projection = registry.durableAppend(prepared);
    var record = try store.appendPreparedAndProject(
        prepared.event(event_id),
        "plugin-test",
        prepared.settlement(),
        @ptrCast(&projection),
        plugin_registry.DurableAppend.prepare,
        plugin_registry.DurableAppend.validate,
        plugin_registry.DurableAppend.commit,
    );
    defer record.deinit(std.testing.allocator);
}

const AppendBarrier = struct {
    store: *event_store.Store,
    prepared: *plugin_registry.PreparedEvent,
    projection: *plugin_registry.DurableAppend,
    validated: std.atomic.Value(bool) = .init(false),
    release: std.atomic.Value(bool) = .init(false),
    failure: ?anyerror = null,

    fn prepare(context: *anyopaque, event: event_store.Event, settlement: event_store.Settlement) !void {
        const barrier: *AppendBarrier = @ptrCast(@alignCast(context));
        return plugin_registry.DurableAppend.prepare(@ptrCast(barrier.projection), event, settlement);
    }

    fn validate(context: *anyopaque) bool {
        const barrier: *AppendBarrier = @ptrCast(@alignCast(context));
        const current = plugin_registry.DurableAppend.validate(@ptrCast(barrier.projection));
        if (!current) return false;
        barrier.validated.store(true, .release);
        while (!barrier.release.load(.acquire)) std.atomic.spinLoopHint();
        return true;
    }

    fn commit(context: *anyopaque, record: event_store.RecordView) void {
        const barrier: *AppendBarrier = @ptrCast(@alignCast(context));
        plugin_registry.DurableAppend.commit(@ptrCast(barrier.projection), record);
    }
};

fn appendAtBarrier(barrier: *AppendBarrier) void {
    var record = barrier.store.appendPreparedAndProject(
        barrier.prepared.event("barrier-effect"),
        "plugin-test",
        barrier.prepared.settlement(),
        @ptrCast(barrier),
        AppendBarrier.prepare,
        AppendBarrier.validate,
        AppendBarrier.commit,
    ) catch |err| {
        barrier.failure = err;
        return;
    };
    record.deinit(std.testing.allocator);
}

test "plugin lifecycle enable disable and old generation lease survival" {
    const allocator = std.testing.allocator;
    var ledger = EffectLedger{};
    const factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{ "shell", "read_file" },
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var registry = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer registry.deinit();
    var owned = try registry_manifest.parseOwned(allocator, base_manifest);
    defer owned.deinit();

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "plugin-events.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();

    var desired = try registry.prepareDesired(.enable, 0, &.{owned.value});
    defer desired.deinit();
    try appendPrepared(&registry, &opened.store, &desired, "plugin-desired-1");
    try std.testing.expectEqual(@as(u64, 1), registry.stagedSnapshot().?.generation);
    try std.testing.expect(registry.activeSnapshot() == null);

    const effect = registry.nextEffect(1).?;
    try registry.executeEffect(effect);
    const apply_calls_before_retry = ledger.apply_calls;
    try registry.executeEffect(effect);
    try std.testing.expectEqual(apply_calls_before_retry, ledger.apply_calls);
    var effect_applied = try registry.prepareEffectApplied(effect);
    defer effect_applied.deinit();
    try appendPrepared(&registry, &opened.store, &effect_applied, "plugin-effect-1");
    try std.testing.expectError(error.PluginEffectAlreadyRecorded, registry.prepareEffectApplied(effect));
    try registry.activateStaged(1);
    var active = try registry.prepareActive(1);
    defer active.deinit();
    try appendPrepared(&registry, &opened.store, &active, "plugin-active-1");
    try std.testing.expectEqual(@as(usize, 1), registry.activeSnapshot().?.plugin_count);
    var lease = try registry.acquireLease();

    const changed_existing_json =
        \\{"schema_version":1,"identity":"acme.echo","version":"1.2.3-alpha.1+build.7","entry_point":"acme.echo","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[],"services":["acme.echo.changed"],"tools":[{"name":"echo","permission":"workspace_read"}],"permissions":["workspace_read"]}
    ;
    const added_json =
        \\{"schema_version":1,"identity":"acme.added","version":"1.0.0","entry_point":"acme.echo","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[],"services":[],"tools":[],"permissions":[]}
    ;
    var changed_existing = try registry_manifest.parseOwned(allocator, changed_existing_json);
    defer changed_existing.deinit();
    var added = try registry_manifest.parseOwned(allocator, added_json);
    defer added.deinit();
    const stages_before_rejection = ledger.stage_calls;
    try std.testing.expectError(error.InvalidEnableGraph, registry.prepareDesired(.enable, 0, &.{ changed_existing.value, added.value }));
    try std.testing.expectEqual(stages_before_rejection, ledger.stage_calls);

    var disable = try registry.prepareDesired(.disable, 0, &.{});
    defer disable.deinit();
    try appendPrepared(&registry, &opened.store, &disable, "plugin-desired-2");
    try registry.activateStaged(2);
    var disabled = try registry.prepareActive(2);
    defer disabled.deinit();
    try appendPrepared(&registry, &opened.store, &disabled, "plugin-active-2");
    try std.testing.expectEqual(@as(usize, 0), registry.activeSnapshot().?.plugin_count);
    try std.testing.expectEqual(@as(u64, 1), lease.snapshot().generation);
    try std.testing.expectEqual(@as(usize, 0), ledger.removals);
    lease.deinit();
    registry.collectRetired();
    try std.testing.expectEqual(@as(usize, 1), ledger.removals);
    try std.testing.expectEqual(@as(usize, 1), ledger.cordis_cleanups);
}

test "plugin lifecycle upgrade reload rollback and failed activation preserve prior generation" {
    const allocator = std.testing.allocator;
    var ledger = EffectLedger{};
    const factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{ "shell", "read_file" },
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var registry = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer registry.deinit();
    var version_one = try registry_manifest.parseOwned(allocator, base_manifest);
    defer version_one.deinit();
    const version_two_json =
        \\{"schema_version":1,"identity":"acme.echo","version":"1.3.0","entry_point":"acme.echo","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[],"services":["acme.echo.service"],"tools":[{"name":"echo","permission":"workspace_read"}],"permissions":["workspace_read"]}
    ;
    const version_three_json =
        \\{"schema_version":1,"identity":"acme.echo","version":"1.4.0","entry_point":"acme.echo","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[],"services":["acme.echo.service"],"tools":[{"name":"echo","permission":"workspace_read"}],"permissions":["workspace_read"]}
    ;
    var version_two = try registry_manifest.parseOwned(allocator, version_two_json);
    defer version_two.deinit();
    var version_three = try registry_manifest.parseOwned(allocator, version_three_json);
    defer version_three.deinit();

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "plugin-transitions.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();

    var desired_one = try registry.prepareDesired(.enable, 0, &.{version_one.value});
    defer desired_one.deinit();
    try appendPrepared(&registry, &opened.store, &desired_one, "desired-1");
    const effect_one = registry.nextEffect(1).?;
    try registry.executeEffect(effect_one);
    var effect_one_event = try registry.prepareEffectApplied(effect_one);
    defer effect_one_event.deinit();
    try appendPrepared(&registry, &opened.store, &effect_one_event, "effect-1");
    try registry.activateStaged(1);
    var active_one = try registry.prepareActive(1);
    defer active_one.deinit();
    try appendPrepared(&registry, &opened.store, &active_one, "active-1");

    var desired_two = try registry.prepareDesired(.upgrade, 0, &.{version_two.value});
    defer desired_two.deinit();
    try appendPrepared(&registry, &opened.store, &desired_two, "desired-2");
    const effect_two = registry.nextEffect(2).?;
    try registry.executeEffect(effect_two);
    var effect_two_event = try registry.prepareEffectApplied(effect_two);
    defer effect_two_event.deinit();
    try appendPrepared(&registry, &opened.store, &effect_two_event, "effect-2");
    try registry.activateStaged(2);
    var active_two = try registry.prepareActive(2);
    defer active_two.deinit();
    try appendPrepared(&registry, &opened.store, &active_two, "active-2");
    registry.collectRetired();
    try std.testing.expectEqual(@as(u64, 2), registry.activeSnapshot().?.generation);
    try std.testing.expectEqual(@as(u64, 1), registry.activeSnapshot().?.previous_generation);

    if (registry.prepareDesired(.upgrade, 0, &.{version_one.value})) |unexpected_value| {
        var unexpected = unexpected_value;
        unexpected.deinit();
        return error.SemanticDowngradeAcceptedAsUpgrade;
    } else |err| {
        try std.testing.expectEqual(error.InvalidUpgradeGraph, err);
    }

    var reload = try registry.prepareDesired(.reload, 0, &.{version_two.value});
    defer reload.deinit();
    try appendPrepared(&registry, &opened.store, &reload, "desired-3");
    const reload_effect = registry.nextEffect(3).?;
    try registry.executeEffect(reload_effect);
    var reload_effect_event = try registry.prepareEffectApplied(reload_effect);
    defer reload_effect_event.deinit();
    try appendPrepared(&registry, &opened.store, &reload_effect_event, "effect-3");
    try registry.activateStaged(3);
    var active_reload = try registry.prepareActive(3);
    defer active_reload.deinit();
    try appendPrepared(&registry, &opened.store, &active_reload, "active-3");
    registry.collectRetired();

    var rollback = try registry.prepareDesired(.rollback, 1, &.{version_one.value});
    defer rollback.deinit();
    try appendPrepared(&registry, &opened.store, &rollback, "desired-4");
    const rollback_effect = registry.nextEffect(4).?;
    try registry.executeEffect(rollback_effect);
    var rollback_effect_event = try registry.prepareEffectApplied(rollback_effect);
    defer rollback_effect_event.deinit();
    try appendPrepared(&registry, &opened.store, &rollback_effect_event, "effect-4");
    try registry.activateStaged(4);
    var active_rollback = try registry.prepareActive(4);
    defer active_rollback.deinit();
    try appendPrepared(&registry, &opened.store, &active_rollback, "active-4");
    registry.collectRetired();
    try std.testing.expectEqual(plugin_registry.LifecycleAction.rollback, registry.activeSnapshot().?.action);

    ledger.fail_generation = 5;
    var failing = try registry.prepareDesired(.upgrade, 0, &.{version_three.value});
    defer failing.deinit();
    try appendPrepared(&registry, &opened.store, &failing, "desired-5");
    const failing_effect = registry.nextEffect(5).?;
    try registry.executeEffect(failing_effect);
    var failing_effect_event = try registry.prepareEffectApplied(failing_effect);
    defer failing_effect_event.deinit();
    try appendPrepared(&registry, &opened.store, &failing_effect_event, "effect-5");
    try std.testing.expectError(error.FixtureActivationFailed, registry.activateStaged(5));
    var failed = try registry.prepareFailed(5, "activation_failed");
    defer failed.deinit();
    try appendPrepared(&registry, &opened.store, &failed, "failed-5");
    registry.rollbackStaged(5);
    var rolled_back = try registry.prepareRolledBack(5);
    defer rolled_back.deinit();
    try appendPrepared(&registry, &opened.store, &rolled_back, "rolled-back-5");
    registry.collectRetired();
    try std.testing.expectEqual(@as(u64, 4), registry.activeSnapshot().?.generation);
    try std.testing.expectEqual(@as(usize, 1), ledger.live_effects);
    var active_lease = try registry.acquireLease();
    defer active_lease.deinit();
    try std.testing.expectEqual(@as(usize, 1), active_lease.manifests().len);
    try std.testing.expectEqualStrings("acme.echo", active_lease.manifests()[0].identity);
    try std.testing.expectEqual(@as(u32, 1), active_lease.manifests()[0].version.major);
    var history = try registry.listHistory(allocator);
    defer allocator.free(history);
    try std.testing.expectEqual(@as(usize, 5), history.len);
    try std.testing.expectEqual(plugin_registry.EventPhase.rolled_back, history[4].phase);
    try std.testing.expectEqual(@as(u64, 4), history[4].previous_generation);
    try std.testing.expectEqualStrings("activation_failed", history[4].failureCode());
}

const ReplayBoundary = enum { desired, effect, activation };

fn witnessReplayBoundary(allocator: std.mem.Allocator, directory: *std.testing.TmpDir, boundary: ReplayBoundary) !void {
    var ledger = EffectLedger{};
    const factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{ "shell", "read_file" },
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var owned = try registry_manifest.parseOwned(allocator, base_manifest);
    defer owned.deinit();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try directory.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const filename = switch (boundary) {
        .desired => "desired-boundary.bin",
        .effect => "effect-boundary.bin",
        .activation => "activation-boundary.bin",
    };
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], filename });
    defer allocator.free(path);

    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    var source = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    var desired = try source.prepareDesired(.enable, 0, &.{owned.value});
    defer desired.deinit();
    try appendPrepared(&source, &opened.store, &desired, "desired-1");
    if (boundary != .desired) {
        const effect = source.nextEffect(1).?;
        try source.executeEffect(effect);
        var effect_event = try source.prepareEffectApplied(effect);
        defer effect_event.deinit();
        try appendPrepared(&source, &opened.store, &effect_event, "effect-1");
    }
    if (boundary == .activation) {
        try source.activateStaged(1);
        var active = try source.prepareActive(1);
        defer active.deinit();
        try appendPrepared(&source, &opened.store, &active, "active-1");
    }
    source.deinit();
    opened.close();

    var reopened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer reopened.close();
    var replayed = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer replayed.deinit();
    const report = try replayed.replay(&reopened.store);
    try std.testing.expectEqual(@as(u64, switch (boundary) {
        .desired => 1,
        .effect => 2,
        .activation => 3,
    }), report.record_count);
    switch (boundary) {
        .desired => {
            try std.testing.expectEqual(@as(u64, 1), replayed.stagedSnapshot().?.generation);
            const effect = replayed.nextEffect(1).?;
            try std.testing.expect(replayed.effectReceiptRequired(effect));
        },
        .effect => {
            const effect = replayed.nextEffect(1).?;
            try std.testing.expect(!replayed.effectReceiptRequired(effect));
            try replayed.executeEffect(effect);
            try replayed.activateStaged(1);
            try std.testing.expectEqual(@as(usize, 2), ledger.apply_calls);
        },
        .activation => {
            try std.testing.expectEqual(@as(u64, 1), replayed.activeSnapshot().?.generation);
            try std.testing.expectEqual(@as(usize, 2), ledger.apply_calls);
            try std.testing.expectEqual(@as(usize, 1), ledger.live_effects);
        },
    }
}

test "plugin lifecycle replays desired effect and activation crash boundaries" {
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    try witnessReplayBoundary(std.testing.allocator, &tmp, .desired);
    try witnessReplayBoundary(std.testing.allocator, &tmp, .effect);
    try witnessReplayBoundary(std.testing.allocator, &tmp, .activation);
}

test "complete graph and factory validation happen before native staging" {
    const allocator = std.testing.allocator;
    var ledger = EffectLedger{};
    const factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{"shell"},
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var registry = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer registry.deinit();

    const missing_json =
        \\{"schema_version":1,"identity":"acme.missing","version":"1.0.0","entry_point":"acme.echo","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[{"identity":"acme.absent","minimum":"1.0.0","maximum_exclusive":"2.0.0"}],"services":[],"tools":[],"permissions":[]}
    ;
    var missing = try registry_manifest.parseOwned(allocator, missing_json);
    defer missing.deinit();
    try std.testing.expectError(error.MissingPluginDependency, registry.prepareDesired(.enable, 0, &.{missing.value}));
    try std.testing.expectEqual(@as(usize, 0), ledger.stage_calls);

    const unknown_factory_json =
        \\{"schema_version":1,"identity":"acme.unknown","version":"1.0.0","entry_point":"acme.unknown","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[],"services":[],"tools":[],"permissions":[]}
    ;
    var unknown_factory = try registry_manifest.parseOwned(allocator, unknown_factory_json);
    defer unknown_factory.deinit();
    try std.testing.expectError(error.UnknownNativeFactory, registry.prepareDesired(.enable, 0, &.{unknown_factory.value}));
    try std.testing.expectEqual(@as(usize, 0), ledger.stage_calls);
    try std.testing.expect(registry.activeSnapshot() == null);
    try std.testing.expect(registry.stagedSnapshot() == null);
}

test "plugin durable replay rejects unknown fields and oversized payloads" {
    const allocator = std.testing.allocator;
    var ledger = EffectLedger{};
    const factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{"shell"},
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var owned = try registry_manifest.parseOwned(allocator, base_manifest);
    defer owned.deinit();

    var source = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    var desired = try source.prepareDesired(.enable, 0, &.{owned.value});
    defer desired.deinit();
    const suffix = ",\"unknown\":true}";
    const unknown_payload = try allocator.alloc(u8, desired.payload().len - 1 + suffix.len);
    defer allocator.free(unknown_payload);
    @memcpy(unknown_payload[0 .. desired.payload().len - 1], desired.payload()[0 .. desired.payload().len - 1]);
    @memcpy(unknown_payload[desired.payload().len - 1 ..], suffix);
    source.deinit();

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const unknown_path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "unknown-plugin-field.bin" });
    defer allocator.free(unknown_path);
    var unknown_store = try event_store.Store.open(allocator, std.testing.io, unknown_path, .{});
    defer unknown_store.close();
    var unknown_record = try unknown_store.store.append(.{ .kind = .plugin_state_changed, .event_id = "unknown", .payload = unknown_payload }, "plugin-test", .succeeded);
    defer unknown_record.deinit(allocator);
    var replay_unknown = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer replay_unknown.deinit();
    try std.testing.expectError(error.UnknownPluginEventField, replay_unknown.replay(&unknown_store.store));

    const oversized_path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "oversized-plugin-payload.bin" });
    defer allocator.free(oversized_path);
    var oversized_store = try event_store.Store.open(allocator, std.testing.io, oversized_path, .{});
    defer oversized_store.close();
    const oversized = try allocator.alloc(u8, plugin_registry.max_lifecycle_payload_bytes + 1);
    defer allocator.free(oversized);
    @memset(oversized, 'x');
    var oversized_record = try oversized_store.store.append(.{ .kind = .plugin_state_changed, .event_id = "oversized", .payload = oversized }, "plugin-test", .succeeded);
    defer oversized_record.deinit(allocator);
    var replay_oversized = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer replay_oversized.deinit();
    try std.testing.expectError(error.InvalidPluginPersistence, replay_oversized.replay(&oversized_store.store));
}

test "plugin effect failure removes partial residue and failed phase is terminal" {
    const allocator = std.testing.allocator;
    var ledger = EffectLedger{ .fail_apply_generation = 1 };
    const factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{"shell"},
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var owned = try registry_manifest.parseOwned(allocator, base_manifest);
    defer owned.deinit();

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "failed-effect.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();

    var source = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    var desired = try source.prepareDesired(.enable, 0, &.{owned.value});
    defer desired.deinit();
    try appendPrepared(&source, &opened.store, &desired, "desired-1");
    const effect = source.nextEffect(1).?;
    try std.testing.expectError(error.FixtureApplyFailed, source.executeEffect(effect));
    try std.testing.expectEqual(@as(usize, 0), ledger.live_effects);
    try std.testing.expectEqual(@as(usize, 1), ledger.removals);

    try std.testing.expectError(error.InvalidPluginFailure, source.prepareFailed(1, "token=secret"));
    var failed = try source.prepareFailed(1, "effect_failed");
    defer failed.deinit();
    try appendPrepared(&source, &opened.store, &failed, "failed-1");
    try std.testing.expect(source.nextEffect(1) == null);
    try std.testing.expectError(error.PluginGenerationNotReconcilable, source.executeEffect(effect));
    try std.testing.expectError(error.PluginGenerationNotReconcilable, source.prepareEffectApplied(effect));
    try std.testing.expectError(error.PluginGenerationNotReconcilable, source.activateStaged(1));

    const digest = std.fmt.bytesToHex(source.stagedSnapshot().?.graph_digest, .lower);
    const backward_payload = try std.fmt.allocPrint(
        allocator,
        "{{\"schema_version\":1,\"phase\":\"effect_applied\",\"action\":\"enable\",\"generation\":1,\"previous_generation\":0,\"target_generation\":0,\"graph_sha256\":\"{s}\",\"effect_plugin\":\"acme.echo\",\"effect_id\":\"install\",\"failure\":\"\",\"manifests\":[]}}",
        .{digest},
    );
    defer allocator.free(backward_payload);
    var backward = try opened.store.append(.{ .kind = .plugin_state_changed, .event_id = "backward-effect", .payload = backward_payload }, "plugin-test", .succeeded);
    defer backward.deinit(allocator);
    source.rollbackStaged(1);
    source.deinit();

    var replayed = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer replayed.deinit();
    try std.testing.expectError(error.PluginGenerationNotReconcilable, replayed.replay(&opened.store));
}

test "plugin factory and effect order is graph-derived across replay" {
    const allocator = std.testing.allocator;
    var ledger = EffectLedger{};
    const factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{"shell"},
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var dependency = try registry_manifest.parseOwned(allocator, dependency_manifest);
    defer dependency.deinit();
    var consumer = try registry_manifest.parseOwned(allocator, consumer_manifest);
    defer consumer.deinit();

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "effect-order.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();

    var source = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    var desired = try source.prepareDesired(.enable, 0, &.{ consumer.value, dependency.value });
    defer desired.deinit();
    try appendPrepared(&source, &opened.store, &desired, "desired-1");
    try std.testing.expectEqualStrings("acme.zdep", ledger.stage_identities[0]);
    try std.testing.expectEqualStrings("acme.consumer", ledger.stage_identities[1]);
    try std.testing.expectEqualStrings("acme.zdep", source.nextEffect(1).?.plugin_identity);
    const consumer_effect = plugin_registry.EffectKey{ .generation = 1, .plugin_identity = "acme.consumer", .effect_id = "install" };
    try std.testing.expectError(error.PluginEffectOutOfOrder, source.executeEffect(consumer_effect));
    try std.testing.expectError(error.PluginEffectOutOfOrder, source.prepareEffectApplied(consumer_effect));
    source.deinit();

    ledger.stage_calls = 0;
    var replayed = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer replayed.deinit();
    _ = try replayed.replay(&opened.store);
    try std.testing.expectEqualStrings("acme.zdep", ledger.stage_identities[0]);
    try std.testing.expectEqualStrings("acme.consumer", ledger.stage_identities[1]);
    try std.testing.expectEqualStrings("acme.zdep", replayed.nextEffect(1).?.plugin_identity);
}

test "plugin replay rejects an effect receipt before its dependency" {
    const allocator = std.testing.allocator;
    var ledger = EffectLedger{};
    const factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{"shell"},
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var dependency = try registry_manifest.parseOwned(allocator, dependency_manifest);
    defer dependency.deinit();
    var consumer = try registry_manifest.parseOwned(allocator, consumer_manifest);
    defer consumer.deinit();

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "out-of-order-effect.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();

    var source = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    var desired = try source.prepareDesired(.enable, 0, &.{ consumer.value, dependency.value });
    defer desired.deinit();
    try appendPrepared(&source, &opened.store, &desired, "desired-1");
    const digest = std.fmt.bytesToHex(source.stagedSnapshot().?.graph_digest, .lower);
    const payload = try std.fmt.allocPrint(
        allocator,
        "{{\"schema_version\":1,\"phase\":\"effect_applied\",\"action\":\"enable\",\"generation\":1,\"previous_generation\":0,\"target_generation\":0,\"graph_sha256\":\"{s}\",\"effect_plugin\":\"acme.consumer\",\"effect_id\":\"install\",\"failure\":\"\",\"manifests\":[]}}",
        .{digest},
    );
    defer allocator.free(payload);
    var receipt = try opened.store.append(.{ .kind = .plugin_state_changed, .event_id = "consumer-first", .payload = payload }, "plugin-test", .succeeded);
    defer receipt.deinit(allocator);
    source.deinit();

    var replayed = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer replayed.deinit();
    try std.testing.expectError(error.PluginEffectOutOfOrder, replayed.replay(&opened.store));
}

test "durable validation reserves mutable plugin state until commit" {
    const allocator = std.testing.allocator;
    var ledger = EffectLedger{};
    const factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{"shell"},
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var dependency = try registry_manifest.parseOwned(allocator, dependency_manifest);
    defer dependency.deinit();
    var consumer = try registry_manifest.parseOwned(allocator, consumer_manifest);
    defer consumer.deinit();

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "append-reservation.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();

    var registry = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer registry.deinit();
    var desired = try registry.prepareDesired(.enable, 0, &.{ consumer.value, dependency.value });
    defer desired.deinit();
    try appendPrepared(&registry, &opened.store, &desired, "desired-1");
    const first = registry.nextEffect(1).?;
    try registry.executeEffect(first);
    var first_receipt = try registry.prepareEffectApplied(first);
    defer first_receipt.deinit();
    var projection = registry.durableAppend(&first_receipt);
    var barrier = AppendBarrier{ .store = &opened.store, .prepared = &first_receipt, .projection = &projection };
    var worker = try std.Thread.spawn(.{}, appendAtBarrier, .{&barrier});
    var joined = false;
    defer if (!joined) {
        barrier.release.store(true, .release);
        worker.join();
    };
    while (!barrier.validated.load(.acquire)) std.atomic.spinLoopHint();

    const second = plugin_registry.EffectKey{ .generation = 1, .plugin_identity = "acme.consumer", .effect_id = "install" };
    try std.testing.expectError(error.PluginAppendInProgress, registry.executeEffect(second));
    barrier.release.store(true, .release);
    worker.join();
    joined = true;
    if (barrier.failure) |err| return err;
    try std.testing.expectEqual(plugin_registry.EventPhase.effect_applied, registry.stagedSnapshot().?.phase);
    try std.testing.expectEqualStrings("acme.consumer", registry.nextEffect(1).?.plugin_identity);
}

test "multi-generation replay removes superseded native effects and contexts" {
    const allocator = std.testing.allocator;
    var ledger = EffectLedger{};
    const factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{"shell"},
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var version_one = try registry_manifest.parseOwned(allocator, base_manifest);
    defer version_one.deinit();
    const version_two_manifest =
        \\{"schema_version":1,"identity":"acme.echo","version":"1.3.0","entry_point":"acme.echo","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[],"services":["acme.echo.service"],"tools":[{"name":"echo","permission":"workspace_read"}],"permissions":["workspace_read"]}
    ;
    var version_two = try registry_manifest.parseOwned(allocator, version_two_manifest);
    defer version_two.deinit();

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "multi-generation-replay.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});

    var source = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    var desired_one = try source.prepareDesired(.enable, 0, &.{version_one.value});
    defer desired_one.deinit();
    try appendPrepared(&source, &opened.store, &desired_one, "desired-1");
    const effect_one = source.nextEffect(1).?;
    try source.executeEffect(effect_one);
    var receipt_one = try source.prepareEffectApplied(effect_one);
    defer receipt_one.deinit();
    try appendPrepared(&source, &opened.store, &receipt_one, "effect-1");
    try source.activateStaged(1);
    var active_one = try source.prepareActive(1);
    defer active_one.deinit();
    try appendPrepared(&source, &opened.store, &active_one, "active-1");

    var desired_two = try source.prepareDesired(.upgrade, 0, &.{version_two.value});
    defer desired_two.deinit();
    try appendPrepared(&source, &opened.store, &desired_two, "desired-2");
    const effect_two = source.nextEffect(2).?;
    try source.executeEffect(effect_two);
    var receipt_two = try source.prepareEffectApplied(effect_two);
    defer receipt_two.deinit();
    try appendPrepared(&source, &opened.store, &receipt_two, "effect-2");
    try source.activateStaged(2);
    var active_two = try source.prepareActive(2);
    defer active_two.deinit();
    try appendPrepared(&source, &opened.store, &active_two, "active-2");
    source.deinit();
    opened.close();

    ledger.apply_calls = 0;
    ledger.live_effects = 0;
    ledger.removals = 0;
    ledger.cordis_starts = 0;
    ledger.cordis_cleanups = 0;
    ledger.applied = @splat(false);
    var reopened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer reopened.close();
    var replayed = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer replayed.deinit();
    const report = try replayed.replay(&reopened.store);
    try std.testing.expectEqual(@as(u64, 6), report.record_count);
    try std.testing.expectEqual(@as(u64, 2), replayed.activeSnapshot().?.generation);
    try std.testing.expectEqual(@as(usize, 1), ledger.live_effects);
    try std.testing.expectEqual(@as(usize, 2), ledger.cordis_starts);
    try std.testing.expectEqual(@as(usize, 1), ledger.removals);
    try std.testing.expectEqual(@as(usize, 1), ledger.cordis_cleanups);
    const history = try replayed.listHistory(allocator);
    defer allocator.free(history);
    try std.testing.expectEqual(@as(usize, 2), history.len);
    try std.testing.expect(history[0].ever_active);
    try std.testing.expect(history[1].ever_active);
}

test "plugin replay rejects trusted native implementation drift" {
    const allocator = std.testing.allocator;
    var ledger = EffectLedger{};
    const original_factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const changed_factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v2", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{"shell"},
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var owned = try registry_manifest.parseOwned(allocator, base_manifest);
    defer owned.deinit();

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "factory-drift.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();

    var source = plugin_registry.Registry.init(allocator, &root_context, &original_factories, validation);
    var desired = try source.prepareDesired(.enable, 0, &.{owned.value});
    defer desired.deinit();
    try appendPrepared(&source, &opened.store, &desired, "desired-1");
    source.deinit();

    var replayed = plugin_registry.Registry.init(allocator, &root_context, &changed_factories, validation);
    defer replayed.deinit();
    try std.testing.expectError(error.InvalidPluginPersistence, replayed.replay(&opened.store));
}

test "trusted callbacks can inspect the prior published generation" {
    const allocator = std.testing.allocator;
    var ledger = EffectLedger{};
    const factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{"shell"},
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var version_one = try registry_manifest.parseOwned(allocator, base_manifest);
    defer version_one.deinit();
    const version_two_json =
        \\{"schema_version":1,"identity":"acme.echo","version":"1.3.0","entry_point":"acme.echo","compatibility":{"minimum":"1.0.0","maximum_exclusive":"2.0.0"},"dependencies":[],"services":["acme.echo.service"],"tools":[{"name":"echo","permission":"workspace_read"}],"permissions":["workspace_read"]}
    ;
    var version_two = try registry_manifest.parseOwned(allocator, version_two_json);
    defer version_two.deinit();

    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var path_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const path_len = try tmp.dir.realPathFile(std.testing.io, ".", &path_buffer);
    const path = try std.fs.path.join(allocator, &.{ path_buffer[0..path_len], "callback-reentry.bin" });
    defer allocator.free(path);
    var opened = try event_store.Store.open(allocator, std.testing.io, path, .{});
    defer opened.close();

    var registry = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer registry.deinit();
    var desired_one = try registry.prepareDesired(.enable, 0, &.{version_one.value});
    defer desired_one.deinit();
    try appendPrepared(&registry, &opened.store, &desired_one, "desired-1");
    const effect_one = registry.nextEffect(1).?;
    try registry.executeEffect(effect_one);
    var receipt_one = try registry.prepareEffectApplied(effect_one);
    defer receipt_one.deinit();
    try appendPrepared(&registry, &opened.store, &receipt_one, "effect-1");
    try registry.activateStaged(1);
    var active_one = try registry.prepareActive(1);
    defer active_one.deinit();
    try appendPrepared(&registry, &opened.store, &active_one, "active-1");

    ledger.reentry_registry = &registry;
    var desired_two = try registry.prepareDesired(.upgrade, 0, &.{version_two.value});
    defer desired_two.deinit();
    try std.testing.expectEqual(@as(u64, 1), ledger.stage_observed_active);
    try appendPrepared(&registry, &opened.store, &desired_two, "desired-2");
    const effect_two = registry.nextEffect(2).?;
    try registry.executeEffect(effect_two);
    try std.testing.expectEqual(@as(u64, 1), ledger.effect_observed_active);
    var receipt_two = try registry.prepareEffectApplied(effect_two);
    defer receipt_two.deinit();
    try appendPrepared(&registry, &opened.store, &receipt_two, "effect-2");
    try registry.activateStaged(2);
    try std.testing.expectEqual(@as(u64, 1), ledger.activation_observed_active);
}

fn pluginAllocationWitness(allocator: std.mem.Allocator) !void {
    var ledger = EffectLedger{};
    const factories = [_]plugin_registry.NativeFactory{.{ .entry_point = "acme.echo", .implementation_id = "fixture-v1", .context = &ledger, .stage = fixtureStage }};
    const validation = registry_manifest.ValidationContext{
        .host_version = try registry_manifest.Version.parse("1.5.0"),
        .permission_ceiling = registry_manifest.PermissionSet.all(),
        .reserved_core_tools = &.{"shell"},
    };
    var root_context = cordis.Context.init(allocator, null);
    defer root_context.deinit();
    var registry = plugin_registry.Registry.init(allocator, &root_context, &factories, validation);
    defer registry.deinit();
    var owned = try registry_manifest.parseOwned(allocator, base_manifest);
    defer owned.deinit();
    var graph = try registry_manifest.validateGraph(allocator, &.{owned.value}, validation);
    defer graph.deinit(allocator);
    var desired = try registry.prepareDesired(.enable, 0, &.{owned.value});
    defer desired.deinit();
    try std.testing.expect(desired.payload().len != 0);
}

test "plugin parsing staging and teardown survive every allocation failure" {
    try std.testing.checkAllAllocationFailures(std.testing.allocator, pluginAllocationWitness, .{});
}
