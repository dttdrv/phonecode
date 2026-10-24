const std = @import("std");
const action_receipt = @import("action_receipt.zig");

pub const default_driver_binary = "/usr/bin/safaridriver";
pub const default_driver_port: u16 = 4567;
pub const maximum_body_bytes = 1024 * 1024;
pub const maximum_snapshot_elements = 500;
pub const maximum_url_bytes = 2048;
pub const maximum_text_bytes = 4096;
pub const request_timeout_ms: u64 = 30_000;

pub const snapshot_js =
    \\(() => {
    \\  const out = [];
    \\  const skip = new Set(['SCRIPT','STYLE','NOSCRIPT','TEMPLATE','HEAD']);
    \\  const sel = 'a,button,input,select,textarea,[role=button],[role=link],[role=checkbox],[role=radio],[role=switch],[role=tab],[role=menuitem],[onclick]';
    \\  function rect(el){ const r = el.getBoundingClientRect(); return {x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height)}; }
    \\  function visible(el){ const r = el.getBoundingClientRect(); if (r.width < 2 || r.height < 2) return false; const s = getComputedStyle(el); return s.display !== 'none' && s.visibility !== 'hidden'; }
    \\  function role(el){ return el.getAttribute('role') || ({A:'link',BUTTON:'button',INPUT:'textbox',SELECT:'combobox',TEXTAREA:'textbox'}[el.tagName] || el.tagName.toLowerCase()); }
    \\  function name(el){ return (el.getAttribute('aria-label') || el.innerText || el.value || el.title || '').trim().slice(0, 120); }
    \\  function xpath(el){ const p=[]; let n=el; while(n && n.nodeType===1 && p.length<8){ let i=1,s=n.previousSibling; while(s){ if(s.nodeType===1 && s.tagName===n.tagName) i++; s=s.previousSibling; } p.unshift(n.tagName.toLowerCase()+'['+i+']'); n=n.parentNode; } return '//'+p.join('/'); }
    \\  function collect(root, prefix){
    \\    for (const el of root.querySelectorAll(sel)) {
    \\      if (out.length >= 500 || skip.has(el.tagName) || !visible(el)) continue;
    \\      out.push({i: out.length, role: role(el), name: name(el), tag: el.tagName.toLowerCase(), href: (el.getAttribute('href')||'').slice(0,256), value: ((el.value||'').toString()).slice(0,256), rect: rect(el), xpath: prefix + xpath(el)});
    \\    }
    \\    for (const f of root.querySelectorAll('iframe')) {
    \\      if (out.length >= 500) break;
    \\      try {
    \\        if (f.contentDocument) { collect(f.contentDocument, prefix + xpath(f) + '//'); continue; }
    \\      } catch(e) {}
    \\      if (visible(f)) out.push({i: out.length, role: 'frame', name: '(blocked frame)', tag: 'iframe', href: (f.getAttribute('src')||'').slice(0,256), value: '', rect: rect(f), xpath: prefix + xpath(f)});
    \\    }
    \\  }
    \\  collect(document, '');
    \\  return {url: location.href, title: document.title.slice(0,256), elements: out};
    \\})()
;

pub const extract_js = "(() => document.body ? document.body.innerText.slice(0, 20000) : '')()";

pub const scroll_js = "((x, y) => { window.scrollBy(x, y); return {x: window.scrollX, y: window.scrollY}; })";

pub const scroll_into_view_js = "((el) => { el.scrollIntoView({block: 'center', inline: 'center'}); const r = el.getBoundingClientRect(); return {x: Math.round(r.x), y: Math.round(r.y)}; })";

pub const select_js = "((el, value) => { el.value = value; el.dispatchEvent(new Event('input', {bubbles: true})); el.dispatchEvent(new Event('change', {bubbles: true})); return el.value; })";

pub const Response = struct {
    allocator: std.mem.Allocator,
    json: []u8,
    status: u16,

    pub fn deinit(self: *Response) void {
        self.allocator.free(self.json);
        self.* = undefined;
    }
};

const Race = union(enum) {
    response: anyerror!Response,
    watchdog: enum { timeout },
};

