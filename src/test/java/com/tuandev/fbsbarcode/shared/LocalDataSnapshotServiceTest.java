package com.tuandev.fbsbarcode.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDataSnapshotServiceTest {
    @TempDir Path tempDir;

    @Test
    void refusesSnapshotBeforeWritingWhenFreeDiskSpaceIsInsufficient() throws Exception {
        Path appData = tempDir.resolve("low-disk-app-data");
        Files.createDirectories(appData);
        Path liveDatabase = appData.resolve("database.db");
        createInventoryDatabase(liveDatabase, "unchanged");
        byte[] databaseBefore = Files.readAllBytes(liveDatabase);

        try (AppDataLock ownership = AppDataLock.acquire(appData, "low-disk-test")) {
            LocalDataSnapshotService service = new LocalDataSnapshotService(path -> 0L);

            LocalDataSnapshotService.InsufficientDiskSpaceException failure = assertThrows(
                    LocalDataSnapshotService.InsufficientDiskSpaceException.class,
                    () -> service.create(ownership, "1.1.10", 1, "before-migration"));

            assertTrue(failure.requiredBytes() > failure.availableBytes());
            assertTrue(failure.getMessage().contains("No data was changed"));
            assertArrayEquals(databaseBefore, Files.readAllBytes(liveDatabase));
            assertFalse(Files.exists(appData.resolve("snapshots")));
        }
    }

    @Test
    void snapshotsCommittedWalDataAndVerifiesChecksumAndIntegrity() throws Exception {
        Path appData = tempDir.resolve("app-data");
        Files.createDirectories(appData);
        Path liveDatabase = appData.resolve("database.db");

        try (AppDataLock ownership = AppDataLock.acquire(appData, "test");
                Connection live = DriverManager.getConnection("jdbc:sqlite:" + liveDatabase);
                Statement statement = live.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA wal_autocheckpoint = 0");
            statement.execute("CREATE TABLE inventory(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            statement.execute("INSERT INTO inventory(name) VALUES ('from-wal')");

            LocalDataSnapshotService service = new LocalDataSnapshotService();
            LocalDataSnapshotService.Snapshot snapshot =
                    service.create(ownership, "1.1.7", 1, "first-javafx-launch");

            assertTrue(Files.exists(liveDatabase.resolveSibling("database.db-wal")));
            assertTrue(Files.exists(snapshot.database()));
            assertTrue(Files.exists(snapshot.metadata()));
            assertTrue(service.verify(snapshot));
            assertEquals(1, countInventoryRows(snapshot.database()));

            Files.write(snapshot.database(), new byte[] {0x01}, java.nio.file.StandardOpenOption.APPEND);
            assertFalse(service.verify(snapshot));
        }
    }

    @Test
    void listsAndRestoresVerifiedSnapshotAfterCreatingARecoveryPoint() throws Exception {
        Path appData = tempDir.resolve("restore-app-data");
        Files.createDirectories(appData);
        Path liveDatabase = appData.resolve("database.db");
        LocalDataSnapshotService service = new LocalDataSnapshotService();

        try (AppDataLock ownership = AppDataLock.acquire(appData, "recovery-test")) {
            try (Connection live = DriverManager.getConnection("jdbc:sqlite:" + liveDatabase);
                    Statement statement = live.createStatement()) {
                statement.execute("CREATE TABLE inventory(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
                statement.execute("INSERT INTO inventory(name) VALUES ('before-migration')");
            }
            LocalDataSnapshotService.Snapshot rollback =
                    service.create(ownership, "1.1.7", 1, "before-migration");
            try (Connection live = DriverManager.getConnection("jdbc:sqlite:" + liveDatabase);
                    Statement statement = live.createStatement()) {
                statement.execute("INSERT INTO inventory(name) VALUES ('after-migration')");
            }

            List<LocalDataSnapshotService.Snapshot> beforeRestore = service.list(ownership);
            assertEquals(List.of(rollback), beforeRestore);

            LocalDataSnapshotService.RestoreResult result =
                    service.restore(ownership, rollback, "1.1.7");

            assertTrue(service.verify(result.recoveryBundle()));
            assertEquals(1, countInventoryRows(liveDatabase));
            assertEquals(2, countInventoryRows(result.recoveryBundle().database()));
            assertEquals(LocalDataSnapshotService.IntegrityStatus.VERIFIED, result.recoveryBundle().integrityStatus());
            assertEquals(1, service.list(ownership).size());
            assertFalse(Files.exists(pendingJournal(appData)));
        }
    }

    @Test
    void retentionKeepsTheRollbackWindowRecentHistoryMarkerReferencesAndUnknownEvidence()
            throws Exception {
        Path appData = tempDir.resolve("retention-app-data");
        Files.createDirectories(appData);
        Path database = appData.resolve("database.db");
        createInventoryDatabase(database, "retention");
        Instant now = Instant.parse("2026-07-19T00:00:00Z");

        try (AppDataLock ownership = AppDataLock.acquire(appData, "retention-test")) {
            LocalDataSnapshotService.Snapshot markerProtected = snapshotAt(
                    ownership, now.minus(60, ChronoUnit.DAYS));
            LocalDataSnapshotService.Snapshot expired = snapshotAt(
                    ownership, now.minus(50, ChronoUnit.DAYS));
            LocalDataSnapshotService.Snapshot olderFallback = snapshotAt(
                    ownership, now.minus(40, ChronoUnit.DAYS));
            LocalDataSnapshotService.Snapshot recent = snapshotAt(
                    ownership, now.minus(10, ChronoUnit.DAYS));
            Path unknown = Files.createDirectory(appData.resolve("snapshots/unknown-forensic-entry"));
            Files.writeString(unknown.resolve("do-not-delete.txt"), "preserve");
            Files.createDirectories(appData.resolve("writer-state"));
            Files.writeString(
                    appData.resolve("writer-state/javafx-1.1.7.ready"),
                    "snapshotSha256=" + markerProtected.sha256() + System.lineSeparator());

            LocalDataSnapshotService.RetentionResult result =
                    new LocalDataSnapshotService(InstantSource.fixed(now))
                            .retainRollbackWindow(ownership);

            assertEquals(1, result.deleted());
            assertFalse(Files.exists(expired.database().getParent()));
            assertTrue(Files.isDirectory(markerProtected.database().getParent()));
            assertTrue(Files.isDirectory(olderFallback.database().getParent()));
            assertTrue(Files.isDirectory(recent.database().getParent()));
            assertTrue(Files.isRegularFile(unknown.resolve("do-not-delete.txt")));
        }
    }

    @Test
    void preservesCorruptLiveDatabaseAndSidecarsBeforeRestoringVerifiedSnapshot() throws Exception {
        Path appData = tempDir.resolve("corrupt-app-data");
        Files.createDirectories(appData);
        Path liveDatabase = appData.resolve("database.db");
        LocalDataSnapshotService service = new LocalDataSnapshotService();

        try (AppDataLock ownership = AppDataLock.acquire(appData, "corrupt-recovery-test")) {
            createInventoryDatabase(liveDatabase, "verified-rollback");
            LocalDataSnapshotService.Snapshot rollback =
                    service.create(ownership, "1.1.7", 1, "before-migration");

            byte[] corruptDatabase = "not-a-sqlite-database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] wal = "forensic-wal".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] shm = "forensic-shm".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(liveDatabase, corruptDatabase, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(liveDatabase.resolveSibling("database.db-wal"), wal);
            Files.write(liveDatabase.resolveSibling("database.db-shm"), shm);

            LocalDataSnapshotService.RestoreResult result =
                    service.restore(ownership, rollback, "1.1.7");

            assertEquals(1, countInventoryRows(liveDatabase));
            assertEquals(LocalDataSnapshotService.IntegrityStatus.CORRUPT, result.recoveryBundle().integrityStatus());
            assertTrue(java.util.Arrays.equals(corruptDatabase, Files.readAllBytes(result.recoveryBundle().database())));
            assertTrue(java.util.Arrays.equals(wal, Files.readAllBytes(result.recoveryBundle().wal())));
            assertTrue(java.util.Arrays.equals(shm, Files.readAllBytes(result.recoveryBundle().shm())));
            assertTrue(service.verify(result.recoveryBundle()));
        }
    }

    @Test
    void rejectsSnapshotMutationBetweenInitialVerificationAndTargetCopy() throws Exception {
        Path appData = tempDir.resolve("mutation-app-data");
        Files.createDirectories(appData);
        Path liveDatabase = appData.resolve("database.db");
        Path replacement = tempDir.resolve("different-valid.db");
        createInventoryDatabase(liveDatabase, "original-live");
        createInventoryDatabase(replacement, "different-snapshot");

        try (AppDataLock ownership = AppDataLock.acquire(appData, "mutation-test")) {
            LocalDataSnapshotService baseline = new LocalDataSnapshotService();
            LocalDataSnapshotService.Snapshot snapshot =
                    baseline.create(ownership, "1.1.7", 1, "before-migration");
            AtomicBoolean mutated = new AtomicBoolean();
            LocalDataSnapshotService service = new LocalDataSnapshotService(point -> {
                if (point == LocalDataSnapshotService.RecoveryPoint.BEFORE_TARGET_COPY
                        && mutated.compareAndSet(false, true)) {
                    Files.copy(replacement, snapshot.database(), StandardCopyOption.REPLACE_EXISTING);
                }
            });

            assertThrows(IOException.class, () -> service.restore(ownership, snapshot, "1.1.7"));
            assertEquals(1, countInventoryRows(liveDatabase));
            assertFalse(Files.exists(pendingJournal(appData)));
        }
    }

    @Test
    void rejectsSnapshotDirectoryAndFileSymlinks() throws Exception {
        Path appData = tempDir.resolve("symlink-app-data");
        Files.createDirectories(appData);
        Path liveDatabase = appData.resolve("database.db");
        createInventoryDatabase(liveDatabase, "live");

        try (AppDataLock ownership = AppDataLock.acquire(appData, "symlink-test")) {
            LocalDataSnapshotService service = new LocalDataSnapshotService();
            LocalDataSnapshotService.Snapshot snapshot =
                    service.create(ownership, "1.1.7", 1, "before-migration");
            String validId = snapshot.database().getParent().getFileName().toString();
            Path outside = tempDir.resolve("outside-snapshot");
            Files.createDirectories(outside);
            Files.copy(snapshot.database(), outside.resolve("database.db"));
            Files.copy(snapshot.metadata(), outside.resolve("snapshot.properties"));
            Path directoryLink = appData.resolve("snapshots").resolve(validId.substring(0, 13)
                    + "-00000000-0000-0000-0000-000000000000");
            Files.createSymbolicLink(directoryLink, outside);

            assertThrows(IOException.class, () -> service.load(ownership, directoryLink.getFileName().toString()));
            assertEquals(List.of(snapshot), service.list(ownership));

            Path originalDatabase = snapshot.database().resolveSibling("database.original");
            Files.move(snapshot.database(), originalDatabase);
            Files.createSymbolicLink(snapshot.database(), originalDatabase.getFileName());
            assertThrows(IOException.class, () -> service.load(ownership, validId));
            assertFalse(service.verify(snapshot));

            Files.delete(snapshot.database());
            Files.move(originalDatabase, snapshot.database());
            Path originalMetadata = snapshot.metadata().resolveSibling("snapshot.original");
            Files.move(snapshot.metadata(), originalMetadata);
            Files.createSymbolicLink(snapshot.metadata(), originalMetadata.getFileName());
            assertThrows(IOException.class, () -> service.load(ownership, validId));
            assertFalse(service.verify(snapshot));
        }
    }

    @Test
    void restartRecoversEveryCrashBoundary() throws Exception {
        Set<LocalDataSnapshotService.RecoveryPoint> boundaries = EnumSet.of(
                LocalDataSnapshotService.RecoveryPoint.AFTER_JOURNAL_PUBLISHED,
                LocalDataSnapshotService.RecoveryPoint.AFTER_WAL_REMOVED,
                LocalDataSnapshotService.RecoveryPoint.AFTER_SHM_REMOVED,
                LocalDataSnapshotService.RecoveryPoint.AFTER_DATABASE_REPLACED,
                LocalDataSnapshotService.RecoveryPoint.BEFORE_MARKER_PUBLISHED);

        for (LocalDataSnapshotService.RecoveryPoint boundary : boundaries) {
            Path appData = tempDir.resolve("crash-" + boundary.name().toLowerCase());
            Files.createDirectories(appData);
            Path liveDatabase = appData.resolve("database.db");
            createInventoryDatabase(liveDatabase, "rollback-target");
            LocalDataSnapshotService.Snapshot snapshot;
            try (AppDataLock ownership = AppDataLock.acquire(appData, "crash-setup")) {
                snapshot = new LocalDataSnapshotService()
                        .create(ownership, "1.1.7", 1, "before-migration");
            }
            createInventoryDatabase(liveDatabase, "newer-live-a", "newer-live-b");

            AtomicBoolean crashed = new AtomicBoolean();
            try (AppDataLock ownership = AppDataLock.acquire(appData, "crashing-restore")) {
                LocalDataSnapshotService crashing = new LocalDataSnapshotService(point -> {
                    if (point == boundary && crashed.compareAndSet(false, true)) {
                        throw new SimulatedCrash();
                    }
                });
                assertThrows(SimulatedCrash.class, () -> crashing.restore(ownership, snapshot, "1.1.7"));
            }

            try (AppDataLock ownership = AppDataLock.acquire(appData, "restart-recovery")) {
                LocalDataSnapshotService.RecoveryOutcome outcome =
                        new LocalDataSnapshotService().recoverInterrupted(ownership);
                boolean targetWasInstalled = boundary == LocalDataSnapshotService.RecoveryPoint.AFTER_DATABASE_REPLACED
                        || boundary == LocalDataSnapshotService.RecoveryPoint.BEFORE_MARKER_PUBLISHED;
                assertEquals(
                        targetWasInstalled
                                ? LocalDataSnapshotService.RecoveryOutcome.COMPLETED
                                : LocalDataSnapshotService.RecoveryOutcome.ROLLED_BACK,
                        outcome,
                        boundary.name());
                assertEquals(targetWasInstalled ? 1 : 2, countInventoryRows(liveDatabase), boundary.name());
                assertFalse(
                        Files.exists(pendingJournal(appData)),
                        boundary.name());
            }
        }
    }

    @Test
    void markerFailureAfterTargetInstallCompletesForwardOnRestart() throws Exception {
        Path appData = tempDir.resolve("marker-failure-app-data");
        Files.createDirectories(appData);
        Path liveDatabase = appData.resolve("database.db");
        createInventoryDatabase(liveDatabase, "rollback-target");

        try (AppDataLock ownership = AppDataLock.acquire(appData, "marker-failure-test")) {
            LocalDataSnapshotService.Snapshot snapshot = new LocalDataSnapshotService()
                    .create(ownership, "1.1.7", 1, "before-migration");
            createInventoryDatabase(liveDatabase, "newer-live-a", "newer-live-b");
            LocalDataSnapshotService service = new LocalDataSnapshotService(point -> {
                if (point == LocalDataSnapshotService.RecoveryPoint.BEFORE_MARKER_PUBLISHED) {
                    throw new IOException("injected marker failure");
                }
            });

            assertThrows(IOException.class, () -> service.restore(ownership, snapshot, "1.1.7"));
            assertEquals(1, countInventoryRows(liveDatabase));
            assertTrue(Files.exists(pendingJournal(appData)));

            assertEquals(
                    LocalDataSnapshotService.RecoveryOutcome.COMPLETED,
                    new LocalDataSnapshotService().recoverInterrupted(ownership));
            assertEquals(1, countInventoryRows(liveDatabase));
            assertFalse(Files.exists(pendingJournal(appData)));
            assertEquals(1, restoreMarkerCount(appData));
        }
    }

    @Test
    void postCommitJournalForceFailureCannotRollbackSuccessfulRestore() throws Exception {
        Path appData = tempDir.resolve("journal-force-app-data");
        Files.createDirectories(appData);
        Path liveDatabase = appData.resolve("database.db");
        createInventoryDatabase(liveDatabase, "rollback-target");

        try (AppDataLock ownership = AppDataLock.acquire(appData, "journal-force-test")) {
            LocalDataSnapshotService.Snapshot snapshot = new LocalDataSnapshotService()
                    .create(ownership, "1.1.7", 1, "before-migration");
            createInventoryDatabase(liveDatabase, "newer-live-a", "newer-live-b");
            LocalDataSnapshotService service = new LocalDataSnapshotService(point -> {
                if (point == LocalDataSnapshotService.RecoveryPoint.AFTER_JOURNAL_DELETED) {
                    throw new IOException("injected directory force failure");
                }
            });

            LocalDataSnapshotService.RestoreResult result =
                    service.restore(ownership, snapshot, "1.1.7");

            assertEquals(snapshot, result.restoredSnapshot());
            assertEquals(1, countInventoryRows(liveDatabase));
            assertFalse(Files.exists(pendingJournal(appData)));
            assertEquals(1, restoreMarkerCount(appData));
        }
    }

    @Test
    void cleanupSymlinkSwapCannotDeleteOutsideAppData() throws Exception {
        Path appData = tempDir.resolve("journal-symlink-app-data");
        Files.createDirectories(appData);
        Path liveDatabase = appData.resolve("database.db");
        Path outside = tempDir.resolve("outside-journal-target.txt");
        Files.writeString(outside, "must-survive");
        createInventoryDatabase(liveDatabase, "rollback-target");

        try (AppDataLock ownership = AppDataLock.acquire(appData, "journal-symlink-test")) {
            LocalDataSnapshotService.Snapshot snapshot = new LocalDataSnapshotService()
                    .create(ownership, "1.1.7", 1, "before-migration");
            createInventoryDatabase(liveDatabase, "newer-live-a", "newer-live-b");
            LocalDataSnapshotService service = new LocalDataSnapshotService(point -> {
                if (point == LocalDataSnapshotService.RecoveryPoint.BEFORE_JOURNAL_REMOVAL) {
                    Files.delete(pendingJournal(appData));
                    Files.createSymbolicLink(pendingJournal(appData), outside);
                }
            });

            service.restore(ownership, snapshot, "1.1.7");

            assertEquals("must-survive", Files.readString(outside));
            assertFalse(Files.exists(pendingJournal(appData), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertEquals(1, countInventoryRows(liveDatabase));
        }
    }

    @Test
    void retainedJournalReplayPreservesNewerWalWhenMainMatchesRestoreTarget() throws Exception {
        Path appData = tempDir.resolve("identical-main-wal-app-data");
        Files.createDirectories(appData);
        Path liveDatabase = appData.resolve("database.db");
        Path liveWal = appData.resolve("database.db-wal");
        Path liveShm = appData.resolve("database.db-shm");
        createInventoryDatabase(liveDatabase, "rollback-target");

        try (AppDataLock ownership = AppDataLock.acquire(appData, "identical-main-wal-test")) {
            LocalDataSnapshotService.Snapshot snapshot = new LocalDataSnapshotService()
                    .create(ownership, "1.1.7", 1, "before-migration");

            byte[] committedWal;
            byte[] sharedMemory;
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + liveDatabase);
                    Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA wal_autocheckpoint = 0");
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                statement.execute("INSERT INTO inventory(name) VALUES ('newer-committed-wal-row')");
                committedWal = Files.readAllBytes(liveWal);
                sharedMemory = Files.readAllBytes(liveShm);
            }
            Files.copy(snapshot.database(), liveDatabase, StandardCopyOption.REPLACE_EXISTING);
            Files.write(liveWal, committedWal);
            Files.write(liveShm, sharedMemory);
            assertArrayEquals(Files.readAllBytes(snapshot.database()), Files.readAllBytes(liveDatabase));

            LocalDataSnapshotService service = new LocalDataSnapshotService(point -> {
                if (point == LocalDataSnapshotService.RecoveryPoint.AFTER_WAL_REMOVED
                        || point == LocalDataSnapshotService.RecoveryPoint.BEFORE_JOURNAL_REMOVAL) {
                    throw new IOException("injected retained-journal rollback");
                }
            });
            assertThrows(IOException.class, () -> service.restore(ownership, snapshot, "1.1.7"));
            assertTrue(Files.exists(pendingJournal(appData)));
            assertTrue(Files.exists(liveWal));

            assertEquals(
                    LocalDataSnapshotService.RecoveryOutcome.ROLLED_BACK,
                    new LocalDataSnapshotService().recoverInterrupted(ownership));

            assertFalse(Files.exists(pendingJournal(appData)));
            assertTrue(Files.exists(liveWal));
            assertEquals(2, countInventoryRows(liveDatabase));
        }
    }

    @Test
    void cleanupFailureCannotBypassRollback() throws Exception {
        Path appData = tempDir.resolve("cleanup-failure-app-data");
        Files.createDirectories(appData);
        Path liveDatabase = appData.resolve("database.db");
        createInventoryDatabase(liveDatabase, "rollback-target");

        try (AppDataLock ownership = AppDataLock.acquire(appData, "cleanup-failure-test")) {
            LocalDataSnapshotService.Snapshot snapshot = new LocalDataSnapshotService()
                    .create(ownership, "1.1.7", 1, "before-migration");
            createInventoryDatabase(liveDatabase, "newer-live-a", "newer-live-b");
            LocalDataSnapshotService service = new LocalDataSnapshotService(point -> {
                if (point == LocalDataSnapshotService.RecoveryPoint.AFTER_WAL_REMOVED
                        || point == LocalDataSnapshotService.RecoveryPoint.BEFORE_TEMP_CLEANUP) {
                    throw new IOException("injected failure");
                }
            });

            IOException failure = assertThrows(
                    IOException.class,
                    () -> service.restore(ownership, snapshot, "1.1.7"));

            assertEquals(2, countInventoryRows(liveDatabase));
            assertTrue(failure.getSuppressed().length >= 1);
        }
    }

    private static LocalDataSnapshotService.Snapshot snapshotAt(
            AppDataLock ownership, Instant createdAt) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + ownership.requireOwnedAppDataDir().resolve("database.db"));
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO inventory(name) VALUES ('" + createdAt.toEpochMilli() + "')");
        }
        return new LocalDataSnapshotService(InstantSource.fixed(createdAt))
                .create(ownership, "1.1.7", 1, "before-migration");
    }

    private static void createInventoryDatabase(Path database, String... names) throws Exception {
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-wal"));
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-shm"));
        Files.deleteIfExists(database);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE inventory(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            for (String name : names) {
                statement.execute("INSERT INTO inventory(name) VALUES ('" + name + "')");
            }
        }
    }

    private static int countInventoryRows(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM inventory")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    private static Path pendingJournal(Path appData) {
        return appData.resolve(".wcode-pending-restore.properties");
    }

    private static long restoreMarkerCount(Path appData) throws Exception {
        try (var entries = Files.list(appData)) {
            return entries.filter(path -> path.getFileName().toString().startsWith(".wcode-restore-"))
                    .count();
        }
    }

    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;
    }
}
