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

/** Durable, one-SKU-at-a-time test workflow. A checkpoint is written before every remote transition. */
public final class ZnackCardRegistrationWorkflow {
    private static final int POLL_ATTEMPTS = 3;
    private static final Object GTIN_CHECKPOINT_LOCK = CatalogAllocationLock.MONITOR;
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256), runnable -> {
        Thread thread = new Thread(()->{try(var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(true)){runnable.run();}}, "znack-card-registration");
        thread.setDaemon(true);
        return thread;
    },new ThreadPoolExecutor.AbortPolicy());

    private final ZnackCardRegistrationRepository registrations;
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();
    private final CatalogFactory catalogFactory;
    private final Executor executor;
    private final boolean strictScope;
    private final String namespace=com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize().toString();
    private final MarketplaceCompletion marketplaceCompletion;

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
        this.registrations=registrations;this.catalogFactory=factory;this.executor=executor;
        this.strictScope=strictScope;this.marketplaceCompletion=completion;
    }
    public static boolean hasActiveJobs(){return !RUNNING.isEmpty();}
    private String operationKey(Shop shop,Sku sku){return namespace+":"+shop.getId()+":"+sku.chrtId();}
    public int activeCount(Shop shop) {
        String prefix=namespace+":"+shop.getId()+":";return (int)RUNNING.stream().filter(key->key.startsWith(prefix)).count();
    }
    public boolean registerNew(Shop shop,Sku published,Draft draft,boolean confirmed,BiConsumer<Status,String> listener) {
        if(!confirmed || published==null || !published.canRegisterNew())return false;
        if(published.status()==Status.ERROR)return replaceRejected(shop,published,draft,listener);
        return schedule(shop,published,draft,RegistrationAction.REGISTER_NEW,listener);
    }
    private boolean replaceRejected(Shop requested,Sku old,Draft draft,BiConsumer<Status,String> listener){
        Shop shop=freezeShop(requested);if(shop==null||draft==null||shop.getMarketplace()!=Marketplace.WILDBERRIES)return false;
        LicenseRuntime.require(Permission.GTIN);String key=operationKey(shop,old);if(!RUNNING.add(key))return false;
        try{executor.execute(()->{
            try{
                guard(shop,old);var session=catalogFactory.open(shop);guard(shop,old);
                if(strictScope&&!registrations.scope(shop.getId(),old.chrtId()).equals(session.scope()))throw new ZnackRegistrationScope.ScopeChangedException();
                if(!CatalogFeedback.confirmedRejection(session.catalog().progress(session.token(),old.feedId(),old.gtin())))throw new IllegalStateException("CATALOG_NEW_GTIN_REQUIRES_REJECTED_FEED");
                guard(shop,old);LicenseRuntime.require(Permission.GTIN);
                if(!registrations.claimRejectedReplacement(shop.getId(),old))throw new IllegalStateException("REGISTRATION_CHANGED");
                execute(shop,freshCandidate(old),draft,true,false,true,listener);
            }catch(Exception error){fail(shop,old,error,listener);}finally{RUNNING.remove(key);}
        });return true;}catch(java.util.concurrent.RejectedExecutionException full){RUNNING.remove(key);return false;}
    }
    /** One bounded executor item; cards are claimed sequentially from the frozen reviewed list. */
    public boolean signApproved(Shop requestedShop,List<Sku> skus,boolean confirmed,BiConsumer<Status,String> listener) {
        Shop shop=freezeShop(requestedShop);
        if(!confirmed || shop==null || shop.getMarketplace()!=Marketplace.WILDBERRIES || skus==null || skus.size()>10_000)return false;
        var selected=new LinkedHashMap<Long,Sku>();
        for(Sku sku:List.copyOf(skus))if(sku!=null && sku.canSignApproved())selected.putIfAbsent(sku.chrtId(),sku);
        List<Sku> frozen=List.copyOf(selected.values());if(frozen.isEmpty())return false;
        LicenseRuntime.require(Permission.GTIN);
        ZnackRegistrationScope owner=strictScope?ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings()):null;
        String bulkKey=namespace+":"+shop.getId()+":bulk-sign";if(!RUNNING.add(bulkKey))return false;
        try {
            executor.execute(()->{
                try {
                    CatalogSession session=null;
                    for(Sku sku:frozen) {
                        String key=operationKey(shop,sku);if(!RUNNING.add(key))continue;
                        boolean claimed=false;
                        try {
                            if(owner!=null){owner.assertCurrent(shop);guard(shop,sku);}
                            LicenseRuntime.require(Permission.GTIN);
                            if(!registrations.claim(shop.getId(),sku,RegistrationAction.SIGN_APPROVED))continue;
                            claimed=true;
                            RegistrationSigningConsent.set(shop,new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId())),sku.gtin(),true);
                            if(session==null) {
                                session=catalogFactory.open(shop);
                                if(owner!=null && !owner.equals(session.scope()))throw new ZnackRegistrationScope.ScopeChangedException();
                            }
                            signApprovedOne(shop,sku,session,listener);
                        }catch(LicenseDeniedException denied){if(claimed)pauseKnownUnsent(shop,sku,denied,listener);else notify(listener,sku.status(),denied.getMessage());break;}
                        catch(ZnackRegistrationScope.ScopeChangedException changed){if(claimed)fail(shop,sku,changed,listener);else notify(listener,sku.status(),changed.getMessage());break;}
                        catch(Exception error){if(claimed)fail(shop,sku,error,listener);else notify(listener,sku.status(),ZnackErrorDetails.summary(error));}
                        finally{RUNNING.remove(key);}
                    }
                }finally{RUNNING.remove(bulkKey);}
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
        com.vncode.app.features.gtinsync.RegisteredGtinProof.requireCurrent(shop,gtin);
        var key=new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(shop.getId(),Marketplace.WILDBERRIES,Long.toString(nmId),Long.toString(variantId));
        String normalized=com.vncode.app.integration.znack.GtinNormalizer.normalize(gtin);
        return new com.vncode.app.integration.wb.WbGtinAdapter(shop).read(key).barcodes().stream()
                .map(com.vncode.app.integration.znack.GtinNormalizer::normalize).anyMatch(normalized::equals);
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
        return confirmed && schedule(shop,sku,draft,RegistrationAction.REQUEST_NEW_GTIN,listener);
    }

    public boolean isRunning(Shop shop,Sku sku) {
        return shop!=null && sku!=null && RUNNING.contains(operationKey(shop,sku));
    }

    public boolean start(Shop shop, Sku sku, Draft draft, BiConsumer<Status, String> listener) {
        return schedule(shop,sku,draft,RegistrationAction.REGISTER,listener);
    }

    private boolean schedule(Shop requestedShop,Sku sku,Draft draft,RegistrationAction action,
                             BiConsumer<Status,String> listener) {
        Shop shop=freezeShop(requestedShop);
        if(shop==null || sku==null || draft==null || shop.getMarketplace()!=Marketplace.WILDBERRIES) return false;
        // A submitted feed may still be read back after expiry. An unsent claim needs current rights.
        if(action!=RegistrationAction.RESUME || !sku.hasFeed())LicenseRuntime.require(Permission.GTIN);
        boolean groupChange=action==RegistrationAction.REGISTER&&sku.hasGtin()&&sku.hasFeed()&&TnvedGroupChange.replaceable(sku.status())&&TnvedGroupChange.changed(TnvedGroupChange.payloadCode(registrations.payload(shop.getId(),sku.chrtId())),draft.tnved());
        String key=operationKey(shop,sku);
        if(!RUNNING.add(key))return false;
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
            boolean claimed=groupChange?registrations.claimGroupReplacement(shop.getId(),sku,draft.tnved()):action==RegistrationAction.REGISTER_NEW?registrations.claimReplacement(shop.getId(),sku):registrations.claim(shop.getId(),sku,action);
            if(!claimed){RUNNING.remove(key);return false;}
            if(bound!=null)registrations.bindScope(shop,sku.chrtId(),bound);
            try {
                executor.execute(()->{
                    try{execute(shop,groupChange||action==RegistrationAction.REGISTER_NEW?freshCandidate(sku):sku,draft,groupChange||action==RegistrationAction.REQUEST_NEW_GTIN || action==RegistrationAction.REGISTER_NEW,action==RegistrationAction.SIGN_APPROVED,action!=RegistrationAction.RESUME&&action!=RegistrationAction.SIGN_APPROVED,listener);}
                    catch(LicenseDeniedException denied){pauseKnownUnsent(shop,sku,denied,listener);}
                    catch(Exception error){fail(shop,sku,error,listener);}
                    finally{RUNNING.remove(key);}
                });
            } catch (java.util.concurrent.RejectedExecutionException error) {
                // No task started, so a durable failure can prove no remote mutation occurred.
                fail(shop,sku,error,listener);
                RUNNING.remove(key);
                return false;
            }
            return true;
        }catch(RuntimeException error){RUNNING.remove(key);throw error;}
    }

    public boolean resume(Shop shop,Sku sku,BiConsumer<Status,String> listener){return resume(shop,sku,null,listener);}
    public boolean resume(Shop shop, Sku sku,Boolean automaticSigning, BiConsumer<Status, String> listener) {
        if (shop == null || sku == null || !sku.canResume()) return false;
        String stored = registrations.payload(shop.getId(), sku.chrtId());
        if (stored.isBlank()) return false;
        JsonObject payload = JsonParser.parseString(stored).getAsJsonObject();
        if(automaticSigning!=null){if(automaticSigning)LicenseRuntime.require(Permission.GTIN);guard(shop,sku);RegistrationSigningConsent.set(shop,new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId())),sku.gtin(),automaticSigning);}
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
        return session.catalog().progress(session.token(),sku.feedId(),sku.gtin());
    }
    /** Reconcile a saved feed; signing is allowed only by an existing exact-card consent. */
    public boolean refreshStatus(Shop requestedShop,Sku sku)throws Exception {
        Shop shop=freezeShop(requestedShop);String operation=operationKey(shop,sku);
        if(!RUNNING.add(operation))return false;
        try {
        guard(shop,sku);var session=catalogFactory.open(shop);guard(shop,sku);if(strictScope&&!registrations.scope(shop.getId(),sku.chrtId()).equals(session.scope()))throw new ZnackRegistrationScope.ScopeChangedException();
        var progress=session.catalog().progress(session.token(),sku.feedId(),sku.gtin());guard(shop,sku);
        if(progress.goodId()!=null&&sku.goodId()!=null&&!progress.goodId().equals(sku.goodId()))throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
        Status status;
        var signature=registrations.signingCheckpoint(shop.getId(),sku.chrtId());
        if(progress.signed()){status=Status.PUBLISHED;registrations.finishSignature(shop.getId(),sku.chrtId(),true);}
        else if(sku.status()==Status.SIGNING||signature!=null&&Set.of("SENDING","RECONCILE_REQUIRED").contains(signature.state()))status=Status.SIGNING;
        else status=progress.failed()?Status.ERROR:progress.readyToSign()?Status.READY_TO_SIGN:Status.PROCESSING;
        String error=progress.failed()?CatalogFeedback.rejection(progress):null;
        if(status==Status.PROCESSING&&java.time.Instant.now().isAfter(registrations.submissionStarted(shop.getId(),sku).plus(java.time.Duration.ofMinutes(5)))
                &&!session.catalog().cardExists(session.token(),sku.gtin(),progress.goodId())){
            status=Status.ERROR;error="CATALOG_CARD_NOT_CREATED_AFTER_WAIT";
        }
        if(sku.status()==Status.PUBLISHED&&status!=Status.PUBLISHED){
            if(!registrations.reconcilePublication(shop,sku,status,error==null?"CATALOG_PUBLICATION_NOT_CONFIRMED":error))return false;
        }else registrations.updateProgress(shop.getId(),sku.chrtId(),status,sku.feedId(),progress.goodId(),error,null);
        if(status==Status.READY_TO_SIGN) {
            var nativeKey=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
            if(RegistrationSigningConsent.allowed(shop,nativeKey,sku.gtin())) {
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
        String token=auth.trueApiToken(settings);
        if(auth.authenticatedParticipantInn()!=null && !scope.inn().equals(auth.authenticatedParticipantInn()))throw new ZnackRegistrationScope.ScopeChangedException();
        scope.assertCurrent(shop);
        return new CatalogSession(token,catalog,scope);
    }
    private static Shop freezeShop(Shop shop){return shop==null?null:new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());}

    private static Sku freshCandidate(Sku sku) {
        return new Sku(sku.nmId(),sku.chrtId(),sku.subjectId(),sku.vendorCode(),sku.subjectName(),sku.brand(),sku.title(),sku.color(),sku.size(),sku.barcodes(),sku.imageUrl(),sku.needKiz(),null,null,null,Status.CHECKING,null,false,sku.wbSize(),sku.registrationRevision());
    }
    private void execute(Shop shop, Sku sku, Draft draft, boolean requestFresh,boolean signingOnly,boolean recordConsent,
                         BiConsumer<Status, String> listener) throws Exception {
        if(!sku.hasFeed())LicenseRuntime.require(Permission.GTIN);
        guard(shop,sku);
        CatalogSession session=catalogFactory.open(shop);
        guard(shop,sku);
        if(strictScope && !registrations.scope(shop.getId(),sku.chrtId()).equals(session.scope()))throw new ZnackRegistrationScope.ScopeChangedException();
        ZnackNationalCatalogService catalog=session.catalog();
        String token=session.token();
        if(signingOnly){signApprovedOne(shop,sku,session,listener);return;}

        String gtin = sku.gtin();
        String feedId = sku.feedId();
        String stored = registrations.payload(shop.getId(), sku.chrtId());
        boolean rebuildPayload = gtin == null || gtin.isBlank() || (sku.status() == Status.ERROR&&!CatalogFeedback.interrupted(sku.errorMessage()))
                || sku.status() == Status.GTIN_GENERATED || feedId == null || feedId.isBlank() || stored.isBlank();
        if(sku.status()==Status.READY_TO_SUBMIT&&!stored.isBlank())rebuildPayload=false;
        if (rebuildPayload) {
            draft=draft.withProductCategory(sku.subjectName());
            draft = withSchemaTypes(draft, catalog.requiredAttributes(draft.categoryId(), token), sku.wbSize());
        }
        String imageUrl = retryImageUrl(sku.imageUrl(), sku.errorMessage());
        JsonObject payload;
        if (gtin == null || gtin.isBlank()) {
            update(shop, sku, Status.CHECKING, null, null, listener);
            ZnackNationalCatalogService.Preflight preflight = catalog.preflight(draft.tnved());
            // Keep selection and the durable local checkpoint atomic across the two workflow
            // workers. Otherwise both can observe the same reusable catalog draft GTIN.
            synchronized (GTIN_CHECKPOINT_LOCK) {
                LicenseRuntime.require(Permission.GTIN);
                update(shop,sku,Status.ALLOCATING_GTIN,null,null,listener);
                LicenseRuntime.require(Permission.GTIN);
                guard(shop,sku);
                gtin = requestFresh
                        ? catalog.generateFreshOne(preflight.token(),registrations.claimedGtins())
                        : catalog.generateOne(preflight.token(), registrations.claimedGtins());
                payload = ZnackNationalCatalogService.buildPayload(gtin, draft, imageUrl);
                registrations.saveGenerated(shop.getId(), sku, gtin, draft.tnved(), draft.categoryId(),
                        draft.goodName(), payload.toString());
            }
            notify(listener, Status.GTIN_GENERATED, gtin);
        } else {
            if (rebuildPayload) {
                payload = ZnackNationalCatalogService.buildPayload(gtin, draft, imageUrl);
                // A pending feed retains its original request until the server confirms rejection.
                if(feedId==null||feedId.isBlank()){
                    registrations.saveGenerated(shop.getId(), sku, gtin, draft.tnved(), draft.categoryId(),draft.goodName(), payload.toString());
                    notify(listener, Status.GTIN_GENERATED, gtin);
                }
            } else {
                payload = JsonParser.parseString(stored).getAsJsonObject();
            }
        }

        var nativeKey=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
        if(recordConsent){RegistrationSigningConsent.set(shop,nativeKey,gtin,draft.autoSign());RegistrationMarketplaceConsent.set(shop,nativeKey,gtin,true);}
        if (feedId == null || feedId.isBlank()) {
            LicenseRuntime.require(Permission.GTIN);
            update(shop,sku,Status.READY_TO_SUBMIT,null,null,listener);
            update(shop,sku,Status.FEED_SUBMITTING,null,null,listener);
            LicenseRuntime.require(Permission.GTIN);
            guard(shop,sku);
            feedId = catalog.submit(token, payload);
            registrations.updateProgress(shop.getId(), sku.chrtId(), Status.FEED_SUBMITTED, feedId,
                    null, null, null);
            notify(listener, Status.FEED_SUBMITTED, feedId);
            new com.vncode.app.features.sync.SyncStreamStore().request(shop,"ZNACK");
        }

        Long goodId = sku.goodId();
        boolean published = sku.status() == Status.PUBLISHED;
        boolean retriedWithoutImage = false;
        boolean retriedRejectedFeed = false;
        boolean retriedPacking = false;
        for (int attempt = 0; !published && attempt < POLL_ATTEMPTS; attempt++) {
            guard(shop,sku);
            ZnackNationalCatalogService.FeedProgress progress = catalog.progress(token, feedId, gtin);
            if(goodId!=null&&progress.goodId()!=null&&!goodId.equals(progress.goodId()))throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
            if (progress.goodId() != null) goodId = progress.goodId();
            if (progress.failed()) {
                boolean rejected=CatalogFeedback.confirmedRejection(progress);
                boolean removeImage=rejected && !retriedWithoutImage && payload.has("good_images") && onlyImageErrors(progress.errors());
                boolean repairRejected=rejected && sku.status()==Status.ERROR && !retriedRejectedFeed;
                Boolean packing=retriedPacking?null:CatalogPackingPolicy.correction(progress,payload.has("is_kit")&&payload.get("is_kit").getAsBoolean());
                if (removeImage || repairRejected || packing!=null) {
                    LicenseRuntime.require(Permission.GTIN);
                    if(removeImage){retriedWithoutImage=true;payload.remove("good_images");}
                    if(packing!=null){retriedPacking=true;draft=draft.withKit(packing);if(packing)payload.addProperty("is_kit",true);else payload.remove("is_kit");}
                    retriedRejectedFeed=true;
                    if(!registrations.prepareRejectedRetry(shop,sku,gtin,feedId,goodId,draft.tnved(),draft.categoryId(),draft.goodName(),payload.toString(),CatalogFeedback.rejection(progress)))throw new IllegalStateException("REGISTRATION_CHANGED");
                    goodId=null;
                    update(shop,sku,Status.FEED_SUBMITTING,null,null,listener);
                    LicenseRuntime.require(Permission.GTIN);
                    guard(shop,sku);
            feedId = catalog.submit(token, payload);
                    registrations.updateProgress(shop.getId(), sku.chrtId(), Status.FEED_SUBMITTED, feedId,
                            null, null, null);
                    notify(listener, Status.FEED_SUBMITTED, feedId);
                    attempt = -1;
                    continue;
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
                if(RegistrationSigningConsent.allowed(shop,nativeKey,gtin))signApprovedOne(shop,registrations.find(shop.getId(),sku.chrtId()),session,listener);
                return;
            }
            update(shop, sku, Status.PROCESSING, feedId, goodId, listener);
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

    private void signApprovedOne(Shop shop,Sku sku,CatalogSession session,BiConsumer<Status,String> listener)throws Exception {
        guard(shop,sku);
        var progress=session.catalog().progress(session.token(),sku.feedId(),sku.gtin());
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

    private Long signOnce(Shop shop,Sku sku,CatalogSession session,String gtin,String feedId,
                          BiConsumer<Status,String> listener)throws Exception {
        guard(shop,sku);
        var stored=registrations.signingCheckpoint(shop.getId(),sku.chrtId());
        if(stored!=null && !"PREPARED".equals(stored.state())) {
            // A send may already have reached Catalog. Only a remote signed readback can resolve it.
            update(shop,sku,Status.SIGNING,feedId,null,listener);
            notify(listener,Status.SIGNING,"The signing result needs readback. No signature was sent again.");
            return null;
        }
        var document=session.catalog().reviewSigning(session.token(),gtin);
        registrations.prepareSignature(shop.getId(),sku.chrtId(),gtin,document);
        guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
        if(!registrations.claimSignatureSend(shop.getId(),sku.chrtId(),document.sha256()))return null;
        update(shop,sku,Status.SIGNING,feedId,document.goodId(),listener);
        try {
            session.catalog().sendReviewedSigning(session.token(),document,()->{
                guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
                var current=session.catalog().progress(session.token(),feedId,gtin);
                if(!current.readyToSign() || current.failed())throw new IllegalStateException("Catalog signing eligibility changed. Refresh the card.");
                var fresh=session.catalog().reviewSigning(session.token(),gtin);
                if(fresh.goodId()!=document.goodId() || !fresh.sha256().equals(document.sha256()))throw new IllegalStateException("Catalog signing document changed. Review the card again.");
                guard(shop,sku);LicenseRuntime.require(Permission.GTIN);
            });
            // The signing POST only acknowledges receipt. Publication needs an independent
            // read of the exact feed/GTIN/card, just as in the native Ozon workflow.
            guard(shop,sku);
            var after=session.catalog().progress(session.token(),feedId,gtin);
            if(after.signed() && Objects.equals(after.goodId(),document.goodId())) {
                registrations.finishSignature(shop.getId(),sku.chrtId(),true);return after.goodId();
            }
            registrations.finishSignature(shop.getId(),sku.chrtId(),false);
            update(shop,sku,Status.SIGNING,feedId,document.goodId(),listener);
            notify(listener,Status.SIGNING,"The signature was received; publication still needs readback.");
            return null;
        }catch(Exception error) {
            registrations.finishSignature(shop.getId(),sku.chrtId(),false);
            // Do not replay POST after timeout. A single readback may prove the original was accepted.
            try {
                guard(shop,sku);
                var progress=session.catalog().progress(session.token(),feedId,gtin);
                if(progress.signed() && Objects.equals(progress.goodId(),document.goodId())) {
                    registrations.finishSignature(shop.getId(),sku.chrtId(),true);return progress.goodId();
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
        for (var attribute : schema) {
            String value = draft.attributes().get(attribute.id());
            if (value == null || value.isBlank()) continue;
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
        return unavailableImage ? "" : imageUrl;
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
        if(checkpoint.status()==Status.SIGNING && registrations.signingCheckpoint(shop.getId(),sku.chrtId())!=null) {
            notify(listener,Status.SIGNING,"The signature needs readback; no POST was repeated.");return;
        }
        if(CatalogFeedback.notFound(error)&&checkpoint.hasGtin()&&checkpoint.hasFeed()){
            registrations.updateProgress(shop.getId(),sku.chrtId(),Status.PROCESSING,null,null,"CATALOG_SIGNING_DOCUMENT_PENDING",null);
            notify(listener,Status.PROCESSING,"CATALOG_SIGNING_DOCUMENT_PENDING");return;
        }
        if(checkpoint.hasGtin()&&checkpoint.hasFeed()&&CatalogFeedback.retryableRead(error)){
            // The feed was accepted. A temporary status-read failure must not strand it
            // as a terminal ERROR or cause a second feed/allocation to be submitted.
            registrations.updateProgress(shop.getId(),sku.chrtId(),Status.PROCESSING,null,null,"CATALOG_READBACK_RETRY_PENDING",null);
            notify(listener,Status.PROCESSING,"CATALOG_READBACK_RETRY_PENDING");return;
        }
        boolean scopeBlocked=error instanceof ZnackRegistrationScope.ScopeChangedException;
        Status failure=scopeBlocked && checkpoint.status()==Status.ALLOCATING_GTIN?Status.PREFLIGHT_ERROR
                :scopeBlocked && checkpoint.status()==Status.FEED_SUBMITTING?Status.PRE_SUBMIT_ERROR
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