pub const Browser = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    environ: *const std.process.Environ.Map,
    mutex: std.Io.Mutex = .init,
    io_mutex: std.Io.Mutex = .init,
    driver: ?std.process.Child = null,
    port: u16 = default_driver_port,
    session_id: ?[]u8 = null,
    snapshot_json: ?[]u8 = null,

    pub fn init(allocator: std.mem.Allocator, io: std.Io, environ: *const std.process.Environ.Map) Browser {
        var port = default_driver_port;
        if (environ.get("MISUL_BROWSER_PORT")) |text| {
            port = std.fmt.parseUnsigned(u16, text, 10) catch default_driver_port;
            if (port == 0) port = default_driver_port;
        }
        return .{ .allocator = allocator, .io = io, .environ = environ, .port = port };
    }

    pub fn deinit(self: *Browser) void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        self.clearSessionLocked();
        self.stopDriverLocked();
    }

    pub fn available(self: *Browser) bool {
        const binary = self.environ.get("MISUL_BROWSER_BINARY") orelse default_driver_binary;
        std.Io.Dir.accessAbsolute(self.io, binary, .{}) catch return false;
        return true;
    }

    fn driverBinary(self: *Browser) []const u8 {
        return self.environ.get("MISUL_BROWSER_BINARY") orelse default_driver_binary;
    }

    fn baseUrl(self: *Browser) ![]u8 {
        return std.fmt.allocPrint(self.allocator, "http://127.0.0.1:{d}", .{self.port});
    }

    fn stopDriverLocked(self: *Browser) void {
        if (self.driver) |*child| child.kill(self.io);
        self.driver = null;
    }

    fn clearSessionLocked(self: *Browser) void {
        if (self.session_id) |id| self.allocator.free(id);
        self.session_id = null;
        if (self.snapshot_json) |snap| self.allocator.free(snap);
        self.snapshot_json = null;
    }

    fn ensureDriver(self: *Browser) !void {
        self.mutex.lockUncancelable(self.io);
        if (self.driver == null) {
            const binary = self.driverBinary();
            var port_buf: [8]u8 = undefined;
            const port_text = std.fmt.bufPrint(&port_buf, "{d}", .{self.port}) catch {
                self.mutex.unlock(self.io);
                return error.BrowserUnavailable;
            };
            self.driver = std.process.spawn(self.io, .{
                .argv = &.{ binary, "-p", port_text },
                .environ_map = self.environ,
                .stdin = .ignore,
                .stdout = .ignore,
                .stderr = .ignore,
            }) catch {
                self.mutex.unlock(self.io);
                return error.BrowserUnavailable;
            };
        }
        self.mutex.unlock(self.io);
        self.waitReady() catch |err| {
            self.mutex.lockUncancelable(self.io);
            self.stopDriverLocked();
            self.mutex.unlock(self.io);
            return err;
        };
    }

    fn waitReady(self: *Browser) !void {
        var attempt: u64 = 0;
        while (attempt < 100) : (attempt += 1) {
            var resp = self.fetch(.GET, "/status", null) catch {
                std.Io.sleep(self.io, .fromMilliseconds(50), .awake) catch return error.BrowserUnavailable;
                continue;
            };
            defer resp.deinit();
            var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, resp.json, .{}) catch continue;
            defer parsed.deinit();
            if (parsed.value == .object) {
                if (parsed.value.object.get("value")) |value| if (value == .object) {
                    if (value.object.get("ready")) |ready| if (ready == .bool and ready.bool) return;
                };
            }
            std.Io.sleep(self.io, .fromMilliseconds(50), .awake) catch return error.BrowserUnavailable;
        }
        return error.BrowserUnavailable;
    }

    fn fetch(self: *Browser, http_method: std.http.Method, path: []const u8, body: ?[]const u8) !Response {
        var resp = try self.raceFetch(http_method, path, body);
        errdefer resp.deinit();
        try checkEnvelope(self.allocator, resp.status, resp.json);
        return resp;
    }

    fn raceFetch(self: *Browser, http_method: std.http.Method, path: []const u8, body: ?[]const u8) !Response {
        var results: [2]Race = undefined;
        var select = std.Io.Select(Race).init(self.io, &results);
        try select.concurrent(.response, httpTask, .{ self, http_method, path, body });
        try select.concurrent(.watchdog, httpWatchdog, .{self.io});
        const first = try select.await();
        switch (first) {
            .response => |response| {
                select.cancelDiscard();
                return response;
            },
            .watchdog => {
                while (select.cancel()) |pending| switch (pending) {
                    .response => |response| {
                        var mutable = response;
                        if (mutable) |*value| value.deinit() else |_| {}
                    },
                    .watchdog => {},
                };
                return error.BrowserTimeout;
            },
        }
    }

    fn httpWatchdog(io: std.Io) @FieldType(Race, "watchdog") {
        const deadline = std.Io.Clock.Timestamp.now(io, .awake).addDuration(.{ .clock = .awake, .raw = .fromMilliseconds(@intCast(request_timeout_ms)) });
        while (true) {
            if (std.Io.Clock.Timestamp.now(io, .awake).compare(.gte, deadline)) return .timeout;
            std.Io.sleep(io, .fromMilliseconds(5), .awake) catch return .timeout;
        }
    }

    fn httpTask(self: *Browser, http_method: std.http.Method, path: []const u8, body: ?[]const u8) anyerror!Response {
        self.io_mutex.lockUncancelable(self.io);
        defer self.io_mutex.unlock(self.io);
        return self.doFetch(http_method, path, body);
    }

    fn doFetch(self: *Browser, http_method: std.http.Method, path: []const u8, body: ?[]const u8) !Response {
        const base = try self.baseUrl();
        defer self.allocator.free(base);
        const url = try std.fmt.allocPrint(self.allocator, "{s}{s}", .{ base, path });
        defer self.allocator.free(url);
        var client = std.http.Client{ .allocator = self.allocator, .io = self.io };
        defer client.deinit();
        const uri = std.Uri.parse(url) catch return error.BrowserUnavailable;
        var request = client.request(http_method, uri, .{
            .redirect_behavior = .unhandled,
            .headers = .{ .content_type = .{ .override = "application/json" } },
            .extra_headers = &.{},
        }) catch return error.BrowserUnavailable;
        defer request.deinit();
        const payload = body orelse "";
        if (payload.len != 0) {
            request.transfer_encoding = .{ .content_length = payload.len };
            var writer = request.sendBodyUnflushed(&.{}) catch return error.BrowserUnavailable;
            writer.writer.writeAll(payload) catch return error.BrowserUnavailable;
            writer.end() catch return error.BrowserUnavailable;
            request.connection.?.flush() catch return error.BrowserUnavailable;
        } else {
            request.sendBodiless() catch return error.BrowserUnavailable;
        }
        var response = request.receiveHead(&.{}) catch return error.BrowserUnavailable;
        const status: u16 = @intCast(@intFromEnum(response.head.status));
        var transfer: [8192]u8 = undefined;
        var decompress: std.http.Decompress = undefined;
        var reader = response.readerDecompressing(&transfer, &decompress, &.{});
        var out: std.Io.Writer.Allocating = std.Io.Writer.Allocating.initCapacity(self.allocator, 4096) catch return error.OutOfMemory;
        defer out.deinit();
        var chunk: [4096]u8 = undefined;
        while (true) {
            const n = reader.readSliceShort(&chunk) catch return error.BrowserUnavailable;
            if (n == 0) break;
            if (out.written().len + n > maximum_body_bytes) return error.BrowserResponseTooLarge;
            out.writer.writeAll(chunk[0..n]) catch return error.OutOfMemory;
        }
        const owned = try self.allocator.dupe(u8, out.written());
        return .{ .allocator = self.allocator, .json = owned, .status = status };
    }

    fn rawFetch(self: *Browser, http_method: std.http.Method, path: []const u8, body: ?[]const u8) !Response {
        return self.raceFetch(http_method, path, body);
    }

    fn checkEnvelope(allocator: std.mem.Allocator, status: u16, body: []const u8) !void {
        if (status >= 200 and status < 300) return;
        var parsed = std.json.parseFromSlice(std.json.Value, allocator, body, .{}) catch return error.BrowserInvalidResponse;
        defer parsed.deinit();
        const message: []const u8 = blk: {
            if (parsed.value != .object) break :blk "";
            const value = parsed.value.object.get("value") orelse break :blk "";
            if (value != .object) break :blk "";
            const text = value.object.get("message") orelse break :blk "";
            if (text != .string) break :blk "";
            break :blk text.string;
        };
        if (status == 404) return error.BrowserSessionGone;
        if (std.mem.indexOf(u8, message, "Allow remote automation") != null) return error.BrowserConsentRequired;
        return error.BrowserExternalFailure;
    }

    fn sessionPath(self: *Browser, suffix: []const u8) ![]u8 {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        const id = self.session_id orelse return error.BrowserNoSession;
        return std.fmt.allocPrint(self.allocator, "/session/{s}{s}", .{ id, suffix });
    }

    pub fn startSession(self: *Browser) ![]u8 {
        try self.ensureDriver();
        self.mutex.lockUncancelable(self.io);
        if (self.session_id) |id| {
            const duped = try self.allocator.dupe(u8, id);
            self.mutex.unlock(self.io);
            return duped;
        }
        self.mutex.unlock(self.io);
        var resp = try self.fetch(.POST, "/session", "{\"capabilities\":{\"alwaysMatch\":{\"browserName\":\"safari\"}}}");
        defer resp.deinit();
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, resp.json, .{}) catch return error.BrowserInvalidResponse;
        defer parsed.deinit();
        if (parsed.value != .object) return error.BrowserInvalidResponse;
        const value = parsed.value.object.get("value") orelse return error.BrowserInvalidResponse;
        if (value != .object) return error.BrowserInvalidResponse;
        const id_value = value.object.get("sessionId") orelse return error.BrowserInvalidResponse;
        if (id_value != .string or id_value.string.len == 0 or id_value.string.len > 256) return error.BrowserInvalidResponse;
        const fresh = try self.allocator.dupe(u8, id_value.string);
        errdefer self.allocator.free(fresh);
        self.mutex.lockUncancelable(self.io);
        if (self.session_id) |id| {
            const duped = try self.allocator.dupe(u8, id);
            self.mutex.unlock(self.io);
            self.deleteSessionById(fresh);
            self.allocator.free(fresh);
            return duped;
        }
        self.clearSessionLocked();
        self.session_id = fresh;
        const stored = try self.allocator.dupe(u8, fresh);
        self.mutex.unlock(self.io);
        return stored;
    }

    fn deleteSessionById(self: *Browser, id: []const u8) void {
        const path = std.fmt.allocPrint(self.allocator, "/session/{s}", .{id}) catch return;
        defer self.allocator.free(path);
        var resp = self.fetch(.DELETE, path, null) catch return;
        resp.deinit();
    }

    pub fn closeSession(self: *Browser) !void {
        self.mutex.lockUncancelable(self.io);
        const id = if (self.session_id) |stored| self.allocator.dupe(u8, stored) catch null else null;
        self.mutex.unlock(self.io);
        if (id) |session_id| {
            defer self.allocator.free(session_id);
            const path = std.fmt.allocPrint(self.allocator, "/session/{s}", .{session_id}) catch return error.OutOfMemory;
            defer self.allocator.free(path);
            var resp = self.fetch(.DELETE, path, null) catch |err| switch (err) {
                error.BrowserSessionGone => {
                    self.mutex.lockUncancelable(self.io);
                    self.clearSessionLocked();
                    self.mutex.unlock(self.io);
                    return;
                },
                else => return err,
            };
            resp.deinit();
        }
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        self.clearSessionLocked();
    }

    pub fn navigate(self: *Browser, url: []const u8) ![]u8 {
        try validateUrl(url);
        const path = try self.sessionPath("/url");
        defer self.allocator.free(path);
        const body = try std.fmt.allocPrint(self.allocator, "{{\"url\":{f}}}", .{std.json.fmt(url, .{})});
        defer self.allocator.free(body);
        var resp = try self.fetch(.POST, path, body);
        defer resp.deinit();
        return self.currentUrl();
    }

    pub fn currentUrl(self: *Browser) ![]u8 {
        const path = try self.sessionPath("/url");
        defer self.allocator.free(path);
        var resp = try self.fetch(.GET, path, null);
        defer resp.deinit();
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, resp.json, .{}) catch return error.BrowserInvalidResponse;
        defer parsed.deinit();
        if (parsed.value != .object) return error.BrowserInvalidResponse;
        const value = parsed.value.object.get("value") orelse return error.BrowserInvalidResponse;
        if (value != .string) return error.BrowserInvalidResponse;
        return self.allocator.dupe(u8, value.string);
    }

    pub fn snapshot(self: *Browser) ![]u8 {
        const path = try self.sessionPath("/execute/sync");
        defer self.allocator.free(path);
        const body = try std.fmt.allocPrint(self.allocator, "{{\"script\":{f},\"args\":[]}}", .{std.json.fmt(snapshot_js, .{})});
        defer self.allocator.free(body);
        var resp = try self.fetch(.POST, path, body);
        defer resp.deinit();
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, resp.json, .{}) catch return error.BrowserInvalidResponse;
        defer parsed.deinit();
        if (parsed.value != .object) return error.BrowserInvalidResponse;
        const value = parsed.value.object.get("value") orelse return error.BrowserInvalidResponse;
        if (value != .object) return error.BrowserInvalidResponse;
        const dialog = try self.dialogText();
        const owned = blk: {
            if (dialog) |text| {
                defer self.allocator.free(text);
                const tree = try std.json.Stringify.valueAlloc(self.allocator, value, .{});
                defer self.allocator.free(tree);
                var tree_parsed = std.json.parseFromSlice(std.json.Value, self.allocator, tree, .{}) catch return error.BrowserInvalidResponse;
                defer tree_parsed.deinit();
                break :blk try std.fmt.allocPrint(self.allocator, "{{\"url\":{f},\"title\":{f},\"dialog\":{f},\"elements\":{f}}}", .{
                    std.json.fmt(tree_parsed.value.object.get("url") orelse .null, .{}),
                    std.json.fmt(tree_parsed.value.object.get("title") orelse .null, .{}),
                    std.json.fmt(text, .{}),
                    std.json.fmt(tree_parsed.value.object.get("elements") orelse .null, .{}),
                });
            }
            break :blk try std.json.Stringify.valueAlloc(self.allocator, value, .{});
        };
        errdefer self.allocator.free(owned);
        try validateSnapshot(self.allocator, owned);
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        if (self.snapshot_json) |old| self.allocator.free(old);
        self.snapshot_json = try self.allocator.dupe(u8, owned);
        return owned;
    }

    pub fn resolveIndex(self: *Browser, index: i64) ![]u8 {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        const stored = self.snapshot_json orelse return error.BrowserNoSnapshot;
        return xpathForIndex(self.allocator, stored, index);
    }

    fn elementId(self: *Browser, xpath: []const u8) ![]u8 {
        const path = try self.sessionPath("/element");
        defer self.allocator.free(path);
        const body = try std.fmt.allocPrint(self.allocator, "{{\"using\":\"xpath\",\"value\":{f}}}", .{std.json.fmt(xpath, .{})});
        defer self.allocator.free(body);
        var resp = try self.fetch(.POST, path, body);
        defer resp.deinit();
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, resp.json, .{}) catch return error.BrowserInvalidResponse;
        defer parsed.deinit();
        if (parsed.value != .object) return error.BrowserInvalidResponse;
        const value = parsed.value.object.get("value") orelse return error.BrowserInvalidResponse;
        if (value != .object) return error.BrowserInvalidResponse;
        var it = value.object.iterator();
        while (it.next()) |entry| {
            if (std.mem.indexOf(u8, entry.key_ptr.*, "element-6066") != null and entry.value_ptr.* == .string) {
                return self.allocator.dupe(u8, entry.value_ptr.string);
            }
        }
        return error.BrowserInvalidResponse;
    }

    pub fn click(self: *Browser, index: i64) !void {
        const xpath = try self.resolveIndex(index);
        defer self.allocator.free(xpath);
        const eid = try self.elementId(xpath);
        defer self.allocator.free(eid);
        const path = try self.sessionPathElement(eid, "/click");
        defer self.allocator.free(path);
        var resp = try self.fetch(.POST, path, "{}");
        resp.deinit();
    }

    pub fn fill(self: *Browser, index: i64, text: []const u8) !void {
        if (text.len == 0 or text.len > maximum_text_bytes) return error.BrowserInvalidParams;
        const xpath = try self.resolveIndex(index);
        defer self.allocator.free(xpath);
        const eid = try self.elementId(xpath);
        defer self.allocator.free(eid);
        const clear_path = try self.sessionPathElement(eid, "/clear");
        defer self.allocator.free(clear_path);
        var cleared = try self.fetch(.POST, clear_path, "{}");
        cleared.deinit();
        const value_path = try self.sessionPathElement(eid, "/value");
        defer self.allocator.free(value_path);
        const body = try std.fmt.allocPrint(self.allocator, "{{\"text\":{f}}}", .{std.json.fmt(text, .{})});
        defer self.allocator.free(body);
        var resp = try self.fetch(.POST, value_path, body);
        resp.deinit();
    }

    pub fn fillPath(self: *Browser, index: i64, path: []const u8) !void {
        const xpath = try self.resolveIndex(index);
        defer self.allocator.free(xpath);
        const eid = try self.elementId(xpath);
        defer self.allocator.free(eid);
        const elem_path = try self.sessionPathElement(eid, "/value");
        defer self.allocator.free(elem_path);
        const body = try std.fmt.allocPrint(self.allocator, "{{\"text\":{f}}}", .{std.json.fmt(path, .{})});
        defer self.allocator.free(body);
        var resp = try self.fetch(.POST, elem_path, body);
        resp.deinit();
    }

    pub fn back(self: *Browser) !void {
        const path = try self.sessionPath("/back");
        defer self.allocator.free(path);
        var resp = try self.fetch(.POST, path, "{}");
        resp.deinit();
    }

    pub fn forward(self: *Browser) !void {
        const path = try self.sessionPath("/forward");
        defer self.allocator.free(path);
        var resp = try self.fetch(.POST, path, "{}");
        resp.deinit();
    }

    fn executeRaw(self: *Browser, script: []const u8, args_json: []const u8) ![]u8 {
        const path = try self.sessionPath("/execute/sync");
        defer self.allocator.free(path);
        const body = try std.fmt.allocPrint(self.allocator, "{{\"script\":{f},\"args\":{s}}}", .{ std.json.fmt(script, .{}), args_json });
        defer self.allocator.free(body);
        var resp = try self.fetch(.POST, path, body);
        defer resp.deinit();
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, resp.json, .{}) catch return error.BrowserInvalidResponse;
        defer parsed.deinit();
        if (parsed.value != .object) return error.BrowserInvalidResponse;
        const value = parsed.value.object.get("value") orelse return error.BrowserInvalidResponse;
        return std.json.Stringify.valueAlloc(self.allocator, value, .{});
    }

    fn executeOnElement(self: *Browser, script: []const u8, element_id: []const u8, extra_arg_json: ?[]const u8) ![]u8 {
        const args = if (extra_arg_json) |extra|
            try std.fmt.allocPrint(self.allocator, "[{{\"element-6066-11e4-a52e-4f735466cecf\":{f}}},{s}]", .{ std.json.fmt(element_id, .{}), extra })
        else
            try std.fmt.allocPrint(self.allocator, "[{{\"element-6066-11e4-a52e-4f735466cecf\":{f}}}]", .{std.json.fmt(element_id, .{})});
        defer self.allocator.free(args);
        return self.executeRaw(script, args);
    }

    pub fn pageScroll(self: *Browser, x: i64, y: i64) ![]u8 {
        const args = try std.fmt.allocPrint(self.allocator, "[{d},{d}]", .{ x, y });
        defer self.allocator.free(args);
        const out = try self.executeRaw(scroll_js, args);
        errdefer self.allocator.free(out);
        return out;
    }

    pub fn scrollElementIntoView(self: *Browser, index: i64) ![]u8 {
        const xpath = try self.resolveIndex(index);
        defer self.allocator.free(xpath);
        const eid = try self.elementId(xpath);
        defer self.allocator.free(eid);
        const out = try self.executeOnElement(scroll_into_view_js, eid, null);
        errdefer self.allocator.free(out);
        return out;
    }

    pub fn selectOption(self: *Browser, index: i64, value: []const u8) ![]u8 {
        if (value.len == 0 or value.len > 1024) return error.BrowserInvalidParams;
        const xpath = try self.resolveIndex(index);
        defer self.allocator.free(xpath);
        const eid = try self.elementId(xpath);
        defer self.allocator.free(eid);
        const arg = try std.fmt.allocPrint(self.allocator, "{f}", .{std.json.fmt(value, .{})});
        defer self.allocator.free(arg);
        const out = try self.executeOnElement(select_js, eid, arg);
        errdefer self.allocator.free(out);
        return out;
    }

    pub fn pageText(self: *Browser) ![]u8 {
        const out = try self.executeRaw(extract_js, "[]");
        errdefer self.allocator.free(out);
        if (out.len > 20480) return error.BrowserResponseTooLarge;
        return out;
    }

    pub fn screenshot(self: *Browser) ![]u8 {
        const path = try self.sessionPath("/screenshot");
        defer self.allocator.free(path);
        var resp = try self.fetch(.GET, path, null);
        defer resp.deinit();
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, resp.json, .{}) catch return error.BrowserInvalidResponse;
        defer parsed.deinit();
        if (parsed.value != .object) return error.BrowserInvalidResponse;
        const value = parsed.value.object.get("value") orelse return error.BrowserInvalidResponse;
        if (value != .string or value.string.len == 0 or value.string.len > maximum_body_bytes) return error.BrowserInvalidResponse;
        return self.allocator.dupe(u8, value.string);
    }

    pub fn dialogText(self: *Browser) !?[]u8 {
        const path = try self.sessionPath("/alert/text");
        defer self.allocator.free(path);
        var resp = try self.rawFetch(.GET, path, null);
        defer resp.deinit();
        if (resp.status == 404) return null;
        try checkEnvelope(self.allocator, resp.status, resp.json);
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, resp.json, .{}) catch return error.BrowserInvalidResponse;
        defer parsed.deinit();
        if (parsed.value != .object) return error.BrowserInvalidResponse;
        const value = parsed.value.object.get("value") orelse return error.BrowserInvalidResponse;
        if (value != .string) return error.BrowserInvalidResponse;
        const duped = try self.allocator.dupe(u8, value.string);
        return duped;
    }

    pub fn dialogRespond(self: *Browser, accept: bool) !void {
        const path = try self.sessionPath(if (accept) "/alert/accept" else "/alert/dismiss");
        defer self.allocator.free(path);
        var resp = try self.rawFetch(.POST, path, "{}");
        defer resp.deinit();
        if (resp.status == 404) return error.BrowserNoDialog;
        try checkEnvelope(self.allocator, resp.status, resp.json);
    }

    pub fn tabHandles(self: *Browser) ![]u8 {
        const path = try self.sessionPath("/window/handles");
        defer self.allocator.free(path);
        var resp = try self.fetch(.GET, path, null);
        defer resp.deinit();
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, resp.json, .{}) catch return error.BrowserInvalidResponse;
        defer parsed.deinit();
        if (parsed.value != .object) return error.BrowserInvalidResponse;
        const value = parsed.value.object.get("value") orelse return error.BrowserInvalidResponse;
        return std.json.Stringify.valueAlloc(self.allocator, value, .{});
    }

    pub fn currentHandle(self: *Browser) ![]u8 {
        const path = try self.sessionPath("/window");
        defer self.allocator.free(path);
        var resp = try self.fetch(.GET, path, null);
        defer resp.deinit();
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, resp.json, .{}) catch return error.BrowserInvalidResponse;
        defer parsed.deinit();
        if (parsed.value != .object) return error.BrowserInvalidResponse;
        const value = parsed.value.object.get("value") orelse return error.BrowserInvalidResponse;
        if (value != .string) return error.BrowserInvalidResponse;
        return self.allocator.dupe(u8, value.string);
    }

    pub fn newTab(self: *Browser, url: ?[]const u8) ![]u8 {
        if (url) |u| try validateUrl(u);
        const path = try self.sessionPath("/window/new");
        defer self.allocator.free(path);
        var resp = try self.fetch(.POST, path, "{\"type\":\"tab\"}");
        defer resp.deinit();
        var parsed = std.json.parseFromSlice(std.json.Value, self.allocator, resp.json, .{}) catch return error.BrowserInvalidResponse;
        defer parsed.deinit();
        if (parsed.value != .object) return error.BrowserInvalidResponse;
        const value = parsed.value.object.get("value") orelse return error.BrowserInvalidResponse;
        if (value != .object) return error.BrowserInvalidResponse;
        const handle = value.object.get("handle") orelse return error.BrowserInvalidResponse;
        if (handle != .string or handle.string.len == 0 or handle.string.len > 256) return error.BrowserInvalidResponse;
        const duped = try self.allocator.dupe(u8, handle.string);
        errdefer self.allocator.free(duped);
        if (url) |u| {
            const nav = try std.fmt.allocPrint(self.allocator, "{{\"handle\":{f}}}", .{std.json.fmt(duped, .{})});
            defer self.allocator.free(nav);
            var switched = try self.fetch(.POST, try self.sessionPath("/window"), nav);
            switched.deinit();
            const current = try self.navigate(u);
            self.allocator.free(current);
        }
        return duped;
    }

    pub fn switchTab(self: *Browser, handle: []const u8) !void {
        if (handle.len == 0 or handle.len > 256) return error.BrowserInvalidParams;
        const path = try self.sessionPath("/window");
        defer self.allocator.free(path);
        const body = try std.fmt.allocPrint(self.allocator, "{{\"handle\":{f}}}", .{std.json.fmt(handle, .{})});
        defer self.allocator.free(body);
        var resp = try self.fetch(.POST, path, body);
        resp.deinit();
    }

    pub fn closeTab(self: *Browser, handle: ?[]const u8) !void {
        if (handle) |h| {
            if (h.len == 0 or h.len > 256) return error.BrowserInvalidParams;
            const current = try self.currentHandle();
            defer self.allocator.free(current);
            if (!std.mem.eql(u8, current, h)) try self.switchTab(h);
        }
        const path = try self.sessionPath("/window");
        defer self.allocator.free(path);
        var resp = self.fetch(.DELETE, path, null) catch |err| switch (err) {
            error.BrowserSessionGone => {
                self.mutex.lockUncancelable(self.io);
                self.clearSessionLocked();
                self.mutex.unlock(self.io);
                return;
            },
            else => return err,
        };
        resp.deinit();
    }

    fn postActions(self: *Browser, actions_json: []const u8) !void {
        const path = try self.sessionPath("/actions");
        defer self.allocator.free(path);
        const body = try std.fmt.allocPrint(self.allocator, "{{\"actions\":{s}}}", .{actions_json});
        defer self.allocator.free(body);
        var resp = try self.fetch(.POST, path, body);
        resp.deinit();
        const release_path = try self.sessionPath("/actions");
        defer self.allocator.free(release_path);
        var released = try self.fetch(.DELETE, release_path, null);
        released.deinit();
    }

    pub fn pointerClick(self: *Browser, index: i64, button: u8, clicks: u8) !void {
        const xpath = try self.resolveIndex(index);
        defer self.allocator.free(xpath);
        const eid = try self.elementId(xpath);
        defer self.allocator.free(eid);
        const ref = try std.fmt.allocPrint(self.allocator, "{{\"element-6066-11e4-a52e-4f735466cecf\":{f}}}", .{std.json.fmt(eid, .{})});
        defer self.allocator.free(ref);
        var seq: std.Io.Writer.Allocating = std.Io.Writer.Allocating.init(self.allocator);
        defer seq.deinit();
        try seq.writer.writeAll("{\"type\":\"pointerMove\",\"duration\":100,\"origin\":");
        try seq.writer.writeAll(ref);
        try seq.writer.writeAll(",\"x\":0,\"y\":0},");
        var n: u8 = 0;
        while (n < clicks) : (n += 1) {
            try seq.writer.print("{{\"type\":\"pointerDown\",\"button\":{d}}},{{\"type\":\"pointerUp\",\"button\":{d}}},", .{ button, button });
        }
        const items = seq.written();
        const trimmed = if (items.len != 0 and items[items.len - 1] == ',') items[0 .. items.len - 1] else items;
        const actions = try std.fmt.allocPrint(self.allocator, "[{{\"type\":\"pointer\",\"id\":\"mouse\",\"parameters\":{{\"pointerType\":\"mouse\"}},\"actions\":[{s}]}}]", .{trimmed});
        defer self.allocator.free(actions);
        try self.postActions(actions);
    }

    pub fn hover(self: *Browser, index: i64) !void {
        const xpath = try self.resolveIndex(index);
        defer self.allocator.free(xpath);
        const eid = try self.elementId(xpath);
        defer self.allocator.free(eid);
        const actions = try std.fmt.allocPrint(self.allocator, "[{{\"type\":\"pointer\",\"id\":\"mouse\",\"parameters\":{{\"pointerType\":\"mouse\"}},\"actions\":[{{\"type\":\"pointerMove\",\"duration\":100,\"origin\":{{\"element-6066-11e4-a52e-4f735466cecf\":{f}}},\"x\":0,\"y\":0}}]}}]", .{std.json.fmt(eid, .{})});
        defer self.allocator.free(actions);
        try self.postActions(actions);
    }

    pub fn drag(self: *Browser, from_index: i64, to_index: i64) !void {
        const from_xpath = try self.resolveIndex(from_index);
        defer self.allocator.free(from_xpath);
        const from_eid = try self.elementId(from_xpath);
        defer self.allocator.free(from_eid);
        const to_xpath = try self.resolveIndex(to_index);
        defer self.allocator.free(to_xpath);
        const to_eid = try self.elementId(to_xpath);
        defer self.allocator.free(to_eid);
        const actions = try std.fmt.allocPrint(self.allocator, "[{{\"type\":\"pointer\",\"id\":\"mouse\",\"parameters\":{{\"pointerType\":\"mouse\"}},\"actions\":[{{\"type\":\"pointerMove\",\"duration\":100,\"origin\":{{\"element-6066-11e4-a52e-4f735466cecf\":{f}}},\"x\":0,\"y\":0}},{{\"type\":\"pointerDown\",\"button\":0}},{{\"type\":\"pointerMove\",\"duration\":300,\"origin\":{{\"element-6066-11e4-a52e-4f735466cecf\":{f}}},\"x\":0,\"y\":0}},{{\"type\":\"pointerUp\",\"button\":0}}]}}]", .{ std.json.fmt(from_eid, .{}), std.json.fmt(to_eid, .{}) });
        defer self.allocator.free(actions);
        try self.postActions(actions);
    }

    pub fn pressKey(self: *Browser, key: []const u8, modifiers: []const []const u8) !void {
        const code = try keyCode(key);
        if (modifiers.len > 3) return error.BrowserInvalidParams;
        var seq: std.Io.Writer.Allocating = std.Io.Writer.Allocating.init(self.allocator);
        defer seq.deinit();
        for (modifiers) |name| {
            const mod = try keyCode(name);
            try seq.writer.print("{{\"type\":\"keyDown\",\"value\":{f}}},", .{std.json.fmt(mod, .{})});
        }
        try seq.writer.print("{{\"type\":\"keyDown\",\"value\":{f}}},{{\"type\":\"keyUp\",\"value\":{f}}},", .{ std.json.fmt(code, .{}), std.json.fmt(code, .{}) });
        var i = modifiers.len;
        while (i != 0) {
            i -= 1;
            const mod = try keyCode(modifiers[i]);
            try seq.writer.print("{{\"type\":\"keyUp\",\"value\":{f}}},", .{std.json.fmt(mod, .{})});
        }
        const items = seq.written();
        const trimmed = if (items.len != 0 and items[items.len - 1] == ',') items[0 .. items.len - 1] else items;
        const actions = try std.fmt.allocPrint(self.allocator, "[{{\"type\":\"key\",\"id\":\"keyboard\",\"actions\":[{s}]}}]", .{trimmed});
        defer self.allocator.free(actions);
        try self.postActions(actions);
    }

    fn sessionPathElement(self: *Browser, element_id: []const u8, suffix: []const u8) ![]u8 {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        const id = self.session_id orelse return error.BrowserNoSession;
        return std.fmt.allocPrint(self.allocator, "/session/{s}/element/{s}{s}", .{ id, element_id, suffix });
    }
};

