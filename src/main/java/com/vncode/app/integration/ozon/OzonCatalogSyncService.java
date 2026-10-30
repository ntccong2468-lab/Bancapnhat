package com.vncode.app.integration.ozon;

import com.google.gson.JsonObject;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import static com.vncode.app.features.license.LicenseModels.Permission.SELLER_API;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Cursor catalog sync. Each page and its next cursor are committed atomically. */
public final class OzonCatalogSyncService {
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 20_000;

    private final int shopId;
    private final OzonApiClient api;
    private final OzonCatalogRepository products;
    private final OzonSyncStateRepository state;

    public OzonCatalogSyncService(int shopId, OzonApiClient api) {
        this(shopId, api, new OzonCatalogRepository(), new OzonSyncStateRepository());
    }

    OzonCatalogSyncService(
            int shopId,
            OzonApiClient api,
            OzonCatalogRepository products,
            OzonSyncStateRepository state) {
        if (shopId <= 0) throw new IllegalArgumentException("shopId must be positive");
        this.shopId = shopId;
        this.api = Objects.requireNonNull(api, "api");
        this.products = Objects.requireNonNull(products, "products");
        this.state = Objects.requireNonNull(state, "state");
    }

    public int sync() throws IOException {
        LicenseRuntime.require(SELLER_API);
        return sync(state.find(shopId).productsLastId());
    }
    /** Opening the workspace never scans a completed catalog again. Interrupted reads resume. */
    public int syncIfNeeded()throws IOException{
        LicenseRuntime.require(SELLER_API);api.requireSyncOwner(shopId);
        return state.productCatalogComplete(shopId)?0:sync();
    }
    /** User-requested refresh revisits already synced cards; cursor recovery remains available in sync(). */
    public int syncFromStart()throws IOException{return sync("");}
    public int syncForRegistration(java.util.function.Consumer<List<OzonProductDto>> page)throws IOException {return sync("",page);}
    /** Resume a paired registration read only from a seller-scoped durable page snapshot. */
    public int syncForRegistration(com.vncode.app.models.Shop shop,String source,java.util.function.Consumer<List<OzonProductDto>> page)throws IOException {
        if(shop.getId()!=shopId)throw new IllegalArgumentException("shop_mismatch");
        var checkpoint=new OzonRegistrationCheckpoint(shop,source);var saved=checkpoint.open();
        try {
            if(saved.pages()>0){
                LicenseRuntime.require(SELLER_API);api.requireSyncOwner(shopId);
                JsonObject head=api.listProducts("",PAGE_SIZE);api.requireSyncOwner(shopId);
                if(head==null||!head.has("result")||!head.get("result").isJsonObject()||!head.getAsJsonObject("result").has("items")||!head.getAsJsonObject("result").get("items").isJsonArray())throw new IOException("OZON_INCOMPLETE_PRODUCT_PAGE");
                var observed=OzonJson.parseProductPage(head);
                if(observed.items().size()!=head.getAsJsonObject("result").getAsJsonArray("items").size()||observed.items().size()>PAGE_SIZE||observed.items().stream().map(OzonJson.ProductReference::productId).distinct().count()!=observed.items().size()||observed.items().stream().anyMatch(row->row.offerId().isBlank()))throw new IOException("OZON_INCOMPLETE_PRODUCT_PAGE");
                if(!checkpoint.headMatches(saved,observed)){checkpoint.clear();saved=checkpoint.open();}
            }
            checkpoint.replay(saved,page);
            if(saved.completed())return 0;
            return sync(saved.cursor(),page,checkpoint,saved);
        } catch(OzonRegistrationCheckpoint.Invalidated changed){
            checkpoint.clear();throw new IOException("OZON_REGISTRATION_CATALOG_CHANGED",changed);
        } catch(IOException invalid){
            if("OZON_REPEATED_PRODUCT_CURSOR".equals(invalid.getMessage()))checkpoint.clear();
            throw invalid;
        }
    }
    /** A completed paired marketplace/Catalog receipt is the only point that clears read progress. */
    public static void completeRegistration(com.vncode.app.models.Shop shop,String source){new OzonRegistrationCheckpoint(shop,source).clear();}
    private int sync(String initialCursor)throws IOException{return sync(initialCursor,page->{});}
    private int sync(String initialCursor,java.util.function.Consumer<List<OzonProductDto>> observer)throws IOException{
        return sync(initialCursor,observer,null,null);
    }
    private int sync(String initialCursor,java.util.function.Consumer<List<OzonProductDto>> observer,OzonRegistrationCheckpoint checkpoint,OzonRegistrationCheckpoint.State saved)throws IOException{
        LicenseRuntime.require(SELLER_API);
        api.requireSyncOwner(shopId);
        String cursor = initialCursor;
        int synced = 0,listed=saved==null?0:saved.count(),supersededReads=0;
        var checkpointState=saved;
        var cursors=new java.util.HashSet<String>();
        if(initialCursor!=null&&!initialCursor.isBlank())cursors.add(initialCursor);
        Map<OzonProductCardAttributeParser.CategoryKey, Map<String, String>> definitionCache = new LinkedHashMap<>();
        Map<OzonProductCardAttributeParser.CategoryKey, String> categoryNames = null;
        try {
            for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("OZON_SYNC_CANCELLED");
                LicenseRuntime.require(SELLER_API);
                JsonObject response = api.listProducts(cursor, PAGE_SIZE);
                api.requireSyncOwner(shopId);
                if(response==null || !response.has("result") || !response.get("result").isJsonObject() || !response.getAsJsonObject("result").has("items") || !response.getAsJsonObject("result").get("items").isJsonArray())throw new IOException("OZON_INCOMPLETE_PRODUCT_PAGE");
                OzonJson.ProductPage page = OzonJson.parseProductPage(response);
                if(page.items().size()!=response.getAsJsonObject("result").getAsJsonArray("items").size() || page.items().size()>PAGE_SIZE || page.items().stream().map(OzonJson.ProductReference::productId).distinct().count()!=page.items().size() || page.items().stream().anyMatch(row->row.offerId().isBlank()))throw new IOException("OZON_INCOMPLETE_PRODUCT_PAGE");
                if (page.items().isEmpty()) {
                    long observed=listed;
                    if(checkpoint==null&&initialCursor!=null&&!initialCursor.isBlank())try(var c=com.vncode.app.config.Database.getConnection();var p=c.prepareStatement("SELECT count(*) FROM ozon_products WHERE shop_id=?")){
                        p.setInt(1,shopId);try(var r=p.executeQuery()){if(!r.next())throw new IOException("OZON_CATALOG_STORAGE");observed=r.getLong(1);}
                    }catch(java.sql.SQLException failure){throw new IOException("OZON_CATALOG_STORAGE",failure);}
                    if(checkpoint!=null)checkpoint.validate(checkpointState,List.of(),page.total());
                    if(page.total()>observed){if(checkpoint!=null)throw new OzonRegistrationCheckpoint.Invalidated();throw new IOException("OZON_INCOMPLETE_PRODUCT_PAGE");}
                    products.upsertPage(shopId, List.of(), cursor,true,java.time.Instant.now(),api.syncOwnerFingerprint(shopId),Map.of());
                    if(checkpoint!=null)checkpointState=checkpoint.append(checkpointState,List.of(),cursor,true,page.total());
                    return synced;
                }
                String next = page.lastId();
                if (next == null || next.isBlank() || !cursors.add(next)) {
                    throw new IOException("OZON_REPEATED_PRODUCT_CURSOR");
                }
                List<String> ids = page.items().stream().map(OzonJson.ProductReference::productId).toList();
                LicenseRuntime.require(SELLER_API);
                // Claim a monotonic product generation before the HTTP read so
                // a slow old response cannot replace a newer request's result.
                String owner=api.syncOwnerFingerprint(shopId);
                var barcodeReadTokens=com.vncode.app.features.gtinsync.MarketplaceBarcodeCache.issueOzon(shopId,owner,ids);
                java.time.Instant barcodeReadStarted=java.time.Instant.now();
                JsonObject details=api.productInfo(ids);
                requireBarcodeObservations(details);
                List<OzonProductDto> detailed = OzonJson.parseProductInfo(details);
                List<OzonProductCardAttributeParser.Card> cards =
                        OzonProductCardAttributeParser.parseCards(api.productAttributes(ids));
                var byId = new LinkedHashMap<String,OzonProductDto>();
                for(var product:detailed)byId.put(product.productId(),product);
                var cardsById = new LinkedHashMap<String,OzonProductCardAttributeParser.Card>();
                for(var card:cards)cardsById.put(card.productId(),card);
                var expected=new java.util.HashSet<>(ids);
                if(detailed.size()!=ids.size() || cards.size()!=ids.size() || !byId.keySet().equals(expected) || !cardsById.keySet().equals(expected))throw new IOException("OZON_INCOMPLETE_PRODUCT_PAGE");
                for(var reference:page.items()){
                    var product=byId.get(reference.productId());var card=cardsById.get(reference.productId());
                    if(!reference.offerId().equals(product.offerId()) || product.name().isBlank() || (!card.offerId().isBlank()&&!reference.offerId().equals(card.offerId())))throw new IOException("OZON_PRODUCT_IDENTITY_INCOMPLETE");
                }
                if (categoryNames == null && !cards.isEmpty()) {
                    categoryNames = OzonProductCategoryTree.parse(api.descriptionCategoryTree());
                }
                for (OzonProductCardAttributeParser.Card card : cards) {
                    if (definitionCache.containsKey(card.category())) continue;
                    LicenseRuntime.require(SELLER_API);
                    JsonObject definitions = api.descriptionCategoryAttributes(
                            card.category().descriptionCategoryId(), card.category().typeId());
                    definitionCache.put(card.category(),
                            OzonProductCardAttributeParser.parseDefinitionNames(definitions));
                }
                Map<String, OzonProductCardAttributeParser.Resolved> attributesById = new LinkedHashMap<>();
                for (OzonProductCardAttributeParser.Card card : cards) {
                    OzonProductCardAttributeParser.Resolved attributes = OzonProductCardAttributeParser.resolve(
                            card, definitionCache.getOrDefault(card.category(), Map.of()),
                            categoryNames == null ? "" : categoryNames.getOrDefault(card.category(), ""));
                    attributesById.put(attributes.productId(), attributes);
                }
                List<OzonProductDto> complete = new ArrayList<>();
                for (OzonJson.ProductReference reference : page.items()) {
                    OzonProductDto product = byId.get(reference.productId());
                    OzonProductCardAttributeParser.Resolved attributes = attributesById.get(reference.productId());
                    if (attributes != null) {
                        product = product.withCardAttributes(
                                attributes.article(), attributes.color(), attributes.size(),
                                attributes.category(), attributes.gender(),attributes.manufacturerSize(),attributes.russianSize());
                    }
                    var nativeCard=cards.stream().filter(card->card.productId().equals(reference.productId())).findFirst();
                    if(nativeCard.isPresent())product=product.withCategoryIdentity(nativeCard.get().category().descriptionCategoryId(),nativeCard.get().category().typeId());
                    complete.add(product);
                }
                api.requireSyncOwner(shopId);
                boolean finished=page.items().size()<PAGE_SIZE&&(page.total()==0||listed+page.items().size()>=page.total());
                if(checkpoint!=null)checkpoint.validate(checkpointState,complete,page.total());
                try {
                    synced += products.upsertPage(shopId, complete, next,finished,barcodeReadStarted,owner,barcodeReadTokens);
                } catch(OzonCatalogRepository.BarcodeSuperseded superseded){
                    // Retry the same read-only page; no product, cursor or receipt was committed.
                    // Bound retries when another reader keeps changing the same products.
                    if(++supersededReads>3)throw new IOException("OZON_CATALOG_BARCODE_SUPERSEDED",superseded);
                    cursors.remove(next);continue;
                }
                supersededReads=0;
                if(checkpoint!=null)checkpointState=checkpoint.append(checkpointState,complete,next,finished,page.total());
                observer.accept(List.copyOf(complete));
                listed+=page.items().size();
                if (finished) return synced;
                cursor = next;
            }
            throw new IOException("Ozon catalog exceeded the safe pagination bound.");
        } catch (LicenseDeniedException paused) {
            throw paused;
        } catch (OzonApiException exception) {
            reportFailure(exception.kind(),exception);
            throw exception;
        } catch (IOException exception) {
            reportFailure("invalid_response",exception);
            throw exception;
        } catch (RuntimeException exception) {
            reportFailure("local_storage",exception);
            throw exception;
        }
    }
    private void reportFailure(String code,Exception failure){
        try{state.recordSafeError(shopId,code);}catch(RuntimeException reporting){failure.addSuppressed(reporting);}
    }
    /** A missing/invalid barcode field is unknown, not proof of an empty barcode list. */
    private static void requireBarcodeObservations(JsonObject details)throws IOException {
        if(details==null)throw new IOException("OZON_INCOMPLETE_PRODUCT_PAGE");
        JsonObject container=details.has("items")?details:details.has("result")&&details.get("result").isJsonObject()?details.getAsJsonObject("result"):details;
        if(!container.has("items")||!container.get("items").isJsonArray())throw new IOException("OZON_INCOMPLETE_PRODUCT_PAGE");
        for(var element:container.getAsJsonArray("items")){
            if(!element.isJsonObject())throw new IOException("OZON_INCOMPLETE_PRODUCT_PAGE");
            var item=element.getAsJsonObject();var values=item.get("barcodes");
            if(values==null||!values.isJsonArray()||values.getAsJsonArray().size()>100)throw new IOException("OZON_INCOMPLETE_PRODUCT_PAGE");
            for(var value:values.getAsJsonArray())if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().isBlank()||!value.getAsString().equals(value.getAsString().strip())||value.getAsString().length()>512)throw new IOException("OZON_INCOMPLETE_PRODUCT_PAGE");
        }
    }
}
