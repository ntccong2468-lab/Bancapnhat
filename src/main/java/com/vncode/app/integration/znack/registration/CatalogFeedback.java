package com.vncode.app.integration.znack.registration;

import com.vncode.app.integration.znack.ZnackSanitizer;
import java.util.Locale;

public final class CatalogFeedback {
    private CatalogFeedback(){}
    public static boolean notFound(Throwable failure){
        for(Throwable error=failure;error!=null;error=error.getCause())if(error instanceof com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException api&&api.statusCode()==404)return true;
        return false;
    }
    public static boolean rateLimited(Throwable failure){
        for(Throwable error=failure;error!=null;error=error.getCause())
            if(error instanceof com.vncode.app.shared.RateLimitDeferral.Deferred
                    ||error instanceof com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException api&&api.statusCode()==429)return true;
        return false;
    }
    /** Only a persisted feed may use this classification, and only for readback. */
    public static boolean retryableRead(Throwable failure){
        for(Throwable error=failure;error!=null;error=error.getCause()){
            if(error instanceof com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException api)
                return api.statusCode()==408||api.statusCode()==425||api.statusCode()==429||api.statusCode()>=500;
            if(error instanceof SecurityException||error instanceof ZnackRegistrationScope.ScopeChangedException
                    ||error instanceof com.vncode.app.features.license.LicenseGate.LicenseDeniedException)return false;
        }
        for(Throwable error=failure;error!=null;error=error.getCause())if(error instanceof java.io.IOException||error instanceof InterruptedException||error instanceof com.vncode.app.shared.RateLimitDeferral.Deferred)return true;
        return false;
    }
    public static boolean confirmedRejection(ZnackNationalCatalogService.FeedProgress progress){
        return progress.failed()&&java.util.Set.of("rejected","failed","error").contains(progress.status().toLowerCase(Locale.ROOT));
    }
    public static boolean interrupted(String message) {
        String value=message==null?"":message.strip().toLowerCase(Locale.ROOT);
        return value.equals("registration interrupted")||value.equals("registration_interrupted");
    }
    public static String rejection(ZnackNationalCatalogService.FeedProgress progress) {
        String detail=display(progress.errorMessage());return detail.isBlank()?"CATALOG_REJECTED":"CATALOG_REJECTED: "+detail;
    }
    public static String stage(ZnackNationalCatalogService.FeedProgress progress){return switch(progress.status().toLowerCase(Locale.ROOT)){
        case "carddraft"->"CATALOG_CARD_DRAFT";
        case "cardmoderation"->"CATALOG_CARD_IN_MODERATION";
        case "publicationpending"->"CATALOG_PUBLICATION_PENDING";
        case "cardnotfound"->"CATALOG_REQUEST_ACCEPTED_CARD_PENDING";
        default->null;
    };}
    public static String display(String text) {
        return ZnackSanitizer.message(text).replaceAll("01[0-9]{14}21[^\\s]{13,}","[KIZ]");
    }
}
