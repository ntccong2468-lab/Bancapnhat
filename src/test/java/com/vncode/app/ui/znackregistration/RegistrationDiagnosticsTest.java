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
                for(String code:new String[]{"CATALOG_REQUEST_ACCEPTED_CARD_PENDING","CATALOG_READBACK_RETRY_PENDING","CATALOG_FEED_IDENTITY_NOT_CONFIRMED","CATALOG_IDENTITY_CHANGED","CATALOG_PUBLICATION_ID_CHANGED","GTIN_VARIANT_CONFLICT","GTIN_VARIANT_UNVERIFIED"}){
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
    @Test void registrationModelFailuresAreLocalizedInRowReports(){
        var i18n=com.vncode.app.shared.I18nService.getInstance();
        for(var language:com.vncode.app.shared.AppLanguage.values()){
            var bundle=java.util.ResourceBundle.getBundle("com.vncode.app.i18n.messages",language.toLocale());
            for(String code:new String[]{"CATALOG_REGISTRATION_SCHEMA_UNAVAILABLE","CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE","CATALOG_COUNTRY_DICTIONARY_INVALID","CATALOG_FEED_RESPONSE_INVALID","CATALOG_SIGNING_REVIEW_REQUIRED","CATALOG_SIGNING_ACKNOWLEDGEMENT_INVALID","ZNACK_INVALID_ATTRIBUTE_VALUE","CATALOG_NEW_GTIN_CONFIRMATION_REQUIRED","CATALOG_EXISTING_CARD_NOT_CONFIRMED","ZNACK_BATCH_PAUSED","ZNACK_BATCH_CANCELLED","ZNACK_BATCH_RETRY_LIMIT_REACHED","SIGNING_REVIEW_TOO_LARGE","SIGNING_REVIEW_REQUIRED","CATALOG_ENDPOINT_INVALID","SYNC_REPORT_CHANGED_RETRY","GTIN_CONFIRM_SELECTED","GTIN_WAIT_READBACK","GTIN_RETRY_READBACK","GTIN_REFRESH_PRODUCTS","GTIN_REVIEW_SOURCE","GTIN_READBACK_COMPLETE","GTIN_VARIANT_IDENTITY_UNVERIFIED","MARKETPLACE_REQUEST_REJECTED","unverified_catalog_gtin","UNVERIFIED_CATALOG_GTIN"}){
                assertFalse(bundle.getString("znack.registration.reason."+code).isBlank());
                assertEquals(i18n.tr("znack.registration.reason."+code),RegistrationDiagnostics.reason(code));
            }
        }
    }
    @Test void wbPriceAndUnsignedReasonsAreAvailableInEveryLanguage(){
        var i18n=com.vncode.app.shared.I18nService.getInstance();
        for(String code:new String[]{"CATALOG_CARD_NOT_SIGNED","WB_PRICE_READBACK_PENDING","WB_PRICE_RECEIPT_UNKNOWN","WB_PRICE_RATE_LIMIT","WB_PRICE_TOKEN_LIMIT","WB_PRICE_OTHER_REQUEST_PENDING","WB_PRICE_CHANGED","WB_PRICE_SIZE_INCOMPLETE","WB_PRICE_REJECTED","WB_PRICE_TOO_MANY_SIZES","WB_PRICE_NOT_FOUND","WB_PRICE_CURRENCY_MISSING","WB_PRICE_CONSENT_REQUIRED","WB_PRICE_REQUEST_IN_FLIGHT","WB_PRICE_CANCELLED_APPLIED","WB_PRICE_CANCELLED_REJECTED","WB_PRICE_CANCELLED_NOT_SENT","WB_PRICE_CANCELLED_STATUS_UNKNOWN"}){
            for(var language:com.vncode.app.shared.AppLanguage.values())assertFalse(java.util.ResourceBundle.getBundle("com.vncode.app.i18n.messages",language.toLocale()).getString("znack.registration.reason."+code).isBlank());
            assertEquals(i18n.tr("znack.registration.reason."+code),RegistrationDiagnostics.reason(code));
        }
    }
    @Test void storedCountryInfraFailureHasActionableReasonWithoutPretendingTheGtinIsMissing(){
        String report=RegistrationDiagnostics.text("Fixture article","00000000000017","","Chưa xác minh",
                "CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE <- ZnackApiException: CATALOG_COUNTRY_HTTP_403; HTTP 403; token=private-secret");
        assertTrue(report.contains("CATALOG_COUNTRY_AUTH_REQUIRED"));assertFalse(report.contains("private-secret"));
        assertFalse(report.contains("GTIN_NOT_FOUND"));assertFalse(report.contains("INVALID_GTIN"));
        assertTrue(report.contains("00000000000017"));
    }
    @Test void rowReportSeparatesTheNativeQueueFromTheActualCatalogStatusAndReadTime(){
        var checked=java.time.Instant.parse("2026-10-20T12:34:00Z");
        var data=com.vncode.app.integration.znack.registration.CatalogProductDataParser.parse(com.google.gson.JsonParser.parseString("{\"good_status\":\"draft\",\"good_detailed_status\":[\"moderation\"]}").getAsJsonObject());
        var actual=new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("00000000000017","A","Blue","M",true,false,"National Catalog@synthetic",checked,data);
        var i18n=com.vncode.app.shared.I18nService.getInstance();String report=RegistrationDiagnostics.observation(com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status.FEED_SUBMITTED,java.util.Optional.of(actual),true);
        assertTrue(report.contains(i18n.tr("znack.registration.local_queue")+": "+i18n.tr("znack.registration.status.feed_submitted")));assertTrue(report.contains(i18n.tr("znack.registration.catalog_observation")+": draft / moderation"));assertTrue(report.contains(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(java.time.ZoneId.systemDefault()).format(checked)));assertTrue(report.contains(i18n.tr("znack.registration.latest_read_unknown")));
        String receiptOnly=RegistrationDiagnostics.observation(com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status.FEED_SUBMITTED,java.util.Optional.empty(),false);
        assertTrue(receiptOnly.contains(i18n.tr("znack.registration.catalog_observation")+": —"));assertFalse(receiptOnly.contains("moderation"));assertFalse(receiptOnly.contains(i18n.tr("znack.registration.status.published")));
    }
    @Test void aFoundPublishedCardDoesNotClaimThatNoCardExistsJustBecauseTheLocalQueueIsEmpty(){
        var actual=new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("00000000000017","Another article","Blue","M",true,true,"National Catalog@synthetic",java.time.Instant.now());
        String report=RegistrationDiagnostics.observation(com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status.NOT_CREATED,java.util.Optional.of(actual),false);
        var i18n=com.vncode.app.shared.I18nService.getInstance();
        assertTrue(report.contains(i18n.tr("znack.registration.local_not_sent")));
        assertFalse(report.contains(i18n.tr("znack.registration.local_queue")+": "+i18n.tr("znack.registration.status.not_created")));
    }
    @Test void retainedReadWarningKeepsBothLocalizedCauseAndStableDiagnosticCode(){
        var i18n=com.vncode.app.shared.I18nService.getInstance();String warning=RegistrationDiagnostics.readWarning("NETWORK_TIMEOUT",true);
        assertTrue(warning.contains(i18n.tr("znack.registration.latest_read_unknown")));assertTrue(warning.contains(i18n.tr("znack.registration.reason.NETWORK_TIMEOUT")));assertTrue(warning.contains("NETWORK_TIMEOUT"));
        assertFalse(RegistrationDiagnostics.readWarning("token=private-secret",true).contains("private-secret"));
    }
}
