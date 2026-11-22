package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.integration.znack.registration.RegistrationSourceConsent;
import com.vncode.app.shared.I18nService;
import com.vncode.app.testsupport.FxDialogDriver;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Native source review with synthetic provenance only; cancelling never grants signing consent. */
class RegistrationSourceSigningModeTest {
    @TempDir Path root;
    @BeforeAll static void startFx(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();}
    @AfterEach void cleanup()throws Exception{ZnackCardRegistrationControllerTest.awaitTasks();com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}

    @ParameterizedTest @CsvSource({"WILDBERRIES,true","WILDBERRIES,false","OZON,true","OZON,false"})
    void reviewShowsTheExactBatchSigningChoiceWithoutChangingSettingsOrGrantingConsent(Marketplace market,boolean automatic)throws Exception{
        var shop=GtinWriteTestFixture.shop(market);var key=new ProductKey(shop.getId(),market,"101","1");
        var review=ZnackCardRegistrationControllerTest.sourceReviewFixture(shop,List.of(key));
        var repository=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));var before=repository.getSettings();
        var result=new AtomicReference<RegistrationSourceConsent.Prepared>();
        FxDialogDriver.choose(()->result.set(RegistrationSourceReviewDialog.show(null,shop,UUID.randomUUID(),review.dynamic(),Map.of(key,RegistrationSourceConsent.forIdentity("",null)),automatic)),
                pane->"registrationSourceReviewDialog".equals(pane.getId()),pane->{
                    var mode=(Label)pane.lookup("#registrationSourceAutoSign");assertNotNull(mode);assertTrue(mode.isVisible()&&mode.isManaged());
                    assertEquals(I18nService.getInstance().tr(automatic?"znack.source_review.auto_sign_enabled":"znack.source_review.auto_sign_disabled"),mode.getText());
                    assertFalse(mode.getText().startsWith("znack."),"The signing mode must be readable copy, not an untranslated key");
                    assertFalse(((CheckBox)pane.lookup("#registrationSourceConfirm")).isSelected());assertTrue(((Button)pane.lookup("#registrationSourceReviewApply")).isDisabled());
                    return pane.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
                });
        assertNull(result.get());assertEquals(before,repository.getSettings());
        try(var connection=Database.getConnection();var statement=connection.createStatement();
            var rows=statement.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' AND name='znack_source_consents'")){
            assertFalse(rows.next(),"Cancelling must not create the batch consent table in a fresh profile");
        }
    }
}
