package com.vncode.app.integration.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vncode.app.features.packing.PackingWorkflow;
import com.vncode.app.integration.wb.WbSupplyWorkflow;
import com.vncode.app.integration.wb.WbSyncWorkflow;
import com.vncode.app.models.Shop;
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
