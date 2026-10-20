package com.vncode.app.integration.update;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UpdateInstallerServiceTest {

    @Test
    void exeFallbackForwardsPassiveOptionsWithoutRepeatingTheWrappersInstallArgument() {
        String command = new UpdateInstallerService()
                .buildWindowsInstallCommand(Path.of("C:\\Temp\\Seller's Update\\VN-code-update.exe"));

        // OpenJDK25 MsiWrapper.cpp adds /i and its extracted MSI, then forwards
        // all EXE arguments to msiexec. Supplying /i again would break that command.
        String fallback = command.substring(command.indexOf("} else {"), command.indexOf("if ($null -ne $process"));
        assertTrue(fallback.contains("-FilePath $installer -ArgumentList @('/passive', '/norestart') -Wait -PassThru"));
        assertFalse(fallback.contains("'/i'"));
        assertTrue(command.contains("Seller''s Update"));
        assertTrue(command.contains("Wait-Process"));
        assertFalse(command.contains("Stop-Process"));
    }

    @Test
    void msiUpgradeIsPassiveAndRelaunchWaitsForTheInstallerInsteadOfAnExtraTimer() {
        String command = new UpdateInstallerService()
                .buildWindowsInstallCommand(Path.of("C:\\Temp\\Seller's Update\\VN-code-update.msi"));

        String direct = command.substring(command.indexOf("if ($extension -eq '.msi')"), command.indexOf("} else {"));
        assertTrue(direct.contains("-FilePath 'msiexec.exe'"));
        assertTrue(direct.contains("@('/i', ('\"' + $installer + '\"'), '/passive', '/norestart') -Wait -PassThru"));
        assertTrue(command.contains("Seller''s Update"));
        assertTrue(command.contains("$process.ExitCode -in @(0,3010)"));
        assertFalse(command.contains("Start-Sleep"));
    }

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
