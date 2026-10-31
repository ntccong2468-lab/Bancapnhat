package com.vncode.app.integration.znack.registration;

/** Durable seller action and exact member of its frozen selection. */
record RegistrationIntent(String runId, int position) {
    RegistrationIntent {
        if (runId == null || runId.isBlank() || position < 0) throw new IllegalArgumentException("invalid_registration_intent");
    }
}
