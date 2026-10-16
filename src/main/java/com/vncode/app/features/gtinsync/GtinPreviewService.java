package com.vncode.app.features.gtinsync;

import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import java.time.*;
import java.util.function.Function;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

public final class GtinPreviewService {
    private final GtinSyncRepository repository;
    private final Function<ProductKey,GtinMarketplaceAdapter> adapters;
    private final Clock clock;
    public GtinPreviewService(GtinSyncRepository repository,Function<ProductKey,GtinMarketplaceAdapter> adapters){this(repository,adapters,Clock.systemUTC());}
    public GtinPreviewService(GtinSyncRepository repository,Function<ProductKey,GtinMarketplaceAdapter> adapters,Clock clock){this.repository=repository;this.adapters=adapters;this.clock=clock;}
    public Preview create(ProductKey key,Operation operation,String oldGtin,String newGtin) throws Exception {
        var adapter=adapters.apply(key);var capability=adapter.capability(key);
        if(!capability.supports(operation))throw new IllegalArgumentException(capability.reason().matches("[A-Z][A-Z0-9_]{2,100}")?capability.reason():"unsupported_operation");
        if(!RegisteredGtinValidator.isValid(newGtin))throw new IllegalArgumentException("invalid_gtin");
        var now=clock.instant();
        var observed=repository.catalogItem(key.shopId(),key.marketplace(),newGtin);
        if(!observed.published()||!observed.tradeUnit()||observed.syncedAt().isBefore(now.minus(Duration.ofHours(24)))||observed.syncedAt().isAfter(now.plusSeconds(300)))throw new IllegalArgumentException("unverified_catalog_gtin");
        adapter.validateRegistration(newGtin);
        LicenseRuntime.require(Permission.SELLER_API);
        var product=adapter.read(key);
        if(!product.key().equals(key))throw new IllegalArgumentException("product_mismatch");
        adapter.validateProduct(key);
        if(product.barcodes().contains(newGtin))throw new IllegalArgumentException("already_present");
        if(operation==Operation.REPLACE && (oldGtin==null || oldGtin.isBlank() || oldGtin.equals(newGtin) || !product.barcodes().contains(oldGtin)))
            throw new IllegalArgumentException("old_gtin_not_found");
        if(operation==Operation.ADD && oldGtin!=null && !oldGtin.isBlank())throw new IllegalArgumentException("unexpected_old_gtin");
        LicenseRuntime.require(Permission.SELLER_API);
        if(adapter.conflicts(key,newGtin))throw new IllegalArgumentException("gtin_on_another_product");
        return new Preview(product,operation,oldGtin,newGtin,capability);
    }
}
