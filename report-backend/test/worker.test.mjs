import assert from "node:assert/strict";
import { before, test } from "node:test";

let cleanup;
let handleRequest;
let worker;

before(async () => {
  try {
    ({ cleanup, handleRequest, default: worker } = await import("../src/worker.mjs"));
  } catch (error) {
    assert.fail(`report backend is missing: ${error.message}`);
  }
});

const DAY = 24 * 60 * 60;
const NOW = Date.UTC(2026, 6, 22, 12) / 1000;
const REPORT_PATH = "https://dttdrv.xyz/api/phonecode/report";

function request(body, {
  ip = "203.0.113.9",
  method = "POST",
  path = REPORT_PATH,
  token,
  contentType = "application/json",
} = {}) {
  const headers = new Headers({ "CF-Connecting-IP": ip });
  if (body !== undefined) headers.set("Content-Type", contentType);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return new Request(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

function validReport(overrides = {}) {
  return {
    version: 1,
    category: "privacy",
    appVersion: "0.4.0",
    platform: "android",
    note: "A private value appeared",
    ...overrides,
  };
}

function environment(overrides = {}) {
  return {
    REPORTS_DB: new MemoryD1(),
    IP_HASH_SECRET: "test-only-secret-that-is-more-than-32-bytes",
    ADMIN_TOKEN: "test-only-admin-token-that-is-more-than-32-bytes",
    RATE_LIMIT_MAX: "5",
    ...overrides,
  };
}

test("accepts the Android v1 payload and stores only its allowlisted fields", async () => {
  const env = environment();
  const response = await handleRequest(request(validReport()), env, NOW);

  assert.equal(response.status, 202);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  const { id } = await response.json();
  assert.match(id, /^PCR-[0-9a-f-]{36}$/);

  assert.deepEqual(env.REPORTS_DB.reports.get(id), {
    id,
    protocolVersion: 1,
    category: "privacy",
    note: "A private value appeared",
    appVersion: "0.4.0",
    platform: "android",
    createdAt: NOW,
  });
  assert.equal(env.REPORTS_DB.rates.size, 1);
  const [rate] = env.REPORTS_DB.rates.values();
  assert.match(rate.ipHash, /^[0-9a-f]{64}$/);
  assert.notEqual(rate.ipHash, "203.0.113.9");
});

test("rejects fields outside the Android report contract", async () => {
  const env = environment();
  const response = await handleRequest(
    request(validReport({ output: "the model response must never be collected" })),
    env,
    NOW,
  );

  assert.equal(response.status, 400);
  assert.equal(env.REPORTS_DB.reports.size, 0);
  assert.equal(env.REPORTS_DB.rates.size, 0);
});

test("requires the exact application/json media type while allowing parameters", async () => {
  const rejected = environment();
  const invalid = await handleRequest(
    request(validReport(), { contentType: "application/json-evil" }),
    rejected,
    NOW,
  );
  assert.equal(invalid.status, 400);
  assert.equal(rejected.REPORTS_DB.reports.size, 0);

  const accepted = environment();
  const valid = await handleRequest(
    request(validReport(), { contentType: "application/json; charset=utf-8" }),
    accepted,
    NOW,
  );
  assert.equal(valid.status, 202);
});

test("rejects invalid categories and notes longer than 1000 characters", async () => {
  for (const payload of [
    validReport({ category: "spam" }),
    validReport({ note: "x".repeat(1001) }),
  ]) {
    const env = environment();
    const response = await handleRequest(request(payload), env, NOW);
    assert.equal(response.status, 400);
    assert.equal(env.REPORTS_DB.reports.size, 0);
  }
});

test("fails closed when Cloudflare did not supply an IP or the hash secret is unsafe", async () => {
  for (const [ip, secret] of [["", "safe-test-secret-that-is-more-than-32-bytes"], ["203.0.113.9", "short"]]) {
    const env = environment({ IP_HASH_SECRET: secret });
    const response = await handleRequest(request(validReport(), { ip }), env, NOW);
    assert.equal(response.status, 503);
    assert.equal(env.REPORTS_DB.reports.size, 0);
    assert.equal(env.REPORTS_DB.rates.size, 0);
  }
});

test("rate limits an IP-derived daily hash without storing the raw IP", async () => {
  const env = environment({ RATE_LIMIT_MAX: "2" });

  assert.equal((await handleRequest(request(validReport()), env, NOW)).status, 202);
  assert.equal((await handleRequest(request(validReport()), env, NOW + 1)).status, 202);
  const limited = await handleRequest(request(validReport()), env, NOW + 2);

  assert.equal(limited.status, 429);
  assert.ok(Number(limited.headers.get("Retry-After")) > 0);
  assert.equal(env.REPORTS_DB.reports.size, 2);
  const [rate] = env.REPORTS_DB.rates.values();
  assert.equal(rate.requestCount, 3);
  assert.equal(JSON.stringify([...env.REPORTS_DB.rates.values()]).includes("203.0.113.9"), false);
});

test("uses a different rate-limit hash on the next UTC day", async () => {
  const env = environment({ RATE_LIMIT_MAX: "1" });

  assert.equal((await handleRequest(request(validReport()), env, NOW)).status, 202);
  assert.equal((await handleRequest(request(validReport()), env, NOW + DAY)).status, 202);

  assert.equal(env.REPORTS_DB.rates.size, 2);
  assert.equal(new Set(env.REPORTS_DB.rates.keys()).size, 2);
});

test("schedules report deletion at 89 days and rate-row deletion at 24 hours", async () => {
  const db = new MemoryD1();
  db.reports.set("old", storedReport("old", NOW - 89 * DAY - 1));
  db.reports.set("boundary", storedReport("boundary", NOW - 89 * DAY));
  db.rates.set("old", storedRate("old", NOW - DAY - 1));
  db.rates.set("boundary", storedRate("boundary", NOW - DAY));

  await cleanup(db, NOW);

  assert.deepEqual([...db.reports.keys()], ["boundary"]);
  assert.deepEqual([...db.rates.keys()], ["boundary"]);
});

test("runs retention cleanup during later report processing", async () => {
  const env = environment();
  env.REPORTS_DB.reports.set("expired", storedReport("expired", NOW - 89 * DAY - 1));
  env.REPORTS_DB.rates.set("expired", storedRate("expired", NOW - DAY - 1));

  const response = await handleRequest(request(validReport()), env, NOW);

  assert.equal(response.status, 202);
  assert.equal(env.REPORTS_DB.reports.has("expired"), false);
  assert.equal(env.REPORTS_DB.rates.has("expired"), false);
});

test("an authenticated operator can honor an early-deletion request without revealing existence", async () => {
  const env = environment();
  const id = "PCR-00000000-0000-4000-8000-000000000000";
  env.REPORTS_DB.reports.set(id, storedReport(id, NOW));
  const path = `${REPORT_PATH}/${id}`;

  assert.equal((await handleRequest(request(undefined, { method: "DELETE", path }), env, NOW)).status, 401);
  assert.equal(env.REPORTS_DB.reports.has(id), true);
  assert.equal((await handleRequest(request(undefined, { method: "DELETE", path, token: env.ADMIN_TOKEN }), env, NOW)).status, 204);
  assert.equal(env.REPORTS_DB.reports.has(id), false);
  assert.equal((await handleRequest(request(undefined, { method: "DELETE", path, token: env.ADMIN_TOKEN }), env, NOW)).status, 204);
});

test("rejects a malformed deletion reference without throwing", async () => {
  const env = environment();
  const response = await handleRequest(
    request(undefined, { method: "DELETE", path: `${REPORT_PATH}/PCR-%ZZ`, token: env.ADMIN_TOKEN }),
    env,
    NOW,
  );

  assert.equal(response.status, 404);
  assert.equal(env.REPORTS_DB.reports.size, 0);
});

test("exposes a scheduled cleanup handler for the configured cron trigger", async () => {
  const env = environment();
  env.REPORTS_DB.reports.set("expired", storedReport("expired", NOW - 89 * DAY - 1));
  let scheduledWork;

  worker.scheduled({ scheduledTime: NOW * 1000 }, env, {
    waitUntil(promise) { scheduledWork = promise; },
  });
  await scheduledWork;

  assert.equal(env.REPORTS_DB.reports.size, 0);
});

function storedReport(id, createdAt) {
  return {
    id,
    protocolVersion: 1,
    category: "other",
    note: null,
    appVersion: "0.4.0",
    platform: "android",
    createdAt,
  };
}

function storedRate(ipHash, lastSeenAt) {
  return { ipHash, requestCount: 1, firstSeenAt: lastSeenAt, lastSeenAt };
}

class MemoryD1 {
  reports = new Map();
  rates = new Map();

  prepare(sql) {
    return new MemoryStatement(this, sql);
  }

  async batch(statements) {
    return Promise.all(statements.map((statement) => statement.run()));
  }
}

class MemoryStatement {
  constructor(db, sql) {
    this.db = db;
    this.sql = sql.replace(/\s+/g, " ").trim();
    this.args = [];
  }

  bind(...args) {
    this.args = args;
    return this;
  }

  async first(column) {
    if (!this.sql.startsWith("INSERT INTO report_rate_limits")) throw new Error(`Unexpected first(): ${this.sql}`);
    const [ipHash, now] = this.args;
    const previous = this.db.rates.get(ipHash);
    const rate = previous
      ? { ...previous, requestCount: previous.requestCount + 1, lastSeenAt: now }
      : { ipHash, requestCount: 1, firstSeenAt: now, lastSeenAt: now };
    this.db.rates.set(ipHash, rate);
    return column ? rate.requestCount : { request_count: rate.requestCount };
  }

  async run() {
    if (this.sql.startsWith("DELETE FROM reports WHERE created_at")) {
      const [cutoff] = this.args;
      for (const [id, report] of this.db.reports) if (report.createdAt < cutoff) this.db.reports.delete(id);
    } else if (this.sql.startsWith("DELETE FROM report_rate_limits WHERE last_seen_at")) {
      const [cutoff] = this.args;
      for (const [hash, rate] of this.db.rates) if (rate.lastSeenAt < cutoff) this.db.rates.delete(hash);
    } else if (this.sql.startsWith("DELETE FROM reports WHERE id")) {
      this.db.reports.delete(this.args[0]);
    } else if (this.sql.startsWith("INSERT INTO reports")) {
      const [id, protocolVersion, category, note, appVersion, platform, createdAt] = this.args;
      this.db.reports.set(id, { id, protocolVersion, category, note, appVersion, platform, createdAt });
    } else {
      throw new Error(`Unexpected run(): ${this.sql}`);
    }
    return { success: true };
  }
}
