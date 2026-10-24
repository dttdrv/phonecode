const std = @import("std");
const protocol = @import("protocol.zig");
const runtime_module = @import("runtime.zig");
const model_catalog = @import("model_catalog.zig");
const build_version = @import("version.zig");

pub const JsonRpcVersion = "2.0";
pub const ProtocolName = "misul-rpc";
pub const ProtocolMajor: i64 = 1;
pub const ProtocolMinor: i64 = 0;
pub const max_record_bytes: usize = 1024 * 1024;

pub const ErrorCode = struct {
    pub const parse_error: i32 = -32700;
    pub const invalid_request: i32 = -32600;
    pub const method_not_found: i32 = -32601;
    pub const invalid_params: i32 = -32602;
    pub const internal_error: i32 = -32603;
    pub const unsupported: i32 = -32004;
    pub const record_too_large: i32 = -32005;
    pub const incompatible_protocol: i32 = -32006;
    pub const handshake_required: i32 = -32007;
    pub const runtime_error: i32 = -32010;
};

fn settlementStatus(stop_reason: runtime_module.StopReason) []const u8 {
    return switch (stop_reason) {
        .complete, .terminated => "completed",
        .canceled => "canceled",
        .failed, .max_steps => "failed",
    };
}

pub const ModelCatalogItem = struct {
    id: []const u8,
    name: []const u8,
    provider: []const u8,
    context_window: u64,
    max_tokens: u64,
    reasoning: bool = false,
    configured_reasoning: ?protocol.ThinkingLevel = null,
    cache_retention: protocol.CacheRetention,
    cache_aggressiveness: protocol.CacheAggressiveness,
    transport: protocol.Transport,
};

const PromptFuture = std.Io.Future(anyerror!void);
const SystemUseWaitFuture = std.Io.Future(anyerror!void);

