package com.vncode.app.shared;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline, synthetic distributions exercise the checked-in wrapper's real bootstrap. */
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
        Path valid = temp.resolve("valid.zip");
        Path changed = temp.resolve("changed.zip");
        Files.write(valid, original);
        byte[] altered = java.util.Arrays.copyOf(original, original.length + 1);
        altered[altered.length - 1] = 42; // Still an ordinary ZIP, but not the pinned artifact.
        Files.write(changed, altered);

        Result accepted = invokeWrapper(wrapper, valid, digest, "valid");
        assertEquals(0, accepted.exitCode(), accepted.output());
        assertTrue(accepted.output().contains("SYNTHETIC_MAVEN_LAUNCHED"), accepted.output());

        Result rejected = invokeWrapper(wrapper, changed, digest, "changed");
        assertNotEquals(0, rejected.exitCode(), rejected.output());
        assertTrue(rejected.output().contains("Failed to validate Maven distribution SHA-256"), rejected.output());
        assertFalse(rejected.output().contains("SYNTHETIC_MAVEN_LAUNCHED"), rejected.output());
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

    private Result invokeWrapper(Path wrapper, Path distribution, String digest, String name) throws Exception {
        Path project = temp.resolve(name);
        Path properties = project.resolve(".mvn/wrapper/maven-wrapper.properties");
        Files.createDirectories(properties.getParent());
        Path localWrapper = properties.resolveSibling("maven-wrapper.jar");
        Files.copy(wrapper, localWrapper);
        var values = new Properties();
        values.setProperty("distributionUrl", distribution.toUri().toString());
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
