package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.ZnackModels.GoodsDocument;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** A valid date alone does not prove that a permit covers this product's customs code. */
public final class RegistrationPermitValidator {
    private RegistrationPermitValidator(){}
    /** Registry tariff metadata is advisory, not an undocumented feed rejection rule. */
    public static boolean advisory(String problem){
        return problem!=null&&(problem.contains("ZNACK_DOCUMENT_TNVED_MISMATCH")||problem.contains("ZNACK_DOCUMENT_TNVED_SCOPE_UNKNOWN"));
    }
    public static long attribute(GoodsDocument document){return switch(document.type()){
        case "CONFORMITY_DECLARATION"->23557;case "CONFORMITY_CERTIFICATE"->23561;
        default->throw new IllegalArgumentException("ZNACK_DOCUMENT_TYPE_UNSUPPORTED");};}
    public static JsonObject request(GoodsDocument document){
        var item=new JsonObject();item.addProperty("attr_id",attribute(document));
        item.addProperty("number",document.number());item.addProperty("from_date",document.date());return item;
    }
    /** Empty means verified; unresolved registry results remain unresolved, never valid. */
    public static String problem(JsonElement response,GoodsDocument selected,String tnved,LocalDate today){
        if(ZnackPermitDocumentParser.registryLookupPending(response))return "ZNACK_DOCUMENT_REGISTRY_PENDING";
        var result=response!=null&&response.isJsonObject()?response.getAsJsonObject():new JsonObject();
        if(result.has("result")&&result.get("result").isJsonObject())result=result.getAsJsonObject("result");
        var entries=result.has("documents")&&result.get("documents").isJsonArray()?result.getAsJsonArray("documents"):new JsonArray();
        JsonObject found=null;
        for(var item:entries)if(item.isJsonObject()){
            var doc=item.getAsJsonObject();
            if(text(doc,"number").equals(selected.number())&&text(doc,"from_date").equals(selected.date())
                    &&text(doc,"attr_id").equals(Long.toString(attribute(selected)))){
                if(found!=null)return "ZNACK_DOCUMENT_REGISTRY_AMBIGUOUS";found=doc;
            }
        }
        if(found==null)return "ZNACK_DOCUMENT_REGISTRY_NOT_FOUND";
        if(!ZnackPermitDocumentParser.activeFromRegistry(response).contains(selected))return "ZNACK_DOCUMENT_INACTIVE";
        try{
            if(LocalDate.parse(text(found,"from_date")).isAfter(today))return "ZNACK_DOCUMENT_NOT_YET_VALID";
            if(!text(found,"to_date").isBlank()&&LocalDate.parse(text(found,"to_date")).isBefore(today))return "ZNACK_DOCUMENT_EXPIRED";
        }catch(java.time.DateTimeException invalid){return "ZNACK_DOCUMENT_REGISTRY_DATE_INVALID";}
        String scope=text(found,"product_tnved");
        if(scope.isBlank())return "ZNACK_DOCUMENT_TNVED_SCOPE_UNKNOWN";
        return covers(scope,tnved)?"":"ZNACK_DOCUMENT_TNVED_MISMATCH: TN VED "+tnved+"; giấy tờ chỉ bao phủ "+scope;
    }
    static boolean covers(String scope,String tnved){
        if(tnved==null||!tnved.matches("[0-9]{4,10}"))return false;
        var ranges=Pattern.compile("(?<![0-9])([0-9]{2,10})\\s*[-–—]\\s*([0-9]{2,10})(?![0-9])").matcher(scope);
        var remaining=new StringBuffer();
        while(ranges.find()){
            String lower=ranges.group(1),upper=ranges.group(2);
            if(lower.length()==upper.length()&&tnved.length()>=lower.length()){
                String prefix=tnved.substring(0,lower.length());
                if(prefix.compareTo(lower)>=0&&prefix.compareTo(upper)<=0)return true;
            }
            ranges.appendReplacement(remaining," ");
        }ranges.appendTail(remaining);
        var codes=Pattern.compile("(?<![0-9])[0-9]{2,10}(?![0-9])").matcher(remaining);
        while(codes.find())if(tnved.startsWith(codes.group()))return true;
        return false;
    }
    private static String text(JsonObject o,String key){return o.has(key)&&o.get(key).isJsonPrimitive()?o.get(key).getAsString().strip():"";}
}
