package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.shared.LocalDataMigrationGate;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class GtinSyncRepositoryTest {
    @TempDir Path temp;
    GtinSyncRepository repo;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());
        Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','test'),(2,'Ozon','OZON','test')");
        }
        repo=new GtinSyncRepository();
    }
    @AfterEach void clear() { System.clearProperty("vncode.appdata.dir"); }
    static Preview preview(int shop, Marketplace marketplace) {
        var product=new ProductSnapshot(new ProductKey(shop,marketplace,"101","11"),"PANTS","Đen","XL",List.of("old"),"fp",Instant.now());
        return new Preview(product,Operation.ADD,"","00000000000017",new Capability(true,false,""));
    }
    @Test void migratesVersionTwoWithSnapshotAndPreservesData() throws Exception {
        // Recreate the pre-GTIN schema-2 fixture, including the newer verified-mapping table.
        try(var c=Database.getConnection();var s=c.createStatement()) {
            for(String table:List.of("gtin_sync_events","gtin_sync_items","gtin_sync_jobs","gtin_sync_verified_mappings","gtin_sync_mappings","gtin_sync_catalog"))s.execute("DROP TABLE "+table);
            s.execute("PRAGMA user_version=2");
            assertFalse(s.executeQuery("SELECT name FROM sqlite_master WHERE name LIKE 'gtin_sync_%'").next());
        }
        try(var session=LocalDataMigrationGate.prepare(temp,"1.1.32","javafx")) {
            try(var c=Database.getConnection();var s=c.createStatement()) {
                var v=s.executeQuery("PRAGMA user_version");assertTrue(v.next());assertEquals(Database.currentSchemaVersion(),v.getInt(1));v.close();
                var integrity=s.executeQuery("PRAGMA integrity_check");assertEquals("ok",integrity.getString(1));integrity.close();
                assertFalse(s.executeQuery("PRAGMA foreign_key_check").next());
                var tables=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'gtin_sync_%'");assertEquals(6,tables.getInt(1));tables.close();
            }
            Path snapshot;
            try(var files=java.nio.file.Files.walk(temp.resolve("snapshots"))) {
                snapshot=files.filter(p->p.getFileName().toString().equals("database.db")).findFirst().orElseThrow();
            }
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+snapshot);var s=c.createStatement()) {
                var v=s.executeQuery("PRAGMA user_version");assertEquals(2,v.getInt(1));v.close();
                assertFalse(s.executeQuery("SELECT name FROM sqlite_master WHERE name LIKE 'gtin_sync_%'").next());
                var shops=s.executeQuery("SELECT count(*) FROM shops");assertEquals(2,shops.getInt(1));shops.close();
            }
        }
        Database.initDatabase();
        assertEquals(2,new com.vncode.app.features.shop.ShopRepository().findAll().size());
    }
    @Test void storesLeadingZerosAndRejectsWrongMarketplace() {
        var row=new RegisteredGtin("00000000000017","PANTS","Đen","XL",true,true,"NK",Instant.now());
        repo.saveCatalog(1,Marketplace.WILDBERRIES,List.of(row));
        assertEquals(row,repo.catalog(1,Marketplace.WILDBERRIES).getFirst());
        assertThrows(IllegalArgumentException.class,()->repo.catalog(1,Marketplace.OZON));
        assertTrue(repo.catalog(2,Marketplace.OZON).isEmpty());
    }
    @Test void deduplicatesConfirmsAndClaimsTransitions() {
        var p=preview(1,Marketplace.WILDBERRIES);
        var id=repo.createJob(List.of(p));
        assertEquals(id,repo.createJob(List.of(p)));
        var item=repo.pending(1,Marketplace.WILDBERRIES).getFirst();
        assertTrue(repo.transition(item.id(),Status.QUEUED,Status.VALIDATING,""));
        assertFalse(repo.transition(item.id(),Status.QUEUED,Status.VALIDATING,""));
        assertThrows(IllegalArgumentException.class,()->repo.transition(item.id(),Status.VALIDATING,Status.SUCCEEDED,""));
        assertTrue(repo.transition(item.id(),Status.VALIDATING,Status.SENDING,""));
        repo.recoverInterrupted();
        assertEquals(Status.RECONCILE_REQUIRED,repo.pending(1,Marketplace.WILDBERRIES).getFirst().status());
    }
    @Test void rejectsMixedShopJobsAtomically() {
        assertThrows(IllegalArgumentException.class,()->repo.createJob(List.of(preview(1,Marketplace.WILDBERRIES),preview(2,Marketplace.OZON))));
        assertTrue(repo.pending(1,Marketplace.WILDBERRIES).isEmpty());
    }
    @Test void productEditClaimBlocksGtinForAllSizes()throws Exception {
        var p=preview(1,Marketplace.WILDBERRIES);repo.createJob(List.of(p));var item=repo.pending(1,Marketplace.WILDBERRIES).getFirst();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO product_edit_jobs VALUES('edit',1,'owner','{}','now')");s.execute("INSERT INTO product_edit_items(job_id,shop_id,marketplace,card_id,state,payload) VALUES('edit',1,'WILDBERRIES','101','RECONCILE_REQUIRED','{}')");}
        assertFalse(repo.transition(item.id(),Status.QUEUED,Status.VALIDATING,""));assertEquals(Status.QUEUED,repo.item(item.id()).status());
    }
    @Test void savesInternalMappingWithoutRemoteJob() {
        var key=preview(1,Marketplace.WILDBERRIES).before().key();
        repo.saveMapping(key,"00000000000017");
        assertEquals("00000000000017",repo.mapping(key));
        assertTrue(repo.pending(1,Marketplace.WILDBERRIES).isEmpty());
    }
    @Test void queuesTwoSizesAndSerializesClaimsAtCardLevel() {
        var first=preview(1,Marketplace.WILDBERRIES);
        var otherKey=new ProductKey(1,Marketplace.WILDBERRIES,"101","12");
        var otherProduct=new ProductSnapshot(otherKey,"PANTS","Đen","L",List.of("old2"),"fp2",Instant.now());
        var second=new Preview(otherProduct,Operation.ADD,"","00000000000024",first.capability());
        var job=repo.createJob(List.of(first,second));var items=repo.jobItems(job);
        assertEquals(2,items.size());
        assertTrue(repo.transition(items.getFirst().id(),Status.QUEUED,Status.VALIDATING,null));
        assertFalse(repo.transition(items.getLast().id(),Status.QUEUED,Status.VALIDATING,null));
    }
    @Test void pauseAtomicallyPreventsSendingAfterValidation() {
        var id=repo.createJob(List.of(preview(1,Marketplace.WILDBERRIES)));
        var item=repo.jobItems(id).getFirst();assertTrue(repo.transition(item.id(),Status.QUEUED,Status.VALIDATING,null));
        repo.pause(id,true);assertFalse(repo.transition(item.id(),Status.VALIDATING,Status.SENDING,null));
    }
    @Test void siblingBatchClaimsExcludeAnotherJobEvenAfterRecoveryAndDirectSql()throws Exception {
        var first=preview(1,Marketplace.WILDBERRIES);var base=first.before();
        var second=new Preview(new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"101","12"),base.article(),base.color(),base.size(),base.barcodes(),base.fingerprint(),base.fetchedAt()),Operation.ADD,"","00000000000024",first.capability());
        var third=new Preview(new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"101","13"),base.article(),base.color(),base.size(),base.barcodes(),base.fingerprint(),base.fetchedAt()),Operation.ADD,"","00000000000031",first.capability());
        var siblings=repo.createJob(List.of(first,second));var other=repo.createJob(List.of(third));
        var claimed=repo.claimBatch(repo.jobItems(siblings));assertEquals(2,claimed.size());assertTrue(repo.claimBatch(repo.jobItems(other)).isEmpty());
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE gtin_sync_items SET status='VALIDATING' WHERE job_id=?")){
            p.setString(1,other.toString());assertThrows(SQLException.class,p::executeUpdate);
        }
        assertTrue(repo.sendingBatch(claimed));repo.requireSendingBatch(claimed);repo.recoverInterrupted();
        assertTrue(repo.jobItems(siblings).stream().allMatch(i->i.status()==Status.RECONCILE_REQUIRED));assertTrue(repo.claimBatch(repo.jobItems(other)).isEmpty());
        repo.applyBatch(repo.jobItems(siblings).stream().map(i->new GtinSyncRepository.Change(i,Status.SUCCEEDED,null,"")).toList());
        assertEquals(1,repo.claimBatch(repo.jobItems(other)).size());
    }
    @Test void upgradesTheOldUniqueClaimIndexWithoutChangingExistingJobs()throws Exception {
        var job=repo.createJob(List.of(preview(1,Marketplace.WILDBERRIES)));
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("DROP INDEX gtin_sync_inflight_product");s.execute("CREATE UNIQUE INDEX gtin_sync_inflight_product ON gtin_sync_items(shop_id,marketplace,product_id) WHERE status IN ('VALIDATING','SENDING','AWAITING_VERIFICATION','RECONCILE_REQUIRED')");
            GtinSyncSchema.initialize(c);try(var rows=s.executeQuery("PRAGMA index_list(gtin_sync_items)")){boolean found=false;while(rows.next())if(rows.getString("name").equals("gtin_sync_inflight_product")){found=true;assertEquals(0,rows.getInt("unique"));}assertTrue(found);}
        }
        assertEquals(1,repo.jobItems(job).size());assertEquals(Status.QUEUED,repo.jobItems(job).getFirst().status());
    }
    @Test void actualTransportBoundaryIsDurableAndPausedOrLegacyClaimsDoNotInventIt(){
        var job=repo.createJob(List.of(preview(1,Marketplace.WILDBERRIES)));var item=repo.jobItems(job).getFirst();
        assertTrue(repo.transition(item.id(),Status.QUEUED,Status.VALIDATING,null));assertTrue(repo.transition(item.id(),Status.VALIDATING,Status.SENDING,null));assertNull(repo.item(item.id()).dispatchedAt());
        repo.pause(job,true);assertThrows(IllegalStateException.class,()->repo.requireSendingBatch(List.of(repo.item(item.id()))));assertNull(repo.item(item.id()).dispatchedAt());
        repo.pause(job,false);var before=Instant.now();repo.requireSendingBatch(List.of(repo.item(item.id())));var actual=repo.item(item.id()).dispatchedAt();assertNotNull(actual);assertFalse(actual.isBefore(before));
        repo.recoverInterrupted();assertEquals(Status.RECONCILE_REQUIRED,repo.item(item.id()).status());assertEquals(actual,repo.jobItems(job).getFirst().dispatchedAt());
        assertEquals(actual,repo.pending(1,Marketplace.WILDBERRIES).getFirst().dispatchedAt());assertEquals(actual,repo.history(1,Marketplace.WILDBERRIES).getFirst().dispatchedAt());
    }

}
