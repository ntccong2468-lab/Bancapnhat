package com.vncode.app.ui.gtinsync;

import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.testsupport.FxDialogDriver;
import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class WbGtinPriceConsentTest {
    @Test void warningNamesThePriceImpactInAllSupportedLanguages(){
        for(var language:com.vncode.app.shared.AppLanguage.values()){
            var bundle=java.util.ResourceBundle.getBundle("com.vncode.app.i18n.messages",language.toLocale());
            assertFalse(bundle.getString("gtinsync.wb_price_consent_body").isBlank());
        }
        var vi=java.util.ResourceBundle.getBundle("com.vncode.app.i18n.messages",java.util.Locale.forLanguageTag("vi"));
        String text=vi.getString("gtinsync.wb_price_consent_body");assertTrue(text.contains("trung bình"));assertTrue(text.contains("tăng")&&text.contains("giảm"));
    }
}
