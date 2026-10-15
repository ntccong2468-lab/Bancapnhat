package com.vncode.app.ui.catalogsync;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.features.catalogsync.CatalogSyncModels.*;

class CatalogSyncPaneTest {
    @TempDir Path path;CatalogSyncPane pane;CountDownLatch release=new CountDownLatch(1);
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> f)throws Exception{var task=new FutureTask<>(f);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",path.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'First shop','fixture'),(2,'Second shop','fixture')");}
        var repository=new CatalogSyncRepository();for(int id:List.of(1,2)){var shop=new Shop(id,"Shop","fixture");repository.insert(new Job("job-"+id,id,shop.getMarketplace().name(),ListingIdentity.fingerprint(shop),Set.of(Field.GTIN),Instant.now(),false,false,List.of()));}
    }
    @AfterEach void clear()throws Exception{release.countDown();if(pane!=null)fx(()->{pane.dispose();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();fx(()->null);System.clearProperty("vncode.appdata.dir");}
    @Test void changingShopDuringHistoryLoadKeepsFxResponsiveAndDropsOldOwnershipAndApproval()throws Exception {
        var entered=new CountDownLatch(1);
        fx(()->{pane=new CatalogSyncPane(()->{entered.countDown();try{if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("Fixture gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return new CatalogSyncService();});new Scene(pane,1060,700);pane.open(new Shop(1,"First shop","fixture"),List.of());return null;});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        fx(()->{pane.open(new Shop(2,"Second shop","fixture"),List.of());assertTrue(((Button)pane.lookup("#catalogSyncApply")).isDisabled());return null;});
        release.countDown();com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();fx(()->{assertEquals(List.of("job-2"),((ComboBox<?>)pane.lookup("#catalogSyncHistory")).getItems());assertFalse(((CheckBox)pane.lookup("#catalogSyncConfirm")).isSelected());assertTrue(((Button)pane.lookup("#catalogSyncApply")).isDisabled());return null;});
    }
    @Test void disposingAPendingViewCannotPopulateItLater()throws Exception {
        var entered=new CountDownLatch(1);
        fx(()->{pane=new CatalogSyncPane(()->{entered.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return new CatalogSyncService();});new Scene(pane,1060,700);pane.open(new Shop(1,"First shop","fixture"),List.of());return null;});
        assertTrue(entered.await(5,TimeUnit.SECONDS));fx(()->{pane.dispose();return null;});release.countDown();com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        fx(()->{assertTrue(((ComboBox<?>)pane.lookup("#catalogSyncHistory")).getItems().isEmpty());return null;});
    }
}
