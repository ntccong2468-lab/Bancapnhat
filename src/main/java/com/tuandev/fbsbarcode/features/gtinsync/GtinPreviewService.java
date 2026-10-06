package com.tuandev.fbsbarcode.features.gtinsync;

import java.time.*;
import java.util.function.Function;
import static com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.*;

public final class GtinPreviewService {
    private final GtinSyncRepository repository;
    private final Function<ProductKey,GtinMarketplaceAdapter> adapters;
    private final Clock clock;
    public GtinPreviewService(GtinSyncRepository repository,Function<ProductKey,GtinMarketplaceAdapter> adapters){this(repository,adapters,Clock.systemUTC());}
    public GtinPreviewService(GtinSyncRepository repository,Function<ProductKey,GtinMarketplaceAdapter> adapters,Clock clock){this.repository=repository;this.adapters=adapters;this.clock=clock;}
    public Preview create(ProductKey key,Operation operation,String oldGtin,String newGtin) throws Exception {
        var adapter=adapters.apply(key);var capability=adapter.capability(key);
        if(!capability.supports(operation))throw new IllegalArgumentException("unsupported_operation");
        if(!RegisteredGtinValidator.isValid(newGtin))throw new IllegalArgumentException("invalid_gtin");
        var now=clock.instant();
        boolean registered=repository.catalog(key.shopId(),key.marketplace()).stream()
                .anyMatch(g -> g.gtin().equals(newGtin) && g.published() && g.tradeUnit()
                        && !g.syncedAt().isBefore(now.minus(Duration.ofHours(24))) && !g.syncedAt().isAfter(now.plusSeconds(300)));
        if(!registered)throw new IllegalArgumentException("unverified_catalog_gtin");
        var product=adapter.read(key);
        if(!product.key().equals(key))throw new IllegalArgumentException("product_mismatch");
        if(product.barcodes().contains(newGtin))throw new IllegalArgumentException("already_present");
        if(operation==Operation.REPLACE && (oldGtin==null || oldGtin.isBlank() || oldGtin.equals(newGtin) || !product.barcodes().contains(oldGtin)))
            throw new IllegalArgumentException("old_gtin_not_found");
        if(operation==Operation.ADD && oldGtin!=null && !oldGtin.isBlank())throw new IllegalArgumentException("unexpected_old_gtin");
        if(adapter.conflicts(key,newGtin))throw new IllegalArgumentException("gtin_on_another_product");
        return new Preview(product,operation,oldGtin,newGtin,capability);
    }
}
