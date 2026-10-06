package com.tuandev.fbsbarcode.ui.znack;

import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProCertificateInfo;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.OperationLog;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZnackAutomationControllerTest {
    @Test void ordersSelectableCertificatesByNameBeforeExpiredCertificates() {
        Instant now = Instant.now();
        CryptoProCertificateInfo expired = certificate("expired", "CN=Alpha", now.minus(1, ChronoUnit.DAYS));
        CryptoProCertificateInfo validZ = certificate("valid-z", "CN=Zulu", now.plus(1, ChronoUnit.DAYS));
        CryptoProCertificateInfo unknown = certificate("unknown", "CN=Beta", null);

        List<CryptoProCertificateInfo> ordered = ZnackAutomationController.orderedCertificates(
                List.of(expired, validZ, unknown), now);

        assertEquals(List.of("unknown", "valid-z", "expired"),
                ordered.stream().map(CryptoProCertificateInfo::selector).toList());
        assertTrue(ZnackAutomationController.certificateSelectable(unknown, now));
        assertTrue(ZnackAutomationController.certificateSelectable(validZ, now));
        assertFalse(ZnackAutomationController.certificateSelectable(expired, now));
    }

    @Test void formatsAuditLogForCopying() {
        String text = ZnackAutomationController.logText(new OperationLog(
                1, 2, "Shop", "SIGNATURE_TEST", null, "ERROR",
                "code=SIGNING_FAILED; diagnostic", null, Instant.parse("2026-06-11T01:00:00Z")));

        assertTrue(text.contains("action=SIGNATURE_TEST"));
        assertTrue(text.contains("severity=ERROR"));
        assertTrue(text.contains("message=code=SIGNING_FAILED; diagnostic"));
    }

    private CryptoProCertificateInfo certificate(String selector, String subject, Instant validTo) {
        return new CryptoProCertificateInfo(selector, selector, subject, "", "", null, validTo,
                false, "CryptoPro", "");
    }
}
