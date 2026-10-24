const std = @import("std");
const builtin = @import("builtin");
const protocol = @import("protocol.zig");
const runtime_module = @import("runtime.zig");

const esc = "\x1b[";
const reset = esc ++ "0m";
const bright = esc ++ "97m";
const text = esc ++ "38;5;252m";
const muted = esc ++ "38;5;244m";
const dim = esc ++ "38;5;240m";
var shutdown_signal_number: c_int = 0;

fn setShutdownSignal(value: c_int) void {
    const pointer: *volatile c_int = &shutdown_signal_number;
    pointer.* = value;
}

fn shutdownSignal() c_int {
    const pointer: *volatile c_int = &shutdown_signal_number;
    return pointer.*;
}

fn shutdownSignalHandler(signal: std.posix.SIG) callconv(.c) void {
    setShutdownSignal(@intCast(@intFromEnum(signal)));
}

const ShutdownSignals = struct {
    term: std.posix.Sigaction,
    hangup: std.posix.Sigaction,
    quit: std.posix.Sigaction,

    fn install() ShutdownSignals {
        setShutdownSignal(0);
        const action: std.posix.Sigaction = .{
            .handler = .{ .handler = shutdownSignalHandler },
            .mask = std.posix.sigemptyset(),
            .flags = 0,
        };
        var previous: ShutdownSignals = undefined;
        std.posix.sigaction(.TERM, &action, &previous.term);
        std.posix.sigaction(.HUP, &action, &previous.hangup);
        std.posix.sigaction(.QUIT, &action, &previous.quit);
        return previous;
    }

    fn restore(self: *const ShutdownSignals) void {
        std.posix.sigaction(.TERM, &self.term, null);
        std.posix.sigaction(.HUP, &self.hangup, null);
        std.posix.sigaction(.QUIT, &self.quit, null);
        setShutdownSignal(0);
    }
};

const Message = struct {
    role: enum { user, assistant, activity, @"error" },
    content: []u8,
};

