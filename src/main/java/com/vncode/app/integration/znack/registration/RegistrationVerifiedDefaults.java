package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.products.ProductSnapshot;
import com.vncode.app.integration.znack.ZnackModels.GoodsDocument;
import java.util.*;
import java.util.function.BiFunction;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;

/** Fills missing schema fields only from facts verified for the selected registration document. */
public final class RegistrationVerifiedDefaults {
    private static final Set<Long> SUPPORTED=Set.of(13836L);
    private static final long PRODUCTION_COUNTRY=2630L;
    private static final Set<String> COUNTRY_FIELDS=Set.of("странапроизводства","странапроисхождения","странаизготовления","странапроизводитель","странаизготовитель","countryoforigin","countryofmanufacture","manufacturingcountry","productioncountry","country","countryiso","countrycode","nướcsảnxuất","quốcgiasảnxuất","xuấtxứ");
    private RegistrationVerifiedDefaults(){}

    /** Read-only auto-fill shared by background registration and the manual registration screen. */
    public static Draft applyAutomatic(Draft draft,List<Attribute> schema,ZnackNationalCatalogService catalog,GoodsDocument document,ProductSnapshot source){
        draft=apply(draft,schema,catalog.verifiedRegistrationAttributes(document,schema),source);
        return applyProductionCountry(draft,schema,productionCountry(source,schema,catalog::registrationCountryCode),source);
    }
    static Draft apply(Draft draft,List<Attribute> schema,Map<Long,String> verified,ProductSnapshot source){
        return fill(draft,schema,verified,source,SUPPORTED);
    }
    /** The seller's default is Russia; an explicit source country is resolved without replacing it. */
    static String productionCountry(ProductSnapshot source,List<Attribute> schema,BiFunction<String,List<Attribute>,String> resolve){
        var fields=schema.stream().filter(attribute->attribute.id()==PRODUCTION_COUNTRY).toList();
        if(fields.size()!=1)return "";
        var field=fields.getFirst();var sourceValues=sourceCountries(source,schema);
        var codes=new LinkedHashSet<String>();
        for(String sourceValue:sourceValues){String code=resolve.apply(sourceValue,schema);if(code==null||code.isBlank())return "";codes.add(code);}
        if(codes.size()>1)return "";
        String value=codes.isEmpty()?"RU":codes.getFirst();
        return value!=null&&field.presets().contains(value)&&RegistrationAttributeRules.validFieldValue(field,value)?value:"";
    }
    /** Applies only an ISO country allowed by the current enriched Catalog country dictionary. */
    public static Draft applyProductionCountry(Draft draft,List<Attribute> schema,String country){
        String value=RegistrationTemplate.clean(country);
        return value.isBlank()?draft:fill(draft,schema,Map.of(PRODUCTION_COUNTRY,value),null,Set.of(PRODUCTION_COUNTRY));
    }
    /** Before seller overrides, conflicting source countries must not retain a mapper's first match. */
    static Draft applyProductionCountry(Draft draft,List<Attribute> schema,String country,ProductSnapshot source){
        if(RegistrationTemplate.clean(country).isBlank()&&!sourceCountries(source,schema).isEmpty()
                &&(draft.attributes().containsKey(PRODUCTION_COUNTRY)||draft.attributeTypes().containsKey(PRODUCTION_COUNTRY))){
            var values=new LinkedHashMap<>(draft.attributes());var types=new LinkedHashMap<>(draft.attributeTypes());
            values.remove(PRODUCTION_COUNTRY);types.remove(PRODUCTION_COUNTRY);
            return new Draft(draft.tnved(),draft.feedTnved(),draft.categoryId(),draft.goodName(),draft.brand(),values,types,draft.autoSign(),draft.isKit());
        }
        return applyProductionCountry(draft,schema,country);
    }
    private static Set<String> sourceCountries(ProductSnapshot source,List<Attribute> schema){
        var fields=schema.stream().filter(attribute->attribute.id()==PRODUCTION_COUNTRY).toList();var values=new LinkedHashSet<String>();
        if(source==null||fields.size()!=1)return values;
        String targetName=normalized(fields.getFirst().name());
        for(var attribute:source.attributes()){
            if(attribute.complexId()!=null&&!attribute.complexId().isBlank()&&!attribute.complexId().equals("0"))continue;
            String name=normalized(attribute.name());
            if(!Long.toString(PRODUCTION_COUNTRY).equals(attribute.id())&&!name.equals(targetName)&&!COUNTRY_FIELDS.contains(name.replaceAll("[^\\p{L}\\p{N}]","")))continue;
            for(String raw:attribute.values()){String value=RegistrationTemplate.clean(raw);if(!value.isBlank())values.add(value);}
        }
        return values;
    }
    private static Draft fill(Draft draft,List<Attribute> schema,Map<Long,String> verified,ProductSnapshot source,Set<Long> allowed){
        if(verified.isEmpty())return draft;
        var values=new LinkedHashMap<>(draft.attributes());var types=new LinkedHashMap<>(draft.attributeTypes());
        boolean changed=false;
        for(var attribute:RegistrationAttributeRules.required(schema,values)){
            if(!allowed.contains(attribute.id())||!RegistrationTemplate.clean(values.get(attribute.id())).isBlank()||providedInSource(source,attribute))continue;
            String value=RegistrationTemplate.clean(verified.get(attribute.id()));
            if(value.isBlank()||value.length()>5000||attribute.presetOnly()&&!attribute.presets().contains(value)
                    ||!RegistrationAttributeRules.validFieldValue(attribute,value))continue;
            String type=ZnackWbAttributeMapper.resolveValueType(attribute,value,"");
            if(type==null||!attribute.valueTypes().isEmpty()&&!attribute.valueTypes().contains(type))continue;
            values.put(attribute.id(),value);types.put(attribute.id(),type);changed=true;
        }
        return changed?new Draft(draft.tnved(),draft.feedTnved(),draft.categoryId(),draft.goodName(),draft.brand(),values,types,draft.autoSign(),draft.isKit()):draft;
    }
    private static boolean providedInSource(ProductSnapshot snapshot,Attribute target){
        if(snapshot==null)return false;
        String expected=normalized(target.name());
        return snapshot.attributes().stream().filter(attribute->attribute.complexId()==null||attribute.complexId().isBlank()||attribute.complexId().equals("0"))
                .anyMatch(attribute->(Long.toString(target.id()).equals(attribute.id())||normalized(attribute.name()).equals(expected)||target.id()==13836L&&normalized(attribute.name()).contains("регламент"))
                        &&attribute.values().stream().anyMatch(value->!RegistrationTemplate.clean(value).isBlank()));
    }
    private static String normalized(String value){return java.text.Normalizer.normalize(RegistrationTemplate.clean(value),java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё','е');}
}
