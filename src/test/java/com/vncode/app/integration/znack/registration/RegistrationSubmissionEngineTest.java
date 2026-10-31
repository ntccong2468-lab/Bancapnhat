package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationSubmissionEngineTest {
    @Test void freezesReviewedPayloadAndKeepsSendBoundaryBeforeDispatch() throws Exception {
        var input = new JsonObject();
        input.addProperty("gtin", "04600000000000");
        var events = new ArrayList<String>();
        var attempt = RegistrationSubmissionEngine.prepare(input, true, (payload, reviewed, beforeSend) -> {
            assertTrue(reviewed);
            assertEquals("04600000000000", payload.get("gtin").getAsString());
            events.add("validated");
            beforeSend.run();
            events.add("sent");
            payload.addProperty("gtin", "changed-in-gateway");
            return "feed-1";
        });
        input.addProperty("gtin", "changed-after-preparation");
        assertEquals("{\"gtin\":\"04600000000000\"}", attempt.checkpointPayload());
        assertEquals("feed-1", attempt.submit(() -> events.add("checkpoint")));
        assertEquals(java.util.List.of("validated", "checkpoint", "sent"), events);
        assertEquals("{\"gtin\":\"04600000000000\"}", attempt.checkpointPayload());
    }

    @Test void doesNotAutomaticallyRepeatAfterAmbiguousSubmissionFailure() {
        var calls = new AtomicInteger();
        var attempt = RegistrationSubmissionEngine.prepare(new JsonObject(), false, (payload, reviewed, beforeSend) -> {
            calls.incrementAndGet();
            assertFalse(reviewed);
            beforeSend.run();
            throw new java.io.IOException("connection lost after sending");
        });
        assertThrows(java.io.IOException.class, () -> attempt.submit(() -> {}));
        assertThrows(IllegalStateException.class, () -> attempt.submit(() -> {}));
        assertEquals(1, calls.get());
    }

    @Test void failedDurableBoundaryPreventsSendAndAttemptIsNotReused() {
        var writes = new AtomicInteger();
        var attempt = RegistrationSubmissionEngine.prepare(new JsonObject(), false, (payload, reviewed, beforeSend) -> {
            beforeSend.run();
            writes.incrementAndGet();
            return "feed-1";
        });
        assertThrows(SecurityException.class, () -> attempt.submit(() -> { throw new SecurityException("owner changed"); }));
        assertEquals(0, writes.get());
        assertThrows(IllegalStateException.class, () -> attempt.submit(() -> {}));
    }
}