pub const App = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    executable: []const u8,
    model: []const u8,
    version: []const u8,
    cwd: []const u8,
    session_id: []const u8,
    runtime: *runtime_module.Runtime,
    input: std.ArrayList(u8) = .empty,
    cursor: usize = 0,
    messages: std.ArrayList(Message) = .empty,
    status: []const u8 = "Ready",
    working: bool = false,
    streaming_index: ?usize = null,
    activity_streaming_index: ?usize = null,
    failure_rendered: bool = false,
    screen_size: Size = .{ .width = 80, .height = 24 },
    render_disabled_for_test: bool = false,
    escape_sequence: [16]u8 = undefined,
    escape_sequence_len: usize = 0,
    bracketed_paste: bool = false,
    paste_end_match: usize = 0,
    active_run_id: ?runtime_module.RunId = null,
    state_mutex: std.Io.Mutex = .init,
    render_pending: std.atomic.Value(bool) = .init(false),

    pub fn deinit(self: *App) void {
        self.input.deinit(self.allocator);
        for (self.messages.items) |message| self.allocator.free(message.content);
        self.messages.deinit(self.allocator);
    }

    pub fn run(self: *App) !void {
        const stdin = std.Io.File.stdin();
        if (!try stdin.isTty(self.io) or !try std.Io.File.stdout().isTty(self.io)) return error.NotATerminal;
        const shutdown_signals = ShutdownSignals.install();
        defer shutdown_signals.restore();
        const original = try std.posix.tcgetattr(stdin.handle);
        var raw = original;
        raw.lflag.ECHO = false;
        raw.lflag.ECHONL = false;
        raw.lflag.ICANON = false;
        raw.lflag.ISIG = false;
        raw.lflag.IEXTEN = false;
        raw.iflag.IXON = false;
        raw.iflag.ICRNL = false;
        try std.posix.tcsetattr(stdin.handle, .FLUSH, raw);
        defer std.posix.tcsetattr(stdin.handle, .FLUSH, original) catch {};

        try terminalWrite(self.io, esc ++ "?1049h" ++ esc ++ "?25l" ++ esc ++ "?2004h");
        defer terminalWrite(self.io, esc ++ "?2004l" ++ esc ++ "?25h" ++ esc ++ "?1049l") catch {};
        try self.render();

        var buffer: [4096]u8 = undefined;
        while (true) {
            if (shutdownSignal() != 0) {
                terminalWrite(self.io, esc ++ "?2004l" ++ esc ++ "?25h" ++ esc ++ "?1049l") catch {};
                std.posix.tcsetattr(stdin.handle, .FLUSH, original) catch {};
                const signal: std.posix.SIG = @enumFromInt(@as(u32, @intCast(shutdownSignal())));
                shutdown_signals.restore();
                try std.posix.raise(signal);
                return;
            }
            if (self.active_run_id != null and self.runtime.activeRun(self.session_id) == null) {
                try self.finishActiveRun();
                try self.render();
                continue;
            }
            var poll_fds = [_]std.posix.pollfd{.{
                .fd = stdin.handle,
                .events = std.posix.POLL.IN,
                .revents = 0,
            }};
            const timeout_ms: i32 = 50;
            if (try std.posix.poll(&poll_fds, timeout_ms) == 0) {
                if (self.render_pending.swap(false, .acq_rel)) try self.render();
                continue;
            }
            const count = try std.posix.read(stdin.handle, &buffer);
            if (count == 0) continue;
            if (try self.handleInput(buffer[0..count])) {
                if (self.active_run_id) |run_id| {
                    _ = try self.runtime.cancel(run_id);
                    self.active_run_id = null;
                }
                break;
            }
            try self.render();
        }
    }

    fn handleInput(self: *App, data: []const u8) !bool {
        for (data) |byte| {
            if (self.bracketed_paste) {
                try self.handlePasteByte(byte);
                continue;
            }
            if (self.escape_sequence_len != 0 and byte == 3) {
                self.escape_sequence_len = 0;
                return true;
            }
            if (self.escape_sequence_len != 0 or byte == 0x1b) {
                switch (self.handleEscapeByte(byte)) {
                    .pending, .consumed => continue,
                    .retry_plain => {},
                }
            }
            if (try self.handlePlainByte(byte)) return true;
        }
        return false;
    }

    fn handlePlainByte(self: *App, byte: u8) !bool {
        switch (byte) {
            3 => return true,
            4 => if (self.input.items.len == 0) return true,
            10 => {
                try self.input.insert(self.allocator, self.cursor, '\n');
                self.cursor += 1;
            },
            13 => if (!self.working and std.mem.trim(u8, self.input.items, " \t\r\n").len != 0) try self.submit(),
            8, 127 => if (self.cursor != 0) {
                const start = previousUtf8Boundary(self.input.items, self.cursor);
                while (self.cursor > start) {
                    self.cursor -= 1;
                    _ = self.input.orderedRemove(self.cursor);
                }
            },
            else => if (byte >= 0x20 or byte >= 0x80) {
                try self.input.insert(self.allocator, self.cursor, byte);
                self.cursor += 1;
            },
        }
        return false;
    }

    const EscapeDisposition = enum { pending, consumed, retry_plain };

    fn handleEscapeByte(self: *App, byte: u8) EscapeDisposition {
        if (self.escape_sequence_len == self.escape_sequence.len) {
            self.escape_sequence_len = 0;
            return .retry_plain;
        }
        self.escape_sequence[self.escape_sequence_len] = byte;
        self.escape_sequence_len += 1;
        const sequence = self.escape_sequence[0..self.escape_sequence_len];
        if (std.mem.eql(u8, sequence, "\x1b[D")) {
            self.cursor = previousUtf8Boundary(self.input.items, self.cursor);
            self.escape_sequence_len = 0;
            return .consumed;
        }
        if (std.mem.eql(u8, sequence, "\x1b[C")) {
            self.cursor = nextUtf8Boundary(self.input.items, self.cursor);
            self.escape_sequence_len = 0;
            return .consumed;
        }
        if (std.mem.eql(u8, sequence, "\x1b[3~")) {
            const end = nextUtf8Boundary(self.input.items, self.cursor);
            for (self.cursor..end) |_| _ = self.input.orderedRemove(self.cursor);
            self.escape_sequence_len = 0;
            return .consumed;
        }
        if (std.mem.eql(u8, sequence, "\x1b[200~")) {
            self.escape_sequence_len = 0;
            self.bracketed_paste = true;
            self.paste_end_match = 0;
            return .consumed;
        }
        if (sequence.len == 1) return .pending;
        if (sequence.len == 2 and (sequence[1] == '[' or sequence[1] == 'O')) return .pending;
        if (sequence[1] != '[' and sequence[1] != 'O') {
            self.escape_sequence_len = 0;
            return .retry_plain;
        }
        if (byte >= 0x40 and byte <= 0x7e) {
            self.escape_sequence_len = 0;
            return .consumed;
        }
        if (byte >= 0x20 and byte <= 0x3f) return .pending;
        self.escape_sequence_len = 0;
        return .retry_plain;
    }

    fn handlePasteByte(self: *App, byte: u8) !void {
        const marker = "\x1b[201~";
        if (byte == marker[self.paste_end_match]) {
            self.paste_end_match += 1;
            if (self.paste_end_match == marker.len) {
                self.paste_end_match = 0;
                self.bracketed_paste = false;
            }
            return;
        }
        if (self.paste_end_match != 0) {
            try self.input.insertSlice(self.allocator, self.cursor, marker[0..self.paste_end_match]);
            self.cursor += self.paste_end_match;
            self.paste_end_match = 0;
        }
        if (byte == marker[0]) {
            self.paste_end_match = 1;
        } else {
            try self.input.insert(self.allocator, self.cursor, byte);
            self.cursor += 1;
        }
    }

    fn submit(self: *App) !void {
        var prompt: ?[]u8 = try self.allocator.dupe(u8, self.input.items);
        errdefer if (prompt) |value| self.allocator.free(value);
        try self.messages.append(self.allocator, .{ .role = .user, .content = prompt.? });
        const owned_prompt = prompt.?;
        prompt = null;
        self.input.clearRetainingCapacity();
        self.cursor = 0;
        self.working = true;
        self.status = "Thinking";
        try self.render();
        self.streaming_index = null;
        self.activity_streaming_index = null;
        self.failure_rendered = false;
        const run_id = self.runtime.startPrompt(.{
            .session_id = self.session_id,
            .prompt = owned_prompt,
            .event_context = self,
            .emit = runtimeEvent,
        }) catch |err| {
            self.working = false;
            try self.appendError(@errorName(err));
            self.status = "Turn failed";
            return;
        };
        self.active_run_id = run_id;
    }

    fn finishActiveRun(self: *App) !void {
        const run_id = self.active_run_id orelse return;
        const settlement = self.runtime.wait(run_id) catch |err| {
            self.active_run_id = null;
            self.working = false;
            try self.appendError(@errorName(err));
            self.status = "Turn failed";
            return;
        };
        self.active_run_id = null;
        self.state_mutex.lockUncancelable(self.io);
        defer self.state_mutex.unlock(self.io);
        self.working = false;
        self.streaming_index = null;
        self.activity_streaming_index = null;
        if (settlement.stop_reason == .failed or settlement.stop_reason == .max_steps) {
            if (!self.failure_rendered) {
                if (settlement.provider_failure) |failure|
                    try self.appendProviderError(failure)
                else
                    try self.appendError(@tagName(settlement.failure));
            }
            self.status = "Turn failed";
        } else {
            self.status = "Ready";
        }
    }

    fn appendAssistantDelta(self: *App, delta: []const u8) !void {
        if (delta.len == 0) return;
        const index = self.streaming_index orelse blk: {
            try self.messages.append(self.allocator, .{ .role = .assistant, .content = &.{} });
            self.streaming_index = self.messages.items.len - 1;
            break :blk self.streaming_index.?;
        };
        const old = self.messages.items[index].content;
        const updated = try std.mem.concat(self.allocator, u8, &.{ old, delta });
        self.allocator.free(old);
        self.messages.items[index].content = updated;
    }

    fn appendError(self: *App, message: []const u8) !void {
        var content: ?[]u8 = try std.fmt.allocPrint(self.allocator, "Turn failed: {s}", .{message});
        errdefer if (content) |value| self.allocator.free(value);
        try self.messages.append(self.allocator, .{
            .role = .@"error",
            .content = content.?,
        });
        content = null;
    }

    fn appendActivity(self: *App, message: []const u8) !void {
        var content: ?[]u8 = try self.allocator.dupe(u8, message);
        errdefer if (content) |value| self.allocator.free(value);
        try self.messages.append(self.allocator, .{ .role = .activity, .content = content.? });
        content = null;
    }

    fn appendActivityDelta(self: *App, delta: []const u8) !void {
        if (delta.len == 0) return;
        const index = self.activity_streaming_index orelse blk: {
            try self.appendActivity("Reasoning: ");
            self.activity_streaming_index = self.messages.items.len - 1;
            break :blk self.activity_streaming_index.?;
        };
        const old = self.messages.items[index].content;
        const updated = try std.mem.concat(self.allocator, u8, &.{ old, delta });
        self.allocator.free(old);
        self.messages.items[index].content = updated;
    }

    fn appendActivityFormat(self: *App, comptime format: []const u8, arguments: anytype) !void {
        var content: ?[]u8 = try std.fmt.allocPrint(self.allocator, format, arguments);
        errdefer if (content) |value| self.allocator.free(value);
        try self.messages.append(self.allocator, .{ .role = .activity, .content = content.? });
        content = null;
    }

    fn appendProviderError(self: *App, failure: protocol.ProviderFailure) !void {
        var content: ?[]u8 = try std.fmt.allocPrint(self.allocator, "Turn failed [{s}]: {s}", .{ @tagName(failure.category), failure.redacted_message });
        errdefer if (content) |value| self.allocator.free(value);
        try self.messages.append(self.allocator, .{
            .role = .@"error",
            .content = content.?,
        });
        content = null;
    }

    fn render(self: *App) !void {
        if (builtin.is_test and self.render_disabled_for_test) return;
        self.state_mutex.lockUncancelable(self.io);
        defer self.state_mutex.unlock(self.io);
        if (terminalSize(self.io)) |size| self.screen_size = size else |_| {}
        const size = self.screen_size;
        var frame: std.ArrayList(u8) = .empty;
        defer frame.deinit(self.allocator);
        try frame.appendSlice(self.allocator, esc ++ "?2026h" ++ esc ++ "H" ++ esc ++ "2J");
        try renderFrame(self.allocator, &frame, .{
            .width = size.width,
            .height = size.height,
            .model = self.model,
            .version = self.version,
            .cwd = self.cwd,
            .messages = self.messages.items,
            .input = self.input.items,
            .cursor = self.cursor,
            .status = self.status,
            .working = self.working,
        });
        try frame.appendSlice(self.allocator, esc ++ "?2026l");
        try terminalWrite(self.io, frame.items);
    }
};

