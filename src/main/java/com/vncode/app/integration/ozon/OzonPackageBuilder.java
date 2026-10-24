package com.vncode.app.integration.ozon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds complete Ozon shipping requests from the current posting quantities. */
public final class OzonPackageBuilder {
    /** Application safety bound, not a claim about an Ozon API maximum. */
    public static final int MAX_UNIT_PACKAGES = 100;

    private OzonPackageBuilder() {
    }

    /**
     * One physical unit per package, as documented by PostingAPI_ShipFbsPostingV4.
     * The complete selection is validated before any request JSON is constructed.
     */
    public static JsonObject perUnitPackages(OzonPostingDto posting) {
        unitCount(posting);
        JsonArray packages = new JsonArray();
        for (OzonPostingItemDto item : posting.items()) {
            long sku = shippingSku(item);
            for (int unit = 0; unit < item.quantity(); unit++) {
                JsonObject product = new JsonObject();
                product.addProperty("product_id", sku);
                product.addProperty("quantity", 1);
                JsonArray products = new JsonArray();
                products.add(product);
                JsonObject pack = new JsonObject();
                pack.add("products", products);
                packages.add(pack);
            }
        }
        JsonObject request = new JsonObject();
        request.addProperty("posting_number", posting.postingNumber());
        request.add("packages", packages);
        JsonObject with = new JsonObject();
        with.addProperty("additional_data", true);
        request.add("with", with);
        return request;
    }

    /** Validated physical-unit total for the same complete request. */
    public static int unitCount(OzonPostingDto posting) {
        if (posting == null || !posting.isSinglePackageSupported()) {
            throw new OzonRequirementGuard.UnsupportedRequirementException(
                    java.util.List.of("partial_or_multibox_package"));
        }
        if (posting.requirements().blocksPreparation()) {
            throw new OzonRequirementGuard.UnsupportedRequirementException(
                    posting.requirements().unsupportedRequirements());
        }
        int total = 0;
        for (OzonPostingItemDto item : posting.items()) {
            if (item.quantity() <= 0) {
                throw new IllegalStateException("OZON_SPLIT_INVALID_QUANTITY");
            }
            shippingSku(item);
            try {
                total = Math.addExact(total, item.quantity());
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException("OZON_SPLIT_UNIT_LIMIT", overflow);
            }
            if (total > MAX_UNIT_PACKAGES) {
                throw new IllegalStateException("OZON_SPLIT_UNIT_LIMIT");
            }
        }
        return total;
    }

    private static long shippingSku(OzonPostingItemDto item) {
        // product_id in the ship endpoint means Ozon SKU, unlike the seller catalog ID.
        String value = item.sku().isBlank() ? item.productId() : item.sku();
        if (!value.matches("[0-9]{1,19}")) {
            throw new IllegalStateException("OZON_SPLIT_INVALID_SKU");
        }
        try {
            long sku = Long.parseLong(value);
            if (sku > 0) return sku;
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("OZON_SPLIT_INVALID_SKU", invalid);
        }
        throw new IllegalStateException("OZON_SPLIT_INVALID_SKU");
    }

    public static JsonObject singleCompletePackage(OzonPostingDto posting) {
        if (posting == null || !posting.isSinglePackageSupported()) {
            throw new OzonRequirementGuard.UnsupportedRequirementException(
                    java.util.List.of("partial_or_multibox_package"));
        }
        // Ship every current item in one package. Ozon expects product_id as an int64 at the API
        // edge even though VN code deliberately stores all external identifiers as TEXT.
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (OzonPostingItemDto item : posting.items()) {
            long productId = shippingSku(item);
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
