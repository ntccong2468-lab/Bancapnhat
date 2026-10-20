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
public final class WbGtinAdapter implements GtinBatchAdapter {
    public static final int MAX_UPDATE_CARDS=3000;
    public static final int MAX_UPDATE_BYTES=10_000_000;
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
    public void validateProduct(ProductKey key)throws IOException{checkedArticle(ownCard(key));}
    public void validateRegistration(String gtin){RegisteredGtinProof.requireCurrent(shop,gtin);}
    public ProductSnapshot read(ProductKey key)throws IOException{var actual=snapshot(key,ownCard(key));observe(actual);return actual;}
    private void observe(ProductSnapshot actual){if(!(client instanceof GtinBatchTransport))MarketplaceBarcodeCache.observe(shop,actual.key(),actual.barcodes());}
    public boolean conflicts(ProductKey key,String gtin)throws IOException {
        requireKey(key);
        if(client instanceof GtinBatchTransport batch)return batch.conflicts(key,gtin);
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
    @Override public void prepareBatch(Collection<ProductKey> keys)throws IOException{for(var key:keys)requireKey(key);if(client instanceof GtinBatchTransport batch)batch.prepare(keys);}
    @Override public void refreshBatch(Collection<ProductKey> keys)throws IOException{for(var key:keys)requireKey(key);if(client instanceof GtinBatchTransport batch)batch.refresh(keys);}
    @Override public void validateRegistrations(Collection<String> gtins){RegisteredGtinProof.requireCurrent(shop,gtins);}
    @Override public List<List<Preview>> partition(List<Preview> previews)throws IOException {
        var groups=new LinkedHashMap<String,List<Preview>>();for(var p:previews){requireKey(p.before().key());groups.computeIfAbsent(p.before().key().productId(),k->new ArrayList<>()).add(p);}
        var result=new ArrayList<List<Preview>>();var current=new ArrayList<Preview>();int cards=0;long bytes=2;
        for(var group:groups.values()){
            long size=update(group).toString().getBytes(StandardCharsets.UTF_8).length;
            if(size+2>MAX_UPDATE_BYTES)throw new IllegalArgumentException("WB_UPDATE_TOO_LARGE");
            if(cards==MAX_UPDATE_CARDS||bytes+size+(cards==0?0:1)>MAX_UPDATE_BYTES){result.add(List.copyOf(current));current.clear();cards=0;bytes=2;}
            current.addAll(group);bytes+=size+(cards++==0?0:1);
        }
        if(!current.isEmpty())result.add(List.copyOf(current));return List.copyOf(result);
    }
    @Override public Map<ProductKey,Submission> submitBatch(List<Preview> previews,Runnable boundaryGuard)throws Exception {
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            if(previews.isEmpty())throw new IllegalArgumentException("empty_selection");requireWrites(previews);
            if(client instanceof GtinBatchTransport batch)batch.refresh(previews.stream().map(p->p.before().key()).toList());
            var groups=new LinkedHashMap<String,List<Preview>>();for(var p:previews)groups.computeIfAbsent(p.before().key().productId(),k->new ArrayList<>()).add(p);
            if(groups.size()>MAX_UPDATE_CARDS)throw new IllegalArgumentException("WB_BATCH_LIMIT");var request=new JsonArray();
            for(var group:groups.values())request.add(update(group));
            if(request.toString().getBytes(StandardCharsets.UTF_8).length>MAX_UPDATE_BYTES)throw new IllegalArgumentException("WB_UPDATE_TOO_LARGE");
            var response=object(client.call("content/v2/cards/update",request,true,()->{requireWrites(previews);boundaryGuard.run();if(client instanceof GtinBatchTransport batch)batch.reserve(previews);dispatched.set(true);}));
            if(!response.has("error")||!response.get("error").isJsonPrimitive()||!response.getAsJsonPrimitive("error").isBoolean())throw new IOException("invalid_wb_update_response");
            var result=new LinkedHashMap<ProductKey,Submission>();for(var p:previews)result.put(p.before().key(),new Submission(response.get("error").getAsBoolean()?"wb-rejected":""));return Map.copyOf(result);
        }catch(Exception failure){if(failure instanceof ListingRateLimitException rate)throw new GtinRateLimitedException("WB_RATE_LIMIT",rate.retryAfter(),rate);if(!dispatched.get())throw new GtinKnownUnsentException(com.vncode.app.features.sync.SyncFailure.of(failure,0).code());throw failure;}
    }
    @Override public Map<ProductKey,VerificationResult> verifyBatch(List<JobItem> items)throws IOException {
        if(client instanceof GtinBatchTransport batch)batch.refresh(items.stream().map(i->i.preview().before().key()).toList());
        var result=new LinkedHashMap<ProductKey,VerificationResult>();var pending=new ArrayList<JobItem>();var targets=new ArrayList<RejectionTarget>();
        for(var item:items)try{
            var p=item.preview();var card=ownCard(p.before().key());var actual=snapshot(p.before().key(),card);observe(actual);
            targets.add(rejectionTarget(p,card,actual));
            if(RegisteredGtinProof.contains(actual.barcodes(),p.newGtin())&&text(card,"vendorCode").equals(com.vncode.app.shared.SellerArticle.trim(p.before().article())))result.put(p.before().key(),new VerificationResult(p.before().barcodes().stream().allMatch(code->RegisteredGtinProof.contains(actual.barcodes(),code))?Verification.APPLIED:Verification.UNKNOWN));
            else if("wb-rejected".equals(item.remoteTaskId()))result.put(p.before().key(),new VerificationResult(Verification.REJECTED));
            else pending.add(item);
        }catch(IOException unavailable){result.put(item.preview().before().key(),new VerificationResult(Verification.UNKNOWN,com.vncode.app.features.sync.SyncFailure.of(unavailable,0).code()));}
        if(!pending.isEmpty())try{
            var errors=recentRejections(targets,20_000);
            for(var item:pending){var key=item.preview().before().key();var error=errors.get(key);result.put(key,new VerificationResult(error!=null?Verification.REJECTED:Verification.PENDING,error!=null?error.issue():""));}
        }catch(IOException unavailable){for(var item:pending)result.put(item.preview().before().key(),new VerificationResult(Verification.UNKNOWN,com.vncode.app.features.sync.SyncFailure.of(unavailable,0).code()));}
        return Map.copyOf(result);
    }
    private record RejectionTarget(Preview preview,Set<String> articles) {}
    private record CardRejection(Instant time,String issue) {}
    private static RejectionTarget rejectionTarget(Preview preview,JsonObject card,ProductSnapshot actual) {
        var articles=new LinkedHashSet<String>();articles.add(com.vncode.app.shared.SellerArticle.trim(preview.before().article()));
        // ProductSnapshot strips article edges. Recover the exact original only from its owned, unchanged preimage.
        if(actual.fingerprint().equals(preview.before().fingerprint()))articles.add(text(card,"vendorCode"));
        return new RejectionTarget(preview,Set.copyOf(articles));
    }
    private Map<ProductKey,CardRejection> recentRejections(List<RejectionTarget> targets,int pageLimit)throws IOException {
        var owners=new HashMap<String,Set<String>>();
        for(var target:targets)for(String article:target.articles())owners.computeIfAbsent(article,k->new HashSet<>()).add(target.preview().before().key().productId());
        var result=new HashMap<ProductKey,CardRejection>();var cursor=new JsonObject();cursor.addProperty("limit",100);var seen=new HashSet<String>();
        for(int page=0;page<pageLimit;page++){
            var body=new JsonObject();body.add("cursor",cursor);var response=object(client.call("content/v2/cards/error/list",body,false));if("true".equals(text(response,"error")))throw new IOException("WB_ERROR_LIST_UNAVAILABLE");var data=obj(response,"data");
            for(var entry:strictArray(data,"items")){var item=object(entry);Instant time;try{time=Instant.parse(text(item,"updatedAt"));}catch(RuntimeException invalid){continue;}if(time.isAfter(Instant.now().plusSeconds(300)))continue;
                for(var code:array(item,"vendorCodes")){
                    if(!code.isJsonPrimitive()||!code.getAsJsonPrimitive().isString())continue;
                    String article=code.getAsString();var errors=obj(item,"errors").get(article);
                    if(owners.getOrDefault(article,Set.of()).size()!=1||errors==null||!errors.isJsonArray()||errors.getAsJsonArray().isEmpty())continue;
                    boolean whitespace=errors.getAsJsonArray().asList().stream().filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString).anyMatch(value->value.toLowerCase(Locale.ROOT).contains("пробел"));
                    String issue=whitespace&&!article.equals(com.vncode.app.shared.SellerArticle.trim(article))?"WB_ARTICLE_EDGE_WHITESPACE":"WB_GTIN_CARD_REJECTED";
                    for(var target:targets)if(target.articles().contains(article)&&!time.isBefore(target.preview().before().fetchedAt()))result.merge(target.preview().before().key(),new CardRejection(time,issue),(a,b)->a.time().isAfter(b.time())?a:b);
                }
            }
            var next=obj(data,"cursor");if(!"true".equals(text(next,"next")))return result;String updated=text(next,"updatedAt"),batch=text(next,"batchUUID");if(updated.isBlank()||!batch.matches("[a-fA-F0-9-]{36}")||!seen.add(updated+"/"+batch))throw new IOException("WB_ERROR_CURSOR_INVALID");cursor=new JsonObject();cursor.addProperty("limit",100);cursor.addProperty("updatedAt",updated);cursor.addProperty("batchUUID",batch);
        }throw new IOException("WB_ERROR_PAGE_LIMIT");
    }
    private JsonObject update(List<Preview> previews)throws IOException {
        var first=previews.getFirst();var card=ownCard(first.before().key());var result=payload(card);result.addProperty("vendorCode",checkedArticle(card));
        for(var p:previews){var key=p.before().key();requireKey(key);if(!key.productId().equals(first.before().key().productId())||p.operation()!=Operation.ADD||!p.oldGtin().isBlank()||!CAPABILITY.equals(p.capability()))throw new IllegalArgumentException("unsupported_operation");
            var actual=snapshot(key,card);if(!actual.fingerprint().equals(p.before().fingerprint()))throw new IllegalArgumentException("product_changed");if(RegisteredGtinProof.contains(actual.barcodes(),p.newGtin()))throw new IllegalArgumentException("already_present");if(conflicts(key,p.newGtin()))throw new IllegalArgumentException("gtin_on_another_product");
            boolean found=false;for(var e:strictArray(result,"sizes")){var size=object(e);if(key.variantId().equals(text(size,"chrtID"))){size.getAsJsonArray("skus").add(p.newGtin());found=true;}}
            if(!found)throw new IOException("variant_not_found");
        }return result;
    }
    private String checkedArticle(JsonObject card)throws IOException{return client instanceof GtinBatchTransport batch?batch.checkedWbArticle(card):WbArticlePolicy.checked(card,client);}
    private void requireWrites(List<Preview> previews){LicenseRuntime.require(Permission.GTIN);LicenseRuntime.require(Permission.SELLER_API);for(var p:previews)requireKey(p.before().key());RegisteredGtinProof.requireCurrent(shop,previews.stream().map(Preview::newGtin).toList());}
    public Submission submit(Preview preview)throws IOException {
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
        try {
        ProductKey key=preview.before().key();requireKey(key);
        if(preview.operation()!=Operation.ADD||!preview.oldGtin().isBlank()||!CAPABILITY.equals(preview.capability()))throw new IllegalArgumentException("unsupported_operation");
        requireWrite(preview);
        var card=ownCard(key);var fresh=snapshot(key,card);
        String trimmedArticle=WbArticlePolicy.checked(card,client);
        if(!fresh.fingerprint().equals(preview.before().fingerprint()))throw new IllegalArgumentException("product_changed");
        if(RegisteredGtinProof.contains(fresh.barcodes(),preview.newGtin()))throw new IllegalArgumentException("already_present");
        if(conflicts(key,preview.newGtin()))throw new IllegalArgumentException("gtin_on_another_product");
        var update=payload(card);
        update.addProperty("vendorCode",trimmedArticle);
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
        var card=ownCard(preview.before().key());var actual=snapshot(preview.before().key(),card);observe(actual);
        if(RegisteredGtinProof.contains(actual.barcodes(),preview.newGtin()) && text(card,"vendorCode").equals(com.vncode.app.shared.SellerArticle.trim(preview.before().article())))
            return preview.before().barcodes().stream().allMatch(code->RegisteredGtinProof.contains(actual.barcodes(),code))?Verification.APPLIED:Verification.UNKNOWN;
        if("wb-rejected".equals(submission.remoteTaskId()))return Verification.REJECTED;
        var rejection=recentRejections(List.of(rejectionTarget(preview,card,actual)),10).get(preview.before().key());if(rejection!=null)throw new GtinRejectedException(rejection.issue());
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
