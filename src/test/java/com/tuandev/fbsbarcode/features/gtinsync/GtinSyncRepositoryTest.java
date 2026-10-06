package com.tuandev.fbsbarcode.features.gtinsync;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.shared.LocalDataMigrationGate;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class GtinSyncRepositoryTest {
    @TempDir Path temp;
    GtinSyncRepository repo;
    @BeforeEach void setup() throws Exception {
        System.setProperty("wcode.appdata.dir",temp.toString());
        Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','test'),(2,'Ozon','OZON','test')");
        }
        repo=new GtinSyncRepository();
    }
    @AfterEach void clear() { System.clearProperty("wcode.appdata.dir"); }
    static Preview preview(int shop, Marketplace marketplace) {
        var product=new ProductSnapshot(new ProductKey(shop,marketplace,"101","11"),"PANTS","Đen","XL",List.of("old"),"fp",Instant.now());
        return new Preview(product,Operation.ADD,"","00000000000017",new Capability(true,false,""));
    }
    @Test void migratesVersionTwoWithSnapshotAndPreservesData() throws Exception {
        // Schema 3 is additive: remove every new table to recreate the actual schema-2 shape.
        try(var c=Database.getConnection();var s=c.createStatement()) {
            for(String table:List.of("gtin_sync_events","gtin_sync_items","gtin_sync_jobs","gtin_sync_mappings","gtin_sync_catalog"))s.execute("DROP TABLE "+table);
            s.execute("PRAGMA user_version=2");
            assertFalse(s.executeQuery("SELECT name FROM sqlite_master WHERE name LIKE 'gtin_sync_%'").next());
        }
        try(var session=LocalDataMigrationGate.prepare(temp,"1.1.32","javafx")) {
            try(var c=Database.getConnection();var s=c.createStatement()) {
                var v=s.executeQuery("PRAGMA user_version");assertTrue(v.next());assertEquals(3,v.getInt(1));v.close();
                var integrity=s.executeQuery("PRAGMA integrity_check");assertEquals("ok",integrity.getString(1));integrity.close();
                assertFalse(s.executeQuery("PRAGMA foreign_key_check").next());
                var tables=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'gtin_sync_%'");assertEquals(5,tables.getInt(1));tables.close();
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
        assertEquals(2,new com.tuandev.fbsbarcode.features.shop.ShopRepository().findAll().size());
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
}
