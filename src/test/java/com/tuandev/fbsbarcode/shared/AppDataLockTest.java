package com.tuandev.fbsbarcode.shared;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppDataLockTest {
    @TempDir Path tempDir;

    @Test
    void rejectsASecondOwnerWithoutTouchingTheDatabase() throws Exception {
        Path appData = tempDir.resolve("app-data");

        try (AppDataLock ignored = AppDataLock.acquire(appData, "javafx")) {
            assertThrows(
                    AppDataLock.AlreadyRunningException.class,
                    () -> AppDataLock.acquire(appData, "second-writer"));
            assertFalse(appData.resolve("database.db").toFile().exists());
        }
    }

    @Test
    void releasesOwnershipWhenClosed() throws Exception {
        Path appData = tempDir.resolve("app-data");
        AppDataLock first = AppDataLock.acquire(appData, "javafx");

        first.close();

        assertDoesNotThrow(() -> {
            try (AppDataLock ignored = AppDataLock.acquire(appData, "second-writer")) {
                // The second entry point now owns the app-data directory.
            }
        });
    }

    @Test
    void preventsASecondProcessFromOwningTheSameDirectory() throws Exception {
        Path appData = tempDir.resolve("shared-app-data");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        Process holder = new ProcessBuilder(
                        javaExecutable.toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        AppDataLockTest.class.getName(),
                        "hold",
                        appData.toString())
                .redirectErrorStream(true)
                .start();

        try (BufferedReader output =
                new BufferedReader(new InputStreamReader(holder.getInputStream()))) {
            assertEquals("LOCKED", output.readLine());
            assertThrows(
                    AppDataLock.AlreadyRunningException.class,
                    () -> AppDataLock.acquire(appData, "second-writer"));
        } finally {
            holder.destroy();
            if (!holder.waitFor(5, TimeUnit.SECONDS)) {
                holder.destroyForcibly();
            }
        }

        assertTrue(holder.waitFor(5, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> {
            try (AppDataLock ignored = AppDataLock.acquire(appData, "second-writer")) {
                // The operating system released the lock when the holder process exited.
            }
        });
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"hold".equals(args[0])) {
            throw new IllegalArgumentException("Expected: hold <app-data-directory>");
        }
        try (AppDataLock ignored = AppDataLock.acquire(Path.of(args[1]), "test-holder")) {
            System.out.println("LOCKED");
            System.out.flush();
            System.in.read();
        }
    }
}
