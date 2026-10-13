package com.vncode.app.features.gs1;

import com.vncode.app.integration.gs1.Gs1MailClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Gs1PrefixTemplateStoreTest {
    @TempDir Path directory;
    private static final String INN = "7707083893";
    private static final String OTHER_INN = "7811089030";

    @Test void storesOnlyExplicitImportsAndSeparatesEnterpriseAndEnvironment() throws Exception {
        var store = new Gs1PrefixTemplateStore(directory);
        assertTrue(store.load(INN, "production").isEmpty());
        assertFalse(Files.exists(directory.resolve("gs1-prefix-templates")), "Reading does not create an import store");
        byte[] first = template("First fixture");
        store.save(INN, "production", first);
        assertArrayEquals(first, store.load(INN, "production").orElseThrow());
        assertTrue(store.load(OTHER_INN, "production").isEmpty());
        assertTrue(store.load(INN, "sandbox").isEmpty());
        byte[] second = template("Other fixture"), sandbox = template("Sandbox fixture");
        store.save(OTHER_INN, "production", second);
        store.save(INN, "sandbox", sandbox);
        assertArrayEquals(first, store.load(INN, "production").orElseThrow());
        assertArrayEquals(second, store.load(OTHER_INN, "production").orElseThrow());
        assertArrayEquals(sandbox, store.load(INN, "sandbox").orElseThrow());
        try (var files = Files.list(directory.resolve("gs1-prefix-templates"))) {
            assertTrue(files.allMatch(path -> !path.getFileName().toString().contains(INN)
                    && !path.getFileName().toString().contains(OTHER_INN)));
        }
    }

    @Test void validatesBeforeAtomicReplacementAndCleansTemporaryFiles() throws Exception {
        var store = new Gs1PrefixTemplateStore(directory);
        byte[] original = template("Original fixture"), updated = template("Updated fixture");
        store.save(INN, "production", original);
        assertThrows(IOException.class, () -> store.save(INN, "production", new byte[] {'P', 'K', 0, 0}));
        assertArrayEquals(original, store.load(INN, "production").orElseThrow());
        assertThrows(IOException.class, () -> store.save(INN, "production", new byte[Gs1MailClient.MAX_ATTACHMENT + 1]));
        assertArrayEquals(original, store.load(INN, "production").orElseThrow());
        store.save(INN, "production", updated);
        assertArrayEquals(updated, store.load(INN, "production").orElseThrow());
        try (var files = Files.list(directory.resolve("gs1-prefix-templates"))) { assertEquals(1, files.count()); }
    }

    @Test void defaultConstructorUsesTheConfiguredAppDataDirectory() throws Exception {
        String previous = System.getProperty("vncode.appdata.dir");
        try {
            System.setProperty("vncode.appdata.dir", directory.toString());
            byte[] data = template("Default path fixture");
            new Gs1PrefixTemplateStore().save(INN, "production", data);
            assertArrayEquals(data, new Gs1PrefixTemplateStore(directory).load(INN, "production").orElseThrow());
        } finally {
            if (previous == null) System.clearProperty("vncode.appdata.dir");
            else System.setProperty("vncode.appdata.dir", previous);
        }
    }

    @Test void rejectsInvalidOrUnsupportedScopeWithoutWritingOrLeakingIt() throws Exception {
        var store = new Gs1PrefixTemplateStore(directory);
        byte[] data = template("Fixture");
        for (String inn : new String[] {"../private", "7707083893/other", "", "12345"}) {
            IOException failure = assertThrows(IOException.class, () -> store.save(inn, "production", data));
            assertEquals("GS1_TEMPLATE_STORE_SCOPE_INVALID", failure.getMessage());
            assertNull(failure.getCause());
            assertThrows(IOException.class, () -> store.load(inn, "production"));
        }
        for (String environment : new String[] {"unsupported", "../private", "Production", ""}) {
            assertThrows(IOException.class, () -> store.save(INN, environment, data));
            assertThrows(IOException.class, () -> store.load(INN, environment));
        }
        assertThrows(IOException.class, () -> store.save(null, "production", data));
        assertThrows(IOException.class, () -> store.load(INN, null));
        assertFalse(Files.exists(directory.resolve("gs1-prefix-templates")));
    }

    @Test void revalidatesStoredTemplatesAndBoundsReads() throws Exception {
        var store = new Gs1PrefixTemplateStore(directory);
        store.save(INN, "production", template("Fixture"));
        Path file = onlyStoredFile();
        Files.writeString(file, "Corrupted seller template", StandardCharsets.UTF_8);
        IOException invalid = assertThrows(IOException.class, () -> store.load(INN, "production"));
        assertEquals("GS1_TEMPLATE_STORE_INVALID", invalid.getMessage());
        assertNull(invalid.getCause());
        Files.write(file, new byte[Gs1MailClient.MAX_ATTACHMENT + 1]);
        assertThrows(IOException.class, () -> store.load(INN, "production"));
        byte[] replacement = template("Re-imported fixture");
        store.save(INN, "production", replacement);
        assertArrayEquals(replacement, store.load(INN, "production").orElseThrow());
    }

    @Test void privatePermissionsAreAppliedWhereSupported() throws Exception {
        var store = new Gs1PrefixTemplateStore(directory);
        store.save(INN, "production", template("Fixture"));
        Path file = onlyStoredFile();
        assumeTrue(Files.getFileAttributeView(file, PosixFileAttributeView.class) != null);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(file.getParent()));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }

    @Test void refusesSymlinkedFilesAndLeavesTheirTargetUntouched() throws Exception {
        var store = new Gs1PrefixTemplateStore(directory);
        byte[] data = template("Fixture");
        store.save(INN, "production", data);
        Path file = onlyStoredFile(), outside = directory.resolve("external.docx");
        Files.write(outside, data);
        Files.delete(file);
        createSymlink(file, outside);
        assertThrows(IOException.class, () -> store.load(INN, "production"));
        assertThrows(IOException.class, () -> store.save(INN, "production", template("Replacement fixture")));
        assertArrayEquals(data, Files.readAllBytes(outside));
        assertTrue(Files.isSymbolicLink(file));
    }

    @Test void refusesSymlinkedStorageDirectoryAndAncestors() throws Exception {
        Path external = Files.createDirectory(directory.resolve("external"));
        Path storageLink = directory.resolve("gs1-prefix-templates");
        createSymlink(storageLink, external);
        var store = new Gs1PrefixTemplateStore(directory);
        assertThrows(IOException.class, () -> store.load(INN, "production"));
        assertThrows(IOException.class, () -> store.save(INN, "production", template("Fixture")));
        Files.delete(storageLink);
        Path ancestor = directory.resolve("linked-app-data");
        createSymlink(ancestor, external);
        var nested = new Gs1PrefixTemplateStore(ancestor.resolve("child"));
        assertThrows(IOException.class, () -> nested.load(INN, "production"));
        assertThrows(IOException.class, () -> nested.save(INN, "production", template("Fixture")));
        try (var files = Files.list(external)) { assertEquals(0, files.count()); }
    }

    private Path onlyStoredFile() throws IOException {
        try (var files = Files.list(directory.resolve("gs1-prefix-templates"))) { return files.findFirst().orElseThrow(); }
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) { assumeTrue(false, "Symbolic links are not available"); }
    }

    private static byte[] template(String marker) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            part(zip, "[Content_Types].xml", """
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            part(zip, "_rels/.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """);
            part(zip, "word/document.xml", """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                    <w:p><w:r><w:t>%s ${companyName} (ИНН ${inn}, номер предприятия в ЮНИСКАН/ГС1 РУС: ${gs1Codes}) просит дополнительный префикс.</w:t></w:r></w:p>
                    <w:p><w:r><w:t>${date}</w:t></w:r></w:p>
                    <w:p><w:r><w:t>${signerName}     ____________________</w:t></w:r></w:p>
                    </w:body></w:document>
                    """.formatted(marker));
        }
        return output.toByteArray();
    }

    private static void part(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
