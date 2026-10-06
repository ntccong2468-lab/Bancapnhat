package com.tuandev.fbsbarcode.shared;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

public final class AppPaths {
    private static final String APP_DIR_NAME = "WCode";
    private static final String WINDOWS_DATA_DIR_NAME = "WCodeData";
    private static final String DATA_PROFILE_PROPERTY = "wcode.data.profile";
    private static final String ZNACK_REGISTRATION_TEST_PROFILE = "znack-registration-test";
    private static final String ZNACK_REGISTRATION_TEST_DIR_NAME = "WCodeZnackRegistrationTestData";

    private AppPaths() {
    }

    public static Path appDataDir() {
        String override = System.getProperty("wcode.appdata.dir");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        Path base = windowsLocalAppData()
                .orElseGet(() -> Paths.get(System.getProperty("user.home", ".")));
        if (isZnackRegistrationTestProfile()) {
            return base.resolve(ZNACK_REGISTRATION_TEST_DIR_NAME);
        }
        return base.resolve(isWindows() ? WINDOWS_DATA_DIR_NAME : APP_DIR_NAME);
    }

    public static List<Path> legacyAppDataDirs() {
        String override = System.getProperty("wcode.appdata.dir");
        if (override != null && !override.isBlank()) {
            return List.of();
        }
        // Test packages must never discover, copy or migrate a production WCode database.
        if (isZnackRegistrationTestProfile()) {
            return List.of();
        }
        Path base = windowsLocalAppData()
                .orElseGet(() -> Paths.get(System.getProperty("user.home", ".")));
        return Stream.of(
                        isWindows() ? base.resolve(APP_DIR_NAME) : null,
                        base.resolve("FBSBarcode")
                )
                .filter(java.util.Objects::nonNull)
                .filter(path -> !path.equals(appDataDir()))
                .toList();
    }

    public static Path logsDir() {
        return appDataDir().resolve("logs");
    }

    public static Path licenseFile() {
        return appDataDir().resolve("license.json");
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
        String directoryName = isZnackRegistrationTestProfile()
                ? ZNACK_REGISTRATION_TEST_DIR_NAME
                : APP_DIR_NAME;
        return windowsProgramData()
                .map(path -> path.resolve(directoryName))
                .orElseGet(() -> windowsTemp()
                        .map(path -> path.resolve(directoryName))
                        .orElseGet(() -> Paths.get(System.getProperty("java.io.tmpdir", "."), directoryName)));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
