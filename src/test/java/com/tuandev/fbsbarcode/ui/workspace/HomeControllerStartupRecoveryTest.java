package com.tuandev.fbsbarcode.ui.workspace;

import com.tuandev.fbsbarcode.integration.znack.ZnackSigningSession;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSigningResult;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureContext;
import com.tuandev.fbsbarcode.models.Shop;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeControllerStartupRecoveryTest {

    @Test
    void startupAuthorizesOnlyTheInitiallySelectedShopForRecoveredSigning() throws Exception {
        int activeShopId = 987_654_321;
        int otherShopId = 987_654_322;
        HomeController controller = new HomeController();
        Shop activeShop = new Shop(activeShopId, "Active shop", "token");
        Shop otherShop = new Shop(otherShopId, "Other shop", "token");
        AtomicInteger activeSigningCalls = new AtomicInteger();

        var activeSigner = ZnackSigningSession.guard(activeShopId, (payload, context) -> {
            activeSigningCalls.incrementAndGet();
            return new CryptoProSigningResult(new byte[]{1}, "ok");
        });
        var otherSigner = ZnackSigningSession.guard(otherShopId,
                (payload, context) -> new CryptoProSigningResult(new byte[]{2}, "ok"));

        assertTrue(controller.authorizeInitialZnackShop(activeShop));
        assertArrayEquals(new byte[]{1},
                activeSigner.sign(new byte[]{9}, ZnackSignatureContext.AUTH_CHALLENGE).cms());
        assertEquals(1, activeSigningCalls.get());

        assertFalse(controller.authorizeInitialZnackShop(otherShop));
        assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> otherSigner.sign(new byte[]{9}, ZnackSignatureContext.AUTH_CHALLENGE));
    }
}
