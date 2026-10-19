package com.vncode.app.features.gtinsync;

import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

/** One HTTP mutation per bounded batch. Each member keeps its own durable state and readback. */
public interface GtinBatchAdapter extends GtinMarketplaceAdapter {
    int MAX_PLAN_ROWS = 10_000;
    void prepareBatch(Collection<ProductKey> keys) throws Exception;
    default void refreshBatch(Collection<ProductKey> keys)throws Exception{prepareBatch(keys);}
    void validateRegistrations(Collection<String> gtins) throws Exception;
    List<List<Preview>> partition(List<Preview> previews) throws Exception;
    default Map<ProductKey,Submission> submitBatch(List<Preview> previews)throws Exception{return submitBatch(previews,()->{});}
    Map<ProductKey,Submission> submitBatch(List<Preview> previews,Runnable boundaryGuard) throws Exception;
    Map<ProductKey,VerificationResult> verifyBatch(List<JobItem> items) throws Exception;
}
