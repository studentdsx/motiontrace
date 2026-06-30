const SESSION_TTL_MS = 1000 * 60 * 60 * 24 * 30;
const MAX_PAYLOAD_BYTES = 900 * 1024;
const PASSWORD_MIN_LENGTH = 8;
const PASSWORD_PBKDF2_ITERATIONS = 100000;
const DEFAULT_ADMIN_USERNAME = "admin";
const DEFAULT_ADMIN_PASSWORD = "Admin@1357";
const ADMIN_SESSION_COOKIE = "motiontrace_admin";
const ADMIN_SESSION_TTL_MS = 1000 * 60 * 60 * 8;

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (request.method === "OPTIONS") {
        return cors(new Response(null, { status: 204 }));
      }

      if (request.method === "GET" && url.pathname === "/health") {
        return json({ ok: true });
      }
      if (request.method === "GET" && (url.pathname === "/admin/login" || url.pathname === "/admin/login/")) {
        if (await isAdminAuthenticated(request, env)) {
          return redirect("/admin");
        }
        return html(adminLoginPage());
      }
      if (request.method === "POST" && url.pathname === "/admin/api/login") {
        return await adminLogin(request, env);
      }
      if (request.method === "POST" && url.pathname === "/admin/api/logout") {
        return adminLogout();
      }
      if (request.method === "GET" && (url.pathname === "/admin" || url.pathname === "/admin/")) {
        if (!(await isAdminAuthenticated(request, env))) {
          return redirect("/admin/login");
        }
        return html(adminPage());
      }
      if (request.method === "GET" && url.pathname === "/admin/api/submissions") {
        return await listSubmissions(request, env);
      }
      if (request.method === "GET" && url.pathname === "/admin/api/track-points") {
        return await listTrackPoints(request, env);
      }
      if (request.method === "GET" && url.pathname === "/admin/api/track-points.csv") {
        return await exportTrackPointsCsv(request, env);
      }
      if (request.method === "POST" && url.pathname === "/auth/register") {
        return await register(request, env);
      }
      if (request.method === "POST" && url.pathname === "/auth/login") {
        return await login(request, env);
      }
      if (request.method === "POST" && url.pathname === "/auth/change-password") {
        return await changePassword(request, env);
      }
      if (request.method === "POST" && url.pathname === "/sync/upload") {
        return await uploadSnapshot(request, env);
      }
      if (request.method === "GET" && url.pathname === "/sync/download") {
        return await downloadSnapshot(request, env);
      }

      return json({ error: "not_found" }, 404);
    } catch (error) {
      return json({ error: "server_error", message: String(error && error.message ? error.message : error) }, 500);
    }
  }
};

async function register(request, env) {
  const body = await readJson(request);
  const email = normalizeEmail(body.email);
  const password = String(body.password || "");
  if (!email || !isValidPassword(password)) {
    return json({ error: "invalid_credentials" }, 400);
  }

  const now = Date.now();
  const salt = randomToken(16);
  const user = {
    id: randomUuid(),
    email,
    password_salt: salt,
    password_hash: await hashPassword(password, salt),
    created_at: now,
    updated_at: now
  };

  try {
    await env.DB.prepare(
      "INSERT INTO users (id, email, password_salt, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
    ).bind(user.id, user.email, user.password_salt, user.password_hash, user.created_at, user.updated_at).run();
  } catch (error) {
    if (String(error).includes("UNIQUE")) {
      return json({ error: "email_exists" }, 409);
    }
    throw error;
  }

  const token = await createSession(env, user.id);
  return json({ token, user: { id: user.id, email: user.email } }, 201);
}

async function login(request, env) {
  const body = await readJson(request);
  const email = normalizeEmail(body.email);
  const password = String(body.password || "");
  if (!email || !password) {
    return json({ error: "invalid_credentials" }, 400);
  }

  const user = await env.DB.prepare("SELECT * FROM users WHERE email = ?").bind(email).first();
  if (!user) {
    return json({ error: "invalid_credentials" }, 401);
  }
  if (!(await verifyPassword(password, user))) {
    return json({ error: "invalid_credentials" }, 401);
  }
  await upgradePasswordHashIfNeeded(env, user, password);

  const token = await createSession(env, user.id);
  return json({ token, user: { id: user.id, email: user.email } });
}

async function changePassword(request, env) {
  const session = await requireSession(request, env);
  if (!session) {
    return json({ error: "unauthorized" }, 401);
  }

  const body = await readJson(request);
  const currentPassword = String(body.currentPassword || "");
  const newPassword = String(body.newPassword || "");
  if (!currentPassword || !isValidPassword(newPassword)) {
    return json({ error: "invalid_password" }, 400);
  }
  if (currentPassword === newPassword) {
    return json({ error: "password_unchanged" }, 400);
  }

  const user = await env.DB.prepare("SELECT * FROM users WHERE id = ?").bind(session.user_id).first();
  if (!user || !(await verifyPassword(currentPassword, user))) {
    return json({ error: "invalid_credentials" }, 401);
  }

  const now = Date.now();
  const salt = randomToken(16);
  const passwordHash = await hashPassword(newPassword, salt);
  await env.DB.prepare(
    "UPDATE users SET password_salt = ?, password_hash = ?, updated_at = ? WHERE id = ?"
  ).bind(salt, passwordHash, now, user.id).run();
  await env.DB.prepare("DELETE FROM sessions WHERE user_id = ? AND token <> ?").bind(user.id, session.token).run();
  return json({ ok: true });
}

async function uploadSnapshot(request, env) {
  const session = await requireSession(request, env);
  if (!session) {
    return json({ error: "unauthorized" }, 401);
  }

  const body = await readJson(request);
  const payload = typeof body.payload === "string" ? body.payload : JSON.stringify(body.payload || {});
  const size = new TextEncoder().encode(payload).length;
  if (size > MAX_PAYLOAD_BYTES) {
    return json({ error: "payload_too_large", maxBytes: MAX_PAYLOAD_BYTES }, 413);
  }

  const now = Date.now();
  const snapshot = parseSnapshot(payload);
  const summary = summarizeSnapshot(snapshot);
  const trackPoints = extractTrackPoints(snapshot, session.user_id, now);
  await env.DB.prepare(
    "INSERT INTO track_snapshots (user_id, payload, updated_at) VALUES (?, ?, ?) " +
      "ON CONFLICT(user_id) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at"
  ).bind(session.user_id, payload, now).run();
  await replaceTrackPoints(env, session.user_id, trackPoints);
  await env.DB.prepare(
    "INSERT INTO sync_submissions " +
      "(id, user_id, bytes, day_count, point_count, checkin_count, trip_count, created_at) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
  ).bind(
    randomUuid(),
    session.user_id,
    size,
    summary.days,
    summary.points,
    summary.checkins,
    summary.trips,
    now
  ).run();
  return json({ ok: true, updatedAt: now, bytes: size, trackPoints: trackPoints.length });
}

async function downloadSnapshot(request, env) {
  const session = await requireSession(request, env);
  if (!session) {
    return json({ error: "unauthorized" }, 401);
  }

  const snapshot = await env.DB.prepare(
    "SELECT payload, updated_at FROM track_snapshots WHERE user_id = ?"
  ).bind(session.user_id).first();
  if (!snapshot) {
    return json({ payload: "{}", updatedAt: 0 });
  }
  return json({ payload: snapshot.payload, updatedAt: snapshot.updated_at });
}

