package com.vncode.app.integration.znack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vncode.app.integration.znack.ZnackModels.KizCode;
import java.util.*;

/** Read-only proof for the exact CIS set requested from True API; status counts alone prove nothing. */
final class ZnackCisReadback {
    private static final String UNKNOWN="Chưa xác minh đầy đủ từng mã KIZ từ Честный знак; giữ nguyên kết quả đã lưu và chờ đối chiếu.";
    record Result(int applied,int introduced,int pending,boolean missingName,String reason) {}
    private ZnackCisReadback() {}

    static boolean validRequests(List<KizCode> codes,String gtin,Long orderId){
        if(codes==null||codes.isEmpty())return false;
        var seen=new HashSet<String>();
        try{String expected=GtinNormalizer.normalize(gtin);for(var code:codes){
            if(code==null||orderId!=null&&code.orderId()!=orderId||!GtinNormalizer.normalize(code.gtin()).equals(expected))return false;
            String cis=ZnackCisNormalizer.forTrueApi(code.rawCode());
            if(!seen.add(cis)||!embeddedGtinMatches(cis,expected))return false;
        }return true;}catch(RuntimeException invalid){return false;}
    }

    static Result inspect(JsonElement response,List<KizCode> requested,String gtin){
        int total=requested.size();
        if(!validRequests(requested,gtin,null))return unknown(total);
        String expected=GtinNormalizer.normalize(gtin);
        var wanted=new HashSet<String>();requested.forEach(code->wanted.add(ZnackCisNormalizer.forTrueApi(code.rawCode())));
        JsonArray entries=new JsonArray();
        if(response!=null&&response.isJsonArray())response.getAsJsonArray().forEach(entries::add);
        else if(response!=null&&response.isJsonObject())entries.add(response);
        else return unknown(total);
        var seen=new HashSet<String>();int applied=0,introduced=0,pending=0;boolean missingName=false;String reason=null;
        for(var element:entries){
            if(!element.isJsonObject())return unknown(total);
            JsonObject entry=element.getAsJsonObject();
            if(entry.has("cisInfo")&&!entry.get("cisInfo").isJsonObject())return unknown(total);
            JsonObject info=entry.has("cisInfo")&&entry.get("cisInfo").isJsonObject()?entry.getAsJsonObject("cisInfo"):entry;
            if(!stringField(info,"requestedCis")||!stringField(entry,"requestedCis")||!stringField(info,"cis")||!stringField(info,"gtin"))return unknown(total);
            String innerRequested=text(info,"requestedCis"),outerRequested=text(entry,"requestedCis"),actual=text(info,"cis");
            String cis="";
            try{for(String value:List.of(innerRequested,outerRequested,actual))if(!value.isBlank()){
                String normalized=ZnackCisNormalizer.forTrueApi(value);
                if(!cis.isBlank()&&!cis.equals(normalized))return unknown(total);cis=normalized;
            }}catch(RuntimeException invalid){return unknown(total);}
            if(cis.isBlank()||!wanted.contains(cis)||!seen.add(cis))return unknown(total);
            String observedGtin=text(info,"gtin");
            if(observedGtin.isBlank()){
                if(!hasEmbeddedGtin(cis))return unknown(total);
            }else try{if(!GtinNormalizer.normalize(observedGtin).equals(expected))return unknown(total);}catch(IllegalArgumentException invalid){return unknown(total);}
            if(!embeddedGtinMatches(cis,expected))return unknown(total);
            if(ZnackErrorMessages.hasCisLookupError(entry)||info!=entry&&ZnackErrorMessages.hasCisLookupError(info)){pending++;if(reason==null)reason=UNKNOWN;continue;}
            String status=text(info,"status"),extra=text(info,"statusEx","status_ex");
            if("INTRODUCED".equalsIgnoreCase(status))introduced++;
            else if("APPLIED".equalsIgnoreCase(status)&&(extra.isBlank()||"EMPTY".equalsIgnoreCase(extra)))applied++;
            else {pending++;if(reason==null)reason=pendingStatus(status,extra);continue;}
            String name=text(info,"productName");if(name.isBlank()||"-".equals(name.strip()))missingName=true;
        }
        if(!seen.equals(wanted))return unknown(total);
        return new Result(applied,introduced,pending,missingName,reason);
    }

    private static String pendingStatus(String status,String extra){
        String remote=status.matches("[A-Z][A-Z0-9_]{0,60}")?status:"";
        if(extra.matches("[A-Z][A-Z0-9_]{0,60}")&&!"EMPTY".equals(extra))remote+=(remote.isBlank()?"":" / ")+extra;
        return remote.isBlank()?UNKNOWN:"KIZ chưa đủ điều kiện lưu thông; trạng thái Честный знак: "+remote+". Giữ nguyên mã đã mua và chờ kiểm tra lại.";
    }
    private static boolean hasEmbeddedGtin(String cis){return cis.length()>=18&&cis.startsWith("01")&&cis.startsWith("21",16)&&cis.substring(2,16).matches("[0-9]{14}");}
    private static boolean embeddedGtinMatches(String cis,String gtin){return !hasEmbeddedGtin(cis)||cis.substring(2,16).equals(gtin);}
    private static Result unknown(int total){return new Result(0,0,total,false,UNKNOWN);}
    private static boolean stringField(JsonObject value,String key){var field=value.get(key);return field==null||field.isJsonNull()||field.isJsonPrimitive()&&field.getAsJsonPrimitive().isString();}
    private static String text(JsonObject value,String...keys){for(String key:keys){var entry=value.get(key);if(entry!=null&&!entry.isJsonNull()){if(!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isString())return "";return entry.getAsString();}}return "";}
}
