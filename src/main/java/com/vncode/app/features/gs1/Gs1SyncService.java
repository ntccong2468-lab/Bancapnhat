package com.vncode.app.features.gs1;

import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One reported/coalesced GS1 read for UI and background workers; never sends business requests. */
public final class Gs1SyncService {
    public record Update(String step,String state,String error,String run,String at) {}
    private static final ConcurrentMap<String,CompletableFuture<Gs1AutomaticSyncService.Result>> FLIGHTS=new ConcurrentHashMap<>();
    private static final ConcurrentMap<String,CopyOnWriteArrayList<Consumer<Update>>> LISTENERS=new ConcurrentHashMap<>();
    private static final ConcurrentMap<String,Update> ACTIVE=new ConcurrentHashMap<>();
    private final Gs1AutomaticSyncService source;
    public Gs1SyncService(){this(new Gs1AutomaticSyncService());}
    Gs1SyncService(Gs1AutomaticSyncService source){this.source=source;}
    private static String ownerKey(Shop shop){return System.getProperty("vncode.appdata.dir","")+"/"+ListingIdentity.fingerprint(shop);}
    public static AutoCloseable observe(Shop shop,Consumer<Update> listener){
        String key=ownerKey(shop);var observers=LISTENERS.computeIfAbsent(key,k->new CopyOnWriteArrayList<>());observers.add(listener);
        var active=ACTIVE.get(key);if(active!=null)listener.accept(active);
        return ()->{observers.remove(listener);if(observers.isEmpty())LISTENERS.remove(key,observers);};
    }
    private static void publish(String key,Update value){
        if(value.state().equals("RUNNING"))ACTIVE.put(key,value);else ACTIVE.remove(key);
        for(var observer:LISTENERS.getOrDefault(key,new CopyOnWriteArrayList<>()))try{observer.accept(value);}catch(RuntimeException disposedView){/* Observers do not own the read. */}
    }
    private static String phase(SyncRunRepository.Item item,String fallback){return item==null||item.step().equals("MATCHING")?fallback:item.step();}
    public Gs1AutomaticSyncService.Result sync(Shop selected)throws Exception {
        var shop=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        ListingIdentity.require(shop);String owner=ownerKey(shop),scope;
        var reports=new SyncRunRepository();
        try{var verified=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(verified);scope=verified.source();}
        catch(RuntimeException error){String run=reports.begin(shop,"gs1","CHECKING_SETTINGS");String code=SyncFailure.of(error,0).code();reports.stage(shop,run,"FAILED","CHECKING_SETTINGS",code);publish(owner,new Update("CHECKING_SETTINGS","FAILED",code,run,Instant.now().toString()));throw error;}
        String key=owner+"/"+scope;
        var future=new CompletableFuture<Gs1AutomaticSyncService.Result>();var prior=FLIGHTS.putIfAbsent(key,future);
        if(prior!=null){
            try{var result=prior.get(180,TimeUnit.SECONDS);if(!scope.equals(RegisteredGtinProof.current(shop).source()))throw new SecurityException("GS1_ACCOUNT_CHANGED");return result;}
            catch(ExecutionException failed){if(failed.getCause() instanceof Exception cause)throw cause;throw new IllegalStateException("GS1_SYNC_FAILED",failed.getCause());}
        }
        String run="";
        try{
            run=reports.begin(shop,"gs1","CHECKING_SETTINGS");String currentRun=run;
            var items=new HashMap<String,SyncRunRepository.Item>();
            for(String part:List.of("CATALOG","BALANCE"))items.put(part,reports.add(shop,run,part,"source","","","","","","QUEUED","","{}"));
            publish(owner,new Update("CHECKING_SETTINGS","RUNNING","",run,Instant.now().toString()));
            var result=source.refresh(shop,progress->{
                var item=items.get(progress.source());
                if(item!=null)reports.update(shop,item,progress.state(),progress.step(),progress.error(),null);
                reports.stage(shop,currentRun,"RUNNING",progress.step(),progress.error());
                publish(owner,new Update(progress.step(),"RUNNING",progress.error(),currentRun,Instant.now().toString()));
            });
            var last=new HashMap<String,SyncRunRepository.Item>();for(var item:reports.items(run,0,10))last.put(item.productId(),item);
            reports.update(shop,items.get("CATALOG"),result.catalogError().isBlank()?"SUCCEEDED":"FAILED",result.catalogError().isBlank()?"CATALOG_COMPLETE":phase(last.get("CATALOG"),"READING_CATALOG"),result.catalogError(),null);
            reports.update(shop,items.get("BALANCE"),result.balanceError().isBlank()?"SUCCEEDED":"FAILED",result.balanceError().isBlank()?"BALANCE_COMPLETE":phase(last.get("BALANCE"),"READING_BALANCE"),result.balanceError(),null);
            String state=result.complete()?"SUCCEEDED":!result.catalogError().isBlank()&&!result.balanceError().isBlank()?"FAILED":"PARTIAL";
            String code=!result.catalogError().isBlank()?result.catalogError():result.balanceError();reports.stage(shop,run,state,"REPORT",code);
            publish(owner,new Update("REPORT",state,code,run,Instant.now().toString()));future.complete(result);return result;
        }catch(Exception error){
            String code=SyncFailure.of(error,0).code();try{source.failed(shop,scope,code);}catch(Exception changed){error.addSuppressed(changed);}
            if(!run.isBlank())try{
                String failedStep=reports.step(run);
                for(var item:reports.items(run,0,300))if(!Set.of("SUCCEEDED","FAILED").contains(item.state()))reports.update(shop,item,item.state().equals("QUEUED")?"CANCELLED":"FAILED",item.step(),item.state().equals("QUEUED")?"GS1_SOURCE_NOT_READ":code,null);
                reports.stage(shop,run,"FAILED",failedStep,code);}catch(RuntimeException changed){error.addSuppressed(changed);}
            publish(owner,new Update("REPORT","FAILED",code,run,Instant.now().toString()));future.completeExceptionally(error);throw error;
        }finally{FLIGHTS.remove(key,future);}
    }
}
