package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.ProductRow;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppTaskExecutor;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OzonRegistrationPaneTest {
    @TempDir Path directory;
    private OzonRegistrationPane pane;
    private final java.util.concurrent.atomic.AtomicInteger gtinOpens=new java.util.concurrent.atomic.AtomicInteger();
    @BeforeAll static void toolkit() throws Exception {
        try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}
    }
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Ozon fixture','OZON','fixture','synthetic'),(2,'Foreign fixture','OZON','other','other')");
            c.setAutoCommit(false);
            try(var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(?,?,?,?,?,'2026-10-14T00:00:00Z')")) {
                for(int n=1;n<=1000;n++){p.setInt(1,1);p.setString(2,Integer.toString(n));p.setString(3,"offer-"+n);p.setString(4,Integer.toString(10000+n));p.setString(5,"Native "+n);p.addBatch();}p.executeBatch();
            }c.commit();
            s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(2,'1','offer-1','10001','Foreign','2026-10-14T00:00:00Z')");
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(new Shop(1,"Ozon fixture",Marketplace.OZON,"fixture","synthetic"));
        if(System.getProperty("vncode.item4.capture.dir")!=null)fx(()->{com.vncode.app.shared.I18nService.getInstance().setLanguage(com.vncode.app.shared.AppLanguage.VI);return null;});
        pane=fx(()->new OzonRegistrationPane(new Shop(1,"Ozon fixture",Marketplace.OZON,"fixture","synthetic"),gtinOpens::incrementAndGet));
        awaitRows(300);
    }
    @AfterEach void cleanup()throws Exception {
        fx(()->{pane.dispose();return null;});
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(AppTaskExecutor.hasRunningTasks()&&System.nanoTime()<end)Thread.sleep(10);
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }
    @Test void aFailedNewReadRetainsLastGoodRowsAsHistoryAndCannotEnableMutationActions()throws Exception {
        var before=fx(()->table().getItems().stream().map(ProductRow::key).toList());
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("UPDATE sync_runs SET state='PARTIAL',error_code='SOURCE_CATALOG_PARTIAL' WHERE kind='registration_sources'");}
        fx(()->{button("refresh").fire();return null;});awaitIdle();fx(()->{assertEquals(before,table().getItems().stream().map(ProductRow::key).toList());for(String action:new String[]{"gtin","sign","register","all"})assertTrue(button(action).isDisabled(),action);assertFalse(button("sync").isDisabled());assertFalse(button("documents").isDisabled());var status=OzonRegistrationPane.class.getDeclaredField("status");status.setAccessible(true);assertTrue(((javafx.scene.control.Label)status.get(pane)).getText().contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.latest_read_unknown")));return null;});
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(new Shop(1,"Ozon fixture",Marketplace.OZON,"fixture","synthetic"));fx(()->{button("refresh").fire();return null;});awaitRows(300);fx(()->{assertFalse(button("gtin").isDisabled());return null;});
    }
    @Test void aChangedOwnerCannotSeeRetainedRowsOrUseTheirProof()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated-fixture' WHERE id=1");}
        fx(()->{button("refresh").fire();return null;});awaitIdle();fx(()->{assertTrue(table().getItems().isEmpty());assertTrue(button("gtin").isDisabled());assertTrue(button("register").isDisabled());return null;});
    }
    @Test void receiptMembershipExcludesCachedProductNotReturnedByTheCurrentMarketplaceRead()throws Exception {
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(1,'2000','old','20000','Old product','old')");}
        fx(()->{button("refresh").fire();return null;});awaitRows(300);
        fx(()->{for(String name:new String[]{"all","gtin"})assertFalse(button(name).isDisabled());assertTrue(table().getItems().stream().noneMatch(row->row.key().cardId().equals("2000")));return null;});
    }
    private void awaitIdle()throws Exception{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();}
    @Test void openingNativeOzonRegistrationPagesLocallyWithoutAllocatingOrSigningAnything()throws Exception {
        fx(()->{var header=(javafx.scene.layout.HBox)pane.getChildren().getFirst();var label=(javafx.scene.control.Label)header.getChildren().getFirst();
            assertEquals(java.util.List.of(button("documents"),button("sign"),button("gtin"),button("sync")),header.getChildren().stream().filter(n->n instanceof Button).toList());assertEquals(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.title_ozon")+" · Ozon fixture",label.getText());assertTrue(table().getColumns().getLast().getMinWidth()>=130);for(var row:table().getItems()){assertEquals(1,row.key().shopId());assertEquals(Marketplace.OZON,row.key().marketplace());assertEquals("offer-"+row.key().cardId(),row.key().variantId());}RegistrationScreenshot.capture(pane,"ozon-registration");return null;});
        try(var c=Database.getConnection();var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT COUNT(*) FROM ozon_card_registrations")){assertEquals(0,r.getInt(1));}
            try(var r=s.executeQuery("SELECT COUNT(*) FROM znack_card_registrations")){assertEquals(0,r.getInt(1));}
        }
    }
    @Test void loadMoreUsesTheNativeCursorAndReachesAllThousandRowsWithoutDuplicateKeys()throws Exception {
        for(int count:new int[]{600,900,1000}){fx(()->{button("more").fire();return null;});awaitRows(count);}
        fx(()->{assertEquals(1000,table().getItems().stream().map(ProductRow::key).distinct().count());assertFalse(button("more").isVisible());return null;});
    }
    @Test void notCreatedSelectAllFreezesEveryMatchingCardBeyondTheVisiblePage()throws Exception {
        fx(()->{var filter=(javafx.scene.control.ComboBox<String>)pane.lookup("#ozonRegistrationStatusFilter");assertTrue(filter.getItems().containsAll(java.util.List.of("NOT_CREATED","IN_PROGRESS","COMPLETED")));filter.setValue("NOT_CREATED");return null;});awaitRows(300);
        fx(()->{button("all").fire();return null;});awaitIdle();
        fx(()->{var selected=OzonRegistrationPane.class.getDeclaredMethod("selected");selected.setAccessible(true);assertEquals(1000,((java.util.List<?>)selected.invoke(pane)).size());assertEquals(300,table().getItems().size());return null;});
    }
    @Test void knownUnsentGtinStaysNotCreatedAndAcceptedFeedMovesToProcessing(){
        var key=new com.vncode.app.features.products.ProductKey(1,Marketplace.OZON,"1","offer-1");
        var unsent=new com.vncode.app.integration.znack.registration.OzonCardRegistrationRepository.Row(key,"10001",com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status.READY_TO_SUBMIT,"00000000000017","","",null,"{}","{}","owner",null,"","","");
        var nativeView=new com.vncode.app.features.sync.RegistrationCardIndex.View("status.ready_to_submit",unsent.gtin(),"",true,false,false);
        assertTrue(OzonRegistrationPane.matchesFilter("NOT_CREATED",unsent,nativeView));assertFalse(OzonRegistrationPane.matchesFilter("IN_PROGRESS",unsent,nativeView));
        var submitted=new com.vncode.app.integration.znack.registration.OzonCardRegistrationRepository.Row(key,"10001",com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status.FEED_SUBMITTED,unsent.gtin(),"","accepted-feed",null,"{}","{}","owner",null,"","","");
        assertFalse(OzonRegistrationPane.matchesFilter("NOT_CREATED",submitted,nativeView));assertTrue(OzonRegistrationPane.matchesFilter("IN_PROGRESS",submitted,nativeView));
    }
    @Test void aPublishedCandidateWithVariantConflictBelongsOnlyToMatchingReview(){
        var candidate=new com.vncode.app.features.sync.RegistrationCardIndex.View("source.ambiguous","00000000000017","GTIN_VARIANT_CONFLICT",true,false,true);
        assertTrue(OzonRegistrationPane.matchesFilter("MATCH_REVIEW",null,candidate));
        for(String filter:java.util.List.of("ERROR","NOT_CREATED","IN_PROGRESS","COMPLETED"))assertFalse(OzonRegistrationPane.matchesFilter(filter,null,candidate),filter);
    }
    @Test void syncedCatalogDraftAppearsInProcessingWithoutInventingANativeFeed(){
        var draft=new com.vncode.app.features.sync.RegistrationCardIndex.View("source.draft","00000000000017","CATALOG_CARD_DRAFT",true,false,true);
        assertTrue(OzonRegistrationPane.matchesFilter("IN_PROGRESS",null,draft));
        assertFalse(OzonRegistrationPane.matchesFilter("NOT_CREATED",null,draft));
        assertFalse(OzonRegistrationPane.matchesFilter("ERROR",null,draft));
    }
    @Test void unchangedPendingCountersStillRefreshNewCatalogDraftsInProcessing()throws Exception{
        var progress=new com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.Progress("pending-live",100,0,0,100,"Next","52","CATALOG_REQUEST_ACCEPTED_CARD_PENDING",com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.State.WAITING_REMOTE,"","");
        fx(()->{stopBatchPolling();((javafx.scene.control.ComboBox<String>)pane.lookup("#ozonRegistrationStatusFilter")).setValue("IN_PROGRESS");return null;});awaitIdle();
        fx(()->{assertEquals("IN_PROGRESS",((javafx.scene.control.ComboBox<?>)pane.lookup("#ozonRegistrationStatusFilter")).getValue());assertTrue(table().getItems().isEmpty(),"The processing filter must finish its local read before adding a new Catalog draft");renderProgress(progress);stopBatchPolling();return null;});
        var owner=new Shop(1,"Ozon fixture",Marketplace.OZON,"fixture","synthetic");
        var details=com.vncode.app.integration.znack.registration.CatalogProductDataParser.parse(com.google.gson.JsonParser.parseString("{\"good_status\":\"draft\",\"good_detailed_status\":[\"draft\"]}").getAsJsonObject());
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,Marketplace.OZON,java.util.List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("00000000000017","offer-1","","",true,false,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(owner),java.time.Instant.now(),details)));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO ozon_product_barcodes(shop_id,product_id,barcode) VALUES(1,'1','00000000000017')");}
        fx(()->{
            var at=OzonRegistrationPane.class.getDeclaredField("lastBatchRowsRefreshNanos");at.setAccessible(true);at.setLong(pane,0);
            renderProgress(progress);var timer=OzonRegistrationPane.class.getDeclaredField("batchRowsRefresh");timer.setAccessible(true);
            assertEquals(javafx.animation.Animation.Status.RUNNING,((javafx.animation.PauseTransition)timer.get(pane)).getStatus(),"An unchanged active batch must still observe new Catalog card state");
            ((javafx.animation.PauseTransition)timer.get(pane)).stop();var refresh=OzonRegistrationPane.class.getDeclaredMethod("refreshBatchRows");refresh.setAccessible(true);refresh.invoke(pane);
            assertFalse(((javafx.scene.control.ComboBox<?>)pane.lookup("#ozonRegistrationStatusFilter")).isDisabled());assertFalse(button("all").isDisabled());return null;
        });awaitIdle();
        fx(()->{stopBatchPolling();assertEquals(java.util.List.of("1"),table().getItems().stream().map(row->row.key().cardId()).toList());
            var checkpoints=OzonRegistrationPane.class.getDeclaredField("states");checkpoints.setAccessible(true);assertTrue(((java.util.Map<?,?>)checkpoints.get(pane)).isEmpty(),"Presentation must not invent a native feed or checkpoint");
            var pill=(javafx.scene.layout.HBox)pane.lookup("#ozonRegistrationBatchStatus");assertTrue(pill.getChildren().stream().anyMatch(javafx.scene.control.ProgressIndicator.class::isInstance));
            String text=((javafx.scene.control.Label)pane.lookup("#ozonRegistrationBatchProgress")).getText();assertFalse(text.contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.batch_waiting_connection")));return null;});
    }
    @Test void backgroundRefreshPreservesTheEntireFrozenNotCreatedSelection()throws Exception{
        fx(()->{stopBatchPolling();((javafx.scene.control.ComboBox<String>)pane.lookup("#ozonRegistrationStatusFilter")).setValue("NOT_CREATED");return null;});awaitRows(300);
        fx(()->{button("all").fire();return null;});awaitIdle();
        fx(()->{var refresh=OzonRegistrationPane.class.getDeclaredMethod("refreshBatchRows");refresh.setAccessible(true);refresh.invoke(pane);
            assertFalse(((javafx.scene.control.ComboBox<?>)pane.lookup("#ozonRegistrationStatusFilter")).isDisabled());assertFalse(button("all").isDisabled());assertEquals(300,table().getItems().size(),"The current page stays visible while refreshing");return null;});awaitIdle();
        fx(()->{var selected=OzonRegistrationPane.class.getDeclaredMethod("selected");selected.setAccessible(true);assertEquals(1000,((java.util.List<?>)selected.invoke(pane)).size());assertEquals(300,table().getSelectionModel().getSelectedItems().size());return null;});
    }
    @Test void processingRefreshKeepsTheRowsAlreadyLoadedByScrolling()throws Exception{
        var owner=new Shop(1,"Ozon fixture",Marketplace.OZON,"fixture","synthetic");
        var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(1,owner.getName())).getSettings();
        String scope=new com.google.gson.Gson().toJson(com.vncode.app.integration.znack.registration.ZnackRegistrationScope.fromSettings(settings));
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,feed_id,scope_json,owner,created_at,updated_at) SELECT shop_id,product_id,offer_id,sku,'FEED_SUBMITTED','feed-'||product_id,?,?,'fixture','fixture' FROM ozon_products WHERE shop_id=1")){
            p.setString(1,scope);p.setString(2,com.vncode.app.integration.listing.ListingIdentity.fingerprint(owner));p.executeUpdate();
        }
        fx(()->{stopBatchPolling();((javafx.scene.control.ComboBox<String>)pane.lookup("#ozonRegistrationStatusFilter")).setValue("IN_PROGRESS");return null;});awaitRows(300);
        fx(()->{button("more").fire();return null;});awaitRows(600);
        fx(()->{button("all").fire();return null;});awaitIdle();
        fx(()->{var refresh=OzonRegistrationPane.class.getDeclaredMethod("refreshBatchRows");refresh.setAccessible(true);refresh.invoke(pane);assertEquals(600,table().getItems().size());return null;});awaitIdle();
        fx(()->{assertEquals(600,table().getItems().size());assertEquals(600,table().getItems().stream().map(ProductRow::key).distinct().count());var selected=OzonRegistrationPane.class.getDeclaredMethod("selected");selected.setAccessible(true);assertEquals(1000,((java.util.List<?>)selected.invoke(pane)).size());assertFalse(((javafx.scene.control.ComboBox<?>)pane.lookup("#ozonRegistrationStatusFilter")).isDisabled());return null;});
    }
    @Test void gtinActionStartsTheInjectedBackgroundActionWithoutOpeningTheReportBrowser()throws Exception {
        fx(()->{var action=button("gtin");assertEquals(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.update_ozon"),action.getText());action.fire();assertEquals(1,gtinOpens.get());return null;});
    }
    @Test void changedOzonTnvedNewCardActionCancelsCleanlyAndPassesExplicitNewCardIntent()throws Exception{
        var owner=new Shop(1,"Ozon fixture",Marketplace.OZON,"fixture","synthetic");String gtin="00000000000017";var source=new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(gtin,"offer-1","Blue","M",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(owner),java.time.Instant.now());new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,Marketplace.OZON,java.util.List.of(source));var accepted=new java.util.concurrent.atomic.AtomicBoolean();var calls=new java.util.concurrent.atomic.AtomicInteger();
        fx(()->{stopBatchPolling();var original=table().getItems().stream().filter(row->row.key().cardId().equals("1")).findFirst().orElseThrow();var row=new ProductRow(original.key(),original.title(),original.vendorArticle(),original.marketArticle(),original.category(),original.brand(),"Blue","M",java.util.List.of(gtin),gtin,"","","6201930000",com.vncode.app.features.products.DocumentState.MISSING,"",true);
            var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(1,owner.getName())).getSettings();String scope=new com.google.gson.Gson().toJson(com.vncode.app.integration.znack.registration.ZnackRegistrationScope.fromSettings(settings));var nativeRow=new com.vncode.app.integration.znack.registration.OzonCardRegistrationRepository.Row(row.key(),"10001",com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status.PUBLISHED,gtin,gtin,"accepted",1L,"{\"tnved\":\"6101930000\"}",scope,com.vncode.app.integration.listing.ListingIdentity.fingerprint(owner),new byte[0],"","","");var states=OzonRegistrationPane.class.getDeclaredField("states");states.setAccessible(true);((java.util.Map<ProductKey,com.vncode.app.integration.znack.registration.OzonCardRegistrationRepository.Row>)states.get(pane)).put(row.key(),nativeRow);var index=OzonRegistrationPane.class.getDeclaredField("cardIndex");index.setAccessible(true);index.set(pane,new com.vncode.app.features.sync.RegistrationCardIndex(owner));
            var confirmation=OzonRegistrationPane.class.getDeclaredField("replacementConfirmation");confirmation.setAccessible(true);confirmation.set(pane,(java.util.function.BiPredicate<ProductKey,Boolean>)(key,newCard)->{assertTrue(newCard);return accepted.get();});var preparation=OzonRegistrationPane.class.getDeclaredField("preparationStarter");preparation.setAccessible(true);preparation.set(pane,(java.util.function.BiConsumer<ProductKey,Boolean>)(key,newCard)->{assertEquals(row.key(),key);assertTrue(newCard,"An explicit new-card button cannot dispatch the ordinary register path");calls.incrementAndGet();});
            var column=(javafx.scene.control.TableColumn<ProductRow,ProductRow>)table().getColumns().getLast();var cell=column.getCellFactory().call(column);var update=cell.getClass().getDeclaredMethod("updateItem",ProductRow.class,boolean.class);update.setAccessible(true);update.invoke(cell,row,false);var menu=(javafx.scene.control.MenuButton)cell.getGraphic();var create=menu.getItems().getFirst();assertEquals(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.create_new_card"),create.getText());assertFalse(create.isDisable());create.fire();assertEquals(0,calls.get());accepted.set(true);create.fire();assertEquals(1,calls.get());return null;});
        try(var c=Database.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT count(*) FROM ozon_card_registrations")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
    }
    @Test void waitingAndRunningProgressShowTheLocalizedSpecificReason()throws Exception{
        fx(()->{
            stopBatchPolling();
            var render=OzonRegistrationPane.class.getDeclaredMethod("renderBatchProgress",com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.Progress.class);render.setAccessible(true);
            for(var state:java.util.List.of(com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.State.WAITING,com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.State.RUNNING)){
                String code=state==com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.State.WAITING?"NETWORK_TIMEOUT":"REGISTRATION_BUSY";
                render.invoke(pane,new com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.Progress("detail-progress",100,2,1,97,"Next article","52",code,state,"Accepted article","XL"));
                String text=((javafx.scene.control.Label)pane.lookup("#ozonRegistrationBatchProgress")).getText();
                assertTrue(text.contains("2/100"));assertTrue(text.contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.reason."+code)));
                var pill=(javafx.scene.layout.HBox)pane.lookup("#ozonRegistrationBatchStatus");
                assertTrue(pill.getChildren().stream().filter(javafx.scene.control.Label.class::isInstance).map(javafx.scene.control.Label.class::cast).anyMatch(label->label.getText().contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.reason."+code))));
            }
            render.invoke(pane,new com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.Progress("detail-progress",100,2,1,97,"","","api_key=sensitive-detail feed_id=private-feed",com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.State.WAITING,"",""));
            String text=((javafx.scene.control.Label)pane.lookup("#ozonRegistrationBatchProgress")).getText();assertFalse(text.contains("sensitive-detail"));assertFalse(text.contains("private-feed"));
            assertFalse(((javafx.scene.control.ComboBox<?>)pane.lookup("#ozonRegistrationStatusFilter")).isDisabled());return null;
        });
    }
    @Test void progressReadFailureIsVisibleSafeAndRecoversWithTheSameCounters()throws Exception{
        var failRead=new java.util.concurrent.atomic.AtomicBoolean(true);
        var progress=new com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.Progress("read-progress",100,2,1,97,"Next article","52","REGISTRATION_BUSY",com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.State.RUNNING,"Accepted article","XL");
        fx(()->{
            var reader=OzonRegistrationPane.class.getDeclaredField("batchReader");reader.setAccessible(true);
            reader.set(pane,(java.util.function.Function<Shop,com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.Progress>)owner->{if(failRead.get())throw new IllegalStateException("api_key=sensitive-progress-secret feed_id=private-feed");return progress;});
            var render=OzonRegistrationPane.class.getDeclaredMethod("renderBatchProgress",progress.getClass());render.setAccessible(true);render.invoke(pane,progress);
            pollBatchProgress();return null;
        });awaitIdle();
        var errorPill=fx(()->{
            stopBatchPolling();String text=((javafx.scene.control.Label)pane.lookup("#ozonRegistrationBatchProgress")).getText();
            assertTrue(text.contains("2/100"));assertTrue(text.contains("SYNC_INTERNAL"));assertFalse(text.contains("sensitive-progress-secret"));assertFalse(text.contains("private-feed"));
            assertFalse(text.contains("api_key"));assertFalse(text.contains("feed_id"));
            var state=OzonRegistrationPane.class.getDeclaredField("batchState");state.setAccessible(true);assertSame(progress,state.get(pane));
            var pill=(javafx.scene.layout.HBox)pane.lookup("#ozonRegistrationBatchStatus");assertNotNull(pill);assertTrue(pill.getChildren().stream().noneMatch(javafx.scene.control.ProgressIndicator.class::isInstance));
            assertFalse(((javafx.scene.control.ComboBox<?>)pane.lookup("#ozonRegistrationStatusFilter")).isDisabled());return pill;
        });
        fx(()->{pollBatchProgress();return null;});awaitIdle();fx(()->{stopBatchPolling();assertSame(errorPill,pane.lookup("#ozonRegistrationBatchStatus"));return null;});
        failRead.set(false);fx(()->{pollBatchProgress();return null;});awaitIdle();
        fx(()->{
            stopBatchPolling();String text=((javafx.scene.control.Label)pane.lookup("#ozonRegistrationBatchProgress")).getText();assertTrue(text.contains("2/100"));assertFalse(text.contains("SYNC_INTERNAL"));
            var pill=(javafx.scene.layout.HBox)pane.lookup("#ozonRegistrationBatchStatus");assertNotSame(errorPill,pill);assertTrue(pill.getChildren().stream().anyMatch(javafx.scene.control.ProgressIndicator.class::isInstance));return null;
        });
    }
    @Test void ozonDurableQueueCanPauseResumeAndCancelPendingWithAnExplicitConfirmation()throws Exception{
        var calls=new java.util.concurrent.CopyOnWriteArrayList<String>();var current=new java.util.concurrent.atomic.AtomicReference<>(new com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.Progress("controls",10,2,0,8,"Next","M","",com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.State.RUNNING,"Created","XL"));
        fx(()->{stopBatchPolling();var reader=OzonRegistrationPane.class.getDeclaredField("batchReader");reader.setAccessible(true);reader.set(pane,(java.util.function.Function<Shop,com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.Progress>)owner->current.get());var control=OzonRegistrationPane.class.getDeclaredField("batchControl");control.setAccessible(true);control.set(pane,(java.util.function.BiFunction<Shop,String,Boolean>)(owner,action)->{assertFalse(Platform.isFxApplicationThread());calls.add(action);var before=current.get();var state=com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.State.valueOf(switch(action){case "PAUSE"->"PAUSED";case "RESUME"->"RUNNING";default->"CANCELLED";});current.set(new com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.Progress(before.runId(),before.total(),before.submitted(),before.failed(),before.remaining(),"","","",state,before.lastSubmittedArticle(),before.lastSubmittedSize()));return true;});renderProgress(current.get());stopBatchPolling();assertFalse(batchMenu("ozonZnackPauseBatch").isDisable());batchMenu("ozonZnackPauseBatch").fire();return null;});awaitIdle();
        fx(()->{renderProgress(current.get());stopBatchPolling();assertEquals(java.util.List.of("PAUSE"),calls);assertTrue(batchMenu("ozonZnackPauseBatch").isDisable());assertFalse(batchMenu("ozonZnackResumeBatch").isDisable());assertFalse(((javafx.scene.control.ComboBox<?>)pane.lookup("#ozonRegistrationStatusFilter")).isDisabled());var pill=(javafx.scene.layout.HBox)pane.lookup("#ozonRegistrationBatchStatus");assertTrue(pill.getChildren().stream().noneMatch(javafx.scene.control.ProgressIndicator.class::isInstance));batchMenu("ozonZnackResumeBatch").fire();return null;});awaitIdle();
        fx(()->{renderProgress(current.get());stopBatchPolling();assertEquals(java.util.List.of("PAUSE","RESUME"),calls);var confirmation=OzonRegistrationPane.class.getDeclaredField("cancelPendingConfirmation");confirmation.setAccessible(true);confirmation.set(pane,(java.util.function.BooleanSupplier)()->false);batchMenu("ozonZnackCancelPendingBatch").fire();assertEquals(2,calls.size());confirmation.set(pane,(java.util.function.BooleanSupplier)()->true);batchMenu("ozonZnackCancelPendingBatch").fire();return null;});awaitIdle();
        fx(()->{renderProgress(current.get());stopBatchPolling();assertEquals(java.util.List.of("PAUSE","RESUME","CANCEL"),calls);assertTrue(batchMenu("ozonZnackPauseBatch").isDisable());assertTrue(batchMenu("ozonZnackResumeBatch").isDisable());assertTrue(batchMenu("ozonZnackCancelPendingBatch").isDisable());assertFalse(button("report").isDisabled());assertTrue(((javafx.scene.control.Label)pane.lookup("#ozonRegistrationBatchProgress")).getText().contains("2/10"));return null;});
    }
    private javafx.scene.control.MenuItem batchMenu(String id){var header=(javafx.scene.layout.HBox)pane.getChildren().getFirst();return ((javafx.scene.control.Label)header.getChildren().getFirst()).getContextMenu().getItems().stream().filter(item->id.equals(item.getId())).findFirst().orElseThrow();}
    @Test void categoryFilterUsesShopCategoriesForAllMatchingSelectionAndSurvivesSoftRefresh()throws Exception{
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("UPDATE ozon_products SET category=CASE WHEN CAST(product_id AS INTEGER)<=500 THEN 'Category A' ELSE 'Category B' END WHERE shop_id=1");}
        fx(()->{button("refresh").fire();return null;});awaitIdle();
        fx(()->{var menu=(javafx.scene.control.MenuButton)pane.lookup("#ozonRegistrationCategoryFilter");assertFalse(menu.isDisabled());var category=menu.getItems().stream().filter(javafx.scene.control.CustomMenuItem.class::isInstance).map(javafx.scene.control.CustomMenuItem.class::cast).map(javafx.scene.control.CustomMenuItem::getContent).filter(javafx.scene.control.CheckBox.class::isInstance).map(javafx.scene.control.CheckBox.class::cast).filter(box->box.getText().equals("Category A")).findFirst().orElseThrow();category.setSelected(true);return null;});awaitIdle();
        fx(()->{((javafx.scene.control.ComboBox<String>)pane.lookup("#ozonRegistrationStatusFilter")).setValue("NOT_CREATED");return null;});awaitIdle();
        fx(()->{button("all").fire();return null;});awaitIdle();
        fx(()->{var selected=OzonRegistrationPane.class.getDeclaredMethod("selected");selected.setAccessible(true);var keys=(java.util.List<com.vncode.app.features.products.ProductKey>)selected.invoke(pane);assertEquals(500,keys.size());assertTrue(keys.stream().allMatch(key->Integer.parseInt(key.cardId())<=500));var refresh=OzonRegistrationPane.class.getDeclaredMethod("refreshBatchRows");refresh.setAccessible(true);refresh.invoke(pane);return null;});awaitIdle();
        fx(()->{assertTrue(((javafx.scene.control.MenuButton)pane.lookup("#ozonRegistrationCategoryFilter")).getText().endsWith("(1)"));assertEquals("NOT_CREATED",((javafx.scene.control.ComboBox<?>)pane.lookup("#ozonRegistrationStatusFilter")).getValue());assertTrue(table().getItems().stream().allMatch(row->Integer.parseInt(row.key().cardId())<=500));return null;});
    }
    @Test void unavailableCountryModelShowsCopyableDiagnosticsWithoutStartingAnOzonBatch()throws Exception{
        var preparing=OzonRegistrationPane.class.getDeclaredField("selectionPreparing");preparing.setAccessible(true);var registerButton=fx(()->button("register"));
        fx(()->{var reader=OzonRegistrationPane.class.getDeclaredField("preflightReader");reader.setAccessible(true);reader.set(pane,(java.util.function.BiFunction<Shop,java.util.List<com.vncode.app.features.products.ProductKey>,RegistrationBatchPreflight.Review>)(owner,keys)->{assertFalse(Platform.isFxApplicationThread());assertTrue(Thread.currentThread().getName().startsWith("vncode-catalog-read-"));throw new IllegalStateException("CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE",new java.net.SocketTimeoutException("fixture dictionary timeout"));});table().getSelectionModel().select(0);return null;});
        com.vncode.app.testsupport.FxDialogDriver.choose(registerButton::fire,dialog->dialog.lookup(".text-area") instanceof javafx.scene.control.TextArea,dialog->{
            String diagnostics=((javafx.scene.control.TextArea)dialog.lookup(".text-area")).getText();assertTrue(diagnostics.contains("CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE"));assertTrue(diagnostics.contains("SocketTimeoutException"));assertTrue(dialog.getButtonTypes().stream().anyMatch(type->type.getButtonData()==javafx.scene.control.ButtonBar.ButtonData.OTHER));try{assertFalse(preparing.getBoolean(pane));}catch(IllegalAccessException error){throw new AssertionError(error);}assertFalse(registerButton.isDisabled());
            return dialog.getButtonTypes().stream().filter(type->type.getButtonData()==javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
        });awaitIdle();
        try(var c=Database.getConnection();var statement=c.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM ozon_registration_batches")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
        try(var c=Database.getConnection();var statement=c.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM ozon_card_registrations")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
    }
    @Test void disposingOzonRegistrationCancelsThePendingCountryReadAndCannotStartTheBatch()throws Exception{
        var started=new CountDownLatch(1);var exited=new CountDownLatch(1);var release=new CountDownLatch(1);var task=new java.util.concurrent.atomic.AtomicReference<javafx.concurrent.Task<?>>();
        try{
            fx(()->{var reader=OzonRegistrationPane.class.getDeclaredField("preflightReader");reader.setAccessible(true);reader.set(pane,(java.util.function.BiFunction<Shop,java.util.List<com.vncode.app.features.products.ProductKey>,RegistrationBatchPreflight.Review>)(owner,keys)->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}finally{exited.countDown();}return new RegistrationBatchPreflight.Review(java.util.List.of());});table().getSelectionModel().select(0);button("register").fire();return null;});assertTrue(started.await(5,TimeUnit.SECONDS));
            fx(()->{var active=OzonRegistrationPane.class.getDeclaredField("registrationPreflightTask");active.setAccessible(true);task.set((javafx.concurrent.Task<?>)active.get(pane));pane.dispose();return null;});assertTrue(exited.await(5,TimeUnit.SECONDS));awaitIdle();assertTrue(task.get().isCancelled());
            try(var c=Database.getConnection();var statement=c.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM ozon_registration_batches")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
        }finally{release.countDown();}
    }
    @Test void anExistingOzonDraftIsReviewableButCancelCannotStartARegistration()throws Exception {
        var owner=new Shop(1,"Ozon fixture",Marketplace.OZON,"fixture","synthetic");
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("UPDATE ozon_products SET article='offer-1',color='Blue',size='M',manufacturer_size='M' WHERE shop_id=1 AND product_id='1'");}
        var details=new com.vncode.app.features.catalogsync.CatalogProductData(java.util.Map.of("@catalog_id",java.util.List.of("901"),"@catalog_status",java.util.List.of("draft"),"@catalog_detailed_status",java.util.List.of("draft"),"@catalog_signed",java.util.List.of("false")),java.util.List.of(),java.util.List.of());
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,Marketplace.OZON,java.util.List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("00000000000017","offer-1","Blue","M",true,false,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(owner),java.time.Instant.now(),details)));
        fx(()->{button("refresh").fire();return null;});awaitIdle();
        fx(()->{var reader=OzonRegistrationPane.class.getDeclaredField("preflightReader");reader.setAccessible(true);reader.set(pane,(java.util.function.BiFunction<Shop,java.util.List<ProductKey>,RegistrationBatchPreflight.Review>)(shop,keys)->{
                assertFalse(Platform.isFxApplicationThread());assertEquals(java.util.List.of(new ProductKey(1,Marketplace.OZON,"1","offer-1")),keys);return ExistingDraftReviewTest.completeReview(shop,keys);});
            table().getSelectionModel().select(table().getItems().stream().filter(row->row.key().cardId().equals("1")).findFirst().orElseThrow());return null;});
        var register=fx(()->button("register"));
        com.vncode.app.testsupport.FxDialogDriver.choose(register::fire,dialog->"registrationMissingFieldsDialog".equals(dialog.getId()),dialog->{
            var content=(javafx.scene.layout.VBox)dialog.getContent();var review=content.getChildren().stream().filter(TableView.class::isInstance).map(TableView.class::cast).findFirst().orElseThrow();assertEquals(2,review.getItems().size());
            return dialog.getButtonTypes().stream().filter(type->type.getButtonData()==javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
        });awaitIdle();
        try(var c=Database.getConnection();var statement=c.createStatement()){
            for(String name:java.util.List.of("ozon_registration_batches","ozon_card_registrations"))try(var rows=statement.executeQuery("SELECT count(*) FROM "+name)){assertTrue(rows.next());assertEquals(0,rows.getInt(1),name);}
        }
    }
    @Test void separateOzonBarcodeAndGtinProofColumnsKeepUnknownAndConfirmedCodesDistinct()throws Exception{
        var owner=new Shop(1,"Ozon fixture",Marketplace.OZON,"fixture","synthetic");String actual="00000000000017";var checked=java.time.Instant.now().minusSeconds(120);
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("UPDATE ozon_products SET color='Blue',size='M',manufacturer_size='M' WHERE shop_id=1 AND product_id='1'");statement.execute("INSERT INTO ozon_product_barcodes(shop_id,product_id,barcode) VALUES(1,'1','00000000000017'),(1,'2','4006381333931')");}
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,Marketplace.OZON,java.util.List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(actual,"offer-1","Blue","M",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(owner),checked)));
        fx(()->{button("refresh").fire();return null;});awaitIdle();
        fx(()->{var raw=(javafx.scene.control.TableColumn<ProductRow,String>)table().getColumns().stream().filter(column->"ozonRawBarcodes".equals(column.getId())).findFirst().orElseThrow();var verified=(javafx.scene.control.TableColumn<ProductRow,String>)table().getColumns().stream().filter(column->"ozonVerifiedGtin".equals(column.getId())).findFirst().orElseThrow();assertEquals(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.barcode_ozon"),raw.getText());assertEquals(table().getColumns().indexOf(raw)+1,table().getColumns().indexOf(verified));
            var observed=table().getItems().stream().filter(row->row.key().cardId().equals("1")).findFirst().orElseThrow();var unknown=table().getItems().stream().filter(row->row.key().cardId().equals("2")).findFirst().orElseThrow();assertEquals(actual,raw.getCellData(observed));assertTrue(verified.getCellData(observed).startsWith(actual+"\n"));assertTrue(verified.getCellData(observed).contains(java.text.MessageFormat.format(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.gtin_verified_at"),java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(java.time.ZoneId.systemDefault()).format(checked))));assertEquals("4006381333931",raw.getCellData(unknown));assertTrue(verified.getCellData(unknown).contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.gtin_unverified")));assertFalse(verified.getCellData(unknown).contains("4006381333931"));
            var index=OzonRegistrationPane.class.getDeclaredField("cardIndex");index.setAccessible(true);var saved=index.get(pane);index.set(pane,null);assertTrue(verified.getCellData(observed).contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.gtin_unverified")));assertEquals(actual,raw.getCellData(observed));index.set(pane,saved);return null;});
        try(var c=Database.getConnection();var statement=c.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM ozon_card_registrations")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
    }
    @Test void originalAndRussianSizesAndAllThreeStatesRemainIndependent()throws Exception {
        try(var c=Database.getConnection();var statement=c.createStatement()){
            statement.execute("UPDATE ozon_products SET size='L',manufacturer_size='L',russian_size='52' WHERE shop_id=1 AND product_id='1'");
            statement.execute("UPDATE ozon_products SET size='XL',manufacturer_size='XL' WHERE shop_id=1 AND product_id='2'");
        }
        fx(()->{button("refresh").fire();return null;});awaitIdle();
        fx(()->{
            var columns=new java.util.HashMap<String,javafx.scene.control.TableColumn<ProductRow,String>>();
            for(var column:table().getColumns())if(column.getId()!=null)columns.put(column.getId(),(javafx.scene.control.TableColumn<ProductRow,String>)column);
            var explicit=table().getItems().stream().filter(row->row.key().cardId().equals("1")).findFirst().orElseThrow();
            var missing=table().getItems().stream().filter(row->row.key().cardId().equals("2")).findFirst().orElseThrow();
            assertEquals("L",columns.get("ozonOriginalSize").getCellData(explicit));assertEquals("52",columns.get("ozonRussianSize").getCellData(explicit));
            assertEquals("XL",columns.get("ozonOriginalSize").getCellData(missing));
            assertEquals(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.table_size_unknown"),columns.get("ozonRussianSize").getCellData(missing));
            for(String id:java.util.List.of("ozonRegistrationStatus","ozonModerationStatus","ozonMarketplaceSyncStatus"))assertNotNull(columns.get(id),id);
            assertTrue(columns.get("ozonModerationStatus").getCellData(explicit).contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.table_catalog_unknown")));
            assertTrue(columns.get("ozonMarketplaceSyncStatus").getCellData(explicit).contains(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.gtin_unverified")));
            return null;
        });
    }
    private void renderProgress(com.vncode.app.integration.znack.registration.OzonRegistrationBatchService.Progress progress)throws Exception{var render=OzonRegistrationPane.class.getDeclaredMethod("renderBatchProgress",progress.getClass());render.setAccessible(true);render.invoke(pane,progress);}
    private void stopBatchPolling()throws Exception{var timer=OzonRegistrationPane.class.getDeclaredField("batchRefresh");timer.setAccessible(true);((javafx.animation.Timeline)timer.get(pane)).stop();var rows=OzonRegistrationPane.class.getDeclaredField("batchRowsRefresh");rows.setAccessible(true);((javafx.animation.PauseTransition)rows.get(pane)).stop();}
    private void pollBatchProgress()throws Exception{stopBatchPolling();var poll=OzonRegistrationPane.class.getDeclaredMethod("readBatchProgress");poll.setAccessible(true);poll.invoke(pane);}
    @SuppressWarnings("unchecked") private TableView<ProductRow> table(){return (TableView<ProductRow>)pane.lookup("#ozonRegistrationTable");}
    private Button button(String name)throws Exception{var field=OzonRegistrationPane.class.getDeclaredField(name);field.setAccessible(true);return (Button)field.get(pane);}
    private void awaitRows(int count)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(fx(()->table().getItems().size()==count&&!button("more").isDisabled()))return;Thread.sleep(10);}fail("Native Ozon page did not arrive");}
    private static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
}
