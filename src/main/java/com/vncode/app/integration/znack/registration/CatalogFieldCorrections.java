package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.integration.znack.ZnackSanitizer;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;

/** Narrow, source-preserving repairs of an exact rejected field against a freshly read Catalog model. */
final class CatalogFieldCorrections {
    // These fields define the product, the type of size, ownership or a legally effective document.
    // Never silently derive or alter them from a validation message.
    private static final Set<Long> PROTECTED=Set.of(12L,35L,36L,3959L,2478L,2483L,2504L,2630L,13914L,13933L,14013L,23557L,23561L,23765L);
    private static final DateTimeFormatter DMY=DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private CatalogFieldCorrections(){}

    /** Caller must separately re-read and verify exact owner, GTIN, good_id, rejected feed and current schema.
     * Every error must identify one safe attribute and have one deterministic correction. */
    static CatalogAutomaticRepair.Proposal suggest(ZnackNationalCatalogService.FeedProgress progress,JsonObject payload,
            String gtin,long verifiedGoodId,boolean verifiedOwner,List<Attribute> currentSchema) {
        return suggest(progress,payload,gtin,verifiedGoodId,verifiedOwner,currentSchema,null);
    }

    /** Approved facts are exact values, never inferred or converted size/color values. */
    static CatalogAutomaticRepair.Proposal suggest(ZnackNationalCatalogService.FeedProgress progress,JsonObject payload,
            String gtin,long verifiedGoodId,boolean verifiedOwner,List<Attribute> currentSchema,CatalogApprovedVariantValues approved) {
        if(!verifiedOwner||verifiedGoodId<=0||progress==null||!CatalogFeedback.confirmedRejection(progress)
                ||!Objects.equals(progress.goodId(),verifiedGoodId)||progress.errors().isEmpty()||payload==null
                ||!payload.has("gtin")||!payload.get("gtin").isJsonPrimitive()
                ||!RegisteredGtinValidator.isValid(gtin)
                ||!RegisteredGtinProof.sameCode(gtin,payload.get("gtin").getAsString())
                ||currentSchema==null||currentSchema.isEmpty())return null;
        try{if(payload.has("good_id")&&exactId(payload.get("good_id"))!=verifiedGoodId)return null;}
        catch(RuntimeException invalid){return null;}
        if(!payload.has("good_attrs")||!payload.get("good_attrs").isJsonArray())return null;

        var schema=flatten(currentSchema);if(schema==null)return null;
        var entries=new LinkedHashMap<Long,JsonObject>();
        for(var element:payload.getAsJsonArray("good_attrs")){
            if(!element.isJsonObject())return null;var attr=element.getAsJsonObject();
            if(!attr.has("attr_id")||!attr.get("attr_id").isJsonPrimitive())return null;
            long id;try{id=exactId(attr.get("attr_id"));}catch(RuntimeException invalid){return null;}
            if(id<=0||entries.putIfAbsent(id,attr)!=null)return null;
        }
        var fixes=new LinkedHashMap<Long,String>();var additions=new LinkedHashMap<Long,JsonObject>();var changes=new ArrayList<CatalogAutomaticRepair.Change>();
        for(String error:progress.errors()){
            var identified=identify(error,schema);if(identified==null)return null;
            var attr=entries.get(identified.id());
            if(attr==null){
                if(approved==null||!approved.matches(gtin,currentSchema)||!Set.of(35L,36L).contains(identified.id())||!missingError(error))return null;
                String value=approved.value(identified.id());
                if(value==null||!value.equals(RegistrationTemplate.clean(value))||value.length()>5000
                        ||!RegistrationAttributeRules.validFieldValue(identified,value)
                        ||identified.presetOnly()&&!identified.presets().contains(value))return null;
                String type=identified.id()==35L
                        ?identified.valueTypes().isEmpty()||identified.valueTypes().contains("")?""
                        :identified.valueTypes().contains("---")?"---":null
                        :ZnackWbAttributeMapper.resolveValueType(identified,value,"");
                if(type==null)return null;
                var addition=new JsonObject();addition.addProperty("attr_id",identified.id());addition.addProperty("attr_value",value);addition.addProperty("attr_value_type",type);
                var previous=additions.putIfAbsent(identified.id(),addition);if(previous!=null&&!previous.equals(addition))return null;
                if(previous==null)changes.add(new CatalogAutomaticRepair.Change("attr:"+identified.id(),"(absent)",ZnackSanitizer.message(value),"SELLER_REVIEWED_EXACT_VARIANT_AND_CURRENT_SCHEMA"));
                continue;
            }
            if(PROTECTED.contains(identified.id())||protectedName(identified.name()))return null;
            if(!attr.has("attr_value")||!attr.get("attr_value").isJsonPrimitive()
                    ||!attr.getAsJsonPrimitive("attr_value").isString())return null;
            String before=attr.get("attr_value").getAsString();String after=correct(identified,before,error);
            if(after==null||after.equals(before)||after.isBlank()||after.length()>5000
                    ||!RegistrationAttributeRules.validFieldValue(identified,after)
                    ||identified.presetOnly()&&!identified.presets().contains(after))return null;
            String prior=fixes.putIfAbsent(identified.id(),after);if(prior!=null&&!prior.equals(after))return null;
            if(prior==null)changes.add(new CatalogAutomaticRepair.Change("attr:"+identified.id(),ZnackSanitizer.message(before),ZnackSanitizer.message(after),"CATALOG_REJECTION_AND_CURRENT_SCHEMA"));
        }
        if(fixes.isEmpty()&&additions.isEmpty()||fixes.size()+additions.size()+(payload.has("good_id")?0:1)>20)return null;
        var result=payload.deepCopy();result.addProperty("good_id",verifiedGoodId);
        if(!payload.has("good_id"))changes.add(new CatalogAutomaticRepair.Change("good_id","(absent)",Long.toString(verifiedGoodId),"CATALOG_VERIFIED_IDENTITY"));
        for(var element:result.getAsJsonArray("good_attrs")){
            var attr=element.getAsJsonObject();String replacement=fixes.get(exactId(attr.get("attr_id")));if(replacement!=null)attr.addProperty("attr_value",replacement);
        }
        additions.values().forEach(result.getAsJsonArray("good_attrs")::add);
        return new CatalogAutomaticRepair.Proposal(payload,result,changes,CatalogFeedback.rejection(progress),verifiedGoodId);
    }
    private static boolean missingError(String error){
        String value=Objects.toString(error,"").toLowerCase(Locale.ROOT);
        return value.contains("отсутств")||value.contains("не указан")||value.contains("не заполн")||value.contains("обязательн")||value.contains("missing")||value.contains("required");
    }
    static boolean mayNeedApprovedVariant(ZnackNationalCatalogService.FeedProgress progress,List<Attribute> schema){
        if(progress==null||schema==null)return false;var fields=flatten(schema);if(fields==null)return false;
        return progress.errors().stream().anyMatch(error->{var field=identify(error,fields);return field!=null&&Set.of(35L,36L).contains(field.id())&&missingError(error);});
    }
    private static String correct(Attribute attr,String before,String error){
        String message=Objects.toString(error,"").toLowerCase(Locale.ROOT);
        String value=before;
        if(message.contains("пробел")||message.contains("whitespace"))value=RegistrationTemplate.clean(value);
        if((message.contains("символ")&&(message.contains("недопустим")||message.contains("запрещ")))||message.contains("invalid character"))
            value=value.replace("\u200B","").replace("\uFEFF","");
        if("date".equalsIgnoreCase(Objects.toString(attr.fieldType(),""))&&(message.contains("дат")||message.contains("date")||message.contains("формат")||message.contains("format"))
                &&value.matches("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")){
            try{value=LocalDate.parse(value,DMY).toString();}catch(RuntimeException invalid){return null;}
        }
        if(attr.presetOnly()&&!attr.presets().contains(value)){
            String normalized=normal(value);var matches=attr.presets().stream().filter(v->normal(v).equals(normalized)).distinct().toList();
            if(matches.size()==1)value=matches.getFirst();
        }
        return value;
    }
    private static Attribute identify(String error,Map<Long,Attribute> schema){
        if(error==null)return null;int colon=error.indexOf(':');if(colon<=0)return null;
        String prefix=error.substring(0,colon).strip();
        if(prefix.matches("[1-9][0-9]{0,17}"))try{return schema.get(Long.parseLong(prefix));}catch(NumberFormatException ignored){return null;}
        var candidates=schema.values().stream().filter(a->!a.name().isBlank()&&a.name().equals(prefix)).toList();return candidates.size()==1?candidates.getFirst():null;
    }
    private static Map<Long,Attribute> flatten(List<Attribute> roots){
        var result=new LinkedHashMap<Long,Attribute>();var todo=new ArrayDeque<>(roots);
        while(!todo.isEmpty()){
            var item=todo.removeFirst();if(item==null||item.id()<=0)return null;
            var previous=result.putIfAbsent(item.id(),item);if(previous!=null){if(!previous.equals(item))return null;continue;}
            if(result.size()>5000)return null;item.dependencies().values().forEach(todo::addAll);
        }return result;
    }
    private static long exactId(JsonElement raw){if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("invalid_id");return raw.getAsBigDecimal().longValueExact();}
    private static boolean protectedName(String name){String value=normal(name);return List.of("gtin","barcode","штрихкод","тн вэд","тнвэд","tnved","артикул","модель","размер","size","цвет","color","brand","бренд","товарный знак","пол","gender","состав","материал","material","страна","country","декларация","сертификат","документ").stream().anyMatch(value::contains);}
    private static String normal(String value){return Normalizer.normalize(Objects.toString(value,""),Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
}
