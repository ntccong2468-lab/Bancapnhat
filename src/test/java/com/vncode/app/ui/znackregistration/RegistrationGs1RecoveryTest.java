package com.vncode.app.ui.znackregistration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationGs1RecoveryTest {
    @Test void exactMembershipErrorOpensMembershipRatherThanAllocatingAnotherGtin() {
        for(String detail:new String[]{"{\"result\":{\"errors\":[{\"code\":\"166\",\"message\":\"expired\"}]}}",
                "error_code = 166", "КОД ОШИБКИ: 166", "166: renewal needed", "GS1_MEMBERSHIP_EXPIRED"})
            assertEquals(RegistrationGs1Recovery.Action.MEMBERSHIP,RegistrationGs1Recovery.detect(detail).orElseThrow(),detail);
        assertEquals(RegistrationGs1Recovery.Action.MEMBERSHIP,RegistrationGs1Recovery.detect("code:166; ZNACK-GS1-PREFIX").orElseThrow());
    }
    @Test void KnownQuotaFailuresSuggestFreshPrefixReviewWithoutTouchingRegistrationIdentity() {
        for(String detail:new String[]{"GS1/GTIN quota is unavailable or exhausted (1/1).",
                "ZNACK-GS1-PREFIX", "GS1_GTIN_QUOTA_EXHAUSTED", "V3_GTIN_QUOTA_UNVERIFIED"})
            assertEquals(RegistrationGs1Recovery.Action.PREFIX,RegistrationGs1Recovery.detect(detail).orElseThrow(),detail);
    }
    @Test void ArticleGtinBarcodeAndStackLineNumbersAreNotMembershipEvidence() {
        for(String detail:new String[]{"Article 166", "GTIN:04616600000017", "barcode:166", "decode:166",
                "code:1660", "code:166SKU", "code:1166", "Card.java:166", "HTTP 166", "code:23557 TN VED"})
            assertTrue(RegistrationGs1Recovery.detect(detail).isEmpty(),detail);
        assertTrue(RegistrationGs1Recovery.detect(null).isEmpty());
        assertTrue(RegistrationGs1Recovery.detect(" ").isEmpty());
    }
    @Test void UnboundedStoredDiagnosticCannotForceUnboundedRecoveryScan() {
        assertTrue(RegistrationGs1Recovery.detect("x".repeat(210_000)+" code:166").isEmpty());
    }
}
