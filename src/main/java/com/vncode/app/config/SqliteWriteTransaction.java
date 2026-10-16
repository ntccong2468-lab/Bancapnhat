package com.vncode.app.config;

import java.sql.Connection;
import java.sql.SQLException;

/** Short local writes only: never put an HTTP call, signature or printing inside this callback. */
public final class SqliteWriteTransaction {
    private SqliteWriteTransaction() {}

    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws SQLException; }

    public static <T> T run(Work<T> work) throws SQLException {
        return run(work, 5000, 3);
    }

    static <T> T run(Work<T> work, int busyTimeoutMillis, int attempts) throws SQLException {
        if (busyTimeoutMillis < 0 || attempts < 1) throw new IllegalArgumentException("Invalid write wait policy");
        for (int attempt = 1; ; attempt++) {
            boolean committed = false;
            try (var connection = Database.getConnection(); var transaction = connection.createStatement()) {
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
            } catch (SQLException | RuntimeException failure) {
                // Close and roll back before retrying. Never replay a committed transaction,
                // constraint/owner failures, or an external marketplace operation.
                if (committed || !isBusy(failure) || attempt >= attempts || Thread.currentThread().isInterrupted())
                    throw failure;
            }
        }
    }

    public static boolean isBusy(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            // Includes SQLite extended BUSY_SNAPSHOT and BUSY_RECOVERY codes.
            if (failure instanceof SQLException sql && (sql.getErrorCode() & 0xff) == 5) return true;
        }
        return false;
    }
}