fn runtimeEvent(raw: *anyopaque, event: runtime_module.Event) !void {
    const app: *App = @ptrCast(@alignCast(raw));
    app.state_mutex.lockUncancelable(app.io);
    defer app.state_mutex.unlock(app.io);
    switch (event.payload) {
        .run_started => app.status = "Thinking",
        .provider_stream => |stream| switch (stream) {
            .text_delta => |delta| try app.appendAssistantDelta(delta.delta),
            .thinking_start => {
                app.activity_streaming_index = null;
                app.status = "Reasoning";
            },
            .thinking_delta => |delta| try app.appendActivityDelta(delta.delta),
            .thinking_end => app.activity_streaming_index = null,
            .tool_call_start, .tool_call_delta => app.status = "Preparing tool",
            .tool_call_end => |tool| {
                app.status = "Preparing tool";
                try app.appendActivityFormat("Tool requested: {s}", .{tool.tool_call.name});
            },
            .done => |message| if (app.streaming_index == null) for (message.content) |part| switch (part) {
                .text => |content| try app.appendAssistantDelta(content.text),
                else => {},
            },
            else => {},
        },
        .tool_started => |tool| {
            app.status = "Using tool";
            try app.appendActivityFormat("Tool started: {s}", .{tool.name});
        },
        .tool_finished => |result| {
            app.status = if (result.is_error) "Tool failed" else "Thinking";
            try app.appendActivityFormat("Tool {s}: {s}", .{ if (result.is_error) "failed" else "finished", result.tool_name });
        },
        .compaction_observed => |compaction| try app.appendActivityFormat("Context compacted: {d} tokens before, kept from message {d}", .{ compaction.tokens_before, compaction.first_kept_index }),
        .cancellation_requested => app.status = "Canceling",
        .typed_failure => |typed| {
            if (!app.failure_rendered) {
                if (typed.provider_failure) |failure|
                    try app.appendProviderError(failure)
                else
                    try app.appendError(@tagName(typed.category));
                app.failure_rendered = true;
            }
            app.status = "Turn failed";
        },
        .settled => |settlement| if (settlement.usage.input != 0 or settlement.usage.output != 0 or settlement.usage.total_tokens != 0) {
            try app.appendActivityFormat("Usage: {d} input · {d} output · {d} total", .{ settlement.usage.input, settlement.usage.output, settlement.usage.total_tokens });
        },
        .message_added => {},
    }
    app.render_pending.store(true, .release);
}

const View = struct {
    width: usize,
    height: usize,
    model: []const u8,
    version: []const u8,
    cwd: []const u8,
    messages: []const Message,
    input: []const u8,
    cursor: usize,
    status: []const u8,
    working: bool,
};