pub const Server = struct {
    allocator: std.mem.Allocator,
    runtime: *runtime_module.Runtime,
    session_id: []u8,
    cwd: []u8,
    current_model: []u8,
    current_thinking: ?[]const u8 = null,
    state_mutex: std.Io.Mutex = std.Io.Mutex.init,
    state_condition: std.Io.Condition = std.Io.Condition.init,
    active_run_id: ?runtime_module.RunId = null,
    prompt_running: bool = false,
    prompt_cancellable: bool = false,
    shutdown_requested: bool = false,
    prompt_future: ?PromptFuture = null,
    system_use_wait_running: bool = false,
    system_use_wait_id: ?u64 = null,
    system_use_wait_future: ?SystemUseWaitFuture = null,
    system_use_wait_cancel_requested: std.atomic.Value(bool) = .init(false),
    system_use_wait_runtime_installed: std.atomic.Value(bool) = .init(false),
    handshake_complete: bool = false,

    pub fn init(allocator: std.mem.Allocator, shared: *runtime_module.Runtime) !Server {
        return initWithDefaults(allocator, shared.workspace_root, shared.default_selection.model, shared);
    }

    fn initWithDefaults(
        allocator: std.mem.Allocator,
        default_cwd: []const u8,
        default_model: []const u8,
        borrowed_runtime: *runtime_module.Runtime,
    ) !Server {
        const initial_session_id = try borrowed_runtime.allocateSessionId(allocator);
        errdefer allocator.free(initial_session_id);
        try borrowed_runtime.createSessionWithId(initial_session_id, borrowed_runtime.default_selection);

        const cwd_copy = try allocator.dupe(u8, default_cwd);
        errdefer allocator.free(cwd_copy);

        const model_copy = try allocator.dupe(u8, default_model);
        errdefer allocator.free(model_copy);

        return .{
            .allocator = allocator,
            .runtime = borrowed_runtime,
            .session_id = initial_session_id,
            .cwd = cwd_copy,
            .current_model = model_copy,
            .current_thinking = if (borrowed_runtime.default_selection.reasoning) |level| @tagName(level) else null,
        };
    }

    pub fn deinit(self: *Server) void {
        // runStdio owns the adapter Future and must join it before teardown.
        std.debug.assert(self.prompt_future == null and !self.prompt_running and !self.prompt_cancellable);
        std.debug.assert(self.system_use_wait_future == null and !self.system_use_wait_running);
        self.allocator.free(self.session_id);
        self.allocator.free(self.cwd);
        self.allocator.free(self.current_model);
    }

    // --- Streaming Notification Emitters ---

    pub fn emitNotification(self: *Server, output: *std.ArrayList(u8), method: []const u8, params: anytype) !void {
        const Notification = struct {
            jsonrpc: []const u8 = JsonRpcVersion,
            method: []const u8,
            params: @TypeOf(params),
        };
        const msg = Notification{
            .method = method,
            .params = params,
        };
        const encoded = try std.json.Stringify.valueAlloc(self.allocator, msg, .{ .emit_null_optional_fields = false });
        defer self.allocator.free(encoded);
        try output.appendSlice(self.allocator, encoded);
        try output.append(self.allocator, '\n');
    }

    pub fn emitAgentStart(
        self: *Server,
        output: *std.ArrayList(u8),
        session_id: []const u8,
        input_message_id: []const u8,
        assistant_message_id: []const u8,
        timestamp_ms: i64,
    ) !void {
        try self.emitNotification(output, "agent_start", .{
            .session_id = session_id,
            .input_message_id = input_message_id,
            .assistant_message_id = assistant_message_id,
            .timestamp_ms = timestamp_ms,
        });
    }

    pub fn emitMessageDelta(
        self: *Server,
        output: *std.ArrayList(u8),
        reasoning_delta: ?[]const u8,
        text_delta: ?[]const u8,
    ) !void {
        try self.emitNotification(output, "message_delta", .{
            .reasoning_delta = reasoning_delta,
            .text_delta = text_delta,
        });
    }

    pub fn emitAgentEnd(self: *Server, output: *std.ArrayList(u8), session_id: []const u8, stop_reason: []const u8, timestamp_ms: i64) !void {
        try self.emitNotification(output, "agent_end", .{
            .session_id = session_id,
            .stop_reason = stop_reason,
            .timestamp_ms = timestamp_ms,
        });
    }

    // --- Response Formatter ---

    fn allocSuccessResponse(self: *Server, id: std.json.Value, result: anytype) !?[]u8 {
        if (id == .null) return null;
        const res_json = try std.json.Stringify.valueAlloc(self.allocator, result, .{ .emit_null_optional_fields = false });
        defer self.allocator.free(res_json);

        const id_json = try std.json.Stringify.valueAlloc(self.allocator, id, .{});
        defer self.allocator.free(id_json);

        const raw = try std.fmt.allocPrint(
            self.allocator,
            "{{\"jsonrpc\":\"2.0\",\"id\":{s},\"result\":{s}}}\n",
            .{ id_json, res_json },
        );
        if (raw.len > max_record_bytes) {
            self.allocator.free(raw);
            return error.RpcResultTooLarge;
        }
        return raw;
    }

    fn writeSuccessResponse(self: *Server, output: *std.ArrayList(u8), id: std.json.Value, result: anytype) !void {
        const raw = self.allocSuccessResponse(id, result) catch |err| switch (err) {
            error.RpcResultTooLarge => return self.writeErrorResponse(output, id, ErrorCode.record_too_large, "RPC result exceeds the negotiated record limit"),
            else => return err,
        };
        defer if (raw) |record| self.allocator.free(record);
        if (raw) |record| try output.appendSlice(self.allocator, record);
    }

    fn writeErrorResponse(self: *Server, output: *std.ArrayList(u8), id: ?std.json.Value, code: i32, message: []const u8) !void {
        if (id) |value| if (value == .null) return;
        const id_json = if (id) |val|
            try std.json.Stringify.valueAlloc(self.allocator, val, .{})
        else
            try self.allocator.dupe(u8, "null");
        defer self.allocator.free(id_json);

        const msg_json = try std.json.Stringify.valueAlloc(self.allocator, message, .{});
        defer self.allocator.free(msg_json);

        const raw = try std.fmt.allocPrint(
            self.allocator,
            "{{\"jsonrpc\":\"2.0\",\"id\":{s},\"error\":{{\"code\":{d},\"message\":{s}}}}}\n",
            .{ id_json, code, msg_json },
        );
        defer self.allocator.free(raw);
        try output.appendSlice(self.allocator, raw);
    }

    // --- Main Dispatcher ---

    const ParsedRequest = struct {
        method: []const u8,
        id: std.json.Value,
        params: ?std.json.Value,
    };

    fn validateParsedRequest(self: *Server, root: std.json.Value, output: *std.ArrayList(u8)) !?ParsedRequest {
        if (root != .object) {
            try self.writeErrorResponse(output, null, ErrorCode.invalid_request, "Invalid Request: expected JSON object");
            return null;
        }
        const obj = root.object;

        const request_id = obj.get("id");
        if (request_id) |value| switch (value) {
            .string, .integer => {},
            else => {
                try self.writeErrorResponse(output, null, ErrorCode.invalid_request, "Invalid Request: 'id' must be a string or integer");
                return null;
            },
        };

        const rpc_ver = obj.get("jsonrpc");
        if (rpc_ver == null or rpc_ver.? != .string or !std.mem.eql(u8, rpc_ver.?.string, JsonRpcVersion)) {
            try self.writeErrorResponse(output, request_id, ErrorCode.invalid_request, "Invalid Request: missing or invalid 'jsonrpc' version");
            return null;
        }

        const method_val = obj.get("method");
        if (method_val == null or method_val.? != .string) {
            try self.writeErrorResponse(output, request_id, ErrorCode.invalid_request, "Invalid Request: missing 'method'");
            return null;
        }
        return .{
            .method = method_val.?.string,
            .id = request_id orelse .null,
            .params = obj.get("params"),
        };
    }

    pub fn handleLine(self: *Server, line: []const u8, output: *std.ArrayList(u8)) !void {
        if (line.len > max_record_bytes) {
            return self.writeErrorResponse(output, null, ErrorCode.record_too_large, "RPC record exceeds the negotiated limit");
        }
        const trimmed = std.mem.trim(u8, line, " \t\r\n");
        if (trimmed.len == 0) return;

        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, trimmed, .{}) catch {
            return self.writeErrorResponse(output, null, ErrorCode.parse_error, "Parse error");
        };
        defer parsed.deinit();

        const request = (try self.validateParsedRequest(parsed.value, output)) orelse return;
        try self.dispatch(request.method, request.id, request.params, output);
    }

    fn dispatch(
        self: *Server,
        method: []const u8,
        id: std.json.Value,
        params: ?std.json.Value,
        output: *std.ArrayList(u8),
    ) !void {
        if (!self.handshake_complete and !std.mem.eql(u8, method, "rpc/handshake")) {
            return self.writeErrorResponse(output, id, ErrorCode.handshake_required, "RPC handshake is required before this method");
        }
        if (std.mem.eql(u8, method, "rpc/handshake")) {
            return self.handleHandshake(id, params, output);
        } else if (std.mem.eql(u8, method, "session/start")) {
            return self.handleSessionStart(id, params, output);
        } else if (std.mem.eql(u8, method, "session/prompt")) {
            return self.handleSessionPrompt(id, params, output);
        } else if (std.mem.eql(u8, method, "session/abort")) {
            return self.handleSessionAbort(id, params, output);
        } else if (std.mem.eql(u8, method, "session/switch")) {
            return self.handleSessionSwitch(id, params, output);
        } else if (std.mem.eql(u8, method, "session/new")) {
            return self.handleSessionNew(id, params, output);
        } else if (std.mem.eql(u8, method, "session/import")) {
            return self.handleSessionImport(id, params, output);
        } else if (std.mem.eql(u8, method, "session/list")) {
            return self.handleSessionList(id, params, output);
        } else if (std.mem.eql(u8, method, "session/history")) {
            return self.handleSessionHistory(id, params, output);
        } else if (std.mem.eql(u8, method, "model/list")) {
            return self.handleModelList(id, params, output);
        } else if (std.mem.eql(u8, method, "model/set")) {
            return self.handleModelSet(id, params, output);
        } else if (std.mem.eql(u8, method, "system_use/permissions")) {
            return self.handleSystemUsePermissions(id, params, output);
        } else if (std.mem.eql(u8, method, "system_use/list")) {
            return self.handleSystemUseList(id, params, output);
        } else if (std.mem.eql(u8, method, "system_use/observe")) {
            return self.handleSystemUseObserve(id, params, output);
        } else if (std.mem.eql(u8, method, "system_use/action")) {
            return self.handleSystemUseAction(id, params, output);
        } else if (std.mem.eql(u8, method, "system_use/wait")) {
            return self.handleSystemUseWait(id, params, output);
        } else if (std.mem.eql(u8, method, "system_use/cancel")) {
            return self.handleSystemUseCancel(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/start")) {
            return self.handleBrowserStart(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/navigate")) {
            return self.handleBrowserNavigate(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/snapshot")) {
            return self.handleBrowserSnapshot(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/click")) {
            return self.handleBrowserClick(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/fill")) {
            return self.handleBrowserFill(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/back")) {
            return self.handleBrowserBack(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/close")) {
            return self.handleBrowserClose(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/forward")) {
            return self.handleBrowserForward(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/tabs")) {
            return self.handleBrowserTabs(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/tab_new")) {
            return self.handleBrowserTabNew(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/tab_switch")) {
            return self.handleBrowserTabSwitch(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/tab_close")) {
            return self.handleBrowserTabClose(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/screenshot")) {
            return self.handleBrowserScreenshot(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/key")) {
            return self.handleBrowserKey(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/select")) {
            return self.handleBrowserSelect(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/upload")) {
            return self.handleBrowserUpload(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/dialog")) {
            return self.handleBrowserDialog(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/scroll")) {
            return self.handleBrowserScroll(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/hover")) {
            return self.handleBrowserHover(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/double_click")) {
            return self.handleBrowserDoubleClick(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/right_click")) {
            return self.handleBrowserRightClick(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/drag")) {
            return self.handleBrowserDrag(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/wait")) {
            return self.handleBrowserWait(id, params, output);
        } else if (std.mem.eql(u8, method, "browser/extract")) {
            return self.handleBrowserExtract(id, params, output);
        } else if (std.mem.eql(u8, method, "approval/respond") or
            std.mem.eql(u8, method, "pty/create") or
            std.mem.eql(u8, method, "pty/input") or
            std.mem.eql(u8, method, "pty/resize") or
            std.mem.eql(u8, method, "pty/close") or
            std.mem.eql(u8, method, "composer/speculate") or
            std.mem.eql(u8, method, "context/query") or
            std.mem.eql(u8, method, "rules/load") or
            std.mem.eql(u8, method, "shadow/apply"))
        {
            return self.writeErrorResponse(output, id, ErrorCode.unsupported, "Capability is not implemented by the shared runtime");
        } else {
            return self.writeErrorResponse(output, id, ErrorCode.method_not_found, "Method not found");
        }
    }

    // --- Method Handlers ---

    fn handleHandshake(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing handshake parameters");
        if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Handshake parameters must be an object");
        const major_value = value.object.get("protocol_major") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing protocol_major");
        const minor_value = value.object.get("protocol_minor") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing protocol_minor");
        if (major_value != .integer or minor_value != .integer or major_value.integer < 0 or minor_value.integer < 0) {
            return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Protocol versions must be non-negative integers");
        }
        if (major_value.integer != ProtocolMajor) {
            return self.writeErrorResponse(output, id, ErrorCode.incompatible_protocol, "Incompatible Misul RPC protocol major version");
        }
        if (id == .null) return;
        self.runtime.ensureSystemUseAvailable() catch {};
        try self.writeSuccessResponse(output, id, .{
            .protocol = .{
                .name = ProtocolName,
                .major = ProtocolMajor,
                .minor = @min(minor_value.integer, ProtocolMinor),
            },
            .server = .{
                .name = "misul-agent",
                .version = build_version.current,
            },
            .limits = .{
                .max_record_bytes = max_record_bytes,
                .max_parallel_prompts = 1,
                .max_session_list_items = runtime_module.maximum_session_list_items,
                .max_session_history_items = runtime_module.maximum_session_history_items,
                .max_session_history_payload_bytes = runtime_module.maximum_session_history_payload_bytes,
            },
            .capabilities = .{
                .session_start = true,
                .session_new = true,
                .session_prompt = true,
                .session_abort = true,
                .session_switch = true,
                .session_list = true,
                .session_history = true,
                .model_list = true,
                .model_set = true,
                .approvals = false,
                .pty = false,
                .event_replay = false,
                .system_use = if (self.runtime.systemUseAvailable()) @as(?bool, true) else null,
                .browser = if (self.runtime.browserAvailable()) @as(?bool, true) else null,
            },
        });
        self.handshake_complete = true;
    }

    fn writeSuccessJson(self: *Server, output: *std.ArrayList(u8), id: std.json.Value, json: []const u8) !void {
        if (id == .null) return;
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, json, .{}) catch return error.InvalidSystemUseResponse;
        defer parsed.deinit();
        return self.writeSuccessResponse(output, id, parsed.value);
    }

    fn requireSystemUse(self: *Server, id: std.json.Value, output: *std.ArrayList(u8)) !bool {
        self.runtime.ensureSystemUseAvailable() catch {};
        if (self.runtime.systemUseAvailable()) return true;
        try self.writeErrorResponse(output, id, ErrorCode.unsupported, "SystemUse is not configured by the shared runtime");
        return false;
    }

    fn handleSystemUseError(self: *Server, id: std.json.Value, output: *std.ArrayList(u8), err: anyerror) !void {
        return switch (err) {
            error.SystemUseUnavailable => self.writeErrorResponse(output, id, ErrorCode.unsupported, "SystemUse is unavailable"),
            error.SystemUsePolicyDenied => self.writeErrorResponse(output, id, ErrorCode.unsupported, "SystemUse action is denied by runtime policy"),
            error.SystemUseWaitBusy => self.writeErrorResponse(output, id, ErrorCode.runtime_error, "A SystemUse wait is already active"),
            error.SystemUseWaitNotFound => self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Unknown SystemUse wait"),
            error.Canceled => self.writeErrorResponse(output, id, ErrorCode.runtime_error, "SystemUse wait was canceled"),
            error.SystemUseTimeout => self.writeErrorResponse(output, id, ErrorCode.runtime_error, "SystemUse platform operation timed out"),
            error.InvalidSystemUseWait, error.InvalidSystemUseParameters, error.UnsupportedSystemUseAction => self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid SystemUse parameters"),
            error.InvalidActionDescriptor => self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid SystemUse action descriptor"),
            error.SystemUseRecordTooLarge => self.writeErrorResponse(output, id, ErrorCode.record_too_large, "SystemUse result exceeds the negotiated record limit"),
            error.SystemUseExternalFailure => self.writeErrorResponse(output, id, ErrorCode.runtime_error, "SystemUse platform operation failed"),
            error.SystemUseTransportFailed, error.InvalidSystemUseResponse, error.InvalidSystemUseHandshake => self.writeErrorResponse(output, id, ErrorCode.runtime_error, "SystemUse service is unavailable"),
            else => err,
        };
    }

    fn handleSystemUsePermissions(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireSystemUse(id, output)) return;
        if (params) |value| if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "SystemUse parameters must be an object");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.systemUsePermissions(systemUseRpcContext(request_id, self.session_id)) catch |err| return self.handleSystemUseError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleSystemUseList(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireSystemUse(id, output)) return;
        if (params) |value| if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "SystemUse parameters must be an object");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.systemUseList(systemUseRpcContext(request_id, self.session_id)) catch |err| return self.handleSystemUseError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn systemUseTarget(params: std.json.Value) !std.json.Value {
        if (params != .object) return error.InvalidSystemUseParameters;
        const target = params.object.get("target") orelse return error.InvalidSystemUseParameters;
        if (target != .object) return error.InvalidSystemUseParameters;
        const id = target.object.get("id") orelse return error.InvalidSystemUseParameters;
        if (id != .string or id.string.len == 0 or id.string.len > 256) return error.InvalidSystemUseParameters;
        return target;
    }

    fn optionalBoundedInteger(params: std.json.Value, key: []const u8, default: i64, minimum: i64, maximum: i64) !i64 {
        const value = params.object.get(key) orelse return default;
        if (value != .integer or value.integer < minimum or value.integer > maximum) return error.InvalidSystemUseParameters;
        return value.integer;
    }

    fn handleSystemUseObserve(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireSystemUse(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing SystemUse parameters");
        const target = systemUseTarget(value) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid SystemUse target");
        const max_depth = optionalBoundedInteger(value, "max_depth", 8, 0, 30) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid SystemUse observation bounds");
        const max_elements = optionalBoundedInteger(value, "max_elements", 500, 1, 5000) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid SystemUse observation bounds");
        const screenshot_value = value.object.get("screenshot");
        if (screenshot_value != null and screenshot_value.? != .bool) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "screenshot must be a boolean");
        const screenshot = if (screenshot_value) |item| item.bool else false;
        const include_actions_value = value.object.get("include_actions");
        if (include_actions_value != null and include_actions_value.? != .bool) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "include_actions must be a boolean");
        const include_actions = if (include_actions_value) |item| item.bool else false;
        const encoded = try std.json.Stringify.valueAlloc(self.allocator, .{
            .target = target,
            .maxDepth = max_depth,
            .maxElements = max_elements,
            .includeActions = include_actions,
            .screenshot = screenshot,
        }, .{});
        defer self.allocator.free(encoded);
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.systemUseObserve(systemUseRpcContext(request_id, self.session_id), encoded, screenshot) catch |err| return self.handleSystemUseError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleSystemUseAction(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireSystemUse(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing SystemUse parameters");
        const target = systemUseTarget(value) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid SystemUse target");
        const name_value = value.object.get("name") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "SystemUse action name is required");
        if (name_value != .string) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "SystemUse action name must be a string");
        const focus_value = value.object.get("focus_mode");
        if (focus_value != null and focus_value.? != .string) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "focus_mode must be background or activate");
        const focus_mode = if (focus_value) |item| item.string else "background";
        if (!std.mem.eql(u8, focus_mode, "background") and !std.mem.eql(u8, focus_mode, "activate")) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "focus_mode must be background or activate");

        var sidecar_method: []const u8 = undefined;
        var encoded: []u8 = undefined;
        if (std.mem.eql(u8, name_value.string, "click")) {
            sidecar_method = "click";
            const element_value = value.object.get("element_id");
            const x_value = value.object.get("x");
            const y_value = value.object.get("y");
            if (element_value != null and element_value.? != .string) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "element_id must be a string");
            if (element_value != null and (element_value.?.string.len == 0 or element_value.?.string.len > 256)) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "element_id is invalid");
            if (element_value != null and std.mem.eql(u8, focus_mode, "activate")) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Element click supports background focus only");
            if ((x_value == null) != (y_value == null)) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Coordinate click requires x and y");
            if (element_value == null and x_value == null) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "click requires element_id or x and y");
            if (element_value != null and x_value != null) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "click requires exactly one of element_id or coordinates");
            if (x_value != null and x_value.? != .integer) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "x must be an integer");
            if (y_value != null and y_value.? != .integer) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "y must be an integer");
            if (x_value != null and (x_value.?.integer < -1_000_000 or x_value.?.integer > 1_000_000 or y_value.?.integer < -1_000_000 or y_value.?.integer > 1_000_000)) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Coordinate click is out of range");
            if (x_value != null and std.mem.eql(u8, focus_mode, "background")) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Coordinate click requires activate focus");
            encoded = try std.json.Stringify.valueAlloc(self.allocator, .{
                .target = target,
                .elementId = element_value,
                .x = x_value,
                .y = y_value,
                .focusMode = focus_mode,
            }, .{ .emit_null_optional_fields = false });
        } else if (std.mem.eql(u8, name_value.string, "type")) {
            if (!std.mem.eql(u8, focus_mode, "activate")) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "type requires activate focus");
            sidecar_method = "type";
            const text_value = value.object.get("value") orelse value.object.get("text") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "type requires value");
            if (text_value != .string) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "type requires a string value");
            if (text_value.string.len == 0 or text_value.string.len > 4096) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "type value must be 1-4096 bytes");
            encoded = try std.json.Stringify.valueAlloc(self.allocator, .{ .target = target, .value = text_value.string, .focusMode = focus_mode }, .{});
        } else if (std.mem.eql(u8, name_value.string, "scroll")) {
            if (!std.mem.eql(u8, focus_mode, "activate")) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "scroll requires activate focus");
            sidecar_method = "scroll";
            const element_value = value.object.get("element_id");
            const x_value = value.object.get("x");
            const y_value = value.object.get("y");
            if (element_value != null and element_value.? != .string) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "element_id must be a string");
            if (element_value != null and (element_value.?.string.len == 0 or element_value.?.string.len > 256)) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "element_id is invalid");
            if ((x_value == null) != (y_value == null)) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Coordinate scroll requires x and y");
            if (element_value == null and x_value == null) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "scroll requires element_id or x and y");
            if (element_value != null and x_value != null) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "scroll requires exactly one of element_id or coordinates");
            if (x_value != null and x_value.? != .integer) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "x must be an integer");
            if (y_value != null and y_value.? != .integer) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "y must be an integer");
            if (x_value != null and (x_value.?.integer < -1_000_000 or x_value.?.integer > 1_000_000 or y_value.?.integer < -1_000_000 or y_value.?.integer > 1_000_000)) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Coordinate scroll is out of range");
            const dx_value = value.object.get("delta_x");
            const dy_value = value.object.get("delta_y") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "scroll requires delta_y");
            if (dx_value != null and dx_value.? != .integer) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "delta_x must be an integer");
            if (dy_value != .integer) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "delta_y must be an integer");
            const dx: i64 = if (dx_value) |item| item.integer else 0;
            if (dx < -10_000 or dx > 10_000 or dy_value.integer < -10_000 or dy_value.integer > 10_000 or (dx == 0 and dy_value.integer == 0)) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "scroll delta is out of range");
            encoded = try std.json.Stringify.valueAlloc(self.allocator, .{
                .target = target,
                .elementId = element_value,
                .x = x_value,
                .y = y_value,
                .deltaX = dx,
                .deltaY = dy_value.integer,
                .focusMode = focus_mode,
            }, .{ .emit_null_optional_fields = false });
        } else if (std.mem.eql(u8, name_value.string, "set_value")) {
            if (std.mem.eql(u8, focus_mode, "activate")) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "set_value supports background focus only");
            sidecar_method = "setValue";
            const element = value.object.get("element_id") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "set_value requires element_id");
            const new_value = value.object.get("value") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "set_value requires value");
            if (element != .string or new_value != .string) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "set_value requires string values");
            encoded = try std.json.Stringify.valueAlloc(self.allocator, .{ .target = target, .elementId = element.string, .value = new_value.string }, .{});
        } else {
            return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Unsupported SystemUse action");
        }
        defer self.allocator.free(encoded);
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.systemUseAction(systemUseRpcContext(request_id, self.session_id), sidecar_method, encoded) catch |err| return self.handleSystemUseError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleSystemUseWait(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireSystemUse(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing SystemUse parameters");
        const target = systemUseTarget(value) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid SystemUse target");
        const text_value = value.object.get("text");
        const element_value = value.object.get("element_id");
        if ((text_value == null) == (element_value == null)) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "SystemUse wait requires exactly one of text or element_id");
        if (text_value != null and text_value.? != .string) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "text must be a string");
        if (element_value != null and element_value.? != .string) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "element_id must be a string");
        if (element_value != null and (element_value.?.string.len == 0 or element_value.?.string.len > 256)) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "element_id is invalid");
        const state_value = value.object.get("state");
        if (state_value != null and state_value.? != .string) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "state must be present or absent");
        const state_name = if (state_value) |item| item.string else "present";
        if (!std.mem.eql(u8, state_name, "present") and !std.mem.eql(u8, state_name, "absent")) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "state must be present or absent");
        const timeout_ms = optionalBoundedInteger(value, "timeout_ms", 5000, 1, 30000) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid SystemUse wait timeout");
        const poll_ms = optionalBoundedInteger(value, "poll_interval_ms", 50, 1, 1000) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid SystemUse wait interval");
        const requested_wait_id = if (value.object.get("wait_id")) |wait| blk: {
            if (wait != .string or !std.mem.startsWith(u8, wait.string, "wait-")) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid wait_id");
            break :blk std.fmt.parseUnsigned(u64, wait.string[5..], 10) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid wait_id");
        } else null;
        const target_json = try std.json.Stringify.valueAlloc(self.allocator, .{ .target = target, .maxDepth = 8, .maxElements = 500, .includeActions = false, .screenshot = false }, .{});
        defer self.allocator.free(target_json);
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.systemUseWait(systemUseRpcContext(request_id, self.session_id), .{
            .target_json = target_json,
            .wait_id = requested_wait_id,
            .text = if (text_value) |item| item.string else null,
            .element_id = if (element_value) |item| item.string else null,
            .present = std.mem.eql(u8, state_name, "present"),
            .timeout_ms = @intCast(timeout_ms),
            .poll_interval_ms = @intCast(poll_ms),
            .external_cancel = &self.system_use_wait_cancel_requested,
            .install_notice = &self.system_use_wait_runtime_installed,
        }) catch |err| return self.handleSystemUseError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleSystemUseCancel(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireSystemUse(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing SystemUse parameters");
        if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "SystemUse parameters must be an object");
        const wait = value.object.get("wait_id") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "wait_id is required");
        if (wait != .string or !std.mem.startsWith(u8, wait.string, "wait-")) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid wait_id");
        const wait_id = std.fmt.parseUnsigned(u64, wait.string[5..], 10) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid wait_id");
        self.state_mutex.lockUncancelable(self.runtime.io);
        const pending_match = self.system_use_wait_running and self.system_use_wait_id == wait_id;
        self.state_mutex.unlock(self.runtime.io);
        if (pending_match) self.system_use_wait_cancel_requested.store(true, .release);
        self.runtime.cancelSystemUseWait(wait_id) catch |err| {
            if (err != error.SystemUseWaitNotFound or !pending_match or self.system_use_wait_runtime_installed.load(.acquire)) return self.handleSystemUseError(id, output, err);
        };
        try self.writeSuccessResponse(output, id, .{ .wait_id = wait.string, .status = "requested" });
    }

    fn requireBrowser(self: *Server, id: std.json.Value, output: *std.ArrayList(u8)) !bool {
        if (self.runtime.browserAvailable()) return true;
        try self.writeErrorResponse(output, id, ErrorCode.unsupported, "Browser automation is not configured by the shared runtime");
        return false;
    }

    fn handleBrowserError(self: *Server, id: std.json.Value, output: *std.ArrayList(u8), err: anyerror) !void {
        return switch (err) {
            error.SystemUsePolicyDenied => self.writeErrorResponse(output, id, ErrorCode.unsupported, "Browser action is denied by runtime policy"),
            error.BrowserNoSession => self.writeErrorResponse(output, id, ErrorCode.invalid_params, "No browser session: call browser/start first"),
            error.BrowserNoDialog => self.writeErrorResponse(output, id, ErrorCode.invalid_params, "No dialog is open"),
            error.BrowserNoSnapshot => self.writeErrorResponse(output, id, ErrorCode.invalid_params, "No browser snapshot: call browser/snapshot first"),
            error.BrowserBadIndex => self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser element index is invalid"),
            error.BrowserInvalidParams => self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid browser parameters"),
            error.BrowserConsentRequired => self.writeErrorResponse(output, id, ErrorCode.runtime_error, "Safari refused automation: enable 'Allow remote automation' in Safari Settings > Developer"),
            error.BrowserSessionGone => self.writeErrorResponse(output, id, ErrorCode.runtime_error, "Browser session is gone: call browser/start again"),
            error.BrowserTimeout => self.writeErrorResponse(output, id, ErrorCode.runtime_error, "Browser operation timed out"),
            error.BrowserExternalFailure => self.writeErrorResponse(output, id, ErrorCode.runtime_error, "Browser operation failed"),
            error.BrowserUnavailable, error.BrowserInvalidResponse, error.BrowserResponseTooLarge => self.writeErrorResponse(output, id, ErrorCode.runtime_error, "Browser service is unavailable"),
            else => err,
        };
    }

    fn handleBrowserStart(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        if (params) |value| if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserStart(systemUseRpcContext(request_id, self.session_id)) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserNavigate(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const url_value = value.object.get("url") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "navigate requires url");
        if (url_value != .string) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "url must be a string");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserNavigate(systemUseRpcContext(request_id, self.session_id), url_value.string) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserSnapshot(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        if (params) |value| if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserSnapshot(systemUseRpcContext(request_id, self.session_id)) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn browserElementIndex(value: std.json.Value) !i64 {
        if (value != .object) return error.BrowserInvalidParams;
        const index = value.object.get("index") orelse return error.BrowserInvalidParams;
        if (index != .integer or index.integer < 0 or index.integer >= 500) return error.BrowserInvalidParams;
        return index.integer;
    }

    fn handleBrowserClick(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        const index = browserElementIndex(value) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "click requires an element index");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserClick(systemUseRpcContext(request_id, self.session_id), index) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserFill(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        const index = browserElementIndex(value) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "fill requires an element index");
        const text_value = if (value == .object) value.object.get("text") else null;
        if (text_value == null or text_value.? != .string or text_value.?.string.len == 0 or text_value.?.string.len > 4096) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "fill requires text of 1-4096 bytes");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserFill(systemUseRpcContext(request_id, self.session_id), index, text_value.?.string) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserBack(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        if (params) |value| if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserBack(systemUseRpcContext(request_id, self.session_id)) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserClose(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        if (params) |value| if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserClose(systemUseRpcContext(request_id, self.session_id)) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserForward(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        if (params) |value| if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserForward(systemUseRpcContext(request_id, self.session_id)) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserTabs(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        if (params) |value| if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserTabs(systemUseRpcContext(request_id, self.session_id)) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserTabNew(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        var url: ?[]const u8 = null;
        if (params) |value| {
            if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
            if (value.object.get("url")) |item| {
                if (item != .string) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "url must be a string");
                url = item.string;
            }
        }
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserTabNew(systemUseRpcContext(request_id, self.session_id), url) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserTabSwitch(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const handle = value.object.get("handle") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "tab_switch requires handle");
        if (handle != .string or handle.string.len == 0 or handle.string.len > 256) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "handle is invalid");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserTabSwitch(systemUseRpcContext(request_id, self.session_id), handle.string) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserTabClose(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        var handle: ?[]const u8 = null;
        if (params) |value| {
            if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
            if (value.object.get("handle")) |item| {
                if (item != .string or item.string.len == 0 or item.string.len > 256) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "handle is invalid");
                handle = item.string;
            }
        }
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserTabClose(systemUseRpcContext(request_id, self.session_id), handle) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserScreenshot(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        if (params) |value| if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserScreenshot(systemUseRpcContext(request_id, self.session_id)) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserKey(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const key = value.object.get("key") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "key requires a key name");
        if (key != .string or key.string.len == 0 or key.string.len > 32) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "key name is invalid");
        var modifiers: [3][]const u8 = undefined;
        var modifier_count: usize = 0;
        if (value.object.get("modifiers")) |item| {
            if (item != .array or item.array.items.len > 3) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "modifiers holds at most 3 names");
            for (item.array.items) |entry| {
                if (entry != .string or entry.string.len == 0 or entry.string.len > 32) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "modifier name is invalid");
                modifiers[modifier_count] = entry.string;
                modifier_count += 1;
            }
        }
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserKey(systemUseRpcContext(request_id, self.session_id), key.string, modifiers[0..modifier_count]) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserSelect(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        const index = browserElementIndex(value) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "select requires an element index");
        const option = if (value == .object) value.object.get("value") else null;
        if (option == null or option.? != .string or option.?.string.len == 0 or option.?.string.len > 1024) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "select requires a value");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserSelect(systemUseRpcContext(request_id, self.session_id), index, option.?.string) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserUpload(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        const index = browserElementIndex(value) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "upload requires an element index");
        const path_value = if (value == .object) value.object.get("path") else null;
        if (path_value == null or path_value.? != .string or path_value.?.string.len == 0 or path_value.?.string.len > 1024) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "upload requires a file path");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserUpload(systemUseRpcContext(request_id, self.session_id), index, path_value.?.string) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserDialog(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const accept_value = value.object.get("accept");
        if (accept_value != null and accept_value.? != .bool) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "accept must be a boolean");
        const accept = if (accept_value) |item| item.bool else true;
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserDialog(systemUseRpcContext(request_id, self.session_id), accept) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserScroll(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const x = if (value.object.get("x")) |item| blk: {
            if (item != .integer or item.integer < -5000 or item.integer > 5000) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "x is out of range");
            break :blk item.integer;
        } else 0;
        const y = if (value.object.get("y")) |item| blk: {
            if (item != .integer or item.integer < -5000 or item.integer > 5000) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "y is out of range");
            break :blk item.integer;
        } else 0;
        var index: ?i64 = null;
        if (value.object.get("index")) |item| {
            if (item != .integer or item.integer < 0 or item.integer >= 500) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "index is invalid");
            index = item.integer;
        }
        if (x == 0 and y == 0 and index == null) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "scroll needs pixels or an element index");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserScrollPage(systemUseRpcContext(request_id, self.session_id), x, y, index) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserHover(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        const index = browserElementIndex(value) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "hover requires an element index");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserHover(systemUseRpcContext(request_id, self.session_id), index) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserDoubleClick(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        const index = browserElementIndex(value) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "double_click requires an element index");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserDoubleClick(systemUseRpcContext(request_id, self.session_id), index) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserRightClick(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        const index = browserElementIndex(value) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "right_click requires an element index");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserRightClick(systemUseRpcContext(request_id, self.session_id), index) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserDrag(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const from_value = value.object.get("from_index") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "drag requires from_index");
        const to_value = value.object.get("to_index") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "drag requires to_index");
        if (from_value != .integer or to_value != .integer or from_value.integer < 0 or from_value.integer >= 500 or to_value.integer < 0 or to_value.integer >= 500) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "drag indices are invalid");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserDrag(systemUseRpcContext(request_id, self.session_id), from_value.integer, to_value.integer) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserWait(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing browser parameters");
        if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const text_value = value.object.get("text") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "wait requires text");
        if (text_value != .string or text_value.string.len == 0 or text_value.string.len > 1024) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "text is invalid");
        const timeout_ms = optionalBoundedInteger(value, "timeout_ms", 5000, 1, 30000) catch return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid wait timeout");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserWaitText(systemUseRpcContext(request_id, self.session_id), text_value.string, @intCast(timeout_ms)) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleBrowserExtract(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (!try self.requireBrowser(id, output)) return;
        if (params) |value| if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Browser parameters must be an object");
        const request_id = try systemUseRequestId(self.allocator, id);
        defer self.allocator.free(request_id);
        var result = self.runtime.browserExtract(systemUseRpcContext(request_id, self.session_id)) catch |err| return self.handleBrowserError(id, output, err);
        defer result.deinit();
        try self.writeSuccessJson(output, id, result.json);
    }

    fn handleSessionStart(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        var next_session_id: ?[]u8 = null;
        var next_cwd: ?[]u8 = null;
        var next_model: ?[]u8 = null;
        defer if (next_session_id) |value| self.allocator.free(value);
        defer if (next_cwd) |value| self.allocator.free(value);
        defer if (next_model) |value| self.allocator.free(value);
        if (params) |p| {
            if (p != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Session start parameters must be an object");
            if (p.object.get("session_id")) |sid| {
                if (sid != .string or sid.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "session_id must be a non-empty string");
                next_session_id = try self.allocator.dupe(u8, sid.string);
            }
            if (p.object.get("cwd")) |c| {
                if (c != .string or c.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "cwd must be a non-empty string");
                next_cwd = try self.allocator.dupe(u8, c.string);
            }
            if (p.object.get("model")) |m| {
                if (m != .string or m.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "model must be a non-empty string");
                next_model = try self.allocator.dupe(u8, m.string);
            }
        }
        const selected_session_id = next_session_id orelse self.session_id;
        const selected_model = next_model orelse self.current_model;
        if (next_cwd) |cwd| if (!std.mem.eql(u8, cwd, self.runtime.workspace_root)) {
            return self.writeErrorResponse(output, id, ErrorCode.unsupported, "Changing cwd is not implemented by the shared runtime");
        };
        const selected = self.resolveRuntimeSelectionValues(selected_session_id, selected_model, null) catch |err| switch (err) {
            error.ModelNotConfigured => return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Model is not configured for the shared runtime"),
            error.InvalidThinking => return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Thinking level is not configured for the shared runtime"),
            else => return self.writeErrorResponse(output, id, ErrorCode.runtime_error, @errorName(err)),
        };
        defer runtime_module.deinitSelection(self.allocator, selected);
        const result_cwd = next_cwd orelse self.cwd;
        const response = self.allocSuccessResponse(id, .{
            .session_id = selected_session_id,
            .cwd = result_cwd,
            .model = selected_model,
            .status = "active",
        }) catch |err| switch (err) {
            error.RpcResultTooLarge => return self.writeErrorResponse(output, id, ErrorCode.record_too_large, "RPC result exceeds the negotiated record limit"),
            else => return err,
        };
        defer if (response) |record| self.allocator.free(record);
        if (response) |record| try output.ensureUnusedCapacity(self.allocator, record.len);
        self.runtime.setSelection(selected_session_id, selected) catch |err|
            return self.writeErrorResponse(output, id, ErrorCode.runtime_error, @errorName(err));
        if (next_session_id) |value| {
            self.allocator.free(self.session_id);
            self.session_id = value;
            next_session_id = null;
        }
        if (next_cwd) |value| {
            self.allocator.free(self.cwd);
            self.cwd = value;
            next_cwd = null;
        }
        if (next_model) |value| {
            self.allocator.free(self.current_model);
            self.current_model = value;
            next_model = null;
        }
        self.current_thinking = if (selected.reasoning) |level| @tagName(level) else null;
        if (response) |record| output.appendSliceAssumeCapacity(record);
    }

    fn parseThinking(value: ?[]const u8) ?protocol.ThinkingLevel {
        const text = value orelse return null;
        if (std.ascii.eqlIgnoreCase(text, "minimal")) return .minimal;
        if (std.ascii.eqlIgnoreCase(text, "low")) return .low;
        if (std.ascii.eqlIgnoreCase(text, "medium")) return .medium;
        if (std.ascii.eqlIgnoreCase(text, "high")) return .high;
        if (std.ascii.eqlIgnoreCase(text, "xhigh")) return .xhigh;
        if (std.ascii.eqlIgnoreCase(text, "max") or std.ascii.eqlIgnoreCase(text, "ultra")) return .max;
        return null;
    }

    fn parsePageOrder(value: ?std.json.Value) !runtime_module.PageOrder {
        const field = value orelse return .ascending;
        if (field != .string) return error.InvalidPageOrder;
        if (std.mem.eql(u8, field.string, "asc")) return .ascending;
        if (std.mem.eql(u8, field.string, "desc")) return .descending;
        return error.InvalidPageOrder;
    }

    fn resolveRuntimeSelectionValues(self: *Server, session_id: []const u8, model_id: []const u8, requested_thinking: ?[]const u8) !runtime_module.ModelSelection {
        const current = self.runtime.selection(session_id) catch |err| switch (err) {
            error.SessionNotFound => null,
            else => return err,
        };
        const selected = if (current) |value|
            if (std.mem.eql(u8, value.model, model_id))
                value
            else replacement: {
                runtime_module.deinitSelection(self.allocator, value);
                break :replacement try self.runtime.selectionForModel(model_id, null);
            }
        else
            try self.runtime.selectionForModel(model_id, null);
        errdefer runtime_module.deinitSelection(self.allocator, selected);
        if (requested_thinking) |text| {
            if (selected.reasoning == null) return error.InvalidThinking;
            const requested = parseThinking(text) orelse return error.InvalidThinking;
            if (requested != selected.reasoning.?) return error.InvalidThinking;
        }
        return selected;
    }

    fn applyRuntimeSelectionValues(self: *Server, session_id: []const u8, model_id: []const u8, requested_thinking: ?[]const u8) !void {
        const selected = try self.resolveRuntimeSelectionValues(session_id, model_id, requested_thinking);
        defer runtime_module.deinitSelection(self.allocator, selected);
        try self.runtime.setSelection(session_id, selected);
    }

    fn syncRuntimeThinking(self: *Server, session_id: []const u8) !void {
        const selected = try self.runtime.selection(session_id);
        defer runtime_module.deinitSelection(self.allocator, selected);
        self.current_thinking = if (selected.reasoning) |level| @tagName(level) else null;
    }

    fn handleSessionPrompt(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        var prompt_text: []const u8 = "";
        var input_id: ?[]const u8 = null;
        if (params) |p| {
            if (p == .object) {
                if (p.object.get("prompt")) |pr| {
                    if (pr == .string) prompt_text = pr.string;
                } else if (p.object.get("message")) |m| {
                    if (m == .string) prompt_text = m.string;
                }
                if (p.object.get("input_id")) |field| {
                    if (field != .string or field.string.len == 0) {
                        return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "input_id must be a non-empty string");
                    }
                    input_id = field.string;
                }
            }
        }
        if (prompt_text.len == 0) {
            return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing 'prompt' or 'message' parameter");
        }

        var bridge = EventBridge{ .allocator = self.allocator };
        defer bridge.deinit();
        const run_id = self.runtime.startPrompt(.{
            .session_id = self.session_id,
            .prompt = prompt_text,
            .input_id = input_id,
            .event_context = &bridge,
            .emit = runtimeEvent,
        }) catch |err| {
            const code: i32 = switch (err) {
                error.InvalidInputMessageId, error.MessageIdAlreadyExists => ErrorCode.invalid_params,
                else => ErrorCode.runtime_error,
            };
            return self.writeErrorResponse(output, id, code, @errorName(err));
        };
        const settlement = self.runtime.wait(run_id) catch |err| {
            return self.writeErrorResponse(output, id, ErrorCode.runtime_error, @errorName(err));
        };
        try self.writeSuccessResponse(output, id, .{
            .session_id = self.session_id,
            .status = settlementStatus(settlement.stop_reason),
            .stop_reason = @tagName(settlement.stop_reason),
            .failure = @tagName(settlement.failure),
            .provider_failure = settlement.provider_failure,
            .steps = settlement.step_count,
            .content = bridge.content.items,
            .input_message_id = bridge.input_message_id.items,
            .assistant_message_id = bridge.assistant_message_id.items,
        });
    }

    fn handleSessionAbort(self: *Server, id: std.json.Value, _: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        return self.writeErrorResponse(output, id, ErrorCode.unsupported, "Abort requires concurrent RPC transport");
    }

    fn handleSessionSwitch(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing session switch parameters");
        if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Session switch parameters must be an object");
        const field = value.object.get("session_id") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing session_id");
        if (field != .string or field.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "session_id must be a non-empty string");

        const selected = self.runtime.selection(field.string) catch |err| switch (err) {
            error.SessionNotFound => return self.writeErrorResponse(output, id, ErrorCode.runtime_error, "SessionNotFound"),
            else => return self.writeErrorResponse(output, id, ErrorCode.runtime_error, @errorName(err)),
        };
        defer runtime_module.deinitSelection(self.allocator, selected);
        var next_session_id: ?[]u8 = try self.allocator.dupe(u8, field.string);
        defer if (next_session_id) |owned_session_id| self.allocator.free(owned_session_id);
        var next_model: ?[]u8 = try self.allocator.dupe(u8, selected.model);
        defer if (next_model) |owned_model| self.allocator.free(owned_model);
        const response = self.allocSuccessResponse(id, .{
            .session_id = next_session_id.?,
            .cwd = self.cwd,
            .model = next_model.?,
            .status = "active",
        }) catch |err| switch (err) {
            error.RpcResultTooLarge => return self.writeErrorResponse(output, id, ErrorCode.record_too_large, "RPC result exceeds the negotiated record limit"),
            else => return err,
        };
        defer if (response) |record| self.allocator.free(record);
        if (response) |record| try output.ensureUnusedCapacity(self.allocator, record.len);
        self.allocator.free(self.session_id);
        self.session_id = next_session_id.?;
        next_session_id = null;
        self.allocator.free(self.current_model);
        self.current_model = next_model.?;
        next_model = null;
        self.current_thinking = if (selected.reasoning) |level| @tagName(level) else null;
        if (response) |record| output.appendSliceAssumeCapacity(record);
    }

    fn handleSessionNew(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        var fresh_id: ?[]u8 = null;
        var next_cwd: ?[]u8 = null;
        var next_model: ?[]u8 = null;
        defer if (fresh_id) |value| self.allocator.free(value);
        defer if (next_cwd) |value| self.allocator.free(value);
        defer if (next_model) |value| self.allocator.free(value);
        if (params) |p| {
            if (p != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Session new parameters must be an object");
            if (p.object.get("cwd")) |cwd| {
                if (cwd != .string or cwd.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "cwd must be a non-empty string");
                next_cwd = try self.allocator.dupe(u8, cwd.string);
            }
            if (p.object.get("model")) |model| {
                if (model != .string or model.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "model must be a non-empty string");
                next_model = try self.allocator.dupe(u8, model.string);
            }
        }
        if (next_cwd) |cwd| if (!std.mem.eql(u8, cwd, self.runtime.workspace_root)) {
            return self.writeErrorResponse(output, id, ErrorCode.unsupported, "Changing cwd is not implemented by the shared runtime");
        };
        const selected_model = next_model orelse self.current_model;
        const selected = self.runtime.selectionForModel(selected_model, null) catch |err| switch (err) {
            error.ModelNotConfigured => return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Model is not configured for the shared runtime"),
            else => return self.writeErrorResponse(output, id, ErrorCode.runtime_error, @errorName(err)),
        };
        defer runtime_module.deinitSelection(self.allocator, selected);
        fresh_id = self.runtime.allocateSessionId(self.allocator) catch |err|
            return self.writeErrorResponse(output, id, ErrorCode.runtime_error, @errorName(err));
        const result_cwd = next_cwd orelse self.cwd;
        const result_model = next_model orelse self.current_model;
        const response = self.allocSuccessResponse(id, .{
            .session_id = fresh_id.?,
            .cwd = result_cwd,
            .model = result_model,
            .status = "active",
        }) catch |err| switch (err) {
            error.RpcResultTooLarge => return self.writeErrorResponse(output, id, ErrorCode.record_too_large, "RPC result exceeds the negotiated record limit"),
            else => return err,
        };
        defer if (response) |record| self.allocator.free(record);
        if (response) |record| try output.ensureUnusedCapacity(self.allocator, record.len);
        self.runtime.createSessionWithId(fresh_id.?, selected) catch |err|
            return self.writeErrorResponse(output, id, ErrorCode.runtime_error, @errorName(err));
        self.allocator.free(self.session_id);
        self.session_id = fresh_id.?;
        fresh_id = null;
        if (next_cwd) |value| {
            self.allocator.free(self.cwd);
            self.cwd = value;
            next_cwd = null;
        }
        if (next_model) |value| {
            self.allocator.free(self.current_model);
            self.current_model = value;
            next_model = null;
        }
        self.current_thinking = if (selected.reasoning) |level| @tagName(level) else null;
        if (response) |record| output.appendSliceAssumeCapacity(record);
    }

    fn handleSessionImport(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing session import parameters");
        if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Session import parameters must be an object");
        const session_field = value.object.get("session_id") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing session_id");
        if (session_field != .string or session_field.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "session_id must be a non-empty string");
        const messages_field = value.object.get("messages") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing messages");
        if (messages_field != .array or messages_field.array.items.len > runtime_module.maximum_session_history_items) {
            return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "messages must be a bounded array");
        }
        const model = if (value.object.get("model")) |field| blk: {
            if (field != .string or field.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "model must be a non-empty string");
            break :blk field.string;
        } else self.current_model;
        const selected = self.runtime.selectionForModel(model, null) catch |err| switch (err) {
            error.ModelNotConfigured => return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Model is not configured for the shared runtime"),
            else => return self.writeErrorResponse(output, id, ErrorCode.runtime_error, @errorName(err)),
        };
        defer runtime_module.deinitSelection(self.allocator, selected);

        var arena = std.heap.ArenaAllocator.init(self.allocator);
        defer arena.deinit();
        const messages = arena.allocator().alloc(protocol.Message, messages_field.array.items.len) catch
            return self.writeErrorResponse(output, id, ErrorCode.runtime_error, "OutOfMemory");
        for (messages_field.array.items, 0..) |message_value, index| {
            messages[index] = std.json.parseFromValueLeaky(protocol.Message, arena.allocator(), message_value, .{}) catch
                return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Invalid imported message");
        }
        const imported = self.runtime.importSession(session_field.string, selected, messages) catch |err| switch (err) {
            error.InvalidSessionId, error.InvalidSessionHistoryLimit, error.InvalidConversation, error.MessageTooLarge => return self.writeErrorResponse(output, id, ErrorCode.invalid_params, @errorName(err)),
            else => return self.writeErrorResponse(output, id, ErrorCode.runtime_error, @errorName(err)),
        };
        try self.writeSuccessResponse(output, id, .{
            .session_id = session_field.string,
            .imported = imported,
            .message_count = if (imported) messages.len else 0,
        });
    }

    fn handleSessionList(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        var after: ?[]const u8 = null;
        var before: ?[]const u8 = null;
        var limit: usize = 50;
        var order: runtime_module.PageOrder = .ascending;
        if (params) |value| {
            if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Session list parameters must be an object");
            order = parsePageOrder(value.object.get("order")) catch
                return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "order must be 'asc' or 'desc'");
            if (value.object.get("after")) |field| {
                if (field != .string or field.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "after must be a non-empty session identifier");
                after = field.string;
            }
            if (value.object.get("before_session_id")) |field| {
                if (field != .string or field.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "before_session_id must be a non-empty session identifier");
                before = field.string;
            }
            if (value.object.get("limit")) |field| {
                if (field != .integer or field.integer <= 0 or field.integer > runtime_module.maximum_session_list_items) {
                    return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "limit exceeds the session list bound");
                }
                limit = @intCast(field.integer);
            }
        }
        if ((order == .ascending and before != null) or (order == .descending and after != null)) {
            return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Cursor does not match the requested session order");
        }
        const cursor = if (order == .ascending) after else before;
        var listed = self.runtime.listSessionsOrdered(self.allocator, cursor, limit, order) catch |err| switch (err) {
            error.InvalidSessionId, error.InvalidSessionListLimit => return self.writeErrorResponse(output, id, ErrorCode.invalid_params, @errorName(err)),
            else => return self.writeErrorResponse(output, id, ErrorCode.runtime_error, @errorName(err)),
        };
        defer listed.deinit();
        const next_cursor: ?[]const u8 = if (listed.sessions.len != 0) listed.sessions[listed.sessions.len - 1].session_id else null;
        try self.writeSuccessResponse(output, id, .{
            .sessions = listed.sessions,
            .has_more = listed.has_more,
            .next_after = if (order == .ascending) next_cursor else null,
            .next_before = if (order == .descending) next_cursor else null,
        });
    }

    fn handleSessionHistory(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        const value = params orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing session history parameters");
        if (value != .object) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Session history parameters must be an object");
        const session_field = value.object.get("session_id") orelse return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing session_id");
        if (session_field != .string or session_field.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "session_id must be a non-empty string");
        var after_message_id: ?[]const u8 = null;
        var before_message_id: ?[]const u8 = null;
        var limit: usize = 50;
        const order = parsePageOrder(value.object.get("order")) catch
            return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "order must be 'asc' or 'desc'");
        if (value.object.get("after_message_id")) |field| {
            if (field != .string or field.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "after_message_id must be a non-empty message identifier");
            after_message_id = field.string;
        }
        if (value.object.get("before_message_id")) |field| {
            if (field != .string or field.string.len == 0) return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "before_message_id must be a non-empty message identifier");
            before_message_id = field.string;
        }
        if (value.object.get("limit")) |field| {
            if (field != .integer or field.integer <= 0 or field.integer > runtime_module.maximum_session_history_items) {
                return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "limit exceeds the session history bound");
            }
            limit = @intCast(field.integer);
        }
        if ((order == .ascending and before_message_id != null) or (order == .descending and after_message_id != null)) {
            return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Cursor does not match the requested history order");
        }

        const cursor = if (order == .ascending) after_message_id else before_message_id;
        var history = self.runtime.sessionHistoryOrdered(session_field.string, cursor, limit, self.allocator, order) catch |err| switch (err) {
            error.SessionNotFound => return self.writeErrorResponse(output, id, ErrorCode.runtime_error, "SessionNotFound"),
            error.UnknownMessageCursor, error.InvalidSessionHistoryLimit => return self.writeErrorResponse(output, id, ErrorCode.invalid_params, @errorName(err)),
            error.HistoryMessageTooLarge => return self.writeErrorResponse(output, id, ErrorCode.record_too_large, "Durable message exceeds the session history payload-byte limit"),
            else => return self.writeErrorResponse(output, id, ErrorCode.runtime_error, @errorName(err)),
        };
        defer history.deinit();
        const HistoryItem = struct {
            id: []const u8,
            branch_id: []const u8,
            parent_id: ?[]const u8,
            message: protocol.Message,
        };
        const items = try self.allocator.alloc(HistoryItem, history.page.messages.len);
        defer self.allocator.free(items);
        for (history.page.messages, history.decoded.messages, 0..) |durable, decoded, index| items[index] = .{
            .id = durable.id,
            .branch_id = durable.branch_id,
            .parent_id = durable.parent_id,
            .message = decoded,
        };
        const next_cursor: ?[]const u8 = if (history.page.messages.len != 0) history.page.messages[history.page.messages.len - 1].id else null;
        try self.writeSuccessResponse(output, id, .{
            .session_id = history.session_id,
            .branch_id = history.branch_id,
            .messages = items,
            .has_more = history.page.has_more,
            .next_after = if (order == .ascending) next_cursor else null,
            .next_before = if (order == .descending) next_cursor else null,
        });
    }

    fn handleModelList(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        if (params) |value| if (value == .object and value.object.count() != 0) {
            return self.writeErrorResponse(output, id, ErrorCode.unsupported, "Dynamic model catalogs are not implemented by the shared runtime");
        };
        const profiles = self.runtime.profiles();
        const models = try self.allocator.alloc(ModelCatalogItem, profiles.len);
        defer self.allocator.free(models);
        for (profiles, 0..) |profile, index| models[index] = .{
            .id = profile.id,
            .name = profile.name,
            .provider = profile.provider,
            .context_window = profile.context_window,
            .max_tokens = profile.max_output_tokens,
            .reasoning = profile.reasoning != null,
            .configured_reasoning = profile.reasoning,
            .cache_retention = profile.cache_retention,
            .cache_aggressiveness = profile.cache_aggressiveness,
            .transport = profile.transport,
        };
        try self.writeSuccessResponse(output, id, .{ .models = models, .current = self.current_model, .thinking = self.current_thinking, .source = "runtime-profile-registry" });
    }

    fn handleModelSet(self: *Server, id: std.json.Value, params: ?std.json.Value, output: *std.ArrayList(u8)) !void {
        var model_name: ?[]const u8 = null;
        var requested_thinking: ?[]const u8 = null;
        if (params) |p| {
            if (p == .object) {
                if (p.object.get("model")) |m| if (m == .string) {
                    model_name = m.string;
                };
                if (model_name == null) {
                    if (p.object.get("model_id")) |m| if (m == .string) {
                        model_name = m.string;
                    };
                }
                if (p.object.get("thinking")) |thinking| if (thinking == .string) {
                    requested_thinking = thinking.string;
                };
            }
        }
        if (model_name == null or model_name.?.len == 0) {
            return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Missing 'model' or 'model_id'");
        }

        var next_model: ?[]u8 = try self.allocator.dupe(u8, model_name.?);
        defer if (next_model) |value| self.allocator.free(value);
        self.applyRuntimeSelectionValues(self.session_id, next_model.?, requested_thinking) catch |err| switch (err) {
            error.ModelNotConfigured => return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Model is not configured for the shared runtime"),
            error.InvalidThinking => return self.writeErrorResponse(output, id, ErrorCode.invalid_params, "Thinking level is not configured for the shared runtime"),
            else => return self.writeErrorResponse(output, id, ErrorCode.runtime_error, @errorName(err)),
        };
        self.allocator.free(self.current_model);
        self.current_model = next_model.?;
        next_model = null;
        try self.syncRuntimeThinking(self.session_id);

        const result = struct {
            model: []const u8,
            thinking: ?[]const u8,
            thinking_reset: bool,
            updated: bool,
        }{
            .model = self.current_model,
            .thinking = self.current_thinking,
            .thinking_reset = false,
            .updated = true,
        };
        try self.writeSuccessResponse(output, id, result);
    }

    // --- Stdio Runner ---

    pub fn runStdio(self: *Server, io: std.Io) !void {
        const stdin = std.Io.File.stdin();
        var stdout = FileOutputContext{ .io = io, .file = std.Io.File.stdout() };
        var output = RecordOutput{ .io = io, .context = &stdout, .write = writeFileRecord };
        defer self.finishConcurrent(true) catch {};

        var read_buf: [8192]u8 = undefined;
        var record = RecordBuffer{};
        defer record.deinit(self.allocator);

        while (true) {
            const count = std.posix.read(stdin.handle, &read_buf) catch |err| switch (err) {
                error.WouldBlock => continue,
                else => break,
            };
            if (count == 0) break;

            var offset: usize = 0;
            while (offset < count) {
                if (std.mem.indexOfScalar(u8, read_buf[offset..count], '\n')) |nl_idx| {
                    const append_state = try record.append(self.allocator, read_buf[offset .. offset + nl_idx]);
                    offset += nl_idx + 1;
                    switch (append_state) {
                        .accepted => try self.handleConcurrentLine(record.bytes.items, &output),
                        .oversized => try self.writeRecordTooLarge(&output),
                        .discarding => {},
                    }
                    record.reset();
                } else {
                    if (try record.append(self.allocator, read_buf[offset..count]) == .oversized) {
                        try self.writeRecordTooLarge(&output);
                    }
                    break;
                }
            }
        }

        if (!record.discarding and record.bytes.items.len != 0) try self.handleConcurrentLine(record.bytes.items, &output);
        try self.finishConcurrent(true);
    }

    fn writeRecordTooLarge(self: *Server, output: *RecordOutput) !void {
        var buffered: std.ArrayList(u8) = .empty;
        defer buffered.deinit(self.allocator);
        try self.writeErrorResponse(&buffered, null, ErrorCode.record_too_large, "RPC record exceeds the negotiated limit");
        try output.writeAtomic(buffered.items);
    }

    pub fn handleConcurrentLine(self: *Server, line: []const u8, output: *RecordOutput) !void {
        try self.reapFinishedPrompt();
        try self.reapFinishedStdioSystemUseWait();
        const trimmed = std.mem.trim(u8, line, " \t\r\n");
        if (trimmed.len == 0) return;
        var buffered: std.ArrayList(u8) = .empty;
        defer buffered.deinit(self.allocator);
        if (trimmed.len > max_record_bytes) {
            try self.writeErrorResponse(&buffered, null, ErrorCode.record_too_large, "RPC record exceeds the negotiated limit");
            return output.writeAtomic(buffered.items);
        }
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, trimmed, .{}) catch {
            try self.writeErrorResponse(&buffered, null, ErrorCode.parse_error, "Parse error");
            return output.writeAtomic(buffered.items);
        };
        defer parsed.deinit();
        const request = (try self.validateParsedRequest(parsed.value, &buffered)) orelse
            return output.writeAtomic(buffered.items);

        if (!self.handshake_complete and !std.mem.eql(u8, request.method, "rpc/handshake")) {
            try self.dispatch(request.method, request.id, request.params, &buffered);
            return output.writeAtomic(buffered.items);
        }

        if (std.mem.eql(u8, request.method, "session/abort")) {
            return self.abortConcurrentPrompt(request.id, output);
        }
        if (std.mem.eql(u8, request.method, "system_use/cancel")) {
            try self.dispatch(request.method, request.id, request.params, &buffered);
            return output.writeAtomic(buffered.items);
        }
        if (self.isPromptRunning() or self.isSystemUseWaitRunning()) {
            try self.writeErrorResponse(&buffered, request.id, ErrorCode.runtime_error, "A long-running RPC operation is already active");
            return output.writeAtomic(buffered.items);
        }
        if (std.mem.eql(u8, request.method, "session/prompt")) {
            self.startConcurrentPrompt(trimmed, output) catch |err| {
                try self.writeErrorResponse(&buffered, request.id, ErrorCode.runtime_error, @errorName(err));
                return output.writeAtomic(buffered.items);
            };
            return;
        }
        if (std.mem.eql(u8, request.method, "system_use/wait")) {
            self.startConcurrentSystemUseWait(trimmed, request.params, output) catch |err| {
                try self.writeErrorResponse(&buffered, request.id, ErrorCode.runtime_error, @errorName(err));
                return output.writeAtomic(buffered.items);
            };
            return;
        }
        try self.dispatch(request.method, request.id, request.params, &buffered);
        if (buffered.items.len != 0) try output.writeAtomic(buffered.items);
    }

    fn startConcurrentPrompt(self: *Server, line: []const u8, output: *RecordOutput) !void {
        try self.reapFinishedPrompt();
        const owned_line = try self.allocator.dupe(u8, line);
        errdefer self.allocator.free(owned_line);
        const task = try self.allocator.create(ConcurrentPromptTask);
        errdefer self.allocator.destroy(task);
        task.* = .{ .server = self, .output = output, .line = owned_line };

        self.state_mutex.lockUncancelable(self.runtime.io);
        defer self.state_mutex.unlock(self.runtime.io);
        if (self.prompt_running or self.prompt_future != null) return error.PromptAlreadyActive;
        self.prompt_running = true;
        self.prompt_cancellable = true;
        self.shutdown_requested = false;
        self.active_run_id = null;
        self.prompt_future = std.Io.concurrent(self.runtime.io, runConcurrentPrompt, .{task}) catch |err| {
            self.prompt_running = false;
            self.prompt_cancellable = false;
            return err;
        };
    }

    fn abortConcurrentPrompt(self: *Server, id: std.json.Value, output: *RecordOutput) !void {
        self.state_mutex.lockUncancelable(self.runtime.io);
        while (self.prompt_running and self.prompt_cancellable and self.active_run_id == null) {
            self.state_condition.waitUncancelable(self.runtime.io, &self.state_mutex);
        }
        const running = self.prompt_running;
        const cancellable = self.prompt_cancellable;
        const run_id = self.active_run_id;
        self.state_mutex.unlock(self.runtime.io);
        if (!running or !cancellable or run_id == null) {
            var buffered: std.ArrayList(u8) = .empty;
            defer buffered.deinit(self.allocator);
            try self.writeErrorResponse(&buffered, id, ErrorCode.unsupported, "No active prompt can be aborted");
            return output.writeAtomic(buffered.items);
        }
        const settlement = self.runtime.cancel(run_id.?) catch |err| {
            var buffered: std.ArrayList(u8) = .empty;
            defer buffered.deinit(self.allocator);
            try self.writeErrorResponse(&buffered, id, ErrorCode.runtime_error, @errorName(err));
            return output.writeAtomic(buffered.items);
        };
        var buffered: std.ArrayList(u8) = .empty;
        defer buffered.deinit(self.allocator);
        try self.writeSuccessResponse(&buffered, id, .{
            .session_id = settlement.session_id,
            .status = settlementStatus(settlement.stop_reason),
            .stop_reason = @tagName(settlement.stop_reason),
        });
        try output.writeAtomic(buffered.items);
    }

    fn isPromptRunning(self: *Server) bool {
        self.state_mutex.lockUncancelable(self.runtime.io);
        defer self.state_mutex.unlock(self.runtime.io);
        return self.prompt_running;
    }

    fn isSystemUseWaitRunning(self: *Server) bool {
        self.state_mutex.lockUncancelable(self.runtime.io);
        defer self.state_mutex.unlock(self.runtime.io);
        return self.system_use_wait_running;
    }

    fn startConcurrentSystemUseWait(self: *Server, line: []const u8, params: ?std.json.Value, output: *RecordOutput) !void {
        try self.reapFinishedStdioSystemUseWait();
        const value = params orelse return error.InvalidSystemUseParameters;
        if (value != .object) return error.InvalidSystemUseParameters;
        const wait = value.object.get("wait_id") orelse return error.SystemUseWaitIdRequired;
        if (wait != .string or !std.mem.startsWith(u8, wait.string, "wait-")) return error.InvalidSystemUseParameters;
        const wait_id = try std.fmt.parseUnsigned(u64, wait.string[5..], 10);
        if (wait_id == 0) return error.InvalidSystemUseParameters;
        const owned_line = try self.allocator.dupe(u8, line);
        errdefer self.allocator.free(owned_line);
        const task = try self.allocator.create(ConcurrentSystemUseWaitTask);
        errdefer self.allocator.destroy(task);
        task.* = .{ .server = self, .output = output, .line = owned_line };
        self.state_mutex.lockUncancelable(self.runtime.io);
        defer self.state_mutex.unlock(self.runtime.io);
        if (self.system_use_wait_running or self.system_use_wait_future != null) return error.SystemUseWaitBusy;
        self.system_use_wait_running = true;
        self.system_use_wait_id = wait_id;
        self.system_use_wait_cancel_requested.store(false, .release);
        self.system_use_wait_runtime_installed.store(false, .release);
        self.system_use_wait_future = std.Io.concurrent(self.runtime.io, runConcurrentSystemUseWait, .{task}) catch |err| {
            self.system_use_wait_running = false;
            self.system_use_wait_id = null;
            return err;
        };
    }

    fn reapFinishedStdioSystemUseWait(self: *Server) !void {
        self.state_mutex.lockUncancelable(self.runtime.io);
        if (self.system_use_wait_running or self.system_use_wait_future == null) {
            self.state_mutex.unlock(self.runtime.io);
            return;
        }
        self.state_mutex.unlock(self.runtime.io);
        const result = if (self.system_use_wait_future) |*future| future.await(self.runtime.io) else {};
        self.state_mutex.lockUncancelable(self.runtime.io);
        self.system_use_wait_future = null;
        self.system_use_wait_id = null;
        self.state_mutex.unlock(self.runtime.io);
        try result;
    }

    fn finishStdioSystemUseWait(self: *Server, cancel_active: bool) !void {
        self.state_mutex.lockUncancelable(self.runtime.io);
        const wait_id = if (cancel_active and self.system_use_wait_running) self.system_use_wait_id else null;
        self.state_mutex.unlock(self.runtime.io);
        if (wait_id) |active| self.runtime.cancelSystemUseWait(active) catch {};
        const result = if (self.system_use_wait_future) |*future| future.await(self.runtime.io) else {};
        self.state_mutex.lockUncancelable(self.runtime.io);
        self.system_use_wait_future = null;
        self.system_use_wait_running = false;
        self.system_use_wait_id = null;
        self.state_mutex.unlock(self.runtime.io);
        try result;
    }

    fn reapFinishedPrompt(self: *Server) !void {
        self.state_mutex.lockUncancelable(self.runtime.io);
        if (self.prompt_running or self.prompt_future == null) {
            self.state_mutex.unlock(self.runtime.io);
            return;
        }
        self.state_mutex.unlock(self.runtime.io);
        const result = if (self.prompt_future) |*future| future.await(self.runtime.io) else {};
        self.state_mutex.lockUncancelable(self.runtime.io);
        self.prompt_future = null;
        self.state_mutex.unlock(self.runtime.io);
        try result;
    }

    fn finishStdioPrompt(self: *Server, cancel_active: bool) !void {
        self.state_mutex.lockUncancelable(self.runtime.io);
        if (cancel_active and self.prompt_running) self.shutdown_requested = true;
        const run_id = if (cancel_active and self.prompt_running) self.active_run_id else null;
        self.state_mutex.unlock(self.runtime.io);
        if (run_id) |active| _ = self.runtime.cancel(active) catch {};

        const result = if (self.prompt_future) |*future| future.await(self.runtime.io) else {};
        self.state_mutex.lockUncancelable(self.runtime.io);
        self.prompt_future = null;
        self.prompt_running = false;
        self.prompt_cancellable = false;
        self.active_run_id = null;
        self.shutdown_requested = false;
        self.state_condition.broadcast(self.runtime.io);
        self.state_mutex.unlock(self.runtime.io);
        try result;
    }

    pub fn finishConcurrent(self: *Server, cancel_active: bool) !void {
        try self.finishStdioSystemUseWait(cancel_active);
        try self.finishStdioPrompt(cancel_active);
    }
};

