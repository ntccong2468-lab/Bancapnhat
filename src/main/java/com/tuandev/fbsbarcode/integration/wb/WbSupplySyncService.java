package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.models.Shop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

public class WbSupplySyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbSupplySyncService.class);
    private static final int PAGE_LIMIT = 1000;
    private static final int RECENT_SUPPLY_DETAIL_LIMIT = 50;
    private static final int OPEN_SUPPLY_DETAIL_LIMIT = 20;
    private static final int INITIAL_OPEN_SUPPLY_SCAN_PAGES = 5;
    private final WbApiClient apiClient;
    private final WbSupplyRepository supplyRepository;
    private final WbSyncStateRepository syncStateRepository;
    private final WbSyncRunRepository syncRunRepository;

    public WbSupplySyncService() {
        this(new WbApiClient(), new WbSupplyRepository(), new WbSyncStateRepository(), new WbSyncRunRepository());
    }

    WbSupplySyncService(WbApiClient apiClient, WbSupplyRepository supplyRepository, WbSyncStateRepository syncStateRepository, WbSyncRunRepository syncRunRepository) {
        this.apiClient = apiClient;
        this.supplyRepository = supplyRepository;
        this.syncStateRepository = syncStateRepository;
        this.syncRunRepository = syncRunRepository;
    }

    public int sync(Shop shop) throws IOException {
        return sync(shop, false);
    }

    public int syncIncremental(Shop shop) throws IOException {
        return sync(shop, true);
    }

    public int syncUntilOpenSuppliesFound(Shop shop) throws IOException {
        WbShopSyncState state = syncStateRepository.getShopSyncState(shop.getId());
        long runId = syncRunRepository.startSyncRun(shop.getId(), "supplies_bootstrap");
        int read = 0;
        int written = 0;
        long next = Math.max(0L, state.suppliesNext());
        boolean foundOpenSupplies = supplyRepository.countOpenSupplies(shop.getId()) > 0;
        try {
            for (int page = 0; page < INITIAL_OPEN_SUPPLY_SCAN_PAGES; page++) {
                WbSuppliesResponse response = apiClient.getSupplies(shop.getApiKey(), next, PAGE_LIMIT);
                if (response == null || response.getSupplies() == null || response.getSupplies().isEmpty()) {
                    syncStateRepository.updateSuppliesCursor(shop.getId(), next, Instant.now().toString());
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }

                supplyRepository.saveSupplies(shop.getId(), response.getSupplies());
                read += response.getSupplies().size();
                written += response.getSupplies().size();
                if (response.getNext() != null) {
                    next = response.getNext();
                }
                syncStateRepository.updateSuppliesCursor(shop.getId(), next, Instant.now().toString());

                boolean pageHasOpen = response.getSupplies().stream().anyMatch(supply -> Boolean.FALSE.equals(supply.getDone()));
                foundOpenSupplies = foundOpenSupplies || pageHasOpen;
                if (response.getSupplies().size() < PAGE_LIMIT || response.getNext() == null) {
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }
                if (foundOpenSupplies) {
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }
            }
            syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
            return written;
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Bootstrap sync supplies thất bại cho shop {}", shop.getId(), ex);
            syncStateRepository.saveSyncError(shop.getId(), ex.getMessage());
            syncRunRepository.finishSyncRun(runId, false, read, written, ex instanceof WbApiException wb ? String.valueOf(wb.getStatusCode()) : "local_error", ex.getMessage());
            throw ex;
        }
    }

    private int sync(Shop shop, boolean singlePageOnly) throws IOException {
        WbShopSyncState state = syncStateRepository.getShopSyncState(shop.getId());
        long runId = syncRunRepository.startSyncRun(shop.getId(), "supplies");
        int read = 0;
        int written = 0;
        long next = Math.max(0L, state.suppliesNext());
        try {
            while (true) {
                WbSuppliesResponse response = apiClient.getSupplies(shop.getApiKey(), next, PAGE_LIMIT);
                if (response == null || response.getSupplies() == null || response.getSupplies().isEmpty()) {
                    syncStateRepository.updateSuppliesCursor(shop.getId(), next, Instant.now().toString());
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }

                supplyRepository.saveSupplies(shop.getId(), response.getSupplies());
                read += response.getSupplies().size();
                written += response.getSupplies().size();
                if (response.getNext() != null) {
                    next = response.getNext();
                }
                syncStateRepository.updateSuppliesCursor(shop.getId(), next, Instant.now().toString());
                if (singlePageOnly || response.getSupplies().size() < PAGE_LIMIT) {
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Sync supplies thất bại cho shop {}", shop.getId(), ex);
            syncStateRepository.saveSyncError(shop.getId(), ex.getMessage());
            syncRunRepository.finishSyncRun(runId, false, read, written, ex instanceof WbApiException wb ? String.valueOf(wb.getStatusCode()) : "local_error", ex.getMessage());
            throw ex;
        }
    }

    public int syncRecentSupplyDetails(Shop shop) throws IOException {
        List<String> supplyIds = supplyRepository.getRecentSupplyIds(shop.getId(), RECENT_SUPPLY_DETAIL_LIMIT);
        return syncSupplyDetails(shop, supplyIds);
    }

    public int syncOpenSupplyDetails(Shop shop) throws IOException {
        List<String> supplyIds = supplyRepository.getOpenSupplyIds(shop.getId(), OPEN_SUPPLY_DETAIL_LIMIT);
        return syncSupplyDetails(shop, supplyIds);
    }

    public int syncOpenSupplyCounts(Shop shop) throws IOException {
        List<String> supplyIds = supplyRepository.getOpenSupplyIds(shop.getId(), OPEN_SUPPLY_DETAIL_LIMIT);
        return syncSupplyCounts(shop, supplyIds);
    }

    public int refreshOpenSuppliesFromStart(Shop shop) throws IOException {
        long runId = syncRunRepository.startSyncRun(shop.getId(), "supplies_refetch");
        int read = 0;
        int written = 0;
        long next = 0L;
        try {
            boolean foundOpenSupplies = supplyRepository.countOpenSupplies(shop.getId()) > 0;
            for (int page = 0; page < INITIAL_OPEN_SUPPLY_SCAN_PAGES; page++) {
                WbSuppliesResponse response = apiClient.getSupplies(shop.getApiKey(), next, PAGE_LIMIT);
                if (response == null || response.getSupplies() == null || response.getSupplies().isEmpty()) {
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }

                supplyRepository.saveSupplies(shop.getId(), response.getSupplies());
                read += response.getSupplies().size();
                written += response.getSupplies().size();
                boolean pageHasOpen = response.getSupplies().stream().anyMatch(supply -> Boolean.FALSE.equals(supply.getDone()));
                foundOpenSupplies = foundOpenSupplies || pageHasOpen;

                if (response.getSupplies().size() < PAGE_LIMIT || response.getNext() == null || foundOpenSupplies) {
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }
                next = response.getNext();
            }
            syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
            return written;
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Refetch supplies thất bại cho shop {}", shop.getId(), ex);
            syncStateRepository.saveSyncError(shop.getId(), ex.getMessage());
            syncRunRepository.finishSyncRun(runId, false, read, written, ex instanceof WbApiException wb ? String.valueOf(wb.getStatusCode()) : "local_error", ex.getMessage());
            throw ex;
        }
    }

    private int syncSupplyDetails(Shop shop, List<String> supplyIds) throws IOException {
        if (supplyIds.isEmpty()) {
            return 0;
        }
        int written = 0;
        IOException firstError = null;
        for (String supplyId : supplyIds) {
            try {
                WbSupplyDto detail = apiClient.getSupplyDetail(shop.getApiKey(), supplyId);
                if (detail != null) {
                    supplyRepository.saveSupplies(shop.getId(), List.of(detail));
                    written++;
                }
            } catch (IOException ex) {
                if (isNotFound(ex)) {
                    removeMissingSupply(shop.getId(), supplyId);
                    continue;
                }
                if (isRateLimited(ex)) {
                    LOGGER.warn("Bỏ qua sync chi tiết supply {} cho shop {} vì WB Marketplace API đang rate limit: {}",
                            supplyId, shop.getId(), ex.getMessage());
                    continue;
                }
                LOGGER.warn("Bỏ qua lỗi sync chi tiết supply {} cho shop {}", supplyId, shop.getId(), ex);
                if (firstError == null) {
                    firstError = ex;
                }
            }
        }
        if (written == 0 && firstError != null) {
            throw firstError;
        }
        return written;
    }

    private int syncSupplyCounts(Shop shop, List<String> supplyIds) throws IOException {
        if (supplyIds.isEmpty()) {
            return 0;
        }
        int written = 0;
        IOException firstError = null;
        for (String supplyId : supplyIds) {
            try {
                WbSupplyOrderIdsResponse response = apiClient.getSupplyOrderIds(shop.getApiKey(), supplyId);
                int count = response == null || response.getOrderIds() == null ? 0 : response.getOrderIds().size();
                supplyRepository.updateSupplyOrderCount(shop.getId(), supplyId, count);
                written++;
            } catch (IOException ex) {
                if (isNotFound(ex)) {
                    removeMissingSupply(shop.getId(), supplyId);
                    continue;
                }
                if (isRateLimited(ex)) {
                    LOGGER.warn("Bỏ qua sync số lượng supply {} cho shop {} vì WB Marketplace API đang rate limit: {}",
                            supplyId, shop.getId(), ex.getMessage());
                    continue;
                }
                LOGGER.warn("Bỏ qua lỗi sync số lượng supply {} cho shop {}", supplyId, shop.getId(), ex);
                if (firstError == null) {
                    firstError = ex;
                }
            }
        }
        if (written == 0 && firstError != null) {
            throw firstError;
        }
        return written;
    }

    private boolean isNotFound(IOException ex) {
        return ex instanceof WbApiException wb && wb.isStatus(404);
    }

    private boolean isRateLimited(IOException ex) {
        return ex instanceof WbApiException wb && wb.isRateLimited();
    }

    private void removeMissingSupply(int shopId, String supplyId) {
        int removed = supplyRepository.deleteSupply(shopId, supplyId);
        LOGGER.info("Removed {} local stale WB supply row(s) after authoritative 404. shopId={}, supplyId={}",
                removed, shopId, supplyId);
    }
}
