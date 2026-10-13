package com.vncode.app.config;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseBatchSessionTest {
    @TempDir Path directory;
    @BeforeEach void setup() {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
    }
    @AfterEach void cleanup() { System.clearProperty("vncode.appdata.dir"); }

    @Test void keepsWalOpenWithoutHoldingLocksChangingDurabilityOrGroupingTransactions() throws Exception {
        int synchronous = pragma("synchronous");
        try (var session = Database.openBatchSession()) {
            try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
                assertTrue(connection.getAutoCommit());
                assertEquals(synchronous, pragma("synchronous"));
                statement.executeUpdate("INSERT INTO app_config(key,value) VALUES('batch-first','committed')");
                connection.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO app_config(key,value) VALUES('batch-second','rolled-back')");
                connection.rollback();
            }
            // Independent connections can read committed progress and write while the session lives.
            try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
                try (var result = statement.executeQuery("SELECT key FROM app_config WHERE key LIKE 'batch-%'")) {
                    assertTrue(result.next());
                    assertEquals("batch-first", result.getString(1));
                    assertFalse(result.next());
                }
                statement.executeUpdate("INSERT INTO app_config(key,value) VALUES('batch-third','committed')");
            }
            assertTrue(java.nio.file.Files.exists(directory.resolve("database.db-wal")));
        }
        // No process-wide connection survives: exclusive maintenance works after the bounded scope.
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA journal_mode=DELETE")) {
            assertTrue(result.next());
            assertEquals("delete", result.getString(1));
        }
    }

    private static int pragma(String name) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA " + name)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
