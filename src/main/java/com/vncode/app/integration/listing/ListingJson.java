package com.vncode.app.integration.listing;

import com.google.gson.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
public final class ListingJson {
    private ListingJson(){}
    public static JsonObject object(JsonElement e){return e!=null&&e.isJsonObject()?e.getAsJsonObject():new JsonObject();}
    public static JsonObject obj(JsonObject o,String k){return object(o.get(k));}
    public static JsonArray array(JsonObject o,String k){var e=o.get(k);return e!=null&&e.isJsonArray()?e.getAsJsonArray():new JsonArray();}
    public static String text(JsonObject o,String k){var e=o.get(k);return e!=null&&e.isJsonPrimitive()?e.getAsString():"";}
    public static List<String> strings(JsonArray a){var r=new ArrayList<String>();for(var e:a)if(e.isJsonPrimitive())r.add(e.getAsString());return List.copyOf(r);}
    public static BigDecimal decimal(JsonObject o,String k){String s=text(o,k);try{return s.isBlank()?null:new BigDecimal(s);}catch(NumberFormatException e){return null;}}
    public static LocalDate date(String s){try{return s.isBlank()?null:LocalDate.parse(s.substring(0,10));}catch(RuntimeException e){return null;}}
    public static Map<String,String> metadata(JsonObject o){var r=new LinkedHashMap<String,String>();o.entrySet().forEach(e->{if(e.getValue().isJsonPrimitive())r.put(e.getKey(),e.getValue().getAsString());});return Map.copyOf(r);}
}
