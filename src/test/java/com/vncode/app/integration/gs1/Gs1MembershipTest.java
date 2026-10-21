package com.vncode.app.integration.gs1;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Gs1MembershipTest {
    private final LocalDate today = LocalDate.of(2026, 10, 10);
    private Gs1Membership snapshot(Boolean member, boolean excluded, LocalDate expiry) {
        return new Gs1Membership("7707083893", "Fixture", member, excluded, expiry,
                List.of(new Gs1Membership.Prefix("4601234", 50, List.of("4601234567893"))),
                Instant.parse("2026-10-10T00:00:00Z"), Gs1Membership.CATALOG_SOURCE);
    }
    @Test void membershipMissingAssertionIsUnknown() {
        assertEquals(Gs1Membership.Status.UNKNOWN, snapshot(null, false, today.plusYears(1)).status(today));
        assertTrue(snapshot(null, false, today.plusYears(1)).visiblePrefixes(today).isEmpty());
    }
    @Test void expiryHidesPrefixes() {
        assertEquals(Gs1Membership.Status.EXPIRED, snapshot(true, false, today.minusDays(1)).status(today));
        assertTrue(snapshot(true, false, today.minusDays(1)).visiblePrefixes(today).isEmpty());
        assertTrue(snapshot(true, true, today.plusYears(1)).visiblePrefixes(today).isEmpty());
    }
    @Test void membershipWithoutExpiryIsNotAssumedActive() {
        assertEquals(Gs1Membership.Status.UNKNOWN, snapshot(true, false, null).status(today));
    }
    @Test void expiryDateIsInclusiveAndRenewalNoticeIsSixtyDays() {
        assertEquals(Gs1Membership.Status.EXPIRING, snapshot(true, false, today).status(today));
        assertEquals(Gs1Membership.Status.EXPIRING, snapshot(true, false, today.plusDays(60)).status(today));
        assertEquals(Gs1Membership.Status.ACTIVE, snapshot(true, false, today.plusDays(61)).status(today));
    }
}
