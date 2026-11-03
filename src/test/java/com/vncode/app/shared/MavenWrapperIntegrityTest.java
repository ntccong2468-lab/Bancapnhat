package com.vncode.app.shared;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loopback-only synthetic distributions exercise the checked-in wrapper's real HTTP bootstrap. */
class MavenWrapperIntegrityTest {
    @TempDir Path temp;

    @Test void projectPinsTheVerifiedMavenDistribution() throws Exception {
        var values = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(".mvn/wrapper/maven-wrapper.properties"), StandardCharsets.ISO_8859_1)) {
            values.load(reader);
        }
        assertEquals("https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.8.5/apache-maven-3.8.5-bin.zip",
                values.getProperty("distributionUrl"));
        assertEquals("d53e045bc5c02aad179fae2fbc565d953354880db6661a8fab31f3a718d7b62c",
                values.getProperty("distributionSha256Sum"));
    }

    @Test void permitsMatchingDistributionAndRejectsModifiedArchive() throws Exception {
        Path wrapper = Path.of(".mvn", "wrapper", "maven-wrapper.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(wrapper), "The tracked wrapper JAR must be available");
        byte[] original = syntheticDistribution();
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        byte[] altered = java.util.Arrays.copyOf(original, original.length + 1);
        altered[altered.length - 1] = 42; // Still an ordinary ZIP, but not the pinned artifact.
        // The pinned wrapper supports HTTP(S) distributions, as used by this project.
        // Its upstream file-URI handling treats /C:/... as a path on Windows; do not
        // make a distribution-integrity test depend on that unrelated local-file mode.
        var downloads = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = switch (exchange.getRequestURI().getPath()) {
                case "/valid.zip" -> original;
                case "/changed.zip" -> altered;
                default -> null;
            };
            try (exchange) {
                if (body == null) { exchange.sendResponseHeaders(404, -1); return; }
                downloads.incrementAndGet();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            Result accepted = invokeWrapper(wrapper, base.resolve("valid.zip"), digest, "valid");
            assertEquals(0, accepted.exitCode(), accepted.output());
            assertTrue(accepted.output().contains("SYNTHETIC_MAVEN_LAUNCHED"), accepted.output());

            Result rejected = invokeWrapper(wrapper, base.resolve("changed.zip"), digest, "changed");
            assertNotEquals(0, rejected.exitCode(), rejected.output());
            assertTrue(rejected.output().contains("Failed to validate Maven distribution SHA-256"), rejected.output());
            assertFalse(rejected.output().contains("SYNTHETIC_MAVEN_LAUNCHED"), rejected.output());
            assertEquals(2, downloads.get(), "Both archives must actually be downloaded and validated");
        } finally {
            server.stop(0);
        }
    }

    private byte[] syntheticDistribution() throws IOException {
        Path source = temp.resolve("source/org/codehaus/plexus/classworlds/launcher/Launcher.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.codehaus.plexus.classworlds.launcher; "
                + "public class Launcher { public static void main(String[] args) { "
                + "System.out.println(\"SYNTHETIC_MAVEN_LAUNCHED\"); } }", StandardCharsets.UTF_8);
        Path classes = temp.resolve("classes");
        Files.createDirectories(classes);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK is required");
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()));
        byte[] classBytes = Files.readAllBytes(classes.resolve("org/codehaus/plexus/classworlds/launcher/Launcher.class"));
        var jarBytes = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(jarBytes)) {
            jar.putNextEntry(new JarEntry("org/codehaus/plexus/classworlds/launcher/Launcher.class"));
            jar.write(classBytes);
            jar.closeEntry();
        }
        var zipBytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new ZipEntry("synthetic-maven/boot/plexus-classworlds-1.jar"));
            zip.write(jarBytes.toByteArray());
            zip.closeEntry();
        }
        return zipBytes.toByteArray();
    }

    private Result invokeWrapper(Path wrapper, URI distribution, String digest, String name) throws Exception {
        Path project = temp.resolve(name);
        Path properties = project.resolve(".mvn/wrapper/maven-wrapper.properties");
        Files.createDirectories(properties.getParent());
        Path localWrapper = properties.resolveSibling("maven-wrapper.jar");
        Files.copy(wrapper, localWrapper);
        var values = new Properties();
        values.setProperty("distributionUrl", distribution.toString());
        values.setProperty("distributionSha256Sum", digest);
        try (var writer = Files.newBufferedWriter(properties, StandardCharsets.ISO_8859_1)) { values.store(writer, null); }
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        Process child = new ProcessBuilder(java.toString(), "-Dmaven.user.home=" + temp.resolve(name + "-home"),
                "-Dmaven.multiModuleProjectDirectory=" + project, "-cp", localWrapper.toString(),
                "org.apache.maven.wrapper.MavenWrapperMain", "--version")
                .directory(project.toFile()).redirectErrorStream(true).start();
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            fail("Wrapper did not finish within 30 seconds");
        }
        return new Result(child.exitValue(), new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"); }
    private record Result(int exitCode, String output) { }
}
