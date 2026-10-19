package com.vncode.app.integration.listing;

import com.google.gson.JsonObject;
import com.vncode.app.integration.znack.ZnackSanitizer;
import com.vncode.app.models.Shop;
import java.io.IOException;

/** An explicit seller API rejection, distinct from an unknown transport outcome. */
public final class ListingRejectedException extends IOException {
    public ListingRejectedException(String reason){super(reason);}
    public static void requireWbAccepted(Shop shop,JsonObject response)throws ListingRejectedException {
        if(response.has("error")&&response.get("error").isJsonPrimitive()
                &&"true".equalsIgnoreCase(response.get("error").getAsString())){
            String reason=ListingJson.text(response,"errorText");
            if(reason.isBlank())reason="WB rejected the product update";
            throw new ListingRejectedException(safe(shop,reason));
        }
    }
    public static String safe(Shop shop,String reason){
        String value=reason==null?"":reason;
        if(shop.getApiKey()!=null&&!shop.getApiKey().isBlank())value=value.replace(shop.getApiKey(),"[REDACTED]");
        return ZnackSanitizer.message(value);
    }
}
