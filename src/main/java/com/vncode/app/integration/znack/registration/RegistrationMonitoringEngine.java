package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.license.LicenseGate;
import com.vncode.app.features.sync.SyncFailure;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/** Shared Catalog readback and consented signing loop; marketplace adapters retain identity guards. */
final class RegistrationMonitoringEngine {
    record Observed<T>(boolean published, T signable) {}
    record Result(int checked, int published, SyncFailure warning) {}

    interface Adapter<T> {
        Observed<T> observe(T item) throws Exception;
        void recordFailure(T item, Exception failure);
        void signConsented(List<T> items) throws Exception;
        boolean isPublished(T item);
    }

    static <T> Result poll(List<T> items, Adapter<T> adapter) throws Exception {
        int checked = 0, published = 0;
        SyncFailure warning = null;
        var signable = new ArrayList<T>();
        for (var item : items) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ZNACK_SYNC_CANCELLED");
            try {
                var observed = adapter.observe(item);
                if (observed.published()) published++;
                if (observed.signable() != null) signable.add(observed.signable());
                checked++;
            } catch (Exception error) {
                if (CatalogFeedback.rateLimited(error)
                        || error instanceof InterruptedIOException && !(error instanceof SocketTimeoutException)) throw error;
                warning = SyncFailure.of(error, 0);
                try { adapter.recordFailure(item, error); }
                catch (RuntimeException originalAccountChanged) { /* Never rebind a historical request. */ }
                checked++;
            }
        }
        if (!signable.isEmpty()) {
            try { adapter.signConsented(List.copyOf(signable)); }
            catch (LicenseGate.LicenseDeniedException paused) { warning = SyncFailure.of(paused, 0); }
            for (var item : signable) if (adapter.isPublished(item)) published++;
        }
        return new Result(checked, published, warning);
    }
    private RegistrationMonitoringEngine() {}
}
