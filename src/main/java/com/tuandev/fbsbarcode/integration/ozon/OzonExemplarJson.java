package com.tuandev.fbsbarcode.integration.ozon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class OzonExemplarJson {
    private OzonExemplarJson() {
    }

    public static List<String> exemplarIds(JsonObject response) {
        List<String> ids = new ArrayList<>();
        collectIds(response, ids);
        return ids.stream().filter(value -> !value.isBlank()).distinct().toList();
    }

    public static Map<String, List<String>> exemplarIdsByProduct(JsonObject response) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        collectProductExemplars(response, result);
        result.replaceAll((ignored, ids) -> List.copyOf(ids));
        return Map.copyOf(result);
    }

    public static OzonExemplarRemoteStatus status(JsonObject response, int expectedCount) {
        return parseStatus(response, expectedCount, true);
    }

    public static OzonExemplarRemoteStatus validation(JsonObject response, int expectedCount) {
        return parseStatus(response, expectedCount, false);
    }

    private static OzonExemplarRemoteStatus parseStatus(
            JsonObject response, int expectedCount, boolean requirePassedRemoteMarks) {
        List<String> statuses = new ArrayList<>();
        collectStatuses(response, statuses);
        String full = response == null ? "" : response.toString().toLowerCase(Locale.ROOT);
        boolean has = hasNonEmptyArray(response, "marks")
                || full.contains("\"mandatory_mark\":\"")
                || full.contains("\"mark\":\"");
        boolean rejected = statuses.stream().anyMatch(OzonExemplarJson::rejectedStatus)
                || booleanCount(response, "valid", false) > 0
                || (requirePassedRemoteMarks && hasNonEmptyArray(response, "error_codes"))
                || (!requirePassedRemoteMarks && (hasNonEmptyArray(response, "errors")
                        || hasNonBlankString(response, "error")))
                || full.contains("validation_failed") || full.contains("invalid_mark");
        long passedMarks = statusCountInNamedArrays(response, "marks", true);
        long validExemplars = booleanCountInNamedArrays(response, "exemplars", "valid", true);
        boolean allPassed = !rejected && (expectedCount == 0
                || (requirePassedRemoteMarks ? passedMarks : validExemplars) >= expectedCount);
        boolean ship = booleanAnywhere(response, "ship_available")
                || "ship_available".equalsIgnoreCase(topLevelString(response, "status"));
        String safe = rejected ? "rejected" : allPassed ? (ship ? "accepted" : "passed") : "pending";
        return new OzonExemplarRemoteStatus(has, allPassed, rejected, ship, safe);
    }

    private static long statusCountInNamedArrays(JsonElement element, String key, boolean passed) {
        if (element == null || element.isJsonNull()) return 0;
        long count = 0;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement named = object.get(key);
            if (named != null && named.isJsonArray()) {
                for (JsonElement item : named.getAsJsonArray()) {
                    List<String> statuses = new ArrayList<>();
                    collectStatuses(item, statuses);
                    if (statuses.stream().anyMatch(passed
                            ? OzonExemplarJson::passedStatus
                            : OzonExemplarJson::rejectedStatus)) {
                        count++;
                    }
                }
            }
            for (var entry : object.entrySet()) {
                if (!key.equals(entry.getKey())) {
                    count += statusCountInNamedArrays(entry.getValue(), key, passed);
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                count += statusCountInNamedArrays(item, key, passed);
            }
        }
        return count;
    }

    private static void collectIds(JsonElement element, List<String> target) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if ("exemplar_id".equals(entry.getKey())
                        && entry.getValue().isJsonPrimitive()) {
                    String value = entry.getValue().getAsString();
                    if (value.length() <= 256) target.add(value);
                } else {
                    collectIds(entry.getValue(), target);
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) collectIds(value, target);
        }
    }

    private static void collectProductExemplars(JsonElement element, Map<String, List<String>> target) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement productId = object.get("product_id");
            JsonElement exemplars = object.get("exemplars");
            if (productId != null && productId.isJsonPrimitive()
                    && exemplars != null && exemplars.isJsonArray()) {
                String key = productId.getAsString();
                List<String> ids = new ArrayList<>();
                collectIds(exemplars, ids);
                if (!key.isBlank() && !ids.isEmpty()) {
                    target.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(ids);
                }
            }
            for (var entry : object.entrySet()) collectProductExemplars(entry.getValue(), target);
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) collectProductExemplars(item, target);
        }
    }

    private static void collectStatuses(JsonElement element, List<String> target) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (("status".equals(entry.getKey()) || "check_status".equals(entry.getKey())
                        || "mark_status".equals(entry.getKey())) && entry.getValue().isJsonPrimitive()) {
                    target.add(entry.getValue().getAsString().toLowerCase(Locale.ROOT));
                } else {
                    collectStatuses(entry.getValue(), target);
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) collectStatuses(value, target);
        }
    }

    private static boolean booleanAnywhere(JsonElement element, String key) {
        if (element == null || element.isJsonNull()) return false;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement direct = object.get(key);
            if (direct != null && direct.isJsonPrimitive()) {
                try {
                    if (direct.getAsBoolean()) return true;
                } catch (RuntimeException ignored) {
                    // Continue recursively.
                }
            }
            for (var entry : object.entrySet()) if (booleanAnywhere(entry.getValue(), key)) return true;
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) if (booleanAnywhere(item, key)) return true;
        }
        return false;
    }

    private static boolean hasNonEmptyArray(JsonElement element, String key) {
        if (element == null || element.isJsonNull()) return false;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement direct = object.get(key);
            if (direct != null && direct.isJsonArray() && !direct.getAsJsonArray().isEmpty()) return true;
            for (var entry : object.entrySet()) {
                if (hasNonEmptyArray(entry.getValue(), key)) return true;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (hasNonEmptyArray(item, key)) return true;
            }
        }
        return false;
    }

    private static boolean hasNonBlankString(JsonElement element, String key) {
        if (element == null || element.isJsonNull()) return false;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement direct = object.get(key);
            if (direct != null && direct.isJsonPrimitive()) {
                try {
                    if (!direct.getAsString().isBlank()) return true;
                } catch (RuntimeException ignored) {
                    // Ignore malformed values; they cannot make a result pass.
                }
            }
            for (var entry : object.entrySet()) {
                if (hasNonBlankString(entry.getValue(), key)) return true;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (hasNonBlankString(item, key)) return true;
            }
        }
        return false;
    }

    private static long booleanCountInNamedArrays(
            JsonElement element, String arrayKey, String booleanKey, boolean expected) {
        if (element == null || element.isJsonNull()) return 0;
        long count = 0;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement named = object.get(arrayKey);
            if (named != null && named.isJsonArray()) {
                for (JsonElement item : named.getAsJsonArray()) {
                    if (!item.isJsonObject()) continue;
                    JsonElement value = item.getAsJsonObject().get(booleanKey);
                    if (value == null || !value.isJsonPrimitive()) continue;
                    try {
                        if (value.getAsBoolean() == expected) count++;
                    } catch (RuntimeException ignored) {
                        // Ignore malformed values; they cannot make a result pass.
                    }
                }
            }
            for (var entry : object.entrySet()) {
                if (!arrayKey.equals(entry.getKey())) {
                    count += booleanCountInNamedArrays(entry.getValue(), arrayKey, booleanKey, expected);
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                count += booleanCountInNamedArrays(item, arrayKey, booleanKey, expected);
            }
        }
        return count;
    }

    private static long booleanCount(JsonElement element, String key, boolean expected) {
        if (element == null || element.isJsonNull()) return 0;
        long count = 0;
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (key.equals(entry.getKey()) && entry.getValue().isJsonPrimitive()) {
                    try {
                        if (entry.getValue().getAsBoolean() == expected) count++;
                    } catch (RuntimeException ignored) {
                        // Ignore malformed values; they cannot make a result pass.
                    }
                }
                count += booleanCount(entry.getValue(), key, expected);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) count += booleanCount(item, key, expected);
        }
        return count;
    }

    private static String topLevelString(JsonObject object, String key) {
        if (object == null) return "";
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static boolean passedStatus(String value) {
        return value.equals("passed") || value.equals("valid") || value.equals("accepted") || value.equals("success");
    }

    private static boolean rejectedStatus(String value) {
        return value.equals("rejected") || value.equals("invalid") || value.equals("failed") || value.equals("error");
    }
}
