package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.KizCode;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;

import java.time.Instant;
import java.util.List;

public class ZnackIntroductionReadinessService {
    private final ZnackApiClient api;
    private final ZnackAuthService auth;
    private final ZnackRepository repository;

    public ZnackIntroductionReadinessService(ZnackApiClient api, ZnackAuthService auth, ZnackRepository repository) {
        this.api = api;
        this.auth = auth;
        this.repository = repository;
    }

    public Readiness check(Settings settings, Product current, List<KizCode> codes) throws Exception {
        String token = auth.trueApiToken(settings);
        Product product = refreshProductCard(settings, token, current);
        repository.updateProductReadiness(product);
        if (codes == null || codes.isEmpty()) {
            return Readiness.waiting("No downloaded KIZ codes are available for introduction.");
        }

        int applied = 0;
        int introduced = 0;
        int pending = 0;
        boolean missingName = false;
        String pendingReason = null;
        for (int start = 0; start < codes.size(); start += ZnackApiClient.CISES_MAX_CODES_PER_REQUEST) {
            List<KizCode> batch = codes.subList(start,
                    Math.min(start + ZnackApiClient.CISES_MAX_CODES_PER_REQUEST, codes.size()));
            JsonArray request = new JsonArray();
            batch.forEach(code -> request.add(ZnackCisNormalizer.forTrueApi(code.rawCode())));
            CisBatch result = inspectCises(api.cisesInfo(settings.resolvedTrueApiBaseUrl(), token, request),
                    product.gtin(), batch.size());
            applied += result.applied();
            introduced += result.introduced();
            pending += result.pending();
            missingName |= result.missingName();
            if (pendingReason == null && result.reason() != null) pendingReason = result.reason();
        }
        if (introduced == codes.size()) return Readiness.introduced();
        if (introduced > 0 && introduced < codes.size() && pendingReason == null) {
            pendingReason = "The batch contains both APPLIED and already introduced KIZ codes.";
        }
        if (pending > 0 || applied + introduced != codes.size() || introduced > 0) {
            return Readiness.waiting(progressMessage(codes.size(), applied, introduced, pending, pendingReason));
        }
        if (!product.cardReadyForIntroduction()) {
            String missing = java.util.stream.Stream.of(
                            Boolean.TRUE.equals(product.goodMarkFlag()) ? null : "goodMarkFlag=true",
                            Boolean.TRUE.equals(product.goodTurnFlag()) ? null : "goodTurnFlag=true")
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.joining(", "));
            return Readiness.waiting("Product card is not ready: missing " + missing + ".");
        }
        return Readiness.ready(missingName ? "Product name is not available from Znack yet." : null);
    }

    private String progressMessage(int total, int applied, int introduced, int pending, String reason) {
        int ready = applied + introduced;
        StringBuilder message = new StringBuilder("True API readiness: ")
                .append(ready).append('/').append(total).append(" KIZ ready");
        if (introduced > 0) message.append(" (").append(introduced).append(" already introduced)");
        if (pending > 0) message.append(", ").append(pending).append(" pending");
        message.append('.');
        if (reason != null && !reason.isBlank()) message.append(' ').append(reason);
        return message.toString();
    }

    private Product refreshProductCard(Settings settings, String token, Product current) throws Exception {
        JsonElement response = api.productCards(settings.resolvedTrueApiBaseUrl(), token, current.gtin());
        JsonArray cards = array(response, "result");
        if (cards == null) {
            return withReadiness(current, null, null, "", "", current.productName());
        }
        for (JsonElement element : cards) {
            if (!element.isJsonObject()) continue;
            JsonObject card = element.getAsJsonObject();
            if (!matchesGtin(card, current.gtin())) continue;
            return withReadiness(current, bool(card, "goodMarkFlag", "good_mark_flag"),
                    bool(card, "goodTurnFlag", "good_turn_flag"),
                    text(card, "goodStatus", "good_status", "cardStatus", "status"),
                    text(card, "goodDetailedStatus", "good_detailed_status", "cardDetailedStatus"),
                    first(text(card, "good_name", "productName", "name"), current.productName()));
        }
        return withReadiness(current, null, null, "", "", current.productName());
    }

    private Product withReadiness(Product current, Boolean mark, Boolean turn, String status, String detailed,
                                  String name) {
        return new Product(current.gtin(), name, current.tnVed(), current.certificateType(),
                current.certificateNumber(), current.certificateDate(), current.productionDate(),
                mark, turn, status, detailed, current.category(), Instant.now(), current.cisType());
    }

    private boolean matchesGtin(JsonObject card, String expected) {
        String direct = text(card, "gtin", "productGtin");
        if (!direct.isBlank() && normalizedEquals(direct, expected)) return true;
        JsonArray identifiers = array(card, "identified_by");
        if (identifiers == null) return false;
        for (JsonElement element : identifiers) {
            if (!element.isJsonObject()) continue;
            JsonObject identifier = element.getAsJsonObject();
            String type = text(identifier, "type");
            if (!type.isBlank() && !"gtin".equalsIgnoreCase(type)) continue;
            if (normalizedEquals(text(identifier, "value", "gtin"), expected)) return true;
        }
        return false;
    }

    private CisBatch inspectCises(JsonElement response, String expectedGtin, int expectedCount) {
        JsonArray entries = new JsonArray();
        if (response != null && response.isJsonArray()) {
            response.getAsJsonArray().forEach(entries::add);
        } else if (response != null && response.isJsonObject()) {
            entries.add(response);
        } else {
            return new CisBatch(0, 0, expectedCount, false, "True API did not return a KIZ list.");
        }
        int applied = 0;
        int introduced = 0;
        int pending = 0;
        boolean missingName = false;
        String reason = null;
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            String error = text(entry, "errorMessage", "error_message", "errorCode", "error_code");
            JsonObject info = object(entry, "cisInfo");
            if (info == null && entry.has("status")) info = entry;
            if (info == null) {
                pending++;
                if (reason == null) reason = "True API did not return KIZ details.";
                continue;
            }
            if (!error.isBlank()) {
                pending++;
                if (reason == null) reason = "True API KIZ lookup is not ready: " + error;
                continue;
            }
            String gtin = text(info, "gtin");
            if (!gtin.isBlank() && !normalizedEquals(gtin, expectedGtin)) {
                pending++;
                if (reason == null) reason = "True API returned a KIZ for a different GTIN.";
                continue;
            }
            String status = text(info, "status");
            String statusEx = text(info, "statusEx", "status_ex");
            if ("INTRODUCED".equalsIgnoreCase(status)) introduced++;
            else if ("APPLIED".equalsIgnoreCase(status)) {
                if (!statusEx.isBlank() && !"EMPTY".equalsIgnoreCase(statusEx)) {
                    pending++;
                    if (reason == null) reason = "KIZ has a special status: " + statusEx;
                    continue;
                }
                applied++;
            } else {
                pending++;
                if (reason == null) reason = "KIZ is not APPLIED yet" + (status.isBlank() ? "." : ": " + status);
                continue;
            }
            String productName = text(info, "productName");
            if (productName.isBlank() || "-".equals(productName.trim())) missingName = true;
        }
        int missing = Math.max(0, expectedCount - applied - introduced - pending);
        if (missing > 0) {
            pending += missing;
            if (reason == null) reason = "Not all downloaded KIZ codes are visible in True API yet.";
        }
        return new CisBatch(applied, introduced, pending, missingName, reason);
    }

    private boolean normalizedEquals(String value, String expected) {
        try {
            return GtinNormalizer.normalize(value).equals(expected);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private JsonArray array(JsonElement value, String key) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonArray()) return value.getAsJsonArray();
        if (!value.isJsonObject()) return null;
        JsonElement nested = value.getAsJsonObject().get(key);
        return nested != null && nested.isJsonArray() ? nested.getAsJsonArray() : null;
    }

    private JsonObject object(JsonObject value, String key) {
        JsonElement nested = value.get(key);
        return nested != null && nested.isJsonObject() ? nested.getAsJsonObject() : null;
    }

    private Boolean bool(JsonObject value, String... keys) {
        for (String key : keys) {
            if (!value.has(key) || value.get(key).isJsonNull()) continue;
            JsonElement element = value.get(key);
            if (!element.isJsonPrimitive()) continue;
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            String text = primitive.getAsString();
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        }
        return null;
    }

    private String text(JsonObject value, String... keys) {
        for (String key : keys) {
            if (value.has(key) && !value.get(key).isJsonNull()) {
                JsonElement element = value.get(key);
                return element.isJsonPrimitive() ? element.getAsString() : element.toString();
            }
        }
        return "";
    }

    private String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() || "-".equals(preferred.trim()) ? fallback : preferred;
    }

    public record Readiness(boolean ready, boolean allIntroduced, String message) {
        static Readiness ready(String warning) { return new Readiness(true, false, warning); }
        static Readiness introduced() { return new Readiness(false, true, null); }
        static Readiness waiting(String reason) { return new Readiness(false, false, reason); }
    }

    private record CisBatch(int applied, int introduced, int pending, boolean missingName, String reason) {
    }
}
