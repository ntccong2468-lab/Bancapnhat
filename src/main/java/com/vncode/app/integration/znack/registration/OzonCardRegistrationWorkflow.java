package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonGtinAdapter;
import com.vncode.app.integration.ozon.OzonProductSnapshotReader;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppPaths;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationWorkflow.CatalogSession;

/** Ozon-native registration with reviewed feed data, durable checkpoints and bounded work.
 * A moderation read performs one poll and releases its worker. Signing is a separate reviewed
 * action; an uncertain allocation, feed or signing POST is never automatically replayed. */
public final class OzonCardRegistrationWorkflow {
    @FunctionalInterface public interface CatalogFactory { CatalogSession open(Shop shop)throws Exception; }
    @FunctionalInterface public interface SnapshotSource { ProductSnapshot read(Shop shop,ProductKey key)throws Exception; }
    @FunctionalInterface public interface MarketplaceCompletion { boolean present(Shop shop,ProductKey key,String sku,String gtin)throws Exception; }
    private static final ExecutorService WORKERS=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),task->{var t=new Thread(()->{try(var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(true)){task.run();}},"ozon-catalog-registration");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    // One accepted work item per shop prevents a large shop from filling the shared queue.
    private static final Set<String> RUNNING=ConcurrentHashMap.newKeySet();
    private static final Map<String,CompletableFuture<Void>> COMPLETIONS=new ConcurrentHashMap<>();
    private final OzonCardRegistrationRepository repository;private final CatalogFactory catalogs;
    private final SnapshotSource snapshots;private final Executor executor;private final MarketplaceCompletion completion;
    public OzonCardRegistrationWorkflow(){this(new OzonCardRegistrationRepository());}
    public OzonCardRegistrationWorkflow(OzonCardRegistrationRepository repository){
        this(repository,ZnackCardRegistrationWorkflow::productionCatalog,new OzonProductSnapshotReader()::read,WORKERS,OzonCardRegistrationWorkflow::candidatePresent);
    }
    public OzonCardRegistrationWorkflow(OzonCardRegistrationRepository repository,CatalogFactory catalogs,SnapshotSource snapshots,Executor executor,MarketplaceCompletion completion){this.repository=Objects.requireNonNull(repository);this.catalogs=Objects.requireNonNull(catalogs);this.snapshots=Objects.requireNonNull(snapshots);this.executor=Objects.requireNonNull(executor);this.completion=Objects.requireNonNull(completion);}
    public record PreflightMetadata(String tnved,String categoryTnved,Gs1Status gs1,List<Category> categories){public PreflightMetadata{categories=List.copyOf(categories);}}
    public static final class Preparation {
        private final ProductSnapshot snapshot;private final String tnved;private final PreflightMetadata preflight;
        private final long categoryId;private final List<Attribute> required;private final ZnackRegistrationDocuments.Selection documents;
        private final Map<Long,String> suggestions;private final CatalogSession session;private final String owner;
        private Preparation(ProductSnapshot snapshot,String tnved,PreflightMetadata preflight,long categoryId,List<Attribute> required,ZnackRegistrationDocuments.Selection documents,Map<Long,String> suggestions,CatalogSession session,String owner){this.snapshot=snapshot;this.tnved=tnved;this.preflight=preflight;this.categoryId=categoryId;this.required=List.copyOf(required);this.documents=documents;this.suggestions=Map.copyOf(suggestions);this.session=session;this.owner=owner;}
        public ProductSnapshot snapshot(){return snapshot;}public String tnved(){return tnved;}public PreflightMetadata preflight(){return preflight;}
        public long categoryId(){return categoryId;}public List<Attribute> required(){return required;}public ZnackRegistrationDocuments.Selection documents(){return documents;}public Map<Long,String> suggestions(){return suggestions;}
        @Override public String toString(){return "OzonRegistrationPreparation[key="+snapshot.key()+"]";}
    }
    public Preparation prepare(Shop shop,ProductKey key)throws Exception{return prepare(shop,key,null);}
    public Preparation prepare(Shop selected,ProductKey key,String suppliedTnved)throws Exception {
        return prepare(selected,key,suppliedTnved,null);
    }
    private Preparation prepare(Shop selected,ProductKey key,String suppliedTnved,CatalogSession sharedSession)throws Exception {
        Shop shop=capture(selected);
        require(shop,key);LicenseRuntime.require(Permission.SELLER_API);
        var snapshot=snapshots.read(shop,key);if(!snapshot.key().equals(key))throw new IllegalStateException("product_mismatch");
        String tnved=suppliedTnved==null?tnved(snapshot):suppliedTnved.strip();
        var settings=settings(shop);var docs=ZnackRegistrationDocuments.select(snapshot,settings,LocalDate.now());
        String owner=ListingIdentity.fingerprint(shop);
        if(tnved.isBlank())return new Preparation(snapshot,"",null,0,List.of(),docs,Map.of(),null,owner);
        if(!tnved.matches("[0-9]{4,10}"))throw new IllegalArgumentException("invalid_tnved");
        var scope=ZnackRegistrationScope.fromSettings(settings);scope.assertCurrent(shop);var session=sharedSession==null?catalogs.open(shop):sharedSession;checkSession(shop,scope,session);
        var checked=session.catalog().preflight(tnved);checkSession(shop,scope,session);
        var metadata=new PreflightMetadata(checked.tnved(),checked.categoryTnved(),checked.gs1(),checked.categories());
        var result=new Preparation(snapshot,tnved,metadata,0,List.of(),docs,Map.of(),session,owner);
        var selectedCategory=ZnackCategoryResolver.resolve(snapshot,checked.categories());
        return selectedCategory.isPresent()?withCategory(shop,result,selectedCategory.get().id()):result;
    }
    public record BatchItem(ProductKey key,Preparation preparation,Draft draft,String issue) {
        public boolean ready(){return preparation!=null&&draft!=null&&issue.isBlank();}
        public boolean needsDocumentConsent(){return preparation!=null&&draft!=null&&"znack.registration.batch_document_review".equals(issue);}
        @Override public String toString(){return "BatchItem[key="+key+", ready="+ready()+"]";}
    }
    public record BatchReview(java.util.List<BatchItem> items,boolean newCard) {
        public BatchReview{items=java.util.List.copyOf(items);}
        public java.util.List<BatchItem> ready(){return items.stream().filter(BatchItem::ready).toList();}
    }
    /** No seller writes. Reuses a bound Catalog session, never infers ambiguous values. */
    public BatchReview prepareBatch(Shop shop,java.util.List<ProductKey> keys,boolean newCard,java.util.function.IntConsumer progress)throws Exception {
        if(keys==null||keys.isEmpty()||keys.size()>1000)throw new IllegalArgumentException("Registration batch limit is 1000");
        require(shop,keys.getFirst());var items=new java.util.ArrayList<BatchItem>();var checkpoints=repository.findMany(keys);CatalogSession shared=null;long retained=0;long quota=Long.MAX_VALUE;
        for(var key:keys.stream().distinct().toList()) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Registration preview cancelled");
            require(shop,key);
            var current=checkpoints.get(key);
            boolean eligible=newCard?current!=null&&current.status()==Status.PUBLISHED&&java.util.Objects.equals(current.gtin(),current.activeGtin())
                    :current==null||java.util.Set.of(Status.NOT_CREATED,Status.PREFLIGHT_ERROR,Status.PRE_SUBMIT_ERROR).contains(current.status());
            if(!eligible){items.add(new BatchItem(key,null,null,"znack.registration.batch_not_eligible"));progress.accept(items.size());continue;}
            try {
                var p=prepare(shop,key,null,shared);if(p.session!=null)shared=p.session;
                retained+=(long)com.vncode.app.features.products.ProductJson.GSON.toJson(p.snapshot()).length()*2;
                if(retained>64L*1024*1024)throw new IllegalArgumentException("Registration preview exceeds 64 MiB; select a smaller batch");
                var draft=suggestedDraft(p);String issue=draft.isPresent()?(p.documents().automatic()==null?"znack.registration.batch_document_review":""):"znack.registration.batch_needs_review";
                if(p.preflight()!=null) {
                    quota=Math.min(quota,p.preflight().gs1().remaining());
                    if(!p.preflight().gs1().quotaKnown()||quota==0){draft=java.util.Optional.empty();issue="znack.registration.batch_quota";}
                }
                if(draft.isPresent())quota--;
                items.add(new BatchItem(key,p,draft.orElse(null),issue));
            }catch(java.io.IOException|IllegalStateException unavailable){items.add(new BatchItem(key,null,null,"znack.registration.batch_read_failed"));}
            progress.accept(items.size());
        }
        return new BatchReview(items,newCard);
    }
    public static java.util.Optional<Draft> suggestedDraft(Preparation p) {
        if(p.preflight()==null||p.categoryId()==0||p.snapshot().title().isBlank()||p.snapshot().brand().isBlank()||p.documents().candidates().size()!=1)return java.util.Optional.empty();
        var values=new java.util.LinkedHashMap<Long,String>();var types=new java.util.LinkedHashMap<Long,String>();
        values.put(2478L,p.snapshot().title());values.put(2504L,p.snapshot().brand());
        for(var a:p.required()) {
            if(java.util.Set.of(2478L,2504L,3959L,ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID).contains(a.id()))continue;
            if(a.id()==13933L&&p.preflight().categoryTnved().length()==10)continue;
            String value=p.suggestions().getOrDefault(a.id(),a.id()==13933L?p.tnved():"");
            if(value.isBlank()||(a.presetOnly()&&!a.presets().contains(value))||a.valueTypes().size()>1)return java.util.Optional.empty();
            values.put(a.id(),value);if(a.valueTypes().size()==1)types.put(a.id(),a.valueTypes().getFirst());
        }
        var document=p.documents().candidates().getFirst();long id=document.type().toUpperCase(java.util.Locale.ROOT).contains("CERTIFICATE")?ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID:ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID;
        values.put(id,document.number()+":::"+document.date());
        return java.util.Optional.of(new Draft(p.tnved(),p.preflight().categoryTnved(),p.categoryId(),p.snapshot().title(),p.snapshot().brand(),values,types).withProductCategory(p.snapshot().metadata().get("source.category.name")));
    }
    /** Chain one accepted job per shop; queueing a whole batch must not discard all but its first SKU. */
    public CompletableFuture<Integer> submitBatchReviewed(Shop shop,List<BatchItem> approved,boolean newCard,boolean confirmed,BiConsumer<Status,String> callback) {
        return submitBatchReviewed(shop,approved,newCard,confirmed,callback,()->false);
    }
    public CompletableFuture<Integer> submitBatchReviewed(Shop shop,List<BatchItem> approved,boolean newCard,boolean confirmed,BiConsumer<Status,String> callback,java.util.function.BooleanSupplier cancelled) {
        if(!confirmed||approved==null||approved.isEmpty()||approved.size()>1000)throw new IllegalArgumentException("Reviewed batch confirmation required");
        var chain=CompletableFuture.completedFuture(0);
        for(var item:List.copyOf(approved)) {
            if(!item.ready())throw new IllegalArgumentException("Batch item needs review");
            chain=chain.thenComposeAsync(count->{
                if(cancelled.getAsBoolean())return CompletableFuture.completedFuture(count);
                boolean accepted=registerReviewed(shop,item.preparation(),item.draft(),true,newCard,callback);
                if(!accepted){notify(callback,Status.ERROR,"registration_already_running");return CompletableFuture.completedFuture(count);}
                var completion=COMPLETIONS.get(namespace(shop));
                return (completion==null?CompletableFuture.<Void>completedFuture(null):completion).thenApply(done->count+1);
            },executor);
        }
        return chain;
    }
    public Preparation withCategory(Shop shop,Preparation preparation,long categoryId)throws Exception {
        require(shop,preparation.snapshot().key());
        if(!preparation.owner.equals(ListingIdentity.fingerprint(shop))||preparation.session==null||preparation.preflight()==null||preparation.preflight().categories().stream().noneMatch(c->c.id()==categoryId))throw new IllegalArgumentException("foreign_category");
        checkSession(shop,preparation.session.scope(),preparation.session);LicenseRuntime.require(Permission.SELLER_API);
        var required=preparation.session.catalog().requiredAttributes(categoryId,preparation.session.token());
        checkSession(shop,preparation.session.scope(),preparation.session);
        return new Preparation(preparation.snapshot(),preparation.tnved(),preparation.preflight(),categoryId,required,preparation.documents(),suggestions(preparation.snapshot(),required),preparation.session,preparation.owner);
    }
    public boolean register(Shop shop,ProductKey key,Draft draft,boolean confirmed,boolean newCard,BiConsumer<Status,String> callback) {
        return register(shop,key,null,draft,confirmed,newCard,callback);
    }
    public boolean registerReviewed(Shop shop,Preparation preparation,Draft draft,boolean confirmed,boolean newCard,BiConsumer<Status,String> callback) {
        if(preparation==null||draft==null)return false;
        require(shop,preparation.snapshot().key());
        if(preparation.session==null||preparation.categoryId()!=draft.categoryId()||!preparation.owner.equals(ListingIdentity.fingerprint(shop)))throw new IllegalArgumentException("registration_review_changed");
        checkSession(shop,preparation.session.scope(),preparation.session);
        return register(shop,preparation.snapshot().key(),preparation,draft,confirmed,newCard,callback);
    }
    private boolean register(Shop selected,ProductKey key,Preparation preparation,Draft draft,boolean confirmed,boolean newCard,BiConsumer<Status,String> callback) {
        Shop shop=capture(selected);
        if(!confirmed||draft==null)return false;require(shop,key);LicenseRuntime.require(Permission.GTIN);
        return schedule(shop,()->{
            LicenseRuntime.require(Permission.GTIN);var snapshot=snapshots.read(shop,key);String sku=sku(snapshot);
            if(preparation!=null&&(!preparation.owner.equals(ListingIdentity.fingerprint(shop))||!preparation.snapshot().revision().equals(snapshot.revision())))throw new IllegalArgumentException("registration_review_changed");
            var scope=ZnackRegistrationScope.fromSettings(settings(shop));var session=preparation==null?catalogs.open(shop):preparation.session;checkSession(shop,scope,session);
            if(preparation!=null&&!preparation.session.scope().equals(scope))throw new ZnackRegistrationScope.ScopeChangedException();
            var preflight=session.catalog().preflight(draft.tnved());
            if(preflight.categories().stream().noneMatch(c->c.id()==draft.categoryId())||!preflight.categoryTnved().equals(draft.feedTnved()))throw new IllegalArgumentException("foreign_category");
            var documents=ZnackRegistrationDocuments.select(snapshot,settings(shop),LocalDate.now());
            Draft reviewed=validateDraft(draft.withProductCategory(snapshot.metadata().get("source.category.name")),session.catalog().requiredAttributes(draft.categoryId(),session.token()),documents);
            checkSession(shop,scope,session);LicenseRuntime.require(Permission.GTIN);
            var previous=repository.find(key);
            if(newCard&&previous!=null&&previous.status()==Status.ERROR){
                repository.guard(shop,key,scope);
                if(previous.feedId().isBlank()||!CatalogFeedback.confirmedRejection(session.catalog().progress(session.token(),previous.feedId(),previous.gtin())))throw new IllegalStateException("CATALOG_NEW_GTIN_REQUIRES_REJECTED_FEED");
            }
            boolean groupChange=previous!=null&&TnvedGroupChange.replaceable(previous.status())&&TnvedGroupChange.changed(TnvedGroupChange.payloadCode(previous.payload()),reviewed.tnved());
            if(previous!=null&&!newCard&&!groupChange&&!previous.feedId().isBlank()&&Set.of(Status.FEED_SUBMITTED,Status.PROCESSING,Status.READY_TO_SIGN).contains(previous.status())) {
                repository.guard(shop,key,scope);RegistrationSigningConsent.set(shop,key,previous.gtin(),reviewed.autoSign());poll(shop,key,session,reviewed.autoSign(),callback);return;
            }
            String active=snapshot.variants().stream().filter(ProductVariant::gtinVerified).map(ProductVariant::gtin).filter(g->g!=null&&!g.isBlank()).findFirst().orElse("");
            if(!repository.claim(shop,key,sku,active,scope,newCard,reviewed.tnved()))return;
            notify(callback,Status.CHECKING,"");
            synchronized(CatalogAllocationLock.MONITOR){
                repository.guard(shop,key,scope);LicenseRuntime.require(Permission.GTIN);
                if(!repository.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN))return;
                notify(callback,Status.ALLOCATING_GTIN,"");
                String gtin;
                try{repository.guard(shop,key,scope);LicenseRuntime.require(Permission.GTIN);gtin=newCard||groupChange?session.catalog().generateFreshOne(session.token(),repository.claimedGtins()):session.catalog().generateOne(session.token(),repository.claimedGtins());}
                catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException denied){repository.transition(key,Status.ALLOCATING_GTIN,Status.PREFLIGHT_ERROR);throw denied;}
                repository.guard(shop,key,scope);
                // Optional photos never substitute a marketplace thumbnail for a verified full-size image.
                var payload=ZnackNationalCatalogService.buildPayload(gtin,reviewed,"");repository.generated(key,gtin,payload.toString());RegistrationSigningConsent.set(shop,key,gtin,reviewed.autoSign());RegistrationMarketplaceConsent.set(shop,key,gtin,true);notify(callback,Status.GTIN_GENERATED,"");
            }
            submitPrepared(shop,key,session,callback);poll(shop,key,session,reviewed.autoSign(),callback);
        },key,callback);
    }
    public boolean resume(Shop selected,ProductKey key,BiConsumer<Status,String> callback) {return resume(selected,key,null,callback);}
    public boolean resume(Shop selected,ProductKey key,Boolean automaticSigning,BiConsumer<Status,String> callback) {
        Shop shop=capture(selected);
        require(shop,key);var row=repository.find(key);if(row==null||row.status()==Status.PUBLISHED||row.status()==Status.GTIN_REVIEW_REQUIRED||row.status()==Status.FEED_REVIEW_REQUIRED||row.status()==Status.ALLOCATING_GTIN||row.status()==Status.FEED_SUBMITTING)return false;
        if(row.feedId().isBlank()&&row.status()!=Status.READY_TO_SUBMIT)return false;
        if(row.feedId().isBlank())LicenseRuntime.require(Permission.GTIN);
        return schedule(shop,()->{
            var scope=scope(row);repository.guard(shop,key,scope);var session=catalogs.open(shop);checkSession(shop,scope,session);
            if(row.status()==Status.ERROR&&!row.feedId().isBlank()&&!Set.of("SENDING","RECONCILE_REQUIRED").contains(row.signatureState())){
                var rejected=session.catalog().progress(session.token(),row.feedId(),row.gtin());
                var payload=com.google.gson.JsonParser.parseString(row.payload()).getAsJsonObject();
                var packing=CatalogPackingPolicy.correction(rejected,payload.has("is_kit")&&payload.get("is_kit").getAsBoolean());
                if(CatalogFeedback.confirmedRejection(rejected)&&repository.retryRejected(shop,key,row,scope,packing))submitPrepared(shop,key,session,callback);
            }else if(row.feedId().isBlank())submitPrepared(shop,key,session,callback);
            if(automaticSigning!=null)RegistrationSigningConsent.set(shop,key,row.gtin(),automaticSigning);
            poll(shop,key,session,RegistrationSigningConsent.allowed(shop,key,row.gtin()),callback);
        },key,callback);
    }
    public boolean signApproved(Shop selected,List<ProductKey> keys,boolean confirmed,BiConsumer<Status,String> callback) {
        Shop shop=capture(selected);
        if(!confirmed||keys==null||keys.isEmpty()||keys.size()>10_000)return false;var frozenKeys=List.copyOf(new LinkedHashSet<>(keys));for(var key:frozenKeys)require(shop,key);LicenseRuntime.require(Permission.GTIN);
        return schedule(shop,()->{
            var scope=ZnackRegistrationScope.fromSettings(settings(shop));var session=catalogs.open(shop);checkSession(shop,scope,session);
            for(var key:frozenKeys){
                if(Thread.currentThread().isInterrupted())return;var row=repository.find(key);if(row==null||row.feedId().isBlank()||row.status()==Status.PUBLISHED)continue;
                try{repository.guard(shop,key,scope);LicenseRuntime.require(Permission.GTIN);RegistrationSigningConsent.set(shop,key,row.gtin(),true);poll(shop,key,session,true,callback);}
                catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException|ZnackRegistrationScope.ScopeChangedException stopped){throw stopped;}
                catch(Exception failure){if(failure instanceof InterruptedException){Thread.currentThread().interrupt();return;}fail(key,callback,failure);}
            }
        },null,callback);
    }
    public boolean complete(Shop selected,ProductKey key,boolean confirmed,BiConsumer<Status,String> callback) {
        Shop shop=capture(selected);
        if(!confirmed)return false;require(shop,key);LicenseRuntime.require(Permission.GTIN);var row=repository.find(key);if(row==null||row.status()!=Status.PUBLISHED)return false;
        return schedule(shop,()->{
            var scope=scope(row);repository.guard(shop,key,scope);LicenseRuntime.require(Permission.GTIN);
            com.vncode.app.features.gtinsync.RegisteredGtinProof.requireCurrent(shop,row.gtin());
            if(!completion.present(shop,key,row.sku(),row.gtin()))throw new IllegalStateException("candidate_not_on_marketplace");
            repository.guard(shop,key,scope);LicenseRuntime.require(Permission.GTIN);
            if(!repository.complete(shop,key,scope,row.gtin()))throw new IllegalStateException("registration_changed");notify(callback,Status.PUBLISHED,"");
        },key,callback);
    }
    /** Background polling never resubmits feeds; exact-card consent may authorize signing after moderation. */
    public boolean refreshStatus(Shop selected,ProductKey key)throws Exception {
        Shop shop=capture(selected);require(shop,key);String operation=namespace(shop);if(!RUNNING.add(operation))return false;
        try {
        var row=repository.find(key);if(row==null||row.feedId().isBlank()||!(Set.of(Status.FEED_SUBMITTED,Status.PROCESSING,Status.READY_TO_SIGN,Status.SIGNING).contains(row.status())||row.status()==Status.ERROR&&"CATALOG_CARD_NOT_CREATED_AFTER_WAIT".equals(row.error())))return false;
        var expected=scope(row);repository.guard(shop,key,expected);var session=catalogs.open(shop);checkSession(shop,expected,session);
        boolean auto=RegistrationSigningConsent.allowed(shop,key,row.gtin());
        if(auto)try{LicenseRuntime.require(Permission.GTIN);}catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException paused){auto=false;}
        poll(shop,key,session,auto,null);return repository.find(key).status()==Status.PUBLISHED;
        }finally{RUNNING.remove(operation);}
    }
    private void submitPrepared(Shop shop,ProductKey key,CatalogSession session,BiConsumer<Status,String> callback)throws Exception {
        repository.guard(shop,key,session.scope());LicenseRuntime.require(Permission.GTIN);var row=repository.find(key);
        if(row.status()!=Status.READY_TO_SUBMIT||!row.feedId().isBlank())return;
        if(!repository.transition(key,Status.READY_TO_SUBMIT,Status.FEED_SUBMITTING))return;
        notify(callback,Status.FEED_SUBMITTING,"");
        try{repository.guard(shop,key,session.scope());LicenseRuntime.require(Permission.GTIN);}
        catch(RuntimeException knownUnsent){repository.transition(key,Status.FEED_SUBMITTING,Status.READY_TO_SUBMIT);throw knownUnsent;}
        String feed;
        try{feed=session.catalog().submit(session.token(),JsonParser.parseString(row.payload()).getAsJsonObject());}
        catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException denied){repository.transition(key,Status.FEED_SUBMITTING,Status.READY_TO_SUBMIT);throw denied;}
        repository.submitted(key,feed);notify(callback,Status.FEED_SUBMITTED,"");
        new com.vncode.app.features.sync.SyncStreamStore().request(shop,"ZNACK");
    }
    private void poll(Shop shop,ProductKey key,CatalogSession session,boolean sign,BiConsumer<Status,String> callback)throws Exception {
        repository.guard(shop,key,session.scope());var row=repository.find(key);if(row.feedId().isBlank())return;
        var progress=session.catalog().progress(session.token(),row.feedId(),row.gtin());repository.guard(shop,key,session.scope());
        if(row.goodId()!=null&&progress.goodId()!=null&&!row.goodId().equals(progress.goodId()))throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
        if(progress.signed()){publish(shop,key,session,progress.goodId(),callback);return;}
        if(row.status()==Status.SIGNING||Set.of("SENDING","RECONCILE_REQUIRED").contains(row.signatureState())){notify(callback,Status.SIGNING,"readback_required");return;}
        if(progress.failed()){repository.progress(key,Status.ERROR,progress.goodId(),CatalogFeedback.rejection(progress));notify(callback,Status.ERROR,CatalogFeedback.rejection(progress));return;}
        if(!progress.readyToSign()){
            if(java.time.Instant.now().isAfter(repository.submissionStarted(key).plus(java.time.Duration.ofMinutes(5)))&&!session.catalog().cardExists(session.token(),row.gtin(),progress.goodId())){
                repository.progress(key,Status.ERROR,progress.goodId(),"CATALOG_CARD_NOT_CREATED_AFTER_WAIT");notify(callback,Status.ERROR,"CATALOG_CARD_NOT_CREATED_AFTER_WAIT");
            }else{repository.progress(key,Status.PROCESSING,progress.goodId());notify(callback,Status.PROCESSING,"");}
            return;
        }
        repository.progress(key,Status.READY_TO_SIGN,progress.goodId());notify(callback,Status.READY_TO_SIGN,"");if(!sign)return;
        LicenseRuntime.require(Permission.GTIN);var document=session.catalog().reviewSigning(session.token(),row.gtin());
        if(progress.goodId()!=null&&progress.goodId()!=document.goodId())throw new IllegalStateException("catalog_identity_changed");
        repository.prepareSignature(key,document);repository.guard(shop,key,session.scope());LicenseRuntime.require(Permission.GTIN);
        if(!repository.claimSignature(key,document.sha256()))return;notify(callback,Status.SIGNING,"");
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            session.catalog().sendReviewedSigning(session.token(),document,()->{
                repository.guard(shop,key,session.scope());LicenseRuntime.require(Permission.GTIN);
                var current=session.catalog().progress(session.token(),row.feedId(),row.gtin());var fresh=session.catalog().reviewSigning(session.token(),row.gtin());
                if(!current.readyToSign()||current.failed()||fresh.goodId()!=document.goodId()||!fresh.sha256().equals(document.sha256()))throw new IllegalStateException("signing_document_changed");
                repository.guard(shop,key,session.scope());LicenseRuntime.require(Permission.GTIN);
                dispatched.set(true);
            });
        }catch(Exception failure){
            if(!dispatched.get()){repository.releaseSignature(key,document.sha256());notify(callback,Status.READY_TO_SIGN,"signing_not_sent");return;}
            repository.uncertain(key,Status.SIGNING);
        }
        repository.guard(shop,key,session.scope());var after=session.catalog().progress(session.token(),row.feedId(),row.gtin());
        if(after.signed()&&after.goodId()!=null&&after.goodId()==document.goodId())publish(shop,key,session,after.goodId(),callback);else notify(callback,Status.SIGNING,"readback_required");
    }
    private void publish(Shop shop,ProductKey key,CatalogSession session,Long goodId,BiConsumer<Status,String> callback) {
        if(goodId==null||goodId<=0)throw new IllegalStateException("catalog_identity_changed");repository.guard(shop,key,session.scope());repository.publish(shop,key,session.scope(),goodId);notify(callback,Status.PUBLISHED,"");
    }
    @FunctionalInterface private interface Action {void run()throws Exception;}
    private boolean schedule(Shop shop,Action action,ProductKey key,BiConsumer<Status,String> callback) {
        String namespace=namespace(shop);if(!RUNNING.add(namespace))return false;
        var completion=new CompletableFuture<Void>();COMPLETIONS.put(namespace,completion);
        try {executor.execute(()->{try{action.run();}catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();fail(key,callback,failure);}finally{RUNNING.remove(namespace);completion.complete(null);COMPLETIONS.remove(namespace,completion);}});return true;}
        catch(RejectedExecutionException full){RUNNING.remove(namespace);COMPLETIONS.remove(namespace,completion);completion.complete(null);return false;}
    }
    private void fail(ProductKey key,BiConsumer<Status,String> callback,Throwable failure) {
        String error=CatalogFeedback.display(com.vncode.app.shared.FriendlyErrorService.format(failure));
        if(key==null){notify(callback,Status.ERROR,error);return;}
        var row=repository.find(key);if(row==null){notify(callback,Status.PREFLIGHT_ERROR,error);return;}
        if(row.status()==Status.PUBLISHED){notify(callback,Status.ERROR,error);return;}
        boolean documentPending=CatalogFeedback.notFound(failure)&&!row.feedId().isBlank()
                &&!Set.of("SENDING","RECONCILE_REQUIRED").contains(row.signatureState());
        Status state=documentPending?Status.PROCESSING:switch(row.status()){
            case ALLOCATING_GTIN->Status.GTIN_REVIEW_REQUIRED;case FEED_SUBMITTING->Status.FEED_REVIEW_REQUIRED;
            case SIGNING->Status.SIGNING;case CHECKING->Status.PREFLIGHT_ERROR;default->row.status();};
        if(documentPending)error="CATALOG_SIGNING_DOCUMENT_PENDING";
        repository.uncertain(key,state,error);notify(callback,state,error);
    }
    public boolean isRunning(Shop shop){return shop!=null&&RUNNING.contains(namespace(shop));}
    public static boolean hasActiveJobs(){return !RUNNING.isEmpty();}
    private static String namespace(Shop shop){return AppPaths.appDataDir().toAbsolutePath().normalize()+":"+shop.getId();}
    private static ZnackModels.Settings settings(Shop shop){return new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();}
    private static ZnackRegistrationScope scope(OzonCardRegistrationRepository.Row row){return new Gson().fromJson(row.scopeJson(),ZnackRegistrationScope.class);}
    private static void checkSession(Shop shop,ZnackRegistrationScope scope,CatalogSession session){ListingIdentity.require(shop);scope.assertCurrent(shop);if(session.scope()==null||!scope.equals(session.scope()))throw new ZnackRegistrationScope.ScopeChangedException();}
    private static void require(Shop shop,ProductKey key){ListingIdentity.require(shop,key);if(key.marketplace()!=Marketplace.OZON||key.variantId().isBlank())throw new IllegalArgumentException("shop_mismatch");}
    private static Shop capture(Shop shop){if(shop==null)throw new IllegalArgumentException("shop_mismatch");return new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());}
    private static String sku(ProductSnapshot snapshot){String sku=snapshot.metadata().getOrDefault("sku","");if(!sku.matches("[1-9][0-9]{0,18}"))throw new IllegalStateException("ozon_sku_unavailable");return sku;}
    private static String tnved(ProductSnapshot snapshot){var values=new LinkedHashSet<String>();for(var a:snapshot.attributes())if(ProductFieldNames.matches("tnved",a.name()))for(var v:a.values()){var code=ProductFieldNames.tnvedCode(v);if(!code.isBlank())values.add(code);}return values.size()==1?values.getFirst():"";}
    private static Map<Long,String> suggestions(ProductSnapshot snapshot,List<Attribute> required){var out=new LinkedHashMap<Long,String>();for(var target:required){var values=new LinkedHashSet<String>();for(var source:snapshot.attributes())if(normalize(target.name()).equals(normalize(source.name()))&&source.values().size()==1&&!source.values().getFirst().isBlank())values.add(source.values().getFirst());if(values.size()==1){String value=values.getFirst();if(!target.presetOnly()||target.presets().contains(value))out.put(target.id(),value);}}return Map.copyOf(out);}
    private static Draft validateDraft(Draft draft,List<Attribute> required,ZnackRegistrationDocuments.Selection documents){
        if(draft.goodName()==null||draft.goodName().isBlank()||draft.brand()==null||draft.brand().isBlank())throw new IllegalArgumentException("invalid_catalog_name");
        long declaration=ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,certificate=ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID;
        var selected=new ArrayList<Long>();for(long id:List.of(declaration,certificate)){String value=draft.attributes().get(id);if(value!=null&&!value.isBlank())selected.add(id);}
        if(selected.size()!=1)throw new IllegalArgumentException("goods_document_required");
        long documentId=selected.getFirst();String documentValue=draft.attributes().get(documentId);
        boolean eligible=documents.candidates().stream().anyMatch(document->
                (document.type().equals("CONFORMITY_CERTIFICATE")?certificate:declaration)==documentId
                        && (document.number()+":::"+document.date()).equals(documentValue));
        if(!eligible)throw new IllegalArgumentException("goods_document_changed");
        var types=new LinkedHashMap<Long,String>(draft.attributeTypes());
        for(var attribute:required){
            if(attribute.id()==3959L||attribute.id()==13933L&&draft.feedTnved().length()==10)continue;
            if((attribute.id()==declaration||attribute.id()==certificate)&&attribute.id()!=documentId)continue;
            String value=draft.attributes().get(attribute.id());if(value==null||value.isBlank())throw new IllegalArgumentException("required_attribute_missing");
            if(attribute.presetOnly()&&!attribute.presets().contains(value))throw new IllegalArgumentException("invalid_attribute_value");
            String type=types.get(attribute.id());if(type==null){if(attribute.valueTypes().size()==1)type=attribute.valueTypes().getFirst();else if(attribute.valueTypes().isEmpty())type="";else throw new IllegalArgumentException("attribute_type_required");}
            if(!attribute.valueTypes().isEmpty()&&!attribute.valueTypes().contains(type))throw new IllegalArgumentException("invalid_attribute_type");types.put(attribute.id(),type);
        }
        return new Draft(draft.tnved(),draft.feedTnved(),draft.categoryId(),draft.goodName(),draft.brand(),draft.attributes(),types,draft.autoSign(),draft.isKit());
    }
    private static String normalize(String value){return value==null?"":value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
    private static boolean candidatePresent(Shop shop,ProductKey key,String sku,String gtin)throws Exception{var adapter=new OzonGtinAdapter(shop);var source=new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(shop.getId(),Marketplace.OZON,key.cardId(),key.variantId());return adapter.hasBarcode(source,sku,gtin);}
    private static void notify(BiConsumer<Status,String> callback,Status status,String detail){if(callback!=null)try{callback.accept(status,detail);}catch(RuntimeException ignored){/* UI does not decide persisted outcomes. */}}
}
