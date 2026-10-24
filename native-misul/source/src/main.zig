const std = @import("std");
const misul_agent = @import("misul_agent");

const default_system_prompt =
    "You are Misul Agent, a precise coding assistant. Inspect evidence before changing files, keep changes focused, and verify completed work.";
const default_model = "gpt-4.1-mini";
const bundled_model_profiles = [_]misul_agent.model_catalog.ModelProfile{.{
    .id = default_model,
    .name = "GPT-4.1 mini",
    .provider = "openai-compatible",
    .context_window = 1_047_576,
    .output_limit = 4_096,
    .max_output_tokens = 32_768,
}};

pub fn main(init: std.process.Init) !void {
    var args = try std.process.Args.Iterator.initAllocator(init.minimal.args, init.gpa);
    defer args.deinit();
    const executable = args.next() orelse "misul";

    const command = args.next() orelse {
        interactiveCommand(init, executable) catch |err| return runtimeSetupError(init.io, err);
        return;
    };
    if (std.mem.eql(u8, command, "--version") or std.mem.eql(u8, command, "version")) {
        return write(init.io, .stdout, "Misul Agent " ++ misul_agent.version ++ "\n");
    }
    if (std.mem.eql(u8, command, "--help") or std.mem.eql(u8, command, "-h") or std.mem.eql(u8, command, "help")) {
        return writeUsage(init.io, .stdout);
    }
    if (std.mem.eql(u8, command, "rpc") or std.mem.eql(u8, command, "--rpc") or std.mem.eql(u8, command, "--mode")) {
        if (std.mem.eql(u8, command, "--mode")) {
            const mode = args.next() orelse return fail(init.io, "missing mode value", 2);
            if (!std.mem.eql(u8, mode, "rpc")) return fail(init.io, "unknown mode", 2);
        }
        rpcCommand(init, &args) catch |err| return runtimeSetupError(init.io, err);
        return;
    }
    if (std.mem.eql(u8, command, "prompt-lanes")) {
        const stable = args.next() orelse return fail(init.io, "missing stable prompt", 2);
        const session = args.next() orelse return fail(init.io, "missing session prompt", 2);
        const prompt = try misul_agent.protocol.joinSystemPromptLanes(init.gpa, stable, session);
        defer init.gpa.free(prompt);
        try write(init.io, .stdout, prompt);
        return write(init.io, .stdout, "\n");
    }
    if (std.mem.eql(u8, command, "run")) {
        runCommand(init, &args) catch |err| switch (err) {
            error.HelpRequested => return,
            error.MissingPrompt => return fail(init.io, "missing prompt", 2),
            error.MissingApiKey => return fail(init.io, "missing API key; set OPENAI_API_KEY or pass --api-key", 2),
            error.MissingOptionValue => return fail(init.io, "missing option value", 2),
            error.UnknownOption => return fail(init.io, "unknown option", 2),
            error.InvalidNumber => return fail(init.io, "invalid numeric option", 2),
            error.EmptyModelProfileRegistry => return fail(init.io, "missing model profiles; set MISUL_MODEL_PROFILES_JSON", 2),
            error.InvalidModelProfileJson, error.InvalidModelProfile, error.DuplicateModelProfile, error.TooManyModelProfiles => return fail(init.io, "invalid MISUL_MODEL_PROFILES_JSON", 2),
            error.ModelNotConfigured => return fail(init.io, "selected model is absent from MISUL_MODEL_PROFILES_JSON", 2),
            error.OutputLimitExceedsProfile => return fail(init.io, "--max-tokens exceeds the selected model profile", 2),
            error.ProviderFailureReported => std.process.exit(1),
            else => |other| return other,
        };
        return;
    }
    try writeUsage(init.io, .stderr);
    return fail(init.io, "unknown command", 2);
}

