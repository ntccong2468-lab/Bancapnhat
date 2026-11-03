package com.vncode.app.features.finance;

import com.vncode.app.config.Database;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FinancePenaltySyncStoreTest {
    @TempDir Path directory;
    final LocalDate from=LocalDate.of(2026,10,1),to=LocalDate.of(2026,10,3);
    final Instant now=Instant.parse("2026-10-04T10:00:00Z");
    FinancePenaltySyncStore store;
    Shop first,second;

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString()); Database.initDatabase(); AnalyticsDatabase.initialize();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(?,?,?,?,?)")) {
            shop(p,1,"WILDBERRIES",null,"fixture-old");shop(p,2,"OZON","fixture-client","fixture-ozon");
        }
        first=new Shop(1,"fixture",Marketplace.WILDBERRIES,null,"fixture-old");
        second=new Shop(2,"fixture",Marketplace.OZON,"fixture-client","fixture-ozon");
        store=new FinancePenaltySyncStore();
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void pageProgressIsDurableAndCompleteShowsOnlyCurrentRun() {
        var request=store.request(first,from,to,now);
        assertEquals(request.runId(),store.request(first,from,to,now.plusSeconds(1)).runId());
        assertFalse(store.status(first,from,to).verified());
        var firstAttempt=store.begin(first,request,now,now.plusSeconds(61));
        assertNotNull(firstAttempt); assertEquals("0",firstAttempt.cursor());
        assertTrue(store.commitPage(first,firstAttempt,List.of(row("1","2026-10-02","first",75)),"42",false,now.plusSeconds(1)));
        assertFalse(store.status(first,from,to).complete()); assertEquals(1,store.count(first,from,to));
        var restarted=new FinancePenaltySyncStore();
        assertNull(restarted.due(first,now));
        var due=restarted.due(first,now.plusSeconds(61)); assertNotNull(due);assertEquals("42",due.cursor());
        var finalAttempt=restarted.begin(first,due,now.plusSeconds(61),now.plusSeconds(122));
        assertTrue(restarted.commitPage(first,finalAttempt,List.of(),null,true,now.plusSeconds(62)));
        assertTrue(restarted.status(first,from,to).complete()); assertEquals("first",restarted.load(first,from,to,10,0).getFirst().description());
        var rerun=restarted.request(first,from,to,now.plusSeconds(123));
        assertNotEquals(request.runId(),rerun.runId()); assertFalse(restarted.status(first,from,to).complete());
        assertEquals(0,restarted.count(first,from,to)); assertEquals(1,observationCount());
    }

    @Test void rotationAndOverlappingRangesDoNotShareRowsOrResetRate() throws Exception {
        var job=store.request(first,from,to,now);var attempt=store.begin(first,job,now,now.plusSeconds(61));
        assertTrue(store.commitPage(first,attempt,List.of(row("same","2026-10-02","first owner",9)),"11",false,now.plusSeconds(1)));
        assertFalse(store.status(first,from.minusDays(1),to).verified());
        rotate("fixture-new");var changed=new Shop(1,"fixture",Marketplace.WILDBERRIES,null,"fixture-new");
        assertThrows(SecurityException.class,()->store.status(first,from,to));
        assertFalse(store.status(changed,from,to).verified());assertEquals(0,store.count(changed,from,to));
        var newJob=store.request(changed,from,to,now.plusSeconds(2));
        assertNull(store.begin(changed,newJob,now.plusSeconds(2),now.plusSeconds(63)));
        assertEquals(now.plusSeconds(61),store.nextAllowed(1,"FINANCE"));
        var newAttempt=store.begin(changed,newJob,now.plusSeconds(61),now.plusSeconds(122));
        assertTrue(store.commitPage(changed,newAttempt,List.of(row("same","2026-10-02","second owner",21)),"20",false,now.plusSeconds(62)));
        assertEquals("second owner",store.load(changed,from,to,10,0).getFirst().description());
        rotate("fixture-old");assertEquals("first owner",store.load(first,from,to,10,0).getFirst().description());
        assertEquals(2,observationCount());
    }

    @Test void cancellationStopsLateResponseAndPreservesObservations() {
        var job=store.request(first,from,to,now);var attempt=store.begin(first,job,now,now.plusSeconds(61));
        assertTrue(store.cancel(first,from,to));
        assertFalse(store.commitPage(first,attempt,List.of(row("late","2026-10-02","late",5)),"1",false,now.plusSeconds(1)));
        assertEquals("CANCELLED",store.status(first,from,to).status());assertEquals(0,observationCount());
        var retry=store.request(first,from,to,now.plusSeconds(2));assertNotEquals(job.runId(),retry.runId());
        assertNull(store.begin(first,retry,now.plusSeconds(2),now.plusSeconds(63)));
    }

    @Test void expiredAttemptIsResumedWithoutAllowingLateWorkerToCommit() {
        var job=store.request(first,from,to,now);var attempt=store.begin(first,job,now,now.plusSeconds(61));
        assertNull(store.due(first,now.plusSeconds(119)));
        var due=store.due(first,now.plusSeconds(120));assertNotNull(due);
        var replacement=store.begin(first,due,now.plusSeconds(120),now.plusSeconds(181));
        assertNotNull(replacement);assertNotEquals(attempt.attemptId(),replacement.attemptId());
        assertFalse(store.commitPage(first,attempt,List.of(row("stale","2026-10-02","old",3)),"1",false,now.plusSeconds(121)));
        assertTrue(store.commitPage(first,replacement,List.of(row("good","2026-10-02","new",4)),"2",false,now.plusSeconds(122)));
        assertEquals("good",store.load(first,from,to,10,0).getFirst().transactionId());
    }

    @Test void retryCooldownIsPersistedAndNeverShortenedByAnotherOwnerOrRequest() throws Exception {
        var job=store.request(first,from,to,now);var attempt=store.begin(first,job,now,now.plusSeconds(61));
        assertTrue(store.defer(first,attempt,now.plusSeconds(7200),"WB_RATE_LIMIT"));
        assertEquals("WAITING",store.status(first,from,to).status());assertEquals("WB_RATE_LIMIT",store.status(first,from,to).failureCode());
        assertEquals(now.plusSeconds(7200),new FinancePenaltySyncStore().nextAllowed(1,"FINANCE"));
        rotate("fixture-new");var changed=new Shop(1,"fixture",Marketplace.WILDBERRIES,null,"fixture-new");
        var newJob=store.request(changed,from,to,now.plusSeconds(1));
        assertNull(store.begin(changed,newJob,now.plusSeconds(61),now.plusSeconds(122)));
        assertEquals(now.plusSeconds(7200),store.nextAllowed(1,"FINANCE"));
    }

    @Test void concurrentRateReservationsNeverShortenTheLongerCooldown() throws Exception {
        assertNull(store.nextAllowed(1,"FINANCE"));
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var longer=pool.submit(()->{start.await();store.extendRate(1,"FINANCE",now.plusSeconds(7200));return null;});
            var shorter=pool.submit(()->{start.await();store.extendRate(1,"FINANCE",now.plusSeconds(61));return null;});
            start.countDown();longer.get(15,java.util.concurrent.TimeUnit.SECONDS);shorter.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(now.plusSeconds(7200),store.nextAllowed(1,"FINANCE"));
    }

    @Test void failedPageRollsBackRowsAndCheckpointWithoutTouchingLegacy() {
        var legacy=row("legacy","2026-10-02","legacy",7);new FinanceAnalyticsRepository().upsertFinanceRows(1,List.of(legacy));
        var job=store.request(first,from,to,now);var attempt=store.begin(first,job,now,now.plusSeconds(61));
        assertThrows(IllegalArgumentException.class,()->store.commitPage(first,attempt,
                List.of(row("same","2026-10-02","A",1),row("same","2026-10-02","B",2)),"8",false,now.plusSeconds(1)));
        assertEquals(0,observationCount());assertFalse(store.status(first,from,to).verified());
        assertEquals("IN_PROGRESS",store.status(first,from,to).status());assertEquals("0",store.due(first,now.plusSeconds(121)).cursor());
        assertEquals(1,legacyCount());
        assertThrows(IllegalArgumentException.class,()->store.commitPage(first,attempt,List.of(row("outside","2026-09-30","bad",1)),"9",false,now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->store.commitPage(first,attempt,List.of(row("invalid","2026-10-02","bad",Double.NaN)),"9",false,now.plusSeconds(1)));
        assertEquals(0,observationCount());
    }

    @Test void externalCredentialChangeImmediatelyBeforeCommitRollsBackPageAndCheckpoint() throws Exception {
        var job=store.request(first,from,to,now);var attempt=store.begin(first,job,now,now.plusSeconds(61));
        assertThrows(SecurityException.class,()->store.commitPage(first,attempt,List.of(row("late","2026-10-02","old",5)),"8",false,now.plusSeconds(1),()->{
            // Simulate an external DB writer bypassing in-process locks.
            try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key=? WHERE id=1")) {
                p.setString(1,"fixture-new");p.executeUpdate();
            } catch(Exception failure){throw new RuntimeException(failure);}
        }));
        assertEquals(0,observationCount());
        var changed=new Shop(1,"fixture",Marketplace.WILDBERRIES,null,"fixture-new");
        assertFalse(store.status(changed,from,to).verified());
        rotate("fixture-old");assertEquals("IN_PROGRESS",store.status(first,from,to).status());
    }

    @Test void schemaRollbackAndSyntheticSnapshotDoNotDamageLegacy() throws Exception {
        new FinanceAnalyticsRepository().upsertFinanceRows(1,List.of(row("legacy","2026-10-02","old",7)));
        Path snapshot=directory.resolve("before.sqlite");
        try(var c=AnalyticsDatabase.getConnection();var p=c.prepareStatement("VACUUM INTO ?")){p.setString(1,snapshot.toString());p.execute();}
        try(var c=AnalyticsDatabase.getConnection()){c.setAutoCommit(false);FinancePenaltySyncStore.createSchema(c);c.rollback();}
        assertEquals(0,tableCount("finance_penalty_sync_job"));assertEquals(1,legacyCount());
        store.request(first,from,to,now);assertEquals(1,tableCount("finance_penalty_sync_job"));
        Path restored=directory.resolve("restore-fixture");Files.createDirectories(restored);Files.copy(snapshot,restored.resolve(AnalyticsDatabase.FILE_NAME));
        try {System.setProperty("vncode.appdata.dir",restored.toString());AnalyticsDatabase.initialize();assertEquals(1,legacyCount());assertEquals(0,tableCount("finance_penalty_sync_job"));}
        finally {System.setProperty("vncode.appdata.dir",directory.toString());}
    }

    @Test void validatesRangesPaginationAndNeverPrintsCredentialInJob() {
        assertThrows(IllegalArgumentException.class,()->store.request(first,to,from,now));
        assertThrows(IllegalArgumentException.class,()->store.load(first,from,to,501,0));
        assertThrows(IllegalArgumentException.class,()->store.load(first,from,to,10,-1));
        var job=store.request(first,from,to,now);assertFalse(job.toString().contains("fixture-old"));
        assertThrows(IllegalArgumentException.class,()->store.defer(first,job,now,"bad input with secrets"));
    }

    private void rotate(String key)throws Exception {ShopOperationCoordinator.withExclusiveShop(1,()->{try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key=? WHERE id=1")){p.setString(1,key);p.executeUpdate();}return null;});}
    private long legacyCount(){return countSql("SELECT count(*) FROM finance_raw");}
    private long observationCount(){return countSql("SELECT count(*) FROM finance_penalty_sync_observation");}
    private long tableCount(String name){try(var c=AnalyticsDatabase.getConnection();var p=c.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?")){p.setString(1,name);try(var r=p.executeQuery()){r.next();return r.getLong(1);}}catch(Exception e){throw new RuntimeException(e);}}
    private long countSql(String sql){try(var c=AnalyticsDatabase.getConnection();var p=c.createStatement();var r=p.executeQuery(sql)){r.next();return r.getLong(1);}catch(Exception e){throw new RuntimeException(e);}}
    private static void shop(java.sql.PreparedStatement p,int id,String market,String client,String key)throws Exception{p.setInt(1,id);p.setString(2,"fixture");p.setString(3,market);p.setString(4,client);p.setString(5,key);p.executeUpdate();}
    private static FinanceRawRow row(String id,String day,String label,double penalty){return new FinanceRawRow(id,"report",day,"RUB","services",label,"100-1","555","ART-1","555",0,false,0,0,0,0,0,0,0,penalty,0,0,0,0,"{}");}
}
