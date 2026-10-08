package com.vncode.app.shared;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class AppPaths {
    private record DataDirectoryKey(Path base, boolean windows, boolean testProfile) {}
    private static final java.util.concurrent.ConcurrentMap<DataDirectoryKey, Path> DEFAULT_DIRECTORIES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final String APP_DIR_NAME = "VNcode";
    private static final String WINDOWS_DATA_DIR_NAME = "VNcodeData";
    private static final String DATA_PROFILE_PROPERTY = "vncode.data.profile";
    private static final String ZNACK_REGISTRATION_TEST_PROFILE = "znack-registration-test";
    private static final String ZNACK_REGISTRATION_TEST_DIR_NAME = "VNcodeZnackRegistrationTestData";

    private AppPaths() {
    }

    public static Path appDataDir() {
        String override = System.getProperty("vncode.appdata.dir", "");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        Path base = windowsLocalAppData()
                .orElseGet(() -> Paths.get(System.getProperty("user.home", ".")));
        return defaultDataDirectory(base, isWindows(), isZnackRegistrationTestProfile());
    }

    static Path defaultDataDirectory(Path base, boolean windows, boolean testProfile) {
        // Every later database/license/log lookup must use the path chosen before locking.
        DataDirectoryKey key = new DataDirectoryKey(base.toAbsolutePath().normalize(), windows, testProfile);
        return DEFAULT_DIRECTORIES.computeIfAbsent(key, selected ->
                selectDataDirectory(selected.base(), selected.windows(), selected.testProfile()));
    }

    // VN code owns a separate directory even when WCode is installed or running.
    static Path selectDataDirectory(Path base, boolean windows, boolean testProfile) {
        return base.resolve(testProfile ? ZNACK_REGISTRATION_TEST_DIR_NAME
                : windows ? WINDOWS_DATA_DIR_NAME : APP_DIR_NAME);
    }

    public static List<Path> legacyAppDataDirs() {
        // Independent application: never scan, copy or migrate WCode user data.
        return List.of();
    }

    public static Path logsDir() {
        return appDataDir().resolve("logs");
    }

    public static Path javaFxCacheDir() {
        return safeSystemDir().resolve("openjfx-cache");
    }

    public static Path updateBackupDir() {
        return safeSystemDir().resolve("update-backup");
    }

    public static Path safeUserHomeDir() {
        return safeSystemDir().resolve("home");
    }

    public static boolean isZnackRegistrationTestProfile() {
        return ZNACK_REGISTRATION_TEST_PROFILE.equalsIgnoreCase(
                System.getProperty(DATA_PROFILE_PROPERTY, "").trim());
    }

    public static Path nativeTempDir() {
        List<Path> candidates = List.of(
                safeSystemDir().resolve("tmp"),
                appDataDir().resolve("tmp")
        );

        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Files.createDirectories(candidate);
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            } catch (IOException ignored) {
            }
        }
        return Paths.get(System.getProperty("java.io.tmpdir", "."));
    }

    public static File preferredFileChooserDirectory() {
        List<Path> candidates = Arrays.asList(
                pathsFromBase(appDataDir(), "exports"),
                pathsFromBase(windowsUserProfile().orElse(null), "Downloads"),
                appDataDir(),
                Paths.get(System.getProperty("user.dir", "."))
        );

        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Files.createDirectories(candidate);
                if (Files.isDirectory(candidate)) {
                    return candidate.toFile();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static File preferredDownloadsDirectory() {
        List<Path> candidates = Arrays.asList(
                pathsFromBase(windowsUserProfile().orElse(null), "Downloads"),
                appDataDir()
        );

        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Files.createDirectories(candidate);
                if (Files.isDirectory(candidate)) {
                    return candidate.toFile();
                }
            } catch (Exception ignored) {
            }
        }
        return preferredFileChooserDirectory();
    }

    private static Path pathsFromBase(Path base, String child) {
        return base == null ? null : base.resolve(child);
    }

    private static Optional<Path> windowsLocalAppData() {
        if (!isWindows()) {
            return Optional.empty();
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Optional.of(Paths.get(localAppData));
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Optional.of(Paths.get(appData));
        }
        return Optional.empty();
    }

    private static Optional<Path> windowsProgramData() {
        if (!isWindows()) {
            return Optional.empty();
        }
        String programData = System.getenv("ProgramData");
        if (programData != null && !programData.isBlank()) {
            return Optional.of(Paths.get(programData));
        }
        return Optional.of(Paths.get("C:\\ProgramData"));
    }

    private static Optional<Path> windowsUserProfile() {
        if (!isWindows()) {
            return Optional.empty();
        }
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null && !userProfile.isBlank()) {
            return Optional.of(Paths.get(userProfile));
        }
        return Optional.empty();
    }

    private static Optional<Path> windowsTemp() {
        if (!isWindows()) {
            return Optional.empty();
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            return Optional.of(Paths.get(systemRoot, "Temp"));
        }
        return Optional.of(Paths.get("C:\\Windows\\Temp"));
    }

    private static Path safeSystemDir() {
        Path base = windowsProgramData()
                .orElseGet(() -> windowsTemp().orElseGet(
                        () -> Paths.get(System.getProperty("java.io.tmpdir", "."))));
        return selectSystemDirectory(base, isZnackRegistrationTestProfile());
    }

    static Path selectSystemDirectory(Path base, boolean testProfile) {
        return base.resolve(testProfile ? ZNACK_REGISTRATION_TEST_DIR_NAME : APP_DIR_NAME);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
