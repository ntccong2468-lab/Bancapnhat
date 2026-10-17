package com.vncode.app.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import com.vncode.app.shared.AppPaths;
import com.vncode.app.features.sync.SyncDataScope;

/** Short local writes only: never put an HTTP call, signature or printing inside this callback. */
public final class SqliteWriteTransaction {
    private static final ConcurrentHashMap<Path,Writer> WRITERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> IN_TRANSACTION = ThreadLocal.withInitial(() -> false);
    private static final class Writer {
        final ReentrantLock lock = new ReentrantLock(true);
        int users; // Only changed under WRITERS.compute for this path.
        Connection connection; // Only used under lock; released after the last queued caller.
    }
    private SqliteWriteTransaction() {}

    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws SQLException; }

    public static <T> T run(Work<T> work) throws SQLException {
        return run(work, 5000, 3);
    }

    static <T> T run(Work<T> work, int busyTimeoutMillis, int attempts) throws SQLException {
        if (busyTimeoutMillis < 0 || attempts < 1) throw new IllegalArgumentException("Invalid write wait policy");
        if (IN_TRANSACTION.get()) throw new IllegalStateException("SYNC_NESTED_WRITE");
        SyncDataScope.requireCurrent();
        Path path = AppPaths.appDataDir().toAbsolutePath().normalize();
        Writer writer = WRITERS.compute(path, (key, existing) -> {
            var selected = existing == null ? new Writer() : existing;
            selected.users++; return selected;
        });
        boolean acquired = false;
        Throwable primary = null;
        try {
            try {
                acquired = writer.lock.tryLock((long)Math.max(1, busyTimeoutMillis) * attempts, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("SYNC_WRITE_INTERRUPTED", "57014", 9, interrupted);
            }
            if (!acquired) throw new SQLException("SYNC_WRITE_BUSY", "", 5);
            SyncDataScope.requireCurrent();
            if (!path.equals(AppPaths.appDataDir().toAbsolutePath().normalize()))
                throw new IllegalStateException("SYNC_DATA_DIRECTORY_CHANGED");
            IN_TRANSACTION.set(true);
            return execute(writer, work, busyTimeoutMillis, attempts);
        } catch (SQLException | RuntimeException | Error failure) {
            primary = failure; throw failure;
        } finally {
            if (acquired) IN_TRANSACTION.remove();
            var last = new java.util.concurrent.atomic.AtomicBoolean();
            WRITERS.compute(path, (key, current) -> {
                if (current != writer) throw new IllegalStateException("SYNC_WRITE_QUEUE_CHANGED");
                if (--writer.users == 0) { last.set(true); return null; }
                return writer;
            });
            try {
                if (last.get()) close(writer);
            } catch (SQLException closeFailure) {
                if (primary != null) primary.addSuppressed(closeFailure); else throw closeFailure;
            } finally {
                if (acquired) writer.lock.unlock();
            }
        }
    }

    private static <T> T execute(Writer writer, Work<T> work, int busyTimeoutMillis, int attempts) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            boolean committed = false;
            if (Thread.currentThread().isInterrupted()) throw new SQLException("SYNC_WRITE_INTERRUPTED", "57014", 9);
            if (writer.connection == null) writer.connection = Database.getConnection();
            var connection = writer.connection;
            try (var transaction = connection.createStatement()) {
                transaction.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
                // Reserve the writer before any owner/snapshot reads. DEFERRED upgrades in WAL
                // can return BUSY immediately, regardless of the configured busy_timeout.
                transaction.execute("BEGIN IMMEDIATE");
                try {
                    T result = work.run(connection);
                    transaction.execute("COMMIT");
                    committed = true;
                    return result;
                } catch (SQLException | RuntimeException | Error failure) {
                    try { transaction.execute("ROLLBACK"); }
                    catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            } catch (SQLException | RuntimeException | Error failure) {
                // Close and roll back before retrying. Never replay a committed transaction,
                // constraint/owner failures, or an external marketplace operation.
                try { close(writer); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
                if (failure instanceof Error || committed || !isBusy(failure) || attempt >= attempts || Thread.currentThread().isInterrupted())
                    throw failure;
            }
        }
    }

    private static void close(Writer writer) throws SQLException {
        var connection = writer.connection; writer.connection = null;
        if (connection != null) connection.close();
    }

    public static boolean isBusy(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            // Includes SQLite extended BUSY_SNAPSHOT and BUSY_RECOVERY codes.
            if (failure instanceof SQLException sql && (sql.getErrorCode() & 0xff) == 5) return true;
        }
        return false;
    }
}
