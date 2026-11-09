package com.vncode.app.ui.znack;

import com.vncode.app.integration.znack.signature.CryptoProCertificateInfo;
import java.time.Instant;
import java.util.List;

/** Keeps non-signing UI tests independent of the Windows user's certificate/token store. */
public final class CertificateDiscoveryFixture {
    private CertificateDiscoveryFixture() {}

    public static void install(ZnackAutomationController controller) {
        controller.certificateReader(settings -> List.of(new CryptoProCertificateInfo(
                "A".repeat(40),"A".repeat(40),"CN=Synthetic UI certificate","CN=Fixture CA","",
                Instant.now().minusSeconds(86400),Instant.now().plusSeconds(86400),
                false,"UI fixture, not verified","")));
    }
}
