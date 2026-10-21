package com.vncode.app.features.gtinsync;

import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import java.time.*;
import java.util.function.Function;
import java.util.*;
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
        if(!RegisteredGtinProof.currentObservation(observed,now))throw new IllegalArgumentException("unverified_catalog_gtin");
        adapter.validateRegistration(newGtin);
        LicenseRuntime.require(Permission.SELLER_API);
        var product=adapter.read(key);
        if(!product.key().equals(key))throw new IllegalArgumentException("product_mismatch");
        adapter.validateProduct(key);
        adapter.validateIdentity(product,observed);
        if(RegisteredGtinProof.contains(product.barcodes(),newGtin))throw new IllegalArgumentException("already_present");
        if(operation==Operation.REPLACE && (oldGtin==null || oldGtin.isBlank() || RegisteredGtinProof.sameCode(oldGtin,newGtin) || !RegisteredGtinProof.contains(product.barcodes(),oldGtin)))
            throw new IllegalArgumentException("old_gtin_not_found");
        if(operation==Operation.ADD && oldGtin!=null && !oldGtin.isBlank())throw new IllegalArgumentException("unexpected_old_gtin");
        LicenseRuntime.require(Permission.SELLER_API);
        if(adapter.conflicts(key,newGtin))throw new IllegalArgumentException("gtin_on_another_product");
        return new Preview(product,operation,oldGtin,newGtin,capability);
    }
    /** ADD-only batch validation; production adapters reuse a fresh, run-scoped catalog index. */
    public List<Preview> createAdds(Map<ProductKey,String> requested)throws Exception {
        if(requested.isEmpty()||requested.size()>GtinBatchAdapter.MAX_PLAN_ROWS)throw new IllegalArgumentException("invalid_selection");
        var first=requested.keySet().iterator().next();var adapter=adapters.apply(first);
        if(!(adapter instanceof GtinBatchAdapter batch)){var result=new ArrayList<Preview>();for(var e:requested.entrySet())result.add(create(e.getKey(),Operation.ADD,"",e.getValue()));return List.copyOf(result);}
        if(requested.keySet().stream().anyMatch(k->k.shopId()!=first.shopId()||k.marketplace()!=first.marketplace()))throw new IllegalArgumentException("mixed_shops");
        var owners=new HashMap<String,ProductKey>();for(var entry:requested.entrySet()){var previous=owners.putIfAbsent(RegisteredGtinProof.canonicalCode(entry.getValue()),entry.getKey());if(previous!=null&&!previous.equals(entry.getKey()))throw new IllegalArgumentException("gtin_on_another_product");}
        LicenseRuntime.require(Permission.SELLER_API);batch.prepareBatch(requested.keySet());
        var observed=repository.catalogItems(first.shopId(),first.marketplace(),requested.values());batch.validateRegistrations(requested.values());
        var now=clock.instant();var result=new ArrayList<Preview>();
        for(var e:requested.entrySet()){
            var key=e.getKey();String gtin=e.getValue();var capability=adapter.capability(key);var source=observed.get(gtin);
            if(!capability.add())throw new IllegalArgumentException("unsupported_operation");
            if(!RegisteredGtinValidator.isValid(gtin))throw new IllegalArgumentException("invalid_gtin");
            if(!RegisteredGtinProof.currentObservation(source,now))throw new IllegalArgumentException("unverified_catalog_gtin");
            var product=adapter.read(key);if(!product.key().equals(key))throw new IllegalArgumentException("product_mismatch");adapter.validateProduct(key);adapter.validateIdentity(product,source);
            if(RegisteredGtinProof.contains(product.barcodes(),gtin))throw new IllegalArgumentException("already_present");
            if(adapter.conflicts(key,gtin))throw new IllegalArgumentException("gtin_on_another_product");
            result.add(new Preview(product,Operation.ADD,"",gtin,capability));
        }return List.copyOf(result);
    }
}
