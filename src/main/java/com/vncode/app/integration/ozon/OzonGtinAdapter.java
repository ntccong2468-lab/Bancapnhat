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
public final class OzonGtinAdapter implements GtinBatchAdapter {
    public static final int MAX_ADD_PAIRS=100;
    @FunctionalInterface public interface BeforeRequest { void await(int shopId)throws InterruptedException; }
    private static final Capability CAPABILITY=new Capability(true,false,"replacement_not_supported");
    private static final OzonApiRateLimiter BARCODE_LIMITER=new OzonApiRateLimiter(Duration.ofSeconds(3));
    private final Shop shop;private final ListingTransport client;private final BeforeRequest beforeRequest;
    public OzonGtinAdapter(Shop shop){this(shop,new ListingHttpClient(shop));}
    public OzonGtinAdapter(Shop shop,ListingTransport client){this(shop,client,id->BARCODE_LIMITER.awaitTurn(com.vncode.app.integration.listing.ListingIdentity.hash(shop.getClientId()),"gtin-barcodes"));}
    public OzonGtinAdapter(Shop shop,ListingTransport client,BeforeRequest beforeRequest){
        MarketplaceGuard.requireOzon(shop);this.shop=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
        this.client=Objects.requireNonNull(client);this.beforeRequest=Objects.requireNonNull(beforeRequest);
    }
    public Capability capability(ProductKey key){requireKey(key);return CAPABILITY;}
    public void validateRegistration(String gtin){RegisteredGtinProof.requireCurrent(shop,gtin);}
    @Override public void validateIdentity(ProductSnapshot target,RegisteredGtin source)throws IOException{
        requireKey(target.key());
        var key=new com.vncode.app.features.products.ProductKey(shop.getId(),Marketplace.OZON,target.key().productId(),target.key().variantId());
        var product=new OzonProductSnapshotReader(ignored->client).readForGtinSync(shop,key);
        var variants=product.variants().stream().filter(value->key.variantId().equals(value.id())).toList();if(variants.size()!=1)throw new IllegalArgumentException("GTIN_VARIANT_IDENTITY_UNVERIFIED");var variant=variants.getFirst();
        for(String field:List.of("color","russianSize")){var values=com.vncode.app.features.products.ProductFieldNames.sourceAttributes(field,product.attributes()).stream().flatMap(attribute->attribute.values().stream()).filter(value->!value.isBlank()).distinct().toList();if(values.size()>1)throw new IllegalArgumentException("GTIN_VARIANT_IDENTITY_UNVERIFIED");}
        var manufacturerSizes=product.attributes().stream().filter(attribute->attribute.complexId().isBlank()||attribute.complexId().equals("0")).filter(attribute->Set.of("размер производителя","manufacturer size").contains(attribute.name().strip().toLowerCase(Locale.ROOT))).flatMap(attribute->attribute.values().stream()).filter(value->!value.isBlank()).distinct().toList();if(manufacturerSizes.size()>1)throw new IllegalArgumentException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
        String primarySize=variant.size().isBlank()?variant.russianSize():variant.size();
        var row=new com.vncode.app.features.products.ProductRow(key,product.title(),product.vendorArticle(),"",product.metadata().getOrDefault("source.category.name",""),product.brand(),variant.color(),primarySize,target.barcodes(),"","","","",com.vncode.app.features.products.DocumentState.UNVERIFIED,"",true,variant.russianSize(),variant.size());
        if(new GtinCatalogIndex(List.of(source)).ozon(row).size()!=1)throw new IllegalArgumentException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
        GtinTnvedIdentity.requireCompatible(product,source);
    }
    public ProductSnapshot read(ProductKey key)throws IOException{var tokens=beginRead(key);var started=Instant.now();var actual=snapshot(key,ownProduct(key),null);if(!observe(actual,started,tokens))throw new IOException("OZON_BARCODE_READ_SUPERSEDED");return actual;}
    private Map<String,Long> beginRead(ProductKey key){requireKey(key);return client instanceof GtinBatchTransport?Map.of():MarketplaceBarcodeCache.issueOzon(shop.getId(),ListingIdentity.fingerprint(shop),List.of(key.productId()));}
    private boolean observe(ProductSnapshot actual,Instant started,Map<String,Long> tokens){return client instanceof GtinBatchTransport||MarketplaceBarcodeCache.observe(shop,actual.key(),actual.barcodes(),started,tokens);}
    /** Exact owned readback for activating a signed Catalog candidate; no card import or write. */
    public boolean hasBarcode(ProductKey key,String expectedSku,String gtin)throws IOException {
        var tokens=beginRead(key);var started=Instant.now();var product=ownProduct(key);
        var actual=snapshot(key,product,null);if(!observe(actual,started,tokens))throw new IOException("OZON_BARCODE_READ_SUPERSEDED");
        if(!expectedSku.equals(text(product,"sku"))||!RegisteredGtinProof.contains(strings(strictArray(product,"barcodes")),gtin))return false;
        try{validateIdentity(actual,new GtinSyncRepository().catalogItem(shop.getId(),Marketplace.OZON,gtin));return true;}catch(IllegalArgumentException unknown){return false;}
    }
    public boolean conflicts(ProductKey key,String gtin)throws IOException {
        requireKey(key);if(client instanceof GtinBatchTransport batch)return batch.conflicts(key,gtin);
        return conflicts(key,gtin,"ALL")||conflicts(key,gtin,"ARCHIVED");
    }
    private boolean conflicts(ProductKey key,String gtin,String visibility)throws IOException{
        String cursor="";var allSeen=new HashSet<String>();var cursors=new HashSet<String>();long expectedTotal=-1;
        for(int page=0;page<20_000;page++) {
            var filter=new JsonObject();filter.addProperty("visibility",visibility);
            var request=new JsonObject();request.add("filter",filter);request.addProperty("last_id",cursor);request.addProperty("limit",500);
            var response=object(client.call("v3/product/list",request,false));
            var result=obj(response,"result");var products=strictArray(result,"items");
            long total=OzonGtinProductReader.declaredTotal(result);if(expectedTotal<0)expectedTotal=total;else if(total!=expectedTotal)throw new IOException("incomplete_ozon_catalog");if(products.isEmpty()){if(total!=allSeen.size())throw new IOException("incomplete_ozon_catalog");return false;}
            var ids=new JsonArray();var requested=new HashSet<String>();
            for(var e:products){String id=text(object(e),"product_id");if(!numeric(id)||!requested.add(id)||!allSeen.add(id))throw new IOException("invalid_ozon_catalog");ids.add(id);}
            if(allSeen.size()>total)throw new IOException("incomplete_ozon_catalog");
            var body=new JsonObject();body.add("product_id",ids);
            var detailed=strictArray(object(client.call("v3/product/info/list",body,false)),"items");
            var seen=new HashSet<String>();
            for(var e:detailed){var product=object(e);String id=text(product,"id");if(!requested.contains(id)||!seen.add(id))throw new IOException("invalid_ozon_catalog");
                if(!key.productId().equals(id)&&RegisteredGtinProof.contains(strings(strictArray(product,"barcodes")),gtin))return true;
            }
            if(!seen.equals(requested))throw new IOException("incomplete_ozon_catalog");
            if(products.size()<500&&total<=allSeen.size())return false;
            String next=text(result,"last_id");if(next.isBlank()||next.equals(cursor)||!cursors.add(next))throw new IOException("invalid_ozon_cursor");cursor=next;
        }
        throw new IOException("ozon_page_limit");
    }
    @Override public void prepareBatch(Collection<ProductKey> keys)throws IOException{for(var key:keys)requireKey(key);if(client instanceof GtinBatchTransport batch)batch.prepare(keys);}
    @Override public void refreshBatch(Collection<ProductKey> keys)throws IOException{for(var key:keys)requireKey(key);if(client instanceof GtinBatchTransport batch)batch.refresh(keys);}
    @Override public void validateRegistrations(Collection<String> gtins){RegisteredGtinProof.requireCurrent(shop,gtins);}
    @Override public List<List<Preview>> partition(List<Preview> previews){var batches=new ArrayList<List<Preview>>();for(int start=0;start<previews.size();start+=MAX_ADD_PAIRS)batches.add(List.copyOf(previews.subList(start,Math.min(start+MAX_ADD_PAIRS,previews.size()))));return List.copyOf(batches);}
    @Override public Map<ProductKey,Submission> submitBatch(List<Preview> previews,Runnable boundaryGuard)throws Exception {
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            if(previews.isEmpty()||previews.size()>MAX_ADD_PAIRS)throw new IllegalArgumentException("OZON_BARCODE_BATCH_LIMIT");requireWrites(previews);
            if(client instanceof GtinBatchTransport batch)batch.prepare(previews.stream().map(preview->preview.before().key()).toList());
            var codes=new JsonArray();var identities=new LinkedHashMap<String,ProductKey>();var keys=new HashSet<ProductKey>();
            for(var p:previews){var key=p.before().key();requireKey(key);if(!keys.add(key)||p.operation()!=Operation.ADD||!p.oldGtin().isBlank()||!CAPABILITY.equals(p.capability()))throw new IllegalArgumentException("unsupported_operation");
                var product=ownProduct(key);var fresh=snapshot(key,product,null);if(!snapshotMatches(p.before(),fresh))throw new IllegalArgumentException("product_changed");if(RegisteredGtinProof.contains(fresh.barcodes(),p.newGtin()))throw new IllegalArgumentException("already_present");if(fresh.barcodes().size()>=100)throw new IllegalArgumentException("ozon_barcode_limit");if(conflicts(key,p.newGtin()))throw new IllegalArgumentException("gtin_on_another_product");
                String identity=text(product,"sku")+":"+GtinBatchTransport.normalize(p.newGtin());if(identities.put(identity,key)!=null)throw new IllegalArgumentException("ambiguous_ozon_sku");
                var value=new JsonObject();value.addProperty("barcode",p.newGtin());value.addProperty("sku",new BigInteger(text(product,"sku")));codes.add(value);
            }
            var body=new JsonObject();body.add("barcodes",codes);beforeRequest.await(shop.getId());
            var response=object(client.call("v1/barcode/add",body,true,()->validateCurrentProducts(previews),()->{requireWrites(previews);if(client instanceof GtinBatchTransport batch)batch.reserve(previews);boundaryGuard.run();dispatched.set(true);}));
            if(!response.has("errors")||!response.get("errors").isJsonArray())throw new IOException("invalid_ozon_barcode_response");
            var rejected=new HashMap<ProductKey,String>();
            for(var e:response.getAsJsonArray("errors")){var error=object(e);var key=identities.get(text(error,"sku")+":"+GtinBatchTransport.normalize(text(error,"barcode")));if(key==null||rejected.containsKey(key))throw new IOException("foreign_ozon_barcode_response");String code=text(error,"code").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]","_");if(code.length()>60)code=code.substring(0,60);rejected.put(key,code);}
            var result=new LinkedHashMap<ProductKey,Submission>();for(var p:previews){var key=p.before().key();result.put(key,new Submission(rejected.containsKey(key)?"ozon-rejected:"+rejected.get(key):""));}return Map.copyOf(result);
        }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();if(failure instanceof ListingRateLimitException rate){BARCODE_LIMITER.registerRateLimit(ListingIdentity.hash(shop.getClientId()),"gtin-barcodes",rate.retryAfter());throw new GtinRateLimitedException("OZON_RATE_LIMIT",rate.retryAfter(),rate);}if(!dispatched.get())throw knownUnsent(failure);throw failure;}
    }
    @Override public Map<ProductKey,VerificationResult> verifyBatch(List<JobItem> items)throws IOException {
        if(client instanceof GtinBatchTransport batch)batch.refresh(items.stream().map(i->i.preview().before().key()).toList());
        var result=new LinkedHashMap<ProductKey,VerificationResult>();for(var item:items)try{var status=verify(item.preview(),new Submission(item.remoteTaskId()));String code=status==Verification.REJECTED&&item.remoteTaskId().startsWith("ozon-rejected:")?"OZON_BARCODE_"+item.remoteTaskId().substring("ozon-rejected:".length()):"";result.put(item.preview().before().key(),new VerificationResult(status,code));}catch(IOException unavailable){result.put(item.preview().before().key(),new VerificationResult(Verification.UNKNOWN,com.vncode.app.features.sync.SyncFailure.of(unavailable,0).code()));}return Map.copyOf(result);
    }
    private void requireWrites(List<Preview> previews){LicenseRuntime.require(Permission.GTIN);LicenseRuntime.require(Permission.SELLER_API);for(var p:previews)requireKey(p.before().key());RegisteredGtinProof.requireCurrent(shop,previews.stream().map(Preview::newGtin).toList());}
    public Submission submit(Preview preview)throws Exception {
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
        try {
        var key=preview.before().key();requireKey(key);
        if(client instanceof GtinBatchTransport batch)batch.prepare(List.of(key));
        if(preview.operation()!=Operation.ADD||!preview.oldGtin().isBlank()||!CAPABILITY.equals(preview.capability()))throw new IllegalArgumentException("unsupported_operation");
        requireWrite(preview);
        var product=ownProduct(key);var fresh=snapshot(key,product,null);
        if(!snapshotMatches(preview.before(),fresh))throw new IllegalArgumentException("product_changed");
        if(RegisteredGtinProof.contains(fresh.barcodes(),preview.newGtin()))throw new IllegalArgumentException("already_present");
        if(fresh.barcodes().size()>=100)throw new IllegalArgumentException("ozon_barcode_limit");
        if(conflicts(key,preview.newGtin()))throw new IllegalArgumentException("gtin_on_another_product");
        var item=new JsonObject();item.addProperty("barcode",preview.newGtin());item.addProperty("sku",new BigInteger(text(product,"sku")));
        var codes=new JsonArray();codes.add(item);var body=new JsonObject();body.add("barcodes",codes);
        beforeRequest.await(shop.getId());
        var response=object(client.call("v1/barcode/add",body,true,()->validateCurrentProducts(List.of(preview)),()->{requireWrite(preview);dispatched.set(true);}));
        if(!response.has("errors")||!response.get("errors").isJsonArray())throw new IOException("invalid_ozon_barcode_response");
        var errors=response.getAsJsonArray("errors");
        if(!errors.isEmpty()) {
            if(errors.size()!=1)throw new IOException("ambiguous_ozon_barcode_response");
            var error=object(errors.get(0));
            if(!text(error,"sku").equals(text(product,"sku"))||!RegisteredGtinProof.sameCode(text(error,"barcode"),preview.newGtin()))throw new IOException("foreign_ozon_barcode_response");
            return new Submission("ozon-rejected:"+rejectionCode(error));
        }
        return new Submission("");
        }catch(Exception failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            if(failure instanceof ListingRateLimitException rate){BARCODE_LIMITER.registerRateLimit(ListingIdentity.hash(shop.getClientId()),"gtin-barcodes",rate.retryAfter());throw new GtinRateLimitedException("OZON_RATE_LIMIT",rate.retryAfter(),rate);}
            if(!dispatched.get())throw knownUnsent(failure);
            throw failure;
        }
    }
    /** Read outside the HTTP budget after both limiter waits; the final callback only checks local rights. */
    private void validateCurrentProducts(List<Preview> previews)throws IOException{
        requireWrites(previews);
        if(client instanceof GtinBatchTransport batch)batch.refresh(previews.stream().map(p->p.before().key()).toList());
        var catalog=new GtinSyncRepository().catalogItems(shop.getId(),Marketplace.OZON,previews.stream().map(Preview::newGtin).toList());
        for(var preview:previews){var key=preview.before().key();var product=ownProduct(key);var fresh=snapshot(key,product,null);
            if(!snapshotMatches(preview.before(),fresh))throw new IllegalArgumentException("product_changed");
            if(RegisteredGtinProof.contains(fresh.barcodes(),preview.newGtin()))throw new IllegalArgumentException("already_present");
            if(fresh.barcodes().size()>=100)throw new IllegalArgumentException("ozon_barcode_limit");
            if(conflicts(key,preview.newGtin()))throw new IllegalArgumentException("gtin_on_another_product");
            var source=catalog.get(preview.newGtin());if(source==null)throw new IllegalArgumentException("unverified_catalog_gtin");validateIdentity(fresh,source);
        }
    }
    private static GtinKnownUnsentException knownUnsent(Exception cause){var error=new GtinKnownUnsentException(com.vncode.app.features.sync.SyncFailure.of(cause,0).code());error.initCause(cause);return error;}
    private static String rejectionCode(JsonObject error){String code=text(error,"code").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]","_");return code.substring(0,Math.min(60,code.length()));}
    public Verification verify(Preview preview,Submission submission)throws IOException {
        var key=preview.before().key();requireKey(key);var tokens=beginRead(key);var started=Instant.now();var product=ownProduct(key);var actual=snapshot(key,product,null);if(!observe(actual,started,tokens))return Verification.UNKNOWN;
        if(RegisteredGtinProof.contains(actual.barcodes(),preview.newGtin())) {
            var restored=actual.barcodes().stream().filter(code->!RegisteredGtinProof.sameCode(code,preview.newGtin())).toList();
            if(!snapshotMatches(preview.before(),snapshot(key,product,restored)))return Verification.UNKNOWN;
            try{validateIdentity(actual,new GtinSyncRepository().catalogItem(shop.getId(),Marketplace.OZON,preview.newGtin()));return Verification.APPLIED;}catch(IllegalArgumentException unknown){return Verification.UNKNOWN;}
        }
        if(submission.remoteTaskId().equals("ozon-rejected")||submission.remoteTaskId().startsWith("ozon-rejected:"))return Verification.REJECTED;
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
        String fingerprint=fingerprint(key,product,codes);
        if(client instanceof GtinBatchTransport batch)fingerprint="ozon-bulk:"+ListingIdentity.hash(fingerprint+"\n"+batch.attributeIdentity(key.productId()));
        return new ProductSnapshot(key,article,"","",codes,fingerprint,Instant.now());
    }
    @Override public boolean snapshotMatches(ProductSnapshot expected,ProductSnapshot actual)throws IOException {
        if(!expected.key().equals(actual.key()))return false;
        if(expected.fingerprint().equals(actual.fingerprint()))return true;
        var key=expected.key();var product=ownProduct(key);String base=fingerprint(key,product,actual.barcodes());
        if(!expected.fingerprint().startsWith("ozon-bulk:"))return expected.fingerprint().equals(base);
        String attributes;
        if(client instanceof GtinBatchTransport batch)attributes=batch.attributeIdentity(key.productId());
        else {var ids=new JsonArray();ids.add(key.productId());var filter=new JsonObject();filter.add("product_id",ids);var body=new JsonObject();body.add("filter",filter);body.addProperty("limit",1);JsonObject found=null;
            for(var entry:strictArray(object(client.call("v4/product/info/attributes",body,false)),"result")){var attr=object(entry);if(!key.productId().equals(text(attr,"id"))||found!=null||!key.variantId().equals(text(attr,"offer_id")))throw new IOException("foreign_ozon_product");found=attr;}
            if(found==null)throw new IOException("product_not_found");attributes=GtinBatchTransport.attributeIdentity(found);
        }
        return expected.fingerprint().equals("ozon-bulk:"+ListingIdentity.hash(base+"\n"+attributes));
    }
    private String fingerprint(ProductKey key,JsonObject product,List<String> codes){return ListingIdentity.hash(ListingIdentity.canonical(new Gson().toJsonTree(List.of(key,ListingIdentity.fingerprint(shop),text(product,"sku"),text(product,"offer_id"),text(product,"name"),codes.stream().distinct().sorted().toList()))));}
    private void requireWrite(Preview preview){requireKey(preview.before().key());LicenseRuntime.require(Permission.GTIN);LicenseRuntime.require(Permission.SELLER_API);RegisteredGtinProof.requireCurrent(shop,preview.newGtin());}
    private void requireKey(ProductKey key){ListingIdentity.require(shop);if(key.shopId()!=shop.getId()||key.marketplace()!=Marketplace.OZON||!numeric(key.productId()))throw new IllegalArgumentException("shop_mismatch");}
    private static boolean numeric(String value){return value.matches("[1-9][0-9]{0,18}")&&new BigInteger(value).compareTo(BigInteger.valueOf(Long.MAX_VALUE))<=0;}
    private static JsonArray strictArray(JsonObject object,String key)throws IOException{if(!object.has(key)||!object.get(key).isJsonArray())throw new IOException("incomplete_ozon_catalog");var array=object.getAsJsonArray(key);if(key.equals("barcodes")&&array.asList().stream().anyMatch(v->!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()||v.getAsString().isBlank()))throw new IOException("invalid_ozon_barcode");return array;}
}
