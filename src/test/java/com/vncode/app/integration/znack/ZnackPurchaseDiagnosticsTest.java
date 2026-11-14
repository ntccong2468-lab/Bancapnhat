package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.I18nService;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ZnackPurchaseDiagnosticsTest {
    @TempDir Path directory;
    private AppLanguage previous;

    @BeforeEach void setup() {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        previous = I18nService.getInstance().getCurrentLanguage();
    }

    @AfterEach void cleanup() {
        try { I18nService.getInstance().setLanguage(previous); }
        finally { System.clearProperty("vncode.appdata.dir"); }
    }

    @ParameterizedTest @EnumSource(AppLanguage.class)
    void aPausedExistingLotExplainsTheRequiredActionWithoutInventingANextCheck(AppLanguage language) {
        var i18n = I18nService.getInstance();
        i18n.setLanguage(language);
        String detail = ZnackPurchaseDiagnostics.text(ZnackSigningSession.WAITING_MESSAGE, "DOWNLOADING_CODES", null);
        assertTrue(detail.contains("SIGNING_CONFIRMATION_REQUIRED"));
        assertFalse(detail.contains(ZnackSigningSession.WAITING_MESSAGE));
        assertTrue(detail.contains(i18n.getBundle().getString("diagnostics.purchase_resume_action")));
        assertTrue(detail.contains(i18n.tr("diagnostics.no_scheduled_check")));
        assertFalse(detail.contains("CRYPTOPRO_CANCELLED"));
    }

    @Test void unrelatedPurchaseErrorsDoNotSuggestResumingASigningPause() {
        String detail = ZnackPurchaseDiagnostics.text("SUZ_KIZ_BUFFER_EXHAUSTED", "DOWNLOADING_CODES", null);
        String action = I18nService.getInstance().tr("diagnostics.purchase_resume_action");
        assertFalse(detail.contains(action));
    }
}