async function listSubmissions(request, env) {
  if (!(await isAdminAuthenticated(request, env))) {
    return json({ error: "unauthorized" }, 401);
  }

  const url = new URL(request.url);
  const rawLimit = Number.parseInt(url.searchParams.get("limit") || "50", 10);
  const limit = Math.max(1, Math.min(Number.isFinite(rawLimit) ? rawLimit : 50, 200));
  const email = String(url.searchParams.get("email") || "").trim().toLowerCase();

  let sql =
    "SELECT s.id, s.user_id, u.email, s.bytes, s.day_count, s.point_count, " +
    "s.checkin_count, s.trip_count, s.created_at " +
    "FROM sync_submissions s LEFT JOIN users u ON u.id = s.user_id";
  const binds = [];
  if (email) {
    sql += " WHERE lower(u.email) LIKE ?";
    binds.push("%" + email + "%");
  }
  sql += " ORDER BY s.created_at DESC LIMIT ?";
  binds.push(limit);

  const result = await env.DB.prepare(sql).bind(...binds).all();
  return json({
    items: (result.results || []).map((item) => ({
      id: item.id,
      userId: item.user_id,
      email: item.email || "",
      bytes: item.bytes || 0,
      dayCount: item.day_count || 0,
      pointCount: item.point_count || 0,
      checkinCount: item.checkin_count || 0,
      tripCount: item.trip_count || 0,
      createdAt: item.created_at || 0
    }))
  });
}

async function listTrackPoints(request, env) {
  if (!(await isAdminAuthenticated(request, env))) {
    return json({ error: "unauthorized" }, 401);
  }

  const query = buildTrackPointQuery(request, 1000);
  const result = await env.DB.prepare(query.sql).bind(...query.binds).all();
  return json({
    items: (result.results || []).map(formatTrackPointRow)
  });
}

async function exportTrackPointsCsv(request, env) {
  if (!(await isAdminAuthenticated(request, env))) {
    return json({ error: "unauthorized" }, 401);
  }

  const query = buildTrackPointQuery(request, 10000);
  const result = await env.DB.prepare(query.sql).bind(...query.binds).all();
  const csv = trackPointsCsv(result.results || []);
  return new Response(csv, {
    headers: {
      "Content-Type": "text/csv; charset=utf-8",
      "Content-Disposition": 'attachment; filename="motiontrace-track-points.csv"',
      "Cache-Control": "no-store"
    }
  });
}

function buildTrackPointQuery(request, maxLimit) {
  const url = new URL(request.url);
  const rawLimit = Number.parseInt(url.searchParams.get("limit") || String(maxLimit), 10);
  const limit = Math.max(1, Math.min(Number.isFinite(rawLimit) ? rawLimit : maxLimit, maxLimit));
  const user = String(url.searchParams.get("user") || url.searchParams.get("email") || "").trim().toLowerCase();
  const tripId = String(url.searchParams.get("tripId") || "").trim().toLowerCase();
  const date = String(url.searchParams.get("date") || "").trim();
  const start = parseTimeParam(url.searchParams.get("start") || url.searchParams.get("startTime"));
  const end = parseTimeParam(url.searchParams.get("end") || url.searchParams.get("endTime"));

  let sql =
    "SELECT p.id, p.user_id, u.email, p.date, p.trip_id, p.trip_index, p.point_index, " +
    "p.timestamp, p.longitude, p.latitude, p.accuracy, p.speed, p.created_at " +
    "FROM track_points p LEFT JOIN users u ON u.id = p.user_id";
  const clauses = [];
  const binds = [];

  if (user) {
    clauses.push("(lower(u.email) LIKE ? OR lower(p.user_id) LIKE ?)");
    binds.push("%" + user + "%", "%" + user + "%");
  }
  if (tripId) {
    clauses.push("lower(p.trip_id) LIKE ?");
    binds.push("%" + tripId + "%");
  }
  if (date) {
    clauses.push("p.date = ?");
    binds.push(date);
  }
  if (start > 0) {
    clauses.push("p.timestamp >= ?");
    binds.push(start);
  }
  if (end > 0) {
    clauses.push("p.timestamp <= ?");
    binds.push(end);
  }
  if (clauses.length) {
    sql += " WHERE " + clauses.join(" AND ");
  }
  sql += " ORDER BY p.timestamp DESC LIMIT ?";
  binds.push(limit);
  return { sql, binds };
}

function formatTrackPointRow(item) {
  return {
    id: item.id,
    userId: item.user_id,
    email: item.email || "",
    date: item.date || "",
    tripId: item.trip_id || "",
    tripIndex: item.trip_index || 0,
    pointIndex: item.point_index || 0,
    timestamp: item.timestamp || 0,
    longitude: Number(item.longitude || 0),
    latitude: Number(item.latitude || 0),
    accuracy: Number(item.accuracy || 0),
    speed: Number(item.speed || 0),
    createdAt: item.created_at || 0
  };
}

function trackPointsCsv(rows) {
  const header = [
    "id",
    "user_id",
    "email",
    "date",
    "trip_id",
    "trip_index",
    "point_index",
    "timestamp",
    "time",
    "longitude",
    "latitude",
    "accuracy",
    "speed"
  ];
  const lines = [header.join(",")];
  for (const row of rows) {
    lines.push([
      row.id,
      row.user_id,
      row.email || "",
      row.date,
      row.trip_id,
      row.trip_index,
      row.point_index,
      row.timestamp,
      row.timestamp ? new Date(Number(row.timestamp)).toISOString() : "",
      row.longitude,
      row.latitude,
      row.accuracy,
      row.speed
    ].map(csvCell).join(","));
  }
  return "\ufeff" + lines.join("\n");
}