fn interactiveCommand(init: std.process.Init, executable: []const u8) !void {
    if (!try std.Io.File.stdin().isTty(init.io) or !try std.Io.File.stdout().isTty(init.io)) return writeUsage(init.io, .stdout);
    const cwd = try std.process.currentPathAlloc(init.io, init.gpa);
    defer init.gpa.free(cwd);
    const model = init.environ_map.get("MISUL_MODEL") orelse init.environ_map.get("OPENAI_MODEL") orelse default_model;
    const state_dir = try defaultStateRoot(init, cwd);
    defer init.gpa.free(state_dir);
    const session_id = try std.fmt.allocPrint(init.gpa, "tui-{x}", .{std.hash.Wyhash.hash(0, cwd)});
    defer init.gpa.free(session_id);
    const endpoint = init.environ_map.get("OPENAI_BASE_URL") orelse "https://api.openai.com/v1";
    var runtime = try createRuntime(init, cwd, state_dir, model, endpoint, init.environ_map.get("OPENAI_API_KEY"), default_system_prompt, null, 32, null, null, null, null, null);
    var app = misul_agent.tui.App{
        .allocator = init.gpa,
        .io = init.io,
        .executable = executable,
        .model = model,
        .version = misul_agent.version,
        .cwd = cwd,
        .session_id = session_id,
        .runtime = runtime,
    };
    defer app.deinit();
    defer runtime.destroy();
    try app.run();
}

const TelemetryState = struct {
    aggregator: misul_agent.cache_telemetry.SessionAggregator,
    io: std.Io,
    debug: bool,

    fn deinit(self: *TelemetryState) void {
        self.aggregator.deinit();
    }

    fn record(raw: *anyopaque, call: misul_agent.cache_telemetry.Call) void {
        const self: *TelemetryState = @ptrCast(@alignCast(raw));
        self.aggregator.record(call) catch return;
        if (!self.debug) return;
        const line = misul_agent.cache_telemetry.serializeLine(self.aggregator.allocator, call) catch return;
        defer self.aggregator.allocator.free(line);
        std.Io.File.stderr().writeStreamingAll(self.io, line) catch {};
    }
};

const RunOptions = struct {
    model: ?[]const u8 = null,
    base_url: ?[]const u8 = null,
    api_key: ?[]const u8 = null,
    system_prompt: []const u8 = default_system_prompt,
    max_tokens: ?u64 = null,
    max_steps: usize = 32,
    timeout_ms: ?u64 = null,
    max_retries: ?u32 = null,
    max_retry_delay_ms: ?u64 = null,
    session_id: ?[]const u8 = null,
};

