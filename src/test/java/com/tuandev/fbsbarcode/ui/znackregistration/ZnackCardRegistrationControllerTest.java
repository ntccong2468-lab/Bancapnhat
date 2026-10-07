package com.tuandev.fbsbarcode.ui.znackregistration;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
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
        System.setProperty("wcode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture')");
            for(int id=1;id<=3;id++) {
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,"+(100+id)+",'SKU-"+id+"','Fixture','2026-10-06T00:00:00Z')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,"+id+","+(100+id)+",'XL')");
            }
            s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,status,created_at,updated_at) VALUES(1,2,102,'CHECKING','fixture','fixture')");
            s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,status,created_at,updated_at) VALUES(1,3,103,'04631993764370','PUBLISHED','fixture','fixture')");
        }
        fx(()->{
            loader=new FXMLLoader(ZnackCardRegistrationController.class.getResource("znack-card-registration-view.fxml"));
            loader.load();((ZnackCardRegistrationController)loader.getController()).setShop(new Shop(1,"Fixture","fixture"));return null;
        });
        awaitTasks();
    }
    @AfterEach void cleanup() throws Exception {awaitTasks();System.clearProperty("wcode.appdata.dir");}
    @Test void selectAllChoosesOnlyEligibleRowsAndUsesMultipleSelection() throws Exception {
        fx(()->{
            assertEquals(SelectionMode.MULTIPLE,table().getSelectionModel().getSelectionMode());
            assertTrue(button("registerSelectedButton").isDisable());
            button("selectAllButton").fire();
            assertEquals(2,table().getSelectionModel().getSelectedItems().size());
            assertTrue(table().getSelectionModel().getSelectedItems().stream().noneMatch(s->s.status()==Status.PUBLISHED));
            assertFalse(button("registerSelectedButton").isDisable());return null;
        });
    }
    @Test void cancellingFreshGtinConfirmationLeavesThePendingCardUnchanged() throws Exception {
        fx(()->{
            table().getSelectionModel().select(1);
            var cancelled=new CompletableFuture<Boolean>();
            Platform.runLater(()->{
                for(Window window:Window.getWindows()) {
                    if(window.getScene()!=null && window.getScene().getRoot() instanceof DialogPane pane) {
                        assertTrue(pane.getContentText().contains("GS1"));
                        ((Button)pane.lookupButton(ButtonType.CANCEL)).fire();cancelled.complete(true);return;
                    }
                }
                cancelled.complete(false);
            });
            button("registerSelectedButton").fire();
            assertTrue(cancelled.getNow(false),"Expected a cancellable quota confirmation");return null;
        });
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery(
                "SELECT status,gtin,updated_at FROM znack_card_registrations WHERE shop_id=1 AND chrt_id=2")) {
            assertTrue(r.next());assertEquals("CHECKING",r.getString(1));assertNull(r.getString(2));assertEquals("fixture",r.getString(3));
        }
    }
    @Test void recoveryCopyExistsInEverySupportedLanguage() {
        for(String language:new String[]{"vi","en","ru","zh"}) {
            var bundle=java.util.ResourceBundle.getBundle("com.tuandev.fbsbarcode.i18n.messages",java.util.Locale.forLanguageTag(language));
            for(String key:new String[]{"znack.registration.new_gtin","znack.registration.confirm_new_gtin",
                    "znack.registration.status.gtin_review_required","znack.registration.register_selected"}) {
                assertTrue(bundle.containsKey(key),language+": "+key);
                assertFalse(bundle.getString(key).isBlank());
            }
        }
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
