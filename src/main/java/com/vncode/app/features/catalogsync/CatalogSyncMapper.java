package com.vncode.app.features.catalogsync;

import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.listingcopy.TargetAttribute;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.vncode.app.features.catalogsync.CatalogSyncModels.*;

/** Category dictionaries are resolved separately for each destination; no copied marketplace IDs. */
public final class CatalogSyncMapper {
    public record Patch(Map<String,List<String>> changes,List<ProductDocument> documents,List<Diff> differences) {
        public Patch {changes=Map.copyOf(changes);documents=documents==null?null:List.copyOf(documents);differences=List.copyOf(differences);}
        public boolean blocked(){return differences.stream().anyMatch(Diff::blocking);}
        public boolean changed(){return !changes.isEmpty()||documents!=null&&!documents.isEmpty();}
    }
    public Patch map(ProductSnapshot current,RegisteredGtin source,Set<Field> fields,List<TargetAttribute> dictionary,List<ProductDocument> supplied) {
        var changes=new LinkedHashMap<String,List<String>>();var diffs=new ArrayList<Diff>();
        for(Field field:fields.stream().sorted().toList()) {
            if(field==Field.GTIN||field==Field.DOCUMENTS||field==Field.OTHER)continue;
            String name=field.name().toLowerCase(Locale.ROOT);var values=source.details().values(name);
            var target=(field==Field.BRAND||field==Field.TITLE)?List.<TargetAttribute>of():dictionary.stream().filter(a->simple(a)&&matches(name,a.name())).toList();
            List<String> old=field==Field.TITLE?List.of(current.title()):field==Field.BRAND?List.of(current.brand()):target.size()==1?old(current,target.getFirst().id()):List.of();
            String issue=values.isEmpty()?"SOURCE_MISSING":field==Field.TNVED&&(values.size()!=1||!values.getFirst().matches("[0-9]{10}"))?"FULL_TNVED_REQUIRED":field!=Field.MATERIAL&&values.size()>1?"SOURCE_AMBIGUOUS":field!=Field.BRAND&&field!=Field.TITLE&&target.size()!=1?"TARGET_FIELD_AMBIGUOUS":"";
            diffs.add(new Diff(name,old,values,issue, !issue.isBlank()));
            if(issue.isBlank()&&!same(old,values))changes.put(field==Field.BRAND?"brand":field==Field.TITLE?"title":"attribute:"+target.getFirst().id(),values);
        }
        if(fields.contains(Field.OTHER))for(var attr:source.details().attributes()) {
            if(attr.name().isBlank()||Set.of("material","tnved","gender","country","color").stream().anyMatch(field->matches(field,attr.name()))||Set.of("артикул","модель","размер","бренд","наименованиетовара","описание").contains(normalize(attr.name()))||Set.of("35","36","13914","13933","2483","14013","2504","2478","3959").contains(attr.id()))continue;
            var targets=dictionary.stream().filter(a->simple(a)&&normalize(a.name()).equals(normalize(attr.name()))).toList();
            if(targets.size()!=1){diffs.add(new Diff("other:"+attr.name(),List.of(),attr.values(),"TARGET_FIELD_UNMAPPED",false));continue;}
            String key="attribute:"+targets.getFirst().id();var old=old(current,targets.getFirst().id());
            if(changes.containsKey(key)&&!same(changes.get(key),attr.values())){diffs.add(new Diff(attr.name(),old,attr.values(),"SOURCE_AMBIGUOUS",true));continue;}
            diffs.add(new Diff(attr.name(),old,attr.values(),"",false));if(!same(old,attr.values()))changes.put(key,attr.values());
        }
        List<ProductDocument> documents=null;
        if(fields.contains(Field.DOCUMENTS)) {
            if(supplied==null)for(String status:source.details().values("@documents_status"))if(status.startsWith("REGISTRY_DOCUMENTS_"))diffs.add(new Diff("documents",List.of(),List.of(),status,true));
            var selected=supplied!=null?supplied:source.details().documents().stream().map(d->document(d,current.key().marketplace())).toList();
            documents=new ArrayList<>();
            if(selected.isEmpty())diffs.add(new Diff("documents",numbers(current.documents()),List.of(),"SOURCE_DOCUMENTS_MISSING",true));
            for(var d:selected) {
                var existing=current.documents().stream().filter(old->Objects.equals(old.number(),d.number())&&canonicalType(old.type()).equals(canonicalType(d.type()))).findFirst();
                if(existing.isPresent()&&existing.get().state()!=DocumentState.REJECTED&&existing.get().state()!=DocumentState.EXPIRED&&sameDocument(existing.get(),d)){
                    diffs.add(new Diff("documents",List.of(d.number()),List.of(d.number()),"CURRENT",false));continue;
                }
                String code=d.number().isBlank()||d.validFrom()==null||d.validUntil()==null&&!"true".equals(d.metadata().get("isEndless"))?"DOCUMENT_DETAILS_REQUIRED":d.validUntil()!=null&&d.validUntil().isBefore(LocalDate.now())||d.state()==DocumentState.EXPIRED||d.state()==DocumentState.REJECTED?"DOCUMENT_EXPIRED_OR_REJECTED":d.validFrom().isAfter(LocalDate.now())?"DOCUMENT_NOT_YET_VALID":current.key().marketplace()==Marketplace.OZON&&d.file()==null?"DOCUMENT_FILE_REQUIRED":"";
                if(d.marketplace()!=current.key().marketplace())code="DOCUMENT_MARKETPLACE_MISMATCH";
                diffs.add(new Diff("documents",numbers(current.documents()),List.of(d.number()),code,!code.isBlank()));
                if(code.isBlank())documents.add(d);
            }
            if(documents.isEmpty())documents=null;
        }
        return new Patch(changes,documents,diffs);
    }
    public static ProductDocument document(CatalogProductData.Document d,Marketplace marketplace) {
        var metadata=new LinkedHashMap<>(d.metadata());metadata.put("isEndless",Boolean.toString(d.endless()));
        return new ProductDocument("",d.type(),d.number(),date(d.from()),date(d.until()),d.applicant(),"",null,marketplace,DocumentState.UNVERIFIED,"https://markirovka.crpt.ru/",Instant.now(),metadata);
    }
    public static LocalDate date(String value){if(value==null||value.isBlank())return null;try{return LocalDate.parse(value.length()>=10&&value.charAt(4)=='-'?value.substring(0,10):value,DateTimeFormatter.ISO_LOCAL_DATE);}catch(RuntimeException invalid){try{return LocalDate.parse(value,DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT));}catch(RuntimeException unknown){return null;}}}
    private static boolean sameDocument(ProductDocument a,ProductDocument b){return Objects.equals(a.validFrom(),b.validFrom())&&Objects.equals(a.validUntil(),b.validUntil())&&Objects.equals(a.metadata().getOrDefault("isEndless","false"),b.metadata().getOrDefault("isEndless","false"))
            &&b.metadata().entrySet().stream().filter(e->!e.getValue().isBlank()&&!Set.of("registryStatus","isEndless").contains(e.getKey())).allMatch(e->Objects.equals(a.metadata().get(e.getKey()),e.getValue()));}
    static String canonicalType(String type){return switch(type){case "1","certificate","CERTIFICATE_OF_CONFORMITY"->"1";case "2","declaration","DECLARATION"->"2";case "3","CERTIFICATE_OF_REGISTRATION"->"3";default->type;};}
    private static List<String> numbers(List<ProductDocument> docs){return docs.stream().map(ProductDocument::number).toList();}
    private static List<String> old(ProductSnapshot current,String id){return current.attributes().stream().filter(a->a.id().equals(id)&&(a.complexId().isBlank()||a.complexId().equals("0"))).flatMap(a->a.values().stream()).toList();}
    private static boolean simple(TargetAttribute a){return !a.namedField()&&(a.complexId().isBlank()||a.complexId().equals("0"));}
    private static boolean same(List<String> a,List<String> b){return new LinkedHashSet<>(a).equals(new LinkedHashSet<>(b));}
    private static String normalize(String value){return java.text.Normalizer.normalize(value==null?"":value,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    private static boolean matches(String field,String name){if(Set.of("tnved","material","gender").contains(field))return ProductFieldNames.matches(field,name);return switch(field){case "color"->Set.of("цвет","цветтовара","color").contains(normalize(name));case "country"->Set.of("странапроизводства","странапроисхождения","countryoforigin","country").contains(normalize(name));default->false;};}
}
