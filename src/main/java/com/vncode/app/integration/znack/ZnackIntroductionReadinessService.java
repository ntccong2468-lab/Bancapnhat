package com.vncode.app.integration.znack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.vncode.app.integration.znack.ZnackModels.KizCode;
import com.vncode.app.integration.znack.ZnackModels.Product;
import com.vncode.app.integration.znack.ZnackModels.Settings;

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
        if(product!=null)repository.updateProductReadiness(product);
        if (codes == null || codes.isEmpty()) {
            return Readiness.waiting("No downloaded KIZ codes are available for introduction.");
        }

        if(!ZnackCisReadback.validRequests(codes,current.gtin(),null))return Readiness.waiting("Chưa xác minh đầy đủ từng mã KIZ từ Честный знак; giữ nguyên kết quả đã lưu và chờ đối chiếu.");
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
            ZnackCisReadback.Result result = ZnackCisReadback.inspect(api.cisesInfo(settings.resolvedTrueApiBaseUrl(), token, request),
                    batch, current.gtin());
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
        if(product==null)return Readiness.waiting("Chưa xác minh được trạng thái thẻ sản phẩm hiện tại; giữ nguyên dữ liệu đã lưu và chờ đối chiếu.");
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
            return null;
        }
        for (JsonElement element : cards) {
            if (!element.isJsonObject()) continue;
            JsonObject card = element.getAsJsonObject();
            if (!matchesGtin(card, current.gtin())) continue;
            Boolean mark=bool(card,"goodMarkFlag","good_mark_flag"),turn=bool(card,"goodTurnFlag","good_turn_flag");
            if(mark==null||turn==null)return null;
            return withReadiness(current, mark, turn,
                    text(card, "goodStatus", "good_status", "cardStatus", "status"),
                    text(card, "goodDetailedStatus", "good_detailed_status", "cardDetailedStatus"),
                    first(text(card, "good_name", "productName", "name"), current.productName()));
        }
        return null;
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

}
