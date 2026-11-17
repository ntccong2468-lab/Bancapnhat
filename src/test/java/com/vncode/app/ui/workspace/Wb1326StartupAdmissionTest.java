package com.vncode.app.ui.workspace;

import com.vncode.app.integration.znack.ZnackSigningSession;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import com.vncode.app.integration.znack.signature.ZnackSignatureContext;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Wb1326StartupAdmissionTest {
    @Test void selectingTheInitialShopDoesNotAuthorizeAnUntestedRecoveredSigner() {
        int shopId = 976_132_601;
        var calls = new AtomicInteger();
        var signer = ZnackSigningSession.guard(shopId, (payload, context) -> {
            calls.incrementAndGet();
            return new CryptoProSigningResult(new byte[]{1}, "synthetic");
        });
        assertTrue(new HomeController().authorizeInitialZnackShop(new Shop(shopId, "Synthetic", "synthetic")));
        assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signer.sign(new byte[]{1}, ZnackSignatureContext.AUTH_CHALLENGE));
        assertEquals(0, calls.get(), "Startup admission must verify the saved certificate before signing");
    }
}