function csvCell(value) {
  const text = String(value == null ? "" : value);
  return /[",\n\r]/.test(text) ? '"' + text.replace(/"/g, '""') + '"' : text;
}

function parseTimeParam(value) {
  const text = String(value || "").trim();
  if (!text) {
    return 0;
  }
  if (/^\d+$/.test(text)) {
    return Number.parseInt(text, 10);
  }
  const parsed = Date.parse(text);
  return Number.isFinite(parsed) ? parsed : 0;
}

async function adminLogin(request, env) {
  const body = await readJson(request);
  const username = String(body.username || "");
  const password = String(body.password || "");
  const expectedUsername = String(env.ADMIN_USERNAME || DEFAULT_ADMIN_USERNAME);
  const expectedPassword = String(env.ADMIN_PASSWORD || DEFAULT_ADMIN_PASSWORD);

  if (username !== expectedUsername || password !== expectedPassword) {
    return json({ error: "unauthorized" }, 401);
  }

  const expiresAt = Date.now() + ADMIN_SESSION_TTL_MS;
  const value = await signAdminSession(username, expiresAt, env);
  const response = json({ ok: true });
  response.headers.set("Set-Cookie", adminCookie(value, Math.floor(ADMIN_SESSION_TTL_MS / 1000)));
  return response;
}

function adminLogout() {
  const response = json({ ok: true });
  response.headers.set("Set-Cookie", adminCookie("", 0));
  return response;
}

async function isAdminAuthenticated(request, env) {
  const cookie = parseCookies(request.headers.get("Cookie") || "")[ADMIN_SESSION_COOKIE];
  if (!cookie) {
    return false;
  }
  const session = await verifyAdminSession(cookie, env);
  const expectedUsername = String(env.ADMIN_USERNAME || DEFAULT_ADMIN_USERNAME);
  return Boolean(session && session.username === expectedUsername);
}

async function signAdminSession(username, expiresAt, env) {
  const payload = base64UrlEncode(JSON.stringify({ username, expiresAt }));
  const signature = await hmac(payload, adminSessionSecret(env));
  return payload + "." + signature;
}

async function verifyAdminSession(value, env) {
  const parts = String(value || "").split(".");
  if (parts.length !== 2) {
    return null;
  }
  const expectedSignature = await hmac(parts[0], adminSessionSecret(env));
  if (parts[1] !== expectedSignature) {
    return null;
  }
  try {
    const payload = JSON.parse(base64UrlDecode(parts[0]));
    if (!payload || Number(payload.expiresAt || 0) < Date.now()) {
      return null;
    }
    return payload;
  } catch (error) {
    return null;
  }
}

function adminSessionSecret(env) {
  return String(env.ADMIN_SESSION_SECRET || env.ADMIN_PASSWORD || DEFAULT_ADMIN_PASSWORD);
}

function parseCookies(header) {
  const cookies = {};
  for (const part of header.split(";")) {
    const separator = part.indexOf("=");
    if (separator < 0) {
      continue;
    }
    const key = part.slice(0, separator).trim();
    const value = part.slice(separator + 1).trim();
    if (key) {
      cookies[key] = value;
    }
  }
  return cookies;
}

function adminCookie(value, maxAge) {
  return ADMIN_SESSION_COOKIE + "=" + value + "; Max-Age=" + maxAge + "; Path=/admin; HttpOnly; Secure; SameSite=Lax";
}

async function createSession(env, userId) {
  const now = Date.now();
  const token = randomToken(32);
  await env.DB.prepare(
    "INSERT INTO sessions (token, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)"
  ).bind(token, userId, now, now + SESSION_TTL_MS).run();
  return token;
}

async function requireSession(request, env) {
  const header = request.headers.get("Authorization") || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  if (!match) {
    return null;
  }
  const token = match[1].trim();
  const session = await env.DB.prepare(
    "SELECT token, user_id, expires_at FROM sessions WHERE token = ?"
  ).bind(token).first();
  if (!session || session.expires_at < Date.now()) {
    return null;
  }
  return session;
}

async function readJson(request) {
  const text = await request.text();
  if (!text) {
    return {};
  }
  return JSON.parse(text);
}

function normalizeEmail(value) {
  const email = String(value || "").trim().toLowerCase();
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) ? email : "";
}

function isValidPassword(password) {
  return typeof password === "string" && password.length >= PASSWORD_MIN_LENGTH && password.length <= 128;
}

async function hashPassword(password, salt, iterations = PASSWORD_PBKDF2_ITERATIONS) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    { name: "PBKDF2" },
    false,
    ["deriveBits"]
  );
  const bits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      hash: "SHA-256",
      salt: new TextEncoder().encode(salt),
      iterations
    },
    key,
    256
  );
  return "pbkdf2$" + iterations + "$" + base64Url(new Uint8Array(bits));
}

async function verifyPassword(password, user) {
  const stored = String(user.password_hash || "");
  if (stored.startsWith("pbkdf2$")) {
    const parts = stored.split("$");
    const iterations = Number.parseInt(parts[1] || "", 10) || PASSWORD_PBKDF2_ITERATIONS;
    return stored === (await hashPassword(password, user.password_salt, iterations));
  }
  return stored === (await legacyHashPassword(password, user.password_salt));
}

async function upgradePasswordHashIfNeeded(env, user, password) {
  const stored = String(user.password_hash || "");
  let shouldUpgrade = !!stored;
  if (stored.startsWith("pbkdf2$")) {
    const parts = stored.split("$");
    const iterations = Number.parseInt(parts[1] || "", 10) || 0;
    shouldUpgrade = iterations < PASSWORD_PBKDF2_ITERATIONS;
  }
  if (!shouldUpgrade) {
    return;
  }
  const salt = randomToken(16);
  const passwordHash = await hashPassword(password, salt);
  await env.DB.prepare(
    "UPDATE users SET password_salt = ?, password_hash = ?, updated_at = ? WHERE id = ?"
  ).bind(salt, passwordHash, Date.now(), user.id).run();
}

async function legacyHashPassword(password, salt) {
  const data = new TextEncoder().encode(`${salt}:${password}`);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return base64Url(new Uint8Array(digest));
}

function randomToken(bytes) {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return base64Url(buffer);
}

function randomUuid() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0"));
  return (
    hex.slice(0, 4).join("") + "-" +
    hex.slice(4, 6).join("") + "-" +
    hex.slice(6, 8).join("") + "-" +
    hex.slice(8, 10).join("") + "-" +
    hex.slice(10, 16).join("")
  );
}

function base64Url(bytes) {
  let value = "";
  for (const byte of bytes) {
    value += String.fromCharCode(byte);
  }
  return btoa(value).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64UrlEncode(value) {
  const bytes = new TextEncoder().encode(value);
  return base64Url(bytes);
}

function base64UrlDecode(value) {
  const normalized = String(value || "").replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new TextDecoder().decode(bytes);
}

async function hmac(value, secret) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value));
  return base64Url(new Uint8Array(signature));
}

function parseSnapshot(payload) {
  try {
    const root = JSON.parse(payload || "{}");
    return root && typeof root === "object" && !Array.isArray(root) ? root : {};
  } catch (error) {
    return {};
  }
}

function summarizeSnapshot(root) {
  const summary = { days: 0, points: 0, checkins: 0, trips: 0 };
  for (const value of Object.values(root || {})) {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      continue;
    }
    summary.days++;
    if (Array.isArray(value.points)) {
      summary.points += value.points.length;
    }
    if (Array.isArray(value.checkins)) {
      summary.checkins += value.checkins.length;
    }
    if (Array.isArray(value.trips)) {
      summary.trips += value.trips.length;
    }
  }
  return summary;
}

function extractTrackPoints(root, userId, createdAt) {
  const rows = [];
  for (const [date, day] of Object.entries(root || {})) {
    if (!day || typeof day !== "object" || Array.isArray(day)) {
      continue;
    }
    const points = Array.isArray(day.points) ? day.points : [];
    const trips = normalizeSnapshotTrips(day);
    for (let i = 0; i < points.length; i++) {
      const point = points[i] || {};
      const latitude = Number(point.latitude);
      const longitude = Number(point.longitude);
      const timestamp = Number(point.timestamp || 0);
      if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || timestamp <= 0) {
        continue;
      }
      const trip = findSnapshotTrip(trips, timestamp) || {
        id: "unassigned",
        index: 0
      };
      rows.push({
        id: randomUuid(),
        userId,
        date,
        tripId: trip.id,
        tripIndex: trip.index,
        pointIndex: i,
        timestamp,
        longitude,
        latitude,
        accuracy: finiteNumber(point.accuracy),
        speed: finiteNumber(point.speed),
        createdAt
      });
    }
  }
  return rows;
}