const RecordBuffer = struct {
    const AppendState = enum { accepted, oversized, discarding };

    bytes: std.ArrayList(u8) = .empty,
    limit: usize = max_record_bytes,
    discarding: bool = false,

    fn deinit(self: *RecordBuffer, allocator: std.mem.Allocator) void {
        self.bytes.deinit(allocator);
    }

    fn append(self: *RecordBuffer, allocator: std.mem.Allocator, fragment: []const u8) !AppendState {
        if (self.discarding) return .discarding;
        if (self.bytes.items.len > self.limit or fragment.len > self.limit - self.bytes.items.len) {
            self.bytes.clearRetainingCapacity();
            self.discarding = true;
            return .oversized;
        }
        try self.bytes.appendSlice(allocator, fragment);
        return .accepted;
    }

    fn reset(self: *RecordBuffer) void {
        self.bytes.clearRetainingCapacity();
        self.discarding = false;
    }
};

pub const RecordOutput = struct {
    io: std.Io,
    context: *anyopaque,
    write: *const fn (*anyopaque, []const u8) anyerror!void,
    mutex: std.Io.Mutex = std.Io.Mutex.init,

    pub fn writeAtomic(self: *RecordOutput, bytes: []const u8) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        try self.writeLocked(bytes);
    }

    pub fn writeLocked(self: *RecordOutput, bytes: []const u8) !void {
        try self.write(self.context, bytes);
    }
};

