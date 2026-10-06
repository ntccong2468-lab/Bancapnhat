package com.tuandev.fbsbarcode.integration.znack.signature;

import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;

import java.io.IOException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CryptoProCommandRunner {
    public record Result(int exitCode, byte[] stdout, byte[] stderr) {
        public String stdoutText() {
            return decode(stdout);
        }

        public String diagnostic() {
            return ZnackSanitizer.message(decode(stderr.length == 0 ? stdout : stderr));
        }
    }

    public Result run(List<String> command, Duration timeout) throws CryptoProException {
        try {
            Process process = new ProcessBuilder(new ArrayList<>(command)).redirectInput(ProcessBuilder.Redirect.INHERIT).start();
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new CryptoProException(CryptoProErrorCode.TIMEOUT,
                        "CryptoPro command timed out after " + timeout.toSeconds() + " seconds.");
            }
            return new Result(process.exitValue(), stdout.join(), stderr.join());
        } catch (IOException e) {
            throw new CryptoProException(CryptoProErrorCode.CRYPTOPRO_MISSING, "CryptoPro command could not be started.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CryptoProException(CryptoProErrorCode.CANCELLED, "CryptoPro command was cancelled.", e);
        }
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

    private static byte[] read(java.io.InputStream stream) {
        try { return stream.readAllBytes(); }
        catch (IOException e) { return new byte[0]; }
    }
}
