package com.tuandev.fbsbarcode.integration.ozon;

import com.google.gson.JsonObject;
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
        String cursor = state.find(shopId).productsLastId();
        int synced = 0;
        Map<OzonProductCardAttributeParser.CategoryKey, Map<String, String>> definitionCache = new LinkedHashMap<>();
        Map<OzonProductCardAttributeParser.CategoryKey, String> categoryNames = null;
        try {
            for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
                JsonObject response = api.listProducts(cursor, PAGE_SIZE);
                OzonJson.ProductPage page = OzonJson.parseProductPage(response);
                if (page.items().isEmpty()) {
                    products.upsertPage(shopId, List.of(), cursor);
                    return synced;
                }
                String next = page.lastId();
                if (next == null || next.isBlank() || Objects.equals(next, cursor)) {
                    throw new IOException("Ozon returned an invalid product cursor.");
                }
                List<String> ids = page.items().stream().map(OzonJson.ProductReference::productId).toList();
                List<OzonProductDto> detailed = OzonJson.parseProductInfo(api.productInfo(ids));
                List<OzonProductCardAttributeParser.Card> cards =
                        OzonProductCardAttributeParser.parseCards(api.productAttributes(ids));
                if (categoryNames == null && !cards.isEmpty()) {
                    categoryNames = OzonProductCategoryTree.parse(api.descriptionCategoryTree());
                }
                for (OzonProductCardAttributeParser.Card card : cards) {
                    if (definitionCache.containsKey(card.category())) continue;
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
                Map<String, OzonProductDto> byId = new LinkedHashMap<>();
                for (OzonProductDto product : detailed) byId.put(product.productId(), product);
                List<OzonProductDto> complete = new ArrayList<>();
                for (OzonJson.ProductReference reference : page.items()) {
                    OzonProductDto product = byId.getOrDefault(reference.productId(), new OzonProductDto(
                            reference.productId(), reference.offerId(), "", "", "", false, "", List.of()));
                    OzonProductCardAttributeParser.Resolved attributes = attributesById.get(reference.productId());
                    if (attributes != null) {
                        product = product.withCardAttributes(
                                attributes.article(), attributes.color(), attributes.size(),
                                attributes.category(), attributes.gender());
                    }
                    complete.add(product);
                }
                synced += products.upsertPage(shopId, complete, next);
                if (page.items().size() < PAGE_SIZE) return synced;
                cursor = next;
            }
            throw new IOException("Ozon catalog exceeded the safe pagination bound.");
        } catch (OzonApiException exception) {
            state.recordSafeError(shopId, exception.kind());
            throw exception;
        } catch (IOException exception) {
            state.recordSafeError(shopId, "invalid_response");
            throw exception;
        } catch (RuntimeException exception) {
            state.recordSafeError(shopId, "local_storage");
            throw exception;
        }
    }
}
