package com.vncode.app.ui.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.features.ozon.OzonFbsPage;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonSyncWorkflow;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Real FBS entry and owner scope, without registering a card or calling a seller API. */
class OzonRegistrationEntryUiTest {
    @TempDir Path profile;
    private String previousProfile;
    private AppLanguage previousLanguage;
    private FXMLLoader loader;
    private OzonDashboardController controller;
    private final AtomicInteger remoteCalls=new AtomicInteger();
    private final Shop first=new Shop(1,"Synthetic Ozon A",Marketplace.OZON,"11","fixture-a");
    private final Shop second=new Shop(2,"Synthetic Ozon B",Marketplace.OZON,"22","fixture-b");

    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException alreadyStarted){}}
    private static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<T>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
    @BeforeEach void initialize()throws Exception{
        previousProfile=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic Ozon A','OZON','11','fixture-a'),(2,'Synthetic Ozon B','OZON','22','fixture-b'),(3,'Synthetic WB','WILDBERRIES','','fixture-wb')");
        }
        loader=fx(()->{
            previousLanguage=I18nService.getInstance().getCurrentLanguage();I18nService.getInstance().setLanguage(AppLanguage.VI);
            var fxml=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");
            fxml.setControllerFactory(type->new OzonDashboardController(new OzonSyncWorkflow(owner->{remoteCalls.incrementAndGet();throw new AssertionError("Registration entry must not synchronize or write remotely");})));
            new Scene(fxml.load(),1024,600);return fxml;
        });
        controller=loader.getController();
        fx(()->{controller.setFbsReader(query->new OzonFbsPage(List.of(),0,Map.of()));return null;});
    }
    @AfterEach void cleanup()throws Exception{
        if(controller!=null)fx(()->{controller.dispose();return null;});
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        if(previousLanguage!=null)fx(()->{I18nService.getInstance().setLanguage(previousLanguage);return null;});
        if(previousProfile==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previousProfile);
    }
    private Button entry(){var button=(Button)loader.getNamespace().get("ozonZnackRegistrationButton");assertNotNull(button,"Ozon FBS needs a discoverable entry into its existing Znack registration workflow");return button;}
    private void callback(Consumer<Shop> open)throws Exception{OzonDashboardController.class.getMethod("setOnZnackRegistration",Consumer.class).invoke(controller,open);}

    @Test void entryUsesCurrentFrozenOzonOwnerWithoutRemoteWork()throws Exception{
        var opened=new AtomicReference<Shop>();
        fx(()->{
            var button=entry();assertEquals(I18nService.getInstance().tr("sidebar.znack_registration"),button.getText());
            callback(opened::set);controller.setShop(first,false);assertFalse(button.isDisabled());button.fire();
            assertNotNull(opened.get());assertEquals(first.getId(),opened.get().getId());assertNotSame(first,opened.get(),"Navigation receives an immutable-in-time copy of the chosen shop");
            controller.setShop(second,false);button.fire();assertEquals(second.getId(),opened.get().getId());
            return null;
        });
        assertEquals(0,remoteCalls.get());
        try(var c=Database.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT count(*) FROM ozon_card_registrations")){assertEquals(0,rows.getInt(1),"Opening registration must not create or pay for cards");}
    }

    @Test void noShopOrWbContextCannotStartOzonRegistration()throws Exception{
        var opened=new AtomicInteger();
        fx(()->{
            var button=entry();callback(owner->opened.incrementAndGet());assertTrue(button.isDisabled());
            assertTrue(button.getAccessibleText().contains(I18nService.getInstance().tr("workspace.error.select_shop")));
            button.fire();controller.setShop(new Shop(3,"Synthetic WB",Marketplace.WILDBERRIES,"","fixture-wb"),false);assertTrue(button.isDisabled());button.fire();
            assertEquals(0,opened.get());return null;
        });
    }

    @Test void rotatedOrDeletedShopCannotDispatchTheSavedEntry()throws Exception{
        var opened=new AtomicInteger();
        fx(()->{entry();callback(owner->opened.incrementAndGet());controller.setShop(first,false);return null;});
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='fixture-rotated' WHERE id=1");}
        fx(()->{entry().fire();assertEquals(0,opened.get());assertFalse(((Label)loader.getNamespace().get("statusLabel")).getText().isBlank());return null;});
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM shops WHERE id=1");}
        fx(()->{entry().fire();assertEquals(0,opened.get());return null;});
    }

    @Test void missingHostBusyOrDisposedViewCannotDispatchRegistration()throws Exception{
        var opened=new AtomicInteger();
        fx(()->{
            var button=entry();controller.setShop(first,false);assertTrue(button.isDisabled(),"A detached FBS page must not silently discard navigation");
            callback(owner->opened.incrementAndGet());var busy=OzonDashboardController.class.getDeclaredMethod("setBusy",boolean.class);busy.setAccessible(true);
            busy.invoke(controller,true);assertTrue(button.isDisabled());button.fire();busy.invoke(controller,false);assertFalse(button.isDisabled());
            controller.dispose();button.fire();assertEquals(0,opened.get());return null;
        });
    }

    @Test void registrationEntryKeepsTheExistingSidebarNavigationSelectorUnique()throws Exception{
        fx(()->{
            javafx.scene.Parent dashboard=loader.getRoot();dashboard.getScene().setRoot(new javafx.scene.layout.StackPane());
            javafx.scene.Parent sidebar=FxmlViewLoader.load(FxmlViewLoader.loader(com.vncode.app.ui.shop.ShopSidebarController.class,"shop-sidebar-view.fxml"));
            var shell=new javafx.scene.layout.HBox(sidebar,dashboard);var scene=new Scene(shell,1280,720);
            com.vncode.app.shared.ThemeService.applyTheme(scene);shell.applyCss();shell.layout();
            var existingNavigation=shell.lookupAll("#znackRegistrationButton");
            assertEquals(1,existingNavigation.size(),"The FBS entry must not shadow the existing sidebar navigation ID");
            assertSame(sidebar.lookup("#znackRegistrationButton"),existingNavigation.iterator().next());
            return null;
        });
    }

    @Test void sidebarOffersNativeRegistrationForOzonAndKeepsWbAvailability()throws Exception{
        fx(()->{
            var view=FxmlViewLoader.loader(com.vncode.app.ui.shop.ShopSidebarController.class,"shop-sidebar-view.fxml");view.load();
            com.vncode.app.ui.shop.ShopSidebarController sidebar=view.getController();
            var registration=(Button)view.getNamespace().get("znackRegistrationButton");var opens=new AtomicInteger();sidebar.setOnZnackRegistration(opens::incrementAndGet);
            var unchanged=List.of("packingButton","fboPackingButton","fboOrdersButton","kizMappingButton");
            sidebar.setMarketplace(Marketplace.OZON);
            assertTrue(registration.isVisible(),"Ozon already has a native registration workflow; its sidebar entry must be discoverable");
            assertTrue(registration.isManaged());assertFalse(registration.isDisabled());registration.fire();assertEquals(1,opens.get());
            for(var id:unchanged){var button=(Button)view.getNamespace().get(id);assertTrue(button.isVisible());assertTrue(button.isManaged());assertFalse(button.isDisabled());}
            sidebar.setMarketplace(Marketplace.WILDBERRIES);assertTrue(registration.isVisible());assertTrue(registration.isManaged());assertFalse(registration.isDisabled());registration.fire();assertEquals(2,opens.get());
            for(var id:unchanged){var button=(Button)view.getNamespace().get(id);assertTrue(button.isVisible());assertTrue(button.isManaged());assertFalse(button.isDisabled());}
            return null;
        });
    }
}
