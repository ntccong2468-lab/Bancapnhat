package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Versioned, deterministic default; never sends seller data to an AI service. */
public final class RegistrationTemplate {
    private static final RegistrationTemplate DEFAULT=load();
    private final JsonObject specification;
    private RegistrationTemplate(JsonObject specification){this.specification=specification;}
    public static RegistrationTemplate defaults(){return DEFAULT;}
    public String id(){return specification.get("id").getAsString();}
    public String prompt(){return specification.get("prompt").getAsString();}
    public JsonObject build(String gtin,Draft draft,String imageUrl){
        var bindings=Map.<String,JsonElement>of(
            "${gtin}",new JsonPrimitive(clean(gtin)),"${tnved}",new JsonPrimitive(clean(draft.feedTnved())),
            "${brand}",new JsonPrimitive(clean(draft.brand())),"${good_name}",new JsonPrimitive(clean(draft.goodName())),
            "${category_id}",new JsonPrimitive(draft.categoryId()));
        var payload=bind(specification.getAsJsonObject("payload"),bindings).getAsJsonObject();
        return ZnackNationalCatalogService.fillTemplate(payload,clean(gtin),draft,clean(imageUrl));
    }
    private static JsonElement bind(JsonElement node,Map<String,JsonElement> values){
        if(node.isJsonObject()){
            var result=new JsonObject();node.getAsJsonObject().entrySet().forEach(e->result.add(e.getKey(),bind(e.getValue(),values)));return result;
        }
        if(node.isJsonArray()){
            var result=new JsonArray();node.getAsJsonArray().forEach(e->result.add(bind(e,values)));return result;
        }
        if(node.isJsonPrimitive()&&node.getAsJsonPrimitive().isString())return values.getOrDefault(node.getAsString(),node).deepCopy();
        return node.deepCopy();
    }
    static String clean(String text){
        if(text==null)return "";int first=0,last=text.length();
        while(first<last){int cp=text.codePointAt(first);if(!space(cp))break;first+=Character.charCount(cp);}
        while(last>first){int cp=text.codePointBefore(last);if(!space(cp))break;last-=Character.charCount(cp);}
        return text.substring(first,last);
    }
    private static boolean space(int cp){return Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xFEFF||cp==0x200B;}
    private static RegistrationTemplate load(){
        try(var stream=RegistrationTemplate.class.getResourceAsStream("default-registration-template.json")){
            if(stream==null)throw new IOException("Missing registration template");
            var json=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            if(json.get("version").getAsInt()!=1||json.get("prompt").getAsString().isBlank()||!json.get("payload").isJsonObject())throw new IOException("Invalid registration template");
            return new RegistrationTemplate(json);
        }catch(IOException|RuntimeException error){throw new IllegalStateException("CATALOG_DEFAULT_TEMPLATE_INVALID",error);}
    }
}
