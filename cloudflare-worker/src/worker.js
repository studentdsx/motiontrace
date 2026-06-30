const SESSION_TTL_MS = 1000 * 60 * 60 * 24 * 30;
const MAX_PAYLOAD_BYTES = 900 * 1024;
const PASSWORD_MIN_LENGTH = 8;
const PASSWORD_PBKDF2_ITERATIONS = 100000;
const DEFAULT_ADMIN_USERNAME = "admin";
const DEFAULT_ADMIN_PASSWORD = "Admin@1357";
const ADMIN_SESSION_COOKIE = "motiontrace_admin";
const ADMIN_SESSION_TTL_MS = 1000 * 60 * 60 * 8;
const USERNAME_PATTERN = /^[A-Za-z0-9_.-]{3,32}$/;

let userSchemaReady = false;

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (request.method === "OPTIONS") {
        return cors(new Response(null, { status: 204 }));
      }

      if (request.method === "GET" && (url.pathname === "/" || url.pathname === "")) {
        if (await isAdminAuthenticated(request, env)) {
          return redirect("/admin");
        }
        return redirect("/admin/login");
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
      if (request.method === "GET" && url.pathname === "/admin/api/users") {
        return await listUsers(request, env);
      }
      const resetUserMatch = url.pathname.match(/^\/admin\/api\/users\/([^/]+)\/reset-password$/);
      if (request.method === "POST" && resetUserMatch) {
        return await resetUserPassword(request, env, decodeURIComponent(resetUserMatch[1]));
      }
      const userMatch = url.pathname.match(/^\/admin\/api\/users\/([^/]+)$/);
      if ((request.method === "PATCH" || request.method === "POST") && userMatch) {
        return await updateUser(request, env, decodeURIComponent(userMatch[1]));
      }
      if (request.method === "DELETE" && userMatch) {
        return await deleteUser(request, env, decodeURIComponent(userMatch[1]));
      }
      if (request.method === "GET" && url.pathname === "/admin/api/trips") {
        return await listTrackTrips(request, env);
      }
      if (request.method === "GET" && url.pathname === "/admin/api/track-points") {
        return await listTrackPoints(request, env);
      }
      if (request.method === "GET" && url.pathname === "/admin/api/track-map") {
        return await listTrackMapPoints(request, env);
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
      if (request.method === "POST" && url.pathname === "/sync/track-point") {
        return await uploadTrackPoint(request, env);
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
  await ensureUserSchema(env);
  const body = await readJson(request);
  const username = normalizeUsername(body.username);
  const email = normalizeEmail(body.email);
  const password = String(body.password || "");
  if (!username) {
    return json({ error: "invalid_username" }, 400);
  }
  if (!email) {
    return json({ error: "invalid_email" }, 400);
  }
  if (!isValidPassword(password)) {
    return json({ error: "invalid_credentials" }, 400);
  }

  const now = Date.now();
  const salt = randomToken(16);
  const user = {
    id: randomUuid(),
    username,
    email,
    password_salt: salt,
    password_hash: await hashPassword(password, salt),
    created_at: now,
    updated_at: now
  };

  try {
    await env.DB.prepare(
      "INSERT INTO users (id, username, email, password_salt, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
    ).bind(user.id, user.username, user.email, user.password_salt, user.password_hash, user.created_at, user.updated_at).run();
  } catch (error) {
    const message = String(error);
    if (message.includes("username")) {
      return json({ error: "username_exists" }, 409);
    }
    if (message.includes("email")) {
      return json({ error: "email_exists" }, 409);
    }
    if (message.includes("UNIQUE")) {
      return json({ error: "username_exists" }, 409);
    }
    throw error;
  }

  const token = await createSession(env, user.id);
  return json({ token, user: userResponse(user) }, 201);
}

async function login(request, env) {
  await ensureUserSchema(env);
  const body = await readJson(request);
  const identifier = normalizeLoginIdentifier(body.username || body.identifier || body.email);
  const password = String(body.password || "");
  if (!identifier || !password) {
    return json({ error: "invalid_credentials" }, 400);
  }

  const user = await env.DB.prepare(
    "SELECT * FROM users WHERE lower(username) = ? OR lower(email) = ? LIMIT 1"
  ).bind(identifier, identifier).first();
  if (!user) {
    return json({ error: "invalid_credentials" }, 401);
  }
  if (!(await verifyPassword(password, user))) {
    return json({ error: "invalid_credentials" }, 401);
  }
  await upgradePasswordHashIfNeeded(env, user, password);

  const token = await createSession(env, user.id);
  return json({ token, user: userResponse(user) });
}

async function changePassword(request, env) {
  await ensureUserSchema(env);
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

async function uploadTrackPoint(request, env) {
  const session = await requireSession(request, env);
  if (!session) {
    return json({ error: "unauthorized" }, 401);
  }

  const point = normalizeRealtimeTrackPoint(await readJson(request), session.user_id, Date.now());
  if (!point) {
    return json({ error: "invalid_track_point" }, 400);
  }

  await env.DB.prepare(
    "INSERT INTO track_points " +
      "(id, user_id, date, trip_id, trip_index, point_index, timestamp, longitude, latitude, accuracy, speed, created_at) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
      "ON CONFLICT(id) DO UPDATE SET " +
      "date = excluded.date, trip_id = excluded.trip_id, trip_index = excluded.trip_index, " +
      "point_index = excluded.point_index, timestamp = excluded.timestamp, longitude = excluded.longitude, " +
      "latitude = excluded.latitude, accuracy = excluded.accuracy, speed = excluded.speed, created_at = excluded.created_at"
  ).bind(
    point.id,
    point.userId,
    point.date,
    point.tripId,
    point.tripIndex,
    point.pointIndex,
    point.timestamp,
    point.longitude,
    point.latitude,
    point.accuracy,
    point.speed,
    point.createdAt
  ).run();

  return json({ ok: true, id: point.id, updatedAt: point.createdAt });
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
  await ensureUserSchema(env);

  const url = new URL(request.url);
  const rawLimit = Number.parseInt(url.searchParams.get("limit") || "50", 10);
  const limit = Math.max(1, Math.min(Number.isFinite(rawLimit) ? rawLimit : 50, 200));
  const user = String(url.searchParams.get("user") || url.searchParams.get("email") || "").trim().toLowerCase();

  let sql =
    "SELECT s.id, s.user_id, u.username, u.email, s.bytes, s.day_count, s.point_count, " +
    "s.checkin_count, s.trip_count, s.created_at " +
    "FROM sync_submissions s LEFT JOIN users u ON u.id = s.user_id";
  const binds = [];
  if (user) {
    sql += " WHERE lower(u.username) LIKE ? OR lower(u.email) LIKE ? OR lower(s.user_id) LIKE ?";
    binds.push("%" + user + "%", "%" + user + "%", "%" + user + "%");
  }
  sql += " ORDER BY s.created_at DESC LIMIT ?";
  binds.push(limit);

  const result = await env.DB.prepare(sql).bind(...binds).all();
  return json({
    items: (result.results || []).map((item) => ({
      id: item.id,
      userId: item.user_id,
      username: item.username || "",
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

async function listUsers(request, env) {
  if (!(await isAdminAuthenticated(request, env))) {
    return json({ error: "unauthorized" }, 401);
  }
  await ensureUserSchema(env);

  const url = new URL(request.url);
  const rawPage = Number.parseInt(url.searchParams.get("page") || "1", 10);
  const rawPageSize = Number.parseInt(url.searchParams.get("pageSize") || "20", 10);
  const page = Math.max(1, Number.isFinite(rawPage) ? rawPage : 1);
  const pageSize = Math.max(1, Math.min(Number.isFinite(rawPageSize) ? rawPageSize : 20, 100));
  const offset = (page - 1) * pageSize;
  const search = String(url.searchParams.get("search") || "").trim().toLowerCase();
  const clauses = [];
  const binds = [];

  if (search) {
    clauses.push("(lower(u.username) LIKE ? OR lower(u.email) LIKE ? OR lower(u.id) LIKE ?)");
    binds.push("%" + search + "%", "%" + search + "%", "%" + search + "%");
  }
  const where = clauses.length ? " WHERE " + clauses.join(" AND ") : "";
  const totalRow = await env.DB.prepare("SELECT COUNT(*) AS total FROM users u" + where).bind(...binds).first();
  const result = await env.DB.prepare(
    "SELECT u.id, u.username, u.email, u.created_at, u.updated_at, " +
      "(SELECT updated_at FROM track_snapshots s WHERE s.user_id = u.id) AS snapshot_updated_at, " +
      "(SELECT COUNT(*) FROM track_points p WHERE p.user_id = u.id) AS point_count, " +
      "(SELECT COUNT(DISTINCT p.date || '|' || p.trip_id) FROM track_points p WHERE p.user_id = u.id) AS trip_count " +
      "FROM users u" + where + " ORDER BY u.created_at DESC LIMIT ? OFFSET ?"
  ).bind(...binds, pageSize, offset).all();

  return json({
    page,
    pageSize,
    total: Number(totalRow && totalRow.total ? totalRow.total : 0),
    items: (result.results || []).map((item) => ({
      id: item.id,
      username: item.username || "",
      email: item.email || "",
      createdAt: item.created_at || 0,
      updatedAt: item.updated_at || 0,
      snapshotUpdatedAt: item.snapshot_updated_at || 0,
      pointCount: item.point_count || 0,
      tripCount: item.trip_count || 0
    }))
  });
}

async function updateUser(request, env, userId) {
  if (!(await isAdminAuthenticated(request, env))) {
    return json({ error: "unauthorized" }, 401);
  }
  await ensureUserSchema(env);

  const body = await readJson(request);
  const username = normalizeUsername(body.username);
  const email = normalizeEmail(body.email);
  if (!username) {
    return json({ error: "invalid_username" }, 400);
  }
  if (!email) {
    return json({ error: "invalid_email" }, 400);
  }

  const user = await env.DB.prepare("SELECT id FROM users WHERE id = ?").bind(userId).first();
  if (!user) {
    return json({ error: "user_not_found" }, 404);
  }

  try {
    const now = Date.now();
    await env.DB.prepare("UPDATE users SET username = ?, email = ?, updated_at = ? WHERE id = ?")
      .bind(username, email, now, userId)
      .run();
    return json({ ok: true, user: { id: userId, username, email, updatedAt: now } });
  } catch (error) {
    const message = String(error);
    if (message.includes("username")) {
      return json({ error: "username_exists" }, 409);
    }
    if (message.includes("email")) {
      return json({ error: "email_exists" }, 409);
    }
    if (message.includes("UNIQUE")) {
      return json({ error: "username_exists" }, 409);
    }
    throw error;
  }
}

async function resetUserPassword(request, env, userId) {
  if (!(await isAdminAuthenticated(request, env))) {
    return json({ error: "unauthorized" }, 401);
  }
  await ensureUserSchema(env);

  const body = await readJson(request);
  const password = String(body.password || "");
  if (!isValidPassword(password)) {
    return json({ error: "invalid_password" }, 400);
  }

  const user = await env.DB.prepare("SELECT id FROM users WHERE id = ?").bind(userId).first();
  if (!user) {
    return json({ error: "user_not_found" }, 404);
  }

  const now = Date.now();
  const salt = randomToken(16);
  const passwordHash = await hashPassword(password, salt);
  await env.DB.prepare(
    "UPDATE users SET password_salt = ?, password_hash = ?, updated_at = ? WHERE id = ?"
  ).bind(salt, passwordHash, now, userId).run();
  await env.DB.prepare("DELETE FROM sessions WHERE user_id = ?").bind(userId).run();
  return json({ ok: true, updatedAt: now });
}

async function deleteUser(request, env, userId) {
  if (!(await isAdminAuthenticated(request, env))) {
    return json({ error: "unauthorized" }, 401);
  }
  await ensureUserSchema(env);

  const user = await env.DB.prepare("SELECT id FROM users WHERE id = ?").bind(userId).first();
  if (!user) {
    return json({ error: "user_not_found" }, 404);
  }

  await env.DB.batch([
    env.DB.prepare("DELETE FROM sessions WHERE user_id = ?").bind(userId),
    env.DB.prepare("DELETE FROM track_points WHERE user_id = ?").bind(userId),
    env.DB.prepare("DELETE FROM sync_submissions WHERE user_id = ?").bind(userId),
    env.DB.prepare("DELETE FROM track_snapshots WHERE user_id = ?").bind(userId),
    env.DB.prepare("DELETE FROM users WHERE id = ?").bind(userId)
  ]);
  return json({ ok: true });
}

async function listTrackPoints(request, env) {
  if (!(await isAdminAuthenticated(request, env))) {
    return json({ error: "unauthorized" }, 401);
  }
  await ensureUserSchema(env);

  const query = buildTrackPointQuery(request, { paginated: true, maxPageSize: 100 });
  const count = await env.DB.prepare(query.countSql).bind(...query.whereBinds).first();
  const result = await env.DB.prepare(query.sql).bind(...query.binds).all();
  return json({
    page: query.page,
    pageSize: query.pageSize,
    total: Number(count && count.total ? count.total : 0),
    items: (result.results || []).map(formatTrackPointRow)
  });
}

async function listTrackMapPoints(request, env) {
  if (!(await isAdminAuthenticated(request, env))) {
    return json({ error: "unauthorized" }, 401);
  }
  await ensureUserSchema(env);

  const query = buildTrackPointQuery(request, { limit: 5000, order: "asc" });
  const result = await env.DB.prepare(query.sql).bind(...query.binds).all();
  return json({
    items: (result.results || []).map(formatTrackPointRow)
  });
}

async function exportTrackPointsCsv(request, env) {
  if (!(await isAdminAuthenticated(request, env))) {
    return json({ error: "unauthorized" }, 401);
  }
  await ensureUserSchema(env);

  const query = buildTrackPointQuery(request, { limit: 10000, order: "asc" });
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

async function listTrackTrips(request, env) {
  if (!(await isAdminAuthenticated(request, env))) {
    return json({ error: "unauthorized" }, 401);
  }
  await ensureUserSchema(env);

  const query = buildTrackPointWhere(new URL(request.url), { includeTrip: false });
  let sql =
    "SELECT p.trip_id, p.trip_index, MIN(p.timestamp) AS start_time, MAX(p.timestamp) AS end_time, COUNT(*) AS point_count " +
    "FROM track_points p LEFT JOIN users u ON u.id = p.user_id";
  if (query.clauses.length) {
    sql += " WHERE " + query.clauses.join(" AND ");
  }
  sql += " GROUP BY p.trip_id, p.trip_index ORDER BY MIN(p.timestamp) ASC";
  const result = await env.DB.prepare(sql).bind(...query.binds).all();
  return json({
    items: (result.results || []).map((item) => ({
      tripId: item.trip_id || "",
      tripIndex: item.trip_index || 0,
      startTime: item.start_time || 0,
      endTime: item.end_time || 0,
      pointCount: item.point_count || 0
    }))
  });
}

function buildTrackPointQuery(request, options = {}) {
  const url = new URL(request.url);
  const paginated = Boolean(options.paginated);
  const maxPageSize = options.maxPageSize || 100;
  const rawPage = Number.parseInt(url.searchParams.get("page") || "1", 10);
  const rawPageSize = Number.parseInt(url.searchParams.get("pageSize") || "50", 10);
  const page = Math.max(1, Number.isFinite(rawPage) ? rawPage : 1);
  const pageSize = Math.max(1, Math.min(Number.isFinite(rawPageSize) ? rawPageSize : 50, maxPageSize));
  const limit = paginated
    ? pageSize
    : Math.max(1, Math.min(Number(options.limit || 1000), Number(options.limit || 1000)));
  const offset = paginated ? (page - 1) * pageSize : 0;
  const where = buildTrackPointWhere(url);
  const order = options.order === "asc" ? "ASC" : "DESC";

  let sql =
    "SELECT p.id, p.user_id, u.username, u.email, p.date, p.trip_id, p.trip_index, p.point_index, " +
    "p.timestamp, p.longitude, p.latitude, p.accuracy, p.speed, p.created_at " +
    "FROM track_points p LEFT JOIN users u ON u.id = p.user_id";
  if (where.clauses.length) {
    sql += " WHERE " + where.clauses.join(" AND ");
  }
  sql += " ORDER BY p.timestamp " + order + " LIMIT ?";
  const binds = where.binds.slice();
  binds.push(limit);
  if (paginated) {
    sql += " OFFSET ?";
    binds.push(offset);
  }
  let countSql = "SELECT COUNT(*) AS total FROM track_points p LEFT JOIN users u ON u.id = p.user_id";
  if (where.clauses.length) {
    countSql += " WHERE " + where.clauses.join(" AND ");
  }
  return { sql, countSql, binds, whereBinds: where.binds, page, pageSize };
}

function buildTrackPointWhere(url, options = {}) {
  const user = String(url.searchParams.get("user") || url.searchParams.get("email") || "").trim().toLowerCase();
  const tripId = String(url.searchParams.get("tripId") || "").trim().toLowerCase();
  const date = String(url.searchParams.get("date") || "").trim();
  const clauses = [];
  const binds = [];

  if (user) {
    clauses.push("(lower(u.username) LIKE ? OR lower(u.email) LIKE ? OR lower(p.user_id) LIKE ?)");
    binds.push("%" + user + "%", "%" + user + "%", "%" + user + "%");
  }
  if (date) {
    clauses.push("p.date = ?");
    binds.push(date);
  }
  if (options.includeTrip !== false && tripId) {
    clauses.push("lower(p.trip_id) LIKE ?");
    binds.push("%" + tripId + "%");
  }
  return { clauses, binds };
}

function formatTrackPointRow(item) {
  return {
    id: item.id,
    userId: item.user_id,
    username: item.username || "",
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
    "username",
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
      row.username || "",
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

function normalizeUsername(value) {
  const username = String(value || "").trim().toLowerCase();
  return USERNAME_PATTERN.test(username) ? username : "";
}

function normalizeLoginIdentifier(value) {
  const text = String(value || "").trim().toLowerCase();
  if (!text) {
    return "";
  }
  return text.includes("@") ? normalizeEmail(text) : normalizeUsername(text);
}

function userResponse(user) {
  return {
    id: user.id,
    username: user.username || "",
    email: user.email || ""
  };
}

async function ensureUserSchema(env) {
  if (userSchemaReady) {
    return;
  }
  try {
    await env.DB.prepare("ALTER TABLE users ADD COLUMN username TEXT").run();
  } catch (error) {
    if (!String(error).toLowerCase().includes("duplicate")) {
      // D1/SQLite 旧表补列只需要成功一次；重复执行的“已存在”错误可忽略。
      const message = String(error).toLowerCase();
      if (!message.includes("already exists") && !message.includes("duplicate column")) {
        throw error;
      }
    }
  }
  await env.DB.prepare(
    "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username ON users(username) " +
      "WHERE username IS NOT NULL AND username <> ''"
  ).run();
  await assignMissingUsernames(env);
  userSchemaReady = true;
}

async function assignMissingUsernames(env) {
  const result = await env.DB.prepare(
    "SELECT id, email FROM users WHERE username IS NULL OR username = '' LIMIT 200"
  ).all();
  for (const user of result.results || []) {
    await assignUsernameForUser(env, user);
  }
}

async function assignUsernameForUser(env, user) {
  const local = String(user.email || "").split("@")[0] || "user";
  const base = normalizeUsername(local.replace(/[^A-Za-z0-9_.-]/g, "").slice(0, 24)) || "user";
  const suffix = String(user.id || "").replace(/-/g, "").slice(0, 6) || randomToken(3).slice(0, 6).toLowerCase();
  const candidates = [
    padUsername(base),
    padUsername((base + "_" + suffix).slice(0, 32)),
    padUsername(("user_" + suffix).slice(0, 32))
  ];
  for (const username of candidates) {
    try {
      await env.DB.prepare("UPDATE users SET username = ? WHERE id = ? AND (username IS NULL OR username = '')")
        .bind(username, user.id)
        .run();
      return;
    } catch (error) {
      if (!String(error).includes("UNIQUE")) {
        throw error;
      }
    }
  }
}

function padUsername(value) {
  let username = normalizeUsername(value) || "user";
  while (username.length < 3) {
    username += "0";
  }
  return username.slice(0, 32);
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
  const tripIndex = buildSnapshotTripIndex(root);
  for (const [date, day] of Object.entries(root || {})) {
    if (!day || typeof day !== "object" || Array.isArray(day)) {
      continue;
    }
    const points = Array.isArray(day.points) ? day.points : [];
    for (let i = 0; i < points.length; i++) {
      const point = points[i] || {};
      const latitude = Number(point.latitude);
      const longitude = Number(point.longitude);
      const timestamp = Number(point.timestamp || 0);
      if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || timestamp <= 0) {
        continue;
      }
      const trip = findSnapshotTrip(tripIndex, timestamp, String(point.tripId || "")) || {
        id: "unassigned",
        index: 0,
        date
      };
      rows.push({
        id: randomUuid(),
        userId,
        date: trip.date || date,
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

function normalizeRealtimeTrackPoint(body, userId, createdAt) {
  const date = String(body && body.date || "").trim();
  const timestamp = Number(body && body.timestamp || 0);
  const longitude = Number(body && body.longitude);
  const latitude = Number(body && body.latitude);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)
      || timestamp <= 0
      || !Number.isFinite(longitude)
      || !Number.isFinite(latitude)
      || longitude < -180
      || longitude > 180
      || latitude < -90
      || latitude > 90) {
    return null;
  }

  const tripId = normalizeTrackText(body.tripId, "unassigned");
  const tripIndex = nonNegativeInteger(body.tripIndex);
  const pointIndex = nonNegativeInteger(body.pointIndex);
  return {
    id: stableTrackPointId(userId, date, tripId, pointIndex, timestamp),
    userId,
    date,
    tripId,
    tripIndex,
    pointIndex,
    timestamp,
    longitude,
    latitude,
    accuracy: finiteNumber(body.accuracy),
    speed: finiteNumber(body.speed),
    createdAt
  };
}

function stableTrackPointId(userId, date, tripId, pointIndex, timestamp) {
  return ["rt", safeTrackIdPart(userId), date, safeTrackIdPart(tripId), pointIndex, timestamp].join("_").slice(0, 240);
}

function safeTrackIdPart(value) {
  return String(value || "")
    .replace(/[^A-Za-z0-9_.-]/g, "_")
    .slice(0, 96) || "none";
}

function normalizeTrackText(value, fallback) {
  const text = String(value || "").trim();
  return (text || fallback).slice(0, 128);
}

function nonNegativeInteger(value) {
  const number = Number.parseInt(String(value == null ? "" : value), 10);
  return Number.isFinite(number) && number > 0 ? number : 0;
}

function buildSnapshotTripIndex(root) {
  const realTrips = [];
  const legacyTrips = [];
  let maxPointTimestamp = 0;

  for (const [date, day] of Object.entries(root || {})) {
    if (!day || typeof day !== "object" || Array.isArray(day)) {
      continue;
    }

    const points = Array.isArray(day.points) ? day.points : [];
    let firstPointTime = 0;
    let lastPointTime = 0;
    for (const point of points) {
      const timestamp = Number(point && point.timestamp || 0);
      if (timestamp <= 0) {
        continue;
      }
      if (firstPointTime <= 0 || timestamp < firstPointTime) {
        firstPointTime = timestamp;
      }
      if (timestamp > lastPointTime) {
        lastPointTime = timestamp;
      }
      if (timestamp > maxPointTimestamp) {
        maxPointTimestamp = timestamp;
      }
    }

    if (Array.isArray(day.trips) && day.trips.length) {
      for (let i = 0; i < day.trips.length; i++) {
        const trip = normalizeSnapshotTrip(day.trips[i], date, i + 1);
        if (trip) {
          realTrips.push(trip);
        }
      }
      continue;
    }

    const startTime = Number(day.startTime || firstPointTime || 0);
    const endTime = Number(day.endTime || lastPointTime || startTime || 0);
    if (startTime > 0) {
      legacyTrips.push({
        id: "legacy_" + date,
        index: 1,
        date,
        startTime,
        endTime,
        effectiveEndTime: endTime
      });
    }
  }

  realTrips.sort((a, b) => a.startTime - b.startTime);
  for (let i = 0; i < realTrips.length; i++) {
    const trip = realTrips[i];
    const nextTrip = realTrips[i + 1];
    trip.effectiveEndTime = trip.endTime > 0
      ? trip.endTime
      : (nextTrip ? nextTrip.startTime - 1 : maxPointTimestamp || Number.MAX_SAFE_INTEGER);
  }
  legacyTrips.sort((a, b) => a.startTime - b.startTime);
  return { realTrips, legacyTrips };
}

function normalizeSnapshotTrip(rawTrip, date, index) {
  const trip = rawTrip || {};
  const startTime = Number(trip.startTime || 0);
  if (startTime <= 0) {
    return null;
  }
  return {
    id: String(trip.id || "trip_" + index),
    index,
    date,
    startTime,
    endTime: Number(trip.endTime || 0),
    effectiveEndTime: Number(trip.endTime || 0)
  };
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
        date: day.date || "",
        startTime,
        endTime: Number(trip.endTime || 0),
        effectiveEndTime: Number(trip.endTime || 0)
      });
    }
  }
  if (!trips.length && Number(day.startTime || 0) > 0) {
    trips.push({
      id: "legacy_1",
      index: 1,
      date: day.date || "",
      startTime: Number(day.startTime || 0),
      endTime: Number(day.endTime || 0),
      effectiveEndTime: Number(day.endTime || 0)
    });
  }
  return trips;
}

function findSnapshotTrip(index, timestamp, pointTripId = "") {
  const tripGroups = index && index.realTrips ? [index.realTrips, index.legacyTrips || []] : [index || []];
  if (pointTripId) {
    for (const trips of tripGroups) {
      const matched = findTripByIdAndTime(trips, pointTripId, timestamp);
      if (matched) {
        return matched;
      }
    }
  }
  for (const trips of tripGroups) {
    const matched = findTripByTime(trips, timestamp);
    if (matched) {
      return matched;
    }
  }
  return null;
}

function findTripByIdAndTime(trips, tripId, timestamp) {
  for (const trip of trips) {
    if (trip.id === tripId && isTripTimeMatch(trip, timestamp)) {
      return trip;
    }
  }
  return null;
}

function findTripByTime(trips, timestamp) {
  for (const trip of trips) {
    if (isTripTimeMatch(trip, timestamp)) {
      return trip;
    }
  }
  return null;
}

function isTripTimeMatch(trip, timestamp) {
  const endTime = trip.effectiveEndTime > 0 ? trip.effectiveEndTime : Number.MAX_SAFE_INTEGER;
  if (timestamp >= trip.startTime && timestamp <= endTime) {
    return true;
  }
  return false;
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
  <title>MotionTrace 管理后台</title>
  <script>
    window._AMapSecurityConfig = { securityJsCode: "a095663fd946546d91f3f03eab4c7b5a" };
    window.__amapReady = false;
    window.__amapCallbacks = [];
    window.onAMapReady = function () {
      window.__amapReady = true;
      window.__amapCallbacks.splice(0).forEach(function (callback) { callback(); });
    };
  </script>
  <script src="https://webapi.amap.com/maps?v=2.0&key=8c2e2da643b4d6cc38473cf1c5f1e0ac&callback=onAMapReady"></script>
  <style>
    :root {
      color-scheme: light;
      --bg: #f6f7f2;
      --surface: #ffffff;
      --surface-2: #f0f3ec;
      --ink: #17211c;
      --muted: #68756f;
      --line: #dfe5dc;
      --green: #1f7255;
      --green-dark: #14503c;
      --blue: #2b5876;
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
    .shell { width: min(1280px, calc(100vw - 28px)); margin: 0 auto; padding: 24px 0 40px; }
    header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
    .brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
    .mark { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 8px; background: #193f34; color: #f6fff8; font-weight: 800; box-shadow: var(--shadow); }
    h1 { margin: 0; font-size: 24px; line-height: 1.15; }
    .sub { margin: 5px 0 0; color: var(--muted); font-size: 14px; }
    .actions { display: flex; align-items: center; gap: 10px; }
    .status { min-width: 132px; padding: 8px 12px; border: 1px solid var(--line); background: var(--surface); border-radius: 8px; color: var(--muted); font-size: 13px; text-align: center; }
    .status.ok { color: var(--green); border-color: #bfdbc9; background: #f2faf5; }
    .status.err { color: var(--red); border-color: #edc8c2; background: #fff6f4; }
    .top-tabs { display: inline-flex; padding: 3px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); margin-bottom: 12px; }
    .top-tab, .tab { height: 32px; padding: 0 14px; border: 0; border-radius: 6px; background: transparent; color: var(--muted); }
    .top-tab.active, .tab.active { color: #fff; background: var(--blue); }
    .section { display: none; }
    .section.active { display: block; }
    .filters, .user-toolbar {
      display: grid;
      gap: 11px;
      align-items: end;
      padding: 14px;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--surface);
      box-shadow: var(--shadow);
    }
    .filters { grid-template-columns: minmax(240px, 1.2fr) 150px minmax(220px, 1fr) auto auto; }
    .user-toolbar { grid-template-columns: minmax(280px, 1fr) auto; margin-bottom: 12px; }
    label { display: block; margin: 0 0 6px; color: #435149; font-size: 12px; font-weight: 700; }
    input, select, button { height: 38px; border-radius: 7px; border: 1px solid var(--line); font: inherit; font-size: 14px; }
    input, select { width: 100%; padding: 0 12px; color: var(--ink); background: #fbfcfb; outline-color: #8db69f; }
    button { padding: 0 16px; border-color: transparent; background: var(--green); color: #fff; cursor: pointer; white-space: nowrap; font-weight: 700; }
    button.secondary { color: var(--green-dark); border-color: #cfe1d5; background: #f6fbf8; }
    button.ghost { color: var(--muted); border-color: var(--line); background: var(--surface); }
    button.danger { color: #fff; background: var(--red); }
    button:disabled { opacity: 0.55; cursor: default; }
    button:hover:not(:disabled) { filter: brightness(0.97); }
    .viewbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 14px 0 10px; }
    .tabs { display: inline-flex; padding: 3px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
    .stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin: 14px 0; }
    .metric { border: 1px solid var(--line); background: var(--surface); border-radius: 8px; padding: 13px 14px; min-height: 78px; }
    .metric b { display: block; font-size: 23px; line-height: 1.1; }
    .metric span { display: block; margin-top: 8px; color: var(--muted); font-size: 13px; }
    .panel { border: 1px solid var(--line); border-radius: 8px; background: var(--surface); overflow: hidden; }
    .table-wrap { overflow: auto; max-height: 560px; }
    table { width: 100%; border-collapse: collapse; min-width: 1040px; }
    th, td { padding: 11px 12px; border-bottom: 1px solid var(--line); text-align: left; font-size: 13px; white-space: nowrap; }
    th { position: sticky; top: 0; background: var(--surface-2); color: #405047; font-weight: 700; z-index: 1; }
    tbody tr:hover { background: #fbfcf8; }
    tbody tr:last-child td { border-bottom: 0; }
    .mono { font-family: ui-monospace, "SFMono-Regular", Consolas, monospace; color: #45524b; }
    .chip { display: inline-flex; align-items: center; min-height: 24px; padding: 0 8px; border-radius: 999px; background: #eef6f1; color: var(--green-dark); font-size: 12px; font-weight: 700; }
    .empty { padding: 28px; color: var(--muted); text-align: center; }
    .pager { display: flex; align-items: center; justify-content: flex-end; gap: 10px; padding: 10px 12px; border-top: 1px solid var(--line); color: var(--muted); font-size: 13px; }
    .map-panel { display: none; padding: 12px; }
    .map-panel.active { display: block; }
    .table-panel.hidden { display: none; }
    #amap { height: 580px; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; background: #eef3ef; }
    .map-note { padding-top: 8px; color: var(--muted); font-size: 12px; }
    .row-actions { display: flex; gap: 8px; }
    .row-actions button { height: 30px; padding: 0 10px; font-size: 12px; }
    dialog { width: min(440px, calc(100vw - 28px)); border: 1px solid var(--line); border-radius: 8px; padding: 0; color: var(--ink); background: var(--surface); box-shadow: 0 24px 70px rgba(22, 34, 28, 0.22); }
    dialog::backdrop { background: rgba(15, 25, 20, 0.32); }
    .dialog-body { padding: 18px; }
    .dialog-body h2 { margin: 0 0 14px; font-size: 19px; }
    .dialog-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 16px; }
    @media (max-width: 820px) {
      header { display: block; }
      .actions { margin-top: 12px; align-items: stretch; }
      .status { text-align: left; flex: 1; }
      .filters, .user-toolbar { grid-template-columns: 1fr; }
      .viewbar { display: block; }
      .tabs { margin-bottom: 8px; }
      .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      #amap { height: 430px; }
    }
  </style>
</head>
<body>
  <main class="shell">
    <header>
      <div class="brand">
        <div class="mark">MT</div>
        <div>
          <h1>MotionTrace 管理后台</h1>
          <p class="sub">轨迹查询、地图回放、用户管理</p>
        </div>
      </div>
      <div class="actions">
        <div id="status" class="status">已登录</div>
        <button id="logout" class="ghost">退出</button>
      </div>
    </header>

    <nav class="top-tabs" aria-label="后台模块">
      <button id="trackNav" class="top-tab active" type="button">轨迹管理</button>
      <button id="userNav" class="top-tab" type="button">用户管理</button>
    </nav>

    <section id="trackSection" class="section active">
      <section class="filters" aria-label="轨迹查询条件">
        <div>
          <label for="trackUser">用户</label>
          <input id="trackUser" type="search" list="userOptions" placeholder="选择或输入用户名 / 用户 ID">
          <datalist id="userOptions"></datalist>
        </div>
        <div>
          <label for="trackDate">轨迹日期</label>
          <input id="trackDate" type="date">
        </div>
        <div>
          <label for="trackTrip">行程 ID</label>
          <select id="trackTrip"><option value="">全部行程</option></select>
        </div>
        <button id="loadTrack">查询</button>
        <button id="clearTrack" class="secondary">重置</button>
      </section>

      <section class="viewbar" aria-label="视图切换">
        <div class="tabs" role="tablist">
          <button id="tableTab" class="tab active" type="button">列表</button>
          <button id="mapTab" class="tab" type="button">地图</button>
        </div>
        <button id="exportCsv" class="secondary">导出 CSV</button>
      </section>

      <section class="stats" aria-label="轨迹汇总">
        <div class="metric"><b id="mCount">0</b><span>GPS 点位</span></div>
        <div class="metric"><b id="mTrips">0</b><span>行程数</span></div>
        <div class="metric"><b id="mPage">1/1</b><span>列表页码</span></div>
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
              <tbody id="trackRows">
                <tr><td colspan="10" class="empty">正在加载轨迹点</td></tr>
              </tbody>
            </table>
          </div>
          <div class="pager">
            <button id="trackPrev" class="ghost" type="button">上一页</button>
            <span id="trackPager">第 1 页</span>
            <button id="trackNext" class="ghost" type="button">下一页</button>
          </div>
        </div>
        <div id="mapPanel" class="map-panel">
          <div id="amap"></div>
          <div id="mapNote" class="map-note">地图加载中</div>
        </div>
      </section>
    </section>

    <section id="userSection" class="section">
      <section class="user-toolbar" aria-label="用户查询">
        <div>
          <label for="userSearch">用户搜索</label>
          <input id="userSearch" type="search" placeholder="用户名或用户 ID">
        </div>
        <button id="loadUsers">查询</button>
      </section>
      <section class="panel">
        <div class="table-wrap" aria-label="用户列表">
          <table>
            <thead>
              <tr>
                <th>用户名</th>
                <th>邮箱</th>
                <th>用户 ID</th>
                <th>创建时间</th>
                <th>更新时间</th>
                <th>轨迹点</th>
                <th>行程</th>
                <th>最近同步</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody id="userRows">
              <tr><td colspan="9" class="empty">正在加载用户</td></tr>
            </tbody>
          </table>
        </div>
        <div class="pager">
          <button id="userPrev" class="ghost" type="button">上一页</button>
          <span id="userPager">第 1 页</span>
          <button id="userNext" class="ghost" type="button">下一页</button>
        </div>
      </section>
    </section>
  </main>

  <dialog id="editDialog">
    <form method="dialog" class="dialog-body">
      <h2>修改用户信息</h2>
      <input id="editUserId" type="hidden">
      <label for="editUsername">用户名</label>
      <input id="editUsername" type="text" minlength="3" maxlength="32" required>
      <label for="editEmail">邮箱</label>
      <input id="editEmail" type="email" required>
      <div class="dialog-actions">
        <button id="cancelEdit" class="ghost" type="button">取消</button>
        <button id="saveEdit" type="button">保存</button>
      </div>
    </form>
  </dialog>

  <dialog id="resetDialog">
    <form method="dialog" class="dialog-body">
      <h2>重置用户密码</h2>
      <input id="resetUserId" type="hidden">
      <label for="resetPassword">新密码</label>
      <input id="resetPassword" type="password" minlength="8" required>
      <div class="dialog-actions">
        <button id="cancelReset" class="ghost" type="button">取消</button>
        <button id="saveReset" type="button">重置</button>
      </div>
    </form>
  </dialog>

  <script>
    var trackState = { page: 1, pageSize: 50, total: 0, items: [], mapItems: [], view: "table" };
    var userState = { page: 1, pageSize: 20, total: 0, items: [] };
    var amap = null;
    var amapOverlays = [];
    var userOptionTimer = 0;
    var trackUser = document.getElementById("trackUser");
    var trackDate = document.getElementById("trackDate");
    var trackTrip = document.getElementById("trackTrip");
    var trackRows = document.getElementById("trackRows");
    var userRows = document.getElementById("userRows");
    var statusBox = document.getElementById("status");

    document.getElementById("logout").addEventListener("click", logout);
    document.getElementById("trackNav").addEventListener("click", function () { switchSection("track"); });
    document.getElementById("userNav").addEventListener("click", function () { switchSection("users"); });
    document.getElementById("loadTrack").addEventListener("click", function () { loadTrips().then(function () { loadTrackPage(1); }); });
    document.getElementById("clearTrack").addEventListener("click", resetTrackFilters);
    document.getElementById("exportCsv").addEventListener("click", exportCsv);
    document.getElementById("tableTab").addEventListener("click", function () { setTrackView("table"); });
    document.getElementById("mapTab").addEventListener("click", function () { setTrackView("map"); });
    document.getElementById("trackPrev").addEventListener("click", function () { loadTrackPage(trackState.page - 1); });
    document.getElementById("trackNext").addEventListener("click", function () { loadTrackPage(trackState.page + 1); });
    document.getElementById("loadUsers").addEventListener("click", function () { loadUsers(1); });
    document.getElementById("userPrev").addEventListener("click", function () { loadUsers(userState.page - 1); });
    document.getElementById("userNext").addEventListener("click", function () { loadUsers(userState.page + 1); });
    document.getElementById("saveEdit").addEventListener("click", saveUserEdit);
    document.getElementById("saveReset").addEventListener("click", savePasswordReset);
    userRows.addEventListener("click", handleUserAction);
    document.getElementById("cancelEdit").addEventListener("click", function () { document.getElementById("editDialog").close(); });
    document.getElementById("cancelReset").addEventListener("click", function () { document.getElementById("resetDialog").close(); });
    trackUser.addEventListener("input", function () {
      clearTimeout(userOptionTimer);
      userOptionTimer = setTimeout(function () { loadUserOptions(trackUser.value.trim()); }, 180);
    });
    trackUser.addEventListener("change", function () { loadTrips(); });
    trackDate.addEventListener("change", function () { loadTrips(); });
    trackTrip.addEventListener("change", function () { loadTrackPage(1); });
    document.getElementById("userSearch").addEventListener("keydown", function (event) {
      if (event.key === "Enter") loadUsers(1);
    });

    setYesterday();
    loadUserOptions("");
    loadTrips().then(function () { return loadTrackPage(1); });
    loadUsers(1);

    function switchSection(section) {
      document.getElementById("trackNav").className = "top-tab" + (section === "track" ? " active" : "");
      document.getElementById("userNav").className = "top-tab" + (section === "users" ? " active" : "");
      document.getElementById("trackSection").className = "section" + (section === "track" ? " active" : "");
      document.getElementById("userSection").className = "section" + (section === "users" ? " active" : "");
      if (section === "track" && trackState.view === "map") renderAmap(trackState.mapItems);
    }

    function setYesterday() {
      var date = new Date();
      date.setDate(date.getDate() - 1);
      trackDate.value = localDateValue(date);
    }

    function resetTrackFilters() {
      trackUser.value = "";
      setYesterday();
      loadTrips().then(function () { loadTrackPage(1); });
    }

    async function loadUserOptions(search) {
      try {
        var params = new URLSearchParams();
        params.set("pageSize", "100");
        if (search) params.set("search", search);
        var payload = await fetchJson("/admin/api/users?" + params.toString());
        document.getElementById("userOptions").innerHTML = (payload.items || []).map(function (item) {
          var value = item.username || item.email || item.id;
          var label = (item.email || shortId(item.id));
          return '<option value="' + escapeHtml(value) + '">' + escapeHtml(label) + '</option>';
        }).join("");
      } catch (error) {
      }
    }

    async function loadTrips() {
      var current = trackTrip.value;
      trackTrip.innerHTML = '<option value="">全部行程</option>';
      try {
        var params = buildTrackFilterParams();
        var payload = await fetchJson("/admin/api/trips?" + params.toString());
        var items = payload.items || [];
        trackTrip.innerHTML = '<option value="">全部行程</option>' + items.map(function (item) {
          var label = (item.tripId || "-") + " · " + formatTimeOnly(item.startTime) + "-" + formatTimeOnly(item.endTime) + " · " + number(item.pointCount) + " 点";
          return '<option value="' + escapeHtml(item.tripId || "") + '">' + escapeHtml(label) + '</option>';
        }).join("");
        if (current && items.some(function (item) { return item.tripId === current; })) {
          trackTrip.value = current;
        }
      } catch (error) {
        setStatus("行程加载失败", "err");
      }
    }

    async function loadTrackPage(page) {
      var nextPage = Math.max(1, page || 1);
      setStatus("查询中", "");
      try {
        var params = buildTrackFilterParams();
        if (trackTrip.value) params.set("tripId", trackTrip.value);
        params.set("page", String(nextPage));
        params.set("pageSize", String(trackState.pageSize));
        var payload = await fetchJson("/admin/api/track-points?" + params.toString());
        trackState.page = payload.page || nextPage;
        trackState.pageSize = payload.pageSize || trackState.pageSize;
        trackState.total = payload.total || 0;
        trackState.items = payload.items || [];
        renderTrackRows(trackState.items);
        renderTrackPager();
        await loadMapPoints();
        setStatus("已加载 " + trackState.items.length + " 点", "ok");
      } catch (error) {
        trackRows.innerHTML = '<tr><td colspan="10" class="empty">' + escapeHtml(error.message || "查询失败") + '</td></tr>';
        setStatus("查询失败", "err");
      }
    }

    async function loadMapPoints() {
      var params = buildTrackFilterParams();
      if (trackTrip.value) params.set("tripId", trackTrip.value);
      var payload = await fetchJson("/admin/api/track-map?" + params.toString());
      trackState.mapItems = payload.items || [];
      renderTrackMetrics();
      if (trackState.view === "map") renderAmap(trackState.mapItems);
    }

    function buildTrackFilterParams() {
      var params = new URLSearchParams();
      if (trackUser.value.trim()) params.set("user", trackUser.value.trim());
      if (trackDate.value) params.set("date", trackDate.value);
      return params;
    }

    function displayUser(item) {
      return (item && (item.username || item.email || shortId(item.userId || item.id))) || "-";
    }

    function renderTrackRows(items) {
      if (!items.length) {
        trackRows.innerHTML = '<tr><td colspan="10" class="empty">没有匹配的轨迹点</td></tr>';
        return;
      }
      trackRows.innerHTML = items.map(function (item) {
        return "<tr>" +
          "<td>" + formatTime(item.timestamp) + "</td>" +
          "<td>" + escapeHtml(displayUser(item)) + "</td>" +
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

    function renderTrackPager() {
      var pages = Math.max(1, Math.ceil(trackState.total / trackState.pageSize));
      document.getElementById("trackPager").textContent = "第 " + trackState.page + " / " + pages + " 页，共 " + number(trackState.total) + " 点";
      document.getElementById("trackPrev").disabled = trackState.page <= 1;
      document.getElementById("trackNext").disabled = trackState.page >= pages;
      document.getElementById("mPage").textContent = trackState.page + "/" + pages;
    }

    function renderTrackMetrics() {
      var trips = new Set();
      var times = [];
      trackState.mapItems.forEach(function (item) {
        trips.add((item.userId || "") + "|" + (item.date || "") + "|" + (item.tripId || ""));
        if (Number(item.timestamp || 0) > 0) times.push(Number(item.timestamp));
      });
      times.sort(function (a, b) { return a - b; });
      document.getElementById("mCount").textContent = number(trackState.total || trackState.mapItems.length);
      document.getElementById("mTrips").textContent = number(trips.size);
      document.getElementById("mRange").textContent = times.length ? shortTimeRange(times[0], times[times.length - 1]) : "-";
    }

    function setTrackView(view) {
      trackState.view = view;
      document.getElementById("tableTab").className = "tab" + (view === "table" ? " active" : "");
      document.getElementById("mapTab").className = "tab" + (view === "map" ? " active" : "");
      document.getElementById("tablePanel").className = view === "table" ? "table-panel" : "table-panel hidden";
      document.getElementById("mapPanel").className = view === "map" ? "map-panel active" : "map-panel";
      if (view === "map") renderAmap(trackState.mapItems);
    }

    async function renderAmap(items) {
      var note = document.getElementById("mapNote");
      note.textContent = "地图加载中";
      try {
        await waitAmap();
        if (!amap) {
          amap = new AMap.Map("amap", { zoom: 12, resizeEnable: true, viewMode: "2D" });
        }
        amap.remove(amapOverlays);
        amapOverlays = [];
        if (!items.length) {
          note.textContent = "没有可绘制的轨迹点";
          return;
        }
        var groups = groupByTrip(items);
        var colors = ["#1f7255", "#2b5876", "#a76321", "#7c5a9e", "#b64b3d", "#546b2f", "#3f6f7e"];
        groups.forEach(function (group, index) {
          var path = group.items.slice().sort(function (a, b) { return Number(a.timestamp || 0) - Number(b.timestamp || 0); }).map(function (item) {
            return [Number(item.longitude), Number(item.latitude)];
          });
          if (!path.length) return;
          var color = colors[index % colors.length];
          if (path.length > 1) {
            amapOverlays.push(new AMap.Polyline({ path: path, strokeColor: color, strokeOpacity: 0.9, strokeWeight: 6, lineJoin: "round", lineCap: "round" }));
          }
          amapOverlays.push(new AMap.Marker({ position: path[0], title: group.label + " 起点", label: { content: "起", direction: "top" } }));
          amapOverlays.push(new AMap.Marker({ position: path[path.length - 1], title: group.label + " 终点", label: { content: "终", direction: "top" } }));
        });
        amap.add(amapOverlays);
        amap.setFitView(amapOverlays, false, [40, 40, 40, 40]);
        amap.resize();
        note.textContent = "已绘制 " + number(items.length) + " 个点";
      } catch (error) {
        note.textContent = "地图加载失败";
      }
    }

    function waitAmap() {
      if (window.AMap && window.__amapReady) return Promise.resolve();
      return new Promise(function (resolve, reject) {
        var timer = setTimeout(function () { reject(new Error("amap_timeout")); }, 12000);
        window.__amapCallbacks.push(function () {
          clearTimeout(timer);
          resolve();
        });
      });
    }

    function groupByTrip(items) {
      var map = {};
      items.forEach(function (item) {
        var key = (item.userId || "") + "|" + (item.date || "") + "|" + (item.tripId || "");
        if (!map[key]) {
          map[key] = { label: displayUser(item) + " / " + (item.date || "-") + " / " + (item.tripId || "-"), items: [] };
        }
        map[key].items.push(item);
      });
      return Object.keys(map).map(function (key) { return map[key]; });
    }

    async function loadUsers(page) {
      var nextPage = Math.max(1, page || 1);
      try {
        var params = new URLSearchParams();
        params.set("page", String(nextPage));
        params.set("pageSize", String(userState.pageSize));
        var search = document.getElementById("userSearch").value.trim();
        if (search) params.set("search", search);
        var payload = await fetchJson("/admin/api/users?" + params.toString());
        userState.page = payload.page || nextPage;
        userState.pageSize = payload.pageSize || userState.pageSize;
        userState.total = payload.total || 0;
        userState.items = payload.items || [];
        renderUserRows(userState.items);
        renderUserPager();
      } catch (error) {
        userRows.innerHTML = '<tr><td colspan="9" class="empty">' + escapeHtml(error.message || "用户加载失败") + '</td></tr>';
      }
    }

    function renderUserRows(items) {
      if (!items.length) {
        userRows.innerHTML = '<tr><td colspan="9" class="empty">没有匹配用户</td></tr>';
        return;
      }
      userRows.innerHTML = items.map(function (item) {
        return "<tr>" +
          "<td>" + escapeHtml(item.username || "-") + "</td>" +
          "<td>" + escapeHtml(item.email || "-") + "</td>" +
          '<td class="mono">' + escapeHtml(shortId(item.id)) + "</td>" +
          "<td>" + formatTime(item.createdAt) + "</td>" +
          "<td>" + formatTime(item.updatedAt) + "</td>" +
          "<td>" + number(item.pointCount) + "</td>" +
          "<td>" + number(item.tripCount) + "</td>" +
          "<td>" + formatTime(item.snapshotUpdatedAt) + "</td>" +
          '<td><div class="row-actions">' +
          '<button class="secondary" type="button" data-action="edit" data-id="' + escapeHtml(item.id) + '" data-username="' + escapeHtml(item.username || "") + '" data-email="' + escapeHtml(item.email || "") + '">修改</button>' +
          '<button class="secondary" type="button" data-action="reset" data-id="' + escapeHtml(item.id) + '">重置</button>' +
          '<button class="danger" type="button" data-action="delete" data-id="' + escapeHtml(item.id) + '" data-username="' + escapeHtml(item.username || "") + '" data-email="' + escapeHtml(item.email || "") + '">删除</button>' +
          "</div></td>" +
          "</tr>";
      }).join("");
    }

    function renderUserPager() {
      var pages = Math.max(1, Math.ceil(userState.total / userState.pageSize));
      document.getElementById("userPager").textContent = "第 " + userState.page + " / " + pages + " 页，共 " + number(userState.total) + " 用户";
      document.getElementById("userPrev").disabled = userState.page <= 1;
      document.getElementById("userNext").disabled = userState.page >= pages;
    }

    function handleUserAction(event) {
      var button = event.target.closest("button[data-action]");
      if (!button) return;
      var action = button.getAttribute("data-action");
      var id = button.getAttribute("data-id") || "";
      var username = button.getAttribute("data-username") || "";
      var email = button.getAttribute("data-email") || "";
      if (action === "edit") openEditUser(id, username, email);
      if (action === "reset") openResetUser(id);
      if (action === "delete") removeUser(id, username || email);
    }

    function openEditUser(id, username, email) {
      document.getElementById("editUserId").value = id;
      document.getElementById("editUsername").value = username;
      document.getElementById("editEmail").value = email;
      document.getElementById("editDialog").showModal();
    }

    function openResetUser(id) {
      document.getElementById("resetUserId").value = id;
      document.getElementById("resetPassword").value = "";
      document.getElementById("resetDialog").showModal();
    }

    async function saveUserEdit() {
      var id = document.getElementById("editUserId").value;
      var username = document.getElementById("editUsername").value.trim();
      var email = document.getElementById("editEmail").value.trim();
      await fetchJson("/admin/api/users/" + encodeURIComponent(id), { method: "PATCH", body: JSON.stringify({ username: username, email: email }) });
      document.getElementById("editDialog").close();
      await loadUsers(userState.page);
      await loadUserOptions(trackUser.value.trim());
      setStatus("用户已更新", "ok");
    }

    async function savePasswordReset() {
      var id = document.getElementById("resetUserId").value;
      var password = document.getElementById("resetPassword").value;
      await fetchJson("/admin/api/users/" + encodeURIComponent(id) + "/reset-password", { method: "POST", body: JSON.stringify({ password: password }) });
      document.getElementById("resetDialog").close();
      setStatus("密码已重置", "ok");
    }

    async function removeUser(id, name) {
      if (!confirm("确认删除用户 " + (name || id) + "？该用户的轨迹和同步记录也会删除。")) return;
      await fetchJson("/admin/api/users/" + encodeURIComponent(id), { method: "DELETE" });
      await loadUsers(userState.page);
      await loadUserOptions(trackUser.value.trim());
      await loadTrips();
      await loadTrackPage(1);
      setStatus("用户已删除", "ok");
    }

    async function logout() {
      await fetch("/admin/api/logout", { method: "POST", credentials: "same-origin" }).catch(function () {});
      location.href = "/admin/login";
    }

    function exportCsv() {
      var params = buildTrackFilterParams();
      if (trackTrip.value) params.set("tripId", trackTrip.value);
      location.href = "/admin/api/track-points.csv?" + params.toString();
    }

    async function fetchJson(url, options) {
      var requestOptions = options || {};
      requestOptions.credentials = "same-origin";
      requestOptions.headers = Object.assign({ "Content-Type": "application/json" }, requestOptions.headers || {});
      var response = await fetch(url, requestOptions);
      var payload = await response.json().catch(function () { return {}; });
      if (!response.ok) {
        if (response.status === 401) {
          location.href = "/admin/login";
          return {};
        }
        throw new Error(errorMessage(payload.error));
      }
      return payload;
    }

    function setStatus(text, type) {
      statusBox.textContent = text;
      statusBox.className = "status" + (type ? " " + type : "");
    }

    function errorMessage(code) {
      var map = {
        unauthorized: "登录已过期",
        invalid_username: "用户名需为 3-32 位字母、数字、下划线、点或横线",
        username_exists: "用户名已存在",
        invalid_email: "邮箱格式不正确",
        email_exists: "邮箱已存在",
        invalid_password: "密码至少 8 位",
        user_not_found: "用户不存在"
      };
      return map[code] || code || "操作失败";
    }

    function localDateValue(date) {
      var year = date.getFullYear();
      var month = String(date.getMonth() + 1).padStart(2, "0");
      var day = String(date.getDate()).padStart(2, "0");
      return year + "-" + month + "-" + day;
    }

    function formatTime(value) {
      if (!value) return "-";
      return new Date(Number(value)).toLocaleString("zh-CN", { hour12: false });
    }

    function formatTimeOnly(value) {
      if (!value) return "-";
      return new Date(Number(value)).toLocaleTimeString("zh-CN", { hour12: false, hour: "2-digit", minute: "2-digit" });
    }

    function shortTimeRange(start, end) {
      if (!start || !end) return "-";
      var s = new Date(Number(start));
      var e = new Date(Number(end));
      var sameDay = s.toLocaleDateString("zh-CN") === e.toLocaleDateString("zh-CN");
      if (sameDay) {
        return s.toLocaleDateString("zh-CN") + " " + formatTimeOnly(start) + "-" + formatTimeOnly(end);
      }
      return s.toLocaleDateString("zh-CN") + " " + formatTimeOnly(start) + " - " + e.toLocaleDateString("zh-CN") + " " + formatTimeOnly(end);
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

function adminTrackLegacyPage() {
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
