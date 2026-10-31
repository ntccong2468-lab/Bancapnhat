package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.license.LicenseGate;
import com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationMonitoringEngineTest {
    @Test void checksRemainingCardsAndSignsOnlyConsentedItems() throws Exception {
        var adapter = new Fixture();
        var result = RegistrationMonitoringEngine.poll(List.of("published", "broken", "ready", "pending"), adapter);
        assertEquals(4, result.checked());
        assertEquals(2, result.published());
        assertNotNull(result.warning());
        assertEquals(List.of("broken"), adapter.failures);
        assertEquals(List.of("ready"), adapter.signed);
    }

    @Test void rateLimitStopsImmediatelyWithoutSigningOrReadingLaterCards() {
        var adapter = new Fixture();
        assertThrows(ZnackApiException.class, () -> RegistrationMonitoringEngine.poll(List.of("ready", "limited", "pending"), adapter));
        assertEquals(List.of("ready", "limited"), adapter.observed);
        assertTrue(adapter.signed.isEmpty());
        assertTrue(adapter.failures.isEmpty());
    }

    @Test void interruptionStopsWithoutRecordingItAsCardError() {
        var adapter = new Fixture();
        assertThrows(InterruptedIOException.class, () -> RegistrationMonitoringEngine.poll(List.of("interrupted", "pending"), adapter));
        assertEquals(List.of("interrupted"), adapter.observed);
        assertTrue(adapter.failures.isEmpty());
        assertTrue(adapter.signed.isEmpty());
    }

    @Test void licenseDenialReportsWarningAndDoesNotMarkUnsignedCardPublished() throws Exception {
        var adapter = new Fixture(); adapter.denySigning = true;
        var result = RegistrationMonitoringEngine.poll(List.of("ready"), adapter);
        assertEquals(1, result.checked());
        assertEquals(0, result.published());
        assertNotNull(result.warning());
        assertTrue(adapter.signed.isEmpty());
    }

    private static final class Fixture implements RegistrationMonitoringEngine.Adapter<String> {
        final List<String> observed = new ArrayList<>(), failures = new ArrayList<>(), signed = new ArrayList<>();
        boolean denySigning;
        @Override public RegistrationMonitoringEngine.Observed<String> observe(String item) throws Exception {
            observed.add(item);
            return switch (item) {
                case "broken" -> throw new IOException("card read failed");
                case "limited" -> throw new ZnackApiException("too many requests", 429, "");
                case "interrupted" -> throw new InterruptedIOException("cancelled");
                case "published" -> new RegistrationMonitoringEngine.Observed<>(true, null);
                case "ready" -> new RegistrationMonitoringEngine.Observed<>(false, item);
                default -> new RegistrationMonitoringEngine.Observed<>(false, null);
            };
        }
        @Override public void recordFailure(String item, Exception failure) { failures.add(item); }
        @Override public void signConsented(List<String> items) {
            if (denySigning) throw new LicenseGate.LicenseDeniedException();
            signed.addAll(items);
        }
        @Override public boolean isPublished(String item) { return signed.contains(item); }
    }
}
