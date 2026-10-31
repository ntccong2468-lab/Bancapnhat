package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.vncode.app.integration.znack.ZnackModels;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogAutomaticRepairTest {
    private static final String GTIN="04601234567893";
    private static final String REFUSAL="Создание комплектов не допускается. Исключите параметр is_kit";
    @TempDir Path dir;

    private static JsonObject payload(){var p=new JsonObject();p.addProperty("gtin",GTIN);p.addProperty("good_id",123L);p.addProperty("is_kit",true);return p;}
    private static ZnackNationalCatalogService.FeedProgress rejected(String... errors){return new ZnackNationalCatalogService.FeedProgress("Rejected",123L,List.of(errors));}
    private static ZnackRegistrationScope scope(String environment){return new ZnackRegistrationScope("1234567890",environment,"A".repeat(40));}

    @Test void correctionRequiresExactConfirmedCardAndOnlyProvenKitError(){
        var original=payload();var p=CatalogAutomaticRepair.packing(rejected(REFUSAL),original,GTIN,123,true);
        assertNotNull(p);assertTrue(original.has("is_kit"));assertFalse(p.payload().has("is_kit"));
        assertEquals(123,p.payload().get("good_id").getAsLong());assertEquals(GTIN,p.payload().get("gtin").getAsString());
        assertEquals("is_kit",p.changes().getFirst().field());assertEquals("true",p.changes().getFirst().before());
        assertEquals("(absent)",p.changes().getFirst().after());assertTrue(p.changes().getFirst().source().contains("is_kit"));
        assertNull(CatalogAutomaticRepair.packing(rejected(REFUSAL),original,GTIN,123,false));
        assertNull(CatalogAutomaticRepair.packing(rejected(REFUSAL),original,GTIN,456,true));
        assertNull(CatalogAutomaticRepair.packing(rejected(REFUSAL),original,"04601234567886",123,true));
        assertNull(CatalogAutomaticRepair.packing(new ZnackNationalCatalogService.FeedProgress("Processing",123L,List.of(REFUSAL)),original,GTIN,123,true));
        assertNull(CatalogAutomaticRepair.packing(rejected(REFUSAL,"Изображение недоступно по URL"),original,GTIN,123,true));
    }

    @Test void ownerMustBePresentAndEveryProvidedOwnerMustMatch(){
        var card=new JsonObject();assertFalse(CatalogAutomaticRepair.ownerMatches(card,"1234567890"));
        card.addProperty("owner_inn","1234567890");assertTrue(CatalogAutomaticRepair.ownerMatches(card,"1234567890"));
        card.addProperty("participant_inn","9999999999");assertFalse(CatalogAutomaticRepair.ownerMatches(card,"1234567890"));
    }

    @Test void ownerReadDoesNotSwallowRateLimitOrNetworkFailure(){
        var rateLimited=new com.vncode.app.integration.znack.ZnackApiClient(){
            @Override public com.google.gson.JsonElement productCards(String base,String token,String gtin)throws java.io.IOException{
                throw new com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException("rate limited",429,"{}");
            }
        };
        var service=new ZnackNationalCatalogService(rateLimited,null,null,ZnackModels.Settings.empty());
        var session=new ZnackCardRegistrationWorkflow.CatalogSession("",service,scope(ZnackModels.PRODUCTION_TRUE_API));
        var failure=assertThrows(com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException.class,()->CatalogAutomaticRepair.verifiedRejectedOwner(session,GTIN,123));
        assertTrue(CatalogFeedback.rateLimited(failure));
        var offline=new com.vncode.app.integration.znack.ZnackApiClient(){
            @Override public com.google.gson.JsonElement productCards(String base,String token,String gtin)throws java.io.IOException{throw new java.net.SocketTimeoutException("timeout");}
        };
        var other=new ZnackCardRegistrationWorkflow.CatalogSession("",new ZnackNationalCatalogService(offline,null,null,ZnackModels.Settings.empty()),scope(ZnackModels.PRODUCTION_TRUE_API));
        assertThrows(java.net.SocketTimeoutException.class,()->CatalogAutomaticRepair.verifiedRejectedOwner(other,GTIN,123));
    }

    @Test void roundLimitAndAuditSurviveRestartAndEndpointAlias() throws Exception {
        var path=dir.resolve("ledger.sqlite");var proposal=CatalogAutomaticRepair.packing(rejected(REFUSAL),payload(),GTIN,123,true);
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path)){
            db.setAutoCommit(false);
            assertTrue(CatalogAutomaticRepairLedger.reserve(db,scope(ZnackModels.PRODUCTION_TRUE_API),GTIN,"feed-1",proposal));db.commit();
            assertFalse(CatalogAutomaticRepairLedger.reserve(db,scope(ZnackModels.PRODUCTION_TRUE_API),GTIN,"feed-1",proposal));db.commit();
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path)){
            db.setAutoCommit(false);
            assertTrue(CatalogAutomaticRepairLedger.reserve(db,scope(ZnackModels.PRODUCTION_NATIONAL_CATALOG),GTIN,"feed-2",proposal));db.commit();
            assertFalse(CatalogAutomaticRepairLedger.reserve(db,scope(ZnackModels.PRODUCTION_TRUE_API),GTIN,"feed-3",proposal));db.commit();
            try(var q=db.createStatement();var rows=q.executeQuery("SELECT COUNT(*),MIN(changes_json),MIN(rejection) FROM catalog_automatic_repairs")){
                assertTrue(rows.next());assertEquals(2,rows.getInt(1));assertTrue(rows.getString(2).contains("is_kit"));assertTrue(rows.getString(3).contains("CATALOG_REJECTED"));
            }
        }
    }

    @Test void aRollbackDoesNotConsumeARound() throws Exception {
        var proposal=CatalogAutomaticRepair.packing(rejected(REFUSAL),payload(),GTIN,123,true);
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("rollback.sqlite"))){
            db.setAutoCommit(false);assertTrue(CatalogAutomaticRepairLedger.reserve(db,scope(ZnackModels.PRODUCTION_TRUE_API),GTIN,"feed-1",proposal));db.rollback();
            assertTrue(CatalogAutomaticRepairLedger.reserve(db,scope(ZnackModels.PRODUCTION_TRUE_API),GTIN,"feed-1",proposal));db.commit();
        }
    }

    @Test void knownUnsentRepairLedgerReleaseParticipatesInTheCallersTransaction() throws Exception {
        var owner=scope(ZnackModels.PRODUCTION_TRUE_API);
        var proposal=CatalogAutomaticRepair.packing(rejected(REFUSAL),payload(),GTIN,123,true);
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("release.sqlite"))){
            db.setAutoCommit(false);assertTrue(CatalogAutomaticRepairLedger.reserve(db,owner,GTIN,"feed-1",proposal));
            try(var s=db.createStatement()){s.executeUpdate("UPDATE catalog_automatic_repairs SET state='SENDING'");}db.commit();
            CatalogAutomaticRepairLedger.releaseKnownUnsent(db,owner,proposal.payload());db.rollback();
            try(var s=db.createStatement();var r=s.executeQuery("SELECT state FROM catalog_automatic_repairs")){assertTrue(r.next());assertEquals("SENDING",r.getString(1));}
            CatalogAutomaticRepairLedger.releaseKnownUnsent(db,owner,proposal.payload());db.commit();
            try(var s=db.createStatement();var r=s.executeQuery("SELECT state FROM catalog_automatic_repairs")){assertTrue(r.next());assertEquals("PRE_SEND_FAILED",r.getString(1));}
        }
    }

    @Test void concurrentConnectionsCannotReserveMoreThanTwoRounds() throws Exception {
        var proposal=CatalogAutomaticRepair.packing(rejected(REFUSAL),payload(),GTIN,123,true);
        var path=dir.resolve("concurrent.sqlite");
        // Production opens and initializes its database in WAL mode before work is
        // dispatched. Do the same here, while leaving the correction budget empty.
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()){
            sql.execute("PRAGMA journal_mode=WAL");sql.execute("BEGIN IMMEDIATE");
            assertTrue(CatalogAutomaticRepairLedger.reserve(db,scope(ZnackModels.PRODUCTION_TRUE_API),GTIN,"fixture-schema",proposal));
            assertEquals(1,sql.executeUpdate("DELETE FROM catalog_automatic_repairs"));sql.execute("COMMIT");
        }
        var workers=java.util.concurrent.Executors.newFixedThreadPool(3);
        var ready=new java.util.concurrent.CountDownLatch(3);var start=new java.util.concurrent.CountDownLatch(1);
        var results=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
        try {
            for(int i=0;i<3;i++){
                final String feed="feed-"+i;
                results.add(workers.submit(()->{
                    try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()){
                        sql.execute("PRAGMA busy_timeout=10000");ready.countDown();start.await();sql.execute("BEGIN IMMEDIATE");
                        boolean reserved=CatalogAutomaticRepairLedger.reserve(db,scope(ZnackModels.PRODUCTION_TRUE_API),GTIN,feed,proposal);
                        sql.execute("COMMIT");return reserved;
                    }
                }));
            }
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));start.countDown();
            int accepted=0;for(var result:results)if(result.get(15,java.util.concurrent.TimeUnit.SECONDS))accepted++;
            assertEquals(2,accepted);
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement();var rows=sql.executeQuery("SELECT COUNT(*) FROM catalog_automatic_repairs")){
                assertTrue(rows.next());assertEquals(2,rows.getInt(1));
            }
        } finally {
            start.countDown();workers.shutdown();
            // JDBC calls can still be completing after one Future fails. Do not
            // let @TempDir remove files while another connection owns them.
            if(!workers.awaitTermination(20,java.util.concurrent.TimeUnit.SECONDS)){
                workers.shutdownNow();assertTrue(workers.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS),"SQLite workers did not close");
            }
        }
    }
}
