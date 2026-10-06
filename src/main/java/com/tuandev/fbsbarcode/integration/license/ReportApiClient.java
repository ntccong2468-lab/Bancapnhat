package com.tuandev.fbsbarcode.integration.license;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.shared.ConfigService;
import java.io.IOException;
import java.time.Duration;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Gửi báo cáo lỗi từ app về license-server để admin xem (endpoint public /api/v1/reports). */
public class ReportApiClient {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient client;
    private final Gson gson = new Gson();
    private final String baseUrl;

    public ReportApiClient() {
        this(new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(15)).build(), resolveBaseUrl());
    }

    ReportApiClient(OkHttpClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String resolveBaseUrl() {
        String override = ConfigService.getLicenseServerUrl();
        return override != null && !override.isBlank() ? override : BuildConfig.getLicenseServerUrl();
    }

    public record Report(
            String licenseKey,
            String fingerprint,
            String shopName,
            String action,
            String entity,
            String errorCode,
            String message,
            String appVersion) {}

    public void send(Report report) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("licenseKey", nz(report.licenseKey()));
        body.addProperty("fingerprint", nz(report.fingerprint()));
        body.addProperty("shopName", nz(report.shopName()));
        body.addProperty("action", nz(report.action()));
        body.addProperty("entity", nz(report.entity()));
        body.addProperty("errorCode", nz(report.errorCode()));
        body.addProperty("message", nz(report.message()));
        body.addProperty("appVersion", nz(report.appVersion()));
        Request request =
                new Request.Builder()
                        .url(baseUrl + "/api/v1/reports")
                        .header("User-Agent", "WCode/" + BuildConfig.getAppVersion())
                        .post(RequestBody.create(gson.toJson(body), JSON))
                        .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Máy chủ trả về HTTP " + response.code());
            }
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
