package com.vncode.app.shared;

import javafx.concurrent.Task;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class AppTaskExecutor {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger ACTIVE_TASK_COUNT = new AtomicInteger(0);
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "vncode-task-" + THREAD_COUNTER.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    },new ThreadPoolExecutor.AbortPolicy());

    private static final ThreadPoolExecutor CATALOG_EXECUTOR=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(20),r->{Thread t=new Thread(r,"vncode-catalog-read-"+THREAD_COUNTER.getAndIncrement());t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledExecutorService CATALOG_TIMEOUT=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"vncode-catalog-deadline");t.setDaemon(true);return t;});
    /** Catalog reads cannot starve UI tasks. Their wait is bounded, including queue time. */
    public static boolean executeCatalog(Task<?> task,Consumer<Throwable> rejected){return executeCatalog(task,java.time.Duration.ofMinutes(3),rejected);}
    static boolean executeCatalog(Task<?> task,java.time.Duration deadline,Consumer<Throwable> rejected){
        if(deadline==null||deadline.isNegative()||deadline.isZero()||deadline.toMillis()>600000)throw new IllegalArgumentException("Catalog deadline is invalid");
        Objects.requireNonNull(task);ACTIVE_TASK_COUNT.incrementAndGet();
        var timeout=CATALOG_TIMEOUT.schedule(()->task.cancel(true),Math.max(1,deadline.toMillis()),TimeUnit.MILLISECONDS);
        try{CATALOG_EXECUTOR.execute(()->{try{task.run();}finally{timeout.cancel(false);ACTIVE_TASK_COUNT.decrementAndGet();}});return true;}
        catch(RejectedExecutionException full){timeout.cancel(false);ACTIVE_TASK_COUNT.decrementAndGet();task.cancel(false);rejected.accept(full);return false;}
    }
    private AppTaskExecutor() {
    }

    public static boolean execute(Task<?> task) {
        Objects.requireNonNull(task);
        ACTIVE_TASK_COUNT.incrementAndGet();
        try {
            EXECUTOR.execute(() -> {
                try {
                    task.run();
                } finally {
                    ACTIVE_TASK_COUNT.updateAndGet(current -> Math.max(0, current - 1));
                }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            ACTIVE_TASK_COUNT.updateAndGet(current -> Math.max(0, current - 1));
            task.cancel(false);
            return false;
        }
    }

    /** Caller supplies normal failure cleanup; rejected tasks have not run their action. */
    public static boolean execute(Task<?> task,Consumer<Throwable> rejected) {
        if(execute(task))return true;
        rejected.accept(new RejectedExecutionException(I18nService.getInstance().tr("runtime.queue_full")));return false;
    }
    public static int pendingCount(){return EXECUTOR.getQueue().size();}

    public static boolean hasRunningTasks() {
        return ACTIVE_TASK_COUNT.get() > 0 || SellerMutationExecutor.activeCount() > 0 || SellerMutationExecutor.pendingCount() > 0 || com.vncode.app.features.sync.SyncRuntime.hasActiveWrites();
    }

    public static int getRunningTaskCount() {
        return ACTIVE_TASK_COUNT.get();
    }

    public static void shutdown() {
        EXECUTOR.shutdown();CATALOG_EXECUTOR.shutdown();CATALOG_TIMEOUT.shutdownNow();
    }
}
