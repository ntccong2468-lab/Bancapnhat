package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.vncode.app.integration.listing.ListingJson.*;

public final class OzonListingAdapter extends AbstractListingAdapter implements ProductUpdateBatchAdapter {
    public OzonListingAdapter(){this(ListingHttpClient::new,ListingMediaInspector.fullSize());}
    public OzonListingAdapter(Function<Shop,ListingTransport> c,ListingMediaInspector images){super(Marketplace.OZON,c,images);}
    public List<TargetCategory> categories(Shop s)throws IOException{var result=new ArrayList<TargetCategory>();tree(array(call(s,"v1/description-category/tree",new JsonObject(),false),"result"),"","",result,0);return List.copyOf(result);}
    private static void tree(JsonArray children,String cat,String name,List<TargetCategory> out,int depth)throws IOException{
        if(depth>30||out.size()>20000)throw new IOException("Category tree limit");for(var e:children){var o=object(e);String c=text(o,"description_category_id");if(c.isBlank())c=cat;String n=text(o,"category_name");if(n.isBlank())n=name;String type=text(o,"type_id");if(!type.isBlank()&&!c.isBlank()&&!"true".equals(text(o,"disabled")))out.add(new TargetCategory(c+":"+type,n+" / "+text(o,"type_name")));tree(array(o,"children"),c,n,out,depth+1);}
    }
    private static JsonObject category(String cat){if(cat==null||!cat.matches("[1-9][0-9]{0,18}:[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Destination Ozon category/type required");var b=new JsonObject();String[] parts=cat.split(":");b.addProperty("description_category_id",new BigInteger(parts[0]));b.addProperty("type_id",new BigInteger(parts[1]));b.addProperty("language","DEFAULT");return b;}
    public List<TargetAttribute> attributes(Shop s,String cat)throws IOException{
        var result=new ArrayList<TargetAttribute>();for(var e:array(call(s,"v1/description-category/attribute",category(cat),false),"result")){var a=object(e);result.add(new TargetAttribute(text(a,"id"),text(a,"name"),text(a,"type"),"true".equals(text(a,"is_required")),"",a.has("is_collection")&&!"true".equals(text(a,"is_collection"))?1:decimal(a,"max_value_count")==null?1:decimal(a,"max_value_count").intValue(),text(a,"dictionary_id"),text(a,"attribute_complex_id"),false));}return List.copyOf(result);
    }
    @Override public List<String> suggestValues(Shop shop,String cat,String field,String query)throws IOException {
        owner(shop);
        var dictionary=attributes(shop,cat);
        var candidates=field.equals("brand")?dictionary.stream().filter(a->Set.of("Бренд","Бренд в одежде и обуви").contains(a.name())&&!a.namedField()&&(a.complexId().isBlank()||a.complexId().equals("0"))).toList():ProductFieldNames.targets(field,dictionary);
        if(candidates.size()!=1)throw new IOException("Field dictionary is missing or ambiguous");
        var attr=candidates.getFirst();
        if(attr.dictionaryId().isBlank()||attr.dictionaryId().equals("0"))return List.of();
        var b=category(cat);b.addProperty("attribute_id",new BigInteger(attr.id()));
        String value=query.strip();
        String path="v1/description-category/attribute/values";
        if(value.length()>=2){b.addProperty("value",value);b.addProperty("limit",100);path+="/search";}
        else{b.addProperty("last_value_id",0);b.addProperty("limit",100);}
        var response=call(shop,path,b,false);
        if(!response.has("result")||!response.get("result").isJsonArray())throw new IOException("Attribute suggestions unavailable");
        return array(response,"result").asList().stream().map(e->text(object(e),"value")).filter(v->!v.isBlank()).distinct().toList();
    }
    public CopyPreview preview(Shop shop,ProductSnapshot source,CopyOptions requested)throws IOException{
        owner(shop);var issues=new ArrayList<CopyIssue>();var categories=categories(shop);var base=CopyDefaults.withCategory(source,shop,requested,categories);if(base.targetCategoryId()==null||!base.targetCategoryId().matches("[1-9][0-9]{0,18}:[1-9][0-9]{0,18}")||categories.stream().noneMatch(c->c.id().equals(base.targetCategoryId()))){issue(issues,"TARGET_CATEGORY_REQUIRED","category");return previewRecord(shop,source,base,new JsonObject(),new JsonArray(),new JsonArray(),issues,List.of(),"CREATE");}
        var dict=attributes(shop,base.targetCategoryId());var o=CopyDefaults.complete(source,shop,base,dict);var variants=selected(source,o,issues);var mapped=mapped(source,o,dict,issues);
        for(String field:List.of("brand","description"))if(o.fields().contains(field)){String value=o.textOverrides().getOrDefault(field,field.equals("brand")?source.brand():source.description());String id=o.attributeMapping().get("@"+field);if(value!=null&&!value.isBlank()){if(id==null||dict.stream().noneMatch(a->a.id().equals(id)))issue(issues,"TEXT_FIELD_MAPPING_REQUIRED",field);else mapped.put(id,List.of(value));}}
        if(!Set.of("RUB","BYN","KZT","EUR","USD","CNY").contains(o.currency()))issue(issues,"TARGET_CURRENCY_REQUIRED","currency");
        if(variants.size()>1&&o.fields().contains("sizes")&&variants.stream().anyMatch(v->o.variantValues().getOrDefault(v.id(),Map.of()).isEmpty()))issue(issues,"VARIANT_MAPPING_REQUIRED","sizes");
        var media=media(source,o,issues);var items=new JsonArray();var articles=new ArrayList<String>();var used=new HashSet<String>();var valueCache=new HashMap<String,Map<String,String>>();
        for(var v:variants){String article=article(o,v,issues);articles.add(article);if(!used.add(article))issue(issues,"ARTICLE_DUPLICATED",article);if(!article.isBlank()&&!find(shop,article).isEmpty())issue(issues,"ARTICLE_COLLISION",article);
            String title=o.textOverrides().getOrDefault("title",source.title());var item=category(o.targetCategoryId());item.remove("language");item.addProperty("offer_id",article);item.addProperty("name",title);if(title==null||title.isBlank()||title.length()>500)issue(issues,"TITLE_LIMIT","title");
            var amount=CopyDefaults.targetPrice(source,o,v);if(amount==null||amount.signum()<=0)issue(issues,"TARGET_PRICE_REQUIRED",v.id());else item.addProperty("price",amount.toPlainString());
            if(CopyDefaults.inheritedCurrencyChanged(source,o,v))issue(issues,"SOURCE_PRICE_CURRENCY_MISMATCH",v.id());
            item.addProperty("currency_code",o.currency());if(o.fields().contains("gtin")){if(CopyDefaults.barcodes(source,v).size()>1)issue(issues,"OWN_BARCODE_SET_UNSUPPORTED",v.id());item.addProperty("barcode",v.gtin());}
            var packageData=dimensions(source.dimensions(),true,issues);packageData.entrySet().forEach(e->item.add(e.getKey(),e.getValue()));if(!media.isEmpty()){item.add("images",urls(media.toString()));item.addProperty("primary_image",text(object(media.get(0)),"url"));}
            var values=new LinkedHashMap<>(mapped);values.putAll(o.variantValues().getOrDefault(v.id(),Map.of()));for(String id:values.keySet())if(dict.stream().noneMatch(a->a.id().equals(id)))issue(issues,"FOREIGN_TARGET_ATTRIBUTE",id);
            var simple=dict.stream().filter(a->a.complexId().isBlank()||a.complexId().equals("0")).toList();item.add("attributes",mappedAttributes(shop,o.targetCategoryId(),simple,values,valueCache,issues));var complex=complexAttributes(shop,source,o,dict,values,valueCache,issues);if(!complex.isEmpty())item.add("complex_attributes",complex);items.add(item);
        }
        var docs=new JsonArray();for(var d:documents(source,o)){
            JsonObject params=certificate(shop,d,issues);if(params!=null)docs.add(params);
        }
        var request=new JsonObject();request.add("items",items);return withLabels(previewRecord(shop,source,o,request,media,docs,issues,articles,"CREATE"),categories,dict);
    }
    private JsonArray mappedAttributes(Shop shop,String cat,List<TargetAttribute> dict,Map<String,List<String>> values,Map<String,Map<String,String>> cache,List<CopyIssue> issues)throws IOException{
        var out=new JsonArray();for(var a:dict){var list=values.getOrDefault(a.id(),List.of());if(!list.isEmpty()&&a.maxValues()==1&&(a.dictionaryId().isBlank()||a.dictionaryId().equals("0"))&&ProductFieldNames.priority("material",a.name())==0)list=composition(list);if(list.isEmpty()){if(a.required())issue(issues,"REQUIRED_ATTRIBUTE",a.id());continue;}if(a.maxValues()>0&&list.size()>a.maxValues())issue(issues,"ATTRIBUTE_VALUE_LIMIT",a.id());
            var entry=new JsonObject();entry.addProperty("id",new BigInteger(a.id()));if(!a.complexId().isBlank()&&!a.complexId().equals("0"))entry.addProperty("complex_id",new BigInteger(a.complexId()));var vals=new JsonArray();Map<String,String> dictionary=Map.of();if(!a.dictionaryId().isBlank()&&!a.dictionaryId().equals("0")){dictionary=cache.get(a.id());boolean large=Set.of("Бренд","Бренд в одежде и обуви").contains(a.name())||ProductFieldNames.matches("tnved",a.name());
                if(large){var known=new LinkedHashMap<String,String>(dictionary==null?Map.of():dictionary);var missing=list.stream().filter(v->!known.containsKey(v)).toList();if(!missing.isEmpty())known.putAll(exactValues(shop,cat,a.id(),missing));dictionary=Map.copyOf(known);cache.put(a.id(),dictionary);}
                else if(dictionary==null){dictionary=values(shop,cat,a.id());cache.put(a.id(),dictionary);}}
            for(String value:list){var val=new JsonObject();val.addProperty("value",value);if(!dictionary.isEmpty()||(!a.dictionaryId().isBlank()&&!a.dictionaryId().equals("0"))){String id=dictionary.get(value);if(id==null)issue(issues,"DICTIONARY_VALUE_REQUIRED",a.id());else val.addProperty("dictionary_value_id",new BigInteger(id));}else if(a.type().equals("integer")||a.type().equals("decimal")){try{new BigDecimal(value);}catch(NumberFormatException e){issue(issues,"ATTRIBUTE_NUMBER_REQUIRED",a.id());}}vals.add(val);}entry.add("values",vals);out.add(entry);
        }return out;
    }
    private static List<String> composition(List<String> values){
        var parts=new ArrayList<String>();
        for(String value:values){
            var trailing=java.util.regex.Pattern.compile("^([^%,]+?)\\s+(\\d{1,3}(?:[.,]\\d+)?)\\s*%$").matcher(value.strip());
            parts.add(trailing.matches()?trailing.group(2)+"% "+trailing.group(1).strip():value.strip());
        }
        return List.of(String.join(", ",parts));
    }
    private JsonArray complexAttributes(Shop s,ProductSnapshot source,CopyOptions o,List<TargetAttribute> dict,Map<String,List<String>> overrides,Map<String,Map<String,String>> cache,List<CopyIssue> issues)throws IOException{
        if(o.fields().contains("attributes")&&"true".equals(source.metadata().get("complex.ambiguous")))issue(issues,"COMPLEX_GROUP_PROOF_REQUIRED","attributes");
        var grouped=new LinkedHashMap<String,Map<String,List<String>>>();if(o.fields().contains("attributes"))for(var a:source.attributes())if(a.complexId()!=null&&!a.complexId().isBlank()&&!a.complexId().equals("0")){
            String id=o.attributeMapping().get(a.mappingKey());if("@skip".equals(id)){issues.add(new CopyIssue("ATTRIBUTE_EXPLICITLY_OMITTED",a.mappingKey(),false));continue;}var target=dict.stream().filter(d->d.id().equals(id)&&!d.complexId().isBlank()&&!d.complexId().equals("0")).findFirst();if(target.isEmpty()){issue(issues,"COMPLEX_ATTRIBUTE_MAPPING_REQUIRED",a.mappingKey());continue;}grouped.computeIfAbsent(a.complexId()+":"+a.groupIndex()+":"+target.get().complexId(),k->new LinkedHashMap<>()).put(id,a.values());
        }
        for(var a:dict)if(!a.complexId().isBlank()&&!a.complexId().equals("0")&&overrides.containsKey(a.id())){boolean placed=false;for(var g:grouped.entrySet())if(g.getKey().endsWith(":"+a.complexId())){g.getValue().put(a.id(),overrides.get(a.id()));placed=true;}if(!placed)grouped.computeIfAbsent("manual:0:"+a.complexId(),k->new LinkedHashMap<>()).put(a.id(),overrides.get(a.id()));}
        var result=new JsonArray();for(var group:grouped.entrySet()){String targetComplex=group.getKey().substring(group.getKey().lastIndexOf(':')+1);var fields=dict.stream().filter(a->a.complexId().equals(targetComplex)).toList();var data=new JsonObject();data.add("attributes",mappedAttributes(s,o.targetCategoryId(),fields,group.getValue(),cache,issues));result.add(data);}if(result.isEmpty()&&dict.stream().anyMatch(a->a.required()&&!a.complexId().isBlank()&&!a.complexId().equals("0")))issue(issues,"COMPLEX_ATTRIBUTE_MAPPING_REQUIRED","attributes");return result;
    }
    /** Resolve only the selected terms in large brand/TN VED dictionaries. */
    private Map<String,String> exactValues(Shop shop,String cat,String attribute,List<String> selected)throws IOException {
        var result=new LinkedHashMap<String,String>();
        for(String value:selected){
            if(value.strip().length()<2){result.putAll(values(shop,cat,attribute));continue;}
            var b=category(cat);b.addProperty("attribute_id",new BigInteger(attribute));b.addProperty("value",value);b.addProperty("limit",100);
            var response=call(shop,"v1/description-category/attribute/values/search",b,false);
            if(!response.has("result")||!response.get("result").isJsonArray())throw new IOException("Dictionary search unavailable");
            for(var e:array(response,"result")){var found=object(e);String term=text(found,"value"),id=text(found,"id");
                if(!term.equals(value))continue;
                if(!id.matches("[1-9][0-9]{0,18}")||(result.containsKey(term)&&!result.get(term).equals(id)))throw new IOException("Ambiguous destination dictionary value");
                result.put(term,id);
            }
        }
        return Map.copyOf(result);
    }
    public Map<String,String> values(Shop shop,String cat,String attribute)throws IOException{
        var result=new LinkedHashMap<String,String>();String last="0";for(int page=0;page<10;page++){var b=category(cat);b.addProperty("attribute_id",new BigInteger(attribute));b.addProperty("last_value_id",new BigInteger(last));b.addProperty("limit",2000);var response=call(shop,"v1/description-category/attribute/values",b,false);var data=array(response,"result");for(var e:data){var d=object(e);String value=text(d,"value"),id=text(d,"id");if(result.containsKey(value)&&!result.get(value).equals(id))throw new IOException("Ambiguous destination dictionary value");result.put(value,id);last=id;}if(!"true".equals(text(response,"has_next")))return Map.copyOf(result);if(data.isEmpty())throw new IOException("Dictionary cursor unavailable");}throw new IOException("Dictionary pagination limit");
    }
    private JsonObject certificate(Shop shop,ProductDocument d,List<CopyIssue> issues)throws IOException{
        if(!validDocument(d)){issue(issues,"DOCUMENT_INVALID",d.number());return null;}
        if(d.file()==null){issue(issues,"DOCUMENT_FILE_REQUIRED",d.number());return null;}if(!"file".equalsIgnoreCase(d.file().getScheme())){issue(issues,"LOCAL_DOCUMENT_FILE_REQUIRED",d.number());return null;}
        var params=new JsonObject();for(String field:List.of("certificate_type","certificate_country","accordance_type","name","number","issue_date","link_to_registry","product_type")){String value=d.metadata().get(field);if(value!=null&&!value.isBlank())params.addProperty(field,value);}
        if(!params.has("number"))params.addProperty("number",d.number());if(!params.has("issue_date")&&d.validFrom()!=null)params.addProperty("issue_date",d.validFrom()+"T00:00:00Z");
        var expiry=new JsonObject();if(d.validUntil()!=null){var date=new JsonObject();date.addProperty("year",d.validUntil().getYear());date.addProperty("month",d.validUntil().getMonthValue());date.addProperty("day",d.validUntil().getDayOfMonth());expiry.add("date",date);}else if("true".equals(d.metadata().get("isEndless")))expiry.addProperty("infinite",true);if(!expiry.isEmpty())params.add("expired_date",expiry);
        try{Path p=Path.of(d.file());if(Files.size(p)>10*1024*1024){issue(issues,"OZON_DOCUMENT_FILE_LIMIT",d.number());return null;}String mime=ListingMediaReader.magic(p);if(!Set.of("image/jpeg","image/png","application/pdf").contains(Objects.toString(mime,""))){issue(issues,"DOCUMENT_TYPE_MISMATCH",d.number());return null;}
            byte[] bytes;try(var in=Files.newInputStream(p)){bytes=in.readNBytes(10*1024*1024+1);}if(bytes.length>10*1024*1024){issue(issues,"OZON_DOCUMENT_FILE_LIMIT",d.number());return null;}var f=new JsonObject();f.addProperty("name",mime.equals("application/pdf")?"certificate.pdf":mime.equals("image/png")?"certificate.png":"certificate.jpg");f.addProperty("file_content",Base64.getEncoder().encodeToString(bytes));var files=new JsonArray();files.add(f);params.add("files",files);
        }catch(IOException|RuntimeException e){issue(issues,"DOCUMENT_READ_FAILED",d.number());return null;}
        var options=call(shop,"v2/product/certification/options",new JsonObject(),false);if(!options.has("option")){issue(issues,"DOCUMENT_OPTIONS_UNAVAILABLE",d.number());return null;}
        var b=new JsonObject();b.add("params",params);var required=call(shop,"v2/product/certification/params",b,false);if(!required.has("params")){issue(issues,"DOCUMENT_RULES_UNAVAILABLE",d.number());return null;}
        for(var e:array(required,"params")){var p=object(e);String field=text(p,"name").toLowerCase(Locale.ROOT);if("true".equals(text(p,"required"))&&!field.equals("skus")&&!params.has(field))issue(issues,"DOCUMENT_METADATA_REQUIRED",field);var variants=array(p,"variants");if(params.has(field)&&!variants.isEmpty()&&variants.asList().stream().noneMatch(v->text(object(v),"value").equals(text(params,field))))issue(issues,"DOCUMENT_TYPE_MAPPING",field);}
        return params;
    }
    public RemoteSubmission submit(Shop s,CopyPreview p)throws IOException{requirePreview(s,p);if(!p.operation().equals("CREATE"))throw new SecurityException("Wrong listing operation");for(String a:p.articles())if(!find(s,a).isEmpty())throw new IOException("Destination article now exists");return send(s,p);}
    private RemoteSubmission send(Shop s,CopyPreview p)throws IOException{String task=text(obj(call(s,"v3/product/import",JsonParser.parseString(p.requestJson()),true),"result"),"task_id");if(!task.matches("[1-9][0-9]{0,18}"))throw new IOException("Import task unavailable; reconciliation required");return submitted(p,task);}
    public RemoteResult reconcile(Shop s,RemoteSubmission r)throws IOException{
        owner(s,r);if(r.taskId()!=null){var b=new JsonObject();b.addProperty("task_id",new BigInteger(r.taskId()));var items=array(obj(call(s,"v1/product/import/info",b,false),"result"),"items");for(String article:r.articles()){
                var matches=items.asList().stream().map(ListingJson::object).filter(i->text(i,"offer_id").equals(article)).toList();if(matches.isEmpty())return RemoteResult.pending();if(matches.size()!=1)throw new IOException("Import owner identity mismatch");var item=matches.getFirst();if(!array(item,"errors").isEmpty()){String reason=array(item,"errors").asList().stream().map(ListingJson::object).map(error->text(error,"code")+": "+text(error,"message")).collect(java.util.stream.Collectors.joining("; "));return new RemoteResult(CopyState.FAILED,List.of(),List.of(new CopyIssue("IMPORT_REJECTED",ListingRejectedException.safe(s,article+": "+reason),true)),Map.of());}if(!text(item,"status").equals("imported"))return RemoteResult.pending();}}
        var ids=new LinkedHashMap<String,String>();for(var e:array(object(JsonParser.parseString(r.requestJson())),"items")){var want=object(e);String article=text(want,"offer_id");var found=find(s,article);if(found.size()!=1)return RemoteResult.pending();var info=found.getFirst();String id=text(info,"id");if(!id.matches("[1-9][0-9]*"))throw new IOException("Destination product identity unavailable");if(r.identities().containsKey(article)&&!id.equals(r.identities().get(article)))throw new IOException("Destination product identity changed");
            var filter=new JsonObject();var array=new JsonArray();array.add(id);filter.add("product_id",array);var b=new JsonObject();b.add("filter",filter);b.addProperty("limit",1);var attrs=array(call(s,"v4/product/info/attributes",b,false),"result");if(attrs.size()!=1||!text(object(attrs.get(0)),"id").equals(id)||!matches(want,info,object(attrs.get(0))))return RemoteResult.pending();if(!urls(r.mediaJson()).isEmpty()&&!imagesMatch(r.mediaJson(),OzonListingImages.ordered(info,object(attrs.get(0)))))return RemoteResult.pending();ids.put(article,id);ids.put("sku:"+article,text(info,"sku"));
        }
        boolean docs=!JsonParser.parseString(r.documentsJson()).getAsJsonArray().isEmpty()&&r.phase().equals("CARD");if(r.phase().equals("ASSETS_SENT")||r.phase().equals("ASSETS_SENDING"))for(var e:JsonParser.parseString(r.documentsJson()).getAsJsonArray())for(String a:r.articles()){var b=new JsonObject();b.addProperty("offer_id",a);b.addProperty("page",1);b.addProperty("page_size",100);String number=text(object(e),"number");if(array(obj(call(s,"v1/product/certificate/list",b,false),"result"),"certificates").asList().stream().noneMatch(v->text(object(v),"certificate_number").equals(number)))return RemoteResult.pending();}
        return new RemoteResult(docs?CopyState.REMOTE_PROCESSING:CopyState.RECONCILED,ids.entrySet().stream().filter(e->!e.getKey().startsWith("sku:")).map(Map.Entry::getValue).toList(),docs?List.of(new CopyIssue("ASSETS_REQUIRED","documents",false)):List.of(),ids);
    }
    public RemoteSubmission finishAssets(Shop s,RemoteSubmission r)throws IOException{
        owner(s,r);if(!r.phase().equals("ASSETS_SENDING")||r.identities().isEmpty())throw new SecurityException("Assets are not claimed");for(var e:JsonParser.parseString(r.documentsJson()).getAsJsonArray()){var params=object(e).deepCopy();var skus=new JsonArray();for(String a:r.articles()){String sku=r.identities().get("sku:"+a);var actual=find(s,a);if(actual.size()!=1||!text(actual.getFirst(),"sku").equals(sku)||!text(actual.getFirst(),"id").equals(r.identities().get(a)))throw new SecurityException("Destination certificate owner changed");if(sku==null||!sku.matches("[1-9][0-9]*"))throw new IOException("Destination SKU unavailable");skus.add(sku);}params.add("skus",skus);var b=new JsonObject();b.add("params",params);var response=call(s,"v2/product/certificate/create",b,true);if(!text(response,"status").equals("COMPLETED")||text(response,"certificate_id").isBlank())throw new IOException("Certificate submission incomplete");}
        return r.withPhase("ASSETS_SENT",r.identities());
    }
    public ProductEditPreview previewUpdate(Shop s,ProductSnapshot fresh,Map<String,List<String>> changes,List<ProductDocument> documents)throws IOException{
        owner(s);ListingIdentity.require(s,fresh.key());if(!fresh.revision().startsWith("live:")||!ListingIdentity.fingerprint(s).equals(fresh.metadata().get("owner")))throw new SecurityException("Fresh own product required");if(!changes.containsKey("title"))return previewAttributeUpdate(s,fresh,changes,documents);var raw=object(JsonParser.parseString(fresh.metadata().get("source.payload")));var info=obj(raw,"info");var attr=obj(raw,"attributes");var item=new JsonObject();
        for(String key:List.of("offer_id","name","description_category_id","type_id","depth","width","height","dimension_unit","weight","weight_unit","primary_image","images","color_image"))if(attr.has(key))item.add(key,attr.get(key).deepCopy());
        for(String key:List.of("price","old_price","min_price","currency_code","vat"))if(info.has(key))item.add(key,info.get(key).deepCopy());String barcode=text(attr,"barcode");if(!barcode.isBlank())item.addProperty("barcode",barcode);var attrs=array(attr,"attributes").deepCopy();var dictionary=attributes(s,fresh.categoryId());var issues=new ArrayList<CopyIssue>();var vals=new LinkedHashMap<String,List<String>>();
        if(attr.has("pdf_list")){
            var pdfs=new JsonArray();int index=1;
            for(var e:array(attr,"pdf_list")){
                var pdf=object(e);String url=text(pdf,"file_name");
                try{var uri=java.net.URI.create(url);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException();}
                catch(IllegalArgumentException bad){issue(issues,"OWN_PDF_SOURCE_UNAVAILABLE","pdf_list");continue;}
                var out=new JsonObject();out.addProperty("index",index++);out.addProperty("name",text(pdf,"name"));out.addProperty("src_url",url);pdfs.add(out);
            }
            item.add("pdf_list",pdfs);
        }
        attrs=importAttributes(attrs);
        var groups=new LinkedHashMap<String,JsonArray>();var seen=new HashSet<String>();for(var e:array(attr,"complex_attributes")){var a=object(e);String group=text(a,"complex_id");if(!seen.add(group+":"+text(a,"id")))issue(issues,"COMPLEX_GROUP_PROOF_REQUIRED","attributes");var mapped=a.deepCopy();mapped.add("values",importValues(array(a,"values")));groups.computeIfAbsent(group,k->new JsonArray()).add(mapped);}if(!groups.isEmpty()){var complex=new JsonArray();for(var entries:groups.values()){var group=new JsonObject();group.add("attributes",entries);complex.add(group);}item.add("complex_attributes",complex);}
        var barcodes=strings(array(attr,"barcodes"));if(barcodes.size()>1)issue(issues,"OWN_BARCODE_SET_UNSUPPORTED","barcodes");else if(barcode.isBlank()&&barcodes.size()==1)item.addProperty("barcode",barcodes.getFirst());
        for(var change:changes.entrySet()){if(change.getKey().equals("title")){if(change.getValue().size()!=1||change.getValue().getFirst().isBlank()||change.getValue().getFirst().length()>500)issue(issues,"TITLE_LIMIT","title");else item.addProperty("name",change.getValue().getFirst());continue;}String id=change.getKey().startsWith("attribute:")?change.getKey().substring(10):change.getKey().equals("brand")?dictionary.stream().filter(a->a.name().equals("Бренд")||a.name().equals("Бренд в одежде и обуви")).map(TargetAttribute::id).findFirst().orElse(""):"";if(id.isBlank()||dictionary.stream().noneMatch(a->a.id().equals(id))){issue(issues,"FOREIGN_TARGET_ATTRIBUTE",change.getKey());continue;}for(int i=attrs.size()-1;i>=0;i--)if(text(object(attrs.get(i)),"id").equals(id))attrs.remove(i);vals.put(id,change.getValue());}
        var changed=mappedAttributes(s,fresh.categoryId(),dictionary.stream().filter(a->vals.containsKey(a.id())).toList(),vals,new HashMap<>(),issues);changed.forEach(attrs::add);item.add("attributes",attrs);if(!item.has("price"))issue(issues,"OWN_PRICE_UNAVAILABLE","price");
        var docs=new JsonArray();if(documents!=null)for(var d:documents){var params=certificate(s,d,issues);if(params!=null)docs.add(params);}var items=new JsonArray();items.add(item);var request=new JsonObject();request.add("items",items);var o=new CopyOptions(changes.keySet(),Set.of(),fresh.categoryId(),Map.of(),changes,Map.of(),null,"RUB",List.of(),null);return new ProductEditPreview(fresh.key(),fresh.revision(),previewRecord(s,fresh,o,request,new JsonArray(),docs,issues,List.of(fresh.vendorArticle()),"UPDATE"));
    }
    /** Partial updates preserve prices, barcode sets, media and unrelated attributes server-side. */
    private ProductEditPreview previewAttributeUpdate(Shop s,ProductSnapshot fresh,Map<String,List<String>> changes,List<ProductDocument> documents)throws IOException{
        var dictionary=attributes(s,fresh.categoryId());var issues=new ArrayList<CopyIssue>();var values=new LinkedHashMap<String,List<String>>();
        for(var change:changes.entrySet()){
            String id=change.getKey().startsWith("attribute:")?change.getKey().substring(10):change.getKey().equals("brand")?dictionary.stream().filter(a->Set.of("Бренд","Бренд в одежде и обуви").contains(a.name())).map(TargetAttribute::id).findFirst().orElse(""):"";
            var target=dictionary.stream().filter(a->a.id().equals(id)&&!a.namedField()&&(a.complexId().isBlank()||a.complexId().equals("0"))).toList();
            if(id.isBlank()||target.size()!=1){issue(issues,"FOREIGN_TARGET_ATTRIBUTE",change.getKey());continue;}
            if(change.getValue().isEmpty()||change.getValue().stream().anyMatch(String::isBlank)){issue(issues,"VALUE_REQUIRED",change.getKey());continue;}values.put(id,change.getValue());
        }
        var attrs=mappedAttributes(s,fresh.categoryId(),dictionary.stream().filter(a->values.containsKey(a.id())).toList(),values,new HashMap<>(),issues);
        var item=new JsonObject();item.addProperty("offer_id",fresh.vendorArticle());if(!changes.isEmpty())item.add("attributes",attrs);
        var docs=new JsonArray();if(documents!=null)for(var d:documents){var params=certificate(s,d,issues);if(params!=null)docs.add(params);}
        var items=new JsonArray();items.add(item);var request=new JsonObject();request.add("items",items);
        var options=new CopyOptions(changes.keySet(),Set.of(),fresh.categoryId(),Map.of(),changes,Map.of(),null,"RUB",List.of(),null);
        return new ProductEditPreview(fresh.key(),fresh.revision(),previewRecord(s,fresh,options,request,new JsonArray(),docs,issues,List.of(fresh.vendorArticle()),changes.isEmpty()?"UPDATE_DOCUMENTS":"UPDATE_ATTRIBUTES"));
    }
    public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p)throws IOException{
        requirePreview(s,p.preview());if(p.preview().operation().equals("UPDATE"))return send(s,p.preview());
        return submitUpdates(s,List.of(p),()->{}).get(p.key()).submission();
    }
    public List<List<ProductEditPreview>> updateBatches(List<ProductEditPreview> previews)throws IOException{
        var result=new ArrayList<List<ProductEditPreview>>();var current=new ArrayList<ProductEditPreview>();String operation="";long bytes=12;
        for(var p:previews){
            String next=p.preview().operation();if(!Set.of("UPDATE","UPDATE_ATTRIBUTES","UPDATE_DOCUMENTS").contains(next))throw new IllegalArgumentException("Wrong edit operation");
            var items=array(object(JsonParser.parseString(p.preview().requestJson())),"items");if(items.size()!=1)throw new IllegalArgumentException("Single product preview required");
            int size=items.get(0).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(size+12>10_000_000)throw new IllegalArgumentException("OZON_UPDATE_TOO_LARGE");
            if(!current.isEmpty()&&(!operation.equals(next)||current.size()==100||bytes+size+1>10_000_000)){result.add(List.copyOf(current));current.clear();bytes=12;}
            operation=next;bytes+=size+(current.isEmpty()?0:1);current.add(p);
        }if(!current.isEmpty())result.add(List.copyOf(current));return List.copyOf(result);
    }
    public Map<ProductKey,ProductUpdateBatchAdapter.Outcome> submitUpdates(Shop s,List<ProductEditPreview> previews,Runnable beforeSend)throws IOException{
        if(previews.isEmpty()||updateBatches(previews).size()!=1)throw new IllegalArgumentException("OZON_BATCH_LIMIT");
        var items=new JsonArray();var keys=new HashSet<String>();
        for(var p:previews){requirePreview(s,p.preview());ListingIdentity.require(s,p.key());if(!keys.add(p.preview().articles().getFirst()))throw new IllegalArgumentException("Duplicate edit article");items.add(array(object(JsonParser.parseString(p.preview().requestJson())),"items").get(0));}
        String operation=previews.getFirst().preview().operation(),task=null;var started=Instant.now();
        if(!operation.equals("UPDATE_DOCUMENTS")){
            var body=new JsonObject();body.add("items",items);var response=object(clients.apply(s).call(operation.equals("UPDATE_ATTRIBUTES")?"v1/product/attributes/update":"v3/product/import",body,true,()->{for(var p:previews)requirePreview(s,p.preview());beforeSend.run();}));
            if("true".equals(text(response,"error")))throw new ListingRejectedException("Ozon product update rejected");
            task=operation.equals("UPDATE_ATTRIBUTES")?text(response,"task_id"):text(obj(response,"result"),"task_id");
            if(!task.matches("[1-9][0-9]{0,18}"))throw new IOException("Import task unavailable; reconciliation required");
        }else beforeSend.run();
        var outcomes=new LinkedHashMap<ProductKey,ProductUpdateBatchAdapter.Outcome>();
        for(var p:previews){var r=submitted(p.preview(),task);outcomes.put(p.key(),ProductUpdateBatchAdapter.Outcome.accepted(new RemoteSubmission(r.shopId(),r.marketplace(),r.owner(),r.requestHash(),task,r.articles(),r.requestJson(),r.mediaJson(),r.documentsJson(),r.phase(),Map.of(r.articles().getFirst(),p.key().cardId()),started)));}
        return Map.copyOf(outcomes);
    }
    public List<JsonObject> find(Shop s,String article)throws IOException{var b=new JsonObject();var ids=new JsonArray();ids.add(article);b.add("offer_id",ids);return array(call(s,"v3/product/info/list",b,false),"items").asList().stream().map(ListingJson::object).filter(o->text(o,"offer_id").equals(article)).toList();}
    private static boolean matches(JsonObject want,JsonObject info,JsonObject attrs){
        for(String key:List.of("offer_id","name","description_category_id","type_id","depth","width","height","weight","dimension_unit","weight_unit"))if(want.has(key)&&!same(want.get(key),attrs.get(key)))return false;
        if(want.has("price")&&(decimal(info,"price")==null||new BigDecimal(text(want,"price")).compareTo(decimal(info,"price"))!=0))return false;
        if(want.has("barcode")&&!strings(array(attrs,"barcodes")).contains(text(want,"barcode"))&&!text(attrs,"barcode").equals(text(want,"barcode")))return false;
        for(var e:array(want,"attributes")){var a=object(e);if(array(attrs,"attributes").asList().stream().noneMatch(v->text(object(v),"id").equals(text(a,"id"))&&same(importValues(array(a,"values")),importValues(array(object(v),"values")))))return false;}
        if(want.has("complex_attributes")){var actual=array(attrs,"complex_attributes").asList().stream().map(ListingJson::object).toList();for(var group:array(want,"complex_attributes"))for(var e:array(object(group),"attributes")){var a=object(e);if(actual.stream().noneMatch(v->text(v,"id").equals(text(a,"id"))&&text(v,"complex_id").equals(text(a,"complex_id"))&&same(importValues(array(v,"values")),importValues(array(a,"values")))))return false;}}
        if(want.has("pdf_list")&&!same(pdfIdentities(array(want,"pdf_list")),pdfIdentities(array(attrs,"pdf_list"))))return false;
        if(want.has("images")&&OzonListingImages.ordered(info,attrs).size()!=array(want,"images").size())return false;return true;
    }
    private static JsonArray importValues(JsonArray values){var out=new JsonArray();for(var e:values){var value=object(e).deepCopy();if(value.has("dictionaryValueId")){var id=value.remove("dictionaryValueId");if(value.has("dictionary_value_id")&&!same(id,value.get("dictionary_value_id")))throw new IllegalArgumentException("Conflicting dictionary identity");value.add("dictionary_value_id",id);}out.add(value);}return out;}
    private static JsonArray importAttributes(JsonArray attributes){var out=new JsonArray();for(var e:attributes){var attribute=object(e).deepCopy();attribute.add("values",importValues(array(attribute,"values")));out.add(attribute);}return out;}
    private static JsonArray pdfIdentities(JsonArray pdfs){var out=new JsonArray();for(var e:pdfs){var pdf=object(e);var identity=new JsonObject();identity.addProperty("name",text(pdf,"name"));identity.addProperty("src_url",pdf.has("src_url")?text(pdf,"src_url"):text(pdf,"file_name"));out.add(identity);}return out;}
}
