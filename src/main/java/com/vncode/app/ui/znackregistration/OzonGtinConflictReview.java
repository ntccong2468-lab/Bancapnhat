package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.catalogsync.CatalogSyncModels;
import com.vncode.app.features.catalogsync.CatalogSyncService;
import com.vncode.app.features.gtinsync.GtinSyncModels;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.sync.SyncRunRepository;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonGtinAdapter;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Re-read a reported marketplace conflict before opening the reviewed new-card workflow. */
final class OzonGtinConflictReview {
    record Confirmed(ProductKey key,String gtin) { }
    interface Probe {
        boolean alreadyOnSelected(GtinSyncModels.ProductKey key,String gtin)throws IOException;
        boolean belongsToAnother(GtinSyncModels.ProductKey key,String gtin)throws IOException;
    }
    private final SyncRunRepository reports=new SyncRunRepository();
    private final Probe probe;
    OzonGtinConflictReview(Shop owner) {
        var adapter=new OzonGtinAdapter(owner);
        probe=new Probe() {
            public boolean alreadyOnSelected(GtinSyncModels.ProductKey key,String gtin)throws IOException {
                return RegisteredGtinProof.contains(adapter.read(key).barcodes(),gtin);
            }
            public boolean belongsToAnother(GtinSyncModels.ProductKey key,String gtin)throws IOException {return adapter.conflicts(key,gtin);}
        };
    }
    OzonGtinConflictReview(Probe probe){this.probe=Objects.requireNonNull(probe);}

    static boolean candidate(Shop owner,SyncRunRepository.Run run,SyncRunRepository.Item row) {
        return owner!=null&&owner.getMarketplace()==Marketplace.OZON&&run!=null&&"znack_marketplace".equals(run.kind())
                &&run.shopId()==owner.getId()&&run.marketplace().equals(Marketplace.OZON.name())
                &&row!=null&&run.id().equals(row.runId())&&"GTIN_ON_ANOTHER_PRODUCT".equals(row.error())
                &&List.of("NEEDS_ACTION","FAILED").contains(row.state())&&!row.productId().startsWith("catalog:")
                &&!row.productId().isBlank()&&!row.variantId().isBlank()&&RegisteredGtinValidator.isValid(row.gtin());
    }

    Confirmed verify(Shop owner,SyncRunRepository.Run run,SyncRunRepository.Item row)throws IOException {
        if(!candidate(owner,run,row))throw new IllegalArgumentException("OZON_GTIN_CONFLICT_REVIEW_CHANGED");
        ListingIdentity.require(owner);reports.assertOwner(owner,run.id());
        requireSame(row,reports.selectedItems(run.id(),List.of(row.id())).get(row.id()));
        // A report may refer to a prepared parent plan. Only a blocked plan without
        // a dispatched child is safe to turn into an offer to create another card.
        if(!row.childJob().isBlank()) {
            var plan=new CatalogSyncService().read(owner,row.childJob());
            var matches=plan.rows().stream().filter(r->r.key().productId().equals(row.productId())&&r.key().variantId().equals(row.variantId())).toList();
            if(matches.size()!=1||matches.getFirst().state()!=CatalogSyncModels.State.BLOCKED
                    ||!matches.getFirst().gtinJob().isBlank()||!"GTIN_ON_ANOTHER_PRODUCT".equals(matches.getFirst().issue())
                    ||matches.getFirst().catalog()==null||!RegisteredGtinProof.sameCode(matches.getFirst().catalog().gtin(),row.gtin()))
                throw new IllegalStateException("OZON_GTIN_CONFLICT_REVIEW_CHANGED");
        }
        var key=new GtinSyncModels.ProductKey(owner.getId(),Marketplace.OZON,row.productId(),row.variantId());
        if(probe.alreadyOnSelected(key,row.gtin())||!probe.belongsToAnother(key,row.gtin()))
            throw new IllegalStateException("OZON_GTIN_CONFLICT_NO_LONGER_CONFIRMED");
        ListingIdentity.require(owner);reports.assertOwner(owner,run.id());
        requireSame(row,reports.selectedItems(run.id(),List.of(row.id())).get(row.id()));
        return new Confirmed(new ProductKey(owner.getId(),Marketplace.OZON,row.productId(),row.variantId()),row.gtin());
    }
    private static void requireSame(SyncRunRepository.Item expected,SyncRunRepository.Item actual) {
        if(!expected.equals(actual))throw new IllegalStateException("OZON_GTIN_CONFLICT_REVIEW_CHANGED");
    }
}
