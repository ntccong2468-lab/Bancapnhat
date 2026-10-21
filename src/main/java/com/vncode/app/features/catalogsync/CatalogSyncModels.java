package com.vncode.app.features.catalogsync;

import com.vncode.app.features.gtinsync.GtinSyncModels;
import com.vncode.app.features.products.ProductDocument;
import java.time.Instant;
import java.util.*;

public final class CatalogSyncModels {
    private CatalogSyncModels(){}
    public enum Field { GTIN,TNVED,MATERIAL,DOCUMENTS,BRAND,TITLE,GENDER,COUNTRY,COLOR,OTHER }
    public enum State { READY,BLOCKED,ATTRIBUTES_PENDING,GTIN_PENDING,RECONCILE_REQUIRED,SUCCEEDED }
    public record Diff(String field,List<String> before,List<String> after,String code,boolean blocking) {
        public Diff {before=List.copyOf(before);after=List.copyOf(after);}
    }
    public record Selection(GtinSyncModels.ProductKey key,String gtin){}
    public record Options(Set<Field> fields,Map<GtinSyncModels.ProductKey,List<ProductDocument>> documents,Map<GtinSyncModels.ProductKey,Map<String,List<String>>> values) {
        public Options(Set<Field> fields,Map<GtinSyncModels.ProductKey,List<ProductDocument>> documents){this(fields,documents,Map.of());}
        public Options {values=freezeValues(values);fields=Set.copyOf(fields);var frozen=new LinkedHashMap<GtinSyncModels.ProductKey,List<ProductDocument>>();documents.forEach((k,v)->frozen.put(k,List.copyOf(v)));documents=Map.copyOf(frozen);if(fields.isEmpty())throw new IllegalArgumentException("Select sync fields");}
    }
    public record Row(String id,GtinSyncModels.ProductKey key,GtinSyncModels.RegisteredGtin catalog,
                      List<String> originalBarcodes,List<Diff> differences,List<ProductDocument> selectedDocuments,Map<String,List<String>> values,String editJob,String gtinJob,State state,String issue) {
        public Row {values=freezeFields(values);originalBarcodes=List.copyOf(originalBarcodes);differences=List.copyOf(differences);selectedDocuments=selectedDocuments==null?null:List.copyOf(selectedDocuments);}
        public Row next(State state,String gtinJob,String issue){return new Row(id,key,catalog,originalBarcodes,differences,selectedDocuments,values,editJob,gtinJob,state,issue);}
    }
    private static Map<String,List<String>> freezeFields(Map<String,List<String>> input){
        var out=new LinkedHashMap<String,List<String>>();if(input!=null)input.forEach((key,values)->{
            if(!Set.of("material","tnved","brand","title","gender","country","color").contains(key)||values.isEmpty()||values.size()>100||values.stream().anyMatch(v->v==null||v.isBlank()||v.length()>5000))throw new IllegalArgumentException("Invalid proposed field value");out.put(key,List.copyOf(values));
        });return Map.copyOf(out);
    }
    private static Map<GtinSyncModels.ProductKey,Map<String,List<String>>> freezeValues(Map<GtinSyncModels.ProductKey,Map<String,List<String>>> input){
        var out=new LinkedHashMap<GtinSyncModels.ProductKey,Map<String,List<String>>>();input.forEach((key,values)->out.put(key,freezeFields(values)));return Map.copyOf(out);
    }
    public record Job(String id,int shopId,String marketplace,String owner,Set<Field> fields,Instant createdAt,boolean confirmed,boolean paused,List<Row> rows) {
        public Job {fields=Set.copyOf(fields);rows=List.copyOf(rows);}
        public long readyCount(){return rows.stream().filter(r->r.state()==State.READY).count();}
    }
}
