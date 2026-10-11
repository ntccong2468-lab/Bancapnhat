package com.vncode.app.shared;
import java.util.concurrent.*;
/** Accepted seller jobs retain their captured owner and are never evicted on navigation. */
public final class SellerMutationExecutor {
    private static final ThreadPoolExecutor EXECUTOR=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"vncode-seller-write");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private SellerMutationExecutor(){}
    public static <T> CompletableFuture<T> submit(Callable<T> action){
        var result=new CompletableFuture<T>();
        try{EXECUTOR.execute(()->{try{result.complete(action.call());}catch(Throwable failure){result.completeExceptionally(failure);}});}catch(RejectedExecutionException full){result.completeExceptionally(full);}
        return result;
    }
    public static int pendingCount(){return EXECUTOR.getQueue().size();}
    public static int activeCount(){return EXECUTOR.getActiveCount();}
    public static int workerLimit(){return 1;}
}
