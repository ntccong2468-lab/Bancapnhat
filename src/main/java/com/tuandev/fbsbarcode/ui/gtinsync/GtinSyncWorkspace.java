package com.tuandev.fbsbarcode.ui.gtinsync;

import com.tuandev.fbsbarcode.features.gtinsync.*;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.integration.wb.*;
import com.tuandev.fbsbarcode.integration.ozon.*;
import com.tuandev.fbsbarcode.integration.znack.registration.NationalCatalogGtinSource;
import com.tuandev.fbsbarcode.models.Shop;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.*;

/** Resolves credentials at the original shop boundary; never exposes them through view state. */
final class GtinSyncWorkspace {
    record Data(List<RegisteredGtin> catalog,List<ProductSnapshot> products,List<JobItem> history,Map<ProductKey,String> mappings) {}
    final GtinSyncRepository repository;
    final GtinSyncCoordinator coordinator;
    private final Function<ProductKey,GtinMarketplaceAdapter> adapters;
    GtinSyncWorkspace(){this(new GtinSyncRepository(),null);}
    GtinSyncWorkspace(GtinSyncRepository repository,Function<ProductKey,GtinMarketplaceAdapter> adapters) {
        this.repository=Objects.requireNonNull(repository);this.adapters=adapters;
        coordinator=new GtinSyncCoordinator(repository,this::adapter,Clock.systemUTC(),6,Duration.ofSeconds(2));
    }
    Shop require(int id,Marketplace marketplace) {
        var shop=new ShopRepository().findById(id);
        if(shop==null || shop.getMarketplace()!=marketplace)throw new IllegalArgumentException("shop_mismatch");return shop;
    }
    GtinMarketplaceAdapter adapter(ProductKey key) {
        var shop=require(key.shopId(),key.marketplace());
        if(adapters!=null)return adapters.apply(key);
        return key.marketplace()==Marketplace.WILDBERRIES?new WbGtinAdapter(shop):new OzonGtinAdapter(shop);
    }
    Data load(GtinSyncViewModel.LoadToken token)throws Exception {
        var shop=require(token.shopId(),token.marketplace());
        var products=shop.getMarketplace()==Marketplace.WILDBERRIES?
                new WbGtinProductReader(shop,new WbApiClient()).list():
                new OzonGtinProductReader(shop,new OzonApiClient(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey()))).list();
        var mappings=new LinkedHashMap<ProductKey,String>();
        for(var product:products) {
            String gtin=repository.mapping(product.key());if(!gtin.isBlank())mappings.put(product.key(),gtin);
        }
        return new Data(repository.catalog(shop.getId(),shop.getMarketplace()),products,repository.history(shop.getId(),shop.getMarketplace()),Map.copyOf(mappings));
    }
    void syncCatalog(GtinSyncViewModel.LoadToken token)throws Exception {
        var shop=require(token.shopId(),token.marketplace());
        new RegisteredGtinSyncService(NationalCatalogGtinSource.forShop(shop),repository).refresh(shop.getId(),shop.getMarketplace());
    }
}
