package com.tuandev.fbsbarcode.integration.marketplace;

import com.tuandev.fbsbarcode.models.Shop;
import java.util.Objects;

/** Fail-closed boundary used before a marketplace-specific API or repository is accessed. */
public final class MarketplaceGuard {
    private MarketplaceGuard() {
    }

    public static Shop require(Shop shop, Marketplace expected) {
        Objects.requireNonNull(shop, "shop");
        Objects.requireNonNull(expected, "expected marketplace");
        if (shop.getMarketplace() != expected) {
            throw new MarketplaceMismatchException(expected, shop.getMarketplace());
        }
        return shop;
    }

    public static Shop requireWildberries(Shop shop) {
        return require(shop, Marketplace.WILDBERRIES);
    }

    public static Shop requireOzon(Shop shop) {
        return require(shop, Marketplace.OZON);
    }

    public static final class MarketplaceMismatchException extends IllegalStateException {
        private final Marketplace expected;
        private final Marketplace actual;

        public MarketplaceMismatchException(Marketplace expected, Marketplace actual) {
            super("This workflow supports " + expected.badge() + " shops only.");
            this.expected = expected;
            this.actual = actual;
        }

        public Marketplace expected() {
            return expected;
        }

        public Marketplace actual() {
            return actual;
        }
    }
}
