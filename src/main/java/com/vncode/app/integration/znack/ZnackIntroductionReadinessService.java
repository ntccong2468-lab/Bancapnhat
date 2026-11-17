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

    public Readiness checkCirculation(Settings settings, Product current, List<KizCode> codes) throws Exception {
        if(current==null||!ZnackCisReadback.validRequests(codes,current.gtin(),null))return Readiness.waiting("KIZ_CIRCULATION_IDENTITY_UNVERIFIED");
        // Authentication and routing use the original paid batch, not changed shop metadata.
        ZnackPurchaseContext context=ZnackPurchaseProtocolContext.resolve(repository,settings,codes.getFirst().orderId());
        settings=context.settings();
        final Settings original=settings;
        String owner=expectedOwner(settings);
        if(owner==null||!owner.matches("[0-9]{10}|[0-9]{12}"))return Readiness.waiting("KIZ_CIRCULATION_OWNER_UNVERIFIED");
        int introduced=0;String reason=null;
        for(int start=0;start<codes.size();start+=ZnackApiClient.CISES_MAX_CODES_PER_REQUEST){
            var batch=codes.subList(start,Math.min(start+ZnackApiClient.CISES_MAX_CODES_PER_REQUEST,codes.size()));
            var request=new JsonArray();batch.forEach(code->request.add(ZnackCisNormalizer.forTrueApi(code.rawCode())));
            JsonElement response=auth.readWithTrueApiRecovery(original,token->api.cisesInfo(
                    original.resolvedTrueApiBaseUrl(),token,request,context.group().apiCode()));
            var result=ZnackCisReadback.inspectOwned(response,batch,current.gtin(),owner);
            introduced+=result.introduced();if(reason==null&&result.reason()!=null)reason=result.reason();
        }
        if(introduced==codes.size())return Readiness.introduced();
        return Readiness.waiting("Đã xác minh "+introduced+"/"+codes.size()+" mã đã lưu thông. "
                +(reason==null?"Честный знак chưa xác nhận toàn bộ mã đã lưu thông.":reason));
    }

    public Readiness check(Settings settings, Product current, List<KizCode> codes) throws Exception {
        if (codes == null || codes.isEmpty()) {
            return Readiness.waiting("No downloaded KIZ codes are available for introduction.");
        }
        ZnackPurchaseContext context=ZnackPurchaseProtocolContext.resolve(repository,settings,codes.getFirst().orderId());
        settings=context.settings();
        final Settings original=settings;
        if(current==null)return Readiness.waiting("KIZ_CIRCULATION_IDENTITY_UNVERIFIED");
        if(!ZnackCisReadback.validRequests(codes,current.gtin(),null))return Readiness.waiting("Chưa xác minh đầy đủ từng mã KIZ từ Честный знак; giữ nguyên kết quả đã lưu và chờ đối chiếu.");
        int applied = 0;
        int introduced = 0;
        int ownedIntroduced = 0;
        String owner = expectedOwner(settings);
        int pending = 0;
        boolean missingName = false;
        String pendingReason = null;
        for (int start = 0; start < codes.size(); start += ZnackApiClient.CISES_MAX_CODES_PER_REQUEST) {
            List<KizCode> batch = codes.subList(start,
                    Math.min(start + ZnackApiClient.CISES_MAX_CODES_PER_REQUEST, codes.size()));
            JsonArray request = new JsonArray();
            batch.forEach(code -> request.add(ZnackCisNormalizer.forTrueApi(code.rawCode())));
            JsonElement response=auth.readWithTrueApiRecovery(original,token->api.cisesInfo(
                    original.resolvedTrueApiBaseUrl(),token,request,context.group().apiCode()));
            ZnackCisReadback.Result result = ZnackCisReadback.inspect(response, batch, current.gtin());
            applied += result.applied();
            introduced += result.introduced();
            if (result.introduced() > 0) {
                ownedIntroduced += ZnackCisReadback.inspectOwned(response, batch, current.gtin(), owner).introduced();
            }
            pending += result.pending();
            missingName |= result.missingName();
            if (pendingReason == null && result.reason() != null) pendingReason = result.reason();
        }
        if (introduced == codes.size()) {
            // A circulating code owned by another enterprise is not this purchase's stock.
            // Reuse the exact response above; no new document, purchase or read is authorized.
            return ownedIntroduced == codes.size() ? Readiness.introduced()
                    : Readiness.waiting("KIZ_CIRCULATION_OWNER_UNVERIFIED");
        }
        if (introduced > 0 && introduced < codes.size() && pendingReason == null) {
            pendingReason = "The batch contains both APPLIED and already introduced KIZ codes.";
        }
        if (pending > 0 || applied + introduced != codes.size() || introduced > 0) {
            return Readiness.waiting(progressMessage(codes.size(), applied, introduced, pending, pendingReason));
        }
        // The code's authoritative circulation status is sufficient on its own.
        // Catalog readiness is needed only before submitting a new introduction.
        Product product = refreshProductCard(settings, current);
        if(product!=null)repository.updateProductReadiness(product);
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

    private String expectedOwner(Settings settings) {
        return settings.ownerInn() == null || settings.ownerInn().isBlank()
                ? auth.resolvedParticipantInn(settings) : settings.ownerInn();
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

    private Product refreshProductCard(Settings settings, Product current) throws Exception {
        JsonElement response=auth.readWithTrueApiRecovery(settings,token->api.productCards(
                settings.resolvedTrueApiBaseUrl(),token,current.gtin()));
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
