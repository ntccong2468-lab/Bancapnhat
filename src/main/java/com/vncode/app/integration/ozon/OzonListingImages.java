package com.vncode.app.integration.ozon;

import com.google.gson.*;
import java.util.*;

/** The explicitly reported primary image wins; remaining images retain their source order. */
final class OzonListingImages {
    private OzonListingImages(){}
    static List<String> ordered(JsonObject info,JsonObject attributes){
        var result=new LinkedHashSet<String>();add(result,info.get("primary_image"));if(result.isEmpty())add(result,attributes.get("primary_image"));
        var images=attributes.get("images");if(images==null||images.isJsonNull()||images.isJsonArray()&&images.getAsJsonArray().isEmpty())images=info.get("images");
        add(result,images);return List.copyOf(result);
    }
    private static void add(Set<String> into,JsonElement value){
        if(value==null||value.isJsonNull())return;
        if(value.isJsonArray()){for(var item:value.getAsJsonArray())add(into,item);return;}
        if(value.isJsonObject()){var object=value.getAsJsonObject();add(into,object.has("url")?object.get("url"):object.get("file_name"));return;}
        if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()&&!value.getAsString().isBlank())into.add(value.getAsString());
    }
}
