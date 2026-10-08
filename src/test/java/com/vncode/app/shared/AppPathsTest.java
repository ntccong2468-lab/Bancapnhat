package com.vncode.app.shared;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPathsTest {
    @TempDir Path tempDir;

    @Test
    void canonicalOverrideIsIsolatedAndIndependentOfWcodeAlias() {
        String canonical = System.getProperty("vncode.appdata.dir");
        String legacy = System.getProperty("wcode.appdata.dir");
        Path selected = tempDir.resolve("vn-code");
        System.setProperty("vncode.appdata.dir", selected.toString());
        System.setProperty("wcode.appdata.dir", tempDir.resolve("wcode").toString());
        try {
            assertEquals(selected, AppPaths.appDataDir());
            assertTrue(AppPaths.legacyAppDataDirs().isEmpty());
        } finally { restore("vncode.appdata.dir", canonical); restore("wcode.appdata.dir", legacy); }
    }

    @Test
    void canonicalProfileIsIndependentOfWcodeProfile() {
        String canonical = System.getProperty("vncode.data.profile");
        String legacy = System.getProperty("wcode.data.profile");
        System.setProperty("vncode.data.profile", "znack-registration-test");
        System.setProperty("wcode.data.profile", "production");
        try {
            assertTrue(AppPaths.isZnackRegistrationTestProfile());
            assertTrue(AppPaths.legacyAppDataDirs().isEmpty());
        } finally { restore("vncode.data.profile", canonical); restore("wcode.data.profile", legacy); }
    }

    @Test
    void wcodeOverrideCannotRedirectVncodeData() {
        String canonical = System.getProperty("vncode.appdata.dir");
        String legacy = System.getProperty("wcode.appdata.dir");
        System.clearProperty("vncode.appdata.dir");
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        try {
            assertNotEquals(tempDir, AppPaths.appDataDir());
            assertTrue(AppPaths.legacyAppDataDirs().isEmpty());
        } finally { restore("vncode.appdata.dir", canonical); restore("wcode.appdata.dir", legacy); }
    }

    @Test
    void freshWindowsInstallUsesVncodeDataDirectory() {
        assertEquals(tempDir.resolve("VNcodeData"), AppPaths.selectDataDirectory(tempDir, true, false));
    }

    @Test
    void wcodeProfileCannotActivateVncodeTestMode() {
        String canonical = System.getProperty("vncode.data.profile");
        String legacy = System.getProperty("wcode.data.profile");
        System.clearProperty("vncode.data.profile");
        System.setProperty("wcode.data.profile", "znack-registration-test");
        try {
            assertFalse(AppPaths.isZnackRegistrationTestProfile());
            assertTrue(AppPaths.legacyAppDataDirs().isEmpty());
        } finally { restore("vncode.data.profile", canonical); restore("wcode.data.profile", legacy); }
    }

    @Test
    void existingWcodeDatabaseAndLicenseAreNeverSelectedOrCopied() throws Exception {
        Path legacy = Files.createDirectory(tempDir.resolve("WCodeData"));
        Files.writeString(legacy.resolve("database.db"), "existing database");
        Files.writeString(legacy.resolve("license.json"), "existing license");
        Path selected = AppPaths.selectDataDirectory(tempDir, true, false);
        assertEquals(tempDir.resolve("VNcodeData"), selected);
        assertFalse(Files.exists(selected.resolve("database.db")));
        assertFalse(Files.exists(selected.resolve("license.json")));
        assertEquals("existing license", Files.readString(legacy.resolve("license.json")));
        assertTrue(AppPaths.legacyAppDataDirs().isEmpty());
    }

    @Test
    void bothApplicationsCanHoldTheirOwnDataLocksConcurrently() throws Exception {
        Path legacy = Files.createDirectory(tempDir.resolve("WCodeData"));
        try (var wcode = AppDataLock.acquire(legacy, "wcode-instance")) {
            Path selected = AppPaths.selectDataDirectory(tempDir, true, false);
            assertNotEquals(legacy, selected);
            try (var vncode = AppDataLock.acquire(selected, "vn-code-instance")) {
                assertThrows(AppDataLock.AlreadyRunningException.class,
                        () -> AppDataLock.acquire(selected, "second-vn-code-instance"));
            }
        }
    }

    @Test
    void existingVncodeDataStaysSelectedWhenBothAppsHaveData() throws Exception {
        Files.createDirectory(tempDir.resolve("WCodeData"));
        Path own = Files.createDirectory(tempDir.resolve("VNcodeData"));
        assertEquals(own, AppPaths.selectDataDirectory(tempDir, true, false));
    }

    @Test
    void nonWindowsDataIsAlsoIndependent() throws Exception {
        Files.createDirectory(tempDir.resolve("WCode"));
        assertEquals(tempDir.resolve("VNcode"), AppPaths.selectDataDirectory(tempDir, false, false));
    }

    @Test
    void testProfileNeverUsesEitherWcodeDataDirectory() throws Exception {
        Files.createDirectory(tempDir.resolve("WCodeData"));
        Files.createDirectory(tempDir.resolve("WCodeZnackRegistrationTestData"));
        assertEquals(tempDir.resolve("VNcodeZnackRegistrationTestData"),
                AppPaths.selectDataDirectory(tempDir, true, true));
    }

    @Test
    void selectedDefaultDirectoryCannotChangeWhileItsOwnershipLockIsHeld() throws Exception {
        Path selected = AppPaths.defaultDataDirectory(tempDir, true, false);
        try (var lock = AppDataLock.acquire(selected, "vn-code-instance")) {
            Files.createDirectory(tempDir.resolve("WCodeData"));
            assertEquals(selected, AppPaths.defaultDataDirectory(tempDir, true, false));
            assertThrows(AppDataLock.AlreadyRunningException.class,
                    () -> AppDataLock.acquire(AppPaths.defaultDataDirectory(tempDir, true, false), "second-instance"));
        }
    }

    @Test
    void systemCacheAndUpdateBackupsNeverUseWcodeFolders() throws Exception {
        Files.createDirectory(tempDir.resolve("WCode"));
        Files.createDirectory(tempDir.resolve("WCodeZnackRegistrationTestData"));
        assertEquals(tempDir.resolve("VNcode"), AppPaths.selectSystemDirectory(tempDir, false));
        assertEquals(tempDir.resolve("VNcodeZnackRegistrationTestData"), AppPaths.selectSystemDirectory(tempDir, true));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
