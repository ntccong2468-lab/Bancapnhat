package com.vncode.app.features.gtinsync;

import com.vncode.app.features.catalogsync.CatalogSyncModels;
import com.vncode.app.features.catalogsync.CatalogSyncService;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.sync.SyncFailure;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.WbProductSnapshotReader;
import com.vncode.app.integration.znack.registration.OzonExistingCardLinkService;
import com.vncode.app.integration.znack.registration.RegistrationReviewedValues;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.function.Consumer;

/** Applies seller-reviewed WB→Ozon associations through the existing GTIN-only queue.
 * A reviewed correspondence is not itself proof of an Ozon write. */
public final class WbOzonGtinDispatchService {
    private static final int MAX_SELECTION=100_000;
    private static final int PLAN_SIZE=100;
    public enum State { CHECKING, VERIFIED, WAITING, FAILED }
    public record Report(WbOzonLinkRepository.Link link,State state,String reason,String jobId) {
        public Report { Objects.requireNonNull(link); Objects.requireNonNull(state); reason=Objects.requireNonNullElse(reason,""); jobId=Objects.requireNonNullElse(jobId,""); }
    }
    interface LinkStore {
        Optional<WbOzonLinkRepository.Link> find(Shop wb,Shop ozon,String product,String offer);
        Optional<String> findDispatch(Shop wb,Shop ozon,WbOzonLinkRepository.Link link);
        List<WbOzonLinkRepository.Link> linksForDispatch(Shop wb,Shop ozon,String job);
        void confirmAndSaveDispatch(Shop wb,Shop ozon,List<WbOzonLinkRepository.Link> links,String job);
    }
    interface CatalogPlan {
        CatalogSyncModels.Job preview(Shop shop,List<CatalogSyncModels.Selection> selection,CatalogSyncModels.Options options)throws Exception;
        CatalogSyncModels.Job runCross(Shop wb,Shop ozon,String id)throws Exception;
        CatalogSyncModels.Job read(Shop shop,String id);
    }
    @FunctionalInterface interface WbVerifier {void verify(Shop wb,WbOzonLinkRepository.Link link)throws Exception;}
    @FunctionalInterface interface LinkApprover {void approve(Shop ozon,GtinSyncModels.ProductKey key,WbOzonLinkRepository.Link link)throws Exception;}
    private final LinkStore links;private final CatalogPlan catalog;private final WbVerifier wbVerifier;private final LinkApprover linkApprover;
    public WbOzonGtinDispatchService(){
        var repository=new WbOzonLinkRepository();var service=new CatalogSyncService();var review=new OzonExistingCardLinkService();
        this.links=new LinkStore(){
            public Optional<WbOzonLinkRepository.Link> find(Shop w,Shop o,String p,String v){return repository.find(w,o,p,v);}
            public Optional<String> findDispatch(Shop w,Shop o,WbOzonLinkRepository.Link l){return repository.findDispatch(w,o,l);}
            public List<WbOzonLinkRepository.Link> linksForDispatch(Shop w,Shop o,String j){return repository.linksForDispatch(w,o,j);}
            public void confirmAndSaveDispatch(Shop w,Shop o,List<WbOzonLinkRepository.Link> l,String j){repository.confirmAndSaveDispatch(w,o,l,j);}
        };
        this.catalog=new CatalogPlan(){
            public CatalogSyncModels.Job preview(Shop s,List<CatalogSyncModels.Selection> l,CatalogSyncModels.Options o)throws Exception{return service.preview(s,l,o);}
            public CatalogSyncModels.Job runCross(Shop w,Shop o,String id)throws Exception{return service.runReviewedWbOzon(w,o,id);}
            public CatalogSyncModels.Job read(Shop s,String id){return service.read(s,id);}
        };
        this.wbVerifier=WbOzonGtinDispatchService::requireCurrentWb;
        this.linkApprover=(shop,key,link)->{
            var observation=review.read(shop,key,link.gtin());
            if(!observation.sku().equals(link.ozonSku())||observation.goodId()!=link.goodId()
                    ||!observation.owner().equals(link.ozonOwner())||!observation.source().equals(link.catalogSource())
                    ||!observation.revision().equals(link.ozonRevision())||!RegisteredGtinProof.sameCode(observation.gtin(),link.gtin()))
                throw new IllegalStateException("WB_OZON_LINK_REVIEW_CHANGED");
            review.approve(shop,observation);
        };
    }
    WbOzonGtinDispatchService(LinkStore links,CatalogPlan catalog,WbVerifier wbVerifier,LinkApprover linkApprover){
        this.links=Objects.requireNonNull(links);this.catalog=Objects.requireNonNull(catalog);
        this.wbVerifier=Objects.requireNonNull(wbVerifier);this.linkApprover=Objects.requireNonNull(linkApprover);
    }

