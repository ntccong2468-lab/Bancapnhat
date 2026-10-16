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
    final List<String> barcodes=new ArrayList<>(List.of("legacy"));int writes,sourceReads;
    ZnackAutoSyncService service;GtinSyncModels.RegisteredGtin observation;
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
            public GtinSyncModels.Submission submit(GtinSyncModels.Preview preview){writes++;barcodes.add(preview.newGtin());return new GtinSyncModels.Submission("");}
            public GtinSyncModels.Verification verify(GtinSyncModels.Preview preview,GtinSyncModels.Submission submission){return barcodes.contains(preview.newGtin())?GtinSyncModels.Verification.APPLIED:GtinSyncModels.Verification.PENDING;}
        };
        var target=new ReadOnlyListing();var repository=new GtinSyncRepository();
        var catalog=new CatalogSyncService(new CatalogSyncRepository(),repository,new ProductBulkEditService(new ProductEditRepository(),(s,k)->snapshot,s->target),(s,k)->snapshot,s->target,k->adapter,RegisteredGtinProof::requireCurrent);
        service=new ZnackAutoSyncService((s,issues)->{sourceReads++;return List.of(observation);},(s,k)->snapshot,catalog);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void wbSourceAutomaticallyChainsToSingleVerifiedGtinWriteAndDurableReport()throws Exception{pipeline(Marketplace.WILDBERRIES);}
    @Test void ozonSourceAutomaticallyChainsWithAlphanumericOfferAndExactSku()throws Exception{pipeline(Marketplace.OZON);}
    private void pipeline(Marketplace market)throws Exception{
        init(market);assertFalse(service.step(shop,"").complete());assertEquals(0,writes);assertEquals(1,sourceReads);
        for(int turn=0;turn<4;turn++)if(service.step(shop,"").complete())break;
        assertEquals(1,writes);assertTrue(barcodes.contains(GtinWriteTestFixture.GTIN));
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();assertEquals("SUCCEEDED",run.state());
        var item=reports.items(run.id(),0,10).getFirst();assertEquals("SUCCEEDED",item.state());assertFalse(item.childJob().isBlank());
        assertEquals(market==Marketplace.OZON?"501":"",item.sku());assertEquals(1,sourceReads);
    }
    @Test void changedOzonSkuStopsBeforeAnyMarketplaceWrite()throws Exception{
        init(Marketplace.OZON);service.step(shop,"");
        snapshot=new ProductSnapshot(snapshot.key(),snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),snapshot.variants(),null,List.of(),Instant.now(),Map.of("sku","different-sku"));
        service.step(shop,"");assertEquals(0,writes);
        var reports=new SyncRunRepository();var item=reports.items(reports.list(shop.getId(),0,10).getFirst().id(),0,10).getFirst();
        assertEquals("NEEDS_ACTION",item.state());assertEquals("OZON_SKU_CHANGED",item.error());
    }
    @Test void wrongSizeDoesNotFallBackToArticleAndDoesNotWrite()throws Exception{
        init(Marketplace.OZON);observation=new GtinSyncModels.RegisteredGtin(observation.gtin(),"Fixture","Blue","XL",true,true,observation.source(),Instant.now());
        service.step(shop,"");service.step(shop,"");assertEquals(0,writes);
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();assertEquals("PARTIAL",run.state());assertEquals("ZNACK_MAPPING_UNVERIFIED",reports.items(run.id(),0,10).getFirst().error());
    }
    @Test void pausedRunCannotStartOrAdvanceWrites()throws Exception{
        init(Marketplace.WILDBERRIES);service.step(shop,"");var reports=new SyncRunRepository();var id=reports.list(shop.getId(),0,10).getFirst().id();
        reports.stage(shop,id,"PAUSED","MATCHING","");assertTrue(service.step(shop,"").complete());assertEquals(0,writes);assertEquals(1,sourceReads);assertEquals(1,reports.list(shop.getId(),0,10).size());assertEquals("PAUSED",reports.find(id).orElseThrow().state());
    }
    @Test void repeatedNetworkFailureEndsInAnActionableReportInsteadOfSynchronizingForever()throws Exception{
        init(Marketplace.OZON);service.step(shop,"");
        // The live detail no longer contains the selected variant; transport can also fail independently.
        var failed=new ZnackAutoSyncService((s,i)->List.of(observation),(s,k)->{throw new java.net.SocketTimeoutException("fixture only");},new CatalogSyncService());
        for(int attempt=0;attempt<4;attempt++)failed.step(shop,"");
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();assertEquals("PARTIAL",run.state());
        var item=reports.items(run.id(),0,10).getFirst();assertEquals("NEEDS_ACTION",item.state());assertEquals("NETWORK_TIMEOUT",item.error());assertEquals(0,writes);
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
