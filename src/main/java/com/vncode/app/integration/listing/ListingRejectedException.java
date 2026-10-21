package com.vncode.app.integration.listing;

import com.google.gson.JsonObject;
import com.vncode.app.integration.znack.ZnackSanitizer;
import com.vncode.app.models.Shop;
import java.io.IOException;

/** An explicit seller API rejection, distinct from an unknown transport outcome. */
public final class ListingRejectedException extends IOException {
    private final com.vncode.app.integration.marketplace.Marketplace marketplace;
    private final int statusCode;
    public ListingRejectedException(String reason){this(null,0,reason);}
    public ListingRejectedException(com.vncode.app.integration.marketplace.Marketplace marketplace,int statusCode,String reason){super(reason);this.marketplace=marketplace;this.statusCode=statusCode;}
    public int statusCode(){return statusCode;}
    public String code(){
        if(marketplace==null||statusCode==0)return "MARKETPLACE_REQUEST_REJECTED";
        String prefix=marketplace==com.vncode.app.integration.marketplace.Marketplace.OZON?"OZON":"WB";
        return prefix+((statusCode==401||statusCode==403)?"_AUTH_REQUIRED":"_HTTP_"+statusCode);
    }
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
