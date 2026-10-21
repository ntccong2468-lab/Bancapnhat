package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.integration.znack.ZnackProductCardAttributes;
import java.util.*;

/** Reverse mapping uses the same documented Catalog IDs as the registration builder. */
public final class CatalogProductDataParser {
    private CatalogProductDataParser(){}
    private static final Map<String,String> IDS=Map.of("13933","tnved","2483","material","14013","gender","36","color","35","size","2504","brand","2478","title","13914","article");
    private static final Map<String,List<String>> NAMES=Map.ofEntries(
        Map.entry("tnved",List.of("tnved","тнвэд","кодтнвэд","тнвэдеаэс")),
        Map.entry("material",List.of("composition","material","состав","материал","составсырья")),
        Map.entry("gender",List.of("gender","пол")),Map.entry("color",List.of("color","цвет")),
        Map.entry("size",List.of("size","размер")),Map.entry("brand",List.of("brand","бренд","товарныйзнак")),
        Map.entry("title",List.of("goodname","title","наименованиетовара","полноенаименованиетовара")),
        Map.entry("article",List.of("article","артикул","артикултовара","артикулпроизводителя")),
        Map.entry("country",List.of("country","странапроизводства","странапроисхождения")),
        Map.entry("description",List.of("description","описание")));
    public static CatalogProductData parse(JsonObject card) {
        var fields=new LinkedHashMap<String,LinkedHashSet<String>>();
        // @ keys are local observation metadata, not National Catalog request fields.
        // Keep the actual remote identity; never synthesize it from GTIN or a local feed checkpoint.
        String catalogId=catalogId(card.get("good_id"));if(!catalogId.isBlank())add(fields,"@catalog_id",List.of(catalogId));
        add(fields,"@catalog_status",values(card.get("good_status")));
        var signed=card.get("good_signed");
        if(signed!=null&&!signed.isJsonNull()){
            if(!signed.isJsonPrimitive()||!signed.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("CATALOG_SIGNATURE_STATUS_UNVERIFIED");
            add(fields,"@catalog_signed",List.of(Boolean.toString(signed.getAsBoolean())));
        }
        add(fields,"@catalog_detailed_status",values(card.get("good_detailed_status")));
        for(var entry:Map.of("tnved","tnved","brand","brand","title","good_name","description","description","article","article","color","color","size","size").entrySet())add(fields,entry.getKey(),values(card.get(entry.getValue())));
        var attrs=new ArrayList<CatalogProductData.Attribute>();var docs=new LinkedHashMap<String,CatalogProductData.Document>();
        for(var a:ZnackProductCardAttributes.from(card)) {
            String id=ZnackProductCardAttributes.id(a),name=ZnackProductCardAttributes.name(a);
            if("35".equals(id))add(fields,"@size_type",values(a.get("attr_value_type")));
            if(Set.of("23557","23561","23765").contains(id)) {
                var shown=object(a,"showValue");String number=text(shown,"number"),from=text(shown,"dateFrom","date_from"),until=text(shown,"dateTo","date_to");
                if(number.isBlank())number=text(a,"certificate_number","certificateNumber");
                if(from.isBlank())from=text(a,"certificate_issued_date","certificateIssuedDate");
                String raw=text(a,"attr_value","attrValue");var parts=raw.split(":::",2);
                if(parts.length==2){if(number.isBlank())number=parts[0].strip();if(from.isBlank())from=parts[1].strip();}
                if(!number.isBlank()) {
                    String type=switch(id){case "23557"->"DECLARATION";case "23561"->"CERTIFICATE_OF_CONFORMITY";default->"CERTIFICATE_OF_REGISTRATION";};
                    boolean endless=shown.has("isEndless")&&shown.get("isEndless").isJsonPrimitive()&&shown.get("isEndless").getAsJsonPrimitive().isBoolean()&&shown.get("isEndless").getAsBoolean();
                    docs.put(type+":"+number,new CatalogProductData.Document(type,number,from,until,endless,text(shown,"applicant"),Map.of()));
                }
                continue;
            }
            var raw=a.has("attr_value")?a.get("attr_value"):a.has("attrValue")?a.get("attrValue"):a.get("value");
            var values=displayValues(a.get("showValue"));
            if(values.isEmpty()){
                if(a.has("valueId")&&a.get("valueId").isJsonPrimitive()&&raw!=null&&raw.isJsonPrimitive()&&a.get("valueId").getAsString().equals(raw.getAsString()))continue;
                values=values(raw);
            }
            if(values.isEmpty())continue;
            attrs.add(new CatalogProductData.Attribute(id,name,values));
            String field=IDS.get(id);
            if(field==null)field=NAMES.entrySet().stream().filter(e->e.getValue().contains(normalize(name))).map(Map.Entry::getKey).findFirst().orElse(null);
            if(field!=null)add(fields,field,values);
        }
        // Four-digit category codes are not a replacement for an observed full customs code.
        var codes=fields.get("tnved");if(codes!=null&&codes.stream().anyMatch(v->v.matches("[0-9]{10}")))codes.removeIf(v->v.matches("[0-9]{4}"));
        var immutable=new LinkedHashMap<String,List<String>>();fields.forEach((k,v)->immutable.put(k,List.copyOf(v)));
        return new CatalogProductData(immutable,attrs,List.copyOf(docs.values()));
    }
    private static String catalogId(JsonElement value){
        if(value==null||value.isJsonNull())return "";
        if(!value.isJsonPrimitive())throw new IllegalArgumentException("CATALOG_IDENTITY_INVALID");
        var primitive=value.getAsJsonPrimitive();String identity;
        try{
            if(primitive.isNumber()){
                var number=primitive.getAsBigDecimal();if(number.precision()>64||number.scale()<-64||number.scale()>64)throw new ArithmeticException("Catalog identity exceeds the observation bound");
                identity=number.toBigIntegerExact().toString();
            }else identity=primitive.isString()?primitive.getAsString():"";
        }
        catch(ArithmeticException|NumberFormatException invalid){throw new IllegalArgumentException("CATALOG_IDENTITY_INVALID",invalid);}
        if(!identity.matches("[0-9]{1,64}")||identity.chars().allMatch(c->c=='0'))throw new IllegalArgumentException("CATALOG_IDENTITY_INVALID");
        return identity;
    }
    public static CatalogProductData withRegistry(CatalogProductData data,JsonElement response){
        var fields=new LinkedHashMap<>(data.fields());
        if(com.vncode.app.integration.znack.ZnackPermitDocumentParser.registryLookupPending(response)){
            fields.put("@documents_status",List.of("REGISTRY_DOCUMENTS_PENDING"));return new CatalogProductData(fields,data.attributes(),List.of());
        }
        JsonArray raw=null;
        if(response!=null&&response.isJsonArray())raw=response.getAsJsonArray();
        if(response!=null&&response.isJsonObject()){
            var root=response.getAsJsonObject();var result=object(root,"result");
            if(result.has("documents")&&result.get("documents").isJsonArray())raw=result.getAsJsonArray("documents");
            else if(root.has("documents")&&root.get("documents").isJsonArray())raw=root.getAsJsonArray("documents");
        }
        if(raw==null)return registryUnavailable(data);
        var active=com.vncode.app.integration.znack.ZnackPermitDocumentParser.activeFromRegistry(response);
        var documents=new ArrayList<CatalogProductData.Document>();
        for(var d:active){
            String type=switch(d.type()){case "CONFORMITY_DECLARATION"->"DECLARATION";case "CONFORMITY_CERTIFICATE"->"CERTIFICATE_OF_CONFORMITY";default->"CERTIFICATE_OF_REGISTRATION";};
            var same=data.documents().stream().filter(old->old.number().equals(d.number())&&old.type().equals(type)).findFirst();
            String end=same.map(CatalogProductData.Document::until).orElse("");boolean endless=same.map(CatalogProductData.Document::endless).orElse(false);String applicant=same.map(CatalogProductData.Document::applicant).orElse("");
            documents.add(new CatalogProductData.Document(type,d.number(),d.date(),end,endless,applicant,Map.of("registryStatus","active")));
        }
        fields.put("@documents_status",List.of("REGISTRY_CHECKED"));return new CatalogProductData(fields,data.attributes(),documents);
    }
    public static CatalogProductData registryUnavailable(CatalogProductData data){
        var fields=new LinkedHashMap<>(data.fields());fields.put("@documents_status",List.of("REGISTRY_DOCUMENTS_UNAVAILABLE"));return new CatalogProductData(fields,data.attributes(),data.documents());
    }
    public static String normalize(String text){return java.text.Normalizer.normalize(text==null?"":text,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    private static void add(Map<String,LinkedHashSet<String>> fields,String field,List<String> values){for(String value:values)if(!value.isBlank())fields.computeIfAbsent(field,k->new LinkedHashSet<>()).add(value);}
    private static List<String> displayValues(JsonElement display){
        if(display==null||display.isJsonNull())return List.of();
        if(display.isJsonPrimitive())return values(display);
        if(display.isJsonObject()){
            var object=display.getAsJsonObject();for(String key:List.of("name","text","label","title","valueName"))if(object.has(key))return values(object.get(key));
        }
        if(display.isJsonArray()){
            var out=new LinkedHashSet<String>();for(var item:display.getAsJsonArray())out.addAll(displayValues(item));return List.copyOf(out);
        }return List.of();
    }
    private static List<String> values(JsonElement value){
        if(value==null||value.isJsonNull())return List.of();var out=new LinkedHashSet<String>();
        if(value.isJsonPrimitive())out.add(value.getAsString().strip());
        else if(value.isJsonArray())for(var e:value.getAsJsonArray())if(e.isJsonPrimitive())out.add(e.getAsString().strip());
        if(out.size()>100||out.stream().anyMatch(v->v.length()>5000))throw new IllegalArgumentException("CATALOG_DATA_LIMIT");return out.stream().filter(v->!v.isBlank()).toList();
    }
    private static String text(JsonObject value,String... keys){return ZnackProductCardAttributes.text(value,keys);}
    private static JsonObject object(JsonObject value,String key){var found=ZnackProductCardAttributes.object(value,key);return found==null?new JsonObject():found;}
}
