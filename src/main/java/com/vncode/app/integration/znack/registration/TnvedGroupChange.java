package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import java.util.Set;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;

/** A four-digit group change requires a new card, never an update to the old GTIN. */
public final class TnvedGroupChange {
    private TnvedGroupChange(){}
    public static boolean changed(String oldCode,String newCode) {
        return oldCode!=null&&newCode!=null&&oldCode.matches("[0-9]{4,10}")&&newCode.matches("[0-9]{4,10}")&&!oldCode.substring(0,4).equals(newCode.substring(0,4));
    }
    public static String payloadCode(String payload) {
        try{var json=JsonParser.parseString(payload).getAsJsonObject();return json.has("tnved")?json.get("tnved").getAsString():"";}catch(RuntimeException invalid){return "";}
    }
    public static String snapshotCode(String payload) {
        try {
            var codes=new java.util.LinkedHashSet<String>();var json=JsonParser.parseString(payload).getAsJsonObject();
            for(var item:json.getAsJsonArray("attributes")){var field=item.getAsJsonObject();if(!com.vncode.app.features.products.ProductFieldNames.matches("tnved",field.get("name").getAsString()))continue;
                for(var value:field.getAsJsonArray("values"))if(value.isJsonPrimitive()&&value.getAsString().matches("[0-9]{4,10}"))codes.add(value.getAsString());}
            return codes.size()==1?codes.getFirst():"";
        }catch(RuntimeException invalid){return "";}
    }
    public static boolean replaceable(Status status) {
        return Set.of(Status.PUBLISHED,Status.ERROR,Status.FEED_SUBMITTED,Status.PROCESSING,Status.READY_TO_SIGN).contains(status);
    }
}
