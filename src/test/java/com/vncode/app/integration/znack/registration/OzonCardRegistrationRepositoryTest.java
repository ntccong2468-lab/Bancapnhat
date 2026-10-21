package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import static org.junit.jupiter.api.Assertions.*;

class OzonCardRegistrationRepositoryTest {
    @TempDir Path root;com.vncode.app.models.Shop shop;OzonCardRegistrationRepository repository;ZnackRegistrationScope scope;
    final ProductKey key=new ProductKey(2,Marketplace.OZON,"501","offer-501");
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        repository=new OzonCardRegistrationRepository();scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(2,"Fixture")).getSettings());
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    void claim(){assertTrue(repository.claim(shop,key,"9001","",scope,false));}
    @Test void nativeIdentityDoesNotCollideWithWbChrtAndMigrationPreservesExistingCheckpoints()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Fixture WB','fixture','WILDBERRIES')");
            s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,status,created_at,updated_at) VALUES(1,9001,501,'00000000000024','PUBLISHED','fixture','fixture')");
            OzonCardRegistrationRepository.initialize(c);
        }
        claim();assertEquals("9001",repository.find(key).sku());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT gtin,status FROM znack_card_registrations WHERE shop_id=1 AND chrt_id=9001")){assertTrue(r.next());assertEquals("00000000000024",r.getString(1));assertEquals("PUBLISHED",r.getString(2));}
        cleanDatabase();
    }
    @Test void bulkLookupScopesOneThousandProductsAcrossTenShops()throws Exception {
        var keys=new ArrayList<ProductKey>();
        try(var c=Database.getConnection();var shops=c.prepareStatement("INSERT INTO shops(id,name,api_key,client_id,marketplace) VALUES(?,'Fixture','fixture','client','OZON')");var insert=c.prepareStatement("INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,scope_json,owner,created_at,updated_at) VALUES(?,?,?,?,'READY_TO_SIGN','{}','fixture','fixture','fixture')")){
            for(int id=10;id<20;id++){shops.setInt(1,id);shops.addBatch();}shops.executeBatch();
            for(int id=10;id<20;id++)for(int i=0;i<1000;i++){String product=Integer.toString(1000+i);insert.setInt(1,id);insert.setString(2,product);insert.setString(3,"offer-"+product);insert.setString(4,Integer.toString(9000+i));insert.addBatch();if(id==10)keys.add(new ProductKey(id,Marketplace.OZON,product,"offer-"+product));}insert.executeBatch();
        }
        var rows=repository.findMany(keys);assertEquals(1000,rows.size());assertTrue(rows.keySet().stream().allMatch(k->k.shopId()==10));
        assertThrows(IllegalArgumentException.class,()->repository.findMany(List.of(keys.getFirst(),new ProductKey(11,Marketplace.OZON,"1000","offer-1000"))));cleanDatabase();
    }
    @Test void generatedAndFeedStatesAreClaimedOnceBeforeAnyRemoteProgress() {
        claim();assertTrue(repository.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN));assertFalse(repository.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN));
        repository.generated(key,GtinWriteTestFixture.GTIN,"{\"gtin\":\"00000000000017\",\"good_name\":\"Fixture\",\"tnved\":\"6204\"}");
        assertTrue(repository.transition(key,Status.READY_TO_SUBMIT,Status.FEED_SUBMITTING));assertFalse(repository.transition(key,Status.READY_TO_SUBMIT,Status.FEED_SUBMITTING));
        repository.submitted(key,"feed-fixture");assertEquals(Status.FEED_SUBMITTED,repository.find(key).status());
        assertFalse(repository.claim(shop,key,"9001","",scope,false));
    }
    @Test void signerChangeBlocksProgressAndRowDisplayRedactsDocumentAndEnterprise() {
        claim();var row=repository.find(key);assertFalse(row.toString().contains("7700000000"));assertFalse(row.toString().contains("AAAAAAAA"));
        GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->repository.guard(shop,key,scope));
        assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->repository.claim(shop,key,"9001","",scope,false));
    }
    @Test void actualDocumentBytesAreFrozenDefensivelyAndKnownUnsentCanReleaseClaim() {
        claim();repository.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN);repository.generated(key,GtinWriteTestFixture.GTIN,"{}");repository.transition(key,Status.READY_TO_SUBMIT,Status.FEED_SUBMITTING);repository.submitted(key,"feed");repository.progress(key,Status.READY_TO_SIGN,91L);
        var document=new ZnackNationalCatalogService.SigningDocument(91,"<good>fixture</good>".getBytes(java.nio.charset.StandardCharsets.UTF_8));repository.prepareSignature(key,document);
        byte[] read=repository.find(key).signatureXml();read[0]=0;assertArrayEquals(document.xml(),repository.find(key).signatureXml());
        assertTrue(repository.claimSignature(key,document.sha256()));assertFalse(repository.claimSignature(key,document.sha256()));repository.releaseSignature(key,document.sha256());assertEquals("PREPARED",repository.find(key).signatureState());assertTrue(repository.claimSignature(key,document.sha256()));
    }
    @Test void knownUnsentRebuildRetainsIdentityAndNeverResetsAnUncertainPost() {
        claim();assertTrue(repository.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN));
        repository.generated(key,GtinWriteTestFixture.GTIN,"{\"gtin\":\"00000000000017\",\"good_name\":\"Old\",\"tnved\":\"6204\"}");
        repository.uncertain(key,Status.PRE_SUBMIT_ERROR,"REGISTRATION_VALIDATION_FAILED");
        var saved=repository.find(key);
        assertThrows(IllegalArgumentException.class,()->repository.rebuildKnownUnsent(shop,key,saved,scope,"9001","{\"gtin\":\"00000000000024\"}"));
        assertTrue(repository.transition(key,Status.PRE_SUBMIT_ERROR,Status.FEED_SUBMITTING));
        assertFalse(repository.rebuildKnownUnsent(shop,key,saved,scope,"9001","{\"gtin\":\"00000000000017\",\"good_name\":\"Fixed\",\"tnved\":\"6204\"}"));
        assertEquals(Status.FEED_SUBMITTING,repository.find(key).status());
        assertEquals(GtinWriteTestFixture.GTIN,repository.find(key).gtin());
    }
    private void readyToPublish(){claim();assertTrue(repository.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN));repository.generated(key,GtinWriteTestFixture.GTIN,"{\"gtin\":\""+GtinWriteTestFixture.GTIN+"\",\"good_name\":\"Fixture\",\"tnved\":\"6204\"}");assertTrue(repository.transition(key,Status.READY_TO_SUBMIT,Status.FEED_SUBMITTING));repository.submitted(key,"feed");repository.progress(key,Status.READY_TO_SIGN,91L);}
    private ZnackNationalCatalogService.ObservedCard publication(long id,boolean unit){var details=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of(Long.toString(id)),"@catalog_status",List.of("published"),"article",List.of("Fixture"),"color",List.of("Blue"),"size",List.of("52")),List.of(),List.of());return new ZnackNationalCatalogService.ObservedCard(id,GtinWriteTestFixture.GTIN,"published",new ZnackNationalCatalogService.FeedProgress("Signed",id,List.of()),details,java.time.Instant.now().minusSeconds(1),unit);}
    @Test void aLegacyPublicationCannotEraseGoodEvidenceOrGrantAuthorityWithoutIt()throws Exception{
        readyToPublish();var catalog=new com.vncode.app.features.gtinsync.GtinSyncRepository();var before=catalog.catalogItem(shop.getId(),Marketplace.OZON,GtinWriteTestFixture.GTIN);repository.publish(shop,key,scope,91L);assertEquals(before,catalog.catalogItem(shop.getId(),Marketplace.OZON,GtinWriteTestFixture.GTIN));
        try(var c=Database.getConnection();var p=c.prepareStatement("DELETE FROM gtin_sync_catalog WHERE shop_id=? AND marketplace='OZON'")){p.setInt(1,shop.getId());p.executeUpdate();}repository.publish(shop,key,scope,91L);assertThrows(IllegalArgumentException.class,()->repository.complete(shop,key,scope,GtinWriteTestFixture.GTIN));assertEquals("",repository.find(key).activeGtin());
    }
    @Test void actualPublicationPreservesItsIdentityDetailsAndReadTime()throws Exception{
        readyToPublish();var proof=publication(91,true);repository.publish(shop,key,scope,proof);var catalog=new com.vncode.app.features.gtinsync.GtinSyncRepository();var stored=catalog.catalogItem(shop.getId(),Marketplace.OZON,GtinWriteTestFixture.GTIN);assertEquals(proof.details(),stored.details());assertEquals(proof.checkedAt(),stored.syncedAt());assertTrue(stored.tradeUnit());assertEquals("Blue",stored.color());repository.publish(shop,key,scope,91L);assertEquals(stored,catalog.catalogItem(shop.getId(),Marketplace.OZON,GtinWriteTestFixture.GTIN));assertTrue(repository.complete(shop,key,scope,GtinWriteTestFixture.GTIN));
    }
    @Test void aDifferentCatalogIdentityOrScopeCannotCompleteTheNativeCandidate(){
        readyToPublish();repository.publish(shop,key,scope,publication(91,true));var changed=publication(92,true);assertThrows(IllegalStateException.class,()->repository.publish(shop,key,scope,changed));
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(shop.getId(),Marketplace.OZON,List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(changed.gtin(),"Fixture","Blue","52",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),changed.checkedAt(),changed.details())));assertThrows(IllegalArgumentException.class,()->repository.complete(shop,key,scope,GtinWriteTestFixture.GTIN));assertEquals("",repository.find(key).activeGtin());
        GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->repository.publish(shop,key,scope,publication(91,true)));
    }
    @Test void aCardOnlyPublicationCannotResolveAnOutstandingPermitRegistryCheck(){
        readyToPublish();var details=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published"),"@documents_status",List.of("REGISTRY_DOCUMENTS_PENDING")),List.of(),List.of(new com.vncode.app.features.catalogsync.CatalogProductData.Document("DECLARATION","D","2025-01-01","",true,"",Map.of())));var catalog=new com.vncode.app.features.gtinsync.GtinSyncRepository();catalog.saveCatalog(shop.getId(),Marketplace.OZON,List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("00000017","Fixture","Blue","52",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),java.time.Instant.now(),details)));
        repository.publish(shop,key,scope,publication(91,true));var stored=catalog.catalogItem(shop.getId(),Marketplace.OZON,GtinWriteTestFixture.GTIN);assertEquals(Status.PUBLISHED,repository.find(key).status());assertEquals("REGISTRY_DOCUMENTS_PENDING",stored.details().single("@documents_status"));assertFalse(stored.details().documents().isEmpty());assertThrows(IllegalArgumentException.class,()->repository.complete(shop,key,scope,GtinWriteTestFixture.GTIN));assertEquals("",repository.find(key).activeGtin());
    }
    private static void cleanDatabase()throws Exception{try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("PRAGMA integrity_check")){assertTrue(r.next());assertEquals("ok",r.getString(1));}try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}}
}