function normalizeSnapshotTrips(day) {
  const trips = [];
  if (Array.isArray(day.trips)) {
    for (let i = 0; i < day.trips.length; i++) {
      const trip = day.trips[i] || {};
      const startTime = Number(trip.startTime || 0);
      if (startTime <= 0) {
        continue;
      }
      trips.push({
        id: String(trip.id || "trip_" + (i + 1)),
        index: i + 1,
        startTime,
        endTime: Number(trip.endTime || 0)
      });
    }
  }
  if (!trips.length && Number(day.startTime || 0) > 0) {
    trips.push({
      id: "legacy_1",
      index: 1,
      startTime: Number(day.startTime || 0),
      endTime: Number(day.endTime || 0)
    });
  }
  return trips;
}

function findSnapshotTrip(trips, timestamp) {
  for (const trip of trips) {
    const endTime = trip.endTime > 0 ? trip.endTime : Number.MAX_SAFE_INTEGER;
    if (timestamp >= trip.startTime && timestamp <= endTime) {
      return trip;
    }
  }
  return null;
}

function finiteNumber(value) {
  const number = Number(value || 0);
  return Number.isFinite(number) ? number : 0;
}

async function replaceTrackPoints(env, userId, rows) {
  await env.DB.prepare("DELETE FROM track_points WHERE user_id = ?").bind(userId).run();
  if (!rows.length) {
    return;
  }
  const insert = env.DB.prepare(
    "INSERT INTO track_points " +
      "(id, user_id, date, trip_id, trip_index, point_index, timestamp, longitude, latitude, accuracy, speed, created_at) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
  );
  for (let offset = 0; offset < rows.length; offset += 100) {
    const batch = rows.slice(offset, offset + 100).map((row) => insert.bind(
      row.id,
      row.userId,
      row.date,
      row.tripId,
      row.tripIndex,
      row.pointIndex,
      row.timestamp,
      row.longitude,
      row.latitude,
      row.accuracy,
      row.speed,
      row.createdAt
    ));
    await env.DB.batch(batch);
  }
}

