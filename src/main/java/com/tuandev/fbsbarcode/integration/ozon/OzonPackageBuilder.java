package com.tuandev.fbsbarcode.integration.ozon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** MVP package builder: all current quantities go into exactly one complete package. */
public final class OzonPackageBuilder {
    private OzonPackageBuilder() {
    }

    public static JsonObject singleCompletePackage(OzonPostingDto posting) {
        if (posting == null || !posting.isSinglePackageSupported()) {
            throw new OzonRequirementGuard.UnsupportedRequirementException(
                    java.util.List.of("partial_or_multibox_package"));
        }
        // Ship every current item in one package. Ozon expects product_id as an int64 at the API
        // edge even though WCode deliberately stores all external identifiers as TEXT.
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (OzonPostingItemDto item : posting.items()) {
            if (item.productId().isBlank()) {
                throw new IllegalStateException("Ozon product id is required before shipping.");
            }
            long productId;
            try {
                productId = Long.parseLong(item.productId());
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Ozon product id must be a positive int64 before shipping.", exception);
            }
            if (productId <= 0) {
                throw new IllegalStateException("Ozon product id must be a positive int64 before shipping.");
            }
            quantities.merge(productId, item.quantity(), Math::addExact);
        }
        JsonObject request = new JsonObject();
        request.addProperty("posting_number", posting.postingNumber());
        JsonArray products = new JsonArray();
        quantities.forEach((productId, quantity) -> {
            JsonObject product = new JsonObject();
            product.addProperty("product_id", productId);
            product.addProperty("quantity", quantity);
            products.add(product);
        });
        JsonObject singlePackage = new JsonObject();
        singlePackage.add("products", products);
        JsonArray packages = new JsonArray();
        packages.add(singlePackage);
        request.add("packages", packages);
        JsonObject with = new JsonObject();
        with.addProperty("additional_data", false);
        request.add("with", with);
        return request;
    }
}
