package com.tuandev.fbsbarcode.integration.ozon;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Bounded, redacted Ozon Seller API client. Mutations are never automatically retried. */
public final class OzonApiClient {
    public static final String DEFAULT_BASE_URL = "https://api-seller.ozon.ru/";
    private static final MediaType JSON = Objects.requireNonNull(MediaType.parse("application/json"));
    private static final long MAX_JSON_BYTES = 20L * 1024 * 1024;
    private static final long MAX_DOCUMENT_BYTES = 100L * 1024 * 1024;
    private static final long MAX_ERROR_BYTES = 256L * 1024;
    private static final Gson GSON = new Gson();
    private static final OzonApiRateLimiter SHARED_LIMITER = new OzonApiRateLimiter();

    private final int shopId;
    private final OzonCredentials credentials;
    private final HttpUrl baseUrl;
    private final OkHttpClient http;
    private final OzonApiRateLimiter limiter;
    private final OzonRetryPolicy retryPolicy;

    public OzonApiClient(int shopId, OzonCredentials credentials) {
        this(
                shopId,
                credentials,
                Objects.requireNonNull(HttpUrl.parse(DEFAULT_BASE_URL)),
                new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(25, TimeUnit.SECONDS)
                        .writeTimeout(25, TimeUnit.SECONDS)
                        .callTimeout(40, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false)
                        .build(),
                SHARED_LIMITER,
                new OzonRetryPolicy());
    }

    public OzonApiClient(
            int shopId,
            OzonCredentials credentials,
            HttpUrl baseUrl,
            OkHttpClient http,
            OzonApiRateLimiter limiter,
            OzonRetryPolicy retryPolicy) {
        if (shopId <= 0) {
            throw new IllegalArgumentException("shopId must be positive");
        }
        this.shopId = shopId;
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.http = Objects.requireNonNull(http, "http");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    public OzonConnectionCheck checkConnection() throws IOException {
        JsonObject roles = postJson("v1/roles", new JsonObject(), "identity");
        JsonObject warehousesRequest = new JsonObject();
        warehousesRequest.addProperty("limit", 1);
        JsonObject warehouses = postJson("v2/warehouse/list", warehousesRequest, "identity");
        JsonArray roleItems = firstArray(roles, "roles", "result");
        JsonArray warehouseItems = firstArray(warehouses, "warehouses", "result");
        String expiresAt = findFirstString(roles, "expires_at");
        String roleText = roleItems.toString().toLowerCase(java.util.Locale.ROOT);
        return new OzonConnectionCheck(
                credentials.clientId(),
                roleItems.size(),
                warehouseItems.size(),
                expiresAt,
                roleText.contains("exemplar"),
                roleText.contains("posting/fbs/ship") || roleText.contains("fbs"),
                roleText.contains("package-label") || roleText.contains("label"));
    }

    public JsonObject listProducts(String lastId, int limit) throws IOException {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Ozon product page limit must be between 1 and 1000");
        }
        JsonObject filter = new JsonObject();
        JsonObject request = new JsonObject();
        request.add("filter", filter);
        request.addProperty("last_id", lastId == null ? "" : lastId);
        request.addProperty("limit", limit);
        return postJson("v3/product/list", request, "catalog");
    }

    public JsonObject productInfo(List<String> productIds) throws IOException {
        JsonArray ids = boundedStrings(productIds, 1000, "product id");
        JsonObject request = new JsonObject();
        request.add("product_id", ids);
        return postJson("v3/product/info/list", request, "catalog");
    }

    public JsonObject productAttributes(List<String> productIds) throws IOException {
        JsonArray ids = boundedStrings(productIds, 1000, "product id");
        JsonObject filter = new JsonObject();
        filter.add("product_id", ids);
        JsonObject request = new JsonObject();
        request.add("filter", filter);
        request.addProperty("limit", ids.size());
        return postJson("v4/product/info/attributes", request, "catalog");
    }

