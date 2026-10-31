package com.vncode.app.integration.znack.registration;

import java.util.Objects;
import java.util.Set;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;

/** Common per-card decision. Native repositories remain the durable authority for each adapter.
 * A receipt is always reconciled first. An uncertain write is never permission to send again. */
final class RegistrationLifecycleDecision {
    enum Next { READBACK, RECONCILE, SUBMIT_SAVED, PREPARE }
    record Evidence(Status status, boolean receiptOrKnownCard, boolean requiresReconciliation,
                    boolean hasSavedIdentity) {
        Evidence { Objects.requireNonNull(status, "status"); }
    }
    private static final Set<Status> UNCERTAIN = Set.of(Status.ALLOCATING_GTIN, Status.GTIN_REVIEW_REQUIRED,
            Status.GTIN_GENERATED, Status.FEED_SUBMITTING, Status.FEED_REVIEW_REQUIRED,
            Status.FEED_SUBMITTED, Status.READY_TO_SIGN, Status.SIGNING, Status.PUBLISHED);

    static Next next(Evidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.receiptOrKnownCard()) return Next.READBACK;
        if (evidence.requiresReconciliation() || UNCERTAIN.contains(evidence.status())) return Next.RECONCILE;
        if (evidence.status() == Status.READY_TO_SUBMIT) {
            return evidence.hasSavedIdentity() ? Next.SUBMIT_SAVED : Next.RECONCILE;
        }
        return Next.PREPARE;
    }
    private RegistrationLifecycleDecision() {}
}
