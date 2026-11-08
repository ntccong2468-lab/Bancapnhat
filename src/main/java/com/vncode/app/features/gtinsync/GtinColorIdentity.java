package com.vncode.app.features.gtinsync;

import com.google.gson.*;
import com.vncode.app.features.products.ProductFieldNames;
import java.util.*;

/** Complete observed color sets. Spelling aliases never remove a shade or a second color. */
public final class GtinColorIdentity {
    private GtinColorIdentity(){}
    public static String normalize(String value){
        var colors=new TreeSet<String>();
        for(String part:GtinMatchingService.normalize(value).split("\\s*[,;/|+]\\s*")){
            String color=base(part);if(!color.isBlank())colors.add(color);
        }
        return String.join(" / ",colors);
    }
    private static String base(String value){
        String text=value.replace('Ё','Е').replaceAll("[‐‑‒–—−]","-").replaceAll("\\s*-\\s*","-");
        return switch(text){
            case "NÂU","NAU","BROWN","КОРИЧНЕВЫЙ"->"BROWN";
            case "ĐEN","DEN","BLACK","ЧЕРНЫЙ"->"BLACK";
            case "TRẮNG","TRANG","WHITE","БЕЛЫЙ"->"WHITE";
            case "BE","BEIGE","БЕЖЕВЫЙ"->"BEIGE";
            case "GRAY","GREY","XÁM","XAM","СЕРЫЙ"->"СЕРЫЙ";
            case "GREEN","XANH LÁ","XANH LA","ЗЕЛЕНЫЙ"->"ЗЕЛЕНЫЙ";
            case "BLUE","XANH DƯƠNG","XANH DUONG","СИНИЙ"->"СИНИЙ";
            case "LIGHT GRAY","LIGHT GREY","СВЕТЛО СЕРЫЙ","СВЕТЛО-СЕРЫЙ"->"СВЕТЛО-СЕРЫЙ";
            case "DARK GRAY","DARK GREY","ТЕМНО СЕРЫЙ","ТЕМНО-СЕРЫЙ"->"ТЕМНО-СЕРЫЙ";
            case "LIGHT GREEN","СВЕТЛО ЗЕЛЕНЫЙ","СВЕТЛО-ЗЕЛЕНЫЙ"->"СВЕТЛО-ЗЕЛЕНЫЙ";
            case "DARK GREEN","ТЕМНО ЗЕЛЕНЫЙ","ТЕМНО-ЗЕЛЕНЫЙ"->"ТЕМНО-ЗЕЛЕНЫЙ";
            default->text;
        };
    }
    public static String value(Collection<String> observed){
        var colors=new LinkedHashMap<String,String>();
        for(String raw:observed){String key=normalize(raw);if(!key.isBlank())colors.putIfAbsent(key,raw.strip());}
        return String.join(" / ",colors.values());
    }
    /** SQL/native readers supply the same complete characteristic list, never just its first value. */
    public static String fromWbCharacteristics(String json){
        if(json==null||json.isBlank())return "";
        try{
            var attributes=JsonParser.parseString(json);if(!attributes.isJsonArray())return "";
            var colors=new ArrayList<String>();
            for(var raw:attributes.getAsJsonArray()){
                if(!raw.isJsonObject())return "";var attribute=raw.getAsJsonObject();
                if(!attribute.has("name")||!attribute.get("name").isJsonPrimitive()||!ProductFieldNames.matches("color",attribute.get("name").getAsString()))continue;
                var v=attribute.get("value");if(v==null||v.isJsonNull())return "";
                if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isString())v=JsonParser.parseString(v.getAsString());
                if(!collect(v,colors))return "";
            }
            return value(colors);
        }catch(JsonParseException|IllegalStateException malformed){return "";}
    }
    private static boolean collect(JsonElement value,List<String> colors){
        if(value.isJsonArray()){for(var item:value.getAsJsonArray())if(!collect(item,colors))return false;return true;}
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())return false;
        colors.add(value.getAsString());return true;
    }
}
