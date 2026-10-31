package com.vncode.app.integration.znack.registration;

import com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationSigningBatchEngineTest {
    @Test void signsOnlyAfterAllFreshAndLocalChecksAndReadsBack() throws Exception {
        var adapter = new Fixture();
        assertTrue(RegistrationSigningBatchEngine.send(List.of("a", "b"), adapter));
        assertEquals(List.of("claim:a", "claim:b", "remote:a", "remote:b", "local:a", "local:b",
                "post:a,b", "unknown:a", "unknown:b", "read:a", "read:b"), adapter.events);
    }

    @Test void ineligibleMemberIsReleasedAndRemainingCardsAreRevalidated() throws Exception {
        var adapter = new Fixture(); adapter.invalid = "a";
        assertTrue(RegistrationSigningBatchEngine.send(List.of("a", "b"), adapter));
        assertTrue(adapter.events.contains("release:a"));
        assertTrue(adapter.events.contains("release:b"));
        assertEquals(1, adapter.events.stream().filter(e -> e.startsWith("post:")).count());
        assertTrue(adapter.events.contains("post:b"));
        assertFalse(adapter.events.contains("post:a,b"));
    }

    @Test void uncertainPostIsNeverRepeatedOrReleased() throws Exception {
        var adapter = new Fixture(); adapter.failAfterPost = true;
        assertFalse(RegistrationSigningBatchEngine.send(List.of("a", "b"), adapter));
        assertEquals(1, adapter.events.stream().filter(e -> e.startsWith("post:")).count());
        assertTrue(adapter.events.contains("unknown:a"));
        assertTrue(adapter.events.contains("unknown:b"));
        assertFalse(adapter.events.stream().anyMatch(e -> e.startsWith("release:")));
        assertFalse(adapter.events.stream().anyMatch(e -> e.startsWith("read:")));
    }

    @Test void rateLimitBeforeSendingReleasesClaimsAndStops() {
        var adapter = new Fixture(); adapter.limited = "b";
        assertThrows(ZnackApiException.class, () -> RegistrationSigningBatchEngine.send(List.of("a", "b"), adapter));
        assertTrue(adapter.events.contains("release:a"));
        assertTrue(adapter.events.contains("release:b"));
        assertFalse(adapter.events.stream().anyMatch(e -> e.startsWith("post:")));
    }

    @Test void adapterCannotReturnWithoutCrossingTheSendBoundary() throws Exception {
        var adapter = new Fixture(); adapter.omitSendBoundary = true;
        assertFalse(RegistrationSigningBatchEngine.send(List.of("a"), adapter));
        assertTrue(adapter.events.contains("release:a"));
        assertTrue(adapter.events.contains("failed:a"));
        assertFalse(adapter.events.contains("unknown:a"));
    }

    @Test void protocolBatchLimitIsEnforcedBeforeClaiming() {
        var adapter = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> RegistrationSigningBatchEngine.send(
                java.util.stream.IntStream.range(0,11).mapToObj(Integer::toString).toList(), adapter));
        assertTrue(adapter.events.isEmpty());
    }

    private static final class Fixture implements RegistrationSigningBatchEngine.Adapter<String> {
        final List<String> events = new ArrayList<>();
        String invalid, limited;
        boolean failAfterPost, omitSendBoundary;
        public boolean claim(String item) { events.add("claim:"+item); return true; }
        public void release(String item) { events.add("release:"+item); }
        public void validateRemote(String item) throws Exception {
            events.add("remote:"+item);
            if (item.equals(invalid)) throw new RegistrationSigningBatchEngine.SkipMember();
            if (item.equals(limited)) throw new ZnackApiException("rate limited", 429, "");
        }
        public void revalidateLocal(String item) { events.add("local:"+item); }
        public void send(List<String> items, RegistrationSigningBatchEngine.CheckedAction beforeSend) throws Exception {
            if (omitSendBoundary) return;
            beforeSend.run(); events.add("post:"+String.join(",",items));
            if (failAfterPost) throw new IOException("unknown outcome");
        }
        public void uncertain(String item) { events.add("unknown:"+item); }
        public void readback(String item) { events.add("read:"+item); }
        public void failed(String item, Exception failure) { events.add("failed:"+item); }
    }
}
