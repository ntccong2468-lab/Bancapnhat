package com.vncode.app.shared;

import java.util.*;
import java.util.concurrent.*;

/** Bounded, coalesced reads only. Seller mutations must use their durable workflow executor. */
public final class ShopReadCoordinator implements AutoCloseable {
    public record Key(int shopId,String workload,String scope) {
        public Key {if(shopId<=0)throw new IllegalArgumentException("Read owner required");Objects.requireNonNull(workload);Objects.requireNonNull(scope);}
        @Override public String toString(){return "ReadKey[shop="+shopId+", workload="+workload+"]";}
    }
    private static final class Shared {static final ShopReadCoordinator INSTANCE=new ShopReadCoordinator(2,64);static final ShopReadCoordinator IMAGES=new ShopReadCoordinator(2,64);}
    public static ShopReadCoordinator shared(){return Shared.INSTANCE;}
    public static ShopReadCoordinator images(){return Shared.IMAGES;}
    public int workerLimit(){return workers.size();}
    private static final class Work<T> {
        final Key key;final Callable<T> callable;final CompletableFuture<T> result=new CompletableFuture<>();
        boolean interactive;Thread thread;
        Work(Key key,boolean interactive,Callable<T> callable){this.key=key;this.interactive=interactive;this.callable=callable;}
    }
    private final Object monitor=new Object();
    private final Deque<Work<?>> pending=new ArrayDeque<>();
    private final Map<Key,Work<?>> inflight=new HashMap<>();
    private final List<Thread> workers=new ArrayList<>();
    private final int capacity;private boolean closed;private int active;
    public ShopReadCoordinator(){this(2,64);}
    public ShopReadCoordinator(int threads,int capacity) {
        if(threads<1||threads>8||capacity<1)throw new IllegalArgumentException("Invalid read limits");this.capacity=capacity;
        for(int i=0;i<threads;i++){var worker=new Thread(this::worker,"vncode-read-"+(i+1));worker.setDaemon(true);workers.add(worker);worker.start();}
    }
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> submit(Key key,boolean interactive,Callable<T> callable) {
        Objects.requireNonNull(key);Objects.requireNonNull(callable);Work<?> evicted=null;Work<T> work;
        synchronized(monitor) {
            if(closed)return CompletableFuture.failedFuture(new RejectedExecutionException("Read coordinator closed"));
            var existing=inflight.get(key);
            if(existing!=null){if(interactive&&pending.remove(existing)){existing.interactive=true;pending.addFirst(existing);}return (CompletableFuture<T>)existing.result;}
            if(pending.size()>=capacity) {
                if(!interactive)return CompletableFuture.failedFuture(new RejectedExecutionException("Read queue full"));
                for(var queued:pending)if(!queued.interactive){evicted=queued;break;}
                if(evicted==null)evicted=pending.peekLast();
                pending.remove(evicted);inflight.remove(evicted.key,evicted);
            }
            work=new Work<>(key,interactive,callable);inflight.put(key,work);
            if(interactive)pending.addFirst(work);else pending.addLast(work);monitor.notifyAll();
        }
        if(evicted!=null)evicted.result.cancel(false);
        return work.result;
    }
    private void worker() {
        while(true) {
            Work<?> work;
            synchronized(monitor) {
                while(!closed&&pending.isEmpty())try{monitor.wait();}catch(InterruptedException interrupted){if(closed)return;}
                if(closed)return;work=pending.removeFirst();work.thread=Thread.currentThread();active++;
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
