package com.vncode.app.ui.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class GtinSyncControllerTest {
    @TempDir Path temp;
    final GtinSyncRepository repository=new GtinSyncRepository();
    final RegisteredGtin first=new RegisteredGtin("00000000000017","PANTS","Đen","XL",true,true,"NK",Instant.now(),catalogDetails("91"));
    final RegisteredGtin alternate=new RegisteredGtin("00000000000024","PANTS","Đen","L",true,true,"NK",Instant.now(),catalogDetails("92"));
    final List<ProductSnapshot> products=List.of(
            ProductFingerprints.snapshot(new ProductKey(1,Marketplace.WILDBERRIES,"101","11"),"PANTS","Đen","XL",List.of("old","keep")),
            ProductFingerprints.snapshot(new ProductKey(1,Marketplace.WILDBERRIES,"102","12"),"PANTS","Đen","L",List.of("other")));
    FXMLLoader loader;GtinSyncController controller;
    CountDownLatch sendEntered=new CountDownLatch(1),releaseSend=new CountDownLatch(1);

    @BeforeAll static void toolkit()throws Exception {
        try{Platform.startup(()->{});}catch(IllegalStateException alreadyStarted){}
        fx(()->{Platform.setImplicitExit(false);return null;});
    }
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture','WILDBERRIES','test')");}
        repository.saveCatalog(1,Marketplace.WILDBERRIES,List.of(first,alternate));
    }
    @AfterEach void cleanup()throws Exception {
        releaseSend.countDown();
        if(controller!=null)fx(()->{controller.dispose();return null;});
        await(()->!com.vncode.app.shared.AppTaskExecutor.hasRunningTasks());
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }
    @Test void previewUsesEditedOperationTargetAndOldBarcodeWithoutSavingMapping()throws Exception {
        load(null,List.of());
        fx(()->{
            table("productTable").getSelectionModel().select(0);
            this.<RegisteredGtin>combo("gtinBox").setValue(alternate);
            this.<Operation>combo("operationBox").setValue(Operation.REPLACE);
            this.<String>combo("oldBarcodeBox").setValue("old");
            button("previewButton").fire();return null;
        });
        await(()->!fxUnchecked(()->button("previewButton").isDisable()));
        String text=fx(()->((TextArea)loader.getNamespace().get("previewArea")).getText());
        assertTrue(text.contains("old → "+alternate.gtin()),text);
        assertFalse(text.contains("→ "+first.gtin()),text);
        assertEquals("",repository.mapping(products.getFirst().key()));
        assertTrue(repository.pending(1,Marketplace.WILDBERRIES).isEmpty());
        assertTrue(fx(()->button("confirmButton").isDisable()),"Unverified production adapter must remain blocked");
    }
    @Test void personalAppConfirmsFixtureOperationWithoutWcodeActivation()throws Exception {
        var cap=new Capability(true,true,"");
        var submissions=new java.util.concurrent.atomic.AtomicInteger();
        var adapter=new GtinMarketplaceAdapter(){
            public Capability capability(ProductKey key){return cap;}
            public ProductSnapshot read(ProductKey key){return products.stream().filter(p->p.key().equals(key)).findFirst().orElseThrow();}
            public boolean conflicts(ProductKey key,String gtin){return false;}
            public void validateIdentity(ProductSnapshot target,RegisteredGtin source){assertExactIdentity(target,source);}
            public Submission submit(Preview p){submissions.incrementAndGet();return new Submission("personal-fixture");}
            public Verification verify(Preview p,Submission submission){return Verification.APPLIED;}
        };
        load(adapter,List.of());
        fx(()->{
            table("productTable").getSelectionModel().select(0);
            this.<RegisteredGtin>combo("gtinBox").setValue(first);
            this.<Operation>combo("operationBox").setValue(Operation.ADD);
            button("previewButton").fire();return null;
        });
        await(()->!fxUnchecked(()->button("confirmButton").isDisable()));
        fx(()->{button("confirmButton").fire();return null;});
        await(()->submissions.get()==1);
        await(()->repository.history(1,Marketplace.WILDBERRIES).stream().anyMatch(i->i.status()==Status.SUCCEEDED));
        assertFalse(java.nio.file.Files.exists(temp.resolve("license.json")));
        assertEquals(1,submissions.get());
    }
    @Test void wbPriceWarningIsVisibleBeforeExistingConfirm()throws Exception {
        var submissions=new java.util.concurrent.atomic.AtomicInteger();
        var adapter=new GtinMarketplaceAdapter(){
            public Capability capability(ProductKey key){return new Capability(true,true,"");}
            public ProductSnapshot read(ProductKey key){return products.getFirst();}
            public boolean conflicts(ProductKey key,String gtin){return false;}
            public void validateIdentity(ProductSnapshot target,RegisteredGtin source){assertExactIdentity(target,source);}
            public Submission submit(Preview p){submissions.incrementAndGet();return new Submission("never");}
            public Verification verify(Preview p,Submission s){return Verification.APPLIED;}
        };
        load(adapter,List.of());
        fx(()->{table("productTable").getSelectionModel().select(0);this.<RegisteredGtin>combo("gtinBox").setValue(first);this.<Operation>combo("operationBox").setValue(Operation.ADD);button("previewButton").fire();return null;});
        await(()->!fxUnchecked(()->button("confirmButton").isDisable()));
        fx(()->{var notice=(javafx.scene.control.Label)loader.getNamespace().get("wbPriceImpactLabel");assertTrue(notice.isVisible());assertEquals(I18nService.getInstance().tr("gtinsync.wb_price_consent_body"),notice.getText());return null;});
        assertEquals(0,submissions.get());assertTrue(repository.pending(1,Marketplace.WILDBERRIES).isEmpty());
        assertFalse(fx(()->button("confirmButton").isDisabled()),"The existing confirmation remains available");
    }
    @Test void cancelledGtinShowsPriceRequestStillInFlightInHistory()throws Exception {
        var cap=new Capability(true,true,"");UUID job=repository.createJob(List.of(new Preview(products.getFirst(),Operation.ADD,"",first.gtin(),cap)));
        repository.cancelKnownUnsent(job);var item=repository.jobItems(job).getFirst();
        repository.issue(item.id(),"WB_PRICE_REQUEST_IN_FLIGHT");load(null,repository.jobItems(job));
        await(()->fxUnchecked(()->{
            var history=this.<JobItem>table("historyTable");if(history.getItems().isEmpty())return false;
            String status=String.valueOf(history.getColumns().getLast().getCellData(history.getItems().getFirst()));
            return status.contains(I18nService.getInstance().tr("znack.registration.reason.WB_PRICE_REQUEST_IN_FLIGHT"));
        }));
    }
    @Test void failedHistoryIssueReadReportsFailureAndCanRecoverOnReload()throws Exception {
        var cap=new Capability(true,true,"");UUID job=repository.createJob(List.of(new Preview(products.getFirst(),Operation.ADD,"",first.gtin(),cap)));
        repository.cancelKnownUnsent(job);var items=repository.jobItems(job);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DROP TABLE sync_gtin_issues");}
        load(null,items);
        await(()->fxUnchecked(()->{
            var history=this.<JobItem>table("historyTable");if(history.getItems().isEmpty())return false;
            String status=String.valueOf(history.getColumns().getLast().getCellData(history.getItems().getFirst()));
            return status.contains(I18nService.getInstance().tr("gtinsync.history_details_unavailable"));
        }));
        Database.initDatabase();repository.issue(items.getFirst().id(),"WB_PRICE_REQUEST_IN_FLIGHT");
        fx(()->{var method=GtinSyncController.class.getDeclaredMethod("showHistory",List.class);method.setAccessible(true);method.invoke(controller,items);return null;});
        await(()->fxUnchecked(()->{
            var history=this.<JobItem>table("historyTable");String status=String.valueOf(history.getColumns().getLast().getCellData(history.getItems().getFirst()));
            return status.contains(I18nService.getInstance().tr("znack.registration.reason.WB_PRICE_REQUEST_IN_FLIGHT"));
        }));
    }
    @Test void runningQueuePublishesStateAndAllowsCancellingUnsentRows()throws Exception {
        var cap=new Capability(true,true,"");
        UUID job=repository.createJob(products.stream().map(p->new Preview(p,Operation.ADD,"",p.key().productId().equals("101")?first.gtin():alternate.gtin(),cap)).toList());
        var adapter=new GtinMarketplaceAdapter(){
            public Capability capability(ProductKey key){return cap;}
            public ProductSnapshot read(ProductKey key){return products.stream().filter(p->p.key().equals(key)).findFirst().orElseThrow();}
            public boolean conflicts(ProductKey key,String gtin){return false;}
            public void validateIdentity(ProductSnapshot target,RegisteredGtin source){assertExactIdentity(target,source);}
            public Submission submit(Preview p)throws Exception{sendEntered.countDown();assertTrue(releaseSend.await(10,TimeUnit.SECONDS));return new Submission("fixture-task");}
            public Verification verify(Preview p,Submission submission){return Verification.APPLIED;}
        };
        load(adapter,repository.jobItems(job));
        fx(()->{table("historyTable").getSelectionModel().select(0);return null;});
        fx(()->{button("resumeButton").fire();return null;});
        assertTrue(sendEntered.await(10,TimeUnit.SECONDS));
        assertFalse(fx(()->button("pauseButton").isDisable()),"Pause must stay usable while a mutation is running");
        assertFalse(fx(()->button("cancelButton").isDisable()),"Cancel-unsent must stay usable while a mutation is running");
        await(()->fxUnchecked(()->this.<JobItem>table("historyTable").getItems().stream().anyMatch(i->i.status()==Status.SENDING)));
        fx(()->{button("cancelButton").fire();return null;});
        await(()->repository.jobItems(job).stream().anyMatch(i->i.status()==Status.CANCELLED));
        assertEquals(1,repository.jobItems(job).stream().filter(i->i.status()==Status.SENDING).count());
        releaseSend.countDown();
        await(()->repository.jobItems(job).stream().anyMatch(i->i.status()==Status.SUCCEEDED));
        assertEquals(1,repository.jobItems(job).stream().filter(i->i.status()==Status.CANCELLED).count());
    }
    @Test void manualGtinQueueReservesAnHttpSlotForContinuousFbsTraffic()throws Exception{
        var cap=new Capability(true,true,"");UUID job=repository.createJob(List.of(new Preview(products.getFirst(),Operation.ADD,"",first.gtin(),cap)));
        var adapter=new GtinMarketplaceAdapter(){
            public Capability capability(ProductKey key){return cap;}public ProductSnapshot read(ProductKey key){return products.getFirst();}public boolean conflicts(ProductKey key,String gtin){return false;}
            public void validateIdentity(ProductSnapshot target,RegisteredGtin source){assertExactIdentity(target,source);}
            public Submission submit(Preview preview)throws Exception{try(var slot=com.vncode.app.shared.MarketplaceRequestBudget.acquire()){sendEntered.countDown();assertTrue(releaseSend.await(10,TimeUnit.SECONDS));return new Submission("synthetic-submit");}}
            public Verification verify(Preview preview,Submission submitted){return Verification.APPLIED;}
        };
        load(adapter,repository.jobItems(job));var workers=Executors.newFixedThreadPool(2);var backgroundStarted=new CountDownLatch(1);var backgroundAcquired=new CountDownLatch(1);
        try{
            fx(()->{table("historyTable").getSelectionModel().select(0);return null;});
            fx(()->{button("resumeButton").fire();return null;});assertTrue(sendEntered.await(5,TimeUnit.SECONDS));
            var anotherCatalog=workers.submit(()->{try(var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(true)){backgroundStarted.countDown();try(var slot=com.vncode.app.shared.MarketplaceRequestBudget.acquire()){backgroundAcquired.countDown();}}return true;});assertTrue(backgroundStarted.await(2,TimeUnit.SECONDS));assertFalse(backgroundAcquired.await(100,TimeUnit.MILLISECONDS),"A second background request must wait while manual GTIN sending occupies its one background slot");
            var fbs=workers.submit(()->{try(var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(false);var slot=com.vncode.app.shared.MarketplaceRequestBudget.acquire()){return true;}});assertTrue(fbs.get(2,TimeUnit.SECONDS),"FBS must acquire the reserved HTTP slot without waiting for the GTIN submission");
            releaseSend.countDown();assertTrue(anotherCatalog.get(5,TimeUnit.SECONDS));await(()->repository.jobItems(job).getFirst().status()==Status.SUCCEEDED);
        }finally{releaseSend.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    private void load(GtinMarketplaceAdapter adapter,List<JobItem> history)throws Exception {
        fx(()->{
            loader=new FXMLLoader(GtinSyncController.class.getResource("gtin-sync-view.fxml"));
            if(adapter!=null){var workspace=new GtinSyncWorkspace(repository,key->adapter,cohort->{
                assertFalse(cohort.isEmpty());
                for(var entry:cohort.entrySet()){
                    var target=products.stream().filter(p->p.key().equals(entry.getKey())).findFirst().orElseThrow();
                    var expected=target.key().equals(products.getFirst().key())?first:alternate;
                    assertEquals(expected.gtin(),entry.getValue());
                    assertExactIdentity(target,repository.catalogItem(entry.getKey().shopId(),entry.getKey().marketplace(),entry.getValue()));
                }
            });loader.setControllerFactory(type->new GtinSyncController(workspace));}
            Parent root=loader.load();controller=loader.getController();controller.setShop(new Shop(1,"Fixture","test"));
            var field=GtinSyncController.class.getDeclaredField("model");field.setAccessible(true);var model=(GtinSyncViewModel)field.get(controller);
            var accept=GtinSyncController.class.getDeclaredMethod("accept",GtinSyncViewModel.LoadToken.class,GtinSyncWorkspace.Data.class);accept.setAccessible(true);
            accept.invoke(controller,model.token(),new GtinSyncWorkspace.Data(List.of(first,alternate),products,history,Map.of()));return null;
        });
        await(()->!com.vncode.app.shared.AppTaskExecutor.hasRunningTasks());
    }
    private static com.vncode.app.features.catalogsync.CatalogProductData catalogDetails(String id){
        return new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of(id),"@catalog_status",List.of("published")),List.of(),List.of());
    }
    private static void assertExactIdentity(ProductSnapshot target,RegisteredGtin source){
        assertNotNull(source);assertEquals(target.article(),source.article());assertEquals(target.color(),source.color());assertEquals(target.size(),source.size());
        assertTrue(source.published());assertTrue(source.tradeUnit());assertFalse(source.details().single("@catalog_id").isBlank());assertEquals("published",source.details().single("@catalog_status"));
    }
    private Button button(String id){return (Button)loader.getNamespace().get(id);}
    @SuppressWarnings("unchecked") private <T>TableView<T> table(String id){return (TableView<T>)loader.getNamespace().get(id);}
    @SuppressWarnings("unchecked") private <T>ComboBox<T> combo(String id){return (ComboBox<T>)loader.getNamespace().get(id);}
    private static <T>T fx(Callable<T> action)throws Exception {var task=new FutureTask<>(action);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    private static <T>T fxUnchecked(Callable<T> action){try{return fx(action);}catch(Exception e){throw new AssertionError(e);}}
    private static void await(BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(20);
        assertTrue(condition.getAsBoolean(),"Timed out awaiting asynchronous state");
    }
}
