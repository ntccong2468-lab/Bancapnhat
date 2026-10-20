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
    @Test void freezesEveryCardAndSendsEachOnceWithoutWaitingForModeration(){
        var workers=new ArrayDeque<Runnable>();var service=service((owner,sku,sign)->draft,workers::add);
        String run=service.start(shop,selection(),false);
        assertEquals(0,api.submits.get());assertEquals(3,service.latest(shop).total());assertEquals(3,new SyncRunRepository().items(run,0,20).size());
        assertEquals(0,service.latest(shop).submitted());workers.remove().run();
        var progress=service.latest(shop);assertEquals(3,progress.submitted());assertEquals(0,progress.remaining());assertEquals(0,progress.failed());assertEquals(ZnackRegistrationBatchService.State.COMPLETE,progress.state());
        assertEquals(3,api.allocations.get());assertEquals(3,api.submits.get());assertEquals(0,api.statusReads.get(),"Batch dispatch must not wait for moderation reads");
        for(var row:selection()){assertEquals(Status.FEED_SUBMITTED,row.status());assertTrue(row.hasFeed());}
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
    class FixtureApi extends ZnackApiClient {
        AtomicInteger allocations=new AtomicInteger(),submits=new AtomicInteger(),statusReads=new AtomicInteger();boolean failFirstFeed;
        @Override public JsonElement generatedGtins(String base,String token){return json("{\"result\":{\"drafts\":[]}}");}
        @Override public JsonElement generateGtins(String base,String token,int quantity){
            int index=allocations.getAndIncrement();String gtin=List.of("04631993764370","04631993764363","04631993764387").get(index);
            return json("{\"result\":{\"drafts\":[{\"gtin\":\""+gtin+"\"}]}}");
        }
        @Override public JsonElement nationalCatalogCategories(String base,String token,String tnved){return json("{\"result\":[{\"cat_id\":1,\"cat_name\":\"Clothes\",\"category_active\":true}]}");}
        @Override public JsonElement nationalCatalogAttributes(String base,String token,long id){return json("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"}]}");}
        @Override public JsonElement submitNationalCatalogFeed(String base,String token,JsonElement payload)throws IOException {
            try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM znack_registration_batch_items")){assertTrue(r.next());assertTrue(r.getInt(1)>0,"Selection must be durable before any POST");}catch(java.sql.SQLException e){throw new IOException(e);}
            int count=submits.incrementAndGet();if(failFirstFeed&&count==1)throw new IOException("response lost");return json("{\"result\":{\"feed_id\":\"feed-"+count+"\"}}");
        }
        @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){statusReads.incrementAndGet();throw new AssertionError("Submission should finish at accepted feed");}
        private JsonElement json(String value){return JsonParser.parseString(value);}
    }
}