const FileOutputContext = struct {
    io: std.Io,
    file: std.Io.File,
};

fn writeFileRecord(raw: *anyopaque, bytes: []const u8) !void {
    const output: *FileOutputContext = @ptrCast(@alignCast(raw));
    try output.file.writeStreamingAll(output.io, bytes);
}

const ConcurrentPromptTask = struct {
    server: *Server,
    output: *RecordOutput,
    line: []u8,
};

const ConcurrentSystemUseWaitTask = struct {
    server: *Server,
    output: *RecordOutput,
    line: []u8,
};

fn systemUseRpcContext(request_id: []const u8, session_id: []const u8) runtime_module.SystemUseRequestContext {
    return .{
        .source_request_id = request_id,
        .causal_request_id = request_id,
        .run_id = request_id,
        .session_id = session_id,
        .actor_id = "local-user",
        .provenance = "rpc:local-user",
    };
}

fn systemUseRequestId(allocator: std.mem.Allocator, id: std.json.Value) ![]u8 {
    const encoded = try std.json.Stringify.valueAlloc(allocator, id, .{});
    defer allocator.free(encoded);
    if (encoded.len <= 200) return std.fmt.allocPrint(allocator, "rpc:{s}", .{encoded});
    var digest: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash(encoded, &digest, .{});
    const hex = std.fmt.bytesToHex(digest, .lower);
    return std.fmt.allocPrint(allocator, "rpc:sha256:{s}", .{&hex});
}

