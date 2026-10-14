package com.vncode.app.ui.license;

import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseModels.Snapshot;
import com.vncode.app.features.license.LicenseModels.Status;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class LicensePaneTest {
    @TempDir Path root;
    private LicensePane pane;
    private FakeActions actions;

    @BeforeAll static void startFx() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    @BeforeEach void setUp() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString());
        Database.initDatabase();
        actions = new FakeActions();
        fx(() -> {
            pane = new LicensePane(actions);
            new Scene(pane, 800, 650);
            pane.applyCss();
            pane.layout();
            return null;
        });
    }

    @AfterEach void tearDown() throws Exception {
        fx(() -> { pane.dispose(); return null; });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }

    @Test void registrationIsAsyncAndGoesDirectlyToOwnerApproval() throws Exception {
        fx(() -> {
            text("licenseGmail").setText("client@gmail.com");
            button("licenseRegister").fire();
            assertEquals("client@gmail.com", actions.email);
            assertTrue(button("licenseRegister").isDisabled());
            assertFalse(button("licenseRegister").getText().isBlank());
            assertNull(pane.lookup("#licenseOtp"));
            assertNull(pane.lookup("#licenseVerify"));
            actions.complete(snapshot(Status.PENDING));
            assertEquals(I18nService.getInstance().tr("license.account.status.pending"), label("licenseAccountStatus").getText());
            assertFalse(text("licenseGmail").isEditable());
            assertTrue(button("licenseRefresh").isDisabled());
            return null;
        });
    }

    @Test void newMachineCanActivateAKeyWithoutGmailRegistration() throws Exception {
        fx(() -> {
            assertEquals(Status.UNREGISTERED, actions.snapshot().status());
            PasswordField key = (PasswordField) pane.lookup("#licenseKey");
            assertFalse(key.isDisabled());
            key.setText("VNC" + "-1234".repeat(8));
            assertFalse(button("licenseActivate").isDisabled());
            button("licenseActivate").fire();
            assertEquals("VNC" + "-1234".repeat(8), actions.code);
            assertNull(actions.email);
            assertTrue(key.getText().isEmpty());
            actions.complete(snapshot(Status.ACTIVE));
            return null;
        });
    }

    @Test void activationHidesKeyAndReadsCurrentSnapshotRatherThanOldCompletion() throws Exception {
        fx(() -> {
            actions.emit(snapshot(Status.PENDING));
            PasswordField key = (PasswordField) pane.lookup("#licenseKey");
            key.setText("key-not-logged");
            button("licenseActivate").fire();
            assertEquals("key-not-logged", actions.code);
            assertTrue(key.getText().isEmpty());
            assertTrue(button("licenseActivate").isDisabled());
            actions.emit(new Snapshot(Status.ACTIVE, "client@gmail.com", 2_000_000_000,
                    1_999_000_000, 2, 3, "", false));
            actions.pending.complete(snapshot(Status.PENDING));
            assertEquals(I18nService.getInstance().tr("license.account.status.active"), label("licenseAccountStatus").getText());
            assertTrue(button("licenseCheck").isDisabled(), "Approval lookup must not clear an active license");
            assertFalse(button("licenseRefresh").isDisabled());
            assertTrue(label("licenseLimits").isVisible());
            return null;
        });
    }

    @Test void disposedViewDropsCallbacksAndRemovesOnlyItsSubscription() throws Exception {
        fx(() -> {
            actions.emit(snapshot(Status.APPROVED));
            ((PasswordField) pane.lookup("#licenseKey")).setText("key-not-logged");
            button("licenseActivate").fire();
            String statusBeforeDispose = label("licenseAccountStatus").getText();
            assertEquals(1, actions.listeners.size());
            pane.dispose();
            assertEquals(0, actions.listeners.size());
            actions.emit(snapshot(Status.EXPIRED));
            actions.pending.complete(snapshot(Status.EXPIRED));
            assertEquals(statusBeforeDispose, label("licenseAccountStatus").getText());
            assertFalse(actions.logoutCalled, "Disposing a view must not clear the shared license session");
            return null;
        });
    }

    @Test void allStatusesAndFieldsTranslateAndUnknownErrorsStayGeneric() throws Exception {
        I18nService i18n = I18nService.getInstance();
        AppLanguage previous = i18n.getCurrentLanguage();
        try {
            for (AppLanguage language : AppLanguage.values()) fx(() -> {
                i18n.setLanguage(language);
                pane.applyTranslations();
                assertEquals(i18n.tr("license.account.activate"), button("licenseActivate").getText());
                for (Status value : Status.values()) {
                    actions.emit(new Snapshot(value, "client@gmail.com", 0, 0, 0, 0, "", false));
                    assertEquals(i18n.tr("license.account.status." + value.name().toLowerCase(java.util.Locale.ROOT)), label("licenseAccountStatus").getText());
                    assertFalse(label("licenseAccountStatus").getText().startsWith("license.account."));
                }
                actions.emit(new Snapshot(Status.APPROVED, "client@gmail.com", 0, 0, 0, 0, "NOT_A_PUBLIC_ERROR", false));
                assertEquals(i18n.tr("license.account.error.unexpected"), label("licenseFeedback").getText());
                assertFalse(label("licenseFeedback").getText().contains("NOT_A_PUBLIC_ERROR"));
                return null;
            });
        } finally { fx(() -> { i18n.setLanguage(previous); return null; }); }
    }

    @Test void transportFailuresShowTranslatedAdviceAndSafeDiagnosticCodesInEveryLanguage() throws Exception {
        I18nService i18n = I18nService.getInstance();
        AppLanguage previous = i18n.getCurrentLanguage();
        try {
            for (AppLanguage language : AppLanguage.values()) fx(() -> {
                i18n.setLanguage(language);
                for (String code : List.of("LICENSE_NETWORK", "LICENSE_HTTP_ERROR", "LICENSE_UNAVAILABLE",
                        "LICENSE_REDIRECT_REJECTED", "LICENSE_BUSY", "LICENSE_CHANGED")) {
                    actions.emit(new Snapshot(Status.APPROVED, "client@gmail.com", 0, 0, 0, 0, code, false));
                    String message = label("licenseFeedback").getText();
                    assertTrue(label("licenseFeedback").isVisible());
                    assertTrue(message.endsWith("[" + code + "]"), language + ": " + message);
                    assertFalse(message.startsWith("license.account."));
                    assertFalse(message.contains(i18n.tr("license.account.error.unexpected")));
                }
                actions.emit(new Snapshot(Status.APPROVED, "client@gmail.com", 0, 0, 0, 0,
                        "token=secret client@gmail.com", false));
                assertEquals(i18n.tr("license.account.error.unexpected"), label("licenseFeedback").getText());
                return null;
            });
        } finally { fx(() -> { i18n.setLanguage(previous); return null; }); }
    }

    private static Snapshot snapshot(Status status) {
        return new Snapshot(status, status == Status.UNREGISTERED ? "" : "client@gmail.com", 0, 0, 0, 0, "", false);
    }
    private TextField text(String id) { return (TextField) pane.lookup("#" + id); }
    private Button button(String id) { return (Button) pane.lookup("#" + id); }
    private Label label(String id) { return (Label) pane.lookup("#" + id); }
    private static <T> T fx(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static final class FakeActions implements LicensePane.Actions {
        Snapshot current = LicensePaneTest.snapshot(Status.UNREGISTERED);
        List<Consumer<Snapshot>> listeners = new ArrayList<>();
        CompletableFuture<Snapshot> pending;
        String email, code;
        boolean logoutCalled;
        public Snapshot snapshot() { return current; }
        public AutoCloseable subscribe(Consumer<Snapshot> listener) {
            listeners.add(listener);
            listener.accept(current);
            return () -> listeners.remove(listener);
        }
        void emit(Snapshot value) { current = value; List.copyOf(listeners).forEach(listener -> listener.accept(value)); }
        void complete(Snapshot value) { emit(value); pending.complete(value); }
        private CompletableFuture<Snapshot> begin() { pending = new CompletableFuture<>(); return pending; }
        public CompletableFuture<Snapshot> startRegistration(String value) { email = value; return begin(); }
        public CompletableFuture<Snapshot> activate(String value) { code = value; return begin(); }
        public CompletableFuture<Snapshot> checkStatus() { return begin(); }
        public CompletableFuture<Snapshot> refresh() { return begin(); }
        public CompletableFuture<Snapshot> logout() { logoutCalled = true; return begin(); }
    }
}
