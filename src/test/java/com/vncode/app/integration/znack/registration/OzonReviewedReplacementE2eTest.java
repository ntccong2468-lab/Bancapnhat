package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.ProductRepository;
import com.vncode.app.testsupport.RegistrationSourceFixture;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Window;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic Catalog only: replacement must retain old identity and require seller consent. */
class OzonReviewedReplacementE2eTest {
    @TempDir Path root;
    private OzonCardRegistrationWorkflowTest fixture;
    private OzonRegistrationBatchService service;
    private RegistrationDynamicPreflight.Review review;

    @BeforeAll static void startFx(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException alreadyStarted){}}
    @BeforeEach void setup() throws Exception {
        fixture=new OzonCardRegistrationWorkflowTest();fixture.root=root;fixture.setup();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(?,?,?,?,?,?)")){
            p.setInt(1,fixture.shop.getId());p.setString(2,fixture.key.cardId());p.setString(3,fixture.key.variantId());p.setString(4,"9001");p.setString(5,"Fixture");p.setString(6,Instant.now().toString());p.executeUpdate();
        }
        RegistrationSourceFixture.ready(fixture.shop);new ProductRepository().saveVerifiedSnapshot(fixture.shop,fixture.snapshot);
        assertTrue(fixture.repository.claim(fixture.shop,fixture.key,"9001","",fixture.session().scope(),false));
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_card_registrations SET status='ERROR',gtin=?,active_gtin=?,feed_id='old-feed',good_id=91,payload_json='{}',error='CATALOG_REJECTED' WHERE shop_id=2 AND product_id='501' AND offer_id='fixture-offer'")){
            p.setString(1,GtinWriteTestFixture.GTIN);p.setString(2,GtinWriteTestFixture.GTIN);assertEquals(1,p.executeUpdate());
        }
        fixture.api.rejected=true;fixture.api.rejectionEvidence=true;fixture.api.feedGtins.put("old-feed",GtinWriteTestFixture.GTIN);fixture.api.allocations.set(1);
        var prepared=fixture.workflow.prepare(fixture.shop,fixture.key);var draft=OzonCardRegistrationWorkflow.suggestedDraft(prepared).orElseThrow();
        var plan=new RegistrationDynamicPreflight.Plan(prepared.snapshot(),draft,prepared.required(),prepared.documents());
        RegistrationFieldProvenance.save(fixture.shop,List.of(RegistrationFieldProvenance.capture(plan.snapshot(),plan.snapshot(),draft,plan.schema(),Map.of(),null,false)));
        review=new RegistrationDynamicPreflight.Review(List.of(plan),List.of());service=new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),fixture.repository,fixture.workflow);
    }
    @AfterEach void cleanup(){try{service.cancel(fixture.shop);}catch(RuntimeException ignored){}fixture.clear();}

    private RegistrationSourceConsent.Prepared reviewReplacement() throws Exception {return reviewReplacement(false);}
    private RegistrationSourceConsent.Prepared reviewReplacement(boolean conflict) throws Exception {
        Class<?> dialog=Class.forName("com.vncode.app.ui.znackregistration.RegistrationSourceReviewDialog");
        Method show=dialog.getDeclaredMethod("show",Window.class,com.vncode.app.models.Shop.class,UUID.class,RegistrationDynamicPreflight.Review.class,Map.class);show.setAccessible(true);
        var target=new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.REPLACE,GtinWriteTestFixture.GTIN,91L,conflict);
        var task=new FutureTask<RegistrationSourceConsent.Prepared>(()->(RegistrationSourceConsent.Prepared)show.invoke(null,null,fixture.shop,UUID.randomUUID(),review,Map.of(fixture.key,target)));
        Platform.runLater(task);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        try{while(System.nanoTime()<deadline){var click=new FutureTask<Boolean>(()->{
            for(var window:List.copyOf(Window.getWindows())){if(!window.isShowing()||window.getScene()==null)continue;var root=window.getScene().getRoot();var pane=root instanceof DialogPane d?d:(DialogPane)root.lookup(".dialog-pane");
                if(pane==null||pane.lookup("#registrationSourceReviewTable")==null)continue;
                var facts=(CheckBox)pane.lookup("#registrationSourceConfirm");var replace=(CheckBox)pane.lookup("#registrationSourceConfirmReplacement");var button=(Button)pane.lookup("#registrationSourceReviewApply");
                if(!facts.isSelected())facts.fire();if(!replace.isSelected()){assertTrue(button.isDisabled(),"A separate replacement confirmation is required");replace.fire();}
                if(!button.isDisabled()){button.fire();return true;}
            }return false;});Platform.runLater(click);if(click.get(3,TimeUnit.SECONDS))return task.get(20,TimeUnit.SECONDS);if(task.isDone())return task.get();Thread.sleep(20);}
            throw new AssertionError("Replacement review did not become ready");
        }finally{if(!task.isDone())Platform.runLater(()->{for(var window:List.copyOf(Window.getWindows()))if(window.isShowing())window.hide();});}
    }
    private void waitForDone()throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<deadline){var progress=service.latest(fixture.shop);if(progress!=null&&(progress.failed()>0||progress.acceptedFeeds()>0))return;Thread.sleep(20);}var row=fixture.repository.find(fixture.key);fail("No replacement result: "+service.latest(fixture.shop)+"; row="+row.status()+" / "+row.error()+"; failure="+fixture.workflow.failure(fixture.shop));}
    @Test void sellerReviewedOldIdentityIsVerifiedBeforeNewAllocationAndSend()throws Exception {
        var approval=reviewReplacement();assertNotNull(approval);service.start(fixture.shop,List.of(fixture.key),false,approval);waitForDone();
        var row=fixture.repository.find(fixture.key);assertEquals("00000000000024",row.gtin(),row.status()+" / "+row.error()+" / "+fixture.workflow.failure(fixture.shop));assertFalse(row.feedId().isBlank());assertEquals(2,fixture.api.allocations.get());assertEquals(1,fixture.api.submissions.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT gtin,good_id,feed_id,status FROM ozon_card_registration_history WHERE shop_id=2 AND product_id='501' AND offer_id='fixture-offer'");var r=p.executeQuery()){
            assertTrue(r.next());assertEquals(GtinWriteTestFixture.GTIN,r.getString(1));assertEquals(91,r.getLong(2));assertEquals("old-feed",r.getString(3));assertEquals("ERROR",r.getString(4));assertFalse(r.next());
        }
    }
    @Test void reviewedChangedTnvedArchivesOldCardBeforeReplacement()throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_card_registrations SET payload_json=? WHERE shop_id=2 AND product_id='501' AND offer_id='fixture-offer'")){p.setString(1,"{\"tnved\":\"6104\"}");assertEquals(1,p.executeUpdate());}
        var approval=reviewReplacement();service.start(fixture.shop,List.of(fixture.key),false,approval);waitForDone();
        assertEquals("00000000000024",fixture.repository.find(fixture.key).gtin());assertEquals(2,fixture.api.allocations.get());assertEquals(1,fixture.api.submissions.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT gtin,feed_id,payload_json FROM ozon_card_registration_history WHERE shop_id=2 AND product_id='501' AND offer_id='fixture-offer'");var r=p.executeQuery()){
            assertTrue(r.next());assertEquals(GtinWriteTestFixture.GTIN,r.getString(1));assertEquals("old-feed",r.getString(2));assertTrue(r.getString(3).contains("6104"));assertFalse(r.next());
        }
    }
    @Test void reviewedReplacementKeepsOldMappingUntilExactOzonReadback()throws Exception {
        String old=GtinWriteTestFixture.GTIN,candidate="00000000000024";
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(2,?,'Old fixture',?)")){
            p.setString(1,old);p.setString(2,Instant.now().toString());p.executeUpdate();
        }
        var mappings=new com.vncode.app.integration.ozon.OzonProductGtinMappingRepository();mappings.put(fixture.shop.getId(),"9001",old);
        fixture.api.rejected=false;fixture.api.rejectedFeed="old-feed";fixture.api.rejectedOldCard=true;
        var approval=reviewReplacement();service.start(fixture.shop,List.of(fixture.key),false,approval);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){var progress=service.latest(fixture.shop);if(progress!=null&&progress.state()==OzonRegistrationBatchService.State.COMPLETED)break;Thread.sleep(20);}
        assertEquals(OzonRegistrationBatchService.State.COMPLETED,service.latest(fixture.shop).state());
        var row=fixture.repository.find(fixture.key);assertEquals(candidate,row.gtin(),row.status()+" / "+row.error());assertEquals(old,row.activeGtin());assertEquals(old,mappings.findGtin(2,"9001"));
        assertTrue(fixture.sign());row=fixture.repository.find(fixture.key);assertEquals(ZnackCardRegistrationModels.Status.PUBLISHED,row.status(),row.error());
        assertEquals(old,row.activeGtin());assertEquals(old,mappings.findGtin(2,"9001"));
        var found=new java.util.concurrent.atomic.AtomicBoolean(false);
        var completion=new OzonCardRegistrationWorkflow(fixture.repository,s->fixture.session(),(s,k)->fixture.snapshot,Runnable::run,(s,k,sku,gtin)->{
            assertEquals(fixture.key,k);assertEquals("9001",sku);assertEquals(candidate,gtin);return found.get();
        });
        assertTrue(completion.complete(fixture.shop,fixture.key,true,null));assertEquals(old,fixture.repository.find(fixture.key).activeGtin());assertEquals(old,mappings.findGtin(2,"9001"));
        found.set(true);assertTrue(completion.complete(fixture.shop,fixture.key,true,null));assertEquals(candidate,fixture.repository.find(fixture.key).activeGtin());assertEquals(candidate,mappings.findGtin(2,"9001"));
        assertEquals(2,fixture.api.allocations.get());assertEquals(1,fixture.api.submissions.get());assertEquals(1,fixture.api.signatures.get());
    }
    @Test void changedRemoteIdentityLeavesOldCheckpointAndDoesNotAllocate()throws Exception {
        var approval=reviewReplacement();fixture.api.invalidFeedIdentity="changed";service.start(fixture.shop,List.of(fixture.key),false,approval);waitForDone();
        assertEquals(GtinWriteTestFixture.GTIN,fixture.repository.find(fixture.key).gtin());assertEquals("old-feed",fixture.repository.find(fixture.key).feedId());assertEquals(1,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
    }
    @Test void reviewedConflictPersistsItsIdentityAndIsRecheckedBeforeAllocation()throws Exception {
        var checks=new java.util.concurrent.atomic.AtomicInteger();var prior=fixture.workflow;
        fixture.workflow=new OzonCardRegistrationWorkflow(fixture.repository,s->fixture.session(),(s,k)->fixture.snapshot,Runnable::run,(s,k,sku,g)->false,prior::lookupOperation,(s,k,g)->{assertEquals(GtinWriteTestFixture.GTIN,g);checks.incrementAndGet();});
        service=new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),fixture.repository,fixture.workflow);
        var approval=reviewReplacement(true);String run=service.start(fixture.shop,List.of(fixture.key),false,approval);waitForDone();
        assertEquals(2,checks.get());assertEquals(2,fixture.api.allocations.get());assertEquals(1,fixture.api.submissions.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT ozon_conflict_gtin FROM znack_source_consents WHERE run_id=?")){p.setString(1,run);try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(GtinWriteTestFixture.GTIN,r.getString(1));}}
    }
    @Test void alteredStoredConflictIdentityCannotAuthorizeReplacement()throws Exception {
        var approval=reviewReplacement(true);String run=OzonRegistrationAttemptReports.queue(fixture.shop,List.of(fixture.key));
        new OzonRegistrationBatchRepository().create(fixture.shop,List.of(fixture.key),false,fixture.session().scope(),run,approval);
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_source_consents SET ozon_conflict_gtin='00000000000024' WHERE run_id=?")){p.setString(1,run);assertEquals(1,p.executeUpdate());}
        try(var c=Database.getConnection()){assertThrows(RegistrationValidationException.class,()->RegistrationSourceConsentGuard.ozonConflictGtin(c,new RegistrationIntent(run,0),fixture.key));}
        assertEquals(GtinWriteTestFixture.GTIN,fixture.repository.find(fixture.key).gtin());assertEquals(1,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
    }
    @Test void reviewedConflictRechecksTheRealOzonAdapterAgainstMockHttp()throws Exception {
        try(var server=new okhttp3.mockwebserver.MockWebServer()){
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){@Override public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request){
                String body=request.getBody().readUtf8();String response;
                if(request.getPath().equals("/v3/product/list"))response="{\"result\":{\"total\":2,\"items\":[{\"product_id\":501},{\"product_id\":502}]}}";
                else if(request.getPath().equals("/v3/product/info/list")){
                    String selected="{\"id\":501,\"offer_id\":\"fixture-offer\",\"sku\":9001,\"barcodes\":[]}";
                    String other="{\"id\":502,\"offer_id\":\"other\",\"sku\":9002,\"barcodes\":[\""+GtinWriteTestFixture.GTIN+"\"]}";
                    response="{\"items\":["+selected+(body.contains("502")?","+other:"")+"]}";
                }else return new okhttp3.mockwebserver.MockResponse().setResponseCode(404);
                return new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","application/json").setBody(response);
            }});server.start();
            var transport=new com.vncode.app.integration.listing.ListingHttpClient(fixture.shop,new okhttp3.OkHttpClient(),server.url("/"));
            var adapter=new com.vncode.app.integration.ozon.OzonGtinAdapter(fixture.shop,transport,id->{});var checks=new java.util.concurrent.atomic.AtomicInteger();var prior=fixture.workflow;
            fixture.workflow=new OzonCardRegistrationWorkflow(fixture.repository,s->fixture.session(),(s,k)->fixture.snapshot,Runnable::run,(s,k,sku,g)->false,prior::lookupOperation,(s,k,g)->{
                var target=new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(s.getId(),com.vncode.app.integration.marketplace.Marketplace.OZON,k.cardId(),k.variantId());
                if(com.vncode.app.features.gtinsync.RegisteredGtinProof.contains(adapter.read(target).barcodes(),g)||!adapter.conflicts(target,g))throw new IllegalArgumentException("OZON_GTIN_CONFLICT_NO_LONGER_CONFIRMED");checks.incrementAndGet();
            });
            service=new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),fixture.repository,fixture.workflow);
            service.start(fixture.shop,List.of(fixture.key),false,reviewReplacement(true));waitForDone();
            assertEquals(2,checks.get());assertEquals(1,fixture.api.submissions.get());assertEquals(2,fixture.api.allocations.get());assertTrue(server.getRequestCount()>=6);
        }
    }
    @Test void reportButtonThroughSellerReviewAndDurableBatchRechecksMockHttp()throws Exception {
        try(var server=new okhttp3.mockwebserver.MockWebServer()){
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){@Override public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request){
                String body=request.getBody().readUtf8();String response;
                if(request.getPath().equals("/v3/product/list"))response="{\"result\":{\"total\":2,\"items\":[{\"product_id\":501},{\"product_id\":502}]}}";
                else if(request.getPath().equals("/v3/product/info/list")){
                    String selected="{\"id\":501,\"offer_id\":\"fixture-offer\",\"sku\":9001,\"barcodes\":[]}";
                    String other="{\"id\":502,\"offer_id\":\"other\",\"sku\":9002,\"barcodes\":[\""+GtinWriteTestFixture.GTIN+"\"]}";
                    response="{\"items\":["+selected+(body.contains("502")?","+other:"")+"]}";
                }else return new okhttp3.mockwebserver.MockResponse().setResponseCode(404);
                return new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","application/json").setBody(response);
            }});server.start();
            var transport=new com.vncode.app.integration.listing.ListingHttpClient(fixture.shop,new okhttp3.OkHttpClient(),server.url("/"));
            var adapter=new com.vncode.app.integration.ozon.OzonGtinAdapter(fixture.shop,transport,id->{});var checks=new java.util.concurrent.atomic.AtomicInteger();var prior=fixture.workflow;
            fixture.workflow=new OzonCardRegistrationWorkflow(fixture.repository,s->fixture.session(),(s,k)->fixture.snapshot,Runnable::run,(s,k,sku,g)->false,prior::lookupOperation,(s,k,g)->{
                var target=new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(s.getId(),com.vncode.app.integration.marketplace.Marketplace.OZON,k.cardId(),k.variantId());
                if(com.vncode.app.features.gtinsync.RegisteredGtinProof.contains(adapter.read(target).barcodes(),g)||!adapter.conflicts(target,g))throw new IllegalArgumentException("OZON_GTIN_CONFLICT_NO_LONGER_CONFIRMED");checks.incrementAndGet();
            });
            service=new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),fixture.repository,fixture.workflow);
            var receipt=new com.vncode.app.features.sync.RegistrationSources().require(fixture.shop);var productRows=new ProductRepository().loadPage(new com.vncode.app.features.products.ProductQuery(fixture.shop.getId(),com.vncode.app.integration.marketplace.Marketplace.OZON,"",List.of(),null,false,null,300),receipt.run()).rows();
            assertEquals(1,productRows.size(),"fixture source membership");var index=new com.vncode.app.features.sync.RegistrationCardIndex(fixture.shop);var checkpoint=fixture.repository.find(fixture.key);var cardView=index.viewOzon(checkpoint.status(),checkpoint.gtin(),productRows.getFirst(),index.ownsOzon(checkpoint.owner(),checkpoint.scopeJson()));assertTrue(cardView.blocked());assertEquals("GTIN_VARIANT_CONFLICT",cardView.detail());
            var reports=new com.vncode.app.features.sync.SyncRunRepository();String reportId=reports.begin(fixture.shop,"znack_marketplace","READBACK");
            reports.add(fixture.shop,reportId,fixture.key.cardId(),fixture.key.variantId(),"9001","Fixture","Blue","XL",GtinWriteTestFixture.GTIN,"NEEDS_ACTION","GTIN_ON_ANOTHER_PRODUCT","{}");
            Class<?> paneClass=Class.forName("com.vncode.app.ui.znackregistration.OzonRegistrationPane");
            Class<?> reportClass=Class.forName("com.vncode.app.ui.znackregistration.RegistrationSyncReportDialog$Report");
            Class<?> confirmedClass=Class.forName("com.vncode.app.ui.znackregistration.OzonGtinConflictReview$Confirmed");
            Class<?> conflictClass=Class.forName("com.vncode.app.ui.znackregistration.OzonGtinConflictReview");
            Class<?> probeClass=Class.forName("com.vncode.app.ui.znackregistration.OzonGtinConflictReview$Probe");
            Class<?> starterClass=Class.forName("com.vncode.app.ui.znackregistration.OzonRegistrationPane$BatchStarter");
            var paneTask=new FutureTask<javafx.scene.layout.Pane>(()->(javafx.scene.layout.Pane)paneClass.getConstructor(com.vncode.app.models.Shop.class,Runnable.class).newInstance(fixture.shop,(Runnable)()->{}));Platform.runLater(paneTask);var pane=paneTask.get(15,TimeUnit.SECONDS);
            var preflightClass=Class.forName("com.vncode.app.ui.znackregistration.RegistrationBatchPreflight$Review");var reviewConstructor=preflightClass.getDeclaredConstructor(List.class,List.class,RegistrationDynamicPreflight.Review.class);reviewConstructor.setAccessible(true);
            var reviewObj=reviewConstructor.newInstance(List.of(),List.of(fixture.key),review);
            var reader=paneClass.getDeclaredField("preflightReader");reader.setAccessible(true);reader.set(pane,(java.util.function.BiFunction<com.vncode.app.models.Shop,List<com.vncode.app.features.products.ProductKey>,Object>)(shop,keys)->reviewObj);
            var started=new CountDownLatch(1);var starter=java.lang.reflect.Proxy.newProxyInstance(starterClass.getClassLoader(),new Class<?>[]{starterClass},(proxy,method,args)->{
                if(!method.getName().equals("start"))return null;String run=service.start((com.vncode.app.models.Shop)args[0],(List<com.vncode.app.features.products.ProductKey>)args[1],(boolean)args[2],(RegistrationSourceConsent.Prepared)args[3]);started.countDown();return run;
            });var starterField=paneClass.getDeclaredField("batchStarter");starterField.setAccessible(true);starterField.set(pane,starter);
            var probe=java.lang.reflect.Proxy.newProxyInstance(probeClass.getClassLoader(),new Class<?>[]{probeClass},(proxy,method,args)->{
                var key=(com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey)args[0];String gtin=(String)args[1];
                return method.getName().equals("alreadyOnSelected")?com.vncode.app.features.gtinsync.RegisteredGtinProof.contains(adapter.read(key).barcodes(),gtin):adapter.conflicts(key,gtin);
            });
            var conflictConstructor=conflictClass.getDeclaredConstructor(probeClass);conflictConstructor.setAccessible(true);Object conflictReview=conflictConstructor.newInstance(probe);
            var prepare=paneClass.getDeclaredMethod("prepareConfirmedConflict",confirmedClass);prepare.setAccessible(true);
            var callback=(java.util.function.Consumer<Object>)value->{try{prepare.invoke(pane,value);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}};
            var reportConstructor=reportClass.getDeclaredConstructor(com.vncode.app.models.Shop.class,String.class,java.util.function.Consumer.class);reportConstructor.setAccessible(true);
            var reportTask=new FutureTask<javafx.scene.layout.Pane>(()->{var report=(javafx.scene.layout.Pane)reportConstructor.newInstance(fixture.shop,reportId,callback);var setter=reportClass.getDeclaredMethod("setConflictReview",conflictClass);setter.setAccessible(true);setter.invoke(report,conflictReview);return report;});Platform.runLater(reportTask);var report=reportTask.get(15,TimeUnit.SECONDS);
            try{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(System.nanoTime()<deadline){var ready=new FutureTask<Boolean>(()->{
                    var readiness=paneClass.getDeclaredMethod("ready");readiness.setAccessible(true);var table=(TableView<?>)report.lookup("#registrationSyncReportItems");return (boolean)readiness.invoke(pane)&&!table.getItems().isEmpty();});Platform.runLater(ready);if(ready.get(3,TimeUnit.SECONDS))break;Thread.sleep(20);}
                var ordinary=new FutureTask<Void>(()->{var inspect=paneClass.getDeclaredMethod("inspectAllowedKeys",List.class,boolean.class,boolean.class);inspect.setAccessible(true);var denied=assertThrows(java.lang.reflect.InvocationTargetException.class,()->inspect.invoke(pane,List.of(fixture.key),true,true));assertTrue(denied.getCause().getMessage().contains("SOURCE_CARD_ACTION_CHANGED"));return null;});Platform.runLater(ordinary);ordinary.get(5,TimeUnit.SECONDS);
                com.vncode.app.testsupport.FxDialogDriver.choose(()->{var table=(TableView<?>)report.lookup("#registrationSyncReportItems");table.getSelectionModel().select(0);((Button)report.lookup("#registrationConflictNewCard")).fire();},dialog->dialog.getButtonTypes().contains(ButtonType.OK)&&dialog.getButtonTypes().contains(ButtonType.CANCEL),dialog->ButtonType.OK);
                long reviewDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean reviewed=false;
                while(System.nanoTime()<reviewDeadline){var click=new FutureTask<Boolean>(()->{for(var window:List.copyOf(Window.getWindows())){if(!window.isShowing()||window.getScene()==null)continue;var root=window.getScene().getRoot();var dialog=root instanceof DialogPane d?d:(DialogPane)root.lookup(".dialog-pane");if(dialog==null)continue;var facts=dialog.lookup("#registrationSourceConfirm");var replace=dialog.lookup("#registrationSourceConfirmReplacement");var go=dialog.lookup("#registrationSourceReviewApply");if(facts instanceof CheckBox a&&replace instanceof CheckBox b&&go instanceof Button button){if(!a.isSelected())a.fire();if(!b.isSelected()){assertTrue(button.isDisabled());b.fire();}if(!button.isDisabled()){button.fire();return true;}}}return false;});Platform.runLater(click);if(click.get(3,TimeUnit.SECONDS)){reviewed=true;break;}Thread.sleep(20);}
                var diagnostic=new FutureTask<String>(()->{var status=paneClass.getDeclaredField("status");status.setAccessible(true);return "status="+((Label)status.get(pane)).getText()+" windows="+Window.getWindows().stream().filter(Window::isShowing).map(Object::toString).toList();});Platform.runLater(diagnostic);assertTrue(reviewed,diagnostic.get(3,TimeUnit.SECONDS));
                assertTrue(started.await(20,TimeUnit.SECONDS));waitForDone();assertEquals(2,checks.get());assertEquals(2,fixture.api.allocations.get());assertEquals(1,fixture.api.submissions.get());assertTrue(server.getRequestCount()>=8);
                String run=service.latest(fixture.shop).runId();try(var c=Database.getConnection();var p=c.prepareStatement("SELECT ozon_conflict_gtin FROM znack_source_consents WHERE run_id=?")){p.setString(1,run);try(var rows=p.executeQuery()){assertTrue(rows.next());assertEquals(GtinWriteTestFixture.GTIN,rows.getString(1));}}
            }finally{var close=new FutureTask<Void>(()->{var dispose=reportClass.getDeclaredMethod("dispose");dispose.setAccessible(true);dispose.invoke(report);paneClass.getMethod("dispose").invoke(pane);return null;});Platform.runLater(close);close.get(5,TimeUnit.SECONDS);}
        }
    }
    @Test void conflictResolvedAfterReviewAndBeforeAllocationNeverAllocates()throws Exception {
        var checks=new java.util.concurrent.atomic.AtomicInteger();var prior=fixture.workflow;
        fixture.workflow=new OzonCardRegistrationWorkflow(fixture.repository,s->fixture.session(),(s,k)->fixture.snapshot,Runnable::run,(s,k,sku,g)->false,prior::lookupOperation,(s,k,g)->{if(checks.incrementAndGet()>1)throw new IllegalArgumentException("OZON_GTIN_CONFLICT_NO_LONGER_CONFIRMED");});
        service=new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),fixture.repository,fixture.workflow);
        var approval=reviewReplacement(true);service.start(fixture.shop,List.of(fixture.key),false,approval);waitForDone();
        assertEquals(2,checks.get());assertEquals(1,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT gtin,good_id,feed_id,status FROM ozon_card_registration_history WHERE shop_id=2 AND product_id='501' AND offer_id='fixture-offer'");var r=p.executeQuery()){
            assertTrue(r.next());assertEquals(GtinWriteTestFixture.GTIN,r.getString(1));assertEquals(91,r.getLong(2));assertEquals("old-feed",r.getString(3));assertEquals("ERROR",r.getString(4));assertFalse(r.next());
        }
    }
    @Test void reviewedConflictSurvivesWorkerRestartAfterExplicitAllocation429()throws Exception {
        var checks=new java.util.concurrent.atomic.AtomicInteger();var prior=fixture.workflow;fixture.api.allocationRateLimits=1;
        fixture.workflow=new OzonCardRegistrationWorkflow(fixture.repository,s->fixture.session(),(s,k)->fixture.snapshot,Runnable::run,(s,k,sku,g)->false,prior::lookupOperation,(s,k,g)->{assertEquals(GtinWriteTestFixture.GTIN,g);checks.incrementAndGet();});
        service=new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),fixture.repository,fixture.workflow);
        String run=service.start(fixture.shop,List.of(fixture.key),false,reviewReplacement(true));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline&&service.latest(fixture.shop).state()!=OzonRegistrationBatchService.State.WAITING)Thread.sleep(20);
        assertEquals(OzonRegistrationBatchService.State.WAITING,service.latest(fixture.shop).state());assertEquals(2,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
        assertTrue(service.pause(fixture.shop));service=new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),fixture.repository,fixture.workflow);
        assertTrue(service.resumePaused(fixture.shop));waitForDone();assertEquals(3,fixture.api.allocations.get());assertEquals(1,fixture.api.submissions.get());assertTrue(checks.get()>=4);
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT ozon_conflict_gtin FROM znack_source_consents WHERE run_id=?")){p.setString(1,run);try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(GtinWriteTestFixture.GTIN,r.getString(1));}}
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT COUNT(*) FROM ozon_card_registration_history WHERE shop_id=2 AND product_id='501' AND offer_id='fixture-offer' AND gtin=?")){p.setString(1,GtinWriteTestFixture.GTIN);try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(1,r.getInt(1));}}
    }
    @Test void reviewedConflictLostAllocationResponseIsNeverReplayedAfterRestart()throws Exception {
        var prior=fixture.workflow;fixture.api.allocationTimeout=true;
        fixture.workflow=new OzonCardRegistrationWorkflow(fixture.repository,s->fixture.session(),(s,k)->fixture.snapshot,Runnable::run,(s,k,sku,g)->false,prior::lookupOperation,(s,k,g)->{});
        service=new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),fixture.repository,fixture.workflow);
        service.start(fixture.shop,List.of(fixture.key),false,reviewReplacement(true));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline&&fixture.repository.find(fixture.key).status()!=ZnackCardRegistrationModels.Status.GTIN_REVIEW_REQUIRED)Thread.sleep(20);
        assertEquals(ZnackCardRegistrationModels.Status.GTIN_REVIEW_REQUIRED,fixture.repository.find(fixture.key).status());assertEquals(2,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
        fixture.api.allocationTimeout=false;new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),fixture.repository,fixture.workflow).resumePending(fixture.shop);
        assertEquals(2,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT COUNT(*) FROM ozon_card_registration_history WHERE shop_id=2 AND product_id='501' AND offer_id='fixture-offer' AND gtin=?")){p.setString(1,GtinWriteTestFixture.GTIN);try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(1,r.getInt(1));}}
    }
    @Test void aTerminalButNotRejectedOzonFeedCannotReplaceAnErrorCard()throws Exception {
        fixture.api.rejected=false;fixture.api.rejectedFeed="";fixture.api.cleanTerminalFeed=true;
        fixture.api.preExisting=fixture.api.json("{\"gtin\":\""+GtinWriteTestFixture.GTIN+"\",\"good_id\":91,\"good_status\":\"draft\",\"owner_inn\":\""+fixture.session().scope().inn()+"\"}").getAsJsonObject();
        assertTrue(fixture.session().catalog().verifiedTerminalFeeds("fixture-token",List.of("old-feed"),GtinWriteTestFixture.GTIN,91L,fixture.session().scope().inn(),()->{})>0);
        assertFalse(fixture.session().catalog().verifiedRejectedFeed("fixture-token","old-feed",GtinWriteTestFixture.GTIN,91L,fixture.session().scope().inn()));
        var approval=reviewReplacement();String run=OzonRegistrationAttemptReports.queue(fixture.shop,List.of(fixture.key));
        new OzonRegistrationBatchRepository().create(fixture.shop,List.of(fixture.key),false,fixture.session().scope(),run,approval);
        assertThrows(RegistrationValidationException.class,()->CatalogReplacementEvidence.verify(fixture.shop,fixture.key,fixture.session().scope(),new RegistrationIntent(run,0),fixture.session()));
        assertEquals(GtinWriteTestFixture.GTIN,fixture.repository.find(fixture.key).gtin());assertEquals("old-feed",fixture.repository.find(fixture.key).feedId());
        assertEquals(1,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
    }
    @Test void lostAllocationResponseNeverAllocatesAnotherReplacementAfterRestart()throws Exception {
        fixture.api.allocationTimeout=true;var approval=reviewReplacement();service.start(fixture.shop,List.of(fixture.key),false,approval);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline&&fixture.repository.find(fixture.key).status()!=ZnackCardRegistrationModels.Status.GTIN_REVIEW_REQUIRED)Thread.sleep(20);
        assertEquals(ZnackCardRegistrationModels.Status.GTIN_REVIEW_REQUIRED,fixture.repository.find(fixture.key).status());
        assertEquals(2,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
        new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),fixture.repository,fixture.workflow).resumePending(fixture.shop);
        assertEquals(2,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT gtin,good_id,feed_id FROM ozon_card_registration_history WHERE shop_id=2 AND product_id='501' AND offer_id='fixture-offer'");var r=p.executeQuery()){
            assertTrue(r.next());assertEquals(GtinWriteTestFixture.GTIN,r.getString(1));assertEquals(91,r.getLong(2));assertEquals("old-feed",r.getString(3));assertFalse(r.next());
        }
    }
    @Test void explicitlyRejectedAllocation429WaitsAndRetainsOldCard()throws Exception {
        fixture.api.allocationRateLimits=1;var approval=reviewReplacement();service.start(fixture.shop,List.of(fixture.key),false,approval);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){var progress=service.latest(fixture.shop);if(progress!=null&&progress.state()==OzonRegistrationBatchService.State.WAITING)break;Thread.sleep(20);}
        assertEquals(OzonRegistrationBatchService.State.WAITING,service.latest(fixture.shop).state());
        assertEquals(ZnackCardRegistrationModels.Status.PREFLIGHT_ERROR,fixture.repository.find(fixture.key).status());
        assertEquals(2,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
        assertTrue(service.latest(fixture.shop).nextRetryAt().isAfter(Instant.now()));
        assertTrue(service.resumePending(fixture.shop));assertEquals(2,fixture.api.allocations.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT COUNT(*) FROM ozon_card_registration_history WHERE shop_id=2 AND product_id='501' AND offer_id='fixture-offer' AND gtin=?")){
            p.setString(1,GtinWriteTestFixture.GTIN);try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(1,r.getInt(1));}
        }
    }
}
