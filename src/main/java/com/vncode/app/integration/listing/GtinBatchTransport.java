package com.vncode.app.integration.listing;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;
import static com.vncode.app.integration.listing.ListingJson.*;

/** Run-scoped owned-catalog index, never a persistent substitute for a fresh source proof.
 * WB uses the official ascending updatedAt/trashedAt cursors after the initial scan.
 * Ozon reads info/attributes in groups of 1000; only the explicit selected cards are retained.
 * A failed/partial scan invalidates the scope before any later write can use it. */
public final class GtinBatchTransport implements ListingTransport, AutoCloseable {
    private static final long MAX_RETAINED_BYTES=64L*1024*1024;
    private final Shop shop;
    private final ListingTransport upstream;
    private final Map<String,Set<ProductKey>> codes=new HashMap<>();
    private final Map<String,Set<String>> articles=new HashMap<>();
    private final Map<String,Owned> owned=new HashMap<>();
    private final Map<String,ProductKey> reserved=new HashMap<>();
    private final GtinSnapshotStore retained=new GtinSnapshotStore(MAX_RETAINED_BYTES);
    private final Map<String,JsonElement> dictionaries=new HashMap<>();
    private final Set<String> attributesRequested=new HashSet<>();
    private final Map<ProductKey,List<String>> observations=new LinkedHashMap<>();
    private Set<String> targets=Set.of();
    private final Set<String> selected=new HashSet<>();
    private JsonObject activeCursor,trashCursor;
    private boolean ready,closed;
    private long generation;
    private record Owned(String article,List<Map.Entry<String,ProductKey>> codes,boolean active){}

