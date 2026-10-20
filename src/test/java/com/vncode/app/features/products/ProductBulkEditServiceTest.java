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
    @Test void perProductValuesSurvivePersistedPreviewAndReopeningBeforeTheActualSave()throws Exception {
        var second=new ProductKey(1,Marketplace.WILDBERRIES,"124","");var initial=snapshot;
        var secondCard=new ProductSnapshot(second,"live:second","Second","Keep description","Old","second","9",List.of(),List.of(),List.of(),initial.dimensions(),List.of(),Instant.now());
        ProductSnapshotReader reader=(s,k)->k.equals(key)?initial:secondCard;
        var sent=new ArrayList<String>();adapter=new FakeAdapter(){@Override public RemoteSubmission submitUpdate(Shop s,ProductEditPreview preview)throws IOException{sent.add(preview.preview().requestJson());return super.submitUpdate(s,preview);}};
        var service=new ProductBulkEditService(repo,reader,s->adapter);
        var preview=service.preview(shop,List.of(key,second),new ProductBulkEdit("brand",List.of("Fallback"),null,Map.of(key,List.of("First brand"),second,List.of("Second brand"))));
        var reopened=new ProductBulkEditService(new ProductEditRepository(),reader,s->adapter);reopened.apply(shop,preview.id(),true);
        assertEquals(2,adapter.writes.get());assertTrue(sent.stream().anyMatch(body->body.contains("First brand")));assertTrue(sent.stream().anyMatch(body->body.contains("Second brand")));assertTrue(sent.stream().noneMatch(body->body.contains("Fallback")));
        reopened.apply(shop,preview.id(),true);assertEquals(2,adapter.writes.get(),"Opening and applying the saved job again must not resend");
    }
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
    @Test void openingAnotherServiceDoesNotRecoverAnActiveSendingClaim()throws Exception {
        var p=service.preview(shop,List.of(key),edit());var item=p.items().getFirst();assertTrue(repo.claim(p.id(),item));
        new ProductBulkEditService(repo,(s,k)->snapshot,s->adapter);
        assertEquals(CopyState.SENDING,repo.items(p.id()).getFirst().state());
        repo.recoverIncomplete();assertEquals(CopyState.RECONCILE_REQUIRED,repo.items(p.id()).getFirst().state());
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
    @Test void batchOverOneHundredCardsReportsEverySendAndReadback()throws Exception{
        var keys=new java.util.ArrayList<ProductKey>();for(int n=0;n<120;n++)keys.add(new ProductKey(1,Marketplace.WILDBERRIES,Integer.toString(1000+n),"1"));
        var base=snapshot;var bulk=new ProductBulkEditService(repo,(s,k)->new ProductSnapshot(k,base.revision(),base.title(),base.description(),base.brand(),k.cardId(),base.categoryId(),base.media(),base.attributes(),base.variants(),base.dimensions(),base.documents(),base.checkedAt(),base.metadata()),s->adapter);
        var progress=new java.util.ArrayList<ProductBulkEditService.Progress>();
        var preview=bulk.preview(shop,keys,edit(),progress::add);assertEquals(120,preview.items().size());
        var sent=bulk.apply(shop,preview.id(),true,progress::add);assertEquals(120,sent.items().size());assertEquals(120,adapter.writes.get());
        assertTrue(progress.stream().filter(p->p.step().equals("SENDING")).allMatch(p->p.succeeded()==0));
        adapter.ready=true;var result=bulk.reconcile(shop,preview.id(),progress::add);assertTrue(result.items().stream().allMatch(i->i.state()==CopyState.RECONCILED));
        for(String phase:List.of("VALIDATING","SENDING","READBACK"))assertTrue(progress.stream().anyMatch(p->p.step().equals(phase)&&p.completed()==120&&p.total()==120),phase);
        var finished=progress.getLast();assertEquals(120,finished.succeeded());assertEquals(0,finished.remaining());assertEquals(0,finished.failed());assertEquals(0,finished.pending());
        bulk.reconcile(shop,preview.id(),p->{throw new IllegalStateException("Observer failure");});assertEquals(120,adapter.writes.get());
    }
    @Test void rejectedAndConfirmedCardsHaveSeparateCountersAndPendingIsNeverSuccess()throws Exception{
        var second=new ProductKey(1,Marketplace.WILDBERRIES,"124","1");var base=snapshot;
        adapter=new FakeAdapter(){@Override public RemoteResult reconcile(Shop shop,RemoteSubmission request){return request.articles().contains("124")?new RemoteResult(CopyState.FAILED,List.of(),List.of(new CopyIssue("PRODUCT_UPDATE_REJECTED","fixture",true)),Map.of()):super.reconcile(shop,request);}};
        var bulk=new ProductBulkEditService(repo,(s,k)->new ProductSnapshot(k,base.revision(),base.title(),base.description(),base.brand(),k.cardId(),base.categoryId(),base.media(),base.attributes(),base.variants(),base.dimensions(),base.documents(),base.checkedAt(),base.metadata()),s->adapter);
        var preview=bulk.preview(shop,List.of(key,second),edit());var changes=new ArrayList<ProductBulkEditService.Progress>();bulk.apply(shop,preview.id(),true,changes::add);
        assertEquals(0,changes.getLast().succeeded());assertEquals(2,changes.getLast().pending());
        bulk.reconcile(shop,preview.id(),changes::add);var waiting=changes.getLast();assertEquals(0,waiting.succeeded());assertEquals(1,waiting.failed());assertEquals(1,waiting.remaining());assertEquals(1,waiting.pending());
        adapter.ready=true;bulk.reconcile(shop,preview.id(),changes::add);var done=changes.getLast();assertEquals(1,done.succeeded());assertEquals(1,done.failed());assertEquals(0,done.remaining());assertEquals(0,done.pending());
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
    @Test void unresolvedBulkEditKeepsShopAndJournal()throws Exception{
        adapter.timeout=true;var p=service.preview(shop,List.of(key),edit());service.apply(shop,p.id(),true);
        assertThrows(IllegalStateException.class,()->new com.vncode.app.features.shop.ShopRepository().delete(shop.getId()));
        assertEquals(CopyState.RECONCILE_REQUIRED,service.result(shop,p.id()).items().getFirst().state());
    }
    @Test void reconciledEditRefreshesItsLocalDetails()throws Exception{
        var old=snapshot;var current=new java.util.concurrent.atomic.AtomicReference<>(new ProductSnapshot(old.key(),old.revision(),old.title(),old.description(),old.brand(),old.vendorArticle(),old.categoryId(),old.media(),old.attributes(),old.variants(),old.dimensions(),old.documents(),old.checkedAt(),Map.of("owner",ListingIdentity.fingerprint(shop))));
        var cache=new ProductRepository();var service=new ProductBulkEditService(repo,(s,k)->{var f=current.get();cache.saveVerifiedSnapshot(s,f);return f;},s->adapter);
        var p=service.preview(shop,List.of(key),edit());service.apply(shop,p.id(),true);adapter.ready=true;
        var f=current.get();current.set(new ProductSnapshot(f.key(),"live:updated",f.title(),"New details", "New",f.vendorArticle(),f.categoryId(),f.media(),f.attributes(),f.variants(),f.dimensions(),f.documents(),Instant.now(),f.metadata()));
        assertEquals(CopyState.RECONCILED,service.reconcile(shop,p.id()).items().getFirst().state());
        assertEquals("New details",cache.localSnapshot(key).orElseThrow().description());
    }
    @Test void laterCardChangedDuringFirstSendNeverUsesStalePayload()throws Exception{
        var second=new ProductKey(1,Marketplace.WILDBERRIES,"124","2");
        var original=snapshot;var fresh=new java.util.concurrent.atomic.AtomicReference<>(new ProductSnapshot(second,"live:original","Second","Keep description","Old","second","9",List.of(),List.of(),List.of(),original.dimensions(),List.of(),Instant.now()));
        var service=new ProductBulkEditService(repo,(s,k)->k.equals(key)?original:fresh.get(),s->adapter);
        var p=service.preview(shop,List.of(key,second),edit());
        adapter.afterSend=()->{var old=fresh.get();fresh.set(new ProductSnapshot(old.key(),"live:changed-by-completed-gtin-edit",old.title(),"New description",old.brand(),old.vendorArticle(),old.categoryId(),old.media(),old.attributes(),old.variants(),old.dimensions(),old.documents(),Instant.now()));};
        assertThrows(IllegalStateException.class,()->service.apply(shop,p.id(),true));
        assertEquals(1,adapter.writes.get());
        var state=service.result(shop,p.id()).items().stream().filter(i->i.key().equals(second)).findFirst().orElseThrow().state();
        assertEquals(CopyState.NEEDS_DATA,state,"Definitely unsent changed card must be available for a fresh preview");
    }
    class FakeAdapter implements ListingTargetAdapter {
        AtomicInteger writes=new AtomicInteger();boolean timeout,ready;Map<String,List<String>> changes;Runnable afterSend=()->{};
        public List<TargetCategory> categories(Shop s){return List.of();}
        public List<TargetAttribute> attributes(Shop s,String c){return List.of(new TargetAttribute("723","Состав","1",false,"",1,"","",false));}
        public CopyPreview preview(Shop s,ProductSnapshot f,CopyOptions o){throw new UnsupportedOperationException();}
        public ProductEditPreview previewUpdate(Shop s,ProductSnapshot f,Map<String,List<String>> changes,List<ProductDocument> docs){
            this.changes=changes;String request=ProductJson.GSON.toJson(changes),owner=ListingIdentity.fingerprint(s);var o=new CopyOptions(changes.keySet(),Set.of(),f.categoryId(),Map.of(),changes,Map.of(),null,"RUB",List.of(),null);
            return new ProductEditPreview(f.key(),f.revision(),new CopyPreview(s.getId(),s.getMarketplace(),owner,f.key(),f.revision(),CopyPreview.hash(request,"[]","[]",owner,f.revision(),"UPDATE"),request,"[]","[]",Map.of(),List.of(),List.of(f.vendorArticle()),o,"UPDATE"));
        }
        public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p)throws IOException {writes.incrementAndGet();afterSend.run();if(timeout)throw new IOException("lost");return submit(s,p.preview());}
        public RemoteSubmission submit(Shop s,CopyPreview p){return new RemoteSubmission(s.getId(),s.getMarketplace(),p.owner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
        public RemoteResult reconcile(Shop s,RemoteSubmission r){return ready?new RemoteResult(CopyState.RECONCILED,List.of("123"),List.of(),Map.of("article","123")):RemoteResult.pending();}
        public RemoteSubmission finishAssets(Shop s,RemoteSubmission r){return r.withPhase("ASSETS_SENT",r.identities());}
    }
}
