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
import java.util.function.Consumer;
import static com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;

/** One owner-scoped source for the Znack UI and automatic marketplace GTIN pipeline. */
public final class ZnackCatalogSyncService {
    @FunctionalInterface interface Source {List<RegisteredGtin> read(Shop shop,Consumer<NationalCatalogGtinSource.CardIssue> issues,Consumer<NationalCatalogGtinSource.Progress> progress)throws Exception;}
    public record Result(List<RegisteredGtin> cards,List<NationalCatalogGtinSource.CardIssue> issues,String runId){public Result{cards=List.copyOf(cards);issues=List.copyOf(issues);}}
    private record Key(String directory,int shop,String source){}
    private record Cached(Result result,Instant at){}
    private static final class Flight {
        final CompletableFuture<Result> result=new CompletableFuture<>();
        final java.util.concurrent.CopyOnWriteArrayList<Consumer<NationalCatalogGtinSource.Progress>> listeners=new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile NationalCatalogGtinSource.Progress last;
        void progress(NationalCatalogGtinSource.Progress value){last=value;for(var listener:listeners)try{listener.accept(value);}catch(RuntimeException disposedView){/* A detached view cannot abort another subscriber's read. */}}
    }
    private static final ConcurrentMap<Key,Flight> IN_FLIGHT=new ConcurrentHashMap<>();
    private static final Map<Key,Cached> CACHE=Collections.synchronizedMap(new LinkedHashMap<>());
    private final Source source;
    public ZnackCatalogSyncService(){this((shop,issues,progress)->NationalCatalogGtinSource.forShopPartial(shop,issues,progress).read(shop.getId()));}
    ZnackCatalogSyncService(Source source){this.source=Objects.requireNonNull(source);}
    public Result refresh(Shop shop,boolean force,Consumer<NationalCatalogGtinSource.Progress> progress)throws Exception {
        LicenseRuntime.require(Permission.SELLER_API);ListingIdentity.require(shop);
        var scope=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(scope);
        var key=new Key(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize().toString(),shop.getId(),scope.source());
        var flight=new Flight();flight.listeners.add(progress);
        var existing=IN_FLIGHT.putIfAbsent(key,flight);
        if(existing!=null)return join(shop,scope,existing,progress);
        var reports=new SyncRunRepository();String run="";
        try {
            var cached=CACHE.get(key);
            if(!force&&cached!=null&&cached.result().issues().isEmpty()&&cached.at().isAfter(Instant.now().minusSeconds(120))){flight.progress(new NationalCatalogGtinSource.Progress("SOURCE_COMPLETE",cached.result().cards().size(),cached.result().cards().size()));flight.result.complete(cached.result());return cached.result();}
            run=reports.begin(shop,"znack_catalog_read","AUTHENTICATING");final String id=run;
            String[] phase={""};int[] last={-25};
            Consumer<NationalCatalogGtinSource.Progress> observer=p->{
                flight.progress(p);
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
            ZnackGtinAutoSync.markAutoSynced(shop.getId());flight.result.complete(result);return result;
        }catch(Exception failed){
            ZnackGtinAutoSync.retryAfterFailure(shop.getId());
            if(!run.isBlank())try{reports.stage(shop,run,"FAILED",reports.step(run),SyncFailure.of(failed,0).code());}
            catch(RuntimeException reportingFailure){failed.addSuppressed(reportingFailure);}
            flight.result.completeExceptionally(failed);throw failed;
        }
        catch(Error failed){flight.result.completeExceptionally(failed);throw failed;}
        finally{IN_FLIGHT.remove(key,flight);flight.listeners.clear();}
    }
    /** A UI subscriber waits off FX and receives the same persisted result, never a second HTTP refresh. */
    private static Result join(Shop shop,RegisteredGtinProof.Scope scope,Flight flight,Consumer<NationalCatalogGtinSource.Progress> progress)throws Exception{
        flight.listeners.add(progress);
        try{
            progress.accept(new NationalCatalogGtinSource.Progress("WAITING_SOURCE",0,0));
            var last=flight.last;if(last!=null)progress.accept(last);
            var result=flight.result.get(180,TimeUnit.SECONDS);
            ListingIdentity.require(shop);if(!scope.source().equals(RegisteredGtinProof.current(shop).source()))throw new IllegalStateException("CATALOG_OWNER_CHANGED");
            return result;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("ZNACK_SYNC_CANCELLED");}
        catch(TimeoutException expired){throw new java.net.SocketTimeoutException("ZNACK_SYNC_TIMEOUT");}
        catch(ExecutionException failed){if(failed.getCause() instanceof Exception exception)throw exception;if(failed.getCause() instanceof Error error)throw error;throw new IllegalStateException("ZNACK_SYNC_FAILED");}
        finally{flight.listeners.remove(progress);}
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
        var metadata=new ArrayList<ZnackRepository.CatalogMetadata>(100);
        for(var row:rows)if(row.published()&&row.tradeUnit()&&!GtinNormalizer.isTechnicalRange(row.gtin())){
            metadata.add(new ZnackRepository.CatalogMetadata(row.gtin(),!row.details().documents().isEmpty(),documentsByGtin.get(row.gtin()),row.details().single("gender"),row.size()));
            if(metadata.size()==100){ListingIdentity.require(shop);repository.updateCatalogMetadata(metadata,settings);metadata.clear();}
        }
        if(!metadata.isEmpty()){ListingIdentity.require(shop);repository.updateCatalogMetadata(metadata,settings);}
    }
}
