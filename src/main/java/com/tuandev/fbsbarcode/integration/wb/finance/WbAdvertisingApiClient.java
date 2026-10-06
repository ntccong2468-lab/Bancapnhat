package com.tuandev.fbsbarcode.integration.wb.finance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.features.finance.AdvertisingRawRow;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class WbAdvertisingApiClient {
    private static final String DEFAULT_URL = "https://advert-api.wildberries.ru/adv/v1/upd";
    private final OkHttpClient client;
    private final String endpoint;

    public WbAdvertisingApiClient() {
        this(defaultClient(), DEFAULT_URL);
    }

    public WbAdvertisingApiClient(OkHttpClient client, String endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    public List<AdvertisingRawRow> loadCosts(String apiKey, LocalDate from, LocalDate to) {
        if (to.toEpochDay() - from.toEpochDay() > 30) {
            throw new IllegalArgumentException("WB Advertising chỉ cho phép tối đa 31 ngày");
        }
        HttpUrl url = HttpUrl.parse(endpoint).newBuilder()
                .addQueryParameter("from", from.toString())
                .addQueryParameter("to", to.toString())
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", apiKey)
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw WbFinanceApiClient.apiError(response, body, "WB Advertising");
            }
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonArray()) {
                throw new WbAnalyticsApiException(response.code(), "WB Advertising trả dữ liệu không hợp lệ", null);
            }
            JsonArray array = root.getAsJsonArray();
            List<AdvertisingRawRow> rows = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                if (element.isJsonObject()) rows.add(parseRow(element.getAsJsonObject(), to));
            }
            return rows;
        } catch (WbAnalyticsApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new WbAnalyticsApiException("Không thể gọi WB Advertising: " + exception.getMessage(), exception);
        }
    }

    static AdvertisingRawRow parseRow(JsonObject object, LocalDate fallbackDate) {
        String updateNumber = text(object, "updNum");
        String updateTime = text(object, "updTime");
        String advertisingId = text(object, "advertId");
        String paymentType = text(object, "paymentType");
        String amount = text(object, "updSum");
        String stable = String.join("|", safe(updateNumber), safe(updateTime), safe(advertisingId),
                safe(paymentType), safe(text(object, "campName")));
        return new AdvertisingRawRow(
                sha256(stable),
                parseDate(updateTime, fallbackDate),
                updateNumber,
                updateTime,
                advertisingId,
                text(object, "campName"),
                integer(object, "advertType"),
                paymentType,
                decimal(amount),
                object.toString());
    }

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(45))
                .retryOnConnectionFailure(false)
                .build();
    }

    private static String parseDate(String value, LocalDate fallback) {
        if (value != null && value.length() >= 10) {
            try {
                return LocalDate.parse(value.substring(0, 10)).toString();
            } catch (RuntimeException ignored) {
                // Use the requested window end.
            }
        }
        return fallback.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int integer(JsonObject object, String name) {
        try {
            return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsInt() : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static double decimal(String value) {
        try {
            return value == null ? 0 : Double.parseDouble(value.replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String text(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? null : element.getAsString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
