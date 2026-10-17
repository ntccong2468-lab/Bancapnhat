package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.ProductRow;
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
    @Test void aFailedPairCannotExposeCachedProductsOrGtinActions()throws Exception {
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("UPDATE sync_runs SET state='PARTIAL',error_code='SOURCE_CATALOG_PARTIAL' WHERE kind='registration_sources'");}
        fx(()->{button("refresh").fire();return null;});awaitIdle();fx(()->{assertTrue(table().getItems().isEmpty());for(String action:new String[]{"gtin","sign","register","all"})assertTrue(button(action).isDisabled(),action);assertFalse(button("sync").isDisabled());assertFalse(button("documents").isDisabled());return null;});
    }
    @Test void receiptMembershipExcludesCachedProductNotReturnedByTheCurrentMarketplaceRead()throws Exception {
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(1,'2000','old','20000','Old product','old')");}
        fx(()->{button("refresh").fire();return null;});awaitRows(300);
        fx(()->{for(String name:new String[]{"all","gtin"})assertFalse(button(name).isDisabled());assertTrue(table().getItems().stream().noneMatch(row->row.key().cardId().equals("2000")));return null;});
    }
    private void awaitIdle()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(AppTaskExecutor.hasRunningTasks()&&System.nanoTime()<end)Thread.sleep(10);fx(()->null);assertFalse(AppTaskExecutor.hasRunningTasks());}
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
    @Test void gtinActionStartsTheInjectedBackgroundActionWithoutOpeningTheReportBrowser()throws Exception {
        fx(()->{var action=button("gtin");assertEquals(com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.update_ozon"),action.getText());action.fire();assertEquals(1,gtinOpens.get());return null;});
    }
    @SuppressWarnings("unchecked") private TableView<ProductRow> table(){return (TableView<ProductRow>)pane.lookup("#ozonRegistrationTable");}
    private Button button(String name)throws Exception{var field=OzonRegistrationPane.class.getDeclaredField(name);field.setAccessible(true);return (Button)field.get(pane);}
    private void awaitRows(int count)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(fx(()->table().getItems().size()==count&&!button("more").isDisabled()))return;Thread.sleep(10);}fail("Native Ozon page did not arrive");}
    private static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
}
