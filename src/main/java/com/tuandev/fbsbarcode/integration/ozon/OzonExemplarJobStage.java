package com.tuandev.fbsbarcode.integration.ozon;

public enum OzonExemplarJobStage {
    CREATED,
    RESERVED,
    VALIDATED,
    SET_PENDING,
    VERIFYING,
    ACCEPTED,
    REJECTED,
    RECONCILE_REQUIRED;

    public boolean terminal() {
        return this == ACCEPTED || this == REJECTED;
    }
}
