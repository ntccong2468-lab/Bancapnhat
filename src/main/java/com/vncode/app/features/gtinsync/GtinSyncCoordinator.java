package com.vncode.app.features.gtinsync;

import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
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
    @FunctionalInterface public interface SourceRefresh {void refresh(Map<ProductKey,String> cohort)throws Exception;}
    private final SourceRefresh sourceRefresh;
    public GtinSyncCoordinator(GtinSyncRepository repository,Function<ProductKey,GtinMarketplaceAdapter> adapters,Clock clock,int maxPolls,Duration interval) {
        this(repository,adapters,clock,maxPolls,interval,cohort->refreshCatalog(repository,cohort));
    }
    public GtinSyncCoordinator(GtinSyncRepository repository,Function<ProductKey,GtinMarketplaceAdapter> adapters,Clock clock,int maxPolls,Duration interval,SourceRefresh sourceRefresh) {
        if(maxPolls<1 || interval.isNegative())throw new IllegalArgumentException("invalid_poll_policy");
        this.repository=repository;this.adapters=adapters;this.clock=clock;this.maxPolls=maxPolls;this.interval=interval;this.sourceRefresh=Objects.requireNonNull(sourceRefresh);
        this.previews=new GtinPreviewService(repository,adapters,clock);
    }
    public UUID confirm(List<Preview> selection) throws Exception {
        if(selection.isEmpty())throw new IllegalArgumentException("empty_selection");
        LicenseRuntime.require(Permission.GTIN);
        sourceRefresh.refresh(cohort(selection));
        if(selection.stream().allMatch(p->p.operation()==Operation.ADD)&&adapters.apply(selection.getFirst().before().key()) instanceof GtinBatchAdapter)validateBatch(selection);
        else for(var p:selection) validate(p);
        LicenseRuntime.require(Permission.GTIN);
        return repository.createJob(selection);
    }
    private void validate(Preview p) throws Exception {
        var current=previews.create(p.before().key(),p.operation(),p.oldGtin(),p.newGtin());
        if(!adapters.apply(p.before().key()).snapshotMatches(p.before(),current.before()))throw new IllegalArgumentException("product_changed");
        if(!current.capability().equals(p.capability()))throw new IllegalArgumentException("capability_changed");
    }
    public void runPending(int shopId,com.vncode.app.integration.marketplace.Marketplace marketplace) {
        runPending(shopId,marketplace,item->{});
    }
    public void runPending(int shopId,com.vncode.app.integration.marketplace.Marketplace marketplace,Consumer<JobItem> progress) {
        runItems(repository.pending(shopId,marketplace),progress);
    }
    /** Only the child job explicitly approved by the Catalog plan may be advanced here. */
    public void runJob(UUID jobId){runItems(repository.jobItems(jobId),item->{});}
    public void runJob(UUID jobId,Consumer<JobItem> progress){runItems(repository.jobItems(jobId),progress);}
    private void runItems(List<JobItem> items,Consumer<JobItem> progress) {
        if(!items.isEmpty()&&adapters.apply(items.getFirst().preview().before().key()) instanceof GtinBatchAdapter batch){runBatches(items,batch,progress,0);return;}
        var queued=items.stream().filter(i->i.status()==Status.QUEUED&&!repository.paused(i.jobId())).toList();
        boolean fresh=queued.isEmpty()||refreshQueued(queued,progress);
        for(var item:items) {
            if(Thread.currentThread().isInterrupted())return;
            if(item.status()==Status.AWAITING_VERIFICATION || item.status()==Status.RECONCILE_REQUIRED){reconcile(item.id());publish(progress,item.id());continue;}
            if(!fresh || item.status()!=Status.QUEUED || repository.paused(item.jobId()))continue;
            try{LicenseRuntime.require(Permission.GTIN);}
            catch(LicenseDeniedException denied){repository.pause(item.jobId(),true);publish(progress,item.id());continue;}
            if(!repository.transition(item.id(),Status.QUEUED,Status.VALIDATING,null))continue;
            try{validate(item.preview());}
            catch(LicenseDeniedException denied){repository.pauseKnownUnsent(item.id());publish(progress,item.id());continue;}
            catch(Exception e){repository.issue(item.id(),com.vncode.app.features.sync.SyncFailure.of(e,0).code());repository.transition(item.id(),Status.VALIDATING,Status.FAILED,null);publish(progress,item.id());continue;}
            if(repository.paused(item.jobId())){repository.transition(item.id(),Status.VALIDATING,Status.QUEUED,null);publish(progress,item.id());continue;}
            try{LicenseRuntime.require(Permission.GTIN);}
            catch(LicenseDeniedException denied){repository.pauseKnownUnsent(item.id());publish(progress,item.id());continue;}
            if(!repository.transition(item.id(),Status.VALIDATING,Status.SENDING,null)) {
                // A pause may win after the check above. Requeue only if cancellation has not won.
                repository.transition(item.id(),Status.VALIDATING,Status.QUEUED,null);continue;
            }
            publish(progress,item.id());
            // This check follows the observer/queue boundary and stays outside the ambiguous-send catch.
            try{LicenseRuntime.require(Permission.GTIN);}
            catch(LicenseDeniedException denied){repository.pauseKnownUnsent(item.id());publish(progress,item.id());continue;}
            try {
                repository.requireSendingBatch(List.of(repository.item(item.id())));
                var submission=adapters.apply(item.preview().before().key()).submit(item.preview());
                repository.transition(item.id(),Status.SENDING,Status.AWAITING_VERIFICATION,submission.remoteTaskId());
            }catch(com.vncode.app.integration.listing.ListingRejectedException rejected){
                repository.issue(item.id(),rejected.code());repository.transition(item.id(),Status.SENDING,Status.FAILED,null);
            }catch(GtinKnownUnsentException knownUnsent){
                repository.issue(item.id(),knownUnsent.code());
                repository.pauseKnownUnsent(item.id());publish(progress,item.id());
                if(Thread.currentThread().isInterrupted())return;
                continue;
            }catch(Exception e){
                repository.issue(item.id(),com.vncode.app.features.sync.SyncFailure.of(e,0).code());
                repository.transition(item.id(),Status.SENDING,Status.RECONCILE_REQUIRED,null);
                if(e instanceof InterruptedException){Thread.currentThread().interrupt();publish(progress,item.id());return;}
            }
            reconcile(item.id());
            publish(progress,item.id());
        }
    }
    private static Map<ProductKey,String> cohort(List<Preview> selection){
        if(selection.isEmpty()||selection.size()>GtinBatchAdapter.MAX_PLAN_ROWS)throw new IllegalArgumentException("invalid_selection");
        var result=new LinkedHashMap<ProductKey,String>();var first=selection.getFirst().before().key();
        for(var preview:selection){var key=preview.before().key();if(key.shopId()!=first.shopId()||key.marketplace()!=first.marketplace()||result.put(key,preview.newGtin())!=null)throw new IllegalArgumentException("invalid_selection");}
        return Collections.unmodifiableMap(result);
    }
    private boolean refreshQueued(List<JobItem> queued,Consumer<JobItem> progress){
        try{LicenseRuntime.require(Permission.GTIN);for(int offset=0;offset<queued.size();offset+=GtinBatchAdapter.MAX_PLAN_ROWS){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("GTIN_SOURCE_REFRESH_INTERRUPTED");var part=queued.subList(offset,Math.min(offset+GtinBatchAdapter.MAX_PLAN_ROWS,queued.size()));sourceRefresh.refresh(cohort(part.stream().map(JobItem::preview).toList()));}return true;}
        catch(Exception unavailable){for(var item:queued){repository.issue(item.id(),com.vncode.app.features.sync.SyncFailure.of(unavailable,0).code());repository.pause(item.jobId(),true);publish(progress,item.id());}return false;}
    }
    /** A completed selected-card remote observation is mandatory before an unsent mutation. */
    private static void refreshCatalog(GtinSyncRepository repository,Map<ProductKey,String> cohort)throws Exception{
        if(cohort.isEmpty())throw new IllegalArgumentException("invalid_selection");
        var first=cohort.keySet().iterator().next();var shop=new com.vncode.app.features.shop.ShopRepository().findById(first.shopId());
        if(shop==null||shop.getMarketplace()!=first.marketplace()||cohort.keySet().stream().anyMatch(k->k.shopId()!=first.shopId()||k.marketplace()!=first.marketplace()))throw new SecurityException("SYNC_OWNER_CHANGED");
        String scope=RegisteredGtinProof.source(shop);var requested=new LinkedHashSet<>(cohort.values());
        var rows=com.vncode.app.integration.znack.registration.NationalCatalogGtinSource.forSelection(shop,requested,false).read(shop.getId());
        var expected=requested.stream().map(RegisteredGtinProof::canonicalCode).collect(java.util.stream.Collectors.toSet());
        var seen=new HashSet<String>();for(var row:rows)if(!RegisteredGtinProof.identifiedObservation(row)||!scope.equals(row.source())||!seen.add(RegisteredGtinProof.canonicalCode(row.gtin())))throw new IllegalStateException("GTIN_SOURCE_READBACK_MISMATCH");
        if(!seen.equals(expected)||!scope.equals(RegisteredGtinProof.source(shop)))throw new IllegalStateException("GTIN_SOURCE_READBACK_MISMATCH");
        RegisteredGtinProof.requireKnownDocumentState(repository.catalogItems(shop.getId(),shop.getMarketplace(),requested).values(),rows);
        repository.mergeCatalog(shop.getId(),shop.getMarketplace(),rows);RegisteredGtinProof.requireCurrent(shop,requested);
    }
    private void validateBatch(List<Preview> selection)throws Exception {
        var requested=new LinkedHashMap<ProductKey,String>();var saved=new HashMap<ProductKey,Preview>();
        for(var p:selection){if(p.operation()!=Operation.ADD||!p.oldGtin().isBlank()||requested.put(p.before().key(),p.newGtin())!=null)throw new IllegalArgumentException("invalid_selection");saved.put(p.before().key(),p);}
        for(var current:previews.createAdds(requested)){
            var p=saved.get(current.before().key());if(!adapters.apply(p.before().key()).snapshotMatches(p.before(),current.before()))throw new IllegalArgumentException("product_changed");
            if(!current.capability().equals(p.capability()))throw new IllegalArgumentException("capability_changed");
        }
    }
    /** Send bounded batches before polling. A delayed member never serializes every other SKU. */
    private void runBatches(List<JobItem> items,GtinBatchAdapter adapter,Consumer<JobItem> progress,int retryDepth) {
        if(Thread.currentThread().isInterrupted())return;
        var previouslySent=items.stream().filter(i->i.status()==Status.AWAITING_VERIFICATION||i.status()==Status.RECONCILE_REQUIRED).toList();
        if(!previouslySent.isEmpty())reconcileBatch(previouslySent,adapter,progress);
        var paused=new HashSet<UUID>();for(var id:items.stream().map(JobItem::jobId).distinct().toList())if(repository.paused(id))paused.add(id);
        var queued=items.stream().filter(i->i.status()==Status.QUEUED&&!paused.contains(i.jobId())).toList();
        if(queued.isEmpty())return;
        if(retryDepth==0&&!refreshQueued(queued,progress))return;
        try{LicenseRuntime.require(Permission.GTIN);adapter.prepareBatch(queued.stream().map(i->i.preview().before().key()).toList());}
        catch(Exception denied){for(var item:queued){repository.issue(item.id(),com.vncode.app.features.sync.SyncFailure.of(denied,0).code());repository.pause(item.jobId(),true);publish(progress,item.id());}return;}
        var claimed=repository.claimBatch(queued);if(claimed.isEmpty())return;
        var owners=new HashMap<String,List<JobItem>>();for(var item:claimed)owners.computeIfAbsent(RegisteredGtinProof.canonicalCode(item.preview().newGtin()),k->new ArrayList<>()).add(item);
        var duplicated=new HashSet<UUID>();for(var group:owners.values())if(group.stream().map(i->i.preview().before().key()).distinct().count()>1){var changes=group.stream().map(i->new GtinSyncRepository.Change(i,Status.FAILED,null,"GTIN_ON_ANOTHER_PRODUCT")).toList();for(var item:repository.applyBatch(changes)){duplicated.add(item.id());publishItem(progress,item);}}
        claimed=claimed.stream().filter(i->!duplicated.contains(i.id())).toList();
        var valid=new ArrayList<JobItem>();validateClaimed(claimed,valid,progress);
        if(valid.isEmpty())return;
        var lookup=new HashMap<ProductKey,JobItem>();valid.forEach(i->lookup.put(i.preview().before().key(),i));var partitions=new ArrayList<List<Preview>>();
        partition(adapter,valid,partitions,progress);
        var sent=new ArrayList<JobItem>();
        for(var part:partitions){var members=part.stream().map(p->lookup.get(p.before().key())).toList();
            if(Thread.currentThread().isInterrupted()){repository.releaseUnsentBatch(members,"ZNACK_SYNC_CANCELLED",true);break;}
            if(!repository.sendingBatch(members)){repository.releaseUnsentBatch(members,"GTIN_BATCH_PAUSED",false);continue;}
            for(var item:members)publishItem(progress,new JobItem(item.id(),item.jobId(),item.preview(),Status.SENDING,item.remoteTaskId()));
            try {
                LicenseRuntime.require(Permission.GTIN);
                var submissions=adapter.submitBatch(part,()->repository.requireSendingBatch(members));
                if(!submissions.keySet().equals(part.stream().map(p->p.before().key()).collect(java.util.stream.Collectors.toSet())))throw new java.io.IOException("GTIN_BATCH_RESPONSE_MISMATCH");
                var changes=members.stream().map(item->new GtinSyncRepository.Change(new JobItem(item.id(),item.jobId(),item.preview(),Status.SENDING,item.remoteTaskId()),Status.AWAITING_VERIFICATION,submissions.get(item.preview().before().key()).remoteTaskId(),null)).toList();
                for(var item:repository.applyBatch(changes)){publishItem(progress,item);sent.add(item);}
            }catch(com.vncode.app.integration.listing.ListingRejectedException rejected){
                var changes=members.stream().map(item->new GtinSyncRepository.Change(new JobItem(item.id(),item.jobId(),item.preview(),Status.SENDING,item.remoteTaskId(),item.dispatchedAt()),Status.FAILED,null,rejected.code())).toList();
                for(var item:repository.applyBatch(changes))publishItem(progress,item);
            }catch(LicenseDeniedException denied){repository.releaseUnsentBatch(members,"LICENSE_REQUIRED",true);for(var item:members)publish(progress,item.id());break;}
            catch(GtinKnownUnsentException unsent){
                boolean changed=Set.of("PRODUCT_CHANGED","ALREADY_PRESENT","GTIN_ON_ANOTHER_PRODUCT","OZON_BARCODE_LIMIT","WB_ARTICLE_TRIM_COLLISION","VARIANT_NOT_FOUND").contains(unsent.code());
                boolean retry=changed&&retryDepth<2;repository.releaseUnsentBatch(members,unsent.code(),!retry);
                if(retry)runBatches(members.stream().map(i->repository.item(i.id())).toList(),adapter,progress,retryDepth+1);
                else for(var item:members)publish(progress,item.id());
            }catch(GtinRateLimitedException rejected){
                repository.releaseUnsentBatch(members,rejected.code(),false);
                if(retryDepth<3)runBatches(members.stream().map(i->repository.item(i.id())).toList(),adapter,progress,retryDepth+1);
                else for(var item:members)publish(progress,item.id());
            }catch(Exception ambiguous){
                var changes=members.stream().map(item->new GtinSyncRepository.Change(new JobItem(item.id(),item.jobId(),item.preview(),Status.SENDING,item.remoteTaskId()),Status.RECONCILE_REQUIRED,null,com.vncode.app.features.sync.SyncFailure.of(ambiguous,0).code())).toList();
                for(var item:repository.applyBatch(changes)){sent.add(item);publishItem(progress,item);}
                if(ambiguous instanceof InterruptedException){Thread.currentThread().interrupt();break;}
            }
        }
        // A pause, cancellation, or failed earlier batch must not strand untouched VALIDATING rows.
        repository.applyBatch(valid.stream().map(item->new GtinSyncRepository.Change(item,Status.QUEUED,null,null)).toList());
        if(!sent.isEmpty()&&!Thread.currentThread().isInterrupted())reconcileBatch(sent,adapter,progress);
    }
    private void validateClaimed(List<JobItem> items,List<JobItem> valid,Consumer<JobItem> progress){
        try{LicenseRuntime.require(Permission.GTIN);validateBatch(items.stream().map(JobItem::preview).toList());valid.addAll(items);}
        catch(LicenseDeniedException|SecurityException denied){repository.releaseUnsentBatch(items,com.vncode.app.features.sync.SyncFailure.of(denied,0).code(),true);for(var item:items)publish(progress,item.id());}
        catch(Exception invalid){
            if(items.size()>1){int middle=items.size()/2;validateClaimed(items.subList(0,middle),valid,progress);validateClaimed(items.subList(middle,items.size()),valid,progress);}
            else {var item=items.getFirst();repository.issue(item.id(),com.vncode.app.features.sync.SyncFailure.of(invalid,0).code());repository.transition(item.id(),Status.VALIDATING,Status.FAILED,null);publish(progress,item.id());}
        }
    }
    private void partition(GtinBatchAdapter adapter,List<JobItem> items,List<List<Preview>> result,Consumer<JobItem> progress){
        try{result.addAll(adapter.partition(items.stream().map(JobItem::preview).toList()));}
        catch(Exception invalid){
            if(items.size()>1){int middle=items.size()/2;partition(adapter,items.subList(0,middle),result,progress);partition(adapter,items.subList(middle,items.size()),result,progress);}
            else {var item=items.getFirst();repository.issue(item.id(),com.vncode.app.features.sync.SyncFailure.of(invalid,0).code());repository.transition(item.id(),Status.VALIDATING,Status.FAILED,null);publish(progress,item.id());}
        }
    }
    private void reconcileBatch(List<JobItem> items,GtinBatchAdapter adapter,Consumer<JobItem> progress) {
        var pending=new ArrayList<>(items);var issues=new HashMap<UUID,String>();
        for(int poll=0;poll<maxPolls&&!pending.isEmpty()&&!Thread.currentThread().isInterrupted();poll++){
            Map<ProductKey,VerificationResult> result;
            try{result=adapter.verifyBatch(List.copyOf(pending));}
            catch(Exception unavailable){for(var item:pending)issues.put(item.id(),com.vncode.app.features.sync.SyncFailure.of(unavailable,0).code());if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();break;}
            var next=new ArrayList<JobItem>();var changes=new ArrayList<GtinSyncRepository.Change>();boolean unknown=false;
            for(var item:pending){var checked=result.getOrDefault(item.preview().before().key(),new VerificationResult(Verification.UNKNOWN));var outcome=checked.outcome();
                if(outcome==Verification.APPLIED||outcome==Verification.REJECTED){String issue=outcome==Verification.APPLIED?"":checked.issue().isBlank()?"GTIN_REJECTED":checked.issue();changes.add(new GtinSyncRepository.Change(item,outcome==Verification.APPLIED?Status.SUCCEEDED:Status.FAILED,null,issue));}
                else {if(!checked.issue().isBlank())issues.put(item.id(),checked.issue());next.add(item);unknown|=outcome==Verification.UNKNOWN;}
            }
            for(var item:repository.applyBatch(changes))publishItem(progress,item);
            pending=next;if(unknown)break;
            if(!pending.isEmpty()&&poll+1<maxPolls&&!interval.isZero())try{Thread.sleep(interval.toMillis());}catch(InterruptedException cancelled){Thread.currentThread().interrupt();break;}
        }
        for(var item:repository.applyBatch(pending.stream().map(i->new GtinSyncRepository.Change(i,Status.RECONCILE_REQUIRED,null,issues.get(i.id()))).toList()))publishItem(progress,item);
    }
    private void publishItem(Consumer<JobItem> progress,JobItem item){try{progress.accept(item);}catch(RuntimeException ignored){/* Persisted outcomes remain authoritative. */}}
    private void publish(Consumer<JobItem> progress,UUID itemId) {
        try{progress.accept(repository.item(itemId));}
        catch(RuntimeException ignored){/* Display callbacks never decide mutation outcomes; persisted history is authoritative. */}
    }
    public void pause(UUID jobId){repository.pause(jobId,true);}
    public void resume(UUID jobId){
        var items=jobItems(jobId);
        if(items.stream().anyMatch(item->item.status()==Status.QUEUED || item.status()==Status.VALIDATING))
            LicenseRuntime.require(Permission.GTIN);
        repository.pause(jobId,false);
    }
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
            try{outcome=adapter.verify(item.preview(),new Submission(item.remoteTaskId(),item.dispatchedAt()));}
            catch(GtinRejectedException rejected){repository.issue(itemId,rejected.code());repository.transition(itemId,item.status(),Status.FAILED,null);return;}
            catch(Exception e){repository.issue(itemId,com.vncode.app.features.sync.SyncFailure.of(e,0).code());outcome=Verification.UNKNOWN;if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
            if(outcome==Verification.APPLIED || outcome==Verification.REJECTED) {
                repository.issue(itemId,outcome==Verification.APPLIED?"":"GTIN_REJECTED");
                repository.transition(itemId,item.status(),outcome==Verification.APPLIED?Status.SUCCEEDED:Status.FAILED,null);return;
            }
            if(outcome==Verification.UNKNOWN || Thread.currentThread().isInterrupted())break;
            if(poll+1<maxPolls && !interval.isZero())try{Thread.sleep(interval.toMillis());}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        repository.transition(itemId,item.status(),Status.RECONCILE_REQUIRED,null);
    }
}
