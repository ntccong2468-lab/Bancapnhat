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
