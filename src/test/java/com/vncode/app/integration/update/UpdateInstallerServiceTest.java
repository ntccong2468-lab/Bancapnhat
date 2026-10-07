package com.vncode.app.integration.update;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateInstallerServiceTest {

    @Test
    void relaunchesFromTheDataSafeInstallDirectoryAfterUpgrade() {
        String command = new UpdateInstallerService()
                .buildWindowsInstallCommand(Path.of("C:\\Temp\\VN-code-update.exe"));

        String currentInstall = "Join-Path $env:LOCALAPPDATA 'VNcodeApp\\VN code.exe'";
        String legacyInstall = "Join-Path $env:LOCALAPPDATA 'Programs\\VN code\\VN code.exe'";

        assertTrue(command.contains(currentInstall));
        assertTrue(command.indexOf(currentInstall) < command.indexOf(legacyInstall));
    }

    @Test
    void testProfileRelaunchesTheIsolatedExecutable() {
        String originalProfile = System.getProperty("vncode.data.profile");
        try {
            System.setProperty("vncode.data.profile", "znack-registration-test");

            String command = new UpdateInstallerService()
                    .buildWindowsInstallCommand(Path.of("C:\\Temp\\VN-code-test-update.exe"));

            assertTrue(command.contains("Join-Path $env:LOCALAPPDATA 'VNcodeZnackRegistrationTestApp\\VN code Znack Test.exe'"));
            assertTrue(command.contains("Programs\\VN code Znack Test\\VN code Znack Test.exe"));
        } finally {
            if (originalProfile == null) System.clearProperty("vncode.data.profile");
            else System.setProperty("vncode.data.profile", originalProfile);
        }
    }
}
