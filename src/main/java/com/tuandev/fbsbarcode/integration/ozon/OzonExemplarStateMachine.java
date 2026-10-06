package com.tuandev.fbsbarcode.integration.ozon;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Explicit transition table prevents a retry from skipping a persisted mutation boundary. */
public final class OzonExemplarStateMachine {
    private static final Map<OzonExemplarJobStage, Set<OzonExemplarJobStage>> ALLOWED = Map.of(
            OzonExemplarJobStage.CREATED, EnumSet.of(
                    OzonExemplarJobStage.RESERVED,
                    OzonExemplarJobStage.REJECTED,
                    OzonExemplarJobStage.RECONCILE_REQUIRED),
            OzonExemplarJobStage.RESERVED, EnumSet.of(
                    OzonExemplarJobStage.VALIDATED,
                    OzonExemplarJobStage.REJECTED,
                    OzonExemplarJobStage.RECONCILE_REQUIRED),
            OzonExemplarJobStage.VALIDATED, EnumSet.of(
                    OzonExemplarJobStage.ACCEPTED,
                    OzonExemplarJobStage.SET_PENDING,
                    OzonExemplarJobStage.REJECTED,
                    OzonExemplarJobStage.RECONCILE_REQUIRED),
            OzonExemplarJobStage.SET_PENDING, EnumSet.of(
                    OzonExemplarJobStage.VALIDATED,
                    OzonExemplarJobStage.VERIFYING,
                    OzonExemplarJobStage.REJECTED,
                    OzonExemplarJobStage.RECONCILE_REQUIRED),
            OzonExemplarJobStage.VERIFYING, EnumSet.of(
                    OzonExemplarJobStage.VALIDATED,
                    OzonExemplarJobStage.ACCEPTED,
                    OzonExemplarJobStage.REJECTED,
                    OzonExemplarJobStage.RECONCILE_REQUIRED),
            OzonExemplarJobStage.RECONCILE_REQUIRED, EnumSet.of(
                    OzonExemplarJobStage.RESERVED,
                    OzonExemplarJobStage.VALIDATED,
                    OzonExemplarJobStage.VERIFYING,
                    OzonExemplarJobStage.ACCEPTED,
                    OzonExemplarJobStage.REJECTED),
            OzonExemplarJobStage.ACCEPTED, EnumSet.noneOf(OzonExemplarJobStage.class),
            OzonExemplarJobStage.REJECTED, EnumSet.of(OzonExemplarJobStage.CREATED));

    private OzonExemplarStateMachine() {
    }

    public static boolean canTransition(OzonExemplarJobStage from, OzonExemplarJobStage to) {
        return from == to || ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireTransition(OzonExemplarJobStage from, OzonExemplarJobStage to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Invalid Ozon exemplar job transition: " + from + " -> " + to);
        }
    }
}