fn renderFrame(allocator: std.mem.Allocator, output: *std.ArrayList(u8), view: View) !void {
    const width = @max(@as(usize, 40), view.width);
    const height = @max(@as(usize, 16), view.height);
    const column = @min(@as(usize, 88), width -| 4);
    const left = (width - column) / 2;
    const safe_model = try sanitizeTerminalText(allocator, view.model, false, false);
    defer allocator.free(safe_model);
    const safe_version = try sanitizeTerminalText(allocator, view.version, false, false);
    defer allocator.free(safe_version);
    const safe_location = try sanitizeTerminalText(allocator, basename(view.cwd), false, false);
    defer allocator.free(safe_location);
    const safe_status = try sanitizeTerminalText(allocator, view.status, false, false);
    defer allocator.free(safe_status);
    const pad = try allocator.alloc(u8, left);
    defer allocator.free(pad);
    @memset(pad, ' ');

    if (height >= 30 and width >= 72) {
        try centeredLine(output, allocator, width, 18, .{ bright, "  ▄████▄  ▄████▄ ", reset });
        try centeredLine(output, allocator, width, 18, .{ bright, " ████████████████", reset });
        try centeredLine(output, allocator, width, 18, .{ bright, " ███  ███   █████", reset });
        try centeredLine(output, allocator, width, 18, .{ bright, " ███  ███   █████", reset });
        try output.append(allocator, '\n');
    }
    try centeredLine(output, allocator, width, 7 + visibleLen(safe_version), .{ bright, "misul", reset, "  ", dim, safe_version, reset });
    try centeredLine(output, allocator, width, 21, .{ muted, "Native coding harness", reset });
    try output.append(allocator, '\n');

    const chrome_rows: usize = if (height >= 30 and width >= 72) 14 else 9;
    const transcript_rows = height -| chrome_rows;
    var transcript: std.ArrayList([]u8) = .empty;
    defer {
        for (transcript.items) |item| allocator.free(item);
        transcript.deinit(allocator);
    }
    if (view.messages.len == 0 and transcript_rows >= 4) {
        try wrapOwned(allocator, &transcript, "Start with a concrete task.", column -| 2, "", false);
        try wrapOwned(allocator, &transcript, "Misul will inspect the workspace before it changes anything.", column -| 2, "", false);
    }
    for (view.messages) |message| {
        const label = switch (message.role) {
            .user => "You",
            .assistant => "Misul",
            .activity => "Activity",
            .@"error" => "Error",
        };
        const color = switch (message.role) {
            .user => muted,
            .assistant => bright,
            .activity => muted,
            .@"error" => esc ++ "38;5;203m",
        };
        const heading = try std.fmt.allocPrint(allocator, "{s}{s}{s}", .{ color, label, reset });
        try appendOwned(allocator, &transcript, heading);
        try wrapOwned(allocator, &transcript, message.content, column -| 2, "  ", false);
        try appendOwned(allocator, &transcript, try allocator.dupe(u8, ""));
    }
    const start = transcript.items.len -| @min(transcript.items.len, transcript_rows);
    for (transcript.items[start..]) |item| try line(output, allocator, .{ pad, text, item, reset });
    var used = transcript.items.len - start;
    while (used < transcript_rows) : (used += 1) try output.append(allocator, '\n');

    const border_count = column -| 2;
    const border = try allocator.alloc(u8, border_count * "─".len);
    defer allocator.free(border);
    for (0..border_count) |index| @memcpy(border[index * "─".len ..][0.."─".len], "─");
    try line(output, allocator, .{ pad, dim, "╭", border, "╮", reset });

    const composer_width = column -| 4;
    var composer: std.ArrayList([]u8) = .empty;
    defer {
        for (composer.items) |item| allocator.free(item);
        composer.deinit(allocator);
    }
    const composer_source = if (view.input.len == 0)
        try allocator.dupe(u8, "\x1f Ask Misul to inspect, change, or verify…")
    else blk: {
        const cursor = @min(view.cursor, view.input.len);
        const before = try sanitizeTerminalText(allocator, view.input[0..cursor], false, true);
        defer allocator.free(before);
        const after = try sanitizeTerminalText(allocator, view.input[cursor..], false, true);
        defer allocator.free(after);
        const value = try allocator.alloc(u8, before.len + 1 + after.len);
        @memcpy(value[0..before.len], before);
        value[before.len] = 0x1f;
        @memcpy(value[before.len + 1 ..], after);
        break :blk value;
    };
    defer allocator.free(composer_source);
    try wrapOwned(allocator, &composer, composer_source, composer_width, "", true);
    var cursor_line: usize = composer.items.len - 1;
    for (composer.items, 0..) |item, index| if (std.mem.indexOfScalar(u8, item, 0x1f) != null) {
        cursor_line = index;
        break;
    };
    const composer_start = if (composer.items.len <= 4) 0 else @min(cursor_line, composer.items.len - 4);
    const composer_end = @min(composer.items.len, composer_start + 4);
    for (composer.items[composer_start..composer_end]) |item| {
        const content_color = if (view.input.len == 0) dim else text;
        try output.appendSlice(allocator, pad);
        try output.appendSlice(allocator, dim ++ "│ " ++ reset);
        try appendComposerLine(output, allocator, item, content_color);
        try spaces(output, allocator, composer_width -| visibleLen(item));
        try output.appendSlice(allocator, reset ++ dim ++ " │" ++ reset ++ "\n");
    }
    try line(output, allocator, .{ pad, dim, "╰", border, "╯", reset });

    const left_plain = try std.fmt.allocPrint(allocator, "{s}  ·  {s}", .{ safe_status, safe_location });
    defer allocator.free(left_plain);
    const right_controls = if (width >= 76) "  ·  enter send  ^J newline  ^C exit" else "^C exit";
    const left_budget = if (width >= 76) column / 3 else column -| visibleLen(right_controls) -| 2;
    const clipped_left = try truncateCells(allocator, left_plain, left_budget);
    defer allocator.free(clipped_left);
    const left_status = try std.fmt.allocPrint(allocator, "{s}{s}{s}", .{ if (view.working) bright else muted, clipped_left, reset });
    defer allocator.free(left_status);
    const right_budget = column -| visibleLen(left_status) -| 2;
    const model_budget = right_budget -| visibleLen(right_controls);
    const clipped_model = try truncateCells(allocator, safe_model, model_budget);
    defer allocator.free(clipped_model);
    const wide_status = try std.fmt.allocPrint(allocator, "{s}{s}", .{ clipped_model, right_controls });
    defer allocator.free(wide_status);
    const right_status = if (width >= 76) wide_status else "^C exit";
    try output.appendSlice(allocator, pad);
    try output.appendSlice(allocator, muted);
    try output.appendSlice(allocator, left_status);
    const gap = column -| visibleLen(left_status) -| visibleLen(right_status);
    if (gap > 1) try spaces(output, allocator, gap) else try output.appendSlice(allocator, "  ");
    try output.appendSlice(allocator, right_status);
    try output.appendSlice(allocator, reset);
}

fn appendOwned(allocator: std.mem.Allocator, lines: *std.ArrayList([]u8), value: []u8) !void {
    errdefer allocator.free(value);
    try lines.append(allocator, value);
}

fn wrapOwned(allocator: std.mem.Allocator, lines: *std.ArrayList([]u8), source: []const u8, width: usize, prefix: []const u8, preserve_cursor: bool) !void {
    const safe = try sanitizeTerminalText(allocator, source, preserve_cursor, true);
    defer allocator.free(safe);
    var remaining = safe;
    if (remaining.len == 0) {
        try appendOwned(allocator, lines, try allocator.dupe(u8, prefix));
        return;
    }
    while (remaining.len != 0) {
        const newline = std.mem.indexOfScalar(u8, remaining, '\n');
        const paragraph = if (newline) |index| remaining[0..index] else remaining;
        if (paragraph.len == 0) {
            try appendOwned(allocator, lines, try allocator.dupe(u8, prefix));
        } else {
            try wrapParagraph(allocator, lines, paragraph, width, prefix);
        }
        if (newline) |index| remaining = remaining[index + 1 ..] else break;
    }
}

fn wrapParagraph(allocator: std.mem.Allocator, lines: *std.ArrayList([]u8), paragraph: []const u8, width: usize, prefix: []const u8) !void {
    const prefix_width = visibleLen(prefix);
    var start: usize = 0;
    var index: usize = 0;
    var cells = prefix_width;
    while (index < paragraph.len) {
        const end = nextGraphemeBoundary(paragraph, index);
        const cell_width = graphemeCellWidth(paragraph[index..end]);
        if (index != start and cells + cell_width > width) {
            try appendOwned(allocator, lines, try std.mem.concat(allocator, u8, &.{ prefix, paragraph[start..index] }));
            start = index;
            cells = prefix_width;
        }
        cells += cell_width;
        index = end;
    }
    try appendOwned(allocator, lines, try std.mem.concat(allocator, u8, &.{ prefix, paragraph[start..] }));
}