const ConcurrentEventBridge = struct {
    server: *Server,
    output: *RecordOutput,
    content: std.ArrayList(u8) = .empty,
    input_message_id: std.ArrayList(u8) = .empty,
    assistant_message_id: std.ArrayList(u8) = .empty,
    emitted_text: bool = false,

    fn deinit(self: *ConcurrentEventBridge) void {
        self.content.deinit(self.server.allocator);
        self.input_message_id.deinit(self.server.allocator);
        self.assistant_message_id.deinit(self.server.allocator);
    }
};

fn runConcurrentPrompt(task: *ConcurrentPromptTask) anyerror!void {
    const self = task.server;
    defer {
        self.allocator.free(task.line);
        self.allocator.destroy(task);
    }
    defer {
        self.state_mutex.lockUncancelable(self.runtime.io);
        self.prompt_running = false;
        self.prompt_cancellable = false;
        self.active_run_id = null;
        self.state_condition.broadcast(self.runtime.io);
        self.state_mutex.unlock(self.runtime.io);
    }

    var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, task.line, .{}) catch return error.InvalidPromptRequest;
    defer parsed.deinit();
    const id = parsed.value.object.get("id") orelse .null;
    const params = parsed.value.object.get("params");
    var prompt: []const u8 = "";
    var input_id: ?[]const u8 = null;
    if (params) |value| if (value == .object) {
        if (value.object.get("prompt")) |field| {
            if (field == .string) prompt = field.string;
        }
        if (prompt.len == 0) if (value.object.get("message")) |field| {
            if (field == .string) prompt = field.string;
        };
        if (value.object.get("input_id")) |field| {
            if (field != .string or field.string.len == 0) {
                var buffered: std.ArrayList(u8) = .empty;
                defer buffered.deinit(self.allocator);
                try self.writeErrorResponse(&buffered, id, ErrorCode.invalid_params, "input_id must be a non-empty string");
                return task.output.writeAtomic(buffered.items);
            }
            input_id = field.string;
        }
    };
    if (prompt.len == 0) {
        var buffered: std.ArrayList(u8) = .empty;
        defer buffered.deinit(self.allocator);
        try self.writeErrorResponse(&buffered, id, ErrorCode.invalid_params, "Missing 'prompt' or 'message' parameter");
        return task.output.writeAtomic(buffered.items);
    }

    var bridge = ConcurrentEventBridge{ .server = self, .output = task.output };
    defer bridge.deinit();
    const run_id = self.runtime.startPrompt(.{
        .session_id = self.session_id,
        .prompt = prompt,
        .input_id = input_id,
        .event_context = &bridge,
        .emit = concurrentRuntimeEvent,
    }) catch |err| {
        var buffered: std.ArrayList(u8) = .empty;
        defer buffered.deinit(self.allocator);
        const code: i32 = switch (err) {
            error.InvalidInputMessageId, error.MessageIdAlreadyExists => ErrorCode.invalid_params,
            else => ErrorCode.runtime_error,
        };
        try self.writeErrorResponse(&buffered, id, code, @errorName(err));
        return task.output.writeAtomic(buffered.items);
    };
    self.state_mutex.lockUncancelable(self.runtime.io);
    if (self.prompt_cancellable) self.active_run_id = run_id;
    const shutdown_requested = self.shutdown_requested;
    self.state_condition.broadcast(self.runtime.io);
    self.state_mutex.unlock(self.runtime.io);
    if (shutdown_requested) _ = self.runtime.cancel(run_id) catch {};

    const settlement = self.runtime.wait(run_id) catch |err| {
        var buffered: std.ArrayList(u8) = .empty;
        defer buffered.deinit(self.allocator);
        try self.writeErrorResponse(&buffered, id, ErrorCode.runtime_error, @errorName(err));
        return task.output.writeAtomic(buffered.items);
    };
    var buffered: std.ArrayList(u8) = .empty;
    defer buffered.deinit(self.allocator);
    try self.writeSuccessResponse(&buffered, id, .{
        .session_id = settlement.session_id,
        .status = settlementStatus(settlement.stop_reason),
        .stop_reason = @tagName(settlement.stop_reason),
        .failure = @tagName(settlement.failure),
        .provider_failure = settlement.provider_failure,
        .steps = settlement.step_count,
        .content = bridge.content.items,
        .input_message_id = bridge.input_message_id.items,
        .assistant_message_id = bridge.assistant_message_id.items,
    });
    try task.output.writeAtomic(buffered.items);
}

