package com.vncode.app.features.sync;

import com.vncode.app.features.gtinsync.GtinSyncCoordinator;
import com.vncode.app.features.gtinsync.GtinSyncRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.WbGtinAdapter;
import com.vncode.app.models.Shop;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** Resume persisted WB GTIN jobs without implicitly granting any new mutation consent. */
public final class WbGtinQueueAutoResume {
    private final GtinSyncRepository repository;
    private final GtinSyncCoordinator coordinator;
    private final com.vncode.app.integration.wb.WbGtinPriceRepair prices;
    public WbGtinQueueAutoResume(Shop shop) {
        this.repository=new GtinSyncRepository();this.prices=new com.vncode.app.integration.wb.WbGtinPriceRepair(shop);
        this.coordinator=new GtinSyncCoordinator(repository,key->{
            if(key.shopId()!=shop.getId()||key.marketplace()!=Marketplace.WILDBERRIES)throw new SecurityException("SYNC_REVIEW_OWNER_CHANGED");
            return new WbGtinAdapter(shop);
        },Clock.systemUTC(),1,Duration.ZERO);
    }
    public WbGtinQueueAutoResume(GtinSyncRepository repository,GtinSyncCoordinator coordinator){this(repository,coordinator,null);}
    public WbGtinQueueAutoResume(GtinSyncRepository repository,GtinSyncCoordinator coordinator,com.vncode.app.integration.wb.WbGtinPriceRepair prices){this.repository=Objects.requireNonNull(repository);this.coordinator=Objects.requireNonNull(coordinator);this.prices=prices;}
    public boolean step(Shop shop){
        if(shop.getMarketplace()!=Marketplace.WILDBERRIES)return false;
        boolean pendingReceipts;
        try(var deferral=com.vncode.app.shared.RateLimitDeferral.enter()){
            try {pendingReceipts=(prices==null?new com.vncode.app.integration.wb.WbGtinPriceRepair(shop):prices).reconcileCancelled();}
            catch(java.io.IOException|com.vncode.app.shared.RateLimitDeferral.Deferred unavailable){pendingReceipts=true;}
            if(hasRunnable(shop))coordinator.runPending(shop.getId(),Marketplace.WILDBERRIES);
        }
        return pendingReceipts||hasRunnable(shop);
    }
    private boolean hasRunnable(Shop shop){return repository.pending(shop.getId(),Marketplace.WILDBERRIES).stream().map(item->item.jobId()).distinct().anyMatch(job->!repository.paused(job));}
}
