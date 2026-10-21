package com.vncode.app.integration.znack.signature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CryptoProCommandRunnerTest {
    @TempDir Path temporary;
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

    @Test @Timeout(15) void returnsTheRealExitCodeAndBothOutputStreams() throws Exception {
        var result = new CryptoProCommandRunner().run(helper("output"), Duration.ofSeconds(10));

        assertEquals(7, result.exitCode());
        assertArrayEquals("payload-данные".getBytes(StandardCharsets.UTF_8), result.stdout());
        assertArrayEquals("diagnostic".getBytes(StandardCharsets.UTF_8), result.stderr());
    }

    @Test @Timeout(25) void capturesLargeValidBase64SizedOutputWithoutTruncatingEitherStream() throws Exception {
        int size = 6 * 1024 * 1024 + 17; // More than Base64 encoding the existing maximum 4 MiB CMS.
        var result = new CryptoProCommandRunner().run(helper("large", Integer.toString(size)), Duration.ofSeconds(20));

        assertEquals(0, result.exitCode());
        byte[] expected = new byte[size];
        Arrays.fill(expected, (byte) 'A');
        assertArrayEquals(expected, result.stdout());
        expected = new byte[96 * 1024 + 3];
        Arrays.fill(expected, (byte) 'B');
        assertArrayEquals(expected, result.stderr());
    }

    @Test @Timeout(25) void rejectsOversizedOutputExplicitlyAndStopsTheWriter() throws Exception {
        Path pid = temporary.resolve("oversized.pid");
        try {
            var error = assertThrows(CryptoProException.class, () -> new CryptoProCommandRunner().run(
                    helper("oversized", pid.toString()), Duration.ofSeconds(20)));
            assertEquals(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT, error.code());
            assertTrue(error.getMessage().contains("capture limit"));
            assertStopped(pid);
        } finally { stop(pid); }
    }

    @Test @Timeout(15) void timeoutStopsTheParentAndItsObservedChild() throws Exception {
        Path parent = temporary.resolve("timeout-parent.pid"), child = temporary.resolve("timeout-child.pid");
        long started = System.nanoTime();
        try {
            var error = assertThrows(CryptoProException.class, () -> new CryptoProCommandRunner().run(
                    helper("tree", parent.toString(), child.toString()), Duration.ofSeconds(4)));
            assertEquals(CryptoProErrorCode.TIMEOUT, error.code());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(7)) < 0);
            assertStopped(parent);
            assertStopped(child);
        } finally { stop(child); stop(parent); }
    }

    @Test @Timeout(15) void anExitedParentCannotLeaveItsChildHoldingTheOutputPastTheDeadline() throws Exception {
        Path parent = temporary.resolve("pipe-parent.pid"), child = temporary.resolve("pipe-child.pid");
        long started = System.nanoTime();
        try {
            var error = assertThrows(CryptoProException.class, () -> new CryptoProCommandRunner().run(
                    helper("orphan", parent.toString(), child.toString()), Duration.ofSeconds(4)));
            assertEquals(CryptoProErrorCode.TIMEOUT, error.code());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(7)) < 0);
            assertStopped(parent);
            assertStopped(child);
        } finally { stop(child); stop(parent); }
    }

    @Test @Timeout(15) void interruptionStopsTheOwnedProcessTreeAndPreservesCancellation() throws Exception {
        Path parent = temporary.resolve("cancel-parent.pid"), child = temporary.resolve("cancel-child.pid");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                new CryptoProCommandRunner().run(helper("tree", parent.toString(), child.toString()), Duration.ofSeconds(30));
            } catch (Throwable error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            awaitFile(parent);
            awaitFile(child);
            // Give the runner's bounded poll one turn to observe the freshly launched child.
            Thread.sleep(100);
            worker.interrupt();
            worker.join(4000);
            assertFalse(worker.isAlive(), "Cancellation must finish without waiting for the signing timeout");
            var error = assertInstanceOf(CryptoProException.class, failure.get());
            assertEquals(CryptoProErrorCode.CANCELLED, error.code());
            assertInstanceOf(InterruptedException.class, error.getCause());
            assertTrue(interrupted.get());
            assertStopped(parent);
            assertStopped(child);
        } finally {
            worker.interrupt();
            stop(child);
            stop(parent);
            worker.join(2000);
        }
    }

    @Test void anAlreadyInterruptedCallerDoesNotLaunchAnotherProcess() throws Exception {
        Path pid = temporary.resolve("not-started.pid");
        Thread.currentThread().interrupt();
        try {
            var error = assertThrows(CryptoProException.class, () -> new CryptoProCommandRunner().run(
                    helper("sleep", pid.toString()), Duration.ofSeconds(2)));
            assertEquals(CryptoProErrorCode.CANCELLED, error.code());
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(pid));
        } finally { Thread.interrupted(); }
    }

    private static List<String> helper(String... arguments) {
        var command = new ArrayList<>(List.of(javaExecutable(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ProcessFixture.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!Files.isRegularFile(file) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.isRegularFile(file), "The local helper must actually start: " + file.getFileName());
    }

    private static void assertStopped(Path pidFile) throws Exception {
        assertTrue(Files.isRegularFile(pidFile), "Process fixture was not started");
        long pid = Long.parseLong(Files.readString(pidFile));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (running(pid) && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(running(pid), "Owned local helper is still running: " + pid);
    }

    private static boolean running(long pid) {
        // A Unix orphan can remain a zombie until the container's PID 1 reaps it; it cannot sign or hold pipes.
        Path state = Path.of("/proc", Long.toString(pid), "stat");
        if (Files.isRegularFile(state)) {
            try {
                String status = Files.readString(state);
                if (status.substring(status.lastIndexOf(')') + 2).startsWith("Z")) return false;
            } catch (Exception ignored) { }
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void stop(Path pidFile) {
        try {
            if (Files.isRegularFile(pidFile)) ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
                    .ifPresent(ProcessHandle::destroyForcibly);
        } catch (Exception ignored) { }
    }

    /** A local Java process fixture, never an external signer, token, API or legal document. */
    public static final class ProcessFixture {
        public static void main(String[] arguments) throws Exception {
            switch (arguments[0]) {
                case "output" -> {
                    System.out.write("payload-данные".getBytes(StandardCharsets.UTF_8));
                    System.err.write("diagnostic".getBytes(StandardCharsets.UTF_8));
                    System.exit(7);
                }
                case "large" -> {
                    write(System.out, Integer.parseInt(arguments[1]), (byte) 'A');
                    write(System.err, 96 * 1024 + 3, (byte) 'B');
                }
                case "oversized" -> {
                    Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
                    write(System.out, CryptoProCommandRunner.MAX_CAPTURE_BYTES + 8192, (byte) 'A');
                    Thread.sleep(60_000);
                }
                case "sleep" -> {
                    Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
                    System.out.print("child ready");
                    System.out.flush();
                    Thread.sleep(60_000);
                }
                case "tree", "orphan" -> {
                    Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
                    new ProcessBuilder(helper("sleep", arguments[2])).inheritIO().start();
                    awaitFile(Path.of(arguments[2]));
                    // Keep the parent present long enough to observe and bind its child before exit.
                    Thread.sleep(750);
                    if (arguments[0].equals("tree")) Thread.sleep(60_000);
                }
                default -> throw new IllegalArgumentException("Unknown local process fixture");
            }
        }

        private static void write(java.io.OutputStream output, int count, byte value) throws Exception {
            byte[] buffer = new byte[8192];
            Arrays.fill(buffer, value);
            while (count > 0) {
                int size = Math.min(count, buffer.length);
                output.write(buffer, 0, size);
                count -= size;
            }
            output.flush();
        }
    }
}
