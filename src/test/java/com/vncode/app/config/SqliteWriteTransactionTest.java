package com.vncode.app.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SqliteWriteTransactionTest {
    @TempDir Path directory;
    @BeforeEach void init() {
        System.setProperty("vncode.appdata.dir", directory.toString()); Database.initDatabase();
    }
    @AfterEach void clear() { System.clearProperty("vncode.appdata.dir"); }

    @Test void constraintFailureRollsBackTheWholeWriteAndIsNotRetried() throws Exception {
        var calls = new AtomicInteger();
        var original = new SQLIntegrityConstraintViolationException("synthetic constraint", "", 19);
        var failed = assertThrows(SQLIntegrityConstraintViolationException.class, () -> SqliteWriteTransaction.run(c -> {
            calls.incrementAndGet();
            try (var s = c.createStatement()) { s.executeUpdate("INSERT INTO app_config(key,value) VALUES('synthetic','rollback')"); }
            throw original;
        }));
        assertSame(original, failed); assertEquals(1, calls.get());
        try (var c = Database.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM app_config WHERE key='synthetic'")) {
            assertTrue(r.next()); assertEquals(0, r.getInt(1));
        }
    }

    @Test void aPersistentWriterLockHasABoundedWaitAndDoesNotRunTheCallback() throws Exception {
        var calls = new AtomicInteger();
        try (var writer = Database.getConnection(); var tx = writer.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                assertTimeout(Duration.ofSeconds(3), () -> {
                    var failure = assertThrows(SQLException.class, () -> SqliteWriteTransaction.run(c -> {
                        calls.incrementAndGet(); return null;
                    }, 40, 2));
                    assertTrue(SqliteWriteTransaction.isBusy(failure));
                });
                assertEquals(0, calls.get());
            } finally { tx.execute("ROLLBACK"); }
        }
        int result = SqliteWriteTransaction.run(c -> 42);
        assertEquals(42, result);
    }

    @Test void retriesAnExpiredBusyWaitOnAFreshConnectionAndCommitsExactlyOnce() throws Exception {
        var calls = new AtomicInteger(); var entered = new CountDownLatch(1);
        try (var writer = Database.getConnection(); var tx = writer.createStatement();
             var executor = Executors.newSingleThreadExecutor()) {
            tx.execute("BEGIN IMMEDIATE");
            var future = executor.submit(() -> {
                entered.countDown();
                return SqliteWriteTransaction.run(c -> {
                    calls.incrementAndGet();
                    try (var s = c.createStatement()) { s.executeUpdate("INSERT INTO app_config(key,value) VALUES('synthetic','committed')"); }
                    return "committed";
                }, 100, 50);
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> future.get(250, TimeUnit.MILLISECONDS));
                assertEquals(0, calls.get());
            } finally { tx.execute("ROLLBACK"); }
            assertEquals("committed", future.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, calls.get());
        try (var c = Database.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT value FROM app_config WHERE key='synthetic'")) {
            assertTrue(r.next()); assertEquals("committed", r.getString(1)); assertFalse(r.next());
        }
    }
}
