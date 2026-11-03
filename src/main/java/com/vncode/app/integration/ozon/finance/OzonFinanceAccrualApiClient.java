package com.vncode.app.integration.ozon.finance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.integration.ozon.OzonCredentials;
import com.vncode.app.shared.BoundedHttpBody;
import com.vncode.app.shared.MarketplaceRequestBudget;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only client for Ozon's beta accrual endpoints; no persistence or automatic retries.
 * The official schema documents that a daily cursor is tied to its date and lives 15 minutes;
 * callers must handle expiry, missing cursors, pagination completeness and duplicate identities.
 * Schema: https://docs.ozon.ru/api/seller/swagger.json
 */
public final class OzonFinanceAccrualApiClient {
    private static final MediaType JSON = Objects.requireNonNull(MediaType.parse("application/json"));
    private static final int MAX_BODY = 20 * 1024 * 1024;
    private static final LocalDate EARLIEST_DATE = LocalDate.of(2022, 1, 1);
    private final OkHttpClient client;
    private final String typesEndpoint;
    private final String dayEndpoint;

    public record AccrualType(int id, String name, String description) { }
    /** Raw accrual objects are retained so unknown categories and monetary signs are not invented. */
    public record DayPage(LocalDate date, List<JsonObject> accruals, String lastId) {
        public DayPage { accruals = accruals.stream().map(JsonObject::deepCopy).toList(); }
        @Override public List<JsonObject> accruals() { return accruals.stream().map(JsonObject::deepCopy).toList(); }
    }

    public OzonFinanceAccrualApiClient() {
        this(new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(45))
                        .writeTimeout(Duration.ofSeconds(20)).callTimeout(Duration.ofSeconds(60))
                        .retryOnConnectionFailure(false).build(),
                "https://api-seller.ozon.ru/v1/finance/accrual/types",
                "https://api-seller.ozon.ru/v1/finance/accrual/by-day");
    }

    public OzonFinanceAccrualApiClient(OkHttpClient client, String typesEndpoint, String dayEndpoint) {
        this.client = Objects.requireNonNull(client); this.typesEndpoint = Objects.requireNonNull(typesEndpoint);
        this.dayEndpoint = Objects.requireNonNull(dayEndpoint);
    }

    public Map<Integer, AccrualType> loadTypes(OzonCredentials credentials) {
        JsonObject root = post(credentials, typesEndpoint, "{}");
        JsonArray types = requiredArray(root, "accrual_types");
        Map<Integer, AccrualType> result = new LinkedHashMap<>();
        for (JsonElement element : types) {
            if (!element.isJsonObject()) throw invalidResponse();
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("id") || !entry.get("id").isJsonPrimitive() || !entry.getAsJsonPrimitive("id").isNumber()) throw invalidResponse();
            int id;
            try { id = entry.get("id").getAsBigDecimal().intValueExact(); } catch (RuntimeException invalid) { throw invalidResponse(); }
            if (result.putIfAbsent(id, new AccrualType(id, text(entry, "name"), text(entry, "description"))) != null)
                throw invalidResponse();
        }
        return Map.copyOf(result);
    }

    public DayPage loadDay(OzonCredentials credentials, LocalDate date, String lastId) {
        if (date == null || date.isBefore(EARLIEST_DATE) || lastId == null || lastId.length() > 8192)
            throw new IllegalArgumentException("Invalid Ozon accrual day or cursor");
        JsonObject body = new JsonObject(); body.addProperty("date", date.toString()); body.addProperty("last_id", lastId);
        JsonObject root = post(credentials, dayEndpoint, body.toString());
        JsonArray values = requiredArray(root, "accruals");
        List<JsonObject> accruals = new ArrayList<>(values.size());
        for (JsonElement value : values) {
            if (!value.isJsonObject()) throw invalidResponse();
            accruals.add(value.getAsJsonObject());
        }
        JsonElement cursor = root.get("last_id");
        if (cursor != null && !cursor.isJsonNull() && (!cursor.isJsonPrimitive() || !cursor.getAsJsonPrimitive().isString())) throw invalidResponse();
        return new DayPage(date, accruals, text(root, "last_id"));
    }

    private JsonObject post(OzonCredentials credentials, String endpoint, String payload) {
        Objects.requireNonNull(credentials, "credentials");
        Request request = new Request.Builder().url(endpoint).header("Client-Id", credentials.clientId())
                .header("Api-Key", credentials.apiKey()).header("Accept", "application/json")
                .post(RequestBody.create(payload, JSON)).build();
        try (var slot = MarketplaceRequestBudget.acquire(); Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Duration retryAfter = null;
                try { String value = response.header("Retry-After"); if (value != null) retryAfter = Duration.ofSeconds(Math.max(1, Long.parseLong(value.strip()))); }
                catch (NumberFormatException ignored) { }
                throw new OzonFinanceApiException(response.code(), "Ozon accrual API HTTP " + response.code(), retryAfter);
            }
            String body = BoundedHttpBody.utf8(response.body(), MAX_BODY);
            try {
                JsonElement root = JsonParser.parseString(body);
                if (!root.isJsonObject()) throw invalidResponse();
                return root.getAsJsonObject();
            } catch (RuntimeException invalid) { throw invalidResponse(); }
        } catch (OzonFinanceApiException failure) { throw failure; }
        catch (IOException failure) { throw new OzonFinanceApiException("Không thể đọc Ozon accrual API", failure); }
    }

    private static JsonArray requiredArray(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonArray()) throw invalidResponse();
        return value.getAsJsonArray();
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static OzonFinanceApiException invalidResponse() {
        return new OzonFinanceApiException("Ozon accrual API trả dữ liệu không hợp lệ", null);
    }
}
