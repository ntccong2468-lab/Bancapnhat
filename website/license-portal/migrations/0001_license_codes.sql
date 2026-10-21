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