fn sanitizeTerminalText(allocator: std.mem.Allocator, source: []const u8, preserve_cursor: bool, preserve_newlines: bool) ![]u8 {
    var safe: std.ArrayList(u8) = .empty;
    errdefer safe.deinit(allocator);
    var index: usize = 0;
    while (index < source.len) {
        const byte = source[index];
        if (byte == '\n' and preserve_newlines) {
            try safe.append(allocator, byte);
            index += 1;
            continue;
        }
        if (byte == '\t') {
            try safe.appendSlice(allocator, "    ");
            index += 1;
            continue;
        }
        if (byte == 0x1f and preserve_cursor) {
            try safe.append(allocator, byte);
            index += 1;
            continue;
        }
        if (byte < 0x20 or byte == 0x7f) {
            try appendByteEscape(allocator, &safe, byte);
            index += 1;
            continue;
        }
        if (byte < 0x80) {
            try safe.append(allocator, byte);
            index += 1;
            continue;
        }
        const length = utf8Length(source[index..]);
        if (length == 1) {
            try appendByteEscape(allocator, &safe, byte);
            index += 1;
            continue;
        }
        const codepoint = std.unicode.utf8Decode(source[index..][0..length]) catch {
            try appendByteEscape(allocator, &safe, byte);
            index += 1;
            continue;
        };
        if (codepoint >= 0x80 and codepoint <= 0x9f) {
            try appendByteEscape(allocator, &safe, @intCast(codepoint));
        } else {
            try safe.appendSlice(allocator, source[index..][0..length]);
        }
        index += length;
    }
    return safe.toOwnedSlice(allocator);
}

fn appendByteEscape(allocator: std.mem.Allocator, output: *std.ArrayList(u8), byte: u8) !void {
    const hex = "0123456789abcdef";
    try output.appendSlice(allocator, "\\x");
    try output.append(allocator, hex[byte >> 4]);
    try output.append(allocator, hex[byte & 0x0f]);
}

fn utf8Length(value: []const u8) usize {
    const length = std.unicode.utf8ByteSequenceLength(value[0]) catch return 1;
    if (length > value.len) return 1;
    _ = std.unicode.utf8Decode(value[0..length]) catch return 1;
    return length;
}

fn appendComposerLine(output: *std.ArrayList(u8), allocator: std.mem.Allocator, value: []const u8, color: []const u8) !void {
    try output.appendSlice(allocator, color);
    for (value) |byte| {
        if (byte == 0x1f) {
            try output.appendSlice(allocator, reset ++ bright ++ "▏" ++ reset);
            try output.appendSlice(allocator, color);
        } else {
            try output.append(allocator, byte);
        }
    }
}

fn centeredLine(output: *std.ArrayList(u8), allocator: std.mem.Allocator, width: usize, visible_width: usize, parts: anytype) !void {
    try spaces(output, allocator, (width -| visible_width) / 2);
    return line(output, allocator, parts);
}

fn line(output: *std.ArrayList(u8), allocator: std.mem.Allocator, parts: anytype) !void {
    inline for (parts) |part| try output.appendSlice(allocator, part);
    try output.append(allocator, '\n');
}

fn spaces(output: *std.ArrayList(u8), allocator: std.mem.Allocator, count: usize) !void {
    try output.appendNTimes(allocator, ' ', count);
}

fn visibleLen(value: []const u8) usize {
    var count: usize = 0;
    var index: usize = 0;
    while (index < value.len) {
        if (value[index] == 0x1b) {
            index += 1;
            if (index < value.len and value[index] == '[') index += 1;
            while (index < value.len) : (index += 1) {
                const byte = value[index];
                if (byte >= 0x40 and byte <= 0x7e) {
                    index += 1;
                    break;
                }
            }
            continue;
        }
        const end = nextGraphemeBoundary(value, index);
        count += graphemeCellWidth(value[index..end]);
        index = end;
    }
    return count;
}

fn nextGraphemeBoundary(value: []const u8, start: usize) usize {
    const first_length = utf8Length(value[start..]);
    const first = std.unicode.utf8Decode(value[start..][0..first_length]) catch value[start];
    var index = start + first_length;
    if (isRegionalIndicator(first) and index < value.len) {
        const second_length = utf8Length(value[index..]);
        const second = std.unicode.utf8Decode(value[index..][0..second_length]) catch value[index];
        if (isRegionalIndicator(second)) return index + second_length;
    }
    while (index < value.len and value[index] != 0x1b) {
        const length = utf8Length(value[index..]);
        const codepoint = std.unicode.utf8Decode(value[index..][0..length]) catch value[index];
        if (codepoint == 0x200d) {
            index += length;
            if (index < value.len and value[index] != 0x1b) index += utf8Length(value[index..]);
            continue;
        }
        if (codepointWidth(codepoint) == 0 and codepoint != 0x1f) {
            index += length;
            continue;
        }
        break;
    }
    return index;
}

fn graphemeCellWidth(value: []const u8) usize {
    var width: usize = 0;
    var regional_indicators: usize = 0;
    var emoji_presentation = false;
    var index: usize = 0;
    while (index < value.len) {
        const length = utf8Length(value[index..]);
        const codepoint = std.unicode.utf8Decode(value[index..][0..length]) catch value[index];
        if (codepoint == 0x1f) width = @max(width, 1) else width = @max(width, codepointWidth(codepoint));
        if (codepoint >= 0x1f1e6 and codepoint <= 0x1f1ff) regional_indicators += 1;
        if (codepoint == 0x20e3) width = @max(width, 2);
        if (codepoint == 0xfe0f) emoji_presentation = true;
        index += length;
    }
    if (emoji_presentation) width = @max(width, 2);
    return if (regional_indicators >= 2) @max(width, 2) else width;
}

fn isRegionalIndicator(codepoint: u21) bool {
    return codepoint >= 0x1f1e6 and codepoint <= 0x1f1ff;
}

fn truncateCells(allocator: std.mem.Allocator, value: []const u8, max_cells: usize) ![]u8 {
    if (visibleLen(value) <= max_cells) return allocator.dupe(u8, value);
    if (max_cells == 0) return allocator.dupe(u8, "");
    const content_budget = max_cells - 1;
    var cells: usize = 0;
    var index: usize = 0;
    while (index < value.len) {
        const end = nextGraphemeBoundary(value, index);
        const width = graphemeCellWidth(value[index..end]);
        if (cells + width > content_budget) break;
        cells += width;
        index = end;
    }
    return std.mem.concat(allocator, u8, &.{ value[0..index], "…" });
}

