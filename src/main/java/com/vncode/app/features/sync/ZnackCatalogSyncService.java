package com.vncode.app.features.sync;

import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.NationalCatalogGtinSource;
import com.vncode.app.models.Shop;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import static com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;

/** One owner-scoped source for the Znack UI and automatic marketplace GTIN pipeline. */
public final class ZnackCatalogSyncService {
    @FunctionalInterface interface Source {List<RegisteredGtin> read(Shop shop,Consumer<NationalCatalogGtinSource.CardIssue> issues,Consumer<NationalCatalogGtinSource.Progress> progress)throws Exception;}
    public record Result(List<RegisteredGtin> cards,List<NationalCatalogGtinSource.CardIssue> issues,String runId){public Result{cards=List.copyOf(cards);issues=List.copyOf(issues);}}
    private record Key(String directory,int shop,String source){}
    private record Cached(Result result,Instant at){}
    private static final ConcurrentMap<Key,ReentrantLock> LOCKS=new ConcurrentHashMap<>();
    private static final Map<Key,Cached> CACHE=Collections.synchronizedMap(new LinkedHashMap<>());
    private final Source source;
    public ZnackCatalogSyncService(){this((shop,issues,progress)->NationalCatalogGtinSource.forShopPartial(shop,issues,progress).read(shop.getId()));}
    ZnackCatalogSyncService(Source source){this.source=Objects.requireNonNull(source);}
    public Result refresh(Shop shop,boolean force,Consumer<NationalCatalogGtinSource.Progress> progress)throws Exception {
        LicenseRuntime.require(Permission.SELLER_API);ListingIdentity.require(shop);
        var scope=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(scope);
        var key=new Key(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize().toString(),shop.getId(),scope.source());
        var lock=LOCKS.computeIfAbsent(key,k->new ReentrantLock());
        if(!lock.tryLock())throw new IllegalStateException("ZNACK_SYNC_ALREADY_RUNNING");
        var reports=new SyncRunRepository();String run="";
        try {
            var cached=CACHE.get(key);
            if(!force&&cached!=null&&cached.result().issues().isEmpty()&&cached.at().isAfter(Instant.now().minusSeconds(120))){progress.accept(new NationalCatalogGtinSource.Progress("SOURCE_COMPLETE",cached.result().cards().size(),cached.result().cards().size()));return cached.result();}
            run=reports.begin(shop,"znack_catalog_read","AUTHENTICATING");final String id=run;
            String[] phase={""};int[] last={-25};
            Consumer<NationalCatalogGtinSource.Progress> observer=p->{
                progress.accept(p);
                if(!phase[0].equals(p.phase())||p.completed()-last[0]>=25){reports.stage(shop,id,"RUNNING",p.phase(),"");phase[0]=p.phase();last[0]=p.completed();}
            };
            var issues=new ArrayList<NationalCatalogGtinSource.CardIssue>();
            List<RegisteredGtin> rows;
            try(var deferral=com.vncode.app.shared.RateLimitDeferral.enter()){rows=source.read(shop,issues::add,observer);}
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ZNACK_SYNC_CANCELLED");
            ListingIdentity.require(shop);if(!scope.source().equals(RegisteredGtinProof.current(shop).source()))throw new IllegalStateException("CATALOG_OWNER_CHANGED");
            if(rows.stream().anyMatch(row->!scope.source().equals(row.source())))throw new SecurityException("CATALOG_OWNER_CHANGED");
            var gtins=new GtinSyncRepository();
            if(issues.isEmpty())gtins.saveCatalog(shop.getId(),shop.getMarketplace(),rows);else gtins.mergeCatalog(shop.getId(),shop.getMarketplace(),rows);
            project(shop,rows);
            var reportItems=new ArrayList<SyncRunRepository.Item>();
            for(var row:rows)reportItems.add(new SyncRunRepository.Item(UUID.randomUUID().toString(),id,"catalog:"+row.gtin(),"catalog","",row.article(),row.color(),row.size(),row.gtin(),row.published()&&row.tradeUnit()?"SUCCEEDED":"UNCHANGED","SOURCE_COMPLETE",row.published()&&row.tradeUnit()?"":"CATALOG_NOT_ELIGIBLE","","{}"));
            for(var issue:issues)reportItems.add(new SyncRunRepository.Item(UUID.randomUUID().toString(),id,"catalog:"+issue.gtin(),"catalog","","","","",issue.gtin(),"NEEDS_ACTION","SOURCE_READ",issue.code(),"","{}"));
            reports.addBatch(shop,id,reportItems);
            reports.stage(shop,id,issues.isEmpty()?"SUCCEEDED":"PARTIAL","SOURCE_COMPLETE",issues.isEmpty()?"":"CATALOG_PARTIAL");
            var result=new Result(rows,issues,id);synchronized(CACHE){CACHE.put(key,new Cached(result,Instant.now()));while(CACHE.size()>30)CACHE.remove(CACHE.keySet().iterator().next());}
            ZnackGtinAutoSync.markAutoSynced(shop.getId());return result;
        }catch(Exception failed){ZnackGtinAutoSync.retryAfterFailure(shop.getId());if(!run.isBlank())reports.stage(shop,run,"FAILED","SOURCE_READ",SyncFailure.of(failed,0).code());throw failed;}
        finally{lock.unlock();}
    }
    public static void project(Shop shop,List<RegisteredGtin> rows){
        ListingIdentity.require(shop);var repository=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));
        var settings=repository.getSettings();var products=new ArrayList<ZnackModels.Product>();var documentsByGtin=new HashMap<String,List<ZnackModels.GoodsDocument>>();
        for(var row:rows){if(!row.published()||!row.tradeUnit()||GtinNormalizer.isTechnicalRange(row.gtin()))continue;
            var documents=row.details().documents().stream().filter(d->!d.number().isBlank()&&!d.from().isBlank()).map(d->new ZnackModels.GoodsDocument(switch(d.type()){case "DECLARATION"->"CONFORMITY_DECLARATION";case "CERTIFICATE_OF_REGISTRATION"->"STATE_REGISTRATION_CERTIFICATE";case "CERTIFICATE_OF_CONFORMITY"->"CONFORMITY_CERTIFICATE";default->d.type();},d.number(),d.from())).toList();
            documentsByGtin.put(row.gtin(),documents);
            products.add(new ZnackModels.Product(row.gtin(),row.details().single("title"),row.details().single("tnved"),null,null,null,null,null,null,"published","","",null,null,documents));
        }
        // Missing permit metadata is a purchase/introduction issue, not a reason to hide a published GTIN.
        repository.upsertProducts(products,settings);
        repository.restoreProducts(products.stream().map(ZnackModels.Product::gtin).toList());
        for(var row:rows)if(row.published()&&row.tradeUnit()&&!GtinNormalizer.isTechnicalRange(row.gtin())){
            if(!row.details().documents().isEmpty())repository.updateProductDocuments(row.gtin(),documentsByGtin.get(row.gtin()));
            repository.updateProductLabelMetadata(row.gtin(),row.details().single("gender"),row.size());
        }
    }
}
