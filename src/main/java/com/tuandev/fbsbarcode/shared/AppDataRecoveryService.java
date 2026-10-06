package com.tuandev.fbsbarcode.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class AppDataRecoveryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppDataRecoveryService.class);
    private static final String DB_NAME = "database.db";
    private static final String MARKER_FILE = "restore-required.marker";

    private AppDataRecoveryService() {
    }

    public static void prepareBackupForUpdate() {
        Path source = AppPaths.appDataDir();
        Path database = source.resolve(DB_NAME);
        if (!Files.exists(source) || !Files.exists(database)) {
            return;
        }
        try {
            Path backupRoot = AppPaths.updateBackupDir();
            Path payloadDir = backupRoot.resolve("payload");
            Files.createDirectories(backupRoot);
            deleteRecursively(payloadDir);
            copyRecursively(source, payloadDir);
            Files.writeString(
                    backupRoot.resolve(MARKER_FILE),
                    "createdAt=" + Instant.now(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            LOGGER.info("Prepared update backup at {}", payloadDir);
        } catch (Exception ex) {
            LOGGER.error("Failed to prepare update backup", ex);
        }
    }

    public static void recoverIfNeededOnStartup() {
        restoreFromUpdateBackupIfNeeded();
        restoreFromLegacyLocationIfNeeded();
    }

    private static void restoreFromUpdateBackupIfNeeded() {
        Path backupRoot = AppPaths.updateBackupDir();
        Path marker = backupRoot.resolve(MARKER_FILE);
        Path payloadDir = backupRoot.resolve("payload");
        if (!Files.exists(marker) || !hasUsableBackup(payloadDir)) {
            return;
        }
        try {
            Path currentDir = AppPaths.appDataDir();
            Path currentDb = currentDir.resolve(DB_NAME);
            if (!Files.exists(currentDb) || databaseLooksEmpty(currentDb)) {
                copyAppDataWithoutDeletingCurrent(payloadDir, currentDir, true);
                LOGGER.info("Restored app data from update backup {}", payloadDir);
            } else {
                LOGGER.info("Skipped update backup restore because current database is not empty");
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to restore app data from update backup", ex);
        } finally {
            try {
                Files.deleteIfExists(marker);
            } catch (IOException ignored) {
            }
        }
    }

    private static void restoreFromLegacyLocationIfNeeded() {
        Path currentDir = AppPaths.appDataDir();
        Path currentDb = currentDir.resolve(DB_NAME);
        if (Files.exists(currentDb) && !databaseLooksEmpty(currentDb)) {
            return;
        }
        for (Path legacyDir : AppPaths.legacyAppDataDirs()) {
            Path legacyDb = legacyDir.resolve(DB_NAME);
            if (!Files.exists(legacyDb) || !databaseHasUserData(legacyDb)) {
                continue;
            }
            try {
                copyAppDataWithoutDeletingCurrent(legacyDir, currentDir, true);
                LOGGER.info("Migrated app data from legacy directory {}", legacyDir);
                return;
            } catch (Exception ex) {
                LOGGER.error("Failed to migrate app data from legacy directory {}", legacyDir, ex);
            }
        }
    }

    private static boolean hasUsableBackup(Path payloadDir) {
        Path backupDb = payloadDir.resolve(DB_NAME);
        return Files.exists(backupDb) && databaseHasUserData(backupDb);
    }

    private static boolean databaseLooksEmpty(Path database) {
        if (!Files.exists(database)) {
            return true;
        }
        return !databaseHasUserData(database);
    }

    private static boolean databaseHasUserData(Path database) {
        String url = "jdbc:sqlite:" + database.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url)) {
            return countIfTableExists(connection, "shops") > 0
                    || countIfTableExists(connection, "wb_orders") > 0
                    || countIfTableExists(connection, "wb_supplies") > 0
                    || countIfTableExists(connection, "print_jobs") > 0
                    || countIfTableExists(connection, "kiz_codes") > 0;
        } catch (SQLException ex) {
            LOGGER.warn("Unable to inspect database {}", database, ex);
            return false;
        }
    }

    private static int countIfTableExists(Connection connection, String tableName) throws SQLException {
        if (!tableExists(connection, tableName)) {
            return 0;
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM " + tableName);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static void copyAppDataWithoutDeletingCurrent(Path source, Path target, boolean replaceDatabase) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                if (isLegacyInstallerPayload(relative)) {
                    continue;
                }
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                    continue;
                }

                Files.createDirectories(destination.getParent());
                boolean isDatabase = isDatabaseFile(path);
                if (isDatabase && replaceDatabase) {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                } else if (!Files.exists(destination)) {
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static boolean isLegacyInstallerPayload(Path relative) {
        if (relative == null || relative.getNameCount() == 0) {
            return false;
        }
        String topLevel = relative.getName(0).toString();
        if ("app".equalsIgnoreCase(topLevel) || "runtime".equalsIgnoreCase(topLevel)) {
            return true;
        }
        return relative.getNameCount() == 1
                && ("WCode.exe".equalsIgnoreCase(topLevel)
                        || "check-portable.bat".equalsIgnoreCase(topLevel));
    }

    private static boolean isDatabaseFile(Path path) {
        String fileName = path.getFileName().toString();
        return DB_NAME.equals(fileName)
                || (DB_NAME + "-wal").equals(fileName)
                || (DB_NAME + "-shm").equals(fileName);
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            List<Path> entries = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
