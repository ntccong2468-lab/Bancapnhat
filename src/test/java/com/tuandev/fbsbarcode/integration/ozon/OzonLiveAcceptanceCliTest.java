package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.config.Database;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OzonLiveAcceptanceCliTest {
    @TempDir
    Path appDataDir;

    @AfterEach
    void clearAppDataOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void initializesCurrentSchemaBeforeReusingAnOlderLiveDatabase() throws Exception {
        System.setProperty("wcode.appdata.dir", appDataDir.toString());
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + appDataDir.resolve("database.db"));
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE shops(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        marketplace TEXT NOT NULL DEFAULT 'WILDBERRIES',
                        client_id TEXT,
                        api_key TEXT NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE ozon_sync_state(
                        shop_id INTEGER PRIMARY KEY,
                        products_last_id TEXT,
                        products_last_synced_at TEXT,
                        postings_changed_since TEXT,
                        postings_last_synced_at TEXT,
                        last_error TEXT)
                    """);
            statement.execute("""
                    CREATE TABLE ozon_products(
                        shop_id INTEGER NOT NULL,
                        product_id TEXT NOT NULL,
                        offer_id TEXT,
                        sku TEXT,
                        name TEXT,
                        primary_image_url TEXT,
                        archived INTEGER NOT NULL DEFAULT 0,
                        updated_at TEXT,
                        synced_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id,product_id))
                    """);
            statement.execute("PRAGMA user_version = " + Database.currentSchemaVersion());
        }

        OzonLiveAcceptanceCli.initializeLocalDatabase();

        Set<String> columns = new HashSet<>();
        try (Connection connection = Database.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info(ozon_products)")) {
            while (result.next()) columns.add(result.getString("name"));
        }
        assertTrue(columns.containsAll(Set.of("article", "color", "size", "category", "gender")));
    }

    @Test
    void acceptsOneMappedUnitEvenWhenOzonDoesNotFlagMarkingInThePosting() {
        OzonPostingDto posting = new OzonPostingDto(
                "POST-1", "", "", "awaiting_packaging", "posting_created", "",
                "2026-09-01T08:30:00Z", "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of("ship"), true,
                List.of(new OzonPostingItemDto(
                        0, "5340693583", "5340693583", "offer", "Product", 1, "RUB", "1")));

        assertTrue(OzonLiveAcceptanceCli.isSingleUnitCandidate(posting));
    }
}