fn codepointWidth(codepoint: u21) usize {
    if (codepoint == 0 or codepoint < 0x20 or (codepoint >= 0x7f and codepoint <= 0x9f)) return 0;
    if (codepoint == 0x200d or
        (codepoint >= 0x0300 and codepoint <= 0x036f) or
        (codepoint >= 0x1ab0 and codepoint <= 0x1aff) or
        (codepoint >= 0x1dc0 and codepoint <= 0x1dff) or
        (codepoint >= 0x20d0 and codepoint <= 0x20ff) or
        (codepoint >= 0xfe00 and codepoint <= 0xfe0f) or
        (codepoint >= 0xfe20 and codepoint <= 0xfe2f) or
        (codepoint >= 0x1f3fb and codepoint <= 0x1f3ff) or
        (codepoint >= 0xe0100 and codepoint <= 0xe01ef)) return 0;
    if ((codepoint >= 0x1100 and codepoint <= 0x115f) or
        codepoint == 0x2329 or codepoint == 0x232a or
        (codepoint >= 0x2e80 and codepoint <= 0xa4cf and codepoint != 0x303f) or
        (codepoint >= 0xac00 and codepoint <= 0xd7a3) or
        (codepoint >= 0xf900 and codepoint <= 0xfaff) or
        (codepoint >= 0xfe10 and codepoint <= 0xfe19) or
        (codepoint >= 0xfe30 and codepoint <= 0xfe6f) or
        (codepoint >= 0xff00 and codepoint <= 0xff60) or
        (codepoint >= 0xffe0 and codepoint <= 0xffe6) or
        (codepoint >= 0x1f300 and codepoint <= 0x1faff) or
        (codepoint >= 0x20000 and codepoint <= 0x3fffd)) return 2;
    return 1;
}

fn basename(path: []const u8) []const u8 {
    return std.fs.path.basename(path);
}

fn previousUtf8Boundary(value: []const u8, cursor: usize) usize {
    if (cursor == 0) return 0;
    const bounded = @min(cursor, value.len);
    var index: usize = 0;
    while (index < bounded) {
        const end = nextGraphemeBoundary(value, index);
        if (end >= bounded) return index;
        index = end;
    }
    return index;
}

fn nextUtf8Boundary(value: []const u8, cursor: usize) usize {
    if (cursor >= value.len) return value.len;
    var index: usize = 0;
    while (index < value.len) {
        const end = nextGraphemeBoundary(value, index);
        if (cursor < end) return end;
        index = end;
    }
    return value.len;
}

const Size = struct { width: usize, height: usize };

fn terminalSize(io: std.Io) !Size {
    var size = std.posix.winsize{ .row = 0, .col = 0, .xpixel = 0, .ypixel = 0 };
    const result = (try io.operate(.{ .device_io_control = .{
        .file = std.Io.File.stdout(),
        .code = std.posix.T.IOCGWINSZ,
        .arg = &size,
    } })).device_io_control;
    if (result < 0 or size.col == 0 or size.row == 0) return error.TerminalSizeUnavailable;
    return .{ .width = size.col, .height = size.row };
}

fn terminalWrite(io: std.Io, bytes: []const u8) !void {
    try std.Io.File.stdout().writeStreamingAll(io, bytes);
}

pub fn streamEventMessage(event: protocol.StreamEvent) ?[]const u8 {
    return switch (event) {
        .text_delta => |delta| delta.delta,
        else => null,
    };
}

test "responsive frame contains hierarchy and compact controls" {
    const allocator = std.testing.allocator;
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);
    try renderFrame(allocator, &output, .{
        .width = 72,
        .height = 24,
        .model = "test-model",
        .version = "0.1-alpha",
        .cwd = "/tmp/project",
        .messages = &.{},
        .input = "",
        .cursor = 0,
        .status = "Ready",
        .working = false,
    });
    try std.testing.expect(std.mem.indexOf(u8, output.items, "misul") != null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "0.1-alpha") != null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "Ask Misul") != null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "project") != null);
}

test "compact empty state stays within the terminal width" {
    const allocator = std.testing.allocator;
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);
    try renderFrame(allocator, &output, .{
        .width = 40,
        .height = 16,
        .model = "test-model",
        .version = "test",
        .cwd = "/tmp/project",
        .messages = &.{},
        .input = "",
        .cursor = 0,
        .status = "Ready",
        .working = false,
    });

    var rows = std.mem.splitScalar(u8, output.items, '\n');
    while (rows.next()) |row| try std.testing.expect(visibleLen(row) <= 40);
}

test "composer navigation and deletion preserve UTF-8 boundaries" {
    const allocator = std.testing.allocator;
    var app = App{
        .allocator = allocator,
        .io = std.testing.io,
        .executable = "misul",
        .model = "test",
        .version = "test",
        .cwd = "/tmp",
        .session_id = "session",
        .runtime = undefined,
        .render_disabled_for_test = true,
    };
    defer app.deinit();

    _ = try app.handleInput("aéz");
    try std.testing.expectEqual(@as(usize, 4), app.cursor);
    _ = try app.handleInput("\x1b[D\x1b[D");
    try std.testing.expectEqual(@as(usize, 1), app.cursor);
    _ = try app.handleInput("\x1b[C");
    try std.testing.expectEqual(@as(usize, 3), app.cursor);
    _ = try app.handleInput("\x7f");
    try std.testing.expectEqualStrings("az", app.input.items);
    try std.testing.expectEqual(@as(usize, 1), app.cursor);
    _ = try app.handleInput("\x1b[3~");
    try std.testing.expectEqualStrings("a", app.input.items);
    try std.testing.expectEqual(@as(usize, 1), app.cursor);

    app.input.clearRetainingCapacity();
    app.cursor = 0;
    _ = try app.handleInput("e\u{0301}x");
    _ = try app.handleInput("\x1b[D");
    try std.testing.expectEqual(@as(usize, "e\u{0301}".len), app.cursor);
    _ = try app.handleInput("\x1b[D");
    try std.testing.expectEqual(@as(usize, 0), app.cursor);

    app.input.clearRetainingCapacity();
    app.cursor = 0;
    _ = try app.handleInput("🇧🇬x");
    _ = try app.handleInput("\x1b[D\x1b[D");
    try std.testing.expectEqual(@as(usize, 0), app.cursor);
}

test "composer retains fragmented terminal sequences across reads" {
    const allocator = std.testing.allocator;
    var app = App{
        .allocator = allocator,
        .io = std.testing.io,
        .executable = "misul",
        .model = "test",
        .version = "test",
        .cwd = "/tmp",
        .session_id = "session",
        .runtime = undefined,
        .render_disabled_for_test = true,
    };
    defer app.deinit();

    _ = try app.handleInput("aéz");
    _ = try app.handleInput("\x1b");
    _ = try app.handleInput("[D");
    _ = try app.handleInput("\x7f");
    try std.testing.expectEqualStrings("az", app.input.items);

    app.input.clearRetainingCapacity();
    app.cursor = 0;
    _ = try app.handleInput("\x1b[20");
    _ = try app.handleInput("0~safe\x1b[2");
    _ = try app.handleInput("01~");
    try std.testing.expectEqualStrings("safe", app.input.items);

    _ = try app.handleInput("\x1b");
    try std.testing.expect(try app.handleInput("\x03"));

    app.input.clearRetainingCapacity();
    app.cursor = 0;
    _ = try app.handleInput("\x1b");
    _ = try app.handleInput("é");
    try std.testing.expectEqualStrings("é", app.input.items);

    _ = try app.handleInput("\x1b[");
    try std.testing.expect(try app.handleInput("\x03"));

    app.input.clearRetainingCapacity();
    app.cursor = 0;
    _ = try app.handleInput("\x1b[");
    _ = try app.handleInput("é");
    try std.testing.expectEqualStrings("é", app.input.items);
}

