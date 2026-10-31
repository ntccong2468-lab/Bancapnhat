package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.vncode.app.models.Shop;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationWorkflow.CatalogSession;

/**
 * Shared Catalog submission boundary. Marketplace adapters still own their durable
 * claims, send-time authorization checks and persisted feed receipts. The engine
 * freezes one payload and dispatches it at most once per prepared attempt: a
 * failure never causes an automatic replay of a possibly accepted POST.
 */
final class RegistrationSubmissionEngine {
    @FunctionalInterface
    interface Gateway {
        String submit(JsonObject payload, boolean reviewed, Runnable beforeSend) throws Exception;
    }
    private interface Tracker {
        boolean sending();
        void preSendFailure(boolean ownsSending);
        void finished(String state);
        boolean callerRollsBackKnownDenial();
    }

    static Attempt prepare(Shop shop, CatalogSession session, JsonObject payload, boolean reviewed) {
        Objects.requireNonNull(shop, "shop");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(session.catalog(), "catalog");
        var frozen=Objects.requireNonNull(payload,"payload").deepCopy();
        var scope=session.scope();
        var tracker=new Tracker(){
            public boolean sending(){return CatalogAutomaticRepairLedger.sending(shop,scope,frozen);}
            public void preSendFailure(boolean ownsSending){CatalogAutomaticRepairLedger.preSendFailure(shop,scope,frozen,ownsSending);}
            public void finished(String state){CatalogAutomaticRepairLedger.finished(shop,scope,frozen,state);}
            public boolean callerRollsBackKnownDenial(){return shop.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON;}
        };
        return new Attempt(frozen,reviewed,(body, isReviewed, beforeSend) -> isReviewed
                ? session.catalog().submitReviewedDraft(shop, session.token(), body, beforeSend)
                : session.catalog().submit(session.token(), body, beforeSend),tracker);
    }

    static Attempt prepare(JsonObject payload, boolean reviewed, Gateway gateway) {
        return new Attempt(Objects.requireNonNull(payload, "payload").deepCopy(), reviewed,
                Objects.requireNonNull(gateway, "gateway"),null);
    }

    static final class Attempt {
        private final JsonObject payload;
        private final boolean reviewed;
        private final Gateway gateway;
        private final Tracker tracker;
        private final AtomicBoolean started = new AtomicBoolean();

        private Attempt(JsonObject payload, boolean reviewed, Gateway gateway,Tracker tracker) {
            this.payload = payload;
            this.reviewed = reviewed;
            this.gateway = gateway;
            this.tracker = tracker;
        }

        /** Exact frozen bytes for the existing WB command journal. */
        String checkpointPayload() { return payload.toString(); }

        String submit(Runnable beforeSend) throws Exception {
            Objects.requireNonNull(beforeSend, "beforeSend");
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("CATALOG_SUBMISSION_ALREADY_ATTEMPTED");
            boolean[] entered={false},tracked={false};
            try {
                String receipt=gateway.submit(payload.deepCopy(), reviewed, ()->{
                    if(tracker!=null)tracked[0]=tracker.sending();
                    beforeSend.run();entered[0]=true;
                });
                if(tracked[0])tracker.finished("ACKNOWLEDGED");
                return receipt;
            }catch(Exception failure){
                if(tracker!=null)try{
                    if(failure instanceof ZnackNationalCatalogService.FeedLicenseDeniedBeforeTransport && tracker.callerRollsBackKnownDenial()) { /* Ozon rolls back the native checkpoint and ledger atomically */ }
                    else if(!entered[0])tracker.preSendFailure(tracked[0]);
                    else if(tracked[0])tracker.finished(CatalogFeedback.rateLimitRejectedRequest(failure)?"WAITING":"UNKNOWN");
                }catch(RuntimeException persistence){failure.addSuppressed(persistence);}
                throw failure;
            }
        }
    }

    private RegistrationSubmissionEngine() { }
}
