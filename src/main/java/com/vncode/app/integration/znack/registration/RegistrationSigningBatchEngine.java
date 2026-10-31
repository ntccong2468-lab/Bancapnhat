package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Marketplace-independent signing group orchestration.  The adapters retain their
 * native durable claims and exact-card checks.  In particular, an exception after
 * the send boundary is ambiguous and is reconciled by reading Catalog, never by
 * automatically replaying the signature.
 */
final class RegistrationSigningBatchEngine {
    @FunctionalInterface interface CheckedAction { void run() throws Exception; }
    interface Adapter<T> {
        boolean claim(T item) throws Exception;
        void release(T item);
        void validateRemote(T item) throws Exception;
        void revalidateLocal(T item) throws Exception;
        void send(List<T> claimed, CheckedAction beforeSend) throws Exception;
        void uncertain(T item);
        void readback(T item) throws Exception;
        void failed(T item, Exception failure);
        default void record(Exception failure) { }
    }

    /** Used when a card was already published or became ineligible, without a new error. */
    static final class SkipMember extends Exception { }

    private static final class MemberUnavailable extends Exception {
        final Object member;
        MemberUnavailable(Object member, Exception failure) { super(failure); this.member = member; }
    }

    static <T> boolean send(List<T> selected, Adapter<T> adapter) throws Exception {
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(adapter, "adapter");
        if (selected.size() > 10) throw new IllegalArgumentException("CATALOG_SIGNING_BATCH_TOO_LARGE");
        var remaining = new ArrayList<>(selected);
        while (!remaining.isEmpty()) {
            var claimed = new ArrayList<T>(remaining.size());
            try {
                for (var item : remaining) if (adapter.claim(item)) claimed.add(item);
            } catch (Exception stopped) {
                adapter.record(stopped);
                for (var item : claimed) adapter.release(item);
                throw stopped;
            }
            if (claimed.isEmpty()) return true;
            var dispatched = new AtomicBoolean();
            try {
                adapter.send(List.copyOf(claimed), () -> {
                    for (var item : claimed) {
                        try { adapter.validateRemote(item); }
                        catch (SkipMember skipped) { throw new MemberUnavailable(item, null); }
                        catch (Exception unavailable) {
                            if (fatal(unavailable)) throw unavailable;
                            throw new MemberUnavailable(item, unavailable);
                        }
                    }
                    // All remote checks precede this final local revalidation. No POST
                    // occurs unless every claimed item is still the reviewed card.
                    for (var item : claimed) adapter.revalidateLocal(item);
                    dispatched.set(true);
                });
                if (!dispatched.get()) throw new IllegalStateException("SIGNING_SEND_BOUNDARY_NOT_ENTERED");
            } catch (Exception failure) {
                adapter.record(failure);
                if (!dispatched.get()) {
                    for (var item : claimed) adapter.release(item);
                    if (failure instanceof MemberUnavailable unavailable) {
                        @SuppressWarnings("unchecked") T item = (T) unavailable.member;
                        if (unavailable.getCause() instanceof Exception cause) adapter.failed(item, cause);
                        remaining.remove(item);
                        continue;
                    }
                    for (var item : claimed) adapter.failed(item, failure);
                    if (fatal(failure)) throw failure;
                    return false;
                }
                for (var item : claimed) adapter.uncertain(item);
                if (CatalogFeedback.rateLimited(failure) || failure instanceof InterruptedException) throw failure;
                return false;
            }
            for (var item : claimed) adapter.uncertain(item);
            for (var item : claimed) {
                try { adapter.readback(item); }
                catch (Exception failure) {
                    if (failure instanceof LicenseDeniedException
                            || failure instanceof ZnackRegistrationScope.ScopeChangedException
                            || failure instanceof SecurityException) throw failure;
                    adapter.record(failure);
                    adapter.failed(item, failure);
                    if (CatalogFeedback.rateLimited(failure) || failure instanceof InterruptedException) throw failure;
                }
            }
            return true;
        }
        return true;
    }

    private static boolean fatal(Exception failure) {
        return failure instanceof LicenseDeniedException
                || failure instanceof ZnackRegistrationScope.ScopeChangedException
                || failure instanceof SecurityException
                || CatalogFeedback.rateLimited(failure)
                || failure instanceof InterruptedException;
    }
    private RegistrationSigningBatchEngine() { }
}
