package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.znack.ZnackApiClient;
import com.vncode.app.integration.znack.ZnackAuthService;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.integration.znack.ZnackErrorDetails;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.RegistrationAction;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.signature.CryptoProSignatureProvider;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import com.vncode.app.models.Shop;

import java.time.Duration;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.concurrent.Executor;

/** Durable registration workflow. A checkpoint is written before every remote transition. */
public final class ZnackCardRegistrationWorkflow {
    private static final int POLL_ATTEMPTS = 3;
    private static final Object GTIN_CHECKPOINT_LOCK = CatalogAllocationLock.MONITOR;
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256), runnable -> {
        Thread thread = new Thread(()->{try(var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(true)){runnable.run();}}, "znack-card-registration");
        thread.setDaemon(true);
        return thread;
    },new ThreadPoolExecutor.AbortPolicy());

    private final ZnackCardRegistrationRepository registrations;
    private final com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal commands=new com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal();
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();
    private static final Map<String,java.util.concurrent.CompletableFuture<Void>> COMPLETIONS = new ConcurrentHashMap<>();
    private static final Map<String,Throwable> SUBMISSION_FAILURES=new ConcurrentHashMap<>();
    private final CatalogFactory catalogFactory;
    private final Executor executor;
    private final boolean strictScope;
    private final String namespace=com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize().toString();
    private final MarketplaceCompletion marketplaceCompletion;
    private final java.util.function.Supplier<RegistrationExistingCards.LookupContext> lookups;
    private final RegistrationSnapshotReview.Reader snapshots;

    public ZnackCardRegistrationWorkflow(ZnackCardRegistrationRepository registrations) {
        this(registrations,ZnackCardRegistrationWorkflow::productionCatalog,EXECUTOR,true,ZnackCardRegistrationWorkflow::productionCandidatePresent);
    }

    public record CatalogSession(String token, ZnackNationalCatalogService catalog, ZnackRegistrationScope scope) {
        public CatalogSession(String token,ZnackNationalCatalogService catalog){this(token,catalog,null);}
    }
    @FunctionalInterface public interface MarketplaceCompletion {
        boolean candidatePresent(Shop shop,long nmId,long variantId,String candidateGtin) throws Exception;
    }

    @FunctionalInterface
    public interface CatalogFactory { CatalogSession open(Shop shop) throws Exception; }

    public ZnackCardRegistrationWorkflow(ZnackCardRegistrationRepository registrations,
                                        CatalogFactory factory, Executor executor) {
        this(registrations,factory,executor,false,(shop,nmId,variantId,gtin)->false);
    }
    public ZnackCardRegistrationWorkflow(ZnackCardRegistrationRepository registrations,
            CatalogFactory factory,Executor executor,boolean strictScope,MarketplaceCompletion completion) {
        this(registrations,factory,executor,strictScope,completion,RegistrationExistingCards::currentOperation);
    }
    public ZnackCardRegistrationWorkflow(ZnackCardRegistrationRepository registrations,
            CatalogFactory factory,Executor executor,boolean strictScope,MarketplaceCompletion completion,
            java.util.function.Supplier<RegistrationExistingCards.LookupContext> lookups) {
        this(registrations,factory,executor,strictScope,completion,lookups,(shop,key)->new com.vncode.app.integration.wb.WbProductSnapshotReader().readForSynchronization(shop,key));
    }
    ZnackCardRegistrationWorkflow(ZnackCardRegistrationRepository registrations,
            CatalogFactory factory,Executor executor,boolean strictScope,MarketplaceCompletion completion,
            java.util.function.Supplier<RegistrationExistingCards.LookupContext> lookups,RegistrationSnapshotReview.Reader snapshots) {
        this.registrations=registrations;this.catalogFactory=factory;this.executor=executor;
        this.strictScope=strictScope;this.marketplaceCompletion=completion;this.lookups=Objects.requireNonNull(lookups);this.snapshots=Objects.requireNonNull(snapshots);
    }
    public static boolean hasActiveJobs(){return !RUNNING.isEmpty();}
    private String operationKey(Shop shop,Sku sku){return namespace+":"+shop.getId()+":"+sku.chrtId();}
    /** The batch advances after the worker checkpoints its result, not when it is enqueued.
     *  Pending moderation is a completed attempt, never a published-card success. */
    public java.util.concurrent.CompletionStage<Void> completion(Shop shop,Sku sku) {
        var result=COMPLETIONS.get(operationKey(shop,sku));
        return result==null?java.util.concurrent.CompletableFuture.completedFuture(null):result.minimalCompletionStage();
    }
    private void finish(String key,java.util.concurrent.CompletableFuture<Void> completion) {
        RUNNING.remove(key);COMPLETIONS.remove(key,completion);completion.complete(null);
    }
    public int activeCount(Shop shop) {
        String prefix=namespace+":"+shop.getId()+":";return (int)RUNNING.stream().filter(key->key.startsWith(prefix)).count();
    }
    public boolean registerNew(Shop shop,Sku published,Draft draft,boolean confirmed,BiConsumer<Status,String> listener) {
        if(!confirmed || published==null || !published.canRegisterNew())return false;
        // A bare boolean is not durable seller approval for an identity-changing
        // allocation. Do not archive/reset the current card before such approval
        // and its exact old identity are bound to the queued operation.
        notify(listener,Status.ERROR,"ZNACK_SOURCE_REVIEW_REQUIRED");return false;
    }
    private boolean replaceRejected(Shop requested,Sku old,Draft draft,BiConsumer<Status,String> listener){
        Shop shop=freezeShop(requested);if(shop==null||draft==null||shop.getMarketplace()!=Marketplace.WILDBERRIES)return false;
        LicenseRuntime.require(Permission.GTIN);String key=operationKey(shop,old);if(!RUNNING.add(key))return false;
        var completion=new java.util.concurrent.CompletableFuture<Void>();COMPLETIONS.put(key,completion);
        try{executor.execute(()->{
            try{
                guard(shop,old);var session=catalogFactory.open(shop);guard(shop,old);
                if(strictScope&&!registrations.scope(shop.getId(),old.chrtId()).equals(session.scope()))throw new ZnackRegistrationScope.ScopeChangedException();
                if(!CatalogFeedback.confirmedRejection(session.catalog().submissionStatus(session.token(),old.feedId(),old.gtin())))throw new IllegalStateException("CATALOG_NEW_GTIN_REQUIRES_REJECTED_FEED");
                guard(shop,old);LicenseRuntime.require(Permission.GTIN);
                if(!registrations.claimRejectedReplacement(shop.getId(),old))throw new IllegalStateException("REGISTRATION_CHANGED");
                execute(shop,freshCandidate(old),draft,true,false,true,listener);
            }catch(Exception error){fail(shop,old,error,listener);}finally{requestUnsentContinuation(shop,old);finish(key,completion);}
        });return true;}catch(java.util.concurrent.RejectedExecutionException full){finish(key,completion);return false;}
    }
    /** One bounded executor item; cards are claimed sequentially from the frozen reviewed list. */
    public boolean signApproved(Shop requestedShop,List<Sku> skus,boolean confirmed,BiConsumer<Status,String> listener) {
        if(confirmed)notify(listener,Status.READY_TO_SIGN,"SIGNING_REVIEW_REQUIRED");return false;
    }
    /** Background aggregation may use only the seller's existing exact-card consent. */
    public boolean signConsented(Shop requestedShop,List<Sku> skus,BiConsumer<Status,String> listener) {
        return signSelected(requestedShop,skus,false,null,listener);
    }
    public RegistrationSigningReview prepareApprovedSigning(Shop requested,List<Sku> selected)throws Exception {
        Shop shop=freezeShop(requested);com.vncode.app.integration.listing.ListingIdentity.require(shop);LicenseRuntime.require(Permission.GTIN);
        if(selected==null||selected.size()>1000)throw new RegistrationValidationException("SIGNING_REVIEW_TOO_LARGE");
        long reviewBytes=0;
        var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());
        var session=catalogFactory.open(shop);if(session.scope()!=null&&!scope.equals(session.scope()))throw new ZnackRegistrationScope.ScopeChangedException();
        var cards=new java.util.ArrayList<RegistrationSigningReview.Card>();var seen=new java.util.HashSet<Long>();
        for(var requestedSku:List.copyOf(selected)){
            if(!seen.add(requestedSku.chrtId()))continue;
            var sku=registrations.find(shop.getId(),requestedSku.chrtId());
            if(sku==null||sku.nmId()!=requestedSku.nmId()||!Objects.equals(sku.gtin(),requestedSku.gtin())||!Objects.equals(sku.registrationRevision(),requestedSku.registrationRevision()))throw new RegistrationValidationException("REGISTRATION_CHANGED");
            guard(shop,sku);var progress=observedProgress(shop,session,sku);
            if(progress.signed())continue;
            var existingSignature=registrations.signingCheckpoint(shop.getId(),sku.chrtId());
            if(existingSignature!=null&&!"PREPARED".equals(existingSignature.state()))continue;
            if(progress.failed()||!progress.readyToSign())throw new RegistrationValidationException(progress.failed()?CatalogFeedback.rejection(progress):"CATALOG_SIGNING_DOCUMENT_PENDING");
            var document=session.catalog().reviewSigning(session.token(),sku.gtin());
            if(progress.goodId()==null||progress.goodId()!=document.goodId()||sku.goodId()==null||sku.goodId()!=document.goodId())throw new RegistrationValidationException("CATALOG_IDENTITY_CHANGED");
            reviewBytes+=document.xml().length;if(reviewBytes>8L*1024*1024)throw new RegistrationValidationException("SIGNING_REVIEW_TOO_LARGE");
            cards.add(new RegistrationSigningReview.Card(Long.toString(sku.nmId()),Long.toString(sku.chrtId()),sku.registrationRevision(),sku.gtin(),sku.goodId(),progress.status(),document));
        }
        scope.assertCurrent(shop);com.vncode.app.integration.listing.ListingIdentity.require(shop);
        return new RegistrationSigningReview(com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop),scope,scope.fingerprint(),cards);
    }
    public boolean signApprovedReviewed(Shop requested,RegistrationSigningReview review,BiConsumer<Status,String> listener){
        Shop shop=freezeShop(requested);if(review==null||review.cards().isEmpty())return false;
        com.vncode.app.integration.listing.ListingIdentity.require(shop);review.scope().assertCurrent(shop);
        if(!review.owner().equals(com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop)))throw new SecurityException("SYNC_OWNER_CHANGED");
        var selected=new java.util.ArrayList<Sku>();
        for(var card:review.cards()){
            var sku=registrations.find(shop.getId(),Long.parseLong(card.variantId()));
            if(sku==null||!Long.toString(sku.nmId()).equals(card.productId())||!Objects.equals(sku.gtin(),card.gtin())||!Objects.equals(sku.goodId(),card.goodId())||!sku.registrationRevision().equals(card.revision()))throw new RegistrationValidationException("REGISTRATION_CHANGED");
            selected.add(sku);
        }
        return signSelected(shop,selected,true,review,listener);
    }
    private boolean signSelected(Shop requestedShop,List<Sku> skus,boolean manual,RegistrationSigningReview reviewed,BiConsumer<Status,String> listener) {
        Shop shop=freezeShop(requestedShop);
        if(shop==null || shop.getMarketplace()!=Marketplace.WILDBERRIES || skus==null || skus.size()>10_000)return false;
        var selected=new LinkedHashMap<Long,Sku>();
        for(Sku sku:List.copyOf(skus))if(sku!=null && sku.canSignApproved())selected.putIfAbsent(sku.chrtId(),sku);
        List<Sku> frozen=List.copyOf(selected.values());if(frozen.isEmpty())return false;
        var cooldown=RegistrationCatalogCooldown.warning(shop);if(cooldown!=null){notify(listener,Status.READY_TO_SIGN,cooldown.code());return false;}
        LicenseRuntime.require(Permission.GTIN);
        ZnackRegistrationScope owner=reviewed!=null?reviewed.scope():strictScope?ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings()):null;
        String bulkKey=namespace+":"+shop.getId()+":bulk-sign";if(!RUNNING.add(bulkKey))return false;
        try {
            executor.execute(()->{
                try {
                    CatalogSession session=null;int next=0;
                    while(next<frozen.size()){
                        var members=new java.util.ArrayList<SigningMember>(10);var held=new java.util.ArrayList<String>(10);
                        try{
                            while(next<frozen.size()&&members.size()<10){
                                Sku sku=frozen.get(next++);String key=operationKey(shop,sku);if(!RUNNING.add(key))continue;
                                boolean retained=false,claimed=false;
                                try{
                                    if(owner!=null){owner.assertCurrent(shop);guard(shop,sku);}LicenseRuntime.require(Permission.GTIN);
                                    var nativeKey=signingKey(shop,sku);
                                    if(!manual&&(new ZnackRegistrationBatchService(registrations).blocksSigning(shop,sku)||!WbSourceConsentPolicy.permitsAutomatic(shop,nativeKey,sku.gtin())||!RegistrationSigningConsent.allowed(shop,nativeKey,sku.gtin())))continue;
                                    if(!registrations.claim(shop.getId(),sku,RegistrationAction.SIGN_APPROVED))continue;
                                    claimed=true;
                                    if(session==null){session=catalogFactory.open(shop);if(owner!=null&&(strictScope&&session.scope()==null||session.scope()!=null&&!owner.equals(session.scope())))throw new ZnackRegistrationScope.ScopeChangedException();}
                                    var approved=reviewed==null?null:reviewed.cards().stream().filter(c->c.variantId().equals(Long.toString(sku.chrtId()))).findFirst().orElseThrow(()->new RegistrationValidationException("SIGNING_REVIEW_REQUIRED"));
                                    var member=prepareSigningMember(shop,sku,session,approved,listener);
                                    if(member!=null){members.add(member);held.add(key);retained=true;}
                                }catch(LicenseDeniedException stopped){
                                    if(claimed)pauseKnownUnsent(shop,sku,stopped,listener);throw stopped;
                                }catch(ZnackRegistrationScope.ScopeChangedException|SecurityException stopped){
                                    if(claimed)fail(shop,sku,stopped,listener);throw stopped;
                                }catch(Exception failure){
                                    if(claimed)fail(shop,sku,failure,listener);else notify(listener,sku.status(),ZnackErrorDetails.summary(failure));
                                    if(CatalogFeedback.rateLimited(failure)||failure instanceof InterruptedException)throw failure;
                                }finally{if(!retained)RUNNING.remove(key);}
                            }
                            if(!members.isEmpty()&&!sendSigningGroup(shop,session,members,listener))break;
                        }finally{for(String key:held)RUNNING.remove(key);}
                    }
                }catch(Exception stopped){RegistrationCatalogCooldown.record(shop,stopped);if(stopped instanceof InterruptedException)Thread.currentThread().interrupt();}
                finally{RUNNING.remove(bulkKey);}
            });return true;
        }catch(java.util.concurrent.RejectedExecutionException error){RUNNING.remove(bulkKey);return false;}
    }
    public boolean completeReplacement(Shop requestedShop,Sku sku,boolean confirmed,BiConsumer<Status,String> listener) {
        Shop shop=freezeShop(requestedShop);
        if(!confirmed || shop==null || shop.getMarketplace()!=Marketplace.WILDBERRIES || sku==null || !sku.canRegisterNew()
                || !registrations.hasPendingReplacement(shop.getId(),sku.chrtId()))return false;
        LicenseRuntime.require(Permission.GTIN);
        String key=operationKey(shop,sku);if(!RUNNING.add(key))return false;
        try {
            executor.execute(()->{
                try {
                    guard(shop,sku);
                    if(!registrations.matchesPublished(shop.getId(),sku))throw new IllegalStateException("Registration changed. Refresh the candidate.");
                    LicenseRuntime.require(Permission.GTIN);
                    if(!marketplaceCompletion.candidatePresent(shop,sku.nmId(),sku.chrtId(),sku.gtin()))
                        throw new IllegalStateException("The signed candidate is not verified on the marketplace yet.");
                    guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
                    if(!registrations.completeReplacement(shop.getId(),sku.chrtId(),sku.gtin()))
                        throw new IllegalStateException("Registration changed. Refresh the candidate.");
                    notify(listener,Status.PUBLISHED,"");
                }catch(Exception error){notify(listener,Status.PUBLISHED,ZnackErrorDetails.summary(error));}
                finally{RUNNING.remove(key);}
            });return true;
        }catch(java.util.concurrent.RejectedExecutionException error){RUNNING.remove(key);return false;}
    }
    private static boolean productionCandidatePresent(Shop shop,long nmId,long variantId,String gtin)throws Exception {
        return candidatePresent(shop,nmId,variantId,gtin,new com.vncode.app.integration.wb.WbGtinAdapter(shop));
    }
    static boolean candidatePresent(Shop shop,long nmId,long variantId,String gtin,com.vncode.app.features.gtinsync.GtinMarketplaceAdapter adapter)throws Exception {
        var source=com.vncode.app.features.gtinsync.RegisteredGtinProof.requireCurrent(shop,List.of(gtin)).get(gtin);
        var nativeCard=new ZnackCardRegistrationRepository().find(shop.getId(),variantId);
        String observedId=source.details().single("@catalog_id");
        if(nativeCard==null||nativeCard.nmId()!=nmId||nativeCard.status()!=Status.PUBLISHED||nativeCard.goodId()==null
                ||!com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(nativeCard.gtin(),gtin)
                ||!observedId.matches("[0-9]{1,64}")||!new java.math.BigInteger(observedId).equals(java.math.BigInteger.valueOf(nativeCard.goodId())))
            throw new RegistrationValidationException("CATALOG_IDENTITY_CHANGED");
        var key=new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(shop.getId(),Marketplace.WILDBERRIES,Long.toString(nmId),Long.toString(variantId));
        var actual=adapter.read(key);adapter.validateIdentity(actual,source);
        return com.vncode.app.features.gtinsync.RegisteredGtinProof.contains(actual.barcodes(),gtin);
    }
    private void guard(Shop shop,Sku sku) {
        if(!strictScope)return;
        var scope=registrations.scope(shop.getId(),sku.chrtId());
        if(scope==null)throw new ZnackRegistrationScope.ScopeChangedException();
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        if(!com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop).equals(registrations.ownerHash(shop.getId(),sku.chrtId())))throw new ZnackRegistrationScope.ScopeChangedException();
        scope.assertCurrent(shop);
    }

    public boolean requestNewGtin(Shop shop, Sku sku, Draft draft, boolean confirmed,
                                 BiConsumer<Status,String> listener) {
        if(confirmed)notify(listener,Status.ERROR,"ZNACK_SOURCE_REVIEW_REQUIRED");return false;
    }

    public boolean isRunning(Shop shop,Sku sku) {
        return shop!=null && sku!=null && RUNNING.contains(operationKey(shop,sku));
    }

    public boolean start(Shop shop, Sku sku, Draft draft, BiConsumer<Status, String> listener) {
        return schedule(shop,sku,draft,RegistrationAction.REGISTER,listener);
    }

    /** Batch submission stops at the durable accepted-feed checkpoint; moderation is independent. */
    RegistrationExistingCards.LookupContext existingCardsOperation(){return lookups.get();}
    public boolean submitOnly(Shop shop,Sku sku,Draft draft,BiConsumer<Status,String> listener) {
        return submitOnly(shop,sku,draft,listener,existingCardsOperation());
    }
    public boolean submitOnly(Shop shop,Sku sku,Draft draft,BiConsumer<Status,String> listener,RegistrationExistingCards.LookupContext lookup) {
        return schedule(shop,sku,draft,RegistrationAction.REGISTER,listener,true,lookup);
    }
    /** Reuse only this card's just-verified preparation session, with all send-time guards. */
    boolean submitOnly(Shop shop,Sku sku,Draft draft,BiConsumer<Status,String> listener,RegistrationExistingCards.LookupContext lookup,CatalogSession prepared) {
        return submitOnly(shop,sku,draft,listener,lookup,prepared,null);
    }
    boolean submitOnly(Shop shop,Sku sku,Draft draft,BiConsumer<Status,String> listener,RegistrationExistingCards.LookupContext lookup,CatalogSession prepared,RegistrationIntent intent) {
        return schedule(shop,sku,draft,RegistrationAction.REGISTER,listener,true,lookup,prepared,intent);
    }
    /** Replacement is bound to a durable reviewed operation, never a bare confirmation. */
    boolean replaceOnly(Shop shop,Sku sku,Draft draft,BiConsumer<Status,String> listener,RegistrationExistingCards.LookupContext lookup,CatalogSession session,RegistrationIntent intent)throws Exception{
        if(intent==null||sku==null||!Set.of(Status.PUBLISHED,Status.ERROR).contains(sku.status())||!sku.hasFeed()||!sku.hasGtin()||sku.goodId()==null)return false;
        if(session==null)session=catalogFactory.open(shop);if(session==null||session.scope()==null)return false;
        var product=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
        if(com.vncode.app.config.SqliteWriteTransaction.run(c->RegistrationSourceConsentGuard.action(c,intent,product))!=RegistrationSourceConsent.Action.REPLACE)throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");
        CatalogReplacementEvidence.verify(shop,product,session.scope(),intent,session); // before archiving/resetting the old row
        return schedule(shop,sku,draft,RegistrationAction.REGISTER_NEW,listener,true,lookup,session,intent);
    }
    public boolean resumeSubmissionOnly(Shop shop,Sku sku,BiConsumer<Status,String> listener) {
        return resumeSubmissionOnly(shop,sku,null,listener);
    }
    public boolean resumeSubmissionOnly(Shop shop,Sku sku,Boolean autoSign,BiConsumer<Status,String> listener) {
        return resumeSubmissionOnly(shop,sku,autoSign,listener,null);
    }
    boolean resumeSubmissionOnly(Shop shop,Sku sku,Boolean autoSign,BiConsumer<Status,String> listener,RegistrationIntent intent) {
        if(sku==null||!sku.canResume()||sku.hasFeed())return false;
        String payload=registrations.payload(shop.getId(),sku.chrtId());
        boolean draftReview=!payload.isBlank()&&ZnackNationalCatalogService.hasReviewedDraft(JsonParser.parseString(payload).getAsJsonObject());
        if(autoSign!=null){
            LicenseRuntime.require(Permission.GTIN);guard(shop,sku);
            var product=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
            if(!autoSign||draftReview)RegistrationSigningConsent.set(shop,product,sku.gtin(),false);RegistrationMarketplaceConsent.set(shop,product,sku.gtin(),!draftReview);
        }
        return !payload.isBlank()&&schedule(shop,sku,draftFromPayload(JsonParser.parseString(payload).getAsJsonObject()),RegistrationAction.RESUME,listener,true,null,null,intent);
    }
    private boolean schedule(Shop requestedShop,Sku sku,Draft draft,RegistrationAction action,
                             BiConsumer<Status,String> listener) {
        return schedule(requestedShop,sku,draft,action,listener,false);
    }
    private boolean schedule(Shop requestedShop,Sku sku,Draft draft,RegistrationAction action,
                             BiConsumer<Status,String> listener,boolean submissionOnly) {
        return schedule(requestedShop,sku,draft,action,listener,submissionOnly,null);
    }
    private boolean schedule(Shop requestedShop,Sku sku,Draft draft,RegistrationAction action,
                             BiConsumer<Status,String> listener,boolean submissionOnly,RegistrationExistingCards.LookupContext lookup) {
        return schedule(requestedShop,sku,draft,action,listener,submissionOnly,lookup,null);
    }
    private boolean schedule(Shop requestedShop,Sku sku,Draft draft,RegistrationAction action,
                             BiConsumer<Status,String> listener,boolean submissionOnly,RegistrationExistingCards.LookupContext lookup,CatalogSession prepared) {
        return schedule(requestedShop,sku,draft,action,listener,submissionOnly,lookup,prepared,null);
    }
    private boolean schedule(Shop requestedShop,Sku sku,Draft draft,RegistrationAction action,
                             BiConsumer<Status,String> listener,boolean submissionOnly,RegistrationExistingCards.LookupContext lookup,CatalogSession prepared,RegistrationIntent intent) {
        Shop shop=freezeShop(requestedShop);
        if(shop==null || sku==null || draft==null || shop.getMarketplace()!=Marketplace.WILDBERRIES) return false;
        // A submitted feed may still be read back after expiry. An unsent claim needs current rights.
        if(action!=RegistrationAction.RESUME || !sku.hasFeed())LicenseRuntime.require(Permission.GTIN);
        boolean groupChange=action==RegistrationAction.REGISTER&&sku.hasGtin()&&sku.hasFeed()&&TnvedGroupChange.replaceable(sku.status())&&TnvedGroupChange.changed(TnvedGroupChange.payloadCode(registrations.payload(shop.getId(),sku.chrtId())),draft.tnved());
        if(groupChange)throw new RegistrationValidationException("CATALOG_NEW_GTIN_CONFIRMATION_REQUIRED");
        String key=operationKey(shop,sku);
        if(!RUNNING.add(key))return false;
        if(submissionOnly)SUBMISSION_FAILURES.remove(key);
        try {
            if(action!=RegistrationAction.RESUME || !sku.hasFeed())LicenseRuntime.require(Permission.GTIN);
            ZnackRegistrationScope bound=null;
            if(strictScope) {
                bound=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());
                var prior=registrations.scope(shop.getId(),sku.chrtId());
                if(prior!=null && (!prior.equals(bound)||!com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop).equals(registrations.ownerHash(shop.getId(),sku.chrtId()))))throw new ZnackRegistrationScope.ScopeChangedException();
                if(prior==null && sku.hasIdentity() && action!=RegistrationAction.REGISTER_NEW)
                    throw new ZnackRegistrationScope.ScopeChangedException();
            }
            boolean claimed=action==RegistrationAction.REGISTER_NEW?registrations.claimReplacement(shop.getId(),sku):registrations.claim(shop.getId(),sku,action);
            if(!claimed){RUNNING.remove(key);return false;}
            if(bound!=null)registrations.bindScope(shop,sku.chrtId(),bound);
            var completion=new java.util.concurrent.CompletableFuture<Void>();COMPLETIONS.put(key,completion);
            try {
                executor.execute(()->{
                    try(var deferral=submissionOnly?com.vncode.app.shared.RateLimitDeferral.enter():null){execute(shop,action==RegistrationAction.REGISTER_NEW?freshCandidate(sku):sku,draft,action==RegistrationAction.REQUEST_NEW_GTIN || action==RegistrationAction.REGISTER_NEW,action==RegistrationAction.SIGN_APPROVED,action!=RegistrationAction.RESUME&&action!=RegistrationAction.SIGN_APPROVED,listener,submissionOnly,lookup,prepared,intent);}
                    catch(LicenseDeniedException denied){if(submissionOnly)SUBMISSION_FAILURES.put(key,denied);pauseKnownUnsent(shop,sku,denied,listener);}
                    catch(Exception error){if(submissionOnly)SUBMISSION_FAILURES.put(key,error);fail(shop,sku,error,listener);}
                    finally{requestUnsentContinuation(shop,sku);finish(key,completion);}
                });
            } catch (java.util.concurrent.RejectedExecutionException error) {
                // No task started, so a durable failure can prove no remote mutation occurred.
                fail(shop,sku,error,listener);
                finish(key,completion);
                return false;
            }
            return true;
        }catch(RuntimeException error){RUNNING.remove(key);throw error;}
    }

    Throwable consumeSubmissionFailure(Shop shop,Sku sku){return SUBMISSION_FAILURES.remove(operationKey(shop,sku));}

    /** Reconcile an acknowledged request without creating, signing or repeating it. */
    public boolean verifySubmissionForBatch(Shop requested, Sku sku) throws Exception {
        return verifySubmissionForBatch(requested,sku,false);
    }
    /** The seller's durable batch may correct a proven rejection without a second click. */
    public boolean advanceSubmissionForBatch(Shop requested,Sku sku)throws Exception {
        return advanceSubmissionForBatch(requested,sku,null);
    }
    boolean advanceSubmissionForBatch(Shop requested,Sku sku,RegistrationIntent intent)throws Exception {
        return verifySubmissionForBatch(requested,sku,true,intent);
    }
    private boolean verifySubmissionForBatch(Shop requested,Sku sku,boolean correctRejected)throws Exception {
        return verifySubmissionForBatch(requested,sku,correctRejected,null);
    }
    private boolean verifySubmissionForBatch(Shop requested,Sku sku,boolean correctRejected,RegistrationIntent intent)throws Exception {
        Shop shop = freezeShop(requested);
        String key = operationKey(shop, sku);
        if (!RUNNING.add(key)) return false;
        try {
            guard(shop, sku);
            CatalogSession session = catalogFactory.open(shop);
            guard(shop, sku);
            if (strictScope && !registrations.scope(shop.getId(), sku.chrtId()).equals(session.scope())) {
                throw new ZnackRegistrationScope.ScopeChangedException();
            }
            if(!sku.hasFeed()&&sku.goodId()!=null){
                var card=session.catalog().observedCardStatus(session.token(),sku.gtin(),sku.goodId());
                if(preserveUnknownDraft(shop,sku,card))return true;
                adoptExisting(shop,sku,card,session,null);
            }
            else if(correctRejected)observeSubmissionWithCorrection(shop,sku,session,null,intent);
            else observeSubmission(shop, sku, session, null);
            return true;
        } catch (Exception error) {
            fail(shop, sku, error, null);
            throw error;
        } finally {
            RUNNING.remove(key);
        }
    }

    private void observeSubmissionWithCorrection(Shop shop,Sku sku,CatalogSession session,BiConsumer<Status,String> listener)throws Exception {
        observeSubmissionWithCorrection(shop,sku,session,listener,null);
    }
    private void observeSubmissionWithCorrection(Shop shop,Sku sku,CatalogSession session,BiConsumer<Status,String> listener,RegistrationIntent intent)throws Exception {
        record Candidate(Sku row,String payload,RegistrationSourceConsentGuard.VerifiedRepairIntent proof) {}
        CatalogCorrectionLifecycle.advance(new CatalogCorrectionLifecycle.Adapter<Candidate>() {
            private boolean first=true;
            @Override public void observe()throws Exception {
                var current=first?sku:registrations.find(shop.getId(),sku.chrtId());first=false;
                observeSubmission(shop,current,session,listener);
            }
            @Override public Candidate rejectedCandidate() throws Exception {
                if(intent==null)return null;
                var row=registrations.find(shop.getId(),sku.chrtId());
                if(row==null||row.status()!=Status.ERROR||!row.hasFeed())return null;
                String stored=registrations.payload(shop.getId(),sku.chrtId());if(stored.isBlank()||session.scope()==null)return null;
                var payload=JsonParser.parseString(stored).getAsJsonObject();
                var proof=RegistrationRepairConsent.proof(shop,signingKey(shop,row),session.scope(),intent,session,payload,snapshots);
                return proof==null?null:new Candidate(row,stored,proof);
            }
            @Override public ZnackNationalCatalogService.FeedProgress readProgress(Candidate c)throws Exception {return observedProgress(shop,session,c.row());}
            @Override public ZnackNationalCatalogService.FeedProgress proveRejection(Candidate c,long id)throws Exception {
                if(session.scope()==null)return null;
                return session.catalog().verifiedRejectedProgress(session.token(),c.row().feedId(),c.row().gtin(),id,session.scope().inn(),
                        ()->{guard(shop,c.row());session.scope().assertCurrent(shop);});
            }
            @Override public CatalogAutomaticRepair.Proposal propose(Candidate c,ZnackNationalCatalogService.FeedProgress proof,long id)throws Exception {
                return CatalogAutomaticRepair.propose(session,proof,JsonParser.parseString(c.payload()).getAsJsonObject(),c.row().gtin(),id,true,shop,c.proof(),snapshots);
            }
            @Override public boolean checkpoint(Candidate c,CatalogAutomaticRepair.Proposal proposal)throws Exception {
                var draft=draftFromPayload(proposal.payload());guard(shop,c.row());LicenseRuntime.require(Permission.GTIN);
                return registrations.prepareAutomaticRejectedRetry(shop,c.row(),c.row().gtin(),c.row().feedId(),draft.tnved(),draft.categoryId(),draft.goodName(),session.scope(),proposal,c.proof());
            }
            @Override public void submit(Candidate c,CatalogAutomaticRepair.Proposal proposal)throws Exception {
                var corrected=proposal.payload();ZnackCardRegistrationWorkflow.notify(listener,Status.READY_TO_SUBMIT,"CATALOG_CORRECTING_OPTIONAL_FIELDS");
                String feed=submitWithJournal(shop,c.row(),session,corrected,ZnackNationalCatalogService.hasReviewedDraft(corrected),()->{guard(shop,c.row());update(shop,c.row(),Status.FEED_SUBMITTING,null,null,listener);},intent);
                registrations.updateProgress(shop.getId(),c.row().chrtId(),Status.FEED_SUBMITTED,feed,null,null,null);ZnackCardRegistrationWorkflow.notify(listener,Status.FEED_SUBMITTED,feed);
            }
        });
    }

    static boolean verifiedSubmission(Sku sku) {
        if (sku == null || !sku.hasGtin() || sku.goodId() == null || sku.goodId() <= 0) return false;
        return Set.of(Status.PUBLISHED, Status.READY_TO_SIGN, Status.SIGNING).contains(sku.status())
                || sku.status() == Status.PROCESSING
                && Set.of("CATALOG_CARD_DRAFT", "CATALOG_CARD_IN_MODERATION").contains(Objects.toString(sku.errorMessage(), ""));
    }

    private ZnackNationalCatalogService.FeedProgress observedProgress(Shop shop,CatalogSession session,Sku sku)throws Exception {
        ZnackNationalCatalogService.FeedProgress progress;
        if(sku.hasFeed())progress=session.catalog().submissionStatus(session.token(),sku.feedId(),sku.gtin());
        else{
            var card=session.catalog().observedCardStatus(session.token(),sku.gtin(),sku.goodId());
            if(card==null)throw new RegistrationValidationException("CATALOG_EXISTING_CARD_NOT_CONFIRMED");progress=card.progress();
        }
        reconcileObserved(shop,session,sku,progress,sku.gtin());
        return progress;
    }
    private void reconcileObserved(Shop shop,CatalogSession session,Sku sku,ZnackNationalCatalogService.FeedProgress progress,String gtin)throws Exception {
        if(sku.goodId()!=null&&progress.goodId()!=null&&!sku.goodId().equals(progress.goodId()))throw new RegistrationValidationException("CATALOG_IDENTITY_CHANGED");
        String stored=registrations.payload(shop.getId(),sku.chrtId());
        if(stored.isBlank())return;
        var body=JsonParser.parseString(stored).getAsJsonObject();
        boolean publishedMatched=session.scope()!=null&&progress.signed()&&progress.goodId()!=null&&session.catalog().verifyReviewedPublication(session.token(),gtin,progress.goodId(),body);
        if(session.scope()!=null&&progress.signed()&&!publishedMatched)throw new RegistrationValidationException("CATALOG_PUBLICATION_NOT_CONFIRMED");
        var owner=commandOwner(shop,session);
        var submit=new com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal.Command(com.vncode.app.integration.znack.registration.v3.RegistrationJob.Action.SUBMIT,commandResource(sku),stored);
        if(sku.hasFeed()&&CatalogFeedback.confirmedRejection(progress))commands.reject(owner,submit,sku.feedId());
        else if(session.scope()!=null&&progress.goodId()!=null&&(publishedMatched||session.catalog().matchesReviewedCard(session.token(),gtin,progress.goodId(),body))){
            if(sku.hasFeed())commands.reconcile(owner,submit,sku.feedId());else commands.observe(owner,submit);
        }
        var signature=registrations.signingCheckpoint(shop.getId(),sku.chrtId());
        if(progress.signed()&&signature!=null&&Objects.equals(signature.document().goodId(),progress.goodId())){
            var signed=new com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal.Command(com.vncode.app.integration.znack.registration.v3.RegistrationJob.Action.SIGN,commandResource(sku),signature.document().goodId()+"|"+signature.document().sha256());
            commands.reconcile(owner,signed,Long.toString(progress.goodId()));
        }
    }
    private void adoptExisting(Shop shop,Sku sku,ZnackNationalCatalogService.ObservedCard card,CatalogSession session,BiConsumer<Status,String> listener)throws Exception {
        if(card==null)throw new RegistrationValidationException("CATALOG_EXISTING_CARD_NOT_CONFIRMED");
        var progress=card.progress();reconcileObserved(shop,session,sku,progress,card.gtin());
        Status status=progress.failed()?Status.ERROR:progress.signed()?Status.PUBLISHED:progress.readyToSign()?Status.READY_TO_SIGN:Status.PROCESSING;
        String detail=progress.failed()?CatalogFeedback.rejection(progress):CatalogFeedback.stage(progress);
        registrations.adoptObservedCard(shop,sku,card,status,detail);notify(listener,status,detail);
    }
    private boolean preserveUnknownDraft(Shop shop,Sku sku,ZnackNationalCatalogService.ObservedCard card){
        if(sku.hasFeed()||card==null||!card.draftForReview())return false;
        String payload=registrations.payload(shop.getId(),sku.chrtId());
        if(payload.isBlank()||!ZnackNationalCatalogService.hasReviewedDraft(JsonParser.parseString(payload).getAsJsonObject()))return false;
        if(sku.status()!=Status.READY_TO_SUBMIT)registrations.updateProgress(shop.getId(),sku.chrtId(),Status.FEED_REVIEW_REQUIRED,null,sku.goodId(),"CATALOG_FEED_READBACK_REQUIRED",null);
        return true;
    }
    private void observeSubmission(Shop shop, Sku sku, CatalogSession session,
                                   BiConsumer<Status, String> listener) throws Exception {
        guard(shop, sku);
        var progress = observedProgress(shop,session,sku);
        guard(shop, sku);
        if (sku.goodId() != null && progress.goodId() != null && !sku.goodId().equals(progress.goodId())) {
            throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
        }
        Status status;
        String detail;
        if (progress.failed()) {
            status = Status.ERROR;
            detail = CatalogFeedback.rejection(progress);
        } else if (!progress.cardVerified()) {
            status = Status.FEED_SUBMITTED;
            detail = "CATALOG_REQUEST_ACCEPTED_CARD_PENDING";
        } else {
            status = progress.signed() ? Status.PUBLISHED : progress.readyToSign() ? Status.READY_TO_SIGN : Status.PROCESSING;
            detail = CatalogFeedback.stage(progress);
        }
        if (sku.status() == Status.PUBLISHED && status != Status.PUBLISHED) {
            registrations.reconcilePublication(shop, sku, status == Status.FEED_SUBMITTED ? Status.PROCESSING : status, detail);
        } else {
            registrations.updateProgress(shop.getId(), sku.chrtId(), status, sku.feedId(), progress.goodId(), detail, null);
        }
        notify(listener, status, detail);
    }

    private void requestUnsentContinuation(Shop shop,Sku sku){
        if(!namespace.equals(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize().toString()))return;
        try{
            if(registrations.checkpoint(shop.getId(),sku.chrtId()).status()!=Status.READY_TO_SUBMIT)return;
            new com.vncode.app.features.sync.SyncStreamStore().request(shop,"ZNACK");
            com.vncode.app.features.sync.SyncRuntime.requestIfRunning(shop,com.vncode.app.features.sync.SyncCoordinator.Stream.ZNACK);
        }catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(ZnackCardRegistrationWorkflow.class).warn("Registration continuation could not be requested: {}",com.vncode.app.features.sync.SyncFailure.of(unavailable,0).code());}
    }

    public boolean resume(Shop shop,Sku sku,BiConsumer<Status,String> listener){return resume(shop,sku,null,listener);}
    public boolean resume(Shop shop, Sku sku,Boolean automaticSigning, BiConsumer<Status, String> listener) {
        if (shop == null || sku == null || !sku.canResume()) return false;
        String stored = registrations.payload(shop.getId(), sku.chrtId());
        boolean reviewedDraftCheckpoint=!stored.isBlank()&&ZnackNationalCatalogService.hasReviewedDraft(JsonParser.parseString(stored).getAsJsonObject());
        if(reviewedDraftCheckpoint&&!sku.hasFeed()&&sku.status()!=Status.READY_TO_SUBMIT)
            throw new RegistrationValidationException("REGISTRATION_REVIEW_REQUIRED");
        if (stored.isBlank()) return false;
        JsonObject payload = JsonParser.parseString(stored).getAsJsonObject();
        if(Boolean.FALSE.equals(automaticSigning))RegistrationSigningConsent.set(shop,signingKey(shop,sku),sku.gtin(),false);
        return schedule(shop, sku, draftFromPayload(payload), RegistrationAction.RESUME, listener);
    }

    /** Legacy checkpoints may be inspected without manufacturing a historical owner binding. */
    public ZnackNationalCatalogService.FeedProgress readOnlyProgress(Shop shop,Sku sku)throws Exception {
        if(shop==null || shop.getMarketplace()!=Marketplace.WILDBERRIES || sku==null || !sku.hasFeed() || !sku.hasGtin())
            throw new IllegalArgumentException("A persisted feed is required.");
        var prior=registrations.scope(shop.getId(),sku.chrtId());
        if(strictScope && prior!=null)prior.assertCurrent(shop);
        var session=catalogFactory.open(shop);
        if(strictScope && prior!=null && !prior.equals(session.scope()))throw new ZnackRegistrationScope.ScopeChangedException();
        return observedProgress(shop,session,sku);
    }
    /** Reconcile a saved feed; signing is allowed only by an existing exact-card consent. */
    public boolean refreshStatus(Shop requestedShop,Sku sku)throws Exception {
        return refreshStatus(requestedShop,sku,true);
    }
    public boolean refreshStatusWithoutSigning(Shop requestedShop,Sku sku)throws Exception {
        return refreshStatus(requestedShop,sku,false);
    }
    private boolean refreshStatus(Shop requestedShop,Sku sku,boolean automaticSigning)throws Exception {
        Shop shop=freezeShop(requestedShop);String operation=operationKey(shop,sku);
        if(!RUNNING.add(operation))return false;
        try {
        guard(shop,sku);var session=catalogFactory.open(shop);guard(shop,sku);if(strictScope&&!registrations.scope(shop.getId(),sku.chrtId()).equals(session.scope()))throw new ZnackRegistrationScope.ScopeChangedException();
        var progress=observedProgress(shop,session,sku);guard(shop,sku);
        if(!sku.hasFeed()&&"CardDraft".equalsIgnoreCase(progress.status())){
            var card=session.catalog().observedCardStatus(session.token(),sku.gtin(),sku.goodId());
            if(preserveUnknownDraft(shop,sku,card))return false;
        }
        if(progress.goodId()!=null&&sku.goodId()!=null&&!progress.goodId().equals(sku.goodId()))throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
        Status status;
        var signature=registrations.signingCheckpoint(shop.getId(),sku.chrtId());
        if(progress.signed()){status=Status.PUBLISHED;registrations.finishSignature(shop.getId(),sku.chrtId(),true);}
        else if(progress.failed())status=Status.ERROR;
        else if(sku.status()==Status.SIGNING||signature!=null&&Set.of("SENDING","RECONCILE_REQUIRED").contains(signature.state()))status=Status.SIGNING;
        else status=!progress.cardVerified()?Status.FEED_SUBMITTED:progress.readyToSign()?Status.READY_TO_SIGN:Status.PROCESSING;
        String error=progress.failed()?CatalogFeedback.rejection(progress):CatalogFeedback.stage(progress);
        if(status==Status.FEED_SUBMITTED)error="CATALOG_REQUEST_ACCEPTED_CARD_PENDING";
        if(sku.status()==Status.PUBLISHED&&status!=Status.PUBLISHED){
            if(!registrations.reconcilePublication(shop,sku,status,error==null?"CATALOG_PUBLICATION_NOT_CONFIRMED":error))return false;
        }else registrations.updateProgress(shop.getId(),sku.chrtId(),status,sku.feedId(),progress.goodId(),error,null);
        if(automaticSigning&&status==Status.READY_TO_SIGN) {
            var nativeKey=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
            if(WbSourceConsentPolicy.permitsAutomatic(shop,nativeKey,sku.gtin())&&RegistrationSigningConsent.allowed(shop,nativeKey,sku.gtin())) {
                try{LicenseRuntime.require(Permission.GTIN);}catch(LicenseDeniedException paused){return false;}
                signApprovedOne(shop,registrations.find(shop.getId(),sku.chrtId()),session,null);
                return registrations.checkpointStatus(shop.getId(),sku.chrtId())==Status.PUBLISHED;
            }
        }
        return status==Status.PUBLISHED;
        }catch(Exception failure){
            if(CatalogFeedback.notFound(failure)&&sku.status()!=Status.PUBLISHED){
                guard(shop,sku);var state=registrations.checkpointStatus(shop.getId(),sku.chrtId());
                if(state!=Status.SIGNING)registrations.updateProgress(shop.getId(),sku.chrtId(),Status.PROCESSING,sku.feedId(),null,"CATALOG_SIGNING_DOCUMENT_PENDING",null);
                return false;
            }
            throw failure;
        }finally{RUNNING.remove(operation);}
    }

    /** A failed status read is not a rejected registration and never authorizes another POST. */
    void recordPollingFailure(Shop shop,Sku sku,Exception failure){
        guard(shop,sku);var current=registrations.find(shop.getId(),sku.chrtId());
        if(current==null||current.status()==Status.PUBLISHED)return;
        registrations.updateProgress(shop.getId(),sku.chrtId(),current.status(),null,null,ZnackErrorDetails.summary(failure),null);
    }

    static Draft draftFromPayload(JsonObject payload) {
        var categoryValue = payload.getAsJsonArray("categories").get(0);
        long categoryId = categoryValue.isJsonObject()
                ? categoryValue.getAsJsonObject().get("cat_id").getAsLong()
                : categoryValue.getAsLong();
        Map<Long, String> attributes = new LinkedHashMap<>();
        Map<Long, String> types = new LinkedHashMap<>();
        if (payload.has("good_attrs")) payload.getAsJsonArray("good_attrs").forEach(element -> {
            JsonObject attribute = element.getAsJsonObject();
            attributes.put(attribute.get("attr_id").getAsLong(), attribute.get("attr_value").getAsString());
            if (attribute.has("attr_value_type") && !attribute.get("attr_value_type").isJsonNull()) {
                types.put(attribute.get("attr_id").getAsLong(), attribute.get("attr_value_type").getAsString());
            }
        });
        String feedTnved = payload.get("tnved").getAsString();
        return new Draft(attributes.getOrDefault(13933L, feedTnved), feedTnved, categoryId,
                payload.get("good_name").getAsString(), payload.get("brand").getAsString(), attributes, types,false,
                payload.has("is_kit")&&payload.get("is_kit").getAsBoolean());
    }

    public static CatalogSession productionCatalog(Shop shop) throws Exception {
        ZnackModels.ShopContext context = new ZnackModels.ShopContext(shop.getId(), shop.getName());
        ZnackModels.Settings settings = new ZnackRepository(context).getSettings();
        ZnackRegistrationScope scope=ZnackRegistrationScope.fromSettings(settings);
        ZnackSignatureProvider signer = scope.verifyingSigner(shop,new CryptoProSignatureProvider(settings.cryptcpPath(),
                settings.signerCertificate(), Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds())));
        ZnackApiClient api = com.vncode.app.integration.znack.CatalogConnectionService.apiForRead(shop, settings);
        ZnackAuthService auth = new ZnackAuthService(api, signer);
        ZnackNationalCatalogService catalog = new ZnackNationalCatalogService(api, auth, signer, settings);
        String token=catalog.catalogToken();
        if(auth.authenticatedParticipantInn()!=null && !scope.inn().equals(auth.authenticatedParticipantInn()))throw new ZnackRegistrationScope.ScopeChangedException();
        scope.assertCurrent(shop);
        return new CatalogSession(token,catalog,scope);
    }
    com.vncode.app.integration.znack.registration.v3.RegistrationJob.Owner commandOwner(Shop shop,CatalogSession session){
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        var scope=session.scope()!=null?session.scope():ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());
        if(strictScope)scope.assertCurrent(shop);
        var url=okhttp3.HttpUrl.get(scope.environment());
        boolean production=Set.of(okhttp3.HttpUrl.get(ZnackModels.PRODUCTION_TRUE_API).host(),okhttp3.HttpUrl.get(ZnackModels.PRODUCTION_NATIONAL_CATALOG).host()).contains(url.host());
        boolean sandbox=Set.of("markirovka.sandbox.crptech.ru",okhttp3.HttpUrl.get(ZnackModels.SANDBOX_NATIONAL_CATALOG).host()).contains(url.host());
        if(!url.isHttps()||url.port()!=443||!url.username().isEmpty()||!url.password().isEmpty()||!(production||sandbox))throw new ZnackRegistrationScope.ScopeChangedException();
        return new com.vncode.app.integration.znack.registration.v3.RegistrationJob.Owner(shop.getId(),com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop),production?"production":"sandbox",
                com.vncode.app.integration.znack.registration.v3.RegistrationJob.hash(scope.inn()+"|"+scope.environment()+"|"+scope.fingerprint()));
    }
    private static String commandResource(Sku sku){return sku.nmId()+"/"+sku.chrtId();}
    private RegistrationAllocationReceipt allocateWithJournal(Shop shop,Sku sku,CatalogSession session,String token,Draft draft,String image,boolean fresh,RegistrationIntent intent)throws Exception {
        if(intent==null)throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");
        guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
        var product=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
        var blank=ZnackNationalCatalogService.buildPayload("",draft,"");
        var owner=commandOwner(shop,session);var action=com.vncode.app.integration.znack.registration.v3.RegistrationJob.Action.ALLOCATE_GTIN;
        com.vncode.app.integration.znack.registration.v3.RegistrationContractPolicy.require(action,owner.environment());
        var command=new com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal.Command(action,commandResource(sku),
                commands.allocationGeneration(shop.getId(),sku.nmId(),sku.chrtId())+"|quantity=1");
        var scope=session.scope()!=null?session.scope():ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());
        String pool=com.vncode.app.integration.znack.registration.v3.RegistrationJob.hash(scope.inn()+"|"+owner.environment());
        String gtin=commands.run(owner,pool,List.of(command),sent->{
            guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
            var claimed=new java.util.HashSet<>(registrations.claimedGtins());claimed.addAll(new com.vncode.app.integration.znack.registration.v3.RegistrationStore().claimedGtins(owner));
            var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
            Runnable boundary=()->{guard(shop,sku);LicenseRuntime.require(Permission.GTIN);try{
                var snapshot=snapshots.read(shop,product);var schema=session.catalog().registrationAttributes(draft.categoryId(),token);
                var sourceAction=com.vncode.app.config.SqliteWriteTransaction.run(c->RegistrationSourceConsentGuard.action(c,intent,product));
                var replacement=sourceAction==RegistrationSourceConsent.Action.REPLACE?CatalogReplacementEvidence.verify(shop,product,scope,intent,session):null;
                com.vncode.app.config.SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.requirePosition(c,intent,product);RegistrationSourceConsentGuard.claimAllocation(c,shop,intent.runId(),snapshot,schema,blank,owner,command,replacement);return null;});
            }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("ZNACK_SOURCE_REVIEW_READ_FAILED",e);}sent.run();dispatched.set(true);};
            try{return List.of(fresh?session.catalog().generateFreshOne(token,claimed,boundary):session.catalog().generateOne(token,claimed,boundary));}
            catch(Exception failure){
                if(!dispatched.get()||CatalogFeedback.rateLimitRejectedRequest(failure))try{com.vncode.app.config.SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.releaseKnownUnsent(c,intent,product,true);return null;});}catch(Exception release){failure.addSuppressed(release);}
                // Only this live invocation proves that the pool/quota read failed before
                // an allocation. Unknown journal entries and sent requests stay protected.
                if(!dispatched.get())try{
                    guard(shop,sku);var checkpoint=registrations.checkpoint(shop.getId(),sku.chrtId());
                    if(checkpoint.status()==Status.ALLOCATING_GTIN&&!checkpoint.hasGtin()&&!checkpoint.hasFeed())
                        registrations.updateProgress(shop.getId(),sku.chrtId(),Status.PREFLIGHT_ERROR,null,null,ZnackErrorDetails.summary(failure),null);
                }catch(RuntimeException changed){failure.addSuppressed(changed);}
                throw failure;
            }
        }).getFirst();return new RegistrationAllocationReceipt(gtin,owner,command,intent);
    }
    private String submitWithJournal(Shop shop,Sku sku,CatalogSession session,JsonObject payload,boolean reviewed,Runnable beforeSend)throws Exception {
        return submitWithJournal(shop,sku,session,payload,reviewed,beforeSend,null);
    }
    /** Recover only an ACK of this exact reviewed allocation; UNKNOWN stays untouched. */
    boolean recoverAcknowledgedForBatch(Shop shop,Sku sku,Draft draft,RegistrationIntent intent)throws Exception{
        if(shop==null||sku==null||draft==null||intent==null||!Set.of(Status.ALLOCATING_GTIN,Status.GTIN_REVIEW_REQUIRED).contains(sku.status())||sku.hasGtin()||sku.hasFeed())return false;
        guard(shop,sku);var session=catalogFactory.open(shop);guard(shop,sku);var scope=session.scope();if(scope==null)throw new ZnackRegistrationScope.ScopeChangedException();scope.assertCurrent(shop);
        var owner=commandOwner(shop,session);var command=new com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal.Command(com.vncode.app.integration.znack.registration.v3.RegistrationJob.Action.ALLOCATE_GTIN,commandResource(sku),commands.allocationGeneration(shop.getId(),sku.nmId(),sku.chrtId())+"|quantity=1");
        var key=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));var blank=ZnackNationalCatalogService.buildPayload("",draft,"");
        var acknowledged=com.vncode.app.config.SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.requireRecoverableDraft(c,shop,intent,key,blank);return com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal.acknowledged(c,owner,command);});
        if(acknowledged.isEmpty())return false;String gtin=acknowledged.get();var payload=ZnackNationalCatalogService.buildPayload(gtin,draft,"");
        registrations.saveReadyToSubmitWithSourceReceipt(shop,sku,draft.tnved(),draft.categoryId(),draft.goodName(),payload.toString(),scope,new RegistrationAllocationReceipt(gtin,owner,command,intent));return true;
    }
    boolean recoverAcknowledgedFeedForBatch(Shop shop,Sku sku,RegistrationIntent intent)throws Exception{
        if(shop==null||sku==null||intent==null||!Set.of(Status.FEED_SUBMITTING,Status.FEED_REVIEW_REQUIRED).contains(sku.status())||sku.hasFeed())return false;
        guard(shop,sku);var session=catalogFactory.open(shop);guard(shop,sku);var scope=session.scope();
        if(scope==null)throw new ZnackRegistrationScope.ScopeChangedException();scope.assertCurrent(shop);
        return registrations.recoverAcknowledgedFeed(shop,sku,scope,intent,commandOwner(shop,session));
    }
    private String submitWithJournal(Shop shop,Sku sku,CatalogSession session,JsonObject payload,boolean reviewed,Runnable beforeSend,RegistrationIntent intent)throws Exception {
        if(intent==null)throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");
        guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
        var owner=commandOwner(shop,session);var action=com.vncode.app.integration.znack.registration.v3.RegistrationJob.Action.SUBMIT;
        com.vncode.app.integration.znack.registration.v3.RegistrationContractPolicy.require(action,owner.environment());
        var attempt=RegistrationSubmissionEngine.prepare(shop,session,payload,reviewed);
        // Test-only sessions can omit scope; production sessions always bind the verified owner.
        var key=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
        var cross=session.scope()==null?null:CatalogSubmissionGuard.prepare(shop,session.scope(),key,payload,
                (feeds,gtin,id)->session.catalog().verifiedTerminalFeeds(session.token(),feeds,gtin,id,session.scope().inn(),()->{guard(shop,sku);LicenseRuntime.require(Permission.GTIN);}));
        var command=new com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal.Command(action,commandResource(sku),attempt.checkpointPayload());
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean();String feed;
        try{feed=commands.run(owner,List.of(command),sent->{
            Runnable boundary=()->{guard(shop,sku);LicenseRuntime.require(Permission.GTIN);try{
                RegistrationSubmissionApproval.claim(shop,key,session,intent,payload,()->snapshots.read(shop,key));
            }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("ZNACK_SOURCE_REVIEW_READ_FAILED",e);}if(cross!=null)cross.reserve();beforeSend.run();guard(shop,sku);LicenseRuntime.require(Permission.GTIN);sent.run();dispatched.set(true);};
            return List.of(attempt.submit(boundary));
        }).getFirst();}catch(Exception failure){if(!dispatched.get()||CatalogFeedback.rateLimitRejectedRequest(failure))try{com.vncode.app.config.SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.releaseKnownUnsent(c,intent,key,false);return null;});}catch(Exception release){failure.addSuppressed(release);}throw failure;}
        com.vncode.app.config.SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.acceptedSubmission(c,intent,key,payload);return null;});return feed;
    }
    private void signWithJournal(Shop shop,CatalogSession session,List<Sku> skus,List<ZnackNationalCatalogService.SigningDocument> documents,ZnackNationalCatalogService.BeforeSigningSend beforeSend)throws Exception {
        for(var sku:skus)guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
        var owner=commandOwner(shop,session);var action=com.vncode.app.integration.znack.registration.v3.RegistrationJob.Action.SIGN;
        com.vncode.app.integration.znack.registration.v3.RegistrationContractPolicy.require(action,owner.environment());
        var batch=new java.util.ArrayList<com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal.Command>();
        for(int i=0;i<skus.size();i++)batch.add(new com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal.Command(action,commandResource(skus.get(i)),documents.get(i).goodId()+"|"+documents.get(i).sha256()));
        commands.run(owner,batch,sent->session.catalog().sendReviewedSigningBatch(session.token(),documents,()->{beforeSend.check();sent.run();}).stream().map(Object::toString).toList());
    }
    private static Shop freezeShop(Shop shop){return shop==null?null:new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());}

    private static Sku freshCandidate(Sku sku) {
        return new Sku(sku.nmId(),sku.chrtId(),sku.subjectId(),sku.vendorCode(),sku.subjectName(),sku.brand(),sku.title(),sku.color(),sku.size(),sku.barcodes(),sku.imageUrl(),sku.needKiz(),null,null,null,Status.CHECKING,null,false,sku.wbSize(),sku.registrationRevision());
    }
    private void execute(Shop shop,Sku sku,Draft draft,boolean requestFresh,boolean signingOnly,boolean recordConsent,
                         BiConsumer<Status,String> listener)throws Exception {
        execute(shop,sku,draft,requestFresh,signingOnly,recordConsent,listener,false);
    }
    private void execute(Shop shop, Sku sku, Draft draft, boolean requestFresh,boolean signingOnly,boolean recordConsent,
                         BiConsumer<Status, String> listener,boolean submissionOnly) throws Exception {
        execute(shop,sku,draft,requestFresh,signingOnly,recordConsent,listener,submissionOnly,null);
    }
    private void execute(Shop shop,Sku sku,Draft draft,boolean requestFresh,boolean signingOnly,boolean recordConsent,
                         BiConsumer<Status,String> listener,boolean submissionOnly,RegistrationExistingCards.LookupContext lookup)throws Exception {
        execute(shop,sku,draft,requestFresh,signingOnly,recordConsent,listener,submissionOnly,lookup,null);
    }
    private void execute(Shop shop,Sku sku,Draft draft,boolean requestFresh,boolean signingOnly,boolean recordConsent,
                         BiConsumer<Status,String> listener,boolean submissionOnly,RegistrationExistingCards.LookupContext lookup,CatalogSession prepared)throws Exception {
        execute(shop,sku,draft,requestFresh,signingOnly,recordConsent,listener,submissionOnly,lookup,prepared,null);
    }
    private void execute(Shop shop,Sku sku,Draft draft,boolean requestFresh,boolean signingOnly,boolean recordConsent,
                         BiConsumer<Status,String> listener,boolean submissionOnly,RegistrationExistingCards.LookupContext lookup,CatalogSession prepared,RegistrationIntent intent)throws Exception {
        if(!sku.hasFeed())LicenseRuntime.require(Permission.GTIN);
        guard(shop,sku);
        CatalogSession session=prepared==null?catalogFactory.open(shop):prepared;
        guard(shop,sku);
        if(strictScope && !registrations.scope(shop.getId(),sku.chrtId()).equals(session.scope()))throw new ZnackRegistrationScope.ScopeChangedException();
        if(prepared!=null){if(prepared.scope()!=null)prepared.scope().assertCurrent(shop);prepared.catalog().assertRegistrationReadScope();}
        ZnackNationalCatalogService catalog=session.catalog();
        String token=session.token();
        if(signingOnly){signApprovedOne(shop,sku,session,listener);return;}
        if(intent!=null){
            var key=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
            if(com.vncode.app.config.SqliteWriteTransaction.run(c->RegistrationSourceConsentGuard.action(c,intent,key))==RegistrationSourceConsent.Action.REPLACE)requestFresh=true;
        }

        String gtin = sku.gtin();
        String feedId = sku.feedId();
        String stored = registrations.payload(shop.getId(), sku.chrtId());
        boolean reviewedDraftCheckpoint=!stored.isBlank()&&ZnackNationalCatalogService.hasReviewedDraft(JsonParser.parseString(stored).getAsJsonObject());
        if(reviewedDraftCheckpoint&&!sku.hasFeed()&&sku.status()!=Status.READY_TO_SUBMIT)
            throw new RegistrationValidationException("REGISTRATION_REVIEW_REQUIRED");
        boolean rebuildPayload = gtin == null || gtin.isBlank() || (sku.status() == Status.ERROR&&!CatalogFeedback.interrupted(sku.errorMessage()))
                || sku.status() == Status.GTIN_GENERATED || feedId == null || feedId.isBlank() || stored.isBlank();
        if(sku.status()==Status.READY_TO_SUBMIT&&!stored.isBlank())rebuildPayload=false;
        // Existing accepted feeds need only readback; do not impose new-write permissions on them.
        try(var countryRead=rebuildPayload||feedId==null||feedId.isBlank()?catalog.registrationCountryReadScope():null){
        if (rebuildPayload) {
            draft=draft.withProductCategory(sku.subjectName());
            draft = withSchemaTypes(draft, catalog.requiredAttributes(draft.categoryId(), token), sku.wbSize());
            catalog.validateDocuments(token,draft);
            catalog.validateCurrentRegistrationDraft(token,draft);
        }
        if(sku.canReviewExistingDraft()){
            var context=lookup==null?existingCardsOperation():lookup;
            try{
                var existing=context.lookup(shop,RegistrationExistingCards.Variant.wb(shop,sku,draft),session)
                        .orElseThrow(()->new RegistrationValidationException("CATALOG_EXISTING_CARD_NOT_CONFIRMED"));
                if(!Objects.equals(sku.goodId(),existing.goodId())||!com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(sku.gtin(),existing.gtin()))throw new RegistrationValidationException("CATALOG_IDENTITY_CHANGED");
                if(existing.draftForReview())submitReviewedExistingDraft(shop,sku,draft,existing,session,listener,intent);
                else adoptExisting(shop,sku,existing,session,listener);
                return;
            }finally{if(lookup==null)context.close();}
        }
        String imageUrl = retryImageUrl(sku.imageUrl(), sku.errorMessage());
        JsonObject payload;
        if (gtin == null || gtin.isBlank()) {
            if(!imageUrl.isBlank()&&intent!=null){
                var product=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
                var source=snapshots.read(shop,product);var schema=catalog.registrationAttributes(draft.categoryId(),token);
                String photo=imageUrl;var reviewedPayload=ZnackNationalCatalogService.buildPayload("",draft,"");boolean approved=com.vncode.app.config.SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.requirePosition(c,intent,product);return RegistrationSourceConsentGuard.imageReviewed(c,shop,intent.runId(),source,schema,reviewedPayload,photo);});
                if(!approved){imageUrl="";notify(listener,Status.CHECKING,"ZNACK_OPTIONAL_IMAGE_NOT_REVIEWED_OMITTED");}
            }
            update(shop, sku, Status.CHECKING, null, null, listener);
            if(!requestFresh){
                var context=lookup==null?existingCardsOperation():lookup;
                try{
                    var existing=context.lookup(shop,RegistrationExistingCards.Variant.wb(shop,sku,draft),session);
                    guard(shop,sku);
                    if(existing.isPresent()){
                        if(existing.get().draftForReview())submitReviewedExistingDraft(shop,sku,draft,existing.get(),session,listener,intent);
                        else adoptExisting(shop,sku,existing.get(),session,listener);
                        return;
                    }
                }finally{if(lookup==null)context.close();}
            }
            // Category is checked here; allocation reads the current quota/drafts itself under
            // its lock. Do not fetch that same draft pool once more in a full preflight.
            catalog.preflightExisting(draft.tnved(),token);
            // Keep selection and the durable local checkpoint atomic across the two workflow
            // workers. Otherwise both can observe the same reusable catalog draft GTIN.
            synchronized (GTIN_CHECKPOINT_LOCK) {
                LicenseRuntime.require(Permission.GTIN);
                update(shop,sku,Status.ALLOCATING_GTIN,null,null,listener);
                LicenseRuntime.require(Permission.GTIN);
                guard(shop,sku);
                var receipt = allocateWithJournal(shop,sku,session,token,draft,imageUrl,requestFresh,intent);gtin=receipt.gtin();
                payload = ZnackNationalCatalogService.buildPayload(gtin, draft, imageUrl);
                registrations.saveReadyToSubmitWithSourceReceipt(shop,sku,draft.tnved(),draft.categoryId(),draft.goodName(),payload.toString(),session.scope(),receipt);
            }
            notify(listener, Status.GTIN_GENERATED, gtin);
        } else {
            if (rebuildPayload) {
                payload = ZnackNationalCatalogService.buildPayload(gtin, draft, imageUrl);
                if(sku.goodId()!=null&&sku.goodId()>0)payload.addProperty("good_id",sku.goodId());
                // A pending feed retains its original request until the server confirms rejection.
                if(feedId==null||feedId.isBlank()){
                    savePreparedCheckpoint(shop,sku,session,gtin,draft,payload,recordConsent);
                    notify(listener, Status.GTIN_GENERATED, gtin);
                }
            } else {
                payload = JsonParser.parseString(stored).getAsJsonObject();
            }
        }

        var nativeKey=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
        if(recordConsent&&!reviewedDraftCheckpoint){RegistrationSigningConsent.set(shop,nativeKey,gtin,draft.autoSign());RegistrationMarketplaceConsent.set(shop,nativeKey,gtin,true);}
        if (feedId == null || feedId.isBlank()) {
            if(sku.goodId()!=null&&sku.goodId()>0){
                if(payload.has("good_id")&&payload.get("good_id").getAsLong()!=sku.goodId())throw new RegistrationValidationException("CATALOG_IDENTITY_CHANGED");
                if(!payload.has("good_id")){payload.addProperty("good_id",sku.goodId());savePreparedCheckpoint(shop,sku,session,gtin,draft,payload,recordConsent);}
            }
            LicenseRuntime.require(Permission.GTIN);
            update(shop,sku,Status.READY_TO_SUBMIT,null,null,listener);
            LicenseRuntime.require(Permission.GTIN);
            guard(shop,sku);
            Runnable sending=()->{guard(shop,sku);LicenseRuntime.require(Permission.GTIN);update(shop,sku,Status.FEED_SUBMITTING,null,null,listener);};
            feedId = submitWithJournal(shop,sku,session,payload,reviewedDraftCheckpoint,sending,intent);
            registrations.updateProgress(shop.getId(), sku.chrtId(), Status.FEED_SUBMITTED, feedId,
                    null, null, null);
            notify(listener, Status.FEED_SUBMITTED, feedId);
            new com.vncode.app.features.sync.SyncStreamStore().request(shop,"ZNACK");
        }

        if(submissionOnly){
            observeSubmissionWithCorrection(shop, registrations.find(shop.getId(), sku.chrtId()), session, listener,intent);
            return;
        }

        Long goodId = sku.goodId();
        boolean published = sku.status() == Status.PUBLISHED;
        for (int attempt = 0; !published && attempt < POLL_ATTEMPTS; attempt++) {
            guard(shop,sku);
            ZnackNationalCatalogService.FeedProgress progress = observedProgress(shop,session,registrations.find(shop.getId(),sku.chrtId()));
            if(goodId!=null&&progress.goodId()!=null&&!goodId.equals(progress.goodId()))throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
            if (progress.goodId() != null) goodId = progress.goodId();
            if (progress.failed()) {
                registrations.updateProgress(shop.getId(),sku.chrtId(),Status.ERROR,feedId,goodId,CatalogFeedback.rejection(progress),null);
                if(CatalogFeedback.confirmedRejection(progress)){
                    var failed=registrations.find(shop.getId(),sku.chrtId());
                    observeSubmissionWithCorrection(shop,failed,session,listener,intent);
                    var current=registrations.find(shop.getId(),sku.chrtId());
                    if(current!=null&&current.hasFeed()&&!Objects.equals(feedId,current.feedId())){
                        feedId=current.feedId();payload=JsonParser.parseString(registrations.payload(shop.getId(),sku.chrtId())).getAsJsonObject();
                        attempt=-1;continue;
                    }
                }
                throw new IllegalStateException(progress.errorMessage().isBlank()
                        ? "National Catalog rejected feed " + feedId : progress.errorMessage());
            }
            if (progress.signed()) {
                registrations.finishSignature(shop.getId(),sku.chrtId(),true);
                published = true;
                update(shop, sku, Status.PUBLISHED, feedId, goodId, listener);
                break;
            }
            if (progress.readyToSign()) {
                update(shop, sku, Status.READY_TO_SIGN, feedId, goodId, listener);
                if(WbSourceConsentPolicy.permitsAutomatic(shop,nativeKey,gtin)&&RegistrationSigningConsent.allowed(shop,nativeKey,gtin))signApprovedOne(shop,registrations.find(shop.getId(),sku.chrtId()),session,listener);
                return;
            }
            update(shop, sku, Status.PROCESSING, feedId, goodId, listener);
            String stage=CatalogFeedback.stage(progress);
            if(stage!=null){registrations.updateProgress(shop.getId(),sku.chrtId(),Status.PROCESSING,feedId,goodId,stage,null);notify(listener,Status.PROCESSING,stage);}
            break;
        }

        if (!published) {
            if(registrations.checkpointStatus(shop.getId(),sku.chrtId())==Status.SIGNING)return;
            notify(listener, Status.PROCESSING, "The card is still being moderated. Use Refresh later to continue.");
            return;
        }

        // Marketplace updates require their own reviewed, verified job. The previous active card remains usable.
        notify(listener, Status.PUBLISHED, gtin);
        }
    }
    private void savePreparedCheckpoint(Shop shop,Sku sku,CatalogSession session,String gtin,Draft draft,JsonObject payload,boolean sellerAction){
        // Automatic repair must not acquire seller permission from an ordinary submit.
        // Until an explicit persisted repair-scope intent is attached, save the native checkpoint only.
        registrations.saveReadyToSubmitGenerated(shop.getId(),sku,gtin,draft.tnved(),draft.categoryId(),draft.goodName(),payload.toString());
    }
    /** The caller has just completed seller review and fresh exact-card discovery. */
    private void submitReviewedExistingDraft(Shop shop,Sku sku,Draft draft,ZnackNationalCatalogService.ObservedCard card,CatalogSession session,
            BiConsumer<Status,String> listener,RegistrationIntent intent)throws Exception{
        if(!card.draftForReview())throw new RegistrationValidationException("SOURCE_CARD_ACTION_CHANGED");
        // A CREATE approval does not authorize editing an unexpected existing
        // identity. Require the seller-reviewed GTIN and good_id before adopting it.
        var key=signingKey(shop,sku);
        if(intent==null||com.vncode.app.config.SqliteWriteTransaction.run(c->RegistrationSourceConsentGuard.action(c,intent,key))!=RegistrationSourceConsent.Action.EXISTING)
            throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");
        var payload=ZnackNationalCatalogService.buildPayload(card.gtin(),draft.withAutoSign(false),"");payload.addProperty("good_id",card.goodId());
        var snapshot=snapshots.read(shop,key);
        var schema=session.catalog().registrationAttributes(draft.categoryId(),session.token());
        com.vncode.app.config.SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.require(c,shop,intent.runId(),snapshot,schema,payload,RegistrationSourceConsent.Action.EXISTING,false);return null;});
        guard(shop,sku);adoptExisting(shop,sku,card,session,listener);
        var current=registrations.find(shop.getId(),sku.chrtId());
        var reviewedPayload=ZnackNationalCatalogService.reviewedDraftPayload(shop,payload);
        savePreparedCheckpoint(shop,current,session,card.gtin(),draft,reviewedPayload,true);
        RegistrationSigningConsent.set(shop,key,card.gtin(),false);RegistrationMarketplaceConsent.set(shop,key,card.gtin(),false);
        notify(listener,Status.READY_TO_SUBMIT,"");
        String feed=submitWithJournal(shop,sku,session,reviewedPayload,true,()->{guard(shop,sku);LicenseRuntime.require(Permission.GTIN);update(shop,sku,Status.FEED_SUBMITTING,null,null,listener);},intent);
        registrations.updateProgress(shop.getId(),sku.chrtId(),Status.FEED_SUBMITTED,feed,card.goodId(),null,null);
        notify(listener,Status.FEED_SUBMITTED,feed);new com.vncode.app.features.sync.SyncStreamStore().request(shop,"ZNACK");
        observeSubmission(shop,registrations.find(shop.getId(),sku.chrtId()),session,listener);
    }

    private record SigningMember(Sku sku,ZnackNationalCatalogService.SigningDocument document,boolean automatic){}
    private static com.vncode.app.features.products.ProductKey signingKey(Shop shop,Sku sku){
        return new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
    }
    private boolean claimSigning(Shop shop,Sku sku,CatalogSession session,ZnackNationalCatalogService.SigningDocument document)throws Exception {
        if(session.scope()==null)return registrations.claimSignatureSend(shop.getId(),sku.chrtId(),document.sha256());
        var guard=CatalogSubmissionGuard.prepareSigning(shop,session.scope(),signingKey(shop,sku),sku.gtin(),document.goodId(),
                (feeds,gtin,id)->session.catalog().verifiedTerminalFeeds(session.token(),feeds,gtin,id,session.scope().inn(),()->{guard(shop,sku);LicenseRuntime.require(Permission.GTIN);}));
        return guard.reserveSigning(document.sha256());
    }
    static boolean blockedCrossFeed(Exception failure){
        if(failure instanceof com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException e&&e.statusCode()==404)return true;
        return failure instanceof IllegalStateException&&Set.of("CATALOG_FEED_READBACK_REQUIRED","CATALOG_OWNER_UNCONFIRMED").contains(failure.getMessage());
    }
    private SigningMember prepareSigningMember(Shop shop,Sku sku,CatalogSession session,RegistrationSigningReview.Card approved,BiConsumer<Status,String> listener)throws Exception {
        guard(shop,sku);var progress=observedProgress(shop,session,sku);
        if(sku.goodId()!=null&&progress.goodId()!=null&&!sku.goodId().equals(progress.goodId()))throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
        if(progress.signed()){registrations.finishSignature(shop.getId(),sku.chrtId(),true);update(shop,sku,Status.PUBLISHED,sku.feedId(),progress.goodId(),listener);return null;}
        if(progress.failed()||!progress.readyToSign()){
            String detail=progress.failed()?CatalogFeedback.rejection(progress):CatalogFeedback.stage(progress);
            registrations.updateProgress(shop.getId(),sku.chrtId(),progress.failed()?Status.ERROR:Status.PROCESSING,sku.feedId(),progress.goodId(),detail,null);
            notify(listener,progress.failed()?Status.ERROR:Status.PROCESSING,detail);return null;
        }
        var checkpoint=registrations.signingCheckpoint(shop.getId(),sku.chrtId());
        if(checkpoint!=null&&!"PREPARED".equals(checkpoint.state())){
            update(shop,sku,Status.SIGNING,sku.feedId(),progress.goodId(),listener);notify(listener,Status.SIGNING,"CATALOG_PUBLICATION_PENDING");return null;
        }
        update(shop,sku,Status.READY_TO_SIGN,sku.feedId(),progress.goodId(),listener);
        var document=session.catalog().reviewSigning(session.token(),sku.gtin());
        if(progress.goodId()==null||progress.goodId()!=document.goodId())throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
        if(approved!=null){
            if(!approved.document().sha256().equals(document.sha256())||approved.document().goodId()!=document.goodId())throw new RegistrationValidationException("SIGNING_DOCUMENT_CHANGED");
            RegistrationSigningConsent.set(shop,signingKey(shop,sku),sku.gtin(),true,approved.document());
        }else authorizeAutomaticDocument(shop,sku,session,document);
        if(!RegistrationSigningConsent.allowed(shop,signingKey(shop,sku),sku.gtin(),document))throw new RegistrationValidationException("CATALOG_SIGNING_CONSENT_REQUIRED");
        registrations.prepareSignature(shop.getId(),sku.chrtId(),sku.gtin(),document);
        return new SigningMember(sku,document,approved==null);
    }
    private boolean sendSigningGroup(Shop shop,CatalogSession session,List<SigningMember> selected,BiConsumer<Status,String> listener)throws Exception {
        return RegistrationSigningBatchEngine.send(selected,new RegistrationSigningBatchEngine.Adapter<SigningMember>() {
            @Override public boolean claim(SigningMember member)throws Exception {
                var sku=member.sku();guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
                var current=registrations.find(shop.getId(),sku.chrtId());
                if(current==null||current.status()!=Status.READY_TO_SIGN||!Objects.equals(current.gtin(),sku.gtin())||!Objects.equals(current.feedId(),sku.feedId()))return false;
                if(member.automatic()&&!WbSourceConsentPolicy.permitsAutomatic(shop,signingKey(shop,sku),sku.gtin()))return false;
                if(!RegistrationSigningConsent.allowed(shop,signingKey(shop,sku),sku.gtin(),member.document()))return false;
                if(registrations.signingCheckpoint(shop.getId(),sku.chrtId())==null)registrations.prepareSignature(shop.getId(),sku.chrtId(),sku.gtin(),member.document());
                boolean admitted;
                try{admitted=claimSigning(shop,sku,session,member.document());}
                catch(Exception blocked){if(!blockedCrossFeed(blocked))throw blocked;ZnackCardRegistrationWorkflow.notify(listener,Status.READY_TO_SIGN,"CATALOG_FEED_READBACK_REQUIRED");return false;}
                if(admitted)update(shop,sku,Status.SIGNING,sku.feedId(),member.document().goodId(),listener);
                return admitted;
            }
            @Override public void release(SigningMember member){releaseSigningMember(shop,member,null);}
            @Override public void validateRemote(SigningMember member)throws Exception {
                var sku=member.sku();guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
                if(!sameSigningIdentity(shop,member))throw new IllegalStateException("SIGNING_DOCUMENT_CHANGED");
                if(member.automatic()&&!WbSourceConsentPolicy.permitsAutomatic(shop,signingKey(shop,sku),sku.gtin()))throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");
                if(!RegistrationSigningConsent.allowed(shop,signingKey(shop,sku),sku.gtin(),member.document()))throw new RegistrationValidationException("CATALOG_SIGNING_CONSENT_REQUIRED");
                var progress=observedProgress(shop,session,sku);
                if(progress.signed()){registrations.finishSignature(shop.getId(),sku.chrtId(),true);update(shop,sku,Status.PUBLISHED,sku.feedId(),progress.goodId(),listener);throw new RegistrationSigningBatchEngine.SkipMember();}
                if(progress.failed()||!progress.readyToSign()){
                    var state=progress.failed()?Status.ERROR:Status.PROCESSING;String detail=progress.failed()?CatalogFeedback.rejection(progress):CatalogFeedback.stage(progress);
                    registrations.updateProgress(shop.getId(),sku.chrtId(),state,sku.feedId(),progress.goodId(),detail,null);ZnackCardRegistrationWorkflow.notify(listener,state,detail);throw new RegistrationSigningBatchEngine.SkipMember();
                }
                if(progress.goodId()==null||progress.goodId()!=member.document().goodId())throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
                var fresh=session.catalog().reviewSigning(session.token(),sku.gtin());
                if(fresh.goodId()!=member.document().goodId()||!fresh.sha256().equals(member.document().sha256()))throw new IllegalStateException("SIGNING_DOCUMENT_CHANGED");
                guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
            }
            @Override public void revalidateLocal(SigningMember member){
                var sku=member.sku();guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
                if(!sameSigningIdentity(shop,member))throw new RegistrationValidationException("SIGNING_DOCUMENT_CHANGED");
                if(member.automatic()&&!WbSourceConsentPolicy.permitsAutomatic(shop,signingKey(shop,sku),sku.gtin()))throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");
                if(!RegistrationSigningConsent.allowed(shop,signingKey(shop,sku),sku.gtin(),member.document()))throw new RegistrationValidationException("CATALOG_SIGNING_CONSENT_REQUIRED");
            }
            @Override public void send(List<SigningMember> members,RegistrationSigningBatchEngine.CheckedAction beforeSend)throws Exception {
                signWithJournal(shop,session,members.stream().map(SigningMember::sku).toList(),members.stream().map(SigningMember::document).toList(),beforeSend::run);
            }
            @Override public void uncertain(SigningMember member){markSigningUncertain(shop,member,listener);}
            @Override public void readback(SigningMember member)throws Exception {
                var sku=member.sku();guard(shop,sku);var after=observedProgress(shop,session,sku);
                if(after.goodId()!=null&&after.goodId()!=member.document().goodId())throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
                if(after.signed()){registrations.finishSignature(shop.getId(),sku.chrtId(),true);update(shop,sku,Status.PUBLISHED,sku.feedId(),after.goodId(),listener);}
                else if(after.failed()){String detail=CatalogFeedback.rejection(after);registrations.updateProgress(shop.getId(),sku.chrtId(),Status.ERROR,sku.feedId(),after.goodId(),detail,null);ZnackCardRegistrationWorkflow.notify(listener,Status.ERROR,detail);}
            }
            @Override public void failed(SigningMember member,Exception failure){fail(shop,member.sku(),failure,listener);}
        });
    }
    private boolean sameSigningIdentity(Shop shop,SigningMember member){
        var sku=member.sku();var current=registrations.find(shop.getId(),sku.chrtId());var checkpoint=registrations.signingCheckpoint(shop.getId(),sku.chrtId());
        return current!=null&&current.status()==Status.SIGNING&&current.nmId()==sku.nmId()&&Objects.equals(current.gtin(),sku.gtin())&&Objects.equals(current.feedId(),sku.feedId())
                &&current.goodId()!=null&&current.goodId()==member.document().goodId()
                &&checkpoint!=null&&"SENDING".equals(checkpoint.state())&&checkpoint.gtin().equals(sku.gtin())&&checkpoint.document().goodId()==member.document().goodId()&&checkpoint.document().sha256().equals(member.document().sha256());
    }
    private void releaseSigningMember(Shop shop,SigningMember member,String detail){
        var sku=member.sku();registrations.releaseUnsentSignature(shop.getId(),sku.chrtId(),member.document().sha256());
        if(!Set.of(Status.PUBLISHED,Status.ERROR,Status.PROCESSING).contains(registrations.checkpointStatus(shop.getId(),sku.chrtId())))registrations.updateProgress(shop.getId(),sku.chrtId(),Status.READY_TO_SIGN,sku.feedId(),member.document().goodId(),detail,null);
    }
    private void markSigningUncertain(Shop shop,SigningMember member,BiConsumer<Status,String> listener){
        var sku=member.sku();registrations.finishSignature(shop.getId(),sku.chrtId(),false);
        registrations.updateProgress(shop.getId(),sku.chrtId(),Status.SIGNING,sku.feedId(),member.document().goodId(),"CATALOG_PUBLICATION_PENDING",null);notify(listener,Status.SIGNING,"CATALOG_PUBLICATION_PENDING");
    }

    private void signApprovedOne(Shop shop,Sku sku,CatalogSession session,BiConsumer<Status,String> listener)throws Exception {
        guard(shop,sku);
        var progress=observedProgress(shop,session,sku);
        if(sku.goodId()!=null&&progress.goodId()!=null&&!sku.goodId().equals(progress.goodId()))throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
        if(progress.signed()) {
            registrations.finishSignature(shop.getId(),sku.chrtId(),true);
            update(shop,sku,Status.PUBLISHED,sku.feedId(),progress.goodId(),listener);
            return;
        }
        if(!progress.readyToSign() || progress.failed()) {
            update(shop,sku,progress.failed()?Status.ERROR:Status.PROCESSING,sku.feedId(),progress.goodId(),listener);
            return;
        }
        update(shop,sku,Status.READY_TO_SIGN,sku.feedId(),progress.goodId(),listener);
        Long id=signOnce(shop,sku,session,sku.gtin(),sku.feedId(),listener);
        if(id!=null)update(shop,sku,Status.PUBLISHED,sku.feedId(),id,listener);
    }

    private void authorizeAutomaticDocument(Shop shop,Sku sku,CatalogSession session,ZnackNationalCatalogService.SigningDocument document)throws Exception {
        var key=signingKey(shop,sku);
        if(RegistrationSigningConsent.allowed(shop,key,sku.gtin(),document))return;
        if(!RegistrationSigningConsent.allowed(shop,key,sku.gtin()))throw new RegistrationValidationException("CATALOG_SIGNING_CONSENT_REQUIRED");
        String approved=registrations.payload(shop.getId(),sku.chrtId());
        if(approved.isBlank())throw new RegistrationValidationException("SIGNING_REVIEW_REQUIRED");
        session.catalog().validateApprovedSigningCard(session.token(),sku.gtin(),document.goodId(),JsonParser.parseString(approved).getAsJsonObject());
        if(!RegistrationSigningConsent.bindVerifiedDocument(shop,key,sku.gtin(),document))throw new RegistrationValidationException("SIGNING_DOCUMENT_CHANGED");
    }
    private Long signOnce(Shop shop,Sku sku,CatalogSession session,String gtin,String feedId,
                          BiConsumer<Status,String> listener)throws Exception {
        guard(shop,sku);
        if(!WbSourceConsentPolicy.permitsAutomatic(shop,signingKey(shop,sku),gtin))throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");
        var stored=registrations.signingCheckpoint(shop.getId(),sku.chrtId());
        if(stored!=null && !"PREPARED".equals(stored.state())) {
            // A send may already have reached Catalog. Only a remote signed readback can resolve it.
            update(shop,sku,Status.SIGNING,feedId,null,listener);
            notify(listener,Status.SIGNING,"The signing result needs readback. No signature was sent again.");
            return null;
        }
        var document=session.catalog().reviewSigning(session.token(),gtin);
        authorizeAutomaticDocument(shop,sku,session,document);
        registrations.prepareSignature(shop.getId(),sku.chrtId(),gtin,document);
        guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
        if(!claimSigning(shop,sku,session,document))return null;
        update(shop,sku,Status.SIGNING,feedId,document.goodId(),listener);
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            signWithJournal(shop,session,List.of(sku),List.of(document),()->{
                guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
                if(!WbSourceConsentPolicy.permitsAutomatic(shop,signingKey(shop,sku),gtin))throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");
                if(!RegistrationSigningConsent.allowed(shop,signingKey(shop,sku),gtin,document))throw new RegistrationValidationException("CATALOG_SIGNING_CONSENT_REQUIRED");
                var current=observedProgress(shop,session,sku);
                if(!current.readyToSign() || current.failed())throw new IllegalStateException("Catalog signing eligibility changed. Refresh the card.");
                var fresh=session.catalog().reviewSigning(session.token(),gtin);
                if(fresh.goodId()!=document.goodId() || !fresh.sha256().equals(document.sha256()))throw new IllegalStateException("Catalog signing document changed. Review the card again.");
                guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
                dispatched.set(true);
            });
            // The signing POST only acknowledges receipt. Publication needs an independent
            // read of the exact feed/GTIN/card, just as in the native Ozon workflow.
            guard(shop,sku);
            var after=observedProgress(shop,session,registrations.find(shop.getId(),sku.chrtId()));
            if(after.signed() && Objects.equals(after.goodId(),document.goodId())) {
                registrations.finishSignature(shop.getId(),sku.chrtId(),true);return after.goodId();
            }
            registrations.finishSignature(shop.getId(),sku.chrtId(),false);
            if(after.failed()){
                String reason=CatalogFeedback.rejection(after);registrations.updateProgress(shop.getId(),sku.chrtId(),Status.ERROR,feedId,after.goodId(),reason,null);
                notify(listener,Status.ERROR,reason);return null;
            }
            update(shop,sku,Status.SIGNING,feedId,document.goodId(),listener);
            String stage=CatalogFeedback.stage(after);
            if(stage!=null)registrations.updateProgress(shop.getId(),sku.chrtId(),Status.SIGNING,feedId,document.goodId(),stage,null);
            notify(listener,Status.SIGNING,"The signature was received; publication still needs readback.");
            return null;
        }catch(Exception error) {
            RegistrationCatalogCooldown.record(shop,error);
            if(!dispatched.get()){
                registrations.releaseUnsentSignature(shop.getId(),sku.chrtId(),document.sha256());
                registrations.updateProgress(shop.getId(),sku.chrtId(),Status.READY_TO_SIGN,feedId,document.goodId(),ZnackErrorDetails.summary(error),null);
                throw error;
            }
            registrations.finishSignature(shop.getId(),sku.chrtId(),false);
            if(CatalogFeedback.rateLimited(error)){
                registrations.updateProgress(shop.getId(),sku.chrtId(),Status.SIGNING,feedId,document.goodId(),"CATALOG_PUBLICATION_PENDING",null);
                notify(listener,Status.SIGNING,"CATALOG_PUBLICATION_PENDING");return null;
            }
            // Do not replay POST after timeout. A single readback may prove the original was accepted.
            try {
                guard(shop,sku);
                var progress=observedProgress(shop,session,registrations.find(shop.getId(),sku.chrtId()));
                if(progress.signed() && Objects.equals(progress.goodId(),document.goodId())) {
                    registrations.finishSignature(shop.getId(),sku.chrtId(),true);return progress.goodId();
                }
                if(progress.failed()){
                    String reason=CatalogFeedback.rejection(progress);registrations.updateProgress(shop.getId(),sku.chrtId(),Status.ERROR,feedId,progress.goodId(),reason,null);
                    notify(listener,Status.ERROR,reason);return null;
                }
            }catch(Exception unavailable){/* Preserve the durable uncertainty. */}
            update(shop,sku,Status.SIGNING,feedId,document.goodId(),listener);
            notify(listener,Status.SIGNING,"The signing result is uncertain. Refresh to reconcile; the POST will not be repeated.");
            return null;
        }
    }

    private void update(Shop shop, Sku sku, Status status, String feedId, Long goodId,
                        BiConsumer<Status, String> listener) {
        registrations.updateProgress(shop.getId(), sku.chrtId(), status, feedId, goodId, null, null);
        if(status==Status.PUBLISHED)new com.vncode.app.features.sync.SyncStreamStore().request(shop,"ZNACK");
        notify(listener, status, "");
    }

    static Draft withSchemaTypes(Draft draft,
            java.util.List<ZnackCardRegistrationModels.Attribute> schema, String wbSize) {
        Map<Long, String> types = new LinkedHashMap<>(draft.attributeTypes());
        var baseIds=schema.stream().map(ZnackCardRegistrationModels.Attribute::id).collect(java.util.stream.Collectors.toSet());
        for (var attribute : RegistrationAttributeRules.required(schema,draft.attributes())) {
            String value = draft.attributes().get(attribute.id());
            if (value == null || value.isBlank()) {if(!baseIds.contains(attribute.id()))throw new IllegalArgumentException("ZNACK_REQUIRED_ATTRIBUTES_MISSING: "+attribute.name());continue;}
            if(!RegistrationAttributeRules.validFieldValue(attribute,value))throw new IllegalArgumentException("ZNACK_INVALID_ATTRIBUTE_VALUE: "+attribute.name());
            String type = types.get(attribute.id());
            if (type == null || (!attribute.valueTypes().isEmpty() && !attribute.valueTypes().contains(type))) {
                type = ZnackWbAttributeMapper.resolveValueType(attribute, value, wbSize);
            }
            if (type == null) {
                throw new IllegalArgumentException("Không xác định được hệ/loại giá trị cho "
                        + attribute.name() + " [" + attribute.id() + "]: " + value
                        + ". Znack cho phép: " + attribute.valueTypes());
            }
            types.put(attribute.id(), type);
        }
        return new Draft(draft.tnved(), draft.feedTnved(), draft.categoryId(), draft.goodName(),
                draft.brand(), draft.attributes(), types,draft.autoSign(),draft.isKit());
    }

    // Photos are optional in /v3/feed. A WB CDN URL rejected by the catalog must not
    // be reintroduced when retrying a feed that also had attribute errors.
    static String retryImageUrl(String imageUrl, String previousError) {
        String error = previousError == null ? "" : previousError.toLowerCase(java.util.Locale.ROOT);
        boolean unavailableImage = error.contains("изображение не доступно по url")
                || error.contains("изображение недоступно по url");
        if(unavailableImage)return "";
        // WB supplies CDN thumbnails; Catalog's own fetcher rejects some of these hosts.
        // Images are optional: keep marketplace media, omit only these URLs from the feed.
        try{String host=java.net.URI.create(Objects.requireNonNullElse(imageUrl,"")).getHost();
            if(host!=null&&(host.equals("wbbasket.ru")||host.endsWith(".wbbasket.ru")))return "";
        }catch(IllegalArgumentException invalid){return "";}
        return imageUrl;
    }

    private void fail(Shop shop, Sku sku, Exception error, BiConsumer<Status, String> listener) {
        String message = ZnackErrorDetails.summary(error);
        var checkpoint=registrations.checkpoint(shop.getId(),sku.chrtId());
        if(checkpoint.status()==Status.PUBLISHED) {
            // Preserve a previously published card without presenting this failed
            // operation as a new successful registration.
            notify(listener,Status.ERROR,ZnackErrorDetails.format(error));
            return;
        }
        if(registrations.signingCheckpoint(shop.getId(),sku.chrtId()) instanceof ZnackCardRegistrationRepository.SigningCheckpoint signature
                && Set.of("SENDING","RECONCILE_REQUIRED").contains(signature.state())) {
            // A manual retry claims CHECKING before its remote read. Restore a transient
            // read failure to the monitor without erasing the immutable uncertain send.
            try{
                guard(shop,sku);var current=registrations.checkpointStatus(shop.getId(),sku.chrtId());
                if(current==Status.CHECKING||current==Status.SIGNING){
                    boolean pending=CatalogFeedback.retryableRead(error)||CatalogFeedback.notFound(error)||error instanceof LicenseDeniedException;
                    registrations.updateProgress(shop.getId(),sku.chrtId(),pending?Status.SIGNING:Status.ERROR,null,null,pending?"CATALOG_READBACK_RETRY_PENDING":message,null);
                }
            }catch(RuntimeException unavailable){/* Never update a historical request after an owner/scope change. */}
            Status current=registrations.checkpointStatus(shop.getId(),sku.chrtId());
            notify(listener,current==Status.ERROR?Status.ERROR:Status.SIGNING,current==Status.ERROR?message:"CATALOG_READBACK_RETRY_PENDING");return;
        }
        var nativeRow=registrations.find(shop.getId(),sku.chrtId());
        if(checkpoint.hasGtin()&&!checkpoint.hasFeed()&&nativeRow!=null&&nativeRow.goodId()!=null&&registrations.payload(shop.getId(),sku.chrtId()).isBlank()){
            try{guard(shop,sku);boolean pending=CatalogFeedback.retryableRead(error)||CatalogFeedback.notFound(error);
                registrations.updateProgress(shop.getId(),sku.chrtId(),pending?Status.PROCESSING:Status.ERROR,null,null,pending?"CATALOG_READBACK_RETRY_PENDING":message,null);
                notify(listener,pending?Status.PROCESSING:Status.ERROR,pending?"CATALOG_READBACK_RETRY_PENDING":message);
            }catch(RuntimeException changed){/* A historical owner is never rebound by a failed read. */}
            return;
        }
        if(CatalogFeedback.notFound(error)&&checkpoint.hasGtin()&&checkpoint.hasFeed()){
            registrations.updateProgress(shop.getId(),sku.chrtId(),Status.PROCESSING,null,null,"CATALOG_SIGNING_DOCUMENT_PENDING",null);
            notify(listener,Status.PROCESSING,"CATALOG_SIGNING_DOCUMENT_PENDING");return;
        }
        if(checkpoint.status()==Status.READY_TO_SUBMIT&&!checkpoint.hasFeed()&&CatalogFeedback.retryableRead(error)){
            registrations.updateProgress(shop.getId(),sku.chrtId(),Status.READY_TO_SUBMIT,null,null,message,null);
            notify(listener,Status.READY_TO_SUBMIT,message);return;
        }
        if(checkpoint.hasGtin()&&checkpoint.hasFeed()&&CatalogFeedback.retryableRead(error)){
            // The feed was accepted. A temporary status-read failure must not strand it
            // as a terminal ERROR or cause a second feed/allocation to be submitted.
            registrations.updateProgress(shop.getId(),sku.chrtId(),Status.PROCESSING,null,null,"CATALOG_READBACK_RETRY_PENDING",null);
            notify(listener,Status.PROCESSING,"CATALOG_READBACK_RETRY_PENDING");return;
        }
        if(checkpoint.status()==Status.READY_TO_SIGN){
            registrations.updateProgress(shop.getId(),sku.chrtId(),Status.READY_TO_SIGN,null,null,message,null);
            notify(listener,Status.READY_TO_SIGN,message);return;
        }
        if(!checkpoint.hasFeed()&&CatalogFeedback.rateLimitRejectedRequest(error)){
            Status retryable=checkpoint.status()==Status.ALLOCATING_GTIN&&!checkpoint.hasGtin()?Status.PREFLIGHT_ERROR
                    :checkpoint.status()==Status.FEED_SUBMITTING&&checkpoint.hasGtin()?Status.READY_TO_SUBMIT:null;
            if(retryable!=null){registrations.updateProgress(shop.getId(),sku.chrtId(),retryable,null,null,message,null);notify(listener,retryable,message);return;}
        }
        boolean scopeBlocked=error instanceof ZnackRegistrationScope.ScopeChangedException||error instanceof RegistrationValidationException;
        Status failure=scopeBlocked && checkpoint.status()==Status.ALLOCATING_GTIN?Status.PREFLIGHT_ERROR
                :error instanceof RegistrationValidationException && checkpoint.hasFeed()?Status.ERROR
                :scopeBlocked && checkpoint.status()==Status.FEED_SUBMITTING?Status.PRE_SUBMIT_ERROR
                :checkpoint.status()==Status.PREFLIGHT_ERROR&&!checkpoint.hasGtin()&&!checkpoint.hasFeed()?Status.PREFLIGHT_ERROR
                :checkpoint.status()==Status.ALLOCATING_GTIN ? Status.GTIN_REVIEW_REQUIRED
                : checkpoint.status()==Status.FEED_SUBMITTING ? Status.FEED_REVIEW_REQUIRED
                : checkpoint.hasGtin() && !checkpoint.hasFeed() ? Status.PRE_SUBMIT_ERROR
                : checkpoint.status()==Status.CHECKING && !checkpoint.hasGtin() && !sku.needsGtinReview()
                ? Status.PREFLIGHT_ERROR : Status.ERROR;
        registrations.updateProgress(shop.getId(), sku.chrtId(), failure, null, null, message, null);
        notify(listener, failure, ZnackErrorDetails.format(error));
    }

    private void pauseKnownUnsent(Shop shop,Sku sku,LicenseDeniedException denied,
                                 BiConsumer<Status,String> listener) {
        var checkpoint=registrations.checkpoint(shop.getId(),sku.chrtId());
        // Never erase an accepted identity or manufacture a REVIEW_REQUIRED remote ambiguity.
        Status paused=!checkpoint.hasGtin()?Status.PREFLIGHT_ERROR
                :!checkpoint.hasFeed()?Status.READY_TO_SUBMIT
                :checkpoint.status()==Status.SIGNING || checkpoint.status()==Status.READY_TO_SIGN
                ?Status.READY_TO_SIGN:Status.PROCESSING;
        registrations.updateProgress(shop.getId(),sku.chrtId(),paused,null,null,denied.getMessage(),null);
        notify(listener,paused,denied.getMessage());
    }

    private static void notify(BiConsumer<Status, String> listener, Status status, String detail) {
        if (listener != null) {
            try { listener.accept(status, detail == null ? "" : detail); }
            catch (RuntimeException ignored) { /* UI observers cannot change the durable remote outcome. */ }
        }
    }

    private static boolean onlyImageErrors(java.util.List<String> errors) {
        return errors != null && !errors.isEmpty() && errors.stream().allMatch(error -> {
            String normalized = error == null ? "" : error.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("изображен") || normalized.contains("photo")
                    || normalized.contains("image") || normalized.contains("url");
        });
    }

}
