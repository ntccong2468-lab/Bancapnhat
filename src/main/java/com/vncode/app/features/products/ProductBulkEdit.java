package com.vncode.app.features.products;

import java.util.*;

/** One explicitly selected card field; size-specific values must agree at card level. */
public record ProductBulkEdit(String field,List<String> values,List<ProductDocument> documents,Map<ProductKey,List<String>> perKeyValues) {
    public ProductBulkEdit {
        if(!Set.of("brand","material","tnved","gender","documents").contains(field))throw new IllegalArgumentException("Unsupported product edit");
        values=List.copyOf(values);documents=documents==null?null:List.copyOf(documents);
        var frozen=new LinkedHashMap<ProductKey,List<String>>();perKeyValues.forEach((k,v)->frozen.put(k,List.copyOf(v)));perKeyValues=Map.copyOf(frozen);
        if(!field.equals("documents")&&(values.isEmpty()||values.stream().anyMatch(v->v==null||v.isBlank()||v.length()>5000)))throw new IllegalArgumentException("Value required");
        if(field.equals("tnved")&&(values.size()!=1||!values.getFirst().matches("[0-9]{10}")))throw new IllegalArgumentException("Ten digit TN VED required");
        if(field.equals("documents")&&documents==null)throw new IllegalArgumentException("Documents required");
    }
}