function adminPage() {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>MotionTrace 轨迹管理</title>
  <style>
    :root {
      color-scheme: light;
      --bg: #f6f7f2;
      --surface: #ffffff;
      --surface-2: #f0f3ec;
      --ink: #17211c;
      --muted: #68756f;
      --line: #dfe5dc;
      --line-strong: #c8d4ca;
      --green: #1f7255;
      --green-dark: #14503c;
      --blue: #2b5876;
      --amber: #a76321;
      --red: #b64b3d;
      --shadow: 0 12px 30px rgba(26, 40, 33, 0.08);
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      background: var(--bg);
      color: var(--ink);
      font-family: "Microsoft YaHei UI", "Segoe UI", ui-sans-serif, system-ui, sans-serif;
      letter-spacing: 0;
    }
    .shell {
      width: min(1280px, calc(100vw - 28px));
      margin: 0 auto;
      padding: 24px 0 40px;
    }
    header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      margin-bottom: 16px;
    }
    .brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
    .mark {
      width: 42px;
      height: 42px;
      display: grid;
      place-items: center;
      border-radius: 8px;
      background: #193f34;
      color: #f6fff8;
      font-weight: 800;
      box-shadow: var(--shadow);
    }
    h1 {
      margin: 0;
      font-size: 24px;
      line-height: 1.15;
    }
    .sub {
      margin: 5px 0 0;
      color: var(--muted);
      font-size: 14px;
    }
    .actions { display: flex; align-items: center; gap: 10px; }
    .status {
      min-width: 132px;
      padding: 8px 12px;
      border: 1px solid var(--line);
      background: var(--surface);
      border-radius: 8px;
      color: var(--muted);
      font-size: 13px;
      text-align: center;
    }
    .status.ok { color: var(--green); border-color: #bfdbc9; background: #f2faf5; }
    .status.err { color: var(--red); border-color: #edc8c2; background: #fff6f4; }
    .filters {
      display: grid;
      grid-template-columns: minmax(190px, 1.1fr) minmax(150px, 0.9fr) 150px 178px 178px 112px auto auto;
      gap: 11px;
      align-items: end;
      padding: 14px;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--surface);
      box-shadow: var(--shadow);
    }
    label {
      display: block;
      margin: 0 0 6px;
      color: #435149;
      font-size: 12px;
      font-weight: 700;
    }
    input, select, button {
      height: 38px;
      border-radius: 7px;
      border: 1px solid var(--line);
      font: inherit;
      font-size: 14px;
    }
    input, select {
      width: 100%;
      padding: 0 12px;
      color: var(--ink);
      background: #fbfcfb;
      outline-color: #8db69f;
    }
    button {
      padding: 0 16px;
      border-color: transparent;
      background: var(--green);
      color: #fff;
      cursor: pointer;
      white-space: nowrap;
      font-weight: 700;
    }
    button.secondary {
      color: var(--green-dark);
      border-color: #cfe1d5;
      background: #f6fbf8;
    }
    button.ghost {
      color: var(--muted);
      border-color: var(--line);
      background: var(--surface);
    }
    button:hover { filter: brightness(0.97); }
    .viewbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin: 14px 0 10px;
    }
    .tabs {
      display: inline-flex;
      padding: 3px;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--surface);
    }
    .tab {
      height: 32px;
      padding: 0 14px;
      border: 0;
      border-radius: 6px;
      background: transparent;
      color: var(--muted);
    }
    .tab.active {
      color: #fff;
      background: var(--blue);
    }
    .stats {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 10px;
      margin: 14px 0;
    }
    .metric {
      border: 1px solid var(--line);
      background: var(--surface);
      border-radius: 8px;
      padding: 13px 14px;
      min-height: 78px;
    }
    .metric b {
      display: block;
      font-size: 23px;
      line-height: 1.1;
    }
    .metric span {
      display: block;
      margin-top: 8px;
      color: var(--muted);
      font-size: 13px;
    }
    .panel {
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--surface);
      overflow: hidden;
    }
    .table-wrap {
      overflow: auto;
      max-height: 540px;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      min-width: 1040px;
    }
    th, td {
      padding: 11px 12px;
      border-bottom: 1px solid var(--line);
      text-align: left;
      font-size: 13px;
      white-space: nowrap;
    }
    th {
      position: sticky;
      top: 0;
      background: var(--surface-2);
      color: #405047;
      font-weight: 700;
      z-index: 1;
    }
    tbody tr:hover { background: #fbfcf8; }
    tbody tr:last-child td { border-bottom: 0; }
    .mono {
      font-family: ui-monospace, "SFMono-Regular", Consolas, monospace;
      color: #45524b;
    }
    .chip {
      display: inline-flex;
      align-items: center;
      min-height: 24px;
      padding: 0 8px;
      border-radius: 999px;
      background: #eef6f1;
      color: var(--green-dark);
      font-size: 12px;
      font-weight: 700;
    }
    .map-panel {
      display: none;
      padding: 12px;
    }
    .map-panel.active { display: block; }
    .table-panel.hidden { display: none; }
    .map-box {
      min-height: 540px;
      border: 1px solid var(--line-strong);
      border-radius: 8px;
      background:
        linear-gradient(90deg, rgba(43, 88, 118, 0.06) 1px, transparent 1px),
        linear-gradient(0deg, rgba(43, 88, 118, 0.06) 1px, transparent 1px),
        #fbfcf7;
      background-size: 48px 48px;
      overflow: hidden;
      position: relative;
    }
    .map-box svg {
      width: 100%;
      height: 540px;
      display: block;
    }
    .map-legend {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      padding: 10px 2px 0;
      color: var(--muted);
      font-size: 12px;
    }
    .legend-item {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      max-width: 360px;
    }
    .legend-color {
      width: 18px;
      height: 3px;
      border-radius: 999px;
      background: var(--green);
      flex: 0 0 auto;
    }
    .empty {
      padding: 28px;
      color: var(--muted);
      text-align: center;
    }
    .map-empty {
      position: absolute;
      inset: 0;
      display: grid;
      place-items: center;
      padding: 24px;
      color: var(--muted);
      text-align: center;
    }
    @media (max-width: 760px) {
      header { display: block; }
      .actions { margin-top: 12px; align-items: stretch; }
      .status { text-align: left; flex: 1; }
      .filters { grid-template-columns: 1fr; }
      .viewbar { display: block; }
      .tabs { margin-bottom: 8px; }
      .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .map-box, .map-box svg { min-height: 430px; height: 430px; }
    }
  </style>
</head>
<body>
  <main class="shell">
    <header>
      <div class="brand">
        <div class="mark">MT</div>
        <div>
          <h1>MotionTrace 轨迹管理</h1>
          <p class="sub">查询云端 GPS 点位，导出 CSV，并查看历史轨迹</p>
        </div>
      </div>
      <div class="actions">
        <div id="status" class="status">已登录</div>
        <button id="logout" class="ghost">退出</button>
      </div>
    </header>

    <section class="filters" aria-label="查询条件">
      <div>
        <label for="user">用户</label>
        <input id="user" type="search" placeholder="邮箱或用户 ID">
      </div>
      <div>
        <label for="tripId">行程 ID</label>
        <input id="tripId" type="search" placeholder="trip_1 / 行程 ID">
      </div>
      <div>
        <label for="date">轨迹日期</label>
        <input id="date" type="date">
      </div>
      <div>
        <label for="startTime">开始时间</label>
        <input id="startTime" type="datetime-local">
      </div>
      <div>
        <label for="endTime">结束时间</label>
        <input id="endTime" type="datetime-local">
      </div>
      <div>
        <label for="limit">加载条数</label>
        <select id="limit">
          <option value="100">100 条</option>
          <option value="300">300 条</option>
          <option value="500" selected>500 条</option>
          <option value="1000">1000 条</option>
        </select>
      </div>
      <button id="load">查询</button>
      <button id="clear" class="secondary">清除</button>
    </section>

    <section class="viewbar" aria-label="视图切换">
      <div class="tabs" role="tablist">
        <button id="tableTab" class="tab active" type="button">列表</button>
        <button id="mapTab" class="tab" type="button">地图</button>
      </div>
      <button id="exportCsv" class="secondary">导出 CSV</button>
    </section>

    <section class="stats" aria-label="汇总">
      <div class="metric"><b id="mCount">0</b><span>GPS 点位</span></div>
      <div class="metric"><b id="mUsers">0</b><span>用户数</span></div>
      <div class="metric"><b id="mTrips">0</b><span>行程数</span></div>
      <div class="metric"><b id="mRange">-</b><span>时间范围</span></div>
    </section>

    <section class="panel">
      <div id="tablePanel" class="table-panel">
        <div class="table-wrap" aria-label="轨迹点列表">
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>用户</th>
                <th>日期</th>
                <th>行程</th>
                <th>点序号</th>
                <th>经度</th>
                <th>纬度</th>
                <th>速度</th>
                <th>精度</th>
                <th>点位 ID</th>
              </tr>
            </thead>
            <tbody id="rows">
              <tr><td colspan="10" class="empty">正在加载轨迹点</td></tr>
            </tbody>
          </table>
        </div>
      </div>
      <div id="mapPanel" class="map-panel">
        <div id="mapBox" class="map-box">
          <div class="map-empty">正在加载轨迹地图</div>
        </div>
        <div id="mapLegend" class="map-legend"></div>
      </div>
    </section>
  </main>

  <script>
    var filters = {
      user: document.getElementById("user"),
      tripId: document.getElementById("tripId"),
      date: document.getElementById("date"),
      startTime: document.getElementById("startTime"),
      endTime: document.getElementById("endTime"),
      limit: document.getElementById("limit")
    };
    var rows = document.getElementById("rows");
    var statusBox = document.getElementById("status");
    var tablePanel = document.getElementById("tablePanel");
    var mapPanel = document.getElementById("mapPanel");
    var tableTab = document.getElementById("tableTab");
    var mapTab = document.getElementById("mapTab");
    var mapBox = document.getElementById("mapBox");
    var mapLegend = document.getElementById("mapLegend");
    var currentItems = [];

    document.getElementById("load").addEventListener("click", load);
    document.getElementById("clear").addEventListener("click", clearFilters);
    document.getElementById("logout").addEventListener("click", logout);
    document.getElementById("exportCsv").addEventListener("click", exportCsv);
    tableTab.addEventListener("click", function () { setView("table"); });
    mapTab.addEventListener("click", function () { setView("map"); });
    Object.keys(filters).forEach(function (key) {
      filters[key].addEventListener("keydown", function (event) {
        if (event.key === "Enter") load();
      });
    });
    load();

    async function load() {
      setStatus("查询中", "");
      try {
        var response = await fetch("/admin/api/track-points?" + buildParams().toString(), { credentials: "same-origin" });
        var payload = await response.json().catch(function () { return {}; });
        if (!response.ok) {
          if (response.status === 401) {
            location.href = "/admin/login";
            return;
          }
          throw new Error(errorMessage(payload.error));
        }
        currentItems = payload.items || [];
        renderRows(currentItems);
        renderMetrics(currentItems);
        renderMap(currentItems);
        setStatus("已加载 " + currentItems.length + " 点", "ok");
      } catch (error) {
        currentItems = [];
        rows.innerHTML = '<tr><td colspan="10" class="empty">' + escapeHtml(error.message || "查询失败") + '</td></tr>';
        mapBox.innerHTML = '<div class="map-empty">' + escapeHtml(error.message || "查询失败") + '</div>';
        mapLegend.innerHTML = "";
        renderMetrics([]);
        setStatus("查询失败", "err");
      }
    }

    function buildParams() {
      var params = new URLSearchParams();
      params.set("limit", filters.limit.value || "500");
      if (filters.user.value.trim()) params.set("user", filters.user.value.trim());
      if (filters.tripId.value.trim()) params.set("tripId", filters.tripId.value.trim());
      if (filters.date.value) params.set("date", filters.date.value);
      var start = localTimeValue(filters.startTime.value);
      var end = localTimeValue(filters.endTime.value);
      if (start > 0) params.set("start", String(start));
      if (end > 0) params.set("end", String(end));
      return params;
    }

    function clearFilters() {
      filters.user.value = "";
      filters.tripId.value = "";
      filters.date.value = "";
      filters.startTime.value = "";
      filters.endTime.value = "";
      filters.limit.value = "500";
      load();
    }

    async function logout() {
      await fetch("/admin/api/logout", { method: "POST", credentials: "same-origin" }).catch(function () {});
      location.href = "/admin/login";
    }

    function exportCsv() {
      location.href = "/admin/api/track-points.csv?" + buildParams().toString();
    }

    function setView(view) {
      tableTab.className = "tab" + (view === "table" ? " active" : "");
      mapTab.className = "tab" + (view === "map" ? " active" : "");
      tablePanel.className = view === "table" ? "table-panel" : "table-panel hidden";
      mapPanel.className = view === "map" ? "map-panel active" : "map-panel";
      if (view === "map") renderMap(currentItems);
    }

    function renderRows(items) {
      if (!items.length) {
        rows.innerHTML = '<tr><td colspan="10" class="empty">没有匹配的轨迹点</td></tr>';
        return;
      }
      rows.innerHTML = items.map(function (item) {
        return "<tr>" +
          "<td>" + formatTime(item.timestamp) + "</td>" +
          "<td>" + escapeHtml(item.email || shortId(item.userId) || "-") + "</td>" +
          "<td>" + escapeHtml(item.date || "-") + "</td>" +
          '<td><span class="chip">' + escapeHtml(item.tripId || "-") + "</span></td>" +
          "<td>" + number(item.pointIndex) + "</td>" +
          '<td class="mono">' + coordinate(item.longitude) + "</td>" +
          '<td class="mono">' + coordinate(item.latitude) + "</td>" +
          "<td>" + speedText(item.speed) + "</td>" +
          "<td>" + accuracyText(item.accuracy) + "</td>" +
          '<td class="mono">' + escapeHtml(shortId(item.id)) + "</td>" +
          "</tr>";
      }).join("");
    }

    function renderMetrics(items) {
      var users = new Set();
      var trips = new Set();
      var times = [];
      items.forEach(function (item) {
        if (item.email || item.userId) users.add(item.email || item.userId);
        trips.add((item.userId || "") + "|" + (item.date || "") + "|" + (item.tripId || ""));
        if (Number(item.timestamp || 0) > 0) times.push(Number(item.timestamp));
      });
      times.sort(function (a, b) { return a - b; });
      document.getElementById("mCount").textContent = number(items.length);
      document.getElementById("mUsers").textContent = number(users.size);
      document.getElementById("mTrips").textContent = number(trips.size);
      document.getElementById("mRange").textContent = times.length ? shortTimeRange(times[0], times[times.length - 1]) : "-";
    }

    function renderMap(items) {
      var points = items.filter(function (item) {
        return Number.isFinite(Number(item.longitude)) && Number.isFinite(Number(item.latitude));
      });
      if (!points.length) {
        mapBox.innerHTML = '<div class="map-empty">没有可绘制的轨迹点</div>';
        mapLegend.innerHTML = "";
        return;
      }

      var width = 1000;
      var height = 540;
      var pad = 54;
      var minLon = Math.min.apply(null, points.map(function (p) { return Number(p.longitude); }));
      var maxLon = Math.max.apply(null, points.map(function (p) { return Number(p.longitude); }));
      var minLat = Math.min.apply(null, points.map(function (p) { return Number(p.latitude); }));
      var maxLat = Math.max.apply(null, points.map(function (p) { return Number(p.latitude); }));
      if (minLon === maxLon) { minLon -= 0.001; maxLon += 0.001; }
      if (minLat === maxLat) { minLat -= 0.001; maxLat += 0.001; }

      var groups = groupByTrip(points);
      var colors = ["#1f7255", "#2b5876", "#a76321", "#7c5a9e", "#b64b3d", "#546b2f", "#3f6f7e"];
      var svg = [
        '<svg viewBox="0 0 ' + width + ' ' + height + '" role="img" aria-label="历史轨迹地图">',
        '<rect x="0" y="0" width="' + width + '" height="' + height + '" fill="rgba(255,255,255,0.35)"/>'
      ];
      var legend = [];

      groups.forEach(function (group, index) {
        var color = colors[index % colors.length];
        var ordered = group.items.slice().sort(function (a, b) { return Number(a.timestamp || 0) - Number(b.timestamp || 0); });
        var projected = ordered.map(function (point) {
          return project(point, minLon, maxLon, minLat, maxLat, width, height, pad);
        });
        if (projected.length > 1) {
          svg.push('<polyline points="' + projected.map(function (p) { return p.x.toFixed(1) + "," + p.y.toFixed(1); }).join(" ") + '" fill="none" stroke="' + color + '" stroke-width="5" stroke-linecap="round" stroke-linejoin="round" opacity="0.9"/>');
        }
        projected.forEach(function (p, pointIndex) {
          var radius = pointIndex === 0 || pointIndex === projected.length - 1 ? 6 : 3.5;
          svg.push('<circle cx="' + p.x.toFixed(1) + '" cy="' + p.y.toFixed(1) + '" r="' + radius + '" fill="' + color + '" opacity="0.9"><title>' + escapeHtml(group.label + " " + formatTime(ordered[pointIndex].timestamp)) + '</title></circle>');
        });
        legend.push('<span class="legend-item"><i class="legend-color" style="background:' + color + '"></i>' + escapeHtml(group.label) + ' · ' + number(group.items.length) + ' 点</span>');
      });

      svg.push(axisLabel("左下", minLon, minLat, 24, height - 20));
      svg.push(axisLabel("右上", maxLon, maxLat, width - 210, 32));
      svg.push("</svg>");
      mapBox.innerHTML = svg.join("");
      mapLegend.innerHTML = legend.join("");
    }

    function groupByTrip(items) {
      var map = {};
      items.forEach(function (item) {
        var key = (item.userId || "") + "|" + (item.date || "") + "|" + (item.tripId || "");
        if (!map[key]) {
          map[key] = {
            label: (item.email || shortId(item.userId) || "未知用户") + " / " + (item.date || "-") + " / " + (item.tripId || "-"),
            items: []
          };
        }
        map[key].items.push(item);
      });
      return Object.keys(map).map(function (key) { return map[key]; }).sort(function (a, b) {
        var aTime = Math.min.apply(null, a.items.map(function (item) { return Number(item.timestamp || 0); }));
        var bTime = Math.min.apply(null, b.items.map(function (item) { return Number(item.timestamp || 0); }));
        return aTime - bTime;
      });
    }

    function project(point, minLon, maxLon, minLat, maxLat, width, height, pad) {
      var lon = Number(point.longitude);
      var lat = Number(point.latitude);
      var x = pad + ((lon - minLon) / (maxLon - minLon)) * (width - pad * 2);
      var y = height - pad - ((lat - minLat) / (maxLat - minLat)) * (height - pad * 2);
      return { x: x, y: y };
    }

    function axisLabel(label, lon, lat, x, y) {
      return '<text x="' + x + '" y="' + y + '" fill="#68756f" font-size="13">' + label + ' ' + coordinate(lon) + ', ' + coordinate(lat) + '</text>';
    }

    function setStatus(text, type) {
      statusBox.textContent = text;
      statusBox.className = "status" + (type ? " " + type : "");
    }

    function errorMessage(code) {
      if (code === "unauthorized") return "登录已过期";
      return code || "查询失败";
    }

    function localTimeValue(value) {
      if (!value) return 0;
      var parsed = new Date(value).getTime();
      return Number.isFinite(parsed) ? parsed : 0;
    }

    function formatTime(value) {
      if (!value) return "-";
      return new Date(Number(value)).toLocaleString("zh-CN", { hour12: false });
    }

    function shortTimeRange(start, end) {
      if (!start || !end) return "-";
      var s = new Date(Number(start));
      var e = new Date(Number(end));
      var sameDay = s.toLocaleDateString("zh-CN") === e.toLocaleDateString("zh-CN");
      if (sameDay) {
        return s.toLocaleDateString("zh-CN") + " " +
          s.toLocaleTimeString("zh-CN", { hour12: false, hour: "2-digit", minute: "2-digit" }) + "-" +
          e.toLocaleTimeString("zh-CN", { hour12: false, hour: "2-digit", minute: "2-digit" });
      }
      return s.toLocaleDateString("zh-CN") + " - " + e.toLocaleDateString("zh-CN");
    }

    function coordinate(value) {
      var numberValue = Number(value || 0);
      return Number.isFinite(numberValue) ? numberValue.toFixed(6) : "-";
    }

    function accuracyText(value) {
      var meters = Number(value || 0);
      return meters > 0 ? meters.toFixed(1) + " m" : "-";
    }

    function speedText(value) {
      var speed = Number(value || 0);
      return speed > 0 ? speed.toFixed(1) + " m/s" : "-";
    }

    function number(value) {
      return Number(value || 0).toLocaleString("zh-CN");
    }

    function shortId(value) {
      value = String(value || "");
      return value.length > 12 ? value.slice(0, 8) + "..." + value.slice(-4) : value;
    }

    function escapeHtml(value) {
      return String(value == null ? "" : value).replace(/[&<>"']/g, function (char) {
        return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char];
      });
    }
  </script>
