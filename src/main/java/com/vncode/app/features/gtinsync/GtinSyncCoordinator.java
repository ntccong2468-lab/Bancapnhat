package com.vncode.app.features.gtinsync;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Consumer;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

/** A persisted SENDING state always leads to reconciliation, never automatic resubmission. */
public final class GtinSyncCoordinator {
    private final GtinSyncRepository repository;
    private final Function<ProductKey,GtinMarketplaceAdapter> adapters;
    private final GtinPreviewService previews;
    private final Clock clock;
    private final int maxPolls;
    private final Duration interval;
    public GtinSyncCoordinator(GtinSyncRepository repository,Function<ProductKey,GtinMarketplaceAdapter> adapters,Clock clock,int maxPolls,Duration interval) {
        if(maxPolls<1 || interval.isNegative())throw new IllegalArgumentException("invalid_poll_policy");
        this.repository=repository;this.adapters=adapters;this.clock=clock;this.maxPolls=maxPolls;this.interval=interval;
        this.previews=new GtinPreviewService(repository,adapters,clock);
    }
    public UUID confirm(List<Preview> selection) throws Exception {
        if(selection.isEmpty())throw new IllegalArgumentException("empty_selection");
        for(var p:selection) validate(p);
        return repository.createJob(selection);
    }
    private void validate(Preview p) throws Exception {
        var current=previews.create(p.before().key(),p.operation(),p.oldGtin(),p.newGtin());
        if(!current.before().fingerprint().equals(p.before().fingerprint()))throw new IllegalArgumentException("product_changed");
        if(!current.capability().equals(p.capability()))throw new IllegalArgumentException("capability_changed");
    }
    public void runPending(int shopId,com.vncode.app.integration.marketplace.Marketplace marketplace) {
        runPending(shopId,marketplace,item->{});
    }
    public void runPending(int shopId,com.vncode.app.integration.marketplace.Marketplace marketplace,Consumer<JobItem> progress) {
        for(var item:repository.pending(shopId,marketplace)) {
            if(Thread.currentThread().isInterrupted())return;
            if(item.status()==Status.AWAITING_VERIFICATION || item.status()==Status.RECONCILE_REQUIRED){reconcile(item.id());publish(progress,item.id());continue;}
            if(item.status()!=Status.QUEUED || repository.paused(item.jobId()))continue;
            if(!repository.transition(item.id(),Status.QUEUED,Status.VALIDATING,null))continue;
            try{validate(item.preview());}
            catch(Exception e){repository.transition(item.id(),Status.VALIDATING,Status.FAILED,null);publish(progress,item.id());continue;}
            if(repository.paused(item.jobId())){repository.transition(item.id(),Status.VALIDATING,Status.QUEUED,null);publish(progress,item.id());continue;}
            if(!repository.transition(item.id(),Status.VALIDATING,Status.SENDING,null)) {
                // A pause may win after the check above. Requeue only if cancellation has not won.
                repository.transition(item.id(),Status.VALIDATING,Status.QUEUED,null);continue;
            }
            publish(progress,item.id());
            try {
                var submission=adapters.apply(item.preview().before().key()).submit(item.preview());
                repository.transition(item.id(),Status.SENDING,Status.AWAITING_VERIFICATION,submission.remoteTaskId());
            }catch(Exception e){
                repository.transition(item.id(),Status.SENDING,Status.RECONCILE_REQUIRED,null);
                if(e instanceof InterruptedException){Thread.currentThread().interrupt();publish(progress,item.id());return;}
            }
            reconcile(item.id());
            publish(progress,item.id());
        }
    }
    private void publish(Consumer<JobItem> progress,UUID itemId) {
        try{progress.accept(repository.item(itemId));}
        catch(RuntimeException ignored){/* Display callbacks never decide mutation outcomes; persisted history is authoritative. */}
    }
    public void pause(UUID jobId){repository.pause(jobId,true);}
    public void resume(UUID jobId){repository.pause(jobId,false);}
    public void cancelQueued(UUID jobId) {
        repository.pause(jobId,true);
        // A concurrent transition to SENDING wins over cancellation; already-sent work is untouched.
        var shopRows=jobItems(jobId);
        for(var item:shopRows)if(item.status()==Status.QUEUED || item.status()==Status.VALIDATING)
            repository.transition(item.id(),item.status(),Status.CANCELLED,null);
    }
    private List<JobItem> jobItems(UUID jobId){return repository.jobItems(jobId);}
    public void reconcile(UUID itemId) {
        var item=repository.item(itemId);
        if(item.status()!=Status.AWAITING_VERIFICATION && item.status()!=Status.RECONCILE_REQUIRED)return;
        var deadline=clock.instant().plus(interval.multipliedBy(maxPolls).plusSeconds(1));
        var adapter=adapters.apply(item.preview().before().key());
        for(int poll=0;poll<maxPolls && !clock.instant().isAfter(deadline);poll++) {
            Verification outcome;
            try{outcome=adapter.verify(item.preview(),new Submission(item.remoteTaskId()));}
            catch(Exception e){outcome=Verification.UNKNOWN;if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
            if(outcome==Verification.APPLIED || outcome==Verification.REJECTED) {
                repository.transition(itemId,item.status(),outcome==Verification.APPLIED?Status.SUCCEEDED:Status.FAILED,null);return;
            }
            if(outcome==Verification.UNKNOWN || Thread.currentThread().isInterrupted())break;
            if(poll+1<maxPolls && !interval.isZero())try{Thread.sleep(interval.toMillis());}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        repository.transition(itemId,item.status(),Status.RECONCILE_REQUIRED,null);
    }
}