pub fn keyCode(name: []const u8) ![]const u8 {
    if (name.len == 1) {
        const byte = name[0];
        if (byte >= 0x20 and byte < 0x7f) return name;
        return error.BrowserInvalidParams;
    }
    const table = .{
        .{ "enter", "\u{E007}" },      .{ "tab", "\u{E004}" },        .{ "escape", "\u{E00C}" },      .{ "esc", "\u{E00C}" },
        .{ "backspace", "\u{E003}" },  .{ "delete", "\u{E017}" },     .{ "space", " " },              .{ "arrow_up", "\u{E013}" },
        .{ "arrow_down", "\u{E014}" }, .{ "arrow_left", "\u{E012}" }, .{ "arrow_right", "\u{E015}" }, .{ "home", "\u{E011}" },
        .{ "end", "\u{E010}" },        .{ "page_up", "\u{E00E}" },    .{ "page_down", "\u{E00F}" },   .{ "f1", "\u{E031}" },
        .{ "f2", "\u{E032}" },         .{ "f3", "\u{E033}" },         .{ "f4", "\u{E034}" },          .{ "f5", "\u{E035}" },
        .{ "f6", "\u{E036}" },         .{ "f7", "\u{E037}" },         .{ "f8", "\u{E038}" },          .{ "f9", "\u{E039}" },
        .{ "f10", "\u{E03A}" },        .{ "f11", "\u{E03B}" },        .{ "f12", "\u{E03C}" },         .{ "shift", "\u{E008}" },
        .{ "ctrl", "\u{E009}" },       .{ "control", "\u{E009}" },    .{ "alt", "\u{E00A}" },         .{ "meta", "\u{E00D}" },
        .{ "command", "\u{E00D}" },
    };
    inline for (table) |entry| {
        if (std.mem.eql(u8, name, entry[0])) return entry[1];
    }
    return error.BrowserInvalidParams;
}

