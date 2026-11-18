package com.vncode.app.ui.gs1;

import com.google.gson.*;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import java.util.*;

/** Catalog business checklists serialize label/value/checked, while dictionaries provide id/text.
 * Only an exact unique dictionary identifier can repair legacy id/text entries. */
public final class Gs1JoinChecklist {
    private Gs1JoinChecklist() {}

    public static JsonArray normalize(JsonArray observed,List<NationalCatalogGs1Client.Choice> dictionary){
        Objects.requireNonNull(observed);Objects.requireNonNull(dictionary);
        var choices=new LinkedHashMap<String,NationalCatalogGs1Client.Choice>();
        for(var choice:dictionary){
            if(choice==null||choice.id()==null||choice.id().isBlank()||choice.text()==null||choice.text().isBlank())throw invalid();
            if(choices.putIfAbsent(choice.id(),choice)!=null)throw ambiguous();
        }
        var result=new JsonArray();var seen=new HashSet<String>();
        var matched=new HashMap<String,JsonObject>();var unmatched=new ArrayList<JsonObject>();
        for(var item:observed){
            if(!item.isJsonObject())throw invalid();
            var entry=item.getAsJsonObject().deepCopy();
            String canonical=text(entry,"value"),legacy=text(entry,"id");
            if(!canonical.isBlank()&&!legacy.isBlank()&&!canonical.equals(legacy)&&choices.containsKey(canonical)&&choices.containsKey(legacy))throw ambiguous();
            String identifier=canonical.isBlank()?legacy:canonical;
            if(!identifier.isBlank()&&!seen.add(identifier))throw ambiguous();
            var verified=choices.get(identifier);
            if(verified!=null){entry.addProperty("value",verified.id());entry.addProperty("label",verified.text());if(!entry.has("checked"))entry.addProperty("checked",false);matched.put(verified.id(),entry);}
            // Unrecognized server data is retained for review. Its label/id is never fuzzy-matched.
            else unmatched.add(entry);
        }
        // The official cabinet restores checked flags by dictionary position plus exact value.
        for(var choice:choices.values()){
            var entry=matched.get(choice.id());
            if(entry==null){entry=new JsonObject();entry.addProperty("label",choice.text());entry.addProperty("value",choice.id());entry.addProperty("checked",false);}
            result.add(entry);
        }
        unmatched.forEach(result::add);
        return result;
    }
    public static String label(JsonObject entry){
        for(String field:List.of("label","text","value","id")){String value=text(entry,field);if(!value.isBlank())return value;}
        return "";
    }
    private static String text(JsonObject entry,String key){
        var value=entry.get(key);return value!=null&&value.isJsonPrimitive()&&(value.getAsJsonPrimitive().isString()||value.getAsJsonPrimitive().isNumber())?value.getAsString():"";
    }
    private static IllegalArgumentException ambiguous(){return new IllegalArgumentException("GS1_CHECKLIST_AMBIGUOUS");}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("GS1_CHECKLIST_INVALID");}
}
