package com.tuandev.fbsbarcode.integration.wb;

import com.google.gson.Gson;
import com.tuandev.fbsbarcode.dto.StickerResponse;
import com.tuandev.fbsbarcode.models.Sticker;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class WbStickerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbStickerService.class);
    private static final String OFFICIAL_BASE_URL = "https://marketplace-api.wildberries.ru";
    private static final OkHttpClient DEFAULT_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final Gson GSON = new Gson();
    private static final int BATCH_SIZE = 100;
    private final OkHttpClient client;
    private final String baseUrl;

    public WbStickerService() {
        this(DEFAULT_CLIENT, OFFICIAL_BASE_URL);
    }

    WbStickerService(OkHttpClient client, String baseUrl) {
        this.client = Objects.requireNonNull(client, "client");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Wildberries base URL is required");
        }
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    public List<Sticker> getStickers(String apiKey, List<Long> orderIds) throws IOException {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("Wildberries API token is not configured.");
        }
        if (orderIds.stream().anyMatch(orderId -> orderId == null || orderId <= 0)) {
            throw new IOException("Wildberries sticker order ids are invalid.");
        }
        List<Sticker> allStickers = new ArrayList<>();
        for (int i = 0; i < orderIds.size(); i += BATCH_SIZE) {
            List<Long> batch = orderIds.subList(i, Math.min(i + BATCH_SIZE, orderIds.size()));
            allStickers.addAll(requestStickerBatch(apiKey, batch));
        }
        return allStickers;
    }

    private List<Sticker> requestStickerBatch(String apiKey, List<Long> orderIds) throws IOException {
        String url = baseUrl + "/api/v3/orders/stickers?type=png&width=58&height=40";

        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("orders", orderIds);

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                GSON.toJson(bodyMap)
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                if (response.body() == null) {
                    throw new IOException("Wildberries returned an empty sticker response.");
                }
                StickerResponse stickerResponse;
                try {
                    stickerResponse = GSON.fromJson(response.body().string(), StickerResponse.class);
                } catch (RuntimeException exception) {
                    throw new IOException("Wildberries returned an invalid sticker response.", exception);
                }
                if (stickerResponse == null) {
                    throw new IOException("Wildberries returned an invalid sticker response.");
                }
                return stickerResponse.getStickers();
            }

            LOGGER.warn("WB sticker request failed with status {}", response.code());
            throw new WbApiException("Wildberries sticker request failed", response.code(), "");
        } catch (InterruptedIOException ex) {
            throw new IOException("Wildberries system response timed out. Please try again.", ex);
        }
    }
}
