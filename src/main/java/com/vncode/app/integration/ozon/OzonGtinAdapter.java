package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static com.vncode.app.integration.listing.ListingJson.*;

/** Uses the dedicated official barcode-add endpoint, leaving card data and old barcodes intact.
 * Replacement would require a separate reviewed, persisted delete phase and is not enabled. */
public final class OzonGtinAdapter implements GtinMarketplaceAdapter {
    @FunctionalInterface public interface BeforeRequest { void await(int shopId)throws InterruptedException; }
    private static final Capability CAPABILITY=new Capability(true,false,"replacement_not_supported");
    private static final OzonApiRateLimiter BARCODE_LIMITER=new OzonApiRateLimiter(Duration.ofSeconds(3));
    private final Shop shop;private final ListingTransport client;private final BeforeRequest beforeRequest;
    public OzonGtinAdapter(Shop shop){this(shop,new ListingHttpClient(shop));}
    public OzonGtinAdapter(Shop shop,ListingTransport client){this(shop,client,id->BARCODE_LIMITER.awaitTurn(com.vncode.app.integration.listing.ListingIdentity.hash(shop.getClientId()+"\n"+shop.getApiKey()),"gtin-barcodes"));}
    public OzonGtinAdapter(Shop shop,ListingTransport client,BeforeRequest beforeRequest){
        MarketplaceGuard.requireOzon(shop);this.shop=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
        this.client=Objects.requireNonNull(client);this.beforeRequest=Objects.requireNonNull(beforeRequest);
    }
    public Capability capability(ProductKey key){requireKey(key);return CAPABILITY;}
    public void validateRegistration(String gtin){RegisteredGtinProof.requireCurrent(shop,gtin);}
    public ProductSnapshot read(ProductKey key)throws IOException{var actual=snapshot(key,ownProduct(key),null);MarketplaceBarcodeCache.observe(shop,key,actual.barcodes());return actual;}
    /** Exact owned readback for activating a signed Catalog candidate; no card import or write. */
    public boolean hasBarcode(ProductKey key,String expectedSku,String gtin)throws IOException {
        var product=ownProduct(key);
        return expectedSku.equals(text(product,"sku"))&&RegisteredGtinProof.contains(strings(strictArray(product,"barcodes")),gtin);
    }
    public boolean conflicts(ProductKey key,String gtin)throws IOException {
        requireKey(key);String cursor="";
        for(int page=0;page<20_000;page++) {
            var filter=new JsonObject();filter.addProperty("visibility","ALL");
            var request=new JsonObject();request.add("filter",filter);request.addProperty("last_id",cursor);request.addProperty("limit",500);
            var response=object(client.call("v3/product/list",request,false));
            var result=obj(response,"result");var products=strictArray(result,"items");
            if(products.isEmpty())return false;
            var ids=new JsonArray();var requested=new HashSet<String>();
            for(var e:products){String id=text(object(e),"product_id");if(!numeric(id)||!requested.add(id))throw new IOException("invalid_ozon_catalog");ids.add(id);}
            var body=new JsonObject();body.add("product_id",ids);
            var detailed=strictArray(object(client.call("v3/product/info/list",body,false)),"items");
            var seen=new HashSet<String>();
            for(var e:detailed){var product=object(e);String id=text(product,"id");if(!requested.contains(id)||!seen.add(id))throw new IOException("invalid_ozon_catalog");
                if(!key.productId().equals(id)&&RegisteredGtinProof.contains(strings(strictArray(product,"barcodes")),gtin))return true;
            }
            if(!seen.equals(requested))throw new IOException("incomplete_ozon_catalog");
            if(products.size()<500)return false;
            String next=text(result,"last_id");if(next.isBlank()||next.equals(cursor))throw new IOException("invalid_ozon_cursor");cursor=next;
        }
        throw new IOException("ozon_page_limit");
    }
    public Submission submit(Preview preview)throws Exception {
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
        try {
        var key=preview.before().key();requireKey(key);
        if(preview.operation()!=Operation.ADD||!preview.oldGtin().isBlank()||!CAPABILITY.equals(preview.capability()))throw new IllegalArgumentException("unsupported_operation");
        requireWrite(preview);
        var product=ownProduct(key);var fresh=snapshot(key,product,null);
        if(!fresh.fingerprint().equals(preview.before().fingerprint()))throw new IllegalArgumentException("product_changed");
        if(RegisteredGtinProof.contains(fresh.barcodes(),preview.newGtin()))throw new IllegalArgumentException("already_present");
        if(fresh.barcodes().size()>=100)throw new IllegalArgumentException("ozon_barcode_limit");
        if(conflicts(key,preview.newGtin()))throw new IllegalArgumentException("gtin_on_another_product");
        var item=new JsonObject();item.addProperty("barcode",preview.newGtin());item.addProperty("sku",new BigInteger(text(product,"sku")));
        var codes=new JsonArray();codes.add(item);var body=new JsonObject();body.add("barcodes",codes);
        beforeRequest.await(shop.getId());
        var response=object(client.call("v1/barcode/add",body,true,()->{requireWrite(preview);dispatched.set(true);}));
        if(!response.has("errors")||!response.get("errors").isJsonArray())throw new IOException("invalid_ozon_barcode_response");
        var errors=response.getAsJsonArray("errors");
        if(!errors.isEmpty()) {
            if(errors.size()!=1)throw new IOException("ambiguous_ozon_barcode_response");
            var error=object(errors.get(0));
            if(!text(error,"sku").equals(text(product,"sku"))||!RegisteredGtinProof.sameCode(text(error,"barcode"),preview.newGtin()))throw new IOException("foreign_ozon_barcode_response");
            return new Submission("ozon-rejected");
        }
        return new Submission("");
        }catch(Exception failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            if(!dispatched.get())throw new GtinKnownUnsentException(com.vncode.app.features.sync.SyncFailure.of(failure,0).code());
            throw failure;
        }
    }
    public Verification verify(Preview preview,Submission submission)throws IOException {
        var key=preview.before().key();requireKey(key);var product=ownProduct(key);var actual=snapshot(key,product,null);MarketplaceBarcodeCache.observe(shop,key,actual.barcodes());
        if(RegisteredGtinProof.contains(actual.barcodes(),preview.newGtin())) {
            var restored=actual.barcodes().stream().filter(code->!RegisteredGtinProof.sameCode(code,preview.newGtin())).toList();
            return snapshot(key,product,restored).fingerprint().equals(preview.before().fingerprint())?Verification.APPLIED:Verification.UNKNOWN;
        }
        if("ozon-rejected".equals(submission.remoteTaskId()))return Verification.REJECTED;
        return Verification.PENDING;
    }
    private JsonObject ownProduct(ProductKey key)throws IOException {
        requireKey(key);var ids=new JsonArray();ids.add(key.productId());var body=new JsonObject();body.add("product_id",ids);
        var response=object(client.call("v3/product/info/list",body,false));JsonObject found=null;
        for(var e:strictArray(response,"items")){var product=object(e);if(!key.productId().equals(text(product,"id")))throw new IOException("foreign_ozon_product");if(found!=null)throw new IOException("ambiguous_ozon_product");found=product;}
        if(found==null||!key.variantId().equals(text(found,"offer_id"))||!numeric(text(found,"sku")))throw new IOException("product_not_found");
        strictArray(found,"barcodes");
        if("true".equals(text(found,"is_archived"))||"true".equals(text(found,"is_autoarchived")))throw new IOException("ozon_product_archived");
        return found;
    }
    private ProductSnapshot snapshot(ProductKey key,JsonObject product,List<String> override)throws IOException {
        var codes=override==null?strings(strictArray(product,"barcodes")):override;
        String article=text(product,"offer_id");
        String fingerprint=ListingIdentity.hash(ListingIdentity.canonical(new Gson().toJsonTree(List.of(key,ListingIdentity.fingerprint(shop),text(product,"sku"),article,text(product,"name"),codes.stream().distinct().sorted().toList()))));
        return new ProductSnapshot(key,article,"","",codes,fingerprint,Instant.now());
    }
    private void requireWrite(Preview preview){requireKey(preview.before().key());LicenseRuntime.require(Permission.GTIN);LicenseRuntime.require(Permission.SELLER_API);RegisteredGtinProof.requireCurrent(shop,preview.newGtin());}
    private void requireKey(ProductKey key){ListingIdentity.require(shop);if(key.shopId()!=shop.getId()||key.marketplace()!=Marketplace.OZON||!numeric(key.productId()))throw new IllegalArgumentException("shop_mismatch");}
    private static boolean numeric(String value){return value.matches("[1-9][0-9]{0,18}");}
    private static JsonArray strictArray(JsonObject object,String key)throws IOException{if(!object.has(key)||!object.get(key).isJsonArray())throw new IOException("incomplete_ozon_catalog");var array=object.getAsJsonArray(key);if(key.equals("barcodes")&&array.asList().stream().anyMatch(v->!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()))throw new IOException("invalid_ozon_barcode");return array;}
}
