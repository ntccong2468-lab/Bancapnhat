package com.vncode.app.shared;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the existing backup boundary with synthetic data, not DPAPI or the app migration. */
class CredentialSnapshotFixtureTest {
    @TempDir Path temporaryDirectory;

    @Test void verifiedSnapshotRestoresOriginalShopAndEachFrozenPipeline() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("restore"));
        Path database = root.resolve("database.db");
        createFixture(database);
        try (AppDataLock owner = AppDataLock.acquire(root, "fixture")) {
            var service = new LocalDataSnapshotService();
            var snapshot = service.create(owner, "1.6.0", 6, "synthetic-credential-migration");
            assertTrue(service.verify(snapshot));
            writeMockMigration(database);
            assertEquals("fixture-cipher-shop", value(database, "SELECT api_key FROM shops WHERE id=1"));

            var result = service.restore(owner, snapshot, "1.6.0");

            assertTrue(service.verify(result.recoveryBundle()));
            assertEquals("fixture-key-one", value(database, "SELECT api_key FROM shops WHERE id=1"));
            assertEquals("fixture-key-two", value(database, "SELECT api_key FROM shops WHERE id=2"));
            assertEquals("fixture-oms-current", value(database, "SELECT oms_connection FROM znack_settings WHERE shop_id=1"));
            assertEquals("fixture-oms-old", value(database, "SELECT json_extract(settings_json,'$.omsConnection') FROM znack_pipeline_connections WHERE pipeline_id=101"));
            assertEquals("fixture-oms-new", value(database, "SELECT json_extract(settings_json,'$.omsConnection') FROM znack_pipeline_connections WHERE pipeline_id=102"));
            assertEquals("fixture-cipher-shop", value(result.recoveryBundle().database(), "SELECT api_key FROM shops WHERE id=1"));
        }
    }

    @Test void failedSyntheticTransactionLeavesAllValuesUnchanged() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("transaction"));
        Path database = root.resolve("database.db");
        createFixture(database);
        try (AppDataLock owner = AppDataLock.acquire(root, "fixture")) {
            var service = new LocalDataSnapshotService();
            var snapshot = service.create(owner, "1.6.0", 6, "synthetic-credential-migration");
            try (Connection connection = connect(database); var statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                SQLException failure = assertThrows(SQLException.class, () -> {
                    try {
                        assertEquals(1, statement.executeUpdate("UPDATE shops SET api_key='fixture-cipher-shop' WHERE id=1"));
                        assertEquals(1, statement.executeUpdate("UPDATE znack_pipeline_connections SET settings_json=json_object('omsConnection','fixture-cipher-pipeline') WHERE pipeline_id=101"));
                        throw new SQLException("synthetic migration failure");
                    } catch (SQLException expected) {
                        statement.execute("ROLLBACK");
                        throw expected;
                    }
                });
                assertEquals("synthetic migration failure", failure.getMessage());
            }
            assertTrue(service.verify(snapshot));
            assertEquals("fixture-key-one", value(database, "SELECT api_key FROM shops WHERE id=1"));
            assertEquals("fixture-oms-old", value(database, "SELECT json_extract(settings_json,'$.omsConnection') FROM znack_pipeline_connections WHERE pipeline_id=101"));
            assertEquals("fixture-oms-new", value(database, "SELECT json_extract(settings_json,'$.omsConnection') FROM znack_pipeline_connections WHERE pipeline_id=102"));
        }
    }

    @Test void corruptedSnapshotIsRejectedWithoutOverwritingLiveData() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("corrupt"));
        Path database = root.resolve("database.db");
        createFixture(database);
        try (AppDataLock owner = AppDataLock.acquire(root, "fixture")) {
            var service = new LocalDataSnapshotService();
            var snapshot = service.create(owner, "1.6.0", 6, "synthetic-credential-migration");
            writeMockMigration(database);
            Files.write(snapshot.database(), new byte[] {1}, StandardOpenOption.APPEND);
            assertFalse(service.verify(snapshot));
            assertThrows(IOException.class, () -> service.restore(owner, snapshot, "1.6.0"));
            assertEquals("fixture-cipher-shop", value(database, "SELECT api_key FROM shops WHERE id=1"));
            assertEquals("fixture-cipher-old", value(database, "SELECT json_extract(settings_json,'$.omsConnection') FROM znack_pipeline_connections WHERE pipeline_id=101"));
        }
    }

    private static Connection connect(Path database) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static void createFixture(Path database) throws SQLException {
        try (Connection connection = connect(database); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY, api_key TEXT NOT NULL)");
            statement.execute("CREATE TABLE znack_settings(shop_id INTEGER PRIMARY KEY, oms_connection TEXT NOT NULL)");
            statement.execute("CREATE TABLE znack_pipeline_connections(pipeline_id INTEGER PRIMARY KEY, shop_id INTEGER NOT NULL, settings_json TEXT NOT NULL)");
            statement.execute("INSERT INTO shops VALUES(1,'fixture-key-one'),(2,'fixture-key-two')");
            statement.execute("INSERT INTO znack_settings VALUES(1,'fixture-oms-current')");
            statement.execute("INSERT INTO znack_pipeline_connections VALUES(101,1,json_object('omsConnection','fixture-oms-old')),(102,1,json_object('omsConnection','fixture-oms-new'))");
        }
    }

    private static void writeMockMigration(Path database) throws SQLException {
        try (Connection connection = connect(database); var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            statement.executeUpdate("UPDATE shops SET api_key='fixture-cipher-shop' WHERE id=1");
            statement.executeUpdate("UPDATE znack_settings SET oms_connection='fixture-cipher-current' WHERE shop_id=1");
            statement.executeUpdate("UPDATE znack_pipeline_connections SET settings_json=json_object('omsConnection','fixture-cipher-old') WHERE pipeline_id=101");
            statement.executeUpdate("UPDATE znack_pipeline_connections SET settings_json=json_object('omsConnection','fixture-cipher-new') WHERE pipeline_id=102");
            statement.execute("COMMIT");
        }
    }

    private static String value(Path database, String sql) throws SQLException {
        try (Connection connection = connect(database); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
