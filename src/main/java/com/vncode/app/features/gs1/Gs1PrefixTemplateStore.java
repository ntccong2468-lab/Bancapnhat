package com.vncode.app.features.gs1;

import com.vncode.app.integration.gs1.Gs1MailClient;
import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.shared.AppPaths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/** Private, explicitly imported seller templates; nothing is bundled or shared between enterprises. */
public final class Gs1PrefixTemplateStore {
    private static final String STORE_DIRECTORY = "gs1-prefix-templates";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
    private final Path appDataDirectory;
    private final Path directory;
    private final Gs1WordPrefixService words = new Gs1WordPrefixService();

    public Gs1PrefixTemplateStore() { this(AppPaths.appDataDir()); }

    Gs1PrefixTemplateStore(Path appDataDirectory) {
        this.appDataDirectory = appDataDirectory.toAbsolutePath().normalize();
        this.directory = this.appDataDirectory.resolve(STORE_DIRECTORY);
    }

    public synchronized Optional<byte[]> load(String inn, String environment) throws IOException {
        Path target = target(inn, environment);
        try {
            if (!safeDirectory(false)) return Optional.empty();
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            requireRegularFile(target);
            if (Files.size(target) > Gs1MailClient.MAX_ATTACHMENT) throw invalid();
            byte[] bytes;
            try (var channel = Files.newByteChannel(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 var stream = Channels.newInputStream(channel)) {
                bytes = stream.readNBytes(Gs1MailClient.MAX_ATTACHMENT + 1);
            }
            validate(bytes);
            safeDirectory(false);
            requireRegularFile(target);
            return Optional.of(bytes);
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    /** Validates a copy before replacing an existing template, and never falls back to a non-atomic write. */
    public synchronized void save(String inn, String environment, byte[] template) throws IOException {
        Path target = target(inn, environment);
        if (template == null || template.length < 4 || template.length > Gs1MailClient.MAX_ATTACHMENT) throw invalid();
        byte[] bytes = template.clone();
        Path temporary = null;
        try {
            validate(bytes);
            safeDirectory(true);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) requireRegularFile(target);
            temporary = privateTemporaryFile();
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            safeDirectory(false);
            requireRegularFile(temporary);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) requireRegularFile(target);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private Path target(String inn, String environment) throws IOException {
        try { Gs1Membership.requireInn(inn); }
        catch (RuntimeException invalid) { throw scopeInvalid(); }
        if (!"production".equals(environment) && !"sandbox".equals(environment)) throw scopeInvalid();
        try {
            byte[] scope = (environment + "\u0000" + inn).getBytes(StandardCharsets.US_ASCII);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scope));
            Path target = directory.resolve(environment + "-" + digest + ".docx").normalize();
            if (!target.getParent().equals(directory)) throw scopeInvalid();
            return target;
        } catch (NoSuchAlgorithmException unavailable) { throw invalid(); }
    }

    /** Check each component before creation, including existing ancestors and Windows directory junctions. */
    private boolean safeDirectory(boolean create) throws IOException {
        Path current = directory.getRoot();
        if (current == null) throw invalid();
        for (Path component : directory) {
            current = current.resolve(component);
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return false;
                try {
                    if (current.equals(appDataDirectory) || current.equals(directory)) createPrivateDirectory(current);
                    else Files.createDirectory(current);
                } catch (FileAlreadyExistsException race) { /* Check the concurrently created component below. */ }
            }
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    || !current.toRealPath().equals(current)) throw invalid();
        }
        if (create && Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        }
        return true;
    }

    private static void createPrivateDirectory(Path path) throws IOException {
        if (Files.getFileStore(path.getParent()).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        } else Files.createDirectory(path);
    }

    private Path privateTemporaryFile() throws IOException {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            return Files.createTempFile(directory, ".import-", ".tmp", PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        }
        return Files.createTempFile(directory, ".import-", ".tmp");
    }

    private void requireRegularFile(Path file) throws IOException {
        if (!file.toAbsolutePath().normalize().getParent().equals(directory)
                || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().getParent().equals(directory)) throw invalid();
    }

    private void validate(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes.length > Gs1MailClient.MAX_ATTACHMENT) throw invalid();
        try { words.inspect(bytes); }
        catch (IOException | RuntimeException invalid) { throw invalid(); }
    }

    private static IOException invalid() { return new IOException("GS1_TEMPLATE_STORE_INVALID"); }
    private static IOException scopeInvalid() { return new IOException("GS1_TEMPLATE_STORE_SCOPE_INVALID"); }
}
