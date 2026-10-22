package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.vncode.app.features.products.*;
import com.vncode.app.features.sync.RegistrationCardIndex;
import com.vncode.app.features.sync.RegistrationSources;
import com.vncode.app.features.sync.SyncFailure;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppPaths;
import java.util.*;
import java.util.concurrent.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;

/** Durable, sequential Catalog submission. Moderation/signing stays with the existing monitor. */
public final class OzonRegistrationBatchService {
    public enum State { QUEUED,RUNNING,WAITING,WAITING_REMOTE,COMPLETED,FAILED,PAUSED,CANCELLED }
    public record Progress(String runId,int total,int submitted,int failed,int remaining,String currentArticle,String currentSize,String detail,State state,String lastSubmittedArticle,String lastSubmittedSize,int cancelled,int retryCount,java.time.Instant nextRetryAt,int acceptedFeeds,int awaitingReadback) {
        public Progress(String runId,int total,int submitted,int failed,int remaining,String currentArticle,String currentSize,String detail,State state,String lastSubmittedArticle,String lastSubmittedSize,int cancelled,int retryCount,java.time.Instant nextRetryAt){this(runId,total,submitted,failed,remaining,currentArticle,currentSize,detail,state,lastSubmittedArticle,lastSubmittedSize,cancelled,retryCount,nextRetryAt,0,0);}
        public Progress(String runId,int total,int submitted,int failed,int remaining,String currentArticle,String currentSize,String detail,State state,String lastSubmittedArticle,String lastSubmittedSize){this(runId,total,submitted,failed,remaining,currentArticle,currentSize,detail,state,lastSubmittedArticle,lastSubmittedSize,0,0,null);}
    }
    private static final ScheduledExecutorService WORKERS=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"ozon-registration-batch");t.setDaemon(true);return t;});
    private static final Set<String> ACTIVE=ConcurrentHashMap.newKeySet();
    private static final Map<String,Object> DEFERRED=new ConcurrentHashMap<>();
    private record LookupRun(String run,RegistrationExistingCards.LookupContext context){}
    private static final Map<String,LookupRun> LOOKUPS=new ConcurrentHashMap<>();
    private static final int MAX_RETRIES=5;
    private final OzonRegistrationBatchRepository batches;
    private final OzonCardRegistrationRepository cards;
    private final OzonCardRegistrationWorkflow workflow;
    public OzonRegistrationBatchService(){this(new OzonRegistrationBatchRepository(),new OzonCardRegistrationRepository(),new OzonCardRegistrationWorkflow());}
    public OzonRegistrationBatchService(OzonRegistrationBatchRepository batches,OzonCardRegistrationRepository cards,OzonCardRegistrationWorkflow workflow){this.batches=Objects.requireNonNull(batches);this.cards=Objects.requireNonNull(cards);this.workflow=Objects.requireNonNull(workflow);}
    public String start(Shop selected,List<ProductKey> keys,boolean autoSign) {
        Shop shop=capture(selected);new RegistrationSources().require(shop);
        String id;
        synchronized(OzonRegistrationBatchService.class){
            var prior=batches.latest(shop);
            if(prior!=null&&!Set.of("COMPLETED","FAILED","CANCELLED").contains(prior.state()))id=prior.id();
            else{var frozen=List.copyOf(new LinkedHashSet<>(keys));if(frozen.isEmpty()||frozen.size()>100000||frozen.stream().anyMatch(k->k.shopId()!=shop.getId()||k.marketplace()!=Marketplace.OZON))throw new IllegalArgumentException("invalid_selection");
                var scope=scope(shop);var eligible=eligible(shop,frozen);id=OzonRegistrationAttemptReports.queue(shop,frozen);id=batches.create(shop,frozen,autoSign,scope,id);
                int position=0;for(var key:frozen){if(!eligible.contains(key))item(shop,new OzonRegistrationBatchRepository.Item(id,position,key,"PENDING",""),"FAILED","SOURCE_CARD_ACTION_CHANGED");position++;}}
        }
        resumePending(shop);return id;
    }
    private Set<ProductKey> eligible(Shop shop,List<ProductKey> keys){
        var receipt=new RegistrationSources().require(shop);var index=new RegistrationCardIndex(shop);var wanted=Set.copyOf(keys);var allowed=new HashSet<ProductKey>();var products=new ProductRepository();String cursor=null;
        do{var page=products.loadPage(new ProductQuery(shop.getId(),Marketplace.OZON,"",List.of(),null,false,cursor,1000),receipt.run());var rows=cards.findMany(page.rows().stream().map(ProductRow::key).toList());
            for(var product:page.rows())if(wanted.contains(product.key())){var row=rows.get(product.key());var status=row==null?Status.NOT_CREATED:row.status();var view=index.viewOzon(status,row==null?"":row.gtin(),product,row==null||index.ownsOzon(row.owner(),row.scopeJson()));
                if(!view.published()&&!view.blocked()&&(row==null?!view.exists():RegistrationCardIndex.notSubmitted(status,row.gtin(),row.goodId(),row.feedId(),false)))allowed.add(product.key());}
            cursor=page.nextCursor();
        }while(cursor!=null);if(!new RegistrationSources().require(shop).run().equals(receipt.run()))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");return Set.copyOf(allowed);
    }
    public Progress latest(Shop shop) {
        var batch=batches.latest(shop);if(batch==null)return null;
        var c=batches.counts(batch.id());var next=batches.next(batch);var last=batches.lastSubmitted(batch);
        return new Progress(batch.id(),c.total(),c.submitted(),c.failed(),c.remaining(),next==null?"":next.key().variantId(),"",batch.detail(),State.valueOf(batch.state()),last==null?"":last.key().variantId(),"",c.cancelled(),batch.retryCount(),batch.nextRetryAt(),c.acceptedFeeds(),c.awaitingReadback());
    }
    public boolean pause(Shop selected){return control(selected,"PAUSED");}
    public boolean cancel(Shop selected){return control(selected,"CANCELLED");}
    public boolean resumePaused(Shop selected){Shop shop=capture(selected);if(!batches.control(shop,"QUEUED"))return false;resumePending(shop);return true;}
    private boolean control(Shop selected,String state){Shop shop=capture(selected);if(!batches.control(shop,state))return false;String namespace=namespace(shop);LOOKUPS.remove(namespace);if(DEFERRED.remove(namespace)!=null)ACTIVE.remove(namespace);var batch=batches.latest(shop);OzonRegistrationAttemptReports.control(shop,batch.id(),state);return true;}
    /** Called by the existing sync monitor at startup and after network recovery. */
    public boolean resumePending(Shop selected) {
        if(selected==null||selected.getMarketplace()!=Marketplace.OZON)return false;
        var batch=batches.latest(selected);
        if(batch!=null&&Set.of("COMPLETED","FAILED").contains(batch.state())){
            var eligible=new ArrayList<OzonRegistrationBatchRepository.Item>();
            for(var item:batches.failedItems(batch)){
                var row=cards.find(item.key());if(row==null||row.status()!=Status.ERROR||row.feedId().isBlank()||Set.of("SENDING","RECONCILE_REQUIRED").contains(row.signatureState()))continue;
                cards.guard(selected,item.key(),scope(selected));
                if(CatalogPackingPolicy.correctedPayload(new ZnackNationalCatalogService.FeedProgress("Rejected",row.goodId(),List.of(row.error())),com.google.gson.JsonParser.parseString(row.payload()).getAsJsonObject())!=null)eligible.add(item);
            }
            if(batches.reopenRejected(selected,batch,eligible))batch=batches.latest(selected);
        }
        if(batch==null||Set.of("COMPLETED","FAILED","PAUSED","CANCELLED").contains(batch.state())){String key=namespace(selected);if(DEFERRED.remove(key)!=null)ACTIVE.remove(key);return false;}
        Shop shop=capture(selected);String namespace=namespace(shop);
        if(ACTIVE.add(namespace)){var directory=AppPaths.appDataDir().toAbsolutePath().normalize();long delay=batch.nextRetryAt()==null?0:Math.max(0,java.time.Duration.between(java.time.Instant.now(),batch.nextRetryAt()).toSeconds());if(delay>0)schedule(shop,namespace,directory,delay);else WORKERS.execute(()->run(shop,namespace,directory));}
        return true;
    }
    public boolean ownsPending(Shop shop,ProductKey key){return batches.ownsPending(shop,key);}
    public boolean blocksSigning(Shop shop,ProductKey key){return batches.blocksSigning(shop,key);}
    public static boolean hasPendingJobs(){return !ACTIVE.isEmpty();}
    private void run(Shop shop,String namespace,java.nio.file.Path directory) {
        boolean deferred=false;OzonRegistrationBatchRepository.Batch batch=null;
        try(var dataScope=com.vncode.app.features.sync.SyncDataScope.enter(directory);var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(true);var cooldown=com.vncode.app.shared.RateLimitDeferral.enter()) {
            com.vncode.app.features.sync.SyncDataScope.requireCurrent();
            batch=batches.latest(shop);if(batch==null||Set.of("COMPLETED","FAILED","PAUSED","CANCELLED").contains(batch.state()))return;
            if(!batch.owner().equals(ListingIdentity.fingerprint(shop))||!batch.scope().equals(new Gson().toJson(scope(shop)))){failPending(shop,batch.id(),"SOURCE_CONFIGURATION_CHANGED");return;}
            var frozenBatch=batch;var lookup=LOOKUPS.compute(namespace,(key,old)->old!=null&&old.run().equals(frozenBatch.id())?old:new LookupRun(frozenBatch.id(),workflow.lookupOperation())).context();
            var checkedReceipts=new HashSet<Integer>();
            for(;;) {
                var current=batches.latest(shop);if(current==null||!current.id().equals(batch.id())||Set.of("PAUSED","CANCELLED").contains(current.state()))return;
                var item=batches.next(batch);
                if(item==null){
                    // Delayed imports never prevent the remaining selected products being sent.
                    // Each saved receipt is read at most once in this worker pass.
                    for(var waiting:batches.waitingReadback(batch)){
                        if(!checkedReceipts.add(waiting.position()))continue;
                        new RegistrationSources().require(shop);scope(shop).assertCurrent(shop);ListingIdentity.require(shop);
                        try{
                            if(workflow.advanceSubmissionForBatch(shop,waiting.key()))observeReceipt(shop,waiting);
                            if(deferReadbackLimit(shop,namespace,directory,batch.id())){deferred=true;return;}
                        }catch(Exception unavailable){
                            if(unavailable instanceof ZnackRegistrationScope.ScopeChangedException changed)throw changed;
                            var reason=SyncFailure.of(unavailable,0);
                            observeReadbackFailure(shop,waiting,unavailable);
                            if(CatalogFeedback.rateLimited(unavailable)){defer(shop,namespace,directory,batch.id(),"CATALOG_READBACK_RETRY_PENDING",Math.max(15,reason.delay().toSeconds()));deferred=true;return;}
                        }
                    }
                    if(batches.counts(batch.id()).remaining()>0){
                        defer(shop,namespace,directory,batch.id(),"CATALOG_REQUEST_ACCEPTED_CARD_PENDING",15);deferred=true;return;
                    }
                    batches.state(batch.id(),"COMPLETED","");return;
                }
                if(workflow.isRunning(shop)){defer(shop,namespace,directory,batch.id(),"REGISTRATION_BUSY",2);deferred=true;return;}
                if(!batches.begin(item))return;
                batches.state(batch.id(),"RUNNING","");OzonRegistrationAttemptReports.observe(shop,item.run(),item.key(),"RUNNING","");
                try {
                    new RegistrationSources().require(shop);scope(shop).assertCurrent(shop);ListingIdentity.require(shop);
                    var row=cards.find(item.key());
                    if(row!=null&&(!row.feedId().isBlank()||row.goodId()!=null)) {
                        checkedReceipts.add(item.position());cards.guard(shop,item.key(),scope(shop));
                        if(workflow.advanceSubmissionForBatch(shop,item.key()))observeReceipt(shop,item);
                        else item(shop,item,"WAITING_REMOTE","REGISTRATION_BUSY");
                        if(deferReadbackLimit(shop,namespace,directory,batch.id())){deferred=true;return;}
                        continue;
                    }
                    if(row!=null&&Set.of(Status.GTIN_REVIEW_REQUIRED,Status.FEED_REVIEW_REQUIRED,Status.ALLOCATING_GTIN,Status.FEED_SUBMITTING).contains(row.status())) {
                        // A crash during a write has an unknown remote outcome. Never request another GTIN/feed.
                        if(row.status()==Status.ALLOCATING_GTIN)cards.uncertain(item.key(),Status.GTIN_REVIEW_REQUIRED,"GTIN_ALLOCATION_READBACK_REQUIRED");
                        if(row.status()==Status.FEED_SUBMITTING)cards.uncertain(item.key(),Status.FEED_REVIEW_REQUIRED,"CATALOG_FEED_READBACK_REQUIRED");
                        item(shop,item,"FAILED",row.status().name());continue;
                    }
                    var member=item;
                    java.util.function.BiConsumer<Status,String> receiptListener=(state,detail)->{if(state==Status.FEED_SUBMITTED)item(shop,member,"WAITING_REMOTE","CATALOG_REQUEST_ACCEPTED_CARD_PENDING");};
                    boolean accepted;
                    if(row!=null&&row.status()==Status.READY_TO_SUBMIT)accepted=workflow.resumeForBatch(shop,item.key(),batch.autoSign(),receiptListener);
                    else {
                        if(row!=null&&row.status()==Status.CHECKING)cards.transition(item.key(),Status.CHECKING,Status.PREFLIGHT_ERROR);
                        var prepared=workflow.prepare(shop,item.key());
                        var draft=OzonCardRegistrationWorkflow.suggestedDraft(prepared);
                        if(draft.isEmpty()||prepared.documents().automatic()==null){item(shop,item,"FAILED",draft.isEmpty()?"REGISTRATION_MISSING_REQUIRED_FIELDS":"REGISTRATION_DOCUMENT_REVIEW_REQUIRED");continue;}
                        accepted=workflow.registerReviewedForBatch(shop,prepared,draft.get().withAutoSign(batch.autoSign()),receiptListener,lookup);
                    }
                    if(!accepted){item(shop,item,"PENDING","");defer(shop,namespace,directory,batch.id(),"REGISTRATION_BUSY",2);deferred=true;return;}
                    workflow.completion(shop).toCompletableFuture().join();
                    row=cards.find(item.key());
                    if(row!=null&&(!row.feedId().isBlank()||row.goodId()!=null)){
                        checkedReceipts.add(item.position());observeReceipt(shop,item);
                        if(deferReadbackLimit(shop,namespace,directory,batch.id())){deferred=true;return;}
                    }
                    else if(row!=null&&row.status()==Status.READY_TO_SUBMIT){item(shop,item,"PENDING",row.error());defer(shop,namespace,directory,batch.id(),"NETWORK_RETRY",10);deferred=true;return;}
                    else if((row==null||row.status()==Status.PREFLIGHT_ERROR)&&workflow.failure(shop)!=null&&!SyncFailure.of(workflow.failure(shop),0).actionRequired()){
                        var reason=SyncFailure.of(workflow.failure(shop),0);item(shop,item,"PENDING",reason.code());defer(shop,namespace,directory,batch.id(),reason.code(),Math.max(2,reason.delay().toSeconds()));deferred=true;return;
                    }
                    else item(shop,item,"FAILED",row==null?"REGISTRATION_NOT_SUBMITTED":row.error().isBlank()?row.status().name():row.error());
                }catch(Exception failure) {
                    if(failure instanceof ZnackRegistrationScope.ScopeChangedException){failPending(shop,batch.id(),"SOURCE_CONFIGURATION_CHANGED");return;}
                    var reason=SyncFailure.of(failure,0);
                    var row=cards.find(item.key());
                    if(row!=null&&(!row.feedId().isBlank()||row.goodId()!=null)){
                        checkedReceipts.add(item.position());observeReadbackFailure(shop,item,failure);
                        if(CatalogFeedback.rateLimited(failure)){defer(shop,namespace,directory,batch.id(),"CATALOG_READBACK_RETRY_PENDING",Math.max(15,reason.delay().toSeconds()));deferred=true;return;}
                        continue;
                    }
                    if(!reason.actionRequired()) {item(shop,item,"PENDING",reason.code());defer(shop,namespace,directory,batch.id(),reason.code(),Math.max(2,reason.delay().toSeconds()));deferred=true;return;}
                    item(shop,item,"FAILED",reason.code());
                }
            }
        }catch(ZnackRegistrationScope.ScopeChangedException changed){
            if(batch!=null&&directory.equals(AppPaths.appDataDir().toAbsolutePath().normalize()))failPending(shop,batch.id(),"SOURCE_CONFIGURATION_CHANGED");
        }finally {if(!deferred){ACTIVE.remove(namespace);LOOKUPS.remove(namespace);}}
    }
    private void observeReceipt(Shop shop,OzonRegistrationBatchRepository.Item item){
        var row=cards.find(item.key());
        if(row!=null&&Set.of(Status.ERROR,Status.PREFLIGHT_ERROR,Status.PRE_SUBMIT_ERROR,Status.GTIN_REVIEW_REQUIRED,Status.FEED_REVIEW_REQUIRED).contains(row.status())){
            item(shop,item,"FAILED",row.error());return;
        }
        var failure=workflow.failure(shop);
        if(failure!=null){observeReadbackFailure(shop,item,failure);return;}
        if(OzonCardRegistrationWorkflow.verifiedSubmission(row)){item(shop,item,"SUBMITTED",row.error());return;}
        item(shop,item,"WAITING_REMOTE",row==null||row.error().isBlank()?"CATALOG_REQUEST_ACCEPTED_CARD_PENDING":row.error());
    }
    private void observeReadbackFailure(Shop shop,OzonRegistrationBatchRepository.Item item,Throwable failure){
        boolean retry=CatalogFeedback.retryableRead(failure)||CatalogFeedback.notFound(failure);
        item(shop,item,retry?"WAITING_REMOTE":"FAILED",retry?"CATALOG_READBACK_RETRY_PENDING":com.vncode.app.integration.znack.ZnackSanitizer.error(failure));
    }
    private boolean deferReadbackLimit(Shop shop,String namespace,java.nio.file.Path directory,String run){
        var failure=workflow.failure(shop);if(failure==null||!CatalogFeedback.rateLimited(failure))return false;
        defer(shop,namespace,directory,run,"CATALOG_READBACK_RETRY_PENDING",Math.max(15,SyncFailure.of(failure,0).delay().toSeconds()));return true;
    }
    private void defer(Shop shop,String namespace,java.nio.file.Path directory,String run,String detail,long seconds){
        if(!detail.equals("REGISTRATION_BUSY"))LOOKUPS.remove(namespace);
        var latest=batches.latest(shop);boolean counted=!Set.of("REGISTRATION_BUSY","CATALOG_REQUEST_ACCEPTED_CARD_PENDING").contains(detail);
        long delay=Math.max(seconds,counted?Math.min(300,5L<<Math.min(6,latest==null?0:latest.retryCount())):1);
        if(!batches.defer(run,detail,delay,counted,MAX_RETRIES)){ACTIVE.remove(namespace);LOOKUPS.remove(namespace);var stopped=batches.latest(shop);if(stopped!=null&&stopped.state().equals("PAUSED"))OzonRegistrationAttemptReports.control(shop,run,"PAUSED");return;}
        schedule(shop,namespace,directory,delay);
    }
    private void schedule(Shop shop,String namespace,java.nio.file.Path directory,long seconds){Object ticket=new Object();DEFERRED.put(namespace,ticket);WORKERS.schedule(()->{if(DEFERRED.remove(namespace,ticket))run(shop,namespace,directory);},Math.max(1,seconds),TimeUnit.SECONDS);}
    private void failPending(Shop shop,String run,String detail){batches.failPending(run,detail);OzonRegistrationAttemptReports.failPending(shop,run,detail);}
    private void item(Shop shop,OzonRegistrationBatchRepository.Item item,String state,String detail){var current=batches.latest(shop);if(state.equals("PENDING")&&current!=null&&current.id().equals(item.run())&&current.state().equals("CANCELLED")){state="CANCELLED";detail="REGISTRATION_BATCH_CANCELLED";}batches.item(item,state,detail);OzonRegistrationAttemptReports.observe(shop,item.run(),item.key(),state,detail);}
    private static ZnackRegistrationScope scope(Shop shop){return ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());}
    private static Shop capture(Shop shop){if(shop==null||shop.getMarketplace()!=Marketplace.OZON)throw new IllegalArgumentException("shop_mismatch");ListingIdentity.require(shop);return new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());}
    private static String namespace(Shop shop){return AppPaths.appDataDir().toAbsolutePath().normalize()+":"+shop.getId();}
}
