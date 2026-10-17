package com.vncode.app.features.listingcopy;

import com.google.gson.*;
import com.vncode.app.features.products.ProductSnapshot;
import java.util.*;

/** Fields outside the verified create contract remain visible to the seller before confirmation. */
public final class CopySourceCoverage {
    private CopySourceCoverage(){}
    public static List<CopyIssue> unsupported(ProductSnapshot source){
        var fields=new LinkedHashSet<String>();
        try{
            var raw=JsonParser.parseString(source.metadata().getOrDefault("source.payload","{}")).getAsJsonObject();
            inspect(raw,fields);for(String nested:List.of("info","attributes"))if(raw.has(nested)&&raw.get(nested).isJsonObject())inspect(raw.getAsJsonObject(nested),fields);
        }catch(RuntimeException invalid){return List.of(new CopyIssue("SOURCE_COVERAGE_UNAVAILABLE","source",true));}
        return fields.stream().map(field->new CopyIssue("SOURCE_FIELD_UNSUPPORTED",field,false)).toList();
    }
    private static void inspect(JsonObject raw,Set<String> fields){
        for(String key:List.of("video","videos","video_cover","videos_cover","rich_content","richContent","images360","pdf_list")){
            var value=raw.get(key);if(value==null||value.isJsonNull())continue;
            if(value.isJsonArray()&&value.getAsJsonArray().isEmpty()||value.isJsonObject()&&value.getAsJsonObject().isEmpty()||value.isJsonPrimitive()&&value.getAsString().isBlank())continue;
            if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()&&!value.getAsBoolean())continue;
            fields.add(key);
        }
    }
}
