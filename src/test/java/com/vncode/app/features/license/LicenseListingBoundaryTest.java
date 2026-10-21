package com.vncode.app.features.license;

import com.vncode.app.config.Database;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.features.products.*;
import com.vncode.app.features.shop.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LicenseListingBoundaryTest {
    @TempDir Path root;
    final Shop source=new Shop(1,"Source","fixture"),destination=new Shop(2,"Destination","fixture");
    final ProductKey key=new ProductKey(1,Marketplace.WILDBERRIES,"123","1");
    LicenseService prior,denied;
    final AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger();
    Runnable onRead=()->{},onWrite=()->{},onPreview=()->{};
    ProductBulkEditService bulk;
    ListingCopyService copy;
    ProductEditRepository edits;
    ListingCopyRepository copies;
    FakeAdapter adapter;

    @BeforeEach void setup()throws Exception {
        prior=LicenseRuntime.service();denied=LicenseDomainFixture.denied(root.resolve("denied"));
        System.setProperty("vncode.appdata.dir",root.resolve("data").toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Source','fixture','WILDBERRIES'),(2,'Destination','fixture','WILDBERRIES')");}
        adapter=new FakeAdapter();edits=new ProductEditRepository();copies=new ListingCopyRepository();
        ProductSnapshotReader reader=(s,k)->{reads.incrementAndGet();onRead.run();return snapshot(k);};
        bulk=new ProductBulkEditService(edits,reader,s->adapter);copy=new ListingCopyService(copies,reader,s->adapter);
    }
    @AfterEach void cleanup(){LicenseRuntime.install(prior);denied.close();System.clearProperty("vncode.appdata.dir");}
    ProductSnapshot snapshot(ProductKey k){return new ProductSnapshot(k,"live:original","Title","Description","Old","article-"+k.cardId(),"9",List.of(),List.of(),List.of(new ProductVariant(k.variantId(),"article-"+k.cardId(),"Black","M","",false)),new PackageDimensions(null,null,null,null),List.of(),Instant.now());}
    ProductBulkEdit edit(){return new ProductBulkEdit("brand",List.of("New"),null,Map.of());}
    CopyOptions options(){return new CopyOptions(Set.of("brand"),Set.of("1"),"9",Map.of(),Map.of(),Map.of("1","NEW"),null,"RUB",List.of(),null);}
    CopyJob draft()throws Exception{return copy.draft(source,List.of(key),List.of(destination),options()).getFirst();}
    void deny(){LicenseRuntime.install(denied);}

    @Test void deniedFreshPreviewsMakeZeroRemoteCallsAndKeepLocalShopsReadable(){
        deny();
        assertThrows(LicenseGate.LicenseDeniedException.class,()->bulk.preview(source,List.of(key),edit()));
        assertThrows(LicenseGate.LicenseDeniedException.class,()->copy.draft(source,List.of(key),List.of(destination),options()));
        assertEquals(0,reads.get());assertEquals(0,writes.get());
        assertEquals(2,new ShopRepository().findAll().size());
        assertNotNull(new ShopRepository().findById(source.getId()));
    }
    @Test void deniedBulkApplyMakesZeroNewReadsAndDoesNotClaimCard()throws Exception {
        var preview=bulk.preview(source,List.of(key),edit());reads.set(0);deny();
        assertThrows(LicenseGate.LicenseDeniedException.class,()->bulk.apply(source,preview.id(),true));
        assertEquals(0,reads.get());assertEquals(0,writes.get());
        assertEquals(CopyState.AWAITING_CONFIRMATION,bulk.result(source,preview.id()).items().getFirst().state());
    }
    @Test void revokeAfterAcceptedCardRetainsCheckpointAndLeavesRemainingCardUnclaimed()throws Exception {
        var second=new ProductKey(1,Marketplace.WILDBERRIES,"124","2");
        var preview=bulk.preview(source,List.of(key,second),edit());onWrite=this::deny;
        assertThrows(LicenseGate.LicenseDeniedException.class,()->bulk.apply(source,preview.id(),true));
        assertEquals(1,writes.get());var result=bulk.result(source,preview.id());
        assertEquals(CopyState.REMOTE_PROCESSING,result.items().getFirst().state());assertNotNull(result.items().getFirst().submission());
        assertEquals(CopyState.AWAITING_CONFIRMATION,result.items().get(1).state());assertNull(result.items().get(1).submission());
        assertEquals(CopyState.RECONCILED,bulk.reconcile(source,preview.id()).items().getFirst().state());
        assertEquals(1,writes.get(),"Accepted-job readback must never re-submit a new card");
    }
    @Test void denyDuringClaimedPreflightReleasesKnownUnsentBulkCard()throws Exception {
        var preview=bulk.preview(source,List.of(key),edit());reads.set(0);
        onRead=()->{if(reads.get()==2)deny();};
        assertThrows(LicenseGate.LicenseDeniedException.class,()->bulk.apply(source,preview.id(),true));
        var item=bulk.result(source,preview.id()).items().getFirst();assertEquals(CopyState.AWAITING_CONFIRMATION,item.state());assertNull(item.submission());assertEquals(0,writes.get());
        LicenseRuntime.install(prior);onRead=()->{};
        assertEquals(CopyState.REMOTE_PROCESSING,bulk.apply(source,preview.id(),true).items().getFirst().state());assertEquals(1,writes.get());
    }
    @Test void copyDenialAfterPreflightLeavesDraftReadyWithoutArticleClaim()throws Exception {
        var job=draft();var preview=copy.preview(destination,job.id());onPreview=this::deny;
        assertThrows(LicenseGate.LicenseDeniedException.class,()->copy.publish(destination,job.id(),preview.requestHash(),true));
        assertEquals(CopyState.AWAITING_CONFIRMATION,copy.get(destination,job.id()).state());assertEquals(0,writes.get());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM listing_copy_articles")){r.next();assertEquals(0,r.getInt(1));}
    }
    @Test void copyQueuedBehindShopLockRechecksRightsBeforeReadsOrClaim()throws Exception {
        var job=draft();var preview=copy.preview(destination,job.id());reads.set(0);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var locker=executor.submit(()->ShopOperationCoordinator.withExclusiveShop(destination.getId(),()->{locked.countDown();release.await(5,TimeUnit.SECONDS);return null;}));
            assertTrue(locked.await(3,TimeUnit.SECONDS));
            var waiting=executor.submit(()->copy.publish(destination,job.id(),preview.requestHash(),true));
            try{assertThrows(TimeoutException.class,()->waiting.get(100,TimeUnit.MILLISECONDS));deny();}
            finally{release.countDown();}
            locker.get(3,TimeUnit.SECONDS);
            var failed=assertThrows(ExecutionException.class,()->waiting.get(3,TimeUnit.SECONDS));assertInstanceOf(LicenseGate.LicenseDeniedException.class,failed.getCause());
        }
        assertEquals(0,reads.get());assertEquals(0,writes.get());assertEquals(CopyState.AWAITING_CONFIRMATION,copy.get(destination,job.id()).state());
    }
    @Test void unsentCopyReleaseChecksOwnerAndHashThenFreesArticleClaim()throws Exception {
        var job=draft();var preview=copy.preview(destination,job.id());assertTrue(copies.claim(destination.getId(),job.id(),preview.requestHash()));
        var sending=copy.get(destination,job.id());assertFalse(copies.releaseUnsent(sending,"wrong-hash"));assertEquals(CopyState.SENDING,copy.get(destination,job.id()).state());
        assertTrue(copies.releaseUnsent(sending,preview.requestHash()));assertEquals(CopyState.AWAITING_CONFIRMATION,copy.get(destination,job.id()).state());
        assertTrue(copies.claim(destination.getId(),job.id(),preview.requestHash()),"A paused unsent request releases its article reservation");
    }
    @Test void deniedShopMutationsPreserveLocalRows(){
        deny();var shops=new ShopRepository();
        assertThrows(LicenseGate.LicenseDeniedException.class,()->shops.insert(new Shop("New","fixture")));
        assertThrows(LicenseGate.LicenseDeniedException.class,()->shops.update(1,new Shop("Changed","fixture")));
        assertThrows(LicenseGate.LicenseDeniedException.class,()->shops.delete(1));
        assertEquals(2,shops.findAll().size());assertEquals("Source",shops.findById(1).getName());
    }
    @Test void concurrentShopCreationCannotExceedSignedCapacity()throws Exception {
        var device=LicenseDeviceKeys.create();var issuer=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();long now=Instant.now().getEpochSecond();
        var store=LicenseDomainFixture.store(root.resolve("capacity"));
        store.save(new LicenseModels.State(device.publicKey(),device.privateKey(),LicenseDomainFixture.TOKEN,null,new LicenseModels.Account("capacity-account","fixture@gmail.com","approved",now+7*86400),LicenseDomainFixture.sign(issuer,device,"capacity-account",now,3),now,1,""));
        try(var limited=new LicenseService(new LicenseDomainFixture.UnusedApi(),new LicenseVerifier(Map.of("test-fixture-v1",issuer.getPublic())),store,Clock.systemUTC(),System::nanoTime)){
            limited.initialize().get(3,TimeUnit.SECONDS);LicenseRuntime.install(limited);
            var start=new CountDownLatch(1);int accepted=0,refused=0;
            try(var executor=Executors.newFixedThreadPool(2)){
                var attempts=List.of(executor.submit(()->{start.await();return new ShopRepository().insert(new Shop("Third A","fixture"));}),executor.submit(()->{start.await();return new ShopRepository().insert(new Shop("Third B","fixture"));}));start.countDown();
                for(var attempt:attempts)try{assertEquals(1,attempt.get(3,TimeUnit.SECONDS));accepted++;}catch(ExecutionException error){assertInstanceOf(LicenseGate.LicenseDeniedException.class,error.getCause());refused++;}
            }
            assertEquals(1,accepted);assertEquals(1,refused);assertEquals(3,new ShopRepository().findAll().size());
        }finally{LicenseRuntime.install(prior);}
    }

    @Test void migratedShopsAboveQuotaCannotStartNewTasksButCanBeManaged()throws Exception {
        var device=LicenseDeviceKeys.create();var issuer=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();long now=Instant.now().getEpochSecond();
        var store=LicenseDomainFixture.store(root.resolve("over-quota"));
        store.save(new LicenseModels.State(device.publicKey(),device.privateKey(),LicenseDomainFixture.TOKEN,null,new LicenseModels.Account("quota-account","fixture@gmail.com","approved",now+7*86400),LicenseDomainFixture.sign(issuer,device,"quota-account",now,1),now,1,""));
        try(var limited=new LicenseService(new LicenseDomainFixture.UnusedApi(),new LicenseVerifier(Map.of("test-fixture-v1",issuer.getPublic())),store,Clock.systemUTC(),System::nanoTime)){
            limited.initialize().get(3,TimeUnit.SECONDS);LicenseRuntime.install(limited);
            assertEquals(2,new ShopRepository().findAll().size());
            assertThrows(LicenseGate.LicenseDeniedException.class,()->LicenseRuntime.require(LicenseModels.Permission.SELLER_API));
            assertThrows(LicenseGate.LicenseDeniedException.class,()->LicenseRuntime.require(LicenseModels.Permission.PRINT_NEW));
            assertDoesNotThrow(()->LicenseRuntime.require(LicenseModels.Permission.SHOP_MANAGE));
        }finally{LicenseRuntime.install(prior);}
    }

    final class FakeAdapter implements ListingTargetAdapter {
        public List<TargetCategory> categories(Shop s){return List.of();}
        public List<TargetAttribute> attributes(Shop s,String id){return List.of();}
        public CopyPreview preview(Shop s,ProductSnapshot f,CopyOptions o){onPreview.run();String owner=ListingIdentity.fingerprint(s),request="{\"offer_id\":\"NEW\"}";return new CopyPreview(s.getId(),s.getMarketplace(),owner,f.key(),f.revision(),CopyPreview.hash(request,"[]","[]",owner,f.revision(),"CREATE"),request,"[]","[]",Map.of(),List.of(),List.of("NEW"),o,"CREATE");}
        public ProductEditPreview previewUpdate(Shop s,ProductSnapshot f,Map<String,List<String>> changes,List<ProductDocument> docs){String owner=ListingIdentity.fingerprint(s),request=ProductJson.GSON.toJson(changes);return new ProductEditPreview(f.key(),f.revision(),new CopyPreview(s.getId(),s.getMarketplace(),owner,f.key(),f.revision(),CopyPreview.hash(request,"[]","[]",owner,f.revision(),"UPDATE"),request,"[]","[]",Map.of(),List.of(),List.of(f.vendorArticle()),options(),"UPDATE"));}
        public RemoteSubmission submit(Shop s,CopyPreview p){writes.incrementAndGet();onWrite.run();return new RemoteSubmission(s.getId(),s.getMarketplace(),p.owner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
        public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p){return submit(s,p.preview());}
        public RemoteResult reconcile(Shop s,RemoteSubmission r){return new RemoteResult(CopyState.RECONCILED,List.of("123"),List.of(),Map.of("article","123"));}
        public RemoteSubmission finishAssets(Shop s,RemoteSubmission r){return r.withPhase("ASSETS_SENT",r.identities());}
    }
}
