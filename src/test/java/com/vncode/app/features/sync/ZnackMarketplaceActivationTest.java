package com.vncode.app.features.sync;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.*;
import com.vncode.app.features.catalogsync.CatalogSyncModels.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository;
import com.vncode.app.integration.znack.registration.ZnackRegistrationScope;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real durable readback and candidate writes: local failure cannot erase marketplace truth. */
class ZnackMarketplaceActivationTest {
    @TempDir Path directory;
    private Shop shop;
    private final SyncRunRepository reports=new SyncRunRepository();
    private final GtinSyncRepository gtins=new GtinSyncRepository();
    private CatalogSyncRepository plans;
    private final Map<ProductKey,ProductSnapshot> products=new HashMap<>();
    private final Map<GtinSyncModels.ProductKey,List<String>> actual=new HashMap<>();
    private final AtomicInteger posts=new AtomicInteger(),readbacks=new AtomicInteger();
    private ZnackAutoSyncService worker;
    private CatalogSyncService catalog;
    private String run,plan;

    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void oneFailedCandidateDoesNotHideVerifiedSiblingOrReplayEitherMarketplaceWrite()throws Exception {
        fixture(2);worker.step(shop,run);
        assertEquals(0,posts.get());assertEquals(2,readbacks.get());assertEquals(2L,reports.marketplaceCounts(run).get("UPDATED"));
        var items=reports.items(run,0,10);var failed=items.getFirst();var succeeded=items.getLast();
        assertEquals("RECONCILING",failed.state());assertEquals("LOCAL_ACTIVATION",failed.step());assertEquals("GTIN_LOCAL_ACTIVATION_FAILED",failed.error());
        assertEquals("SUCCEEDED",succeeded.state());assertEquals("CANDIDATE",nativeState(201));assertEquals("ACTIVE",nativeState(202));
        for(var item:items)assertTrue(JsonParser.parseString(item.evidence()).getAsJsonObject().get("marketplaceVerified").getAsBoolean());
        assertTrue(catalog.read(shop,plan).rows().stream().allMatch(row->row.state()==State.SUCCEEDED));
        releaseTrigger();worker.step(shop,run);
        assertEquals(0,posts.get());assertEquals(2,readbacks.get(),"Local activation retry must reuse the verified child result");assertEquals(2L,reports.marketplaceCounts(run).get("UPDATED"));
        assertTrue(reports.items(run,0,10).stream().allMatch(item->item.state().equals("SUCCEEDED")));assertEquals("ACTIVE",nativeState(201));assertEquals("ACTIVE",nativeState(202));
    }

    @Test void singleCandidateRetainsVerifiedCountWhenItsLocalActivationFails()throws Exception {
        fixture(1);worker.step(shop,run);
        var item=reports.items(run,0,10).getFirst();assertEquals("RECONCILING",item.state());assertEquals("GTIN_LOCAL_ACTIVATION_FAILED",item.error());
        assertEquals(1L,reports.marketplaceCounts(run).get("UPDATED"));assertTrue(JsonParser.parseString(item.evidence()).getAsJsonObject().get("marketplaceVerified").getAsBoolean());assertEquals(0,posts.get());assertEquals(1,readbacks.get());
        releaseTrigger();worker.step(shop,run);assertEquals("SUCCEEDED",reports.items(run,0,10).getFirst().state());assertEquals(0,posts.get());assertEquals(1,readbacks.get());
    }

