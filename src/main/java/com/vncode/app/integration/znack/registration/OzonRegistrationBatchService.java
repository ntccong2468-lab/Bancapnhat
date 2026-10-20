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
    public enum State { QUEUED,RUNNING,WAITING,COMPLETED,FAILED }
    public record Progress(String runId,int total,int submitted,int failed,int remaining,String currentArticle,String currentSize,String detail,State state,String lastSubmittedArticle,String lastSubmittedSize) {}
    private static final ScheduledExecutorService WORKERS=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"ozon-registration-batch");t.setDaemon(true);return t;});
    private static final Set<String> ACTIVE=ConcurrentHashMap.newKeySet();
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
            if(prior!=null&&!Set.of("COMPLETED","FAILED").contains(prior.state()))id=prior.id();
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
        return new Progress(batch.id(),c.total(),c.submitted(),c.failed(),c.remaining(),next==null?"":next.key().variantId(),"",batch.detail(),State.valueOf(batch.state()),last==null?"":last.key().variantId(),"");
    }
    /** Called by the existing sync monitor at startup and after network recovery. */
    public boolean resumePending(Shop selected) {
        if(selected==null||selected.getMarketplace()!=Marketplace.OZON)return false;
        var batch=batches.latest(selected);if(batch==null||Set.of("COMPLETED","FAILED").contains(batch.state()))return false;
        Shop shop=capture(selected);String namespace=namespace(shop);
        if(ACTIVE.add(namespace)){var directory=AppPaths.appDataDir().toAbsolutePath().normalize();WORKERS.execute(()->run(shop,namespace,directory));}
        return true;
    }
    public boolean ownsPending(Shop shop,ProductKey key){return batches.ownsPending(shop,key);}
    public static boolean hasPendingJobs(){return !ACTIVE.isEmpty();}
    private void run(Shop shop,String namespace,java.nio.file.Path directory) {
        boolean deferred=false;OzonRegistrationBatchRepository.Batch batch=null;
        try(var dataScope=com.vncode.app.features.sync.SyncDataScope.enter(directory);var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(true)) {
            com.vncode.app.features.sync.SyncDataScope.requireCurrent();
            batch=batches.latest(shop);if(batch==null)return;
            if(!batch.owner().equals(ListingIdentity.fingerprint(shop))||!batch.scope().equals(new Gson().toJson(scope(shop)))){failPending(shop,batch.id(),"SOURCE_CONFIGURATION_CHANGED");return;}
            for(;;) {
                var item=batches.next(batch);
                if(item==null){batches.state(batch.id(),"COMPLETED","");return;}
                if(workflow.isRunning(shop)){defer(shop,namespace,directory,batch.id(),"REGISTRATION_BUSY",2);deferred=true;return;}
                batches.state(batch.id(),"RUNNING","");item(shop,item,"RUNNING","");
                try {
                    new RegistrationSources().require(shop);scope(shop).assertCurrent(shop);ListingIdentity.require(shop);
                    var row=cards.find(item.key());
                    if(row!=null&&!row.feedId().isBlank()) {
                        cards.guard(shop,item.key(),scope(shop));item(shop,item,"SUBMITTED","");continue;
                    }
                    if(row!=null&&Set.of(Status.GTIN_REVIEW_REQUIRED,Status.FEED_REVIEW_REQUIRED,Status.ALLOCATING_GTIN,Status.FEED_SUBMITTING).contains(row.status())) {
                        // A crash during a write has an unknown remote outcome. Never request another GTIN/feed.
                        if(row.status()==Status.ALLOCATING_GTIN)cards.uncertain(item.key(),Status.GTIN_REVIEW_REQUIRED,"GTIN_ALLOCATION_READBACK_REQUIRED");
                        if(row.status()==Status.FEED_SUBMITTING)cards.uncertain(item.key(),Status.FEED_REVIEW_REQUIRED,"CATALOG_FEED_READBACK_REQUIRED");
                        item(shop,item,"FAILED",row.status().name());continue;
                    }
                    boolean accepted;
                    if(row!=null&&row.status()==Status.READY_TO_SUBMIT)accepted=workflow.resumeForBatch(shop,item.key(),batch.autoSign());
                    else {
                        if(row!=null&&row.status()==Status.CHECKING)cards.transition(item.key(),Status.CHECKING,Status.PREFLIGHT_ERROR);
                        var prepared=workflow.prepare(shop,item.key());
                        var draft=OzonCardRegistrationWorkflow.suggestedDraft(prepared);
                        if(draft.isEmpty()||prepared.documents().automatic()==null){item(shop,item,"FAILED",draft.isEmpty()?"REGISTRATION_MISSING_REQUIRED_FIELDS":"REGISTRATION_DOCUMENT_REVIEW_REQUIRED");continue;}
                        accepted=workflow.registerReviewedForBatch(shop,prepared,draft.get().withAutoSign(batch.autoSign()),null);
                    }
                    if(!accepted){item(shop,item,"PENDING","");defer(shop,namespace,directory,batch.id(),"REGISTRATION_BUSY",2);deferred=true;return;}
                    workflow.completion(shop).toCompletableFuture().join();
                    row=cards.find(item.key());
                    if(row!=null&&!row.feedId().isBlank())item(shop,item,"SUBMITTED","");
                    else if(row!=null&&row.status()==Status.READY_TO_SUBMIT){item(shop,item,"PENDING",row.error());defer(shop,namespace,directory,batch.id(),"NETWORK_RETRY",10);deferred=true;return;}
                    else if((row==null||row.status()==Status.PREFLIGHT_ERROR)&&workflow.failure(shop)!=null&&!SyncFailure.of(workflow.failure(shop),0).actionRequired()){
                        var reason=SyncFailure.of(workflow.failure(shop),0);item(shop,item,"PENDING",reason.code());defer(shop,namespace,directory,batch.id(),reason.code(),Math.max(2,reason.delay().toSeconds()));deferred=true;return;
                    }
                    else item(shop,item,"FAILED",row==null?"REGISTRATION_NOT_SUBMITTED":row.error().isBlank()?row.status().name():row.error());
                }catch(Exception failure) {
                    if(failure instanceof ZnackRegistrationScope.ScopeChangedException){failPending(shop,batch.id(),"SOURCE_CONFIGURATION_CHANGED");return;}
                    var reason=SyncFailure.of(failure,0);
                    if(!reason.actionRequired()) {item(shop,item,"PENDING",reason.code());defer(shop,namespace,directory,batch.id(),reason.code(),Math.max(2,reason.delay().toSeconds()));deferred=true;return;}
                    item(shop,item,"FAILED",reason.code());
                }
            }
        }catch(ZnackRegistrationScope.ScopeChangedException changed){
            if(batch!=null&&directory.equals(AppPaths.appDataDir().toAbsolutePath().normalize()))failPending(shop,batch.id(),"SOURCE_CONFIGURATION_CHANGED");
        }finally {if(!deferred)ACTIVE.remove(namespace);}
    }
    private void defer(Shop shop,String namespace,java.nio.file.Path directory,String run,String detail,long seconds){batches.state(run,"WAITING",detail);WORKERS.schedule(()->run(shop,namespace,directory),Math.min(300,seconds),TimeUnit.SECONDS);}
    private void failPending(Shop shop,String run,String detail){batches.failPending(run,detail);OzonRegistrationAttemptReports.failPending(shop,run,detail);}
    private void item(Shop shop,OzonRegistrationBatchRepository.Item item,String state,String detail){batches.item(item,state,detail);OzonRegistrationAttemptReports.observe(shop,item.run(),item.key(),state,detail);}
    private static ZnackRegistrationScope scope(Shop shop){return ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());}
    private static Shop capture(Shop shop){if(shop==null||shop.getMarketplace()!=Marketplace.OZON)throw new IllegalArgumentException("shop_mismatch");ListingIdentity.require(shop);return new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());}
    private static String namespace(Shop shop){return AppPaths.appDataDir().toAbsolutePath().normalize()+":"+shop.getId();}
}
