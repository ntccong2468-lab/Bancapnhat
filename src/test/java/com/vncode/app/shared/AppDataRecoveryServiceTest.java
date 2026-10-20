package com.vncode.app.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppDataRecoveryServiceTest {
    @TempDir Path tempDir;

    @Test
    void unreadableCurrentDatabaseAndWalAreNeverReplacedByPendingBackup() throws Exception {
        Path current = tempDir.resolve("current");
        Files.createDirectories(current);
        byte[] damaged = "existing seller database needing diagnosis".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] wal = "keep the original WAL evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(current.resolve("database.db"), damaged);
        Files.write(current.resolve("database.db-wal"), wal);
        Path backup = pendingBackup();

        var failure = assertThrows(IllegalStateException.class,
                () -> AppDataRecoveryService.recoverIfNeededOnStartup(current, backup, List.of()));

        assertTrue(failure.getMessage().startsWith("STARTUP_DATA_INSPECTION_FAILED"));
        assertTrue(failure.getMessage().contains("Do not delete or replace"));
        assertArrayEquals(damaged, Files.readAllBytes(current.resolve("database.db")));
        assertArrayEquals(wal, Files.readAllBytes(current.resolve("database.db-wal")));
        assertTrue(Files.isRegularFile(backup.resolve("restore-required.marker")));
        assertEquals("backup", shopName(backup.resolve("payload/database.db")));
    }

    @Test
    void lockedCurrentDatabaseIsNotMistakenForAnEmptyDatabase() throws Exception {
        Path current = tempDir.resolve("current");
        Path database = current.resolve("database.db");
        createDatabase(database, "current");
        byte[] original = Files.readAllBytes(database);
        Path backup = pendingBackup();
        try (var locking = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = locking.createStatement()) {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("BEGIN EXCLUSIVE");
            try {
                var failure = assertThrows(IllegalStateException.class,
                        () -> AppDataRecoveryService.recoverIfNeededOnStartup(current, backup, List.of()));
                assertTrue(failure.getMessage().startsWith("STARTUP_DATA_INSPECTION_FAILED"));
                assertArrayEquals(original, Files.readAllBytes(database));
                assertTrue(Files.isRegularFile(backup.resolve("restore-required.marker")));
            } finally {
                statement.execute("ROLLBACK");
            }
        }
        assertEquals("current", shopName(database));
    }

    @Test
    void confirmedEmptyDatabaseStillRestoresAndClearsOnlyTheCompletedRequest() throws Exception {
        Path current = tempDir.resolve("seller data with spaces");
        createDatabase(current.resolve("database.db"), null);
        Path backup = pendingBackup();
        Files.writeString(backup.resolve("payload/seller-note.txt"), "saved settings");

        AppDataRecoveryService.recoverIfNeededOnStartup(current, backup, List.of());

        assertEquals("backup", shopName(current.resolve("database.db")));
        assertEquals("saved settings", Files.readString(current.resolve("seller-note.txt")));
        assertFalse(Files.exists(backup.resolve("restore-required.marker")));
    }

    @Test
    void existingReadableUserDatabaseIsKeptAndPendingRestoreIsSafelySkipped() throws Exception {
        Path current = tempDir.resolve("current");
        Path database = current.resolve("database.db");
        createDatabase(database, "current");
        byte[] original = Files.readAllBytes(database);
        Path backup = pendingBackup();

        AppDataRecoveryService.recoverIfNeededOnStartup(current, backup, List.of());

        assertArrayEquals(original, Files.readAllBytes(database));
        assertEquals("current", shopName(database));
        assertFalse(Files.exists(backup.resolve("restore-required.marker")));
    }

    @Test
    void unreadableCurrentDatabaseAlsoStopsLegacyFallbackWithoutReplacingItsWal() throws Exception {
        Path current = tempDir.resolve("current");
        Files.createDirectories(current);
        Files.writeString(current.resolve("database.db"), "keep corrupt current database");
        Files.writeString(current.resolve("database.db-wal"), "keep current WAL");
        Path legacy = tempDir.resolve("legacy");
        createDatabase(legacy.resolve("database.db"), "legacy");

        var failure = assertThrows(IllegalStateException.class,
                () -> AppDataRecoveryService.recoverIfNeededOnStartup(current,
                        tempDir.resolve("no-pending-backup"), List.of(legacy)));

        assertTrue(failure.getMessage().startsWith("STARTUP_DATA_INSPECTION_FAILED"));
        assertEquals("keep corrupt current database", Files.readString(current.resolve("database.db")));
        assertEquals("keep current WAL", Files.readString(current.resolve("database.db-wal")));
        assertEquals("legacy", shopName(legacy.resolve("database.db")));
    }

    @Test
    void failedRecoveryKeepsPendingMarkerAndDoesNotContinueToAnotherRestoreSource() throws Exception {
        Path current = tempDir.resolve("current");
        createDatabase(current.resolve("database.db"), null);
        Files.writeString(current.resolve("exports"), "existing file blocks the backup directory");
        Path backup = pendingBackup();
        Files.createDirectories(backup.resolve("payload/exports"));
        Files.writeString(backup.resolve("payload/exports/label.txt"), "backup label");
        Path legacy = tempDir.resolve("legacy");
        createDatabase(legacy.resolve("database.db"), "legacy");
        Files.writeString(legacy.resolve("legacy-only.txt"), "must not copy");

        var failure = assertThrows(IllegalStateException.class,
                () -> AppDataRecoveryService.recoverIfNeededOnStartup(current, backup, List.of(legacy)));

        assertTrue(failure.getMessage().startsWith("STARTUP_DATA_RECOVERY_FAILED"));
        assertTrue(Files.isRegularFile(backup.resolve("restore-required.marker")));
        assertFalse(Files.exists(current.resolve("legacy-only.txt")));
        assertEquals("existing file blocks the backup directory", Files.readString(current.resolve("exports")));
    }

    @Test
    void missingPendingBackupIsReportedAndItsMarkerIsRetained() throws Exception {
        Path current = tempDir.resolve("current");
        Path backup = tempDir.resolve("update-backup");
        Files.createDirectories(backup);
        Files.writeString(backup.resolve("restore-required.marker"), "pending");

        var failure = assertThrows(IllegalStateException.class,
                () -> AppDataRecoveryService.recoverIfNeededOnStartup(current, backup, List.of()));

        assertTrue(failure.getMessage().startsWith("STARTUP_DATA_RECOVERY_FAILED"));
        assertTrue(Files.isRegularFile(backup.resolve("restore-required.marker")));
        assertFalse(Files.exists(current.resolve("database.db")), "Read-only inspection must not create a database");
    }

    @Test
    void legacyMigrationCopiesUserDataButNotTheOldJpackageRuntime() throws Exception {
        Path source = tempDir.resolve("VN code");
        Path target = tempDir.resolve("VNcodeData");
        Files.createDirectories(source.resolve("app"));
        Files.createDirectories(source.resolve("runtime/bin"));
        Files.createDirectories(source.resolve("exports"));
        Files.writeString(source.resolve("database.db"), "database");
        Files.writeString(source.resolve("license.json"), "license");
        Files.writeString(source.resolve("exports/orders.pdf"), "pdf");
        Files.writeString(source.resolve("WCode.exe"), "legacy executable");
        Files.writeString(source.resolve("app/VN code.cfg"), "legacy config");
        Files.writeString(source.resolve("runtime/bin/java.exe"), "legacy runtime");

        AppDataRecoveryService.copyAppDataWithoutDeletingCurrent(source, target, true);

        assertEquals("database", Files.readString(target.resolve("database.db")));
        assertEquals("license", Files.readString(target.resolve("license.json")));
        assertTrue(Files.isRegularFile(target.resolve("exports/orders.pdf")));
        assertFalse(Files.exists(target.resolve("WCode.exe")));
        assertFalse(Files.exists(target.resolve("app")));
        assertFalse(Files.exists(target.resolve("runtime")));
    }

    private Path pendingBackup() throws Exception {
        Path backup = tempDir.resolve("update-backup");
        createDatabase(backup.resolve("payload/database.db"), "backup");
        Files.writeString(backup.resolve("restore-required.marker"), "pending");
        return backup;
    }

    private static void createDatabase(Path database, String name) throws Exception {
        Files.createDirectories(database.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY,name TEXT NOT NULL)");
            if (name != null) {
                try (var insert = connection.prepareStatement("INSERT INTO shops VALUES(1,?)")) {
                    insert.setString(1, name);
                    insert.executeUpdate();
                }
            }
        }
    }

    private static String shopName(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT name FROM shops WHERE id=1")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
