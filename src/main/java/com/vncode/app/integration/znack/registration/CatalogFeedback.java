package com.vncode.app.integration.znack.registration;

import com.vncode.app.integration.znack.ZnackSanitizer;
import java.util.Locale;

public final class CatalogFeedback {
    private CatalogFeedback(){}
    public static boolean interrupted(String message) {
        String value=message==null?"":message.strip().toLowerCase(Locale.ROOT);
        return value.equals("registration interrupted")||value.equals("registration_interrupted");
    }
    public static String rejection(ZnackNationalCatalogService.FeedProgress progress) {
        String detail=display(progress.errorMessage());return detail.isBlank()?"CATALOG_REJECTED":"CATALOG_REJECTED: "+detail;
    }
    public static String display(String text) {
        return ZnackSanitizer.message(text).replaceAll("01[0-9]{14}21[^\\s]{13,}","[KIZ]");
    }
}
