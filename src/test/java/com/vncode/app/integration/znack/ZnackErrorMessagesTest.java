package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.I18nService;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** Local presentation only; no provider process, seller API or real signature. */
class ZnackErrorMessagesTest {
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
    void aConfirmationPauseHasALocalizedActionAndDoesNotInventASignerFailure(AppLanguage language) {
        var i18n = I18nService.getInstance();
        i18n.setLanguage(language);
        String raw = ZnackSigningSession.WAITING_MESSAGE;
        assertEquals("SIGNING_CONFIRMATION_REQUIRED", ZnackErrorMessages.errorCode(raw));
        String shown = ZnackErrorMessages.display(raw);
        assertNotEquals(raw, shown);
        assertEquals(i18n.getBundle().getString("znack.error.signing_confirmation_required"), shown);
        assertEquals(shown, ZnackErrorMessages.displayForPipeline("DOWNLOADING_CODES", raw));
        assertFalse(shown.contains("CRYPTOPRO_CANCELLED"));
        assertFalse(shown.contains("PIN"));
    }

    @ParameterizedTest @EnumSource(CryptoProErrorCode.class)
    void anExplicitRecordedSignerFailureKeepsItsActualCode(CryptoProErrorCode code) {
        String raw = ZnackSigningSession.WAITING_MESSAGE + " [CRYPTOPRO_" + code.name() + "]";
        assertEquals("CRYPTOPRO_" + code.name(), ZnackErrorMessages.errorCode(raw));
        assertNotEquals(raw, ZnackErrorMessages.display(raw));
        assertTrue(ZnackErrorMessages.display(raw).contains("CRYPTOPRO_" + code.name()));
    }
}
