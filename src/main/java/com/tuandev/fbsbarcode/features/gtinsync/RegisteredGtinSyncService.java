package com.tuandev.fbsbarcode.features.gtinsync;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
public final class RegisteredGtinSyncService {
    private final RegisteredGtinSource source;
    private final GtinSyncRepository repository;
    public RegisteredGtinSyncService(RegisteredGtinSource source,GtinSyncRepository repository) {this.source=source;this.repository=repository;}
    public void refresh(int shopId,Marketplace marketplace) throws Exception {
        var shop=new ShopRepository().findById(shopId);
        if(shop==null || shop.getMarketplace()!=marketplace) throw new IllegalArgumentException("shop_mismatch");
        var complete=source.read(shopId);
        repository.saveCatalog(shopId,marketplace,complete);
    }
}
