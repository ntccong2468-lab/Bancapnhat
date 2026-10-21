package com.vncode.app.integration.znack.signature;

import com.vncode.app.integration.znack.ZnackSanitizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class CryptoProCommandRunner {
    // Allows the existing 4 MiB CMS limit in Base64 and the GS1 XML output without truncation.
    static final int MAX_CAPTURE_BYTES = 16 * 1024 * 1024;
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long OUTPUT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long CLEANUP_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    public record Result(int exitCode, byte[] stdout, byte[] stderr) {
        public String stdoutText() {
            return decode(stdout);
        }

        public String diagnostic() {
            return ZnackSanitizer.message(decode(stderr.length == 0 ? stdout : stderr));
        }
    }

    public Result run(List<String> command, Duration timeout) throws CryptoProException {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("CryptoPro timeout must be positive.");
        long budget = timeout.toNanos();
        long started = System.nanoTime();
        Process process = null;
        Set<ProcessHandle> descendants = new LinkedHashSet<>();
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            process = new ProcessBuilder(new ArrayList<>(command)).redirectInput(ProcessBuilder.Redirect.INHERIT).start();
            var stdout = new ByteArrayOutputStream();
            var stderr = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long lastOutput = started;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                rememberDescendants(process, descendants);
                if (System.nanoTime() - started >= budget)
                    throw new CryptoProException(CryptoProErrorCode.TIMEOUT,
                            "CryptoPro command timed out after " + timeout.toSeconds() + " seconds.");
                // No blocking read/EOF join: a shell can exit while a child still holds its pipes.
                int captured = drainAvailable(process.getInputStream(), stdout, buffer)
                        + drainAvailable(process.getErrorStream(), stderr, buffer);
                if (captured > 0) lastOutput = System.nanoTime();
                if (!process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive)) {
                    drainAvailable(process.getInputStream(), stdout, buffer);
                    drainAvailable(process.getErrorStream(), stderr, buffer);
                    if (process.getInputStream().available() > 0 || process.getErrorStream().available() > 0) continue;
                    return new Result(process.exitValue(), stdout.toByteArray(), stderr.toByteArray());
                }
                long remaining = budget - (System.nanoTime() - started);
                // Windows pipes can hold only a few KiB. Sleeping after every successful drain
                // throttles a valid multi-MiB signature (or the overflow check) until it times out.
                // Keep draining active writers; retain bounded passes and the deadline above.
                if (captured > 0) continue;
                if (remaining > 0) {
                    long pause = System.nanoTime() - lastOutput < TimeUnit.MILLISECONDS.toNanos(100)
                            ? OUTPUT_POLL_NANOS : POLL_NANOS;
                    if (process.isAlive()) process.waitFor(Math.min(pause, remaining), TimeUnit.NANOSECONDS);
                    else TimeUnit.NANOSECONDS.sleep(Math.min(pause, remaining));
                }
            }
        } catch (IOException e) {
            throw new CryptoProException(process == null ? CryptoProErrorCode.CRYPTOPRO_MISSING : CryptoProErrorCode.SIGNING_FAILED,
                    process == null ? "CryptoPro command could not be started." : "CryptoPro command output could not be read.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CryptoProException(CryptoProErrorCode.CANCELLED, "CryptoPro command was cancelled.", e);
        } finally {
            if (process != null) cleanup(process, descendants);
        }
    }

    private static int drainAvailable(InputStream stream, ByteArrayOutputStream output, byte[] buffer)
            throws IOException, CryptoProException {
        // Bound each pass so a continuously writing process cannot postpone the deadline check.
        int remaining = 64 * 1024;
        while (remaining > 0) {
            int available = stream.available();
            if (available <= 0) break;
            int count = stream.read(buffer, 0, Math.min(Math.min(buffer.length, available), remaining));
            if (count <= 0) break;
            if (output.size() > MAX_CAPTURE_BYTES - count)
                throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT,
                        "CryptoPro command output exceeded the 16 MiB capture limit.");
            output.write(buffer, 0, count);
            remaining -= count;
        }
        return 64 * 1024 - remaining;
    }

    private static void rememberDescendants(Process process, Set<ProcessHandle> descendants) {
        // ProcessHandle is best effort, not a Windows Job Object: remember children before a shell exits.
        try (var children = process.descendants()) { children.forEach(descendants::add); }
        catch (SecurityException | UnsupportedOperationException ignored) { }
    }

    private static void cleanup(Process process, Set<ProcessHandle> descendants) {
        boolean interrupted = Thread.interrupted();
        long started = System.nanoTime();
        try {
            rememberDescendants(process, descendants);
            // Keep the parent alive briefly to reap children before stopping the parent itself.
            descendants.forEach(CryptoProCommandRunner::destroy);
            while (process.isAlive() && descendants.stream().anyMatch(ProcessHandle::isAlive)
                    && System.nanoTime() - started < CLEANUP_NANOS / 2)
                LockSupport.parkNanos(POLL_NANOS);
            rememberDescendants(process, descendants);
            destroy(process.toHandle());
            descendants.forEach(CryptoProCommandRunner::destroy);
            while ((process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
                    && System.nanoTime() - started < CLEANUP_NANOS)
                LockSupport.parkNanos(POLL_NANOS);
        } finally {
            close(process.getInputStream());
            close(process.getErrorStream());
            close(process.getOutputStream());
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void destroy(ProcessHandle process) {
        try { if (process.isAlive()) process.destroyForcibly(); }
        catch (SecurityException | UnsupportedOperationException ignored) { }
    }

    private static void close(java.io.Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }

    public String resolve(String override, String command) throws CryptoProException {
        if (override != null && !override.isBlank()) {
            Path configured = Path.of(override.trim());
            if (runnable(configured)) return configured.toString();
            if (configured.getParent() == null) {
                for (Path candidate : candidates(configured.toString())) {
                    if (runnable(candidate)) return candidate.toString();
                }
            }
            throw new CryptoProException(missingCode(command),
                    "Configured CryptoPro command is not executable: " + override.trim());
        }
        for (Path candidate : candidates(command)) {
            if (runnable(candidate)) return candidate.toString();
        }
        throw new CryptoProException(missingCode(command), "CryptoPro command not found: " + command);
    }

    List<Path> candidates(String command) {
        return candidates(command, isWindows(), System.getenv());
    }

    List<Path> candidates(String command, boolean windows, Map<String, String> environment) {
        Set<Path> result = new LinkedHashSet<>();
        List<String> executables = executableNames(command, windows);
        String path = environment.get("PATH");
        if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                for (String executable : executables) result.add(Path.of(entry, executable));
            }
        }
        if (windows) {
            for (String root : windowsCryptoProRoots(environment)) {
                for (String executable : executables) result.add(Path.of(root, executable));
            }
        } else {
            for (String root : List.of("/opt/cprocsp/bin", "/opt/cprocsp/sbin",
                    "/opt/cprocsp/bin/amd64", "/opt/cprocsp/bin/aarch64", "/opt/cprocsp/bin/arm64",
                    "/opt/cprocsp/bin/ia32", "/opt/cprocsp/sbin/amd64", "/opt/cprocsp/sbin/aarch64",
                    "/opt/cprocsp/sbin/arm64", "/Applications/CryptoPro/CSP/bin")) {
                for (String executable : executables) result.add(Path.of(root, executable));
            }
        }
        return List.copyOf(result);
    }

    private List<String> executableNames(String command, boolean windows) {
        if (!windows || command.toLowerCase(Locale.ROOT).endsWith(".exe")) return List.of(command);
        if ("cryptcp".equalsIgnoreCase(command)) {
            return List.of("cryptcp.exe", "cryptcp.x64.exe", "cryptcp.x86.exe");
        }
        return List.of(command + ".exe");
    }

    private List<String> windowsCryptoProRoots(Map<String, String> environment) {
        Set<String> roots = new LinkedHashSet<>();
        addWindowsRoot(roots, environment.get("ProgramFiles"));
        addWindowsRoot(roots, environment.get("ProgramFiles(x86)"));
        addWindowsRoot(roots, environment.get("ProgramW6432"));
        roots.add("C:\\Program Files\\Crypto Pro\\CSP");
        roots.add("C:\\Program Files (x86)\\Crypto Pro\\CSP");
        return List.copyOf(roots);
    }

    private void addWindowsRoot(Set<String> roots, String programFiles) {
        if (programFiles != null && !programFiles.isBlank()) {
            roots.add(Path.of(programFiles, "Crypto Pro", "CSP").toString());
        }
    }

    private boolean runnable(Path path) {
        return Files.isRegularFile(path) && (isWindows() || Files.isExecutable(path));
    }

    private CryptoProErrorCode missingCode(String command) {
        return switch (command.toLowerCase(Locale.ROOT).replace(".exe", "")) {
            case "cryptcp", "cryptcp.x64", "cryptcp.x86" -> CryptoProErrorCode.CRYPTCP_MISSING;
            case "certmgr" -> CryptoProErrorCode.CERTMGR_MISSING;
            default -> CryptoProErrorCode.CRYPTOPRO_MISSING;
        };
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String decode(byte[] value) {
        String utf8 = new String(value, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) return utf8;
        // certmgr/cryptcp/csptest are console apps: on Russian Windows their Cyrillic output uses the
        // OEM codepage (cp866), not cp1251. Both single-byte codepages decode every byte, so pick the
        // one that yields the most valid Cyrillic letters \u2014 that is the correct decoding of the labels.
        String best = utf8;
        int bestScore = cyrillicCount(utf8);
        for (String charsetName : List.of("IBM866", "windows-1251")) {
            try {
                String decoded = new String(value, Charset.forName(charsetName));
                int score = cyrillicCount(decoded);
                if (score > bestScore) {
                    bestScore = score;
                    best = decoded;
                }
            } catch (Exception ignored) {}
        }
        return best;
    }

    private static int cyrillicCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u0410' && c <= '\u044F') count++;
        }
        return count;
    }

}
