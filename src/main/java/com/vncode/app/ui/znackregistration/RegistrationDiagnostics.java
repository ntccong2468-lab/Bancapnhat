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
    static String observation(com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status local,
            java.util.Optional<com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin> observed,boolean latestUnknown){
        var i18n=I18nService.getInstance();var text=new StringBuilder();
        if(latestUnknown)text.append(i18n.tr("znack.registration.latest_read_unknown")).append('\n');
        text.append(i18n.tr("znack.registration.local_queue")).append(": ").append(i18n.tr("znack.registration.status."+local.name().toLowerCase(java.util.Locale.ROOT)));
        text.append('\n').append(i18n.tr("znack.registration.catalog_observation")).append(": ");
        if(observed.isEmpty())return text.append('—').toString();
        var card=observed.get();var raw=new java.util.LinkedHashSet<>(card.details().values("@catalog_status"));raw.addAll(card.details().values("@catalog_detailed_status"));
        text.append(raw.isEmpty()?i18n.tr("znack.registration."+com.vncode.app.integration.znack.registration.CatalogCardState.of(card).viewStatus):String.join(" / ",raw));
        text.append('\n').append(i18n.tr("znack.registration.catalog_checked_at")).append(": ").append(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(java.time.ZoneId.systemDefault()).format(card.syncedAt()));
        return CatalogFeedback.display(text.toString());
    }
    static String readWarning(String code,boolean retained){
        String raw=code==null?"":code,key="znack.registration.reason."+raw,detail=I18nService.getInstance().tr(key);
        if(detail.equals(key))detail=reason(raw);
        if(!detail.equals(raw)&&raw.matches("[A-Z][A-Z0-9_]{1,100}"))detail+=" ["+raw+"]";
        return I18nService.getInstance().tr(retained?"znack.registration.latest_read_unknown":"znack.registration.sources_required")+" · "+detail;
    }
    static String reason(String raw){
        String value=raw==null?"":raw;
        var country=com.vncode.app.shared.CatalogReadFailure.country(value);
        if(country.isPresent())return country.get().message()+"\n["+country.get().code()+"]";
        for(String code:new String[]{"CATALOG_REGISTRATION_SCHEMA_UNAVAILABLE","CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE","CATALOG_COUNTRY_DICTIONARY_INVALID","CATALOG_FEED_RESPONSE_INVALID","CATALOG_SIGNING_REVIEW_REQUIRED","CATALOG_SIGNING_ACKNOWLEDGEMENT_INVALID","ZNACK_INVALID_ATTRIBUTE_VALUE","CATALOG_NEW_GTIN_CONFIRMATION_REQUIRED","CATALOG_EXISTING_CARD_NOT_CONFIRMED","ZNACK_BATCH_PAUSED","ZNACK_BATCH_CANCELLED","ZNACK_BATCH_RETRY_LIMIT_REACHED","SIGNING_REVIEW_TOO_LARGE","SIGNING_REVIEW_REQUIRED","CATALOG_ENDPOINT_INVALID","CATALOG_EXISTING_CARD_LOOKUP_CLOSED","CATALOG_EXISTING_CARD_LOOKUP_INVALID","CATALOG_EXISTING_CARD_VARIANT_UNVERIFIED","CATALOG_EXISTING_CARD_VARIANT_CONFLICT","CATALOG_EXISTING_CARD_AMBIGUOUS","CATALOG_EXISTING_CARD_READBACK_REQUIRED","CATALOG_LIST_RANGE_UNSUPPORTED","CATALOG_LIST_RESPONSE_INCOMPLETE","SYNC_REPORT_CHANGED_RETRY","GTIN_CONFIRM_SELECTED","GTIN_WAIT_READBACK","GTIN_RETRY_READBACK","GTIN_REFRESH_PRODUCTS","GTIN_REVIEW_SOURCE","GTIN_READBACK_COMPLETE","GTIN_VARIANT_IDENTITY_UNVERIFIED","MARKETPLACE_REQUEST_REJECTED","unverified_catalog_gtin","UNVERIFIED_CATALOG_GTIN"}){
            String key="znack.registration.reason."+code,translated=I18nService.getInstance().tr(key);
            if(!translated.equals(key))value=value.replace(code,translated);
        }
        for(String code:new String[]{"GTIN_READBACK_PENDING","GTIN_MISSING","SOURCE_CARD_NOT_VERIFIED","SOURCE_REGISTRATION_OWNER_CHANGED","SOURCE_PUBLISHED_NOT_CONFIRMED","CATALOG_FEED_ERROR","CATALOG_CARD_DRAFT","CATALOG_CARD_IN_MODERATION","CATALOG_REQUEST_ACCEPTED_CARD_PENDING","CATALOG_READBACK_RETRY_PENDING","CATALOG_FEED_IDENTITY_NOT_CONFIRMED","CATALOG_IDENTITY_CHANGED","CATALOG_PUBLICATION_ID_CHANGED","CATALOG_PUBLICATION_PENDING","CATALOG_CARD_REJECTED_DETAILS_UNAVAILABLE","ZNACK_BATCH_SUBMITTING","ZNACK_PREPARING_CARD","ZNACK_CARD_READY","ZNACK_SUBMITTING_SAVED_PAYLOAD","ZNACK_BATCH_SUBMITTED","ZNACK_BATCH_NEEDS_ACTION","REGISTRATION_MISSING_REQUIRED_FIELDS","REGISTRATION_DOCUMENT_REVIEW_REQUIRED","REGISTRATION_REVIEW_REQUIRED","REGISTRATION_NOT_SUBMITTED","REGISTRATION_BUSY","NETWORK_RETRY","GTIN_ALLOCATION_READBACK_REQUIRED","CATALOG_FEED_READBACK_REQUIRED","SOURCE_CARD_ACTION_CHANGED","ZNACK_REGISTRATION_BATCH_ALREADY_RUNNING","ZNACK_REQUIRED_ATTRIBUTES_MISSING","ZNACK_PREFLIGHT_READ_FAILED","ZNACK_REVIEWED_VALUES_CHANGED","ZNACK_DOCUMENT_TNVED_MISMATCH","ZNACK_DOCUMENT_TNVED_SCOPE_UNKNOWN","ZNACK_PRODUCT_DOCUMENT_REVIEW_REQUIRED","ZNACK_MISSING_PRODUCT_DOCUMENT","ZNACK_CATEGORY_REVIEW_REQUIRED","GTIN_VARIANT_CONFLICT","GTIN_VARIANT_UNVERIFIED"}){
            String key="znack.registration.reason."+code,translated=I18nService.getInstance().tr(key);
            if(!translated.equals(key))value=value.replace(code,translated);
        }
        return CatalogFeedback.display(value);
    }
    static void show(String article,String gtin,String feed,String status,String error){
        AlertService.showDetailedError(article+" · "+status,text(article,gtin,feed,status,error));
    }
    static boolean confirmCancelPending(){
        var i18n=I18nService.getInstance();var alert=new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,i18n.tr("znack.registration.confirm_cancel_pending"),javafx.scene.control.ButtonType.OK,javafx.scene.control.ButtonType.CANCEL);
        alert.setHeaderText(i18n.tr("gtinsync.cancel"));return alert.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL)==javafx.scene.control.ButtonType.OK;
    }
    private static String value(String value){return value==null||value.isBlank()?"—":value;}
}
