package com.vncode.app.shared;

import com.vncode.app.models.Shop;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import java.util.*;
import java.util.concurrent.*;

/** Accepted writes are bounded, never evicted, and serialized per shop across navigation. */
public final class SellerMutationExecutor {
    private record Work<T>(int owner,Callable<T> action,CompletableFuture<T> result,UpdateHandoff.Ticket continuation) {}
    private static final Object MONITOR=new Object();
    private static final Deque<Work<?>> PENDING=new ArrayDeque<>();
    private static final Set<Integer> ACTIVE=new HashSet<>();
    private static final int WORKERS=2,CAPACITY=32;
    static{for(int n=0;n<WORKERS;n++){var worker=new Thread(SellerMutationExecutor::worker,"vncode-seller-write-"+(n+1));worker.setDaemon(true);worker.start();}}
    private SellerMutationExecutor(){}
    /** Legacy unscoped actions retain a single serial lane. */
    public static <T> CompletableFuture<T> submit(Callable<T> action){return enqueue(0,action);}
    public static <T> CompletableFuture<T> submit(Shop selected,Callable<T> action){
        Objects.requireNonNull(selected);var shop=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        return enqueue(shop.getId(),()->ShopOperationCoordinator.withActiveShop(shop.getId(),()->{ListingIdentity.require(shop);return action.call();}));
    }
    private static <T> CompletableFuture<T> enqueue(int owner,Callable<T> action){
        Objects.requireNonNull(action);var result=new CompletableFuture<T>();
        synchronized(MONITOR){if(PENDING.size()>=CAPACITY){result.completeExceptionally(new RejectedExecutionException("Seller queue full"));return result;}PENDING.addLast(new Work<>(owner,action,result,UpdateHandoff.continuation()));MONITOR.notifyAll();}return result;
    }
    private static void worker(){
        while(true){Work<?> work=null;
            synchronized(MONITOR){while(work==null){for(var iterator=PENDING.iterator();iterator.hasNext();){var candidate=iterator.next();if(candidate.owner()==0?ACTIVE.isEmpty():!ACTIVE.contains(0)&&!ACTIVE.contains(candidate.owner())){work=candidate;iterator.remove();ACTIVE.add(work.owner());break;}}if(work==null)try{MONITOR.wait();}catch(InterruptedException interrupted){return;}}}
            execute(work);
        }
    }
    private static <T> void execute(Work<T> work){T value=null;Throwable error=null;
        try(var handoff=UpdateHandoff.work(work.continuation())){value=work.action().call();}catch(Throwable failure){error=failure;}
        finally{synchronized(MONITOR){ACTIVE.remove(work.owner());MONITOR.notifyAll();}}
        if(error==null)work.result().complete(value);else work.result().completeExceptionally(error);
    }
    public static int pendingCount(){synchronized(MONITOR){return PENDING.size();}}
    public static int activeCount(){synchronized(MONITOR){return ACTIVE.size();}}
    public static int workerLimit(){return WORKERS;}
}
