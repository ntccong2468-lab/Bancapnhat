package com.vncode.app.ui.znackregistration;

import com.vncode.app.shared.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;
import static org.junit.jupiter.api.Assertions.*;

class FlowAuditDiagnosticsTest {
    @TempDir Path directory;
    private String previousDirectory;
    @BeforeEach void isolate() throws Exception {
        previousDirectory=System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir",directory.toString());
        com.vncode.app.config.Database.initDatabase();
    }
    @AfterEach void restore() {
        if(previousDirectory==null)System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir",previousDirectory);
    }
    @Test void finalGtinReasonsExplainAllThreeScreenshotBlockers() {
        var i18n=I18nService.getInstance();
        for(String code:List.of("GTIN_AMBIGUOUS","GTIN_ON_ANOTHER_PRODUCT","ZNACK_MAPPING_UNVERIFIED"))
            assertEquals(i18n.tr("znack.registration.reason."+code),RegistrationDiagnostics.reason(code),code);
        for(var language:AppLanguage.values()) {
            var bundle=ResourceBundle.getBundle("com.vncode.app.i18n.messages",language.toLocale());
            assertFalse(bundle.getString("znack.registration.reason.GTIN_ON_ANOTHER_PRODUCT").contains("Ozon"),
                    "A shared WB/Ozon conflict must not direct every WB seller to Ozon");
        }
    }
    @Test void paidPermitFailureShowsTheExactLocalizedReasonWithoutWrapperSecrets() {
        var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
        try {for(var language:AppLanguage.values()) {
            i18n.setLanguage(language);
            for(String code:List.of("GTIN_DOCUMENT_EXPIRED","GTIN_DOCUMENT_INACTIVE","GTIN_DOCUMENT_NOT_YET_VALID","REGISTRY_DOCUMENTS_PENDING","REGISTRY_DOCUMENTS_UNAVAILABLE")) {
                String expected=i18n.tr("mapping.reason."+code);
                assertNotEquals("mapping.reason."+code,expected);
                assertEquals(expected+"\n["+code+"]",FriendlyErrorService.format(
                        new RuntimeException("private wrapper token=fixture-secret",new IllegalStateException(code))));
            }
        }} finally {i18n.setLanguage(previous);}
    }
    @Test void mappingFailuresHaveAdviceForTheCorrectVariantInAllLocales() {
        for(var language:AppLanguage.values()) {
            var bundle=ResourceBundle.getBundle("com.vncode.app.i18n.messages",language.toLocale());
            for(String code:List.of("GTIN_ARTICLE_MISMATCH","GTIN_BARCODE_MISSING"))
                assertFalse(bundle.getString("mapping.reason."+code).isBlank());
        }
    }
    @Test void changedCatalogReadSourceAsksForResyncWithoutLosingHistory() {
        var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
        try {for(var language:AppLanguage.values()) {
            i18n.setLanguage(language);
            for(String code:List.of("SOURCE_SYNC_REQUIRED","SOURCE_CONFIGURATION_CHANGED")) {
                String expected=i18n.tr("znack.registration.reason."+code);
                assertNotEquals("znack.registration.reason."+code,expected);
                assertEquals(expected,RegistrationDiagnostics.reason(code));
                assertEquals(expected+"\n["+code+"]",FriendlyErrorService.format(
                        new RuntimeException("private key=fixture-secret",new IllegalStateException(code))));
            }
        }} finally {i18n.setLanguage(previous);}
    }
}
