package com.vncode.app.shared;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPathsTest {
    @TempDir Path tempDir;

    @Test
    void canonicalOverrideIsIsolatedAndPreferredOverLegacyAlias() {
        String canonical = System.getProperty("vncode.appdata.dir");
        String legacy = System.getProperty("wcode.appdata.dir");
        Path selected = tempDir.resolve("canonical");
        System.setProperty("vncode.appdata.dir", selected.toString());
        System.setProperty("wcode.appdata.dir", tempDir.resolve("legacy").toString());
        try {
            assertEquals(selected, AppPaths.appDataDir());
            assertTrue(AppPaths.legacyAppDataDirs().isEmpty());
        } finally {
            restore("vncode.appdata.dir", canonical);
            restore("wcode.appdata.dir", legacy);
        }
    }

    @Test
    void canonicalProfileOverridesLegacyProfile() {
        String canonical = System.getProperty("vncode.data.profile");
        String legacy = System.getProperty("wcode.data.profile");
        System.setProperty("vncode.data.profile", "znack-registration-test");
        System.setProperty("wcode.data.profile", "production");
        try {
            assertTrue(AppPaths.isZnackRegistrationTestProfile());
            assertTrue(AppPaths.legacyAppDataDirs().isEmpty());
        } finally {
            restore("vncode.data.profile", canonical);
            restore("wcode.data.profile", legacy);
        }
    }

    @Test
    void legacyOverrideStillIsolatesData() {
        String canonical = System.getProperty("vncode.appdata.dir");
        String legacy = System.getProperty("wcode.appdata.dir");
        System.clearProperty("vncode.appdata.dir");
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        try {
            assertEquals(tempDir, AppPaths.appDataDir());
            assertTrue(AppPaths.legacyAppDataDirs().isEmpty());
        } finally {
            restore("vncode.appdata.dir", canonical);
            restore("wcode.appdata.dir", legacy);
        }
    }

    @Test
    void freshWindowsInstallUsesDataOnlyDirectory() {
        assertEquals(tempDir.resolve("VNcodeData"), AppPaths.selectDataDirectory(tempDir, true, false));
    }

    @Test
    void legacyTestProfileRemainsIsolated() {
        String canonical = System.getProperty("vncode.data.profile");
        String legacy = System.getProperty("wcode.data.profile");
        System.clearProperty("vncode.data.profile");
        System.setProperty("wcode.data.profile", "znack-registration-test");
        try {
            assertTrue(AppPaths.isZnackRegistrationTestProfile());
            assertTrue(AppPaths.legacyAppDataDirs().isEmpty());
            assertEquals(tempDir.resolve("VNcodeZnackRegistrationTestData"),
                    AppPaths.selectDataDirectory(tempDir, true, true));
        } finally {
            restore("vncode.data.profile", canonical);
            restore("wcode.data.profile", legacy);
        }
    }

    @Test
    void existingWindowsDatabaseAndLicenseStayInTheSameDirectory() throws Exception {
        Path legacy = Files.createDirectory(tempDir.resolve("WCodeData"));
        Files.writeString(legacy.resolve("database.db"), "existing database");
        Files.writeString(legacy.resolve("license.json"), "existing license");
        assertEquals(legacy, AppPaths.selectDataDirectory(tempDir, true, false));
        assertEquals("existing license", Files.readString(legacy.resolve("license.json")));
        assertFalse(Files.exists(tempDir.resolve("VNcodeData")));
    }

    @Test
    void emptyLegacyDirectoryKeepsTheOldProcessesOwnershipLock() throws Exception {
        Path legacy = Files.createDirectory(tempDir.resolve("WCodeData"));
        try (var lock = AppDataLock.acquire(legacy, "legacy-instance")) {
            Path selected = AppPaths.selectDataDirectory(tempDir, true, false);
            assertEquals(legacy, selected);
            assertThrows(AppDataLock.AlreadyRunningException.class,
                    () -> AppDataLock.acquire(selected, "vn-code-instance"));
        }
    }

    @Test
    void legacyDirectoryRemainsPreferredIfANewDirectoryAlreadyExists() throws Exception {
        Path legacy = Files.createDirectory(tempDir.resolve("WCodeData"));
        Files.createDirectory(tempDir.resolve("VNcodeData"));
        assertEquals(legacy, AppPaths.selectDataDirectory(tempDir, true, false));
    }

    @Test
    void nonWindowsDataRemainsCompatible() throws Exception {
        assertEquals(tempDir.resolve("VNcode"), AppPaths.selectDataDirectory(tempDir, false, false));
        Path legacy = Files.createDirectory(tempDir.resolve("WCode"));
        assertEquals(legacy, AppPaths.selectDataDirectory(tempDir, false, false));
    }

    @Test
    void testProfileCannotSelectProductionDirectory() throws Exception {
        Files.createDirectory(tempDir.resolve("WCodeData"));
        assertEquals(tempDir.resolve("VNcodeZnackRegistrationTestData"),
                AppPaths.selectDataDirectory(tempDir, true, true));
        Path legacyTest = Files.createDirectory(tempDir.resolve("WCodeZnackRegistrationTestData"));
        assertEquals(legacyTest, AppPaths.selectDataDirectory(tempDir, true, true));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