fn runConcurrentSystemUseWait(task: *ConcurrentSystemUseWaitTask) anyerror!void {
    const self = task.server;
    defer {
        self.allocator.free(task.line);
        self.allocator.destroy(task);
    }
    defer {
        self.state_mutex.lockUncancelable(self.runtime.io);
        self.system_use_wait_running = false;
        self.state_condition.broadcast(self.runtime.io);
        self.state_mutex.unlock(self.runtime.io);
    }
    var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, task.line, .{}) catch return error.InvalidSystemUseParameters;
    defer parsed.deinit();
    const id = parsed.value.object.get("id") orelse .null;
    const params = parsed.value.object.get("params");
    var buffered: std.ArrayList(u8) = .empty;
    defer buffered.deinit(self.allocator);
    try self.dispatch("system_use/wait", id, params, &buffered);
    if (buffered.items.len != 0) try task.output.writeAtomic(buffered.items);
}

fn concurrentRuntimeEvent(raw: *anyopaque, event: runtime_module.Event) !void {
    const bridge: *ConcurrentEventBridge = @ptrCast(@alignCast(raw));
    bridge.output.mutex.lockUncancelable(bridge.server.runtime.io);
    defer bridge.output.mutex.unlock(bridge.server.runtime.io);
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(bridge.server.allocator);
    switch (event.payload) {
        .run_started => |started| {
            try bridge.input_message_id.appendSlice(bridge.server.allocator, started.input_message_id);
            try bridge.assistant_message_id.appendSlice(bridge.server.allocator, started.assistant_message_id);
            bridge.server.state_mutex.lockUncancelable(bridge.server.runtime.io);
            if (bridge.server.prompt_cancellable) bridge.server.active_run_id = event.run_id;
            bridge.server.state_condition.broadcast(bridge.server.runtime.io);
            bridge.server.state_mutex.unlock(bridge.server.runtime.io);
            try bridge.server.emitAgentStart(
                &output,
                event.session_id,
                started.input_message_id,
                started.assistant_message_id,
                std.Io.Clock.real.now(bridge.server.runtime.io).toMilliseconds(),
            );
        },
        .provider_stream => |stream| switch (stream) {
            .text_delta => |delta| if (delta.delta.len != 0) {
                bridge.emitted_text = true;
                try bridge.content.appendSlice(bridge.server.allocator, delta.delta);
                try bridge.server.emitMessageDelta(&output, null, delta.delta);
            },
            .thinking_delta => |delta| if (delta.delta.len != 0) try bridge.server.emitMessageDelta(&output, delta.delta, null),
            .done => |message| if (!bridge.emitted_text) for (message.content) |part| switch (part) {
                .text => |content| if (content.text.len != 0) {
                    try bridge.content.appendSlice(bridge.server.allocator, content.text);
                    try bridge.server.emitMessageDelta(&output, null, content.text);
                },
                else => {},
            },
            else => {},
        },
        .settled => |settlement| {
            bridge.server.state_mutex.lockUncancelable(bridge.server.runtime.io);
            if (bridge.server.prompt_running) {
                bridge.server.active_run_id = null;
                bridge.server.prompt_cancellable = false;
                bridge.server.state_condition.broadcast(bridge.server.runtime.io);
            }
            bridge.server.state_mutex.unlock(bridge.server.runtime.io);
            try bridge.server.emitAgentEnd(&output, event.session_id, @tagName(settlement.stop_reason), std.Io.Clock.real.now(bridge.server.runtime.io).toMilliseconds());
        },
        .tool_started => |call| try bridge.server.emitNotification(&output, "tool_start", .{
            .id = call.id,
            .name = call.name,
            .input = call.arguments,
        }),
        .tool_finished => |result| try bridge.server.emitNotification(&output, "tool_end", .{
            .id = result.tool_call_id,
            .name = result.tool_name,
            .is_error = result.is_error,
        }),
        else => {},
    }
    if (output.items.len != 0) try bridge.output.writeLocked(output.items);
}

