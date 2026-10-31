package com.vncode.app.integration.znack.registration;

import java.util.Objects;

/**
 * Shared correction lifecycle for one Catalog card. Adapters use their native durable
 * repositories for candidate/transactional checkpoint and the same send-boundary guards
 * as an ordinary submission. The engine never retries an exception: an ambiguous send
 * must be recovered by the native journal and readback on a later pass.
 */
final class CatalogCorrectionLifecycle {
    interface Adapter<T> {
        void observe() throws Exception;
        T rejectedCandidate() throws Exception;
        ZnackNationalCatalogService.FeedProgress readProgress(T candidate) throws Exception;
        ZnackNationalCatalogService.FeedProgress proveRejection(T candidate, long goodId) throws Exception;
        CatalogAutomaticRepair.Proposal propose(T candidate, ZnackNationalCatalogService.FeedProgress proof, long goodId) throws Exception;
        boolean checkpoint(T candidate, CatalogAutomaticRepair.Proposal proposal) throws Exception;
        void submit(T candidate, CatalogAutomaticRepair.Proposal proposal) throws Exception;
    }

    static <T> void advance(Adapter<T> adapter) throws Exception {
        Objects.requireNonNull(adapter, "adapter");
        adapter.observe();
        T candidate = adapter.rejectedCandidate();
        if (candidate == null) return;
        var progress = adapter.readProgress(candidate);
        if (progress == null || progress.goodId() == null || progress.goodId() <= 0) return;
        long id = progress.goodId();
        var proof = adapter.proveRejection(candidate, id);
        if (proof == null || !Objects.equals(proof.goodId(), id) || !CatalogFeedback.confirmedRejection(proof)) return;
        var proposal = adapter.propose(candidate, proof, id);
        if (proposal == null || proposal.goodId() != id) return;
        if (!adapter.checkpoint(candidate, proposal)) return;
        adapter.submit(candidate, proposal);
        adapter.observe();
    }
    private CatalogCorrectionLifecycle() {}
}
