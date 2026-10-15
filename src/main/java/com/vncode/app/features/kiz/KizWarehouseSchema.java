package com.vncode.app.features.kiz;

import java.sql.*;

public final class KizWarehouseSchema {
    private KizWarehouseSchema(){}
    public static void initialize(Connection c)throws SQLException {
        boolean priority=false;try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info(kiz_codes)")){while(r.next())if(r.getString("name").equals("source_priority"))priority=true;}
        try(var s=c.createStatement()){
            if(!priority)s.execute("ALTER TABLE kiz_codes ADD COLUMN source_priority INTEGER NOT NULL DEFAULT 1");
            s.execute("CREATE INDEX IF NOT EXISTS idx_kiz_codes_priority_circulation ON kiz_codes(shop_id,gtin,source_priority,id) WHERE status='AVAILABLE' AND legal_status='IN_CIRCULATION'");
            s.execute("""
                CREATE TABLE IF NOT EXISTS kiz_import_batches(
                  id TEXT PRIMARY KEY,shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  owner TEXT NOT NULL,source TEXT NOT NULL,inn TEXT NOT NULL,file_hash TEXT NOT NULL,created_at TEXT NOT NULL,
                  UNIQUE(shop_id,owner,file_hash))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS kiz_import_entries(
                  code_id INTEGER PRIMARY KEY REFERENCES kiz_codes(id) ON DELETE CASCADE,
                  batch_id TEXT NOT NULL REFERENCES kiz_import_batches(id) ON DELETE CASCADE,
                  cis TEXT NOT NULL UNIQUE,reason TEXT NOT NULL DEFAULT 'KIZ_NOT_VERIFIED',verified_at TEXT)
                """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_kiz_import_batch ON kiz_import_entries(batch_id,code_id)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS kiz_warehouse_settings(
                  shop_id INTEGER PRIMARY KEY REFERENCES shops(id) ON DELETE CASCADE,
                  owner TEXT NOT NULL,auto_purchase INTEGER NOT NULL DEFAULT 0)
                """);
        }
    }
}
