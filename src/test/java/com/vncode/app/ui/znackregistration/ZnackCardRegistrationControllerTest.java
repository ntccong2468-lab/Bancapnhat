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
            new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("04631993764370","SKU-3","","XL",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(owner),java.time.Instant.now())));
        fx(()->{
            loader=new FXMLLoader(ZnackCardRegistrationController.class.getResource("znack-card-registration-view.fxml"));
            loader.load();((ZnackCardRegistrationController)loader.getController()).setShop(new Shop(1,"Fixture","fixture"));return null;
        });
        awaitTasks();
    }
    @AfterEach void cleanup() throws Exception {fx(()->{((ZnackCardRegistrationController)loader.getController()).dispose();return null;});awaitTasks();com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    @Test void failedOrMissingPairHidesCachedRowsButKeepsConfigurationAndSyncAvailable()throws Exception {
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("UPDATE sync_runs SET state='FAILED',error_code='NETWORK_TIMEOUT' WHERE kind='registration_sources'");}
        fx(()->{button("refreshButton").fire();return null;});awaitTasks();
        fx(()->{assertTrue(table().getItems().isEmpty());for(String action:new String[]{"updateWbButton","signApprovedButton","registerSelectedButton","selectAllButton"})assertTrue(button(action).isDisabled(),action);
            assertFalse(button("syncZnackButton").isDisabled());assertFalse(button("configButton").isDisabled());assertTrue(((Label)loader.getNamespace().get("readinessLabel")).getText().contains("NETWORK_TIMEOUT"));return null;});
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
        });awaitTasks();fx(()->{assertEquals(2,table().getItems().size());assertEquals(java.util.Set.of(1L,3L),table().getItems().stream().map(Sku::chrtId).collect(java.util.stream.Collectors.toSet()));return null;});
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
    @Test void cancellingFreshGtinConfirmationLeavesThePendingCardUnchanged() throws Exception {
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
            assertTrue(r.next());assertEquals("CHECKING",r.getString(1));assertNull(r.getString(2));assertEquals("fixture",r.getString(3));
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
        fx(()->{assertEquals(300,table().getItems().size());assertTrue(button("loadMoreButton").isVisible());button("selectAllButton").fire();return null;});awaitTasks();
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
            assertEquals(1_003,table().getItems().stream().map(Sku::chrtId).distinct().count());return null;});
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
    @SuppressWarnings("unchecked") TableView<Sku> table(){return (TableView<Sku>)loader.getNamespace().get("productTable");}
    Button button(String id){var b=(Button)loader.getNamespace().get(id);assertNotNull(b,id);return b;}
    static <T>T fx(Callable<T> task) throws Exception {
        var result=new FutureTask<>(task);Platform.runLater(result);
        try{return result.get(10,TimeUnit.SECONDS);}
        catch(ExecutionException e){if(e.getCause() instanceof Error error)throw error;throw e;}
    }
    static void awaitTasks() throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(AppTaskExecutor.hasRunningTasks() && System.nanoTime()<end)Thread.sleep(20);
        fx(()->null);assertFalse(AppTaskExecutor.hasRunningTasks());
    }
}
