package com.tuandev.fbsbarcode.features.gtinsync;

import java.sql.*;

public final class GtinSyncSchema {
    private GtinSyncSchema() {}
    public static void initialize(Connection c) throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS gtin_sync_catalog(
                  shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  marketplace TEXT NOT NULL, gtin TEXT NOT NULL, data_json TEXT NOT NULL,
                  PRIMARY KEY(shop_id,marketplace,gtin))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS gtin_sync_mappings(
                  shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  marketplace TEXT NOT NULL, product_id TEXT NOT NULL, variant_id TEXT NOT NULL,
                  gtin TEXT NOT NULL, updated_at TEXT NOT NULL,
                  PRIMARY KEY(shop_id,marketplace,product_id,variant_id))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS gtin_sync_jobs(
                  id TEXT PRIMARY KEY, dedup_key TEXT NOT NULL UNIQUE,
                  shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  marketplace TEXT NOT NULL, paused INTEGER NOT NULL DEFAULT 0,
                  created_at TEXT NOT NULL)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS gtin_sync_items(
                  id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES gtin_sync_jobs(id) ON DELETE CASCADE,
                  shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  marketplace TEXT NOT NULL, product_id TEXT NOT NULL, variant_id TEXT NOT NULL,
                  preview_json TEXT NOT NULL, status TEXT NOT NULL, remote_task_id TEXT NOT NULL DEFAULT '',
                  updated_at TEXT NOT NULL)
                """);
            // Serialize mutations at card/product level, not just at size level.
            s.execute("DROP INDEX IF EXISTS gtin_sync_active_product");
            s.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS gtin_sync_inflight_product ON
                  gtin_sync_items(shop_id,marketplace,product_id)
                  WHERE status IN ('VALIDATING','SENDING','AWAITING_VERIFICATION','RECONCILE_REQUIRED')
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS gtin_sync_events(
                  id INTEGER PRIMARY KEY AUTOINCREMENT, item_id TEXT NOT NULL
                  REFERENCES gtin_sync_items(id) ON DELETE CASCADE,
                  status TEXT NOT NULL, created_at TEXT NOT NULL)
                """);
        }
    }
}
