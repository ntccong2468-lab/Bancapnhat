package com.vncode.app.features.sync;

import java.sql.Connection;
import java.sql.SQLException;

/** Additive storage: existing marketplace journals remain authoritative for writes. */
public final class SyncSchema {
    private SyncSchema() {}
    public static void initialize(Connection c) throws SQLException {
        com.vncode.app.features.kiz.status.Kn3Repository.initialize(c);
        com.vncode.app.features.gs1.Gs1AutomaticSyncService.initialize(c);
        try (var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS sync_gtin_issues(item_id TEXT PRIMARY KEY REFERENCES gtin_sync_items(id) ON DELETE CASCADE,code TEXT NOT NULL,updated_at TEXT NOT NULL)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS sync_streams(
                  shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  stream TEXT NOT NULL,owner TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,
                  requested INTEGER NOT NULL DEFAULT 0,checkpoint TEXT NOT NULL DEFAULT '',
                  last_attempt TEXT,last_success TEXT,last_complete TEXT,next_attempt TEXT NOT NULL,
                  failures INTEGER NOT NULL DEFAULT 0,error_code TEXT NOT NULL DEFAULT '',
                  PRIMARY KEY(shop_id,stream))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS sync_runs(
                  id TEXT PRIMARY KEY,shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  marketplace TEXT NOT NULL,owner TEXT NOT NULL,kind TEXT NOT NULL,
                  state TEXT NOT NULL,step TEXT NOT NULL,total INTEGER NOT NULL DEFAULT 0,
                  created_at TEXT NOT NULL,updated_at TEXT NOT NULL,finished_at TEXT,
                  error_code TEXT NOT NULL DEFAULT '',source_revision TEXT NOT NULL DEFAULT '')
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS sync_run_items(
                  id TEXT PRIMARY KEY,run_id TEXT NOT NULL REFERENCES sync_runs(id) ON DELETE CASCADE,
                  product_id TEXT NOT NULL,variant_id TEXT NOT NULL,sku TEXT NOT NULL DEFAULT '',
                  article TEXT NOT NULL DEFAULT '',color TEXT NOT NULL DEFAULT '',size TEXT NOT NULL DEFAULT '',
                  gtin TEXT NOT NULL DEFAULT '',state TEXT NOT NULL,step TEXT NOT NULL,
                  error_code TEXT NOT NULL DEFAULT '',child_job TEXT NOT NULL DEFAULT '',
                  evidence_json TEXT NOT NULL DEFAULT '{}',updated_at TEXT NOT NULL,
                  UNIQUE(run_id,product_id,variant_id))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS sync_run_events(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT NOT NULL REFERENCES sync_runs(id) ON DELETE CASCADE,
                  item_id TEXT,step TEXT NOT NULL,state TEXT NOT NULL,error_code TEXT NOT NULL DEFAULT '',
                  created_at TEXT NOT NULL)
                """);
            s.execute("CREATE INDEX IF NOT EXISTS sync_runs_shop_time ON sync_runs(shop_id,created_at DESC)");
            s.execute("CREATE INDEX IF NOT EXISTS sync_items_run_state ON sync_run_items(run_id,state)");
            s.execute("CREATE INDEX IF NOT EXISTS sync_items_child ON sync_run_items(run_id,child_job)");
        }
    }
}
