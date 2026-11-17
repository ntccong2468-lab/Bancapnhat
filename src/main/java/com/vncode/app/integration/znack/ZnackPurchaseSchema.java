package com.vncode.app.integration.znack;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Additive purchase state plus the narrowly scoped external-order/GTIN uniqueness migration. */
final class ZnackPurchaseSchema {
    private static final Pattern OLD_UNIQUE = Pattern.compile("(?i)UNIQUE\\s*\\(\\s*shop_id\\s*,\\s*external_order_id\\s*\\)");
    private ZnackPurchaseSchema() { }

    static void initialize(Connection c) throws SQLException {
        migrateOrderUniqueness(c, () -> { });
        ensureVersionFence(c);
        try (Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_pipeline_connections(
                  pipeline_id INTEGER PRIMARY KEY REFERENCES znack_purchase_pipelines(id) ON DELETE CASCADE,
                  shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE, settings_json TEXT NOT NULL)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_purchase_contexts(
                  pipeline_id INTEGER PRIMARY KEY REFERENCES znack_purchase_pipelines(id) ON DELETE CASCADE,
                  shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  group_code TEXT NOT NULL, purchase_method TEXT NOT NULL, settings_json TEXT NOT NULL,
                  identity_key TEXT NOT NULL, created_at TEXT NOT NULL)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_purchase_batches(
                  id INTEGER PRIMARY KEY AUTOINCREMENT, shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  identity_key TEXT NOT NULL, account_key TEXT NOT NULL, group_code TEXT NOT NULL,
                  purchase_method TEXT NOT NULL, settings_json TEXT NOT NULL, state TEXT NOT NULL,
                  payload_json TEXT, payload_hash TEXT, external_order_id TEXT,
                  created_at TEXT NOT NULL, attempted_at TEXT, next_attempt_at TEXT, error_message TEXT,
                  recovery_attempt INTEGER NOT NULL DEFAULT 0, known_not_created INTEGER NOT NULL DEFAULT 0,
                  parent_batch_id INTEGER REFERENCES znack_purchase_batches(id), root_batch_id INTEGER REFERENCES znack_purchase_batches(id),
                  UNIQUE(shop_id,id), UNIQUE(shop_id,external_order_id))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_purchase_batch_members(
                  batch_id INTEGER NOT NULL REFERENCES znack_purchase_batches(id) ON DELETE CASCADE,
                  shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  pipeline_id INTEGER NOT NULL REFERENCES znack_purchase_pipelines(id) ON DELETE CASCADE,
                  order_id INTEGER NOT NULL REFERENCES kiz_orders(id), gtin TEXT NOT NULL,
                  approved_quantity INTEGER NOT NULL CHECK(approved_quantity>0), request_key TEXT NOT NULL,
                  PRIMARY KEY(batch_id,pipeline_id))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_purchase_account_holds(
                  account_key TEXT PRIMARY KEY, until_at TEXT NOT NULL, reason TEXT)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_code_download_targets(
                  order_id INTEGER PRIMARY KEY REFERENCES kiz_orders(id) ON DELETE CASCADE,
                  shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  expected_total INTEGER NOT NULL CHECK(expected_total>0), started_at TEXT NOT NULL)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_purchase_batch_recovery(
                  rejected_batch_id INTEGER NOT NULL REFERENCES znack_purchase_batches(id),
                  child_batch_id INTEGER NOT NULL REFERENCES znack_purchase_batches(id),
                  pipeline_id INTEGER NOT NULL REFERENCES znack_purchase_pipelines(id),
                  attempt INTEGER NOT NULL, previous_settings_json TEXT NOT NULL,
                  replacement_settings_json TEXT NOT NULL, negative_proof TEXT NOT NULL, created_at TEXT NOT NULL,
                  PRIMARY KEY(rejected_batch_id,pipeline_id))
                """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_znack_batch_identity_state ON znack_purchase_batches(shop_id,identity_key,state)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_znack_batch_member_order ON znack_purchase_batch_members(shop_id,order_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_znack_batch_member_pipeline ON znack_purchase_batch_members(shop_id,pipeline_id)");
        }
        ZnackRepository.captureLegacyPurchaseConnections(c);
    }

    @FunctionalInterface interface MigrationCheckpoint { void afterOldTableDropped() throws SQLException; }

    /** A checkpoint is injectable only by package tests to prove rollback after transactional DDL. */
    static void migrateOrderUniqueness(Connection c, MigrationCheckpoint checkpoint) throws SQLException {
        String sql;
        try (var p = c.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name='kiz_orders'"); var row = p.executeQuery()) {
            if (!row.next()) return;
            sql = row.getString(1);
        }
        if (sql == null || !OLD_UNIQUE.matcher(sql).find()) return;
        if (!c.getAutoCommit()) throw new SQLException("Purchase uniqueness migration requires an unowned transaction.");
        String replacement = OLD_UNIQUE.matcher(sql).replaceFirst("UNIQUE(shop_id,external_order_id,gtin)")
                .replaceFirst("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[\"`\\[]?kiz_orders[\"`\\]]?", "CREATE TABLE kiz_orders_wb1326_upgrade");
        if (replacement.equals(sql)) throw new SQLException("Could not prepare purchase uniqueness migration.");
        List<String> columns = new ArrayList<>(), objects = new ArrayList<>();
        try (var s = c.createStatement(); var row = s.executeQuery("PRAGMA table_info(kiz_orders)")) {
            while (row.next()) columns.add(quote(row.getString("name")));
        }
        try (var p = c.prepareStatement("SELECT sql FROM sqlite_master WHERE tbl_name='kiz_orders' AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name"); var row = p.executeQuery()) {
            while (row.next()) objects.add(row.getString(1));
        }
        long highWater = 0;
        try (var p = c.prepareStatement("SELECT seq FROM sqlite_sequence WHERE name='kiz_orders'"); var row = p.executeQuery()) {
            if (row.next()) highWater = row.getLong(1);
        }
        int originalForeignKeys, originalLegacyAlter;
        try (var s = c.createStatement(); var row = s.executeQuery("PRAGMA foreign_keys")) { originalForeignKeys = row.next() ? row.getInt(1) : 0; }
        try (var s = c.createStatement(); var row = s.executeQuery("PRAGMA legacy_alter_table")) { originalLegacyAlter = row.next() ? row.getInt(1) : 0; }
        boolean began = false;
        try (var s = c.createStatement()) {
            // SQLite ignores this PRAGMA inside a transaction. It must happen before BEGIN.
            s.execute("PRAGMA foreign_keys=OFF");
            s.execute("BEGIN IMMEDIATE"); began = true;
            s.execute(replacement);
            String names = String.join(",", columns);
            s.execute("INSERT INTO kiz_orders_wb1326_upgrade(" + names + ") SELECT " + names + " FROM kiz_orders");
            s.execute("DROP TABLE kiz_orders");
            checkpoint.afterOldTableDropped();
            // Never rename the old parent: that would rewrite child FKs to its temporary name.
            s.execute("PRAGMA legacy_alter_table=ON");
            s.execute("ALTER TABLE kiz_orders_wb1326_upgrade RENAME TO kiz_orders");
            for (String object : objects) s.execute(object);
            try (var p = c.prepareStatement("INSERT INTO sqlite_sequence(name,seq) SELECT 'kiz_orders',? WHERE NOT EXISTS(SELECT 1 FROM sqlite_sequence WHERE name='kiz_orders')")) { p.setLong(1, highWater); p.executeUpdate(); }
            try (var p = c.prepareStatement("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name='kiz_orders'")) { p.setLong(1, highWater); p.executeUpdate(); }
            try (var rows = s.executeQuery("PRAGMA foreign_key_check")) { if (rows.next()) throw new SQLException("Purchase migration foreign-key check failed."); }
            // The incompatible parent key and downgrade fence commit together. Later unrelated
            // bootstrap failures must never leave a schema6 label on a schema7 database.
            ensureVersionFence(c);
            s.execute("COMMIT"); began = false;
        } catch (SQLException | RuntimeException | Error error) {
            if (began) try (var s = c.createStatement()) { s.execute("ROLLBACK"); } catch (SQLException rollback) { error.addSuppressed(rollback); }
            throw error;
        } finally {
            try (var s = c.createStatement()) { s.execute("PRAGMA legacy_alter_table=" + originalLegacyAlter); s.execute("PRAGMA foreign_keys=" + originalForeignKeys); }
        }
    }
    private static void ensureVersionFence(Connection c)throws SQLException{
        int version;try(var s=c.createStatement();var row=s.executeQuery("PRAGMA user_version")){version=row.next()?row.getInt(1):0;}
        if(version<7)try(var s=c.createStatement()){s.execute("PRAGMA user_version=7");}
    }
    private static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
}
