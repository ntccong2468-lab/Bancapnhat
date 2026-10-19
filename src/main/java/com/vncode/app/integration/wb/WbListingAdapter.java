package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import static com.vncode.app.integration.listing.ListingJson.*;

public final class WbListingAdapter extends AbstractListingAdapter {
    private final Function<Shop,ListingTransport> priceClients;
    public WbListingAdapter(){this(ListingHttpClient::new,ListingMediaInspector.fullSize(),s->new ListingHttpClient(s,new okhttp3.OkHttpClient(),okhttp3.HttpUrl.parse("https://discounts-prices-api.wildberries.ru/")));}
    public WbListingAdapter(Function<Shop,ListingTransport> c,ListingMediaInspector images){this(c,images,c);}
    public WbListingAdapter(Function<Shop,ListingTransport> c,ListingMediaInspector images,Function<Shop,ListingTransport> prices){super(Marketplace.WILDBERRIES,c,images);priceClients=Objects.requireNonNull(prices);}
    public List<TargetCategory> categories(Shop s)throws IOException{
        var result=new ArrayList<TargetCategory>();for(int page=0;page<20;page++){var data=array(call(s,"content/v2/object/all?limit=1000&offset="+(page*1000),null,false),"data");for(var e:data){var o=object(e);result.add(new TargetCategory(text(o,"subjectID"),text(o,"parentName")+" / "+text(o,"subjectName")));}if(data.size()<1000)return List.copyOf(result);}throw new IOException("WB category pagination limit");
    }
    public List<TargetAttribute> attributes(Shop s,String cat)throws IOException{
        if(!cat.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Invalid destination subject");var result=new ArrayList<TargetAttribute>();
        for(var e:array(call(s,"content/v2/object/charcs/"+cat,null,false),"data")){var a=object(e);result.add(new TargetAttribute(text(a,"charcID"),text(a,"name"),text(a,"charcType"),"true".equals(text(a,"required")),text(a,"unitName"),decimal(a,"maxCount")==null?0:decimal(a,"maxCount").intValue(),"","","true".equals(text(a,"existNamedField"))));}return List.copyOf(result);
    }
    @Override public List<String> suggestValues(Shop shop,String cat,String field,String query)throws IOException {
        return suggestValues(shop,cat,field,query,values->{});
    }
    @Override public List<String> suggestValues(Shop shop,String cat,String field,String query,java.util.function.Consumer<List<String>> partial)throws IOException {
        owner(shop);
        if(!cat.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Invalid destination subject");
        var values=new LinkedHashSet<String>();
        if(field.equals("brand")){
            String next="";var cursors=new HashSet<String>();
            for(int page=0;page<20;page++){
                var response=call(shop,"api/content/v1/brands?subjectId="+cat+(next.isBlank()?"":"&next="+next),null,false);
                if(!response.has("brands")||!response.get("brands").isJsonArray())throw new IOException("Brand dictionary unavailable");
                for(var v:array(response,"brands")){String name=text(object(v),"name");if(!name.isBlank())values.add(name);}
                partial.accept(List.copyOf(values));
                String cursor=text(response,"next");
                if(cursor.isBlank()||cursor.equals("0"))return List.copyOf(values);
                if(!cursor.matches("[1-9][0-9]{0,18}")||!cursors.add(cursor))throw new IOException("Brand dictionary cursor unavailable");
                next=cursor;
            }
            throw new IOException("Brand dictionary pagination limit");
        }
        if(field.equals("gender"))for(var v:array(call(shop,"content/v2/directory/kinds",null,false),"data"))values.add(v.getAsString());
        if(field.equals("tnved")){
            String search=query.strip();if(!search.matches("[0-9]{0,10}"))return List.of();
            for(var v:array(call(shop,"content/v2/directory/tnved?subjectID="+cat+"&search="+search,null,false),"data"))values.add(text(object(v),"tnved"));
        }
        return List.copyOf(values);
    }
    public CopyPreview preview(Shop shop,ProductSnapshot source,CopyOptions requested)throws IOException{
        owner(shop);var issues=new ArrayList<CopyIssue>();var categories=categories(shop);var base=CopyDefaults.withCategory(source,shop,requested,categories);
        if(base.targetCategoryId()==null||!base.targetCategoryId().matches("[1-9][0-9]{0,18}")||categories.stream().noneMatch(c->c.id().equals(base.targetCategoryId()))){issue(issues,"TARGET_CATEGORY_REQUIRED","category");return previewRecord(shop,source,base,new JsonArray(),new JsonArray(),new JsonArray(),issues,List.of(),"CREATE");}
        var dictionary=attributes(shop,base.targetCategoryId());var o=CopyDefaults.complete(source,shop,base,dictionary);var selected=selected(source,o,issues);var mapped=mapped(source,o,dictionary,issues);var chars=new JsonArray();
        if(selected.stream().anyMatch(v->CopyDefaults.inheritedCurrencyChanged(source,o,v)))issue(issues,"WB_PRICE_CURRENCY_UNVERIFIED","currency");
        if(!"RUB".equals(o.currency())&&(selected.stream().anyMatch(v->CopyDefaults.targetPrice(source,o,v)!=null)))issue(issues,"WB_PRICE_CURRENCY_UNVERIFIED","currency");
        for(var d:dictionary){var values=mapped.getOrDefault(d.id(),List.of());if(values.isEmpty()){if(d.required()&&!d.namedField())issue(issues,"REQUIRED_ATTRIBUTE",d.id());continue;}
            if(d.namedField()||!(d.type().equals("1")||d.type().equals("4"))){issue(issues,"ATTRIBUTE_TYPE_UNSUPPORTED",d.id());continue;}if(d.maxValues()>0&&values.size()>d.maxValues())issue(issues,"ATTRIBUTE_VALUE_LIMIT",d.id());var a=new JsonObject();a.addProperty("id",new BigInteger(d.id()));try{if(d.type().equals("4")){if(values.size()!=1)throw new NumberFormatException();a.addProperty("value",new java.math.BigDecimal(values.getFirst()));}else a.add("value",ProductJson.GSON.toJsonTree(values));chars.add(a);}catch(NumberFormatException e){issue(issues,"ATTRIBUTE_NUMBER_REQUIRED",d.id());}
        }
        String article=selected.isEmpty()?"":article(o,selected.getFirst(),issues);if(!article.isBlank()&&!find(shop,article).isEmpty())issue(issues,"ARTICLE_COLLISION","article");
        String title=namedValue(source,o,"title",source.title());var listing=new JsonObject();listing.addProperty("vendorCode",article);listing.addProperty("title",title);if(title==null||title.isBlank()||title.length()>60)issue(issues,"TITLE_LIMIT","title");
        if(o.fields().contains("description"))listing.addProperty("description",namedValue(source,o,"description",source.description()));if(o.fields().contains("brand"))listing.addProperty("brand",namedValue(source,o,"brand",source.brand()));listing.add("characteristics",chars);
        if(o.kizMarked()!=null)listing.addProperty("kizMarked",o.kizMarked());
        if(source.key().marketplace()==Marketplace.WILDBERRIES&&"true".equals(text(object(JsonParser.parseString(source.metadata().getOrDefault("source.payload","{}"))),"needKiz"))&&!Boolean.TRUE.equals(o.kizMarked()))issue(issues,"MARKING_CONFIRMATION_REQUIRED","kizMarked");
        var sizes=new JsonArray();for(var v:selected){var overrides=o.variantValues().getOrDefault(v.id(),Map.of());String tech=overrides.getOrDefault("@size",List.of(Objects.toString(v.size(),""))).getFirst(),ru=overrides.getOrDefault("@russianSize",List.of(Objects.toString(v.russianSize(),""))).getFirst();var size=new JsonObject();size.addProperty("techSize",tech.isBlank()?"0":tech);if(!ru.isBlank())size.addProperty("wbSize",ru);else if(o.fields().contains("sizes")&&!tech.isBlank()&&!tech.equals("0"))issue(issues,"RUSSIAN_SIZE_REQUIRED",v.id());var skus=new JsonArray();if(o.fields().contains("gtin")){for(String barcode:CopyDefaults.barcodes(source,v)){skus.add(barcode);if(!find(shop,barcode).isEmpty())issue(issues,"GTIN_COLLISION",v.id());}}size.add("skus",skus);var inheritedPrice=CopyDefaults.targetPrice(source,o,v);if(inheritedPrice!=null){try{size.addProperty("price",inheritedPrice.intValueExact());}catch(ArithmeticException e){issue(issues,"PRICE_INTEGER_REQUIRED","price");}}sizes.add(size);}listing.add("sizes",sizes);
        listing.add("dimensions",dimensions(source.dimensions(),false,issues));var docs=new JsonArray();for(var d:documents(source,o)){
            if(!validDocument(d)){issue(issues,"DOCUMENT_INVALID",d.number());continue;}
            if(!wbType(d).matches("[1-9]")){issue(issues,"DOCUMENT_TYPE_MAPPING",d.number());continue;}
            if(d.number()==null||d.number().isBlank()||d.validFrom()==null||(d.validUntil()==null&&!"true".equals(d.metadata().get("isEndless"))))issue(issues,"DOCUMENT_METADATA_REQUIRED",d.number());docs.add(wbDocument(d,false));
        }if(!docs.isEmpty()){var data=new JsonObject();data.add("items",docs);data.addProperty("excludeDocuments",false);listing.add("documents",data);issues.add(new CopyIssue("DESTINATION_DOCUMENT_APPROVAL_PENDING","documents",false));}
        var variants=new JsonArray();variants.add(listing);var group=new JsonObject();group.addProperty("subjectID",new BigInteger(o.targetCategoryId()));group.add("variants",variants);var request=new JsonArray();request.add(group);
        return withLabels(previewRecord(shop,source,o,request,media(source,o,issues),new JsonArray(),issues,List.of(article),"CREATE"),categories,dictionary);
    }
    public RemoteSubmission submit(Shop s,CopyPreview p)throws IOException{requirePreview(s,p);if(!p.operation().equals("CREATE"))throw new SecurityException("Wrong listing operation");for(String a:p.articles())if(!find(s,a).isEmpty())throw new IOException("Destination article now exists");call(s,"content/v2/cards/upload",JsonParser.parseString(p.requestJson()),true);return submitted(p,null);}
    public RemoteResult reconcile(Shop s,RemoteSubmission r)throws IOException{
        owner(s,r);var ids=new LinkedHashMap<String,String>();var expected=JsonParser.parseString(r.requestJson()).getAsJsonArray();boolean update=expected.get(0).getAsJsonObject().has("nmID");var listings=update?expected:expected.get(0).getAsJsonObject().getAsJsonArray("variants");
        for(var e:listings){var want=object(e);String article=text(want,"vendorCode");var cards=find(s,article);if(cards.isEmpty()){var cursor=new JsonObject();cursor.addProperty("limit",100);var b=new JsonObject();b.add("cursor",cursor);var errors=array(obj(call(s,"content/v2/cards/error/list",b,false),"data"),"items");if(errors.asList().stream().anyMatch(v->strings(array(object(v),"vendorCodes")).contains(article)))return new RemoteResult(CopyState.FAILED,List.of(),List.of(new CopyIssue("IMPORT_REJECTED",article,true)),Map.of());return RemoteResult.pending();}if(cards.size()!=1)throw new IOException("Ambiguous destination article");var actual=cards.getFirst();String id=text(actual,"nmID");if(!id.matches("[1-9][0-9]*")||(update&&!text(want,"nmID").equals(id)))throw new IOException("Destination identity mismatch");
            if(!matches(want,actual)){
                String rejection=update?updateRejection(s,r,article):"";
                if(!rejection.isBlank())return new RemoteResult(CopyState.FAILED,List.of(id),List.of(new CopyIssue("PRODUCT_UPDATE_REJECTED",rejection,true)),Map.of(article,id));
                return RemoteResult.pending();
            }ids.put(article,id);
            if(!pricesMatch(s,want,actual))return new RemoteResult(CopyState.REMOTE_PROCESSING,List.copyOf(ids.values()),List.of(new CopyIssue("PRICE_READBACK_PENDING",article,false)),ids);
            if((r.phase().equals("ASSETS_SENT")||r.phase().equals("ASSETS_SENDING"))&&!urls(r.mediaJson()).isEmpty()&&!imagesMatch(r.mediaJson(),array(actual,"photos").asList().stream().map(v->text(object(v),"big")).toList()))return RemoteResult.pending();
        }
        boolean assets=!urls(r.mediaJson()).isEmpty()&&r.phase().equals("CARD");return new RemoteResult(assets?CopyState.REMOTE_PROCESSING:CopyState.RECONCILED,List.copyOf(ids.values()),assets?List.of(new CopyIssue("ASSETS_REQUIRED","images",false)):List.of(),ids);
    }
    public RemoteSubmission finishAssets(Shop s,RemoteSubmission r)throws IOException{owner(s,r);if(!r.phase().equals("ASSETS_SENDING")||r.identities().isEmpty())throw new SecurityException("Assets are not claimed");var urls=urls(r.mediaJson());if(!urls.isEmpty())for(String article:r.articles()){String id=r.identities().get(article);var cards=find(s,article);if(cards.size()!=1||!text(cards.getFirst(),"nmID").equals(id))throw new SecurityException("Destination media identity changed");var body=new JsonObject();body.addProperty("nmId",new BigInteger(id));body.add("data",urls);call(s,"content/v3/media/save",body,true);}return r.withPhase("ASSETS_SENT",r.identities());}
    public ProductEditPreview previewUpdate(Shop s,ProductSnapshot fresh,Map<String,List<String>> changes,List<ProductDocument> docs)throws IOException{
        owner(s);ListingIdentity.require(s,fresh.key());if(!fresh.revision().startsWith("live:")||!ListingIdentity.fingerprint(s).equals(fresh.metadata().get("owner")))throw new SecurityException("Fresh own card required");
        var source=object(JsonParser.parseString(fresh.metadata().get("source.payload")));var request=new JsonObject();for(String field:List.of("nmID","vendorCode","kizMarked","brand","title","gtin","description","dimensions","documents","characteristics","sizes"))if(source.has(field))request.add(field,source.get(field).deepCopy());
        String trimmedArticle=WbArticlePolicy.checked(source,clients.apply(s));request.addProperty("vendorCode",trimmedArticle);
        var issues=new ArrayList<CopyIssue>();var dictionary=attributes(s,fresh.categoryId());
        for(var change:changes.entrySet()){
            if(change.getKey().equals("title")){if(change.getValue().size()!=1||change.getValue().getFirst().isBlank()||change.getValue().getFirst().length()>60)issue(issues,"TITLE_LIMIT","title");else request.addProperty("title",change.getValue().getFirst());}
            else if(change.getKey().equals("brand")){if(change.getValue().size()!=1)issue(issues,"VALUE_REQUIRED","brand");else request.addProperty("brand",change.getValue().getFirst());}
            else if(change.getKey().startsWith("attribute:")){String id=change.getKey().substring(10);var d=dictionary.stream().filter(a->a.id().equals(id)&&!a.namedField()).findFirst();if(d.isEmpty()){issue(issues,"FOREIGN_TARGET_ATTRIBUTE",id);continue;}var chars=array(request,"characteristics");for(int i=chars.size()-1;i>=0;i--)if(text(object(chars.get(i)),"id").equals(id))chars.remove(i);var a=new JsonObject();a.addProperty("id",new BigInteger(id));if(d.get().type().equals("4")){try{a.addProperty("value",new java.math.BigDecimal(change.getValue().getFirst()));}catch(RuntimeException e){issue(issues,"ATTRIBUTE_NUMBER_REQUIRED",id);}}else a.add("value",ProductJson.GSON.toJsonTree(change.getValue()));chars.add(a);request.add("characteristics",chars);}
            else issue(issues,"EDIT_FIELD_UNSUPPORTED",change.getKey());
        }
        if(docs!=null){var items=new JsonArray();for(var d:docs){if(!validDocument(d)||!wbType(d).matches("[1-9]")){issue(issues,"DOCUMENT_INVALID",d.number());continue;}items.add(wbDocument(d,true));}var data=new JsonObject();data.add("items",items);data.addProperty("excludeDocuments",false);request.add("documents",data);}
        if(request.has("dimensions"))obj(request,"dimensions").remove("isValid");if(request.has("documents")){var data=obj(request,"documents");data.remove("overallVerdict");for(var e:array(data,"items")){var d=object(e);d.remove("verdict");d.remove("createdAt");}}
        var arr=new JsonArray();arr.add(request);var options=new CopyOptions(changes.keySet(),Set.of(),fresh.categoryId(),Map.of(),changes,Map.of(),null,"RUB",List.of(),null);var p=previewRecord(s,fresh,options,arr,new JsonArray(),new JsonArray(),issues,List.of(trimmedArticle),"UPDATE");return new ProductEditPreview(fresh.key(),fresh.revision(),p);
    }
    public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p)throws IOException{requirePreview(s,p.preview());if(!p.preview().operation().equals("UPDATE"))throw new SecurityException("Wrong edit operation");var started=java.time.Instant.now();call(s,"content/v2/cards/update",JsonParser.parseString(p.preview().requestJson()),true);var r=submitted(p.preview(),null);return new RemoteSubmission(r.shopId(),r.marketplace(),r.owner(),r.requestHash(),r.taskId(),r.articles(),r.requestJson(),r.mediaJson(),r.documentsJson(),r.phase(),r.identities(),started);}
    private String updateRejection(Shop shop,RemoteSubmission submission,String article)throws IOException{
        if(submission.submittedAt()==null)return "";
        var cursor=new JsonObject();cursor.addProperty("limit",100);var seen=new HashSet<String>();
        for(int page=0;page<20;page++){
            var body=new JsonObject();body.add("cursor",cursor);var data=obj(call(shop,"content/v2/cards/error/list",body,false),"data");
            for(var value:array(data,"items")){
                var item=object(value);java.time.Instant updated;
                try{updated=java.time.Instant.parse(text(item,"updatedAt"));}catch(RuntimeException unknownTime){continue;}
                if(updated.isBefore(submission.submittedAt())||updated.isAfter(java.time.Instant.now().plusSeconds(300)))continue;
                if(!strings(array(item,"vendorCodes")).contains(article))continue;
                var errors=obj(item,"errors").get(article);
                if(errors!=null&&errors.isJsonArray()&&!errors.getAsJsonArray().isEmpty())
                    return ListingRejectedException.safe(shop,article+": "+String.join("; ",strings(errors.getAsJsonArray())));
            }
            var next=obj(data,"cursor");if(!"true".equals(text(next,"next")))return "";
            String date=text(next,"updatedAt"),batch=text(next,"batchUUID");
            if(date.isBlank()||!batch.matches("[a-fA-F0-9-]{36}")||!seen.add(date+"/"+batch))throw new IOException("WB_ERROR_CURSOR_INVALID");
            cursor=new JsonObject();cursor.addProperty("limit",100);cursor.addProperty("updatedAt",date);cursor.addProperty("batchUUID",batch);
        }
        return "";
    }
    public List<JsonObject> find(Shop s,String article)throws IOException{
        var filter=new JsonObject();filter.addProperty("textSearch",article);filter.addProperty("withPhoto",-1);var settings=new JsonObject();settings.add("filter",filter);var cursor=new JsonObject();cursor.addProperty("limit",100);settings.add("cursor",cursor);var b=new JsonObject();b.add("settings",settings);var r=call(s,"content/v2/get/cards/list",b,false);var out=new ArrayList<JsonObject>();for(var e:array(r,"cards")){var c=object(e);if(text(c,"vendorCode").equals(article)||strings(array(c,"sizes")).contains(article)||array(c,"sizes").asList().stream().anyMatch(v->strings(array(object(v),"skus")).contains(article)))out.add(c);}return out;
    }
    private static boolean matches(JsonObject want,JsonObject actual){
        if(!subset(want,actual,Set.of("sizes","characteristics","documents","dimensions")))return false;
        for(var e:array(want,"sizes")){var size=object(e);if(array(actual,"sizes").asList().stream().noneMatch(a->subset(size,object(a),Set.of("price"))))return false;}
        for(var e:array(want,"characteristics")){var a=object(e);if(array(actual,"characteristics").asList().stream().noneMatch(v->text(object(v),"id").equals(text(a,"id"))&&same(a.get("value"),object(v).get("value"))))return false;}
        if(want.has("dimensions")&&!subset(obj(want,"dimensions"),obj(actual,"dimensions"),Set.of("isValid")))return false;
        for(var e:array(obj(want,"documents"),"items"))if(array(obj(actual,"documents"),"items").asList().stream().noneMatch(v->subset(object(e),object(v),Set.of())))return false;return true;
    }
    private boolean pricesMatch(Shop shop,JsonObject expected,JsonObject actual)throws IOException{
        var wanted=array(expected,"sizes").asList().stream().map(ListingJson::object).filter(size->size.has("price")).toList();if(wanted.isEmpty())return true;
        var response=object(priceClients.apply(shop).call("api/v2/list/goods/filter?limit=100&filterNmID="+text(actual,"nmID"),null,false));
        var goods=array(obj(response,"data"),"listGoods").asList().stream().map(ListingJson::object).filter(item->text(item,"nmID").equals(text(actual,"nmID"))).toList();if(goods.size()!=1)return false;
        for(var size:wanted){
            var matches=array(actual,"sizes").asList().stream().map(ListingJson::object).filter(value->subset(size,value,Set.of("price"))).toList();if(matches.size()!=1||text(matches.getFirst(),"chrtID").isBlank())return false;
            var prices=array(goods.getFirst(),"sizes").asList().stream().map(ListingJson::object).filter(value->text(value,"sizeID").equals(text(matches.getFirst(),"chrtID"))).toList();
            if(prices.size()!=1||decimal(prices.getFirst(),"price")==null||decimal(size,"price").compareTo(decimal(prices.getFirst(),"price"))!=0)return false;
        }
        return true;
    }
}
