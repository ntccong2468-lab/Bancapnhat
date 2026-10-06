package com.tuandev.fbsbarcode.integration.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tuandev.fbsbarcode.features.packing.PackingWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbSyncWorkflow;
import com.tuandev.fbsbarcode.models.Shop;
import org.junit.jupiter.api.Test;

class MarketplaceIsolationTest {
    @Test
    void everyWildberriesEntryPointRejectsOzonBeforeUsingItsCredential() {
        Shop ozon = new Shop(9, "Ozon", Marketplace.OZON, "client-9", "must-not-reach-wb");

        assertMismatch(() -> new WbSyncWorkflow().syncOverview(ozon));
        assertMismatch(() -> new WbSupplyWorkflow().loadOrdersForSupplyLocal(ozon, "WB-SUPPLY"));
        assertMismatch(() -> new PackingWorkflow().loadBoard(ozon));
    }

    private static void assertMismatch(ThrowingCall call) {
        MarketplaceGuard.MarketplaceMismatchException failure = assertThrows(
                MarketplaceGuard.MarketplaceMismatchException.class, call::run);
        assertEquals(Marketplace.WILDBERRIES, failure.expected());
        assertEquals(Marketplace.OZON, failure.actual());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
