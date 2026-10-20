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

    @Test void queuedWritesReuseOneConnectionWithoutHoldingReadersOrWeakeningDurability() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var firstConnection = new java.util.concurrent.atomic.AtomicReference<Connection>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> SqliteWriteTransaction.run(c -> {
                firstConnection.set(c);
                try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA synchronous")) {
                    assertEquals(2, r.getInt(1), "Critical journals must retain FULL durability");
                }
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException(e); }
                return 1;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var waiting = new CountDownLatch(1);
            var second = executor.submit(() -> {
                waiting.countDown();
                return SqliteWriteTransaction.run(c -> {
                    assertSame(firstConnection.get(), c);
                    try (var s = c.createStatement()) { s.executeUpdate("INSERT INTO app_config(key,value) VALUES('queued','saved')"); }
                    return 2;
                });
            });
            try {
                assertTrue(waiting.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
                try (var c = Database.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM app_config")) {
                    assertTrue(r.next(), "WAL readers must continue while writes are queued");
                }
            } finally { release.countDown(); }
            assertEquals(1, first.get(5, TimeUnit.SECONDS)); assertEquals(2, second.get(5, TimeUnit.SECONDS));
        }
        assertTrue(firstConnection.get().isClosed(), "No global connection may pin Windows database files after a batch");
    }

    @Test void aQueuedWriteTimesOutWithoutExecutingAndDoesNotCorruptTheActiveWriter() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var active = executor.submit(() -> SqliteWriteTransaction.run(c -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException(e); }
                return 3;
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var failure = assertThrows(SQLException.class, () -> SqliteWriteTransaction.run(c -> { calls.incrementAndGet(); return 0; }, 20, 2));
                assertTrue(SqliteWriteTransaction.isBusy(failure)); assertEquals(0, calls.get());
            } finally { release.countDown(); }
            assertEquals(3, active.get(5, TimeUnit.SECONDS));
        }
        int next = SqliteWriteTransaction.run(c -> 4); assertEquals(4, next);
    }

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