const EventBridge = struct {
    allocator: std.mem.Allocator,
    content: std.ArrayList(u8) = .empty,
    input_message_id: std.ArrayList(u8) = .empty,
    assistant_message_id: std.ArrayList(u8) = .empty,
    emitted_text: bool = false,

    fn deinit(self: *EventBridge) void {
        self.content.deinit(self.allocator);
        self.input_message_id.deinit(self.allocator);
        self.assistant_message_id.deinit(self.allocator);
    }
};

fn runtimeEvent(raw: *anyopaque, event: runtime_module.Event) !void {
    const bridge: *EventBridge = @ptrCast(@alignCast(raw));
    switch (event.payload) {
        .run_started => |started| {
            try bridge.input_message_id.appendSlice(bridge.allocator, started.input_message_id);
            try bridge.assistant_message_id.appendSlice(bridge.allocator, started.assistant_message_id);
        },
        .provider_stream => |stream| switch (stream) {
            .text_delta => |delta| {
                if (delta.delta.len != 0) {
                    bridge.emitted_text = true;
                    try bridge.content.appendSlice(bridge.allocator, delta.delta);
                }
            },
            .done => |message| if (!bridge.emitted_text) for (message.content) |part| switch (part) {
                .text => |content| try bridge.content.appendSlice(bridge.allocator, content.text),
                else => {},
            },
            else => {},
        },
        else => {},
    }
}

const RpcTestState = struct {
    environment: std.process.Environ.Map,
    seen_model: std.ArrayList(u8) = .empty,
    seen_prompt: std.ArrayList(u8) = .empty,
    seen_options: protocol.StreamOptions = .{},

    fn deinit(self: *RpcTestState, allocator: std.mem.Allocator) void {
        self.seen_model.deinit(allocator);
        self.seen_prompt.deinit(allocator);
        self.environment.deinit();
    }
};

fn completeTestHandshake(server: *Server, output: *std.ArrayList(u8)) !void {
    output.clearRetainingCapacity();
    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":\"test-handshake\",\"method\":\"rpc/handshake\",\"params\":{\"protocol_major\":1,\"protocol_minor\":0}}\n", output);
    if (std.mem.indexOf(u8, output.items, "\"result\"") == null) return error.TestHandshakeFailed;
    output.clearRetainingCapacity();
}

fn rpcTestStream(raw: *anyopaque, model: *const protocol.Model, context: *const protocol.Context, options: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    const state: *RpcTestState = @ptrCast(@alignCast(raw));
    try state.seen_model.appendSlice(std.testing.allocator, model.id);
    try state.seen_prompt.appendSlice(std.testing.allocator, context.messages[context.messages.len - 1].user.content[0].text.text);
    state.seen_options = options;
    const content = [_]protocol.AssistantContent{.{ .text = .{ .text = "rpc-real-response" } }};
    const message = protocol.AssistantMessage{
        .content = &content,
        .api = model.api,
        .provider = model.provider,
        .model = model.id,
        .stop_reason = .stop,
        .timestamp_ms = 1,
    };
    try sink(sink_context, .{ .text_delta = .{ .content_index = 0, .delta = "rpc-real-response" } });
    try sink(sink_context, .{ .done = message });
}

test "session new releases generated id when cwd duplication fails" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "test-model", .context_window = 1024, .output_limit = 128 },
    }, &state, rpcTestStream);
    defer shared.destroy();
    var parsed = try std.json.parseFromSlice(std.json.Value, allocator, "{\"cwd\":\"different\"}", .{});
    defer parsed.deinit();
    var failing = std.testing.FailingAllocator.init(allocator, .{});
    const failing_allocator = failing.allocator();
    {
        var server = try Server.init(failing_allocator, shared);
        defer server.deinit();
        var output: std.ArrayList(u8) = .empty;
        defer output.deinit(failing_allocator);

        failing.fail_index = failing.alloc_index + 1;
        try std.testing.expectError(error.OutOfMemory, server.handleSessionNew(.{ .integer = 1 }, parsed.value, &output));
    }
    try std.testing.expectEqual(failing.allocated_bytes, failing.freed_bytes);
}

test "session new does not commit before its response allocation succeeds" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "test-model", .context_window = 1024, .output_limit = 128 },
    }, &state, rpcTestStream);
    defer shared.destroy();
    var failing = std.testing.FailingAllocator.init(allocator, .{});
    const failing_allocator = failing.allocator();
    {
        var server = try Server.init(failing_allocator, shared);
        defer server.deinit();
        var output: std.ArrayList(u8) = .empty;
        defer output.deinit(failing_allocator);
        const before = shared.highWater();

        failing.fail_index = failing.alloc_index + 1;
        try std.testing.expectError(error.OutOfMemory, server.handleSessionNew(.{ .integer = 1 }, null, &output));
        try std.testing.expectEqual(before, shared.highWater());
    }
}

test "session start does not commit or swap state before its response allocation succeeds" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "test-model", .context_window = 1024, .output_limit = 128 },
    }, &state, rpcTestStream);
    defer shared.destroy();
    var parsed = try std.json.parseFromSlice(std.json.Value, allocator, "{\"session_id\":\"start-allocation-failure\"}", .{});
    defer parsed.deinit();
    var failing = std.testing.FailingAllocator.init(allocator, .{});
    const failing_allocator = failing.allocator();
    {
        var server = try Server.init(failing_allocator, shared);
        defer server.deinit();
        const original_session_id = try allocator.dupe(u8, server.session_id);
        defer allocator.free(original_session_id);
        var output: std.ArrayList(u8) = .empty;
        defer output.deinit(failing_allocator);
        const before = shared.highWater();

        failing.fail_index = failing.alloc_index + 1;
        try std.testing.expectError(error.OutOfMemory, server.handleSessionStart(.{ .integer = 1 }, parsed.value, &output));
        try std.testing.expectEqual(before, shared.highWater());
        try std.testing.expectEqualStrings(original_session_id, server.session_id);
        try std.testing.expectError(error.SessionNotFound, shared.selection("start-allocation-failure"));
    }
}

test "session switch does not swap adapter state before its response allocation succeeds" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const selection: runtime_module.ModelSelection = .{ .provider = "test", .model = "test-model", .context_window = 1024, .output_limit = 128 };
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = selection,
    }, &state, rpcTestStream);
    defer shared.destroy();
    try shared.createSessionWithId("switch-allocation-failure", selection);
    var parsed = try std.json.parseFromSlice(std.json.Value, allocator, "{\"session_id\":\"switch-allocation-failure\"}", .{});
    defer parsed.deinit();
    var failing = std.testing.FailingAllocator.init(allocator, .{});
    const failing_allocator = failing.allocator();
    {
        var server = try Server.init(failing_allocator, shared);
        defer server.deinit();
        const original_session_id = try allocator.dupe(u8, server.session_id);
        defer allocator.free(original_session_id);
        const original_model = try allocator.dupe(u8, server.current_model);
        defer allocator.free(original_model);
        var output: std.ArrayList(u8) = .empty;
        defer output.deinit(failing_allocator);

        failing.fail_index = failing.alloc_index + 2;
        try std.testing.expectError(error.OutOfMemory, server.handleSessionSwitch(.{ .integer = 1 }, parsed.value, &output));
        try std.testing.expectEqualStrings(original_session_id, server.session_id);
        try std.testing.expectEqualStrings(original_model, server.current_model);
    }
}

test "settled stdio event relinquishes abort ownership before agent end" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "test-model", .context_window = 1024, .output_limit = 128 },
    }, &state, rpcTestStream);
    defer shared.destroy();
    var server = try Server.init(allocator, shared);
    defer {
        server.prompt_running = false;
        server.prompt_cancellable = false;
        server.active_run_id = null;
        server.deinit();
    }
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var file = try tmp.dir.createFile(std.testing.io, "events.jsonl", .{});
    defer file.close(std.testing.io);
    var file_output = FileOutputContext{ .io = std.testing.io, .file = file };
    var output = RecordOutput{ .io = std.testing.io, .context = &file_output, .write = writeFileRecord };
    var bridge = ConcurrentEventBridge{ .server = &server, .output = &output };
    defer bridge.deinit();
    server.prompt_running = true;
    server.active_run_id = 91;

    try concurrentRuntimeEvent(&bridge, .{
        .run_id = 91,
        .session_id = server.session_id,
        .sequence = 2,
        .payload = .{ .settled = .{
            .run_id = 91,
            .session_id = server.session_id,
            .stop_reason = .complete,
            .step_count = 1,
            .usage = .{},
        } },
    });

    try std.testing.expect(server.prompt_running);
    try std.testing.expect(!server.prompt_cancellable);
    try std.testing.expectEqual(@as(?runtime_module.RunId, null), server.active_run_id);
}

test "stdio requires the negotiated handshake before prompt and abort" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "test-model", .context_window = 1024, .output_limit = 128 },
    }, &state, rpcTestStream);
    defer shared.destroy();
    var server = try Server.init(allocator, shared);
    defer server.deinit();
    defer server.finishStdioPrompt(true) catch {};
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var file = try tmp.dir.createFile(std.testing.io, "handshake.jsonl", .{ .read = true });
    defer file.close(std.testing.io);
    var file_output = FileOutputContext{ .io = std.testing.io, .file = file };
    var output = RecordOutput{ .io = std.testing.io, .context = &file_output, .write = writeFileRecord };

    try server.handleConcurrentLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"must not run\"}}", &output);
    try std.testing.expect(server.prompt_future == null);
    try server.handleConcurrentLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/abort\",\"params\":{}}", &output);
    try std.testing.expectEqual(@as(usize, 0), state.seen_prompt.items.len);

    const length: usize = @intCast(try file.length(std.testing.io));
    const bytes = try allocator.alloc(u8, length);
    defer allocator.free(bytes);
    try std.testing.expectEqual(length, try file.readPositionalAll(std.testing.io, bytes, 0));
    try std.testing.expectEqual(@as(usize, 2), std.mem.count(u8, bytes, "\"code\":-32007"));
}

test "stdio validates the complete JSON-RPC envelope before concurrent prompt dispatch" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "test-model", .context_window = 1024, .output_limit = 128 },
    }, &state, rpcTestStream);
    defer shared.destroy();
    var server = try Server.init(allocator, shared);
    defer server.deinit();
    defer server.finishStdioPrompt(true) catch {};
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var file = try tmp.dir.createFile(std.testing.io, "validation.jsonl", .{ .read = true });
    defer file.close(std.testing.io);
    var file_output = FileOutputContext{ .io = std.testing.io, .file = file };
    var output = RecordOutput{ .io = std.testing.io, .context = &file_output, .write = writeFileRecord };

    try server.handleConcurrentLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc/handshake\",\"params\":{\"protocol_major\":1,\"protocol_minor\":0}}", &output);
    try server.handleConcurrentLine("{\"id\":2,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"must not run without jsonrpc\"}}", &output);
    try server.handleConcurrentLine("{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"must not run with a boolean id\"}}", &output);
    try server.finishStdioPrompt(true);
    try std.testing.expectEqual(@as(usize, 0), state.seen_prompt.items.len);

    const length: usize = @intCast(try file.length(std.testing.io));
    const bytes = try allocator.alloc(u8, length);
    defer allocator.free(bytes);
    try std.testing.expectEqual(length, try file.readPositionalAll(std.testing.io, bytes, 0));
    try std.testing.expectEqual(@as(usize, 2), std.mem.count(u8, bytes, "\"code\":-32600"));
}

test "stdio live message identifiers are the exact durable history identifiers" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "test-model", .context_window = 1024, .output_limit = 128 },
    }, &state, rpcTestStream);
    defer shared.destroy();
    var server = try Server.init(allocator, shared);
    defer server.deinit();
    defer server.finishStdioPrompt(true) catch {};
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();
    var file = try tmp.dir.createFile(std.testing.io, "durable-identifiers.jsonl", .{ .read = true });
    defer file.close(std.testing.io);
    var file_output = FileOutputContext{ .io = std.testing.io, .file = file };
    var output = RecordOutput{ .io = std.testing.io, .context = &file_output, .write = writeFileRecord };

    try server.handleConcurrentLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc/handshake\",\"params\":{\"protocol_major\":1,\"protocol_minor\":0}}", &output);
    try server.handleConcurrentLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/start\",\"params\":{\"session_id\":\"live-session\",\"model\":\"test-model\"}}", &output);
    try server.handleConcurrentLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"live prompt\",\"input_id\":\"live-input\"}}", &output);
    try server.finishStdioPrompt(false);

    var history = try shared.sessionHistory("live-session", null, 10, allocator);
    defer history.deinit();
    try std.testing.expectEqual(@as(usize, 2), history.page.messages.len);
    try std.testing.expectEqualStrings("live-input", history.page.messages[0].id);
    const assistant_message_id = history.page.messages[1].id;

    const length: usize = @intCast(try file.length(std.testing.io));
    const bytes = try allocator.alloc(u8, length);
    defer allocator.free(bytes);
    try std.testing.expectEqual(length, try file.readPositionalAll(std.testing.io, bytes, 0));
    try std.testing.expect(std.mem.indexOf(u8, bytes, "\"method\":\"agent_start\"") != null);
    try std.testing.expect(std.mem.indexOf(u8, bytes, "\"input_message_id\":\"live-input\"") != null);
    const assistant_fragment = try std.fmt.allocPrint(allocator, "\"assistant_message_id\":\"{s}\"", .{assistant_message_id});
    defer allocator.free(assistant_fragment);
    try std.testing.expect(std.mem.indexOf(u8, bytes, assistant_fragment) != null);
}

