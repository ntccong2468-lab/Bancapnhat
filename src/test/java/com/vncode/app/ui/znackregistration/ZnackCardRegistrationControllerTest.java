package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackCardRegistrationControllerTest {
    @TempDir Path temp;
    FXMLLoader loader;
    @BeforeAll static void toolkit() throws Exception {
        try{Platform.startup(()->{});}catch(IllegalStateException alreadyStarted){}
        fx(()->{Platform.setImplicitExit(false);return null;});
    }
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture')");
            for(int id=1;id<=3;id++) {
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,"+(100+id)+",'SKU-"+id+"','Fixture','2026-10-06T00:00:00Z')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,"+id+","+(100+id)+",'XL')");
                s.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,"+(100+id)+",14177449,'Цвет','[\"Blue\"]')");
            }
            s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,status,created_at,updated_at) VALUES(1,2,102,'CHECKING','fixture','fixture')");
            s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,status,created_at,updated_at) VALUES(1,3,103,'04631993764370','PUBLISHED','fixture','fixture')");
        }
        var owner=new Shop(1,"Fixture","fixture");
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(owner);
        var scope=com.vncode.app.integration.znack.registration.ZnackRegistrationScope.fromSettings(new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(1,"Fixture")).getSettings());
        var nativeRows=new com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository();
        nativeRows.bindScope(owner,2,scope);nativeRows.bindScope(owner,3,scope);
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES,java.util.List.of(
            new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("04631993764370","SKU-3","Blue","XL",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(owner),java.time.Instant.now())));
        fx(()->{
            if(System.getProperty("vncode.item4.capture.dir")!=null)I18nService.getInstance().setLanguage(com.vncode.app.shared.AppLanguage.VI);
            loader=new FXMLLoader(ZnackCardRegistrationController.class.getResource("znack-card-registration-view.fxml"));
            loader.load();((ZnackCardRegistrationController)loader.getController()).setShop(new Shop(1,"Fixture","fixture"));return null;
        });
        awaitTasks();
    }
    @AfterEach void cleanup() throws Exception {fx(()->{((ZnackCardRegistrationController)loader.getController()).dispose();return null;});awaitTasks();com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    @Test void registrationProgressCountsAcceptedFeedsAndDoesNotLockBrowsing()throws Exception{
        fx(()->{
            var controller=(ZnackCardRegistrationController)loader.getController();
            var preparing=ZnackCardRegistrationController.class.getDeclaredField("preparing");preparing.setAccessible(true);preparing.setBoolean(controller,true);
            var actions=ZnackCardRegistrationController.class.getDeclaredMethod("updateActionState");actions.setAccessible(true);actions.invoke(controller);
            for(String id:new String[]{"searchField","categoryMenuButton","statusFilter","clearFiltersButton"})
                assertFalse(((Control)loader.getNamespace().get(id)).isDisabled(),id+" must stay available during preparation");
            preparing.setBoolean(controller,false);
            var render=ZnackCardRegistrationController.class.getDeclaredMethod("renderBatchProgress",com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.Progress.class);render.setAccessible(true);
            render.invoke(controller,new com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.Progress("durable-test",100,2,1,97,"Next article","XL","Accepted article","52","CATALOG_CARD_IN_MODERATION",com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.State.RUNNING));
            var footer=(Label)loader.getNamespace().get("batchProgressLabel");assertTrue(footer.isVisible());assertTrue(footer.getText().contains("2/100"));assertTrue(footer.getText().contains("97"));
            assertTrue(button("registerSelectedButton").isDisabled(),"Another batch cannot be started while the durable queue runs");
            assertFalse(((ComboBox<?>)loader.getNamespace().get("statusFilter")).isDisabled());
            var host=(javafx.scene.layout.StackPane)table().getParent();assertTrue(host.lookup("#registrationBackgroundPill")!=null);
            assertTrue(((javafx.scene.layout.HBox)host.lookup("#registrationBackgroundPill")).getChildren().stream().filter(Label.class::isInstance).map(Label.class::cast).anyMatch(l->l.getText().contains("Accepted article · 52")));
            return null;
        });
    }
    @Test void progressReadFailureIsVisibleSafeAndRecoversWithoutLosingKnownCounts()throws Exception{
        var controller=(ZnackCardRegistrationController)loader.getController();
        var progress=new com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.Progress("read-progress",100,2,1,97,"Next article","XL","Accepted article","52","CATALOG_CARD_IN_MODERATION",com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.State.RUNNING);
        var failRead=new java.util.concurrent.atomic.AtomicBoolean(true);
        fx(()->{
            var reader=ZnackCardRegistrationController.class.getDeclaredField("batchReader");reader.setAccessible(true);
            reader.set(controller,(java.util.function.Function<Shop,com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.Progress>)owner->{
                if(failRead.get())throw new IllegalStateException("api_key=sensitive-progress-secret feed_id=private-feed");
                return progress;
            });
            var render=ZnackCardRegistrationController.class.getDeclaredMethod("renderBatchProgress",progress.getClass());render.setAccessible(true);render.invoke(controller,progress);
            var refresh=ZnackCardRegistrationController.class.getDeclaredField("workflowRefresh");refresh.setAccessible(true);((javafx.animation.PauseTransition)refresh.get(controller)).stop();
            pollBatchProgress(controller);return null;
        });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        var errorPill=fx(()->{
            stopBatchPolling(controller);
            String text=((Label)loader.getNamespace().get("batchProgressLabel")).getText();
            assertTrue(text.contains("2/100"));assertTrue(text.contains("SYNC_INTERNAL"));
            assertTrue(text.contains(I18nService.getInstance().tr("znack.registration.batch_progress_read_unknown")));
            assertFalse(text.contains("sensitive-progress-secret"));assertFalse(text.contains("private-feed"));
            assertFalse(text.contains("api_key"));assertFalse(text.contains("feed_id"));
            var state=ZnackCardRegistrationController.class.getDeclaredField("batchProgress");state.setAccessible(true);assertSame(progress,state.get(controller));
            assertFalse(((ComboBox<?>)loader.getNamespace().get("statusFilter")).isDisabled());
            assertFalse(((TextField)loader.getNamespace().get("searchField")).isDisabled());
            var pill=(javafx.scene.layout.HBox)((javafx.scene.layout.StackPane)table().getParent()).lookup("#registrationBackgroundPill");
            assertNotNull(pill);assertTrue(pill.getChildren().stream().noneMatch(ProgressIndicator.class::isInstance));return pill;
        });
        fx(()->{pollBatchProgress(controller);return null;});
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        fx(()->{stopBatchPolling(controller);assertSame(errorPill,((javafx.scene.layout.StackPane)table().getParent()).lookup("#registrationBackgroundPill"),"A repeated read error must not reset its dismissal timer");return null;});
        failRead.set(false);
        fx(()->{pollBatchProgress(controller);return null;});
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        fx(()->{
            stopBatchPolling(controller);
            String text=((Label)loader.getNamespace().get("batchProgressLabel")).getText();
            assertTrue(text.contains("2/100"));assertFalse(text.contains("SYNC_INTERNAL"));
            var pill=(javafx.scene.layout.HBox)((javafx.scene.layout.StackPane)table().getParent()).lookup("#registrationBackgroundPill");
            assertNotSame(errorPill,pill);assertTrue(pill.getChildren().stream().anyMatch(ProgressIndicator.class::isInstance),"A successful read restores the still-running batch even when its counters did not change");return null;
        });
    }
    private static void stopBatchPolling(ZnackCardRegistrationController controller)throws Exception{
        var refresh=ZnackCardRegistrationController.class.getDeclaredField("batchRefresh");refresh.setAccessible(true);((javafx.animation.PauseTransition)refresh.get(controller)).stop();
        var rows=ZnackCardRegistrationController.class.getDeclaredField("batchRowsRefresh");rows.setAccessible(true);((javafx.animation.PauseTransition)rows.get(controller)).stop();
    }
    private static void pollBatchProgress(ZnackCardRegistrationController controller)throws Exception{
        stopBatchPolling(controller);var poll=ZnackCardRegistrationController.class.getDeclaredMethod("pollBatchProgress");poll.setAccessible(true);poll.invoke(controller);
    }
    @Test void unchangedRemotePendingCountersRefreshANewCatalogDraftInProcessing()throws Exception{
        var controller=(ZnackCardRegistrationController)loader.getController();
        var progress=new com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.Progress("pending-live",100,0,0,100,"Next","52","","","CATALOG_REQUEST_ACCEPTED_CARD_PENDING",com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.State.WAITING_REMOTE);
        fx(()->{stopBatchPolling(controller);((ComboBox<?>)loader.getNamespace().get("statusFilter")).getSelectionModel().select(2);return null;});awaitTasks();
        fx(()->{assertTrue(table().getItems().stream().noneMatch(row->row.chrtId()==1));renderProgress(controller,progress);stopBatchPolling(controller);return null;});
        var owner=new Shop(1,"Fixture","fixture");var catalog=new com.vncode.app.features.gtinsync.GtinSyncRepository();
        var cards=new java.util.ArrayList<>(catalog.catalog(1,owner.getMarketplace()));
        var details=com.vncode.app.integration.znack.registration.CatalogProductDataParser.parse(com.google.gson.JsonParser.parseString("{\"good_status\":\"draft\",\"good_detailed_status\":[\"draft\"]}").getAsJsonObject());
        cards.add(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("00000000000017","SKU-1","","XL",true,false,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(owner),java.time.Instant.now(),details));catalog.saveCatalog(1,owner.getMarketplace(),cards);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,1,'00000000000017')");}
        fx(()->{
            var at=ZnackCardRegistrationController.class.getDeclaredField("lastBatchRowsRefreshNanos");at.setAccessible(true);at.setLong(controller,0);renderProgress(controller,progress);
            var timer=ZnackCardRegistrationController.class.getDeclaredField("batchRowsRefresh");timer.setAccessible(true);
            assertEquals(javafx.animation.Animation.Status.RUNNING,((javafx.animation.PauseTransition)timer.get(controller)).getStatus());((javafx.animation.PauseTransition)timer.get(controller)).stop();
            var refresh=ZnackCardRegistrationController.class.getDeclaredMethod("refreshBatchRows");refresh.setAccessible(true);refresh.invoke(controller);
            assertFalse(((ComboBox<?>)loader.getNamespace().get("statusFilter")).isDisabled());assertFalse(((TextField)loader.getNamespace().get("searchField")).isDisabled());return null;
        });awaitTasks();
        fx(()->{stopBatchPolling(controller);var row=table().getItems().stream().filter(item->item.chrtId()==1).findFirst().orElseThrow();assertFalse(row.hasFeed());
            @SuppressWarnings("unchecked") var status=(TableColumn<Sku,String>)loader.getNamespace().get("statusColumn");assertEquals(I18nService.getInstance().tr("znack.registration.source.draft"),status.getCellData(row));
            var pill=(javafx.scene.layout.HBox)((javafx.scene.layout.StackPane)table().getParent()).lookup("#registrationBackgroundPill");assertTrue(pill.getChildren().stream().anyMatch(ProgressIndicator.class::isInstance));return null;});
    }
    @Test void backgroundRefreshPreservesSelectedNotCreatedCardsAndKeepsThePageVisible()throws Exception{
        var controller=(ZnackCardRegistrationController)loader.getController();
        fx(()->{stopBatchPolling(controller);((ComboBox<?>)loader.getNamespace().get("statusFilter")).getSelectionModel().select(1);return null;});awaitTasks();
        fx(()->{button("selectAllButton").fire();return null;});awaitTasks();
        var before=fx(()->{var selected=ZnackCardRegistrationController.class.getDeclaredMethod("selectedRows");selected.setAccessible(true);return java.util.List.copyOf((java.util.List<Sku>)selected.invoke(controller));});
        assertFalse(before.isEmpty());
        fx(()->{var refresh=ZnackCardRegistrationController.class.getDeclaredMethod("refreshBatchRows");refresh.setAccessible(true);refresh.invoke(controller);assertFalse(table().getItems().isEmpty());assertFalse(((ComboBox<?>)loader.getNamespace().get("statusFilter")).isDisabled());assertFalse(button("selectAllButton").isDisabled());return null;});awaitTasks();
        fx(()->{var selected=ZnackCardRegistrationController.class.getDeclaredMethod("selectedRows");selected.setAccessible(true);assertEquals(before.stream().map(Sku::chrtId).toList(),((java.util.List<Sku>)selected.invoke(controller)).stream().map(Sku::chrtId).toList());assertEquals(before.size(),table().getSelectionModel().getSelectedItems().size());return null;});
    }
    private static void renderProgress(ZnackCardRegistrationController controller,com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.Progress progress)throws Exception{
        var render=ZnackCardRegistrationController.class.getDeclaredMethod("renderBatchProgress",progress.getClass());render.setAccessible(true);render.invoke(controller,progress);
    }
    @Test void processingRefreshPreservesAlreadyLoadedPagesAndSelection()throws Exception{
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);
            try(var card=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,?,'bulk','Fixture','fixture')");var size=c.prepareStatement("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,?,?,'XL')")){
                for(int id=4;id<=603;id++){card.setInt(1,100+id);card.addBatch();size.setInt(1,id);size.setInt(2,100+id);size.addBatch();}card.executeBatch();size.executeBatch();
            }
            try(var s=c.createStatement()){
                s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,status,feed_id,created_at,updated_at) SELECT shop_id,chrt_id,nm_id,'FEED_SUBMITTED','feed-'||chrt_id,'fixture','fixture' FROM wb_product_sizes WHERE shop_id=1 AND chrt_id>3");
                s.execute("INSERT INTO znack_card_registration_scopes(shop_id,chrt_id,participant_inn,environment,fingerprint,owner_hash,created_at) SELECT r.shop_id,r.chrt_id,o.participant_inn,o.environment,o.fingerprint,o.owner_hash,'fixture' FROM znack_card_registrations r JOIN znack_card_registration_scopes o ON o.shop_id=r.shop_id AND o.chrt_id=2 WHERE r.shop_id=1 AND r.chrt_id>3");
            }c.commit();
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(new Shop(1,"Fixture","fixture"));
        var controller=(ZnackCardRegistrationController)loader.getController();
        fx(()->{stopBatchPolling(controller);((ComboBox<?>)loader.getNamespace().get("statusFilter")).getSelectionModel().select(2);return null;});awaitTasks();
        fx(()->{assertEquals(300,table().getItems().size());button("loadMoreButton").fire();return null;});awaitTasks();
        fx(()->{assertEquals(600,table().getItems().size());table().getSelectionModel().select(450);var refresh=ZnackCardRegistrationController.class.getDeclaredMethod("refreshBatchRows");refresh.setAccessible(true);refresh.invoke(controller);assertEquals(600,table().getItems().size());return null;});awaitTasks();
        fx(()->{assertEquals(600,table().getItems().size());assertEquals(600,table().getItems().stream().map(Sku::chrtId).distinct().count());assertEquals(1,table().getSelectionModel().getSelectedItems().size());assertFalse(((ComboBox<?>)loader.getNamespace().get("statusFilter")).isDisabled());return null;});
    }
    @Test void terminalBatchShowsActionReasonAndKeepsMoreRecentReport()throws Exception{
        fx(()->{
            var controller=(ZnackCardRegistrationController)loader.getController();
            var render=ZnackCardRegistrationController.class.getDeclaredMethod("renderBatchProgress",com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.Progress.class);render.setAccessible(true);
            var progress=new com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.Progress("old-batch",100,0,0,100,"","","","","SOURCE_CONFIGURATION_CHANGED",com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.State.NEEDS_ACTION);
            render.invoke(controller,progress);
            assertTrue(((Label)loader.getNamespace().get("batchProgressLabel")).getText().contains("SOURCE_CONFIGURATION_CHANGED"));
            var run=ZnackCardRegistrationController.class.getDeclaredField("lastRegistrationRun");run.setAccessible(true);run.set(controller,"newer-gtin-run");render.invoke(controller,progress);
            assertEquals("newer-gtin-run",run.get(controller));return null;
        });
    }
    @Test void durableQueueControlsPauseResumeAndCancelOnlyAfterSellerConfirmation()throws Exception{
        var controller=(ZnackCardRegistrationController)loader.getController();var calls=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var current=new java.util.concurrent.atomic.AtomicReference<>(new com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.Progress("controls",10,2,0,8,"Next","M","Created","XL","",com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.State.RUNNING));
        fx(()->{stopBatchPolling(controller);var reader=controller.getClass().getDeclaredField("batchReader");reader.setAccessible(true);reader.set(controller,(java.util.function.Function<Shop,com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.Progress>)owner->current.get());var control=controller.getClass().getDeclaredField("batchControl");control.setAccessible(true);control.set(controller,(java.util.function.BiFunction<Shop,String,Boolean>)(owner,action)->{assertFalse(Platform.isFxApplicationThread());calls.add(action);var before=current.get();var state=com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.State.valueOf(switch(action){case "PAUSE"->"PAUSED";case "RESUME"->"RUNNING";default->"CANCELLED";});current.set(new com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService.Progress(before.runId(),before.total(),before.submitted(),before.failed(),before.remaining(),"","",before.lastSubmittedArticle(),before.lastSubmittedSize(),"",state));return true;});renderProgress(controller,current.get());stopBatchPolling(controller);assertFalse(batchMenu("znackPauseBatch").isDisable());assertTrue(batchMenu("znackResumeBatch").isDisable());batchMenu("znackPauseBatch").fire();return null;});awaitTasks();com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        fx(()->{renderProgress(controller,current.get());stopBatchPolling(controller);assertEquals(java.util.List.of("PAUSE"),calls);assertFalse(batchMenu("znackResumeBatch").isDisable());assertTrue(batchMenu("znackPauseBatch").isDisable());assertFalse(((ComboBox<?>)loader.getNamespace().get("statusFilter")).isDisabled());assertTrue(((Label)loader.getNamespace().get("batchProgressLabel")).getText().contains(I18nService.getInstance().tr("znack.registration.batch_paused")));var pill=(javafx.scene.layout.HBox)((javafx.scene.layout.StackPane)table().getParent()).lookup("#registrationBackgroundPill");assertTrue(pill.getChildren().stream().noneMatch(ProgressIndicator.class::isInstance));batchMenu("znackResumeBatch").fire();return null;});awaitTasks();com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        fx(()->{renderProgress(controller,current.get());stopBatchPolling(controller);assertEquals(java.util.List.of("PAUSE","RESUME"),calls);var confirmation=controller.getClass().getDeclaredField("cancelPendingConfirmation");confirmation.setAccessible(true);confirmation.set(controller,(java.util.function.BooleanSupplier)()->false);batchMenu("znackCancelPendingBatch").fire();assertEquals(2,calls.size());confirmation.set(controller,(java.util.function.BooleanSupplier)()->true);batchMenu("znackCancelPendingBatch").fire();return null;});awaitTasks();com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        fx(()->{renderProgress(controller,current.get());stopBatchPolling(controller);assertEquals(java.util.List.of("PAUSE","RESUME","CANCEL"),calls);assertTrue(batchMenu("znackPauseBatch").isDisable());assertTrue(batchMenu("znackResumeBatch").isDisable());assertTrue(batchMenu("znackCancelPendingBatch").isDisable());assertFalse(((Hyperlink)loader.getNamespace().get("reportLink")).isDisabled());assertTrue(((Label)loader.getNamespace().get("batchProgressLabel")).getText().contains("2/10"),"Accepted cards stay in the report after cancellation");return null;});
    }
    private MenuItem batchMenu(String id){return ((Label)loader.getNamespace().get("titleLabel")).getContextMenu().getItems().stream().filter(item->id.equals(item.getId())).findFirst().orElseThrow();}
    @Test void errorFilterSelectAllOpensCompleteCatalogReportEvenForUnmatchedHistoricalCards()throws Exception{
        var owner=new Shop(1,"Fixture","fixture");var raw=com.google.gson.JsonParser.parseString("{\"good_status\":\"draft\",\"good_detailed_status\":[\"errors\"]}").getAsJsonObject();
        var card=new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("00000000000017","UNMATCHED","ЧЕРНЫЙ","1",true,false,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(owner),java.time.Instant.now(),com.vncode.app.integration.znack.registration.CatalogProductDataParser.parse(raw));
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,owner.getMarketplace(),java.util.List.of(card));
        fx(()->{((ComboBox<?>)loader.getNamespace().get("statusFilter")).getSelectionModel().selectLast();return null;});awaitTasks();
        fx(()->{assertFalse(button("registerSelectedButton").isDisabled(),"Catalog-only errors still have a report action");((CheckBox)loader.getNamespace().get("allFilteredCheckBox")).fire();return null;});
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean found=false;
        while(!found&&System.nanoTime()<deadline){found=fx(()->{var dialog=Window.getWindows().stream().filter(Window::isShowing).map(w->w.getScene().getRoot()).filter(n->"catalogErrorReportDialog".equals(n.getId())).findFirst().orElse(null);if(dialog==null)return false;assertEquals(1,((TableView<?>)dialog.lookup("#catalogErrorReportTable")).getItems().size());var pane=(DialogPane)dialog;var cancel=pane.getButtonTypes().stream().filter(b->b.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();((Button)pane.lookupButton(cancel)).fire();return true;});if(!found)Thread.sleep(20);}
        assertTrue(found,"Selecting all errors must open the report through the actual screen button");
        try(var c=Database.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT count(*) FROM znack_card_registrations WHERE gtin='00000000000017'")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
    @Test void failedReadKeepsLastGoodRowsButMarksNewVerificationUnknownAndBlocksMutations()throws Exception {
        var before=fx(()->table().getItems().stream().map(Sku::chrtId).toList());
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("UPDATE sync_runs SET state='FAILED',error_code='NETWORK_TIMEOUT' WHERE kind='registration_sources'");}
        fx(()->{button("refreshButton").fire();return null;});awaitTasks();
        fx(()->{assertEquals(before,table().getItems().stream().map(Sku::chrtId).toList());for(String action:new String[]{"updateWbButton","signApprovedButton","registerSelectedButton","selectAllButton"})assertTrue(button(action).isDisabled(),action);
            assertFalse(button("syncZnackButton").isDisabled());assertFalse(button("configButton").isDisabled());String warning=((Label)loader.getNamespace().get("readinessLabel")).getText();assertTrue(warning.contains("NETWORK_TIMEOUT"));assertTrue(warning.contains(I18nService.getInstance().tr("znack.registration.latest_read_unknown")));
            var actual=table().getItems().stream().filter(row->row.chrtId()==3).findFirst().orElseThrow();var verified=(TableColumn<Sku,String>)loader.getNamespace().get("verifiedGtinColumn");assertTrue(verified.getCellData(actual).contains(I18nService.getInstance().tr("znack.registration.gtin_unverified")),"A failed new read must not reuse its former successful verification label");
            var local=table().getItems().stream().filter(row->row.chrtId()==1).findFirst().orElseThrow();assertEquals(Status.NOT_CREATED,local.status(),"Infrastructure failure must not rewrite a saved native checkpoint");return null;});
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(new Shop(1,"Fixture","fixture"));fx(()->{button("refreshButton").fire();return null;});awaitTasks();
        fx(()->{assertFalse(button("updateWbButton").isDisabled());var actual=table().getItems().stream().filter(row->row.chrtId()==3).findFirst().orElseThrow();assertFalse(((TableColumn<Sku,String>)loader.getNamespace().get("verifiedGtinColumn")).getCellData(actual).contains(I18nService.getInstance().tr("znack.registration.gtin_unverified")));return null;});
    }
    @Test void ownerChangeCannotRetainAnotherScopeLastGoodRows()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated-fixture' WHERE id=1");}
        fx(()->{button("refreshButton").fire();return null;});awaitTasks();
        fx(()->{assertTrue(table().getItems().isEmpty());assertTrue(button("updateWbButton").isDisabled());assertTrue(button("registerSelectedButton").isDisabled());return null;});
    }
    @Test void completedSourceMembershipExcludesCachedProductsThatWereNotObserved()throws Exception {
        try(var c=Database.getConnection();var statement=c.createStatement()){
            statement.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,999,'OLD','Not on source','old')");
            statement.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,999,999,'XL')");
        }
        fx(()->{button("refreshButton").fire();return null;});awaitTasks();fx(()->{assertEquals(3,table().getItems().size());assertTrue(table().getItems().stream().noneMatch(row->row.chrtId()==999));return null;});
    }
    @Test void publishedCatalogCardWithoutLocalRegistrationIsVisibleAndEffectiveFiltersAgree()throws Exception {
        var owner=new Shop(1,"Fixture","fixture");
        var catalog=new com.vncode.app.features.gtinsync.GtinSyncRepository();var cards=new java.util.ArrayList<>(catalog.catalog(1,com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES));
        cards.add(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(com.vncode.app.features.gtinsync.GtinWriteTestFixture.GTIN,"SKU-1","Blue","XL",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(owner),java.time.Instant.now()));catalog.saveCatalog(1,com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES,cards);
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,1,'00000000000017')");}
        fx(()->{button("refreshButton").fire();return null;});awaitTasks();
        fx(()->{
            @SuppressWarnings("unchecked") var column=(javafx.scene.control.TableColumn<Sku,String>)loader.getNamespace().get("statusColumn");
            assertEquals(I18nService.getInstance().tr("znack.registration.status.published"),column.getCellData(table().getItems().stream().filter(row->row.chrtId()==1).findFirst().orElseThrow()));
            assertTrue(((Label)loader.getNamespace().get("summaryLabel")).getText().contains("2"));
            RegistrationScreenshot.capture(loader.getRoot(),"wb-registration");
            ((javafx.scene.control.ComboBox<?>)loader.getNamespace().get("statusFilter")).getSelectionModel().select(5);return null;
        });awaitTasks();fx(()->{assertEquals(java.util.Set.of(1L),table().getItems().stream().map(Sku::chrtId).collect(java.util.stream.Collectors.toSet()));
            ((javafx.scene.control.ComboBox<?>)loader.getNamespace().get("statusFilter")).getSelectionModel().select(2);return null;
        });awaitTasks();fx(()->{assertTrue(table().getItems().stream().anyMatch(row->row.chrtId()==3),"A published Catalog card still waits for marketplace GTIN readback");
            @SuppressWarnings("unchecked") var column=(javafx.scene.control.TableColumn<Sku,String>)loader.getNamespace().get("statusColumn");
            var row=table().getItems().stream().filter(item->item.chrtId()==3).findFirst().orElseThrow();
            assertEquals(I18nService.getInstance().tr("znack.registration.status.published"),column.getCellData(row));
            @SuppressWarnings("unchecked") var marketplace=(TableColumn<Sku,String>)loader.getNamespace().get("marketplaceSyncColumn");
            assertTrue(marketplace.getCellData(row).contains(I18nService.getInstance().tr("znack.registration.awaiting_wb_gtin")));return null;});
    }
    @Test void originalAndRussianSizesAndTheThreeIndependentStatesHaveSeparateColumns()throws Exception {
        try(var c=Database.getConnection();var statement=c.createStatement()){
            statement.execute("UPDATE wb_product_sizes SET wb_size='52' WHERE shop_id=1 AND chrt_id=3");
        }
        fx(()->{button("refreshButton").fire();return null;});awaitTasks();
        fx(()->{
            @SuppressWarnings("unchecked") var original=(TableColumn<Sku,String>)loader.getNamespace().get("sizeColumn");
            @SuppressWarnings("unchecked") var russian=(TableColumn<Sku,String>)loader.getNamespace().get("russianSizeColumn");
            @SuppressWarnings("unchecked") var registration=(TableColumn<Sku,String>)loader.getNamespace().get("statusColumn");
            @SuppressWarnings("unchecked") var moderation=(TableColumn<Sku,String>)loader.getNamespace().get("moderationColumn");
            @SuppressWarnings("unchecked") var marketplace=(TableColumn<Sku,String>)loader.getNamespace().get("marketplaceSyncColumn");
            var known=table().getItems().stream().filter(row->row.chrtId()==3).findFirst().orElseThrow();
            var unknown=table().getItems().stream().filter(row->row.chrtId()==1).findFirst().orElseThrow();
            assertEquals("XL",original.getCellData(known));assertEquals("52",russian.getCellData(known));
            assertEquals(I18nService.getInstance().tr("znack.registration.table_size_unknown"),russian.getCellData(unknown));
            assertEquals(I18nService.getInstance().tr("znack.registration.column_registration"),registration.getText());
            assertEquals(I18nService.getInstance().tr("znack.registration.column_moderation"),moderation.getText());
            assertEquals(I18nService.getInstance().tr("znack.registration.status.published"),registration.getCellData(known));
            assertTrue(moderation.getCellData(known).contains(I18nService.getInstance().tr("znack.registration.status.published")));
            assertTrue(marketplace.getCellData(known).contains(I18nService.getInstance().tr("znack.registration.awaiting_wb_gtin")));
            assertTrue(marketplace.getCellData(known).contains(java.text.MessageFormat.format(I18nService.getInstance().tr("znack.registration.table_checked_at"),"")));
            return null;
        });
    }
    @Test void selectAllIncludesPublishedRowsForTheSeparateNewCardAction() throws Exception {
        fx(()->{
            assertEquals(SelectionMode.MULTIPLE,table().getSelectionModel().getSelectionMode());
            assertTrue(button("registerSelectedButton").isDisable());
            button("selectAllButton").fire();
            assertEquals(3,table().getSelectionModel().getSelectedItems().size());
            assertEquals(1,table().getSelectionModel().getSelectedItems().stream().filter(s->s.status()==Status.PUBLISHED).count());
            assertFalse(button("newCardsSelectedButton").isDisable());
            assertFalse(button("registerSelectedButton").isDisable());return null;
        });
    }
    @Test void changedTnvedRowNewCardButtonRequiresExplicitConfirmationAndUsesReplacementFlow()throws Exception{
        var accepted=new java.util.concurrent.atomic.AtomicBoolean();var calls=new java.util.concurrent.atomic.AtomicInteger();
        fx(()->{var controller=(ZnackCardRegistrationController)loader.getController();var source=table().getItems().stream().filter(row->row.chrtId()==3).findFirst().orElseThrow();var changed=new Sku(source.nmId(),source.chrtId(),source.subjectId(),source.vendorCode(),source.subjectName(),source.brand(),source.title(),source.color(),source.size(),source.barcodes(),source.imageUrl(),source.needKiz(),source.gtin(),source.goodId(),source.feedId(),source.status(),source.errorMessage(),source.wbUpdated(),source.wbSize(),source.registrationRevision(),true);
            var confirmation=controller.getClass().getDeclaredField("replacementConfirmation");confirmation.setAccessible(true);confirmation.set(controller,(java.util.function.Predicate<Sku>)row->accepted.get());var preparation=controller.getClass().getDeclaredField("replacementPreparation");preparation.setAccessible(true);preparation.set(controller,(ZnackCardRegistrationController.ReplacementPreparation)(owner,epoch,row,fresh,replacement,done)->{assertEquals(3,row.chrtId());assertFalse(fresh);assertTrue(replacement,"A button labelled new card must use the explicitly approved new-card path");calls.incrementAndGet();});
            var column=(TableColumn<Sku,Sku>)loader.getNamespace().get("actionColumn");var cell=column.getCellFactory().call(column);var update=cell.getClass().getDeclaredMethod("updateItem",Sku.class,boolean.class);update.setAccessible(true);update.invoke(cell,changed,false);var button=(Button)cell.getGraphic();assertEquals(I18nService.getInstance().tr("znack.registration.create_new_card"),button.getText());assertFalse(button.isDisabled());button.fire();assertEquals(0,calls.get(),"Cancelling the confirmation must not prepare, allocate or send a replacement");accepted.set(true);button.fire();assertEquals(1,calls.get());return null;});
    }
    @Test void cancellingFreshGtinConfirmationLeavesThePendingCardUnchanged() throws Exception {
        try(var c=Database.getConnection();var statement=c.createStatement()){
            statement.execute("UPDATE znack_card_registrations SET status='ALLOCATING_GTIN' WHERE shop_id=1 AND chrt_id=2");
        }
        fx(()->{button("refreshButton").fire();return null;});awaitTasks();
        fx(()->{
            // Other FXML tests may leave a dialog open. Never dismiss or inspect that dialog.
            Alert unrelated=new Alert(Alert.AlertType.INFORMATION,"Unrelated fixture notification");
            unrelated.show();
            var previous=java.util.Set.copyOf(Window.getWindows());
            table().getSelectionModel().select(1);
            var cancelled=new CompletableFuture<Boolean>();
            Platform.runLater(()->{
                for(Window window:Window.getWindows()) {
                    if(!previous.contains(window) && window.getScene()!=null
                            && window.getScene().getRoot() instanceof DialogPane pane
                            && I18nService.getInstance().tr("znack.registration.register_selected").equals(pane.getHeaderText())) {
                        // Close the dialog even if its warning is wrong, so assertion failures cannot hang FX.
                        boolean warnsAboutQuota=pane.getContentText().contains("GS1");
                        ((Button)pane.lookupButton(ButtonType.CANCEL)).fire();
                        cancelled.complete(warnsAboutQuota);return;
                    }
                }
                cancelled.complete(false);
            });
            try {
                button("registerSelectedButton").fire();
                assertTrue(cancelled.getNow(false),"Expected a cancellable GS1 quota confirmation");
                assertTrue(unrelated.isShowing(),"The unrelated dialog must remain untouched");
            } finally { unrelated.close(); }
            return null;
        });
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery(
                "SELECT status,gtin,updated_at FROM znack_card_registrations WHERE shop_id=1 AND chrt_id=2")) {
            assertTrue(r.next());assertEquals("ALLOCATING_GTIN",r.getString(1));assertNull(r.getString(2));assertEquals("fixture",r.getString(3));
        }
    }
    @Test void recoveryCopyExistsInEverySupportedLanguage() {
        for(String language:new String[]{"vi","en","ru","zh"}) {
            var bundle=java.util.ResourceBundle.getBundle("com.vncode.app.i18n.messages",java.util.Locale.forLanguageTag(language));
            for(String key:new String[]{"znack.registration.new_gtin","znack.registration.confirm_new_gtin",
                    "znack.registration.status.gtin_review_required","znack.registration.register_selected",
                    "znack.registration.sign_approved","znack.registration.sync_znack","znack.registration.page_count",
                    "znack.registration.catalog_summary","znack.registration.confirm_replacement","znack.registration.title_ozon"}) {
                assertTrue(bundle.containsKey(key),language+": "+key);
                assertFalse(bundle.getString(key).isBlank());
            }
        }
    }
    @Test void loadsBeyondFiveHundredAndFreezesAllFilteredWithoutExpandingFxRows() throws Exception {
        try(var c=Database.getConnection()) {
            c.setAutoCommit(false);
            try(var card=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,?,'bulk','Fixture','fixture')");
                var size=c.prepareStatement("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,?,?,'XL')")) {
                for(int id=4;id<=1_003;id++){card.setInt(1,100+id);card.addBatch();size.setInt(1,id);size.setInt(2,100+id);size.addBatch();}
                card.executeBatch();size.executeBatch();
            }
            c.commit();
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(new Shop(1,"Fixture","fixture"));
        fx(()->{button("refreshButton").fire();return null;});awaitTasks();
        fx(()->{assertEquals(300,table().getItems().size());assertFalse(button("loadMoreButton").isVisible(),"Scrolling loads more rows without a pagination control");button("selectAllButton").fire();return null;});awaitTasks();
        fx(()->{
            assertEquals(300,table().getItems().size(),"All-filtered selection must not create every FX row");
            Label selected=(Label)loader.getNamespace().get("selectionLabel");
            assertEquals(java.text.MessageFormat.format(I18nService.getInstance().tr("znack.registration.selection_count"),1_003),selected.getText());
            assertFalse(button("registerSelectedButton").isDisable());button("clearSelectionButton").fire();
            assertTrue(button("registerSelectedButton").isDisable());button("loadMoreButton").fire();return null;
        });awaitTasks();
        fx(()->{assertEquals(600,table().getItems().size());button("loadMoreButton").fire();return null;});awaitTasks();
        fx(()->{assertEquals(900,table().getItems().size());button("loadMoreButton").fire();return null;});awaitTasks();
        fx(()->{assertEquals(1_003,table().getItems().size());assertFalse(button("loadMoreButton").isVisible());
            assertEquals(1_003,table().getItems().stream().map(Sku::chrtId).distinct().count());
            ((ComboBox<?>)loader.getNamespace().get("statusFilter")).getSelectionModel().select(1);return null;});awaitTasks();
        var responsive=new CountDownLatch(1);
        fx(()->{((CheckBox)loader.getNamespace().get("allFilteredCheckBox")).fire();Platform.runLater(responsive::countDown);return null;});
        assertTrue(responsive.await(3,TimeUnit.SECONDS));awaitTasks();
        fx(()->{assertTrue(((Hyperlink)loader.getNamespace().get("reportLink")).isVisible());
            assertEquals(java.text.MessageFormat.format(I18nService.getInstance().tr("znack.registration.selection_count"),1_002),((Label)loader.getNamespace().get("selectionLabel")).getText());
            assertFalse(button("registerSelectedButton").isDisabled());return null;});
    }
    @Test void sourceActionsExistAndOpeningOrRefreshingDoesNotResumeRemoteRegistration() throws Exception {
        fx(()->{for(String id:new String[]{"configButton","signApprovedButton","updateWbButton","syncZnackButton"})assertNotNull(button(id));
            assertNotNull(loader.getNamespace().get("genderColumn"));button("refreshButton").fire();return null;});awaitTasks();
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT status,updated_at FROM znack_card_registrations WHERE shop_id=1 AND chrt_id=2")) {
            assertTrue(r.next());assertEquals("CHECKING",r.getString(1));assertEquals("fixture",r.getString(2));
        }
    }
    @Test void dispatchesOzonToItsOwnHostAndClearsWbSelection() throws Exception {
        fx(()->{
            ZnackCardRegistrationController controller=loader.getController();
            controller.setOzonRegistrationFactory(owner->new Label("Ozon fixture "+owner.getId()));
            controller.setShop(new Shop(9,"Ozon fixture",com.vncode.app.integration.marketplace.Marketplace.OZON,"fixture-client","fixture"));
            assertTrue(((javafx.scene.layout.StackPane)loader.getNamespace().get("ozonRegistrationHost")).isVisible());
            assertFalse(((javafx.scene.layout.VBox)loader.getNamespace().get("wbRegistrationContent")).isManaged());
            assertTrue(table().getItems().isEmpty());assertTrue(button("registerSelectedButton").isDisable());return null;
        });
    }
    @Test void headerAndFooterMatchTheApprovedRegistrationLayout()throws Exception{
        fx(()->{
            var header=(javafx.scene.layout.HBox)loader.getNamespace().get("registrationHeader");
            assertEquals(java.util.List.of("configButton","signApprovedButton","updateWbButton","syncZnackButton"),header.getChildren().stream().filter(n->n instanceof Button).map(javafx.scene.Node::getId).toList());
            var footer=button("registerSelectedButton").getParent();assertSame(footer,loader.getNamespace().get("allFilteredCheckBox") instanceof javafx.scene.Node n?n.getParent():null);
            assertSame(footer,((Label)loader.getNamespace().get("paginationLabel")).getParent());
            assertEquals("selectionColumn",table().getColumns().getFirst().getId());
            assertFalse(((TableColumn<?,?>)loader.getNamespace().get("detailColumn")).isVisible());
            return null;
        });
    }
    @Test void selectAllAcrossPagesDoesNotMisrouteAnUnsentPreSubmitErrorToCatalogRepair()throws Exception{
        try(var c=Database.getConnection();var q=c.createStatement()){
            q.execute("UPDATE znack_card_registrations SET status='PRE_SUBMIT_ERROR',gtin='00000000000017' WHERE shop_id=1 AND chrt_id=2");
            for(int id=4;id<=305;id++){
                q.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,"+(100+id)+",'SKU-"+id+"','Fixture','fixture')");
                q.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,"+id+","+(100+id)+",'XL')");
            }
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(new Shop(1,"Fixture","fixture"));
        var controller=(ZnackCardRegistrationController)loader.getController();var dispatched=new CountDownLatch(1);var count=new java.util.concurrent.atomic.AtomicInteger();
        fx(()->{
            var preflight=controller.getClass().getDeclaredField("preflightReader");preflight.setAccessible(true);preflight.set(controller,(java.util.function.BiFunction<Shop,java.util.List<com.vncode.app.features.products.ProductKey>,RegistrationBatchPreflight.Review>)(owner,selection)->new RegistrationBatchPreflight.Review(java.util.List.of()));
            var starter=controller.getClass().getDeclaredField("batchStarter");starter.setAccessible(true);starter.set(controller,(ZnackCardRegistrationController.BatchStarter)(owner,selection,sign)->{count.set(selection.size());dispatched.countDown();return "all-unsent-fixture";});
            ((ComboBox<?>)loader.getNamespace().get("statusFilter")).getSelectionModel().select(1);return null;
        });awaitTasks();
        fx(()->{((CheckBox)loader.getNamespace().get("allFilteredCheckBox")).fire();return null;});awaitTasks();
        fx(()->{assertEquals(300,table().getItems().size());assertEquals(I18nService.getInstance().tr("znack.registration.register_short"),button("registerSelectedButton").getText());button("registerSelectedButton").fire();return null;});
        assertTrue(dispatched.await(5,TimeUnit.SECONDS),"Select all and one Register click must start submission, not error repair");assertEquals(304,count.get());awaitTasks();
    }
    @Test void automaticRefreshKeepsFilteredLoadedExtentAndSelection()throws Exception{
        try(var c=Database.getConnection();var q=c.createStatement()){
            for(int id=4;id<=405;id++){
                q.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,"+(100+id)+",'SKU-"+id+"','Fixture','fixture')");
                q.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,"+id+","+(100+id)+",'XL')");
            }
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(new Shop(1,"Fixture","fixture"));
        fx(()->{((ComboBox<?>)loader.getNamespace().get("statusFilter")).getSelectionModel().select(1);return null;});awaitTasks();
        fx(()->{button("loadMoreButton").fire();return null;});awaitTasks();
        var timerDone=new CountDownLatch(1);var changes=new java.util.concurrent.atomic.AtomicInteger();
        fx(()->{assertEquals(404,table().getItems().size());table().getSelectionModel().select(0);table().getItems().addListener((javafx.collections.ListChangeListener<Sku>)change->changes.incrementAndGet());((ZnackCardRegistrationController)loader.getController()).refreshLocal();var timer=new javafx.animation.PauseTransition(javafx.util.Duration.millis(650));timer.setOnFinished(e->timerDone.countDown());timer.play();return null;});
        assertTrue(timerDone.await(3,TimeUnit.SECONDS));awaitTasks();
        fx(()->{assertEquals(404,table().getItems().size());assertEquals(1,((ComboBox<?>)loader.getNamespace().get("statusFilter")).getSelectionModel().getSelectedIndex());assertEquals(1,table().getSelectionModel().getSelectedItems().size());assertEquals(0,changes.get(),"An unchanged background sync must not replace the current table contents");return null;});
    }
    @Test void oneRegisterClickDispatchesTheFrozenSelectionWhileFiltersRemainUsable()throws Exception{
        var controller=(ZnackCardRegistrationController)loader.getController();
        var dispatched=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var frozen=new java.util.concurrent.atomic.AtomicReference<java.util.List<Sku>>();
        fx(()->{
            var preflight=controller.getClass().getDeclaredField("preflightReader");preflight.setAccessible(true);
            preflight.set(controller,(java.util.function.BiFunction<Shop,java.util.List<com.vncode.app.features.products.ProductKey>,RegistrationBatchPreflight.Review>)(owner,selection)->new RegistrationBatchPreflight.Review(java.util.List.of()));
            var field=controller.getClass().getDeclaredField("batchStarter");field.setAccessible(true);
            field.set(controller,(ZnackCardRegistrationController.BatchStarter)(owner,selection,autoSign)->{
                frozen.set(java.util.List.copyOf(selection));dispatched.countDown();
                try{if(!finish.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture dispatch timed out");}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                return "frozen-button-run";
            });
            ((ComboBox<?>)loader.getNamespace().get("statusFilter")).getSelectionModel().select(1);return null;
        });awaitTasks();
        try{
            fx(()->{((CheckBox)loader.getNamespace().get("allFilteredCheckBox")).fire();button("registerSelectedButton").fire();return null;});
            assertTrue(dispatched.await(5,TimeUnit.SECONDS),"One click must dispatch every safe unsent card without another create click");
            assertEquals(java.util.Set.of(1L,2L),frozen.get().stream().map(Sku::chrtId).collect(java.util.stream.Collectors.toSet()));
            fx(()->{
                assertFalse(((ComboBox<?>)loader.getNamespace().get("statusFilter")).isDisabled());
                assertFalse(((TextField)loader.getNamespace().get("searchField")).isDisabled());
                ((ComboBox<?>)loader.getNamespace().get("statusFilter")).getSelectionModel().select(2);
                return null;
            });
            assertEquals(2,frozen.get().size(),"Changing the view cannot discard the dispatched selection");
        }finally{finish.countDown();}
        awaitTasks();
    }
    @Test void countryDictionaryFailureKeepsTheBatchUnstartedAndProvidesCopyableRootCause()throws Exception{
        var controller=(ZnackCardRegistrationController)loader.getController();var starts=new java.util.concurrent.atomic.AtomicInteger();
        fx(()->{var preflight=controller.getClass().getDeclaredField("preflightReader");preflight.setAccessible(true);preflight.set(controller,(java.util.function.BiFunction<Shop,java.util.List<com.vncode.app.features.products.ProductKey>,RegistrationBatchPreflight.Review>)(owner,selected)->{assertFalse(Platform.isFxApplicationThread());assertTrue(Thread.currentThread().getName().startsWith("vncode-catalog-read-"));throw new IllegalStateException("CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE",new java.net.SocketTimeoutException("fixture dictionary timeout"));});var starter=controller.getClass().getDeclaredField("batchStarter");starter.setAccessible(true);starter.set(controller,(ZnackCardRegistrationController.BatchStarter)(owner,selected,sign)->{starts.incrementAndGet();return "must-not-start";});table().getSelectionModel().select(table().getItems().stream().filter(row->row.chrtId()==1).findFirst().orElseThrow());return null;});
        var preparingField=controller.getClass().getDeclaredField("preparing");preparingField.setAccessible(true);
        com.vncode.app.testsupport.FxDialogDriver.choose(()->button("registerSelectedButton").fire(),pane->pane.lookup(".text-area") instanceof TextArea,pane->{
            String diagnostics=((TextArea)pane.lookup(".text-area")).getText();assertTrue(diagnostics.contains("CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE"));assertTrue(diagnostics.contains("SocketTimeoutException"));assertTrue(pane.getButtonTypes().stream().anyMatch(button->button.getButtonData()==ButtonBar.ButtonData.OTHER));
            try{assertFalse(preparingField.getBoolean(controller));}catch(IllegalAccessException failure){throw new AssertionError(failure);}assertFalse(button("registerSelectedButton").isDisabled());assertTrue(((Label)loader.getNamespace().get("operationLabel")).getText().contains("[CATALOG_COUNTRY_TIMEOUT]"),"The short message must classify the preserved timeout cause; full diagnostics retain its dictionary context");
            return pane.getButtonTypes().stream().filter(button->button.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
        });awaitTasks();assertEquals(0,starts.get());
        try(var c=Database.getConnection();var statement=c.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM znack_card_registrations WHERE chrt_id=1")){assertTrue(rows.next());assertEquals(0,rows.getInt(1),"Infrastructure failure must not allocate GTIN or mark this product as a failed submission");}
    }
    @Test void selectingExistingDraftsOpensFullFreshReviewAndCancellingDoesNotStartOrResume()throws Exception {
        var owner=new Shop(1,"Fixture","fixture");var catalog=new com.vncode.app.features.gtinsync.GtinSyncRepository();
        var cards=new java.util.ArrayList<>(catalog.catalog(1,owner.getMarketplace()));
        for(int n=1;n<=2;n++){
            String code=n==1?"00000000000017":"00000000000024";
            var details=new com.vncode.app.features.catalogsync.CatalogProductData(java.util.Map.of("@catalog_id",java.util.List.of(Integer.toString(900+n)),"@catalog_status",java.util.List.of("draft"),"@catalog_detailed_status",java.util.List.of("draft"),"@catalog_signed",java.util.List.of("false")),java.util.List.of(),java.util.List.of());
            cards.add(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(code,"SKU-"+n,"Blue","XL",true,false,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(owner),java.time.Instant.now(),details));
        }
        catalog.saveCatalog(1,owner.getMarketplace(),cards);
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("UPDATE znack_card_registrations SET gtin='00000000000024',good_id=902,status='PROCESSING',feed_id=NULL,error_message='CATALOG_CARD_DRAFT' WHERE shop_id=1 AND chrt_id=2");}
        fx(()->{button("refreshButton").fire();return null;});awaitTasks();
        var controller=(ZnackCardRegistrationController)loader.getController();var starts=new java.util.concurrent.atomic.AtomicInteger();
        fx(()->{var reader=controller.getClass().getDeclaredField("preflightReader");reader.setAccessible(true);reader.set(controller,(java.util.function.BiFunction<Shop,java.util.List<com.vncode.app.features.products.ProductKey>,RegistrationBatchPreflight.Review>)(shop,keys)->{
                assertFalse(Platform.isFxApplicationThread());assertEquals(java.util.Set.of("1","2"),keys.stream().map(com.vncode.app.features.products.ProductKey::variantId).collect(java.util.stream.Collectors.toSet()));return ExistingDraftReviewTest.completeReview(shop,keys);});
            var starter=controller.getClass().getDeclaredField("batchStarter");starter.setAccessible(true);starter.set(controller,(ZnackCardRegistrationController.BatchStarter)(shop,rows,sign)->{starts.incrementAndGet();return "must-not-start";});return null;});
        com.vncode.app.testsupport.FxDialogDriver.choose(()->{button("selectAllButton").fire();assertFalse(button("registerSelectedButton").isDisabled());button("registerSelectedButton").fire();},pane->"registrationMissingFieldsDialog".equals(pane.getId()),pane->{
            var content=(javafx.scene.layout.VBox)pane.getContent();var review=content.getChildren().stream().filter(TableView.class::isInstance).map(TableView.class::cast).findFirst().orElseThrow();
            assertEquals(4,review.getItems().size(),"Both complete Drafts require explicit review, not just missing fields");
            assertFalse(((ComboBox<?>)loader.getNamespace().get("statusFilter")).isDisabled());
            return pane.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
        });awaitTasks();assertEquals(0,starts.get());
        try(var c=Database.getConnection();var statement=c.createStatement();var rows=statement.executeQuery("SELECT status,gtin,good_id,feed_id FROM znack_card_registrations WHERE shop_id=1 AND chrt_id=2")){
            assertTrue(rows.next());assertEquals("PROCESSING",rows.getString(1));assertEquals("00000000000024",rows.getString(2));assertEquals(902,rows.getLong(3));assertNull(rows.getString(4));
        }
    }
    @Test void navigatingAwayInterruptsAnInitialCatalogReadWithoutDispatchingARegistration()throws Exception{
        var controller=(ZnackCardRegistrationController)loader.getController();var started=new CountDownLatch(1);var exited=new CountDownLatch(1);var release=new CountDownLatch(1);var starts=new java.util.concurrent.atomic.AtomicInteger();var savedTask=new java.util.concurrent.atomic.AtomicReference<javafx.concurrent.Task<?>>();
        try{
            fx(()->{var reader=controller.getClass().getDeclaredField("preflightReader");reader.setAccessible(true);reader.set(controller,(java.util.function.BiFunction<Shop,java.util.List<com.vncode.app.features.products.ProductKey>,RegistrationBatchPreflight.Review>)(owner,selected)->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}finally{exited.countDown();}return new RegistrationBatchPreflight.Review(java.util.List.of());});var starter=controller.getClass().getDeclaredField("batchStarter");starter.setAccessible(true);starter.set(controller,(ZnackCardRegistrationController.BatchStarter)(owner,selected,sign)->{starts.incrementAndGet();return "must-not-start";});table().getSelectionModel().select(table().getItems().stream().filter(row->row.chrtId()==1).findFirst().orElseThrow());button("registerSelectedButton").fire();return null;});assertTrue(started.await(5,TimeUnit.SECONDS));
            fx(()->{var active=controller.getClass().getDeclaredField("registrationPreflightTask");active.setAccessible(true);savedTask.set((javafx.concurrent.Task<?>)active.get(controller));controller.dispose();return null;});assertTrue(exited.await(5,TimeUnit.SECONDS));awaitTasks();assertTrue(savedTask.get().isCancelled());assertEquals(0,starts.get());
        }finally{release.countDown();}
    }
    @Test void aTransientManualPreflightReadHasThreeTotalAttemptsAndNoUnboundedRetryLoop()throws Exception{
        var controller=(ZnackCardRegistrationController)loader.getController();
        fx(()->{var method=controller.getClass().getDeclaredMethod("retryPreparation",Shop.class,long.class,Sku.class,boolean.class,boolean.class,Runnable.class,Throwable.class);method.setAccessible(true);var epoch=controller.getClass().getDeclaredField("shopEpoch");epoch.setAccessible(true);var timer=controller.getClass().getDeclaredField("preparationRetry");timer.setAccessible(true);var transition=(javafx.animation.PauseTransition)timer.get(controller);var row=table().getItems().stream().filter(sku->sku.chrtId()==1).findFirst().orElseThrow();
            for(int attempt=1;attempt<=3;attempt++){assertEquals(attempt<3,method.invoke(controller,new Shop(1,"Fixture","fixture"),epoch.getLong(controller),row,false,false,(Runnable)()->{},new java.net.SocketTimeoutException("fixture dictionary timeout")));transition.stop();}var attempts=controller.getClass().getDeclaredField("preparationAttempts");attempts.setAccessible(true);assertFalse(((java.util.Map<?,?>)attempts.get(controller)).containsKey(1L));return null;});
    }
    @Test void separateBarcodeAndCatalogProofColumnsPreserveRawValuesAndNeverVerifyByLength()throws Exception{
        var owner=new Shop(1,"Fixture","fixture");String actual="04631993764370";var checked=java.time.Instant.now().minusSeconds(120);
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,1,'4006381333931'),(1,3,'04631993764370')");}
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,owner.getMarketplace(),java.util.List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(actual,"SKU-3","Blue","XL",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(owner),checked)));
        fx(()->{button("refreshButton").fire();return null;});awaitTasks();
        fx(()->{var raw=(TableColumn<Sku,String>)loader.getNamespace().get("barcodeColumn");var verified=(TableColumn<Sku,String>)loader.getNamespace().get("verifiedGtinColumn");assertEquals(I18nService.getInstance().tr("znack.registration.barcode_wb"),raw.getText());assertEquals(I18nService.getInstance().tr("znack.registration.verified_gtin"),verified.getText());assertEquals(table().getColumns().indexOf(raw)+1,table().getColumns().indexOf(verified));
            var unknown=table().getItems().stream().filter(row->row.chrtId()==1).findFirst().orElseThrow();var observed=table().getItems().stream().filter(row->row.chrtId()==3).findFirst().orElseThrow();assertEquals("4006381333931",raw.getCellData(unknown));assertTrue(verified.getCellData(unknown).contains(I18nService.getInstance().tr("znack.registration.gtin_unverified")));assertFalse(verified.getCellData(unknown).contains("4006381333931"),"EAN13 does not become a registered GTIN merely by being displayed");assertEquals(actual,raw.getCellData(observed));assertTrue(verified.getCellData(observed).startsWith(actual+"\n"));assertTrue(verified.getCellData(observed).contains(java.text.MessageFormat.format(I18nService.getInstance().tr("znack.registration.gtin_verified_at"),java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(java.time.ZoneId.systemDefault()).format(checked))));assertFalse(verified.getCellData(observed).contains(I18nService.getInstance().tr("znack.registration.gtin_unverified")));
            var controller=(ZnackCardRegistrationController)loader.getController();var index=controller.getClass().getDeclaredField("cardIndex");index.setAccessible(true);var saved=index.get(controller);index.set(controller,null);assertTrue(verified.getCellData(observed).contains(I18nService.getInstance().tr("znack.registration.gtin_unverified")),"Unavailable verification must never reuse a successful label");assertEquals(actual,raw.getCellData(observed));index.set(controller,saved);return null;});
    }
    @SuppressWarnings("unchecked") TableView<Sku> table(){return (TableView<Sku>)loader.getNamespace().get("productTable");}
    Button button(String id){var b=(Button)loader.getNamespace().get(id);assertNotNull(b,id);return b;}
    static <T>T fx(Callable<T> task) throws Exception {
        var result=new FutureTask<>(task);Platform.runLater(result);
        try{return result.get(10,TimeUnit.SECONDS);}
        catch(ExecutionException e){if(e.getCause() instanceof Error error)throw error;throw e;}
    }
    static void awaitTasks() throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        int idle=0;
        while(System.nanoTime()<end) {
            // Completion on FX may enqueue another worker after the first worker reaches zero.
            fx(()->null);
            idle=AppTaskExecutor.hasRunningTasks()?0:idle+1;
            if(idle==2)return;
            Thread.sleep(20);
        }
        assertFalse(AppTaskExecutor.hasRunningTasks());
        fail("JavaFX callbacks and their follow-up tasks must settle within ten seconds");
    }
}
