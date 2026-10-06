package com.tuandev.fbsbarcode.features.finance;

import javafx.concurrent.Task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated, low-priority executors so analytics cannot occupy order/KIZ workers. */
public final class FinanceExecutor {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(
            threadFactory("wcode-finance-scheduler-"));
    private static final ExecutorService SYNC_WORKER = Executors.newSingleThreadExecutor(
            threadFactory("wcode-finance-sync-"));
    private static final ExecutorService QUERY_WORKER = Executors.newSingleThreadExecutor(
            threadFactory("wcode-finance-query-"));

    private FinanceExecutor() {
    }

    public static void scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit) {
        try {
            SCHEDULER.scheduleWithFixedDelay(task, initialDelay, delay, unit);
        } catch (RejectedExecutionException ignored) {
            // Application is shutting down.
        }
    }

    public static void executeSync(Runnable task) {
        try {
            SYNC_WORKER.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Application is shutting down.
        }
    }

    public static void executeQuery(Task<?> task) {
        try {
            QUERY_WORKER.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Application is shutting down.
        }
    }

    public static void shutdown() {
        SCHEDULER.shutdownNow();
        SYNC_WORKER.shutdownNow();
        QUERY_WORKER.shutdownNow();
    }

    private static ThreadFactory threadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + COUNTER.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }
}
