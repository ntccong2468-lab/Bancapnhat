package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.vncode.app.integration.listing.ListingJson.*;

public final class WbProductSnapshotReader implements ProductSnapshotReader {
    private final Function<Shop,ListingTransport> clients,prices;
    public WbProductSnapshotReader(){this(ListingHttpClient::new,s->new ListingHttpClient(s,new okhttp3.OkHttpClient(),okhttp3.HttpUrl.parse("https://discounts-prices-api.wildberries.ru/")));}
    public WbProductSnapshotReader(Function<Shop,ListingTransport> clients){this(clients,clients);}
    public WbProductSnapshotReader(Function<Shop,ListingTransport> clients,Function<Shop,ListingTransport> prices){this.clients=clients;this.prices=prices;}
    public ProductSnapshot read(Shop shop,ProductKey key)throws IOException{return read(shop,key,true);}
    public ProductSnapshot readForSynchronization(Shop shop,ProductKey key)throws IOException{return read(shop,key,false);}
    private ProductSnapshot read(Shop shop,ProductKey key,boolean includePrices)throws IOException{
        ListingIdentity.require(shop,key);if(key.marketplace()!=Marketplace.WILDBERRIES)throw new SecurityException("Not a WB card");
        JsonObject filter=new JsonObject();filter.addProperty("textSearch",key.cardId());filter.addProperty("withPhoto",-1);
        JsonObject cursor=new JsonObject();cursor.addProperty("limit",100);JsonObject settings=new JsonObject();settings.add("filter",filter);settings.add("cursor",cursor);
        JsonObject body=new JsonObject();body.add("settings",settings);
        JsonObject response=object(clients.apply(shop).call("content/v2/get/cards/list",body,false));
        JsonObject card=null;for(var e:array(response,"cards")){var c=object(e);if(text(c,"nmID").equals(key.cardId())){if(card!=null)throw new IOException("Ambiguous WB identity");card=c;}}
        if(card==null||!card.has("sizes")||!card.has("characteristics")||!card.has("dimensions")||!card.has("subjectID"))throw new IOException("Complete WB card unavailable");
        // WB can reorder characteristics on every read. Their identity is charcID,
        // whereas photo and value ordering can carry meaning and must be retained.
        var ordered=new java.util.TreeMap<java.math.BigInteger,JsonElement>();
        for(var entry:array(card,"characteristics")){
            String id=text(object(entry),"id");
            if(!id.matches("[1-9][0-9]{0,18}")||ordered.put(new java.math.BigInteger(id),entry)!=null)throw new IOException("Ambiguous WB characteristic identity");
        }
        var stableCharacteristics=new JsonArray();ordered.values().forEach(stableCharacteristics::add);card.add("characteristics",stableCharacteristics);
        var media=new ArrayList<ProductMedia>();for(var e:array(card,"photos")){String url=text(object(e),"big");if(!url.isBlank())media.add(new ProductMedia(URI.create(url),null,null,null,null));}
        var attributes=new ArrayList<ProductAttribute>();for(var e:array(card,"characteristics")){var a=object(e);var v=a.get("value");attributes.add(new ProductAttribute(text(a,"id"),text(a,"name"),v!=null&&v.isJsonArray()?strings(v.getAsJsonArray()):v==null?List.of():List.of(v.getAsString()),null));}
        var variants=new ArrayList<ProductVariant>();for(var e:array(card,"sizes")){
            var v=object(e);String id=text(v,"chrtID");var skus=strings(array(v,"skus"));String proof=ListingIdentity.verifiedGtin(new ProductKey(shop.getId(),shop.getMarketplace(),key.cardId(),id),skus);
            variants.add(new ProductVariant(id,text(card,"vendorCode"),"",text(v,"techSize"),proof.isBlank()?(skus.isEmpty()?"":skus.getFirst()):proof,!proof.isBlank(),text(v,"wbSize")));
        }
        if(!key.variantId().isBlank()&&variants.stream().noneMatch(v->v.id().equals(key.variantId())))throw new IOException("WB variant identity changed");
        var d=obj(card,"dimensions");var dimensions=new PackageDimensions(decimal(d,"length"),decimal(d,"width"),decimal(d,"height"),decimal(d,"weightBrutto"));
        var now=Instant.now();var docs=new ArrayList<ProductDocument>();for(var e:array(obj(card,"documents"),"items")){
            var doc=object(e);var verdict=obj(doc,"verdict");var end=date(text(doc,"endDate"));DocumentState state=DocumentState.UNVERIFIED;
            if(end!=null&&end.isBefore(LocalDate.now()))state=DocumentState.EXPIRED;
            else if("true".equals(text(verdict,"verified")))state="1".equals(text(verdict,"status"))?DocumentState.VALID:DocumentState.REJECTED;
            else if(doc.has("verdict"))state=DocumentState.PENDING;
            docs.add(new ProductDocument(text(doc,"id"),text(doc,"type"),text(doc,"number"),date(text(doc,"startDate")),end,text(doc,"applicant"),text(doc,"productNumber"),null,Marketplace.WILDBERRIES,state,"https://content-api.wildberries.ru/content/v2/get/cards/list",now,metadata(doc)));
        }
        var inherited=new LinkedHashMap<String,String>();inherited.put("source.category.name",text(card,"subjectName"));
        if(includePrices)try{
            var pricing=object(prices.apply(shop).call("api/v2/list/goods/filter?limit=100&filterNmID="+key.cardId(),null,false));
            var matching=array(obj(pricing,"data"),"listGoods").asList().stream().map(com.vncode.app.integration.listing.ListingJson::object).filter(g->text(g,"nmID").equals(key.cardId())).toList();
            if(matching.size()==1){var goods=matching.getFirst();String currency=text(goods,"currencyIsoCode4217");if(!currency.isBlank())inherited.put("source.currency",currency);
                var amounts=new HashSet<java.math.BigDecimal>();for(var e:array(goods,"sizes")){var size=object(e);var amount=decimal(size,"price");if(amount!=null&&amount.signum()>0){inherited.put("source.price."+text(size,"sizeID"),amount.toPlainString());amounts.add(amount.stripTrailingZeros());}}
                if(amounts.size()==1)inherited.put("source.price",amounts.iterator().next().toPlainString());
            }
        }catch(IOException unavailable){inherited.put("source.price.unavailable","true");}
        String raw=ListingIdentity.canonical(card);inherited.put("source.payload",raw);inherited.put("owner",ListingIdentity.fingerprint(shop));var snapshot=new ProductSnapshot(key,"live:"+ListingIdentity.hash(raw+ListingIdentity.canonical(ProductJson.GSON.toJsonTree(inherited))),text(card,"title"),text(card,"description"),text(card,"brand"),text(card,"vendorCode"),text(card,"subjectID"),media,attributes,variants,dimensions,docs,now,inherited);new ProductRepository().saveVerifiedSnapshot(shop,snapshot);return snapshot;
    }
}