fn runCommand(init: std.process.Init, args: *std.process.Args.Iterator) !void {
    var options = RunOptions{};
    var prompt: std.ArrayList(u8) = .empty;
    defer prompt.deinit(init.gpa);

    while (args.next()) |arg| {
        if (std.mem.eql(u8, arg, "--help") or std.mem.eql(u8, arg, "-h")) {
            try writeRunUsage(init.io);
            return error.HelpRequested;
        } else if (std.mem.eql(u8, arg, "--model")) {
            options.model = args.next() orelse return error.MissingOptionValue;
        } else if (std.mem.eql(u8, arg, "--base-url")) {
            options.base_url = args.next() orelse return error.MissingOptionValue;
        } else if (std.mem.eql(u8, arg, "--api-key")) {
            options.api_key = args.next() orelse return error.MissingOptionValue;
        } else if (std.mem.eql(u8, arg, "--system-prompt")) {
            options.system_prompt = args.next() orelse return error.MissingOptionValue;
        } else if (std.mem.eql(u8, arg, "--session")) {
            options.session_id = args.next() orelse return error.MissingOptionValue;
        } else if (std.mem.eql(u8, arg, "--max-tokens")) {
            options.max_tokens = std.fmt.parseInt(u64, args.next() orelse return error.MissingOptionValue, 10) catch return error.InvalidNumber;
            if (options.max_tokens.? == 0) return error.InvalidNumber;
        } else if (std.mem.eql(u8, arg, "--max-steps")) {
            options.max_steps = std.fmt.parseInt(usize, args.next() orelse return error.MissingOptionValue, 10) catch return error.InvalidNumber;
            if (options.max_steps == 0) return error.InvalidNumber;
        } else if (std.mem.eql(u8, arg, "--timeout-ms")) {
            options.timeout_ms = std.fmt.parseInt(u64, args.next() orelse return error.MissingOptionValue, 10) catch return error.InvalidNumber;
            if (options.timeout_ms.? == 0) return error.InvalidNumber;
        } else if (std.mem.eql(u8, arg, "--max-retries")) {
            options.max_retries = std.fmt.parseInt(u32, args.next() orelse return error.MissingOptionValue, 10) catch return error.InvalidNumber;
        } else if (std.mem.eql(u8, arg, "--max-retry-delay-ms")) {
            options.max_retry_delay_ms = std.fmt.parseInt(u64, args.next() orelse return error.MissingOptionValue, 10) catch return error.InvalidNumber;
            if (options.max_retry_delay_ms.? == 0) return error.InvalidNumber;
        } else if (std.mem.startsWith(u8, arg, "-")) {
            return error.UnknownOption;
        } else {
            if (prompt.items.len != 0) try prompt.append(init.gpa, ' ');
            try prompt.appendSlice(init.gpa, arg);
        }
    }
    if (prompt.items.len == 0) return error.MissingPrompt;

    const model_id = options.model orelse init.environ_map.get("MISUL_MODEL") orelse init.environ_map.get("OPENAI_MODEL") orelse default_model;
    const base_url = options.base_url orelse init.environ_map.get("OPENAI_BASE_URL") orelse "https://api.openai.com/v1";
    const credential = misul_agent.provider_auth.resolveOpenAi(options.api_key, init.environ_map) catch |err| switch (err) {
        error.MissingCredential => return error.MissingApiKey,
        else => |other| return other,
    };
    const api_key = credential.token;
    const cwd = try std.process.currentPathAlloc(init.io, init.gpa);
    defer init.gpa.free(cwd);
    const state_root = try defaultStateRoot(init, cwd);
    defer init.gpa.free(state_root);
    var telemetry_state = TelemetryState{
        .aggregator = misul_agent.cache_telemetry.SessionAggregator.init(init.gpa, 256),
        .io = init.io,
        .debug = std.mem.eql(u8, init.environ_map.get("MISUL_CACHE_DEBUG") orelse "", "1"),
    };
    defer telemetry_state.deinit();
    var runtime = try createRuntime(init, cwd, state_root, model_id, base_url, api_key, options.system_prompt, options.max_tokens, options.max_steps, options.timeout_ms, options.max_retries, options.max_retry_delay_ms, &telemetry_state, TelemetryState.record);
    defer runtime.destroy();
    var events = CliEvents{ .io = init.io };
    const run_id = try runtime.startPrompt(.{ .session_id = options.session_id orelse "cli", .prompt = prompt.items, .event_context = &events, .emit = cliEvent });
    const settlement = try runtime.wait(run_id);
    _ = events.wrote;
    if (settlement.stop_reason == .failed or settlement.stop_reason == .max_steps) {
        try reportProviderFailure(init.gpa, init.io, settlement.provider_failure);
        return error.ProviderFailureReported;
    }
}

const CliEvents = struct {
    io: std.Io,
    wrote: bool = false,
    provider_event_seen: bool = false,
};

fn cliEvent(raw: *anyopaque, event: misul_agent.runtime.Event) !void {
    const state: *CliEvents = @ptrCast(@alignCast(raw));
    switch (event.payload) {
        .provider_stream => |stream| switch (stream) {
            .text_delta => |delta| {
                state.provider_event_seen = true;
                try write(state.io, .stdout, delta.delta);
                state.wrote = true;
            },
            else => state.provider_event_seen = true,
        },
        .message_added => |message| if (state.provider_event_seen and !state.wrote and message.* == .assistant) for (message.assistant.content) |part| switch (part) {
            .text => |content| {
                try write(state.io, .stdout, content.text);
                state.wrote = true;
            },
            else => {},
        },
        .settled => if (state.wrote) try write(state.io, .stdout, "\n"),
        else => {},
    }
}

