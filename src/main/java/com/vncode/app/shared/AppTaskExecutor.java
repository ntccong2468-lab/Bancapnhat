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
        EXECUTOR.shutdown();
    }
}
