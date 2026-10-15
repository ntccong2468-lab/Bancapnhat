package com.vncode.app.features.listingcopy;

import com.google.gson.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.*;
import java.math.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.vncode.app.integration.listing.ListingJson.*;

/** Shared validation only. Destination IDs, dictionaries and payloads remain marketplace-specific. */
public abstract class AbstractListingAdapter implements ListingTargetAdapter {
    protected final Function<Shop,ListingTransport> clients;protected final ListingMediaInspector images;protected final Marketplace market;
    protected AbstractListingAdapter(Marketplace m,Function<Shop,ListingTransport> c,ListingMediaInspector i){market=m;clients=c;images=i;}
    protected void owner(Shop s){ListingIdentity.require(s);if(s.getMarketplace()!=market)throw new SecurityException("Wrong listing marketplace");}
    protected void owner(Shop s,RemoteSubmission r){owner(s);if(r.shopId()!=s.getId()||r.marketplace()!=market||!r.owner().equals(ListingIdentity.fingerprint(s)))throw new SecurityException("Foreign listing job");}
    protected JsonObject call(Shop s,String path,JsonElement body,boolean mutation)throws IOException{owner(s);var r=object(clients.apply(s).call(path,body,mutation));if("true".equals(text(r,"error")))throw new IOException("Listing request rejected");return r;}
    protected static void issue(List<CopyIssue> list,String code,String field){list.add(new CopyIssue(code,field,true));}
    protected List<ProductVariant> selected(ProductSnapshot source,CopyOptions o,List<CopyIssue> issues){
        if(!source.revision().startsWith("live:"))issue(issues,"FRESH_SOURCE_REQUIRED","source");
        var result=source.variants().stream().filter(v->o.variantIds().contains(v.id())).toList();
        if(result.isEmpty()||result.size()!=o.variantIds().size())issue(issues,"VARIANT_SELECTION_CHANGED","sizes");
        // Copying a fresh listing's barcode does not grant a Catalog/KIZ purchase proof.
        if(o.fields().contains("gtin")){var codes=new HashSet<String>();for(var v:result){var inherited=CopyDefaults.barcodes(source,v);if(inherited.isEmpty())issue(issues,"GTIN_NOT_VERIFIED",v.id());for(String code:inherited)if(code.isBlank()||code.length()>100||code.codePoints().anyMatch(Character::isISOControl))issue(issues,"GTIN_NOT_VERIFIED",v.id());else if(!codes.add(code))issue(issues,"DUPLICATE_VARIANT_GTIN",v.id());}}
        return result;
    }
    protected Map<String,List<String>> mapped(ProductSnapshot s,CopyOptions o,List<TargetAttribute> dictionary,List<CopyIssue> issues){
        var ids=new HashSet<String>();dictionary.forEach(a->ids.add(a.id()));var result=new LinkedHashMap<String,List<String>>();
        if(o.fields().contains("attributes"))for(var a:s.attributes()){
            if(a.complexId()!=null&&!a.complexId().isBlank()&&!a.complexId().equals("0")){if(market==Marketplace.WILDBERRIES)issue(issues,"COMPLEX_STRUCTURE_NOT_SUPPORTED",a.mappingKey());continue;}
            if(a.name().toLowerCase(Locale.ROOT).matches(".*(kiz|км|код маркиров).*")){issue(issues,"MARKING_CODES_NOT_TRANSFERABLE",a.id());continue;}
            String id=o.attributeMapping().get(a.id());if(id==null){issue(issues,"ATTRIBUTE_MAPPING_REQUIRED",a.id());continue;}
            if(id.equals("@skip")){issues.add(new CopyIssue("ATTRIBUTE_EXPLICITLY_OMITTED",a.mappingKey(),false));continue;}
            if(market==Marketplace.WILDBERRIES&&Set.of("@brand","@description","@title").contains(id)){if(!o.fields().contains(id.substring(1)))issue(issues,"TEXT_FIELD_MAPPING_REQUIRED",id);continue;}
            if(!ids.contains(id)){issue(issues,"FOREIGN_TARGET_ATTRIBUTE",id);continue;}
            if(result.putIfAbsent(id,a.values())!=null)issue(issues,"ATTRIBUTE_MAPPING_CONFLICT",id);
        }
        for(var e:o.attributeValues().entrySet())if(!ids.contains(e.getKey()))issue(issues,"FOREIGN_TARGET_ATTRIBUTE",e.getKey());else result.put(e.getKey(),e.getValue());
        return result;
    }
    protected JsonArray media(ProductSnapshot s,CopyOptions o,List<CopyIssue> issues)throws IOException{
        var result=new JsonArray();if(!o.fields().contains("images"))return result;
        if(s.media().isEmpty()){issue(issues,"SOURCE_IMAGES_MISSING","images");return result;}
        if(s.media().size()+o.documents().size()>ListingMediaReader.MAX_FILES){issue(issues,"FILE_COUNT_LIMIT","images");return result;}
        for(var m:s.media()){
            if(!ListingMediaReader.allowedMarketplaceUrl(m.uri())){issue(issues,"IMAGE_SOURCE_UNSUPPORTED","images");continue;}
            try{var i=images.inspect(m);if(market==Marketplace.WILDBERRIES&&(i.width()<700||i.height()<900))issue(issues,"WB_IMAGE_DIMENSIONS","images");if(!Set.of("image/jpeg","image/png").contains(i.mimeType()))issue(issues,"TARGET_IMAGE_FORMAT","images");var a=new JsonObject();a.addProperty("url",m.uri().toString());a.addProperty("sha256",i.sha256());if(i.visualHash()!=null)a.addProperty("visualHash",i.visualHash());a.addProperty("width",i.width());a.addProperty("height",i.height());result.add(a);}
            catch(IOException e){issue(issues,"IMAGE_READ_FAILED","images");}
        }return result;
    }
    protected JsonObject dimensions(PackageDimensions d,boolean ozon,List<CopyIssue> issues){
        var r=new JsonObject();if(d==null){issue(issues,"PACKAGE_REQUIRED","dimensions");return r;}
        BigDecimal[] values={d.lengthCm(),d.widthCm(),d.heightCm(),d.weightKg()};String[] names=ozon?new String[]{"depth","width","height","weight"}:new String[]{"length","width","height","weightBrutto"};
        for(int i=0;i<4;i++){var v=values[i];if(v==null||v.signum()<=0){issue(issues,"PACKAGE_REQUIRED",names[i]);continue;}try{
            if(ozon)r.addProperty(names[i],v.movePointRight(i==3?3:1).intValueExact());else if(i<3)r.addProperty(names[i],v.intValueExact());else {if(v.scale()>3)issue(issues,"PACKAGE_UNIT_PRECISION",names[i]);r.addProperty(names[i],v);}
        }catch(ArithmeticException e){issue(issues,"PACKAGE_UNIT_PRECISION",names[i]);}}
        if(ozon){r.addProperty("dimension_unit","mm");r.addProperty("weight_unit","g");}return r;
    }
    protected List<ProductDocument> documents(ProductSnapshot s,CopyOptions o){return !o.documents().isEmpty()?o.documents():o.fields().contains("documents")?s.documents():List.of();}
    protected CopyPreview previewRecord(Shop s,ProductSnapshot source,CopyOptions o,JsonElement request,JsonArray media,JsonArray docs,List<CopyIssue> issues,List<String> articles,String operation){
        String r=ListingIdentity.canonical(request),m=ListingIdentity.canonical(media),d=ListingIdentity.canonical(docs),owner=ListingIdentity.fingerprint(s);
        var diff=new LinkedHashMap<String,String>();diff.put("source",source.title());diff.put("target",s.getName()+" · "+market);diff.put("category",o.targetCategoryId());diff.put("articles",String.join(", ",articles));diff.put("fields",String.join(", ",new TreeSet<>(o.fields())));
        return new CopyPreview(s.getId(),market,owner,source.key(),source.revision(),CopyPreview.hash(r,m,d,owner,source.revision(),operation),r,m,d,diff,issues,articles,o,operation);
    }
    protected void requirePreview(Shop s,CopyPreview p){owner(s);if(p.shopId()!=s.getId()||p.marketplace()!=market||!p.owner().equals(ListingIdentity.fingerprint(s))||!p.publishable())throw new SecurityException("Listing preview is not publishable");}
    protected RemoteSubmission submitted(CopyPreview p,String task){return new RemoteSubmission(p.shopId(),p.marketplace(),p.owner(),p.requestHash(),task,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
    protected String article(CopyOptions o,ProductVariant v,List<CopyIssue> issues){String a=o.newArticles().get(market==Marketplace.WILDBERRIES?"*":v.id());if(a==null||a.isBlank()||a.length()>(market==Marketplace.WILDBERRIES?72:50)||a.codePoints().anyMatch(Character::isISOControl)){issue(issues,"NEW_ARTICLE_REQUIRED",v.id());return "";}return a.strip();}
    protected static JsonArray urls(String media){var urls=new JsonArray();for(var e:JsonParser.parseString(media).getAsJsonArray())urls.add(text(object(e),"url"));return urls;}
    protected static boolean same(JsonElement a,JsonElement b){return a!=null&&b!=null&&ListingIdentity.canonical(a).equals(ListingIdentity.canonical(b));}
    protected static boolean subset(JsonObject expected,JsonObject actual,Set<String> skip){for(var e:expected.entrySet())if(!skip.contains(e.getKey())&&!same(e.getValue(),actual.get(e.getKey())))return false;return true;}
    protected boolean imagesMatch(String expected,List<String> actual)throws IOException{
        var list=JsonParser.parseString(expected).getAsJsonArray();if(list.size()!=actual.size())return false;
        for(int i=0;i<list.size();i++){
            var want=object(list.get(i));var uri=URI.create(actual.get(i));if(!ListingMediaReader.allowedMarketplaceUrl(uri))return false;var current=images.inspect(new ProductMedia(uri,null,null,null,null));
            if(text(want,"sha256").equals(current.sha256()))continue;String visual=text(want,"visualHash");if(!visual.matches("[a-f0-9]{16}")||current.visualHash()==null||Long.bitCount(Long.parseUnsignedLong(visual,16)^Long.parseUnsignedLong(current.visualHash(),16))>4)return false;
            var w=decimal(want,"width");var h=decimal(want,"height");if(w==null||h==null||Math.abs(w.doubleValue()/h.doubleValue()-((double)current.width()/current.height()))>0.02)return false;
        }return true;
    }
    protected static String wbType(ProductDocument d){return switch(d.type()){case "CERTIFICATE_OF_CONFORMITY","certificate"->"1";case "DECLARATION","declaration"->"2";case "CERTIFICATE_OF_REGISTRATION"->"3";case "REGISTRATION_CERTIFICATE"->"4";default->d.type();};}
    protected static JsonObject wbDocument(ProductDocument d,boolean own){
        var r=new JsonObject();if(own&&!d.id().isBlank())r.addProperty("id",d.id());r.addProperty("type",new BigInteger(wbType(d)));r.addProperty("number",d.number());
        for(String field:List.of("productNumber","tradeName","applicant","isEndless")){String value=d.metadata().get(field);if(value!=null){if(field.equals("isEndless"))r.addProperty(field,Boolean.parseBoolean(value));else r.addProperty(field,value);}}
        if(!r.has("applicant")&&d.owner()!=null&&!d.owner().isBlank())r.addProperty("applicant",d.owner());
        if(d.validFrom()!=null)r.addProperty("startDate",d.validFrom().toString()+"T00:00:00Z");if(d.validUntil()!=null)r.addProperty("endDate",d.validUntil().toString()+"T00:00:00Z");return r;
    }
    protected static boolean expired(ProductDocument d){return d.state()==DocumentState.EXPIRED||d.validUntil()!=null&&d.validUntil().isBefore(LocalDate.now());}
    protected static boolean validDocument(ProductDocument d){return !expired(d)&&d.state()!=DocumentState.REJECTED;}
    protected static String namedValue(ProductSnapshot s,CopyOptions o,String field,String fallback){String explicit=o.textOverrides().get(field);if(explicit!=null&&!explicit.isBlank())return explicit;return s.attributes().stream().filter(a->("@"+field).equals(o.attributeMapping().get(a.mappingKey()))).findFirst().map(a->String.join(" ",a.values())).orElse(explicit==null?fallback:explicit);}
}
