package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Flattens attributes from both documented feed cards and the catalog UI card schema. */
final class ZnackProductCardAttributes {
    private ZnackProductCardAttributes() {
    }

    static List<JsonObject> from(JsonObject card) {
        if (card == null) return List.of();
        List<JsonObject> attributes = new ArrayList<>();
        addObjects(attributes, array(card, "good_attrs", "goodAttrs"));

        JsonObject businessLayer = object(card, "businessLayer", "business_layer");
        JsonArray groups = array(businessLayer, "attrGroup", "attr_group");
        if (groups != null) {
            for (JsonElement groupElement : groups) {
                if (!groupElement.isJsonObject()) continue;
                addObjects(attributes, array(groupElement.getAsJsonObject(), "attributes", "attrs"));
            }
        }
        return List.copyOf(attributes);
    }

    static String id(JsonObject attribute) {
        return text(attribute, "attr_id", "attrId", "id");
    }

    static String name(JsonObject attribute) {
        return text(attribute, "attr_name", "attrName", "name");
    }

    static String text(JsonObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull() && object.get(key).isJsonPrimitive()) {
                return object.get(key).getAsString();
            }
        }
        return "";
    }

    static JsonObject object(JsonObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonObject()) return object.getAsJsonObject(key);
        }
        return null;
    }

    private static void addObjects(List<JsonObject> target, JsonArray source) {
        if (source == null) return;
        for (JsonElement element : source) {
            if (element.isJsonObject()) target.add(element.getAsJsonObject());
        }
    }

    private static JsonArray array(JsonObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonArray()) return object.getAsJsonArray(key);
        }
        return null;
    }
}
