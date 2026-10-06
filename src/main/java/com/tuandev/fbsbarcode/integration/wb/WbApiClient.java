package com.tuandev.fbsbarcode.integration.wb;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WbApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbApiClient.class);
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final Gson GSON = new Gson();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(20))
            .writeTimeout(Duration.ofSeconds(20))
            .callTimeout(Duration.ofSeconds(30))
            .build();

    public WbProductCardsResponse getProductCards(String apiKey, String locale, String updatedAtCursor, Long nmIdCursor, int limit)
            throws IOException {
        Map<String, Object> cursor = new LinkedHashMap<>();
        cursor.put("limit", limit);
        if (updatedAtCursor != null && !updatedAtCursor.isBlank()) {
            cursor.put("updatedAt", updatedAtCursor);
        }
        if (nmIdCursor != null && nmIdCursor > 0) {
            cursor.put("nmID", nmIdCursor);
        }

        Map<String, Object> payload = Map.of(
                "settings", Map.of(
                        "sort", Map.of("ascending", true),
                        "filter", Map.of("withPhoto", -1),
                        "cursor", cursor
                )
        );

        String url = "https://content-api.wildberries.ru/content/v2/get/cards/list?locale=" + locale;
        return postJson(apiKey, url, payload, WbProductCardsResponse.class);
    }

    public WbSuppliesResponse getSupplies(String apiKey, long next, int limit) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/supplies?limit=" + limit + "&next=" + next;
        return getJson(apiKey, url, WbSuppliesResponse.class);
    }

    public WbOrdersResponse getNewOrders(String apiKey) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/orders/new";
        return getJson(apiKey, url, WbOrdersResponse.class);
    }

    public WbOrdersResponse getOrders(String apiKey, long next, int limit, Long dateFrom, Long dateTo) throws IOException {
        StringBuilder url = new StringBuilder("https://marketplace-api.wildberries.ru/api/v3/orders?limit=")
                .append(limit)
                .append("&next=")
                .append(next);
        if (dateFrom != null) {
            url.append("&dateFrom=").append(dateFrom);
        }
        if (dateTo != null) {
            url.append("&dateTo=").append(dateTo);
        }
        return getJson(apiKey, url.toString(), WbOrdersResponse.class);
    }

    public WbOrderStatusesResponse getOrderStatuses(String apiKey, List<Long> orderIds) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/orders/status";
        return postJson(apiKey, url, Map.of("orders", orderIds), WbOrderStatusesResponse.class);
    }

    public WbSupplyOrderIdsResponse getSupplyOrderIds(String apiKey, String supplyId) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/marketplace/v3/supplies/" + supplyId + "/order-ids";
        return getJson(apiKey, url, WbSupplyOrderIdsResponse.class);
    }

    public WbSupplyDto getSupplyDetail(String apiKey, String supplyId) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/supplies/" + supplyId;
        return getJson(apiKey, url, WbSupplyDto.class);
    }

    public WbCreateSupplyResponse createSupply(String apiKey, String name) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/supplies";
        return postJson(apiKey, url, Map.of("name", name), WbCreateSupplyResponse.class);
    }

    public void addOrdersToSupply(String apiKey, String supplyId, List<Long> orderIds) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/marketplace/v3/supplies/" + supplyId + "/orders";
        patchJson(apiKey, url, Map.of("orders", orderIds));
    }

    public void deliverSupply(String apiKey, String supplyId) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/supplies/" + supplyId + "/deliver";
        patchJson(apiKey, url, Map.of());
    }

    public void deleteSupply(String apiKey, String supplyId) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/supplies/" + supplyId;
        delete(apiKey, url);
    }

    public WbOrderMetaDetailsResponse getOrderMetadata(String apiKey, List<Long> orderIds) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/marketplace/v3/orders/meta";
        return postJson(apiKey, url, Map.of("orders", orderIds), WbOrderMetaDetailsResponse.class);
    }

    public WbCrossBorderStickerResponse getCrossBorderStickers(String apiKey, List<Long> orderIds, String type, int width, int height)
            throws IOException {
        String safeType = type == null || type.isBlank() ? "png" : type;
        String url = "https://marketplace-api.wildberries.ru/api/v3/orders/stickers/cross-border"
                + "?type=" + safeType + "&width=" + width + "&height=" + height;
        return postJson(apiKey, url, Map.of("orders", orderIds), WbCrossBorderStickerResponse.class);
    }

    public WbSupplyBoxesResponse getSupplyBoxes(String apiKey, String supplyId) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/supplies/" + supplyId + "/trbx";
        return getJson(apiKey, url, WbSupplyBoxesResponse.class);
    }

    public WbSupplyBoxesResponse addSupplyBoxes(String apiKey, String supplyId, int amount) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/supplies/" + supplyId + "/trbx";
        return postJson(apiKey, url, Map.of("amount", amount), WbSupplyBoxesResponse.class);
    }

    public void deleteSupplyBox(String apiKey, String supplyId, String trbxId) throws IOException {
        deleteSupplyBoxes(apiKey, supplyId, List.of(trbxId));
    }

    public void deleteSupplyBoxes(String apiKey, String supplyId, List<String> trbxIds) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/supplies/" + supplyId + "/trbx";
        deleteJson(apiKey, url, Map.of("trbxIds", trbxIds));
    }

    public WbSupplyBarcodeResponse getSupplyBoxStickers(String apiKey, String supplyId, List<String> trbxIds, String type, int width, int height)
            throws IOException {
        String safeType = type == null || type.isBlank() ? "png" : type;
        String url = "https://marketplace-api.wildberries.ru/api/v3/supplies/" + supplyId + "/trbx/stickers"
                + "?type=" + safeType + "&width=" + width + "&height=" + height;
        return postJson(apiKey, url, Map.of("trbxIds", trbxIds), WbSupplyBarcodeResponse.class);
    }

    public byte[] getSupplyBarcode(String apiKey, String supplyId, String type) throws IOException {
        String safeType = type == null || type.isBlank() ? "png" : type;
        String url = "https://marketplace-api.wildberries.ru/api/v3/supplies/" + supplyId + "/barcode?type=" + safeType;
        WbSupplyBarcodeResponse response = getJson(apiKey, url, WbSupplyBarcodeResponse.class);
        if (response == null || response.getFile() == null || response.getFile().isBlank()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(response.getFile());
    }

    private <T> T getJson(String apiKey, String url, Class<T> type) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        return execute(apiKey, request, type);
    }

    private <T> T postJson(String apiKey, String url, Object payload, Class<T> type) throws IOException {
        RequestBody body = RequestBody.create(GSON.toJson(payload), JSON);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();
        return execute(apiKey, request, type);
    }

    private void patchJson(String apiKey, String url, Object payload) throws IOException {
        RequestBody body = RequestBody.create(GSON.toJson(payload), JSON);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .patch(body)
                .build();
        execute(apiKey, request, Void.class);
    }

    private void deleteJson(String apiKey, String url, Object payload) throws IOException {
        RequestBody body = RequestBody.create(GSON.toJson(payload), JSON);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .delete(body)
                .build();
        execute(apiKey, request, Void.class);
    }

    private void delete(String apiKey, String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .delete()
                .build();
        execute(apiKey, request, Void.class);
    }

    private <T> T execute(String apiKey, Request request, Class<T> type) throws IOException {
        if (isContentApi(request.url())) {
            WbContentApiRateLimiter.awaitTurn(apiKey);
        } else if (isMarketplaceApi(request.url())) {
            WbMarketplaceApiRateLimiter.awaitTurn(apiKey);
        }
        try (Response response = CLIENT.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                if (isContentApiRateLimited(request.url(), response.code())) {
                    WbContentApiRateLimiter.registerRateLimit(apiKey, response.headers());
                } else if (isMarketplaceApiRateLimited(request.url(), response.code())) {
                    WbMarketplaceApiRateLimiter.registerRateLimit(apiKey, response.headers());
                }
                String message = extractErrorMessage(request.url(), response.code(), body);
                LOGGER.warn("WB API request failed: {} {} -> {} {}", request.method(), request.url(), response.code(), message);
                throw new WbApiException(message, response.code(), body);
            }
            if (type == Void.class) {
                return null;
            }
            try {
                return GSON.fromJson(body, type);
            } catch (com.google.gson.JsonSyntaxException ex) {
                LOGGER.error("GSON parsing failed for WB API response: {} {} as {}: {}",
                        request.method(), request.url(), type.getSimpleName(), ex.getMessage(), ex);
                throw ex;
            }
        } catch (InterruptedIOException ex) {
            throw new IOException("Wildberries system response timed out. Please try again.", ex);
        }
    }

    private String extractErrorMessage(HttpUrl url, int statusCode, String body) {
        if (isContentApiUnauthorized(url, statusCode)) {
            String code = extractJsonField(body, "code");
            String requestId = extractJsonField(body, "requestId");
            return compactMessage(
                    "WB Content API permission denied",
                    statusCode,
                    code,
                    requestId,
                    null
            );
        }
        if (isContentApiRateLimited(url, statusCode)) {
            String code = extractJsonField(body, "code");
            String requestId = extractJsonField(body, "requestId");
            String detail = extractJsonField(body, "detail");
            return compactMessage(
                    "WB Content API rate limited",
                    statusCode,
                    code,
                    requestId,
                    detail
            );
        }
        if (body == null || body.isBlank()) {
            return "WB API request failed";
        }
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            String metaValidation = extractMetaValidationMessage(statusCode, object);
            if (metaValidation != null) {
                return metaValidation;
            }
            if (object.has("message")) {
                return object.get("message").getAsString();
            }
            if (object.has("errorText")) {
                return object.get("errorText").getAsString();
            }
            if (object.has("code")) {
                return object.get("code").getAsString();
            }
        } catch (Exception ignored) {
            // fall back to raw body
        }
        return body;
    }

    private String extractMetaValidationMessage(int statusCode, JsonObject object) {
        if (statusCode != 409 || object == null) {
            return null;
        }
        String code = object.has("code") && !object.get("code").isJsonNull() ? object.get("code").getAsString() : "";
        if (!"MetaValidationFail".equalsIgnoreCase(code)) {
            return null;
        }
        StringBuilder message = new StringBuilder("WB metadata validation failed");
        if (object.has("message") && !object.get("message").isJsonNull()) {
            message.append(": ").append(object.get("message").getAsString());
        }
        if (object.has("metaDetails") && object.get("metaDetails").isJsonArray()) {
            List<String> details = new java.util.ArrayList<>();
            object.getAsJsonArray("metaDetails").forEach(element -> {
                if (!element.isJsonObject()) {
                    return;
                }
                JsonObject detail = element.getAsJsonObject();
                String key = detail.has("key") && !detail.get("key").isJsonNull()
                        ? detail.get("key").getAsString()
                        : detail.has("name") && !detail.get("name").isJsonNull()
                        ? detail.get("name").getAsString()
                        : "";
                String error = detail.has("error") && !detail.get("error").isJsonNull()
                        ? detail.get("error").getAsString()
                        : detail.has("status") && !detail.get("status").isJsonNull()
                        ? detail.get("status").getAsString()
                        : "";
                if (!key.isBlank() || !error.isBlank()) {
                    details.add(key + (error.isBlank() ? "" : "=" + error));
                }
            });
            if (!details.isEmpty()) {
                message.append(" (").append(String.join(", ", details)).append(")");
            }
        }
        return message.toString();
    }

    private String extractJsonField(String body, String fieldName) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            if (!object.has(fieldName) || object.get(fieldName).isJsonNull()) {
                return null;
            }
            return object.get(fieldName).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String compactMessage(String prefix, int statusCode, String code, String requestId, String detail) {
        StringBuilder message = new StringBuilder(prefix).append(" (HTTP ").append(statusCode).append(')');
        if (code != null && !code.isBlank()) {
            message.append(", code=").append(code);
        }
        if (requestId != null && !requestId.isBlank()) {
            message.append(", requestId=").append(requestId);
        }
        if (detail != null && !detail.isBlank()) {
            message.append(", detail=").append(detail);
        }
        return message.toString();
    }

    private boolean isContentApiUnauthorized(HttpUrl url, int statusCode) {
        if (url == null || (statusCode != 401 && statusCode != 403)) {
            return false;
        }
        return isContentApi(url);
    }

    private boolean isContentApiRateLimited(HttpUrl url, int statusCode) {
        return url != null
                && statusCode == 429
                && isContentApi(url);
    }

    private boolean isMarketplaceApiRateLimited(HttpUrl url, int statusCode) {
        return url != null
                && statusCode == 429
                && isMarketplaceApi(url);
    }

    private boolean isContentApi(HttpUrl url) {
        return url != null && "content-api.wildberries.ru".equalsIgnoreCase(url.host());
    }

    private boolean isMarketplaceApi(HttpUrl url) {
        return url != null && "marketplace-api.wildberries.ru".equalsIgnoreCase(url.host());
    }
}
