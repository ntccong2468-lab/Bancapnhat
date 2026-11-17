package com.vncode.app.integration.znack;

import com.vncode.app.integration.znack.ZnackModels.GoodsDocument;
import com.vncode.app.integration.znack.ZnackModels.Product;
import com.vncode.app.integration.znack.ZnackModels.Settings;

/** Local purchase admission; current registry evidence is read when introducing goods. */
public final class ZnackPurchasePreflight {
    private ZnackPurchasePreflight() { }

    public static void requireLocalPermit(Settings settings, Product product, ZnackPurchaseMethod method) {
        if (product == null) throw new IllegalArgumentException("GTIN is not registered for the selected shop.");
        if (method == null) throw new IllegalArgumentException("Purchase method is required.");
        if (!method.requiresProductionMetadata()) return;
        if (!hasLocalPermit(product)) {
            throw new IllegalStateException("GTIN has no complete local declaration or certificate. "
                    + "Save its goods document before buying KIZ.");
        }
    }

    public static void requireLocalPermit(int shopId, Settings settings, Product product) {
        requireLocalPermit(settings, product, new ZnackPurchasePreferences(shopId).method());
    }

    public static boolean hasLocalPermit(Product product) {
        return product != null && (!ZnackPermitDocumentParser.selectForCirculation(product.permitDocuments()).isEmpty()
                || new GoodsDocument(product.certificateType(), product.certificateNumber(), product.certificateDate()).complete());
    }
}