pub fn validateUrl(url: []const u8) !void {
    if (url.len == 0 or url.len > maximum_url_bytes) return error.BrowserInvalidParams;
    if (!std.mem.startsWith(u8, url, "http://") and !std.mem.startsWith(u8, url, "https://")) return error.BrowserInvalidParams;
    if (std.mem.indexOfScalar(u8, url, ' ') != null) return error.BrowserInvalidParams;
}

pub fn validateSnapshot(allocator: std.mem.Allocator, json: []const u8) !void {
    var parsed = std.json.parseFromSlice(std.json.Value, allocator, json, .{}) catch return error.BrowserInvalidResponse;
    defer parsed.deinit();
    if (parsed.value != .object) return error.BrowserInvalidResponse;
    const url = parsed.value.object.get("url") orelse return error.BrowserInvalidResponse;
    if (url != .string or url.string.len == 0 or url.string.len > maximum_url_bytes) return error.BrowserInvalidResponse;
    const elements = parsed.value.object.get("elements") orelse return error.BrowserInvalidResponse;
    if (elements != .array or elements.array.items.len > maximum_snapshot_elements) return error.BrowserInvalidResponse;
    for (elements.array.items, 0..) |element, index| {
        if (element != .object) return error.BrowserInvalidResponse;
        const idx = element.object.get("i") orelse return error.BrowserInvalidResponse;
        const role = element.object.get("role") orelse return error.BrowserInvalidResponse;
        const xpath = element.object.get("xpath") orelse return error.BrowserInvalidResponse;
        const rect = element.object.get("rect") orelse return error.BrowserInvalidResponse;
        if (idx != .integer or idx.integer != @as(i64, @intCast(index)) or
            role != .string or role.string.len == 0 or role.string.len > 64 or
            xpath != .string or xpath.string.len == 0 or xpath.string.len > 4096) return error.BrowserInvalidResponse;
        if (rect != .object) return error.BrowserInvalidResponse;
        for ([_][]const u8{ "x", "y", "width", "height" }) |axis| {
            const edge = rect.object.get(axis) orelse return error.BrowserInvalidResponse;
            if (edge != .integer and edge != .float) return error.BrowserInvalidResponse;
        }
        for (elements.array.items[0..index]) |prior| {
            if (prior.object.get("i").?.integer == idx.integer) return error.BrowserInvalidResponse;
        }
    }
    if (parsed.value.object.get("dialog")) |dialog| {
        if (dialog != .null and dialog != .string) return error.BrowserInvalidResponse;
        if (dialog == .string and dialog.string.len > 1024) return error.BrowserInvalidResponse;
    }
}

