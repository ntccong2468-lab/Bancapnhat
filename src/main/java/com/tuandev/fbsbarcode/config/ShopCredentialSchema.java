package com.tuandev.fbsbarcode.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Additive credential-mirror schema that legacy JavaFX safely ignores during rollback. */
public final class ShopCredentialSchema {
    private ShopCredentialSchema() {
    }

    public static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS shop_credential_mirrors(
                        shop_id INTEGER PRIMARY KEY,
                        secret_key TEXT NOT NULL UNIQUE,
                        credential_version INTEGER NOT NULL CHECK(credential_version > 0),
                        credential_fingerprint TEXT NOT NULL CHECK(length(credential_fingerprint) = 64),
                        mirrored_version INTEGER,
                        mirrored_fingerprint TEXT,
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE,
                        CHECK((mirrored_version IS NULL AND mirrored_fingerprint IS NULL)
                           OR (mirrored_version > 0 AND length(mirrored_fingerprint) = 64))
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS shop_credential_tombstones(
                        secret_key TEXT PRIMARY KEY,
                        credential_version INTEGER NOT NULL CHECK(credential_version > 0),
                        credential_fingerprint TEXT NOT NULL CHECK(length(credential_fingerprint) = 64)
                    )
                    """);
        }
    }
}
