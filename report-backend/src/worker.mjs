const REPORT_PATH = "/api/phonecode/report";
const CATEGORIES = new Set([
  "hate",
  "harassment",
  "sexual",
  "violence",
  "self_harm",
  "illegal",
  "privacy",
  "other",
]);
const FIELDS = new Set(["version", "category", "appVersion", "platform", "note"]);
// Cleanup runs daily. Delete one interval early so normal scheduled execution keeps the public
// maximums below 90 days for reports and below 48 hours for rate-limit rows.
const REPORT_TTL_SECONDS = 89 * 24 * 60 * 60;
const RATE_TTL_SECONDS = 24 * 60 * 60;
const MAX_BODY_BYTES = 4096;

export default {
  fetch(request, env) {
    return handleRequest(request, env, Math.floor(Date.now() / 1000));
  },

  scheduled(controller, env, context) {
    context.waitUntil(cleanup(env.REPORTS_DB, Math.floor(controller.scheduledTime / 1000)));
  },
};

export async function handleRequest(request, env, now) {
  const path = new URL(request.url).pathname;
  if (path === REPORT_PATH && request.method === "POST") return submitReport(request, env, now);
  if (path.startsWith(`${REPORT_PATH}/`) && request.method === "DELETE") {
    return deleteReport(request, env, path.slice(REPORT_PATH.length + 1));
  }
  if (path === REPORT_PATH) return response(405, { error: "method_not_allowed" }, { Allow: "POST" });
  return response(404, { error: "not_found" });
}

async function submitReport(request, env, now) {
  const payload = await parsePayload(request);
  if (!payload) return response(400, { error: "invalid_request" });

  const ip = request.headers.get("CF-Connecting-IP")?.trim();
  if (!ip || typeof env.IP_HASH_SECRET !== "string" || env.IP_HASH_SECRET.length < 32) {
    return response(503, { error: "temporarily_unavailable" });
  }

  await cleanup(env.REPORTS_DB, now);
  const ipHash = await dailyIpHash(ip, env.IP_HASH_SECRET, now);
  const count = await env.REPORTS_DB.prepare(`
    INSERT INTO report_rate_limits (ip_hash, request_count, first_seen_at, last_seen_at)
    VALUES (?, 1, ?, ?)
    ON CONFLICT(ip_hash) DO UPDATE SET
      request_count = report_rate_limits.request_count + 1,
      last_seen_at = excluded.last_seen_at
    RETURNING request_count
  `).bind(ipHash, now, now).first("request_count");

  const limit = rateLimit(env.RATE_LIMIT_MAX);
  if (!Number.isInteger(count) || count > limit) {
    return response(429, { error: "rate_limited" }, { "Retry-After": String(secondsUntilNextUtcDay(now)) });
  }

  const id = `PCR-${crypto.randomUUID()}`;
  await env.REPORTS_DB.prepare(`
    INSERT INTO reports (id, protocol_version, category, note, app_version, platform, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `).bind(
    id,
    payload.version,
    payload.category,
    payload.note ?? null,
    payload.appVersion,
    payload.platform,
    now,
  ).run();

  return response(202, { id });
}

async function deleteReport(request, env, id) {
  if (!/^PCR-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id)) {
    return response(404, { error: "not_found" });
  }
  if (typeof env.ADMIN_TOKEN !== "string" || env.ADMIN_TOKEN.length < 32) {
    return response(503, { error: "temporarily_unavailable" });
  }
  const authorization = request.headers.get("Authorization") ?? "";
  const supplied = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
  if (!(await secretsEqual(supplied, env.ADMIN_TOKEN))) return response(401, { error: "unauthorized" });

  await env.REPORTS_DB.prepare("DELETE FROM reports WHERE id = ?").bind(id).run();
  return new Response(null, { status: 204, headers: noStoreHeaders() });
}

export async function cleanup(db, now) {
  await db.batch([
    db.prepare("DELETE FROM reports WHERE created_at < ?").bind(now - REPORT_TTL_SECONDS),
    db.prepare("DELETE FROM report_rate_limits WHERE last_seen_at < ?").bind(now - RATE_TTL_SECONDS),
  ]);
}

async function parsePayload(request) {
  const mediaType = request.headers.get("Content-Type")?.split(";", 1)[0].trim().toLowerCase();
  if (mediaType !== "application/json") return null;
  try {
    const text = await readTextLimited(request, MAX_BODY_BYTES);
    const value = JSON.parse(text);
    if (!value || Array.isArray(value) || typeof value !== "object") return null;
    if (Object.keys(value).some((key) => !FIELDS.has(key))) return null;
    if (value.version !== 1 || value.platform !== "android" || !CATEGORIES.has(value.category)) return null;
    if (typeof value.appVersion !== "string" || !value.appVersion.trim() || value.appVersion.length > 64) return null;
    if (value.note !== undefined && (typeof value.note !== "string" || value.note.trim().length > 1000)) return null;
    return {
      version: 1,
      category: value.category,
      appVersion: value.appVersion.trim(),
      platform: "android",
      ...(value.note?.trim() ? { note: value.note.trim() } : {}),
    };
  } catch {
    return null;
  }
}

async function readTextLimited(request, limit) {
  const declared = Number(request.headers.get("Content-Length"));
  if (Number.isFinite(declared) && declared > limit) throw new Error("body too large");
  if (!request.body) return "";

  const reader = request.body.getReader();
  const decoder = new TextDecoder();
  let bytes = 0;
  let text = "";
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    bytes += value.byteLength;
    if (bytes > limit) {
      await reader.cancel();
      throw new Error("body too large");
    }
    text += decoder.decode(value, { stream: true });
  }
  return text + decoder.decode();
}

async function dailyIpHash(ip, secret, now) {
  const day = new Date(now * 1000).toISOString().slice(0, 10);
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${day}\n${ip}`));
  return [...new Uint8Array(signature)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function secretsEqual(left, right) {
  const digest = async (value) => new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)),
  );
  const [a, b] = await Promise.all([digest(left), digest(right)]);
  let different = 0;
  for (let i = 0; i < a.length; i += 1) different |= a[i] ^ b[i];
  return different === 0;
}

function rateLimit(value) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= 1 && parsed <= 100 ? parsed : 5;
}

function secondsUntilNextUtcDay(now) {
  return 24 * 60 * 60 - (now % (24 * 60 * 60));
}

function response(status, body, headers = {}) {
  return Response.json(body, { status, headers: { ...noStoreHeaders(), ...headers } });
}

function noStoreHeaders() {
  return { "Cache-Control": "no-store" };
}
