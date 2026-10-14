package com.vncode.app.ui.products;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait;

class ProductBulkEditPaneTest {
    @TempDir Path root;ProductBulkEditPane pane;ShopReadCoordinator reads;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> f)throws Exception{var t=new FutureTask<>(f);Platform.runLater(t);return t.get(10,TimeUnit.SECONDS);}
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Owner','fixture','WILDBERRIES'),(2,'Other','fixture','WILDBERRIES')");}reads=new ShopReadCoordinator(2,64);fx(()->{pane=new ProductBulkEditPane(new ProductBulkEditService(),reads);new Scene(pane,1024,600);return null;});}
    @AfterEach void clear()throws Exception{fx(()->{pane.dispose();return null;});closeAndAwait(reads);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    @Test void shopChangeDropsSelectionAndApproval()throws Exception{fx(()->{pane.open(new Shop(1,"Owner","fixture"),"brand",List.of(new ProductKey(1,Marketplace.WILDBERRIES,"123","1")));assertEquals(1,pane.frozenKeys().size());((CheckBox)pane.lookup("#editConfirm")).setSelected(true);pane.setShop(new Shop(2,"Other","fixture"));assertTrue(pane.frozenKeys().isEmpty());assertFalse(((CheckBox)pane.lookup("#editConfirm")).isSelected());assertTrue(((Button)pane.lookup("#editApply")).isDisabled());return null;});}
    @Test void fieldsAndInlineLookupTranslateInAllLocales()throws Exception{var i=I18nService.getInstance();var old=i.getCurrentLanguage();try{for(var locale:AppLanguage.values())fx(()->{i.setLanguage(locale);pane.open(new Shop(1,"Owner","fixture"),"tnved",List.of(new ProductKey(1,Marketplace.WILDBERRIES,"123","1")));pane.applyTranslations();pane.applyCss();pane.layout();assertEquals(i.tr("edit.preview"),((Button)pane.lookup("#editPreview")).getText());assertFalse(((Button)pane.lookup("#editLookup")).getText().startsWith("edit."));assertTrue(pane.lookup("#editLookup").isVisible());assertTrue(((Button)pane.lookup("#editApply")).isDisabled());return null;});}finally{fx(()->{i.setLanguage(old);return null;});}}
}
