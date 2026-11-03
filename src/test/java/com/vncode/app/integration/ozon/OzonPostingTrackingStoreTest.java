package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonPostingTrackingStoreTest {
    @TempDir Path temp;
    private final Shop shop = new Shop(1, "one", Marketplace.OZON, "client-one", "key-one");
    private final Shop other = new Shop(2, "two", Marketplace.OZON, "client-two", "key-two");
    private final OzonPostingRepository repository = new OzonPostingRepository();

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", temp.resolve("appdata").toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'one','OZON','client-one','key-one')");
            sql.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(2,'two','OZON','client-two','key-two')");
        }
    }

    @AfterEach void cleanup() { System.clearProperty("vncode.appdata.dir"); }

    @Test void absentNullAndEmptyResponsesKeepTheLastValueButMarkItUnverified() throws Exception {
        var first = observation(shop, "post-1", "TRACK-123", true);
        repository.upsertDetail(1, first);
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-123", true), read(shop, "post-1"));

        repository.upsertDetail(1, observation(shop, "post-1", null, false));
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-123", false), read(shop, "post-1"));
        repository.upsertDetail(1, observation(shop, "post-1", "", true));
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-123", false), read(shop, "post-1"));
        var jsonNull = response("post-1", null, false);
        jsonNull.getAsJsonObject("result").add("tracking_number", JsonNull.INSTANCE);
        repository.upsertDetail(1, OzonJson.parsePostingDetail(jsonNull).withTrackingOwner(ListingIdentity.fingerprint(shop)));
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-123", false), read(shop, "post-1"));
        repository.upsertDetail(1, observation(shop, "post-1", "TRACK-NEW", true));
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-NEW", true), read(shop, "post-1"));
        repository.upsertDetail(1, OzonJson.parsePostingDetail(response("post-1", "UNTRUSTED", true)));
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-NEW", false), read(shop, "post-1"),
                "An observation without a bound request owner may not replace the stored value or keep it verified");
    }

    @Test void olderStatusResponseDoesNotReplaceOrDemoteANewerObservation() throws Exception {
        repository.upsertDetail(1, observation(shop, "post-1", "TRACK-NEW", true));
        assertEquals(0, repository.upsertPageIfUnchanged(1,
                List.of(observation(shop, "post-1", null, false)), java.time.Instant.EPOCH));
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-NEW", true), read(shop, "post-1"));
    }

    @Test void incompleteRefreshCannotKeepOldTrackingMarkedAsVerified() throws Exception {
        repository.upsertDetail(1, observation(shop, "post-1", "TRACK-NEW", true));
        repository.recordIncomplete(1, observation(shop, "post-1", null, false), java.time.Instant.now().plusSeconds(1));
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-NEW", false), read(shop, "post-1"));
    }

    @Test void statusOnlyRefreshCanUpdateTracking() throws Exception {
        repository.upsertDetail(1, observation(shop, "post-1", "TRACK-OLD", true));
        var next = observation(shop, "post-1", "TRACK-NEW", true);
        var started = java.time.Instant.now().plusSeconds(1);
        assertEquals(1, repository.updateStatusPageIfUnchanged(1, List.of(next), started, started));
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-NEW", true), read(shop, "post-1"));
    }

    @Test void malformedTrackingNeverReplacesThePriorValue() throws Exception {
        repository.upsertDetail(1, observation(shop, "post-1", "TRACK-OLD", true));
        repository.upsertDetail(1, observation(shop, "post-1", "X".repeat(513), true));
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-OLD", false), read(shop, "post-1"));
        repository.upsertDetail(1, observation(shop, "post-1", "TRACK\nINJECTED", true));
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-OLD", false), read(shop, "post-1"));
    }

    @Test void malformedCachedJsonIsNotShownAsVerifiedOrOverwritten() throws Exception {
        String key = "ozon_posting_tracking.v1:1:" + ListingIdentity.fingerprint(shop) + ":" + ListingIdentity.hash("post-1");
        for (String corrupt : List.of("{\"number\":\"TRACK-OLD\",\"verified\":\"true\"}",
                "{\"number\":\"TRACK-OLD\",\"verified\":true,}")) {
            try (var connection = Database.getConnection(); var insert = connection.prepareStatement(
                    "INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                insert.setString(1, key); insert.setString(2, corrupt); insert.executeUpdate();
            }
            assertNull(read(shop, "post-1"), "Malformed cache must not produce a verified tracking number");
            repository.upsertDetail(1, observation(shop, "post-1", "TRACK-NEW", true));
            try (var connection = Database.getConnection(); var select = connection.prepareStatement("SELECT value FROM app_config WHERE key=?")) {
                select.setString(1, key);
                try (var row = select.executeQuery()) { assertTrue(row.next()); assertEquals(corrupt, row.getString(1)); }
            }
        }
    }

    @Test void scopesSamePostingToExactOwnerAndRejectsLateResponseAfterCredentialChange() throws Exception {
        repository.upsertDetail(1, observation(shop, "same-post", "TRACK-ONE", true));
        repository.upsertDetail(2, observation(other, "same-post", "TRACK-TWO", true));
        assertEquals("TRACK-ONE", read(shop, "same-post").number());
        assertEquals("TRACK-TWO", read(other, "same-post").number());

        var late = observation(shop, "same-post", "TRACK-OLD-RESPONSE", true);
        try (var connection = Database.getConnection(); var update = connection.prepareStatement("UPDATE shops SET api_key=? WHERE id=1")) {
            update.setString(1, "key-rotated"); update.executeUpdate();
        }
        repository.upsertDetail(1, late);
        var rotated = new Shop(1, "one", Marketplace.OZON, "client-one", "key-rotated");
        assertNull(read(rotated, "same-post"), "A response issued with an old API key cannot populate the rotated owner");
        assertNull(read(shop, "same-post"), "Old credentials cannot read the current shop");
        try (var connection = Database.getConnection(); var update = connection.prepareStatement("UPDATE shops SET client_id=?,api_key=? WHERE id=1")) {
            update.setString(1, "client-new"); update.setString(2, "key-new"); update.executeUpdate();
        }
        repository.upsertDetail(1, late);
        var changed = new Shop(1, "one", Marketplace.OZON, "client-new", "key-new");
        assertNull(read(changed, "same-post"));
        assertNull(read(shop, "same-post"), "Stale shop credentials must not expose saved tracking");
        assertEquals("TRACK-TWO", read(other, "same-post").number());
    }

    @Test void trackingAndPostingRollbackTogether() throws Exception {
        assertThrows(RuntimeException.class, () -> repository.upsertPage(1,
                Arrays.asList(observation(shop, "rollback-post", "TRACK-ROLLBACK", true), null)));
        assertNull(read(shop, "rollback-post"));
        assertTrue(repository.findMany(1, List.of("rollback-post")).isEmpty());
    }

    @Test void trackingWriteFailureDoesNotLeaveACommittedPosting() throws Exception {
        try (var connection = Database.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TRIGGER fail_synthetic_tracking BEFORE INSERT ON app_config WHEN NEW.key LIKE 'ozon_posting_tracking.v1:%' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
        }
        assertThrows(RuntimeException.class, () -> repository.upsertDetail(1,
                observation(shop, "write-fails", "TRACK-FAIL", true)));
        assertTrue(repository.findMany(1, List.of("write-fails")).isEmpty());
    }

    @Test void readsOneThousandDistinctPostingsInBoundedBatches() throws Exception {
        var postings = new ArrayList<OzonPostingDto>();
        for (int i = 0; i < 1000; i++) postings.add(observation(shop, "post-" + i, "TRACK-" + i, true));
        repository.upsertPage(1, postings);
        var recovered = OzonPostingTrackingStore.forExport(shop, postings);
        assertEquals(1000, recovered.size());
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-0", true), recovered.get("post-0"));
        assertEquals(new OzonPostingTrackingStore.Tracking("TRACK-999", true), recovered.get("post-999"));
        assertTrue(OzonPostingTrackingStore.forExport(other, postings).isEmpty());
    }

    private static OzonPostingTrackingStore.Tracking read(Shop owner, String postingNumber) throws Exception {
        return OzonPostingTrackingStore.forExport(owner, List.of(observation(owner, postingNumber, null, false)))
                .get(postingNumber);
    }

    private static OzonPostingDto observation(Shop owner, String number, String tracking, boolean present) {
        return OzonJson.parsePostingDetail(response(number, tracking, present)).withTrackingOwner(ListingIdentity.fingerprint(owner));
    }

    private static JsonObject response(String number, String tracking, boolean present) {
        var result = new JsonObject(); result.addProperty("posting_number", number); result.addProperty("status", "awaiting_deliver");
        if (present) result.addProperty("tracking_number", tracking);
        var response = new JsonObject(); response.add("result", result); return response;
    }
}