fn createRuntime(
    init: std.process.Init,
    workspace_root: []const u8,
    state_root: []const u8,
    model: []const u8,
    endpoint: []const u8,
    api_key: ?[]const u8,
    system_prompt: []const u8,
    max_tokens: ?u64,
    max_steps: usize,
    timeout_ms: ?u64,
    max_retries: ?u32,
    max_retry_delay_ms: ?u64,
    telemetry_context: ?*anyopaque,
    telemetry: ?misul_agent.agent_loop.TelemetrySink,
) !*misul_agent.runtime.Runtime {
    var profiles = try loadModelProfiles(init.gpa, init.environ_map);
    defer profiles.deinit();
    const profile = profiles.find(model) orelse return error.ModelNotConfigured;
    const output_limit = max_tokens orelse profile.output_limit;
    if (output_limit == 0 or output_limit > profile.max_output_tokens) return error.OutputLimitExceedsProfile;
    const reasoning_storage = [_]misul_agent.protocol.ThinkingLevel{profile.reasoning orelse .low};
    const capabilities = [_]misul_agent.openai_compatible.ModelCapability{.{
        .id = profile.id,
        .context_window = profile.context_window,
        .output_limit = profile.max_output_tokens,
        .reasoning = profile.reasoning != null,
        .reasoning_efforts = if (profile.reasoning != null) &reasoning_storage else &.{},
    }};
    const providers = [_]misul_agent.openai_compatible.ProviderConfig{.{
        .id = profile.provider,
        .endpoint = endpoint,
        .credential_ref = api_key orelse "OPENAI_API_KEY",
        .credential_source = if (api_key != null) .explicit else .environment,
        .models = &capabilities,
        .retry = .{
            .max_retries = max_retries orelse 2,
            .max_retry_delay_ms = max_retry_delay_ms orelse 30_000,
            .deadline_ms = timeout_ms orelse 120_000,
        },
    }};
    var parsed_provider_configs: ?misul_agent.provider_config.Parsed = if (init.environ_map.get("MISUL_PROVIDER_CONFIGS_JSON")) |json|
        try misul_agent.provider_config.parseJson(init.gpa, json)
    else
        null;
    defer if (parsed_provider_configs) |*parsed| parsed.deinit();
    const provider_configs = if (parsed_provider_configs) |*parsed| parsed.configs() else &providers;
    return misul_agent.runtime.Runtime.create(init.gpa, init.io, init.environ_map, .{
        .workspace_root = workspace_root,
        .state_root = state_root,
        .system_prompt = system_prompt,
        .default_selection = .{
            .provider = profile.provider,
            .model = profile.id,
            .context_window = profile.context_window,
            .output_limit = output_limit,
            .reasoning = profile.reasoning,
            .cache_retention = profile.cache_retention,
            .cache_aggressiveness = profile.cache_aggressiveness,
            .transport = profile.transport,
        },
        .model_profiles = profiles.profiles,
        .provider_configs = provider_configs,
        .max_steps = max_steps,
        .telemetry_context = telemetry_context,
        .telemetry = telemetry,
    });
}

fn rpcCommand(init: std.process.Init, args: *std.process.Args.Iterator) !void {
    var model_opt: ?[]const u8 = null;
    while (args.next()) |arg| {
        if (std.mem.eql(u8, arg, "--model")) {
            model_opt = args.next() orelse return error.MissingOptionValue;
        } else return error.UnknownOption;
    }
    const cwd = try std.process.currentPathAlloc(init.io, init.gpa);
    defer init.gpa.free(cwd);
    const state_root = try defaultStateRoot(init, cwd);
    defer init.gpa.free(state_root);
    const model = model_opt orelse init.environ_map.get("MISUL_MODEL") orelse init.environ_map.get("OPENAI_MODEL") orelse default_model;
    const endpoint = init.environ_map.get("OPENAI_BASE_URL") orelse "https://api.openai.com/v1";
    // Cancellation-safety policy: RPC never reissues a canceled transport.
    // Provider retry classification remains runtime-03 ownership.
    var runtime = try createRuntime(init, cwd, state_root, model, endpoint, init.environ_map.get("OPENAI_API_KEY"), default_system_prompt, null, 32, null, 0, null, null, null);
    defer runtime.destroy();
    var server = try misul_agent.rpc_server.Server.init(init.gpa, runtime);
    defer server.deinit();
    try server.runStdio(init.io);
}

