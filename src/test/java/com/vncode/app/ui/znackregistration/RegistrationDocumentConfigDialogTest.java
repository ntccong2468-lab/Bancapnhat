package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import com.vncode.app.testsupport.FxDialogDriver;
import javafx.application.Platform;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JavaFX dialog and SQLite settings; all shop identities and documents are synthetic. */
class RegistrationDocumentConfigDialogTest {
    @TempDir Path root;
    private final Shop shop=new Shop(1,"Fixture",Marketplace.WILDBERRIES,null,"fixture-token");
    private ZnackRepository repository;

    @BeforeAll static void startFx(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','WILDBERRIES',NULL,'fixture-token'),(2,'Other Fixture','WILDBERRIES',NULL,'other-fixture-token')")){p.executeUpdate();}
        repository=repository(1,"Fixture");
        repository.saveSettings(ZnackModels.Settings.empty().withDefaultGoodsDocument("CONFORMITY_DECLARATION","FIXTURE-1","01.01.2026"));
        repository(2,"Other Fixture").saveSettings(ZnackModels.Settings.empty().withDefaultGoodsDocument("CONFORMITY_DECLARATION","OTHER-1","01.02.2025"));
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_settings SET document_expiry_date=?, oms_id=?, oms_connection=? WHERE shop_id=?")){
            p.setString(1,"31.12.2027");p.setString(2,"synthetic-oms-1");p.setString(3,"synthetic-connection-1");p.setInt(4,1);p.executeUpdate();
            p.setString(1,"31.12.2028");p.setString(2,"synthetic-oms-2");p.setString(3,"synthetic-connection-2");p.setInt(4,2);p.executeUpdate();
        }
    }
    @AfterEach void cleanup()throws Exception{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    private static ZnackRepository repository(int id,String name){return new ZnackRepository(new ZnackModels.ShopContext(id,name));}
    private static TextField field(DialogPane pane,String id){pane.applyCss();pane.layout();var node=pane.lookup("#"+id);assertInstanceOf(TextField.class,node,"Required seller field "+id);return (TextField)node;}
    private static TextField issue(DialogPane pane){return field(pane,"registrationConfiguredDocumentDate");}
    private static TextField expiry(DialogPane pane){return field(pane,"registrationConfiguredDocumentExpiryDate");}
    private static TextField number(DialogPane pane){return field(pane,"registrationConfiguredDocumentNumber");}
    private static ButtonType apply(DialogPane pane){return pane.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();}
    private static void open(ZnackModels.Settings settings,Shop owner,Runnable saved,java.util.function.Function<DialogPane,ButtonType> choice)throws Exception{
        FxDialogDriver.choose(()->RegistrationDocumentConfigDialog.show(null,owner,settings,saved),
                pane->"registrationConfiguredDocumentDialog".equals(pane.getId()),choice);
    }
    private static void saved(CountDownLatch latch)throws Exception{assertTrue(latch.await(10,TimeUnit.SECONDS),"Settings write must complete");com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();}

    @Test void expiryFieldExistsAndPrefillsStoredValue() throws Exception {
        open(repository.getSettings(),shop,()->{},pane->{
            assertEquals("01.01.2026",issue(pane).getText());
            assertEquals("31.12.2027",expiry(pane).getText(),"The seller must see the stored expiry");
            return ButtonType.CANCEL;
        });
    }

    @Test void applyPersistsIsoInputsInCanonicalDottedFormAndReloadsWithoutOverwritingCurrentOmsOrOtherShop()throws Exception{
        var other=repository(2,"Other Fixture");var otherBefore=other.getSettings();var latch=new CountDownLatch(1);
        open(repository.getSettings(),shop,latch::countDown,pane->{
            issue(pane).setText("2026-02-03");expiry(pane).setText("2029-12-31");
            // A connection change while the modal is open must survive the later document-only save.
            try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_settings SET oms_id=?,oms_connection=? WHERE shop_id=1")){
                p.setString(1,"changed-synthetic-oms");p.setString(2,"changed-synthetic-connection");p.executeUpdate();
            }catch(java.sql.SQLException failure){throw new AssertionError(failure);}
            return apply(pane);
        });
        saved(latch);var current=repository.getSettings();assertEquals("03.02.2026",current.documentDate());assertEquals("31.12.2029",current.documentExpiryDate());
        var declaration=com.vncode.app.features.products.ConfiguredProductDeclaration.fromSettings(shop,current);
        assertEquals(java.time.LocalDate.of(2029,12,31),declaration.validUntil());
        assertEquals(com.vncode.app.features.products.DocumentState.UNVERIFIED,declaration.state(),"Saving the date does not verify registry proof");
        assertEquals("changed-synthetic-oms",current.omsId());assertEquals("changed-synthetic-connection",current.omsConnection());assertEquals(otherBefore,other.getSettings());
        open(current,shop,()->{},pane->{assertEquals("03.02.2026",issue(pane).getText());assertEquals("31.12.2029",expiry(pane).getText());return ButtonType.CANCEL;});
    }

    @Test void emptyExpiryRemainsUnknownAfterApplyAndReopen()throws Exception{
        var latch=new CountDownLatch(1);
        open(repository.getSettings(),shop,latch::countDown,pane->{
            expiry(pane).clear();pane.applyCss();pane.layout();
            var note=com.vncode.app.shared.I18nService.getInstance().tr("znack.registration.config_expiry_note");
            assertTrue(pane.lookupAll(".label").stream().anyMatch(n->n instanceof Label label&&label.getText().contains(note)),"The editor must explain blank is unknown");
            return apply(pane);
        });
        saved(latch);var actual=repository.getSettings();assertTrue(actual.documentExpiryDate()==null||actual.documentExpiryDate().isBlank());
        assertEquals("01.01.2026",actual.documentDate());
        open(actual,shop,()->{},pane->{assertEquals("",expiry(pane).getText());return ButtonType.CANCEL;});
    }

    @ParameterizedTest(name="Invalid issue {0}, expiry {1}")
    @CsvSource({"31.02.2026,31.12.2027,znack.registration.invalid_document_date", "01.01.2026,31.02.2027,znack.registration.invalid_document_expiry_date", "01.01.2026,31.12.2025,znack.registration.document_expiry_before_start"})
    void invalidDatesOrExpiryBeforeIssueKeepDialogOpenAndNeverSave(String issueDate,String expiryDate,String messageKey)throws Exception{
        var before=repository.getSettings();var writes=new AtomicInteger();
        open(before,shop,writes::incrementAndGet,pane->{
            issue(pane).setText(issueDate);expiry(pane).setText(expiryDate);
            ((Button)pane.lookupButton(apply(pane))).fire();
            assertTrue(pane.getScene().getWindow().isShowing(),"Invalid values cannot close the editor");
            var expected=com.vncode.app.shared.I18nService.getInstance().tr(messageKey);
            assertTrue(pane.lookupAll(".label").stream().anyMatch(n->n instanceof Label label&&label.getText().contains(expected)),"The editor must identify the failing date or ordering rule");
            assertEquals(before,repository.getSettings(),"Invalid values cannot reach SQLite");
            return ButtonType.CANCEL;
        });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();assertEquals(0,writes.get());assertEquals(before,repository.getSettings());
    }

    @Test void cancelLeavesDocumentAndConnectionUnchanged()throws Exception{
        var before=repository.getSettings();var writes=new AtomicInteger();
        open(before,shop,writes::incrementAndGet,pane->{issue(pane).setText("02.01.2026");expiry(pane).setText("31.12.2029");return ButtonType.CANCEL;});
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();assertEquals(0,writes.get());assertEquals(before,repository.getSettings());
    }

    @Test void changedNumberClearsPreviousExpiryAndExplicitReplacementCanBeSaved()throws Exception{
        var latch=new CountDownLatch(1);
        open(repository.getSettings(),shop,latch::countDown,pane->{
            assertEquals("31.12.2027",expiry(pane).getText());number(pane).setText("FIXTURE-2");
            assertTrue(expiry(pane).getText().isBlank(),"The old permit expiry must not follow a new identity");
            expiry(pane).setText("2030-12-31");return apply(pane);
        });
        saved(latch);var current=repository.getSettings();assertEquals("FIXTURE-2",current.documentNumber());assertEquals("31.12.2030",current.documentExpiryDate());
        open(current,shop,()->{},pane->{assertEquals("31.12.2030",expiry(pane).getText());return ButtonType.CANCEL;});
    }

    @Test void equivalentIdentitySpellingsDoNotEraseExpiry()throws Exception{
        var latch=new CountDownLatch(1);
        open(repository.getSettings(),shop,latch::countDown,pane->{
            number(pane).setText("  FIXTURE-1  ");assertEquals("31.12.2027",expiry(pane).getText());
            issue(pane).setText("2026-01-01");assertEquals("31.12.2027",expiry(pane).getText());
            return apply(pane);
        });
        saved(latch);assertEquals("01.01.2026",repository.getSettings().documentDate());assertEquals("31.12.2027",repository.getSettings().documentExpiryDate());
    }

    @Test void retypingEquivalentIssueDateThroughEmptyAndPartialInputRestoresOriginalExpiry()throws Exception{
        var latch=new CountDownLatch(1);
        open(repository.getSettings(),shop,latch::countDown,pane->{
            var date=issue(pane);date.clear();
            for(char key:"2026-01-01".toCharArray())date.appendText(Character.toString(key));
            assertEquals("31.12.2027",expiry(pane).getText(),"Equivalent identity after normal typing must retain the old expiry");
            return apply(pane);
        });
        saved(latch);assertEquals("01.01.2026",repository.getSettings().documentDate());assertEquals("31.12.2027",repository.getSettings().documentExpiryDate());
    }

    @Test void retypingEquivalentNumberThroughEmptyAndPartialInputRestoresOriginalExpiry()throws Exception{
        var latch=new CountDownLatch(1);
        open(repository.getSettings(),shop,latch::countDown,pane->{
            var documentNumber=number(pane);documentNumber.clear();
            for(char key:" FIXTURE-1 ".toCharArray())documentNumber.appendText(Character.toString(key));
            assertEquals("31.12.2027",expiry(pane).getText(),"Typing an equivalent permit number must retain the old expiry");
            return apply(pane);
        });
        saved(latch);assertEquals("FIXTURE-1",repository.getSettings().documentNumber());assertEquals("31.12.2027",repository.getSettings().documentExpiryDate());
    }

    @Test void explicitlyClearedExpiryIsNotRestoredWhenAnEquivalentIdentityIsRetyped()throws Exception{
        var latch=new CountDownLatch(1);
        open(repository.getSettings(),shop,latch::countDown,pane->{
            expiry(pane).clear();assertEquals("",expiry(pane).getText());
            var date=issue(pane);date.clear();for(char key:"2026-01-01".toCharArray())date.appendText(Character.toString(key));
            assertEquals("",expiry(pane).getText(),"An expiry deliberately deleted by the seller must remain unknown");
            return apply(pane);
        });
        saved(latch);var current=repository.getSettings();assertEquals("01.01.2026",current.documentDate());assertTrue(current.documentExpiryDate()==null||current.documentExpiryDate().isBlank());
    }

    @Test void editedExpiryRemainsWithItsPermitWhenAnEquivalentDateIsRetyped()throws Exception{
        var latch=new CountDownLatch(1);
        open(repository.getSettings(),shop,latch::countDown,pane->{
            expiry(pane).setText("31.12.2029");
            var date=issue(pane);date.clear();for(char key:"2026-01-01".toCharArray())date.appendText(Character.toString(key));
            assertEquals("31.12.2029",expiry(pane).getText(),"Retyping the same identity must preserve the seller's edited expiry, not the original value");
            return apply(pane);
        });
        saved(latch);assertEquals("31.12.2029",repository.getSettings().documentExpiryDate());
    }

    @Test void changingBetweenPermitIdentitiesKeepsSeparateExpiryDrafts()throws Exception{
        var latch=new CountDownLatch(1);
        open(repository.getSettings(),shop,latch::countDown,pane->{
            assertEquals("31.12.2027",expiry(pane).getText());
            number(pane).setText("FIXTURE-2");assertTrue(expiry(pane).getText().isBlank(),"A new identity cannot inherit another permit's expiry");
            expiry(pane).setText("31.12.2030");
            number(pane).setText("FIXTURE-1");assertEquals("31.12.2027",expiry(pane).getText(),"Returning restores only that identity's draft");
            number(pane).setText("FIXTURE-2");assertEquals("31.12.2030",expiry(pane).getText(),"Each identity keeps its own explicitly entered date");
            return apply(pane);
        });
        saved(latch);var current=repository.getSettings();assertEquals("FIXTURE-2",current.documentNumber());assertEquals("31.12.2030",current.documentExpiryDate());
    }

    @Test void clearedExpiryStaysBlankAfterSwitchingToAnotherPermitAndBack()throws Exception{
        var latch=new CountDownLatch(1);
        open(repository.getSettings(),shop,latch::countDown,pane->{
            expiry(pane).clear();number(pane).setText("FIXTURE-2");assertTrue(expiry(pane).getText().isBlank());
            expiry(pane).setText("31.12.2030");number(pane).setText("FIXTURE-1");
            assertEquals("",expiry(pane).getText(),"An explicit deletion is an unknown-date draft, not a cue to restore a stale expiry");
            return apply(pane);
        });
        saved(latch);var current=repository.getSettings();assertEquals("FIXTURE-1",current.documentNumber());assertTrue(current.documentExpiryDate()==null||current.documentExpiryDate().isBlank());
    }

    @Test void changedIssueAndTypeClearThePreviouslyStoredExpiry()throws Exception{
        open(repository.getSettings(),shop,()->{},pane->{
            issue(pane).setText("02.01.2026");assertTrue(expiry(pane).getText().isBlank());
            expiry(pane).setText("31.12.2029");
            @SuppressWarnings("unchecked") var type=(ComboBox<String>)pane.lookup(".combo-box");assertNotNull(type);
            type.setValue("CONFORMITY_CERTIFICATE");assertTrue(expiry(pane).getText().isBlank());return ButtonType.CANCEL;
        });
        assertEquals("31.12.2027",repository.getSettings().documentExpiryDate());
    }
}
