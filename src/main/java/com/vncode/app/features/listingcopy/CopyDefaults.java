package com.vncode.app.features.listingcopy;

import com.google.gson.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import static com.vncode.app.integration.listing.ListingJson.*;

/** Inherit source data; validate destination dictionaries rather than reusing foreign attribute IDs. */
public final class CopyDefaults {
    private CopyDefaults(){}
    public static CopyOptions initial(ProductSnapshot source,Shop target,Set<String> variants,String targetCategory){
        return new CopyOptions(Set.of("title","description","brand","attributes","images","gtin","sizes","dimensions","documents"),variants,targetCategory,Map.of(),Map.of(),Map.of(),null,source.metadata().getOrDefault("source.currency","RUB"),source.documents(),marked(source));
    }
    public static TargetCategory category(ProductSnapshot source,Marketplace target,List<TargetCategory> categories){
        if(source.key().marketplace()==target){var exact=categories.stream().filter(c->c.id().equals(source.categoryId())).findFirst();if(exact.isPresent())return exact.get();}
        String name=semantic(source.metadata().get("source.category.name"));if(name.isBlank())return null;
        var matches=categories.stream().filter(c->{String label=c.name();int separator=label.lastIndexOf(" / ");return semantic(separator<0?label:label.substring(separator+3)).equals(name);}).toList();
        return matches.size()==1?matches.getFirst():null;
    }
    public static CopyOptions withCategory(ProductSnapshot source,Shop target,CopyOptions options,List<TargetCategory> categories){
        if(options.targetCategoryId()!=null&&!options.targetCategoryId().isBlank())return options;
        var category=category(source,target.getMarketplace(),categories);if(category==null)return options;
        return new CopyOptions(options.fields(),options.variantIds(),category.id(),options.attributeMapping(),options.attributeValues(),options.newArticles(),options.targetPrice(),options.currency(),options.documents(),options.kizMarked(),options.variantValues(),options.textOverrides(),options.variantPrices());
    }
    public static String semantic(String name){
        String n=Normalizer.normalize(Objects.toString(name,""),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
        return switch(n){
            case "brand","бренд","брендв одеждеиобуви","брендводеждеиобуви","thươnghiệu"->"brand";
            case "description","описание","аннотация","môtả"->"description";
            case "size","размерпроизводителя","размеризделия","kíchthước"->"size";
            case "color","цвет","цветтовара","màusắc"->"color";
            default -> ProductFieldNames.matches("material",name)?"material":ProductFieldNames.matches("tnved",name)?"tnved":ProductFieldNames.matches("gender",name)?"gender":ProductFieldNames.matches("russianSize",name)?"russianSize":n;
        };
    }
    public static TargetAttribute match(String name,List<TargetAttribute> dictionary){
        String key=semantic(name);if(key.isBlank())return null;
        var candidates=dictionary.stream().filter(a->semantic(a.name()).equals(key)).toList();
        return candidates.size()==1?candidates.getFirst():null;
    }
    public static Map<String,String> mappings(ProductSnapshot source,Marketplace target,String category,List<TargetAttribute> dictionary){
        var result=new LinkedHashMap<String,String>();boolean same=source.key().marketplace()==target&&source.categoryId().equals(category);
        for(var attribute:source.attributes()){
            String field=semantic(attribute.name());
            if(target==Marketplace.WILDBERRIES&&field.equals("size")&&source.variants().stream().allMatch(v->v.size()!=null&&!v.size().isBlank())){result.put(attribute.mappingKey(),"@size");continue;}
            if(target==Marketplace.WILDBERRIES&&field.equals("russianSize")&&source.variants().stream().allMatch(v->v.russianSize()!=null&&!v.russianSize().isBlank())){result.put(attribute.mappingKey(),"@russianSize");continue;}
            boolean complex=attribute.complexId()!=null&&!attribute.complexId().isBlank()&&!attribute.complexId().equals("0");
            var shape=dictionary.stream().filter(a->complex==(!a.complexId().isBlank()&&!a.complexId().equals("0")))
                .filter(a->attribute.unit()==null||attribute.unit().isBlank()||Objects.toString(a.unit(),"").isBlank()||attribute.unit().equalsIgnoreCase(a.unit())).toList();
            var matched=same?shape.stream().filter(a->a.id().equals(attribute.id())&&(!complex||a.complexId().equals(attribute.complexId()))).findFirst().orElse(null):null;
            if(matched==null)matched=match(attribute.name(),shape);
            if(matched!=null){String named=semantic(matched.name());result.put(attribute.mappingKey(),target==Marketplace.WILDBERRIES&&matched.namedField()&&Set.of("brand","description","title").contains(named)?"@"+named:matched.id());}
        }
        for(String text:List.of("brand","description")){var a=match(text,dictionary);if(a!=null)result.put("@"+text,a.id());}
        return Map.copyOf(result);
    }
    public static CopyOptions complete(ProductSnapshot source,Shop target,CopyOptions options,List<TargetAttribute> dictionary){
        var maps=new LinkedHashMap<>(mappings(source,target.getMarketplace(),options.targetCategoryId(),dictionary));maps.putAll(options.attributeMapping());
        var articles=new LinkedHashMap<String,String>();articles.put("*",article(source,target,null));
        source.variants().forEach(v->articles.put(v.id(),article(source,target,v)));articles.putAll(options.newArticles());
        var values=new LinkedHashMap<String,Map<String,List<String>>>();
        var size=match("size",dictionary);
        var russianSize=match("russianSize",dictionary);
        for(var variant:source.variants()){
            var inherited=new LinkedHashMap<String,List<String>>();
            if(target.getMarketplace()==Marketplace.OZON&&size!=null&&variant.size()!=null&&!variant.size().isBlank())inherited.put(size.id(),List.of(variant.size()));
            if(target.getMarketplace()==Marketplace.OZON&&russianSize!=null&&variant.russianSize()!=null&&!variant.russianSize().isBlank())inherited.put(russianSize.id(),List.of(variant.russianSize()));
            inherited.putAll(options.variantValues().getOrDefault(variant.id(),Map.of()));values.put(variant.id(),Map.copyOf(inherited));
        }
        return new CopyOptions(options.fields(),options.variantIds(),options.targetCategoryId(),maps,options.attributeValues(),articles,options.targetPrice(),options.currency(),options.documents(),options.kizMarked()==null?marked(source):options.kizMarked(),values,options.textOverrides(),options.variantPrices());
    }
    public static String article(ProductSnapshot source,Shop target,ProductVariant variant){
        String value=variant!=null&&variant.sellerArticle()!=null&&!variant.sellerArticle().isBlank()?variant.sellerArticle():source.vendorArticle();
        value=Objects.toString(value,"");
        if(source.key().shopId()==target.getId()&&source.key().marketplace()==target.getMarketplace())value+="-VN";
        if(target.getMarketplace()==Marketplace.OZON&&source.variants().size()>1&&source.variants().stream().filter(v->Objects.equals(v.sellerArticle(),variant==null?source.vendorArticle():variant.sellerArticle())).count()>1&&variant!=null)value+="-"+variant.id();
        return value;
    }
    public static Boolean marked(ProductSnapshot source){
        if(source.key().marketplace()!=Marketplace.WILDBERRIES)return null;
        var raw=payload(source);if(raw.has("needKiz"))return "true".equals(text(raw,"needKiz"));if(raw.has("kizMarked"))return "true".equals(text(raw,"kizMarked"));return null;
    }
    public static BigDecimal price(ProductSnapshot source){
        try{String v=source.metadata().get("source.price");return v==null?null:new BigDecimal(v);}catch(NumberFormatException invalid){return null;}
    }
    public static BigDecimal variantPrice(ProductSnapshot source,ProductVariant variant){
        try{String v=source.metadata().get("source.price."+variant.id());return v==null?price(source):new BigDecimal(v);}catch(NumberFormatException invalid){return null;}
    }
    /** Only explicit seller overrides outrank a variant's own inherited source price. */
    public static BigDecimal targetPrice(ProductSnapshot source,CopyOptions options,ProductVariant variant){
        if(options.variantPrices().containsKey(variant.id()))return options.variantPrices().get(variant.id());
        return options.targetPrice()!=null?options.targetPrice():variantPrice(source,variant);
    }
    public static boolean inheritedCurrencyChanged(ProductSnapshot source,CopyOptions options,ProductVariant variant){
        return options.targetPrice()==null&&!options.variantPrices().containsKey(variant.id())&&targetPrice(source,options,variant)!=null
            &&!source.metadata().getOrDefault("source.currency",options.currency()).equals(options.currency());
    }
    public static List<String> barcodes(ProductSnapshot source,ProductVariant variant){
        if(source.key().marketplace()==Marketplace.WILDBERRIES){
            for(var e:array(payload(source),"sizes")){var size=object(e);if(text(size,"chrtID").equals(variant.id()))return strings(array(size,"skus"));}
        }
        if(source.key().marketplace()==Marketplace.OZON){
            var product=object(payload(source).get("attributes"));var codes=new LinkedHashSet<String>();String primary=text(product,"barcode");if(!primary.isBlank())codes.add(primary);codes.addAll(strings(array(product,"barcodes")));if(!codes.isEmpty())return List.copyOf(codes);
        }
        return variant.gtin()==null||variant.gtin().isBlank()?List.of():List.of(variant.gtin());
    }
    private static JsonObject payload(ProductSnapshot source){try{return object(JsonParser.parseString(source.metadata().getOrDefault("source.payload","{}")));}catch(JsonParseException invalid){return new JsonObject();}}
}
