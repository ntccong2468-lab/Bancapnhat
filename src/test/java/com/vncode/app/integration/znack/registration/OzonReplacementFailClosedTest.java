package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Safety coverage while the operation-bound replacement flow is not yet enabled. */
class OzonReplacementFailClosedTest {
    @TempDir Path root;

    @Test void legacyReplacementCannotArchiveOrOverwritePriorCatalogIdentity() throws Exception {
        var fixture = new OzonCardRegistrationWorkflowTest();
        fixture.root = root;
        fixture.setup();
        try {
            assertTrue(fixture.repository.claim(fixture.shop, fixture.key, "9001", "", fixture.session().scope(), false));
            try (var connection = Database.getConnection();
                 var statement = connection.prepareStatement("UPDATE ozon_card_registrations SET status='PUBLISHED',gtin=?,active_gtin=?,feed_id='old-feed',good_id=91,payload_json='old-payload' WHERE shop_id=2 AND product_id='501' AND offer_id='fixture-offer'")) {
                statement.setString(1, GtinWriteTestFixture.GTIN);
                statement.setString(2, GtinWriteTestFixture.GTIN);
                assertEquals(1, statement.executeUpdate());
            }
            var before = fixture.row();
            var detail = new AtomicReference<String>();
            assertFalse(fixture.workflow.register(fixture.shop, fixture.key, fixture.draft, true, true, (status, value) -> detail.set(value)));
            assertEquals("ZNACK_SOURCE_REVIEW_REQUIRED", detail.get());
            assertSameIdentity(before, fixture.row());
            var prepared = fixture.workflow.prepare(fixture.shop, fixture.key);
            assertFalse(fixture.workflow.registerReviewedAfterConflict(fixture.shop, prepared, fixture.draft, true, GtinWriteTestFixture.GTIN, (status, value) -> detail.set(value)));
            assertEquals("ZNACK_SOURCE_REVIEW_REQUIRED", detail.get());
            assertSameIdentity(before, fixture.row());
            assertEquals(0, fixture.api.allocations.get());
            assertEquals(0, fixture.api.submissions.get());
            try (var connection = Database.getConnection();
                 var statement = connection.prepareStatement("SELECT COUNT(*) FROM ozon_card_registration_history WHERE shop_id=2 AND product_id='501' AND offer_id='fixture-offer'");
                 var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        } finally {
            fixture.clear();
        }
    }

    private static void assertSameIdentity(OzonCardRegistrationRepository.Row before, OzonCardRegistrationRepository.Row after) {
        assertNotNull(after);
        assertEquals(before.status(), after.status());
        assertEquals(before.gtin(), after.gtin());
        assertEquals(before.activeGtin(), after.activeGtin());
        assertEquals(before.goodId(), after.goodId());
        assertEquals(before.feedId(), after.feedId());
        assertEquals(before.payload(), after.payload());
    }
}
