package com.vncode.app.features.products;

import com.vncode.app.config.Database;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductBulkEditServiceTest {
    @TempDir Path root;
    Shop shop=new Shop(1,"Owner","fixture"),other=new Shop(2,"Other","fixture");
    ProductKey key=new ProductKey(1,Marketplace.WILDBERRIES,"123","1");
    ProductSnapshot snapshot;ProductEditRepository repo;ProductBulkEditService service;FakeAdapter adapter;
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Owner','fixture','WILDBERRIES'),(2,'Other','fixture','WILDBERRIES')");}
        snapshot=new ProductSnapshot(key,"live:original","Title","Keep description","Old","article","9",List.of(),List.of(),List.of(new ProductVariant("1","article","Black","M","",false)),new PackageDimensions(null,null,null,null),List.of(),Instant.now());
        repo=new ProductEditRepository();adapter=new FakeAdapter();service=new ProductBulkEditService(repo,(s,k)->snapshot,s->adapter);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    ProductBulkEdit edit(){return new ProductBulkEdit("brand",List.of("New"),null,Map.of());}
    @Test void cardLevelConflictingSizesCannotSubmit()throws Exception {
        var second=new ProductKey(1,Marketplace.WILDBERRIES,"123","2");
        var e=new ProductBulkEdit("brand",List.of("New"),null,Map.of(key,List.of("A"),second,List.of("B")));
        assertThrows(IllegalArgumentException.class,()->service.preview(shop,List.of(key,second),e));assertEquals(0,adapter.writes.get());
    }
    @Test void previewRequiresFreshMatchingRevision()throws Exception {
        var p=service.preview(shop,List.of(key),edit());var s=snapshot;snapshot=new ProductSnapshot(s.key(),"live:changed",s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),s.attributes(),s.variants(),s.dimensions(),s.documents(),Instant.now());
        assertThrows(IllegalStateException.class,()->service.apply(shop,p.id(),true));assertEquals(0,adapter.writes.get());
    }
    @Test void confirmationRequired()throws Exception {
        var p=service.preview(shop,List.of(key),edit());assertThrows(IllegalArgumentException.class,()->service.apply(shop,p.id(),false));assertEquals(0,adapter.writes.get());
    }
    @Test void documentUploadDoesNotImplyValid()throws Exception {
        var p=service.preview(shop,List.of(key),new ProductBulkEdit("documents",List.of(),List.of(),Map.of()));
        var r=service.apply(shop,p.id(),true);assertFalse(r.documentsVerified());adapter.ready=true;assertFalse(service.reconcile(shop,p.id()).documentsVerified());
    }
    @Test void timeoutNeverResends()throws Exception {
        adapter.timeout=true;var p=service.preview(shop,List.of(key),edit());assertEquals(CopyState.RECONCILE_REQUIRED,service.apply(shop,p.id(),true).items().getFirst().state());
        var restart=new ProductBulkEditService(repo,(s,k)->snapshot,s->adapter);restart.apply(shop,p.id(),true);assertEquals(1,adapter.writes.get());adapter.ready=true;assertEquals(CopyState.RECONCILED,restart.reconcile(shop,p.id()).items().getFirst().state());assertEquals(1,adapter.writes.get());
    }
    @Test void shopChangeDoesNotReassignJob()throws Exception {
        var p=service.preview(shop,List.of(key),edit());assertThrows(SecurityException.class,()->service.apply(other,p.id(),true));assertThrows(SecurityException.class,()->service.apply(new Shop(1,"Owner","rotated"),p.id(),true));assertEquals(0,adapter.writes.get());
    }
    @Test void separateDraftCannotOverwritePendingCard()throws Exception {
        var a=service.preview(shop,List.of(key),edit());var b=service.preview(shop,List.of(key),edit());service.apply(shop,a.id(),true);assertThrows(IllegalStateException.class,()->service.apply(shop,b.id(),true));assertEquals(1,adapter.writes.get());
    }
    @Test void currentCategoryDictionaryResolvesMaterialWithoutHardcodedId()throws Exception {
        var p=service.preview(shop,List.of(key),new ProductBulkEdit("material",List.of("Cotton"),null,Map.of()));assertEquals(Map.of("attribute:723",List.of("Cotton")),adapter.changes);assertTrue(p.publishable());
    }
    @Test void previewShowsActualOldAndNewValue()throws Exception {var p=service.preview(shop,List.of(key),edit());assertEquals(List.of("Old"),p.items().getFirst().before());assertEquals(List.of("New"),p.items().getFirst().changes().get("brand"));}
    @Test void pendingGtinUpdateBlocksCardEdit()throws Exception {
        var p=service.preview(shop,List.of(key),edit());try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO gtin_sync_jobs VALUES('g','hash',1,'WILDBERRIES',0,'now')");s.execute("INSERT INTO gtin_sync_items VALUES('i','g',1,'WILDBERRIES','123','1','{}','RECONCILE_REQUIRED','','now')");}
        assertThrows(IllegalStateException.class,()->service.apply(shop,p.id(),true));assertEquals(0,adapter.writes.get());
    }
    class FakeAdapter implements ListingTargetAdapter {
        AtomicInteger writes=new AtomicInteger();boolean timeout,ready;Map<String,List<String>> changes;
        public List<TargetCategory> categories(Shop s){return List.of();}
        public List<TargetAttribute> attributes(Shop s,String c){return List.of(new TargetAttribute("723","Состав","1",false,"",1,"","",false));}
        public CopyPreview preview(Shop s,ProductSnapshot f,CopyOptions o){throw new UnsupportedOperationException();}
        public ProductEditPreview previewUpdate(Shop s,ProductSnapshot f,Map<String,List<String>> changes,List<ProductDocument> docs){
            this.changes=changes;String request=ProductJson.GSON.toJson(changes),owner=ListingIdentity.fingerprint(s);var o=new CopyOptions(changes.keySet(),Set.of(),f.categoryId(),Map.of(),changes,Map.of(),null,"RUB",List.of(),null);
            return new ProductEditPreview(f.key(),f.revision(),new CopyPreview(s.getId(),s.getMarketplace(),owner,f.key(),f.revision(),CopyPreview.hash(request,"[]","[]",owner,f.revision(),"UPDATE"),request,"[]","[]",Map.of(),List.of(),List.of(f.vendorArticle()),o,"UPDATE"));
        }
        public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p)throws IOException {writes.incrementAndGet();if(timeout)throw new IOException("lost");return submit(s,p.preview());}
        public RemoteSubmission submit(Shop s,CopyPreview p){return new RemoteSubmission(s.getId(),s.getMarketplace(),p.owner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
        public RemoteResult reconcile(Shop s,RemoteSubmission r){return ready?new RemoteResult(CopyState.RECONCILED,List.of("123"),List.of(),Map.of("article","123")):RemoteResult.pending();}
        public RemoteSubmission finishAssets(Shop s,RemoteSubmission r){return r.withPhase("ASSETS_SENT",r.identities());}
    }
}
