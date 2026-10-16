package com.vncode.app.features.tnved;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Factual tariff reference, separate from the seller's versioned/verified imported catalog. */
public final class FashionTnvedCatalog {
    public record Entry(String code,String name,String gender,List<String> materials,String fabric,String chapter,List<String> categories){
        public Entry {materials=List.copyOf(materials);categories=List.copyOf(categories);fabric=Objects.toString(fabric,"");}
        @Override public String toString(){return code+" · "+name;}
    }
    private static final class Loaded {static final List<Entry> ITEMS=load();}
    public List<Entry> entries(){return Loaded.ITEMS;}
    public List<Entry> search(String query,Set<String> categories,String gender,String material,String fabric){
        String text=normalize(query);
        return entries().stream().filter(e->text.isBlank()||normalize(e.code()+" "+e.name()+" "+String.join(" ",e.categories())).contains(text))
                .filter(e->categories.isEmpty()||e.categories().stream().anyMatch(categories::contains))
                .filter(e->gender==null||gender.isBlank()||e.gender().equals(gender)||e.gender().equals("Унисекс"))
                .filter(e->material==null||material.isBlank()||e.materials().contains(material)||(Set.of("Синтетика","Искусственные нити").contains(material)&&e.materials().contains("Химические нити")))
                .filter(e->fabric==null||fabric.isBlank()||e.fabric().equals(fabric)).toList();
    }
    public List<String> categories(){return entries().stream().flatMap(e->e.categories().stream()).distinct().sorted().toList();}
    public List<String> materials(){return entries().stream().flatMap(e->e.materials().stream()).distinct().sorted().toList();}
    public List<String> genders(){return entries().stream().map(Entry::gender).distinct().sorted().toList();}
    public List<String> fabrics(){return entries().stream().map(Entry::fabric).filter(v->!v.isBlank()).distinct().sorted().toList();}
    public static String normalize(String s){return java.text.Normalizer.normalize(Objects.toString(s,""),java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё','е').strip();}
    private static List<Entry> load(){
        try(var in=FashionTnvedCatalog.class.getResourceAsStream("/com/vncode/app/tnved/fashion-reference.json")){
            if(in==null)throw new IOException("Tariff reference unavailable");
            var root=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();var out=new ArrayList<Entry>();var seen=new HashSet<String>();
            for(var item:root.getAsJsonArray("items")){
                var o=item.getAsJsonObject();String code=o.get("code").getAsString();if(!code.matches("[0-9]{10}")||!seen.add(code))throw new IOException("Invalid tariff reference identity");
                out.add(new Entry(code,o.get("name").getAsString(),o.get("gender").getAsString(),strings(o,"materials"),o.get("fabric").isJsonNull()?"":o.get("fabric").getAsString(),o.get("chapter").getAsString(),strings(o,"categories")));
            }
            return List.copyOf(out);
        }catch(IOException|RuntimeException failure){throw new IllegalStateException("TNVED_REFERENCE_UNAVAILABLE",failure);}
    }
    private static List<String> strings(JsonObject o,String key){return o.getAsJsonArray(key).asList().stream().map(JsonElement::getAsString).toList();}
}
