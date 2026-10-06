package com.tuandev.fbsbarcode.integration.wb.fbw;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.integration.wb.WbApiException;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class WbFbwApiClient {
    public static final String DEFAULT_BASE_URL = "https://supplies-api.wildberries.ru/";
    private static final long MAX_RESPONSE_BYTES = 20L * 1024 * 1024;
    private static final MediaType JSON = Objects.requireNonNull(MediaType.parse("application/json"));
    private static final WbFbwRateLimiter SHARED_LIMITER = new WbFbwRateLimiter();

    private final int shopId;
    private final String token;
    private final HttpUrl baseUrl;
    private final OkHttpClient http;
    private final WbFbwRateLimiter limiter;

    public WbFbwApiClient(int shopId, String token) {
        this(shopId, token, Objects.requireNonNull(HttpUrl.parse(DEFAULT_BASE_URL)),
                new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .callTimeout(45, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false)
                        .build(), SHARED_LIMITER);
    }

    public WbFbwApiClient(int shopId, String token, HttpUrl baseUrl, OkHttpClient http, WbFbwRateLimiter limiter) {
        if (shopId <= 0) throw new IllegalArgumentException("shopId must be positive");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("WB token is required");
        this.shopId = shopId;
        this.token = token.strip();
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.http = Objects.requireNonNull(http, "http");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    public JsonArray listSupplies(int limit, int offset) throws IOException {
        if (limit < 1 || limit > 1000 || offset < 0) throw new IllegalArgumentException("Invalid FBW pagination");
        HttpUrl url = Objects.requireNonNull(baseUrl.resolve("api/v1/supplies")).newBuilder()
                .addQueryParameter("limit", Integer.toString(limit))
                .addQueryParameter("offset", Integer.toString(offset)).build();
        JsonElement result = execute(url, "POST", RequestBody.create(JSON, "{}"));
        if (!result.isJsonArray()) throw invalidResponse();
        return result.getAsJsonArray();
    }

    public JsonObject getSupply(String id, boolean preorderId) throws IOException {
        HttpUrl url = supplyUrl(id, null).newBuilder()
                .addQueryParameter("isPreorderID", Boolean.toString(preorderId)).build();
        JsonElement result = execute(url, "GET", null);
        if (!result.isJsonObject()) throw invalidResponse();
        return result.getAsJsonObject();
    }

    public JsonArray getGoods(String id, boolean preorderId, int limit, int offset) throws IOException {
        if (limit < 1 || limit > 1000 || offset < 0) throw new IllegalArgumentException("Invalid FBW goods pagination");
        HttpUrl url = supplyUrl(id, "goods").newBuilder()
                .addQueryParameter("limit", Integer.toString(limit))
                .addQueryParameter("offset", Integer.toString(offset))
                .addQueryParameter("isPreorderID", Boolean.toString(preorderId)).build();
        JsonElement result = execute(url, "GET", null);
        if (!result.isJsonArray()) throw invalidResponse();
        return result.getAsJsonArray();
    }

    private HttpUrl supplyUrl(String id, String suffix) {
        String safeId = id == null ? "" : id.strip();
        if (!safeId.matches("[1-9][0-9]{0,38}")) throw new IllegalArgumentException("Invalid FBW supply identifier");
        String relative = "api/v1/supplies/" + safeId + (suffix == null ? "" : "/" + suffix);
        return Objects.requireNonNull(baseUrl.resolve(relative));
    }

    private JsonElement execute(HttpUrl url, String method, RequestBody body) throws IOException {
        try {
            limiter.awaitTurn(shopId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("WB FBW request interrupted");
            failure.initCause(exception);
            throw failure;
        }
        Request.Builder request = new Request.Builder().url(url).header("Authorization", token);
        if ("POST".equals(method)) request.post(body); else request.get();
        try (Response response = http.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 429) limiter.registerRateLimit(shopId, response.headers());
                throw new WbApiException("WB FBW API request failed", response.code(), "");
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null || responseBody.contentLength() > MAX_RESPONSE_BYTES) throw invalidResponse();
            byte[] bytes = responseBody.bytes();
            if (bytes.length > MAX_RESPONSE_BYTES) throw invalidResponse();
            try {
                return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            } catch (RuntimeException exception) {
                throw invalidResponse();
            }
        }
    }

    private static WbApiException invalidResponse() {
        return new WbApiException("WB FBW API returned an invalid response", 0, "");
    }
}
