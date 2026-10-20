package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackAutoSyncServiceTest {
    @TempDir Path root;Shop shop;ProductKey key;ProductSnapshot snapshot;
    final List<String> barcodes=new ArrayList<>(List.of("legacy"));int writes,sourceReads;boolean apply=true,failVerification;
    ZnackAutoSyncService service;CatalogSyncService catalog;GtinSyncModels.RegisteredGtin observation;
    void init(Marketplace market)throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(market);
        key=new ProductKey(shop.getId(),market,"101",market==Marketplace.OZON?"offer-a":"201");
        try(var c=Database.getConnection();var s=c.createStatement()){
            if(market==Marketplace.OZON)s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,article,color,size,synced_at) VALUES(2,'101','offer-a','501','Synthetic','Fixture','Blue','M','now')");
            else{
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,synced_at) VALUES(1,101,'Synthetic','Fixture','now')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,201,101,'M')");
                s.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,14177449,'Цвет','[\"Blue\"]')");
            }
        }
        observation=new GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"Fixture","Blue","M",true,true,RegisteredGtinProof.source(shop),Instant.now());
        snapshot=new ProductSnapshot(key,"live:fixture","Synthetic","","","Fixture","1",List.of(),List.of(),List.of(new ProductVariant(key.variantId(),"Fixture","Blue","M","",false)),null,List.of(),Instant.now(),Map.of("sku","501","owner",ListingIdentity.fingerprint(shop)));
        GtinMarketplaceAdapter adapter=new GtinMarketplaceAdapter(){
            public GtinSyncModels.Capability capability(GtinSyncModels.ProductKey key){return new GtinSyncModels.Capability(true,false,"");}
            public GtinSyncModels.ProductSnapshot read(GtinSyncModels.ProductKey key){return new GtinSyncModels.ProductSnapshot(key,"Fixture","Blue","M",barcodes,ListingIdentity.hash(barcodes.toString()),Instant.now());}
            public boolean conflicts(GtinSyncModels.ProductKey key,String gtin){return false;}
            public GtinSyncModels.Submission submit(GtinSyncModels.Preview preview){writes++;if(apply)barcodes.add(preview.newGtin());return new GtinSyncModels.Submission("");}
            public GtinSyncModels.Verification verify(GtinSyncModels.Preview preview,GtinSyncModels.Submission submission)throws Exception{if(failVerification)throw new java.net.SocketTimeoutException();return barcodes.contains(preview.newGtin())?GtinSyncModels.Verification.APPLIED:GtinSyncModels.Verification.PENDING;}
        };
        var target=new ReadOnlyListing();var repository=new GtinSyncRepository();
        catalog=new CatalogSyncService(new CatalogSyncRepository(),repository,new ProductBulkEditService(new ProductEditRepository(),(s,k)->snapshot,s->target),(s,k)->snapshot,s->target,k->adapter,RegisteredGtinProof::requireCurrent);
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
        service=new ZnackAutoSyncService((s,issues)->{sourceReads++;return List.of(observation);},(s,k)->snapshot,catalog);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void automaticMatchingReportsChangedTnvedGroupInsteadOfReusingTheOldCard()throws Exception{
        init(Marketplace.WILDBERRIES);
        observation=new GtinSyncModels.RegisteredGtin(observation.gtin(),observation.article(),observation.color(),observation.size(),true,true,observation.source(),Instant.now(),new CatalogProductData(Map.of("tnved",List.of("6103109000")),List.of(),List.of()));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,88,'ТНВЭД','[\"6112110000\"]')");}
        String run=service.review(shop);var item=new SyncRunRepository().items(run,0,10).getFirst();assertEquals("NEEDS_ACTION",item.state());assertEquals("GTIN_TNVED_GROUP_CHANGED",item.error());assertEquals(0,writes);
    }
    @Test void wbSourceRequiresReviewThenChainsToSingleVerifiedGtinWriteAndDurableReport()throws Exception{pipeline(Marketplace.WILDBERRIES);}
    @Test void reportingFailureCannotReplaceTheOriginalCatalogReadFailure()throws Exception{
        init(Marketplace.WILDBERRIES);var original=new java.io.IOException("CATALOG_READ_FAILED");
        service=new ZnackAutoSyncService((s,issues)->{
            try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("CREATE TRIGGER reject_failure_report BEFORE UPDATE OF state ON sync_runs WHEN NEW.state='FAILED' BEGIN SELECT RAISE(ABORT,'report unavailable'); END");}
            throw original;
        },(s,k)->snapshot,catalog);
        assertSame(original,assertThrows(java.io.IOException.class,()->service.step(shop,"")));
        assertEquals(1,original.getSuppressed().length);assertEquals("SYNC_STORAGE",original.getSuppressed()[0].getMessage());assertEquals(0,writes);
    }
    @Test void ozonSourceRequiresReviewWithAlphanumericOfferAndExactSku()throws Exception{pipeline(Marketplace.OZON);}
    @Test void ozonSkuProposalIsPersistedAndRechecksFreshBrandBeforeConfirmation()throws Exception{
        init(Marketplace.OZON);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_products SET offer_id='152DASH-52',article='152DASH-52',name='Костюм',color='Brown',size='52',manufacturer_size='XL',russian_size='52' WHERE shop_id=2");}
        key=new ProductKey(shop.getId(),Marketplace.OZON,"101","152DASH-52");
        observation=new GtinSyncModels.RegisteredGtin(observation.gtin(),"152DASHOA","Brown","52",true,true,observation.source(),Instant.now(),new CatalogProductData(Map.of("@size_type",List.of("РОССИЯ"),"brand",List.of("DASHOA"),"title",List.of("Костюм")),List.of(),List.of()));
        snapshot=new ProductSnapshot(key,"live:fixture","Костюм","","DASHOA","152DASH-52","1",List.of(),List.of(),List.of(new ProductVariant(key.variantId(),key.variantId(),"Brown","XL","",false,"52")),null,List.of(),Instant.now(),Map.of("sku","501","owner",ListingIdentity.fingerprint(shop)));
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);service.step(shop,"");
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();var items=reports.items(run.id(),0,10);
        assertEquals(1,items.size());assertTrue(items.getFirst().evidence().contains("OZON_SELLER_SKU_MODEL_SIZE"));
        var review=new ZnackGtinReviewService(s->{},service,(s,k)->snapshot);assertEquals(1,review.select(shop,run.id(),items).size());
        snapshot=new ProductSnapshot(snapshot.key(),snapshot.revision(),snapshot.title(),snapshot.description(),"OTHER",snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),snapshot.variants(),snapshot.dimensions(),snapshot.documents(),snapshot.checkedAt(),snapshot.metadata());
        assertEquals("OZON_SKU_GTIN_MISMATCH",assertThrows(IllegalArgumentException.class,()->review.select(shop,run.id(),items)).getMessage());assertEquals(0,writes);
    }
    @Test void onlyExplicitlyRegisteredExactWbCardIsAutomaticallyLinkedThenVerified()throws Exception{automaticRegistration(Marketplace.WILDBERRIES);}
    @Test void onlyExplicitlyRegisteredExactOzonSkuIsAutomaticallyLinkedThenVerified()throws Exception{automaticRegistration(Marketplace.OZON);}
    private void automaticRegistration(Marketplace market)throws Exception{
        init(market);
        var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var scope=com.vncode.app.integration.znack.registration.ZnackRegistrationScope.fromSettings(settings);
        String now=Instant.now().toString();
        if(market==Marketplace.WILDBERRIES){
            try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,feed_id,good_id,status,created_at,updated_at) VALUES(1,201,101,?,'synthetic-feed',91,'PUBLISHED',?,?)")){
                p.setString(1,observation.gtin());p.setString(2,now);p.setString(3,now);p.executeUpdate();
            }
            new com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository().bindScope(shop,201,scope);
        }else{
            try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,gtin,feed_id,good_id,scope_json,owner,created_at,updated_at) VALUES(2,'101','offer-a','501','PUBLISHED',?,'synthetic-feed',91,?,?,?,?)")){
                p.setString(1,observation.gtin());p.setString(2,ProductJson.GSON.toJson(scope));p.setString(3,ListingIdentity.fingerprint(shop));p.setString(4,now);p.setString(5,now);p.executeUpdate();
            }
        }
        com.vncode.app.integration.znack.registration.RegistrationMarketplaceConsent.set(shop,key,observation.gtin(),true);
        var candidate=new com.vncode.app.integration.znack.registration.RegistrationMarketplaceConsent.Candidate(key.cardId(),key.variantId(),observation.gtin());
        assertEquals(Set.of(candidate),com.vncode.app.integration.znack.registration.RegistrationMarketplaceConsent.publishedCandidates(shop));
        com.vncode.app.integration.znack.registration.RegistrationMarketplaceConsent.set(shop,key,observation.gtin(),false);
        assertTrue(com.vncode.app.integration.znack.registration.RegistrationMarketplaceConsent.publishedCandidates(shop).isEmpty());
        com.vncode.app.integration.znack.registration.RegistrationMarketplaceConsent.set(shop,key,observation.gtin(),true);
        service.step(shop,"");assertEquals(0,writes);
        service.step(shop,"");assertEquals(0,writes,"Authorization and linking must be durable before dispatch");
        var runs=new SyncRunRepository();var active=runs.active(shop,"znack_marketplace").orElseThrow();
        var item=runs.items(active,0,10).getFirst();assertFalse(item.childJob().isBlank());assertTrue(catalog.read(shop,item.childJob()).confirmed());
        for(int n=0;n<5;n++)if(service.step(shop,"").complete())break;
        assertEquals(1,writes);assertEquals("SUCCEEDED",runs.items(active,0,10).getFirst().state());assertTrue(barcodes.contains(observation.gtin()));
    }
    private void pipeline(Marketplace market)throws Exception{
        init(market);assertFalse(service.step(shop,"").complete());assertEquals(0,writes);assertEquals(1,sourceReads);
        assertTrue(service.step(shop,"").complete());assertEquals(0,writes,"A source refresh must never approve a write");
        approve();
        for(int turn=0;turn<4;turn++)if(service.step(shop,"").complete())break;
        assertEquals(1,writes);assertTrue(barcodes.contains(GtinWriteTestFixture.GTIN));
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();assertEquals("SUCCEEDED",run.state());
        var item=reports.items(run.id(),0,10).getFirst();assertEquals("SUCCEEDED",item.state());assertFalse(item.childJob().isBlank());
        assertEquals(market==Marketplace.OZON?"501":"",item.sku());assertEquals(1,sourceReads);
    }
    private void approve()throws Exception {
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();
        var selection=new ZnackGtinReviewService(s->{},service,(s,k)->snapshot).select(shop,run.id(),reports.items(run.id(),0,10));
        var plan=catalog.preview(shop,selection,new CatalogSyncModels.Options(Set.of(CatalogSyncModels.Field.GTIN),Map.of()));
        assertThrows(IllegalArgumentException.class,()->catalog.run(shop,plan.id(),false));assertEquals(0,writes);
        var result=catalog.run(shop,plan.id(),true);reports.linkReviewed(shop,run.id(),result);
    }
    @Test void changedOzonSkuStopsBeforeAnyMarketplaceWrite()throws Exception{
        init(Marketplace.OZON);service.step(shop,"");
        snapshot=new ProductSnapshot(snapshot.key(),snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),snapshot.variants(),null,List.of(),Instant.now(),Map.of("sku","different-sku"));
        var reports=new SyncRunRepository();var item=reports.items(reports.list(shop.getId(),0,10).getFirst().id(),0,10).getFirst();
        var review=new ZnackGtinReviewService(s->{},service,(s,k)->snapshot);
        assertEquals("OZON_SKU_CHANGED",assertThrows(IllegalArgumentException.class,()->review.select(shop,item.runId(),List.of(item))).getMessage());assertEquals(0,writes);
    }
    @Test void ozonRussianSizeFromTheCatalogIsComparedToTheExplicitRussianSizeOnFreshReadback()throws Exception{
        init(Marketplace.OZON);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_products SET size='52' WHERE shop_id=2");}
        observation=new GtinSyncModels.RegisteredGtin(observation.gtin(),"Fixture","Blue","52",true,true,observation.source(),Instant.now());
        snapshot=new ProductSnapshot(snapshot.key(),snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),List.of(new ProductVariant(key.variantId(),"Fixture","Blue","L","",false,"52")),null,List.of(),Instant.now(),snapshot.metadata());
        service.step(shop,"");approve();
        for(int turn=0;turn<4;turn++)if(service.step(shop,"").complete())break;
        assertEquals(1,writes);assertEquals("SUCCEEDED",new SyncRunRepository().list(shop.getId(),0,10).getFirst().state());
    }
    @Test void wbCatalogRussianSizeUsesObservedWbSizeWhileReviewKeepsNativeManufacturerSize()throws Exception{
        init(Marketplace.WILDBERRIES);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE wb_product_sizes SET wb_size='54' WHERE shop_id=1");}
        observation=new GtinSyncModels.RegisteredGtin(observation.gtin(),"Fixture","Blue","54",true,true,observation.source(),Instant.now(),new CatalogProductData(Map.of("@size_type",List.of("РОССИЯ")),List.of(),List.of()));
        service.step(shop,"");approve();for(int turn=0;turn<4;turn++)if(service.step(shop,"").complete())break;
        assertEquals(1,writes);assertEquals("SUCCEEDED",new SyncRunRepository().list(shop.getId(),0,10).getFirst().state());
    }
    @Test void ozonChangedRussianSizeStillBlocksTheReviewEvenWhenManufacturerSizeIsUnchanged()throws Exception{
        init(Marketplace.OZON);service.step(shop,"");
        snapshot=new ProductSnapshot(snapshot.key(),snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),List.of(new ProductVariant(key.variantId(),"Fixture","Blue","M","",false,"54")),null,List.of(),Instant.now(),snapshot.metadata());
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();
        var review=new ZnackGtinReviewService(s->{},service,(s,k)->snapshot);
        assertEquals("GTIN_VARIANT_CONFLICT",assertThrows(IllegalArgumentException.class,()->review.select(shop,run.id(),reports.items(run.id(),0,10))).getMessage());assertEquals(0,writes);
    }
    @Test void ozonInternationalCatalogCardUsesManufacturerSizeWhileTheVisibleCatalogKeepsRussianSize()throws Exception{
        init(Marketplace.OZON);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_products SET size='52',russian_size='52',manufacturer_size='M' WHERE shop_id=2");}
        observation=new GtinSyncModels.RegisteredGtin(observation.gtin(),"Fixture","Blue","M",true,true,observation.source(),Instant.now(),new CatalogProductData(Map.of("@size_type",List.of("МЕЖДУНАРОДНЫЙ")),List.of(),List.of()));
        snapshot=new ProductSnapshot(snapshot.key(),snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),List.of(new ProductVariant(key.variantId(),"Fixture","Blue","M","",false,"52")),null,List.of(),Instant.now(),snapshot.metadata());
        service.step(shop,"");approve();for(int turn=0;turn<4;turn++)if(service.step(shop,"").complete())break;
        assertEquals(1,writes);assertEquals("SUCCEEDED",new SyncRunRepository().list(shop.getId(),0,10).getFirst().state());
    }
    @Test void wrongSizeDoesNotFallBackToArticleAndDoesNotWrite()throws Exception{
        init(Marketplace.OZON);observation=new GtinSyncModels.RegisteredGtin(observation.gtin(),"Fixture","Blue","XL",true,true,observation.source(),Instant.now());
        service.step(shop,"");service.step(shop,"");assertEquals(0,writes);
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();assertEquals("PARTIAL",run.state());assertEquals("ZNACK_MAPPING_UNVERIFIED",reports.items(run.id(),0,10).getFirst().error());
    }
    @Test void pausedRunCannotStartOrAdvanceWrites()throws Exception{
        init(Marketplace.WILDBERRIES);service.step(shop,"");var reports=new SyncRunRepository();var id=reports.list(shop.getId(),0,10).getFirst().id();
        reports.stage(shop,id,"PAUSED","MATCHING","");assertTrue(service.step(shop,"").complete());assertEquals(0,writes);assertEquals(1,sourceReads);assertEquals(1,reports.list(shop.getId(),0,10).stream().filter(r->r.kind().equals("znack_marketplace")).count());assertEquals("PAUSED",reports.find(id).orElseThrow().state());
    }
    @Test void olderPausedReportCannotStarveANewConfirmedReview()throws Exception{
        init(Marketplace.WILDBERRIES);service.step(shop,"");var reports=new SyncRunRepository();var paused=reports.list(shop.getId(),0,10).getFirst().id();
        reports.stage(shop,paused,"PAUSED","MATCHING","");String next=service.review(shop);approve();
        for(int turn=0;turn<4;turn++)if(service.step(shop,"").complete())break;
        assertEquals("SUCCEEDED",reports.find(next).orElseThrow().state());assertEquals("PAUSED",reports.find(paused).orElseThrow().state());assertEquals(1,writes);
    }
    @Test void repeatedNetworkFailureEndsInAnActionableReportInsteadOfSynchronizingForever()throws Exception{
        init(Marketplace.OZON);service.step(shop,"");
        apply=false;failVerification=true;approve();
        for(int attempt=0;attempt<4;attempt++)service.step(shop,"");
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();assertEquals("PARTIAL",run.state());
        var item=reports.items(run.id(),0,10).getFirst();assertEquals("NEEDS_ACTION",item.state());assertEquals("NETWORK_TIMEOUT",item.error());assertEquals(1,writes,"Readback errors must never replay the confirmed mutation");
    }
    @Test void changedOzonNameAndForeignReportCannotBeSelected()throws Exception {
        init(Marketplace.OZON);service.step(shop,"");var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();var rows=reports.items(run.id(),0,10);
        snapshot=new ProductSnapshot(snapshot.key(),snapshot.revision(),"Different product",snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),snapshot.variants(),null,List.of(),Instant.now(),snapshot.metadata());
        var review=new ZnackGtinReviewService(s->{},service,(s,k)->snapshot);
        assertEquals("OZON_TITLE_CHANGED",assertThrows(IllegalArgumentException.class,()->review.select(shop,run.id(),rows)).getMessage());assertEquals(0,writes);
        var foreign=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        assertThrows(SecurityException.class,()->review.page(foreign,run.id(),0));
    }
    @Test void marketplaceReadFailureCannotPresentOldProductsAsSuccessfullySynchronized()throws Exception {
        init(Marketplace.OZON);var review=new ZnackGtinReviewService(s->{throw new java.net.SocketTimeoutException();},service,(s,k)->snapshot);
        assertThrows(java.net.SocketTimeoutException.class,()->review.prepare(shop));assertEquals(0,sourceReads);assertEquals(0,writes);
    }
    @Test void unpublishedCardCannotReachPreviewOrWrite()throws Exception {
        init(Marketplace.OZON);observation=new GtinSyncModels.RegisteredGtin(observation.gtin(),"Fixture","Blue","M",true,false,observation.source(),Instant.now());service.step(shop,"");
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();var rows=reports.items(run.id(),0,10);
        assertFalse(ZnackGtinReviewService.eligible(rows.getFirst()));
        assertThrows(IllegalArgumentException.class,()->new ZnackGtinReviewService(s->{},service,(s,k)->snapshot).select(shop,run.id(),rows));assertEquals(0,writes);
    }
    static final class ReadOnlyListing implements ListingTargetAdapter {
        public List<TargetCategory> categories(Shop s){return List.of();}
        public List<TargetAttribute> attributes(Shop s,String id){throw new AssertionError("GTIN-only sync does not need metadata dictionaries");}
        public CopyPreview preview(Shop s,ProductSnapshot f,CopyOptions o){throw new AssertionError("No product copy");}
        public ProductEditPreview previewUpdate(Shop s,ProductSnapshot f,Map<String,List<String>> changes,List<ProductDocument> docs){throw new AssertionError("GTIN-only task cannot alter metadata");}
        public RemoteSubmission submit(Shop s,CopyPreview p){throw new AssertionError("No product copy");}
        public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p){throw new AssertionError("No metadata write");}
        public RemoteResult reconcile(Shop s,RemoteSubmission r){throw new AssertionError("No metadata task");}
        public RemoteSubmission finishAssets(Shop s,RemoteSubmission r){throw new AssertionError("No uploads");}
    }
}