</body>
</html>`;
}

function adminSubmissionsPage() {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>MotionTrace Admin</title>
  <style>
    :root {
      color-scheme: light;
      --bg: #f4f6f3;
      --surface: #ffffff;
      --surface-2: #eef3ef;
      --ink: #1d2622;
      --muted: #66736b;
      --line: #dce4dc;
      --green: #1f6f54;
      --green-dark: #164e3d;
      --amber: #b26b27;
      --red: #b94f44;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      background: var(--bg);
      color: var(--ink);
      font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      letter-spacing: 0;
    }
    .shell {
      width: min(1160px, calc(100vw - 28px));
      margin: 0 auto;
      padding: 28px 0 42px;
    }
    header {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      gap: 16px;
      margin-bottom: 18px;
    }
    h1 {
      margin: 0;
      font-size: 28px;
      line-height: 1.15;
    }
    .sub {
      margin: 7px 0 0;
      color: var(--muted);
      font-size: 14px;
    }
    .status {
      min-width: 150px;
      padding: 8px 12px;
      border: 1px solid var(--line);
      background: var(--surface);
      border-radius: 8px;
      color: var(--muted);
      font-size: 13px;
      text-align: center;
    }
    .status.ok { color: var(--green); border-color: #bfdbc9; background: #f2faf5; }
    .status.err { color: var(--red); border-color: #edc8c2; background: #fff6f4; }
    .toolbar {
      display: grid;
      grid-template-columns: minmax(260px, 1fr) 120px auto auto auto;
      gap: 10px;
      align-items: center;
      padding: 12px;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--surface);
    }
    input, select, button {
      height: 38px;
      border-radius: 7px;
      border: 1px solid var(--line);
      font: inherit;
      font-size: 14px;
    }
    input, select {
      width: 100%;
      padding: 0 12px;
      color: var(--ink);
      background: #fbfcfb;
      outline-color: #8db69f;
    }
    button {
      padding: 0 16px;
      border-color: transparent;
      background: var(--green);
      color: #fff;
      cursor: pointer;
      white-space: nowrap;
    }
    button.secondary {
      color: var(--green-dark);
      border-color: #cfe1d5;
      background: #f6fbf8;
    }
    button:hover { filter: brightness(0.97); }
    .stats {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 10px;
      margin: 14px 0;
    }
    .metric {
      border: 1px solid var(--line);
      background: var(--surface);
      border-radius: 8px;
      padding: 14px;
      min-height: 86px;
    }
    .metric b {
      display: block;
      font-size: 24px;
      line-height: 1.1;
    }
    .metric span {
      display: block;
      margin-top: 8px;
      color: var(--muted);
      font-size: 13px;
    }
    .table-wrap {
      overflow: auto;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--surface);
    }
    table {
      width: 100%;
      border-collapse: collapse;
      min-width: 840px;
    }
    th, td {
      padding: 12px 14px;
      border-bottom: 1px solid var(--line);
      text-align: left;
      font-size: 13px;
      white-space: nowrap;
    }
    th {
      position: sticky;
      top: 0;
      background: var(--surface-2);
      color: #405047;
      font-weight: 700;
    }
    tbody tr:hover { background: #fbfcf8; }
    tbody tr:last-child td { border-bottom: 0; }
    .mono {
      font-family: ui-monospace, "SFMono-Regular", Consolas, monospace;
      color: #45524b;
    }
    .empty {
      padding: 28px;
      color: var(--muted);
      text-align: center;
    }
    @media (max-width: 760px) {
      header { display: block; }
      .status { margin-top: 12px; text-align: left; }
      .toolbar { grid-template-columns: 1fr; }
      .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    }
  </style>
</head>
<body>
  <main class="shell">
    <header>
      <div>
        <h1>MotionTrace Admin</h1>
        <p class="sub">轨迹同步提交记录</p>
      </div>
      <div id="status" class="status">已登录</div>
    </header>

    <section class="toolbar" aria-label="查询条件">
      <input id="email" type="search" placeholder="按邮箱过滤">
      <select id="limit" aria-label="加载条数">
        <option value="50">50 条</option>
        <option value="100">100 条</option>
        <option value="200">200 条</option>
      </select>
      <button id="load">查询</button>
      <button id="clear" class="secondary">清除</button>
      <button id="logout" class="secondary">退出</button>
    </section>

    <section class="stats" aria-label="汇总">
      <div class="metric"><b id="mCount">0</b><span>提交记录</span></div>
      <div class="metric"><b id="mUsers">0</b><span>涉及用户</span></div>
      <div class="metric"><b id="mPoints">0</b><span>轨迹点</span></div>
      <div class="metric"><b id="mBytes">0 B</b><span>上传体积</span></div>
    </section>

    <section class="table-wrap" aria-label="提交列表">
      <table>
        <thead>
          <tr>
            <th>提交时间</th>
            <th>邮箱</th>
            <th>天数</th>
            <th>轨迹点</th>
            <th>行程</th>
            <th>打卡</th>
            <th>体积</th>
            <th>记录 ID</th>
          </tr>
        </thead>
        <tbody id="rows">
          <tr><td colspan="8" class="empty">正在加载提交记录</td></tr>
        </tbody>
      </table>
    </section>
  </main>

  <script>
    var email = document.getElementById("email");
    var limit = document.getElementById("limit");
    var rows = document.getElementById("rows");
    var statusBox = document.getElementById("status");

    document.getElementById("load").addEventListener("click", load);
    document.getElementById("clear").addEventListener("click", function () {
      email.value = "";
      rows.innerHTML = '<tr><td colspan="8" class="empty">点击查询加载提交记录</td></tr>';
      setStatus("已登录", "");
      renderMetrics([]);
    });
    document.getElementById("logout").addEventListener("click", logout);
    email.addEventListener("keydown", function (event) {
      if (event.key === "Enter") load();
    });
    load();

    async function load() {
      setStatus("查询中", "");
      var params = new URLSearchParams();
      params.set("limit", limit.value);
      if (email.value.trim()) params.set("email", email.value.trim());
      try {
        var response = await fetch("/admin/api/submissions?" + params.toString(), { credentials: "same-origin" });
        var payload = await response.json().catch(function () { return {}; });
        if (!response.ok) {
          if (response.status === 401) {
            location.href = "/admin/login";
            return;
          }
          throw new Error(errorMessage(payload.error));
        }
        var items = payload.items || [];
        renderRows(items);
        renderMetrics(items);
        setStatus("已加载 " + items.length + " 条", "ok");
      } catch (error) {
        rows.innerHTML = '<tr><td colspan="8" class="empty">' + escapeHtml(error.message || "查询失败") + '</td></tr>';
        renderMetrics([]);
        setStatus("查询失败", "err");
      }
    }

    async function logout() {
      await fetch("/admin/api/logout", { method: "POST", credentials: "same-origin" }).catch(function () {});
      location.href = "/admin/login";
    }

    function renderRows(items) {
      if (!items.length) {
        rows.innerHTML = '<tr><td colspan="8" class="empty">没有匹配记录</td></tr>';
        return;
      }
      rows.innerHTML = items.map(function (item) {
        return "<tr>" +
          "<td>" + formatTime(item.createdAt) + "</td>" +
          "<td>" + escapeHtml(item.email || item.userId || "-") + "</td>" +
          "<td>" + number(item.dayCount) + "</td>" +
          "<td>" + number(item.pointCount) + "</td>" +
          "<td>" + number(item.tripCount) + "</td>" +
          "<td>" + number(item.checkinCount) + "</td>" +
          "<td>" + formatBytes(item.bytes) + "</td>" +
          '<td class="mono">' + escapeHtml(shortId(item.id)) + "</td>" +
          "</tr>";
      }).join("");
    }

    function renderMetrics(items) {
      var users = new Set();
      var bytes = 0;
      var points = 0;
      items.forEach(function (item) {
        users.add(item.email || item.userId || "");
        bytes += Number(item.bytes || 0);
        points += Number(item.pointCount || 0);
      });
      document.getElementById("mCount").textContent = number(items.length);
      document.getElementById("mUsers").textContent = number(users.size);
      document.getElementById("mPoints").textContent = number(points);
      document.getElementById("mBytes").textContent = formatBytes(bytes);
    }

    function setStatus(text, type) {
      statusBox.textContent = text;
      statusBox.className = "status" + (type ? " " + type : "");
    }

    function errorMessage(code) {
      if (code === "unauthorized") return "登录已过期";
      return code || "查询失败";
    }

    function formatTime(value) {
      if (!value) return "-";
      return new Date(Number(value)).toLocaleString("zh-CN", { hour12: false });
    }

    function formatBytes(value) {
      var bytes = Number(value || 0);
      if (bytes < 1024) return bytes + " B";
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
      return (bytes / 1024 / 1024).toFixed(2) + " MB";
    }

    function number(value) {
      return Number(value || 0).toLocaleString("zh-CN");
    }

    function shortId(value) {
      value = String(value || "");
      return value.length > 12 ? value.slice(0, 8) + "..." + value.slice(-4) : value;
    }

    function escapeHtml(value) {
      return String(value == null ? "" : value).replace(/[&<>"']/g, function (char) {
        return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char];
      });
    }
  </script>
</body>
</html>`;
}