fn addSkillRoot(catalog: *misul_agent.skills.Catalog, io: std.Io, path: []const u8) !void {
    catalog.addRoot(io, path) catch |err| switch (err) {
        error.FileNotFound, error.NotDir => {},
        else => |other| return other,
    };
}

fn defaultStateRoot(init: std.process.Init, workspace_root: []const u8) ![]u8 {
    const base = init.environ_map.get("HOME") orelse "/tmp";
    var digest: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash(workspace_root, &digest, .{});
    const workspace_id = std.fmt.bytesToHex(digest, .lower);
    const path = try std.fs.path.join(init.gpa, &.{ base, ".local", "state", "misul", "workspaces", &workspace_id });
    errdefer init.gpa.free(path);
    try std.Io.Dir.cwd().createDirPath(init.io, path);
    return path;
}

fn loadModelProfiles(allocator: std.mem.Allocator, environment: *const std.process.Environ.Map) !misul_agent.model_catalog.ProfileRegistry {
    if (environment.get("MISUL_MODEL_PROFILES_JSON")) |json| {
        return misul_agent.model_catalog.ProfileRegistry.parseJson(allocator, json);
    }
    return misul_agent.model_catalog.ProfileRegistry.init(allocator, &bundled_model_profiles);
}

const Output = enum { stdout, stderr };

fn writeUsage(io: std.Io, output: Output) !void {
    try write(io, output,
        \\Usage:
        \\  misul
        \\  misul --version
        \\  misul rpc [options]
        \\  misul run [options] <prompt>
        \\  misul prompt-lanes <stable> <session>
        \\
        \\Default model: gpt-4.1-mini
        \\
    );
}

fn writeRunUsage(io: std.Io) !void {
    try write(io, .stdout,
        \\Usage: misul run [options] <prompt>
        \\
        \\Options:
        \\  --model ID             Model ID (default: gpt-4.1-mini)
        \\  --base-url URL         OpenAI-compatible base URL (OPENAI_BASE_URL)
        \\  --api-key KEY          API key (OPENAI_API_KEY)
        \\  --system-prompt TEXT   Stable system prompt
        \\  --session ID           Persist and resume a durable session
        \\  --max-tokens N         Maximum output tokens (profile default)
        \\  --max-steps N          Maximum provider turns (default: 32)
        \\  --timeout-ms N         Per-request timeout in milliseconds
        \\  --max-retries N        Maximum transient retries (hard cap: 8)
        \\  --max-retry-delay-ms N Maximum retry delay in milliseconds
        \\  -h, --help             Show this help
        \\
    );
}

fn fail(io: std.Io, message: []const u8, status: u8) noreturn {
    write(io, .stderr, "error: ") catch {};
    write(io, .stderr, message) catch {};
    write(io, .stderr, "\n") catch {};
    std.process.exit(status);
}

fn reportProviderFailure(allocator: std.mem.Allocator, io: std.Io, failure: ?misul_agent.protocol.ProviderFailure) !void {
    try write(io, .stderr, "error: provider request failed");
    if (failure) |diagnostic| {
        const safe_message = try sanitizeDiagnosticText(allocator, diagnostic.redacted_message);
        defer allocator.free(safe_message);
        try write(io, .stderr, " [");
        try write(io, .stderr, @tagName(diagnostic.category));
        try write(io, .stderr, "]: ");
        try write(io, .stderr, safe_message);
    }
    try write(io, .stderr, "\n");
}

