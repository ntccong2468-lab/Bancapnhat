package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.ProductRepository;
import com.vncode.app.testsupport.RegistrationSourceFixture;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Window;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real seller review dialog + durable Ozon worker, with synthetic external APIs only. */
class OzonSellerRegistrationE2eTest {
    @TempDir Path root;
    private OzonCardRegistrationWorkflowTest fixture;
    private RegistrationDynamicPreflight.Review review;
    private OzonRegistrationBatchService service;

    @BeforeAll static void startFx(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException alreadyStarted){}}
    @BeforeEach void setup() throws Exception {
        fixture=new OzonCardRegistrationWorkflowTest();fixture.root=root;fixture.setup();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(?,?,?,?,?,?)")){
            p.setInt(1,fixture.shop.getId());p.setString(2,fixture.key.cardId());p.setString(3,fixture.key.variantId());p.setString(4,"9001");p.setString(5,"Fixture");p.setString(6,java.time.Instant.now().toString());p.executeUpdate();
        }
        RegistrationSourceFixture.ready(fixture.shop);
        new ProductRepository().saveVerifiedSnapshot(fixture.shop,fixture.snapshot);
        var prepared=fixture.workflow.prepare(fixture.shop,fixture.key);
        var draft=OzonCardRegistrationWorkflow.suggestedDraft(prepared).orElseThrow();
        var plan=new RegistrationDynamicPreflight.Plan(prepared.snapshot(),draft,prepared.required(),prepared.documents());
        RegistrationFieldProvenance.save(fixture.shop,List.of(RegistrationFieldProvenance.capture(plan.snapshot(),plan.snapshot(),draft,plan.schema(),Map.of(),null,false)));
        review=new RegistrationDynamicPreflight.Review(List.of(plan),List.of());
        service=new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),fixture.repository,fixture.workflow);
    }
    @AfterEach void cleanup(){try{service.cancel(fixture.shop);}catch(RuntimeException ignored){}fixture.clear();}

    private RegistrationSourceConsent.Prepared sellerReview(boolean accept) throws Exception {
        Class<?> dialog=Class.forName("com.vncode.app.ui.znackregistration.RegistrationSourceReviewDialog");
        Method show=dialog.getDeclaredMethod("show",Window.class,com.vncode.app.models.Shop.class,UUID.class,RegistrationDynamicPreflight.Review.class,Map.class);show.setAccessible(true);
        var task=new FutureTask<RegistrationSourceConsent.Prepared>(()->(RegistrationSourceConsent.Prepared)show.invoke(null,null,fixture.shop,UUID.randomUUID(),review,Map.of(fixture.key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null))));
        Platform.runLater(task);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        try {
            while(System.nanoTime()<deadline){
                var click=new FutureTask<Boolean>(()->{
                    for(var window:List.copyOf(Window.getWindows())){
                        if(!window.isShowing()||window.getScene()==null)continue;
                        var root=window.getScene().getRoot();var pane=root instanceof DialogPane d?d:(DialogPane)root.lookup(".dialog-pane");
                        if(pane==null||pane.lookup("#registrationSourceReviewTable")==null)continue;
                        if(!accept){var type=pane.getButtonTypes().stream().filter(t->t.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();((Button)pane.lookupButton(type)).fire();return true;}
                        var check=(CheckBox)pane.lookup("#registrationSourceConfirm");var button=(Button)pane.lookup("#registrationSourceReviewApply");
                        if(!check.isSelected())check.fire();if(!button.isDisabled()){button.fire();return true;}
                    }return false;
                });Platform.runLater(click);if(click.get(3,TimeUnit.SECONDS))return task.get(20,TimeUnit.SECONDS);
                if(task.isDone())return task.get();Thread.sleep(20);
            }
            throw new AssertionError("Source review did not become ready");
        }finally{
            if(!task.isDone())Platform.runLater(()->{for(var window:List.copyOf(Window.getWindows()))if(window.isShowing())window.hide();});
        }
    }
    private void waitForFeed() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){
            var row=fixture.repository.find(fixture.key);if(row!=null&&!row.feedId().isBlank())return;
            var progress=service.latest(fixture.shop);if(progress!=null&&(progress.state()==OzonRegistrationBatchService.State.FAILED||progress.failed()>0))fail("Batch failed: "+progress.detail()+" / "+(row==null?"no native row":row.error()));
            Thread.sleep(20);
        }
        fail("No acknowledged feed arrived");
    }
    @Test void oneSellerApprovedCardIsActuallySubmittedOnceToFakeCatalog() throws Exception {
        var approved=sellerReview(true);assertNotNull(approved);
        String run=service.start(fixture.shop,List.of(fixture.key),false,approved);assertFalse(run.isBlank());
        waitForFeed();assertEquals(1,fixture.api.allocations.get());assertEquals(1,fixture.api.submissions.get());assertFalse(fixture.repository.find(fixture.key).feedId().isBlank());
    }
    @Test void cancellingTheRealReviewDoesNotCreateAQueueOrWrite() throws Exception {
        assertNull(sellerReview(false));assertNull(service.latest(fixture.shop));assertNull(fixture.repository.find(fixture.key));
        assertEquals(0,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
    }
    @Test void changedProductAfterSellerReviewCannotAllocateOrSend() throws Exception {
        var approved=sellerReview(true);assertNotNull(approved);
        var original=fixture.snapshot;
        fixture.snapshot=new com.vncode.app.features.products.ProductSnapshot(original.key(),original.revision(),"Changed title",original.description(),original.brand(),original.vendorArticle(),original.categoryId(),original.media(),original.attributes(),original.variants(),original.dimensions(),original.documents(),original.checkedAt(),original.metadata());
        service.start(fixture.shop,List.of(fixture.key),false,approved);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){
            var progress=service.latest(fixture.shop);
            if(progress!=null&&progress.failed()>0)break;
            Thread.sleep(20);
        }
        assertTrue(service.latest(fixture.shop).failed()>0,"The changed variant needs another seller review");
        assertEquals(0,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
    }
    @Test void directFeed429RetriesSameSellerApprovedGtinWithoutAnotherAllocation() throws Exception {
        fixture.api.feedRateLimits=1;fixture.api.writeRetryAfter=java.time.Duration.ofSeconds(1);
        var approved=sellerReview(true);assertNotNull(approved);
        String run=service.start(fixture.shop,List.of(fixture.key),false,approved);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){var progress=service.latest(fixture.shop);if(progress!=null&&progress.state()==OzonRegistrationBatchService.State.WAITING)break;Thread.sleep(20);}
        assertEquals(OzonRegistrationBatchService.State.WAITING,service.latest(fixture.shop).state());
        assertEquals(1,fixture.api.allocations.get());assertEquals(1,fixture.api.submissions.get());String gtin=fixture.repository.find(fixture.key).gtin();assertFalse(gtin.isBlank());
        assertTrue(service.latest(fixture.shop).nextRetryAt().isAfter(java.time.Instant.now()));
        assertTrue(service.resumePending(fixture.shop));
        assertEquals(1,fixture.api.submissions.get(),"A resume before the retry deadline must not POST again");
        waitForFeed();
        assertEquals(gtin,fixture.repository.find(fixture.key).gtin());assertEquals(1,fixture.api.allocations.get());assertEquals(2,fixture.api.submissions.get());
    }
    @Test void lostFeedResponseKeepsItsGtinAndNeverReplaysAfterWorkerRestart() throws Exception {
        fixture.api.feedTimeout=true;
        var approved=sellerReview(true);assertNotNull(approved);
        service.start(fixture.shop,List.of(fixture.key),false,approved);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){
            var row=fixture.repository.find(fixture.key);
            if(row!=null&&row.status()==ZnackCardRegistrationModels.Status.FEED_REVIEW_REQUIRED)break;
            Thread.sleep(20);
        }
        var row=fixture.repository.find(fixture.key);assertNotNull(row);
        assertEquals(ZnackCardRegistrationModels.Status.FEED_REVIEW_REQUIRED,row.status());
        assertFalse(row.gtin().isBlank());assertTrue(row.feedId().isBlank());
        assertEquals(1,fixture.api.allocations.get());assertEquals(1,fixture.api.submissions.get());
        var restarted=new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),fixture.repository,fixture.workflow);
        restarted.resumePending(fixture.shop);
        assertEquals(row.gtin(),fixture.repository.find(fixture.key).gtin());
        assertEquals(1,fixture.api.allocations.get());assertEquals(1,fixture.api.submissions.get());
    }
}
