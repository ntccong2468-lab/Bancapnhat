PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_salt TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('user','admin')),
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','suspended')),
  email_verified INTEGER NOT NULL DEFAULT 0 CHECK (email_verified IN (0,1)),
  license_expires_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_users_created ON users(created_at DESC);
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
CREATE TABLE IF NOT EXISTS action_tokens (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose TEXT NOT NULL CHECK (purpose IN ('verify','reset')),
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS rate_limits (
  key TEXT PRIMARY KEY,
  count INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS audit_log (
  id TEXT PRIMARY KEY,
  actor_id TEXT,
  target_id TEXT,
  action TEXT NOT NULL,
  detail TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(created_at DESC);

-- License extension for new databases.
-- VNCode License v1: additive migration; no changes to existing user accounts.
-- The users.license_expires_at value remains the only source of truth for access.
CREATE TABLE IF NOT EXISTS license_codes (
  id TEXT PRIMARY KEY,
  code_hash TEXT NOT NULL UNIQUE,
  code_last4 TEXT NOT NULL,
  days INTEGER NOT NULL CHECK(days BETWEEN 1 AND 3650),
  assigned_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  created_by TEXT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  state TEXT NOT NULL DEFAULT 'unused' CHECK(state IN ('unused','redeemed','void')),
  redeemed_by TEXT REFERENCES users(id),
  redeemed_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_license_codes_created ON license_codes(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_license_codes_assigned ON license_codes(assigned_user_id);
CREATE TABLE IF NOT EXISTS license_redemptions (
  id TEXT PRIMARY KEY,
  code_id TEXT NOT NULL UNIQUE REFERENCES license_codes(id) ON DELETE RESTRICT,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  claimed_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_license_redemptions_user ON license_redemptions(user_id,claimed_at DESC);
-- Additive and repeatable. Apply after 0001_license_codes.sql; never import a user backup over production.
CREATE TABLE IF NOT EXISTS desktop_registrations (
 id TEXT PRIMARY KEY, email TEXT NOT NULL, otp_hash TEXT NOT NULL,
 token_hash TEXT NOT NULL UNIQUE, attempts INTEGER NOT NULL DEFAULT 0,
 verified INTEGER NOT NULL DEFAULT 0, user_id TEXT REFERENCES users(id),
 expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_desktop_registration_expiry ON desktop_registrations(expires_at);
CREATE TABLE IF NOT EXISTS license_policy (
 user_id TEXT PRIMARY KEY REFERENCES users(id), max_devices INTEGER NOT NULL DEFAULT 1 CHECK(max_devices BETWEEN 1 AND 10),
 max_shops INTEGER NOT NULL DEFAULT 1 CHECK(max_shops BETWEEN 1 AND 100),
 offline_hours INTEGER NOT NULL DEFAULT 72 CHECK(offline_hours BETWEEN 0 AND 168),
 revision INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS license_devices (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), public_key TEXT NOT NULL,
 key_hash TEXT NOT NULL, name TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0,
 created_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, last_lease_expires_at INTEGER,
 UNIQUE(user_id,key_hash)
);
CREATE TABLE IF NOT EXISTS desktop_sessions (
 token_hash TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES license_devices(id), expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS desktop_challenges (
 id TEXT PRIMARY KEY, credential_hash TEXT NOT NULL, key_hash TEXT NOT NULL,
 action TEXT NOT NULL, message TEXT NOT NULL, expires_at INTEGER NOT NULL,
 used INTEGER NOT NULL DEFAULT 0, claim TEXT
);

-- Additive and repeatable. Indexes only; no user or entitlement data is rewritten.
CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON sessions(expires_at);
CREATE INDEX IF NOT EXISTS idx_action_tokens_expiry ON action_tokens(expires_at);
CREATE INDEX IF NOT EXISTS idx_rate_limits_expiry ON rate_limits(expires_at);
CREATE INDEX IF NOT EXISTS idx_desktop_sessions_expiry ON desktop_sessions(expires_at);
CREATE INDEX IF NOT EXISTS idx_desktop_challenges_expiry ON desktop_challenges(expires_at);
CREATE INDEX IF NOT EXISTS idx_license_devices_active_user ON license_devices(user_id) WHERE revoked=0;
CREATE INDEX IF NOT EXISTS idx_license_devices_user_created ON license_devices(user_id,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_desktop_sessions_device ON desktop_sessions(device_id);
CREATE INDEX IF NOT EXISTS idx_users_admin_status_created ON users(role,status,created_at DESC,id);
