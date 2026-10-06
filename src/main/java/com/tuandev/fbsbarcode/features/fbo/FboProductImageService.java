package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.features.print.history.ImageCacheRepository;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class FboProductImageService {
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService IMAGE_EXECUTOR = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "fbo-product-image-loader");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, byte[]> MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<byte[]>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Set<String> FAILED_KEYS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final ImageCacheRepository imageCacheRepository = new ImageCacheRepository();

    public CompletableFuture<byte[]> loadImage(String imageUrl) {
        String cacheKey = PrintHistoryService.imageCacheKey(imageUrl);
        if (cacheKey == null || FAILED_KEYS.contains(cacheKey)) {
            return CompletableFuture.completedFuture(null);
        }

        byte[] memoryImage = MEMORY_CACHE.get(cacheKey);
        if (memoryImage != null) {
            return CompletableFuture.completedFuture(memoryImage);
        }

        return IN_FLIGHT.computeIfAbsent(cacheKey, key ->
                CompletableFuture.supplyAsync(() -> loadOrDownload(key, imageUrl), IMAGE_EXECUTOR)
                        .whenComplete((image, error) -> IN_FLIGHT.remove(key))
        );
    }

    private byte[] loadOrDownload(String cacheKey, String imageUrl) {
        byte[] cachedImage = imageCacheRepository.findImage(cacheKey);
        if (cachedImage != null && cachedImage.length > 0) {
            MEMORY_CACHE.put(cacheKey, cachedImage);
            return cachedImage;
        }

        byte[] downloadedImage = downloadProductImage(imageUrl);
        if (downloadedImage == null || downloadedImage.length == 0) {
            FAILED_KEYS.add(cacheKey);
            return null;
        }

        MEMORY_CACHE.put(cacheKey, downloadedImage);
        imageCacheRepository.saveImage(cacheKey, imageUrl, downloadedImage, "image/png");
        return downloadedImage;
    }

    private byte[] downloadProductImage(String imageUrl) {
        Request request = new Request.Builder().url(imageUrl).get().build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }

            byte[] body = response.body().bytes();
            try (ByteArrayInputStream input = new ByteArrayInputStream(body);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                BufferedImage image = ImageIO.read(input);
                if (image == null) {
                    return null;
                }
                ImageIO.write(image, "png", output);
                return output.toByteArray();
            }
        } catch (IOException | IllegalArgumentException | LinkageError ex) {
            return null;
        }
    }
}
