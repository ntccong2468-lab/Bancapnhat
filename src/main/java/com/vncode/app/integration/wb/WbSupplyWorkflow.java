package com.vncode.app.integration.wb;

import com.vncode.app.features.print.history.ImageCacheRepository;
import com.vncode.app.features.print.history.PrintHistoryService;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Order;
import com.vncode.app.models.Shop;
import com.vncode.app.models.Sticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class WbSupplyWorkflow {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbSupplyWorkflow.class);
    private static final com.vncode.app.shared.ThumbnailCache<byte[]> IMAGE_CACHE=com.vncode.app.shared.EncodedImageCache.shared();


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
        LicenseRuntime.require(Permission.PACKING);
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
        LicenseRuntime.require(Permission.PACKING);
        api.deleteSupply(shop.getApiKey(), supplyId);
        supplyRepository.deleteSupply(shop.getId(), supplyId);
    }

    public List<Order> loadOrdersForSupplyLocal(Shop shop, String supplyId) {
        MarketplaceGuard.requireWildberries(shop);
        List<Order> orders = orderRepository.getOrdersForSupply(shop.getId(), supplyId);
        populateCachedOrderImages(orders);
        return orders;
    }

    /** Lightweight screen projection: full image bytes are reserved for explicit printing. */
    public List<Order> loadOrdersForSupplyData(Shop shop,String supplyId) {
        MarketplaceGuard.requireWildberries(shop);return orderRepository.getOrdersForSupply(shop.getId(),supplyId);
    }

    public boolean hasMissingProducts(Shop shop, String supplyId) {
        MarketplaceGuard.requireWildberries(shop);
        return orderRepository.hasMissingProductsForSupply(shop.getId(), supplyId);
    }

    public List<Order> loadOrdersForSupply(Shop shop,String supplyId)throws IOException {return loadOrdersForSupply(shop,supplyId,true);}
    public List<Order> loadOrdersForSupplyDisplay(Shop shop,String supplyId)throws IOException {return loadOrdersForSupply(shop,supplyId,false);}
    private List<Order> loadOrdersForSupply(Shop shop, String supplyId,boolean cachedImages) throws IOException {
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

        if(cachedImages)populateCachedOrderImages(orders);
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
            Map<Long, com.vncode.app.features.kiz.KizService.SgtinMetadata> metadata =
                    com.vncode.app.features.kiz.KizService.getSgtinMetadata(shop.getApiKey(), orderIds);
            for (Order order : orders) {
                com.vncode.app.features.kiz.KizService.SgtinMetadata meta = metadata.get(order.getId());
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
        var groups=new LinkedHashMap<String,List<Order>>();
        for(var order:orders)if(order.getImage()==null&&order.getImageUrl()!=null&&!order.getImageUrl().isBlank())groups.computeIfAbsent(order.getImageUrl(),ignored->new ArrayList<>()).add(order);
        var service=new com.vncode.app.features.fbo.FboProductImageService();
        var batch=new ArrayList<java.util.concurrent.CompletableFuture<?>>();
        for(var entry:groups.entrySet()){
            batch.add(service.loadImage(entry.getKey()).thenAccept(bytes->{if(bytes!=null)for(var order:entry.getValue())order.setImage(bytes);}));
            if(batch.size()==32){java.util.concurrent.CompletableFuture.allOf(batch.toArray(java.util.concurrent.CompletableFuture[]::new)).join();batch.clear();}
        }
        java.util.concurrent.CompletableFuture.allOf(batch.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
    }

    public static void clearImageCache() {
        IMAGE_CACHE.clear();

    }

    public static void shutdownImageLoader() {

        clearImageCache();
    }
}
