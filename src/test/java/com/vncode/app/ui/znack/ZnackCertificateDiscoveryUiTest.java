package com.vncode.app.ui.znack;

import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.Settings;
import com.vncode.app.integration.znack.ZnackModels.ShopContext;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.integration.znack.signature.CryptoProCertificateInfo;
import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import com.vncode.app.integration.znack.signature.CryptoProException;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.testsupport.ShopReadTestCleanup;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Native controller with synthetic discovery only; never reads a real certificate store or signs. */
class ZnackCertificateDiscoveryUiTest {
    private static final String OLD="A".repeat(40), RENEWED="B".repeat(40), INN="7707083893";
    @TempDir Path profile;
    private FXMLLoader loader;
    private ZnackAutomationController controller;
    private ZnackRepository repository;
    private Settings saved;

    @BeforeAll static void startFx() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch(IllegalStateException started) {}
    }

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",profile.toString());
        Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Certificate fixture','fixture')");
        }
        repository=new ZnackRepository(new ShopContext(1,"Certificate fixture"));
        repository.saveSettings(repository.getSettings());
        var old=certificate(OLD);
        var metadata=new JsonObject();
        metadata.addProperty("selector",OLD);metadata.addProperty("thumbprint",OLD);
        metadata.addProperty("subject",old.subject());metadata.addProperty("issuer",old.issuer());
        metadata.addProperty("inn",INN);metadata.addProperty("hasPrivateKey",true);
        metadata.addProperty("provider","CryptoPro CMS signer");
        metadata.addProperty("validFrom",old.validFrom().toString());metadata.addProperty("validTo",old.validTo().toString());
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_settings SET participant_inn=?,signer_certificate=?,certificate_metadata_json=?,signer_tested_at=? WHERE shop_id=1")) {
            p.setString(1,INN);p.setString(2,OLD);p.setString(3,metadata.toString());
            p.setString(4,Instant.now().minusSeconds(60).toString());p.executeUpdate();
        }
        saved=repository.getSettings();
        fx(() -> {
            loader=FxmlViewLoader.loader(ZnackAutomationController.class,"znack-automation-view.fxml");
            FxmlViewLoader.load(loader);controller=loader.getController();
            controller.certificateReader(settings -> { throw new AssertionError("Fixture must supply discovery"); });
            controller.signerChangeConfirmation(() -> { throw new AssertionError("Fixture must supply signer-change confirmation"); });
            controller.signatureTaskSubmitter((task,rejected) -> { throw new AssertionError("Fixture must not execute real certificate signing"); });
            controller.setShop(new Shop(1,"Certificate fixture","fixture"));
            return null;
        });
    }

    @AfterEach void cleanup() throws Exception {
        if(controller!=null)fx(() -> { controller.setShop(null);return null; });
        ShopReadTestCleanup.awaitSharedIdle();
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void enteringEditDiscoversNewCertificatesWithoutChangingTheSavedSigner() throws Exception {
        var reads=new AtomicInteger();
        fx(() -> {
            controller.certificateReader(settings -> {
                assertFalse(Platform.isFxApplicationThread(),"Certificate discovery must run off the UI thread");
                assertEquals(saved,settings);reads.incrementAndGet();
                return List.of(certificate(OLD),certificate(RENEWED));
            });
            assertTrue(certificates().isDisabled());
            assertEquals(List.of(OLD),certificates().getItems().stream().map(CryptoProCertificateInfo::selector).toList());
            node("editSettingsButton",Button.class).fire();
            assertFalse(certificates().isDisabled());
            return null;
        });
        ShopReadTestCleanup.awaitSharedIdle();
        fx(() -> {
            assertEquals(1,reads.get());
            assertEquals(List.of(OLD,RENEWED),certificates().getItems().stream().map(CryptoProCertificateInfo::selector).toList());
            assertEquals(OLD,certificates().getValue().selector(),"A renewal must not silently replace the selected certificate");
            assertEquals(I18nService.getInstance().tr("znack.signature.verified"),node("signatureSummaryLabel",Label.class).getText());
            assertEquals(saved,repository.getSettings(),"Discovery cannot persist or authenticate a different signing identity");
            return null;
        });
    }

    @Test void explicitlySelectedRenewalSurvivesDropdownRefreshWithoutInheritingVerification() throws Exception {
        var reads=new AtomicInteger();
        fx(() -> {
            controller.certificateReader(settings -> List.of(certificate(OLD),
                    certificate(reads.incrementAndGet()==1?RENEWED:" b ".repeat(40))));
            node("editSettingsButton",Button.class).fire();return null;
        });
        ShopReadTestCleanup.awaitSharedIdle();
        fx(() -> {
            certificates().setValue(certificates().getItems().stream().filter(c -> RENEWED.equals(c.selector())).findFirst().orElseThrow());
            assertEquals(I18nService.getInstance().tr("znack.signature.not_verified"),node("signatureSummaryLabel",Label.class).getText());
            certificates().getOnShowing().handle(new Event(ComboBoxBase.ON_SHOWING));
            return null;
        });
        ShopReadTestCleanup.awaitSharedIdle();
        fx(() -> {
            assertEquals(2,reads.get(),"Opening the dropdown must still refresh the store");
            assertNotNull(certificates().getValue());
            assertEquals(" b ".repeat(40),certificates().getValue().selector());
            assertEquals(I18nService.getInstance().tr("znack.signature.not_verified"),node("signatureSummaryLabel",Label.class).getText());
            assertTrue(node("saveButton",Button.class).isDisabled(),"The renewed certificate still needs real verification");
            assertEquals(saved,repository.getSettings());return null;
        });
    }

    @Test void missingConfiguredCertificateDoesNotAutomaticallySelectItsRenewal() throws Exception {
        var reads=new AtomicInteger();
        fx(() -> {
            controller.certificateReader(settings -> { reads.incrementAndGet();return List.of(certificate(RENEWED)); });
            node("editSettingsButton",Button.class).fire();return null;
        });
        ShopReadTestCleanup.awaitSharedIdle();
        fx(() -> {
            assertEquals(List.of(RENEWED),certificates().getItems().stream().map(CryptoProCertificateInfo::selector).toList());
            assertNull(certificates().getValue(),"Same enterprise INN is not permission to select a different thumbprint");
            assertTrue(node("saveButton",Button.class).isDisabled());
            assertTrue(node("testSignatureButton",Button.class).isDisabled());
            assertEquals(saved,repository.getSettings());return null;
        });
        fx(() -> { certificates().getOnShowing().handle(new Event(ComboBoxBase.ON_SHOWING));return null; });
        ShopReadTestCleanup.awaitSharedIdle();
        fx(() -> {
            assertEquals(2,reads.get());
            assertNull(certificates().getValue(),"Refreshing again cannot turn a missing saved signer into permission to choose its renewal");
            assertTrue(node("saveButton",Button.class).isDisabled());
            assertEquals(saved,repository.getSettings());return null;
        });
    }

    @Test void delayedEditDiscoveryCannotReplaceTheNextShopsSelectedCertificate() throws Exception {
        String next="C".repeat(40);
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(2,'Second certificate fixture','fixture')");
        }
        var nextRepository=new ZnackRepository(new ShopContext(2,"Second certificate fixture"));
        nextRepository.saveSettings(nextRepository.getSettings());
        var nextMetadata=com.google.gson.JsonParser.parseString(saved.certificateMetadataJson()).getAsJsonObject();
        nextMetadata.addProperty("selector",next);nextMetadata.addProperty("thumbprint",next);
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_settings SET participant_inn=?,signer_certificate=?,certificate_metadata_json=?,signer_tested_at=? WHERE shop_id=2")) {
            p.setString(1,INN);p.setString(2,next);p.setString(3,nextMetadata.toString());
            p.setString(4,saved.signerTestedAt().toString());p.executeUpdate();
        }
        Settings nextSaved=nextRepository.getSettings();
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        fx(() -> {
            controller.certificateReader(settings -> {
                started.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError(interrupted); }
                return List.of(certificate(OLD),certificate(RENEWED));
            });
            node("editSettingsButton",Button.class).fire();return null;
        });
        try {
            assertTrue(started.await(5,TimeUnit.SECONDS));
            fx(() -> { controller.setShop(new Shop(2,"Second certificate fixture","fixture"));return null; });
        } finally { release.countDown(); }
        ShopReadTestCleanup.awaitSharedIdle();
        fx(() -> {
            assertEquals(List.of(next),certificates().getItems().stream().map(CryptoProCertificateInfo::selector).toList());
            assertEquals(next,certificates().getValue().selector());
            assertEquals(nextSaved,nextRepository.getSettings());
            assertEquals(saved,repository.getSettings());return null;
        });
    }

    @Test void cancellingEditDiscardsItsDelayedDiscoveryWithoutChangingTheSavedCertificate() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        fx(() -> {
            controller.certificateReader(settings -> {
                started.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError(interrupted); }
                return List.of(certificate(RENEWED));
            });
            node("editSettingsButton",Button.class).fire();return null;
        });
        try {
            assertTrue(started.await(5,TimeUnit.SECONDS));
            fx(() -> {
                node("editSettingsButton",Button.class).fire();
                assertTrue(certificates().isDisabled());
                assertEquals(OLD,certificates().getValue().selector());return null;
            });
        } finally { release.countDown(); }
        ShopReadTestCleanup.awaitSharedIdle();
        fx(() -> {
            assertEquals(List.of(OLD),certificates().getItems().stream().map(CryptoProCertificateInfo::selector).toList());
            assertEquals(OLD,certificates().getValue().selector());
            assertTrue(certificates().isDisabled());
            assertEquals(I18nService.getInstance().tr("znack.signature.verified"),node("signatureSummaryLabel",Label.class).getText());
            assertEquals(saved,repository.getSettings());return null;
        });
    }

    @Test void cancellingDiscoveryInterruptsOnlyItsReadAndReopeningEditCanReadFreshCertificates() throws Exception {
        var started=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        var staleRelease=new CountDownLatch(1);var freshStarted=new CountDownLatch(1);var freshRelease=new CountDownLatch(1);
        var reads=new AtomicInteger();
        fx(() -> {
            controller.certificateReader(settings -> {
                if(reads.incrementAndGet()==1) {
                    started.countDown();
                    try { assertTrue(staleRelease.await(5,TimeUnit.SECONDS)); }
                    catch(InterruptedException cancelled) {
                        interrupted.countDown();Thread.currentThread().interrupt();
                        throw new CryptoProException(CryptoProErrorCode.CANCELLED,"Synthetic discovery cancelled");
                    }
                    throw new AssertionError("Cancelled discovery must be interrupted before its release");
                }
                freshStarted.countDown();
                try { assertTrue(freshRelease.await(5,TimeUnit.SECONDS)); }
                catch(InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new CryptoProException(CryptoProErrorCode.CANCELLED,"Synthetic fresh discovery cancelled");
                }
                return List.of(certificate(OLD),certificate(RENEWED));
            });
            node("editSettingsButton",Button.class).fire();return null;
        });
        try {
            assertTrue(started.await(5,TimeUnit.SECONDS));
            fx(() -> {
                node("editSettingsButton",Button.class).fire();
                node("editSettingsButton",Button.class).fire();return null;
            });
            assertTrue(interrupted.await(5,TimeUnit.SECONDS),"Cancel must release the stale read worker");
            assertTrue(freshStarted.await(5,TimeUnit.SECONDS),"The next Edit must start a new read");
            assertEquals(1,staleRelease.getCount(),"A fresh read must not wait for the cancelled reader to be released");
            fx(() -> {
                assertFalse(certificates().isDisabled());
                assertTrue(node("testSignatureButton",Button.class).isDisabled());
                assertEquals(I18nService.getInstance().tr("znack.signature.loading"),node("signatureSummaryLabel",Label.class).getText(),
                        "The old cancellation cannot clear the new discovery's busy state");
                return null;
            });
        } finally { staleRelease.countDown();freshRelease.countDown(); }
        ShopReadTestCleanup.awaitSharedIdle();
        fx(() -> {
            assertEquals(2,reads.get());
            assertEquals(List.of(OLD,RENEWED),certificates().getItems().stream().map(CryptoProCertificateInfo::selector).toList());
            assertEquals(OLD,certificates().getValue().selector());
            assertFalse(node("testSignatureButton",Button.class).isDisabled());
            assertEquals(saved,repository.getSettings());return null;
        });
    }

    @Test void cancellingSignerChangeStartsNoSigningTaskAndKeepsStoredData() throws Exception {
        prepareRenewalSelection();
        var confirmations=new AtomicInteger();
        fx(() -> {
            controller.certificateReader(settings -> { throw new AssertionError("Confirmation cannot read a certificate store"); });
            controller.signerChangeConfirmation(() -> { confirmations.incrementAndGet();return false; });
            assertEquals(0,AppTaskExecutor.getRunningTaskCount());
            node("testSignatureButton",Button.class).fire();
            assertEquals(1,confirmations.get());
            assertEquals(0,AppTaskExecutor.getRunningTaskCount());
            assertFalse(node("testSignatureButton",Button.class).isDisabled());
            assertEquals(saved,repository.getSettings());assertTrue(repository.findLogs().isEmpty());
            return null;
        });
    }

    @Test void confirmedUnchangedSignerChangeOnlyPreparesTheOriginalVerificationTask() throws Exception {
        prepareRenewalSelection();
        var confirmations=new AtomicInteger();var submitted=new AtomicReference<Task<?>>();
        fx(() -> {
            controller.signerChangeConfirmation(() -> { confirmations.incrementAndGet();return true; });
            controller.signatureTaskSubmitter((task,rejected) -> submitted.set(task));
            node("testSignatureButton",Button.class).fire();
            assertEquals(1,confirmations.get());assertNotNull(submitted.get());
            assertEquals(Worker.State.READY,submitted.get().getState(),"Fixture captures continuation without running CryptoPro or HTTP");
            assertTrue(node("testSignatureButton",Button.class).isDisabled());
            assertEquals(saved,repository.getSettings());assertTrue(repository.findLogs().isEmpty());
            return null;
        });
    }

    @Test void sameStoredFingerprintWithDifferentFormattingDoesNotRequestSignerChangeConfirmation() throws Exception {
        prepareRenewalSelection();
        var submitted=new AtomicReference<Task<?>>();
        fx(() -> {
            certificates().setValue(certificate(" a ".repeat(40)));
            controller.signerChangeConfirmation(() -> { throw new AssertionError("Same stored fingerprint cannot reset Catalog ownership"); });
            controller.signatureTaskSubmitter((task,rejected) -> submitted.set(task));
            node("testSignatureButton",Button.class).fire();
            assertNotNull(submitted.get());assertEquals(Worker.State.READY,submitted.get().getState());
            assertEquals(saved,repository.getSettings());return null;
        });
    }

    @Test void shopChangeDuringConfirmationCannotStartVerificationForThePreviousShop() throws Exception {
        prepareRenewalSelection();
        fx(() -> {
            controller.signerChangeConfirmation(() -> { controller.setShop(null);return true; });
            node("testSignatureButton",Button.class).fire();
            assertEquals(saved,repository.getSettings());assertTrue(repository.findLogs().isEmpty());return null;
        });
    }

    @Test void selectionChangeDuringConfirmationCannotVerifyThePreviouslySelectedCertificate() throws Exception {
        prepareRenewalSelection();
        fx(() -> {
            controller.signerChangeConfirmation(() -> { certificates().setValue(certificate(OLD));return true; });
            node("testSignatureButton",Button.class).fire();
            assertEquals(OLD,certificates().getValue().selector());
            assertFalse(node("testSignatureButton",Button.class).isDisabled());
            assertEquals(saved,repository.getSettings());assertTrue(repository.findLogs().isEmpty());return null;
        });
    }

    @Test void draftSettingsChangeDuringConfirmationCannotStartVerificationFromTheOldSnapshot() throws Exception {
        prepareRenewalSelection();
        fx(() -> {
            controller.signerChangeConfirmation(() -> { node("omsConnectionField",TextField.class).setText("synthetic-new-draft");return true; });
            node("testSignatureButton",Button.class).fire();
            assertFalse(node("testSignatureButton",Button.class).isDisabled());
            assertEquals(saved,repository.getSettings());assertTrue(repository.findLogs().isEmpty());return null;
        });
    }

    @Test void persistedSettingsChangeDuringConfirmationCannotBeOverwrittenByTheOldSnapshot() throws Exception {
        prepareRenewalSelection();
        fx(() -> {
            controller.signerChangeConfirmation(() -> {
                try(var c=Database.getConnection();var s=c.createStatement()) {
                    s.execute("UPDATE znack_settings SET oms_connection='synthetic-concurrent' WHERE shop_id=1");
                } catch(Exception failure) { throw new AssertionError(failure); }
                return true;
            });
            node("testSignatureButton",Button.class).fire();
            assertEquals("synthetic-concurrent",repository.getSettings().omsConnection());
            assertEquals(OLD,repository.getSettings().signerCertificate());
            assertTrue(repository.findLogs().isEmpty());return null;
        });
    }

    @Test void signerChangeWarningExistsInEveryLocaleAndNamesTheAffectedLocalData() throws Exception {
        for(String locale:List.of("vi","en","ru","zh")) {
            var properties=new Properties();
            try(var input=getClass().getResourceAsStream("/com/vncode/app/i18n/messages_"+locale+".properties")) {
                assertNotNull(input);properties.load(new InputStreamReader(input,StandardCharsets.UTF_8));
            }
            for(String key:List.of("title","header","body"))
                assertFalse(properties.getProperty("znack.signature.change."+key,"").isBlank(),locale+" "+key);
            String body=properties.getProperty("znack.signature.change.body");
            var affectedFields=locale.equals("ru")?List.of("GTIN","КИЗ","Каталога","API","Znack"):List.of("GTIN","KIZ","Catalog","API","Znack");
            for(String affected:affectedFields)assertTrue(body.contains(affected),locale+" "+affected);
        }
    }

    private void prepareRenewalSelection() throws Exception {
        fx(() -> {
            controller.certificateReader(settings -> List.of(certificate(OLD),certificate(RENEWED)));
            node("editSettingsButton",Button.class).fire();return null;
        });
        ShopReadTestCleanup.awaitSharedIdle();
        fx(() -> {
            certificates().setValue(certificates().getItems().stream().filter(c -> RENEWED.equals(c.selector())).findFirst().orElseThrow());
            assertFalse(node("testSignatureButton",Button.class).isDisabled());return null;
        });
    }

    private static CryptoProCertificateInfo certificate(String selector) {
        return new CryptoProCertificateInfo(selector,selector.replaceAll("\\s+", ""),
                "O=Certificate fixture,INNLE="+INN,"CN=Fixture CA",INN,
                Instant.now().minusSeconds(86400),Instant.now().plusSeconds(864000),
                true,"CryptoPro CMS signer","");
    }
    @SuppressWarnings("unchecked") private ComboBox<CryptoProCertificateInfo> certificates() {
        return (ComboBox<CryptoProCertificateInfo>)loader.getNamespace().get("signatureCertificateCombo");
    }
    private <T>T node(String id,Class<T> type) { return type.cast(loader.getNamespace().get(id)); }
    private static <T>T fx(Callable<T> work) throws Exception {
        var task=new FutureTask<>(work);Platform.runLater(task);return task.get(5,TimeUnit.SECONDS);
    }
}
