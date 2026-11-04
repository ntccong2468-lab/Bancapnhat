package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationThreeSourceReviewGateTest {
    private static Sku row(long chrtId, String article, String gtin, Long goodId, String feedId, Status status) {
        return new Sku(1000L, chrtId, 1, article, "Костюм", "Sports Suits", article,
                "black", "50", List.of("4601234567890"), "", true,
                gtin, goodId, feedId, status, "", false, "50");
    }

    @Test void reportsUncertainFeedWithoutCreatingAnotherCard() {
        Sku uncertain = row(11, "SUIT-50", "04601234567890", null, "feed-123", Status.PROCESSING);
        var result = RegistrationThreeSourceReviewGate.blocked(List.of(uncertain),
                sku -> false, sku -> false, sku -> false);
        assertEquals(1, result.size());
        assertEquals("FEED_EXISTS_GOOD_ID_UNKNOWN", result.getFirst().reason());
        assertTrue(RegistrationThreeSourceReviewGate.warning(result).contains("SUIT-50"));
        assertTrue(RegistrationThreeSourceReviewGate.warning(result).contains("11"));
    }

    @Test void acceptsExplicitlyReviewableDraftAndReplacement() {
        Sku draft = row(12, "SUIT-52", "04601234567891", 20L, "", Status.PROCESSING);
        Sku replacement = row(13, "SUIT-54", "04601234567892", 21L, "feed-456", Status.PUBLISHED);
        assertTrue(RegistrationThreeSourceReviewGate.blocked(List.of(draft,replacement),
                sku -> false, sku -> sku == draft, sku -> sku == replacement).isEmpty());
    }

    @Test void findsOnlyBlockedRowsWithinBatch() {
        Sku allowed = row(14, "SUIT-56", "", null, "", Status.NOT_CREATED);
        Sku blocked = row(15, "SUIT-58", "04601234567893", null, "feed-789", Status.READY_TO_SIGN);
        var result = RegistrationThreeSourceReviewGate.blocked(List.of(allowed, blocked),
                sku -> sku == allowed, sku -> false, sku -> false);
        assertEquals(1,result.size());
        assertEquals(15L,result.getFirst().chrtId());
    }
}
