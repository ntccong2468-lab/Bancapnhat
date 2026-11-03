package com.vncode.app.features.finance;

import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** User-requested penalty reporting; jobs are persisted and resumed in the background. */
public final class FinancePenaltySyncService {
    private static final LocalDate WB_EARLIEST=LocalDate.of(2024,1,29);
    private final FinancePenaltySyncStore store;
    public FinancePenaltySyncService(){this(new FinancePenaltySyncStore());}
    FinancePenaltySyncService(FinancePenaltySyncStore store){this.store=Objects.requireNonNull(store);}

    public FinancePenaltySyncStore.Job request(Shop shop,LocalDate from,LocalDate to) {
        Objects.requireNonNull(shop,"shop");
        if(shop.getMarketplace()!=Marketplace.WILDBERRIES)
            throw new IllegalStateException("OZON_PENALTY_UNAVAILABLE");
        if(from==null||to==null||from.isBefore(WB_EARLIEST)||from.isAfter(to))
            throw new IllegalArgumentException("WB_PENALTY_INVALID_RANGE");
        var job=store.request(shop,from,to,Instant.now());
        // The scheduler will also resume this persisted job after restart.
        FinanceExecutor.executeSync(()->FinanceSyncScheduler.getInstance().runCoordinatedStep());
        return job;
    }
    public boolean cancel(Shop shop,LocalDate from,LocalDate to){return store.cancel(shop,from,to);}
    public FinancePenaltySyncStore.ScopeStatus status(Shop shop,LocalDate from,LocalDate to){return store.status(shop,from,to);}
    public List<FinancePenaltyEntry> load(Shop shop,LocalDate from,LocalDate to,int limit,int offset){return store.load(shop,from,to,limit,offset);}
    public long count(Shop shop,LocalDate from,LocalDate to){return store.count(shop,from,to);}
}
