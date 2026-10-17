package com.vncode.app.integration.wb;

import com.vncode.app.models.Shop;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import static com.vncode.app.features.license.LicenseModels.Permission.SELLER_API;
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

    public int sync(Shop shop) throws IOException { return sync(shop,false,cards->{}); }

    /** A registration snapshot must revisit every page, including existing cards. */
    public int syncFromStart(Shop shop,java.util.function.Consumer<List<WbProductCard>> page) throws IOException {
        return sync(shop,true,page);
    }
    private int sync(Shop shop,boolean complete,java.util.function.Consumer<List<WbProductCard>> page) throws IOException {
        LicenseRuntime.require(SELLER_API);
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        WbShopSyncState state = syncStateRepository.getShopSyncState(shop.getId());
        long runId = syncRunRepository.startSyncRun(shop.getId(), "products");
        int read = 0;
        int written = 0;
        String cursorUpdatedAt = complete?null:state.productsCursorUpdatedAt();
        Long cursorNmId = complete?null:state.productsCursorNmId();
        var cursors=new java.util.HashSet<String>();
        if(cursorUpdatedAt!=null&&cursorNmId!=null)cursors.add(cursorUpdatedAt+"/"+cursorNmId);
        int pages=0;
        try {
            while (true) {
                if(++pages>20000 || Thread.currentThread().isInterrupted())throw new IOException("WB_SNAPSHOT_INTERRUPTED_OR_LIMIT");
                LicenseRuntime.require(SELLER_API);
                WbProductCardsResponse response = apiClient.getProductCards(shop.getApiKey(), "ru", cursorUpdatedAt, cursorNmId, PAGE_LIMIT);
                if(response==null || response.getCards()==null || response.getCards().size()>PAGE_LIMIT)throw new IOException("WB_INCOMPLETE_PRODUCT_PAGE");
                if(response.getCursor()==null || response.getCursor().getTotal()==null || response.getCursor().getTotal()<response.getCards().size() || response.getCursor().getTotal()>PAGE_LIMIT)throw new IOException("WB_INCOMPLETE_PRODUCT_CURSOR");
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                if (response.getCards().isEmpty()) {
                    if(response.getCursor().getTotal()!=0)throw new IOException("WB_INCOMPLETE_PRODUCT_CURSOR");
                    syncStateRepository.updateProductsCursor(shop.getId(), cursorUpdatedAt, cursorNmId, Instant.now().toString());
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }

                var next=response.getCursor();
                if(next.getUpdatedAt()==null || next.getUpdatedAt().isBlank() || next.getNmID()==null || next.getNmID()<=0)throw new IOException("WB_INCOMPLETE_PRODUCT_CURSOR");
                if(!cursors.add(next.getUpdatedAt()+"/"+next.getNmID()))throw new IOException("WB_REPEATED_PRODUCT_CURSOR");
                var ids=new java.util.HashSet<Long>();
                for(var card:response.getCards())if(card==null || card.getNmID()==null || card.getNmID()<=0 || !ids.add(card.getNmID()))throw new IOException("WB_INCOMPLETE_PRODUCT_PAGE");
                productRepository.saveProductBatch(shop.getId(), response.getCards());
                page.accept(List.copyOf(response.getCards()));
                read += response.getCards().size();
                written += response.getCards().size();

                if (response.getCursor() != null) {
                    cursorUpdatedAt = response.getCursor().getUpdatedAt();
                    cursorNmId = response.getCursor().getNmID();
                }

                syncStateRepository.saveProductsCheckpoint(shop.getId(), cursorUpdatedAt, cursorNmId);
                Integer total = response.getCursor() == null ? null : response.getCursor().getTotal();
                if (total == null || total < PAGE_LIMIT) {
                    syncStateRepository.updateProductsCursor(shop.getId(), cursorUpdatedAt, cursorNmId, Instant.now().toString());
                    syncRunRepository.finishSyncRun(runId, true, read, written, null, null);
                    return written;
                }
            }
        } catch (LicenseDeniedException paused) {
            reportFailure(shop,runId,read,written,"license_required",paused);throw paused;
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof WbApiException wb && wb.isRateLimited()) {
                LOGGER.warn("Sync products bị WB rate limit cho shop {}: {}", shop.getId(), wb.getMessage());
            } else {
                LOGGER.error("Sync products thất bại cho shop {}", shop.getId(), ex);
            }
            reportFailure(shop,runId,read,written,ex instanceof WbApiException wb ? String.valueOf(wb.getStatusCode()) : "local_error",ex);
            throw ex;
        }
    }

    private void reportFailure(Shop shop,long run,int read,int written,String code,Exception failure){
        try{syncStateRepository.saveSyncError(shop.getId(),failure.getMessage());}catch(RuntimeException reporting){failure.addSuppressed(reporting);}
        try{syncRunRepository.finishSyncRun(run,false,read,written,code,failure.getMessage());}catch(RuntimeException reporting){failure.addSuppressed(reporting);}
    }

    public int recoverProductsByNmIds(Shop shop, List<Long> nmIds) throws IOException {
        LicenseRuntime.require(SELLER_API);
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
                LicenseRuntime.require(SELLER_API);
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
        } catch (LicenseDeniedException paused) {
            syncRunRepository.finishSyncRun(runId,false,read,written,"license_required",paused.getMessage());throw paused;
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
