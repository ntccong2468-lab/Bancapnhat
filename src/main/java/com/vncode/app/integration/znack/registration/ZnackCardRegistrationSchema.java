package com.vncode.app.integration.znack.registration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class ZnackCardRegistrationSchema {
    private ZnackCardRegistrationSchema() {
    }

    public static void initialize(Connection connection) throws SQLException {
        RegistrationSigningConsent.initialize(connection);
        RegistrationMarketplaceConsent.initialize(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS znack_card_registration_history(
                  shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,chrt_id INTEGER NOT NULL,nm_id INTEGER NOT NULL,
                  gtin TEXT NOT NULL,good_id INTEGER,feed_id TEXT,payload_json TEXT,tnved TEXT,status TEXT NOT NULL,
                  error_message TEXT,archived_at TEXT NOT NULL,PRIMARY KEY(shop_id,chrt_id,gtin))
                """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS znack_card_registrations(
                        shop_id INTEGER NOT NULL,
                        chrt_id INTEGER NOT NULL,
                        nm_id INTEGER NOT NULL,
                        vendor_code TEXT,
                        source_barcode TEXT,
                        gtin TEXT,
                        good_id INTEGER,
                        feed_id TEXT,
                        tnved TEXT,
                        category_id INTEGER,
                        good_name TEXT,
                        payload_json TEXT,
                        status TEXT NOT NULL DEFAULT 'NOT_CREATED',
                        error_message TEXT,
                        wb_updated INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id, chrt_id),
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_znack_card_registrations_shop_status
                    ON znack_card_registrations(shop_id, status, updated_at)
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_znack_card_registrations_shop_gtin
                    ON znack_card_registrations(shop_id, gtin)
                    WHERE gtin IS NOT NULL AND TRIM(gtin) <> ''
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS znack_card_registration_scopes(
                        shop_id INTEGER NOT NULL,chrt_id INTEGER NOT NULL,
                        participant_inn TEXT NOT NULL,environment TEXT NOT NULL,fingerprint TEXT NOT NULL,
                        owner_hash TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id,chrt_id),
                        FOREIGN KEY(shop_id,chrt_id) REFERENCES znack_card_registrations(shop_id,chrt_id) ON DELETE CASCADE)
                    """);
            boolean ownerHash=false;
            try(var columns=statement.executeQuery("PRAGMA table_info(znack_card_registration_scopes)")){
                while(columns.next())if("owner_hash".equals(columns.getString("name")))ownerHash=true;
            }
            if(!ownerHash)statement.execute("ALTER TABLE znack_card_registration_scopes ADD COLUMN owner_hash TEXT NOT NULL DEFAULT ''");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS znack_card_registration_signatures(
                        shop_id INTEGER NOT NULL,chrt_id INTEGER NOT NULL,gtin TEXT NOT NULL,
                        good_id INTEGER NOT NULL,document_xml BLOB NOT NULL,document_sha256 TEXT NOT NULL,
                        state TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id,chrt_id),
                        FOREIGN KEY(shop_id,chrt_id) REFERENCES znack_card_registrations(shop_id,chrt_id) ON DELETE CASCADE)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS znack_card_registration_replacements(
                        shop_id INTEGER NOT NULL,chrt_id INTEGER NOT NULL,
                        active_gtin TEXT NOT NULL,active_good_id INTEGER,active_feed_id TEXT,
                        active_payload_json TEXT,active_wb_updated INTEGER NOT NULL DEFAULT 0,
                        state TEXT NOT NULL DEFAULT 'CANDIDATE',created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id,chrt_id),
                        FOREIGN KEY(shop_id,chrt_id) REFERENCES znack_card_registrations(shop_id,chrt_id) ON DELETE CASCADE)
                    """);
        }
    }
}
