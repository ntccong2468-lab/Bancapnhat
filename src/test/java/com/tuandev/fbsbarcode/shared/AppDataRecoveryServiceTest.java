package com.tuandev.fbsbarcode.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppDataRecoveryServiceTest {
    @TempDir Path tempDir;

    @Test
    void legacyMigrationCopiesUserDataButNotTheOldJpackageRuntime() throws Exception {
        Path source = tempDir.resolve("WCode");
        Path target = tempDir.resolve("WCodeData");
        Files.createDirectories(source.resolve("app"));
        Files.createDirectories(source.resolve("runtime/bin"));
        Files.createDirectories(source.resolve("exports"));
        Files.writeString(source.resolve("database.db"), "database");
        Files.writeString(source.resolve("license.json"), "license");
        Files.writeString(source.resolve("exports/orders.pdf"), "pdf");
        Files.writeString(source.resolve("WCode.exe"), "legacy executable");
        Files.writeString(source.resolve("app/WCode.cfg"), "legacy config");
        Files.writeString(source.resolve("runtime/bin/java.exe"), "legacy runtime");

        AppDataRecoveryService.copyAppDataWithoutDeletingCurrent(source, target, true);

        assertEquals("database", Files.readString(target.resolve("database.db")));
        assertEquals("license", Files.readString(target.resolve("license.json")));
        assertTrue(Files.isRegularFile(target.resolve("exports/orders.pdf")));
        assertFalse(Files.exists(target.resolve("WCode.exe")));
        assertFalse(Files.exists(target.resolve("app")));
        assertFalse(Files.exists(target.resolve("runtime")));
    }
}
