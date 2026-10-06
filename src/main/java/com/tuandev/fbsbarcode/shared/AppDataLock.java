package com.tuandev.fbsbarcode.shared;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/** Holds the cross-entry-point ownership lock for one WCode app-data directory. */
public final class AppDataLock implements AutoCloseable {
    private static final String LOCK_FILE_NAME = ".wcode.lock";

    private final FileChannel channel;
    private final FileLock lock;
    private final Path appDataDir;
    private boolean closed;

    private AppDataLock(FileChannel channel, FileLock lock, Path appDataDir) {
        this.channel = channel;
        this.lock = lock;
        this.appDataDir = appDataDir;
    }

    public static AppDataLock acquire(Path appDataDir, String owner) throws IOException {
        Objects.requireNonNull(appDataDir, "appDataDir");
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }

        Path normalizedDir = appDataDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDir);
        Path lockFile = normalizedDir.resolve(LOCK_FILE_NAME);
        FileChannel channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException exception) {
            channel.close();
            throw new AlreadyRunningException(normalizedDir, exception);
        } catch (IOException exception) {
            channel.close();
            throw exception;
        }

        if (lock == null) {
            channel.close();
            throw new AlreadyRunningException(normalizedDir, null);
        }

        try {
            byte[] metadata = ("owner=" + owner + System.lineSeparator()
                            + "pid=" + ProcessHandle.current().pid() + System.lineSeparator()
                            + "acquiredAt=" + Instant.now() + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
            channel.truncate(0);
            channel.position(0);
            channel.write(ByteBuffer.wrap(metadata));
            channel.force(true);
            return new AppDataLock(channel, lock, normalizedDir);
        } catch (IOException exception) {
            lock.release();
            channel.close();
            throw exception;
        }
    }

    synchronized Path requireOwnedAppDataDir() throws IOException {
        if (closed || !lock.isValid()) {
            throw new IOException("The WCode app-data ownership lock is not active.");
        }
        return appDataDir;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }

    public static final class AlreadyRunningException extends IOException {
        private AlreadyRunningException(Path appDataDir, Throwable cause) {
            super("WCode app-data is already owned by another process: " + appDataDir, cause);
        }
    }
}
