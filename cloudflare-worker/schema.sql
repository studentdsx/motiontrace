CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  password_salt TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS track_snapshots (
  user_id TEXT PRIMARY KEY,
  payload TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS sync_submissions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  bytes INTEGER NOT NULL,
  day_count INTEGER NOT NULL DEFAULT 0,
  point_count INTEGER NOT NULL DEFAULT 0,
  checkin_count INTEGER NOT NULL DEFAULT 0,
  trip_count INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS track_points (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  date TEXT NOT NULL,
  trip_id TEXT NOT NULL,
  trip_index INTEGER NOT NULL DEFAULT 0,
  point_index INTEGER NOT NULL DEFAULT 0,
  timestamp INTEGER NOT NULL,
  longitude REAL NOT NULL,
  latitude REAL NOT NULL,
  accuracy REAL NOT NULL DEFAULT 0,
  speed REAL NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON sessions(expires_at);
CREATE INDEX IF NOT EXISTS idx_sync_submissions_user_id ON sync_submissions(user_id);
CREATE INDEX IF NOT EXISTS idx_sync_submissions_created_at ON sync_submissions(created_at);
CREATE INDEX IF NOT EXISTS idx_track_points_user_id ON track_points(user_id);
CREATE INDEX IF NOT EXISTS idx_track_points_trip_id ON track_points(trip_id);
CREATE INDEX IF NOT EXISTS idx_track_points_timestamp ON track_points(timestamp);
CREATE INDEX IF NOT EXISTS idx_track_points_user_time ON track_points(user_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_track_points_date ON track_points(date);
