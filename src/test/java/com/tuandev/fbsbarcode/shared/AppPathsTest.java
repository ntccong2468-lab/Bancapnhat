package com.tuandev.fbsbarcode.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPathsTest {
    @TempDir Path tempDir;

    @Test
    void appDataOverrideDisablesLegacyUserDirectoryScanning() {
        String previous = System.getProperty("wcode.appdata.dir");
        Path isolatedAppData = tempDir.resolve("isolated");
        System.setProperty("wcode.appdata.dir", isolatedAppData.toString());
        try {
            assertEquals(isolatedAppData, AppPaths.appDataDir());
            assertTrue(AppPaths.legacyAppDataDirs().isEmpty());
        } finally {
            if (previous == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previous);
            }
        }
    }

    @Test
    void windowsSeparatesCurrentDataFromTheLegacyInstallerDirectory() {
        String previousOs = System.getProperty("os.name");
        String previousOverride = System.getProperty("wcode.appdata.dir");
        System.setProperty("os.name", "Windows 11");
        System.clearProperty("wcode.appdata.dir");
        try {
            assertEquals("WCodeData", AppPaths.appDataDir().getFileName().toString());
            assertTrue(AppPaths.legacyAppDataDirs().stream()
                    .anyMatch(path -> path.getFileName().toString().equals("WCode")));
        } finally {
            if (previousOs == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", previousOs);
            }
            if (previousOverride == null) {
                System.clearProperty("wcode.appdata.dir");
            } else {
                System.setProperty("wcode.appdata.dir", previousOverride);
            }
        }
    }

    @Test
    void znackRegistrationTestProfileNeverUsesOrMigratesProductionData() {
        String previousOs = System.getProperty("os.name");
        String previousOverride = System.getProperty("wcode.appdata.dir");
        String previousProfile = System.getProperty("wcode.data.profile");
        System.setProperty("os.name", "Windows 11");
        System.clearProperty("wcode.appdata.dir");
        System.setProperty("wcode.data.profile", "znack-registration-test");
        try {
            assertEquals("WCodeZnackRegistrationTestData", AppPaths.appDataDir().getFileName().toString());
            assertTrue(AppPaths.isZnackRegistrationTestProfile());
            assertTrue(AppPaths.legacyAppDataDirs().isEmpty());
        } finally {
            restore("os.name", previousOs);
            restore("wcode.appdata.dir", previousOverride);
            restore("wcode.data.profile", previousProfile);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
