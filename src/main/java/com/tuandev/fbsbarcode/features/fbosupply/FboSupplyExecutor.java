package com.tuandev.fbsbarcode.features.fbosupply;

import javafx.concurrent.Task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public final class FboSupplyExecutor {
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wcode-fbo-supply-" + THREAD_NUMBER.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    private FboSupplyExecutor() {
    }

    public static void execute(Task<?> task) {
        if (task == null || EXECUTOR.isShutdown()) return;
        try {
            EXECUTOR.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }
}
