package com.tuandev.fbsbarcode.integration.znack.signature;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoProCommandRunnerTest {
    @Test void considersArchitectureIndependentCryptoProInstallDirectories() {
        CryptoProCommandRunner runner = new CryptoProCommandRunner();

        assertTrue(runner.candidates("cryptcp", false, Map.of()).contains(Path.of("/opt/cprocsp/bin/cryptcp")));
        assertTrue(runner.candidates("cpconfig", false, Map.of()).contains(Path.of("/opt/cprocsp/sbin/cpconfig")));
    }

    @Test void findsWindowsCryptcpArchitectureAliasesAndEnvironmentInstallRoots() {
        CryptoProCommandRunner runner = new CryptoProCommandRunner();

        var candidates = runner.candidates("cryptcp", true, Map.of(
                "ProgramFiles", "D:\\Programs",
                "ProgramFiles(x86)", "D:\\Programs x86"));

        assertTrue(candidates.contains(Path.of("D:\\Programs", "Crypto Pro", "CSP", "cryptcp.exe")));
        assertTrue(candidates.contains(Path.of("D:\\Programs", "Crypto Pro", "CSP", "cryptcp.x64.exe")));
        assertTrue(candidates.contains(Path.of("D:\\Programs x86", "Crypto Pro", "CSP", "cryptcp.x86.exe")));
    }

    @Test void reportsMissingSigningAndDiscoveryToolsSeparately() {
        CryptoProCommandRunner runner = new CryptoProCommandRunner();

        assertEquals(CryptoProErrorCode.CRYPTCP_MISSING,
                org.junit.jupiter.api.Assertions.assertThrows(CryptoProException.class,
                        () -> runner.resolve("missing-command", "cryptcp")).code());
        assertEquals(CryptoProErrorCode.CERTMGR_MISSING,
                org.junit.jupiter.api.Assertions.assertThrows(CryptoProException.class,
                        () -> runner.resolve("missing-command", "certmgr")).code());
    }
}
