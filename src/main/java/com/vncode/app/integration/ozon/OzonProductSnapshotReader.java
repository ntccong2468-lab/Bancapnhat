package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.vncode.app.integration.listing.ListingJson.*;

public final class OzonProductSnapshotReader implements ProductSnapshotReader {
    private final Function<Shop,ListingTransport> clients;
    public OzonProductSnapshotReader(){this(ListingHttpClient::new);}
    public OzonProductSnapshotReader(Function<Shop,ListingTransport> clients){this.clients=clients;}
    public ProductSnapshot read(Shop shop,ProductKey key)throws IOException{return read(shop,key,true);}
    public ProductSnapshot readForSynchronization(Shop shop,ProductKey key)throws IOException{return read(shop,key,false);}
    private ProductSnapshot read(Shop shop,ProductKey key,boolean includePrices)throws IOException{
        ListingIdentity.require(shop,key);if(key.marketplace()!=Marketplace.OZON)throw new SecurityException("Not an Ozon card");var client=clients.apply(shop);
        var ids=new JsonArray();ids.add(key.cardId());var b=new JsonObject();b.add("product_id",ids);
        var info=find(array(object(client.call("v3/product/info/list",b,false)),"items"),key.cardId());
        var filter=new JsonObject();filter.add("product_id",ids);b=new JsonObject();b.add("filter",filter);b.addProperty("limit",1);
        var attr=find(array(object(client.call("v4/product/info/attributes",b,false)),"result"),key.cardId());
        if(info==null||attr==null||!attr.has("attributes")||!attr.has("dimension_unit")||!attr.has("weight_unit")||!attr.has("description_category_id")||!attr.has("type_id"))throw new IOException("Complete Ozon card unavailable");
        if(!text(info,"offer_id").equals(text(attr,"offer_id"))||(!key.variantId().isBlank()&&!key.variantId().equals(text(attr,"offer_id"))))throw new IOException("Ozon variant identity changed");
        var attributes=new ArrayList<ProductAttribute>();String description="",brand="",size="",color="",russianSize="";var cat=new JsonObject();cat.addProperty("description_category_id",new java.math.BigInteger(text(attr,"description_category_id")));cat.addProperty("type_id",new java.math.BigInteger(text(attr,"type_id")));cat.addProperty("language","DEFAULT");var names=new HashMap<String,String>();for(var e:array(object(client.call("v1/description-category/attribute",cat,false)),"result")){var a=object(e);names.put(text(a,"id"),text(a,"name"));}
        var groups=new HashMap<String,Integer>();var all=new JsonArray();array(attr,"attributes").forEach(all::add);array(attr,"complex_attributes").forEach(all::add);
        for(var e:all){var a=object(e);var values=new ArrayList<String>();for(var v:array(a,"values"))values.add(text(object(v),"value"));String id=text(a,"id"),complex=text(a,"complex_id"),name=names.getOrDefault(id,"");int group=complex.isBlank()||complex.equals("0")?0:groups.merge(complex+":"+id,1,Integer::sum)-1;attributes.add(new ProductAttribute(id,name,values,null,complex,group));if(!values.isEmpty()){if(id.equals("4191"))description=values.getFirst();if(name.equals("Бренд")||name.equals("Бренд в одежде и обуви")||id.equals("85"))brand=values.getFirst();if(name.equals("Размер производителя"))size=values.getFirst();if(name.equals("Цвет товара"))color=values.getFirst();}}
        var media=new ArrayList<ProductMedia>();for(var url:OzonListingImages.ordered(info,attr))media.add(new ProductMedia(URI.create(url),null,null,null,null));
        var dimensions=new PackageDimensions(cm(attr,"depth"),cm(attr,"width"),cm(attr,"height"),kg(attr));
        var codes=new ArrayList<>(strings(array(attr,"barcodes")));if(!text(attr,"barcode").isBlank()&&!codes.contains(text(attr,"barcode")))codes.addFirst(text(attr,"barcode"));
        String proof=ListingIdentity.verifiedGtin(new ProductKey(shop.getId(),shop.getMarketplace(),key.cardId(),text(attr,"offer_id")),codes);
        for(var attribute:attributes)if(ProductFieldNames.matches("russianSize",attribute.name())&&!attribute.values().isEmpty())russianSize=attribute.values().getFirst();
        var variants=List.of(new ProductVariant(text(attr,"offer_id"),text(attr,"offer_id"),color,size,proof.isBlank()?(codes.isEmpty()?"":codes.getFirst()):proof,!proof.isBlank(),russianSize));
        var docs=new ArrayList<ProductDocument>();var now=Instant.now();int pages=1;
        for(int page=1;page<=pages;page++){
            b=new JsonObject();b.addProperty("offer_id",text(attr,"offer_id"));b.addProperty("page",page);b.addProperty("page_size",100);
            var result=obj(object(client.call("v1/product/certificate/list",b,false)),"result");
            if(!result.has("certificates"))throw new IOException("Ozon document metadata unavailable");
            pages=Math.max(1,result.has("page_count")?result.get("page_count").getAsInt():1);if(pages>10)throw new IOException("Too many Ozon certificate pages");
            for(var e:array(result,"certificates")){var doc=object(e);var end=date(text(doc,"expire_date"));var state=end!=null&&end.isBefore(LocalDate.now())?DocumentState.EXPIRED:DocumentState.UNVERIFIED;
                docs.add(new ProductDocument(text(doc,"certificate_id"),text(doc,"type_code"),text(doc,"certificate_number"),date(text(doc,"issue_date")),end,"","",null,Marketplace.OZON,state,"https://api-seller.ozon.ru/v1/product/certificate/list",now,metadata(doc)));
            }
        }
        var inherited=new LinkedHashMap<String,String>();
        try(var connection=com.vncode.app.config.Database.getConnection();var query=connection.prepareStatement("SELECT category FROM ozon_products WHERE shop_id=? AND product_id=?")){
            query.setInt(1,shop.getId());query.setString(2,key.cardId());try(var result=query.executeQuery()){if(result.next())inherited.put("source.category.name",Objects.toString(result.getString(1),""));}
        }catch(java.sql.SQLException unavailable){throw new IOException("Ozon category cache unavailable",unavailable);}
        if(includePrices)try{
            var amount=price(info);String priceCurrency=info.has("price")&&info.get("price").isJsonObject()?text(obj(info,"price"),"currency_code"):text(info,"currency_code");
            if(amount==null||amount.signum()<=0){var pricingFilter=new JsonObject();pricingFilter.add("product_id",ids);pricingFilter.addProperty("visibility","ALL");var pricingRequest=new JsonObject();pricingRequest.add("filter",pricingFilter);pricingRequest.addProperty("limit",1);pricingRequest.addProperty("cursor","");
                var pricing=object(client.call("v5/product/info/prices",pricingRequest,false));
                var matches=array(pricing,"items").asList().stream().map(com.vncode.app.integration.listing.ListingJson::object).filter(item->text(item,"product_id").equals(key.cardId())&&(text(item,"offer_id").isBlank()||text(item,"offer_id").equals(text(attr,"offer_id")))).toList();
                if(matches.size()>1)throw new IOException("Ambiguous Ozon source price");
                if(matches.size()==1){amount=price(matches.getFirst());priceCurrency=text(obj(matches.getFirst(),"price"),"currency_code");}
            }
            if(amount!=null&&amount.signum()>0){inherited.put("source.price",amount.toPlainString());inherited.put("source.currency",priceCurrency.isBlank()?"RUB":priceCurrency);}
        }catch(IOException unavailable){inherited.put("source.price.unavailable","true");}
        var raw=new JsonObject();raw.add("info",info);raw.add("attributes",attr);raw.add("documents",ProductJson.GSON.toJsonTree(docs.stream().map(ProductDocument::metadata).toList()));String payload=ListingIdentity.canonical(raw);
        inherited.putAll(Map.of("source.payload",payload,"owner",ListingIdentity.fingerprint(shop),"sku",text(info,"sku"),"complex.ambiguous",Boolean.toString(groups.values().stream().anyMatch(v->v>1))));
        var snapshot=new ProductSnapshot(key,"live:"+ListingIdentity.hash(payload+ListingIdentity.canonical(ProductJson.GSON.toJsonTree(inherited))),text(attr,"name"),description,brand,text(attr,"offer_id"),text(attr,"description_category_id")+":"+text(attr,"type_id"),media,attributes,variants,dimensions,docs,now,inherited);new ProductRepository().saveVerifiedSnapshot(shop,snapshot);return snapshot;
    }
    private static BigDecimal price(JsonObject item){var value=item.get("price");if(value==null||value.isJsonNull())return null;try{return value.isJsonObject()?decimal(value.getAsJsonObject(),"price"):value.getAsBigDecimal();}catch(RuntimeException invalid){return null;}}
    private static JsonObject find(JsonArray a,String id)throws IOException{JsonObject found=null;for(var e:a){var o=object(e);if(text(o,"id").equals(id)){if(found!=null)throw new IOException("Ambiguous Ozon identity");found=o;}}return found;}
    private static BigDecimal cm(JsonObject o,String key)throws IOException{var v=decimal(o,key);if(v==null)return null;return switch(text(o,"dimension_unit")){case "mm"->v.movePointLeft(1);case "cm"->v;case "in"->v.multiply(new BigDecimal("2.54"));default->throw new IOException("Unknown Ozon dimension unit");};}
    private static BigDecimal kg(JsonObject o)throws IOException{var v=decimal(o,"weight");if(v==null)return null;return switch(text(o,"weight_unit")){case "g"->v.movePointLeft(3);case "kg"->v;default->throw new IOException("Unknown Ozon weight unit");};}
}
