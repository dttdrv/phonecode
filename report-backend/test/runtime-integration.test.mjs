import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { Miniflare } from "miniflare";

const DAY = 24 * 60 * 60;

test("the real Workers runtime stores the Android contract and runs D1 retention", { timeout: 15_000 }, async () => {
  const mf = new Miniflare({
    modules: [{ type: "ESModule", path: fileURLToPath(new URL("../src/worker.mjs", import.meta.url)) }],
    compatibilityDate: "2026-07-22",
    cf: false,
    bindings: {
      IP_HASH_SECRET: "synthetic-runtime-secret-that-is-more-than-32-bytes",
      ADMIN_TOKEN: "synthetic-admin-secret-that-is-more-than-32-bytes",
      RATE_LIMIT_MAX: "5",
    },
    d1Databases: { REPORTS_DB: "00000000-0000-0000-0000-000000000001" },
  });

  try {
    const db = await mf.getD1Database("REPORTS_DB");
    const migration = await readFile(new URL("../migrations/0001_reports.sql", import.meta.url), "utf8");
    for (const statement of migration.split(";").map((sql) => sql.trim()).filter(Boolean)) {
      await db.prepare(statement).run();
    }

    const response = await mf.dispatchFetch("https://dttdrv.xyz/api/phonecode/report", {
      method: "POST",
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "CF-Connecting-IP": "203.0.113.44",
      },
      body: JSON.stringify({
        version: 1,
        category: "privacy",
        appVersion: "0.4.0",
        platform: "android",
        note: "synthetic runtime test",
      }),
    });

    assert.equal(response.status, 202);
    const { id } = await response.json();
    const report = await db.prepare(`
      SELECT protocol_version, category, note, app_version, platform FROM reports WHERE id = ?
    `).bind(id).first();
    assert.deepEqual(report, {
      protocol_version: 1,
      category: "privacy",
      note: "synthetic runtime test",
      app_version: "0.4.0",
      platform: "android",
    });

    const rate = await db.prepare("SELECT ip_hash FROM report_rate_limits").first();
    assert.match(rate.ip_hash, /^[0-9a-f]{64}$/);
    assert.notEqual(rate.ip_hash, "203.0.113.44");

    const scheduledAt = new Date();
    const expiredId = "PCR-00000000-0000-4000-8000-000000000000";
    await db.prepare(`
      INSERT INTO reports (id, protocol_version, category, note, app_version, platform, created_at)
      VALUES (?, 1, 'other', NULL, '0.4.0', 'android', ?)
    `).bind(expiredId, Math.floor(scheduledAt.getTime() / 1000) - 91 * DAY).run();

    const worker = await mf.getWorker();
    const scheduled = await worker.scheduled({ scheduledTime: scheduledAt, cron: "17 3 * * *" });
    assert.equal(scheduled.outcome, "ok");
    assert.equal(await db.prepare("SELECT id FROM reports WHERE id = ?").bind(expiredId).first(), null);
  } finally {
    await mf.dispose();
  }
});
