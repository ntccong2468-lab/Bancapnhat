package com.tuandev.fbsbarcode.integration.update;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateInstallerServiceTest {

    @Test
    void relaunchesFromTheDataSafeInstallDirectoryAfterUpgrade() {
        String command = new UpdateInstallerService()
                .buildWindowsInstallCommand(Path.of("C:\\Temp\\WCode-update.exe"));

        String currentInstall = "Join-Path $env:LOCALAPPDATA 'WCodeApp\\WCode.exe'";
        String legacyInstall = "Join-Path $env:LOCALAPPDATA 'Programs\\WCode\\WCode.exe'";

        assertTrue(command.contains(currentInstall));
        assertTrue(command.indexOf(currentInstall) < command.indexOf(legacyInstall));
    }

    @Test
    void testProfileRelaunchesTheIsolatedExecutable() {
        String originalProfile = System.getProperty("wcode.data.profile");
        try {
            System.setProperty("wcode.data.profile", "znack-registration-test");

            String command = new UpdateInstallerService()
                    .buildWindowsInstallCommand(Path.of("C:\\Temp\\WCode-test-update.exe"));

            assertTrue(command.contains("Join-Path $env:LOCALAPPDATA 'WCodeZnackRegistrationTestApp\\WCodeZnackTest.exe'"));
            assertTrue(command.contains("Programs\\WCodeZnackTest\\WCodeZnackTest.exe"));
        } finally {
            if (originalProfile == null) System.clearProperty("wcode.data.profile");
            else System.setProperty("wcode.data.profile", originalProfile);
        }
    }
}
