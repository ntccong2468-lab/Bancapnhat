package com.tuandev.fbsbarcode.integration.znack.signature;

public enum ZnackSignatureContext {
    AUTH_CHALLENGE(false),
    SUZ_POST_BODY(true),
    TRUE_API_DOCUMENT(true),
    SIGNATURE_TEST(true);

    private final boolean detached;

    ZnackSignatureContext(boolean detached) {
        this.detached = detached;
    }

    public boolean detached() {
        return detached;
    }
}
