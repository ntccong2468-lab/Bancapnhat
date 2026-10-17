package com.vncode.app.features.catalogsync;

import java.util.*;

/** Observed Catalog data, never a claim of destination approval or a generated certificate. */
public record CatalogProductData(Map<String,List<String>> fields,List<Attribute> attributes,List<Document> documents) {
    public static final CatalogProductData EMPTY=new CatalogProductData(Map.of(),List.of(),List.of());
    public CatalogProductData {
        var frozen=new LinkedHashMap<String,List<String>>();
        if(fields!=null)fields.forEach((key,values)->frozen.put(key,List.copyOf(values)));
        fields=Collections.unmodifiableMap(frozen);
        attributes=attributes==null?List.of():List.copyOf(attributes);
        documents=documents==null?List.of():List.copyOf(documents);
        if(fields.size()>64||attributes.size()>1000||documents.size()>16
                ||fields.values().stream().flatMap(List::stream).mapToLong(String::length).sum()+attributes.stream().flatMap(a->a.values().stream()).mapToLong(String::length).sum()>256000)throw new IllegalArgumentException("CATALOG_DATA_LIMIT");
    }
    public List<String> values(String field){return fields.getOrDefault(field,List.of());}
    public String single(String field){var values=values(field);return values.size()==1?values.getFirst():"";}
    public record Attribute(String id,String name,List<String> values) {
        public Attribute {id=safe(id);name=safe(name);values=List.copyOf(values);if(values.size()>100||values.stream().anyMatch(v->v==null||v.length()>5000))throw new IllegalArgumentException("CATALOG_DATA_LIMIT");}
    }
    public record Document(String type,String number,String from,String until,boolean endless,String applicant,Map<String,String> metadata) {
        public Document {type=safe(type);number=safe(number);from=safe(from);until=safe(until);applicant=safe(applicant);metadata=metadata==null?Map.of():Map.copyOf(metadata);}
    }
    private static String safe(String value){return value==null?"":value.strip();}
}
