package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackCardRegistrationRepositoryTest {
    @TempDir Path temp;
    ZnackCardRegistrationRepository repository;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());
        Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture'),(2,'Ozon','OZON','fixture')");
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,101,'PANTS','Fixture','2026-10-06T00:00:00Z')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'XL')");
        }
        repository=new ZnackCardRegistrationRepository();
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    Sku current(){return repository.search(new SearchCriteria(1,"",List.of(),"ALL",20,0)).getFirst();}
    @Test void refusesToReplaceAnExistingGtinAndPreservesSubmissionIdentity() {
        repository.saveGenerated(1,current(),"04631993764363","6204",1,"Fixture","{}");
        repository.updateProgress(1,11,Status.FEED_SUBMITTED,"feed-1",91L,null,false);
        assertThrows(IllegalStateException.class,()->repository.saveGenerated(1,current(),
                "04631993764370","6204",1,"Replacement","{}"));
        Sku stored=current();
        assertEquals("04631993764363",stored.gtin());
        assertEquals("feed-1",stored.feedId());
        assertEquals(91L,stored.goodId());
        assertEquals(Status.FEED_SUBMITTED,stored.status());
    }
    @Test void refusesToRecreatePublishedCardsEvenWithTheSameGtin() {
        repository.saveGenerated(1,current(),"04631993764363","6204",1,"Fixture","{}");
        repository.updateProgress(1,11,Status.PUBLISHED,"feed-1",91L,null,true);
        assertThrows(IllegalStateException.class,()->repository.saveGenerated(1,current(),
                "04631993764363","6204",1,"Changed","{\"changed\":true}"));
        assertEquals(Status.PUBLISHED,current().status());
        assertTrue(current().wbUpdated());
        assertEquals("{}",repository.payload(1,11));
    }
    @Test void checkpointsNewRowsAndRejectsTheSameStaleSnapshot() {
        Sku stale=current();
        assertTrue(repository.claim(1,stale,RegistrationAction.REGISTER));
        assertEquals(Status.CHECKING,current().status());
        assertFalse(repository.claim(1,stale,RegistrationAction.REGISTER));
    }
    @Test void repeatedFreshRequestsRequireANewSnapshotEvenWhenStatusIsUnchanged() throws Exception {
        insertPending("CHECKING");
        Sku pending=current();
        assertTrue(repository.claim(1,pending,RegistrationAction.REQUEST_NEW_GTIN));
        assertFalse(repository.claim(1,pending,RegistrationAction.REQUEST_NEW_GTIN));
        assertTrue(current().needsGtinReview());
    }
    @Test void refusesWrongShopAndMarketWithoutInsertingAnything() {
        assertFalse(repository.claim(2,current(),RegistrationAction.REGISTER));
        assertEquals(Status.NOT_CREATED,current().status());
        assertEquals("",repository.payload(2,11));
    }
    @Test void changingFeedIdentityInvalidatesPreviouslyLoadedRows() {
        repository.saveGenerated(1,current(),"04631993764363","6204",1,"Fixture","{}");
        repository.updateProgress(1,11,Status.ERROR,"feed-1",91L,"fixture",false);
        Sku stale=current();
        repository.updateProgress(1,11,Status.ERROR,"feed-2",92L,"fixture",false);
        assertFalse(repository.claim(1,stale,RegistrationAction.REGISTER));
        assertEquals("feed-2",current().feedId());
        assertEquals(92L,current().goodId());
    }
    @Test void deletingAShopCannotEraseAnUnknownAllocationOutcome() throws Exception {
        insertPending("GTIN_REVIEW_REQUIRED");
        assertThrows(IllegalStateException.class,()->new com.vncode.app.features.shop.ShopRepository().delete(1));
        assertEquals(Status.GTIN_REVIEW_REQUIRED,current().status());
    }
    @Test void upgradingSchemaThreePreservesLegacyHistoryAndItsRollbackSnapshot() throws Exception {
        repository.saveGenerated(1,current(),"04631993764363","6204",1,"Fixture","{}");
        repository.updateProgress(1,11,Status.ERROR,"feed-legacy",91L,"fixture",false);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("PRAGMA user_version=3");}
        try(var session=com.vncode.app.shared.LocalDataMigrationGate.prepare(temp,"1.1.33","javafx")) {
            try(var c=Database.getConnection();var s=c.createStatement()) {
                var version=s.executeQuery("PRAGMA user_version");assertEquals(4,version.getInt(1));version.close();
                var integrity=s.executeQuery("PRAGMA integrity_check");assertEquals("ok",integrity.getString(1));integrity.close();
                assertFalse(s.executeQuery("PRAGMA foreign_key_check").next());
            }
            assertEquals("04631993764363",current().gtin());assertEquals("feed-legacy",current().feedId());
            Path snapshot;
            try(var files=java.nio.file.Files.walk(temp.resolve("snapshots"))) {
                snapshot=files.filter(path->path.getFileName().toString().equals("database.db")).findFirst().orElseThrow();
            }
            try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+snapshot);var s=c.createStatement()) {
                var v=s.executeQuery("PRAGMA user_version");assertEquals(3,v.getInt(1));v.close();
                var r=s.executeQuery("SELECT gtin,feed_id FROM znack_card_registrations WHERE shop_id=1 AND chrt_id=11");
                assertTrue(r.next());assertEquals("04631993764363",r.getString(1));assertEquals("feed-legacy",r.getString(2));
            }
        }
    }
    @Test void reusableDraftsExcludeGtinsAlreadyClaimedByAnyLocalShop() throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(3,'WB two','WILDBERRIES','fixture')");
            s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,status,created_at,updated_at) VALUES(3,22,202,'04631993764363','READY_TO_SUBMIT','fixture','fixture')");
        }
        assertTrue(repository.claimedGtins().contains("04631993764363"));
    }
    @Test void publishedCheckpointCannotBeDowngradedOrHaveItsIdentityRewritten() {
        repository.saveGenerated(1,current(),"04631993764363","6204",1,"Fixture","{}");
        repository.updateProgress(1,11,Status.PUBLISHED,"feed-published",91L,null,true);
        repository.updateProgress(1,11,Status.ERROR,"feed-stale",92L,"late failure",false);
        assertEquals(Status.PUBLISHED,current().status());
        assertEquals("feed-published",current().feedId());assertEquals(91L,current().goodId());
        assertTrue(current().wbUpdated());
    }
    void insertPending(String status) throws Exception {
        try(var c=Database.getConnection();var s=c.prepareStatement("""
                INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,status,created_at,updated_at)
                VALUES(1,11,101,?,'2026-10-06T00:00:00Z','2026-10-06T00:00:00Z')
                """)) {s.setString(1,status);s.executeUpdate();}
    }
}
