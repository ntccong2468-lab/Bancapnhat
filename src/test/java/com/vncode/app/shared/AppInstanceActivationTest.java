package com.vncode.app.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppInstanceActivationTest {
    @TempDir Path tempDir;

    @Test
    void activatesTheOwnerWithoutReleasingTheWriterLockOrCreatingTheDatabase() throws Exception {
        Path profile = tempDir.resolve("profile");
        AtomicInteger restores = new AtomicInteger();
        try (AppDataLock ownership = AppDataLock.acquire(profile, "first");
                AppInstanceActivation listener = AppInstanceActivation.listen(ownership, () -> {
                    restores.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                })) {
            assertTrue(Files.isRegularFile(profile.resolve(".activation/owner")),
                    "The listener must be discoverable before startup continues.");
            assertTrue(AppInstanceActivation.requestActivation(profile, Duration.ofSeconds(2)));
            assertEquals(1, restores.get());
            assertThrows(AppDataLock.AlreadyRunningException.class,
                    () -> AppDataLock.acquire(profile, "second"));
            assertFalse(Files.exists(profile.resolve("database.db")));
            assertTrue(Files.isRegularFile(profile.resolve(".wcode.lock")));
        }
        try (AppDataLock ignored = AppDataLock.acquire(profile, "next")) {
            assertFalse(Files.exists(profile.resolve(".activation/owner")));
        }
    }

    @Test
    void acknowledgesOnlyAfterTheRestoreFutureCompletes() throws Exception {
        Path profile = tempDir.resolve("profile");
        CountDownLatch called = new CountDownLatch(1);
        CompletableFuture<Void> restored = new CompletableFuture<>();
        AtomicInteger restores = new AtomicInteger();
        try (AppDataLock ownership = AppDataLock.acquire(profile, "first");
                AppInstanceActivation listener = AppInstanceActivation.listen(ownership, () -> {
                    restores.incrementAndGet();
                    called.countDown();
                    return restored;
                });
                var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> AppInstanceActivation.requestActivation(profile, Duration.ofSeconds(2)));
            assertTrue(called.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> request.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, acknowledgementCount(profile));
            restored.complete(null);
            assertTrue(request.get(2, TimeUnit.SECONDS));
            assertEquals(1, restores.get());
            assertEquals(0, requestCount(profile));
            assertEquals(0, acknowledgementCount(profile));
        }
    }

    @Test
    void failedRestoreNeverAcknowledgesAndLeavesTheWriterLockHeld() throws Exception {
        Path profile = tempDir.resolve("profile");
        CountDownLatch called = new CountDownLatch(1);
        try (AppDataLock ownership = AppDataLock.acquire(profile, "first");
                AppInstanceActivation listener = AppInstanceActivation.listen(ownership, () -> {
                    called.countDown();
                    return CompletableFuture.failedFuture(new IOException("Window unavailable"));
                })) {
            assertFalse(AppInstanceActivation.requestActivation(profile, Duration.ofMillis(500)));
            assertEquals(0, called.getCount());
            assertEquals(0, acknowledgementCount(profile));
            assertEquals(0, requestCount(profile));
            assertThrows(AppDataLock.AlreadyRunningException.class,
                    () -> AppDataLock.acquire(profile, "second"));
        }
    }

    @Test
    void closeDoesNotWaitForRestoreAndPreventsALateAcknowledgement() throws Exception {
        Path profile = tempDir.resolve("profile");
        CountDownLatch called = new CountDownLatch(1);
        CompletableFuture<Void> oldRestore = new CompletableFuture<>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            try (AppDataLock ownership = AppDataLock.acquire(profile, "first");
                    AppInstanceActivation listener = AppInstanceActivation.listen(ownership, () -> {
                        called.countDown();
                        return oldRestore;
                    })) {
                var request = executor.submit(() -> AppInstanceActivation.requestActivation(profile, Duration.ofMillis(600)));
                assertTrue(called.await(2, TimeUnit.SECONDS));
                listener.close();
                assertFalse(oldRestore.isDone(), "Close must not wait for the FX restore future.");
                assertFalse(Files.exists(profile.resolve(".activation/owner")));
                assertFalse(request.get(2, TimeUnit.SECONDS));
            }
            try (AppDataLock nextOwner = AppDataLock.acquire(profile, "next");
                    AppInstanceActivation next = AppInstanceActivation.listen(nextOwner,
                            () -> CompletableFuture.completedFuture(null))) {
                String nextDescriptor = Files.readString(profile.resolve(".activation/owner"));
                oldRestore.complete(null);
                assertEquals(nextDescriptor, Files.readString(profile.resolve(".activation/owner")));
                assertEquals(0, acknowledgementCount(profile));
                assertTrue(AppInstanceActivation.requestActivation(profile, Duration.ofSeconds(2)));
            }
        }
    }

    @Test
    void keepsSeparateProfilesIndependent() throws Exception {
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        try (AppDataLock firstOwner = AppDataLock.acquire(first, "first");
                AppDataLock secondOwner = AppDataLock.acquire(second, "second");
                AppInstanceActivation firstListener = AppInstanceActivation.listen(firstOwner, () -> {
                    firstCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });
                AppInstanceActivation secondListener = AppInstanceActivation.listen(secondOwner, () -> {
                    secondCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                })) {
            assertTrue(AppInstanceActivation.requestActivation(second, Duration.ofSeconds(2)));
            assertEquals(0, firstCalls.get());
            assertEquals(1, secondCalls.get());
            assertTrue(AppInstanceActivation.requestActivation(first, Duration.ofSeconds(2)));
            assertEquals(1, firstCalls.get());
            assertEquals(1, secondCalls.get());
        }
    }

    @Test
    void noListenerTimesOutWithoutCreatingAnActivationDirectory() throws Exception {
        Path profile = tempDir.resolve("older-app");
        try (AppDataLock ownership = AppDataLock.acquire(profile, "older-app")) {
            assertFalse(AppInstanceActivation.requestActivation(profile, Duration.ofMillis(100)));
            assertFalse(Files.exists(profile.resolve(".activation")));
            assertThrows(AppDataLock.AlreadyRunningException.class,
                    () -> AppDataLock.acquire(profile, "second"));
        }
    }

    @Test
    void rejectsNonDirectoryAndOversizedSignals() throws Exception {
        Path profile = tempDir.resolve("profile");
        try (AppDataLock ownership = AppDataLock.acquire(profile, "first")) {
            Path activation = profile.resolve(".activation");
            Files.writeString(activation, "not a directory");
            assertThrows(IOException.class, () -> AppInstanceActivation.listen(ownership,
                    () -> CompletableFuture.completedFuture(null)));
            assertThrows(IOException.class,
                    () -> AppInstanceActivation.requestActivation(profile, Duration.ofMillis(100)));
            Files.delete(activation);
            Files.createDirectory(activation);
            Path descriptor = activation.resolve("owner");
            Files.createDirectory(descriptor);
            assertThrows(IOException.class, () -> AppInstanceActivation.listen(ownership,
                    () -> CompletableFuture.completedFuture(null)));
            assertThrows(IOException.class,
                    () -> AppInstanceActivation.requestActivation(profile, Duration.ofMillis(100)));
            Files.delete(descriptor);
            Files.writeString(descriptor, "x".repeat(257));
            assertThrows(IOException.class, () -> AppInstanceActivation.listen(ownership,
                    () -> CompletableFuture.completedFuture(null)));
            assertThrows(IOException.class,
                    () -> AppInstanceActivation.requestActivation(profile, Duration.ofMillis(100)));
            assertThrows(AppDataLock.AlreadyRunningException.class,
                    () -> AppDataLock.acquire(profile, "second"));
        }
    }

    @Test
    void closedOwnershipCannotPublishAListener() throws Exception {
        Path profile = tempDir.resolve("profile");
        AppDataLock ownership = AppDataLock.acquire(profile, "first");
        ownership.close();
        assertThrows(IOException.class, () -> AppInstanceActivation.listen(ownership,
                () -> CompletableFuture.completedFuture(null)));
        assertFalse(Files.exists(profile.resolve(".activation")));
    }

    private static long acknowledgementCount(Path profile) throws IOException {
        return signalCount(profile, ".ack");
    }

    private static long requestCount(Path profile) throws IOException {
        return signalCount(profile, ".request");
    }

    private static long signalCount(Path profile, String suffix) throws IOException {
        try (var files = Files.list(profile.resolve(".activation"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        }
    }
}
