package com.vncode.app.ui.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizWarehouseRepository;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KizWarehousePaneTest {
    @TempDir Path path;KizWarehousePane pane;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> f)throws Exception{var task=new FutureTask<>(f);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",path.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture shop','fixture'),(2,'Other fixture','fixture')");}
        fx(()->{pane=new KizWarehousePane();new Scene(pane,1060,700);return null;});
    }
    @AfterEach void clear()throws Exception{awaitTasks();fx(()->{pane.setShop(null);return null;});System.clearProperty("vncode.appdata.dir");}
    static void awaitTasks()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(AppTaskExecutor.getRunningTaskCount()>0&&System.nanoTime()<until)Thread.sleep(10);assertEquals(0,AppTaskExecutor.getRunningTaskCount());fx(()->null);}
    @Test void shopSwitchResetsConsentAndNeverWritesThePreviousShopSetting()throws Exception{
        new KizWarehouseRepository().autoPurchase(new Shop(1,"Fixture shop","fixture"),true);
        fx(()->{pane.setShop(new Shop(1,"Fixture shop","fixture"));pane.reload();return null;});awaitTasks();
        fx(()->{assertTrue(((CheckBox)pane.lookup("#kiz-warehouse-auto-purchase")).isSelected());pane.setShop(new Shop(2,"Other fixture","fixture"));assertFalse(((CheckBox)pane.lookup("#kiz-warehouse-auto-purchase")).isSelected());pane.reload();return null;});awaitTasks();
        assertTrue(new KizWarehouseRepository().autoPurchase(new Shop(1,"Fixture shop","fixture")));assertFalse(new KizWarehouseRepository().autoPurchase(new Shop(2,"Other fixture","fixture")));
    }
    @Test void controlsAndPlaceholderTranslateWithoutEnablingImportForAnUnavailableShop()throws Exception{
        var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
        try{for(var language:AppLanguage.values())fx(()->{i18n.setLanguage(language);pane.applyTranslations();pane.applyCss();pane.layout();assertEquals(i18n.tr("kiz.warehouse.import"),((Button)pane.lookup("#kiz-warehouse-import")).getText());assertTrue(((Button)pane.lookup("#kiz-warehouse-import")).isDisabled());assertEquals(i18n.tr("kiz.warehouse.empty"),((Label)((TableView<?>)pane.lookup("#kiz-warehouse-table")).getPlaceholder()).getText());return null;});}
        finally{fx(()->{i18n.setLanguage(previous);return null;});}
    }
}
