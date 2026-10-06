package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.models.Shop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class WbSyncWorkflow {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbSyncWorkflow.class);
    private final WbProductSyncService productSyncService = new WbProductSyncService();
    private final WbSupplySyncService supplySyncService = new WbSupplySyncService();
    private final WbOrderSyncService orderSyncService = new WbOrderSyncService();
    private final WbSyncStateRepository syncStateRepository = new WbSyncStateRepository();

    public int syncProducts(Shop shop) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        return productSyncService.sync(shop);
    }

    public WbSyncReport syncOverview(Shop shop) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        WbShopSyncState state = syncStateRepository.getShopSyncState(shop.getId());
        boolean needsInitialProductSync = state.productsLastSyncedAt() == null || state.productsLastSyncedAt().isBlank();
        boolean needsInitialSupplySync = state.suppliesLastSyncedAt() == null || state.suppliesLastSyncedAt().isBlank();

        int products = 0;
        if (needsInitialProductSync) {
            products = syncProductsIfAvailable(shop);
        }

        if (needsInitialSupplySync) {
            int supplies = supplySyncService.syncUntilOpenSuppliesFound(shop);
            int openSupplyDetails = supplySyncService.syncOpenSupplyDetails(shop);
            int openSupplyCounts = supplySyncService.syncOpenSupplyCounts(shop);
            return new WbSyncReport(products, supplies + openSupplyDetails + openSupplyCounts, 0, 0);
        }

        int supplies = supplySyncService.syncIncremental(shop);
        int openSupplyDetails = supplySyncService.syncOpenSupplyDetails(shop);
        int openSupplyCounts = supplySyncService.syncOpenSupplyCounts(shop);
        return new WbSyncReport(products, supplies + openSupplyDetails + openSupplyCounts, 0, 0);
    }

    public WbSyncReport syncAll(Shop shop) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        int products = syncProductsIfAvailable(shop);
        int supplies = supplySyncService.sync(shop);
        int newOrders = orderSyncService.syncNewOrders(shop);
        int orderWindow = orderSyncService.syncOrdersWindow(shop);
        int supplyDetails = supplySyncService.syncRecentSupplyDetails(shop);
        int openSupplyDetails = supplySyncService.syncOpenSupplyDetails(shop);
        int openSupplyCounts = supplySyncService.syncOpenSupplyCounts(shop);
        return new WbSyncReport(products, supplies + supplyDetails + openSupplyDetails + openSupplyCounts,
                newOrders + orderWindow, 0);
    }

    public WbSyncReport refetchSupplies(Shop shop) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        int supplies = supplySyncService.refreshOpenSuppliesFromStart(shop);
        int openSupplyDetails = supplySyncService.syncOpenSupplyDetails(shop);
        int openSupplyCounts = supplySyncService.syncOpenSupplyCounts(shop);
        return new WbSyncReport(0, supplies + openSupplyDetails + openSupplyCounts, 0, 0);
    }

    public int syncSupplyOrdersAndStatuses(Shop shop, String supplyId) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        orderSyncService.syncOrdersWindow(shop);
        int supplyOrders = orderSyncService.syncSupplyOrders(shop, supplyId);
        int statuses = orderSyncService.syncOrderStatusesForSupply(shop, supplyId);
        return supplyOrders + statuses;
    }

    private int syncProductsIfAvailable(Shop shop) throws IOException {
        try {
            return productSyncService.sync(shop);
        } catch (WbApiException ex) {
            if (ex.isContentPermissionError()) {
                LOGGER.warn("Bỏ qua sync products cho shop {} vì token không có quyền Content: {}", shop.getId(), ex.getMessage());
                return 0;
            }
            if (ex.isRateLimited()) {
                LOGGER.warn("Bỏ qua sync products cho shop {} vì WB Content API đang rate limit: {}", shop.getId(), ex.getMessage());
                return 0;
            }
            throw ex;
        }
    }

}
