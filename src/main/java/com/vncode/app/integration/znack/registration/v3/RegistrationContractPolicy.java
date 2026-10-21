package com.vncode.app.integration.znack.registration.v3;

/** Versioned authoritative contracts; no runtime switch can approve an undocumented write. */
public final class RegistrationContractPolicy {
    public static final String CATALOG_SPECIFICATION="https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/";
    public static final String CATALOG_VERSION="5.68 (2026-09-28)";
    private RegistrationContractPolicy() { }
    public static void require(RegistrationJob.Action action, String environment) {
        java.util.Objects.requireNonNull(action);
        if(!"production".equals(environment))throw new IllegalStateException("V3_SANDBOX_AUTH_UNVERIFIED");
        if(action==RegistrationJob.Action.SYNC_WB)throw new IllegalStateException("V3_API_CONTRACT_UNVERIFIED");
        // ALLOCATE_GTIN: 3.2.1; SUBMIT: 3.2.2; SIGN: 3.4.1 and 3.4.3 (detached CAdES).
        // See docs/evidence/znack-v3-catalog-contracts.json for retrieval and document digest.
        // Permission to call an endpoint does not replace per-card consent or runtime owner checks.
    }
}
