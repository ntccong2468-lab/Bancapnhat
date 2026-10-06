package com.tuandev.fbsbarcode.integration.ozon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves the category/type IDs stored on an Ozon card to a human-readable product type. */
final class OzonProductCategoryTree {
    private OzonProductCategoryTree() {
    }

    static Map<OzonProductCardAttributeParser.CategoryKey, String> parse(JsonObject response) {
        JsonArray roots = OzonJson.array(response, "result");
        if (roots.isEmpty()) roots = OzonJson.array(OzonJson.object(response, "result"), "items");
        Map<OzonProductCardAttributeParser.CategoryKey, String> values = new LinkedHashMap<>();
        for (JsonElement root : roots) {
            if (root.isJsonObject()) collect(root.getAsJsonObject(), List.of(), values);
        }
        return Map.copyOf(values);
    }

    private static void collect(
            JsonObject node,
            List<String> ancestorCategoryIds,
            Map<OzonProductCardAttributeParser.CategoryKey, String> destination) {
        List<String> categoryIds = new ArrayList<>(ancestorCategoryIds);
        String categoryId = OzonJson.text(node, "description_category_id");
        if (!categoryId.isBlank() && !categoryIds.contains(categoryId)) categoryIds.add(categoryId);
        String typeId = OzonJson.text(node, "type_id");
        String typeName = OzonJson.text(node, "type_name");
        String categoryName = OzonJson.text(node, "category_name");
        String visibleName = typeName.isBlank() ? categoryName : typeName;
        if (!typeId.isBlank() && !visibleName.isBlank()) {
            for (String id : categoryIds) {
                destination.putIfAbsent(
                        new OzonProductCardAttributeParser.CategoryKey(id, typeId), visibleName);
            }
        }
        for (JsonElement child : OzonJson.array(node, "children")) {
            if (child.isJsonObject()) collect(child.getAsJsonObject(), categoryIds, destination);
        }
    }
}
