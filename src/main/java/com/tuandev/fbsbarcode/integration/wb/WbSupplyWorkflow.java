package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.features.print.history.ImageCacheRepository;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.models.Sticker;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class WbSupplyWorkflow {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbSupplyWorkflow.class);
    private static final long IMAGE_WARMUP_WAIT_SECONDS = 3L;
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService IMAGE_EXECUTOR = Executors.newFixedThreadPool(6, runnable -> {
        Thread thread = new Thread(runnable, "wb-image-loader");
        thread.setDaemon(true);
        return thread;
    });
    private static final int IMAGE_CACHE_LIMIT = 500;
    private static final Map<String, byte[]> IMAGE_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > IMAGE_CACHE_LIMIT;
                }
            }
    );
    private static final Set<String> FAILED_IMAGE_URLS = ConcurrentHashMap.newKeySet();

    private final WbSupplyRepository supplyRepository = new WbSupplyRepository();
    private final WbOrderRepository orderRepository = new WbOrderRepository();
    private final WbProductSyncService productSyncService = new WbProductSyncService();
    private final WbStickerService stickerService = new WbStickerService();
    private final ImageCacheRepository imageCacheRepository = new ImageCacheRepository();
    private final WbApiClient api;

    public WbSupplyWorkflow() {
        this(new WbApiClient());
    }

    WbSupplyWorkflow(WbApiClient api) {
        this.api = java.util.Objects.requireNonNull(api, "api");
    }

    public List<WbSupplySummary> getSupplies(int shopId) {
        return supplyRepository.getSupplySummaries(shopId);
    }

    public void deleteEmptySupply(Shop shop, String supplyId) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        if (supplyId == null || !supplyId.matches("[A-Za-z0-9-]{1,128}")) {
            throw new IllegalArgumentException("Invalid Wildberries supply ID");
        }
        WbSupplyOrderIdsResponse remote = api.getSupplyOrderIds(shop.getApiKey(), supplyId);
        if (remote == null || remote.getOrderIds() == null) {
            throw new IOException("Wildberries returned an invalid supply order list");
        }
        List<Long> remoteOrderIds = remote.getOrderIds();
        if (!remoteOrderIds.isEmpty()) {
            throw new WbSupplyNotEmptyException();
        }
        api.deleteSupply(shop.getApiKey(), supplyId);
        supplyRepository.deleteSupply(shop.getId(), supplyId);
    }

    public List<Order> loadOrdersForSupplyLocal(Shop shop, String supplyId) {
        MarketplaceGuard.requireWildberries(shop);
        List<Order> orders = orderRepository.getOrdersForSupply(shop.getId(), supplyId);
        populateCachedOrderImages(orders);
        return orders;
    }

    public boolean hasMissingProducts(Shop shop, String supplyId) {
        MarketplaceGuard.requireWildberries(shop);
        return orderRepository.hasMissingProductsForSupply(shop.getId(), supplyId);
    }

    public List<Order> loadOrdersForSupply(Shop shop, String supplyId) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        List<Long> missingNmIds = orderRepository.findMissingProductNmIdsForSupply(shop.getId(), supplyId);
        if (!missingNmIds.isEmpty()) {
            try {
                productSyncService.recoverProductsByNmIds(shop, missingNmIds);
            } catch (WbApiException ex) {
                if (!ex.isContentPermissionError() && !ex.isRateLimited()) {
                    throw ex;
                }
                if (ex.isContentPermissionError()) {
                    LOGGER.warn("Không thể khôi phục products cho supply {} của shop {} vì token thiếu quyền Content: {}",
                            supplyId, shop.getId(), ex.getMessage());
                } else {
                    LOGGER.warn("Không thể khôi phục products cho supply {} của shop {} vì WB rate limit: {}",
                            supplyId, shop.getId(), ex.getMessage());
                }
            }
        }
        List<Order> orders = orderRepository.getOrdersForSupply(shop.getId(), supplyId);
        if (orders.isEmpty()) {
            return orders;
        }

        populateCachedOrderImages(orders);
        enrichOrders(shop, orders);
        return orders;
    }

    public void enrichOrders(Shop shop, List<Order> orders) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        enrichOrderKizMetadata(shop, orders);
    }

    public void enrichOrderStickers(Shop shop, List<Order> orders) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        if (orders == null || orders.isEmpty()) {
            return;
        }

        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        List<Sticker> stickers = stickerService.getStickers(shop.getApiKey(), orderIds);
        Map<Long, Sticker> stickerMap = stickers.stream().collect(Collectors.toMap(Sticker::getOrderId, value -> value));

        for (Order order : orders) {
            Sticker sticker = stickerMap.get(order.getId());
            if (sticker == null) {
                continue;
            }
            order.setSticker(sticker.getPartA() + " " + sticker.getPartB());
            order.setStickerCode(sticker.getBarcode());
        }
    }

    public void enrichOrderKizMetadata(Shop shop, List<Order> orders) {
        MarketplaceGuard.requireWildberries(shop);
        if (orders == null || orders.isEmpty()) {
            return;
        }
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        try {
            Map<Long, com.tuandev.fbsbarcode.features.kiz.KizService.SgtinMetadata> metadata =
                    com.tuandev.fbsbarcode.features.kiz.KizService.getSgtinMetadata(shop.getApiKey(), orderIds);
            for (Order order : orders) {
                com.tuandev.fbsbarcode.features.kiz.KizService.SgtinMetadata meta = metadata.get(order.getId());
                if (meta != null) {
                    if (meta.available()) {
                        order.setRequiresKiz(true);
                    }
                    if (meta.hasAppliedValue()) {
                        order.setKiz(meta.appliedValue());
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.warn("Failed to fetch KIZ metadata for supply orders for shop {}", shop.getId(), ex);
        }
    }

    public void ensureOrderImages(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        populateOrderImages(orders);
    }

    public List<Order> populateCachedOrderImages(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return orders;
        }
        Map<String, List<Order>> ordersByCacheKey = new HashMap<>();
        for (Order order : orders) {
            if (order.getImage() != null) {
                continue;
            }
            String imageUrl = order.getImageUrl();
            if (imageUrl == null || imageUrl.isBlank()) {
                continue;
            }
            String cacheKey = PrintHistoryService.imageCacheKey(imageUrl);
            if (cacheKey == null) {
                continue;
            }
            byte[] memoryImage = IMAGE_CACHE.get(cacheKey);
            if (memoryImage != null) {
                order.setImage(memoryImage);
                continue;
            }
            ordersByCacheKey.computeIfAbsent(cacheKey, ignored -> new ArrayList<>()).add(order);
        }
        if (ordersByCacheKey.isEmpty()) {
            return orders;
        }
        Map<String, byte[]> cachedImages = imageCacheRepository.findImages(ordersByCacheKey.keySet());
        for (Map.Entry<String, byte[]> entry : cachedImages.entrySet()) {
            byte[] image = entry.getValue();
            if (image == null) {
                continue;
            }
            IMAGE_CACHE.put(entry.getKey(), image);
            List<Order> matchingOrders = ordersByCacheKey.get(entry.getKey());
            if (matchingOrders == null) {
                continue;
            }
            for (Order order : matchingOrders) {
                order.setImage(image);
            }
        }
        return orders;
    }

    private void populateOrderImages(List<Order> orders) {
        List<Order> missingImages = orders.stream()
                .filter(order -> order.getImage() == null)
                .filter(order -> order.getImageUrl() != null && !order.getImageUrl().isBlank())
                .toList();

        if (missingImages.isEmpty()) {
            return;
        }

        Map<String, List<Order>> missingByCacheKey = new LinkedHashMap<>();
        for (Order order : missingImages) {
            String cacheKey = PrintHistoryService.imageCacheKey(order.getImageUrl());
            if (cacheKey == null) {
                continue;
            }
            byte[] memoryImage = IMAGE_CACHE.get(cacheKey);
            if (memoryImage != null) {
                order.setImage(memoryImage);
                continue;
            }
            missingByCacheKey.computeIfAbsent(cacheKey, ignored -> new ArrayList<>()).add(order);
        }
        if (missingByCacheKey.isEmpty()) {
            return;
        }

        Map<String, byte[]> dbImages = imageCacheRepository.findImages(missingByCacheKey.keySet());
        for (Map.Entry<String, byte[]> entry : dbImages.entrySet()) {
            byte[] image = entry.getValue();
            if (image == null) {
                continue;
            }
            IMAGE_CACHE.put(entry.getKey(), image);
            List<Order> matchingOrders = missingByCacheKey.remove(entry.getKey());
            if (matchingOrders == null) {
                continue;
            }
            for (Order order : matchingOrders) {
                order.setImage(image);
            }
        }

        if (missingByCacheKey.isEmpty()) {
            return;
        }

        CountDownLatch latch = new CountDownLatch(missingByCacheKey.size());
        for (Map.Entry<String, List<Order>> entry : missingByCacheKey.entrySet()) {
            IMAGE_EXECUTOR.submit(() -> {
                try {
                    populateOrdersByCacheKey(entry.getKey(), entry.getValue());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(IMAGE_WARMUP_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void populateOrdersByCacheKey(String cacheKey, List<Order> orders) {
        if (cacheKey == null || cacheKey.isBlank() || orders == null || orders.isEmpty()) {
            return;
        }
        String imageUrl = orders.get(0).getImageUrl();
        if (imageUrl == null || imageUrl.isBlank()) {
            return;
        }

        byte[] image = IMAGE_CACHE.get(cacheKey);
        if (image == null && !FAILED_IMAGE_URLS.contains(cacheKey)) {
            try {
                image = downloadProductImage(imageUrl);
                if (image != null) {
                    IMAGE_CACHE.put(cacheKey, image);
                    imageCacheRepository.saveImage(cacheKey, imageUrl, image, "image/png");
                } else {
                    FAILED_IMAGE_URLS.add(cacheKey);
                }
            } catch (RuntimeException ex) {
                LOGGER.debug("Ignoring product image caching failure for {}", imageUrl, ex);
                FAILED_IMAGE_URLS.add(cacheKey);
            }
        }

        if (image != null) {
            for (Order order : orders) {
                order.setImage(image);
            }
        }
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

    public static void clearImageCache() {
        IMAGE_CACHE.clear();
        FAILED_IMAGE_URLS.clear();
    }

    public static void shutdownImageLoader() {
        IMAGE_EXECUTOR.shutdownNow();
        clearImageCache();
    }
}