    /** The caller must obtain seller confirmation of these exact rows before calling this method. */
    public List<Report> apply(Shop wb,Shop ozon,List<WbOzonLinkRepository.Link> sellerApproved,Consumer<Report> progress)throws Exception {
        if(sellerApproved==null||sellerApproved.isEmpty()||sellerApproved.size()>MAX_SELECTION)throw new IllegalArgumentException("WB_OZON_DISPATCH_SELECTION_LIMIT");
        List<WbOzonLinkRepository.Link> selected=List.copyOf(sellerApproved);Consumer<Report> sink=progress==null?r->{}:progress;
        var reports=new LinkedHashMap<GtinSyncModels.ProductKey,Report>();var ready=new LinkedHashMap<GtinSyncModels.ProductKey,WbOzonLinkRepository.Link>();
        var existingJobs=new LinkedHashMap<String,List<WbOzonLinkRepository.Link>>();var selectedGtins=new HashSet<String>();
        var selectedTargets=new HashSet<GtinSyncModels.ProductKey>();
        for(var link:selected)if(!selectedTargets.add(target(link))||!selectedGtins.add(RegisteredGtinProof.canonicalCode(link.gtin())))throw new IllegalArgumentException("WB_OZON_DISPATCH_DUPLICATE");
        boolean preflightDeferred=false;
        for(var link:selected){
            var key=target(link);
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("ZNACK_SYNC_CANCELLED");
            if(preflightDeferred){var result=new Report(link,State.WAITING,"API_COOLDOWN","");reports.put(key,result);sink.accept(result);continue;}
            sink.accept(new Report(link,State.CHECKING,"",""));
            try {
                var saved=links.find(wb,ozon,link.ozonProductId(),link.ozonOfferId()).orElseThrow(()->new IllegalStateException("WB_OZON_LINK_REVIEW_REQUIRED"));
                if(!saved.equals(link))throw new IllegalStateException("WB_OZON_LINK_REVIEW_CHANGED");
                var previous=links.findDispatch(wb,ozon,link);
                if(previous.isPresent()){existingJobs.computeIfAbsent(previous.get(),unused->new ArrayList<>()).add(link);continue;}
                wbVerifier.verify(wb,link);
                // approve rereads the exact Catalog card and Ozon SKU. It never writes to the marketplace.
                linkApprover.approve(ozon,key,link);
                wbVerifier.verify(wb,link);
                ready.put(key,link);
            }catch(Exception failure){var result=failure(link,failure,"");reports.put(key,result);sink.accept(result);if(isDeferred(result))preflightDeferred=true;}
        }
        if(preflightDeferred){
            for(var entry:existingJobs.entrySet())for(var link:entry.getValue()){var result=new Report(link,State.WAITING,"API_COOLDOWN",entry.getKey());reports.put(target(link),result);sink.accept(result);}
            for(var entry:ready.entrySet()){var result=new Report(entry.getValue(),State.WAITING,"API_COOLDOWN","");reports.put(entry.getKey(),result);sink.accept(result);}
            return selected.stream().map(link->reports.get(target(link))).filter(Objects::nonNull).toList();
        }
        for(var entry:existingJobs.entrySet()){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("ZNACK_SYNC_CANCELLED");
            try {
                links.linksForDispatch(wb,ozon,entry.getKey());
                var before=catalog.read(ozon,entry.getKey());
                if(before.rows().stream().anyMatch(row->row.state()!=CatalogSyncModels.State.SUCCEEDED&&row.state()!=CatalogSyncModels.State.BLOCKED)){
                    before=catalog.runCross(wb,ozon,entry.getKey());
                }
                reportJob(entry.getValue(),before,reports,sink);
            }
            catch(Exception e){for(var link:entry.getValue()){var r=failure(link,e,entry.getKey());reports.put(target(link),r);sink.accept(r);}}
        }
        var pending=new ArrayList<>(ready.entrySet());boolean deferred=false;
        for(int offset=0;offset<pending.size();offset+=PLAN_SIZE){
            var part=pending.subList(offset,Math.min(offset+PLAN_SIZE,pending.size()));
            if(deferred){for(var entry:part){var r=new Report(entry.getValue(),State.WAITING,"API_COOLDOWN","");reports.put(entry.getKey(),r);sink.accept(r);}continue;}
            var selections=part.stream().map(e->new CatalogSyncModels.Selection(e.getKey(),e.getValue().gtin())).toList();
            CatalogSyncModels.Job job=null;boolean checkpointed=false;
            try{
                job=catalog.preview(ozon,selections,new CatalogSyncModels.Options(Set.of(CatalogSyncModels.Field.GTIN),Map.of()));
                if(job.readyCount()>0){
                    links.confirmAndSaveDispatch(wb,ozon,part.stream().map(Map.Entry::getValue).toList(),job.id());
                    checkpointed=true;
                    job=catalog.runCross(wb,ozon,job.id());
                }
                reportJob(part.stream().map(Map.Entry::getValue).toList(),job,reports,sink);
                deferred=part.stream().map(e->reports.get(e.getKey())).filter(Objects::nonNull).anyMatch(WbOzonGtinDispatchService::isDeferred);
            }catch(Exception failure){String id=checkpointed?job.id():"";for(var entry:part){var r=failure(entry.getValue(),failure,id);reports.put(entry.getKey(),r);sink.accept(r);if(isDeferred(r))deferred=true;}}
        }
        return selected.stream().map(link->reports.get(target(link))).filter(Objects::nonNull).toList();
    }
    private static GtinSyncModels.ProductKey target(WbOzonLinkRepository.Link link){return new GtinSyncModels.ProductKey(link.ozonShopId(),Marketplace.OZON,link.ozonProductId(),link.ozonOfferId());}
    private static void reportJob(List<WbOzonLinkRepository.Link> links,CatalogSyncModels.Job job,Map<GtinSyncModels.ProductKey,Report> out,Consumer<Report> sink){
        var rows=new HashMap<GtinSyncModels.ProductKey,CatalogSyncModels.Row>();for(var row:job.rows())rows.put(row.key(),row);
        for(var link:links){var row=rows.get(target(link));State state=row==null?State.WAITING:switch(row.state()){
            case SUCCEEDED->State.VERIFIED;case BLOCKED->State.FAILED;default->State.WAITING;};
            String issue=row==null?"WB_OZON_RESULT_UNAVAILABLE":Objects.toString(row.issue(),"");
            var result=new Report(link,state,issue,job.id());out.put(target(link),result);sink.accept(result);
        }
    }
    private static Report failure(WbOzonLinkRepository.Link link,Exception error,String job){
        var failure=SyncFailure.of(error,0);boolean retry=error instanceof IOException||failure.code().contains("RATE_LIMIT")||failure.code().equals("API_COOLDOWN")||failure.code().startsWith("NETWORK_")||!job.isBlank();
        return new Report(link,retry?State.WAITING:State.FAILED,failure.code(),job);
    }
    private static boolean isDeferred(Report report){return report.state()==State.WAITING&&(report.reason().contains("RATE_LIMIT")||report.reason().equals("API_COOLDOWN")||report.reason().startsWith("NETWORK_"));}
    /** Recheck the reviewed WB variant, not just the seller-facing article or title. */
    static void requireCurrentWb(Shop wb,WbOzonLinkRepository.Link link)throws IOException{
        if(wb==null||wb.getId()!=link.wbShopId()||wb.getMarketplace()!=Marketplace.WILDBERRIES)throw new SecurityException("WB_OZON_SHOP_MISMATCH");
        var key=new ProductKey(wb.getId(),Marketplace.WILDBERRIES,link.wbNmId(),link.wbChrtId());
        ListingIdentity.require(wb,key);
        var snapshot=new WbProductSnapshotReader().read(wb,key);
        requireWbSnapshot(wb,link,snapshot);
    }
    private static void requireWbSnapshot(Shop wb,WbOzonLinkRepository.Link link,com.vncode.app.features.products.ProductSnapshot source){
        var key=new ProductKey(wb.getId(),Marketplace.WILDBERRIES,link.wbNmId(),link.wbChrtId());
        if(source.key().shopId()!=key.shopId()||source.key().marketplace()!=key.marketplace()||!source.key().cardId().equals(key.cardId()))throw new IllegalStateException("WB_OZON_WB_PRODUCT_CHANGED");
        var snapshot=source.key().equals(key)?source:new com.vncode.app.features.products.ProductSnapshot(key,source.revision(),source.title(),source.description(),source.brand(),source.vendorArticle(),source.categoryId(),source.media(),source.attributes(),source.variants(),source.dimensions(),source.documents(),source.checkedAt(),source.metadata());
        if(!link.wbRevision().equals(RegistrationReviewedValues.productRevision(snapshot)))throw new IllegalStateException("WB_OZON_WB_PRODUCT_CHANGED");
        var variants=snapshot.variants().stream().filter(v->link.wbChrtId().equals(v.id())).toList();
        if(variants.size()!=1)throw new IllegalStateException("WB_OZON_WB_VARIANT_CHANGED");
        if(RegisteredGtinProof.contains(variants.getFirst().barcodes(),link.gtin()))return;
        if(!WbOzonSourceEvidence.matches(wb,key,link.gtin(),link.goodId()))throw new IllegalStateException("WB_OZON_WB_GTIN_UNVERIFIED");
    }
    /** Invoked by every Ozon GTIN POST path, including orphan child jobs and history. */
    public static void verifyQueuedCross(Shop ozon,GtinSyncModels.ProductKey target,String gtin)throws IOException {
        if(target.marketplace()!=Marketplace.OZON)return;
        verifyQueuedCrossBatch(ozon,Map.of(target,gtin));
    }
    @FunctionalInterface interface WbSnapshotSource {com.vncode.app.features.products.ProductSnapshot read(Shop wb,ProductKey key)throws IOException;}
    /** Read each WB article once per fresh check, while preserving each approved size identity. */
    public static void verifyQueuedCrossBatch(Shop ozon,Map<GtinSyncModels.ProductKey,String> requested)throws IOException {
        verifyQueuedCrossBatch(ozon,requested,(shop,key)->new WbProductSnapshotReader().read(shop,key));
    }
    static void verifyQueuedCrossBatch(Shop ozon,Map<GtinSyncModels.ProductKey,String> requested,WbSnapshotSource reader)throws IOException {
        if(requested.isEmpty())return;
        var found=new WbOzonLinkRepository().findCrossTargets(ozon,requested);
        var shops=new HashMap<Integer,Shop>();var snapshots=new HashMap<String,com.vncode.app.features.products.ProductSnapshot>();
        for(var link:found.values()){
            var wb=shops.computeIfAbsent(link.wbShopId(),id->new com.vncode.app.features.shop.ShopRepository().findById(id));
            if(wb==null)throw new IllegalStateException("WB_OZON_SOURCE_CHANGED");
            String group=link.wbShopId()+"/"+link.wbNmId();var snapshot=snapshots.get(group);
            if(snapshot==null){var key=new ProductKey(wb.getId(),Marketplace.WILDBERRIES,link.wbNmId(),link.wbChrtId());snapshot=reader.read(wb,key);snapshots.put(group,snapshot);}
            requireWbSnapshot(wb,link,snapshot);
        }
    }
}
