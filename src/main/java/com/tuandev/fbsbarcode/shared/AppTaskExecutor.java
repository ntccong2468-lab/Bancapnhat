package com.tuandev.fbsbarcode.shared;

import javafx.concurrent.Task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class AppTaskExecutor {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger ACTIVE_TASK_COUNT = new AtomicInteger(0);
    private static final int THREAD_COUNT = Math.max(4, Math.min(Runtime.getRuntime().availableProcessors(), 8));
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREAD_COUNT, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "fbsbarcode-bg-" + THREAD_COUNTER.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    });

    private AppTaskExecutor() {
    }

    public static void execute(Task<?> task) {
        if (EXECUTOR.isShutdown()) {
            return;
        }
        ACTIVE_TASK_COUNT.incrementAndGet();
        try {
            EXECUTOR.execute(() -> {
                try {
                    task.run();
                } finally {
                    ACTIVE_TASK_COUNT.updateAndGet(current -> Math.max(0, current - 1));
                }
            });
        } catch (RejectedExecutionException ex) {
            ACTIVE_TASK_COUNT.updateAndGet(current -> Math.max(0, current - 1));
        }
    }

    public static boolean hasRunningTasks() {
        return ACTIVE_TASK_COUNT.get() > 0;
    }

    public static int getRunningTaskCount() {
        return ACTIVE_TASK_COUNT.get();
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }
}
