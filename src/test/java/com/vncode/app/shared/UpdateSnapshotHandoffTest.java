package com.vncode.app.shared;

import com.vncode.app.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class UpdateSnapshotHandoffTest {
    @TempDir Path data;
    private String previousDataDirectory;

    @AfterEach void restoreDataDirectory() {
        if (previousDataDirectory == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previousDataDirectory);
    }

    @Test void reusesOnlyTheVerifiedSnapshotForTheExactTargetAndSchema() throws Exception {
        createInventory();
        try (var owner = AppDataLock.acquire(data, "update-test")) {
            var snapshots = new LocalDataSnapshotService();
            var before = UpdateSnapshotHandoff.capture(owner);
            var snapshot = snapshots.create(owner, "1.5.2", 6, "signed-update-install");
            UpdateSnapshotHandoff.record(owner, "1.5.3", snapshot, before, UpdateSnapshotHandoff.capture(owner));

            assertEquals(snapshot, UpdateSnapshotHandoff.reusable(owner, "1.5.3", 6, snapshots));
            assertNull(UpdateSnapshotHandoff.reusable(owner, "1.5.4", 6, snapshots));
            assertNull(UpdateSnapshotHandoff.reusable(owner, "1.5.3", 7, snapshots));
            assertTrue(snapshots.verify(snapshot), "Public rollback verification must remain strict");

            Files.write(snapshot.database(), new byte[] {1}, StandardOpenOption.APPEND);
            assertNull(UpdateSnapshotHandoff.reusable(owner, "1.5.3", 6, snapshots));
            assertFalse(snapshots.verifyPreviouslyValidated(snapshot, snapshot.sha256()));
            assertFalse(snapshots.verify(snapshot));
        }
    }

    @Test void changedUncheckpointedWalInvalidatesReuseAndTheNextRollbackIncludesItsData() throws Exception {
        createInventory();
        Path database = data.resolve("database.db");
        try (var owner = AppDataLock.acquire(data, "update-test");
             var live = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = live.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("INSERT INTO inventory(name) VALUES('first-wal-row')");
            byte[] databaseBefore = Files.readAllBytes(database);
            var snapshots = new LocalDataSnapshotService();
            var before = UpdateSnapshotHandoff.capture(owner);
            var first = snapshots.create(owner, "1.5.2", 6, "signed-update-install");
            UpdateSnapshotHandoff.record(owner, "1.5.3", first, before, UpdateSnapshotHandoff.capture(owner));
            assertEquals(first, UpdateSnapshotHandoff.reusable(owner, "1.5.3", 6, snapshots));

            statement.execute("INSERT INTO inventory(name) VALUES('later-wal-row')");
            assertArrayEquals(databaseBefore, Files.readAllBytes(database), "The new row must still be in WAL");
            assertNull(UpdateSnapshotHandoff.reusable(owner, "1.5.3", 6, snapshots));
            var fresh = snapshots.create(owner, "1.5.3", 6, "javafx-writer-1.5.3");
            assertEquals(2, inventoryRows(first.database()));
            assertEquals(3, inventoryRows(fresh.database()));
            assertTrue(snapshots.verify(fresh));
        }
    }

    @Test void concurrentDataChangeNeverPublishesAReusableReceipt() throws Exception {
        createInventory();
        try (var owner = AppDataLock.acquire(data, "update-test")) {
            var snapshots = new LocalDataSnapshotService();
            var before = UpdateSnapshotHandoff.capture(owner);
            var snapshot = snapshots.create(owner, "1.5.2", 6, "signed-update-install");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("database.db"));
                 var statement = connection.createStatement()) {
                statement.execute("INSERT INTO inventory(name) VALUES('changed-during-backup')");
            }
            UpdateSnapshotHandoff.record(owner, "1.5.3", snapshot, before, UpdateSnapshotHandoff.capture(owner));

            assertNull(UpdateSnapshotHandoff.reusable(owner, "1.5.3", 6, snapshots));
            assertEquals(2, inventoryRows(data.resolve("database.db")));
            assertTrue(snapshots.verify(snapshot));
        }
    }

    @Test void missingMalformedOrUnsafeReceiptFallsBackWithoutReadingAnotherDatabase() throws Exception {
        createInventory();
        Path receipt = data.resolve(".vncode-update-snapshot.properties");
        try (var owner = AppDataLock.acquire(data, "update-test")) {
            var snapshots = new LocalDataSnapshotService();
            assertNull(UpdateSnapshotHandoff.reusable(owner, "1.5.3", 6, snapshots));
            Files.writeString(receipt, "targetVersion=1.5.3\nschemaVersion=6\nsnapshotId=../../outside\n");
            assertNull(UpdateSnapshotHandoff.reusable(owner, "1.5.3", 6, snapshots));
            Files.delete(receipt);
            Files.createSymbolicLink(receipt, data.resolve("database.db"));
            assertNull(UpdateSnapshotHandoff.reusable(owner, "1.5.3", 6, snapshots));
            assertEquals(1, inventoryRows(data.resolve("database.db")));
        }
    }

    @Test void nextVersionKeepsSyncedDataAndReusesTheCompletedUpdateRollback() throws Exception {
        configureAppData();
        try (var session = LocalDataMigrationGate.prepare(data, "1.5.2", "javafx")) {
            try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
                statement.execute("INSERT INTO shops(id,name,api_key) VALUES(41,'Synced shop','fixture-only')");
            }
            session.createSignedUpdateSnapshot("1.5.3");
        }
        long completedRollbackCount = snapshotCount();
        assertTrue(Files.exists(data.resolve(".vncode-update-snapshot.properties")));

        try (var session = LocalDataMigrationGate.prepare(data, "1.5.3", "javafx")) {
            assertEquals(completedRollbackCount, snapshotCount(), "An unchanged rollback must not be copied again");
            try (var connection = Database.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT name,api_key FROM shops WHERE id=41")) {
                assertTrue(result.next());
                assertEquals("Synced shop", result.getString(1));
                assertEquals("fixture-only", result.getString(2));
            }
            assertFalse(Files.exists(data.resolve(".vncode-update-snapshot.properties")));
        }
        try (var session = LocalDataMigrationGate.prepare(data, "1.5.3", "javafx")) {
            assertEquals(completedRollbackCount, snapshotCount(), "Subsequent startup keeps the same verified rollback");
        }
    }

    @Test void changedDataAfterDownloadCreatesAFreshRollbackWithoutReplacingLiveData() throws Exception {
        configureAppData();
        try (var session = LocalDataMigrationGate.prepare(data, "1.5.2", "javafx")) {
            session.createSignedUpdateSnapshot("1.5.3");
            try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
                statement.execute("INSERT INTO shops(id,name,api_key) VALUES(42,'New data after backup','fixture-only')");
            }
        }
        long previousRollbackCount = snapshotCount();
        try (var session = LocalDataMigrationGate.prepare(data, "1.5.3", "javafx")) {
            assertEquals(previousRollbackCount + 1, snapshotCount());
            try (var connection = Database.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT name FROM shops WHERE id=42")) {
                assertTrue(result.next());
                assertEquals("New data after backup", result.getString(1));
            }
        }
    }

    private void configureAppData() {
        previousDataDirectory = System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir", data.toString());
    }

    private void createInventory() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("database.db"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE inventory(id INTEGER PRIMARY KEY,name TEXT NOT NULL)");
            statement.execute("INSERT INTO inventory(name) VALUES('existing-row')");
        }
    }

    private static int inventoryRows(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM inventory")) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }

    private long snapshotCount() throws Exception {
        try (var files = Files.walk(data.resolve("snapshots"))) {
            return files.filter(path -> path.getFileName().toString().equals("snapshot.properties")).count();
        }
    }
}
