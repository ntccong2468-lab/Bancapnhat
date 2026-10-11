package com.vncode.app.features.listingcopy;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ListingCopyServiceTest {
    @TempDir Path root;
    Shop source=new Shop(1,"Source","fixture"),dest=new Shop(2,"Target","fixture"),other=new Shop(3,"Other","fixture");
    ProductSnapshot snapshot;AtomicInteger writes;FakeAdapter adapter;ListingCopyRepository repo;ListingCopyService service;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Source','fixture','WILDBERRIES'),(2,'Target','fixture','WILDBERRIES'),(3,'Other','fixture','WILDBERRIES')");}
        var s=ListingCopyMappingTest.source(Marketplace.WILDBERRIES);snapshot=new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"123","v1"),s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),s.attributes(),s.variants(),s.dimensions(),s.documents(),s.checkedAt(),Map.of());writes=new AtomicInteger();adapter=new FakeAdapter();repo=new ListingCopyRepository();service=new ListingCopyService(repo,(shop,key)->snapshot,shop->adapter);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    CopyJob draft(Shop destination)throws Exception{return service.draft(source,List.of(snapshot.key()),List.of(destination),ListingCopyMappingTest.options(Marketplace.WILDBERRIES)).getFirst();}
    @Test void largeDocumentHistoryLoadsSummariesOnly()throws Exception {
        String marker="LARGE_DOCUMENT_"+"A".repeat(1024*1024);
        for(int i=0;i<100;i++){var now=java.time.Instant.now();var p=new CopyPreview(2,dest.getMarketplace(),ListingIdentity.fingerprint(dest),snapshot.key(),snapshot.revision(),"hash","{}","[]",marker,Map.of(),List.of(),List.of("NEW-"+i),ListingCopyMappingTest.options(dest.getMarketplace()),"CREATE");repo.insert(new CopyJob("large-"+i,1,ListingIdentity.fingerprint(source),2,dest.getMarketplace(),ListingIdentity.fingerprint(dest),snapshot,p.options(),p,null,CopyState.AWAITING_CONFIRMATION,List.of(),now,now));}
        var summaries=service.list(dest);assertEquals(100,summaries.size());assertEquals(100,service.listSource(source).size());
        String json=ProductJson.GSON.toJson(summaries);assertFalse(json.contains("LARGE_DOCUMENT_"));assertTrue(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<200_000);
        assertTrue(summaries.stream().allMatch(j->j.preview()==null&&j.submission()==null&&j.options()==null&&j.source().metadata().isEmpty()));
        assertEquals(marker,service.get(dest,"large-42").preview().documentsJson());
    }
    @Test void selectJobLoadsOnlyOwnedDetail()throws Exception {
        var j=draft(dest);service.preview(dest,j.id());
        assertNotNull(service.get(dest,j.id()).preview());assertThrows(SecurityException.class,()->service.get(other,j.id()));assertThrows(SecurityException.class,()->service.get(new Shop(2,"Rotated","new-key"),j.id()));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='new-key' WHERE id=2");}assertThrows(SecurityException.class,()->service.get(dest,j.id()));
    }
    @Test void unconfirmedOrChangedPreviewCannotSend()throws Exception{
        var job=draft(dest);var preview=service.preview(dest,job.id());assertThrows(IllegalArgumentException.class,()->service.publish(dest,job.id(),preview.requestHash(),false));assertThrows(IllegalStateException.class,()->service.publish(dest,job.id(),"changed",true));
        var s=snapshot;snapshot=new ProductSnapshot(s.key(),"live:new",s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),s.attributes(),s.variants(),s.dimensions(),s.documents(),s.checkedAt(),s.metadata());assertThrows(IllegalStateException.class,()->service.publish(dest,job.id(),preview.requestHash(),true));assertEquals(0,writes.get());
    }
    @Test void doubleClickClaimsOnlyOnce()throws Exception{
        var job=draft(dest);var p=service.preview(dest,job.id());var gate=new CountDownLatch(1);adapter.gate=gate;
        try(var pool=Executors.newFixedThreadPool(2)){var first=pool.submit(()->service.publish(dest,job.id(),p.requestHash(),true));while(writes.get()==0)Thread.sleep(5);var second=pool.submit(()->service.publish(dest,job.id(),p.requestHash(),true));second.get(3,TimeUnit.SECONDS);gate.countDown();first.get(3,TimeUnit.SECONDS);assertEquals(1,writes.get());}
    }
    @Test void lostResponseRequiresReconciliationAndRestartCannotRepeatSubmittedJob()throws Exception{
        adapter.timeout=true;var job=draft(dest);var p=service.preview(dest,job.id());assertEquals(CopyState.RECONCILE_REQUIRED,service.publish(dest,job.id(),p.requestHash(),true).state());
        var restart=new ListingCopyService(new ListingCopyRepository(),(shop,key)->snapshot,shop->adapter);assertEquals(CopyState.RECONCILE_REQUIRED,restart.publish(dest,job.id(),p.requestHash(),true).state());assertEquals(1,writes.get());
        adapter.ready=true;assertEquals(CopyState.RECONCILED,restart.reconcile(dest,job.id()).state());assertEquals(1,writes.get());assertEquals("live:source",repo.find(2,job.id()).orElseThrow().source().revision());
    }
    @Test void destinationsHaveIndependentResults()throws Exception{
        var jobs=service.draft(source,List.of(snapshot.key()),List.of(dest,other),ListingCopyMappingTest.options(Marketplace.WILDBERRIES));var p=service.preview(dest,jobs.get(0).id());service.publish(dest,jobs.get(0).id(),p.requestHash(),true);assertEquals(CopyState.DRAFT,repo.find(3,jobs.get(1).id()).orElseThrow().state());assertEquals(1,writes.get());
    }
    @Test void foreignOwnerCannotResume()throws Exception{
        var job=draft(dest);assertThrows(SecurityException.class,()->service.preview(other,job.id()));assertThrows(SecurityException.class,()->service.preview(new Shop(2,"Target","rotated"),job.id()));assertEquals(0,writes.get());
    }
    @Test void restartBetweenClaimAndHttpNeverResends()throws Exception{
        var job=draft(dest);var p=service.preview(dest,job.id());assertTrue(repo.claim(2,job.id(),p.requestHash()));var restart=new ListingCopyService(repo,(s,k)->snapshot,s->adapter);assertEquals(CopyState.RECONCILE_REQUIRED,restart.publish(dest,job.id(),p.requestHash(),true).state());assertEquals(0,writes.get());
    }
    @Test void twoDraftsCannotClaimSameDestinationArticle()throws Exception{
        var a=draft(dest);var b=draft(dest);var p=service.preview(dest,a.id());var q=service.preview(dest,b.id());assertTrue(repo.claim(2,a.id(),p.requestHash()));assertFalse(repo.claim(2,b.id(),q.requestHash()));
    }
    @Test void recoveredUnknownCardCanCheckpointAssetsAndStaleClaimCannotRepeatThem()throws Exception{
        var job=draft(dest);var p=service.preview(dest,job.id());assertTrue(repo.claim(2,job.id(),p.requestHash()));adapter.ready=true;adapter.assetsNeeded=true;
        var restart=new ListingCopyService(repo,(s,k)->snapshot,s->adapter);var result=restart.reconcile(dest,job.id());assertEquals(1,adapter.assetWrites.get());assertEquals("ASSETS_SENT",result.submission().phase());assertEquals(0,writes.get());
        var old=result.with(CopyState.REMOTE_PROCESSING,result.source(),result.options(),result.preview(),result.submission().withPhase("CARD",Map.of()),List.of());
        assertFalse(repo.claimAssets(old,old.submission().withPhase("ASSETS_SENDING",Map.of("NEW","999"))));assertEquals(CopyState.RECONCILED,restart.reconcile(dest,job.id()).state());assertEquals(1,adapter.assetWrites.get());
    }
    class FakeAdapter implements ListingTargetAdapter {
        volatile boolean timeout,ready,assetsNeeded;CountDownLatch gate;AtomicInteger assetWrites=new AtomicInteger();
        public List<TargetCategory> categories(Shop s){return List.of();}public List<TargetAttribute> attributes(Shop s,String id){return List.of();}
        public CopyPreview preview(Shop s,ProductSnapshot source,CopyOptions o){String owner=ListingIdentity.fingerprint(s),r="{\"offer_id\":\"NEW\"}";return new CopyPreview(s.getId(),s.getMarketplace(),owner,source.key(),source.revision(),CopyPreview.hash(r,"[]","[]",owner,source.revision(),"CREATE"),r,"[]","[]",Map.of(),List.of(),List.of("NEW"),o,"CREATE");}
        public RemoteSubmission submit(Shop s,CopyPreview p)throws IOException{writes.incrementAndGet();if(gate!=null)try{gate.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException();}if(timeout)throw new IOException("lost response");return new RemoteSubmission(s.getId(),s.getMarketplace(),p.owner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
        public RemoteResult reconcile(Shop s,RemoteSubmission r){if(assetsNeeded&&r.phase().equals("CARD"))return new RemoteResult(CopyState.REMOTE_PROCESSING,List.of("999"),List.of(new CopyIssue("ASSETS_REQUIRED","images",false)),Map.of("NEW","999"));return ready?new RemoteResult(CopyState.RECONCILED,List.of("999"),List.of(),Map.of("NEW","999")):RemoteResult.pending();}
        public RemoteSubmission finishAssets(Shop s,RemoteSubmission r){assetWrites.incrementAndGet();return r.withPhase("ASSETS_SENT",r.identities());}
        public ProductEditPreview previewUpdate(Shop s,ProductSnapshot f,Map<String,List<String>> c,List<ProductDocument>d){throw new UnsupportedOperationException();}public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p){throw new UnsupportedOperationException();}
    }
}
