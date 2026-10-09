package com.vncode.app.ui.workspace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupplyPrintRequestTest {
    @Test void waitsForSuccessAndConsumesOnlyOnePrintRequest() {
        SupplyPrintRequest request = new SupplyPrintRequest();
        assertFalse(request.request());
        request.begin(1, "WB-A", 10);
        assertTrue(request.isLoading());
        assertTrue(request.request());
        assertFalse(request.request());
        assertTrue(request.isPending());
        assertTrue(request.complete(1, "WB-A", 10, true));
        assertFalse(request.isLoading());
        assertFalse(request.isPending());
        assertFalse(request.complete(1, "WB-A", 10, true));
    }

    @Test void ignoresStaleShopSupplyAndRequestToken() {
        SupplyPrintRequest request = new SupplyPrintRequest();
        request.begin(2, "WB-B", 11);
        request.request();
        assertFalse(request.complete(1, "WB-B", 11, true));
        assertFalse(request.complete(2, "WB-A", 11, true));
        assertFalse(request.complete(2, "WB-B", 10, true));
        assertTrue(request.isPending());
        assertTrue(request.complete(2, "WB-B", 11, true));
    }

    @Test void failureAndContextResetNeverPrintCachedOrders() {
        SupplyPrintRequest request = new SupplyPrintRequest();
        request.begin(1, "WB-A", 10);
        request.request();
        assertFalse(request.complete(1, "WB-A", 10, false));
        assertFalse(request.isLoading());
        assertFalse(request.isPending());
        request.begin(1, "WB-A", 11);
        request.request();
        request.cancel();
        assertFalse(request.complete(1, "WB-A", 11, true));
        assertFalse(request.request());
        request.begin(2, "WB-B", 12);
        assertFalse(request.isPending());
    }
}