test "renderer preserves UTF-8 boundaries cell widths and terminal safety" {
    const allocator = std.testing.allocator;
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);
    const messages = [_]Message{.{
        .role = .assistant,
        .content = @constCast("\x1b[31mPWN\x1b[0m"),
    }};
    try renderFrame(allocator, &output, .{
        .width = 40,
        .height = 16,
        .model = "\x1b]52;c;MODEL\x07",
        .version = "\x1b]52;c;VERSION\x07\nSPOOF-VERSION",
        .cwd = "/tmp/\x1b]52;c;CWD\x07",
        .messages = &messages,
        .input = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaé",
        .cursor = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaé".len,
        .status = "Ready",
        .working = false,
    });

    try std.testing.expect(std.unicode.utf8ValidateSlice(output.items));
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\x1b[31mPWN\x1b[0m") == null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\\x1b[31mPWN\\x1b[0m") != null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\x1b]52;c;MODEL\x07") == null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\x1b]52;c;VERSION\x07") == null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\x1b]52;c;CWD\x07") == null);
    try std.testing.expect(std.mem.indexOf(u8, output.items, "\\x0aSPOOF-VERSION") != null);
    try std.testing.expectEqual(@as(usize, 3), visibleLen("é界"));
    try std.testing.expectEqual(@as(usize, 1), visibleLen("\x1f"));
    try std.testing.expectEqual(@as(usize, 2), visibleLen("🇧🇬"));
    try std.testing.expectEqual(@as(usize, 2), visibleLen("❤️"));

    var grapheme_output: std.ArrayList(u8) = .empty;
    defer grapheme_output.deinit(allocator);
    try renderFrame(allocator, &grapheme_output, .{
        .width = 40,
        .height = 16,
        .model = "test",
        .version = "test",
        .cwd = "/tmp",
        .messages = &.{},
        .input = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa👩‍💻",
        .cursor = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa👩‍💻".len,
        .status = "Ready",
        .working = false,
    });
    try std.testing.expect(std.mem.indexOf(u8, grapheme_output.items, "👩‍💻") != null);

    var long_model: [200]u8 = undefined;
    @memset(&long_model, 'm');
    var bounded_output: std.ArrayList(u8) = .empty;
    defer bounded_output.deinit(allocator);
    try renderFrame(allocator, &bounded_output, .{
        .width = 80,
        .height = 24,
        .model = &long_model,
        .version = "test",
        .cwd = "/tmp",
        .messages = &.{},
        .input = "",
        .cursor = 0,
        .status = "Ready",
        .working = false,
    });
    var rows = std.mem.splitScalar(u8, bounded_output.items, '\n');
    while (rows.next()) |row| try std.testing.expect(visibleLen(row) <= 80);

    var long_input: [240]u8 = undefined;
    @memset(&long_input, 'x');
    var cursor_output: std.ArrayList(u8) = .empty;
    defer cursor_output.deinit(allocator);
    try renderFrame(allocator, &cursor_output, .{
        .width = 40,
        .height = 16,
        .model = "test",
        .version = "test",
        .cwd = "/tmp",
        .messages = &.{},
        .input = &long_input,
        .cursor = 1,
        .status = "Ready",
        .working = false,
    });
    try std.testing.expect(std.mem.indexOf(u8, cursor_output.items, "▏") != null);

    var control_output: std.ArrayList(u8) = .empty;
    defer control_output.deinit(allocator);
    try renderFrame(allocator, &control_output, .{
        .width = 40,
        .height = 16,
        .model = "test",
        .version = "test",
        .cwd = "/tmp",
        .messages = &.{},
        .input = "\x1f",
        .cursor = 1,
        .status = "Ready",
        .working = false,
    });
    try std.testing.expectEqual(@as(usize, 1), std.mem.count(u8, control_output.items, "▏"));
    try std.testing.expect(std.mem.indexOf(u8, control_output.items, "\\x1f") != null);
}

fn renderFrameAllocationWitness(allocator: std.mem.Allocator) !void {
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(allocator);
    const messages = [_]Message{.{ .role = .assistant, .content = @constCast("provider text") }};
    try renderFrame(allocator, &output, .{
        .width = 72,
        .height = 24,
        .model = "test-model",
        .version = "test",
        .cwd = "/tmp",
        .messages = &messages,
        .input = "composer",
        .cursor = "composer".len,
        .status = "Ready",
        .working = false,
    });
}

test "renderer releases every owned line on allocation failure" {
    try std.testing.checkAllAllocationFailures(std.testing.allocator, renderFrameAllocationWitness, .{});
}

test "submitted prompt ownership survives a later render allocation failure" {
    var failing = std.testing.FailingAllocator.init(std.testing.allocator, .{});
    const allocator = failing.allocator();
    var app = App{
        .allocator = allocator,
        .io = std.testing.io,
        .executable = "misul",
        .model = "test",
        .version = "test",
        .cwd = "/tmp",
        .session_id = "session",
        .runtime = undefined,
    };
    try app.input.appendSlice(allocator, "owned");
    app.cursor = app.input.items.len;
    failing.fail_index = failing.alloc_index + 2;
    defer app.deinit();

    try std.testing.expectError(error.OutOfMemory, app.submit());
    try std.testing.expectEqual(@as(usize, 1), app.messages.items.len);
    try std.testing.expectEqual(@as(usize, 0), failing.deallocations);
}

test "appendError releases formatted content when message growth fails" {
    var failing = std.testing.FailingAllocator.init(std.testing.allocator, .{});
    const allocator = failing.allocator();
    var app = App{
        .allocator = allocator,
        .io = std.testing.io,
        .executable = "misul",
        .model = "test",
        .version = "test",
        .cwd = "/tmp",
        .session_id = "session",
        .runtime = undefined,
    };
    defer app.deinit();

    failing.fail_index = failing.alloc_index + 1;
    try std.testing.expectError(error.OutOfMemory, app.appendError(""));
    try std.testing.expectEqual(failing.allocated_bytes, failing.freed_bytes);
}

const TuiTestStreamState = struct {
    prompt: std.ArrayList(u8) = .empty,
};

