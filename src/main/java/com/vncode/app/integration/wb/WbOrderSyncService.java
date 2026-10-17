package com.vncode.app.integration.wb;

import com.vncode.app.models.Shop;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import static com.vncode.app.features.license.LicenseModels.Permission.SELLER_API;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class WbOrderSyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbOrderSyncService.class);
    private static final int PAGE_LIMIT = 1000;
    private static final int STATUS_BATCH_SIZE = 1000;

    private final WbApiClient apiClient;
    private final WbOrderRepository orderRepository;
    private final WbSupplyRepository supplyRepository;
    private final WbSyncStateRepository syncStateRepository;
    private final WbSyncRunRepository syncRunRepository;

    public WbOrderSyncService() {
        this(new WbApiClient(), new WbOrderRepository(), new WbSupplyRepository(), new WbSyncStateRepository(), new WbSyncRunRepository());
    }

    WbOrderSyncService(WbApiClient apiClient, WbOrderRepository orderRepository, WbSupplyRepository supplyRepository, WbSyncStateRepository syncStateRepository, WbSyncRunRepository syncRunRepository) {
        this.apiClient = apiClient;
        this.orderRepository = orderRepository;
        this.supplyRepository = supplyRepository;
        this.syncStateRepository = syncStateRepository;
        this.syncRunRepository = syncRunRepository;
    }

    public int syncNewOrders(Shop shop) throws IOException {
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        LicenseRuntime.require(SELLER_API);
        long runId = syncRunRepository.startSyncRun(shop.getId(), "orders_new");
        try {
            WbOrdersResponse response = apiClient.getNewOrders(shop.getApiKey());
            WbFbsResponseValidator.orders(response);
            com.vncode.app.integration.listing.ListingIdentity.require(shop);
            int count = response.getOrders().size();
            orderRepository.saveCurrentNewOrdersSnapshot(shop.getId(), response.getOrders());
            syncRunRepository.finishSyncRun(runId, true, count, count, null, null);
            return count;
        } catch (LicenseDeniedException paused) {
            syncRunRepository.finishSyncRun(runId,false,0,0,"license_required",paused.getMessage());throw paused;
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Sync new orders thất bại cho shop {}", shop.getId(), ex);
            reportFailure(shop, runId, 0, 0, ex);
            throw ex;
        }
    }

    public int syncOrdersWindow(Shop shop) throws IOException {
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        LicenseRuntime.require(SELLER_API);
        WbShopSyncState state = syncStateRepository.getShopSyncState(shop.getId());
        long runId = syncRunRepository.startSyncRun(shop.getId(), "orders");
        long next = 0L;
        Instant now = Instant.now();
        long windowTo = now.getEpochSecond();
        long minWindowFrom = now.minus(30, ChronoUnit.DAYS).getEpochSecond();
        Long previousWindowTo = state.ordersWindowTo();
        long windowFrom = previousWindowTo == null
                ? minWindowFrom
                : Math.max(minWindowFrom, previousWindowTo - ChronoUnit.DAYS.getDuration().getSeconds());
        int read = 0;
        int written = 0;
        try {
            for (int page = 0; page < 20_000; page++) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("FBS_SYNC_CANCELLED");
                LicenseRuntime.require(SELLER_API);
                WbOrdersResponse response = apiClient.getOrders(shop.getApiKey(), next, PAGE_LIMIT, windowFrom, windowTo);
                WbFbsResponseValidator.orders(response);
                long advanced = WbFbsResponseValidator.next(next, response.getNext(), response.getOrders().size(), PAGE_LIMIT);
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                if (response.getOrders().isEmpty()) {
                    syncStateRepository.updateOrdersCursor(shop.getId(), next, windowFrom, windowTo, Instant.now().toString());
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }
                orderRepository.saveOrders(shop.getId(), response.getOrders());
                read += response.getOrders().size();
                written += response.getOrders().size();
                next = advanced;
                if (response.getOrders().size() < PAGE_LIMIT) {
                    syncStateRepository.updateOrdersCursor(shop.getId(), next, windowFrom, windowTo, Instant.now().toString());
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }
            }
            throw new IOException("FBS_CURSOR_LIMIT");
        } catch (LicenseDeniedException paused) {
            syncRunRepository.finishSyncRun(runId,false,read,written,"license_required",paused.getMessage());throw paused;
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Sync orders window thất bại cho shop {}", shop.getId(), ex);
            reportFailure(shop, runId, read, written, ex);
            throw ex;
        }
    }

    public int syncOrderStatusesForSupply(Shop shop, String supplyId) throws IOException {
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        LicenseRuntime.require(SELLER_API);
        List<Long> orderIds = orderRepository.getOrderIdsForSupply(shop.getId(), supplyId);
        if (orderIds.isEmpty()) {
            return 0;
        }
        long runId = syncRunRepository.startSyncRun(shop.getId(), "order_statuses");
        int written = 0;
        try {
            for (int i = 0; i < orderIds.size(); i += STATUS_BATCH_SIZE) {
                LicenseRuntime.require(SELLER_API);
                List<Long> batch = orderIds.subList(i, Math.min(i + STATUS_BATCH_SIZE, orderIds.size()));
                WbOrderStatusesResponse response = apiClient.getOrderStatuses(shop.getApiKey(), batch);
                WbFbsResponseValidator.statuses(response, batch);
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                int count = response.getOrders().size();
                orderRepository.updateOrderStatuses(shop.getId(), response.getOrders());
                written += count;
            }
            syncRunRepository.finishSyncRun(runId, true, orderIds.size(), written, null, null);
            return written;
        } catch (LicenseDeniedException paused) {
            syncRunRepository.finishSyncRun(runId,false,orderIds.size(),written,"license_required",paused.getMessage());throw paused;
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Sync order statuses thất bại cho shop {} supply {}", shop.getId(), supplyId, ex);
            reportFailure(shop, runId, orderIds.size(), written, ex);
            throw ex;
        }
    }

    public int syncSupplyOrders(Shop shop, String supplyId) throws IOException {
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        LicenseRuntime.require(SELLER_API);
        long runId = syncRunRepository.startSyncRun(shop.getId(), "supply_orders");
        try {
            WbSupplyOrderIdsResponse response = apiClient.getSupplyOrderIds(shop.getApiKey(), supplyId);
            List<Long> orderIds = new ArrayList<>(WbFbsResponseValidator.supplyIds(response));
            com.vncode.app.integration.listing.ListingIdentity.require(shop);
            orderRepository.replaceSupplyOrders(shop.getId(), supplyId, orderIds);
            supplyRepository.updateSupplyOrderCount(shop.getId(), supplyId, orderIds.size());
            syncRunRepository.finishSyncRun(runId, true, orderIds.size(), orderIds.size(), null, null);
            return orderIds.size();
        } catch (LicenseDeniedException paused) {
            syncRunRepository.finishSyncRun(runId,false,0,0,"license_required",paused.getMessage());throw paused;
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Sync supply orders thất bại cho shop {} supply {}", shop.getId(), supplyId, ex);
            reportFailure(shop, runId, 0, 0, ex);
            throw ex;
        }
    }
    private void reportFailure(Shop shop, long run, int read, int written, Exception primary) {
        String code = com.vncode.app.features.sync.SyncFailure.of(primary, 0).code();
        try { syncStateRepository.saveSyncError(shop.getId(), code); }
        catch (RuntimeException reporting) { primary.addSuppressed(reporting); }
        try { syncRunRepository.finishSyncRun(run, false, read, written, code, code); }
        catch (RuntimeException reporting) { primary.addSuppressed(reporting); }
    }

}
