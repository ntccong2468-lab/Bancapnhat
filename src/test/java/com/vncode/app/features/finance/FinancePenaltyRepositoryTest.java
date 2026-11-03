package com.vncode.app.features.finance;

import com.vncode.app.config.Database;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class FinancePenaltyRepositoryTest {
    @TempDir Path tempDir;
    Shop first, second;
    final LocalDate from=LocalDate.of(2026,10,1), to=LocalDate.of(2026,10,3);
    final Instant when=Instant.parse("2026-10-04T10:00:00Z");
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",tempDir.toString()); Database.initDatabase(); AnalyticsDatabase.initialize();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(?,?,?,?,?)")) {
            insertShop(p,1,"OZON","client-1","fixture-a"); insertShop(p,2,"WILDBERRIES",null,"fixture-b");
        }
        first=new Shop(1,"fixture",Marketplace.OZON,"client-1","fixture-a");
        second=new Shop(2,"fixture",Marketplace.WILDBERRIES,null,"fixture-b");
    }
    @AfterEach void clearOverride() { System.clearProperty("vncode.appdata.dir"); }

    @Test void legacyRowsRemainUnverifiedAndNewRowsAreOwnerScopedAndPaginated() {
        var legacy=row("legacy","2026-10-02","legacy description",25);
        new FinanceAnalyticsRepository().upsertFinanceRows(1,List.of(legacy));
        var repo=new FinancePenaltyRepository();
        assertFalse(repo.scopeStatus(first).verified()); assertEquals(0,repo.count(first,from,to));
        assertTrue(repo.load(first,from,to,10,0).isEmpty());
        repo.recordVerifiedPage(first,List.of(row("ozon:1","2026-10-01","Штраф за заказ",-75),
                row("ozon:2","2026-10-03",null,25),row("ozon:3","2026-10-03","not a fee",0),
                row("ozon:4","2026-09-30","outside",10)),when);
        repo.recordVerifiedPage(second,List.of(row("999","2026-10-03","other seller",999)),when);
        assertTrue(repo.scopeStatus(first).verified()); assertFalse(repo.scopeStatus(first).complete());
        assertEquals(when,repo.scopeStatus(first).lastReadAt()); assertEquals(2,repo.count(first,from,to));
        var firstPage=repo.load(first,from,to,1,0); assertEquals("ozon:2",firstPage.getFirst().transactionId());
        assertNull(firstPage.getFirst().description()); assertEquals(25,firstPage.getFirst().amount());
        var secondPage=repo.load(first,from,to,1,1); assertEquals("ozon:1",secondPage.getFirst().transactionId());
        assertEquals("Штраф за заказ",secondPage.getFirst().description()); assertEquals(-75,secondPage.getFirst().amount());
        assertEquals("100-1",secondPage.getFirst().orderId()); assertEquals("ART-1",secondPage.getFirst().article());
        assertTrue(repo.load(first,from,to,1,2).isEmpty()); assertEquals(1,repo.count(second,from,to));
        assertEquals(1,legacyRowCount());
        // A corrected zero amount hides the old fee but does not touch legacy rows.
        repo.recordVerifiedPage(first,List.of(row("ozon:1","2026-10-01","correction",0)),when.plusSeconds(1));
        assertEquals(1,repo.count(first,from,to)); assertEquals(1,legacyRowCount());
    }

    @Test void changingCredentialsInvalidatesScopeWithoutRemovingPreviousData() throws Exception {
        var repo=new FinancePenaltyRepository(); repo.recordVerifiedPage(first,List.of(row("same","2026-10-02","A",50)),when);
        rotate("fixture-new"); Shop next=new Shop(1,"fixture",Marketplace.OZON,"client-1","fixture-new");
        assertThrows(SecurityException.class,()->repo.load(first,from,to,10,0));
        assertFalse(repo.scopeStatus(next).verified()); assertTrue(repo.load(next,from,to,10,0).isEmpty());
        repo.recordVerifiedPage(next,List.of(row("same","2026-10-02","B",70)),when.plusSeconds(2));
        assertEquals("B",repo.load(next,from,to,10,0).getFirst().description());
        rotate("fixture-a");
        assertEquals("A",repo.load(first,from,to,10,0).getFirst().description());
        assertEquals(2,scopedRowCount());
    }

    @Test void clearsCorrectionsAcrossMoreThanOneBoundedSqlBatch() {
        var repo=new FinancePenaltyRepository();
        repo.recordVerifiedPage(first,List.of(row("item-000","2026-10-02","fee",10),row("item-999","2026-10-02","fee",20)),when);
        var corrected=new java.util.ArrayList<FinanceRawRow>();
        for(int i=0;i<1200;i++)corrected.add(row("item-"+String.format(java.util.Locale.ROOT,"%03d",i),"2026-10-02","corrected",0));
        repo.recordVerifiedPage(first,corrected,when.plusSeconds(1));
        assertEquals(0,repo.count(first,from,to)); assertEquals(2,scopedRowCount());
    }

    @Test void rotationDuringHttpWaitRejectsStalePageBeforeAnyOwnerWrite() throws Exception {
        var repo=new FinancePenaltyRepository(); var started=new CountDownLatch(1); var resume=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var job=executor.submit(()->{ started.countDown(); assertTrue(resume.await(10,TimeUnit.SECONDS));
                assertThrows(SecurityException.class,()->repo.recordVerifiedPage(first,List.of(row("stale","2026-10-02","A",5)),when)); return null; });
            assertTrue(started.await(10,TimeUnit.SECONDS)); rotate("fixture-new"); resume.countDown(); job.get(10,TimeUnit.SECONDS);
        }
        var next=new Shop(1,"fixture",Marketplace.OZON,"client-1","fixture-new");
        assertFalse(repo.scopeStatus(next).verified()); assertEquals(0,scopedRowCount());
    }

    @Test void ownerChangeImmediatelyBeforeCommitRollsBackRowsAndStatus() throws Exception {
        var repo=new FinancePenaltyRepository();
        assertThrows(SecurityException.class,()->repo.recordVerifiedPage(first,List.of(row("stale","2026-10-02","A",5)),when,()->{
            // Simulates an external writer bypassing app locks; owner is rechecked inside the analytics transaction.
            try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key=? WHERE id=1")) {
                p.setString(1,"fixture-new"); p.executeUpdate();
            } catch(Exception failure) { throw new RuntimeException(failure); }
        }));
        assertEquals(0,scopedRowCount());
        var next=new Shop(1,"fixture",Marketplace.OZON,"client-1","fixture-new"); assertFalse(repo.scopeStatus(next).verified());
    }

    @Test void scopedSchemaAndWritesRollbackOnFailureWithoutTouchingLegacy() throws Exception {
        new FinanceAnalyticsRepository().upsertFinanceRows(1,List.of(row("legacy","2026-10-02","legacy",7)));
        Path snapshot=tempDir.resolve("analytics-before-scope.sqlite");
        try(var c=AnalyticsDatabase.getConnection();var p=c.prepareStatement("VACUUM INTO ?")) { p.setString(1,snapshot.toString()); p.execute(); }
        try(var c=AnalyticsDatabase.getConnection()) { c.setAutoCommit(false); FinancePenaltyRepository.createScopedSchema(c); c.rollback(); }
        try(var c=AnalyticsDatabase.getConnection();var p=c.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='finance_penalty_scoped'");var r=p.executeQuery()) { assertTrue(r.next()); assertEquals(0,r.getInt(1)); }
        var repo=new FinancePenaltyRepository(); assertFalse(repo.scopeStatus(first).verified());
        try(var c=AnalyticsDatabase.getConnection();var p=c.createStatement()) {
            p.execute("CREATE TRIGGER fixture_fail_scope BEFORE INSERT ON finance_penalty_scope_state BEGIN SELECT RAISE(ABORT,'fixture'); END");
        }
        assertThrows(IllegalStateException.class,()->repo.recordVerifiedPage(first,List.of(row("new","2026-10-02","new",8)),when));
        assertEquals(0,scopedRowCount()); assertFalse(repo.scopeStatus(first).verified()); assertEquals(1,legacyRowCount());
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+snapshot);var p=c.createStatement()) {
            try(var r=p.executeQuery("SELECT count(*) FROM finance_raw WHERE rrd_id='legacy'")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
            try(var r=p.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='finance_penalty_scoped'")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
        }
        // Restore only into a synthetic directory, never over the active data.
        Path restored=tempDir.resolve("restored-fixture"); java.nio.file.Files.createDirectories(restored);
        java.nio.file.Files.copy(snapshot,restored.resolve(AnalyticsDatabase.FILE_NAME));
        try {
            System.setProperty("vncode.appdata.dir",restored.toString()); AnalyticsDatabase.initialize();
            assertEquals(1,legacyRowCount());
        } finally { System.setProperty("vncode.appdata.dir",tempDir.toString()); }
    }

    @Test void rejectsInvalidScopeAndUnboundedPages() {
        var repo=new FinancePenaltyRepository();
        assertThrows(IllegalArgumentException.class,()->repo.load(null,from,to,10,0));
        assertThrows(IllegalArgumentException.class,()->repo.load(first,to,from,10,0));
        assertThrows(IllegalArgumentException.class,()->repo.load(first,from,to,501,0));
        assertThrows(IllegalArgumentException.class,()->repo.load(first,from,to,10,-1));
    }

    private void rotate(String credential) throws Exception {
        ShopOperationCoordinator.withExclusiveShop(1,()->{try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key=? WHERE id=1")){p.setString(1,credential);p.executeUpdate();}return null;});
    }
    private long legacyRowCount() { return count("SELECT count(*) FROM finance_raw"); }
    private long scopedRowCount() { return count("SELECT count(*) FROM finance_penalty_scoped"); }
    private long count(String sql) {try(var c=AnalyticsDatabase.getConnection();var p=c.createStatement();var r=p.executeQuery(sql)){r.next();return r.getLong(1);}catch(Exception failure){throw new RuntimeException(failure);}}
    private static void insertShop(java.sql.PreparedStatement p,int id,String market,String client,String key) throws Exception {
        p.setInt(1,id);p.setString(2,"fixture");p.setString(3,market);p.setString(4,client);p.setString(5,key);p.executeUpdate();
    }
    private static FinanceRawRow row(String id,String day,String description,double penalty) {
        return new FinanceRawRow(id,"report",day,"RUB","services",description,"100-1","555","ART-1","555",
                0,false,0,0,0,0,0,0,0,penalty,0,0,0,0,"{}");
    }
}
