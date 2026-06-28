const SESSION_TTL_MS = 1000 * 60 * 60 * 24 * 30;
const MAX_PAYLOAD_BYTES = 900 * 1024;

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
      if (request.method === "POST" && url.pathname === "/auth/register") {
        return register(request, env);
      }
      if (request.method === "POST" && url.pathname === "/auth/login") {
        return login(request, env);
      }
      if (request.method === "POST" && url.pathname === "/sync/upload") {
        return uploadSnapshot(request, env);
      }
      if (request.method === "GET" && url.pathname === "/sync/download") {
        return downloadSnapshot(request, env);
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
  if (!email || password.length < 8) {
    return json({ error: "invalid_credentials" }, 400);
  }

  const now = Date.now();
  const salt = randomToken(16);
  const user = {
    id: crypto.randomUUID(),
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
  const passwordHash = await hashPassword(password, user.password_salt);
  if (passwordHash !== user.password_hash) {
    return json({ error: "invalid_credentials" }, 401);
  }

  const token = await createSession(env, user.id);
  return json({ token, user: { id: user.id, email: user.email } });
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
  await env.DB.prepare(
    "INSERT INTO track_snapshots (user_id, payload, updated_at) VALUES (?, ?, ?) " +
      "ON CONFLICT(user_id) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at"
  ).bind(session.user_id, payload, now).run();
  return json({ ok: true, updatedAt: now, bytes: size });
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

async function hashPassword(password, salt) {
  const data = new TextEncoder().encode(`${salt}:${password}`);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return base64Url(new Uint8Array(digest));
}

function randomToken(bytes) {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return base64Url(buffer);
}

function base64Url(bytes) {
  let value = "";
  for (const byte of bytes) {
    value += String.fromCharCode(byte);
  }
  return btoa(value).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
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
  response.headers.set("Access-Control-Allow-Headers", "Content-Type,Authorization");
  return response;
}
