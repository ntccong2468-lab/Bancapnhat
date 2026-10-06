package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.models.Shop;
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
        long runId = syncRunRepository.startSyncRun(shop.getId(), "orders_new");
        try {
            WbOrdersResponse response = apiClient.getNewOrders(shop.getApiKey());
            int count = response == null || response.getOrders() == null ? 0 : response.getOrders().size();
            orderRepository.saveCurrentNewOrdersSnapshot(shop.getId(), response == null ? List.of() : response.getOrders());
            syncRunRepository.finishSyncRun(runId, true, count, count, null, null);
            return count;
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Sync new orders thất bại cho shop {}", shop.getId(), ex);
            syncStateRepository.saveSyncError(shop.getId(), ex.getMessage());
            syncRunRepository.finishSyncRun(runId, false, 0, 0, ex instanceof WbApiException wb ? String.valueOf(wb.getStatusCode()) : "local_error", ex.getMessage());
            throw ex;
        }
    }

    public int syncOrdersWindow(Shop shop) throws IOException {
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
            while (true) {
                WbOrdersResponse response = apiClient.getOrders(shop.getApiKey(), next, PAGE_LIMIT, windowFrom, windowTo);
                if (response == null || response.getOrders() == null || response.getOrders().isEmpty()) {
                    syncStateRepository.updateOrdersCursor(shop.getId(), next, windowFrom, windowTo, Instant.now().toString());
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }
                orderRepository.saveOrders(shop.getId(), response.getOrders());
                read += response.getOrders().size();
                written += response.getOrders().size();
                next = response.getNext() == null ? next : response.getNext();
                syncStateRepository.updateOrdersCursor(shop.getId(), next, windowFrom, windowTo, Instant.now().toString());
                if (response.getOrders().size() < PAGE_LIMIT) {
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Sync orders window thất bại cho shop {}", shop.getId(), ex);
            syncStateRepository.saveSyncError(shop.getId(), ex.getMessage());
            syncRunRepository.finishSyncRun(runId, false, read, written, ex instanceof WbApiException wb ? String.valueOf(wb.getStatusCode()) : "local_error", ex.getMessage());
            throw ex;
        }
    }

    public int syncOrderStatusesForSupply(Shop shop, String supplyId) throws IOException {
        List<Long> orderIds = orderRepository.getOrderIdsForSupply(shop.getId(), supplyId);
        if (orderIds.isEmpty()) {
            return 0;
        }
        long runId = syncRunRepository.startSyncRun(shop.getId(), "order_statuses");
        int written = 0;
        try {
            for (int i = 0; i < orderIds.size(); i += STATUS_BATCH_SIZE) {
                List<Long> batch = orderIds.subList(i, Math.min(i + STATUS_BATCH_SIZE, orderIds.size()));
                WbOrderStatusesResponse response = apiClient.getOrderStatuses(shop.getApiKey(), batch);
                int count = response == null || response.getOrders() == null ? 0 : response.getOrders().size();
                orderRepository.updateOrderStatuses(shop.getId(), response == null ? List.of() : response.getOrders());
                written += count;
            }
            syncRunRepository.finishSyncRun(runId, true, orderIds.size(), written, null, null);
            return written;
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Sync order statuses thất bại cho shop {} supply {}", shop.getId(), supplyId, ex);
            syncStateRepository.saveSyncError(shop.getId(), ex.getMessage());
            syncRunRepository.finishSyncRun(runId, false, orderIds.size(), written, ex instanceof WbApiException wb ? String.valueOf(wb.getStatusCode()) : "local_error", ex.getMessage());
            throw ex;
        }
    }

    public int syncSupplyOrders(Shop shop, String supplyId) throws IOException {
        long runId = syncRunRepository.startSyncRun(shop.getId(), "supply_orders");
        try {
            WbSupplyOrderIdsResponse response = apiClient.getSupplyOrderIds(shop.getApiKey(), supplyId);
            List<Long> orderIds = response == null || response.getOrderIds() == null ? List.of() : new ArrayList<>(response.getOrderIds());
            orderRepository.replaceSupplyOrders(shop.getId(), supplyId, orderIds);
            supplyRepository.updateSupplyOrderCount(shop.getId(), supplyId, orderIds.size());
            syncRunRepository.finishSyncRun(runId, true, orderIds.size(), orderIds.size(), null, null);
            return orderIds.size();
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Sync supply orders thất bại cho shop {} supply {}", shop.getId(), supplyId, ex);
            syncStateRepository.saveSyncError(shop.getId(), ex.getMessage());
            syncRunRepository.finishSyncRun(runId, false, 0, 0, ex instanceof WbApiException wb ? String.valueOf(wb.getStatusCode()) : "local_error", ex.getMessage());
            throw ex;
        }
    }
}
