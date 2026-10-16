package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static com.vncode.app.integration.listing.ListingJson.*;

/** Official content/v2/cards/update permits adding SKUs, but explicitly forbids replacing them.
 * Each update preserves the fresh complete own card; an HTTP 200 is never applied evidence. */
public final class WbGtinAdapter implements GtinMarketplaceAdapter {
    private static final Capability CAPABILITY=new Capability(true,false,"replacement_not_supported");
    private static final List<String> CARD_FIELDS=List.of("nmID","vendorCode","kizMarked","brand","title","gtin","description");
    private static final List<String> DOCUMENT_FIELDS=List.of("id","type","number","productNumber","tradeName","applicant","startDate","endDate","isEndless");
    private final Shop shop;
    private final ListingTransport client;

    public WbGtinAdapter(Shop shop){this(shop,new ListingHttpClient(shop));}
    public WbGtinAdapter(Shop shop,ListingTransport client){
        MarketplaceGuard.requireWildberries(shop);
        this.shop=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
        this.client=Objects.requireNonNull(client);
    }
    public Capability capability(ProductKey key){requireKey(key);return CAPABILITY;}
    public void validateRegistration(String gtin){RegisteredGtinProof.requireCurrent(shop,gtin);}
    public ProductSnapshot read(ProductKey key)throws IOException{return snapshot(key,ownCard(key));}
    public boolean conflicts(ProductKey key,String gtin)throws IOException {
        requireKey(key);
        String updated=null,nm=null;
        for(int page=0;page<20_000;page++) {
            var response=search(gtin,updated,nm);
            for(var e:strictArray(response,"cards")) {
                var card=object(e);
                for(var v:strictArray(card,"sizes")) {
                    var size=object(v);
                    if(RegisteredGtinProof.contains(strings(strictArray(size,"skus")),gtin)
                            && (!key.productId().equals(text(card,"nmID"))||!key.variantId().equals(text(size,"chrtID"))))return true;
                }
            }
            if(strictArray(response,"cards").size()<100)return false;
            var cursor=obj(response,"cursor");String nextUpdated=text(cursor,"updatedAt"),nextNm=text(cursor,"nmID");
            if(nextUpdated.isBlank()||!numeric(nextNm)||Objects.equals(updated,nextUpdated)&&Objects.equals(nm,nextNm))throw new IOException("invalid_wb_cursor");
            updated=nextUpdated;nm=nextNm;
        }
        throw new IOException("wb_page_limit");
    }
    public Submission submit(Preview preview)throws IOException {
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
        try {
        ProductKey key=preview.before().key();requireKey(key);
        if(preview.operation()!=Operation.ADD||!preview.oldGtin().isBlank()||!CAPABILITY.equals(preview.capability()))throw new IllegalArgumentException("unsupported_operation");
        requireWrite(preview);
        var card=ownCard(key);var fresh=snapshot(key,card);
        if(!fresh.fingerprint().equals(preview.before().fingerprint()))throw new IllegalArgumentException("product_changed");
        if(RegisteredGtinProof.contains(fresh.barcodes(),preview.newGtin()))throw new IllegalArgumentException("already_present");
        if(conflicts(key,preview.newGtin()))throw new IllegalArgumentException("gtin_on_another_product");
        var update=payload(card);
        boolean found=false;
        for(var e:strictArray(update,"sizes")) {
            var size=object(e);
            if(key.variantId().equals(text(size,"chrtID"))){size.getAsJsonArray("skus").add(preview.newGtin());found=true;}
        }
        if(!found)throw new IOException("variant_not_found");
        var request=new JsonArray();request.add(update);
        if(request.toString().getBytes(StandardCharsets.UTF_8).length>10*1024*1024)throw new IOException("wb_update_too_large");
        var response=object(client.call("content/v2/cards/update",request,true,()->{requireWrite(preview);dispatched.set(true);}));
        if(!response.has("error")||!response.get("error").isJsonPrimitive()||!response.get("error").getAsJsonPrimitive().isBoolean())throw new IOException("invalid_wb_update_response");
        if(response.get("error").getAsBoolean())return new Submission("wb-rejected");
        return new Submission("");
        }catch(IOException|RuntimeException failure){
            if(!dispatched.get())throw new GtinKnownUnsentException(com.vncode.app.features.sync.SyncFailure.of(failure,0).code());
            throw failure;
        }
    }
    public Verification verify(Preview preview,Submission submission)throws IOException {
        requireKey(preview.before().key());
        var actual=read(preview.before().key());
        if(RegisteredGtinProof.contains(actual.barcodes(),preview.newGtin()))
            return preview.before().barcodes().stream().allMatch(code->RegisteredGtinProof.contains(actual.barcodes(),code))?Verification.APPLIED:Verification.UNKNOWN;
        if("wb-rejected".equals(submission.remoteTaskId()))return Verification.REJECTED;
        return Verification.PENDING;
    }
    private void requireWrite(Preview preview){
        requireKey(preview.before().key());
        LicenseRuntime.require(Permission.GTIN);LicenseRuntime.require(Permission.SELLER_API);
        RegisteredGtinProof.requireCurrent(shop,preview.newGtin());
    }
    private JsonObject ownCard(ProductKey key)throws IOException {
        requireKey(key);
        var response=search(key.productId(),null,null);JsonObject selected=null;
        for(var e:strictArray(response,"cards")) {
            var card=object(e);
            if(key.productId().equals(text(card,"nmID"))){if(selected!=null)throw new IOException("ambiguous_wb_product");selected=card;}
        }
        if(selected==null)throw new IOException("product_not_found");
        payload(selected);
        if(strictArray(selected,"sizes").asList().stream().noneMatch(e->key.variantId().equals(text(object(e),"chrtID"))))throw new IOException("variant_not_found");
        return selected;
    }
    private JsonObject search(String textSearch,String updated,String nm)throws IOException {
        var filter=new JsonObject();filter.addProperty("textSearch",textSearch);filter.addProperty("withPhoto",-1);
        var cursor=new JsonObject();cursor.addProperty("limit",100);
        if(updated!=null){cursor.addProperty("updatedAt",updated);cursor.addProperty("nmID",new java.math.BigInteger(nm));}
        var settings=new JsonObject();settings.add("filter",filter);settings.add("cursor",cursor);
        var body=new JsonObject();body.add("settings",settings);
        var response=object(client.call("content/v2/get/cards/list",body,false));
        if("true".equals(text(response,"error")))throw new IOException("wb_catalog_rejected");
        strictArray(response,"cards");return response;
    }
    private ProductSnapshot snapshot(ProductKey key,JsonObject card)throws IOException {
        var dto=new Gson().fromJson(card,WbProductCard.class);
        var result=WbGtinProductReader.fromCard(key,dto);
        String fingerprint=ListingIdentity.hash(key+"\n"+ListingIdentity.fingerprint(shop)+"\n"+ListingIdentity.canonical(payload(card)));
        return new ProductSnapshot(key,result.article(),result.color(),result.size(),result.barcodes(),fingerprint,Instant.now());
    }
    private static JsonObject payload(JsonObject card)throws IOException {
        if(!numeric(text(card,"nmID"))||text(card,"vendorCode").isBlank()||!card.has("dimensions")||!card.get("dimensions").isJsonObject())throw new IOException("incomplete_wb_card");
        var request=new JsonObject();copy(card,request,CARD_FIELDS);
        var dimensions=new JsonObject();copy(obj(card,"dimensions"),dimensions,List.of("length","width","height","weightBrutto"));request.add("dimensions",dimensions);
        var orderedCharacteristics=new TreeMap<java.math.BigInteger,JsonObject>();
        for(var e:strictArray(card,"characteristics")){var a=object(e);if(!numeric(text(a,"id"))||!a.has("value"))throw new IOException("incomplete_wb_characteristic");var out=new JsonObject();copy(a,out,List.of("id","value"));if(orderedCharacteristics.put(new java.math.BigInteger(text(a,"id")),out)!=null)throw new IOException("ambiguous_wb_characteristic");}
        var characteristics=new JsonArray();orderedCharacteristics.values().forEach(characteristics::add);request.add("characteristics",characteristics);
        var sizes=new JsonArray();var ids=new HashSet<String>();for(var e:strictArray(card,"sizes")){
            var size=object(e);if(!numeric(text(size,"chrtID"))||!ids.add(text(size,"chrtID")))throw new IOException("incomplete_wb_size");
            var codes=strictArray(size,"skus");if(codes.asList().stream().anyMatch(v->!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()))throw new IOException("invalid_wb_barcode");
            var out=new JsonObject();copy(size,out,List.of("chrtID","techSize","wbSize","skus"));sizes.add(out);
        }if(sizes.isEmpty())throw new IOException("incomplete_wb_sizes");request.add("sizes",sizes);
        if(card.has("documents")&&!card.get("documents").isJsonNull()){
            if(!card.get("documents").isJsonObject())throw new IOException("incomplete_wb_documents");
            var source=obj(card,"documents");var docs=new JsonObject();var items=new JsonArray();
            for(var e:strictArray(source,"items")){var doc=object(e);if(text(doc,"type").isBlank()||text(doc,"number").isBlank())throw new IOException("incomplete_wb_document");var out=new JsonObject();copy(doc,out,DOCUMENT_FIELDS);items.add(out);}
            docs.add("items",items);if(source.has("excludeDocuments"))docs.add("excludeDocuments",source.get("excludeDocuments").deepCopy());request.add("documents",docs);
        }
        return request;
    }
    private static void copy(JsonObject source,JsonObject target,List<String> fields){for(String field:fields)if(source.has(field))target.add(field,source.get(field).deepCopy());}
    private static JsonArray strictArray(JsonObject object,String key)throws IOException{if(!object.has(key)||!object.get(key).isJsonArray())throw new IOException("incomplete_wb_catalog");return object.getAsJsonArray(key);}
    private static boolean numeric(String value){return value.matches("[1-9][0-9]{0,19}");}
    private void requireKey(ProductKey key){ListingIdentity.require(shop);if(key.shopId()!=shop.getId()||key.marketplace()!=Marketplace.WILDBERRIES||!numeric(key.productId())||!numeric(key.variantId()))throw new IllegalArgumentException("shop_mismatch");}
}