test "rpc borrowed runtime executes real stream and routes model selection" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const profiles = [_]model_catalog.ModelProfile{
        .{ .id = "gpt-4.1-mini", .name = "Exact bundled-id witness", .provider = "configured-provider", .context_window = 7777, .output_limit = 321, .max_output_tokens = 512, .reasoning = .high, .cache_retention = .long, .cache_aggressiveness = .aggressive, .transport = .websocket },
        .{ .id = "compact-sse", .name = "Compact SSE", .provider = "other-provider", .context_window = 8192, .output_limit = 128, .max_output_tokens = 256, .reasoning = .low, .cache_retention = .short, .cache_aggressiveness = .conservative, .transport = .sse },
    };
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{
            .provider = "configured-provider",
            .model = "gpt-4.1-mini",
            .context_window = 7777,
            .output_limit = 321,
            .reasoning = .high,
            .cache_retention = .long,
            .cache_aggressiveness = .aggressive,
            .transport = .websocket,
        },
        .model_profiles = &profiles,
    }, &state, rpcTestStream);
    defer shared.destroy();
    var server = try Server.init(allocator, shared);
    defer server.deinit();
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);

    try completeTestHandshake(&server, &output);
    try server.handleLine(
        "{\"jsonrpc\":\"2.0\",\"id\":40,\"method\":\"session/import\",\"params\":{\"session_id\":\"legacy-session\",\"model\":\"gpt-4.1-mini\",\"messages\":[{\"user\":{\"content\":[{\"text\":{\"text\":\"legacy prompt\"}}],\"timestamp_ms\":1}},{\"assistant\":{\"content\":[{\"thinking\":{\"thinking\":\"legacy reasoning\"}},{\"text\":{\"text\":\"legacy answer\"}}],\"api\":\"openai_chat\",\"provider\":\"configured-provider\",\"model\":\"gpt-4.1-mini\",\"stop_reason\":\"stop\",\"timestamp_ms\":2}}]}}\n",
        &output,
    );
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"imported\":true") != null);
    var imported_history = try shared.sessionHistory("legacy-session", null, 10, allocator);
    defer imported_history.deinit();
    try std.testing.expectEqual(@as(usize, 2), imported_history.decoded.messages.len);
    try std.testing.expectEqualStrings("legacy prompt", imported_history.decoded.messages[0].user.content[0].text.text);
    try std.testing.expectEqualStrings("legacy reasoning", imported_history.decoded.messages[1].assistant.content[0].thinking.thinking);
    try std.testing.expectEqualStrings("legacy answer", imported_history.decoded.messages[1].assistant.content[1].text.text);

    output.clearRetainingCapacity();
    try server.handleLine(
        "{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"session/import\",\"params\":{\"session_id\":\"legacy-session\",\"model\":\"gpt-4.1-mini\",\"messages\":[]}}\n",
        &output,
    );
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"imported\":false") != null);

    output.clearRetainingCapacity();
    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/start\",\"params\":{\"session_id\":\"rpc-session\",\"model\":\"gpt-4.1-mini\"}}\n", &output);
    var selected = try shared.selection("rpc-session");
    defer runtime_module.deinitSelection(allocator, selected);
    try std.testing.expectEqualStrings("configured-provider", selected.provider);
    try std.testing.expectEqualStrings("gpt-4.1-mini", selected.model);
    try std.testing.expectEqual(@as(u64, 7777), selected.context_window);
    try std.testing.expectEqual(@as(u64, 321), selected.output_limit);
    try std.testing.expectEqual(protocol.ThinkingLevel.high, selected.reasoning.?);
    try std.testing.expectEqual(protocol.CacheRetention.long, selected.cache_retention);
    try std.testing.expectEqual(protocol.CacheAggressiveness.aggressive, selected.cache_aggressiveness);
    try std.testing.expectEqual(protocol.Transport.websocket, selected.transport);

    output.clearRetainingCapacity();
    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"model/list\",\"params\":{}}\n", &output);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"id\":\"gpt-4.1-mini\"") != null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"context_window\":7777") != null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"id\":\"compact-sse\"") != null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "runtime-profile-registry") != null);

    output.clearRetainingCapacity();
    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"model/set\",\"params\":{\"model\":\"gpt-4.1-mini\",\"thinking\":\"low\"}}\n", &output);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"code\":-32602") != null);
    runtime_module.deinitSelection(allocator, selected);
    selected = try shared.selection("rpc-session");
    try std.testing.expectEqual(protocol.ThinkingLevel.high, selected.reasoning.?);

    output.clearRetainingCapacity();
    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"model/set\",\"params\":{\"model\":\"compact-sse\"}}\n", &output);
    runtime_module.deinitSelection(allocator, selected);
    selected = try shared.selection("rpc-session");
    try std.testing.expectEqualStrings("other-provider", selected.provider);
    try std.testing.expectEqual(@as(u64, 8192), selected.context_window);
    try std.testing.expectEqual(protocol.ThinkingLevel.low, selected.reasoning.?);
    try std.testing.expectEqual(protocol.CacheRetention.short, selected.cache_retention);
    try std.testing.expectEqual(protocol.CacheAggressiveness.conservative, selected.cache_aggressiveness);
    try std.testing.expectEqual(protocol.Transport.sse, selected.transport);

    output.clearRetainingCapacity();
    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"prompt-from-rpc\"}}\n", &output);
    try std.testing.expectEqualStrings("compact-sse", state.seen_model.items);
    try std.testing.expectEqualStrings("prompt-from-rpc", state.seen_prompt.items);
    try std.testing.expectEqual(@as(?u64, 128), state.seen_options.max_tokens);
    try std.testing.expectEqual(protocol.ThinkingLevel.low, state.seen_options.reasoning.?);
    try std.testing.expectEqual(protocol.CacheRetention.short, state.seen_options.cache_retention);
    try std.testing.expectEqual(protocol.CacheAggressiveness.conservative, state.seen_options.cache_aggressiveness);
    try std.testing.expectEqual(protocol.Transport.sse, state.seen_options.transport);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"content\":\"rpc-real-response\"") != null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"method\":\"message_delta\"") == null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"method\":\"agent_start\"") == null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"method\":\"agent_end\"") == null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "Analyzing request") == null);

    output.clearRetainingCapacity();
    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"session/abort\",\"params\":{}}\n", &output);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"code\":-32004") != null);
}

fn rpcCanceledStream(_: *anyopaque, _: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, _: *anyopaque, _: protocol.StreamSink) !void {
    return error.Canceled;
}

test "rpc reports a canceled prompt as canceled instead of completed" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "cancel-model", .context_window = 1024, .output_limit = 128 },
    }, &state, rpcCanceledStream);
    defer shared.destroy();
    var server = try Server.init(allocator, shared);
    defer server.deinit();
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);

    try completeTestHandshake(&server, &output);
    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/prompt\",\"params\":{\"prompt\":\"cancel me\"}}\n", &output);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"status\":\"canceled\"") != null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"status\":\"completed\"") == null);
}

test "rpc invalid and unavailable capabilities fail closed with a real runtime" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "only-model", .context_window = 1024, .output_limit = 128 },
    }, &state, rpcTestStream);
    defer shared.destroy();
    var server = try Server.init(allocator, shared);
    defer server.deinit();
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);

    try server.handleLine("invalid json {[\n", &output);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"code\":-32700") != null);
    try completeTestHandshake(&server, &output);
    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"model/set\",\"params\":{\"model\":\"unknown\"}}\n", &output);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"code\":-32602") != null);
    output.clearRetainingCapacity();
    for ([_][]const u8{ "approval/respond", "pty/create", "composer/speculate" }) |method| {
        const request = try std.fmt.allocPrint(allocator, "{{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"{s}\",\"params\":{{}}}}\n", .{method});
        defer allocator.free(request);
        try server.handleLine(request, &output);
        try std.testing.expect(std.mem.indexOf(u8, output.items, "\"code\":-32004") != null);
        output.clearRetainingCapacity();
    }

    try server.handleLine("{\"jsonrpc\":\"2.0\",\"method\":\"model/list\",\"params\":{}}\n", &output);
    try std.testing.expectEqual(@as(usize, 0), output.items.len);
    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"model/list\",\"params\":{}}\n", &output);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"code\":-32600") != null);
}

test "rpc handshake reports only implemented capabilities and numeric limits" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "only-model", .context_window = 1024, .output_limit = 128 },
    }, &state, rpcTestStream);
    defer shared.destroy();
    var server = try Server.init(allocator, shared);
    defer server.deinit();
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);

    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":\"before-hello\",\"method\":\"model/list\",\"params\":{}}\n", &output);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"code\":-32007") != null);
    output.clearRetainingCapacity();
    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":\"hello\",\"method\":\"rpc/handshake\",\"params\":{\"protocol_major\":1,\"protocol_minor\":0}}\n", &output);

    var response = try std.json.parseFromSlice(std.json.Value, allocator, output.items, .{});
    defer response.deinit();
    const result = response.value.object.get("result") orelse return error.TestUnexpectedResult;
    const protocol_value = result.object.get("protocol") orelse return error.TestUnexpectedResult;
    try std.testing.expectEqualStrings("misul-rpc", protocol_value.object.get("name").?.string);
    try std.testing.expectEqual(@as(i64, 1), protocol_value.object.get("major").?.integer);
    try std.testing.expectEqual(@as(i64, 0), protocol_value.object.get("minor").?.integer);
    const server_value = result.object.get("server") orelse return error.TestUnexpectedResult;
    try std.testing.expectEqualStrings("misul-agent", server_value.object.get("name").?.string);
    try std.testing.expectEqualStrings("0.1-alpha", server_value.object.get("version").?.string);
    const limits = result.object.get("limits") orelse return error.TestUnexpectedResult;
    try std.testing.expect(limits.object.get("max_record_bytes").?.integer > 0);
    try std.testing.expectEqual(@as(i64, 1), limits.object.get("max_parallel_prompts").?.integer);
    const capabilities = result.object.get("capabilities") orelse return error.TestUnexpectedResult;
    try std.testing.expect(capabilities.object.get("session_start").?.bool);
    try std.testing.expect(capabilities.object.get("session_new").?.bool);
    try std.testing.expect(capabilities.object.get("session_prompt").?.bool);
    try std.testing.expect(capabilities.object.get("session_abort").?.bool);
    try std.testing.expect(capabilities.object.get("model_list").?.bool);
    try std.testing.expect(capabilities.object.get("model_set").?.bool);
    try std.testing.expect(capabilities.object.get("session_switch").?.bool);
    try std.testing.expect(capabilities.object.get("session_list").?.bool);
    try std.testing.expect(capabilities.object.get("session_history").?.bool);
    try std.testing.expect(!capabilities.object.get("approvals").?.bool);
    try std.testing.expect(!capabilities.object.get("pty").?.bool);
    try std.testing.expect(!capabilities.object.get("event_replay").?.bool);

    output.clearRetainingCapacity();
    try server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":\"wrong-major\",\"method\":\"rpc/handshake\",\"params\":{\"protocol_major\":2,\"protocol_minor\":0}}\n", &output);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"code\":-32006") != null);
}

test "long rpc request ids keep the governed rpc provenance prefix" {
    const long_id = "x" ** 256;
    const request_id = try systemUseRequestId(std.testing.allocator, .{ .string = long_id });
    defer std.testing.allocator.free(request_id);

    try std.testing.expect(std.mem.startsWith(u8, request_id, "rpc:sha256:"));
    try std.testing.expectEqual(@as(usize, "rpc:sha256:".len + 64), request_id.len);
}

test "rpc rejects records larger than the advertised limit" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "only-model", .context_window = 1024, .output_limit = 128 },
    }, &state, rpcTestStream);
    defer shared.destroy();
    var server = try Server.init(allocator, shared);
    defer server.deinit();
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);
    const oversized = try allocator.alloc(u8, 1024 * 1024 + 1);
    defer allocator.free(oversized);
    @memset(oversized, 'x');

    try server.handleLine(oversized, &output);

    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"code\":-32005") != null);
}

test "rpc never emits a success record larger than its advertised limit" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var state = RpcTestState{ .environment = std.process.Environ.Map.init(allocator) };
    defer state.deinit(allocator);
    const shared = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &state.environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "only-model", .context_window = 1024, .output_limit = 128 },
    }, &state, rpcTestStream);
    defer shared.destroy();
    var server = try Server.init(allocator, shared);
    defer server.deinit();
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);
    const oversized = try allocator.alloc(u8, max_record_bytes);
    defer allocator.free(oversized);
    @memset(oversized, 'x');

    try server.writeSuccessResponse(&output, .{ .integer = 1 }, .{ .payload = oversized });

    try std.testing.expect(output.items.len <= max_record_bytes);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\"code\":-32005") != null);
}

test "stdio record buffer bounds fragments and recovers after the record delimiter" {
    const allocator = std.testing.allocator;
    var buffer = RecordBuffer{ .limit = 4 };
    defer buffer.deinit(allocator);

    try std.testing.expectEqual(RecordBuffer.AppendState.accepted, try buffer.append(allocator, "ab"));
    try std.testing.expectEqual(RecordBuffer.AppendState.accepted, try buffer.append(allocator, "cd"));
    try std.testing.expectEqualStrings("abcd", buffer.bytes.items);
    buffer.reset();

    try std.testing.expectEqual(RecordBuffer.AppendState.accepted, try buffer.append(allocator, "abc"));
    try std.testing.expectEqual(RecordBuffer.AppendState.oversized, try buffer.append(allocator, "de"));
    try std.testing.expectEqual(@as(usize, 0), buffer.bytes.items.len);
    try std.testing.expectEqual(RecordBuffer.AppendState.discarding, try buffer.append(allocator, "ignored"));
    buffer.reset();

    try std.testing.expectEqual(RecordBuffer.AppendState.accepted, try buffer.append(allocator, "{}"));
    try std.testing.expectEqualStrings("{}", buffer.bytes.items);
}
