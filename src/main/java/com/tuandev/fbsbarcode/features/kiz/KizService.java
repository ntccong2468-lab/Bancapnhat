package com.tuandev.fbsbarcode.features.kiz;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.models.Kiz;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventoryService;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class KizService {
    private static final Logger LOGGER = LoggerFactory.getLogger(KizService.class);
    private static final char GS = 0x1D;
    private static final int METADATA_BATCH_SIZE = 100;
    private static final int MAX_ATTACH_ATTEMPTS = 4;
    private static final long ATTACH_BASE_RETRY_DELAY_MS = 400L;
    private static final long ATTACH_REQUEST_SPACING_MS = 150L;
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final Gson gson = new Gson();

    public static String extractGtin(String code) {
        String safeCode = scannerSafeCode(code);
        if (safeCode == null || safeCode.length() < 16 || !safeCode.startsWith("01")) {
            return "";
        }
        return safeCode.substring(2, 16);
    }

    public static void deleteKizs(int shopId, List<Kiz> kizList) {
        new ZnackGtinInventoryService().consume(shopId, kizList);
    }

    public static void releaseKizs(int shopId, List<Kiz> kizList) {
        new ZnackGtinInventoryService().release(shopId, kizList);
    }

    public static AttachCodeResult addDataMatrixCodeToOrder(String apiKey, Long orderId, String code) throws IOException {
        AttachCodeResult lastResult = null;
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTACH_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                sleepQuietly(retryDelayForAttempt(attempt));
            }

            try {
                AttachCodeResult result = sendAttachCodeRequest(apiKey, orderId, code);
                lastResult = result;
                if (result.success()) {
                    sleepQuietly(ATTACH_REQUEST_SPACING_MS);
                    return result;
                }
                if (!isRetryableStatus(result.statusCode())) {
                    sleepQuietly(ATTACH_REQUEST_SPACING_MS);
                    return result;
                }
                LOGGER.warn("WB attach KIZ retrying for order {} after status {} on attempt {}/{}",
                        orderId, result.statusCode(), attempt, MAX_ATTACH_ATTEMPTS);
            } catch (IOException ex) {
                lastException = ex;
                if (attempt >= MAX_ATTACH_ATTEMPTS) {
                    throw ex;
                }
                LOGGER.warn("WB attach KIZ retrying for order {} after IO error on attempt {}/{}",
                        orderId, attempt, MAX_ATTACH_ATTEMPTS, ex);
            }
        }

        if (lastResult != null) {
            sleepQuietly(ATTACH_REQUEST_SPACING_MS);
            return lastResult;
        }
        if (lastException != null) {
            throw lastException;
        }
        return new AttachCodeResult(false, 0, "WB attach KIZ failed");
    }

    public static RemoveMetaResult removeSgtinFromOrder(String apiKey, Long orderId) throws IOException {
        RemoveMetaResult lastResult = null;
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTACH_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                sleepQuietly(retryDelayForAttempt(attempt));
            }

            try {
                RemoveMetaResult result = sendRemoveSgtinRequest(apiKey, orderId);
                lastResult = result;
                if (result.success()) {
                    sleepQuietly(ATTACH_REQUEST_SPACING_MS);
                    return result;
                }
                if (!isRetryableStatus(result.statusCode())) {
                    sleepQuietly(ATTACH_REQUEST_SPACING_MS);
                    return result;
                }
                LOGGER.warn("WB remove KIZ retrying for order {} after status {} on attempt {}/{}",
                        orderId, result.statusCode(), attempt, MAX_ATTACH_ATTEMPTS);
            } catch (IOException ex) {
                lastException = ex;
                if (attempt >= MAX_ATTACH_ATTEMPTS) {
                    throw ex;
                }
                LOGGER.warn("WB remove KIZ retrying for order {} after IO error on attempt {}/{}",
                        orderId, attempt, MAX_ATTACH_ATTEMPTS, ex);
            }
        }

        if (lastResult != null) {
            sleepQuietly(ATTACH_REQUEST_SPACING_MS);
            return lastResult;
        }
        if (lastException != null) {
            throw lastException;
        }
        return new RemoveMetaResult(false, 0, "WB remove KIZ failed");
    }

    public static String scannerSafeCode(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        int index = 0;
        while (index < code.length() && code.charAt(index) == GS) {
            index++;
        }
        return index == 0 ? code : code.substring(index);
    }

    public static Map<Long, SgtinMetadata> getSgtinMetadata(String apiKey, List<Long> orderIds) throws IOException {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, SgtinMetadata> metadataByOrderId = new HashMap<>();
        for (int start = 0; start < orderIds.size(); start += METADATA_BATCH_SIZE) {
            List<Long> batch = orderIds.subList(start, Math.min(start + METADATA_BATCH_SIZE, orderIds.size()));
            metadataByOrderId.putAll(requestSgtinMetadataBatch(apiKey, batch));
        }
        return metadataByOrderId;
    }

    private static Map<Long, SgtinMetadata> requestSgtinMetadataBatch(String apiKey, List<Long> orderIds) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/marketplace/v3/orders/meta";
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("orders", orderIds);

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), gson.toJson(bodyMap));
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        WbLabelIdentifierRateLimiter.awaitTurn(apiKey);
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            WbLabelIdentifierRateLimiter.registerResponse(apiKey, response.code(), response.headers());
            if (!response.isSuccessful()) {
                LOGGER.warn("WB metadata request failed for {} orders with status {} and body {}",
                        orderIds.size(), response.code(), responseBody);
                throw new IOException("WB metadata request failed: HTTP " + response.code() + " " + responseBody);
            }
            return parseSgtinMetadata(responseBody);
        } catch (InterruptedIOException ex) {
            throw new IOException("Wildberries system response timed out. Please try again.", ex);
        }
    }

    private static Map<Long, SgtinMetadata> parseSgtinMetadata(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Map.of();
        }
        Map<Long, SgtinMetadata> metadataByOrderId = new HashMap<>();
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray orders = root.has("orders") && root.get("orders").isJsonArray()
                ? root.getAsJsonArray("orders")
                : new JsonArray();
        for (JsonElement orderElement : orders) {
            if (!orderElement.isJsonObject()) {
                continue;
            }
            JsonObject orderObject = orderElement.getAsJsonObject();
            if (!orderObject.has("id") || orderObject.get("id").isJsonNull()) {
                continue;
            }
            long orderId = orderObject.get("id").getAsLong();
            boolean available = hasSgtinMetaObject(orderObject) || hasSgtinMetaDetail(orderObject);
            String appliedValue = firstSgtinValue(orderObject);
            metadataByOrderId.put(orderId, new SgtinMetadata(available, appliedValue));
        }
        return metadataByOrderId;
    }

    private static boolean hasSgtinMetaObject(JsonObject orderObject) {
        if (!orderObject.has("meta") || !orderObject.get("meta").isJsonObject()) {
            return false;
        }
        JsonObject meta = orderObject.getAsJsonObject("meta");
        return meta.has("sgtin") && meta.get("sgtin").isJsonObject();
    }

    private static boolean hasSgtinMetaDetail(JsonObject orderObject) {
        if (!orderObject.has("metaDetails") || !orderObject.get("metaDetails").isJsonArray()) {
            return false;
        }
        for (JsonElement detailElement : orderObject.getAsJsonArray("metaDetails")) {
            if (!detailElement.isJsonObject()) {
                continue;
            }
            JsonObject detail = detailElement.getAsJsonObject();
            if (detail.has("key") && "sgtin".equalsIgnoreCase(detail.get("key").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String firstSgtinValue(JsonObject orderObject) {
        if (!orderObject.has("meta") || !orderObject.get("meta").isJsonObject()) {
            return "";
        }
        JsonObject meta = orderObject.getAsJsonObject("meta");
        if (!meta.has("sgtin") || !meta.get("sgtin").isJsonObject()) {
            return "";
        }
        JsonObject sgtin = meta.getAsJsonObject("sgtin");
        if (!sgtin.has("value") || sgtin.get("value").isJsonNull()) {
            return "";
        }
        JsonElement value = sgtin.get("value");
        if (value.isJsonArray()) {
            JsonArray values = value.getAsJsonArray();
            if (values.isEmpty() || values.get(0).isJsonNull()) {
                return "";
            }
            return values.get(0).getAsString();
        }
        return value.getAsString();
    }

    private static AttachCodeResult sendAttachCodeRequest(String apiKey, Long orderId, String code) throws IOException {
        String url = "https://marketplace-api.wildberries.ru/api/v3/orders/" + orderId + "/meta/sgtin";

        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("sgtins", List.of(scannerSafeCode(code)));

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), gson.toJson(bodyMap));

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .put(body)
                .build();

        WbLabelIdentifierRateLimiter.awaitTurn(apiKey);
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            WbLabelIdentifierRateLimiter.registerResponse(apiKey, response.code(), response.headers());
            if (!response.isSuccessful()) {
                if (response.code() == 409 && responseBody.contains("FailedToUpdateMeta")) {
                    LOGGER.info("WB skipped KIZ attach for order {} because it is not in Processing status: {}", orderId, responseBody);
                } else {
                    LOGGER.warn("WB attach KIZ failed for order {} with status {} and body {}", orderId, response.code(), responseBody);
                }
            }
            return new AttachCodeResult(response.isSuccessful(), response.code(), responseBody);
        } catch (InterruptedIOException ex) {
            throw new IOException("Wildberries system response timed out. Please try again.", ex);
        }
    }

    private static RemoveMetaResult sendRemoveSgtinRequest(String apiKey, Long orderId) throws IOException {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse("https://marketplace-api.wildberries.ru/api/v3/orders/" + orderId + "/meta"))
                .newBuilder()
                .addQueryParameter("key", "sgtin")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .delete()
                .build();

        WbLabelIdentifierRateLimiter.awaitTurn(apiKey);
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            WbLabelIdentifierRateLimiter.registerResponse(apiKey, response.code(), response.headers());
            if (!response.isSuccessful()) {
                LOGGER.warn("WB remove KIZ failed for order {} with status {} and body {}", orderId, response.code(), responseBody);
            }
            return new RemoveMetaResult(response.isSuccessful(), response.code(), responseBody);
        } catch (InterruptedIOException ex) {
            throw new IOException("Wildberries system response timed out. Please try again.", ex);
        }
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static long retryDelayForAttempt(int attempt) {
        long multiplier = 1L << Math.max(0, attempt - 2);
        return ATTACH_BASE_RETRY_DELAY_MS * multiplier;
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public record AttachCodeResult(boolean success, int statusCode, String responseBody) {
    }

    public record RemoveMetaResult(boolean success, int statusCode, String responseBody) {
    }

    public record SgtinMetadata(boolean available, String appliedValue) {
        public boolean hasAppliedValue() {
            return appliedValue != null && !appliedValue.isBlank();
        }
    }
}