    private void fixture(int count)throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();plans=new CatalogSyncRepository();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        var observations=new ArrayList<GtinSyncModels.RegisteredGtin>();var previews=new ArrayList<GtinSyncModels.Preview>();var reportItems=new ArrayList<SyncRunRepository.Item>();
        var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());String now=Instant.now().toString();
        for(int i=0;i<count;i++){
            String product=Integer.toString(101+i),variant=Integer.toString(201+i),gtin=i==0?GtinWriteTestFixture.GTIN:"00000000000024",size=i==0?"M":"L",article="Fixture-"+i;
            try(var c=Database.getConnection();var s=c.createStatement()){
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,synced_at) VALUES(1,"+product+",'Synthetic','"+article+"','fixture')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,"+variant+","+product+",'"+size+"')");
                s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,"+variant+",'legacy')");
                try(var p=c.prepareStatement("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,feed_id,status,created_at,updated_at) VALUES(1,?,?,?,'synthetic-feed','PUBLISHED',?,?)")){
                    p.setString(1,variant);p.setString(2,product);p.setString(3,gtin);p.setString(4,now);p.setString(5,now);p.executeUpdate();
                }
                try(var p=c.prepareStatement("INSERT INTO znack_card_registration_replacements(shop_id,chrt_id,active_gtin,state,created_at,updated_at) VALUES(1,?,'','CANDIDATE',?,?)")){
                    p.setString(1,variant);p.setString(2,now);p.setString(3,now);p.executeUpdate();
                }
            }
            new ZnackCardRegistrationRepository().bindScope(shop,Long.parseLong(variant),scope);
            var key=new ProductKey(1,Marketplace.WILDBERRIES,product,variant);var nativeKey=new GtinSyncModels.ProductKey(1,Marketplace.WILDBERRIES,product,variant);
            var snapshot=new ProductSnapshot(key,"live:"+product,"Synthetic","","",article,"1",List.of(),List.of(),List.of(new ProductVariant(variant,article,"Blue",size,"",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));
            products.put(key,snapshot);actual.put(nativeKey,List.of("legacy",gtin));
            observations.add(new GtinSyncModels.RegisteredGtin(gtin,article,"Blue",size,true,true,RegisteredGtinProof.source(shop),Instant.now()));
            var before=new GtinSyncModels.ProductSnapshot(nativeKey,article,"Blue",size,List.of("legacy"),"before-"+product,Instant.now());
            previews.add(new GtinSyncModels.Preview(before,GtinSyncModels.Operation.ADD,"",gtin,new GtinSyncModels.Capability(true,false,"")));
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);gtins.saveCatalog(shop.getId(),shop.getMarketplace(),observations);
        // Both POSTs were acknowledged before shutdown; only readback may run after restart.
        UUID queue=gtins.createJob(previews);for(var item:gtins.jobItems(queue)){
            assertTrue(gtins.transition(item.id(),GtinSyncModels.Status.QUEUED,GtinSyncModels.Status.VALIDATING,null));
            assertTrue(gtins.transition(item.id(),GtinSyncModels.Status.VALIDATING,GtinSyncModels.Status.SENDING,null));
            assertTrue(gtins.transition(item.id(),GtinSyncModels.Status.SENDING,GtinSyncModels.Status.AWAITING_VERIFICATION,""));
        }
        GtinMarketplaceAdapter adapter=new GtinMarketplaceAdapter(){
            public GtinSyncModels.Capability capability(GtinSyncModels.ProductKey key){return new GtinSyncModels.Capability(true,false,"");}
            public GtinSyncModels.ProductSnapshot read(GtinSyncModels.ProductKey key){var p=products.get(new ProductKey(key.shopId(),key.marketplace(),key.productId(),key.variantId()));return new GtinSyncModels.ProductSnapshot(key,p.vendorArticle(),"Blue",p.variants().getFirst().size(),actual.get(key),"after-"+key.productId(),Instant.now());}
            public boolean conflicts(GtinSyncModels.ProductKey key,String gtin){return false;}
            public GtinSyncModels.Submission submit(GtinSyncModels.Preview preview){posts.incrementAndGet();throw new AssertionError("An acknowledged GTIN write must never be replayed");}
            public GtinSyncModels.Verification verify(GtinSyncModels.Preview preview,GtinSyncModels.Submission submission){readbacks.incrementAndGet();return actual.get(preview.before().key()).contains(preview.newGtin())?GtinSyncModels.Verification.APPLIED:GtinSyncModels.Verification.PENDING;}
        };
        var target=new ZnackAutoSyncServiceTest.ReadOnlyListing();ProductSnapshotReader reader=(selected,key)->Objects.requireNonNull(products.get(key));
        catalog=new CatalogSyncService(plans,gtins,new ProductBulkEditService(new ProductEditRepository(),reader,selected->target),reader,selected->target,key->adapter,RegisteredGtinProof::requireCurrent);
        var rows=new ArrayList<Row>();for(int i=0;i<count;i++){
            var preview=previews.get(i);var snapshot=products.get(new ProductKey(1,Marketplace.WILDBERRIES,preview.before().key().productId(),preview.before().key().variantId()));String identity=GtinProductIdentity.fingerprint(snapshot);
            rows.add(new Row(UUID.randomUUID().toString(),preview.before().key(),observations.get(i),preview.before().barcodes(),List.of(new Diff("identity",List.of(identity),List.of(identity),"",false)),null,Map.of(),"",queue.toString(),State.GTIN_PENDING,""));
        }
        plan=UUID.randomUUID().toString();var child=new Job(plan,1,Marketplace.WILDBERRIES.name(),ListingIdentity.fingerprint(shop),Set.of(Field.GTIN),Instant.now(),true,false,rows);plans.insert(child);plans.confirm(shop,plan);
        run=reports.begin(shop,"znack_marketplace","MATCHING");for(int i=0;i<count;i++){
            var preview=previews.get(i);reportItems.add(new SyncRunRepository.Item(UUID.randomUUID().toString(),run,preview.before().key().productId(),preview.before().key().variantId(),"",preview.before().article(),"Blue",preview.before().size(),preview.newGtin(),"AWAITING_CONFIRMATION","MATCHING","","","{}"));
        }
        reports.addBatch(shop,run,reportItems);reports.linkReviewed(shop,run,child);assertEquals(0L,reports.marketplaceCounts(run).get("UPDATED"));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("CREATE TRIGGER fail_local_activation BEFORE UPDATE OF state ON znack_card_registration_replacements WHEN OLD.chrt_id=201 AND NEW.state='ACTIVE' BEGIN SELECT RAISE(ABORT,'fixture local activation unavailable'); END");}
        worker=new ZnackAutoSyncService((selected,issues)->{throw new AssertionError("Existing confirmed readback does not reload Catalog");},reader,catalog);
    }
    private String nativeState(long variant)throws Exception{try(var c=Database.getConnection();var p=c.prepareStatement("SELECT state FROM znack_card_registration_replacements WHERE shop_id=1 AND chrt_id=?")){p.setLong(1,variant);try(var r=p.executeQuery()){assertTrue(r.next());return r.getString(1);}}}
    private void releaseTrigger()throws Exception{try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DROP TRIGGER fail_local_activation");}}
}