fn tuiTestStream(raw: *anyopaque, model: *const protocol.Model, context: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    const state: *TuiTestStreamState = @ptrCast(@alignCast(raw));
    try state.prompt.appendSlice(std.testing.allocator, context.messages[context.messages.len - 1].user.content[0].text.text);
    const content = [_]protocol.AssistantContent{.{ .text = .{ .text = "provider-derived reply" } }};
    const message = protocol.AssistantMessage{
        .content = &content,
        .api = model.api,
        .provider = model.provider,
        .model = model.id,
        .stop_reason = .stop,
        .timestamp_ms = 1,
    };
    try sink(sink_context, .{ .text_delta = .{ .content_index = 0, .delta = "provider-derived reply" } });
    try sink(sink_context, .{ .done = message });
}

test "TUI submit reaches the shared runtime and displays provider content" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    var stream_state = TuiTestStreamState{};
    defer stream_state.prompt.deinit(allocator);
    const runtime = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "test-model", .context_window = 1024, .output_limit = 128 },
    }, &stream_state, tuiTestStream);
    defer runtime.destroy();
    var app = App{
        .allocator = allocator,
        .io = std.testing.io,
        .executable = "misul",
        .model = "test-model",
        .version = "test",
        .cwd = ".",
        .session_id = "tui-session",
        .runtime = runtime,
        .render_disabled_for_test = true,
    };
    defer app.deinit();
    try app.input.appendSlice(allocator, "prompt from TUI");
    app.cursor = app.input.items.len;

    try app.submit();
    try std.testing.expect(app.active_run_id != null);
    try app.finishActiveRun();

    try std.testing.expectEqualStrings("prompt from TUI", stream_state.prompt.items);
    try std.testing.expectEqual(@as(usize, 2), app.messages.items.len);
    try std.testing.expectEqualStrings("prompt from TUI", app.messages.items[0].content);
    try std.testing.expectEqualStrings("provider-derived reply", app.messages.items[1].content);
    try std.testing.expectEqualStrings("Ready", app.status);
}

fn tuiTestAuthenticationFailure(_: *anyopaque, model: *const protocol.Model, _: *const protocol.Context, _: protocol.StreamOptions, sink_context: *anyopaque, sink: protocol.StreamSink) !void {
    const failure = protocol.ProviderFailure{
        .category = .authentication,
        .redacted_message = "provider credential is unavailable",
    };
    try sink(sink_context, .{ .failed = .{
        .content = &.{},
        .api = model.api,
        .provider = model.provider,
        .model = model.id,
        .stop_reason = .@"error",
        .provider_failure = failure,
        .timestamp_ms = 1,
    } });
}

test "TUI displays the bounded provider diagnostic instead of a generic failure" {
    const allocator = std.testing.allocator;
    var state_tmp = std.testing.tmpDir(.{});
    defer state_tmp.cleanup();
    var state_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const state_len = try state_tmp.dir.realPath(std.testing.io, &state_buffer);
    const state_root = state_buffer[0..state_len];
    var environment = std.process.Environ.Map.init(allocator);
    defer environment.deinit();
    var stream_state: u8 = 0;
    const runtime = try runtime_module.Runtime.createForTest(allocator, std.testing.io, &environment, .{
        .workspace_root = state_root,
        .state_root = state_root,
        .system_prompt = "stable",
        .default_selection = .{ .provider = "test", .model = "test-model", .context_window = 1024, .output_limit = 128 },
    }, &stream_state, tuiTestAuthenticationFailure);
    defer runtime.destroy();
    var app = App{
        .allocator = allocator,
        .io = std.testing.io,
        .executable = "misul",
        .model = "test-model",
        .version = "test",
        .cwd = ".",
        .session_id = "tui-failure",
        .runtime = runtime,
        .render_disabled_for_test = true,
    };
    defer app.deinit();
    try app.input.appendSlice(allocator, "prompt");
    app.cursor = app.input.items.len;

    try app.submit();
    try std.testing.expect(app.active_run_id != null);
    try app.finishActiveRun();

    try std.testing.expectEqual(@as(usize, 2), app.messages.items.len);
    try std.testing.expectEqualStrings("Turn failed [authentication]: provider credential is unavailable", app.messages.items[1].content);
}

test "TUI renders reasoning tools compaction cancellation and usage events" {
    const allocator = std.testing.allocator;
    var app = App{
        .allocator = allocator,
        .io = std.testing.io,
        .executable = "misul",
        .model = "test-model",
        .version = "test",
        .cwd = ".",
        .session_id = "events",
        .runtime = undefined,
        .render_disabled_for_test = true,
    };
    defer app.deinit();

    try runtimeEvent(&app, .{ .run_id = 1, .session_id = "events", .sequence = 1, .payload = .{ .provider_stream = .{ .thinking_start = 0 } } });
    try runtimeEvent(&app, .{ .run_id = 1, .session_id = "events", .sequence = 2, .payload = .{ .provider_stream = .{ .thinking_delta = .{ .content_index = 0, .delta = "inspect evidence" } } } });
    try runtimeEvent(&app, .{ .run_id = 1, .session_id = "events", .sequence = 3, .payload = .{ .provider_stream = .{ .thinking_end = .{ .content_index = 0, .content = "inspect evidence" } } } });
    const tool = protocol.ToolCall{ .id = "tool-1", .name = "read_file", .arguments = .null };
    try runtimeEvent(&app, .{ .run_id = 1, .session_id = "events", .sequence = 4, .payload = .{ .tool_started = &tool } });
    const result = protocol.ToolResultMessage{ .tool_call_id = "tool-1", .tool_name = "read_file", .content = &.{}, .is_error = false, .timestamp_ms = 1 };
    try runtimeEvent(&app, .{ .run_id = 1, .session_id = "events", .sequence = 5, .payload = .{ .tool_finished = &result } });
    try runtimeEvent(&app, .{ .run_id = 1, .session_id = "events", .sequence = 6, .payload = .{ .compaction_observed = .{ .tokens_before = 4096, .first_kept_index = 3 } } });
    try runtimeEvent(&app, .{ .run_id = 1, .session_id = "events", .sequence = 7, .payload = .cancellation_requested });
    try runtimeEvent(&app, .{ .run_id = 1, .session_id = "events", .sequence = 8, .payload = .{ .settled = .{ .run_id = 1, .session_id = "events", .stop_reason = .complete, .step_count = 1, .usage = .{ .input = 7, .output = 3, .total_tokens = 10 } } } });

    try std.testing.expectEqualStrings("Canceling", app.status);
    try std.testing.expectEqual(@as(usize, 5), app.messages.items.len);
    try std.testing.expectEqualStrings("Reasoning: inspect evidence", app.messages.items[0].content);
    try std.testing.expectEqualStrings("Tool started: read_file", app.messages.items[1].content);
    try std.testing.expectEqualStrings("Tool finished: read_file", app.messages.items[2].content);
    try std.testing.expectEqualStrings("Context compacted: 4096 tokens before, kept from message 3", app.messages.items[3].content);
    try std.testing.expectEqualStrings("Usage: 7 input · 3 output · 10 total", app.messages.items[4].content);
}
