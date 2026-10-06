package com.tuandev.fbsbarcode.integration.ozon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves printable variants from Ozon product-card attributes and their category metadata. */
final class OzonProductCardAttributeParser {
    private OzonProductCardAttributeParser() {
    }

    static List<Card> parseCards(JsonObject response) {
        JsonArray result = OzonJson.array(response, "result");
        if (result.isEmpty()) result = OzonJson.array(OzonJson.object(response, "result"), "items");
        List<Card> cards = new ArrayList<>();
        for (JsonElement element : result) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String productId = first(OzonJson.text(item, "id"), OzonJson.text(item, "product_id"));
            String descriptionCategoryId = OzonJson.text(item, "description_category_id");
            String typeId = OzonJson.text(item, "type_id");
            if (productId.isBlank() || descriptionCategoryId.isBlank() || typeId.isBlank()) continue;
            Map<String, List<String>> values = new LinkedHashMap<>();
            collectAttributes(OzonJson.array(item, "attributes"), values);
            for (JsonElement complex : OzonJson.array(item, "complex_attributes")) {
                if (complex.isJsonObject()) {
                    collectAttributes(OzonJson.array(complex.getAsJsonObject(), "attributes"), values);
                }
            }
            cards.add(new Card(
                    productId, OzonJson.text(item, "offer_id"),
                    new CategoryKey(descriptionCategoryId, typeId), Map.copyOf(values)));
        }
        return List.copyOf(cards);
    }

    static Map<String, String> parseDefinitionNames(JsonObject response) {
        JsonArray result = OzonJson.array(response, "result");
        if (result.isEmpty()) result = OzonJson.array(OzonJson.object(response, "result"), "attributes");
        Map<String, String> names = new LinkedHashMap<>();
        for (JsonElement element : result) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = OzonJson.text(item, "id");
            String name = OzonJson.text(item, "name");
            if (!id.isBlank() && !name.isBlank()) names.put(id, name);
        }
        return Map.copyOf(names);
    }

    static Resolved resolve(Card card, Map<String, String> definitionNames) {
        return resolve(card, definitionNames, "");
    }

    static Resolved resolve(Card card, Map<String, String> definitionNames, String category) {
        Map<String, String> names = definitionNames == null ? Map.of() : definitionNames;
        String article = best(card, names, OzonProductCardAttributeParser::articleScore);
        String color = best(card, names, OzonProductCardAttributeParser::colorScore);
        String size = best(card, names, OzonProductCardAttributeParser::sizeScore);
        String gender = best(card, names, OzonProductCardAttributeParser::genderScore);
        return new Resolved(card.productId(), first(article, card.offerId()), color, size,
                category == null ? "" : category.strip(), gender);
    }

    private static String best(Card card, Map<String, String> names, Scorer scorer) {
        int bestScore = 0;
        String best = "";
        for (var entry : card.attributeValues().entrySet()) {
            int score = scorer.score(normalize(names.get(entry.getKey())));
            if (score <= bestScore || entry.getValue().isEmpty()) continue;
            String value = entry.getValue().stream().filter(item -> item != null && !item.isBlank())
                    .map(String::strip).distinct().reduce((left, right) -> left + ", " + right).orElse("");
            if (value.isBlank()) continue;
            bestScore = score;
            best = value;
        }
        return best;
    }

    private static int articleScore(String name) {
        if (name.equals("код продавца") || name.equals("seller code")) return 100;
        if (name.contains("артикул продавца") || name.contains("seller article")) return 90;
        return 0;
    }

    private static int colorScore(String name) {
        if (name.equals("цвет товара") || name.equals("product color")) return 100;
        if (name.equals("название цвета") || name.equals("color name")) return 90;
        if ((name.contains("цвет") || name.contains("color") || name.contains("mau"))
                && !name.contains("изображ") && !name.contains("image")) return 50;
        return 0;
    }

    private static int sizeScore(String name) {
        if (name.equals("российский размер") || name.equals("russian size")) return 100;
        if ((name.contains("размер") || name.contains("size"))
                && (name.contains(" ru") || name.endsWith(" ru") || name.contains("россий"))) return 95;
        if (name.equals("размер производителя") || name.equals("manufacturer size")) return 85;
        if (name.equals("размер") || name.equals("size")) return 80;
        if (name.contains("размер на модели") || name.contains("model size")
                || name.contains("таблиц") || name.contains("table")
                || name.contains("упаков") || name.contains("package")
                || name.contains("изображ") || name.contains("image")) return 0;
        return name.contains("размер") || name.contains("size") ? 50 : 0;
    }

    private static int genderScore(String name) {
        if (name.equals("пол") || name.equals("gender") || name.equals("sex")) return 100;
        if (name.equals("пол ребенка") || name.equals("child gender")) return 90;
        if (name.contains("пол") && !name.contains("полимер") && !name.contains("получ")) return 60;
        return 0;
    }

    private static void collectAttributes(JsonArray attributes, Map<String, List<String>> destination) {
        for (JsonElement element : attributes) {
            if (!element.isJsonObject()) continue;
            JsonObject attribute = element.getAsJsonObject();
            String id = OzonJson.text(attribute, "id");
            if (id.isBlank()) continue;
            List<String> values = new ArrayList<>();
            for (JsonElement valueElement : OzonJson.array(attribute, "values")) {
                if (!valueElement.isJsonObject()) continue;
                String value = OzonJson.text(valueElement.getAsJsonObject(), "value");
                if (!value.isBlank()) values.add(value);
            }
            if (!values.isEmpty()) destination.put(id, List.copyOf(values));
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null ? "" : fallback.strip()) : preferred.strip();
    }

    record CategoryKey(String descriptionCategoryId, String typeId) {
    }

    record Card(String productId, String offerId, CategoryKey category, Map<String, List<String>> attributeValues) {
    }

    record Resolved(String productId, String article, String color, String size, String category, String gender) {
    }

    @FunctionalInterface
    private interface Scorer {
        int score(String normalizedName);
    }
}
