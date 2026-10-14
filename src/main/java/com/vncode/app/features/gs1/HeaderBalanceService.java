package com.vncode.app.features.gs1;

import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.time.Instant;
import java.util.List;

/** Header reads cached enterprise-scoped balances; opening it never signs or makes a network request. */
public final class HeaderBalanceService {
    public List<BalanceSnapshot> cached(Shop shop) {
        ListingIdentity.require(shop);
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var identity=ZnackAccountInfo.identity(settings,Instant.now());if(identity.isEmpty())return List.of();
        var balances=new Gs1BalanceRepository().list(identity.get().inn(),identity.get().environment());
        ListingIdentity.require(shop);return ZnackAccountInfo.scopedBalances(identity.get(),balances);
    }
}