function adminLoginPage() {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>MotionTrace Admin Login</title>
  <style>
    :root {
      color-scheme: light;
      --bg: #f4f6f3;
      --surface: #ffffff;
      --ink: #1d2622;
      --muted: #66736b;
      --line: #dce4dc;
      --green: #1f6f54;
      --red: #b94f44;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      display: grid;
      place-items: center;
      padding: 24px;
      background: var(--bg);
      color: var(--ink);
      font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      letter-spacing: 0;
    }
    .panel {
      width: min(420px, 100%);
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--surface);
      padding: 24px;
    }
    h1 {
      margin: 0;
      font-size: 26px;
      line-height: 1.15;
    }
    .sub {
      margin: 8px 0 22px;
      color: var(--muted);
      font-size: 14px;
    }
    label {
      display: block;
      margin: 14px 0 7px;
      color: #405047;
      font-size: 13px;
      font-weight: 700;
    }
    input, button {
      width: 100%;
      height: 42px;
      border-radius: 7px;
      border: 1px solid var(--line);
      font: inherit;
      font-size: 14px;
    }
    input {
      padding: 0 12px;
      color: var(--ink);
      background: #fbfcfb;
      outline-color: #8db69f;
    }
    button {
      margin-top: 18px;
      border-color: transparent;
      background: var(--green);
      color: #fff;
      cursor: pointer;
    }
    button:disabled {
      opacity: 0.65;
      cursor: default;
    }
    .error {
      min-height: 20px;
      margin-top: 12px;
      color: var(--red);
      font-size: 13px;
    }
  </style>