    public GtinBatchTransport(Shop shop,ListingTransport upstream){
        this.shop=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
        this.upstream=Objects.requireNonNull(upstream);
    }
    public long generation(){return generation;}
    public long retainedBytes(){return retained.retainedBytes();}
    public int ownedProducts(){return owned.size();}
    public String attributeIdentity(String id)throws IOException {
        requireReady();var value=get("a:"+id);if(value==null)throw new IOException("GTIN_BATCH_ATTRIBUTES_NOT_LOADED");return attributeIdentity(value);
    }
    public static String attributeIdentity(JsonObject value){var copy=value.deepCopy();for(String field:List.of("barcode","barcodes","updated_at","created_at"))copy.remove(field);return ListingIdentity.canonical(copy);}
    public void prepare(Collection<ProductKey> keys)throws IOException {
        requireKeys(keys);targets=keys.stream().map(ProductKey::productId).collect(java.util.stream.Collectors.toSet());
        try {
            if(!ready){clear();selected.addAll(targets);if(shop.getMarketplace()==Marketplace.WILDBERRIES){scanWb(false,false);scanWb(true,false);}else scanOzon();ready=true;}
            else if(keys.stream().anyMatch(k->owned.containsKey(k.productId())&&owned.get(k.productId()).active()&&get(infoKey(k.productId()))==null)) {
                selected.addAll(targets);
                if(shop.getMarketplace()==Marketplace.WILDBERRIES)scanWb(false,false);else infoOzon(targets);
            }
            selected.addAll(targets);
            if(shop.getMarketplace()==Marketplace.OZON){var missing=new LinkedHashSet<String>();for(var id:targets)if(!attributesRequested.contains(id)||get("a:"+id)==null&&owned.containsKey(id)&&owned.get(id).active())missing.add(id);attributesOzon(missing);}
            flushObservations();generation++;
        }catch(IOException|RuntimeException failure){ready=false;observations.clear();throw failure;}
    }
    /** Runs once per request batch/poll, not once per GTIN. No HTTP permit is held while waiting. */
    public void refresh(Collection<ProductKey> keys)throws IOException {
        requireKeys(keys);if(!ready){prepare(keys);return;}
        targets=keys.stream().map(ProductKey::productId).collect(java.util.stream.Collectors.toSet());
        selected.addAll(targets);
        try {
            if(shop.getMarketplace()==Marketplace.WILDBERRIES){scanWb(false,activeCursor!=null);scanWb(true,trashCursor!=null);}
            else {infoOzon(targets);attributesOzon(targets);}
            flushObservations();ready=true;generation++;
        }catch(IOException|RuntimeException failure){ready=false;observations.clear();throw failure;}
    }
    public boolean conflicts(ProductKey key,String gtin){
        requireReady();requireKeys(List.of(key));String code=normalize(gtin);
        var owners=codes.getOrDefault(code,Set.of());var pending=reserved.get(code);
        return owners.stream().anyMatch(owner->!owner.equals(key))||pending!=null&&!pending.equals(key);
    }
    public void reserve(List<GtinSyncModels.Preview> previews){
        requireReady();var proposed=new HashMap<String,ProductKey>();
        for(var p:previews){var key=p.before().key();String code=normalize(p.newGtin());var previous=proposed.putIfAbsent(code,key);if(previous!=null&&!previous.equals(key)||conflicts(key,p.newGtin()))throw new IllegalArgumentException("gtin_on_another_product");}
        reserved.putAll(proposed);
    }
    public String checkedWbArticle(JsonObject card){
        requireReady();String original=text(card,"vendorCode"),trimmed=com.vncode.app.shared.SellerArticle.trim(original);
        if(trimmed.isBlank())throw new IllegalArgumentException("WB_ARTICLE_REQUIRED");
        if(!trimmed.equals(original)&&articles.getOrDefault(trimmed.toLowerCase(Locale.ROOT),Set.of()).stream().anyMatch(id->!id.equals(text(card,"nmID"))))throw new IllegalArgumentException("WB_ARTICLE_TRIM_COLLISION");
        return trimmed;
    }
    @Override public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException{return call(path,body,mutation,()->{});}
    @Override public JsonElement call(String path,JsonElement body,boolean mutation,ListingTransport.CheckedGuard freshRead,Runnable guard)throws IOException {
        requireReady();
        if(mutation)return upstream.call(path,body,true,()->{requireReady();freshRead.check();},()->{requireReady();guard.run();});
        freshRead.check();return call(path,body,false,guard);
    }
    @Override public JsonElement call(String path,JsonElement body,boolean mutation,Runnable guard)throws IOException {
        requireReady();
        if(mutation)return upstream.call(path,body,true,()->{requireReady();guard.run();});
        var request=body!=null&&body.isJsonObject()?body.getAsJsonObject():new JsonObject();
        if(shop.getMarketplace()==Marketplace.WILDBERRIES&&path.equals("content/v2/get/cards/list")) {
            String id=text(obj(obj(request,"settings"),"filter"),"textSearch");
            var matches=new LinkedHashSet<String>();
            if(owned.containsKey(id))matches.add(id);
            matches.addAll(articles.getOrDefault(article(id),Set.of()));
            for(var key:codes.getOrDefault(normalize(id),Set.of()))matches.add(key.productId());
            boolean loaded=!id.isBlank();var cards=new JsonArray();
            for(String match:matches)if(owned.get(match).active()){
                var card=get("w:"+match);if(card==null){loaded=false;break;}cards.add(card.deepCopy());
            }
            if(loaded&&!matches.isEmpty()){
                guard.run();var result=new JsonObject();result.add("cards",cards);var cursor=new JsonObject();cursor.addProperty("total",cards.size());result.add("cursor",cursor);return result;
            }
            // An absent nmID must stay absent; do not turn a per-card lookup into a new full scan.
            if(matches.isEmpty()&&id.matches("[1-9][0-9]{0,19}")){guard.run();var result=new JsonObject();result.add("cards",new JsonArray());return result;}
        }
        if(shop.getMarketplace()==Marketplace.OZON&&path.equals("v3/product/info/list"))return ozonResponse(request,"product_id","i:","items");
        if(shop.getMarketplace()==Marketplace.OZON&&path.equals("v4/product/info/attributes"))return ozonResponse(obj(request,"filter"),"product_id","a:","result");
        if(path.equals("v1/description-category/attribute")){
            String key=ListingIdentity.canonical(body);var result=dictionaries.get(key);
            if(result==null){result=upstream.call(path,body,false);if(dictionaries.size()>=1000)throw new IOException("GTIN_BATCH_DICTIONARY_LIMIT");dictionaries.put(key,result.deepCopy());}
            return result.deepCopy();
        }
        return upstream.call(path,body,false);
    }
    private JsonObject ozonResponse(JsonObject request,String field,String prefix,String resultField)throws IOException {
        var result=new JsonObject();var items=new JsonArray();
        if(request.has("offer_id")&&!request.has(field)){
            for(var e:strictArray(request,"offer_id")){
                String offer=e.getAsString();
                for(String id:articles.getOrDefault(article(offer),Set.of())){
                    if(!owned.get(id).article().equals(offer))continue;
                    if(!selected.contains(id))throw new IOException("GTIN_BATCH_UNSELECTED_PRODUCT");
                    var value=get(prefix+id);if(value!=null)items.add(value.deepCopy());
                }
            }
            result.add(resultField,items);return result;
        }
        for(var e:strictArray(request,field)){String id=e.getAsString();if(!targets.contains(id))throw new IOException("GTIN_BATCH_UNSELECTED_PRODUCT");var value=get(prefix+id);if(value!=null)items.add(value.deepCopy());}
        result.add(resultField,items);return result;
    }
    private void scanWb(boolean trash,boolean incremental)throws IOException {
        JsonObject cursor=incremental?(trash?trashCursor:activeCursor).deepCopy():new JsonObject();cursor.addProperty("limit",100);
        var seen=new HashSet<String>();var products=new HashSet<String>();boolean finished=false;
        for(int page=0;page<20_000;page++){
            var settings=new JsonObject();settings.add("cursor",cursor);var sort=new JsonObject();sort.addProperty("ascending",true);settings.add("sort",sort);
            if(!trash){var filter=new JsonObject();filter.addProperty("withPhoto",-1);settings.add("filter",filter);}
            var request=new JsonObject();request.add("settings",settings);
            var response=object(upstream.call(trash?"content/v2/get/cards/trash":"content/v2/get/cards/list",request,false));
            if("true".equals(text(response,"error")))throw new IOException("wb_catalog_rejected");
            var cards=strictArray(response,"cards");
            var next=obj(response,"cursor");int total;
            try{var value=next.get("total");if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new NumberFormatException();total=value.getAsBigDecimal().toBigIntegerExact().intValueExact();if(total<0||total>100||total!=cards.size())throw new NumberFormatException();}
            catch(RuntimeException invalid){throw new IOException("GTIN_BATCH_INCOMPLETE_RESPONSE",invalid);}
            // The cursor advances for the whole account. Keep changes to every selected
            // card, including earlier write batches, or final readback would see stale data.
            for(var e:cards){var card=object(e);String id=text(card,"nmID");if(!id.matches("[1-9][0-9]{0,19}")||!products.add(id))throw new IOException("invalid_wb_catalog");indexWb(id,card,!trash);if(!trash&&selected.contains(id))retain("w:"+id,card);if(trash)remove("w:"+id);}
            String date=text(next,trash?"trashedAt":"updatedAt"),id=text(next,"nmID");
            if(!date.isBlank()&&id.matches("[1-9][0-9]{0,19}")){var saved=new JsonObject();saved.addProperty(trash?"trashedAt":"updatedAt",date);saved.addProperty("nmID",new java.math.BigInteger(id));if(trash)trashCursor=saved;else activeCursor=saved;}
            if(total<100){finished=true;break;}
            if(date.isBlank()||!id.matches("[1-9][0-9]{0,19}")||!seen.add(date+":"+id))throw new IOException("invalid_wb_cursor");
            cursor=next.deepCopy();cursor.remove("total");cursor.addProperty("limit",100);
        }
        if(!finished)throw new IOException("wb_page_limit");
    }
    private void scanOzon()throws IOException {
        var products=new HashSet<String>();
        // Ozon's ALL explicitly excludes archived products, which can still own a barcode.
        for(String visibility:List.of("ALL","ARCHIVED"))scanOzon(visibility,products);
    }
    private void scanOzon(String visibility,Set<String> products)throws IOException {
        String cursor="";var seen=new HashSet<String>();long observed=0,expected=-1;
        for(int page=0;page<20_000;page++){
            var filter=new JsonObject();filter.addProperty("visibility",visibility);var request=new JsonObject();request.add("filter",filter);request.addProperty("last_id",cursor);request.addProperty("limit",1000);
            var result=obj(object(upstream.call("v3/product/list",request,false)),"result");var rows=strictArray(result,"items");var ids=new LinkedHashSet<String>();
            long total=ozonTotal(result);if(expected<0)expected=total;else if(total!=expected)throw new IOException("incomplete_ozon_catalog");
            if(rows.size()>1000)throw new IOException("incomplete_ozon_catalog");
            for(var e:rows){String id=text(object(e),"product_id");if(!id.matches("[1-9][0-9]{0,18}")||!ids.add(id)||!products.add(id))throw new IOException("invalid_ozon_catalog");}
            observed+=ids.size();if(observed>expected)throw new IOException("incomplete_ozon_catalog");
            infoOzon(ids);
            if(observed==expected)return;
            if(rows.isEmpty())throw new IOException("incomplete_ozon_catalog");
            String next=text(result,"last_id");if(next.isBlank()||!seen.add(next)||next.equals(cursor))throw new IOException("invalid_ozon_cursor");cursor=next;
        }
        throw new IOException("ozon_page_limit");
    }
    private static long ozonTotal(JsonObject result)throws IOException {
        Long total=null;
        for(String field:List.of("total_items","total"))if(result.has(field)){
            try {
                var value=result.get(field);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new NumberFormatException();
                long current=value.getAsBigDecimal().toBigIntegerExact().longValueExact();if(current<0||total!=null&&total!=current)throw new NumberFormatException();total=current;
            }catch(RuntimeException invalid){throw new IOException("incomplete_ozon_catalog",invalid);}
        }
        if(total==null)throw new IOException("incomplete_ozon_catalog");return total;
    }
    private void infoOzon(Collection<String> ids)throws IOException {
        for(var batch:chunks(ids,1000)){
            var request=new JsonObject();request.add("product_id",new Gson().toJsonTree(batch));var response=object(upstream.call("v3/product/info/list",request,false));var seen=new HashSet<String>();
            for(var e:strictArray(response,"items")){var product=object(e);String id=text(product,"id"),offer=text(product,"offer_id");if(!batch.contains(id)||!seen.add(id)||offer.isBlank())throw new IOException("foreign_ozon_product");var entries=new ArrayList<Map.Entry<String,ProductKey>>();var key=new ProductKey(shop.getId(),Marketplace.OZON,id,offer);var barcodes=stringCodes(strictArray(product,"barcodes"));for(var code:barcodes)entries.add(Map.entry(code,key));boolean active=!"true".equals(text(product,"is_archived"))&&!"true".equals(text(product,"is_autoarchived"));index(id,new Owned(offer,entries,active));if(targets.contains(id)){retain("i:"+id,product);if(active)observations.put(key,barcodes);}}
            if(!seen.equals(new HashSet<>(batch)))throw new IOException("incomplete_ozon_catalog");
        }
    }
    private void attributesOzon(Collection<String> ids)throws IOException {
        for(var batch:chunks(ids,1000)){
            var filter=new JsonObject();filter.add("product_id",new Gson().toJsonTree(batch));filter.addProperty("visibility","ALL");var request=new JsonObject();request.add("filter",filter);request.addProperty("limit",1000);request.addProperty("last_id","");
            var response=object(upstream.call("v4/product/info/attributes",request,false));var seen=new HashSet<String>();
            for(var e:strictArray(response,"result")){var product=object(e);String id=text(product,"id");if(!batch.contains(id)||!seen.add(id))throw new IOException("foreign_ozon_product");var info=get("i:"+id);if(info!=null&&!text(info,"offer_id").equals(text(product,"offer_id")))throw new IOException("OZON_SKU_CHANGED");retain("a:"+id,product);}
            for(var id:batch)if(!seen.contains(id))remove("a:"+id);attributesRequested.addAll(batch);
        }
    }
    private void indexWb(String id,JsonObject card,boolean active)throws IOException {
        var entries=new ArrayList<Map.Entry<String,ProductKey>>();
        var sizes=new HashSet<String>();
        for(var e:strictArray(card,"sizes")){var size=object(e);String variant=text(size,"chrtID");if(!variant.matches("[1-9][0-9]{0,19}")||!sizes.add(variant))throw new IOException("invalid_wb_size");var key=new ProductKey(shop.getId(),Marketplace.WILDBERRIES,id,variant);var barcodes=stringCodes(strictArray(size,"skus"));for(var code:barcodes)entries.add(Map.entry(code,key));if(active&&selected.contains(id))observations.put(key,barcodes);}
        index(id,new Owned(text(card,"vendorCode"),entries,active));
    }
    private void index(String id,Owned replacement){
        var previous=owned.put(id,replacement);
        if(previous!=null){for(var entry:previous.codes()){var set=codes.get(normalize(entry.getKey()));if(set!=null){set.remove(entry.getValue());if(set.isEmpty())codes.remove(normalize(entry.getKey()));}}var set=articles.get(article(previous.article()));if(set!=null){set.remove(id);if(set.isEmpty())articles.remove(article(previous.article()));}}
        for(var entry:replacement.codes())codes.computeIfAbsent(normalize(entry.getKey()),k->new HashSet<>()).add(entry.getValue());articles.computeIfAbsent(article(replacement.article()),k->new HashSet<>()).add(id);
    }
    private String infoKey(String id){return (shop.getMarketplace()==Marketplace.WILDBERRIES?"w:":"i:")+id;}
    private JsonObject get(String key){return retained.get(key);}
    private void retain(String key,JsonObject value){retained.put(key,value);}
    private void remove(String key){retained.remove(key);}
    private static String article(String value){return com.vncode.app.shared.SellerArticle.trim(value).toLowerCase(Locale.ROOT);}
    public static String normalize(String value){return RegisteredGtinProof.canonicalCode(value);}
    private void requireReady(){ListingIdentity.require(shop);if(closed||!ready)throw new IllegalStateException("GTIN_BATCH_SNAPSHOT_INCOMPLETE");}
    private void requireKeys(Collection<ProductKey> keys){ListingIdentity.require(shop);if(closed)throw new IllegalStateException("GTIN_BATCH_CLOSED");for(var key:keys)if(key.shopId()!=shop.getId()||key.marketplace()!=shop.getMarketplace())throw new SecurityException("shop_mismatch");}
    private static JsonArray strictArray(JsonObject value,String key)throws IOException{if(!value.has(key)||!value.get(key).isJsonArray())throw new IOException("GTIN_BATCH_INCOMPLETE_RESPONSE");return value.getAsJsonArray(key);}
    private static List<String> stringCodes(JsonArray values)throws IOException{var result=new ArrayList<String>();for(var value:values){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().isBlank())throw new IOException("GTIN_BATCH_INVALID_BARCODE");result.add(value.getAsString());}return List.copyOf(result);}
    private void flushObservations(){MarketplaceBarcodeCache.observeBatch(shop,observations);observations.clear();}
    private static List<List<String>> chunks(Collection<String> ids,int size){var all=new ArrayList<>(ids);var result=new ArrayList<List<String>>();for(int i=0;i<all.size();i+=size)result.add(List.copyOf(all.subList(i,Math.min(i+size,all.size()))));return result;}
    private void clear(){ready=false;codes.clear();articles.clear();owned.clear();reserved.clear();retained.clear();attributesRequested.clear();dictionaries.clear();observations.clear();selected.clear();activeCursor=null;trashCursor=null;}
    @Override public void close(){clear();closed=true;}
}
