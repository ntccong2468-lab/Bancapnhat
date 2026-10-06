package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.models.Shop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class WbProductSyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbProductSyncService.class);
    private static final int PAGE_LIMIT = 100;

    private final WbApiClient apiClient;
    private final WbProductRepository productRepository;
    private final WbSyncStateRepository syncStateRepository;
    private final WbSyncRunRepository syncRunRepository;

    public WbProductSyncService() {
        this(new WbApiClient(), new WbProductRepository(), new WbSyncStateRepository(), new WbSyncRunRepository());
    }

    WbProductSyncService(WbApiClient apiClient, WbProductRepository productRepository, WbSyncStateRepository syncStateRepository, WbSyncRunRepository syncRunRepository) {
        this.apiClient = apiClient;
        this.productRepository = productRepository;
        this.syncStateRepository = syncStateRepository;
        this.syncRunRepository = syncRunRepository;
    }

    public int sync(Shop shop) throws IOException {
        WbShopSyncState state = syncStateRepository.getShopSyncState(shop.getId());
        long runId = syncRunRepository.startSyncRun(shop.getId(), "products");
        int read = 0;
        int written = 0;
        String cursorUpdatedAt = state.productsCursorUpdatedAt();
        Long cursorNmId = state.productsCursorNmId();
        try {
            while (true) {
                WbProductCardsResponse response = apiClient.getProductCards(shop.getApiKey(), "ru", cursorUpdatedAt, cursorNmId, PAGE_LIMIT);
                if (response == null || response.getCards() == null || response.getCards().isEmpty()) {
                    syncStateRepository.updateProductsCursor(shop.getId(), cursorUpdatedAt, cursorNmId, Instant.now().toString());
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }

                productRepository.saveProductBatch(shop.getId(), response.getCards());
                read += response.getCards().size();
                written += response.getCards().size();

                if (response.getCursor() != null) {
                    cursorUpdatedAt = response.getCursor().getUpdatedAt();
                    cursorNmId = response.getCursor().getNmID();
                }

                syncStateRepository.updateProductsCursor(shop.getId(), cursorUpdatedAt, cursorNmId, Instant.now().toString());
                Integer total = response.getCursor() == null ? null : response.getCursor().getTotal();
                if (total == null || total < PAGE_LIMIT) {
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof WbApiException wb && wb.isRateLimited()) {
                LOGGER.warn("Sync products bị WB rate limit cho shop {}: {}", shop.getId(), wb.getMessage());
            } else {
                LOGGER.error("Sync products thất bại cho shop {}", shop.getId(), ex);
            }
            syncStateRepository.saveSyncError(shop.getId(), ex.getMessage());
            syncRunRepository.finishSyncRun(runId, false, read, written, ex instanceof WbApiException wb ? String.valueOf(wb.getStatusCode()) : "local_error", ex.getMessage());
            throw ex;
        }
    }

    public int recoverProductsByNmIds(Shop shop, List<Long> nmIds) throws IOException {
        Set<Long> remaining = new LinkedHashSet<>();
        if (nmIds != null) {
            for (Long nmId : nmIds) {
                if (nmId != null && nmId > 0) {
                    remaining.add(nmId);
                }
            }
        }
        if (remaining.isEmpty()) {
            return 0;
        }

        long runId = syncRunRepository.startSyncRun(shop.getId(), "products_recovery");
        int read = 0;
        int written = 0;
        String cursorUpdatedAt = null;
        Long cursorNmId = null;
        try {
            while (true) {
                WbProductCardsResponse response = apiClient.getProductCards(shop.getApiKey(), "ru", cursorUpdatedAt, cursorNmId, PAGE_LIMIT);
                if (response == null || response.getCards() == null || response.getCards().isEmpty()) {
                    break;
                }

                read += response.getCards().size();
                List<WbProductCard> foundCards = response.getCards().stream()
                        .filter(card -> card.getNmID() != null && remaining.contains(card.getNmID()))
                        .toList();
                if (!foundCards.isEmpty()) {
                    productRepository.saveProductBatch(shop.getId(), foundCards);
                    written += foundCards.size();
                    foundCards.stream()
                            .map(WbProductCard::getNmID)
                            .forEach(remaining::remove);
                    if (remaining.isEmpty()) {
                        syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                        return written;
                    }
                }

                if (response.getCursor() != null) {
                    cursorUpdatedAt = response.getCursor().getUpdatedAt();
                    cursorNmId = response.getCursor().getNmID();
                }
                Integer total = response.getCursor() == null ? null : response.getCursor().getTotal();
                if (total == null || total < PAGE_LIMIT) {
                    break;
                }
            }

            String missing = String.join(", ", remaining.stream().map(String::valueOf).toList());
            String message = "Sản phẩm không tồn tại trên WB: nmId " + missing + ". Vui lòng kiểm tra lại thùng rác.";
            syncRunRepository.finishSyncRun(runId, false, read, written, "not_found", message);
            throw new IOException(message);
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof WbApiException wb && wb.isRateLimited()) {
                LOGGER.warn("Recover products bị WB rate limit cho shop {}: {}", shop.getId(), wb.getMessage());
            } else if (!"not_found".equals(errorCode(ex))) {
                LOGGER.error("Recover products thất bại cho shop {}", shop.getId(), ex);
            }
            if (!"not_found".equals(errorCode(ex))) {
                syncRunRepository.finishSyncRun(runId, false, read, written, ex instanceof WbApiException wb ? String.valueOf(wb.getStatusCode()) : "local_error", ex.getMessage());
            }
            throw ex;
        }
    }

    private String errorCode(Exception ex) {
        String message = ex.getMessage();
        return message != null && message.startsWith("Sản phẩm không tồn tại trên WB:") ? "not_found" : "";
    }
}
