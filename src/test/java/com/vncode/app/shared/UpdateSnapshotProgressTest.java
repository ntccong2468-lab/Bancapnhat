package com.vncode.app.shared;

import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the native backup against actual concurrent WAL commits, without timing assertions. */
class UpdateSnapshotProgressTest {
    @TempDir Path data;

    @Test void walWritesDuringCopyCannotRestartTheUpdateRollbackOrChangeItsFrozenReadSnapshot() throws Exception {
        Path database=data.resolve("database.db");
        try(var live=DriverManager.getConnection("jdbc:sqlite:"+database);
            var writer=live.createStatement();
            var owner=AppDataLock.acquire(data,"snapshot-progress")) {
            createFixture(live,writer);
            var service=new LocalDataSnapshotService();
            var copying=new ArrayList<LocalDataSnapshotService.Progress>();
            var writes=new AtomicInteger();

            var snapshot=service.create(owner,"1.5.3",6,"signed-update-install",progress->{
                if(!"COPYING_DATA".equals(progress.phase())||progress.total()<=0||progress.completed()<=0)return;
                copying.add(progress);
                // The observer runs after sqlite3_backup_step. A different connection
                // commits while the backup still has many remaining pages to copy.
                if(writes.get()<3) {
                    try {
                        writer.executeUpdate("UPDATE marker SET current_value=current_value+1,paired_value=paired_value+1");
                        writes.incrementAndGet();
                    } catch(SQLException failure) {
                        throw new AssertionError("WAL writes must continue while the update rollback is read",failure);
                    }
                }
            });

            assertTrue(Files.size(database)>=16L*1024*1024,"Exercise multiple real native backup steps");
            assertTrue(copying.size()>3,"The large rollback must report incremental native copy progress");
            assertEquals(3,writes.get(),"Do not pause or silently reject ordinary committed WAL writes");
            long total=copying.getFirst().total(),prior=0;
            for(var progress:copying) {
                assertEquals(total,progress.total(),"One frozen read transaction has one stable source page count");
                assertTrue(progress.completed()<=total);
                assertTrue(progress.completed()>prior||prior==total,
                        "Another connection's WAL commit must not reset or stall copied pages");
                prior=progress.completed();
            }
            assertEquals(total,copying.getLast().completed());
            long firstStep=copying.getFirst().completed();
            long maximumCallbacks=(total+firstStep-1)/firstStep+2;
            assertTrue(copying.size()<=maximumCallbacks,"Backup must finish in its page count, not restart for each WAL write");

            assertMarker(snapshot.database(),0);
            assertMarker(database,3);
            assertTrue(service.verify(snapshot),"Checksum and full SQLite integrity verification remain mandatory");
            assertThrows(AppDataLock.AlreadyRunningException.class,()->AppDataLock.acquire(data,"second-writer"));
        }
    }

    @Test void cancellationDuringNativeCopyRemovesTheIncompleteRollbackAndKeepsLiveDataAndOwnership() throws Exception {
        Path database=data.resolve("database.db");
        try(var live=DriverManager.getConnection("jdbc:sqlite:"+database);
            var writer=live.createStatement();
            var owner=AppDataLock.acquire(data,"snapshot-cancel")) {
            createFixture(live,writer);
            var interruptedDuringCopy=new AtomicBoolean();
            var service=new LocalDataSnapshotService();
            try {
                assertThrows(InterruptedIOException.class,()->service.create(owner,"1.5.3",6,"signed-update-install",progress->{
                    if("COPYING_DATA".equals(progress.phase())&&progress.total()>0
                            &&interruptedDuringCopy.compareAndSet(false,true))Thread.currentThread().interrupt();
                }));
            } finally {
                // The service preserves the caller's cancellation signal. Do not let
                // that signal contaminate JDBC assertions or other tests on this thread.
                Thread.interrupted();
            }
            assertTrue(interruptedDuringCopy.get(),"Cancel at an actual native copy callback, not before preparation");
            assertMarker(database,0);
            try(var result=writer.executeQuery("SELECT count(*) FROM payload")) {
                assertTrue(result.next());assertEquals(1024,result.getInt(1));
            }
            assertTrue(service.list(owner).isEmpty(),"No cancelled rollback may be advertised as complete");
            Path snapshots=data.resolve("snapshots");
            if(Files.exists(snapshots))try(var entries=Files.list(snapshots)) {
                assertEquals(0,entries.count(),"Remove partial database, metadata and their temporary snapshot directory");
            }
            assertFalse(Files.exists(data.resolve(".vncode-update-snapshot.properties")));
            assertThrows(AppDataLock.AlreadyRunningException.class,()->AppDataLock.acquire(data,"second-writer"));
        }
    }

    private static void createFixture(Connection live,Statement writer) throws SQLException {
        writer.execute("PRAGMA page_size=4096");
        writer.execute("PRAGMA journal_mode=WAL");
        writer.execute("PRAGMA wal_autocheckpoint=0");
        writer.execute("PRAGMA user_version=6");
        writer.execute("CREATE TABLE marker(current_value INTEGER NOT NULL,paired_value INTEGER NOT NULL)");
        writer.execute("INSERT INTO marker VALUES(0,0)");
        writer.execute("CREATE TABLE payload(data BLOB NOT NULL)");
        live.setAutoCommit(false);
        try {
            for(int row=0;row<1024;row++)writer.execute("INSERT INTO payload VALUES(zeroblob(16384))");
            live.commit();
        } finally {
            live.setAutoCommit(true);
        }
        writer.execute("PRAGMA wal_checkpoint(TRUNCATE)");
    }

    private static void assertMarker(Path database,int expected) throws SQLException {
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+database);
            var statement=connection.createStatement();
            var result=statement.executeQuery("SELECT current_value,paired_value FROM marker")) {
            assertTrue(result.next());assertEquals(expected,result.getInt(1));assertEquals(expected,result.getInt(2));
            assertFalse(result.next());
        }
    }
}