    public JsonObject descriptionCategoryAttributes(String descriptionCategoryId, String typeId)
            throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("description_category_id", numericId(descriptionCategoryId, "description category id"));
        request.addProperty("type_id", numericId(typeId, "product type id"));
        request.addProperty("language", "DEFAULT");
        return postJson("v1/description-category/attribute", request, "catalog");
    }

    public JsonObject descriptionCategoryTree() throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("language", "DEFAULT");
        return postJson("v1/description-category/tree", request, "catalog");
    }

    public JsonObject listPostings(String since, String to, String cursor, int limit) throws IOException {
        if (since == null || since.isBlank() || to == null || to.isBlank()) {
            throw new IllegalArgumentException("A bounded Ozon posting window is required");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid Ozon posting pagination");
        }
        JsonObject filter = new JsonObject();
        filter.addProperty("since", since);
        filter.addProperty("to", to);
        JsonObject with = new JsonObject();
        with.addProperty("analytics_data", false);
        with.addProperty("barcodes", false);
        with.addProperty("financial_data", false);
        with.addProperty("legal_info", false);
        JsonObject request = new JsonObject();
        request.add("filter", filter);
        request.add("with", with);
        request.addProperty("sort_dir", "asc");
        request.addProperty("cursor", cursor == null ? "" : cursor);
        request.addProperty("limit", limit);
        return postJson("v4/posting/fbs/list", request, "postings");
    }

    public JsonObject listUnfulfilledPostings(
            String cutoffFrom, String cutoffTo, String cursor, int limit) throws IOException {
        if (cutoffFrom == null || cutoffFrom.isBlank() || cutoffTo == null || cutoffTo.isBlank()) {
            throw new IllegalArgumentException("A bounded Ozon cutoff window is required");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid Ozon posting pagination");
        }
        JsonObject filter = new JsonObject();
        filter.addProperty("cutoff_from", cutoffFrom);
        filter.addProperty("cutoff_to", cutoffTo);
        JsonObject with = new JsonObject();
        with.addProperty("analytics_data", false);
        with.addProperty("barcodes", false);
        with.addProperty("financial_data", false);
        with.addProperty("legal_info", false);
        JsonObject request = new JsonObject();
        request.add("filter", filter);
        request.add("with", with);
        request.addProperty("sort_dir", "asc");
        request.addProperty("translit", false);
        request.addProperty("cursor", cursor == null ? "" : cursor);
        request.addProperty("limit", limit);
        return postJson("v4/posting/fbs/unfulfilled/list", request, "postings");
    }

    public JsonObject getPosting(String postingNumber, boolean withExemplars) throws IOException {
        JsonObject with = new JsonObject();
        with.addProperty("analytics_data", false);
        with.addProperty("financial_data", false);
        with.addProperty("product_exemplars", withExemplars);
        JsonObject request = new JsonObject();
        request.addProperty("posting_number", requireExternalId(postingNumber, "posting number"));
        request.add("with", with);
        return postJson("v3/posting/fbs/get", request, "postings");
    }

    public JsonObject countFboSupplyOrders() throws IOException {
        return postJson("v1/supply-order/status/counter", new JsonObject(), "fbo-supplies");
    }

    public JsonObject listFboSupplyOrders(List<String> states, String lastId, int limit) throws IOException {
        if (states == null || states.isEmpty() || states.size() > 20) {
            throw new IllegalArgumentException("A bounded list of Ozon FBO states is required");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Ozon FBO page limit must be between 1 and 100");
        }
        JsonArray stateValues = new JsonArray();
        for (String state : states) {
            String safe = requireExternalId(state, "FBO state");
            if (!safe.matches("[A-Z][A-Z0-9_]{1,63}") || safe.startsWith("ORDER_STATE_")) {
                throw new IllegalArgumentException("Invalid Ozon FBO state");
            }
            stateValues.add(safe);
        }
        JsonObject filter = new JsonObject();
        filter.add("states", stateValues);
        JsonObject request = new JsonObject();
        request.add("filter", filter);
        request.addProperty("last_id", lastId == null ? "" : requireCursor(lastId));
        request.addProperty("limit", limit);
        request.addProperty("sort_by", "ORDER_STATE_UPDATED_AT");
        request.addProperty("sort_dir", "DESC");
        return postJson("v3/supply-order/list", request, "fbo-supplies");
    }

    public JsonObject getFboSupplyOrders(List<String> orderIds) throws IOException {
        JsonObject request = new JsonObject();
        request.add("order_ids", boundedStrings(orderIds, 50, "FBO order id"));
        return postJson("v3/supply-order/get", request, "fbo-supplies");
    }

    public JsonObject getFboSupplyBundle(List<String> bundleIds, String lastId, int limit) throws IOException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Ozon FBO bundle page limit must be between 1 and 100");
        }
        JsonObject request = new JsonObject();
        request.add("bundle_ids", boundedStrings(bundleIds, 100, "FBO bundle id"));
        request.addProperty("is_asc", true);
        request.addProperty("last_id", lastId == null ? "" : requireCursor(lastId));
        request.addProperty("limit", limit);
        request.addProperty("query", "");
        request.addProperty("sort_field", "SKU");
        return postJson("v1/supply-order/bundle", request, "fbo-supplies");
    }

    public JsonObject createOrGetExemplars(JsonObject request) throws IOException {
        return mutateJson("v6/fbs/posting/product/exemplar/create-or-get", request, "exemplars");
    }

    public JsonObject validateExemplars(JsonObject request) throws IOException {
        return postJson("v5/fbs/posting/product/exemplar/validate", request, "exemplars");
    }

    public JsonObject setExemplars(JsonObject request) throws IOException {
        return mutateJson("v6/fbs/posting/product/exemplar/set", request, "exemplars");
    }

    public JsonObject exemplarStatus(JsonObject request) throws IOException {
        return postJson("v5/fbs/posting/product/exemplar/status", request, "exemplars");
    }

    public JsonObject ship(JsonObject request) throws IOException {
        return mutateJson("v4/posting/fbs/ship", request, "ship");
    }

    public JsonObject createLabelJob(JsonObject request) throws IOException {
        return mutateJson("v2/posting/fbs/package-label/create", request, "labels");
    }

    public JsonObject getLabelJob(JsonObject request) throws IOException {
        return postJson("v1/posting/fbs/package-label/get", request, "labels");
    }

    public byte[] downloadOfficialDocument(String rawUrl) throws IOException {
        HttpUrl url = rawUrl == null || rawUrl.length() > 8192 ? null : HttpUrl.parse(rawUrl);
        boolean testOrigin = url != null
                && !"api-seller.ozon.ru".equalsIgnoreCase(baseUrl.host())
                && baseUrl.host().equalsIgnoreCase(url.host())
                && baseUrl.scheme().equalsIgnoreCase(url.scheme());
        if (url == null || (!testOrigin && (!"https".equalsIgnoreCase(url.scheme()) || !isOzonDocumentHost(url.host())))) {
            throw new OzonApiException("invalid_response", 0, false, false, null);
        }
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/pdf")
                .get()
                .build();
        OkHttpClient documentClient = http.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        try (Response response = documentClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new OzonApiException(
                        errorKind(response.code()), response.code(), false, false, null);
            }
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_DOCUMENT_BYTES) {
                throw new OzonApiException("invalid_response", response.code(), false, false, null);
            }
            byte[] bytes = body.bytes();
            if (bytes.length > MAX_DOCUMENT_BYTES) {
                throw new OzonApiException("response_too_large", response.code(), false, false, null);
            }
            return bytes;
        }
    }

    public JsonObject postJson(String path, JsonObject body, String family) throws IOException {
        byte[] bytes = execute(path, body, family, false, MAX_JSON_BYTES);
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw invalidResponse();
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw invalidResponse(exception);
        }
    }

    public JsonObject mutateJson(String path, JsonObject body, String family) throws IOException {
        byte[] bytes = execute(path, body, family, true, MAX_JSON_BYTES);
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw invalidResponse(null, true);
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw invalidResponse(exception, true);
        }
    }

    private byte[] execute(String path, JsonObject body, String family, boolean mutation, long maxBytes)
            throws IOException {
        String safePath = requirePath(path);
        String safeFamily = requireFamily(family);
        int attempts = mutation || "fbo-supplies".equals(safeFamily) ? 1 : retryPolicy.maximumAttempts();
        IOException lastFailure = null;
        Duration retryAfter = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                sleep(retryPolicy.delay(attempt - 1, retryAfter));
                retryAfter = null;
            }
            try {
                limiter.awaitTurn(shopId, safeFamily);
                return executeOnce(safePath, body, mutation, maxBytes, attempt, attempts);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new OzonApiException("cancelled", 0, false, mutation, exception);
            } catch (OzonApiException exception) {
                lastFailure = exception;
                retryAfter = exception.retryAfter();
                if ("rate_limited".equals(exception.kind())) {
                    limiter.registerRateLimit(shopId, safeFamily, retryAfter);
                }
                if (mutation || !exception.retryable() || attempt >= attempts) {
                    throw exception;
                }
            } catch (InterruptedIOException exception) {
                lastFailure = new OzonApiException("timeout", 0, true, mutation, exception);
                if (mutation || attempt >= attempts) {
                    throw lastFailure;
                }
            } catch (IOException exception) {
                lastFailure = new OzonApiException("unavailable", 0, true, mutation, exception);
                if (mutation || attempt >= attempts) {
                    throw lastFailure;
                }
            }
        }
        throw lastFailure == null
                ? new OzonApiException("upstream", 0, true, mutation, null)
                : lastFailure;
    }

    private byte[] executeOnce(
            String path, JsonObject body, boolean mutation, long maxBytes, int attempt, int attempts)
            throws IOException {
        HttpUrl url = Objects.requireNonNull(baseUrl.resolve(path));
        Request request = new Request.Builder()
                .url(url)
                .header("Client-Id", credentials.clientId())
                .header("Api-Key", credentials.apiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, GSON.toJson(body == null ? new JsonObject() : body)))
                .build();
        try (Response response = http.newCall(request).execute()) {
            int status = response.code();
            if (!response.isSuccessful()) {
                boolean retryable = retryPolicy.isRetryableStatus(status);
                Duration retryAfter = !mutation && retryable
                        ? OzonRetryPolicy.parseRetryAfter(response.header("Retry-After")) : null;
                String upstreamCode = safeUpstreamCode(response.body());
                throw new OzonApiException(
                        errorKind(status), status, retryable, mutation && retryable, null, retryAfter, upstreamCode);
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return new byte[0];
            }
            long length = responseBody.contentLength();
            if (length > maxBytes) {
                throw new OzonApiException("response_too_large", status, false, mutation, null);
            }
            byte[] bytes = responseBody.bytes();
            if (bytes.length > maxBytes) {
                throw new OzonApiException("response_too_large", status, false, mutation, null);
            }
            return bytes;
        }
    }

    private static String errorKind(int status) {
        if (status == 401 || status == 403) return "credentials";
        if (status == 429) return "rate_limited";
        if (status == 400 || status == 404 || status == 409 || status == 422) return "invalid_request";
        return "upstream";
    }

    private static OzonApiException invalidResponse() {
        return invalidResponse(null);
    }

    private static OzonApiException invalidResponse(Throwable cause) {
        return invalidResponse(cause, false);
    }

    private static OzonApiException invalidResponse(Throwable cause, boolean ambiguousMutation) {
        return new OzonApiException("invalid_response", 0, false, ambiguousMutation, cause);
    }

    private static String safeUpstreamCode(ResponseBody body) {
        if (body == null || body.contentLength() > MAX_ERROR_BYTES) return null;
        try (java.io.InputStream stream = body.byteStream()) {
            byte[] bytes = stream.readNBytes((int) MAX_ERROR_BYTES + 1);
            if (bytes.length > MAX_ERROR_BYTES) return null;
            JsonElement parsed = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            String code = findSafeErrorCode(parsed);
            if (code != null && !code.startsWith("CODE_")) return code;
            String classified = classifySafeError(parsed.toString());
            return classified == null ? code : classified;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String findSafeErrorCode(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                if (("code".equals(key) || "error_code".equals(key) || "errorcode".equals(key))
                        && entry.getValue().isJsonPrimitive()) {
                    String value = entry.getValue().getAsString().strip().toUpperCase(java.util.Locale.ROOT);
                    if (value.matches("[A-Z][A-Z0-9_:-]{0,63}")) return value;
                    if (value.matches("-?[0-9]{1,10}")) return "CODE_" + value.replace('-', 'N');
                }
            }
            for (var entry : element.getAsJsonObject().entrySet()) {
                String nested = findSafeErrorCode(entry.getValue());
                if (nested != null) return nested;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                String nested = findSafeErrorCode(child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    /** Converts an upstream message to a small allowlisted diagnostic vocabulary. */
    private static String classifySafeError(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.toLowerCase(java.util.Locale.ROOT);
        List<String> tags = new java.util.ArrayList<>();
        tag(tags, text, "GTD", "gtd", "гтд");
        tag(tags, text, "RNPT", "rnpt", "рнпт");
        tag(tags, text, "MULTIBOX", "multi_box", "multibox");
        tag(tags, text, "EXEMPLAR", "exemplar", "экземпляр");
        tag(tags, text, "PRODUCT", "product_id", "product id", "товар");
        tag(tags, text, "MARK_TYPE", "mark_type");
        tag(tags, text, "MARK", "mandatory_mark", "marking code", "код маркиров", "маркировк");
        tag(tags, text, "POSTING", "posting_number", "posting number", "отправлен");
        tag(tags, text, "NOT_BELONG", "does not belong", "not belong", "не принадлеж");
        tag(tags, text, "REDUNDANT", "redundant", "избыточ");
        tag(tags, text, "REQUIRED", "required", "обязател");
        tag(tags, text, "INVALID", "invalid", "incorrect", "некоррект", "неверн");
        tag(tags, text, "EMPTY", "empty", "пуст");
        if (tags.isEmpty()) return null;
        String result = "ERR_" + String.join("_", tags);
        return result.length() <= 64 ? result : result.substring(0, 64);
    }

    private static void tag(List<String> target, String text, String tag, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                target.add(tag);
                return;
            }
        }
    }

    private static String requirePath(String path) {
        if (path == null || !path.matches("[a-zA-Z0-9][a-zA-Z0-9/_-]{1,159}")) {
            throw new IllegalArgumentException("Invalid Ozon API path");
        }
        return path;
    }

    private static String requireFamily(String family) {
        if (family == null || !family.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("Invalid Ozon endpoint family");
        }
        return family;
    }

    private static boolean isOzonDocumentHost(String host) {
        String normalized = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("ozone.ru") || normalized.endsWith(".ozone.ru")
                || normalized.equals("ozon.ru") || normalized.endsWith(".ozon.ru");
    }

    static String requireExternalId(String value, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()
                || normalized.length() > 256
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A valid Ozon " + label + " is required");
        }
        return normalized;
    }

    private static BigInteger numericId(String value, String label) {
        String normalized = requireExternalId(value, label);
        if (!normalized.matches("[1-9][0-9]{0,38}")) {
            throw new IllegalArgumentException("Ozon " + label + " must be a positive numeric identifier");
        }
        return new BigInteger(normalized);
    }

    private static String requireCursor(String value) {
        String cursor = value == null ? "" : value.strip();
        if (cursor.length() > 2048 || cursor.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid Ozon cursor");
        }
        return cursor;
    }

    private static JsonArray boundedStrings(List<String> values, int maximum, String label) {
        if (values == null || values.isEmpty() || values.size() > maximum) {
            throw new IllegalArgumentException("A bounded list of Ozon " + label + " values is required");
        }
        JsonArray result = new JsonArray();
        for (String value : values) {
            result.add(requireExternalId(value, label));
        }
        return result;
    }

    private static JsonArray firstArray(JsonObject source, String directKey, String resultKey) {
        JsonElement direct = source.get(directKey);
        if (direct != null && direct.isJsonArray()) {
            return direct.getAsJsonArray();
        }
        JsonElement result = source.get(resultKey);
        if (result != null && result.isJsonArray()) {
            return result.getAsJsonArray();
        }
        if (result != null && result.isJsonObject()) {
            JsonObject object = result.getAsJsonObject();
            JsonElement nested = object.get(directKey);
            if (nested != null && nested.isJsonArray()) {
                return nested.getAsJsonArray();
            }
        }
        return new JsonArray();
    }

    private static String findFirstString(JsonElement element, String key) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement found = object.get(key);
            if (found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isString()) {
                String value = found.getAsString();
                return value.length() <= 80 ? value : "";
            }
            for (var entry : object.entrySet()) {
                String nested = findFirstString(entry.getValue(), key);
                if (!nested.isEmpty()) return nested;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                String nested = findFirstString(item, key);
                if (!nested.isEmpty()) return nested;
            }
        }
        return "";
    }

    private static void sleep(Duration duration) throws InterruptedIOException {
        if (duration == null || duration.isZero() || duration.isNegative()) return;
        try {
            Thread.sleep(Math.min(duration.toMillis(), 60_000));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Ozon request interrupted");
            interrupted.initCause(exception);
            throw interrupted;
        }
    }
}
