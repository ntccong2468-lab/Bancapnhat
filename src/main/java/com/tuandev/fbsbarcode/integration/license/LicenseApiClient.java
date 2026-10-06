package com.tuandev.fbsbarcode.integration.license;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.tuandev.fbsbarcode.BuildConfig;
import java.io.IOException;
import java.time.Duration;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Client gọi wcode-license-server (activate/validate/deactivate). */
public class LicenseApiClient {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient client;
    private final Gson gson = new Gson();
    private final String baseUrl;

    public LicenseApiClient() {
        this(
                new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(15)).build(),
                BuildConfig.getLicenseServerUrl());
    }

    LicenseApiClient(OkHttpClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public static LicenseApiClient withBaseUrl(String baseUrl) {
        return new LicenseApiClient(
                new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(15)).build(), baseUrl);
    }

    public record LicenseCheckResponse(
            String status, long expiresAt, String plan, SignedLicenseFile licenseFile) {}

    public LicenseCheckResponse activate(
            String licenseKey, String fingerprint, String deviceName, String appVersion)
            throws IOException, LicenseApiException {
        JsonObject body = new JsonObject();
        body.addProperty("licenseKey", licenseKey);
        body.addProperty("fingerprint", fingerprint);
        body.addProperty("deviceName", deviceName);
        body.addProperty("appVersion", appVersion);
        return post("/api/v1/activate", body, LicenseCheckResponse.class);
    }

    public LicenseCheckResponse validate(String licenseKey, String fingerprint, String appVersion)
            throws IOException, LicenseApiException {
        JsonObject body = new JsonObject();
        body.addProperty("licenseKey", licenseKey);
        body.addProperty("fingerprint", fingerprint);
        body.addProperty("appVersion", appVersion);
        return post("/api/v1/validate", body, LicenseCheckResponse.class);
    }

    public void deactivate(String licenseKey, String fingerprint)
            throws IOException, LicenseApiException {
        JsonObject body = new JsonObject();
        body.addProperty("licenseKey", licenseKey);
        body.addProperty("fingerprint", fingerprint);
        post("/api/v1/deactivate", body, JsonObject.class);
    }

    private <T> T post(String path, JsonObject body, Class<T> responseType)
            throws IOException, LicenseApiException {
        Request request =
                new Request.Builder()
                        .url(baseUrl + path)
                        .header("User-Agent", "WCode/" + BuildConfig.getAppVersion())
                        .post(RequestBody.create(gson.toJson(body), JSON))
                        .build();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw toApiException(response.code(), responseBody);
            }
            try {
                return gson.fromJson(responseBody, responseType);
            } catch (JsonSyntaxException e) {
                throw new IOException("Phản hồi license server không hợp lệ", e);
            }
        }
    }

    private LicenseApiException toApiException(int statusCode, String responseBody) {
        String code = "http_" + statusCode;
        String message = "License server trả về HTTP " + statusCode;
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonObject error = json.getAsJsonObject("error");
            if (error != null) {
                if (error.has("code")) {
                    code = error.get("code").getAsString();
                }
                if (error.has("message")) {
                    message = error.get("message").getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // giữ code/message mặc định nếu body không phải JSON lỗi chuẩn
        }
        return new LicenseApiException(statusCode, code, message);
    }

    /** Lỗi nghiệp vụ từ license server (HTTP != 2xx) kèm mã lỗi máy đọc được. */
    public static class LicenseApiException extends Exception {
        public static final String CODE_INVALID_LICENSE = "invalid_license";
        public static final String CODE_DEVICE_NOT_ACTIVATED = "device_not_activated";
        public static final String CODE_DEVICE_LIMIT_REACHED = "device_limit_reached";

        private final int statusCode;
        private final String code;

        LicenseApiException(int statusCode, String code, String message) {
            super(message);
            this.statusCode = statusCode;
            this.code = code;
        }

        public int statusCode() {
            return statusCode;
        }

        public String code() {
            return code;
        }
    }
}
