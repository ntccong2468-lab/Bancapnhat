package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import com.vncode.app.testsupport.RegistrationSourceFixture;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Seller review dialog, durable batch, and real loopback Catalog HTTP transport. */
class WbSourceReviewHttpEndToEndTest {
    @TempDir Path directory;
    Shop shop; ProductKey key; ProductSnapshot snapshot; Draft draft; List<Attribute> schema;
    RegistrationDynamicPreflight.Review review; HttpServer server; ExecutorService serverWorkers;
    ZnackCardRegistrationRepository repository; ZnackRegistrationBatchService batch; ZnackCardRegistrationWorkflow.CatalogSession session;
    final AtomicInteger allocations=new AtomicInteger(),posts=new AtomicInteger();
    final AtomicReference<String> sentGtin=new AtomicReference<>();
    final AtomicReference<Throwable> serverFailure=new AtomicReference<>();
    volatile boolean changed; volatile String feedFailure="",oldFeedStatus="Completed",oldOwner="7700000000"; volatile int oldFeedHttp=200,oldCardHttp=200;
    @BeforeAll static void initFx() throws Exception {try{Platform.startup(()->{});}catch(IllegalStateException already){} fx(()->{Platform.setImplicitExit(false);return null;});}
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture WB','WILDBERRIES','fixture')");
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,101,'ARTICLE','Fixture','2026-10-06T00:00:00Z')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'XL')");
        }
        shop=new Shop(1,"Fixture WB","fixture");RegistrationSourceFixture.ready(shop);
        repository=RegistrationWorkflowTestFixtures.repository(shop);key=new ProductKey(1,Marketplace.WILDBERRIES,"101","11");
        var variant=new ProductVariant("11","ARTICLE","Blue","XL","",false,"52");
        snapshot=new ProductSnapshot(key,"fixture","Fixture","","Brand","ARTICLE","category",List.of(),List.of(),List.of(variant),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));
        draft=new Draft("6204420000","6204",1,"Fixture","Brand",Map.of(),Map.of(),false,false);
        String model="{\"result\":[{\"attr_id\":2478,\"attr_name\":\"Name\",\"attr_field_type\":\"text\"},{\"attr_id\":2504,\"attr_name\":\"Brand\",\"attr_field_type\":\"text\"}]}";
        schema=ZnackNationalCatalogService.parseAttributes(JsonParser.parseString(model));
        var plan=new RegistrationDynamicPreflight.Plan(snapshot,draft,schema,null);review=new RegistrationDynamicPreflight.Review(List.of(plan),List.of());
        RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(snapshot,snapshot,draft,schema,Map.of(),null,false)));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);serverWorkers=Executors.newFixedThreadPool(4);server.setExecutor(serverWorkers);
        server.createContext("/",exchange->{int status=200;String body="{}";try{
            String path=exchange.getRequestURI().getPath(),query=Objects.toString(exchange.getRequestURI().getQuery(),"");
            switch(path){
                case "/v3/categories" -> body="{\"result\":[{\"cat_id\":1,\"cat_name\":\"Clothes\",\"category_active\":true}]}";
                case "/v3/attributes" -> body=model;
                case "/v3/generate-gtins" -> {if(query.contains("quantity=1")){allocations.incrementAndGet();body="{\"result\":{\"drafts\":[{\"gtin\":\"04631993764370\"}]}}";}else body="{\"result\":{\"drafts\":[],\"monthly-limit\":{\"limit\":100,\"usage\":0}}}";}
                case "/v3/feed" -> {int attempt=posts.incrementAndGet();var payload=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();sentGtin.set(payload.get("gtin").getAsString());if(attempt==1&&feedFailure.equals("429")){status=429;exchange.getResponseHeaders().set("Retry-After","0");body="{\"error\":\"rate limit\"}";}else if(attempt==1&&feedFailure.equals("lost")){exchange.close();return;}else body="{\"result\":{\"feed_id\":\"wire-feed\"}}";}
                case "/v3/feed-status" -> {if(query.contains("old-feed"))status=oldFeedHttp;body=query.contains("old-feed")?"{\"result\":{\"feed_id\":\"old-feed\",\"status\":\""+oldFeedStatus+"\",\"item\":[{\"gtin\":\"04631993764363\",\"good_id\":90,\"status_code\":"+("Rejected".equals(oldFeedStatus)?1:0)+"}]}}":"{\"result\":{\"feed_id\":\"wire-feed\",\"status\":\"Processing\",\"item\":[{\"gtin\":\""+sentGtin.get()+"\",\"good_id\":91}]}}";}
                case "/v3/feed-product" -> {if(query.contains("04631993764363"))status=oldCardHttp;body=query.contains("04631993764363")?"{\"result\":[{\"gtin\":\"04631993764363\",\"good_id\":90,\"good_status\":\""+("Rejected".equals(oldFeedStatus)?"draft":"published")+"\",\"good_detailed_status\":[\""+("Rejected".equals(oldFeedStatus)?"errors":"published")+"\"],\"owner_inn\":\""+oldOwner+"\"}]}":"{\"result\":[{\"gtin\":\""+sentGtin.get()+"\",\"good_id\":91,\"good_status\":\"draft\",\"good_detailed_status\":[\"moderation\"]}]}";}
                default -> throw new AssertionError("Unexpected Catalog request: "+path);
            }
        }catch(Throwable failure){serverFailure.compareAndSet(null,failure);status=422;body="{\"error\":\"fixture\"}";}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        var api=ZnackApiClient.forCatalogKey("fixture",()->{});var auth=new ZnackAuthService(api,null){@Override public String trueApiToken(ZnackModels.Settings ignored){return "fixture-token";}};
        var catalogSettings=new ZnackModels.Settings("http://127.0.0.1:"+server.getAddress().getPort(),"","","","","","","","","[]","","","",false);
        var catalog=new ZnackNationalCatalogService(api,auth,(bytes,ctx)->{throw new AssertionError("No signing");},catalogSettings);
        var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(1,shop.getName())).getSettings());
        session=new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",catalog,scope);
        var workflow=new ZnackCardRegistrationWorkflow(repository,ignored->session,Runnable::run,false,(owner,nm,variantId,gtin)->false,RegistrationWorkflowTestFixtures::emptyLookup,(owner,product)->changed?new ProductSnapshot(snapshot.key(),snapshot.revision(),"Changed title",snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),snapshot.variants(),snapshot.dimensions(),snapshot.documents(),snapshot.checkedAt(),snapshot.metadata()):snapshot);
        batch=new ZnackRegistrationBatchService(repository,workflow,(owner,row,sign)->draft,Runnable::run,false);
    }
    @AfterEach void cleanup(){if(server!=null)server.stop(0);if(serverWorkers!=null)serverWorkers.shutdownNow();System.clearProperty("vncode.appdata.dir");}
    private RegistrationSourceConsent.Prepared review(boolean approve)throws Exception{
        return review(approve,RegistrationSourceConsent.forIdentity("",null));
    }
    private RegistrationSourceConsent.Prepared review(boolean approve,RegistrationSourceConsent.Target target)throws Exception{
        var result=new CompletableFuture<RegistrationSourceConsent.Prepared>();
        Platform.runLater(()->{try{var cls=Class.forName("com.vncode.app.ui.znackregistration.RegistrationSourceReviewDialog");var m=cls.getDeclaredMethod("show",Window.class,Shop.class,UUID.class,RegistrationDynamicPreflight.Review.class,Map.class);m.setAccessible(true);result.complete((RegistrationSourceConsent.Prepared)m.invoke(null,null,shop,UUID.randomUUID(),review,Map.of(key,target)));}catch(Throwable e){result.completeExceptionally(e);}});
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);boolean done=false;
        while(System.nanoTime()<deadline&&!done){done=fx(()->{for(var w:List.copyOf(Window.getWindows())){if(!w.isShowing()||w.getScene()==null)continue;var pane=w.getScene().getRoot().lookup(".dialog-pane");if(pane==null||pane.lookup("#registrationSourceReviewTable")==null)continue;if(!approve){((DialogPane)pane).lookupButton(((DialogPane)pane).getButtonTypes().stream().filter(t->t.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow()).fireEvent(new javafx.event.ActionEvent());return true;}var box=(CheckBox)pane.lookup("#registrationSourceConfirm");var button=(Button)pane.lookup("#registrationSourceReviewApply");if(!box.isSelected())box.fire();if(!button.isDisabled()){button.fire();return true;}}return false;});if(!done)Thread.sleep(20);}
        assertTrue(done,"Source review dialog did not become actionable");return result.get(15,TimeUnit.SECONDS);
    }
    private static <T>T fx(Callable<T> c)throws Exception{var t=new FutureTask<>(c);Platform.runLater(t);return t.get(10,TimeUnit.SECONDS);}
    @Test void sellerReviewPersistsAndPostsOneRealHttpFeed()throws Exception{
        var consent=review(true);assertNotNull(consent);String run=batch.start(shop,List.of(repository.find(1,11)),false,consent);
        assertNull(serverFailure.get(),()->"loopback server: "+serverFailure.get());assertEquals(1,allocations.get());assertEquals(1,posts.get());
        assertEquals(Status.PROCESSING,repository.find(1,11).status());assertEquals("wire-feed",repository.find(1,11).feedId());assertEquals(1,batch.latest(shop).submitted());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT phase FROM znack_source_consents WHERE run_id=?")){p.setString(1,run);try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("SUBMITTED",r.getString(1));}}
    }
    private Sku publishedOldCard(){
        var row=repository.find(1,11);repository.saveGenerated(1,row,"04631993764363","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764363",draft,"").toString());
        repository.updateProgress(1,11,Status.PUBLISHED,"old-feed",90L,null,true);return repository.find(1,11);
    }
    private Sku rejectedOldCard(){
        var row=repository.find(1,11);repository.saveGenerated(1,row,"04631993764363","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764363",draft,"").toString());
        repository.updateProgress(1,11,Status.ERROR,"old-feed",90L,"Rejected",false);return repository.find(1,11);
    }
    private RegistrationSourceConsent.Prepared approvedReplacement(Sku old){
        return RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),review.plans(),Map.of(key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.REPLACE,old.gtin(),old.goodId())),Map.of(),Map.of(key,RegistrationFieldProvenance.read(shop,snapshot,draft,schema)),false);
    }
    @Test void sellerReviewedReplacementKeepsOldCardAndSendsOnlyOneNewFeed()throws Exception{
        var old=publishedOldCard();var approved=approvedReplacement(old);
        String run=batch.start(shop,List.of(old),false,approved);
        assertNull(serverFailure.get(),()->"loopback server: "+serverFailure.get());assertEquals(1,allocations.get());assertEquals(1,posts.get());
        assertEquals("04631993764370",repository.find(1,11).gtin());assertEquals("wire-feed",repository.find(1,11).feedId());assertEquals("04631993764363",repository.activeGtin(1,11));
        batch.resumePending(shop);assertEquals(1,allocations.get());assertEquals(1,posts.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT replaced_gtin_hash,replaced_good_id,expected_gtin_hash FROM znack_source_consents WHERE run_id=?")){p.setString(1,run);try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(ListingIdentity.hash(old.gtin()),r.getString(1));assertEquals(90,r.getLong(2));assertEquals(ListingIdentity.hash("04631993764370"),r.getString(3));}}
    }
    @Test void changedTnvedReplacementUsesFreshGtinAndKeepsOldUntilReadback()throws Exception{
        var previousDraft=new Draft("6104",1,"Fixture","Brand",Map.of());
        var row=repository.find(1,11);repository.saveGenerated(1,row,"04631993764363","6104",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764363",previousDraft,"").toString());
        repository.updateProgress(1,11,Status.PUBLISHED,"old-feed",90L,null,true);
        var old=repository.find(1,11);
        batch.start(shop,List.of(old),false,approvedReplacement(old));
        assertNull(serverFailure.get());assertEquals(1,allocations.get());assertEquals(1,posts.get());
        assertEquals("04631993764370",repository.find(1,11).gtin());assertEquals(old.gtin(),repository.activeGtin(1,11));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT active_gtin,active_payload_json FROM znack_card_registration_replacements")){
            assertTrue(r.next());assertEquals(old.gtin(),r.getString(1));assertTrue(r.getString(2).contains("6104"));
        }
    }
    @Test void replacementCandidateActivatesOnlyAfterVerifiedMarketplaceReadback()throws Exception{
        var old=publishedOldCard();batch.start(shop,List.of(old),false,approvedReplacement(old));
        assertEquals(1,allocations.get());assertEquals(1,posts.get());
        repository.updateProgress(1,11,Status.PUBLISHED,"wire-feed",91L,null,true);
        var noReadback=RegistrationWorkflowTestFixtures.workflow(repository,ignored->session,Runnable::run,false,(s,n,v,g)->false);
        assertTrue(noReadback.completeReplacement(shop,repository.find(1,11),true,null));assertEquals(old.gtin(),repository.activeGtin(1,11));
        com.vncode.app.features.gtinsync.GtinWriteTestFixture.saveSettings(shop,"A".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        var actual=repository.find(1,11);var details=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of(actual.goodId().toString()),"@catalog_status",List.of("published")),List.of(),List.of());
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,shop.getMarketplace(),List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(actual.gtin(),"ARTICLE","","XL",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),Instant.now(),details)));
        var matched=RegistrationWorkflowTestFixtures.workflow(repository,ignored->session,Runnable::run,false,(s,n,v,g)->g.equals("04631993764370"));
        assertTrue(matched.completeReplacement(shop,repository.find(1,11),true,null));assertEquals("04631993764370",repository.activeGtin(1,11));
        assertEquals(1,allocations.get());assertEquals(1,posts.get());
    }
    @Test void replacementOwnerMismatchLeavesOriginalCardAndDoesNotSend()throws Exception{
        var old=publishedOldCard();oldOwner="9999999999";var approved=approvedReplacement(old);
        batch.start(shop,List.of(old),false,approved);assertEquals(0,allocations.get());assertEquals(0,posts.get());assertEquals(old.gtin(),repository.find(1,11).gtin());assertEquals("old-feed",repository.find(1,11).feedId());assertEquals(Status.PUBLISHED,repository.find(1,11).status());
    }
    @Test void rejectedCardNeedsExactReviewAndTerminalCatalogRejectionBeforeReplacement()throws Exception{
        var old=rejectedOldCard();assertEquals(Status.ERROR,old.status());
        oldFeedStatus="Rejected";var approved=approvedReplacement(old);batch.start(shop,List.of(old),false,approved);
        assertNull(serverFailure.get());assertEquals(1,allocations.get());assertEquals(1,posts.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT gtin,good_id,feed_id,status FROM znack_card_registration_history WHERE shop_id=1 AND chrt_id=11");var r=p.executeQuery()){
            assertTrue(r.next());assertEquals(old.gtin(),r.getString(1));assertEquals(90,r.getLong(2));assertEquals("old-feed",r.getString(3));assertEquals("ERROR",r.getString(4));
        }
    }
    @Test void errorRowWithFeedStillProcessingCannotArchiveOrAllocate()throws Exception{
        var old=rejectedOldCard();assertEquals(Status.ERROR,old.status());
        oldFeedStatus="Processing";batch.start(shop,List.of(old),false,approvedReplacement(old));
        assertEquals(0,allocations.get());assertEquals(0,posts.get());assertEquals(old.gtin(),repository.find(1,11).gtin());assertEquals("old-feed",repository.find(1,11).feedId());
    }
    @Test void rejectedCardWithWrongOwnerCannotArchiveOrAllocate()throws Exception{
        var old=rejectedOldCard();oldFeedStatus="Rejected";oldOwner="9999999999";
        batch.start(shop,List.of(old),false,approvedReplacement(old));assertOldNotReplaced(old);
    }
    @Test void rejectedCardWithoutSellerReviewCannotArchiveOrAllocate()throws Exception{
        var old=rejectedOldCard();oldFeedStatus="Rejected";
        assertThrows(RuntimeException.class,()->batch.start(shop,List.of(old),false,null));assertOldNotReplaced(old);
    }
    @Test void rejectedCardWithMissingFeedCannotArchiveOrAllocate()throws Exception{
        var old=rejectedOldCard();oldFeedStatus="Rejected";oldFeedHttp=404;
        batch.start(shop,List.of(old),false,approvedReplacement(old));assertOldNotReplaced(old);
    }
    @Test void rejectedCardWithUnreadableCardCannotArchiveOrAllocate()throws Exception{
        var old=rejectedOldCard();oldFeedStatus="Rejected";oldCardHttp=404;
        batch.start(shop,List.of(old),false,approvedReplacement(old));assertOldNotReplaced(old);
    }
    private void assertOldNotReplaced(Sku old)throws Exception{
        assertEquals(0,allocations.get());assertEquals(0,posts.get());assertEquals(old.gtin(),repository.find(1,11).gtin());assertEquals(old.feedId(),repository.find(1,11).feedId());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT count(*) FROM znack_card_registration_history WHERE shop_id=1 AND chrt_id=11");var r=p.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
    @Test void cancelReviewCannotStartAWrite()throws Exception {assertNull(review(false));assertEquals(0,allocations.get());assertEquals(0,posts.get());}
    @Test void changedSourceAfterReviewCannotAllocateOrSubmit()throws Exception{
        var consent=review(true);changed=true;batch.start(shop,List.of(repository.find(1,11)),false,consent);
        assertEquals(0,allocations.get());assertEquals(0,posts.get());assertNotEquals(Status.PROCESSING,repository.find(1,11).status());
    }
    @Test void explicitRateLimitRetriesSameGtinAfterSellerReview()throws Exception{
        feedFailure="429";var consent=review(true);String run=batch.start(shop,List.of(repository.find(1,11)),false,consent);
        assertEquals(1,allocations.get());assertEquals(1,posts.get());String gtin=repository.find(1,11).gtin();
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_registration_batches SET retry_at=NULL WHERE id=?")){p.setString(1,run);p.executeUpdate();}
        assertTrue(batch.resumePending(shop));assertEquals(gtin,repository.find(1,11).gtin());assertEquals(1,allocations.get());assertEquals(2,posts.get());assertEquals(Status.PROCESSING,repository.find(1,11).status());
    }
    @Test void lostFeedResponseIsNeverBlindlyReplayed()throws Exception{
        feedFailure="lost";var consent=review(true);batch.start(shop,List.of(repository.find(1,11)),false,consent);
        assertEquals(1,allocations.get());assertEquals(1,posts.get());assertEquals(Status.FEED_REVIEW_REQUIRED,repository.find(1,11).status());
        batch.resumePending(shop);assertEquals(1,allocations.get());assertEquals(1,posts.get());
    }
}
