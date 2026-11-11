package com.vncode.app.ui.products;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.ShopReadCoordinator;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait;

class ProductRefreshUiTest {
    @TempDir Path root;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> f)throws Exception{var task=new FutureTask<>(f);Platform.runLater(task);return task.get(5,TimeUnit.SECONDS);}
    @Test void syncSummarySeparatesMarketplaceProductsFromUnreadDetails()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture','WILDBERRIES','fixture')");
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,synced_at) VALUES(1,11,'One','now'),(1,12,'Two','now'),(1,13,'Three','now')");
        }
        var owner=new Shop(1,"Fixture","fixture");var readsAttempted=new AtomicInteger();
        var service=new ProductRefreshService(s->3,(s,k)->{
            readsAttempted.incrementAndGet();if(k.cardId().equals("13"))throw new java.io.IOException("token=synthetic-private-diagnostic");
            return new ProductSnapshot(k,"live:fixture-"+k.cardId(),"Title","Details","Brand","article","9",List.of(),List.of(),List.of(),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(s)));
        },new ProductRepository());
        try(var reads=new ShopReadCoordinator(2,16)){
            var pane=fx(()->{var p=new ProductsPane(null,reads,service);new Scene(p,720,600);p.setShop(owner);return p;});
            try{
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(fx(()->((Button)pane.lookup("#productSync")).isDisabled())&&System.nanoTime()<end)Thread.sleep(10);
                fx(()->{((Button)pane.lookup("#productSync")).fire();return null;});
                end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while((readsAttempted.get()<3||reads.activeCount()>0||reads.pendingCount()>0||fx(()->((Button)pane.lookup("#productSync")).isDisabled()))&&System.nanoTime()<end)Thread.sleep(10);
                fx(()->{
                    assertEquals(3,readsAttempted.get());
                    var text=((Label)pane.lookup("#productStatus")).getText();
                    assertTrue(text.contains("3"),"The summary must retain the full marketplace product count: "+text);
                    assertTrue(text.contains("2/3"),"The detail progress must show successful reads against all products: "+text);
                    assertEquals(3,((ListView<?>)pane.lookup("#productRows")).getItems().size(),"An unread detail is not a missing marketplace product");
                    pane.applyCss();pane.layout();
                    var statusBounds=pane.lookup("#productSyncStatusRow").localToScene(pane.lookup("#productSyncStatusRow").getBoundsInLocal());
                    var listBounds=pane.lookup("#productRows").localToScene(pane.lookup("#productRows").getBoundsInLocal());
                    assertTrue(statusBounds.getMaxY()<=listBounds.getMinY()+.5,"Wrapped status must not cover the product list");
                    var details=(Button)pane.lookup("#productSyncDetails");assertTrue(details.isVisible());assertFalse(details.getAccessibleText().isBlank());details.fire();
                    var window=javafx.stage.Window.getWindows().stream().filter(javafx.stage.Window::isShowing).filter(w->w.getScene().lookup("#productSyncFailureDetails")!=null).findFirst().orElseThrow();
                    try{var area=(TextArea)window.getScene().lookup("#productSyncFailureDetails");assertTrue(area.getText().contains("13"));assertFalse(area.getText().contains("synthetic-private-diagnostic"));assertFalse(area.isEditable());}
                    finally{window.hide();}
                    return null;
                });
            }finally{fx(()->{pane.dispose();return null;});closeAndAwait(reads);}
        }finally{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    @Test void credentialRotationRefreshesContextWithTheSameShopId()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'First','WILDBERRIES','fixture')");}
        var calls=new AtomicInteger();try(var reads=new ShopReadCoordinator(2,16)){
            var pane=fx(()->{var p=new ProductsPane(q->{calls.incrementAndGet();return new ProductPage(List.of(),0,null);},reads);new Scene(p,1024,600);p.setShop(new Shop(1,"First","fixture"));return p;});
            try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(calls.get()<1&&System.nanoTime()<end)Thread.sleep(10);assertEquals(1,calls.get());
                fx(()->{pane.setShop(new Shop(1,"First","rotated"));return null;});end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(calls.get()<2&&System.nanoTime()<end)Thread.sleep(10);assertEquals(2,calls.get());
            }finally{fx(()->{pane.dispose();return null;});closeAndAwait(reads);}
        }finally{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
    @Test void syncButtonUsesBackgroundRefreshAndOldOwnerCompletionDoesNotReplaceNewShop()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'First','WILDBERRIES','fixture'),(2,'Second','WILDBERRIES','fixture')");s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,synced_at) VALUES(1,123,'Title','now')");}
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();var shop=new Shop(1,"First","fixture");
        var service=new ProductRefreshService(s->{calls.incrementAndGet();entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException(e);}return 1;},(s,k)->new ProductSnapshot(k,"live:original","Title","Details","VN","article","9",List.of(),List.of(),List.of(),null,List.of(new ProductDocument("doc","2","DOC",null,LocalDate.of(2028,1,1),"","",null,Marketplace.WILDBERRIES,DocumentState.VALID,"https://content-api.wildberries.ru/content/v2/get/cards/list",Instant.now())),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(s))),new ProductRepository());
        try(var reads=new ShopReadCoordinator(2,16)){
            var pane=fx(()->{var p=new ProductsPane(null,reads,service);new Scene(p,1024,600);p.setShop(shop);return p;});
            try{
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(fx(()->((Button)pane.lookup("#productSync")).isDisabled())&&System.nanoTime()<end)Thread.sleep(10);
                assertEquals(0,calls.get());fx(()->{((Button)pane.lookup("#productSync")).fire();return null;});assertTrue(entered.await(3,TimeUnit.SECONDS));
                fx(()->{pane.setShop(new Shop(2,"Second","fixture"));return null;});release.countDown();
                end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while((reads.activeCount()>0||reads.pendingCount()>0)&&System.nanoTime()<end)Thread.sleep(10);
                fx(()->{assertTrue(((ListView<?>)pane.lookup("#productRows")).getItems().isEmpty());assertEquals("",((Label)pane.lookup("#productStatus")).getText());assertFalse(pane.lookup("#productSyncDetails").isVisible());return null;});
                assertEquals(1,calls.get());assertEquals(1,new ProductRepository().loadPage(new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),DocumentState.VALID,false,null,100)).total());
            }finally{release.countDown();fx(()->{pane.dispose();return null;});closeAndAwait(reads);}
        }finally{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
}
