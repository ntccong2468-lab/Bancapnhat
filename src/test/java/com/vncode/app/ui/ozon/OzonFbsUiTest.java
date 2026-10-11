package com.vncode.app.ui.ozon;
import com.vncode.app.config.Database;
import com.vncode.app.features.ozon.*;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonFbsUiTest {
    @TempDir Path root;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> f)throws Exception{var t=new FutureTask<>(f);Platform.runLater(t);return t.get(10,TimeUnit.SECONDS);}
    @Test void oldShopResultCannotUpdateTable()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'First','OZON','11','fixture'),(2,'Second','OZON','22','fixture')");}
        var gate=new CountDownLatch(1);var entered=new CountDownLatch(1);FXMLLoader loader=fx(()->{var l=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");new Scene(l.load(),1024,600);return l;});OzonDashboardController controller=loader.getController();
        try{
            fx(()->{controller.setFbsReader(q->{if(q.shopId()==1){entered.countDown();try{gate.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}return new OzonFbsPage(List.of(new OzonPostingDto("shop-"+q.shopId(),"","",q.status(),"","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of())),1001,Map.of());});controller.setShop(new Shop(1,"First",Marketplace.OZON,"11","fixture"),false);return null;});assertTrue(entered.await(3,TimeUnit.SECONDS));
            fx(()->{controller.setShop(new Shop(2,"Second",Marketplace.OZON,"22","fixture"),false);return null;});gate.countDown();
            for(int i=0;i<500;i++){Thread.sleep(10);if(ShopReadCoordinator.shared().activeCount()==0&&ShopReadCoordinator.shared().pendingCount()==0)break;}fx(()->{var table=(TableView<OzonPostingDto>)loader.getNamespace().get("newOrdersTable");assertEquals("shop-2",table.getItems().getFirst().postingNumber());assertTrue(((Tab)loader.getNamespace().get("newOrdersTab")).getText().contains("1001"));assertEquals(100,controller.currentPageLimit());return null;});
        }finally{gate.countDown();fx(()->{controller.dispose();return null;});for(int i=0;i<500&&(AppTaskExecutor.hasRunningTasks()||ShopReadCoordinator.shared().activeCount()>0);i++)Thread.sleep(10);System.clearProperty("vncode.appdata.dir");}
    }
}
