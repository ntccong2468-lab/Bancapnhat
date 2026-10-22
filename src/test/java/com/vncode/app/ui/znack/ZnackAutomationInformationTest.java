package com.vncode.app.ui.znack;

import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.BalanceScope;
import com.vncode.app.integration.znack.BalanceSnapshot;
import com.vncode.app.integration.znack.ChestnyZnakBalanceClient;
import com.vncode.app.integration.znack.ZnackModels.ShopContext;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ZnackAutomationInformationTest {
    private static final String INN_A = "7707083893", INN_B = "7811089030";
    @TempDir Path root;
    private final I18nService i18n = I18nService.getInstance();
    private AppLanguage previousLanguage;
    private FXMLLoader loader;
    private ZnackAutomationController controller;

    @BeforeAll static void startFx() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) {}
    }
    @Test void catalogKeyIsMaskedAndClearedWhenSwitchingShopsAndLanguages() throws Exception {
        fx(() -> {
            var field=node("catalogKeyField",javafx.scene.control.PasswordField.class);
            for(var language:AppLanguage.values()) {
                i18n.setLanguage(language);controller.applyTranslations();
                assertEquals(i18n.tr("znack.settings.catalog_key"),label("catalogTitleLabel"));
                assertFalse(node("checkCatalogButton",Button.class).getText().contains("znack.catalog."));
            }
            field.setText("synthetic-private-key");
            assertTrue(label("catalogStatusLabel").contains(i18n.tr("znack.catalog.unchecked")));
            assertFalse(label("catalogStatusLabel").contains(field.getText()));
            controller.setShop(shop(2));assertEquals("",field.getText());
            assertEquals(i18n.tr("znack.catalog.not_saved"),label("catalogStatusLabel"));
            controller.setShop(null);assertTrue(field.isDisabled());assertTrue(node("checkCatalogButton",Button.class).isDisabled());
            return null;
        });
    }
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString());
        Database.initDatabase();
        previousLanguage = i18n.getCurrentLanguage();
        seed(1, INN_A, "Fixture A", Instant.now().plusSeconds(86400));
        seed(2, INN_B, "Fixture B", Instant.now().plusSeconds(86400));
        fx(() -> {
            loader = FxmlViewLoader.loader(ZnackAutomationController.class, "znack-automation-view.fxml");
            FxmlViewLoader.load(loader);
            controller = loader.getController();
            controller.setShop(shop(1));
            return null;
        });
    }
    @AfterEach void close() throws Exception {
        awaitWorkers();
        fx(() -> { controller.setShop(null); i18n.setLanguage(previousLanguage); return null; });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }

    @Test void informationIsFirstAndSavedProofNeverMakesTheLiveStatusGreenInAnyLanguage() throws Exception {
        var remoteCalls = new AtomicInteger();
        fx(() -> {
            controller.accountReader((settings, owner, existing, active) -> { remoteCalls.incrementAndGet(); throw new AssertionError("No automatic login"); });
            var information = node("informationTab", Tab.class);
            assertSame(information, information.getTabPane().getSelectionModel().getSelectedItem());
            assertEquals(List.of("informationTab", "ordersTab", "deletedTab", "settingsTab", "warehouseTab"),
                    information.getTabPane().getTabs().stream().map(Tab::getId).toList());
            for (var language : AppLanguage.values()) {
                i18n.setLanguage(language); controller.applyTranslations();
                assertEquals("Fixture A", label("accountOwnerLabel"));
                assertEquals("INN " + INN_A, label("accountInnLabel"));
                assertEquals(i18n.tr("znack.account.not_checked"), label("accountStatusLabel"));
                assertFalse(node("accountStatusLabel", Label.class).getStyleClass().contains("activation-active"));
                assertFalse(node("refreshAccountButton", Button.class).isDisabled());
                for (String key : List.of("znack.tab.information", "znack.tab.kiz_history", "znack.account.balance", "znack.account.tested_at"))
                    assertNotEquals(key, i18n.tr(key));
            }
            node("accountSettingsButton", Button.class).fire();
            assertSame(node("settingsTab", Tab.class), information.getTabPane().getSelectionModel().getSelectedItem());
            assertEquals(0, remoteCalls.get());
            return null;
        });
    }

    @Test void explicitRefreshKeepsContractChoiceAndDoesNotClaimTrueApiLogin() throws Exception {
        CountDownLatch rendered = new CountDownLatch(1);
        var calls = new AtomicInteger();
        fx(() -> {
            controller.accountReader((settings, owner, existing, active) -> {
                calls.incrementAndGet();
                assertEquals(1, owner.shop().shopId());
                assertEquals(INN_A, settings.participantInn());
                assertTrue(active.getAsBoolean());
                return fresh(INN_A, List.of(balance(INN_A, "1", "10", "23.45"), balance(INN_A, "2", "20", "100.00")));
            });
            node("authStatusLabel", Label.class).textProperty().addListener((ignored, old, value) -> {
                if (value.equals(i18n.tr("znack.account.refreshed"))) rendered.countDown();
            });
            node("refreshAccountButton", Button.class).fire();
            return null;
        });
        assertTrue(rendered.await(5, TimeUnit.SECONDS));
        fx(() -> {
            assertEquals(1, calls.get());
            var choice = balances();
            assertEquals(2, choice.getItems().size());
            assertTrue(choice.isVisible());
            assertNull(choice.getValue(), "Multiple contracts require an explicit choice instead of summing balances");
            assertEquals(i18n.tr("znack.account.unknown"), label("accountBalanceLabel"));
            choice.setValue(choice.getItems().getFirst());
            assertTrue(label("accountBalanceLabel").contains("23"));
            assertFalse(label("accountBalanceLabel").contains("123"));
            assertEquals(i18n.tr("znack.account.not_checked"), label("accountStatusLabel"));
            assertFalse(node("accountStatusLabel", Label.class).getStyleClass().contains("activation-active"));
            assertTrue(label("accountBalanceUpdatedLabel").contains(i18n.tr("znack.account.balance_updated").split("\\{0\\}")[0]));
            node("omsConnectionField", TextField.class).setText("changed-unsaved");
            assertTrue(node("refreshAccountButton", Button.class).isDisabled());
            assertEquals(i18n.tr("znack.account.unsaved"), label("accountStatusLabel"));
            return null;
        });
    }

    @Test void shopChangeDropsAnInflightResultAndExpiredCertificatesCannotStartARefresh() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        fx(() -> {
            controller.accountReader((settings, owner, existing, active) -> {
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return fresh(INN_A, List.of(balance(INN_A, "1", "10", "999.00")));
            });
            node("refreshAccountButton", Button.class).fire();
            return null;
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            fx(() -> { controller.setShop(shop(2)); return null; });
        } finally { release.countDown(); }
        awaitWorkers();
        fx(() -> {
            assertEquals("Fixture B", label("accountOwnerLabel"));
            assertEquals("INN " + INN_B, label("accountInnLabel"));
            assertEquals(i18n.tr("znack.account.unknown"), label("accountBalanceLabel"));
            return null;
        });
        seed(2, INN_B, "Fixture B", Instant.now().minusSeconds(60));
        var calls = new AtomicInteger();
        fx(() -> {
            controller.accountReader((settings, owner, existing, active) -> { calls.incrementAndGet(); throw new AssertionError(); });
            controller.refresh();
            assertEquals("Fixture B", label("accountOwnerLabel"), "Expired proof keeps the historical identity");
            assertEquals(i18n.tr("znack.account.expired"), label("accountStatusLabel"));
            assertTrue(node("refreshAccountButton", Button.class).isDisabled());
            node("refreshAccountButton", Button.class).fire();
            assertEquals(0, calls.get());
            return null;
        });
    }

    @Test void foreignBalanceCannotBeRenderedOrWrittenToCache() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        fx(() -> {
            controller.accountReader((settings, owner, existing, active) -> fresh(INN_A,
                    List.of(balance(INN_B, "1", "10", "999.00"))));
            node("authStatusLabel", Label.class).textProperty().addListener((ignored, old, value) -> {
                if (value.equals(i18n.tr("znack.account.refresh_failed"))) failed.countDown();
            });
            node("refreshAccountButton", Button.class).fire();
            return null;
        });
        assertTrue(failed.await(5, TimeUnit.SECONDS));
        fx(() -> {
            assertEquals(i18n.tr("znack.account.unknown"), label("accountBalanceLabel"));
            assertEquals(i18n.tr("znack.account.not_checked"), label("accountStatusLabel"));
            return null;
        });
        assertTrue(new com.vncode.app.features.gs1.Gs1BalanceRepository().list(INN_B, "production").isEmpty());
    }

    private void seed(int id, String inn, String name, Instant expiry) throws Exception {
        try (var c = Database.getConnection(); var p = c.prepareStatement("INSERT OR IGNORE INTO shops(id,name,api_key) VALUES(?,?,?)")) {
            p.setLong(1, id); p.setString(2, name); p.setString(3, "fixture"); p.executeUpdate();
        }
        var repo = new ZnackRepository(new ShopContext(id, name)); repo.saveSettings(repo.getSettings());
        var json = new JsonObject();
        json.addProperty("inn", inn); json.addProperty("thumbprint", "A".repeat(40));
        json.addProperty("subject", "O=" + name + ",INNLE=" + inn);
        json.addProperty("hasPrivateKey", true);
        json.addProperty("validFrom", Instant.now().minusSeconds(864000).toString());
        json.addProperty("validTo", expiry.toString());
        try (var c = Database.getConnection(); var p = c.prepareStatement("UPDATE znack_settings SET participant_inn=?,signer_certificate=?,certificate_metadata_json=?,signer_tested_at=? WHERE shop_id=?")) {
            p.setString(1, inn); p.setString(2, "A".repeat(40)); p.setString(3, json.toString());
            p.setString(4, Instant.now().minusSeconds(3600).toString()); p.setLong(5, id); p.executeUpdate();
        }
    }
    private Shop shop(int id) { return new Shop(id, "Fixture " + (id == 1 ? "A" : "B"), "fixture"); }
    private BalanceSnapshot balance(String inn, String group, String contract, String amount) {
        return new BalanceSnapshot(new BalanceScope(inn, "production", group, contract), new BigDecimal(amount), "RUB",
                Instant.now(), URI.create("https://markirovka.crpt.ru/bff-elk/v1/organisation/balance"));
    }
    private ZnackAutomationController.AccountRefresh fresh(String inn, List<BalanceSnapshot> balances) {
        return new ZnackAutomationController.AccountRefresh(new ChestnyZnakBalanceClient.ServiceSession("ELK",
                ChestnyZnakBalanceClient.PRODUCTION, inn, "production", "fixture-session", Instant.now().plusSeconds(1200)), balances);
    }
    private String label(String key) { return node(key, Label.class).getText(); }
    private <T> T node(String key, Class<T> type) { return type.cast(loader.getNamespace().get(key)); }
    @SuppressWarnings("unchecked") private ComboBox<BalanceSnapshot> balances() { return (ComboBox<BalanceSnapshot>) loader.getNamespace().get("accountBalanceCombo"); }
    private static <T> T fx(Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call); Platform.runLater(task); return task.get(5, TimeUnit.SECONDS);
    }
    private static void awaitWorkers() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (AppTaskExecutor.getRunningTaskCount() > 0 && System.nanoTime() < until) Thread.sleep(10);
        assertEquals(0, AppTaskExecutor.getRunningTaskCount(), "Information workers must complete");
        fx(() -> null);
    }
}
