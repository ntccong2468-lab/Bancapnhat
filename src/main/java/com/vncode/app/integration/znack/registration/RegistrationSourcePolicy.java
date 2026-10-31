package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;

/** Shared marketplace dispatch for source-bound automatic mutation. */
public final class RegistrationSourcePolicy {
    private RegistrationSourcePolicy() {}

    public static boolean permitsAutomatic(Shop shop, ProductKey key, String gtin) {
        if (shop == null || key == null || shop.getMarketplace() != key.marketplace()) return false;
        if (key.marketplace() == Marketplace.OZON) return OzonSourceConsentPolicy.permitsAutomatic(shop,key,gtin);
        if (key.marketplace() == Marketplace.WILDBERRIES) return WbSourceConsentPolicy.permitsAutomatic(shop,key,gtin);
        return false;
    }
}