pub fn xpathForIndex(allocator: std.mem.Allocator, snapshot_json: []const u8, index: i64) ![]u8 {
    if (index < 0) return error.BrowserBadIndex;
    var parsed = std.json.parseFromSlice(std.json.Value, allocator, snapshot_json, .{}) catch return error.BrowserInvalidResponse;
    defer parsed.deinit();
    if (parsed.value != .object) return error.BrowserInvalidResponse;
    const elements = parsed.value.object.get("elements") orelse return error.BrowserInvalidResponse;
    if (elements != .array or index >= elements.array.items.len) return error.BrowserBadIndex;
    const element = elements.array.items[@intCast(index)];
    if (element != .object) return error.BrowserInvalidResponse;
    const idx = element.object.get("i") orelse return error.BrowserInvalidResponse;
    const xpath = element.object.get("xpath") orelse return error.BrowserInvalidResponse;
    if (idx != .integer or idx.integer != index or xpath != .string) return error.BrowserBadIndex;
    return allocator.dupe(u8, xpath.string);
}

pub fn evidenceForIndex(buffer: []u8, method: []const u8, result_json: []const u8, index: i64, xpath: []const u8, duration_us: u64) ![]const u8 {
    var digest: action_receipt.Digest = undefined;
    std.crypto.hash.sha2.Sha256.hash(result_json, &digest, .{});
    const result_hex = std.fmt.bytesToHex(digest, .lower);
    var xpath_digest: action_receipt.Digest = undefined;
    std.crypto.hash.sha2.Sha256.hash(xpath, &xpath_digest, .{});
    const xpath_hex = std.fmt.bytesToHex(xpath_digest, .lower);
    return std.fmt.bufPrint(buffer, "v1;method={s};result_sha256={s};duration_us={d};index={d};xpath_sha256={s}", .{ method, &result_hex, duration_us, index, &xpath_hex }) catch error.ActionReceiptMismatch;
}

