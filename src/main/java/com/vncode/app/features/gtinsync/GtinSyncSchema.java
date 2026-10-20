package com.vncode.app.features.gtinsync;

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
                CREATE TABLE IF NOT EXISTS gtin_sync_verified_mappings(
                  shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  marketplace TEXT NOT NULL,product_id TEXT NOT NULL,variant_id TEXT NOT NULL,
                  gtin TEXT NOT NULL,owner TEXT NOT NULL,source TEXT NOT NULL,verified_at TEXT NOT NULL,
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
            // One job owns an active card. Its sibling sizes may share one atomic HTTP update.
            // Different jobs remain excluded by storage triggers, including after a restart.
            s.execute("DROP INDEX IF EXISTS gtin_sync_active_product");
            boolean oldUnique=false;
            try(var indexes=c.createStatement();var rows=indexes.executeQuery("PRAGMA index_list(gtin_sync_items)")){
                while(rows.next())if("gtin_sync_inflight_product".equals(rows.getString("name")))oldUnique=rows.getInt("unique")==1;
            }
            if(oldUnique)s.execute("DROP INDEX gtin_sync_inflight_product");
            s.execute("""
                CREATE INDEX IF NOT EXISTS gtin_sync_inflight_product ON
                  gtin_sync_items(shop_id,marketplace,product_id)
                  WHERE status IN ('VALIDATING','SENDING','AWAITING_VERIFICATION','RECONCILE_REQUIRED')
                """);
            String conflict="NEW.status IN ('VALIDATING','SENDING','AWAITING_VERIFICATION','RECONCILE_REQUIRED') AND EXISTS(SELECT 1 FROM gtin_sync_items other WHERE other.id<>NEW.id AND other.shop_id=NEW.shop_id AND other.marketplace=NEW.marketplace AND other.product_id=NEW.product_id AND other.job_id<>NEW.job_id AND other.status IN ('VALIDATING','SENDING','AWAITING_VERIFICATION','RECONCILE_REQUIRED'))";
            s.execute("CREATE TRIGGER IF NOT EXISTS gtin_sync_claim_insert BEFORE INSERT ON gtin_sync_items WHEN "+conflict+" BEGIN SELECT RAISE(ABORT,'gtin_product_busy'); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS gtin_sync_claim_update BEFORE UPDATE OF status,job_id,product_id,shop_id,marketplace ON gtin_sync_items WHEN "+conflict+" BEGIN SELECT RAISE(ABORT,'gtin_product_busy'); END");
            s.execute("CREATE INDEX IF NOT EXISTS gtin_sync_job_items ON gtin_sync_items(job_id,updated_at,id)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS gtin_sync_events(
                  id INTEGER PRIMARY KEY AUTOINCREMENT, item_id TEXT NOT NULL
                  REFERENCES gtin_sync_items(id) ON DELETE CASCADE,
                  status TEXT NOT NULL, created_at TEXT NOT NULL)
                """);
        }
    }
}
