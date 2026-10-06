package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.features.kizmapping.ZnackKizLabelMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads physical-label attributes from the National Catalog product-card schema. */
final class ZnackProductLabelMetadataParser {
    private ZnackProductLabelMetadataParser() {
    }

    static ZnackKizLabelMetadata fromProductCard(JsonObject card) {
        if (card == null) return new ZnackKizLabelMetadata("", "", "");
        Candidate gender = Candidate.EMPTY;
        Candidate size = Candidate.EMPTY;
        for (JsonObject attribute : ZnackProductCardAttributes.from(card)) {
            String id = ZnackProductCardAttributes.id(attribute);
            String name = ZnackProductCardAttributes.name(attribute).strip();
            String value = attributeValue(attribute);
            if (value.isBlank()) continue;
            int genderScore = genderScore(id, name);
            if (genderScore > gender.score()) gender = new Candidate(value, genderScore);
            int sizeScore = sizeScore(id, name);
            if (sizeScore > size.score()) size = new Candidate(value, sizeScore);
        }
        return new ZnackKizLabelMetadata("", gender.value(), size.value());
    }

    private static int genderScore(String id, String name) {
        if ("14013".equals(id)) return 100;
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.equals("целевой пол") || normalized.equals("target gender")) return 90;
        if (normalized.equals("пол") || normalized.equals("gender")) return 80;
        return 0;
    }

    private static int sizeScore(String id, String name) {
        if ("35".equals(id)) return 100;
        if ("13886".equals(id)) return 90;
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("размер одежды") || normalized.equals("clothing size")) return 80;
        if (normalized.startsWith("размер") || normalized.equals("size")) return 60;
        return 0;
    }

    private static String attributeValue(JsonObject object) {
        for (String key : List.of("attr_value", "attrValue", "value")) {
            if (!object.has(key) || object.get(key).isJsonNull()) continue;
            JsonElement value = object.get(key);
            if (value.isJsonPrimitive()) return value.getAsString().strip();
            if (value.isJsonArray()) {
                List<String> values = new ArrayList<>();
                for (JsonElement item : value.getAsJsonArray()) {
                    if (item.isJsonPrimitive() && !item.getAsString().isBlank()) values.add(item.getAsString().strip());
                }
                return String.join(", ", values);
            }
        }
        return "";
    }

    private record Candidate(String value, int score) {
        private static final Candidate EMPTY = new Candidate("", 0);
    }
}
