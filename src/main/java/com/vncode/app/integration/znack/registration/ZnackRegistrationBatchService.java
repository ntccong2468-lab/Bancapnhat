package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppPaths;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Durable sequential actual Catalog submissions, independent of controller/view lifetime. */
public final class ZnackRegistrationBatchService {
    public enum State { QUEUED,RUNNING,WAITING_NETWORK,WAITING_REMOTE,NEEDS_ACTION,COMPLETE,PAUSED,CANCELLED }
    public record Progress(String runId,int total,int submitted,int failed,int remaining,String currentArticle,
            String currentSize,String lastSubmittedArticle,String lastSubmittedSize,String detail,State state,int acceptedFeeds,int awaitingReadback) {
        public Progress(String runId,int total,int submitted,int failed,int remaining,String currentArticle,String currentSize,String lastSubmittedArticle,String lastSubmittedSize,String detail,State state){this(runId,total,submitted,failed,remaining,currentArticle,currentSize,lastSubmittedArticle,lastSubmittedSize,detail,state,0,0);}
        public boolean running(){return state==State.QUEUED||state==State.RUNNING||state==State.WAITING_NETWORK||state==State.WAITING_REMOTE;}
        public boolean waitingOffline(){return state==State.WAITING_NETWORK&&!"API_COOLDOWN".equals(detail);}
    }
    @FunctionalInterface interface DraftFactory { Draft prepare(Shop shop,Sku sku,boolean autoSign)throws Exception; }
    private static final Gson JSON=new Gson();
    private static final Set<String> RUNNING=ConcurrentHashMap.newKeySet(),PENDING=ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService EXECUTOR=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"znack-durable-batch");t.setDaemon(true);return t;});
    private final Path directory=AppPaths.appDataDir().toAbsolutePath().normalize();
    private final ZnackRegistrationBatchStore store=new ZnackRegistrationBatchStore();
    private final ZnackCardRegistrationRepository registrations;
    private final ZnackCardRegistrationWorkflow workflow;
    private final DraftFactory drafts;
    private final Executor executor;
    private final boolean strictScope;
    public ZnackRegistrationBatchService(){this(new ZnackCardRegistrationRepository());}
    public ZnackRegistrationBatchService(ZnackCardRegistrationRepository registrations){this(registrations,new ZnackCardRegistrationWorkflow(registrations),RegistrationAutomaticDraft::prepare,EXECUTOR,true);}
    ZnackRegistrationBatchService(ZnackCardRegistrationRepository registrations,ZnackCardRegistrationWorkflow workflow,DraftFactory drafts,Executor executor,boolean strictScope){
        this.registrations=registrations;this.workflow=workflow;this.drafts=drafts;this.executor=executor;this.strictScope=strictScope;
    }
    /** Nonblocking close-to-tray predicate; offline queues remain pending between retries. */
    public static boolean hasPendingJobs(){return !PENDING.isEmpty();}
    private String key(Shop shop){return directory+":"+shop.getId();}
    /** Called on a worker after the full-selection review. All identities are durable before dispatch. */
    public String start(Shop requested,List<Sku> selected,boolean autoSign){
        Shop shop=freeze(requested);if(shop.getMarketplace()!=Marketplace.WILDBERRIES)throw new IllegalArgumentException("WB_REGISTRATION_REQUIRED");
        ListingIdentity.require(shop);LicenseRuntime.require(Permission.GTIN);
        if(RUNNING.contains(key(shop)))throw new IllegalStateException("ZNACK_REGISTRATION_BATCH_ALREADY_RUNNING");
        var unique=new LinkedHashMap<Long,Sku>();for(var sku:List.copyOf(selected))if(sku!=null)unique.putIfAbsent(sku.chrtId(),sku);
        if(unique.isEmpty())throw new IllegalArgumentException("ZNACK_EMPTY_SELECTION");
        for(var sku:unique.values())if(sku.tnvedGroupChanged()||!RegistrationCardIndex.notSubmitted(sku)&&!sku.canReviewExistingDraft())
            throw new RegistrationValidationException("ZNACK_BATCH_REQUIRES_KNOWN_UNSUBMITTED_CARD");
        var previous=store.latest(shop);if(previous!=null&&(active(previous.state())||"PAUSED".equals(previous.state())))throw new IllegalStateException("ZNACK_REGISTRATION_BATCH_ALREADY_RUNNING");
        String scope=strictScope?JSON.toJson(currentScope(shop)):"";
        var reports=new RegistrationAttemptReports(shop,List.copyOf(unique.values()));String run=reports.queue();
        try{store.create(shop,run,List.copyOf(unique.values()),autoSign,scope);}
        catch(RuntimeException unavailable){reports.stopPending(SyncFailure.of(unavailable,0).code(),false);throw unavailable;}
        request(shop);dispatch(shop,run);return run;
    }
    public Progress latest(Shop shop){
        var batch=store.latest(shop);if(batch==null)return null;
        var summary=store.summary(shop,batch.id());State state=State.valueOf(batch.state());
        return new Progress(batch.id(),summary.total(),summary.submitted(),summary.failed(),summary.total()-summary.submitted()-summary.failed()-summary.cancelled(),
                article(summary.current()),size(summary.current()),article(summary.last()),size(summary.last()),batch.detail(),state,summary.acceptedFeeds(),summary.awaitingReadback());
    }
    boolean ownsPending(Shop shop,Sku sku){
        try(var c=com.vncode.app.config.Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM znack_registration_batch_items i JOIN znack_registration_batches b ON b.id=i.run_id WHERE b.shop_id=? AND b.owner=? AND b.state IN ('QUEUED','RUNNING','WAITING_NETWORK','WAITING_REMOTE','PAUSED','CANCELLED') AND i.state NOT IN ('SUBMITTED','NEEDS_ACTION') AND json_extract(i.sku_json,'$.chrtId')=? AND NOT EXISTS (SELECT 1 FROM znack_registration_batch_items newer JOIN znack_registration_batches n ON n.id=newer.run_id WHERE n.shop_id=b.shop_id AND n.owner=b.owner AND n.rowid>b.rowid AND json_extract(newer.sku_json,'$.chrtId')=json_extract(i.sku_json,'$.chrtId')) LIMIT 1")){
            p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setLong(3,sku.chrtId());try(var r=p.executeQuery()){return r.next();}
        }catch(java.sql.SQLException error){throw new IllegalStateException("SYNC_STORAGE",error);}
    }
    boolean blocksSigning(Shop shop,Sku sku){
        try(var c=com.vncode.app.config.Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM znack_registration_batch_items i JOIN znack_registration_batches b ON b.id=i.run_id WHERE b.shop_id=? AND b.owner=? AND b.state IN ('PAUSED','CANCELLED') AND json_extract(i.sku_json,'$.chrtId')=? AND NOT EXISTS (SELECT 1 FROM znack_registration_batch_items newer JOIN znack_registration_batches n ON n.id=newer.run_id WHERE n.shop_id=b.shop_id AND n.owner=b.owner AND n.rowid>b.rowid AND json_extract(newer.sku_json,'$.chrtId')=json_extract(i.sku_json,'$.chrtId')) LIMIT 1")){
            p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setLong(3,sku.chrtId());try(var r=p.executeQuery()){return r.next();}
        }catch(java.sql.SQLException error){throw new IllegalStateException("SYNC_STORAGE",error);}
    }
    /** Startup/background monitor entry point. Never allocates another identity for an uncertain send. */
    public boolean resumePending(Shop requested){
        if(requested.getMarketplace()!=Marketplace.WILDBERRIES)return false;
        Shop shop=freeze(requested);var batch=store.latest(shop);if(batch==null)return false;
        if("NEEDS_ACTION".equals(batch.state())){
            assertScope(shop,batch);var correctable=new ArrayList<Integer>();
            for(var item:store.items(shop,batch.id()))if("NEEDS_ACTION".equals(item.state())){
                var current=registrations.find(shop.getId(),item.sku().chrtId());
                if(current==null||current.nmId()!=item.sku().nmId()||current.status()!=Status.ERROR||!current.hasFeed())continue;
                assertNativeScope(shop,current,batch);String payload=registrations.payload(shop.getId(),current.chrtId());
                if(!payload.isBlank()&&CatalogPackingPolicy.correctedPayload(new ZnackNationalCatalogService.FeedProgress("Rejected",current.goodId(),List.of(Objects.toString(current.errorMessage(),""))),com.google.gson.JsonParser.parseString(payload).getAsJsonObject())!=null)correctable.add(item.position());
            }
            // This only schedules readback. advanceSubmissionForBatch must re-prove rejection.
            if(!store.reopenRejected(shop,batch.id(),correctable))return false;
            batch=store.get(shop,batch.id());
        }
        if(!active(batch.state()))return false;
        PENDING.add(key(shop));
        if(batch.retryAt()==null||!batch.retryAt().isAfter(Instant.now()))dispatch(shop,batch.id());
        return true;
    }
    public boolean pause(Shop requested){return control(requested,"PAUSED","ZNACK_BATCH_PAUSED");}
    public boolean cancel(Shop requested){return control(requested,"CANCELLED","ZNACK_BATCH_CANCELLED");}
    public boolean resumePaused(Shop requested){
        Shop shop=freeze(requested);var batch=store.latest(shop);if(batch==null||!"PAUSED".equals(batch.state()))return false;
        assertScope(shop,batch);LicenseRuntime.require(Permission.GTIN);
        if(!store.control(shop,batch.id(),"QUEUED","ZNACK_BATCH_SUBMITTING"))return false;
        request(shop);dispatch(shop,batch.id());return true;
    }
    private boolean control(Shop requested,String state,String detail){
        Shop shop=freeze(requested);var batch=store.latest(shop);if(batch==null)return false;
        assertScope(shop,batch);boolean changed=store.control(shop,batch.id(),state,detail);
        if(changed){PENDING.remove(key(shop));if("CANCELLED".equals(state))RegistrationAttemptReports.attach(shop,batch.id()).stopPending(detail,true);request(shop);}return changed;
    }
    private void dispatch(Shop shop,String run){
        String key=key(shop);PENDING.add(key);if(!RUNNING.add(key))return;
        var continuation=com.vncode.app.shared.UpdateHandoff.continuation();
        try{executor.execute(()->{
            try(var work=com.vncode.app.shared.UpdateHandoff.work(continuation);var data=SyncDataScope.enter(directory);var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(true);var cooldown=com.vncode.app.shared.RateLimitDeferral.enter()){
                run(shop,run);
            }catch(Exception error){
                if(com.vncode.app.shared.UpdateHandoff.isStopping())return;
                try{if(directory.equals(AppPaths.appDataDir().toAbsolutePath().normalize()))pause(shop,run,error);}catch(RuntimeException ignored){PENDING.remove(key);}
            }finally{
                RUNNING.remove(key);
                if(!com.vncode.app.shared.UpdateHandoff.isStopping()&&directory.equals(AppPaths.appDataDir().toAbsolutePath().normalize())){
                    try{var batch=store.get(shop,run);if(active(batch.state()))scheduleRetry(shop,run,batch.retryAt());else PENDING.remove(key);}catch(RuntimeException changed){PENDING.remove(key);}
                }else PENDING.remove(key);
            }
        });}catch(RejectedExecutionException rejected){RUNNING.remove(key);pause(shop,run,rejected);scheduleRetry(shop,run,Instant.now().plusSeconds(5));}
    }
    private void run(Shop shop,String run)throws Exception {
        var batch=store.get(shop,run);if(!active(batch.state()))return;assertScope(shop,batch);store.state(shop,run,"RUNNING","ZNACK_BATCH_SUBMITTING",null);
        var reports=RegistrationAttemptReports.attach(shop,run);
        try(var lookup=workflow.existingCardsOperation()){
        boolean readFailureObserved=false;Duration readbackDelay=Duration.ofSeconds(15);String readbackDetail="CATALOG_REQUEST_ACCEPTED_CARD_PENDING";
        for(var item:store.items(shop,run)){
            com.vncode.app.shared.UpdateHandoff.checkpoint();
            if(!active(store.get(shop,run).state()))return;
            if(Set.of("SUBMITTED","NEEDS_ACTION","CANCELLED").contains(item.state()))continue;
            assertScope(shop,batch);LicenseRuntime.require(Permission.GTIN);
            var attempt=reports.begin(item.sku());
            try {
                Sku current=registrations.find(shop.getId(),item.sku().chrtId());
                if(current==null||current.nmId()!=item.sku().nmId())throw new SecurityException("PRODUCT_MISMATCH");
                assertNativeScope(shop,current,batch);
                if(current.hasFeed()||current.hasGtin()&&current.goodId()!=null&&!current.canReviewExistingDraft()&&current.status()!=Status.READY_TO_SUBMIT){
                    if(!workflow.advanceSubmissionForBatch(shop,current)){
                        store.item(shop,item,"WAITING_REMOTE","REGISTRATION_BUSY",null,current.feedId());attempt.waiting(new IllegalStateException("REGISTRATION_BUSY"));continue;
                    }
                    observeReceipt(shop,item,registrations.find(shop.getId(),current.chrtId()),attempt);continue;
                }
                if(workflow.isRunning(shop,current))workflow.completion(shop,current).toCompletableFuture().join();
                current=registrations.find(shop.getId(),item.sku().chrtId());
                if(current.hasFeed()||current.hasGtin()&&current.goodId()!=null&&!current.canReviewExistingDraft()&&current.status()!=Status.READY_TO_SUBMIT){
                    if(!workflow.advanceSubmissionForBatch(shop,current)){
                        store.item(shop,item,"WAITING_REMOTE","REGISTRATION_BUSY",null,current.feedId());attempt.waiting(new IllegalStateException("REGISTRATION_BUSY"));continue;
                    }
                    observeReceipt(shop,item,registrations.find(shop.getId(),current.chrtId()),attempt);continue;
                }
                if(current.status()==Status.CHECKING&&!current.hasIdentity()){
                    store.recoverPreAllocation(shop,current);current=registrations.find(shop.getId(),current.chrtId());
                }
                if(current.status()==Status.ALLOCATING_GTIN||current.needsGtinReview()||current.needsFeedReview()
                        ||current.status()==Status.FEED_SUBMITTING||current.status()==Status.FEED_REVIEW_REQUIRED)
                    throw new RegistrationValidationException("ZNACK_PREVIOUS_REQUEST_REQUIRES_RECONCILIATION");
                java.util.function.BiConsumer<Status,String> receiptListener=(status,detail)->{
                    attempt.observe(status,detail);
                    store.state(shop,run,"RUNNING",switch(status){
                        case CHECKING -> "CATALOG_CHECKING_EXISTING_CARDS";
                        case ALLOCATING_GTIN -> "CATALOG_ALLOCATING_GTIN";
                        case GTIN_GENERATED,READY_TO_SUBMIT -> "ZNACK_CARD_READY";
                        case FEED_SUBMITTING -> "ZNACK_BATCH_SUBMITTING";
                        case FEED_SUBMITTED -> "CATALOG_REQUEST_ACCEPTED_CARD_PENDING";
                        default -> detail;
                    },null);
                    if(status==Status.FEED_SUBMITTED){var accepted=registrations.find(shop.getId(),item.sku().chrtId());if(accepted!=null&&accepted.hasFeed())store.item(shop,item,"WAITING_REMOTE","CATALOG_REQUEST_ACCEPTED_CARD_PENDING",null,accepted.feedId());}
                };
                boolean started;
                if(current.status()==Status.READY_TO_SUBMIT&&current.hasGtin()){
                    store.item(shop,item,"RUNNING","ZNACK_SUBMITTING_SAVED_PAYLOAD",null,"");
                    started=workflow.resumeSubmissionOnly(shop,current,batch.autoSign(),receiptListener);
                }else{
                    if(!current.canRegister())throw new RegistrationValidationException("ZNACK_REGISTRATION_STATE_REQUIRES_REVIEW");
                    store.item(shop,item,"PREPARING","ZNACK_PREPARING_CARD",null,"");
                    store.state(shop,run,"RUNNING","ZNACK_PREPARING_CARD",null);
                    Draft draft=drafts.prepare(shop,current,batch.autoSign());
                    if(item.draft()!=null&&!ZnackNationalCatalogService.buildPayload("",item.draft(),"").equals(ZnackNationalCatalogService.buildPayload("",draft,"")))
                        throw new RegistrationValidationException("ZNACK_REVIEWED_VALUES_CHANGED");
                    assertScope(shop,batch);store.item(shop,item,"READY","ZNACK_CARD_READY",draft,"");
                    current=registrations.find(shop.getId(),current.chrtId());
                    started=workflow.submitOnly(shop,current,draft.withAutoSign(batch.autoSign()),receiptListener,lookup);
                }
                if(!started&&!workflow.isRunning(shop,current))throw new RegistrationValidationException("ZNACK_REGISTRATION_CHANGED_REFRESH_REQUIRED");
                workflow.completion(shop,current).toCompletableFuture().join();
                var updated=registrations.find(shop.getId(),current.chrtId());
                if(updated!=null&&(updated.hasFeed()||updated.hasGtin()&&updated.goodId()!=null)){
                    Throwable readFailure=workflow.consumeSubmissionFailure(shop,current);
                    if(readFailure!=null){
                        if(readFailure instanceof SecurityException||readFailure instanceof ZnackRegistrationScope.ScopeChangedException)
                            throw readFailure instanceof RuntimeException e?e:new IllegalStateException(readFailure);
                        boolean retryable=CatalogFeedback.retryableRead(readFailure)||CatalogFeedback.notFound(readFailure);
                        if(!retryable){
                            store.item(shop,item,"NEEDS_ACTION",ZnackSanitizer.error(readFailure),null,updated.feedId());attempt.failed(readFailure);continue;
                        }
                        var retry=SyncFailure.of(readFailure,0);if(retry.delay().compareTo(readbackDelay)>0)readbackDelay=retry.delay();
                        readbackDetail="CATALOG_READBACK_RETRY_PENDING";readFailureObserved=true;
                        store.item(shop,item,"WAITING_REMOTE",readbackDetail,null,updated.feedId());attempt.waiting(readFailure);
                        if(CatalogFeedback.rateLimited(readFailure)){
                            waitRemote(shop,run,readbackDetail,readbackDelay,true);request(shop);return;
                        }
                        continue;
                    }
                    observeReceipt(shop,item,updated,attempt);continue;
                }
                Throwable failure=workflow.consumeSubmissionFailure(shop,current);
                if(failure==null)failure=new RegistrationValidationException(updated==null?"ZNACK_REGISTRATION_NOT_SUBMITTED":Objects.requireNonNullElse(updated.errorMessage(),"ZNACK_REGISTRATION_NOT_SUBMITTED"));
                if(updated!=null&&safeUnsent(updated)&&CatalogFeedback.retryableRead(failure)){
                    if(updated.status()==Status.CHECKING)store.recoverPreAllocation(shop,updated);
                    store.item(shop,item,"QUEUED",ZnackSanitizer.error(failure),null,"");attempt.waiting(failure);pause(shop,run,failure);return;
                }
                throw failure instanceof Exception e?e:new IllegalStateException(failure);
            }catch(Exception error){
                if(error instanceof SecurityException||error instanceof ZnackRegistrationScope.ScopeChangedException)throw error;
                var current=registrations.find(shop.getId(),item.sku().chrtId());
                if(current!=null&&(current.hasFeed()||current.hasGtin()&&current.goodId()!=null)){
                    if(CatalogFeedback.retryableRead(error)||CatalogFeedback.notFound(error)){
                        var retry=SyncFailure.of(error,0);if(retry.delay().compareTo(readbackDelay)>0)readbackDelay=retry.delay();
                        readbackDetail="CATALOG_READBACK_RETRY_PENDING";readFailureObserved=true;
                        store.item(shop,item,"WAITING_REMOTE",readbackDetail,null,current.feedId());attempt.waiting(error);
                        if(CatalogFeedback.rateLimited(error)){
                            waitRemote(shop,run,readbackDetail,readbackDelay,true);request(shop);return;
                        }
                    }else{
                        store.item(shop,item,"NEEDS_ACTION",ZnackSanitizer.error(error),null,current.feedId());attempt.failed(error);
                    }
                    continue;
                }
                if(CatalogFeedback.retryableRead(error)&&(current==null||safeUnsent(current))){
                    store.item(shop,item,"QUEUED",ZnackSanitizer.error(error),null,"");attempt.waiting(error);pause(shop,run,error);return;
                }
                store.item(shop,item,"NEEDS_ACTION",ZnackSanitizer.error(error),null,"");attempt.failed(error);
            }
        }
        // Imports may become visible only after the later POSTs finish. Read each still-pending
        // receipt once more now, without allocating, submitting, correcting or signing anything.
        if(!readFailureObserved)for(var item:store.items(shop,run)){
            if(!active(store.get(shop,run).state()))return;
            if(!"WAITING_REMOTE".equals(item.state()))continue;
            var current=registrations.find(shop.getId(),item.sku().chrtId());
            if(current==null||!current.hasFeed())continue;
            assertScope(shop,batch);assertNativeScope(shop,current,batch);
            var attempt=reports.begin(item.sku());
            try{
                if(workflow.verifySubmissionForBatch(shop,current))observeReceipt(shop,item,registrations.find(shop.getId(),current.chrtId()),attempt);
            }catch(Exception error){
                if(error instanceof SecurityException||error instanceof ZnackRegistrationScope.ScopeChangedException)throw error;
                if(!CatalogFeedback.retryableRead(error)&&!CatalogFeedback.notFound(error)){
                    store.item(shop,item,"NEEDS_ACTION",ZnackSanitizer.error(error),null,current.feedId());attempt.failed(error);continue;
                }
                var retry=SyncFailure.of(error,0);if(retry.delay().compareTo(readbackDelay)>0)readbackDelay=retry.delay();
                readbackDetail="CATALOG_READBACK_RETRY_PENDING";readFailureObserved=true;
                store.item(shop,item,"WAITING_REMOTE",readbackDetail,null,current.feedId());attempt.waiting(error);
                // Stop this read pass at a service limit; the saved queue resumes later.
                if(CatalogFeedback.rateLimited(error))break;
            }
        }
        var progress=latest(shop);
        if(progress.remaining()>0){
            waitRemote(shop,run,readbackDetail,readbackDelay,readFailureObserved);
        }else{
            store.state(shop,run,progress.failed()>0?"NEEDS_ACTION":"COMPLETE",progress.failed()>0?"ZNACK_BATCH_NEEDS_ACTION":"ZNACK_BATCH_SUBMITTED",null);
        }
        request(shop);
        }
    }
    private void waitRemote(Shop shop,String run,String detail,Duration delay,boolean failure){
        if(!active(store.get(shop,run).state()))return;
        boolean exhausted=failure&&store.retry(shop,run)>=5;
        // A healthy acknowledged import is read-only pending work, not a failed create.
        // Slow Catalog processing must never require a second Register click.
        if(exhausted){
            store.control(shop,run,"PAUSED","ZNACK_BATCH_RETRY_LIMIT_REACHED");PENDING.remove(key(shop));return;
        }
        store.state(shop,run,"WAITING_REMOTE",detail,Instant.now().plus(delay));
    }
    private void observeReceipt(Shop shop,ZnackRegistrationBatchStore.Item item,Sku current,RegistrationAttemptReports.Attempt attempt){
        if(current==null||!current.hasGtin()||current.goodId()==null&&!current.hasFeed())throw new IllegalStateException("ZNACK_ACCEPTED_FEED_REQUIRED");
        boolean failed=Set.of(Status.ERROR,Status.PREFLIGHT_ERROR,Status.PRE_SUBMIT_ERROR,Status.FEED_REVIEW_REQUIRED,Status.GTIN_REVIEW_REQUIRED).contains(current.status());
        boolean verified=ZnackCardRegistrationWorkflow.verifiedSubmission(current);
        String detail=Objects.toString(current.errorMessage(),"");
        if(!failed&&!verified&&detail.isBlank())detail="CATALOG_REQUEST_ACCEPTED_CARD_PENDING";
        store.item(shop,item,failed?"NEEDS_ACTION":verified?"SUBMITTED":"WAITING_REMOTE",detail,null,current.feedId());
        attempt.observe(current.status(),detail);
        store.state(shop,item.run(),"RUNNING",detail,null);
    }
    private static boolean safeUnsent(Sku sku){return !sku.hasFeed()&&sku.goodId()==null&&(Set.of(Status.READY_TO_SUBMIT,Status.PRE_SUBMIT_ERROR).contains(sku.status())
            ||!sku.hasGtin()&&Set.of(Status.NOT_CREATED,Status.CHECKING,Status.PREFLIGHT_ERROR,Status.ERROR).contains(sku.status()));}
    private void pause(Shop shop,String run,Throwable error){
        var failure=SyncFailure.of(error,0);
        boolean retry=!failure.actionRequired()&&CatalogFeedback.retryableRead(error);
        if(retry&&store.retry(shop,run)>=5){store.control(shop,run,"PAUSED","ZNACK_BATCH_RETRY_LIMIT_REACHED");PENDING.remove(key(shop));request(shop);return;}
        store.state(shop,run,retry?"WAITING_NETWORK":"NEEDS_ACTION",failure.code(),retry?Instant.now().plus(failure.delay()):null);
        if(!retry){PENDING.remove(key(shop));RegistrationAttemptReports.attach(shop,run).stopPending(failure.code(),false);}
        request(shop);
    }
    private void scheduleRetry(Shop shop,String run,Instant retry){
        if(executor!=EXECUTOR)return;long delay=Math.max(1,retry==null?5:Duration.between(Instant.now(),retry).toMillis()/1000);
        EXECUTOR.schedule(()->{try(var scope=SyncDataScope.enter(directory)){var batch=store.get(shop,run);if(active(batch.state()))dispatch(shop,run);}catch(RuntimeException error){PENDING.remove(key(shop));}},delay,TimeUnit.SECONDS);
    }
    private void assertScope(Shop shop,ZnackRegistrationBatchStore.Batch batch){
        ListingIdentity.require(shop);if(strictScope&&!JSON.fromJson(batch.scope(),ZnackRegistrationScope.class).equals(currentScope(shop)))throw new ZnackRegistrationScope.ScopeChangedException();
    }
    private void assertNativeScope(Shop shop,Sku sku,ZnackRegistrationBatchStore.Batch batch){
        if(!strictScope||!sku.hasIdentity())return;
        if(!batch.owner().equals(registrations.ownerHash(shop.getId(),sku.chrtId()))||!JSON.fromJson(batch.scope(),ZnackRegistrationScope.class).equals(registrations.scope(shop.getId(),sku.chrtId())))throw new ZnackRegistrationScope.ScopeChangedException();
    }
    private static ZnackRegistrationScope currentScope(Shop shop){return ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());}
    private static boolean active(String state){return Set.of("QUEUED","RUNNING","WAITING_NETWORK","WAITING_REMOTE").contains(state);}
    private static Shop freeze(Shop shop){return new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());}
    private static String article(Sku sku){return sku==null?"":Objects.requireNonNullElse(sku.vendorCode(),"");}
    private static String size(Sku sku){return sku==null?"":Objects.requireNonNullElse(sku.size(),"");}
    private static void request(Shop shop){try{new SyncStreamStore().request(shop,"ZNACK");SyncRuntime.requestIfRunning(shop,SyncCoordinator.Stream.ZNACK);}catch(RuntimeException unavailable){/* The frozen queue is already durable. */}}
}
