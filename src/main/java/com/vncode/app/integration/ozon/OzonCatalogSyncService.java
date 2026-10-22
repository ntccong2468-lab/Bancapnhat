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
    /** User-requested refresh revisits already synced cards; cursor recovery remains available in sync(). */
    public int syncFromStart()throws IOException{return sync("");}
    public int syncForRegistration(java.util.function.Consumer<List<OzonProductDto>> page)throws IOException {return sync("",page);}
    private int sync(String initialCursor)throws IOException{return sync(initialCursor,page->{});}
    private int sync(String initialCursor,java.util.function.Consumer<List<OzonProductDto>> observer)throws IOException{
        LicenseRuntime.require(SELLER_API);
        api.requireSyncOwner(shopId);
        String cursor = initialCursor;
        int synced = 0,listed=0;
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
                    if(page.total()>listed)throw new IOException("OZON_INCOMPLETE_PRODUCT_PAGE");
                    products.upsertPage(shopId, List.of(), cursor);
                    return synced;
                }
                String next = page.lastId();
                if (next == null || next.isBlank() || !cursors.add(next)) {
                    throw new IOException("OZON_REPEATED_PRODUCT_CURSOR");
                }
                List<String> ids = page.items().stream().map(OzonJson.ProductReference::productId).toList();
                LicenseRuntime.require(SELLER_API);
                List<OzonProductDto> detailed = OzonJson.parseProductInfo(api.productInfo(ids));
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
                synced += products.upsertPage(shopId, complete, next);
                observer.accept(List.copyOf(complete));
                listed+=page.items().size();
                if (page.items().size() < PAGE_SIZE && (page.total()==0||listed>=page.total())) return synced;
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
}
