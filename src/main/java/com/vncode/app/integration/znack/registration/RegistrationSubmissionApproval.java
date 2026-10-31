package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.products.ProductSnapshot;
import com.vncode.app.models.Shop;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationWorkflow.CatalogSession;

/** Shared, send-time consent and identity validation for both marketplace adapters. */
final class RegistrationSubmissionApproval {
    @FunctionalInterface interface SnapshotSource { ProductSnapshot read() throws Exception; }

    static void claim(Shop shop, ProductKey key, CatalogSession session, RegistrationIntent intent,
                      JsonObject payload, SnapshotSource snapshots) throws Exception {
        var action = SqliteWriteTransaction.run(c -> RegistrationSourceConsentGuard.action(c, intent, key));
        boolean correction = SqliteWriteTransaction.run(c -> RegistrationSourceConsentGuard.isCorrection(c, intent, key));
        if (correction) {
            var proof = RegistrationRepairConsent.proof(shop, key, session.scope(), intent, session, payload, (seller,product)->snapshots.read());
            SqliteWriteTransaction.run(c -> {
                RegistrationSourceConsentGuard.claimCorrection(c, shop, intent, key, session.scope(), payload, proof);
                return null;
            });
            return;
        }
        var source = snapshots.read();
        var schema = session.catalog().registrationAttributes(
                ZnackCardRegistrationWorkflow.draftFromPayload(payload).categoryId(), session.token());
        if (action == RegistrationSourceConsent.Action.REPAIR) {
            CatalogRepairImageEvidence.verifyBeforeSubmit(shop, key, session.scope(), intent,
                    payload.get("gtin").getAsString(), payload.get("good_id").getAsLong(), payload, session);
        }
        if (action == RegistrationSourceConsent.Action.RESUME_GTIN
                && SqliteWriteTransaction.run(c -> RegistrationSourceConsentGuard.manualRepairRun(c, intent.runId()))) {
            CatalogRepairFeedEvidence.verifyBeforeSubmit(shop, key, session.scope(), intent,
                    payload.get("gtin").getAsString(), payload, session);
        }
        SqliteWriteTransaction.run(c -> {
            RegistrationSourceConsentGuard.claimSubmission(c, shop, intent, source, schema, payload);
            return null;
        });
    }
    private RegistrationSubmissionApproval() { }
}