</head>
<body>
  <main class="panel">
    <h1>MotionTrace Admin</h1>
    <p class="sub">登录后查看轨迹同步提交记录</p>
    <form id="form">
      <label for="username">管理员账号</label>
      <input id="username" name="username" type="text" autocomplete="username" value="admin" required>
      <label for="password">管理员密码</label>
      <input id="password" name="password" type="password" autocomplete="current-password" required autofocus>
      <button id="submit" type="submit">登录</button>
      <div id="error" class="error"></div>
    </form>
  </main>
  <script>
    var form = document.getElementById("form");
    var username = document.getElementById("username");
    var password = document.getElementById("password");
    var submit = document.getElementById("submit");
    var error = document.getElementById("error");

    form.addEventListener("submit", async function (event) {
      event.preventDefault();
      error.textContent = "";
      submit.disabled = true;
      submit.textContent = "登录中";
      try {
        var response = await fetch("/admin/api/login", {
          method: "POST",
          credentials: "same-origin",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            username: username.value.trim(),
            password: password.value
          })
        });
        if (!response.ok) {
          error.textContent = "账号或密码不正确";
          return;
        }
        location.href = "/admin";
      } catch (err) {
        error.textContent = "登录失败，请稍后重试";
      } finally {
        submit.disabled = false;
        submit.textContent = "登录";
      }
    });
  </script>
</body>
</html>`;
}

function html(body) {
  return new Response(body, {
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store"
    }
  });
}

function redirect(location) {
  return new Response(null, {
    status: 302,
    headers: {
      Location: location,
      "Cache-Control": "no-store"
    }
  });
}

function json(body, status = 200) {
  return cors(new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" }
  }));
}

function cors(response) {
  response.headers.set("Access-Control-Allow-Origin", "*");
  response.headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  response.headers.set("Access-Control-Allow-Headers", "Content-Type,Authorization,Cookie");
  return response;
}
