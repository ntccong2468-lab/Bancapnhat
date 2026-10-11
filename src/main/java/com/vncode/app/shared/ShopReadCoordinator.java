package com.vncode.app.shared;

import java.util.*;
import java.util.concurrent.*;

/** Bounded, coalesced reads only. Seller mutations must use their durable workflow executor. */
public final class ShopReadCoordinator implements AutoCloseable {
    public record Key(int shopId,String workload,String scope) {
        public Key {if(shopId<=0)throw new IllegalArgumentException("Read owner required");Objects.requireNonNull(workload);Objects.requireNonNull(scope);}
        @Override public String toString(){return "ReadKey[shop="+shopId+", workload="+workload+"]";}
    }
    private static final class Shared {static final ShopReadCoordinator INSTANCE=new ShopReadCoordinator(3,64,1);static final ShopReadCoordinator IMAGES=new ShopReadCoordinator(2,64,0);}
    public static ShopReadCoordinator shared(){return Shared.INSTANCE;}
    public static ShopReadCoordinator images(){return Shared.IMAGES;}
    public int workerLimit(){return workers.size();}
    private static final class Work<T> {
        final Key key;final Callable<T> callable;final CompletableFuture<T> result=new CompletableFuture<>();
        final boolean local;boolean interactive;Thread thread;
        Work(Key key,boolean interactive,boolean local,Callable<T> callable){this.key=key;this.interactive=interactive;this.local=local;this.callable=callable;}
    }
    private final Object monitor=new Object();
    private final Deque<Work<?>> pending=new ArrayDeque<>();
    private final Map<Key,Work<?>> inflight=new HashMap<>();
    private final List<Thread> workers=new ArrayList<>();
    private final int capacity,localWorkers;private boolean closed;private int active;
    private int lastLocalShop=-1,lastNetworkShop=-1;
    public ShopReadCoordinator(){this(2,64);}
    public ShopReadCoordinator(int threads,int capacity) {this(threads,capacity,0);}
    public ShopReadCoordinator(int threads,int capacity,int localWorkers) {
        if(threads<1||threads>8||capacity<1||localWorkers<0||localWorkers>=threads)throw new IllegalArgumentException("Invalid read limits");this.capacity=capacity;this.localWorkers=localWorkers;
        for(int i=0;i<threads;i++){boolean local=i<localWorkers;var worker=new Thread(()->worker(local),"vncode-read-"+(local?"local-":"network-")+(i+1));worker.setDaemon(true);workers.add(worker);worker.start();}
    }
    public <T> CompletableFuture<T> submitLocal(Key key,Callable<T> callable){return enqueue(key,true,true,callable);}
    public <T> CompletableFuture<T> submit(Key key,boolean interactive,Callable<T> callable){return enqueue(key,interactive,false,callable);}
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> enqueue(Key key,boolean interactive,boolean local,Callable<T> callable) {
        Objects.requireNonNull(key);Objects.requireNonNull(callable);Work<?> evicted=null;Work<T> work;
        synchronized(monitor) {
            if(closed)return CompletableFuture.failedFuture(new RejectedExecutionException("Read coordinator closed"));
            var existing=inflight.get(key);
            if(existing!=null){if(existing.local!=local)return CompletableFuture.failedFuture(new IllegalArgumentException("Read key belongs to another lane"));if(interactive&&pending.remove(existing)){existing.interactive=true;pending.addFirst(existing);}return (CompletableFuture<T>)existing.result;}
            if(pending.size()>=capacity) {
                if(!interactive)return CompletableFuture.failedFuture(new RejectedExecutionException("Read queue full"));
                for(var queued:pending)if(!queued.interactive){evicted=queued;break;}
                if(evicted==null)evicted=pending.peekLast();
                pending.remove(evicted);inflight.remove(evicted.key,evicted);
            }
            work=new Work<>(key,interactive,local,callable);inflight.put(key,work);
            if(interactive)pending.addFirst(work);else pending.addLast(work);monitor.notifyAll();
        }
        if(evicted!=null)evicted.result.cancel(false);
        return work.result;
    }
    private Work<?> next(boolean local) {
        Work<?> first=null,other=null;int last=local?lastLocalShop:lastNetworkShop;
        for(var work:pending) {
            if(localWorkers>0&&work.local!=local)continue;
            if(work.interactive){pending.remove(work);return work;}
            if(first==null)first=work;
            if(other==null&&work.key.shopId()!=last)other=work;
        }
        var selected=other==null?first:other;
        if(selected!=null){pending.remove(selected);if(local)lastLocalShop=selected.key.shopId();else lastNetworkShop=selected.key.shopId();}
        return selected;
    }
    private void worker(boolean local) {
        while(true) {
            Work<?> work=null;
            synchronized(monitor) {
                while(!closed&&(work=next(local))==null)try{monitor.wait();}catch(InterruptedException interrupted){if(closed)return;}
                if(closed)return;work.thread=Thread.currentThread();active++;
            }
            execute(work);
        }
    }
    private <T> void execute(Work<T> work) {
        T value=null;Throwable error=null;
        try {if(!work.result.isCancelled())value=work.callable.call();}catch(Throwable failure){error=failure;}
        finally {synchronized(monitor){active--;work.thread=null;inflight.remove(work.key,work);monitor.notifyAll();}Thread.interrupted();}
        if(error==null)work.result.complete(value);else work.result.completeExceptionally(error);
    }
    public void cancelReads(int shopId) {
        List<Work<?>> cancel;
        synchronized(monitor) {
            cancel=inflight.values().stream().filter(w->w.key.shopId()==shopId).toList();
            for(var work:cancel){pending.remove(work);inflight.remove(work.key,work);}
        }
        cancel.forEach(w->w.result.cancel(false));
        synchronized(monitor){for(var work:cancel)if(work.thread!=null)work.thread.interrupt();}
    }
    public int activeCount(){synchronized(monitor){return active;}}
    public int pendingCount(){synchronized(monitor){return pending.size();}}
    public void close() {
        List<Work<?>> cancel;
        synchronized(monitor){if(closed)return;closed=true;cancel=List.copyOf(inflight.values());pending.clear();inflight.clear();monitor.notifyAll();}
        cancel.forEach(w->w.result.cancel(false));workers.forEach(Thread::interrupt);
    }
}
