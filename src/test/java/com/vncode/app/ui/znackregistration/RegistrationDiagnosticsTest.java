package com.vncode.app.ui.znackregistration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationDiagnosticsTest {
    @Test void aFeedReceiptDoesNotShowModerationUntilTheCardIsObserved(){
        var submitted=com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status.FEED_SUBMITTED;
        var processing=com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status.PROCESSING;
        assertEquals("znack.registration.waiting_card_creation",RegistrationDiagnostics.pendingCardAction(submitted,null,"accepted-feed","CATALOG_REQUEST_ACCEPTED_CARD_PENDING",""));
        assertEquals("znack.registration.waiting_card_creation",RegistrationDiagnostics.pendingCardAction(processing,123L,"accepted-feed","CATALOG_READBACK_RETRY_PENDING",""));
        assertEquals("znack.registration.waiting_card_creation",RegistrationDiagnostics.pendingCardAction(processing,null,"accepted-feed","CATALOG_CARD_IN_MODERATION",""));
        assertEquals("znack.registration.waiting_moderation",RegistrationDiagnostics.pendingCardAction(processing,123L,"accepted-feed","CATALOG_CARD_IN_MODERATION",""));
        assertEquals("znack.registration.waiting_moderation",RegistrationDiagnostics.pendingCardAction(submitted,null,"accepted-feed","","source.moderation"),"A fresh Catalog moderation observation is real evidence, even without a local good ID");
        assertEquals("",RegistrationDiagnostics.pendingCardAction(com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status.READY_TO_SIGN,123L,"accepted-feed","",""),"Approved cards must retain their signing actions");
        assertEquals("",RegistrationDiagnostics.pendingCardAction(processing,null,"","",""));
        assertEquals("znack.registration.source.draft",RegistrationDiagnostics.pendingCardAction(processing,123L,"accepted-feed","CATALOG_CARD_DRAFT",""));
        assertEquals("znack.registration.source.draft",RegistrationDiagnostics.pendingCardAction(submitted,null,"","","source.draft"));
    }
    @Test void acceptedRequestAndRetryDiagnosticsAreTranslatedInEveryLanguage(){
        var i18n=com.vncode.app.shared.I18nService.getInstance();
        for(var language:com.vncode.app.shared.AppLanguage.values()){
                var bundle=java.util.ResourceBundle.getBundle("com.vncode.app.i18n.messages",language.toLocale());
                for(String code:new String[]{"CATALOG_REQUEST_ACCEPTED_CARD_PENDING","CATALOG_READBACK_RETRY_PENDING","CATALOG_FEED_IDENTITY_NOT_CONFIRMED","CATALOG_IDENTITY_CHANGED","CATALOG_PUBLICATION_ID_CHANGED"}){
                    assertFalse(bundle.getString("znack.registration.reason."+code).isBlank());
                    assertEquals(i18n.tr("znack.registration.reason."+code),RegistrationDiagnostics.reason(code));
                }
        }
    }
    @Test void oneRowReportContainsExactRemoteRequestAndReasonWithoutACredential(){
        String report=RegistrationDiagnostics.text("Fixture article","00000000000017","feed-123","Lỗi","23557: TN VED mismatch; token=fixture-secret");
        assertTrue(report.contains("feed_id: feed-123"));assertTrue(report.contains("00000000000017"));
        assertTrue(report.contains("23557: TN VED mismatch"));assertFalse(report.contains("fixture-secret"));
        assertFalse(report.contains("jobs.status"));
    }
}
