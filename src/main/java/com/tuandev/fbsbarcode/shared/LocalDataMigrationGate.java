package com.tuandev.fbsbarcode.shared;

import com.tuandev.fbsbarcode.config.Database;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Owns app-data while gating each writer and schema revision behind a verified rollback point. */
public final class LocalDataMigrationGate {
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    private static final Pattern WRITER_PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,15}");
    private static final String DATA_MIGRATION = "wcode-schema-v2";

    private LocalDataMigrationGate() {
    }

    public static Session prepare(Path appDataDir, String appVersion, String writerId) throws Exception {
        if (appVersion == null || !VERSION_PATTERN.matcher(appVersion).matches()) {
            throw new IllegalArgumentException("appVersion has an invalid format");
        }
        if (writerId == null || !WRITER_PATTERN.matcher(writerId).matches()) {
            throw new IllegalArgumentException("writerId has an invalid format");
        }
        Path normalizedDir = appDataDir.toAbsolutePath().normalize();
        if (!normalizedDir.equals(AppPaths.appDataDir().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("appDataDir must match the configured WCode app-data directory");
        }

        AppDataLock ownership = AppDataLock.acquire(normalizedDir, writerId);
        try {
            LocalDataSnapshotService snapshots = new LocalDataSnapshotService();
            snapshots.recoverInterrupted(ownership);
            AppDataRecoveryService.recoverIfNeededOnStartup();
            Path database = normalizedDir.resolve("database.db");
            if (Files.exists(database, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(database)
                            || !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("The WCode database path is unsafe.");
            }
            int currentSchemaVersion = Database.currentSchemaVersion();
            int existingSchemaVersion;
            if (Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
                existingSchemaVersion = readSchemaVersion(database);
            } else {
                initializeEmptyDatabase(database);
                existingSchemaVersion = 0;
            }
            if (existingSchemaVersion > currentSchemaVersion) {
                throw new IOException("The WCode database was created by a newer application version.");
            }
            Path marker = requireSafeMarker(
                    normalizedDir.resolve("writer-state"), writerId + "-" + appVersion + ".ready");
            if (existingSchemaVersion == currentSchemaVersion
                    && isReady(marker, appVersion, currentSchemaVersion, snapshots, ownership)) {
                snapshots.retainRollbackWindow(ownership);
                return new Session(ownership, appVersion, normalizedDir);
            }

            String reason = existingSchemaVersion < currentSchemaVersion
                    ? "wcode-schema-" + currentSchemaVersion
                    : writerId + "-writer-"
                            + appVersion.toLowerCase(Locale.ROOT).replace('_', '-');
            LocalDataSnapshotService.Snapshot snapshot = snapshots.create(
                    ownership, appVersion, existingSchemaVersion, reason);
            String snapshotChecksum = snapshot.sha256();

            Database.initDatabase();
            if (readSchemaVersion(database) != currentSchemaVersion) {
                throw new IOException("The WCode database schema migration did not complete safely.");
            }
            writeReadyMarker(marker, appVersion, currentSchemaVersion, snapshotChecksum);
            snapshots.retainRollbackWindow(ownership);
            return new Session(ownership, appVersion, normalizedDir);
        } catch (Exception exception) {
            ownership.close();
            throw exception;
        }
    }

    private static int readSchemaVersion(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void initializeEmptyDatabase(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 0");
        }
    }

    private static boolean isReady(
            Path marker,
            String appVersion,
            int schemaVersion,
            LocalDataSnapshotService snapshots,
            AppDataLock ownership) {
        try {
            if (Files.isSymbolicLink(marker)
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(marker) > 4096) {
                return false;
            }
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (lines.size() != 4
                    || !("writerVersion=" + appVersion).equals(lines.get(0))
                    || !("dataMigration=" + DATA_MIGRATION).equals(lines.get(1))
                    || !("schemaVersion=" + schemaVersion).equals(lines.get(2))) {
                return false;
            }
            String snapshotLine = lines.get(3);
            String prefix = "snapshotSha256=";
            String expected = snapshotLine.startsWith(prefix) ? snapshotLine.substring(prefix.length()) : "";
            if (!expected.matches("[0-9a-f]{64}")) {
                return false;
            }
            return snapshots.list(ownership).stream()
                    .anyMatch(snapshot -> expected.equals(snapshot.sha256()) && snapshots.verify(snapshot));
        } catch (IOException exception) {
            return false;
        }
    }

    private static void writeReadyMarker(
            Path marker, String appVersion, int schemaVersion, String snapshotChecksum)
            throws IOException {
        requireSafeDirectory(marker.getParent());
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(temporary)
                        || !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("The WCode writer marker temporary path is unsafe.");
        }
        String content = "writerVersion=" + appVersion + System.lineSeparator()
                + "dataMigration=" + DATA_MIGRATION + System.lineSeparator()
                + "schemaVersion=" + schemaVersion + System.lineSeparator()
                + "snapshotSha256=" + snapshotChecksum + System.lineSeparator();
        Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            Files.move(
                    temporary,
                    marker,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path requireSafeMarker(Path writerState, String markerName) throws IOException {
        if (Files.notExists(writerState, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(writerState);
            } catch (FileAlreadyExistsException ignored) {
                // Validate the path below after a concurrent creator wins the race.
            }
        }
        requireSafeDirectory(writerState);
        Path marker = writerState.resolve(markerName);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(marker)
                        || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("The WCode writer marker path is unsafe.");
        }
        return marker;
    }

    private static void requireSafeDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The WCode writer-state directory is unsafe.");
        }
    }

    public static final class Session implements AutoCloseable {
        private final AppDataLock ownership;
        private final String appVersion;
        private final Path appDataDir;

        private Session(AppDataLock ownership, String appVersion, Path appDataDir) {
            this.ownership = ownership;
            this.appVersion = appVersion;
            this.appDataDir = appDataDir;
        }

        public synchronized void createSignedUpdateSnapshot() throws Exception {
            Path database = appDataDir.resolve("database.db");
            int schemaVersion = readSchemaVersion(database);
            LocalDataSnapshotService snapshots = new LocalDataSnapshotService();
            snapshots.create(ownership, appVersion, schemaVersion, "signed-update-install");
            snapshots.retainRollbackWindow(ownership);
        }

        @Override
        public void close() throws IOException {
            ownership.close();
        }
    }
}
