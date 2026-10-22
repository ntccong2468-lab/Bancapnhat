package com.vncode.app.integration.znack.registration;

import java.util.Locale;

/** Correct a known rejected request only; an in-flight feed must never be replayed. */
final class CatalogPackingPolicy {
    private CatalogPackingPolicy() {}
    /** Only server-proven optional-image/packaging errors can be corrected automatically.
     * Other field errors and unknown/in-flight outcomes must stay visible for seller review. */
    static com.google.gson.JsonObject correctedPayload(ZnackNationalCatalogService.FeedProgress progress,com.google.gson.JsonObject payload) {
        if(!CatalogFeedback.confirmedRejection(progress)||progress.errors().isEmpty())return null;
        boolean kit=payload.has("is_kit")&&payload.get("is_kit").getAsBoolean();
        Boolean packing=correction(progress,kit);
        boolean removeImage=payload.has("good_images")&&progress.errors().stream().anyMatch(CatalogPackingPolicy::imageUnavailable);
        if(packing==null&&!removeImage)return null;
        for(String error:progress.errors())if(!imageUnavailable(error)&&correction(new ZnackNationalCatalogService.FeedProgress("Rejected",null,java.util.List.of(error)),kit)==null)return null;
        var corrected=payload.deepCopy();
        if(packing!=null){if(packing)corrected.addProperty("is_kit",true);else corrected.remove("is_kit");}
        if(removeImage)corrected.remove("good_images");
        return corrected;
    }
    static boolean imageUnavailable(String message) {
        String text=message==null?"":message.toLowerCase(Locale.ROOT);
        return text.contains("изображение не доступно по url")||text.contains("изображение недоступно по url");
    }

    static Boolean correction(ZnackNationalCatalogService.FeedProgress progress,boolean current) {
        if(!CatalogFeedback.confirmedRejection(progress))return null;
        String message=progress.errorMessage().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").strip();
        boolean forbidden=message.contains("is kit")&&(message.contains("не допускается")||message.contains("исключите параметр"));
        boolean required=message.contains("карточ")&&message.contains("создан")&&message.contains("типом комплект");
        if(forbidden==required)return null;
        boolean desired=required;return desired!=current?desired:null;
    }
}
