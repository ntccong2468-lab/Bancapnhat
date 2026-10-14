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
        pane=fx(()->new OzonRegistrationPane(new Shop(1,"Ozon fixture",Marketplace.OZON,"fixture","synthetic"),()->{}));
        awaitRows(300);
    }
    @AfterEach void cleanup()throws Exception {
        fx(()->{pane.dispose();return null;});
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(AppTaskExecutor.hasRunningTasks()&&System.nanoTime()<end)Thread.sleep(10);
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }
    @Test void openingNativeOzonRegistrationPagesLocallyWithoutAllocatingOrSigningAnything()throws Exception {
        fx(()->{for(var row:table().getItems()){assertEquals(1,row.key().shopId());assertEquals(Marketplace.OZON,row.key().marketplace());assertEquals("offer-"+row.key().cardId(),row.key().variantId());}return null;});
        try(var c=Database.getConnection();var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT COUNT(*) FROM ozon_card_registrations")){assertEquals(0,r.getInt(1));}
            try(var r=s.executeQuery("SELECT COUNT(*) FROM znack_card_registrations")){assertEquals(0,r.getInt(1));}
        }
    }
    @Test void loadMoreUsesTheNativeCursorAndReachesAllThousandRowsWithoutDuplicateKeys()throws Exception {
        for(int count:new int[]{600,900,1000}){fx(()->{button("more").fire();return null;});awaitRows(count);}
        fx(()->{assertEquals(1000,table().getItems().stream().map(ProductRow::key).distinct().count());assertFalse(button("more").isVisible());return null;});
    }
    @SuppressWarnings("unchecked") private TableView<ProductRow> table(){return (TableView<ProductRow>)pane.lookup("#ozonRegistrationTable");}
    private Button button(String name)throws Exception{var field=OzonRegistrationPane.class.getDeclaredField(name);field.setAccessible(true);return (Button)field.get(pane);}
    private void awaitRows(int count)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(fx(()->table().getItems().size()==count&&!button("more").isDisabled()))return;Thread.sleep(10);}fail("Native Ozon page did not arrive");}
    private static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
}