test "browser snapshot validation accepts bounded trees and rejects shape violations" {
    const valid = "{\"url\":\"https://example.com\",\"title\":\"Example\",\"elements\":[{\"i\":0,\"role\":\"link\",\"name\":\"More\",\"tag\":\"a\",\"href\":\"/more\",\"value\":\"\",\"rect\":{\"x\":8,\"y\":9,\"width\":40,\"height\":20},\"xpath\":\"//a[1]\"}],\"dialog\":null}";
    try validateSnapshot(std.testing.allocator, valid);
    try std.testing.expectError(error.BrowserInvalidResponse, validateSnapshot(std.testing.allocator, "{\"url\":\"https://example.com\",\"elements\":[{\"i\":1,\"role\":\"link\",\"rect\":{\"x\":0,\"y\":0,\"width\":1,\"height\":1},\"xpath\":\"//a[1]\"}]}"));
    try std.testing.expectError(error.BrowserInvalidResponse, validateSnapshot(std.testing.allocator, "{\"url\":\"https://example.com\",\"elements\":[{\"i\":0,\"role\":\"link\",\"rect\":{\"x\":0,\"y\":0,\"width\":1,\"height\":1},\"xpath\":\"//a[1]\"},{\"i\":0,\"role\":\"button\",\"rect\":{\"x\":0,\"y\":0,\"width\":1,\"height\":1},\"xpath\":\"//button[1]\"}]}"));
    try std.testing.expectError(error.BrowserInvalidResponse, validateSnapshot(std.testing.allocator, "{\"url\":\"https://example.com\",\"elements\":[{\"i\":0,\"role\":\"link\",\"xpath\":\"//a[1]\"}]}"));
    try std.testing.expectError(error.BrowserInvalidResponse, validateSnapshot(std.testing.allocator, "{\"url\":\"\",\"elements\":[]}"));
    try validateSnapshot(std.testing.allocator, "{\"url\":\"about:blank\",\"title\":\"\",\"elements\":[]}");
    try validateSnapshot(std.testing.allocator, "{\"url\":\"https://example.com\",\"title\":\"t\",\"dialog\":\"Confirm?\",\"elements\":[]}");
}

