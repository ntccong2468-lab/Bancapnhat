package com.vncode.app.ui.znack;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.ShopContext;
import com.vncode.app.integration.znack.ZnackPurchaseMethod;
import com.vncode.app.integration.znack.ZnackPurchasePreferences;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real FXML/persisted preference projection only; does not discover certificates or authenticate. */
class Wb1326PurchasePreferencesUiTest {
    @TempDir Path profile;
    @BeforeAll static void fxStartup() {
        try { Platform.startup(()->Platform.setImplicitExit(false)); } catch(IllegalStateException alreadyStarted) { }
    }

    @Test void purchaseMethodProjectsPerShopAndSurvivesTranslationWithoutAnIntroductionOptOut() throws Exception {
        System.setProperty("vncode.appdata.dir",profile.toString());
        var i18n=I18nService.getInstance();var originalLanguage=i18n.getCurrentLanguage();
        FXMLLoader[] loader=new FXMLLoader[1];ZnackAutomationController[] controller=new ZnackAutomationController[1];
        try {
            Database.initDatabase();
            try(var connection=Database.getConnection();var statement=connection.createStatement()) {
                statement.execute("INSERT INTO shops(id,name,api_key) VALUES(7132691,'Synthetic production','fixture'),(7132692,'Synthetic commission','fixture')");
            }
            var first=new ZnackRepository(new ShopContext(7132691,"Synthetic production"));first.saveSettings(first.getSettings());
            var second=new ZnackRepository(new ShopContext(7132692,"Synthetic commission"));second.saveSettings(second.getSettings());
            new ZnackPurchasePreferences(7132692).saveMethod(ZnackPurchaseMethod.COMMISSION);
            fx(()->{
                loader[0]=FxmlViewLoader.loader(ZnackAutomationController.class,"znack-automation-view.fxml");
                FxmlViewLoader.load(loader[0]);controller[0]=loader[0].getController();
                var methods=(ComboBox<?>)loader[0].getNamespace().get("productionMethodCombo");
                var auto=(CheckBox)loader[0].getNamespace().get("autoIntroductionCheck");
                controller[0].setShop(new Shop(7132691,"Synthetic production","fixture"));
                assertEquals(0,methods.getSelectionModel().getSelectedIndex());assertTrue(methods.isDisabled());
                assertTrue(auto.isSelected());assertFalse(auto.isVisible());assertFalse(auto.isManaged());
                controller[0].setShop(new Shop(7132692,"Synthetic commission","fixture"));
                assertEquals(1,methods.getSelectionModel().getSelectedIndex());
                for(var language:AppLanguage.values()) {
                    i18n.setLanguage(language);controller[0].applyTranslations();
                    assertEquals(2,methods.getItems().size());assertEquals(1,methods.getSelectionModel().getSelectedIndex());
                    assertEquals(i18n.tr("znack.settings.commission"),methods.getValue());
                    assertNotEquals("znack.settings.commission",methods.getValue());
                }
                controller[0].refresh();assertEquals(1,methods.getSelectionModel().getSelectedIndex());
                controller[0].setShop(new Shop(7132691,"Synthetic production","fixture"));
                assertEquals(0,methods.getSelectionModel().getSelectedIndex());return null;
            });
            assertEquals(ZnackPurchaseMethod.PRODUCTION,new ZnackPurchasePreferences(7132691).method());
            assertEquals(ZnackPurchaseMethod.COMMISSION,new ZnackPurchasePreferences(7132692).method());
        } finally {
            if(controller[0]!=null)fx(()->{controller[0].setShop(null);i18n.setLanguage(originalLanguage);return null;});
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            System.clearProperty("vncode.appdata.dir");
        }
    }

    private static <T>T fx(Callable<T> action)throws Exception {
        var result=new FutureTask<>(action);Platform.runLater(result);return result.get(10,TimeUnit.SECONDS);
    }
}
