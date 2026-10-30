package com.vncode.app.features.gtinsync;

import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

/** One HTTP mutation per bounded batch. Each member keeps its own durable state and readback. */
public interface GtinBatchAdapter extends GtinMarketplaceAdapter {
    int MAX_PLAN_ROWS = 10_000;
    enum PreparationState { READY, PENDING, FAILED }
    record Preparation(PreparationState state,String code) {
        public Preparation { Objects.requireNonNull(state);code=Objects.requireNonNullElse(code,""); }
        public static Preparation ready(){return new Preparation(PreparationState.READY,"");}
    }
    /** Optional durable preparation before a GTIN write, for one selected product. */
    @FunctionalInterface interface SqlGuard {void check(java.sql.Connection connection)throws java.sql.SQLException;}
    default Preparation beforeSubmit(List<JobItem> items,Runnable guard,Runnable priceConsentGuard,SqlGuard claimGuard)throws Exception{return Preparation.ready();}
    /** Called for each partition, including the first; run-scoped adapters retain cadence across child jobs. */
    default void beforeNextBatch()throws InterruptedException{}
    void prepareBatch(Collection<ProductKey> keys) throws Exception;
    default void refreshBatch(Collection<ProductKey> keys)throws Exception{prepareBatch(keys);}
    void validateRegistrations(Collection<String> gtins) throws Exception;
    List<List<Preview>> partition(List<Preview> previews) throws Exception;
    default Map<ProductKey,Submission> submitBatch(List<Preview> previews)throws Exception{return submitBatch(previews,()->{});}
    Map<ProductKey,Submission> submitBatch(List<Preview> previews,Runnable boundaryGuard) throws Exception;
    Map<ProductKey,VerificationResult> verifyBatch(List<JobItem> items) throws Exception;
}
