package com.vncode.app.integration.znack.registration;

import java.util.Locale;

/** Correct a known rejected request only; an in-flight feed must never be replayed. */
final class CatalogPackingPolicy {
    private CatalogPackingPolicy() {}
    static Boolean correction(ZnackNationalCatalogService.FeedProgress progress,boolean current) {
        if(!CatalogFeedback.confirmedRejection(progress))return null;
        String message=progress.errorMessage().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").strip();
        boolean forbidden=message.contains("is kit")&&(message.contains("не допускается")||message.contains("исключите параметр"));
        boolean required=message.contains("карточ")&&message.contains("создан")&&message.contains("типом комплект");
        if(forbidden==required)return null;
        boolean desired=required;return desired!=current?desired:null;
    }
}