test "browser index resolution is range checked against the stored snapshot" {
    const snap = "{\"url\":\"https://example.com\",\"title\":\"t\",\"elements\":[{\"i\":0,\"role\":\"link\",\"name\":\"a\",\"tag\":\"a\",\"href\":\"\",\"value\":\"\",\"rect\":{\"x\":0,\"y\":0,\"width\":1,\"height\":1},\"xpath\":\"//a[1]\"}]}";
    const xpath = try xpathForIndex(std.testing.allocator, snap, 0);
    defer std.testing.allocator.free(xpath);
    try std.testing.expectEqualStrings("//a[1]", xpath);
    try std.testing.expectError(error.BrowserBadIndex, xpathForIndex(std.testing.allocator, snap, 1));
    try std.testing.expectError(error.BrowserBadIndex, xpathForIndex(std.testing.allocator, snap, -1));
}

test "browser key names resolve to W3C codes and reject unknown names" {
    try std.testing.expectEqualStrings("\u{E007}", try keyCode("enter"));
    try std.testing.expectEqualStrings("\u{E00C}", try keyCode("esc"));
    try std.testing.expectEqualStrings("a", try keyCode("a"));
    try std.testing.expectEqual(@as(usize, 3), (try keyCode("enter")).len);
    try std.testing.expectError(error.BrowserInvalidParams, keyCode("hyper"));
    try std.testing.expectError(error.BrowserInvalidParams, keyCode(""));
}

test "browser url validation rejects non-http targets and overlong input" {
    try validateUrl("https://example.com/search?q=misul");
    try std.testing.expectError(error.BrowserInvalidParams, validateUrl("file:///etc/passwd"));
    try std.testing.expectError(error.BrowserInvalidParams, validateUrl("javascript:alert(1)"));
    try std.testing.expectError(error.BrowserInvalidParams, validateUrl(""));
}
