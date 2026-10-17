package com.vncode.app.integration.wb;

import com.vncode.app.models.Shop;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import static com.vncode.app.features.license.LicenseModels.Permission.SELLER_API;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

public class WbSupplySyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbSupplySyncService.class);
    private static final int PAGE_LIMIT = 20;
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
        LicenseRuntime.require(SELLER_API);
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        WbShopSyncState state = syncStateRepository.getShopSyncState(shop.getId());
        long runId = syncRunRepository.startSyncRun(shop.getId(), "supplies_bootstrap");
        int read = 0;
        int written = 0;
        long next = Math.max(0L, state.suppliesNext());
        boolean foundOpenSupplies = supplyRepository.countOpenSupplies(shop.getId()) > 0;
        try {
            for (int page = 0; page < INITIAL_OPEN_SUPPLY_SCAN_PAGES; page++) {
                LicenseRuntime.require(SELLER_API);
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                WbSuppliesResponse response = apiClient.getSupplies(shop.getApiKey(), next, PAGE_LIMIT);
                WbFbsResponseValidator.supplies(response, next, PAGE_LIMIT);
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                if (response.getSupplies().isEmpty()) {
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
        } catch (LicenseDeniedException paused) {
            syncRunRepository.finishSyncRun(runId,false,read,written,"license_required",paused.getMessage());throw paused;
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Bootstrap sync supplies thất bại cho shop {}", shop.getId(), ex);
            reportFailure(shop, runId, read, written, ex);
            throw ex;
        }
    }

    private int sync(Shop shop, boolean singlePageOnly) throws IOException {
        LicenseRuntime.require(SELLER_API);
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        WbShopSyncState state = syncStateRepository.getShopSyncState(shop.getId());
        long runId = syncRunRepository.startSyncRun(shop.getId(), "supplies");
        int read = 0;
        int written = 0;
        long next = Math.max(0L, state.suppliesNext());
        try {
            for (int page = 0; page < 20_000; page++) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("FBS_SYNC_CANCELLED");
                LicenseRuntime.require(SELLER_API);
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                WbSuppliesResponse response = apiClient.getSupplies(shop.getApiKey(), next, PAGE_LIMIT);
                WbFbsResponseValidator.supplies(response, next, PAGE_LIMIT);
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                if (response.getSupplies().isEmpty()) {
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
            throw new IOException("FBS_CURSOR_LIMIT");
        } catch (LicenseDeniedException paused) {
            syncRunRepository.finishSyncRun(runId,false,read,written,"license_required",paused.getMessage());throw paused;
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Sync supplies thất bại cho shop {}", shop.getId(), ex);
            reportFailure(shop, runId, read, written, ex);
            throw ex;
        }
    }

    public int syncRecentSupplyDetails(Shop shop) throws IOException {
        LicenseRuntime.require(SELLER_API);
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        List<String> supplyIds = supplyRepository.getRecentSupplyIds(shop.getId(), RECENT_SUPPLY_DETAIL_LIMIT);
        return syncSupplyDetails(shop, supplyIds);
    }

    public int syncOpenSupplyDetails(Shop shop) throws IOException {
        LicenseRuntime.require(SELLER_API);
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        List<String> supplyIds = supplyRepository.getOpenSupplyIds(shop.getId(), OPEN_SUPPLY_DETAIL_LIMIT);
        return syncSupplyDetails(shop, supplyIds);
    }

    public int syncOpenSupplyCounts(Shop shop) throws IOException {
        LicenseRuntime.require(SELLER_API);
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        List<String> supplyIds = supplyRepository.getOpenSupplyIds(shop.getId(), OPEN_SUPPLY_DETAIL_LIMIT);
        return syncSupplyCounts(shop, supplyIds);
    }

    public int refreshOpenSuppliesFromStart(Shop shop) throws IOException {
        LicenseRuntime.require(SELLER_API);
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        long runId = syncRunRepository.startSyncRun(shop.getId(), "supplies_refetch");
        int read = 0;
        int written = 0;
        long next = 0L;
        try {
            boolean foundOpenSupplies = supplyRepository.countOpenSupplies(shop.getId()) > 0;
            for (int page = 0; page < INITIAL_OPEN_SUPPLY_SCAN_PAGES; page++) {
                LicenseRuntime.require(SELLER_API);
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                WbSuppliesResponse response = apiClient.getSupplies(shop.getApiKey(), next, PAGE_LIMIT);
                WbFbsResponseValidator.supplies(response, next, PAGE_LIMIT);
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                if (response.getSupplies().isEmpty()) {
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
        } catch (LicenseDeniedException paused) {
            syncRunRepository.finishSyncRun(runId,false,read,written,"license_required",paused.getMessage());throw paused;
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Refetch supplies thất bại cho shop {}", shop.getId(), ex);
            reportFailure(shop, runId, read, written, ex);
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
            LicenseRuntime.require(SELLER_API);
            com.vncode.app.integration.listing.ListingIdentity.require(shop);
            try {
                WbSupplyDto detail = apiClient.getSupplyDetail(shop.getApiKey(), supplyId);
                if (detail == null || !supplyId.equals(detail.getId()) || detail.getDone() == null) throw new IOException("FBS_FOREIGN_SUPPLY");
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                supplyRepository.saveSupplies(shop.getId(), List.of(detail));
                written++;
            } catch (IOException ex) {
                if (isNotFound(ex)) {
                    com.vncode.app.integration.listing.ListingIdentity.require(shop);
                    removeMissingSupply(shop.getId(), supplyId);
                    continue;
                }
                if (isRateLimited(ex)) {
                    LOGGER.warn("Bỏ qua sync chi tiết supply {} cho shop {} vì WB Marketplace API đang rate limit: {}",
                            supplyId, shop.getId(), ex.getMessage());
                    if (firstError == null) firstError = ex;
                    break;
                }
                LOGGER.warn("Bỏ qua lỗi sync chi tiết supply {} cho shop {}", supplyId, shop.getId(), ex);
                if (firstError == null) {
                    firstError = ex;
                }
            }
        }
        if (firstError != null) {
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
            LicenseRuntime.require(SELLER_API);
            com.vncode.app.integration.listing.ListingIdentity.require(shop);
            try {
                WbSupplyOrderIdsResponse response = apiClient.getSupplyOrderIds(shop.getApiKey(), supplyId);
                int count = WbFbsResponseValidator.supplyIds(response).size();
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                supplyRepository.updateSupplyOrderCount(shop.getId(), supplyId, count);
                written++;
            } catch (IOException ex) {
                if (isNotFound(ex)) {
                    com.vncode.app.integration.listing.ListingIdentity.require(shop);
                    removeMissingSupply(shop.getId(), supplyId);
                    continue;
                }
                if (isRateLimited(ex)) {
                    LOGGER.warn("Bỏ qua sync số lượng supply {} cho shop {} vì WB Marketplace API đang rate limit: {}",
                            supplyId, shop.getId(), ex.getMessage());
                    if (firstError == null) firstError = ex;
                    break;
                }
                LOGGER.warn("Bỏ qua lỗi sync số lượng supply {} cho shop {}", supplyId, shop.getId(), ex);
                if (firstError == null) {
                    firstError = ex;
                }
            }
        }
        if (firstError != null) {
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
    private void reportFailure(Shop shop, long run, int read, int written, Exception primary) {
        String code = com.vncode.app.features.sync.SyncFailure.of(primary, 0).code();
        try { syncStateRepository.saveSyncError(shop.getId(), code); }
        catch (RuntimeException reporting) { primary.addSuppressed(reporting); }
        try { syncRunRepository.finishSyncRun(run, false, read, written, code, code); }
        catch (RuntimeException reporting) { primary.addSuppressed(reporting); }
    }

}