fn sanitizeDiagnosticText(allocator: std.mem.Allocator, source: []const u8) ![]u8 {
    var safe: std.ArrayList(u8) = .empty;
    errdefer safe.deinit(allocator);
    var index: usize = 0;
    while (index < source.len) {
        const byte = source[index];
        if (byte < 0x20 or byte == 0x7f) {
            try appendDiagnosticByte(allocator, &safe, byte);
            index += 1;
            continue;
        }
        if (byte < 0x80) {
            try safe.append(allocator, byte);
            index += 1;
            continue;
        }
        const length = std.unicode.utf8ByteSequenceLength(byte) catch 1;
        if (length > source.len - index) {
            try appendDiagnosticByte(allocator, &safe, byte);
            index += 1;
            continue;
        }
        const codepoint = std.unicode.utf8Decode(source[index..][0..length]) catch {
            try appendDiagnosticByte(allocator, &safe, byte);
            index += 1;
            continue;
        };
        if (codepoint >= 0x80 and codepoint <= 0x9f) {
            for (source[index..][0..length]) |part| try appendDiagnosticByte(allocator, &safe, part);
        } else {
            try safe.appendSlice(allocator, source[index..][0..length]);
        }
        index += length;
    }
    return safe.toOwnedSlice(allocator);
}

fn appendDiagnosticByte(allocator: std.mem.Allocator, output: *std.ArrayList(u8), byte: u8) !void {
    const hex = "0123456789abcdef";
    try output.appendSlice(allocator, "\\x");
    try output.append(allocator, hex[byte >> 4]);
    try output.append(allocator, hex[byte & 0x0f]);
}

fn runtimeSetupError(io: std.Io, err: anyerror) !void {
    return switch (err) {
        error.EmptyModelProfileRegistry => fail(io, "missing model profiles; set MISUL_MODEL_PROFILES_JSON", 2),
        error.InvalidModelProfileJson, error.InvalidModelProfile, error.DuplicateModelProfile, error.TooManyModelProfiles => fail(io, "invalid MISUL_MODEL_PROFILES_JSON", 2),
        error.ModelNotConfigured => fail(io, "selected model is absent from MISUL_MODEL_PROFILES_JSON", 2),
        error.OutputLimitExceedsProfile => fail(io, "output limit exceeds the selected model profile", 2),
        error.MissingOptionValue => fail(io, "missing option value", 2),
        error.UnknownOption => fail(io, "unknown option", 2),
        else => |other| other,
    };
}

fn write(io: std.Io, output: Output, text: []const u8) !void {
    const file = if (output == .stdout) std.Io.File.stdout() else std.Io.File.stderr();
    try file.writeStreamingAll(io, text);
}

test "model profiles fall back to the documented bundled model" {
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();

    var profiles = try loadModelProfiles(std.testing.allocator, &environment);
    defer profiles.deinit();

    const profile = profiles.find("gpt-4.1-mini").?;
    try std.testing.expectEqualStrings("openai-compatible", profile.provider);
    try std.testing.expectEqual(@as(u64, 1_047_576), profile.context_window);
    try std.testing.expectEqual(@as(u64, 32_768), profile.max_output_tokens);
    try std.testing.expect(profile.reasoning == null);
}

test "explicit model profiles override the bundled model" {
    var environment = std.process.Environ.Map.init(std.testing.allocator);
    defer environment.deinit();
    try environment.put("MISUL_MODEL_PROFILES_JSON",
        \\{"profiles":[{"id":"custom","name":"Custom","provider":"custom-provider","context_window":8192,"output_limit":512,"max_output_tokens":1024}]}
    );

    var profiles = try loadModelProfiles(std.testing.allocator, &environment);
    defer profiles.deinit();

    try std.testing.expect(profiles.find("gpt-4.1-mini") == null);
    try std.testing.expectEqualStrings("custom-provider", profiles.find("custom").?.provider);
}

test "provider diagnostics cannot emit terminal controls" {
    const safe = try sanitizeDiagnosticText(std.testing.allocator, "denied \x1b[31m\u{009b}é");
    defer std.testing.allocator.free(safe);
    try std.testing.expectEqualStrings("denied \\x1b[31m\\xc2\\x9bé", safe);
}
