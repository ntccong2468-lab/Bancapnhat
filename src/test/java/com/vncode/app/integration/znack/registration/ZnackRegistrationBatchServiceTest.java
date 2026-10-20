package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.sync.SyncRunRepository;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackRegistrationBatchServiceTest {
    @TempDir Path directory;
    final Shop shop=new Shop(1,"WB","fixture");
    final Draft draft=new Draft("6204",1,"Fixture","Brand",Map.of());
    ZnackCardRegistrationRepository repository;
    FixtureApi api;
    ZnackCardRegistrationWorkflow workflow;
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture')");
            for(int i=1;i<=3;i++){
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,"+(100+i)+",'ARTICLE-"+i+"','Fixture','2026-10-06T00:00:00Z')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,"+(10+i)+","+(100+i)+",'XL')");
            }
        }
        repository=new ZnackCardRegistrationRepository();api=new FixtureApi();
        var auth=new ZnackAuthService(api,null){@Override public String trueApiToken(ZnackModels.Settings settings){return "fixture-token";}};
        var catalog=new ZnackNationalCatalogService(api,auth,(payload,context)->new com.vncode.app.integration.znack.signature.CryptoProSigningResult(new byte[]{1},""),ZnackModels.Settings.empty());
        workflow=new ZnackCardRegistrationWorkflow(repository,ignored->new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",catalog),Runnable::run);
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    List<Sku> selection(){return repository.search(new SearchCriteria(1,"",List.of(),"ALL",20,0));}
    ZnackRegistrationBatchService service(ZnackRegistrationBatchService.DraftFactory drafts,java.util.concurrent.Executor executor){return new ZnackRegistrationBatchService(repository,workflow,drafts,executor,false);}
    @Test void freezesEveryCardAndCountsOnlyExactCardsWithoutWaitingForModeration(){
        var workers=new ArrayDeque<Runnable>();var service=service((owner,sku,sign)->draft,workers::add);
        String run=service.start(shop,selection(),false);
        assertEquals(0,api.submits.get());assertEquals(3,service.latest(shop).total());assertEquals(3,new SyncRunRepository().items(run,0,20).size());
        assertEquals(0,service.latest(shop).submitted());workers.remove().run();
        var progress=service.latest(shop);assertEquals(3,progress.submitted());assertEquals(0,progress.remaining());assertEquals(0,progress.failed());assertEquals(ZnackRegistrationBatchService.State.COMPLETE,progress.state());
        assertEquals(3,api.allocations.get());assertEquals(3,api.submits.get());assertEquals(3,api.statusReads.get(),"Each feed needs one immediate readback, not a moderation wait loop");
        assertEquals(3,api.cardReads.get());
        for(var row:selection()){assertEquals(Status.PROCESSING,row.status());assertTrue(row.hasFeed());assertNotNull(row.goodId());}
        assertEquals(3,new SyncRunRepository().items(run,0,20).size());assertFalse(service.resumePending(shop));
    }
    @Test void offlineQueueResumesAllRemainingCardsWithoutASecondRegisterClick()throws Exception {
        var preparations=new AtomicInteger();var first=service((owner,sku,sign)->{if(preparations.incrementAndGet()==2)throw new java.net.UnknownHostException("offline");return draft;},Runnable::run);
        String run=first.start(shop,selection(),false);
        assertEquals(1,first.latest(shop).submitted());assertEquals(2,first.latest(shop).remaining());assertTrue(first.latest(shop).waitingOffline());
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_registration_batches SET retry_at=NULL WHERE id=?")){p.setString(1,run);p.executeUpdate();}
        var restarted=service((owner,sku,sign)->draft,Runnable::run);assertTrue(restarted.resumePending(shop));
        assertEquals(3,restarted.latest(shop).submitted());assertEquals(3,api.submits.get());assertEquals(3,api.allocations.get());
        assertEquals(run,restarted.latest(shop).runId());assertEquals(3,new SyncRunRepository().items(run,0,20).size());
    }
    @Test void uncertainFeedIsReportedWithoutAnotherAllocationOrPost() {
        api.failFirstFeed=true;var service=service((owner,sku,sign)->draft,Runnable::run);service.start(shop,selection(),false);
        assertEquals(3,api.allocations.get());assertEquals(3,api.submits.get());assertEquals(2,service.latest(shop).submitted());assertEquals(1,service.latest(shop).failed());
        assertEquals(Status.FEED_REVIEW_REQUIRED,repository.find(1,11).status());
        assertFalse(service.resumePending(shop));assertEquals(3,api.submits.get());assertEquals(3,api.allocations.get());
    }
    @Test void crashAfterAllocationUsesSavedUnsentPayloadWithoutAllocatingAgain(){
        var sku=repository.find(1,11);repository.saveReadyToSubmitGenerated(1,sku,"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        var service=service((owner,row,sign)->{throw new AssertionError("Must reuse the prepared exact payload");},Runnable::run);
        service.start(shop,List.of(repository.find(1,11)),false);
        assertEquals(1,service.latest(shop).submitted());assertEquals(0,api.allocations.get());assertEquals(1,api.submits.get());
    }
    @Test void crashBeforeAllocationResumesCheckingWithoutClaimingAnotherGtin(){
        var sku=repository.find(1,11);assertTrue(repository.claim(1,sku,RegistrationAction.REGISTER));
        var service=service((owner,row,sign)->draft,Runnable::run);service.start(shop,List.of(repository.find(1,11)),false);
        assertEquals(1,service.latest(shop).submitted());assertEquals(1,api.allocations.get());assertEquals(1,api.submits.get());
    }
    @Test void changingMarketplaceOwnerCannotResumeTheFrozenQueue()throws Exception {
        var workers=new ArrayDeque<Runnable>();var service=service((owner,sku,sign)->draft,workers::add);service.start(shop,selection(),false);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='other-owner' WHERE id=1");}
        assertThrows(SecurityException.class,()->service.latest(shop));workers.remove().run();assertEquals(0,api.allocations.get());assertEquals(0,api.submits.get());
    }

    @Test void feedReceiptsWithoutCardsRemainPendingAndLateCardsResolveWithoutAnotherPost()throws Exception {
        api.cardsVisible=false;
        var first=service((owner,row,sign)->draft,Runnable::run);
        String run=first.start(shop,selection(),false);

        assertEquals(3,api.submits.get(),"A pending import must not stop later selected products being sent");
        assertEquals(0,first.latest(shop).submitted());assertEquals(3,first.latest(shop).remaining());assertEquals(0,first.latest(shop).failed());
        assertEquals(ZnackRegistrationBatchService.State.WAITING_REMOTE,first.latest(shop).state());
        for(var row:selection()){assertTrue(row.hasFeed());assertTrue(row.hasGtin());assertNull(row.goodId());assertNotEquals(Status.PUBLISHED,row.status());}

        api.cardsVisible=true;retryNow(run);
        var restarted=service((owner,row,sign)->{throw new AssertionError("Read the saved feed, never prepare another card");},Runnable::run);
        assertTrue(restarted.resumePending(shop));
        assertEquals(3,restarted.latest(shop).submitted());assertEquals(0,restarted.latest(shop).remaining());
        assertEquals(ZnackRegistrationBatchService.State.COMPLETE,restarted.latest(shop).state());
        assertEquals(3,api.submits.get());assertEquals(3,api.allocations.get());assertFalse(restarted.resumePending(shop));
        assertEquals(run,restarted.latest(shop).runId());
    }

    @Test void oneUnconfirmedCardDoesNotBlockOtherSelectedCards()throws Exception {
        api.hiddenGtins.add("04631993764370");
        var batch=service((owner,row,sign)->draft,Runnable::run);String run=batch.start(shop,selection(),false);
        assertEquals(3,api.submits.get());assertEquals(2,batch.latest(shop).submitted());assertEquals(1,batch.latest(shop).remaining());
        assertEquals(0,batch.latest(shop).failed());assertEquals(ZnackRegistrationBatchService.State.WAITING_REMOTE,batch.latest(shop).state());
        api.hiddenGtins.clear();retryNow(run);assertTrue(batch.resumePending(shop));
        assertEquals(3,batch.latest(shop).submitted());assertEquals(3,api.submits.get());assertEquals(3,api.allocations.get());
    }

    @Test void explicitFailedFeedWithoutErrorDetailsIsNotCountedAsCreated(){
        api.failedFeed="feed-1";api.failureStatus="Failed";
        var batch=service((owner,row,sign)->draft,Runnable::run);batch.start(shop,selection(),false);
        assertEquals(3,api.submits.get());assertEquals(2,batch.latest(shop).submitted());assertEquals(1,batch.latest(shop).failed());
        assertEquals(Status.ERROR,repository.find(1,11).status());assertEquals("feed-1",repository.find(1,11).feedId());
        assertTrue(repository.find(1,11).hasGtin());assertFalse(batch.resumePending(shop));assertEquals(3,api.submits.get());
    }

    @Test void readFailureAfterAcknowledgementRetainsAllIdentitiesAndRetriesOnlyReads()throws Exception {
        api.statusReadFailure=503;
        var batch=service((owner,row,sign)->draft,Runnable::run);String run=batch.start(shop,selection(),false);
        assertEquals(3,api.submits.get());assertEquals(0,batch.latest(shop).submitted());assertEquals(3,batch.latest(shop).remaining());
        for(var row:selection()){assertTrue(row.hasFeed());assertTrue(row.hasGtin());assertNotEquals(Status.FEED_REVIEW_REQUIRED,row.status());}
        api.statusReadFailure=0;retryNow(run);assertTrue(batch.resumePending(shop));
        assertEquals(3,batch.latest(shop).submitted());assertEquals(3,api.submits.get());assertEquals(3,api.allocations.get());
    }

    @Test void permanentReadFailureCannotReuseAnOldNativeGoodIdAsCreatedCardProof()throws Exception {
        api.cardsVisible=false;
        var batch=service((owner,row,sign)->draft,Runnable::run);
        String run=batch.start(shop,List.of(repository.find(1,11)),false);
        var pending=repository.find(1,11);
        assertEquals(0,batch.latest(shop).submitted());assertTrue(pending.hasFeed());
        repository.updateProgress(1,11,Status.PROCESSING,pending.feedId(),91L,null,null);
        api.statusReadFailure=401;retryNow(run);

        assertTrue(batch.resumePending(shop));

        assertEquals(0,batch.latest(shop).submitted(),"An old good_id is not fresh Catalog readback proof");
        assertEquals(1,batch.latest(shop).failed());assertEquals(0,batch.latest(shop).remaining());
        assertEquals(ZnackRegistrationBatchService.State.NEEDS_ACTION,batch.latest(shop).state());
        var failed=repository.find(1,11);
        assertEquals(pending.gtin(),failed.gtin());assertEquals(pending.feedId(),failed.feedId());
        assertEquals(1,api.submits.get());assertEquals(1,api.allocations.get());
    }

    @Test void aConcurrentMonitorReadCannotTurnAnOldReadyCardIntoFreshSubmissionProof()throws Exception {
        api.cardsVisible=false;
        var batch=service((owner,row,sign)->draft,Runnable::run);
        String run=batch.start(shop,List.of(repository.find(1,11)),false);
        var pending=repository.find(1,11);
        repository.updateProgress(1,11,Status.READY_TO_SIGN,pending.feedId(),100L,null,null);
        api.cardsVisible=true;
        api.feedReadEntered=new java.util.concurrent.CountDownLatch(1);
        api.releaseFeedRead=new java.util.concurrent.CountDownLatch(1);
        var monitor=java.util.concurrent.CompletableFuture.runAsync(()->{
            try{workflow.refreshStatus(shop,repository.find(1,11));}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        });
        try {
            assertTrue(api.feedReadEntered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            retryNow(run);assertTrue(batch.resumePending(shop));
            assertEquals(0,batch.latest(shop).submitted(),"A busy reconciliation has supplied no new card readback");
            assertEquals(1,batch.latest(shop).remaining());assertEquals(0,batch.latest(shop).failed());
            assertEquals(ZnackRegistrationBatchService.State.WAITING_REMOTE,batch.latest(shop).state());
            assertTrue(ZnackRegistrationBatchService.hasPendingJobs());
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT detail FROM znack_registration_batch_items WHERE run_id=?")){
                p.setString(1,run);try(var result=p.executeQuery()){assertTrue(result.next());assertEquals("REGISTRATION_BUSY",result.getString(1));}
            }
            assertEquals(1,api.submits.get());assertEquals(1,api.allocations.get());
        }finally{
            api.releaseFeedRead.countDown();monitor.get(3,java.util.concurrent.TimeUnit.SECONDS);
            // This injected executor has no scheduled retry. Finish its own saved batch
            // through real readback before the fixture's app-data directory is released.
            retryNow(run);assertTrue(batch.resumePending(shop));
            assertEquals(ZnackRegistrationBatchService.State.COMPLETE,batch.latest(shop).state());
            assertEquals(1,batch.latest(shop).submitted());assertEquals(0,batch.latest(shop).remaining());
            assertEquals(1,api.submits.get());assertEquals(1,api.allocations.get());
            assertFalse(ZnackRegistrationBatchService.hasPendingJobs(),"The fixture must release its completed background registration");
            String profile=System.getProperty("vncode.appdata.dir");
            try{
                System.setProperty("vncode.appdata.dir",directory.resolve("another-profile").toString());
                assertFalse(ZnackRegistrationBatchService.hasPendingJobs(),"Completed fixture work must not block another profile's updater");
            }finally{System.setProperty("vncode.appdata.dir",profile);}
        }
    }

    private void retryNow(String run)throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_registration_batches SET retry_at=NULL WHERE id=?")){p.setString(1,run);p.executeUpdate();}
    }

    class FixtureApi extends ZnackApiClient {
        AtomicInteger allocations=new AtomicInteger(),submits=new AtomicInteger(),statusReads=new AtomicInteger(),cardReads=new AtomicInteger();boolean failFirstFeed,cardsVisible=true;
        String failedFeed="",failureStatus="Rejected";int statusReadFailure;
        java.util.concurrent.CountDownLatch feedReadEntered,releaseFeedRead;
        final Map<String,String> feedGtins=new HashMap<>();final Set<String> hiddenGtins=new HashSet<>();
        @Override public JsonElement generatedGtins(String base,String token){return json("{\"result\":{\"drafts\":[]}}");}
        @Override public JsonElement generateGtins(String base,String token,int quantity){
            int index=allocations.getAndIncrement();String gtin=List.of("04631993764370","04631993764363","04631993764387").get(index);
            return json("{\"result\":{\"drafts\":[{\"gtin\":\""+gtin+"\"}]}}");
        }
        @Override public JsonElement nationalCatalogCategories(String base,String token,String tnved){return json("{\"result\":[{\"cat_id\":1,\"cat_name\":\"Clothes\",\"category_active\":true}]}");}
        @Override public JsonElement nationalCatalogAttributes(String base,String token,long id){return json("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"}]}");}
        @Override public JsonElement submitNationalCatalogFeed(String base,String token,JsonElement payload)throws IOException {
            try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM znack_registration_batch_items")){assertTrue(r.next());assertTrue(r.getInt(1)>0,"Selection must be durable before any POST");}catch(java.sql.SQLException e){throw new IOException(e);}
            int count=submits.incrementAndGet();if(failFirstFeed&&count==1)throw new IOException("response lost");feedGtins.put("feed-"+count,payload.getAsJsonObject().get("gtin").getAsString());return json("{\"result\":{\"feed_id\":\"feed-"+count+"\"}}");
        }
        @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed)throws IOException {
            if(feedReadEntered!=null){feedReadEntered.countDown();try{if(!releaseFeedRead.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("fixture monitor timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}}
            statusReads.incrementAndGet();
            if(statusReadFailure>0)throw new ZnackApiException("read unavailable",statusReadFailure,"{}");
            String status=feed.equals(failedFeed)?failureStatus:"Processing";
            return json("{\"result\":{\"status\":\""+status+"\",\"item\":[{\"gtin\":\""+feedGtins.get(feed)+"\"}]}}");
        }
        @Override public JsonElement productCards(String base,String token,String gtin){
            cardReads.incrementAndGet();
            if(!cardsVisible||hiddenGtins.contains(gtin))return json("{\"result\":[]}");
            return json("{\"result\":[{\"gtin\":\""+gtin+"\",\"good_id\":"+(100+List.of("04631993764370","04631993764363","04631993764387").indexOf(gtin))+",\"good_status\":\"draft\",\"good_detailed_status\":[\"moderation\"]}]}");
        }
        private JsonElement json(String value){return JsonParser.parseString(value);}
    }
}
