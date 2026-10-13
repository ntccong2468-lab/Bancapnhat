package com.vncode.app.ui.znack;

import com.vncode.app.integration.znack.signature.CryptoProCertificateInfo;
import com.vncode.app.integration.znack.ZnackModels.OperationLog;
import com.vncode.app.integration.znack.ZnackModels.Settings;
import com.vncode.app.integration.znack.signature.CryptoProException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZnackAutomationControllerTest {
    private static final String FINGERPRINT = "a".repeat(40);
    private static final Instant VERIFIED_AT = Instant.parse("2026-10-12T12:00:00Z");

    @Test void recoversActualCompanyIdentityWhenCertmgrMetadataIsIncomplete() throws Exception {
        Settings incomplete = settings("", "{\"subject\":\"CN=Incomplete\",\"hasPrivateKey\":false}");
        var verified = ZnackAutomationController.recoverCertificateSettings(incomplete, actualCertificate(), VERIFIED_AT);
        assertEquals("7707083893", verified.participantInn());
        assertEquals(VERIFIED_AT, verified.signerTestedAt());
        var metadata = JsonParser.parseString(verified.certificateMetadataJson()).getAsJsonObject();
        assertEquals(FINGERPRINT, metadata.get("thumbprint").getAsString());
        assertEquals("7707083893", metadata.get("inn").getAsString());
        assertTrue(metadata.get("hasPrivateKey").getAsBoolean());
        assertEquals("2029-01-01T00:00:00Z", metadata.get("validTo").getAsString());
        assertTrue(metadata.get("subject").getAsString().contains("ООО Фикстура"));
        assertFalse(metadata.has("rawSummary"));
        // Preparation does not alter the previous configuration or claim its authentication succeeded.
        assertEquals("", incomplete.participantInn()); assertNull(incomplete.signerTestedAt());
        assertFalse(JsonParser.parseString(incomplete.certificateMetadataJson()).getAsJsonObject().get("hasPrivateKey").getAsBoolean());
        assertEquals(incomplete.omsConnection(), verified.omsConnection());
        assertEquals(incomplete.producerInn(), verified.producerInn());
        assertEquals(incomplete.ownerInn(), verified.ownerInn());
    }

    @Test void rejectsDifferentAccountOwnerWithoutChangingPreviousSettings() {
        Settings previous = settings("7811089030", "{\"inn\":\"7811089030\"}");
        var failure = assertThrows(CryptoProException.class,
                () -> ZnackAutomationController.recoverCertificateSettings(previous, actualCertificate(), VERIFIED_AT));
        assertFalse(failure.getMessage().contains("7811089030"));
        assertFalse(failure.getMessage().contains("7707083893"));
        assertEquals("7811089030", previous.participantInn()); assertNull(previous.signerTestedAt());
        assertEquals("{\"inn\":\"7811089030\"}", previous.certificateMetadataJson());
    }

    @Test void rejectsDifferentSelectedCertificateAndExpiredActualCertificate() {
        var actual = actualCertificate();
        var wrong = new CryptoProCertificateInfo("b".repeat(40), "b".repeat(40), actual.subject(), actual.issuer(),
                actual.inn(), actual.validFrom(), actual.validTo(), true, "Actual CMS", "");
        var expired = new CryptoProCertificateInfo(FINGERPRINT, FINGERPRINT, actual.subject(), actual.issuer(),
                actual.inn(), actual.validFrom(), VERIFIED_AT, true, "Actual CMS", "");
        for (var certificate : List.of(wrong, expired))
            assertThrows(CryptoProException.class,
                    () -> ZnackAutomationController.recoverCertificateSettings(settings("", "{}"), certificate, VERIFIED_AT));
    }

    @Test void laterIncompleteCertmgrListingCannotEraseRecoveredIdentity() throws Exception {
        var verified = ZnackAutomationController.recoverCertificateSettings(settings("", "{}"), actualCertificate(), VERIFIED_AT);
        var sparse = new CryptoProCertificateInfo(FINGERPRINT, FINGERPRINT, "CN=Incomplete", "", "", null, null,
                false, "certmgr", "");
        var merged = ZnackAutomationController.preserveVerifiedCertificateMetadata(sparse, verified);
        assertEquals("7707083893", merged.inn()); assertTrue(merged.hasPrivateKey());
        assertEquals("ООО Фикстура", merged.ownerName());
        assertEquals(actualCertificate().validTo(), merged.validTo());
        // Metadata is retained only for the same previously tested signing certificate.
        assertSame(sparse, ZnackAutomationController.preserveVerifiedCertificateMetadata(sparse, settings("", "{}")));
        var other = new CryptoProCertificateInfo("b".repeat(40), "b".repeat(40), "CN=Other", "", "", null, null,
                false, "certmgr", "");
        assertSame(other, ZnackAutomationController.preserveVerifiedCertificateMetadata(other, verified));
    }

    private Settings settings(String inn, String metadata) {
        return new Settings("", "", "oms", "connection", inn, "7811089030", "7707083893", "", FINGERPRINT,
                "[]", "document", "12.10.2026", "folder", true, "", "[]", metadata, null);
    }
    private CryptoProCertificateInfo actualCertificate() {
        return new CryptoProCertificateInfo(FINGERPRINT, FINGERPRINT,
                "CN=Иван Иванов,O=ООО Фикстура,INN=770123456789,INNLE=7707083893", "CN=CA", "7707083893",
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2029-01-01T00:00:00Z"),
                true, "CryptoPro CMS signer", "private diagnostic not persisted");
    }

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
