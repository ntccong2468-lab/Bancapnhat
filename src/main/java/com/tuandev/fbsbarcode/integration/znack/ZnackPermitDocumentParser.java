package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.GoodsDocument;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parses permit documents using the National Catalog v5.62 response contract. */
final class ZnackPermitDocumentParser {
    private static final Map<String, String> TYPES = Map.of(
            "23557", "CONFORMITY_DECLARATION",
            "23561", "CONFORMITY_CERTIFICATE",
            "23765", "STATE_REGISTRATION_CERTIFICATE");
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "действует",
            "подписан и действует",
            "возобновлен",
            "возобновлён",
            "продлен",
            "продлён",
            "ожидает проверки оператора реестра");

    private ZnackPermitDocumentParser() {
    }

    static List<GoodsDocument> fromProductCard(JsonObject card) {
        LinkedHashSet<GoodsDocument> documents = new LinkedHashSet<>();
        for (JsonObject attribute : ZnackProductCardAttributes.from(card)) {
            String type = TYPES.get(ZnackProductCardAttributes.id(attribute));
            if (type == null) continue;
            JsonObject displayed = object(attribute, "showValue");
            String number = text(displayed, "number");
            String date = text(displayed, "dateFrom", "date_from");
            if (number.isBlank()) number = text(attribute, "certificate_number", "certificateNumber");
            if (date.isBlank()) date = text(attribute, "certificate_issued_date", "certificateIssuedDate");
            if (number.isBlank() || date.isBlank()) {
                String[] legacy = text(attribute, "attr_value", "attrValue").split(":::", 2);
                if (legacy.length == 2) {
                    if (number.isBlank()) number = legacy[0].trim();
                    if (date.isBlank()) date = legacy[1].trim();
                }
            }
            addComplete(documents, type, number, date);
        }
        return List.copyOf(documents);
    }

    static boolean registryLookupPending(JsonElement response) {
        if (response == null || response.isJsonNull()) return false;
        if (response.isJsonObject()) {
            JsonObject object = response.getAsJsonObject();
            String code = text(object, "error_code", "errorCode", "code").strip();
            if ("18".equals(code) || "19".equals(code)) return true;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (registryLookupPending(entry.getValue())) return true;
            }
        } else if (response.isJsonArray()) {
            for (JsonElement element : response.getAsJsonArray()) {
                if (registryLookupPending(element)) return true;
            }
        }
        return false;
    }

    static List<GoodsDocument> activeFromRegistry(JsonElement response) {
        JsonArray entries = registryDocuments(response);
        if (entries == null) return List.of();
        LinkedHashSet<GoodsDocument> documents = new LinkedHashSet<>();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            JsonObject document = element.getAsJsonObject();
            if (!active(document)) continue;
            String type = TYPES.get(text(document, "attr_id", "attrId"));
            if (type == null) continue;
            addComplete(documents, type, text(document, "number", "certificate_number"),
                    text(document, "from_date", "certificate_issued_date"));
        }
        return List.copyOf(documents);
    }

    static List<GoodsDocument> selectForCirculation(List<GoodsDocument> documents) {
        if (documents == null || documents.isEmpty()) return List.of();
        LinkedHashSet<GoodsDocument> complete = new LinkedHashSet<>();
        for (GoodsDocument document : documents) {
            if (document != null && document.complete()) complete.add(document);
        }
        List<GoodsDocument> declarations = documentsOfType(complete, "CONFORMITY_DECLARATION");
        if (!declarations.isEmpty()) return declarations;
        List<GoodsDocument> certificates = documentsOfType(complete, "CONFORMITY_CERTIFICATE");
        if (!certificates.isEmpty()) return certificates;
        // Preserve the existing state-registration-document fallback for product groups that use it.
        return List.copyOf(complete);
    }

    private static List<GoodsDocument> documentsOfType(Set<GoodsDocument> documents, String type) {
        return documents.stream()
                .filter(document -> type.equalsIgnoreCase(document.type()))
                .toList();
    }

    private static JsonArray registryDocuments(JsonElement response) {
        if (response == null || response.isJsonNull()) return null;
        if (response.isJsonArray()) return response.getAsJsonArray();
        JsonObject root = response.getAsJsonObject();
        JsonObject result = object(root, "result");
        JsonArray documents = array(result == null ? root : result, "documents");
        return documents == null ? array(root, "documents") : documents;
    }

    private static boolean active(JsonObject document) {
        JsonElement group = value(document, "status_group", "statusGroup");
        Integer groupValue = null;
        if (group != null) {
            try {
                groupValue = group.getAsInt();
                if (groupValue != 1) return false;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        JsonElement active = value(document, "active");
        if (active != null && active.isJsonPrimitive() && active.getAsJsonPrimitive().isBoolean()) {
            if (!active.getAsBoolean()) return false;
            if (groupValue == null) return true;
        }
        if (groupValue != null) return true;
        return ACTIVE_STATUSES.contains(text(document, "status").strip().toLowerCase(Locale.ROOT));
    }

    private static void addComplete(Set<GoodsDocument> target, String type, String number, String date) {
        GoodsDocument document = new GoodsDocument(type, number == null ? "" : number.trim(),
                date == null ? "" : date.trim());
        if (document.complete()) target.add(document);
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : null;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : null;
    }

    private static JsonElement value(JsonObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) return object.get(key);
        }
        return null;
    }

    private static String text(JsonObject object, String... keys) {
        JsonElement value = value(object, keys);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
