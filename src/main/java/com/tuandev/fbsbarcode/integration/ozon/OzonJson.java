package com.tuandev.fbsbarcode.integration.ozon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Tolerant DTO parser: unknown Ozon fields are ignored and external numeric IDs remain strings. */
public final class OzonJson {
    private static final Set<String> SUPPORTED_REQUIREMENTS = Set.of(
            "products_requiring_mandatory_mark", "products_requiring_optional_mark");

    private OzonJson() {
    }

    public static ProductPage parseProductPage(JsonObject response) {
        JsonObject result = object(response, "result");
        JsonArray items = array(result, "items");
        List<ProductReference> references = new ArrayList<>();
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String productId = text(item, "product_id");
            if (productId.isBlank()) continue;
            references.add(new ProductReference(productId, text(item, "offer_id")));
        }
        return new ProductPage(List.copyOf(references), text(result, "last_id"), integer(result, "total", 0));
    }

    public static List<OzonProductDto> parseProductInfo(JsonObject response) {
        JsonArray items = array(response, "items");
        if (items.isEmpty()) items = array(object(response, "result"), "items");
        List<OzonProductDto> result = new ArrayList<>();
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String productId = text(item, "id");
            if (productId.isBlank()) productId = text(item, "product_id");
            if (productId.isBlank()) continue;
            List<String> barcodes = strings(item.get("barcodes"));
            String primaryImage = text(item, "primary_image");
            if (primaryImage.isBlank()) {
                List<String> images = strings(item.get("images"));
                primaryImage = images.isEmpty() ? "" : images.getFirst();
            }
            String sku = text(item, "sku");
            if (sku.isBlank()) sku = text(item, "fbo_sku");
            result.add(new OzonProductDto(
                    productId,
                    text(item, "offer_id"),
                    sku,
                    text(item, "name"),
                    primaryImage,
                    bool(item, "is_archived", false) || bool(item, "archived", false),
                    text(item, "updated_at"),
                    barcodes));
        }
        return List.copyOf(result);
    }

    public static PostingPage parsePostingPage(JsonObject response) {
        JsonObject result = object(response, "result");
        JsonArray postings = array(result, "postings");
        if (postings.isEmpty()) postings = array(response, "postings");
        List<OzonPostingDto> parsed = new ArrayList<>();
        for (JsonElement posting : postings) {
            if (posting.isJsonObject()) parsePosting(posting.getAsJsonObject()).ifPresent(parsed::add);
        }
        boolean hasNext = bool(result, "has_next", false) || bool(response, "has_next", false);
        String cursor = firstNonBlank(text(result, "cursor"), text(response, "cursor"));
        return new PostingPage(List.copyOf(parsed), hasNext, cursor);
    }

    public static OzonPostingDto parsePostingDetail(JsonObject response) {
        JsonObject result = object(response, "result");
        JsonObject candidate = result.size() == 0 ? response : result;
        return parsePosting(candidate).orElseThrow(() -> new IllegalArgumentException("Ozon posting response is invalid"));
    }

    private static java.util.Optional<OzonPostingDto> parsePosting(JsonObject posting) {
        String postingNumber = text(posting, "posting_number");
        if (postingNumber.isBlank()) return java.util.Optional.empty();
        JsonArray rawItems = array(posting, "products");
        if (rawItems.isEmpty()) rawItems = array(posting, "items");
        List<OzonPostingItemDto> items = new ArrayList<>();
        int index = 0;
        for (JsonElement element : rawItems) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            int quantity = integer(item, "quantity", 1);
            if (quantity <= 0) continue;
            String sku = text(item, "sku");
            String productId = text(item, "product_id");
            // FBS posting responses identify products by numeric SKU; exemplar endpoints call the
            // same value product_id. Keep it as text locally and only serialize it at the API edge.
            if (productId.isBlank()) productId = sku;
            items.add(new OzonPostingItemDto(
                    index++,
                    productId,
                    sku,
                    text(item, "offer_id"),
                    text(item, "name"),
                    quantity,
                    text(item, "currency_code"),
                    text(item, "price")));
        }
        OzonRequirements requirements = parseRequirements(
                object(posting, "requirements"), object(posting, "optional"), posting);
        List<String> actions = strings(posting.get("available_actions"));
        boolean shipAvailable = bool(posting, "ship_available", false)
                || actions.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(value -> switch (value) {
                    case "ship", "fbs_ship", "posting_ship", "ship_available" -> true;
                    default -> false;
                });
        JsonObject barcodes = object(posting, "barcodes");
        return java.util.Optional.of(new OzonPostingDto(
                postingNumber,
                text(posting, "order_id"),
                text(posting, "order_number"),
                text(posting, "status"),
                text(posting, "substatus"),
                firstNonBlank(text(posting, "warehouse_id"), text(object(posting, "delivery_method"), "warehouse_id")),
                text(posting, "shipment_date"),
                text(posting, "in_process_at"),
                text(barcodes, "lower_barcode"),
                text(barcodes, "upper_barcode"),
                requirements,
                actions,
                shipAvailable,
                items));
    }

    public static OzonRequirements parseRequirements(JsonObject requirements) {
        return parseRequirements(requirements, new JsonObject(), new JsonObject());
    }

    private static OzonRequirements parseRequirements(
            JsonObject requirements, JsonObject optional, JsonObject posting) {
        List<String> mandatory = strings(requirements.get("products_requiring_mandatory_mark"));
        List<String> optionalMarks = strings(optional.get("products_with_possible_mandatory_mark"));
        if (optionalMarks.isEmpty()) {
            optionalMarks = strings(requirements.get("products_requiring_optional_mark"));
        }
        Set<String> unsupported = new LinkedHashSet<>();
        for (var entry : requirements.entrySet()) {
            if (SUPPORTED_REQUIREMENTS.contains(entry.getKey()) || isEmptyRequirement(entry.getValue())) continue;
            unsupported.add(entry.getKey());
        }
        String integrationType = text(posting, "tpl_integration_type");
        if (!integrationType.isBlank() && !"ozon".equalsIgnoreCase(integrationType)) {
            unsupported.add("non_standard_fbs");
        }
        if (bool(posting, "is_multibox", false) || integer(posting, "multi_box_qty", 1) > 1) {
            unsupported.add("multibox_package");
        }
        return new OzonRequirements(mandatory, optionalMarks, List.copyOf(unsupported));
    }

    private static boolean isEmptyRequirement(JsonElement value) {
        if (value == null || value.isJsonNull()) return true;
        if (value.isJsonArray()) return value.getAsJsonArray().isEmpty();
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return !value.getAsBoolean();
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return value.getAsString().isBlank();
        return false;
    }

    static JsonObject object(JsonObject parent, String key) {
        if (parent == null) return new JsonObject();
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    static JsonArray array(JsonObject parent, String key) {
        if (parent == null) return new JsonArray();
        JsonElement value = parent.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    static String text(JsonObject parent, String key) {
        if (parent == null) return "";
        JsonElement value = parent.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return "";
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    static int integer(JsonObject parent, String key, int fallback) {
        try {
            JsonElement value = parent == null ? null : parent.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static boolean bool(JsonObject parent, String key, boolean fallback) {
        try {
            JsonElement value = parent == null ? null : parent.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static List<String> strings(JsonElement value) {
        if (value == null || value.isJsonNull()) return List.of();
        List<String> result = new ArrayList<>();
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) result.add(item.getAsString());
            }
        } else if (value.isJsonPrimitive()) {
            result.add(value.getAsString());
        }
        return result.stream().filter(item -> item != null && !item.isBlank()).map(String::strip).distinct().toList();
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    public record ProductReference(String productId, String offerId) {
    }

    public record ProductPage(List<ProductReference> items, String lastId, int total) {
    }

    public record PostingPage(List<OzonPostingDto> postings, boolean hasNext, String cursor) {
    }
}
