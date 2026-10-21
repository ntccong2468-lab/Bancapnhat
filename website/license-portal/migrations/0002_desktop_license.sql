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
