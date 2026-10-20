package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.znack.registration.CatalogFeedback;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.I18nService;

/** The exact card and remote request are available from the row, even before a sync report exists. */
final class RegistrationDiagnostics {
    private RegistrationDiagnostics(){}
    /** A feed receipt is not evidence that the product card exists or entered moderation. */
    static String pendingCardAction(com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status status,
            Long goodId,String feed,String detail,String observedStatus){
        if("source.draft".equals(observedStatus)||goodId!=null&&goodId>0&&"CATALOG_CARD_DRAFT".equals(detail))return "znack.registration.source.draft";
        if("source.moderation".equals(observedStatus))return "znack.registration.waiting_moderation";
        if((status!=com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status.FEED_SUBMITTED
                &&status!=com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status.PROCESSING)
                ||feed==null||feed.isBlank())return "";
        return goodId!=null&&goodId>0&&"CATALOG_CARD_IN_MODERATION".equals(detail)
                ?"znack.registration.waiting_moderation":"znack.registration.waiting_card_creation";
    }
    static String text(String article,String gtin,String feed,String status,String error){
        return CatalogFeedback.display("Article: "+value(article)+"\nGTIN: "+value(gtin)
                +"\nfeed_id: "+value(feed)+"\n"+I18nService.getInstance().tr("znack.registration.status")+": "+value(status)
                +"\n"+I18nService.getInstance().tr("jobs.reason")+": "+reason(value(error)));
    }
    static String reason(String raw){
        String value=raw==null?"":raw;
        for(String code:new String[]{"CATALOG_REGISTRATION_SCHEMA_UNAVAILABLE","CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE","CATALOG_COUNTRY_DICTIONARY_INVALID"}){
            String key="znack.registration.reason."+code,translated=I18nService.getInstance().tr(key);
            if(!translated.equals(key))value=value.replace(code,translated);
        }
        for(String code:new String[]{"CATALOG_CARD_DRAFT","CATALOG_CARD_IN_MODERATION","CATALOG_REQUEST_ACCEPTED_CARD_PENDING","CATALOG_READBACK_RETRY_PENDING","CATALOG_FEED_IDENTITY_NOT_CONFIRMED","CATALOG_IDENTITY_CHANGED","CATALOG_PUBLICATION_ID_CHANGED","CATALOG_PUBLICATION_PENDING","CATALOG_CARD_REJECTED_DETAILS_UNAVAILABLE","ZNACK_BATCH_SUBMITTING","ZNACK_PREPARING_CARD","ZNACK_CARD_READY","ZNACK_SUBMITTING_SAVED_PAYLOAD","ZNACK_BATCH_SUBMITTED","ZNACK_BATCH_NEEDS_ACTION","REGISTRATION_MISSING_REQUIRED_FIELDS","REGISTRATION_DOCUMENT_REVIEW_REQUIRED","REGISTRATION_REVIEW_REQUIRED","REGISTRATION_NOT_SUBMITTED","REGISTRATION_BUSY","NETWORK_RETRY","GTIN_ALLOCATION_READBACK_REQUIRED","CATALOG_FEED_READBACK_REQUIRED","SOURCE_CARD_ACTION_CHANGED","ZNACK_REGISTRATION_BATCH_ALREADY_RUNNING","ZNACK_REQUIRED_ATTRIBUTES_MISSING","ZNACK_PREFLIGHT_READ_FAILED","ZNACK_REVIEWED_VALUES_CHANGED","ZNACK_DOCUMENT_TNVED_MISMATCH","ZNACK_DOCUMENT_TNVED_SCOPE_UNKNOWN","ZNACK_PRODUCT_DOCUMENT_REVIEW_REQUIRED","ZNACK_MISSING_PRODUCT_DOCUMENT","ZNACK_CATEGORY_REVIEW_REQUIRED"}){
            String key="znack.registration.reason."+code,translated=I18nService.getInstance().tr(key);
            if(!translated.equals(key))value=value.replace(code,translated);
        }
        return CatalogFeedback.display(value);
    }
    static void show(String article,String gtin,String feed,String status,String error){
        AlertService.showDetailedError(article+" · "+status,text(article,gtin,feed,status,error));
    }
    private static String value(String value){return value==null||value.isBlank()?"—":value;}
}
